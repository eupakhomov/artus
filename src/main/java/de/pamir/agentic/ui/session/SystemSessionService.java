package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.journal.JournalPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the backend-initiated "system sessions" (ticket import, library AI-fill,
 * reflection, service discovery, commit/PR drafting, handoff briefs, auto-titling all run a turn
 * on one) — one stateless worker per {@link SystemTurnLane} (phase 19), each with its own
 * find-or-create/wake/resume, single-turn-in-flight lock, and the
 * pendingSystemTurn/pendingSystemText handshake that stitches the sidecar's assistant_message/
 * turn_complete/error events back onto the waiting caller's future. Extracted out of
 * SessionService (see docs/plan/phase-9-production-hardening.md S1) because this cluster is the
 * riskiest, most independently-testable logic in the class (T2). SessionService still owns the
 * general session state machine and calls into this class's package-private hooks from
 * onSidecarEvent/onSidecarExit; this class calls back into SessionService's package-private
 * session-mechanics (transition/spawn/wake/record/sendUserMessage/setModel/resume) — a genuine
 * two-way dependency, so the edge from here back to SessionService is {@code @Lazy} to let Spring
 * construct the cycle (SessionService's own dependency on this class is a plain, eager one).
 */
@Service
public class SystemSessionService {

	private static final Logger log = LoggerFactory.getLogger(SystemSessionService.class);

	/** {@code name} of the INTERACTIVE lane's session; {@code kind} is {@code 'system'} for both. */
	static final String INTERACTIVE_NAME = "system-interactive";
	static final String BACKGROUND_NAME = "system-background";
	static final String VISUALIZE_NAME = "system-visualize";
	/** Names of every lane's session row — anything else live with {@code kind='system'} is legacy. */
	static final java.util.Set<String> LANE_NAMES = java.util.Set.of(INTERACTIVE_NAME, BACKGROUND_NAME, VISUALIZE_NAME);

	/**
	 * Per-lane state that used to be class-level singleton fields: the fair binary lock, the
	 * pending-turn handshake, and the name its session row is found by. A Semaphore, not a
	 * ReentrantLock: the turn body runs on turnExecutor and releases this itself once done (see
	 * runSystemTurn's javadoc) — a ReentrantLock requires the same thread to lock and unlock.
	 */
	private static final class SystemWorker {
		final SystemTurnLane lane;
		final String name;
		final Semaphore lock = new Semaphore(1, true);
		volatile UUID pendingTurnSessionId;
		volatile CompletableFuture<String> pendingTurn;
		volatile String pendingText = "";
		/** A real turn (not a warm-up) holds the lock — what {@link #busy()} reports. */
		volatile boolean turnRunning;

		SystemWorker(SystemTurnLane lane, String name) {
			this.lane = lane;
			this.name = name;
		}
	}

	private final Map<SystemTurnLane, SystemWorker> workers = new EnumMap<>(Map.of(
			SystemTurnLane.INTERACTIVE, new SystemWorker(SystemTurnLane.INTERACTIVE, INTERACTIVE_NAME),
			SystemTurnLane.BACKGROUND, new SystemWorker(SystemTurnLane.BACKGROUND, BACKGROUND_NAME),
			SystemTurnLane.VISUALIZE, new SystemWorker(SystemTurnLane.VISUALIZE, VISUALIZE_NAME)));

	private final AppProperties props;
	private final SettingsService settings;
	private final SessionRepository sessions;
	private final SessionConfigFactory configFactory;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;
	private final SessionService sessionService;

	private static final Duration INTERACTIVE_LOCK_WAIT = Duration.ofSeconds(20);
	/** Extra budget, on top of the caller's own {@code timeout}, for reviving a parked/crashed
	 * system session (spawn + provider handshake) before {@link #runSystemTurnLocked} even
	 * reaches its own timed wait — see runSystemTurn's javadoc. */
	private static final Duration REVIVAL_BUDGET = Duration.ofSeconds(30);

	// runs runSystemTurnLocked's body so this call's own wait can be bounded end-to-end (see
	// runSystemTurn's javadoc) rather than only bounding its last step
	private final ExecutorService turnExecutor = Executors.newVirtualThreadPerTaskExecutor();

	public SystemSessionService(AppProperties props, SettingsService settings, SessionRepository sessions,
								 SessionConfigFactory configFactory, JournalPublisher journalPublisher,
								 ObjectMapper mapper, @Lazy SessionService sessionService) {
		this.props = props;
		this.settings = settings;
		this.sessions = sessions;
		this.configFactory = configFactory;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
		this.sessionService = sessionService;
	}

	/**
	 * Runs one backend-initiated turn and returns the assistant's final text. {@code lane}
	 * decides how long this call is willing to wait for another in-flight system turn to finish
	 * before giving up (see {@link SystemTurnLane}) — the turn itself still gets its full {@code
	 * timeout} once the lock is acquired.
	 */
	public String runSystemTurn(String prompt, SystemTurnLane lane, Duration timeout) {
		return runSystemTurn(prompt, null, lane, timeout);
	}

	/**
	 * Same as {@link #runSystemTurn(String, SystemTurnLane, Duration)}, but switches the system
	 * session to {@code modelOverride} for this one turn and back to its normal model afterward
	 * (used by reflection to run on a different model than the system session's default haiku —
	 * see docs/plan/phase-5.3-memory-reflection.md decision 7). {@code null} keeps the current
	 * model.
	 *
	 * <p>Once the lock is acquired, the actual turn (revival, dispatch, the wait, and the
	 * model-revert) runs on {@link #turnExecutor} so THIS call's wait is bounded end to end —
	 * {@code timeout} plus a fixed {@link #REVIVAL_BUDGET} for spawn/handshake — rather than only
	 * bounding the final wait-for-completion step. Previously, a stuck revival or a stuck
	 * {@code sendUserMessage} (its journal write, in particular) blocked the caller forever with
	 * no timeout ever firing. The background task, not this method, releases
	 * the worker's lock once it actually finishes, however long that takes:
	 * the worker's pending-turn fields are shared mutable state, so unlocking
	 * here while that task might still be running would let a second caller start a new turn the
	 * abandoned one could later stomp on.
	 */
	public String runSystemTurn(String prompt, String modelOverride, SystemTurnLane lane, Duration timeout) {
		SystemWorker worker = workers.get(lane);
		Duration lockWait = lane == SystemTurnLane.BACKGROUND ? timeout : INTERACTIVE_LOCK_WAIT;
		boolean acquired;
		try {
			acquired = worker.lock.tryAcquire(lockWait.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for the system session");
		}
		if (!acquired) {
			throw new IllegalStateException("system session is busy with another task; try again shortly");
		}
		worker.turnRunning = true;
		Future<String> body = turnExecutor.submit(() -> {
			try {
				return runSystemTurnLocked(worker, prompt, modelOverride, timeout);
			} finally {
				// still holding the lock: no caller can start a turn on the stale conversation
				recycle(worker);
				worker.turnRunning = false;
				worker.lock.release();
				if (lane == SystemTurnLane.INTERACTIVE) {
					warmUp(worker);
				}
			}
		});
		try {
			return body.get(timeout.plus(REVIVAL_BUDGET).toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("system task timed out after " + timeout.toSeconds() + "s");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException re) {
				throw re;
			}
			throw new IllegalStateException("system task failed: "
					+ (cause != null ? cause.getMessage() : e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for system task");
		}
	}

	/** Runs the turn body; caller must hold the worker's lock and release it. */
	private String runSystemTurnLocked(SystemWorker worker, String prompt, String modelOverride, Duration timeout) {
		SessionEntity session = getOrCreateSystemSession(worker);
		String originalModel = session.model();
		boolean switchModel = modelOverride != null && !modelOverride.equals(originalModel);
		if (switchModel) {
			sessionService.setModel(session.id(), modelOverride);
		}
		worker.pendingText = "";
		CompletableFuture<String> future = new CompletableFuture<>();
		worker.pendingTurn = future;
		worker.pendingTurnSessionId = session.id();
		try {
			sessionService.sendUserMessage(session.id(), prompt);
			return future.get(timeout.toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("system task timed out after " + timeout.toSeconds() + "s");
		} catch (ExecutionException e) {
			throw new IllegalStateException("system task failed: "
					+ (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for system task");
		} finally {
			worker.pendingTurn = null;
			worker.pendingTurnSessionId = null;
			if (switchModel) {
				sessionService.setModel(session.id(), originalModel);
			}
		}
	}

	@PreDestroy
	void shutdown() {
		turnExecutor.shutdownNow();
	}

	/**
	 * Fresh conversation per task: every system-turn prompt is self-contained, so the history is
	 * pure cost (and stale blocks bleed into later answers). Terminates the sidecar, clears the
	 * provider session id and parks, so the next wake spawns without {@code --resume}. Runs under
	 * the worker's lock. Terminates <em>before</em> clearing: events from the dying sidecar (a
	 * late system_init/turn_complete) can no longer land after the clear. Non-fatal — a session
	 * left CRASHED is revived by {@link #getOrCreateSystemSession}'s CRASHED arm.
	 */
	private void recycle(SystemWorker worker) {
		try {
			sessions.findSystemSession(worker.name).ifPresent(sessionService::recycleSystemSession);
		} catch (RuntimeException e) {
			log.warn("recycling {} failed: {}", worker.name, e.getMessage());
		}
	}

	/**
	 * Best-effort: revives/creates the session in the background without blocking the caller and
	 * without stealing the lock from a turn already in flight — used to move the "spin up the
	 * system session" cost off a user-visible dialog open (see
	 * docs/plan/phase-12-linear-cache-serena-context.md Step A2). A no-op when a turn is already
	 * running: {@code tryAcquire} never waits, so this can't delay an interactive turn.
	 */
	private void warmUp(SystemWorker worker) {
		if (!worker.lock.tryAcquire()) {
			return;
		}
		try {
			turnExecutor.submit(() -> {
				try {
					getOrCreateSystemSession(worker);
				} catch (RuntimeException e) {
					// best-effort; the next real caller will hit and surface the same failure
				} finally {
					worker.lock.release();
				}
			});
		} catch (RuntimeException e) { // executor shut down
			worker.lock.release();
		}
	}

	/** Warms the INTERACTIVE worker — the one in front of the dialogs; BACKGROUND warms on first use. */
	public void warmUp() {
		warmUp(workers.get(SystemTurnLane.INTERACTIVE));
	}

	/** True while the interactive worker is running a task — a new interactive
	 * request would queue behind it. A snapshot; the dialogs only use it to pick honest wait copy. */
	public boolean busy() {
		return workers.get(SystemTurnLane.INTERACTIVE).turnRunning;
	}

	/** True when the interactive session already exists in a state a wake/reuse is fast from — i.e.
	 * not "doesn't exist yet" or crashed/failed, both of which need a full cold spawn. Used to
	 * pick honest "warming up…" vs. cold-start copy in the create/quick dialogs (Step A3). */
	public boolean isWarm() {
		return sessions.findSystemSession(INTERACTIVE_NAME)
				.map(s -> s.state() != SessionState.CRASHED && s.state() != SessionState.FAILED)
				.orElse(false);
	}

	/** Find-or-create the worker's system session; caller must hold the worker's lock. */
	private SessionEntity getOrCreateSystemSession(SystemWorker worker) {
		return sessions.findSystemSession(worker.name).map(s -> {
			return switch (s.state()) {
				case PARKED -> {
					sessionService.wake(s);
					yield sessions.get(s.id());
				}
				case CRASHED -> sessionService.resume(s.id());
				default -> s;
			};
		}).orElseGet(() -> createSystemSession(worker));
	}

	private SessionEntity createSystemSession(SystemWorker worker) {
		UUID id = UUID.randomUUID();
		Path scratch = Path.of(props.worktreeRoot(), "_system", id.toString());
		try {
			Files.createDirectories(scratch);
		} catch (IOException e) {
			throw new IllegalStateException("failed to create system session scratch dir: " + e.getMessage(), e);
		}
		JsonNode mcpConfig = configFactory.linearMcpServer();
		// Backend-initiated turns have nobody to answer an interactive permission prompt, so tools
		// exposed via linearMcpServer() are pre-approved here (allowedTools bypasses canUseTool
		// entirely, regardless of permissionMode) rather than left to prompt and hang/time out.
		// (Codex rejects allowedTools outright — see SettingsService.systemProvider()'s javadoc.)
		List<String> allowedTools = mcpConfig != null ? List.of("mcp__linear") : List.of();
		String provider = settings.systemProvider();
		// A turn must end with the answer itself. Left enabled, the model may delegate to a background
		// subagent ("I've launched an agent… waiting") and end the turn with a status line, which
		// SystemTurnClient then fails to parse as JSON. (Codex rejects disallowedTools outright.)
		List<String> disallowedTools = "claude".equals(provider) ? List.of("Task", "Agent") : List.of();
		String model = ModelCatalog.byTier(provider, "cheap").orElse(null);
		SessionEntity entity = SessionEntity.builder()
				.id(id).name(worker.name).provider(provider)
				.repoPath("(system)").contextDirs(List.of())
				.branch("(system)").baseBranch("(system)").worktreePath(scratch.toString())
				.model(model).permissionMode("default")
				.allowedTools(allowedTools).disallowedTools(disallowedTools).mcpConfig(mcpConfig)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.CREATING).kind("system").reflectionEnabled(false)
				.build();
		sessionService.enforceSessionLimitAndInsert(entity);
		journalPublisher.record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		try {
			sessionService.transition(id, SessionState.PROVISIONING);
			sessionService.writeMcpConfig(entity);
			sessionService.transition(id, SessionState.STARTING);
			sessionService.spawn(sessions.get(id), false);
		} catch (RuntimeException e) {
			journalPublisher.record(id, "error",
					mapper.createObjectNode().put("message", e.getMessage()).put("fatal", true));
			sessionService.transition(id, SessionState.FAILED);
			throw e;
		}
		return sessions.get(id);
	}

	// ------------------------------------------------------------------ sidecar-event hooks (called by SessionService)

	/** Accumulates assistant text for the in-flight system turn, if {@code sessionId} is the pending one. */
	void onAssistantMessage(UUID sessionId, JsonNode content) {
		SystemWorker worker = workerFor(sessionId);
		if (worker == null) {
			return;
		}
		String text = extractText(content);
		if (!text.isBlank()) {
			worker.pendingText = text;
		}
	}

	/** Completes the pending system turn (if any) for {@code sessionId} with the accumulated assistant text. */
	void completeTurn(UUID sessionId) {
		SystemWorker worker = workerFor(sessionId);
		if (worker != null) {
			completePending(worker, worker.pendingText, null);
		}
	}

	/** Fails the pending system turn (if any) for {@code sessionId} — a fatal sidecar error or crash. */
	void failTurn(UUID sessionId, Throwable error) {
		SystemWorker worker = workerFor(sessionId);
		if (worker != null) {
			completePending(worker, null, error);
		}
	}

	/** The worker whose in-flight turn belongs to {@code sessionId}, if any. */
	private SystemWorker workerFor(UUID sessionId) {
		for (SystemWorker worker : workers.values()) {
			if (sessionId.equals(worker.pendingTurnSessionId)) {
				return worker;
			}
		}
		return null;
	}

	private void completePending(SystemWorker worker, String result, Throwable error) {
		CompletableFuture<String> waiter = worker.pendingTurn;
		if (waiter == null) {
			return;
		}
		if (error != null) {
			waiter.completeExceptionally(error);
		} else {
			waiter.complete(result);
		}
	}

	/** Text of the last assistant_message in a turn (there may be several around a tool call). */
	static String extractText(JsonNode content) {
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
