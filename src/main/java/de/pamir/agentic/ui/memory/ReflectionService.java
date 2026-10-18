package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.concurrent.FireAndForget;
import de.pamir.agentic.ui.concurrent.InFlightGuard;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import de.pamir.agentic.ui.library.EmbeddingClient;
import de.pamir.agentic.ui.library.LibraryService;
import de.pamir.agentic.ui.session.ModelCatalog;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SystemTurnClient;
import de.pamir.agentic.ui.session.SystemTurnLane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * End-of-session memory retrospective: one structured system-session turn distills a
 * transcript into an episode summary plus semantic-memory ops (see docs/plan/phase-5.3-
 * memory-reflection.md, decisions 1-2). Depends on SystemTurnClient (for the system-session
 * turn); SessionService never depends back on this — the close-time trigger is a Spring event
 * (ReflectionRequested) precisely to avoid that cycle, while the manual "Reflect now" endpoint
 * calls {@link #reflect} directly from the controller.
 */
@Service
public class ReflectionService {

	private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);
	private static final Duration TIMEOUT = Duration.ofMinutes(3);
	private static final int MAX_OPS = 10;
	private static final int DISCARD_NOTE_CHARS = 200;
	private static final Set<String> VALID_OPS = Set.of("create", "update", "archive");
	private static final Set<String> VALID_SCOPES = Set.of("ecosystem", "service");
	/** Events that change what a digest contains — anything else (state changes, reflection's own journal lines) is not "new" material. */
	private static final Set<String> DIGEST_RELEVANT = Set.of("user_message", "assistant_message", "block_marked", "tool_started");

	private final SessionRepository sessions;
	private final SystemTurnClient systemTurnClient;
	private final EventJournal journal;
	private final JournalPublisher journalPublisher;
	private final SettingsService settings;
	private final MemoryDocService docService;
	private final MemoryRepository docs;
	private final MemoryEpisodeRepository episodes;
	private final MemoryProposalRepository proposals;
	private final EmbeddingClient embeddings;
	private final LibraryService library;
	private final ObjectMapper mapper;
	private final InFlightGuard<UUID> inFlight = new InFlightGuard<>();
	private final InFlightGuard<UUID> skillInFlight = new InFlightGuard<>();

	public ReflectionService(SessionRepository sessions, SystemTurnClient systemTurnClient, EventJournal journal,
							  JournalPublisher journalPublisher, SettingsService settings, MemoryDocService docService,
							  MemoryRepository docs, MemoryEpisodeRepository episodes,
							  MemoryProposalRepository proposals, EmbeddingClient embeddings, LibraryService library,
								  ObjectMapper mapper) {
		this.sessions = sessions;
		this.systemTurnClient = systemTurnClient;
		this.journal = journal;
		this.journalPublisher = journalPublisher;
		this.settings = settings;
		this.docService = docService;
		this.docs = docs;
		this.episodes = episodes;
		this.proposals = proposals;
		this.embeddings = embeddings;
		this.library = library;
		this.mapper = mapper;
	}

	@EventListener
	public void onReflectionRequested(ReflectionRequested event) {
		FireAndForget.run("reflect-" + event.sessionId(), log,
				"reflection failed for session " + event.sessionId(), () -> reflect(event.sessionId()));
	}

	/**
	 * Runs a reflection synchronously. Best-effort past this point: a failed system turn or
	 * unparseable response journals a warning and leaves reflectedSeq untouched (retryable),
	 * it never throws for those cases — only for the up-front validation below.
	 */
	public void reflect(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions are not reflected");
		}
		long lastSeq = journal.lastSeq(sessionId);
		// reflection journals its own events after capturing lastSeq, so lastSeq > reflectedSeq is always true
		// once a reflection ran — only messages, marks and tool calls count as new material (phase 21 decision 7)
		long since = session.reflectedSeq() == null ? 0 : session.reflectedSeq();
		if (lastSeq == 0 || !journal.hasEventsAfter(sessionId, since, DIGEST_RELEVANT)) {
			throw new IllegalStateException("nothing to reflect: no new messages or marks since the last reflection");
		}
		if (!inFlight.tryAcquire(sessionId)) {
			throw new IllegalStateException("a reflection is already in progress for this session");
		}
		try {
			runReflection(session, lastSeq);
		} finally {
			inFlight.release(sessionId);
		}
	}

	/**
	 * Approves a pending proposal, optionally with edited episode text / ops (mirrors
	 * {@code permission_response}'s {@code updatedInput} — edit before allowing), and applies it
	 * exactly as the auto-apply path would have.
	 */
	public void approveProposal(UUID proposalId, String editedEpisode, JsonNode editedOps) {
		var existing = proposals.get(proposalId);
		if (MemoryProposalRepository.KIND_SKILL.equals(existing.kind())) {
			approveSkill(existing, editedEpisode, editedOps);
			return;
		}
		proposals.decide(proposalId, "APPROVED");
		var proposal = proposals.get(proposalId);
		SessionEntity session = sessions.get(proposal.sessionId());
		String episode = editedEpisode != null && !editedEpisode.isBlank() ? editedEpisode : proposal.episode();
		JsonNode ops = editedOps != null ? editedOps : proposal.ops();
		applyReflection(session, proposal.reflectedSeq(), episode, ops);
	}

	/** Discards a pending proposal — nothing is written to the vault; {@code reflectedSeq} stays unset, so a later "Reflect now" can try again. */
	public void discardProposal(UUID proposalId) {
		proposals.decide(proposalId, "DISCARDED");
		var proposal = proposals.get(proposalId);
		record(proposal.sessionId(), "reflection_discarded", mapper.createObjectNode().put("episode", discardNote(proposal)));
	}

	/** A short label for the discard journal line — a skill proposal's episode is its whole SKILL.md body, which nothing renders. */
	static String discardNote(MemoryProposalRepository.Proposal proposal) {
		if (MemoryProposalRepository.KIND_SKILL.equals(proposal.kind())) {
			return "skill proposal: " + proposal.ops().path("name").asText("(unnamed)");
		}
		String episode = proposal.episode();
		return episode.length() > DISCARD_NOTE_CHARS ? episode.substring(0, DISCARD_NOTE_CHARS) + "…" : episode;
	}

	/** A refresh replaces a still-pending proposal: only called once the new one is ready, so a failed turn never loses the old one. */
	private void supersedePending(UUID sessionId, String kind) {
		proposals.findPendingForSession(sessionId, kind).ifPresent(pending -> {
			proposals.decide(pending.id(), "DISCARDED");
			record(sessionId, "reflection_discarded", mapper.createObjectNode()
					.put("episode", discardNote(pending)).put("reason", "superseded by refresh"));
		});
	}

	/**
	 * Distills the procedure a session demonstrated into a pending skill proposal (phase 21
	 * decisions 10-12). Always held for approval — an overfit skill auto-landing in every future
	 * session's picker is a different risk class from a memory note — and never marks the session
	 * reflected. Same best-effort contract as {@link #reflect}: a failed turn journals a warning.
	 */
	public void extractSkill(UUID sessionId) {
		SessionEntity session = sessions.get(sessionId);
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions are not reflected");
		}
		if (journal.lastSeq(sessionId) == 0 || !journal.hasEventsAfter(sessionId, 0, DIGEST_RELEVANT)) {
			throw new IllegalStateException("nothing to extract a skill from: the journal is empty or was pruned");
		}
		if (!skillInFlight.tryAcquire(sessionId)) {
			throw new IllegalStateException("a skill extraction is already in progress for this session");
		}
		try {
			runSkillExtraction(session);
		} finally {
			skillInFlight.release(sessionId);
		}
	}

	/**
	 * "Remember this": one marked block becomes a small memory proposal via a single cheap turn, with
	 * no episode and without marking the session reflected (phase 21 decision 14).
	 */
	public void remember(UUID sessionId, long targetSeq) {
		SessionEntity session = sessions.get(sessionId);
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions are not reflected");
		}
		var event = journal.findBySeq(sessionId, targetSeq)
				.orElseThrow(() -> new java.util.NoSuchElementException("no event with seq " + targetSeq + " in session " + sessionId));
		String text = TranscriptDigest.blockText(event);
		if (text == null || text.isBlank()) {
			throw new IllegalArgumentException("only user and assistant messages can be remembered");
		}
		String prompt = buildRememberPrompt(session.servicePath(), targetSeq, text, docs.findIndex(session.servicePath()));
		JsonNode result;
		try {
			String modelOverride = ModelCatalog.byTier(settings.systemProvider(), settings.current().memoryReflectionModel()).orElse(null);
			result = systemTurnClient.json(prompt, modelOverride, SystemTurnLane.INTERACTIVE, Duration.ofSeconds(45));
		} catch (RuntimeException e) {
			warn(sessionId, "remember failed: " + e.getMessage());
			return;
		}
		JsonNode ops = result.path("semantic");
		if (!ops.isArray() || ops.isEmpty()) {
			warn(sessionId, "nothing durable to remember in block #" + targetSeq);
			return;
		}
		ProposalEvidenceService.normalizeEvidence(ops);
		if (settings.current().memoryReflectionApprovalRequired()) {
			var proposal = proposals.insert(MemoryProposalRepository.KIND_REMEMBER, sessionId, session.name(),
					session.servicePath(), null, "", ops);
			ObjectNode payload = mapper.createObjectNode();
			payload.put("proposalId", proposal.id().toString());
			payload.put("kind", MemoryProposalRepository.KIND_REMEMBER);
			payload.put("episode", "remember block #" + targetSeq + ": " + ops.size() + " memory op(s) proposed");
			record(sessionId, "reflection_proposed", payload);
		} else {
			applyReflection(session, null, "", ops);
		}
	}

	static String buildRememberPrompt(String servicePath, long seq, String blockText, List<MemoryRepository.IndexEntry> index) {
		StringBuilder indexText = new StringBuilder();
		for (var entry : index) {
			indexText.append("- [").append(entry.scope()).append("] ").append(entry.name())
					.append(": ").append(entry.description()).append('\n');
		}
		return """
				The user flagged one block of a coding session as worth remembering (block #%d). Decide
				whether it holds a durable, non-obvious fact — a decision, gotcha, convention or user
				preference — and respond with ONE JSON object: {"semantic": [...]} holding at most 2 ops, each
				{"op": "create"|"update", "scope": "ecosystem"|"service", "name": "kebab-slug", "description":
				"...", "tags": ["..."], "content": "markdown body", "reason": "...", "evidence": [%d]}.
				Prefer "update" of an existing indexed doc over "create". Use scope "ecosystem" only for facts
				that apply beyond this one service. If the block holds nothing durable, return {"semantic": []}.
				Return ONLY the JSON object.

				Existing memory index (service: %s):
				%s

				Block #%d:
				%s
				""".formatted(seq, seq, servicePath, indexText.isEmpty() ? "(none yet)" : indexText, seq, blockText);
	}

	private void runSkillExtraction(SessionEntity session) {
		String digest = TranscriptDigest.render(journal.readAfter(session.id(), 0));
		String prompt = buildSkillPrompt(digest, library.skillNames());
		JsonNode result;
		try {
			String modelOverride = ModelCatalog.byTier(settings.systemProvider(), settings.current().memoryReflectionModel()).orElse(null);
			result = systemTurnClient.json(prompt, modelOverride, SystemTurnLane.BACKGROUND, TIMEOUT);
		} catch (RuntimeException e) {
			warn(session.id(), "skill extraction failed: " + e.getMessage());
			return;
		}
		JsonNode skill = result.path("skill");
		String content = skill.path("content").asText("");
		String name = skill.path("name").asText("");
		if (!skill.isObject() || content.isBlank()) {
			warn(session.id(), "no reusable procedure found in this session"
					+ (result.path("reason").asText("").isBlank() ? "" : ": " + result.path("reason").asText()));
			return;
		}
		if (!LibraryService.isValidSkillName(name)) {
			warn(session.id(), "skill extraction returned an invalid name: " + name);
			return;
		}
		ObjectNode meta = mapper.createObjectNode();
		meta.put("name", name);
		meta.put("description", skill.path("description").asText(""));
		meta.set("tags", mapper.valueToTree(SystemTurnClient.lowercaseTags(skill)));
		if (skill.path("evidence").isArray()) {
			meta.set("evidence", skill.get("evidence"));
			ProposalEvidenceService.normalizeEvidence(meta);
		}
		supersedePending(session.id(), MemoryProposalRepository.KIND_SKILL);
		var proposal = proposals.insert(MemoryProposalRepository.KIND_SKILL, session.id(), session.name(),
				session.servicePath(), null, content, meta);
		ObjectNode payload = mapper.createObjectNode();
		payload.put("proposalId", proposal.id().toString());
		payload.put("kind", MemoryProposalRepository.KIND_SKILL);
		payload.put("episode", "skill proposed: " + name);
		record(session.id(), "reflection_proposed", payload);
	}

	/**
	 * Applies the skill first and decides second: a name collision must leave the proposal pending
	 * so the user can rename it in the approval card and try again.
	 */
	private void approveSkill(MemoryProposalRepository.Proposal proposal, String editedContent, JsonNode editedMeta) {
		if (!"PENDING".equals(proposal.status())) {
			throw new IllegalStateException("proposal " + proposal.id() + " is not pending (already decided, or unknown)");
		}
		JsonNode meta = editedMeta != null && editedMeta.isObject() ? editedMeta : proposal.ops();
		String content = editedContent != null && !editedContent.isBlank() ? editedContent : proposal.episode();
		String name = meta.path("name").asText("");
		var asset = library.createSkill(name, meta.path("description").asText(""),
				SystemTurnClient.lowercaseTags(meta), content);
		proposals.decide(proposal.id(), "APPROVED");
		ObjectNode payload = mapper.createObjectNode().put("name", name).put("assetId", asset.id().toString());
		record(proposal.sessionId(), "skill_extracted", payload);
	}

	static String buildSkillPrompt(String digest, List<String> existingSkills) {
		return """
				You are extracting a reusable SKILL from a coding session. A skill is procedural: step-wise
				instructions an agent can follow the next time a similar task comes up — not a record of what
				happened (that is a memory episode) and not a fact (that is a memory note).

				Read the transcript digest below and respond with ONE JSON object:
				{"skill": {"name": "kebab-slug", "description": "...", "tags": ["..."], "content": "markdown body",
				"evidence": [<seq numbers>]}} — or {"skill": null, "reason": "why nothing here is skill-shaped"}.

				Rules:
				- Only extract a procedure that was actually demonstrated and worked, and that generalizes
				  beyond this one session. If the session was a one-off fix, exploration or a dead end,
				  return {"skill": null, ...}. Do not invent a skill to fill the answer.
				- "description" is one sentence telling an agent WHEN to use the skill (it is what skill
				  selection reads). "content" is the Markdown body WITHOUT frontmatter: when to use it, then
				  numbered steps, then pitfalls observed in this session. Generalize: no session-specific
				  file names, branch names or ids unless they are genuinely fixed conventions.
				- Digest lines prefixed "★ MARKED" were flagged by the user as the important part: build the
				  skill around them. Lines reading "[excluded from memory by the user]" were withheld on
				  purpose. "evidence" lists, as plain integers (e.g. [322]), the N of the "[#N]" anchors the skill is based on.
				- "name" must be a lowercase kebab-case slug that is not already taken.

				Skills already in the library (do not duplicate these): %s

				Transcript digest:
				%s
				""".formatted(existingSkills.isEmpty() ? "(none)" : String.join(", ", existingSkills), digest);
	}

	private void runReflection(SessionEntity session, long lastSeq) {
		String digest = TranscriptDigest.render(journal.readAfter(session.id(), 0));
		List<MemoryRepository.IndexEntry> index = docs.findIndex(session.servicePath());
		String prompt = buildPrompt(session.servicePath(), digest, index, episodes.findBySession(session.id()));
		JsonNode result;
		try {
			String modelOverride = ModelCatalog.byTier(settings.systemProvider(), settings.current().memoryReflectionModel()).orElse(null);
			result = systemTurnClient.json(prompt, modelOverride, SystemTurnLane.BACKGROUND, TIMEOUT);
		} catch (RuntimeException e) {
			warn(session.id(), "reflection failed: " + e.getMessage());
			return;
		}
		String episodeSummary = result.path("episode").asText("");
		if (episodeSummary.isBlank()) {
			warn(session.id(), "reflection response missing an episode summary");
			return;
		}
		JsonNode ops = result.path("semantic");
		if (!ops.isArray()) {
			ops = mapper.createArrayNode();
		}
		ProposalEvidenceService.normalizeEvidence(ops);
		if (settings.current().memoryReflectionApprovalRequired()) {
			supersedePending(session.id(), MemoryProposalRepository.KIND_MEMORY);
			var proposal = proposals.insert(session.id(), session.name(), session.servicePath(), lastSeq,
					episodeSummary, ops);
			ObjectNode payload = mapper.createObjectNode();
			payload.put("proposalId", proposal.id().toString());
			payload.put("episode", episodeSummary);
			payload.set("ops", ops);
			record(session.id(), "reflection_proposed", payload);
		} else {
			applyReflection(session, lastSeq, episodeSummary, ops);
		}
	}

	/** Writes the episode + semantic ops for real — the terminal step of both the auto-apply and the approve-proposal paths. */
	private void applyReflection(SessionEntity session, Long reflectedSeq, String episodeSummary, JsonNode ops) {
		if (!episodeSummary.isBlank()) {
			var episode = episodes.insert(session.id(), session.name(), session.servicePath(), episodeSummary);
			maybeEmbedEpisode(episode.id(), episodeSummary);
		}

		List<String> created = new ArrayList<>();
		List<String> updated = new ArrayList<>();
		List<String> archived = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		int count = 0;
		for (JsonNode op : ops) {
			if (count++ >= MAX_OPS) {
				warnings.add("more than " + MAX_OPS + " semantic ops returned; extras ignored");
				break;
			}
			applyOp(session, op, created, updated, archived, warnings);
		}
		if (reflectedSeq != null) {
			sessions.updateReflectedSeq(session.id(), reflectedSeq);
		}
		ObjectNode payload = mapper.createObjectNode();
		payload.put("episode", episodeSummary);
		payload.set("created", mapper.valueToTree(created));
		payload.set("updated", mapper.valueToTree(updated));
		payload.set("archived", mapper.valueToTree(archived));
		payload.set("warnings", mapper.valueToTree(warnings));
		record(session.id(), "reflection_complete", payload);
	}

	private void applyOp(SessionEntity session, JsonNode op, List<String> created, List<String> updated,
						  List<String> archived, List<String> warnings) {
		String kind = op.path("op").asText("");
		String scope = op.path("scope").asText("");
		String name = op.path("name").asText("");
		if (!VALID_OPS.contains(kind) || !VALID_SCOPES.contains(scope) || !name.matches("[a-z0-9][a-z0-9-]*")) {
			warnings.add("skipped malformed semantic op: " + op);
			return;
		}
		String servicePath = "service".equals(scope) ? session.servicePath() : null;
		try {
			switch (kind) {
				case "create" -> {
					docService.write(scope, servicePath, name, op.path("description").asText(""),
							SystemTurnClient.lowercaseTags(op), op.path("content").asText(""));
					created.add(name);
				}
				case "update" -> {
					var existing = docs.findByScopeAndName(scope, servicePath, name);
					if (existing.isEmpty()) {
						warnings.add("update target not found, skipped: " + name);
						return;
					}
					docService.write(scope, servicePath, name, op.path("description").asText(""),
							SystemTurnClient.lowercaseTags(op), op.path("content").asText(""));
					updated.add(name);
				}
				case "archive" -> {
					var existing = docs.findByScopeAndName(scope, servicePath, name);
					if (existing.isEmpty()) {
						warnings.add("archive target not found, skipped: " + name);
						return;
					}
					docService.archive(existing.get().id());
					archived.add(name);
				}
				default -> { /* unreachable, guarded above */ }
			}
		} catch (RuntimeException e) {
			warnings.add("op on '" + name + "' failed: " + e.getMessage());
		}
	}

	private void maybeEmbedEpisode(UUID episodeId, String summary) {
		float[] vector = embeddings.tryEmbed(summary, false);
		if (vector != null) {
			episodes.upsertEmbedding(episodeId, vector, embeddings.model());
		}
	}

	static String buildPrompt(String servicePath, String digest, List<MemoryRepository.IndexEntry> index,
							  List<MemoryEpisodeRepository.Episode> priorEpisodes) {
		StringBuilder indexText = new StringBuilder();
		for (var entry : index) {
			indexText.append("- [").append(entry.scope()).append("] ").append(entry.name())
					.append(": ").append(entry.description())
					.append(" (tags: ").append(String.join(", ", entry.tags())).append(")\n");
		}
		StringBuilder prior = new StringBuilder();
		if (!priorEpisodes.isEmpty()) {
			prior.append("This session was reflected on before. Those reflections already recorded these episodes:\n");
			for (var episode : priorEpisodes) {
				prior.append("- ").append(episode.summary()).append('\n');
			}
			prior.append("Do not restate them — record only what the marked blocks and any newer turns add, and prefer \"update\" ")
					.append("ops on existing memory over \"create\".\n\n");
		}
		return """
				You are retrospecting a finished coding session to extract durable memory. Read the
				transcript digest below and produce a JSON object with exactly two keys:

				"episode": a 3-6 sentence summary of what happened — goal, approach, outcome, failures,
				dead ends. This is an immutable log entry, write it as history, not advice.

				"semantic": an array of at most 8 ops, each {"op": "create"|"update"|"archive", "scope":
				"ecosystem"|"service", "name": "kebab-slug", "description": "...", "tags": ["..."],
				"content": "markdown body", "reason": "why this is worth remembering", "evidence": [the
				seq numbers N of the "[#N]" anchors this op is based on, as plain integers e.g. [322]]}. Only durable,
				non-obvious learnings — architectural decisions, gotchas, conventions, user preferences —
				never a restatement of what the repo's own docs already say. Prefer "update" of an
				existing indexed doc (listed below) over "create". Use scope "ecosystem" only for facts
				that apply beyond this one service/repo. Link related memories inline in "content" using
				[[slug]] wikilinks — liberally, including a link to a slug that doesn't exist yet if it
				marks a real gap. If nothing is worth remembering, return an empty "semantic" array — do
				not invent content to fill it. Return ONLY the JSON object, no prose, no code fences.

				Blocks in the digest prefixed "★ MARKED" were flagged by the user as the important part
				of the session: weight them heavily. Lines read "[excluded from memory by the user]" were
				withheld on purpose — never speculate about their content. The digest's "[#N]" anchors
				identify blocks.

				%sExisting memory index for this session's visible scopes (service: %s):
				%s

				Transcript digest:
				%s
				""".formatted(prior, servicePath, indexText.isEmpty() ? "(none yet)" : indexText, digest);
	}

	private void warn(UUID sessionId, String message) {
		log.warn("reflection warning for session {}: {}", sessionId, message);
		record(sessionId, "warning", mapper.createObjectNode().put("message", "reflection: " + message));
	}

	private void record(UUID sessionId, String type, JsonNode payload) {
		journalPublisher.record(sessionId, type, payload);
	}
}
