package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.SessionEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link SystemSessionService#runSystemTurn}'s pendingSystemTurn/pendingSystemText
 * handshake end to end — successful completion, timeout, crash, and the model-override/restore
 * dance — against a fully-wired real SessionService/SystemSessionService pair (the same
 * two-way collaboration production uses, just backed by in-memory fakes). See
 * docs/plan/phase-9-production-hardening.md T2.
 *
 * <p>SystemSessionService's constructor dependency on SessionService is {@code @Lazy} in
 * production specifically to let Spring construct this cycle; a plain {@code new} can't express
 * that, so this test seeds the same back-reference via reflection once both objects exist —
 * standing in for what Spring's lazy proxy resolves automatically.
 */
class SystemSessionServiceRunTurnTest {

	private final ObjectMapper mapper = new JsonMapper();

	private FakeSessionRepository sessions;
	private FakeSidecarManager sidecars;
	private SystemSessionService systemSessionService;
	private SessionService sessionService;
	private AppProperties props;
	private JournalPublisher journalPublisher;
	private UUID systemId;

	@TempDir
	Path worktreeRoot;

	@BeforeEach
	void setUp() throws ReflectiveOperationException {
		props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		Settings fixed = new Settings(false, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
				false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", "", "none", "");
		SettingsService settings = new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
		sessions = new FakeSessionRepository();
		sidecars = new FakeSidecarManager();
		journalPublisher = new JournalPublisher(new FakeEventJournal(props), new SessionEventBus());
		SessionConfigFactory configFactory = new SessionConfigFactory(props, settings, null, mapper, null, 8080, null, null, null, null, null);
		systemSessionService = new SystemSessionService(props, settings, sessions, configFactory, journalPublisher,
				mapper, null);
		sessionService = new SessionService(props, settings, sessions, null, null, null, sidecars,
				new FakeEventJournal(props), journalPublisher, mapper, event -> {
		}, configFactory, systemSessionService, null, null, null, null);
		bindLazySessionService(systemSessionService, sessionService);

		systemId = seedSystemSession(SystemSessionService.INTERACTIVE_NAME);
	}

	/** Seeds a live, resumed-looking (provider id set) system session with a running fake sidecar. */
	private UUID seedSystemSession(String name) {
		UUID id = UUID.randomUUID();
		SessionEntity system = SessionEntity.builder()
				.id(id).name(name).provider("claude").repoPath("(system)")
				.branch("(system)").baseBranch("(system)").worktreePath(worktreeRoot.toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.model("original-model").state(SessionState.IDLE).kind("system").providerSessionId("prov-" + name)
				.build();
		sessions.seed(system);
		sidecars.spawn(system, null, false, null, e -> {
		}, (h, c) -> {
		});
		return id;
	}

	/** Mirrors what Spring's {@code @Lazy} proxy resolves automatically in production. */
	private static void bindLazySessionService(SystemSessionService systemSessionService,
												SessionService sessionService) throws ReflectiveOperationException {
		Field field = SystemSessionService.class.getDeclaredField("sessionService");
		field.setAccessible(true);
		field.set(systemSessionService, sessionService);
	}

	private ArrayNode textContent(String text) {
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", text));
		return content;
	}

	@Test
	void runSystemTurnReturnsTheAccumulatedAssistantTextOnCompletion() throws InterruptedException {
		AtomicReference<String> result = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().start(() ->
				result.set(systemSessionService.runSystemTurn("prompt", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));

		awaitPendingTurn();
		systemSessionService.onAssistantMessage(systemId, textContent("the answer"));
		systemSessionService.completeTurn(systemId);
		caller.join(2000);

		assertThat(result.get()).isEqualTo("the answer");
	}

	@Test
	void runSystemTurnThrowsAfterItsOwnTimeoutElapses() {
		seedSystemSession(SystemSessionService.BACKGROUND_NAME);
		assertThatThrownBy(() -> systemSessionService.runSystemTurn("prompt", SystemTurnLane.BACKGROUND, java.time.Duration.ofSeconds(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("timed out");
	}

	@Test
	void runSystemTurnSurfacesAFatalSidecarErrorAsAFailure() throws InterruptedException {
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				systemSessionService.runSystemTurn("prompt", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2));
			} catch (RuntimeException e) {
				error.set(e);
			}
		});

		awaitPendingTurn();
		systemSessionService.failTurn(systemId, new IllegalStateException("system session crashed (exit 1)"));
		caller.join(2000);

		assertThat(error.get()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("system task failed").hasMessageContaining("crashed (exit 1)");
	}

	@Test
	void runSystemTurnSwitchesToTheOverrideModelAndRestoresItAfterward() throws InterruptedException {
		Thread caller = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("prompt", "override-model", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2)));

		awaitPendingTurn();
		assertThat(sidecars.sentTo(systemId)).anyMatch(line -> line.contains("\"set_model\"") && line.contains("override-model"));

		systemSessionService.completeTurn(systemId);
		caller.join(2000);

		assertThat(sidecars.sentTo(systemId)).anyMatch(line -> line.contains("\"set_model\"") && line.contains("original-model"));
	}

	/**
	 * runSystemTurn's body now runs on a background executor and releases the systemSessionLock
	 * itself (a Semaphore, not a ReentrantLock — see its javadoc) once done, rather than the
	 * calling thread releasing it directly. This proves that switch didn't break the "exactly one
	 * system turn in flight at a time" guarantee: a second call must still block until the first
	 * actually finishes and releases, not just until the first caller's own wait returns.
	 */
	@Test
	void systemTurnsBlockToolsThatWouldNeedAnUnanswerablePermissionPrompt() {
		assertThat(SystemSessionService.systemDisallowedTools("claude"))
				.contains("Bash", "Edit", "Write", "Task", "Agent");
		assertThat(SystemSessionService.systemDisallowedTools("codex")).isEmpty();
	}

	@Test
	void onlyOneSystemTurnRunsAtATimeAcrossBackgroundReleases() throws InterruptedException {
		AtomicReference<String> firstResult = new AtomicReference<>();
		Thread first = Thread.ofVirtual().start(() -> firstResult.set(
				systemSessionService.runSystemTurn("first", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));
		awaitPendingTurn();

		AtomicReference<String> secondResult = new AtomicReference<>();
		Thread second = Thread.ofVirtual().start(() -> secondResult.set(
				systemSessionService.runSystemTurn("second", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));
		awaitPendingTurn();
		assertThat(secondResult.get()).as("second call must still be waiting for the first to release").isNull();

		systemSessionService.onAssistantMessage(systemId, textContent("first answer"));
		systemSessionService.completeTurn(systemId);
		first.join(2000);
		assertThat(firstResult.get()).isEqualTo("first answer");

		awaitPendingTurn();
		systemSessionService.onAssistantMessage(systemId, textContent("second answer"));
		systemSessionService.completeTurn(systemId);
		second.join(2000);
		assertThat(secondResult.get()).isEqualTo("second answer");
	}

	@Test
	void aBackgroundTurnInFlightDoesNotBlockAnInteractiveTurn() throws InterruptedException {
		UUID backgroundId = seedSystemSession(SystemSessionService.BACKGROUND_NAME);
		Thread background = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("slow", SystemTurnLane.BACKGROUND, java.time.Duration.ofSeconds(5)));
		awaitPendingTurn();

		AtomicReference<String> result = new AtomicReference<>();
		Thread interactive = Thread.ofVirtual().start(() -> result.set(
				systemSessionService.runSystemTurn("quick", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));
		awaitPendingTurn();
		systemSessionService.onAssistantMessage(systemId, textContent("interactive answer"));
		systemSessionService.completeTurn(systemId);
		interactive.join(2000);

		assertThat(result.get()).isEqualTo("interactive answer");
		// the background turn is still pending and untouched
		assertThat(sidecars.sentTo(backgroundId)).anyMatch(line -> line.contains("slow"));
		systemSessionService.completeTurn(backgroundId);
		background.join(2000);
	}

	@Test
	void eventsRouteToTheWorkerWhoseTurnMatchesTheSessionId() throws InterruptedException {
		UUID backgroundId = seedSystemSession(SystemSessionService.BACKGROUND_NAME);
		AtomicReference<String> backgroundResult = new AtomicReference<>();
		Thread background = Thread.ofVirtual().start(() -> backgroundResult.set(
				systemSessionService.runSystemTurn("bg", SystemTurnLane.BACKGROUND, java.time.Duration.ofSeconds(5))));
		awaitPendingTurn();

		systemSessionService.onAssistantMessage(systemId, textContent("not for background")); // no interactive turn pending
		systemSessionService.onAssistantMessage(backgroundId, textContent("bg answer"));
		systemSessionService.completeTurn(backgroundId);
		background.join(2000);

		assertThat(backgroundResult.get()).isEqualTo("bg answer");
	}

	@Test
	void aCompletedBackgroundTurnRecyclesToParkedWithNoProviderSessionId() throws InterruptedException {
		UUID backgroundId = seedSystemSession(SystemSessionService.BACKGROUND_NAME);
		Thread background = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("bg", SystemTurnLane.BACKGROUND, java.time.Duration.ofSeconds(5)));
		awaitPendingTurn();
		systemSessionService.completeTurn(backgroundId);
		background.join(2000);
		awaitPendingTurn();

		SessionEntity after = sessions.get(backgroundId);
		assertThat(after.state()).as("background does not re-warm").isEqualTo(SessionState.PARKED);
		assertThat(after.providerSessionId()).isNull();
		assertThat(sidecars.hasLiveHandle(backgroundId)).isFalse();
	}

	@Test
	void aFailedTurnStillRecyclesAndTheInteractiveWorkerRewarms() throws InterruptedException {
		Thread caller = Thread.ofVirtual().start(() -> {
			try {
				systemSessionService.runSystemTurn("prompt", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2));
			} catch (RuntimeException expected) {
				// failure path
			}
		});
		awaitPendingTurn();
		systemSessionService.failTurn(systemId, new IllegalStateException("boom"));
		caller.join(2000);
		awaitPendingTurn();

		SessionEntity after = sessions.get(systemId);
		assertThat(after.providerSessionId()).isNull();
		// re-warm woke the parked row again (fake sidecar never reports ready, so STARTING)
		assertThat(after.state()).isIn(SessionState.PARKED, SessionState.STARTING);
		assertThat(sidecars.hasLiveHandle(systemId)).isEqualTo(after.state() == SessionState.STARTING);
	}

	@Test
	void startupSweepRetiresALegacySystemRowButKeepsTheNamedLaneSessions() {
		UUID legacy = seedSystemSession("system");
		UUID background = seedSystemSession(SystemSessionService.BACKGROUND_NAME);
		SessionHousekeeping housekeeping = new SessionHousekeeping(props, sessions, sidecars, journalPublisher,
				mapper, sessionService);

		housekeeping.retireLegacySystemSessions();

		assertThat(sessions.get(legacy).state()).isEqualTo(SessionState.CLOSED);
		assertThat(sidecars.hasLiveHandle(legacy)).isFalse();
		assertThat(sessions.get(systemId).state()).isEqualTo(SessionState.IDLE);
		assertThat(sessions.get(background).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void aVisualizeTurnInFlightDoesNotBlockInteractiveOrBackgroundTurns() throws InterruptedException {
		UUID visualizeId = seedSystemSession(SystemSessionService.VISUALIZE_NAME);
		Thread visualize = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("diagram", SystemTurnLane.VISUALIZE, java.time.Duration.ofSeconds(5)));
		awaitPendingTurn();
		assertThat(systemSessionService.busy()).as("busy() is about the interactive worker").isFalse();

		AtomicReference<String> result = new AtomicReference<>();
		Thread interactive = Thread.ofVirtual().start(() -> result.set(
				systemSessionService.runSystemTurn("quick", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2))));
		awaitPendingTurn();
		systemSessionService.onAssistantMessage(systemId, textContent("interactive answer"));
		systemSessionService.completeTurn(systemId);
		interactive.join(2000);

		assertThat(result.get()).isEqualTo("interactive answer");
		assertThat(sidecars.sentTo(visualizeId)).anyMatch(line -> line.contains("diagram"));
		systemSessionService.completeTurn(visualizeId);
		visualize.join(2000);
	}

	@Test
	void startupSweepKeepsTheVisualizeLaneSession() {
		UUID visualize = seedSystemSession(SystemSessionService.VISUALIZE_NAME);
		SessionHousekeeping housekeeping = new SessionHousekeeping(props, sessions, sidecars, journalPublisher,
				mapper, sessionService);

		housekeeping.retireLegacySystemSessions();

		assertThat(sessions.get(visualize).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void busyReportsAnInteractiveTurnInFlightOnly() throws InterruptedException {
		assertThat(systemSessionService.busy()).isFalse();
		Thread caller = Thread.ofVirtual().start(() ->
				systemSessionService.runSystemTurn("p", SystemTurnLane.INTERACTIVE, java.time.Duration.ofSeconds(2)));
		awaitPendingTurn();
		assertThat(systemSessionService.busy()).isTrue();

		systemSessionService.completeTurn(systemId);
		caller.join(2000);
		awaitPendingTurn();
		assertThat(systemSessionService.busy()).as("the re-warm is not a task").isFalse();
	}

	/** No hook to await the pendingSystemTurn assignment directly (it's a private field, by design) —
	 * everything before it is non-blocking in-memory work, so a short sleep is a safe, deterministic
	 * enough stand-in for "the caller thread has reached future.get()". */
	private static void awaitPendingTurn() throws InterruptedException {
		Thread.sleep(100);
	}
}
