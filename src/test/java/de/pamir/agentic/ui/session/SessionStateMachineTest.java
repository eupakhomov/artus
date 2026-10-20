package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.discovery.ServiceDiscoveryRequested;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.SessionEventBus;
import de.pamir.agentic.ui.memory.ReflectionRequested;
import de.pamir.agentic.ui.provision.AssetProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives SessionService's state machine directly — sendUserMessage routing per state,
 * becomeIdleAndDrainQueue's peek-not-pop/budget-hold semantics, close's dirty-mode branches,
 * resume's CRASHED-only guard, and onSidecarExit's crash-vs-shutdownRequested branches — against
 * in-memory fakes (FakeSessionRepository/FakeSidecarManager/FakeEventJournal/
 * FakeGitWorktreeService, no Mockito, no DB, no real OS process). See
 * docs/plan/phase-9-production-hardening.md T2.
 */
class SessionStateMachineTest {

	private final ObjectMapper mapper = new JsonMapper();

	private FakeSessionRepository sessions;
	private FakeSidecarManager sidecars;
	private FakeEventJournal journal;
	private FakeGitWorktreeService worktrees;
	private List<Object> publishedEvents;
	private SessionService sessionService;

	@TempDir
	Path worktreeRoot;

	@BeforeEach
	void setUp() {
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SettingsService settings = fakeSettings(false, false);
		sessions = new FakeSessionRepository();
		sidecars = new FakeSidecarManager();
		journal = new FakeEventJournal(props);
		worktrees = new FakeGitWorktreeService();
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SessionConfigFactory configFactory = new SessionConfigFactory(props, settings, null, mapper, null, 8080, null, worktrees, null, null, null);
		SystemSessionService systemSessionService =
				new SystemSessionService(props, settings, sessions, configFactory, journalPublisher, mapper, null);
		publishedEvents = new ArrayList<>();
		sessionService = new SessionService(props, settings, sessions, worktrees, null, null, sidecars, journal,
				journalPublisher, mapper, publishedEvents::add, configFactory, systemSessionService, null, null, null, null);
	}

	private static SettingsService fakeSettings(boolean memoryEnabled, boolean serviceDiscoveryEnabled) {
		Settings fixed = new Settings(false, "", "", "", true, true, 180, "", "", false, true, 60, "claude", "", "",
				memoryEnabled, false, "cheap", 5, 0, true, serviceDiscoveryEnabled, 14, "cheap", 70, "", "uv", "", "", "none", "");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private SessionEntity session(SessionState state) {
		SessionEntity entity = SessionEntity.builder()
				.id(UUID.randomUUID()).name("s").provider("claude").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath(worktreeRoot.resolve(UUID.randomUUID().toString()).toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(state).kind("user")
				.build();
		sessions.seed(entity);
		return entity;
	}

	// ------------------------------------------------------------------ sendUserMessage

	@Test
	void sendUserMessageDispatchesImmediatelyWhenIdleWithALiveHandle() {
		SessionEntity s = session(SessionState.IDLE);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("\"user_message\"") && line.contains("hello"));
		assertThat(sessions.queued(s.id())).isEmpty();
	}

	@Test
	void sendUserMessageRejectsWhenIdleButTheSidecarHandleIsDead() {
		SessionEntity s = session(SessionState.IDLE);
		// no spawn() call: hasLiveHandle() is false

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not accept messages");
	}

	@Test
	void sendUserMessageRejectsWhenIdleButBudgetIsExhausted() {
		SessionEntity s = session(SessionState.IDLE).toBuilder().costBudgetUsd(new BigDecimal("1.00")).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), new BigDecimal("1.00"));
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("budget exhausted");
		assertThat(sidecars.sentTo(s.id())).isEmpty();
	}

	@Test
	void sendUserMessageEnqueuesAndWakesAParkedSession() {
		SessionEntity s = session(SessionState.PARKED);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
		assertThat(sidecars.hasLiveHandle(s.id())).isTrue(); // wake() spawned a fresh handle
	}

	@Test
	void sendUserMessageRejectsAParkedSessionWithAnExhaustedBudget() {
		SessionEntity s = session(SessionState.PARKED).toBuilder().costBudgetUsd(BigDecimal.ZERO).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), BigDecimal.ZERO);

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(sessions.queued(s.id())).isEmpty();
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.PARKED);
	}

	@Test
	void sendUserMessageJustEnqueuesWhileAnotherTurnIsAlreadyInFlight() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
	}

	@Test
	void sendUserMessageJustEnqueuesWhileWaitingOnAPermissionPrompt() {
		SessionEntity s = session(SessionState.WAITING_INPUT);

		sessionService.sendUserMessage(s.id(), "hello");

		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("hello");
	}

	@Test
	void sendUserMessageRejectsATerminalSession() {
		SessionEntity s = session(SessionState.CLOSED);

		assertThatThrownBy(() -> sessionService.sendUserMessage(s.id(), "hello"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not accept messages");
	}

	// ------------------------------------------------------------------ compact

	@Test
	void compactSendsDirectlyWhenIdleWithALiveHandle() {
		SessionEntity s = session(SessionState.IDLE);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});

		sessionService.compact(s.id());

		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("\"compact\""));
	}

	@Test
	void compactWakesAParkedSessionAndSendsOnceItReportsReady() {
		SessionEntity s = session(SessionState.PARKED);

		sessionService.compact(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
		assertThat(sidecars.sentTo(s.id())).noneMatch(line -> line.contains("\"compact\""));

		sessionService.becomeIdleAndDrainQueue(s.id()); // simulates the woken sidecar's "ready"->STARTING drain

		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("\"compact\""));
	}

	@Test
	void compactRejectsARunningSession() {
		SessionEntity s = session(SessionState.RUNNING);

		assertThatThrownBy(() -> sessionService.compact(s.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cannot be compacted");
	}

	@Test
	void compactRejectsAnIdleSessionWithADeadHandle() {
		SessionEntity s = session(SessionState.IDLE);
		// no spawn() call: hasLiveHandle() is false

		assertThatThrownBy(() -> sessionService.compact(s.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("cannot be compacted");
	}

	// ------------------------------------------------------------------ becomeIdleAndDrainQueue

	@Test
	void becomeIdleAndDrainQueueGoesIdleWithAnEmptyQueue() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void becomeIdleAndDrainQueueDispatchesTheHeadOfTheQueueAndRemovesItOnSuccess() {
		SessionEntity s = session(SessionState.RUNNING);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING); // dispatch() re-enters RUNNING
		assertThat(sessions.queued(s.id())).isEmpty();
		assertThat(sidecars.sentTo(s.id())).anyMatch(line -> line.contains("queued message"));
	}

	@Test
	void becomeIdleAndDrainQueueLeavesTheMessageQueuedWhenSendFails() {
		SessionEntity s = session(SessionState.RUNNING);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});
		sidecars.brokenHandles.add(s.id()); // simulates a dead/broken sidecar handle
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE); // never reached dispatch()'s RUNNING
		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("queued message");
	}

	@Test
	void becomeIdleAndDrainQueueHoldsAQueueWhenTheBudgetIsExhausted() {
		SessionEntity s = session(SessionState.RUNNING).toBuilder().costBudgetUsd(new BigDecimal("5")).build();
		sessions.seed(s);
		journal.setCostToDate(s.id(), new BigDecimal("5"));
		sessions.enqueue(s.id(), "queued message");

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
		assertThat(sessions.queued(s.id())).extracting("text").containsExactly("queued message");
	}

	/**
	 * Reproduces a real race: close() blocks on sidecars.terminate() for up to ~7s while the
	 * sidecar finishes its in-flight turn before honoring the shutdown, so a "turn_complete"
	 * (which calls becomeIdleAndDrainQueue unconditionally) can arrive for a session that
	 * close() already transitioned to CLOSED — and had its worktree deleted — moments earlier.
	 * Without the isTerminal guard this stomped the state back to IDLE with no live sidecar
	 * handle, permanently breaking every future message to that session (the singleton system
	 * session hitting this is what a user sees as "session is IDLE and does not accept
	 * messages" on every ticket import/browse afterward).
	 */
	@Test
	void becomeIdleAndDrainQueueDoesNotResurrectASessionClosedWhileItsTurnWasInFlight() {
		SessionEntity s = session(SessionState.CLOSED);

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void becomeIdleAndDrainQueueDoesNotResurrectACrashedSession() {
		SessionEntity s = session(SessionState.CRASHED);

		sessionService.becomeIdleAndDrainQueue(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CRASHED);
	}

	// ------------------------------------------------------------------ crash reason

	@Test
	void aBenignCrashReasonIsStoredAndJournaledAndAnyLaterTransitionClearsIt() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.transition(s.id(), SessionState.CRASHED, SessionService.CRASH_BACKEND_RESTART);

		assertThat(sessions.get(s.id()).crashReason()).isEqualTo("backend_restart");
		var crashed = journal.journaled(s.id()).getLast();
		assertThat(crashed.type()).isEqualTo("state_changed");
		assertThat(crashed.payload().path("reason").asString()).isEqualTo("backend_restart");

		sessionService.transition(s.id(), SessionState.STARTING);
		assertThat(sessions.get(s.id()).crashReason()).isNull();
		assertThat(journal.journaled(s.id()).getLast().payload().has("reason")).isFalse();
	}

	@Test
	void aPlainCrashHasNoReason() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.transition(s.id(), SessionState.CRASHED);

		assertThat(sessions.get(s.id()).crashReason()).isNull();
		assertThat(journal.journaled(s.id()).getLast().payload().has("reason")).isFalse();
	}

	// ------------------------------------------------------------------ resume

	@Test
	void resumeRejectsANonCrashedSession() {
		SessionEntity s = session(SessionState.IDLE);

		assertThatThrownBy(() -> sessionService.resume(s.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("only CRASHED sessions can be resumed");
	}

	@Test
	void resumeRespawnsACrashedSession() {
		SessionEntity s = session(SessionState.CRASHED);

		sessionService.resume(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
		assertThat(sidecars.hasLiveHandle(s.id())).isTrue();
	}

	@Test
	void resumeRejectsARegularSessionAtTheConcurrencyLimit() {
		for (int i = 0; i < 4; i++) {
			session(SessionState.IDLE); // props' maxSessions is 4 — this fills it
		}
		SessionEntity s = session(SessionState.CRASHED);

		assertThatThrownBy(() -> sessionService.resume(s.id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("max concurrent sessions reached");
	}

	/**
	 * The system session is a single, always-needed, backend-managed resource (ticket import,
	 * commit-message drafting, service discovery, ...), not a slot competing with user sessions
	 * — reviving it must not fail just because the user already has the configured number of
	 * regular sessions open (the normal case, not an edge case).
	 */
	@Test
	void resumeExemptsTheSystemSessionFromTheConcurrencyLimit() {
		for (int i = 0; i < 4; i++) {
			session(SessionState.IDLE);
		}
		SessionEntity s = SessionEntity.builder()
				.id(UUID.randomUUID()).name("system").provider("claude").repoPath("(system)")
				.branch("(system)").baseBranch("(system)")
				.worktreePath(worktreeRoot.resolve(UUID.randomUUID().toString()).toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.CRASHED).kind("system")
				.build();
		sessions.seed(s);

		sessionService.resume(s.id());

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.STARTING);
	}

	// ------------------------------------------------------------------ close

	@Test
	void closeIsANoOpWhenAlreadyClosed() {
		SessionEntity s = session(SessionState.CLOSED);

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	/** A close that died part-way leaves CLOSING behind; closing again must finish it, not no-op. */
	@Test
	void closeRetriesASessionStuckInClosing() {
		SessionEntity s = session(SessionState.CLOSING);

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeRetryOfClosingSkipsTheDirtyCheckBecauseTheWorktreeIsHalfGone() {
		SessionEntity s = session(SessionState.CLOSING);
		worktrees.setDirtyFiles(List.of(" M a.txt"));

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeThrowsOnADirtyWorktreeByDefault() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		assertThatThrownBy(() -> sessionService.close(s.id(), null, null))
				.isInstanceOf(DirtyWorktreeException.class);
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
	}

	@Test
	void closeRejectsAnUnknownDirtyMode() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		assertThatThrownBy(() -> sessionService.close(s.id(), "bogus", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown dirty mode");
	}

	@Test
	void closeDiscardsADirtyWorktreeWhenAskedTo() {
		SessionEntity s = session(SessionState.IDLE);
		worktrees.setDirtyFiles(List.of("a.txt"));

		sessionService.close(s.id(), "discard", null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeSucceedsDirectlyOnACleanWorktree() {
		SessionEntity s = session(SessionState.IDLE);

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeReleasesTheSessionLockAndTheJournalNoLongerNeedsToBeQueried() {
		SessionEntity s = session(SessionState.IDLE);

		sessionService.close(s.id(), null, null);

		assertThat(sessionService.hasLock(s.id())).isFalse();
		// FakeEventJournal doesn't track a real in-memory session map to assert against directly,
		// but readAfter (unaffected by release — see EventJournal.release's javadoc) must still
		// work for a released session's event history, same as it does for a never-seen one.
		assertThat(journal.readAfter(s.id(), 0)).isNotNull();
	}

	@Test
	void closePublishesReflectionAndServiceDiscoveryEventsWhenEnabled() {
		SessionConfigFactory configFactory = new SessionConfigFactory(
				new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills", "/memory", 4,
						"authtoken", "", "", "logs", 30, 65536, 1048576, Map.of()),
				fakeSettings(false, true), null, mapper, null, 8080, null, worktrees, null, null, null);
		SettingsService settings = fakeSettings(false, true);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SystemSessionService systemSessionService =
				new SystemSessionService(null, settings, sessions, configFactory, journalPublisher, mapper, null);
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SessionService withDiscovery = new SessionService(props, settings, sessions, worktrees, null, null, sidecars,
				journal, journalPublisher, mapper, publishedEvents::add, configFactory, systemSessionService, null, null, null, null);
		SessionEntity s = session(SessionState.IDLE).toBuilder().reflectionEnabled(true).build();
		sessions.seed(s);

		withDiscovery.close(s.id(), null, null);

		assertThat(publishedEvents).hasAtLeastOneElementOfType(ReflectionRequested.class);
		assertThat(publishedEvents).hasAtLeastOneElementOfType(ServiceDiscoveryRequested.class);
	}

	/** docs/plan/phase-15-review-sessions.md proposal 10: a review session never commits, even to close. */
	@Test
	void closeRefusesCommitDirtyModeOnAReviewSession() {
		SessionEntity s = session(SessionState.IDLE).toBuilder().sessionType("review").build();
		sessions.seed(s);
		worktrees.setDirtyFiles(List.of("scratch-notes.txt"));

		assertThatThrownBy(() -> sessionService.close(s.id(), "commit", null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("review sessions cannot commit");
		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.IDLE);
	}

	/** stash/discard stay available for a dirty review worktree (scratch notes, build artifacts). */
	@Test
	void closeStillAllowsStashOnADirtyReviewSession() {
		SessionEntity s = session(SessionState.IDLE).toBuilder().sessionType("review").build();
		sessions.seed(s);
		worktrees.setDirtyFiles(List.of("scratch-notes.txt"));

		sessionService.close(s.id(), "stash", null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void closeTerminatesASystemSessionAndSkipsTheDirtyCheck() {
		SessionEntity s = SessionEntity.builder()
				.id(UUID.randomUUID()).name("system").provider("claude").repoPath("(system)")
				.branch("(system)").baseBranch("(system)").worktreePath(worktreeRoot.resolve("nonexistent").toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("system")
				.build();
		sessions.seed(s);
		worktrees.setDirtyFiles(List.of("would normally block")); // must be ignored for kind=system

		sessionService.close(s.id(), null, null);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	// ------------------------------------------------------------------ create (servicePath, Step 3)

	private static ProviderCapabilities fullCapabilities() {
		return new ProviderCapabilities(List.of("default", "acceptEdits", "plan", "bypassPermissions"),
				true, true, true, true, true, true, true, true, true, true, true, List.of(), true, true, true,
				"claude-code");
	}

	/**
	 * Full docs/plan/phase-11-monorepo.md Step 3 DoD: a session created with {@code servicePath} =
	 * {@code <repo>/packages/foo} gets assets provisioned at the worktree-relative service cwd
	 * (decision A) and the matching {@code info/exclude} lines — a real {@link SessionService#create}
	 * call, not a directly-seeded entity like every other test above.
	 */
	@Test
	void createOnAServiceSubfolderProvisionsAssetsAtTheServiceCwdAndWritesTheMatchingExclude() throws Exception {
		String repo = "/repo";
		String servicePath = repo + "/packages/foo";
		worktrees.setRepoRootOfResult(Path.of(repo));
		worktrees.setKnownServices(List.of(new GitWorktreeService.ServiceInfo("packages/foo", servicePath, repo)));

		Settings fixedSettings = new Settings(false, "", "/eco", "packages/*,services/*,apps/*,libs/*", true, true, 180,
				"", "", false, true, 60, "claude", "", "", false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", "", "none", "");
		SettingsService settingsWithEcosystem = new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixedSettings;
			}
		};
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		ProviderCatalog catalog = ProviderCatalog.fixedForTest(Map.of("claude", fullCapabilities()));
		SessionConfigFactory configFactory =
				new SessionConfigFactory(props, settingsWithEcosystem, null, mapper, null, 8080, catalog, worktrees, null, null, null);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SystemSessionService systemSessionService =
				new SystemSessionService(props, settingsWithEcosystem, sessions, configFactory, journalPublisher, mapper, null);

		AtomicReference<Path> capturedAssetsRoot = new AtomicReference<>();
		AssetProvisioningService fakeAssets = new AssetProvisioningService(null, null, mapper) {
			@Override
			public List<Warning> provision(Path assetsRoot, JsonNode skillSources, JsonNode agentSources) {
				capturedAssetsRoot.set(assetsRoot);
				return List.of();
			}
		};
		GitCommandRunner fakeGit = new GitCommandRunner() {
			@Override
			public GitResult run(Path workingDir, String... args) {
				return new GitResult(0, ".git/info/exclude", "");
			}
		};
		SessionService withServiceSubfolder = new SessionService(props, settingsWithEcosystem, sessions, worktrees,
				fakeGit, fakeAssets, sidecars, journal, journalPublisher, mapper, publishedEvents::add, configFactory,
				systemSessionService, null, catalog, null, null);

		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "branch", "main", null, null, mapper.createObjectNode(), Map.of(), false)
				.withServicePath(servicePath);

		SessionEntity created = withServiceSubfolder.create(options);

		Path expectedWorktree = worktreeRoot.resolve(created.id().toString());
		Path expectedAssetsRoot = expectedWorktree.resolve("packages/foo");
		assertThat(created.repoPath()).isEqualTo(repo);
		assertThat(created.servicePath()).isEqualTo(servicePath);
		assertThat(created.cwdPath()).isEqualTo(expectedAssetsRoot.toString());
		assertThat(capturedAssetsRoot.get()).isEqualTo(expectedAssetsRoot);

		String exclude = Files.readString(expectedWorktree.resolve(".git/info/exclude"));
		assertThat(exclude).contains("packages/foo/.claude/skills/")
				.contains("packages/foo/.claude/agents/")
				.contains("packages/foo/.serena/")
				.contains(".artus.pid");
		// the pid line is never prefixed — the pid file always lives at the worktree root
		assertThat(exclude).doesNotContain("packages/foo/.artus.pid");
	}

	// ------------------------------------------------------------------ create (sessionType, phase 15)

	/**
	 * A real {@link SessionService#create} call needs a real {@link ProviderCatalog} (the default
	 * {@code sessionService} from {@link #setUp} is built with a null one, since none of the other
	 * tests in this class drive {@code create()} against it directly — see the servicePath test
	 * above, which builds its own for the same reason) and a {@link GitCommandRunner} fake so
	 * {@code excludeProvisionedAssets} can write its info/exclude line without a real git worktree.
	 */
	private SessionService reviewCapableSessionService() {
		ProviderCatalog catalog = ProviderCatalog.fixedForTest(Map.of("claude", fullCapabilities()));
		SessionConfigFactory configFactory = new SessionConfigFactory(
				new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills", "/memory", 4,
						"authtoken", "", "", "logs", 30, 65536, 1048576, Map.of()),
				fakeSettings(false, false), null, mapper, null, 8080, catalog, worktrees, null, null, null);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		SystemSessionService systemSessionService = new SystemSessionService(
				null, fakeSettings(false, false), sessions, configFactory, journalPublisher, mapper, null);
		GitCommandRunner fakeGit = new GitCommandRunner() {
			@Override
			public GitResult run(Path workingDir, String... args) {
				return new GitResult(0, ".git/info/exclude", "");
			}
		};
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		return new SessionService(props, fakeSettings(false, false), sessions, worktrees, fakeGit,
				new AssetProvisioningService(null, null, mapper), sidecars, journal, journalPublisher, mapper,
				publishedEvents::add, configFactory, systemSessionService, null, catalog, null, null);
	}

	@Test
	void createOnAReviewSessionUsesTheDetachedReviewWorktreeNotOrdinaryCreateWorktree() {
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"review: feat-x", "feat-x", "main", System.getProperty("user.dir"), null,
				mapper.createObjectNode(), Map.of(), false).withSessionType("review");

		SessionEntity created = reviewCapableSessionService().create(options);

		assertThat(created.sessionType()).isEqualTo("review");
		assertThat(worktrees.lastReviewWorktreeBranch()).isEqualTo("feat-x");
		assertThat(worktrees.createWorktreeCalled()).isFalse();
	}

	@Test
	void createOnADevelopmentSessionIsByteIdenticalUsingOrdinaryCreateWorktree() {
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"s", "feat-x", "main", System.getProperty("user.dir"), null,
				mapper.createObjectNode(), Map.of(), false);

		SessionEntity created = reviewCapableSessionService().create(options);

		assertThat(created.sessionType()).isEqualTo("development");
		assertThat(worktrees.createWorktreeCalled()).isTrue();
		assertThat(worktrees.lastReviewWorktreeBranch()).isNull();
	}

	@Test
	void createOnAReviewSessionAttachesThePrWhenOneWasPickedAtCreation() {
		SessionService.CreateOptions options = new SessionService.CreateOptions(
				"review: feat-x", "feat-x", "main", System.getProperty("user.dir"), null,
				mapper.createObjectNode(), Map.of(), false)
				.withSessionType("review").withPrUrl("https://github.com/acme/widget/pull/7");
		SessionService service = reviewCapableSessionService();

		SessionEntity created = service.create(options);

		assertThat(created.prUrl()).isEqualTo("https://github.com/acme/widget/pull/7");
	}

	// ------------------------------------------------------------------ onSidecarExit

	@Test
	void onSidecarExitIgnoresAnExpectedShutdown() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.onSidecarExit(s.id(), 0, List.of(), true);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
	}

	@Test
	void onSidecarExitIsANoOpForATerminalState() {
		SessionEntity s = session(SessionState.CLOSED);

		sessionService.onSidecarExit(s.id(), 1, List.of(), false);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void onSidecarExitMarksAnUnexpectedExitAsCrashed() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.onSidecarExit(s.id(), 1, List.of("stack trace line"), false);

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.CRASHED);
	}

	@Test
	void aTurnTheProviderStartsOnItsOwnMovesAnIdleSessionToRunning() {
		// phase 25: a finished background task makes the CLI open a turn with no user message
		SessionEntity s = session(SessionState.IDLE);

		sessionService.onSidecarEvent(s.id(), mapper.readTree(
				"{\"type\":\"system_init\",\"providerSessionId\":\"p\",\"model\":\"claude-sonnet-5\"}"));

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
	}

	@Test
	void aChildStoppingForApprovalOrCrashingLeavesANoteOnItsParent() {
		SessionEntity parent = session(SessionState.IDLE);
		SessionEntity child = SessionEntity.builder()
				.id(UUID.randomUUID()).name("svc: child").provider("claude").repoPath("/repo")
				.branch("child").baseBranch("main").worktreePath(worktreeRoot.resolve(UUID.randomUUID().toString()).toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.RUNNING).kind("user").parentSessionId(parent.id())
				.build();
		sessions.seed(child);

		sessionService.transition(child.id(), SessionState.WAITING_INPUT);
		sessionService.transition(child.id(), SessionState.IDLE);

		assertThat(journal.countEventType(parent.id(), "child_attention")).isEqualTo(1);
		JsonNode note = journal.firstEventOfType(parent.id(), "child_attention").orElseThrow().payload();
		assertThat(note.path("state").asText()).isEqualTo("WAITING_INPUT");
		assertThat(note.path("childName").asText()).isEqualTo("svc: child");
	}

	@Test
	void aChildAskingSeveralTimesInOneTurnIsOneNoteAndAReportedChildNone() {
		SessionEntity parent = session(SessionState.IDLE);
		SessionEntity child = SessionEntity.builder()
				.id(UUID.randomUUID()).name("svc: child").provider("claude").repoPath("/repo")
				.branch("child").baseBranch("main").worktreePath(worktreeRoot.resolve(UUID.randomUUID().toString()).toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.RUNNING).kind("user").parentSessionId(parent.id())
				.build();
		sessions.seed(child);

		// two approvals within one turn: one stall
		sessionService.transition(child.id(), SessionState.WAITING_INPUT);
		sessionService.transition(child.id(), SessionState.RUNNING);
		sessionService.transition(child.id(), SessionState.WAITING_INPUT);
		assertThat(journal.countEventType(parent.id(), "child_attention")).isEqualTo(1);
		// crashing is news even right after a prompt
		sessionService.transition(child.id(), SessionState.CRASHED);
		assertThat(journal.countEventType(parent.id(), "child_attention")).isEqualTo(2);
		// a new turn that stops again is a new stall
		sessionService.transition(child.id(), SessionState.IDLE);
		sessionService.transition(child.id(), SessionState.RUNNING);
		sessionService.transition(child.id(), SessionState.WAITING_INPUT);
		assertThat(journal.countEventType(parent.id(), "child_attention")).isEqualTo(3);
		// once it has reported, the parent is no longer waiting on it
		journal.append(child.id(), "child_reported", mapper.createObjectNode());
		sessionService.transition(child.id(), SessionState.IDLE);
		sessionService.transition(child.id(), SessionState.RUNNING);
		sessionService.transition(child.id(), SessionState.WAITING_INPUT);
		assertThat(journal.countEventType(parent.id(), "child_attention")).isEqualTo(3);
	}

	@Test
	void aHiddenSessionComesBackWhenItNeedsTheUser() {
		SessionEntity s = session(SessionState.RUNNING);

		sessionService.setHidden(s.id(), true);
		assertThat(sessions.get(s.id()).hidden()).isTrue();
		assertThat(journal.countEventType(s.id(), "visibility_changed")).isEqualTo(1);

		sessionService.setHidden(s.id(), true); // no-op: nothing journaled twice
		assertThat(journal.countEventType(s.id(), "visibility_changed")).isEqualTo(1);

		sessionService.transition(s.id(), SessionState.WAITING_INPUT);
		assertThat(sessions.get(s.id()).hidden()).isFalse();
		assertThat(journal.countEventType(s.id(), "visibility_changed")).isEqualTo(2);
	}

	@Test
	void backgroundTasksAreTrackedFromTheLatestSetAndDieWithTheSidecar() {
		SessionEntity s = session(SessionState.IDLE);

		sessionService.onSidecarEvent(s.id(), mapper.readTree(
				"{\"type\":\"background_tasks\",\"tasks\":[{\"taskId\":\"k\",\"kind\":\"local_bash\",\"description\":\"sleep\"}]}"));
		assertThat(sessionService.hasBackgroundTasks(s.id())).isTrue();

		sessionService.onSidecarEvent(s.id(), mapper.readTree("{\"type\":\"background_tasks\",\"tasks\":[]}"));
		assertThat(sessionService.hasBackgroundTasks(s.id())).isFalse();

		sessionService.onSidecarEvent(s.id(), mapper.readTree(
				"{\"type\":\"background_tasks\",\"tasks\":[{\"taskId\":\"k\",\"kind\":\"local_bash\",\"description\":\"sleep\"}]}"));
		sessionService.onSidecarExit(s.id(), 0, List.of(), true);
		assertThat(sessionService.hasBackgroundTasks(s.id())).isFalse();
	}

	@Test
	void onSidecarExitIsANoOpWhenTheSessionIsAlreadyGone() {
		UUID unknown = UUID.randomUUID();

		sessionService.onSidecarExit(unknown, 1, List.of(), false); // must not throw
	}

	// ------------------------------------------------------------------ pending permissions (phase 18)

	private SessionEntity runningWithSidecar() {
		SessionEntity s = session(SessionState.RUNNING);
		sidecars.spawn(s, null, false, null, e -> {
		}, (h, c) -> {
		});
		return s;
	}

	private void permissionRequest(SessionEntity s, String requestId) {
		sessionService.onSidecarEvent(s.id(), mapper.readTree("{\"type\":\"permission_request\",\"requestId\":\"" + requestId
				+ "\",\"toolName\":\"Bash\",\"input\":{\"command\":\"ls\"}}"));
	}

	private tools.jackson.databind.node.ObjectNode decision(String requestId, String behavior) {
		return mapper.createObjectNode().put("type", "permission_response").put("requestId", requestId)
				.put("behavior", behavior).put("source", "slack").put("slackUser", "U1");
	}

	@Test
	void aPermissionRequestIsTrackedAsPendingAndMovesTheSessionToWaiting() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.WAITING_INPUT);
		assertThat(sessionService.pendingPermissions().peek(s.id(), "p1")).isPresent();
		assertThat(sessionService.pendingPermissions().peek(s.id(), "p1").get().toolName()).isEqualTo("Bash");
	}

	@Test
	void theFirstDecisionWinsAndTheSecondIsToldItIsTooLate() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");

		assertThat(sessionService.respondPermissionIfPending(s.id(), decision("p1", "allow"))).isTrue();
		assertThat(sessionService.respondPermissionIfPending(s.id(), decision("p1", "deny"))).isFalse();

		assertThat(sessions.get(s.id()).state()).isEqualTo(SessionState.RUNNING);
		assertThat(sidecars.sentTo(s.id()).stream().filter(l -> l.contains("permission_response"))).hasSize(1);
	}

	@Test
	void theAuditFieldsAreJournaledButNeverSentToTheSidecar() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");

		sessionService.respondPermissionIfPending(s.id(), decision("p1", "allow"));

		String sent = sidecars.sentTo(s.id()).stream().filter(l -> l.contains("permission_response")).findFirst().orElseThrow();
		assertThat(sent).contains("\"requestId\":\"p1\"").contains("\"behavior\":\"allow\"")
				.doesNotContain("slackUser").doesNotContain("source");
		JsonNode journaled = journal.firstEventOfType(s.id(), "permission_response").orElseThrow().payload();
		assertThat(journaled.path("slackUser").asText()).isEqualTo("U1");
		assertThat(journaled.path("source").asText()).isEqualTo("slack");
	}

	@Test
	void aDashboardDecisionSettlesTheRequestSoALaterSlackClickIsRejected() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");

		sessionService.respondPermission(s.id(), mapper.createObjectNode().put("type", "permission_response")
				.put("requestId", "p1").put("behavior", "deny"));

		assertThat(sessionService.pendingPermissions().peek(s.id(), "p1")).isEmpty();
		assertThat(sessionService.respondPermissionIfPending(s.id(), decision("p1", "allow"))).isFalse();
		assertThat(sidecars.sentTo(s.id()).stream().filter(l -> l.contains("permission_response"))).hasSize(1);
	}

	@Test
	void severalRequestsArePendingIndependently() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");
		permissionRequest(s, "p2");

		assertThat(sessionService.respondPermissionIfPending(s.id(), decision("p2", "allow"))).isTrue();

		assertThat(sessionService.pendingPermissions().list(s.id())).extracting(p -> p.requestId()).containsExactly("p1");
	}

	@Test
	void aTurnEndingOrTheSidecarExitingClearsWhatWasPending() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");
		try {
			sessionService.onSidecarEvent(s.id(), mapper.readTree("{\"type\":\"turn_complete\",\"stopReason\":\"interrupted\"}"));
		} catch (NullPointerException noProviderCatalogInThisHarness) {
			// the cost-estimation step that follows the code under test needs a ProviderCatalog this harness doesn't wire
		}
		assertThat(sessionService.pendingPermissions().list(s.id())).isEmpty();

		permissionRequest(s, "p2");
		sessionService.onSidecarExit(s.id(), 1, List.of(), false);
		assertThat(sessionService.pendingPermissions().list(s.id())).isEmpty();
		assertThat(sessionService.respondPermissionIfPending(s.id(), decision("p2", "allow"))).isFalse();
	}

	@Test
	void aDecisionThatCannotBeDeliveredLeavesTheRequestPending() {
		SessionEntity s = runningWithSidecar();
		permissionRequest(s, "p1");
		sidecars.brokenHandles.add(s.id());

		assertThatThrownBy(() -> sessionService.respondPermissionIfPending(s.id(), decision("p1", "allow")))
				.isInstanceOf(IllegalStateException.class);

		assertThat(sessionService.pendingPermissions().peek(s.id(), "p1")).isPresent();
	}

	@Test
	void externalInputIsJournaledAsAnAuditEvent() {
		SessionEntity s = runningWithSidecar();

		sessionService.recordExternalInput(s.id(), mapper.createObjectNode().put("source", "slack").put("slackUser", "U1").put("kind", "stop"));

		assertThat(journal.firstEventOfType(s.id(), "slack_input").orElseThrow().payload().path("kind").asText()).isEqualTo("stop");
	}
}
