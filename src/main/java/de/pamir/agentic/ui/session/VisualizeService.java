package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.ChangesService;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visualize (phase 22): one system turn over the branch diff plus the tail of the transcript digest
 * yields a Mermaid diagram of the code logic. The result is journaled as {@code diagram_generated}
 * (latest wins) so reopening costs no LLM call. The service never parses Mermaid — the frontend's
 * renderer is the validator and asks for a {@link #repair} on a parse failure. See
 * docs/plan/phase-22-visualize.md.
 */
@Service
public class VisualizeService {

	public static final String EVENT_TYPE = "diagram_generated";
	/** The diagram types {@code beautiful-mermaid} renders and that suit code logic. */
	public static final List<String> TYPES = List.of("flowchart", "sequence", "class", "er", "state");
	public static final String AUTO = "auto";

	private static final Duration TIMEOUT = Duration.ofSeconds(90);
	/**
	 * Refuse a reply whose diagram would blow the journal payload cap (default 64 KB —
	 * {@code ARTUS_JOURNAL_PAYLOAD_CAP}): a larger payload would be journaled as EventJournal's
	 * truncation stub, so every later read/replay of the event would see an empty diagram. A real
	 * diagram under the prompt's 25-node rule is a few KB; anything near this size is model garbage.
	 */
	static final int MAX_MERMAID_BYTES = 48 * 1024;
	static final int DIFF_CHARS = 60_000;
	static final int DIGEST_TAIL_CHARS = 20_000;
	private static final Pattern FENCE = Pattern.compile("```(?:mermaid)?[ \\t]*\\R(.*?)\\R?```", Pattern.DOTALL);
	private static final Pattern TYPE_LINE = Pattern.compile("(?im)^\\s*TYPE:\\s*(\\S+)\\s*$");
	private static final Pattern WHY_LINE = Pattern.compile("(?im)^\\s*WHY:\\s*(.+?)\\s*$");

	/** A diagram as stored in the {@code diagram_generated} payload. */
	public record Diagram(String diagramType, String mermaid, String rationale, String fingerprint,
						  boolean repaired, long seq) {
	}

	/** {@code latest} is null before the first generation; {@code stale} = the diff changed since. */
	public record Status(Diagram latest, String currentFingerprint, boolean stale) {
	}

	record Parsed(String type, String rationale, String mermaid) {
	}

	private final SystemTurnClient systemTurnClient;
	private final SessionRepository sessions;
	private final EventJournal journal;
	private final JournalPublisher publisher;
	private final ChangesService changes;
	private final SettingsService settings;
	private final ObjectMapper mapper;

	public VisualizeService(SystemTurnClient systemTurnClient, SessionRepository sessions, EventJournal journal,
							JournalPublisher publisher, ChangesService changes, SettingsService settings,
							ObjectMapper mapper) {
		this.systemTurnClient = systemTurnClient;
		this.sessions = sessions;
		this.journal = journal;
		this.publisher = publisher;
		this.changes = changes;
		this.settings = settings;
		this.mapper = mapper;
	}

	/** Staleness-poll cache: full-diff fingerprint, valid while the cheap worktree signature holds. */
	private record CachedFingerprint(String signature, String fingerprint) {
	}

	private final Map<UUID, CachedFingerprint> fingerprints = new ConcurrentHashMap<>();

	public Status status(UUID sessionId) {
		SessionEntity session = eligible(sessions.get(sessionId), false);
		String current = currentFingerprint(session);
		Diagram latest = latest(sessionId).orElse(null);
		return new Status(latest, current, latest != null && !latest.fingerprint().equals(current));
	}

	public Diagram generate(UUID sessionId, String requestedType) {
		String type = requestedType == null || requestedType.isBlank() ? AUTO : requestedType.strip().toLowerCase(Locale.ROOT);
		if (!AUTO.equals(type) && !TYPES.contains(type)) {
			throw new IllegalArgumentException("type must be one of auto, " + String.join(", ", TYPES));
		}
		SessionEntity session = eligible(sessions.get(sessionId), true);
		ChangesService.DiffSnapshot diff = diff(session);
		if (diff.empty() && journal.lastSeq(sessionId) == 0) {
			throw new IllegalStateException("nothing to visualize — no changes on this branch and the journal is empty or was pruned");
		}
		String digest = TranscriptDigest.render(journal.readAfter(sessionId, 0));
		if (digest.length() > DIGEST_TAIL_CHARS) {
			digest = "…" + digest.substring(digest.length() - DIGEST_TAIL_CHARS);
		}
		String reply = turn(prompt(session, type, diff, digest));
		Parsed parsed = parse(reply, type);
		return store(sessionId, parsed, diff.fingerprint(), false);
	}

	/** One more turn that fixes {@code mermaid}, which the renderer rejected with {@code error}. */
	public Diagram repair(UUID sessionId, String mermaid, String error) {
		if (mermaid == null || mermaid.isBlank()) {
			throw new IllegalArgumentException("mermaid is required");
		}
		SessionEntity session = eligible(sessions.get(sessionId), true);
		Diagram latest = latest(sessionId).orElse(null);
		String type = latest != null ? latest.diagramType() : AUTO;
		String prompt = ("The Mermaid diagram below failed to render. Fix its syntax and return the corrected diagram "
				+ "without changing its meaning. " + RULES + "\n\nRenderer error:\n%s\n\nBroken source:\n```mermaid\n%s\n```\n\n"
				+ ENVELOPE).formatted(error == null ? "(none given)" : truncate(error, 1000), truncate(mermaid, 20_000));
		Parsed parsed = parse(turn(prompt), type);
		String fingerprint = latest != null ? latest.fingerprint() : currentFingerprint(session);
		return store(sessionId, parsed, fingerprint, true);
	}

	// ------------------------------------------------------------------ internals

	private SessionEntity eligible(SessionEntity session, boolean forTurn) {
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions cannot be visualized");
		}
		if (session.state() == SessionState.RUNNING || session.state() == SessionState.WAITING_INPUT) {
			if (forTurn) {
				throw new IllegalStateException("a turn is running — wait for it to finish before visualizing");
			}
		}
		return session;
	}

	/** Computes the full diff and refreshes the staleness cache with it. */
	private ChangesService.DiffSnapshot diff(SessionEntity session) {
		Path worktree = worktree(session);
		String signature = changes.changeSignature(worktree);
		ChangesService.DiffSnapshot diff = changes.unifiedDiff(worktree, session.baseBranch(), DIFF_CHARS);
		fingerprints.put(session.id(), new CachedFingerprint(signature, diff.fingerprint()));
		return diff;
	}

	/**
	 * The current diff's fingerprint for staleness checks. {@link #status} is polled (window focus +
	 * a 15 s interval per open dialog/pop-out), so the full {@code git diff} + SHA-256 only reruns
	 * when {@link ChangesService#changeSignature} says the worktree actually changed.
	 */
	private String currentFingerprint(SessionEntity session) {
		Path worktree = worktree(session);
		String signature = changes.changeSignature(worktree);
		CachedFingerprint cached = fingerprints.get(session.id());
		if (cached != null && cached.signature().equals(signature)) {
			return cached.fingerprint();
		}
		String fingerprint = changes.unifiedDiff(worktree, session.baseBranch(), DIFF_CHARS).fingerprint();
		fingerprints.put(session.id(), new CachedFingerprint(signature, fingerprint));
		return fingerprint;
	}

	private static Path worktree(SessionEntity session) {
		return session.worktreePath() == null ? null : Path.of(session.worktreePath());
	}

	private Optional<Diagram> latest(UUID sessionId) {
		return journal.lastEventOfType(sessionId, EVENT_TYPE).map(e -> fromPayload(e.seq(), e.payload()));
	}

	private static Diagram fromPayload(long seq, JsonNode p) {
		return new Diagram(p.path("diagramType").asText(""), p.path("mermaid").asText(""),
				p.hasNonNull("rationale") ? p.get("rationale").asText() : null,
				p.path("fingerprint").asText(""), p.path("repaired").asBoolean(false), seq);
	}

	private String turn(String prompt) {
		String model = ModelCatalog.byTier(settings.systemProvider(), settings.current().visualizeModel()).orElse(null);
		String reply = systemTurnClient.text(prompt, model, SystemTurnLane.VISUALIZE, TIMEOUT);
		if (reply.isBlank()) {
			throw new IllegalStateException("the model returned an empty reply");
		}
		return reply;
	}

	private Diagram store(UUID sessionId, Parsed parsed, String fingerprint, boolean repaired) {
		ObjectNode payload = mapper.createObjectNode()
				.put("diagramType", parsed.type()).put("mermaid", parsed.mermaid())
				.put("fingerprint", fingerprint).put("repaired", repaired);
		if (parsed.rationale() != null) {
			payload.put("rationale", parsed.rationale());
		}
		var event = publisher.record(sessionId, EVENT_TYPE, payload);
		return fromPayload(event.seq(), payload);
	}

	private static final String RULES = "Mermaid rules: use ONLY these diagram types: flowchart, sequence "
			+ "(sequenceDiagram), class (classDiagram), er (erDiagram), state (stateDiagram-v2). Quote every node "
			+ "label (A[\"label\"]). Use ASCII node ids and never a reserved word (end, graph, subgraph, class) as an "
			+ "id. At most 25 nodes — collapse detail into subgraphs or summary nodes. Label edges sparingly. No "
			+ "styling, no click handlers, no HTML in labels.";

	private static final String ENVELOPE = "Reply in exactly this form and nothing else: first line \"TYPE: <type>\" "
			+ "(one of flowchart, sequence, class, er, state), second line \"WHY: <one sentence on why this type "
			+ "fits>\", then one ```mermaid fenced block holding the diagram.";

	private static String prompt(SessionEntity session, String type, ChangesService.DiffSnapshot diff, String digest) {
		String typeRule = AUTO.equals(type)
				? "Choose the diagram type that best shows the logic that changed (flowchart for control/data flow "
				+ "and component relations, sequence for interactions over time, state for lifecycles, class for "
				+ "structure, er for data models)."
				: "Use the diagram type \"" + type + "\".";
		return ("You are drawing a diagram of the code LOGIC a coding session touched — how the new or changed code "
				+ "flows, which components talk to each other, what states something moves through. Base it on the "
				+ "diff (what the branch actually carries); use the conversation tail only for intent. Do not "
				+ "describe the diff file by file. " + typeRule + " " + RULES + "\n\nSession \"%s\" on branch \"%s\".\n\n"
				+ "%s\n\nConversation tail (transcript digest; [#N] are anchors, ignore them):\n%s\n\n" + ENVELOPE)
				.formatted(session.name(), session.branch(),
						diff.empty() ? "There are no code changes on this branch (a conversation-only session) — draw "
								+ "what was discussed." : "Unified diff:\n```diff\n" + diff.text() + "\n```",
						digest);
	}

	/**
	 * Reads the {@code TYPE:}/{@code WHY:}/fence envelope. A reply without a fence is taken whole (minus
	 * the TYPE/WHY lines) as the source; an unknown or missing type is inferred from the diagram's own
	 * keyword, then from the requested type.
	 */
	static Parsed parse(String reply, String requestedType) {
		Matcher fence = FENCE.matcher(reply);
		String body;
		if (fence.find()) {
			body = fence.group(1);
		} else {
			body = TYPE_LINE.matcher(WHY_LINE.matcher(reply).replaceAll("")).replaceAll("");
		}
		body = body.strip();
		if (body.isEmpty()) {
			throw new IllegalStateException("the model returned no diagram");
		}
		int bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
		if (bytes > MAX_MERMAID_BYTES) {
			throw new IllegalStateException("the model returned an oversized diagram (" + bytes
					+ " bytes) — try regenerating");
		}
		Matcher t = TYPE_LINE.matcher(reply);
		String type = t.find() ? t.group(1).toLowerCase(Locale.ROOT) : "";
		if (!TYPES.contains(type)) {
			type = infer(body);
		}
		if (type == null) {
			type = TYPES.contains(requestedType) ? requestedType : null;
		}
		if (type == null) {
			throw new IllegalStateException("the model returned an unsupported diagram type");
		}
		Matcher w = WHY_LINE.matcher(reply);
		return new Parsed(type, w.find() ? w.group(1) : null, body);
	}

	private static String infer(String mermaid) {
		String first = mermaid.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("%%"))
				.findFirst().orElse("").toLowerCase(Locale.ROOT);
		if (first.startsWith("flowchart") || first.startsWith("graph")) return "flowchart";
		if (first.startsWith("sequencediagram")) return "sequence";
		if (first.startsWith("classdiagram")) return "class";
		if (first.startsWith("erdiagram")) return "er";
		if (first.startsWith("statediagram")) return "state";
		return null;
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
