package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.SessionEventBus;
import de.pamir.agentic.ui.provision.AssetProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrchestrationMcpTools#spawnChildSession}/{@code report_result} against a real monorepo
 * fixture (real {@code git init}/worktrees under {@code @TempDir}) — proves {@code repoPath}
 * resolves to the monorepo root (not the package subfolder) and the child's report names the
 * package, not the repo. See docs/plan/phase-11-monorepo.md Step 5.
 */
class OrchestrationMcpToolsTest {

	private static void initRepo(Path dir, GitCommandRunner git) throws IOException {
		Files.createDirectories(dir);
		git.runOrThrow(dir, "init", "-q");
		git.runOrThrow(dir, "config", "user.email", "test@test.local");
		git.runOrThrow(dir, "config", "user.name", "Test");
	}

	private static void commit(Path dir, String message, GitCommandRunner git) {
		git.runOrThrow(dir, "add", "-A");
		git.runOrThrow(dir, "commit", "-q", "--allow-empty", "-m", message);
	}

	private static SettingsService fakeSettings(String ecosystemRoot) {
		Settings fixed = new Settings(false, "", ecosystemRoot, "packages/*,services/*,apps/*,libs/*", true, true, 180,
				"", "", false, true, 60, "claude", "", "", false, false, "cheap", 5, 0, true, false, 14, "cheap", 70, "", "uv", "", "", "none", "");
		return new SettingsService(null, null, null) {
			@Override
			public Settings current() {
				return fixed;
			}
		};
	}

	private static ProviderCapabilities fullCapabilities() {
		return new ProviderCapabilities(List.of("default", "acceptEdits", "plan", "bypassPermissions"),
				true, true, true, true, true, true, true, true, true, true, true, List.of(), true, true, true,
				"claude-code");
	}

	@Test
	void spawnChildSessionOnAPackageResolvesRepoPathToTheMonorepoRootAndReportResultNamesThePackage(
			@TempDir Path tmp) throws IOException {
		GitCommandRunner git = new GitCommandRunner();
		GitWorktreeService worktrees = new GitWorktreeService(git);
		Path mono = tmp.resolve("mono");
		initRepo(mono, git);
		Files.writeString(mono.resolve("package.json"), "{\"workspaces\": [\"packages/*\"]}");
		Files.createDirectories(mono.resolve("packages/foo"));
		Files.createDirectories(mono.resolve("packages/bar"));
		commit(mono, "init", git);

		Path worktreeRoot = tmp.resolve("worktrees");
		Files.createDirectories(worktreeRoot);
		AppProperties props = new AppProperties(mono.toString(), worktreeRoot.toString(), "/skills", "/memory", 4,
				"authtoken", "", "", "logs", 30, 65536, 1048576, Map.of());
		SettingsService settings = fakeSettings(mono.toString());
		ObjectMapper mapper = new JsonMapper();
		ProviderCatalog catalog = ProviderCatalog.fixedForTest(Map.of("claude", fullCapabilities()));
		SessionConfigFactory configFactory = new SessionConfigFactory(props, settings, null, mapper, null, 8080, catalog, worktrees, null, null, null);

		FakeSessionRepository sessions = new FakeSessionRepository();
		FakeSidecarManager sidecars = new FakeSidecarManager();
		FakeEventJournal journal = new FakeEventJournal(props);
		JournalPublisher journalPublisher = new JournalPublisher(journal, new SessionEventBus());
		AssetProvisioningService noopAssets = new AssetProvisioningService(null, null, mapper) {
			@Override
			public List<Warning> provision(Path assetsRoot, JsonNode skillSources, JsonNode agentSources) {
				return List.of();
			}
		};
		SystemSessionService systemSessionService =
				new SystemSessionService(props, settings, sessions, configFactory, journalPublisher, mapper, null);
		SessionService sessionService = new SessionService(props, settings, sessions, worktrees, git, noopAssets,
				sidecars, journal, journalPublisher, mapper, e -> { }, configFactory, systemSessionService, null, catalog, null, null);
		OrchestrationMcpTools tools = new OrchestrationMcpTools(settings, sessions, sessionService, worktrees, journal,
				journalPublisher, mapper);

		SessionEntity parent = SessionEntity.builder()
				.id(UUID.randomUUID()).name("parent").provider("claude")
				.repoPath(mono.toString()).servicePath(mono.resolve("packages/foo").toString())
				.ecosystemPath(mono.toString())
				.branch("parent-branch").baseBranch("master")
				.worktreePath(worktreeRoot.resolve("parent-wt").toString())
				.contextDirs(List.of()).permissionMode("default").allowedTools(List.of()).disallowedTools(List.of())
				.state(SessionState.IDLE).kind("user").build();
		sessions.seed(parent);

		Map<String, String> spawned = tools.spawnChildSession(parent.id().toString(),
				mono.resolve("packages/bar").toString(), "child-branch", "do the bar thing", null);
		UUID childId = UUID.fromString(spawned.get("childId"));
		SessionEntity child = sessions.get(childId);

		assertThat(child.repoPath()).isEqualTo(mono.toString());
		assertThat(child.servicePath()).isEqualTo(mono.resolve("packages/bar").toString());
		assertThat(child.parentSessionId()).isEqualTo(parent.id());
		var spawnEvent = journal.firstEventOfType(parent.id(), "child_spawned").orElseThrow();
		assertThat(spawnEvent.payload().path("childId").asText()).isEqualTo(childId.toString());
		assertThat(spawnEvent.payload().path("service").asText()).isEqualTo("bar");

		// give the parent a live handle so report_result's sendUserMessage dispatches immediately
		// rather than just enqueueing behind a park→wake cycle
		sidecars.spawn(parent, null, false, null, e -> { }, (h, c) -> { });
		// phase 25: a parent that hid itself when it parked comes back for the turn the report starts
		sessions.updateHidden(parent.id(), true);
		tools.reportResult(childId.toString(), "finished the bar work");
		assertThat(sessions.get(parent.id()).hidden()).isFalse();

		var reportEvent = journal.firstEventOfType(childId, "child_reported").orElseThrow();
		assertThat(reportEvent.payload().path("service").asText()).isEqualTo("bar");
		assertThat(sidecars.sentTo(parent.id())).anyMatch(line -> line.contains("/ bar]") && line.contains("finished the bar work"));
	}
}
