package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.SessionEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link AutoTitleService#maybeAutoTitle} against in-memory fakes — no real system
 * session, no DB. See docs/plan/phase-10-review-followups.md R3: proves the bail-before-touching-
 * the-journal-further behavior (targeted count/first-of-type queries instead of a full {@code
 * readAfter}) and that the title turn itself now runs via {@link
 * de.pamir.agentic.ui.concurrent.FireAndForget} on its own thread.
 */
class AutoTitleServiceTest {

	private FakeSessionRepository sessions;
	private FakeEventJournal journal;
	private JournalPublisher journalPublisher;
	private final ObjectMapper mapper = new JsonMapper();

	@TempDir
	Path worktreeRoot;

	@BeforeEach
	void setUp() {
		AppProperties props = new AppProperties(worktreeRoot.toString(), worktreeRoot.toString(), "/skills",
				"/memory", 4, "authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		sessions = new FakeSessionRepository();
		journal = new FakeEventJournal(props);
		journalPublisher = new JournalPublisher(journal, new SessionEventBus());
	}

	private SessionEntity seedUntitledSession(UUID id) {
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("b").provider("claude").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath(worktreeRoot.resolve(id.toString()).toString())
				.contextDirs(java.util.List.of()).permissionMode("default")
				.allowedTools(java.util.List.of()).disallowedTools(java.util.List.of())
				.state(SessionState.IDLE).kind("user")
				.build();
		sessions.seed(entity);
		return entity;
	}

	private static SettingsService fakeSettings() {
		return fakeSettings(true);
	}

	private static SettingsService fakeSettings(boolean autoTitle) {
		return new SettingsService(null, null, null) {
			@Override
			public String systemProvider() {
				return "claude";
			}

			@Override
			public boolean autoTitle() {
				return autoTitle;
			}
		};
	}

	/** Records whether it was invoked, and completes a latch once done, instead of running a real system turn. */
	private static final class RecordingSystemTurnClient extends SystemTurnClient {
		final CountDownLatch invoked = new CountDownLatch(1);
		volatile String title = "A Generated Title";

		RecordingSystemTurnClient() {
			super(null, null);
		}

		@Override
		public String text(String prompt, String modelOverride, SystemTurnLane lane, Duration timeout) {
			invoked.countDown();
			return title;
		}
	}

	@Test
	void bailsWithoutTouchingTheJournalFurtherWhenMoreThanOneTurnHasAlreadyCompleted() throws InterruptedException {
		UUID id = UUID.randomUUID();
		seedUntitledSession(id);
		journal.append(id, "user_message", mapper.createObjectNode().put("text", "hi"));
		journal.append(id, "turn_complete", mapper.createObjectNode());
		journal.append(id, "turn_complete", mapper.createObjectNode());
		RecordingSystemTurnClient client = new RecordingSystemTurnClient();
		AutoTitleService service = new AutoTitleService(sessions, journal, fakeSettings(), client, journalPublisher, mapper);

		service.maybeAutoTitle(id);

		assertThat(client.invoked.await(200, TimeUnit.MILLISECONDS)).isFalse();
		assertThat(journal.readAfterCalled).isFalse();
		assertThat(sessions.get(id).name()).isEqualTo("b");
	}

	@Test
	void titlesTheSessionAfterExactlyOneTurnWithoutEverCallingReadAfter() throws InterruptedException {
		UUID id = UUID.randomUUID();
		seedUntitledSession(id);
		journal.append(id, "user_message", mapper.createObjectNode().put("text", "please add a login page"));
		journal.append(id, "turn_complete", mapper.createObjectNode());
		RecordingSystemTurnClient client = new RecordingSystemTurnClient();
		AutoTitleService service = new AutoTitleService(sessions, journal, fakeSettings(), client, journalPublisher, mapper);

		service.maybeAutoTitle(id);

		assertThat(client.invoked.await(2, TimeUnit.SECONDS)).isTrue();
		// the rename itself happens on the FireAndForget thread right after; give it a moment
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (sessions.get(id).name().equals("b") && System.nanoTime() < deadline) {
			Thread.onSpinWait();
		}
		assertThat(sessions.get(id).name()).isEqualTo(client.title);
		assertThat(journal.readAfterCalled).isFalse();
	}

	@Test
	void rejectsAnAnswerThatIsNotATitle() {
		assertThat(AutoTitleService.looksLikeATitle("Add login page")).isTrue();
		assertThat(AutoTitleService.looksLikeATitle("No Explore subagent available; sleep started in background (ID: bl6rqb1ao).")).isFalse();
		assertThat(AutoTitleService.looksLikeATitle("Done.")).isFalse();
		assertThat(AutoTitleService.looksLikeATitle("line one\nline two")).isFalse();
		assertThat(AutoTitleService.looksLikeATitle("")).isFalse();
	}

	@Test
	void stripsQuotesAndATrailingPeriodBeforeJudging() {
		assertThat(AutoTitleService.normalizeTitle("\"Add login page\"")).isEqualTo("Add login page");
		assertThat(AutoTitleService.normalizeTitle("`Fix flaky date parsing`.")).isEqualTo("Fix flaky date parsing");
		assertThat(AutoTitleService.normalizeTitle("  Add CSV export. ")).isEqualTo("Add CSV export");
		assertThat(AutoTitleService.normalizeTitle("Wait...")).isEqualTo("Wait...");
		assertThat(AutoTitleService.looksLikeATitle(AutoTitleService.normalizeTitle("\"Done.\""))).isTrue();
	}

	@Test
	void doesNothingWhenAutoTitleIsSwitchedOff() throws InterruptedException {
		UUID id = UUID.randomUUID();
		seedUntitledSession(id);
		journal.append(id, "user_message", mapper.createObjectNode().put("text", "please add a login page"));
		journal.append(id, "turn_complete", mapper.createObjectNode());
		RecordingSystemTurnClient client = new RecordingSystemTurnClient();
		AutoTitleService service = new AutoTitleService(sessions, journal, fakeSettings(false), client, journalPublisher, mapper);

		service.maybeAutoTitle(id);

		assertThat(client.invoked.await(200, TimeUnit.MILLISECONDS)).isFalse();
		assertThat(sessions.get(id).name()).isEqualTo("b");
	}

	@Test
	void doesNothingForASessionAlreadyRenamed() {
		UUID id = UUID.randomUUID();
		SessionEntity entity = seedUntitledSession(id).toBuilder().name("already renamed").build();
		sessions.seed(entity);
		RecordingSystemTurnClient client = new RecordingSystemTurnClient();
		AutoTitleService service = new AutoTitleService(sessions, journal, fakeSettings(), client, journalPublisher, mapper);

		service.maybeAutoTitle(id);

		assertThat(client.invoked.getCount()).isEqualTo(1);
		assertThat(journal.readAfterCalled).isFalse();
	}
}
