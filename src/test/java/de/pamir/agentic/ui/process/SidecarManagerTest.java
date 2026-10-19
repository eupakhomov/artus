package de.pamir.agentic.ui.process;

import de.pamir.agentic.ui.session.ProviderCapabilities;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link SidecarManager#buildArgs} directly (package-private for this reason — see
 * its own comment) against a fabricated provider's capabilities, proving the flag-gating is
 * capability-driven rather than a hardcoded provider-name check (docs/plan/
 * phase-10-review-followups.md R1). buildArgs is defense-in-depth (SessionConfigFactory.prepare
 * is the primary enforcement point), so this only needs to prove the guard exists, not
 * duplicate every rejection case.
 */
class SidecarManagerTest {

	private static SessionEntity.Builder baseEntity() {
		return SessionEntity.builder().id(UUID.randomUUID()).name("s").repoPath("/repo")
				.branch("b").baseBranch("main").worktreePath("/wt").state(SessionState.CREATING)
				.allowedTools(List.of("Bash")).disallowedTools(List.of("Write"))
				.thinking("adaptive").maxTurns(7).fallbackModel("fallback-model");
	}

	private static de.pamir.agentic.ui.session.ProviderCatalog catalogOf(String providerId, ProviderCapabilities caps) {
		return de.pamir.agentic.ui.session.ProviderCatalog.fixedForTest(Map.of(providerId, caps));
	}

	@Test
	void omitsFlagsTheProviderDeclaresUnsupportedEvenWhenTheSessionCarriesValuesForThem() {
		ProviderCapabilities limited = new ProviderCapabilities(List.of("default"), false, true, false, true,
				true, false, true, true, false, false, true,
				List.of("allowedTools", "disallowedTools", "thinking", "maxTurns", "fallbackModel"), false, false, true, "claude-code");
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", limited));
		SessionEntity entity = baseEntity().provider("widget").ecosystemPath("/ecosystem").build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).doesNotContain("--allowed-tools", "--disallowed-tools", "--thinking",
				"--max-turns", "--fallback-model", "--context-dir");
	}

	@Test
	void includesTheSameFlagsForAProviderThatSupportsThem() {
		ProviderCapabilities full = new ProviderCapabilities(List.of("default"), true, true, true, true, true,
				true, true, true, true, true, true, List.of(), true, true, true, "claude-code");
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", full));
		SessionEntity entity = baseEntity().provider("widget").build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).contains("--allowed-tools", "--disallowed-tools", "--thinking",
				"--max-turns", "--fallback-model");
	}

	// --- docs/plan/phase-11-monorepo.md Step 4: --writable-root, cwd, context dirs ---

	private static ProviderCapabilities fullCaps() {
		return new ProviderCapabilities(List.of("default"), true, true, true, true, true,
				true, true, true, true, true, true, List.of(), true, true, true, "claude-code");
	}

	/** Same as {@link #fullCaps()} but with contextDirs off — sidecar-codex's actual capabilities.json shape. */
	private static ProviderCapabilities codexLikeCaps() {
		return new ProviderCapabilities(List.of("default"), true, true, true, true, true,
				true, true, true, true, true, true, List.of(), false, true, true, "codex");
	}

	@Test
	void buildArgsForAPolyrepoSessionEqualsThePrePhaseArgsPlusExactlyWritableRoot() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		SessionEntity entity = baseEntity().provider("widget").ecosystemPath("/ecosystem").build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).containsExactly(
				"--cwd", "/wt",
				"--writable-root", "/wt",
				"--fallback-model", "fallback-model",
				"--permission-mode", "default",
				"--allowed-tools", "Bash",
				"--disallowed-tools", "Write",
				"--context-dir", "/ecosystem",
				"--thinking", "adaptive",
				"--max-turns", "7");
	}

	@Test
	void buildArgsForLayoutAUsesTheServiceCwdAndReplacesTheEcosystemContextDirWithTheWorktree() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		// layout (a): the ecosystem root IS the monorepo, so ecosystemPath == repoPath
		SessionEntity entity = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("widget")
				.repoPath("/mono").servicePath("/mono/packages/foo").ecosystemPath("/mono")
				.branch("b").baseBranch("main").worktreePath("/wt").state(SessionState.CREATING)
				.allowedTools(List.of()).disallowedTools(List.of()).build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).containsSubsequence("--cwd", "/wt/packages/foo", "--writable-root", "/wt");
		assertThat(args).containsSequence("--context-dir", "/wt");
		// the stale original checkout must never be attached alongside the fresh worktree
		assertThat(args).doesNotContainSequence("--context-dir", "/mono");
	}

	@Test
	void buildArgsForLayoutBEmitsBothTheWorktreeAndTheEcosystemContextDirs() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		// layout (b): the ecosystem root is a folder of repos, one of which is the monorepo
		SessionEntity entity = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("widget")
				.repoPath("/eco/mono").servicePath("/eco/mono/packages/foo").ecosystemPath("/eco")
				.branch("b").baseBranch("main").worktreePath("/wt").state(SessionState.CREATING)
				.allowedTools(List.of()).disallowedTools(List.of()).build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).containsSequence("--context-dir", "/wt");
		assertThat(args).containsSequence("--context-dir", "/eco");
	}

	@Test
	void buildArgsForACodexLikeProviderNeverEmitsContextDirButStillEmitsWritableRoot() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("codex", codexLikeCaps()));
		SessionEntity entity = SessionEntity.builder().id(UUID.randomUUID()).name("s").provider("codex")
				.repoPath("/mono").servicePath("/mono/packages/foo").ecosystemPath("/mono")
				.branch("b").baseBranch("main").worktreePath("/wt").state(SessionState.CREATING)
				.allowedTools(List.of()).disallowedTools(List.of()).build();

		List<String> args = manager.buildArgs(entity, null, false, null);

		assertThat(args).containsSequence("--writable-root", "/wt");
		assertThat(args).doesNotContain("--context-dir");
	}

	// --- MCP tool search: defer tool schemas instead of loading every one up front ---

	@Test
	void sidecarEnvironmentEnablesToolSearchByDefault() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		Map<String, String> env = new HashMap<>();

		manager.applySidecarEnvironment(baseEntity().provider("widget").build(), env);

		assertThat(env).containsEntry("ENABLE_TOOL_SEARCH", "true").doesNotContainKey("MCP_TIMEOUT");
	}

	@Test
	void aSessionsOwnEnvVarsOverrideTheToolSearchDefault() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		Map<String, String> env = new HashMap<>();
		SessionEntity entity = baseEntity().provider("widget")
				.envVars(new ObjectMapper().createObjectNode().put("ENABLE_TOOL_SEARCH", "false").put("FOO", "bar"))
				.build();

		manager.applySidecarEnvironment(entity, env);

		assertThat(env).containsEntry("ENABLE_TOOL_SEARCH", "false").containsEntry("FOO", "bar");
	}

	@Test
	void anInheritedExperimentalBetasKillSwitchIsDroppedSoToolSearchTakesEffect() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		Map<String, String> env = new HashMap<>(Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1", "KEEP", "me"));

		manager.applySidecarEnvironment(baseEntity().provider("widget").build(), env);

		assertThat(env).doesNotContainKey("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS").containsEntry("KEEP", "me");
	}

	@Test
	void aSessionCanRestoreTheExperimentalBetasKillSwitchViaItsEnvVars() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		Map<String, String> env = new HashMap<>(Map.of("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1"));
		SessionEntity entity = baseEntity().provider("widget")
				.envVars(new ObjectMapper().createObjectNode().put("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1"))
				.build();

		manager.applySidecarEnvironment(entity, env);

		assertThat(env).containsEntry("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS", "1");
	}

	@Test
	void serenaSessionsStillGetTheLongMcpTimeout() {
		SidecarManager manager = new SidecarManager(null, null, catalogOf("widget", fullCaps()));
		Map<String, String> env = new HashMap<>();

		manager.applySidecarEnvironment(baseEntity().provider("widget").codeIntel("serena").build(), env);

		assertThat(env).containsEntry("MCP_TIMEOUT", "300000").containsEntry("ENABLE_TOOL_SEARCH", "true");
	}

	@Test
	void passesPluginDirsOnlyToAProviderThatSupportsThem() {
		ProviderCapabilities noPlugins = new ProviderCapabilities(List.of("default"), true, true, true, true, true,
				true, true, true, true, true, true, List.of("pluginDirs"), true, true, true, "claude-code");
		SessionEntity entity = baseEntity().provider("widget").pluginDirs(List.of("/plugins/a", "/plugins/b")).build();

		List<String> with = new SidecarManager(null, null, catalogOf("widget", fullCaps())).buildArgs(entity, null, false, null);
		List<String> without = new SidecarManager(null, null, catalogOf("widget", noPlugins)).buildArgs(entity, null, false, null);

		assertThat(with).containsSubsequence("--plugin-dir", "/plugins/a", "--plugin-dir", "/plugins/b");
		assertThat(without).doesNotContain("--plugin-dir");
	}
}
