package de.pamir.agentic.ui.slack;

import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.EventJournal.JournalEvent;
import de.pamir.agentic.ui.journal.SessionEventBus;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionService;
import de.pamir.agentic.ui.session.SessionState;
import de.pamir.agentic.ui.slack.SlackBlocks.ParentView;
import de.pamir.agentic.ui.slack.SlackBlocks.PlanMessage;
import de.pamir.agentic.ui.slack.SlackMessaging.SlackMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Outbound half of the Slack integration (phase 18 Step 2): follows every session's journal events
 * and mirrors the ones a person cares about into that session's Slack thread.
 *
 * <p>A session with {@code slackEnabled} gets a <b>parent message</b> (name, state, branch, buttons)
 * that is edited in place as the session changes — at most once per 10 s — and a <b>thread</b> of
 * curated replies: permission, plan and question prompts (with their buttons), a line when a turn
 * finishes, fatal errors, PR status changes, and a final message when the session closes. It is
 * deliberately not a transcript. A child spawned with {@code spawn_child_session} posts into its
 * parent's thread, tagged with its name, and follows its parent's opt-in. The system session never
 * posts. Slack being down or misconfigured never touches a session: everything goes through {@link
 * SlackOutbox}, and every failure here is logged and swallowed.
 *
 * <p>All event handling runs on one thread, so a session's posts are enqueued in journal order.
 */
@Component
@ConditionalOnProperty(prefix = "artus.slack", name = "enabled", havingValue = "true")
public class SlackSessionMirror {

	private static final Logger log = LoggerFactory.getLogger(SlackSessionMirror.class);

	private static final long PARENT_UPDATE_EVERY_NANOS = Duration.ofSeconds(10).toNanos();
	/** States that change what the person can do, so the card reflecting them is never held back. */
	private static final Set<String> URGENT_STATES = Set.of("IDLE", "WAITING_INPUT", "PARKED", "CRASHED", "FAILED");
	private static final long THREAD_RETRY_COOLDOWN_NANOS = Duration.ofSeconds(30).toNanos();
	private static final int EXCERPT_CHARS = 600;
	private static final int KICKOFF_CHARS = 300;

	/** Event types the mirror reacts to; everything else (stream_delta, tool_*, …) is dropped at the door. */
	private static final Set<String> HANDLED = Set.of(
			"state_changed", "user_message", "assistant_message", "permission_request", "permission_response",
			"turn_complete", "error", "budget_exhausted", "pr_status_changed", "session_renamed", "slack_input", "interrupt");

	record ThreadRef(String channel, String ts) {
	}

	private record Prompt(String kind, String title, String detail, CompletableFuture<String> ts) {
	}

	/** Mirroring state of one session. Mutable fields are touched only on the mirror thread. */
	private static final class State {
		final UUID id;
		final boolean mirrored;
		final boolean child;
		final UUID rootId;
		final String name;
		final Map<String, Prompt> prompts = new HashMap<>();
		CompletableFuture<ThreadRef> thread;
		long threadAttemptNanos;
		String lastAssistantText = "";
		boolean interruptRequested;
		boolean closing;
		boolean updateScheduled;
		ScheduledFuture<?> updateFuture;
		long lastUpdateNanos = System.nanoTime() - PARENT_UPDATE_EVERY_NANOS;
		boolean closed;
		volatile String firstPrompt;

		State(UUID id, boolean mirrored, boolean child, UUID rootId, String name) {
			this.id = id;
			this.mirrored = mirrored;
			this.child = child;
			this.rootId = rootId;
			this.name = name;
		}
	}

	private final SlackProperties props;
	private final SlackOutbox outbox;
	private final SessionRepository sessions;
	private final SessionEventBus bus;
	private final Function<UUID, BigDecimal> costToDate;
	private final Map<UUID, State> states = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
			java.lang.Thread.ofVirtual().name("slack-mirror").factory());
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		java.lang.Thread t = new java.lang.Thread(r, "slack-mirror-timer");
		t.setDaemon(true);
		return t;
	});

	@Autowired
	public SlackSessionMirror(SlackProperties props, SlackOutbox outbox, SessionRepository sessions,
							  SessionEventBus bus, EventJournal journal) {
		this(props, outbox, sessions, bus, journal::costToDate);
	}

	/** Test seam: the session's cumulative cost without a real journal. */
	SlackSessionMirror(SlackProperties props, SlackOutbox outbox, SessionRepository sessions,
							  SessionEventBus bus, Function<UUID, BigDecimal> costToDate) {
		this.props = props;
		this.outbox = outbox;
		this.sessions = sessions;
		this.bus = bus;
		this.costToDate = costToDate;
	}

	@EventListener(ApplicationReadyEvent.class)
	void start() {
		if (props.channel().isBlank()) {
			log.warn("slack: no channel configured — the mirror is idle");
			return;
		}
		bus.subscribeAll((sessionId, event) -> {
			if (HANDLED.contains(event.type())) {
				executor.execute(() -> handle(sessionId, event));
			}
		});
		log.info("slack: mirroring opted-in sessions into channel {}", props.channel());
	}

	@PreDestroy
	void stop() {
		scheduler.shutdownNow();
		executor.shutdownNow();
	}

	// ------------------------------------------------------------------ dispatch

	private void handle(UUID sessionId, JournalEvent event) {
		try {
			State st = stateFor(sessionId);
			if (st == null || !st.mirrored || st.closed) {
				return;
			}
			JsonNode p = event.payload();
			switch (event.type()) {
				case "state_changed" -> onState(st, p.path("state").asText(""), p.path("reason").asText(""));
				case "user_message" -> onUserMessage(st, p);
				case "assistant_message" -> onAssistantMessage(st, p);
				case "permission_request" -> onPermissionRequest(st, p);
				case "permission_response" -> onPermissionResponse(st, p);
				case "turn_complete" -> onTurnComplete(st, p);
				case "error" -> onError(st, p);
				case "budget_exhausted" -> onBudgetExhausted(st, p);
				case "pr_status_changed" -> onPrStatus(st, p);
				case "session_renamed" -> scheduleParentUpdate(rootOf(st));
				case "slack_input" -> onSlackInput(st, p);
				case "interrupt" -> st.interruptRequested = true;
				default -> { /* filtered out by HANDLED */ }
			}
		} catch (RuntimeException e) {
			log.warn("slack: could not mirror {} for session {}: {}", event.type(), sessionId, e.getMessage());
		}
	}

	/** Resolves (once, then cached) whether a session is mirrored and where its thread comes from. */
	private State stateFor(UUID id) {
		State known = states.get(id);
		if (known != null) {
			return known;
		}
		SessionEntity e = sessions.find(id).orElse(null);
		if (e == null) {
			return null;
		}
		boolean mirrored = false;
		UUID rootId = id;
		boolean child = e.parentSessionId() != null;
		if ("user".equals(e.kind())) {
			if (child) {
				SessionEntity parent = sessions.find(e.parentSessionId()).orElse(null);
				mirrored = parent != null && "user".equals(parent.kind()) && parent.slackEnabled();
				rootId = e.parentSessionId();
			} else {
				mirrored = e.slackEnabled();
			}
		}
		State st = new State(id, mirrored, child, rootId, e.name());
		states.put(id, st);
		return st;
	}

	private State rootOf(State st) {
		return st.child ? stateFor(st.rootId) : st;
	}

	// ------------------------------------------------------------------ handlers

	private void onState(State st, String state, String reason) {
		State root = rootOf(st);
		if (root == null || !root.mirrored) {
			return;
		}
		switch (state) {
			case "CREATING" -> {
				if (st.child) {
					notice(st, "child started", "↳ *" + SlackBlocks.esc(st.name) + "* started");
				} else {
					ensureThread(st);
				}
			}
			case "CRASHED" -> {
				if (SessionService.CRASH_BACKEND_RESTART.equals(reason)) {
					notice(st, "session interrupted",
							":warning: *Session interrupted* by a backend restart — resume it from the dashboard.");
				} else {
					notice(st, "session crashed", ":rotating_light: *Session crashed* — resume it from the dashboard.");
				}
			}
			case "FAILED" -> notice(st, "session " + state.toLowerCase(),
					":rotating_light: *Session " + state.toLowerCase() + "*"
							+ ("CRASHED".equals(state) ? " — resume it from the dashboard." : "."));
			case "CLOSING" -> st.closing = true;
			case "CLOSED" -> onClosed(st, root);
			default -> { /* label only; the parent update below covers it */ }
		}
		if (!st.child && !"CREATING".equals(state)) {
			// the creation post already renders the current state; and a change in what the person can do (it is now
			// idle, waiting for them, parked, dead) must not sit behind the 10 s throttle — a card still saying "working"
			// for ten seconds after the turn ended is exactly what makes someone press Stop on nothing
			scheduleParentUpdate(st, URGENT_STATES.contains(state));
		}
	}

	private void onClosed(State st, State root) {
		String cost = money(costToDate.apply(st.id));
		notice(st, "session closed", ":lock: *Session closed* — cost $" + cost);
		if (!st.child) {
			sendParentUpdate(st);
			ensureThread(st).thenAccept(t -> outbox.react(t.channel(), t.ts(), "white_check_mark"));
		}
		st.closed = true;
		st.prompts.clear();
	}

	private void onUserMessage(State st, JsonNode p) {
		st.interruptRequested = false; // a new prompt starts a new turn
		if (!st.child && st.firstPrompt == null) {
			st.firstPrompt = p.path("text").asText("");
			scheduleParentUpdate(st);
		}
	}

	private void onAssistantMessage(State st, JsonNode p) {
		if (p.hasNonNull("parentToolUseId")) {
			return; // a sub-agent's message, not the main agent's
		}
		String text = textOf(p.get("content"));
		if (!text.isBlank()) {
			st.lastAssistantText = text;
		}
	}

	private void onPermissionRequest(State st, JsonNode p) {
		String requestId = p.path("requestId").asText("");
		String tool = p.path("toolName").asText("");
		JsonNode input = p.path("input");
		if (requestId.isBlank()) {
			return;
		}
		String sid = st.id.toString();
		SlackMessage message;
		String kind;
		String title;
		String detail = null;
		String planForSnippet = null;
		switch (tool) {
			case "ExitPlanMode" -> {
				String plan = mask(input.path("plan").asText(""));
				PlanMessage pm = SlackBlocks.plan(sid, requestId, plan, st.name);
				message = pm.message();
				planForSnippet = pm.needsSnippet() ? plan : null;
				kind = "plan";
				title = "*Plan*";
			}
			case "AskUserQuestion" -> {
				message = SlackBlocks.question(sid, requestId, input, st.name);
				kind = "question";
				String asked = input.path("questions").path(0).path("question").asText("");
				title = "*Question*" + (asked.isBlank() ? "" : " \u2014 " + SlackBlocks.esc(SlackToolSummary.truncate(mask(asked), 140)));
			}
			default -> {
				detail = SlackToolSummary.summarize(tool, input, props.verbose());
				message = SlackBlocks.permission(sid, requestId, tool, detail, st.name);
				kind = "permission";
				title = "*Permission* — `" + SlackBlocks.esc(tool) + "`";
			}
		}
		String prefix = st.child ? "*" + SlackBlocks.esc(st.name) + "* · " : "";
		SlackMessage toSend = st.child ? SlackBlocks.withPrefix(message, prefix) : message;
		CompletableFuture<String> posted = threadOf(st).thenCompose(t -> outbox.post(t.channel(), t.ts(), true, () -> toSend));
		st.prompts.put(requestId, new Prompt(kind, prefix + title, detail, posted));
		posted.exceptionally(e -> {
			log.debug("slack: prompt {} not posted: {}", requestId, e.getMessage());
			return null;
		});
		if (planForSnippet != null) {
			String plan = planForSnippet;
			threadOf(st).thenAccept(t -> outbox.snippet(t.channel(), t.ts(), "plan", plan));
		}
	}

	/** A prompt was answered (here, on the dashboard, or by a crash): strip its buttons and say how it ended. */
	private void onPermissionResponse(State st, JsonNode p) {
		Prompt prompt = st.prompts.remove(p.path("requestId").asText(""));
		if (prompt == null) {
			return;
		}
		boolean allowed = "allow".equals(p.path("behavior").asText(""));
		String by = "slack".equals(p.path("source").asText("")) && p.hasNonNull("slackUser")
				? "by <@" + p.get("slackUser").asText() + ">" : "on the dashboard";
		String outcome = switch (prompt.kind()) {
			case "plan" -> allowed ? "Plan approved " + by : "Kept planning " + by;
			case "question" -> allowed ? "Answered " + answerOf(p) + by : "Skipped " + by;
			default -> (allowed ? "Allowed " : "Denied ") + by;
		};
		String requestId = p.path("requestId").asText("");
		threadOf(st).thenAcceptBoth(prompt.ts(), (t, ts) -> outbox.update("prompt:" + requestId, t.channel(), ts,
				() -> SlackBlocks.settled(prompt.title(), prompt.detail(), outcome)));
	}

	/** The answer given to a question, as a quoted word followed by a space (empty when the response carries none). */
	private String answerOf(JsonNode response) {
		for (JsonNode answer : response.path("updatedInput").path("answers")) {
			String text = answer.asText("");
			if (!text.isBlank()) {
				return "\u201c" + SlackBlocks.esc(SlackToolSummary.truncate(mask(text), 80)) + "\u201d ";
			}
		}
		return "";
	}

	private void onTurnComplete(State st, JsonNode p) {
		String stopReason = p.path("stopReason").asText("");
		// the SDK reports an interrupted turn as error_during_execution, so the stop reason alone cannot tell
		boolean interrupted = st.interruptRequested || stopReason.contains("interrupt");
		st.interruptRequested = false;
		String excerpt = SlackToolSummary.truncate(mask(st.lastAssistantText.strip()), EXCERPT_CHARS);
		st.lastAssistantText = "";
		String duration = p.hasNonNull("durationMs") ? " (" + formatDuration(p.get("durationMs").asLong()) + ")" : "";
		StringBuilder text = new StringBuilder(interrupted ? ":octagonal_sign: *Turn interrupted*" : ":white_check_mark: *Turn finished*")
				.append(duration);
		if (!excerpt.isBlank()) {
			text.append("\n> ").append(SlackBlocks.esc(excerpt).replace("\n", "\n> "));
		}
		notice(st, "turn finished", text.toString());
	}

	private void onError(State st, JsonNode p) {
		if (!p.path("fatal").asBoolean(false) || st.closing) {
			return; // a closing session's sidecar often dies noisily; that is not news
		}
		// first line only: the rest is usually a stack trace full of local file paths
		String message = SlackToolSummary.truncate(mask(p.path("message").asText("").lines().findFirst().orElse("")), 300);
		notice(st, "error", ":rotating_light: *Error* — " + SlackBlocks.esc(message));
	}

	/** Something was done from Slack that leaves no event of its own: say who did it, where everyone can see. */
	private void onSlackInput(State st, JsonNode p) {
		if (!p.hasNonNull("slackUser")) {
			return;
		}
		String user = p.get("slackUser").asText();
		switch (p.path("kind").asText("")) {
			case "stop" -> notice(st, "stop requested", ":octagonal_sign: *Stop requested* by <@" + user + ">");
			case "close" -> notice(st, "close requested", ":lock: *Close requested* by <@" + user + ">");
			case "start" -> notice(st, "started from slack", ":rocket: Started from Slack by <@" + user + "> ("
					+ SlackBlocks.esc(p.path("tool").asText("session")) + ").");
			default -> { /* a prompt gets a reaction on the message itself */ }
		}
	}

	private void onBudgetExhausted(State st, JsonNode p) {
		notice(st, "budget reached", ":moneybag: *Cost budget reached* — $" + p.path("costToDate").asText("?")
				+ " of $" + p.path("costBudgetUsd").asText("?") + ". Raise the budget on the dashboard to continue.");
	}

	private void onPrStatus(State st, JsonNode p) {
		String url = p.path("url").asText("");
		String status = p.path("status").asText("?");
		String previous = p.path("previousStatus").asText("");
		String link = url.isBlank() ? "Pull request" : "<" + url + "|Pull request>";
		notice(st, "PR status", ":twisted_rightwards_arrows: " + link + " — "
				+ (previous.isBlank() ? "" : SlackBlocks.esc(previous) + " → ") + "*" + SlackBlocks.esc(status) + "*");
		scheduleParentUpdate(rootOf(st));
	}

	// ------------------------------------------------------------------ thread + parent message

	/** The thread this session posts into: its own, or — for a child — its parent's. */
	private CompletableFuture<ThreadRef> threadOf(State st) {
		State root = rootOf(st);
		if (root == null || !root.mirrored) {
			return CompletableFuture.failedFuture(new IllegalStateException("session is not mirrored"));
		}
		return ensureThread(root);
	}

	/**
	 * The root session's thread, creating its parent message on first use. If that first post fails
	 * the failure is remembered for {@value #THREAD_RETRY_COOLDOWN_NANOS} ns of cooldown and the
	 * next event tries again — a Slack outage at creation must not leave a session threadless forever.
	 */
	private CompletableFuture<ThreadRef> ensureThread(State root) {
		long now = System.nanoTime();
		if (root.thread != null && !(root.thread.isCompletedExceptionally()
				&& now - root.threadAttemptNanos >= THREAD_RETRY_COOLDOWN_NANOS)) {
			return root.thread;
		}
		root.threadAttemptNanos = now;
		SessionEntity e = sessions.get(root.id);
		if (e.slackThreadTs() != null) {
			String channel = e.slackChannel() != null ? e.slackChannel() : props.channel();
			root.thread = CompletableFuture.completedFuture(new ThreadRef(channel, e.slackThreadTs()));
			return root.thread;
		}
		String channel = props.channel();
		root.thread = outbox.post(channel, null, true, () -> SlackBlocks.parent(view(root)))
				.thenApply(ts -> {
					sessions.attachSlackThread(root.id, channel, ts);
					return new ThreadRef(channel, ts);
				});
		return root.thread;
	}

	/** Coalesces parent edits: the first goes out at once, later ones at most every 10 s. */
	private void scheduleParentUpdate(State root) {
		scheduleParentUpdate(root, false);
	}

	/**
	 * Coalesces card edits: the first goes out at once, later ones at most every 10 s. An {@code urgent} one
	 * (see {@link #URGENT_STATES}) overtakes a delayed edit that is already waiting and goes out now; the outbox
	 * still collapses edits to one pending per session and spaces calls, so a burst cannot flood Slack.
	 */
	private void scheduleParentUpdate(State root, boolean urgent) {
		if (root == null || !root.mirrored) {
			return;
		}
		if (root.updateScheduled) {
			if (!urgent) {
				return;
			}
			if (root.updateFuture != null) {
				root.updateFuture.cancel(false);
			}
		}
		root.updateScheduled = true;
		long wait = urgent ? 0 : Math.max(0, root.lastUpdateNanos + PARENT_UPDATE_EVERY_NANOS - System.nanoTime());
		root.updateFuture = scheduler.schedule(() -> executor.execute(() -> {
			root.updateScheduled = false;
			sendParentUpdate(root);
		}), wait, TimeUnit.NANOSECONDS);
	}

	private void sendParentUpdate(State root) {
		root.lastUpdateNanos = System.nanoTime();
		ensureThread(root).thenAccept(t -> outbox.update("parent:" + root.id, t.channel(), t.ts(),
				() -> SlackBlocks.parent(view(root))));
	}

	/** Renders the parent from the session as it is <em>now</em> (called at send time, on the outbox thread). */
	private ParentView view(State root) {
		SessionEntity e = sessions.get(root.id);
		StringBuilder context = new StringBuilder(e.provider()).append(" · ").append(e.branch())
				.append(" · ").append(e.sessionType());
		if (e.model() != null) {
			context.append(" · ").append(e.model());
		}
		if (e.ticketRef() != null) {
			context.append(" · ").append(e.ticketRef());
		}
		String pr = null;
		if (e.prUrl() != null) {
			pr = "<" + e.prUrl() + "|Pull request>" + (e.prCheckStatus() != null ? " — " + SlackBlocks.esc(e.prCheckStatus()) : "");
		}
		String kickoff = e.kickoffPrompt() != null && !e.kickoffPrompt().isBlank() ? e.kickoffPrompt() : root.firstPrompt;
		boolean live = e.state() != SessionState.CLOSED && e.state() != SessionState.FAILED;
		String label = SessionService.CRASH_BACKEND_RESTART.equals(e.crashReason())
				? ":large_orange_circle: interrupted (backend restart)" : stateLabel(e.state());
		return new ParentView(e.id().toString(), e.name(), label, context.toString(), pr,
				kickoff == null ? null : SlackToolSummary.truncate(mask(kickoff.strip()), KICKOFF_CHARS),
				openUrl(), live);
	}

	// ------------------------------------------------------------------ helpers

	/** A plain mrkdwn line into the session's thread (a child's lines are tagged with its name). */
	private void notice(State st, String fallback, String mrkdwn) {
		String text = st.child && !mrkdwn.startsWith("↳") ? "*" + SlackBlocks.esc(st.name) + "* · " + mrkdwn : mrkdwn;
		SlackMessage message = SlackBlocks.notice(fallback, text);
		threadOf(st).thenAccept(t -> outbox.post(t.channel(), t.ts(), false, () -> message))
				.exceptionally(e -> {
					log.debug("slack: '{}' not posted: {}", fallback, e.getMessage());
					return null;
				});
	}

	private String mask(String text) {
		return props.verbose() ? text : SlackRedactor.mask(text);
	}

	private String openUrl() {
		String base = props.publicUrl();
		if (base == null || base.isBlank()) {
			return null;
		}
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	static String stateLabel(SessionState state) {
		return switch (state) {
			case CREATING, PROVISIONING, STARTING -> ":large_yellow_circle: starting";
			case IDLE -> ":large_green_circle: idle";
			case RUNNING -> ":large_blue_circle: working";
			case WAITING_INPUT -> ":large_orange_circle: waiting for you";
			case PARKED -> ":white_circle: parked (idle)";
			case CLOSING -> ":black_circle: closing";
			case CLOSED -> ":black_circle: closed";
			case CRASHED -> ":red_circle: crashed";
			case FAILED -> ":red_circle: failed";
		};
	}

	/** Cumulative cost as dollars: summed floating-point turn costs carry noise, so round to 4 places and show at least 2. */
	static String money(BigDecimal amount) {
		BigDecimal rounded = amount.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
		return (rounded.scale() < 2 ? rounded.setScale(2) : rounded).toPlainString();
	}

	static String formatDuration(long millis) {
		long seconds = Math.max(0, millis / 1000);
		if (seconds < 60) {
			return seconds + "s";
		}
		long minutes = seconds / 60;
		return minutes < 60 ? minutes + "m " + (seconds % 60) + "s" : (minutes / 60) + "h " + (minutes % 60) + "m";
	}

	private static String textOf(JsonNode content) {
		if (content == null || !content.isArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : content) {
			if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
				sb.append(block.get("text").asText());
			}
		}
		return sb.toString();
	}
}
