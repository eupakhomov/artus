package de.pamir.agentic.ui.process;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.session.ProviderCapabilities;
import de.pamir.agentic.ui.session.ProviderCatalog;
import de.pamir.agentic.ui.session.SessionEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Spawns and tracks one sidecar per session, building CLI args from the session row. */
@Component
public class SidecarManager {

	private static final Logger log = LoggerFactory.getLogger(SidecarManager.class);

	private final AppProperties props;
	private final ObjectMapper mapper;
	private final ProviderCatalog catalog;
	private final Map<UUID, SidecarHandle> handles = new ConcurrentHashMap<>();

	public SidecarManager(AppProperties props, ObjectMapper mapper, ProviderCatalog catalog) {
		this.props = props;
		this.mapper = mapper;
		this.catalog = catalog;
	}

	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume,
							   Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		return spawn(session, mcpConfigFile, resume, null, onEvent, onExit);
	}

	/**
	 * @param extraSystemPrompt appended alongside the session's own {@code instructions} in
	 *                          {@code --append-system-prompt} — used for the memory episodic
	 *                          window (docs/plan/phase-5.3-memory-reflection.md), computed fresh
	 *                          on every spawn (including resume/wake) rather than stored, so a
	 *                          park→wake picks up episodes reflected meanwhile for free.
	 */
	public SidecarHandle spawn(SessionEntity session, Path mcpConfigFile, boolean resume, String extraSystemPrompt,
							   Consumer<JsonNode> onEvent, BiConsumer<SidecarHandle, Integer> onExit) {
		AppProperties.Provider provider = props.providers().get(session.provider());
		if (provider == null) {
			throw new IllegalArgumentException("unknown provider: " + session.provider());
		}
		List<String> command = new ArrayList<>();
		for (String part : provider.command()) {
			// relative launcher paths (e.g. sidecar/dist/index.js) resolve against the app's cwd
			command.add(part.contains("/") && !Path.of(part).isAbsolute()
					? Path.of(part).toAbsolutePath().normalize().toString()
					: part);
		}
		// Fail with an actionable message instead of letting node die with MODULE_NOT_FOUND on a
		// fresh clone (dist/ is gitignored, and `mvn package` doesn't build the sidecars).
		command.stream().filter(part -> part.endsWith(".js")).findFirst().ifPresent(script -> {
			if (!Files.isRegularFile(Path.of(script))) {
				throw new IllegalStateException("provider '" + session.provider() + "': sidecar not built — "
						+ script + " is missing; run `npm install && npm run build` in its package directory "
						+ "(see docs/DEPLOY.md §5)");
			}
		});
		command.addAll(buildArgs(session, mcpConfigFile, resume, extraSystemPrompt));

		ProcessBuilder builder = new ProcessBuilder(command).directory(Path.of(session.worktreePath()).toFile());
		applySidecarEnvironment(session, builder.environment());
		try {
			Process process = builder.start();
			Path stderrLog = Path.of(props.logDir(), "sidecar", session.id() + ".log");
			SidecarHandle handle = new SidecarHandle(session.id(), process, mapper, stderrLog, onEvent, (h, code) -> {
				handles.remove(session.id(), h);
				deletePidFile(session);
				onExit.accept(h, code);
			});
			// handles.put before watchExit: a process that's already dead on arrival must not be
			// able to fire the exit callback (removing an entry that was never inserted) before
			// the entry actually exists — see docs/plan/phase-10-review-followups.md R5.
			handles.put(session.id(), handle);
			handle.watchExit();
			writePidFile(session, handle.pid());
			log.info("session {}: sidecar pid {} spawned ({})", session.id(), handle.pid(), String.join(" ", command));
			return handle;
		} catch (IOException e) {
			throw new IllegalStateException("failed to start sidecar: " + e.getMessage()
					+ " (is node on the backend's PATH?)", e);
		}
	}

	/** PID file inside the worktree lets a restarted backend kill orphans from a kill -9'd predecessor. */
	private void writePidFile(SessionEntity session, long pid) {
		try {
			Files.writeString(pidFile(session), Long.toString(pid));
		} catch (IOException e) {
			log.warn("session {}: cannot write pid file: {}", session.id(), e.getMessage());
		}
	}

	private void deletePidFile(SessionEntity session) {
		try {
			Files.deleteIfExists(pidFile(session));
		} catch (IOException ignored) {
			// worktree may already be gone
		}
	}

	public static Path pidFile(SessionEntity session) {
		return Path.of(session.worktreePath(), ".artus.pid");
	}

	public SidecarHandle handle(UUID sessionId) {
		SidecarHandle handle = handles.get(sessionId);
		if (handle == null || !handle.isAlive()) {
			throw new IllegalStateException("no live sidecar for session " + sessionId);
		}
		return handle;
	}

	public boolean hasLiveHandle(UUID sessionId) {
		SidecarHandle handle = handles.get(sessionId);
		return handle != null && handle.isAlive();
	}

	public void terminate(UUID sessionId) {
		SidecarHandle handle = handles.remove(sessionId);
		if (handle != null) {
			handle.terminate();
		}
	}

	@PreDestroy
	void terminateAll() {
		log.info("terminating {} sidecar(s)", handles.size());
		handles.keySet().forEach(this::terminate);
	}

	/**
	 * Environment defaults for a session's sidecar, then the session's own {@code envVars} on top
	 * so any of them can be overridden per session. Package-private: unit-tested directly.
	 */
	void applySidecarEnvironment(SessionEntity session, Map<String, String> env) {
		// Defer MCP tool schemas behind the SDK's tool search instead of loading every tool
		// definition into the first request (Linear alone is ~80 tools, tens of thousands of tokens
		// of a 200k window). A Claude Code setting; the Codex sidecar and its child ignore it, so
		// no per-provider gate. Override per session with envVars ENABLE_TOOL_SEARCH=auto|false.
		env.put("ENABLE_TOOL_SEARCH", "true");
		// ...but only if experimental betas aren't suppressed: a backend launched from a shell that
		// exports CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1 (typical for LiteLLM/gateway setups) makes
		// the CLI silently ignore ENABLE_TOOL_SEARCH, and measured ~37k extra tokens per session
		// (verified through a LiteLLM proxy on Opus 4.6, Sonnet and Haiku 4.5). Drop the inherited
		// value for sidecars only; a session's own envVars below can set it back.
		env.remove("CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS");
		if (SettingsService.CODE_INTEL_SERENA.equals(session.codeIntel())) {
			// Serena's language server can take minutes to download on a cold cache (docs/plan/
			// phase-12-linear-cache-serena-context.md Step B0) — a generous, conservative default
			// the client's own MCP handshake timeout should honor, so it doesn't give up on Serena
			// as a dead server mid-download. Serena-only: graphify's stdio server initializes in
			// under a second (phase-13 Step 0) and the graph is built out of band.
			env.put("MCP_TIMEOUT", "300000");
		}
		if (session.envVars() != null && session.envVars().isObject()) {
			session.envVars().properties().forEach(e -> env.put(e.getKey(), e.getValue().asText()));
		}
	}

	// package-private (not private): unit-tested directly — see SidecarManagerTest /
	// docs/plan/phase-10-review-followups.md R1's DoD.
	List<String> buildArgs(SessionEntity s, Path mcpConfigFile, boolean resume, String extraSystemPrompt) {
		// Not every adapter understands every flag — see the provider's own capabilities.json
		// (docs/plan/phase-10-review-followups.md R1). SessionConfigFactory.prepare already
		// rejects a session that explicitly set a field its provider doesn't support, so these
		// guards are defense-in-depth, not the primary enforcement point. --mcp-config and
		// --append-system-prompt are supported by every adapter today, so they're passed through
		// unconditionally below.
		ProviderCapabilities caps = catalog.get(s.provider());

		// --cwd is the service's own subfolder (equal to the worktree root for a polyrepo
		// session); --writable-root is unconditionally the worktree root — both adapters honor
		// it (docs/plan/phase-11-monorepo.md Step 4), no capability gate needed.
		List<String> args = new ArrayList<>(List.of("--cwd", s.cwdPath(), "--writable-root", s.worktreePath()));
		if (resume && s.providerSessionId() != null) {
			args.addAll(List.of("--resume", s.providerSessionId()));
		}
		if (s.model() != null) {
			args.addAll(List.of("--model", s.model()));
		}
		if (caps.supports("fallbackModel") && s.fallbackModel() != null) {
			args.addAll(List.of("--fallback-model", s.fallbackModel()));
		}
		if (s.permissionMode() != null) {
			args.addAll(List.of("--permission-mode", s.permissionMode()));
		}
		if (caps.supports("allowedTools") && !s.allowedTools().isEmpty()) {
			args.addAll(List.of("--allowed-tools", String.join(",", s.allowedTools())));
		}
		if (caps.supports("disallowedTools") && !s.disallowedTools().isEmpty()) {
			args.addAll(List.of("--disallowed-tools", String.join(",", s.disallowedTools())));
		}
		if (caps.supports("pluginDirs")) {
			for (String dir : s.pluginDirs()) {
				args.addAll(List.of("--plugin-dir", dir));
			}
		}
		if (mcpConfigFile != null && Files.exists(mcpConfigFile)) {
			args.addAll(List.of("--mcp-config", mcpConfigFile.toString()));
		}
		String systemPrompt = Stream.of(s.instructions(), extraSystemPrompt)
				.filter(p -> p != null && !p.isBlank()).collect(Collectors.joining("\n\n"));
		if (!systemPrompt.isBlank()) {
			args.addAll(List.of("--append-system-prompt", systemPrompt));
		}
		if (caps.contextDirs()) {
			// Monorepo (docs/plan/phase-11-monorepo.md Step 4): the worktree is this session's own
			// fresh checkout of the service's repo, so it always replaces (layout (a): ecosystem
			// root IS that repo) or joins (layout (b): ecosystem root is a wider folder) the
			// ecosystem context — using the ecosystem path alone in layout (a) would attach the
			// *original, stale* checkout of the very code this session is editing.
			boolean monorepo = !s.servicePath().equals(s.repoPath());
			boolean ecosystemConfigured = s.ecosystemPath() != null && !s.ecosystemPath().isBlank();
			if (monorepo) {
				args.addAll(List.of("--context-dir", s.worktreePath()));
				if (ecosystemConfigured && !Path.of(s.ecosystemPath()).equals(Path.of(s.repoPath()))) {
					args.addAll(List.of("--context-dir", s.ecosystemPath()));
				}
			} else if (ecosystemConfigured) {
				args.addAll(List.of("--context-dir", s.ecosystemPath()));
			}
			for (String dir : s.contextDirs()) {
				args.addAll(List.of("--context-dir", dir));
			}
		}
		if (caps.supports("thinking") && s.thinking() != null) {
			args.addAll(List.of("--thinking", s.thinking()));
		}
		if (s.effort() != null) {
			args.addAll(List.of("--effort", s.effort()));
		}
		if (caps.supports("maxTurns") && s.maxTurns() != null) {
			args.addAll(List.of("--max-turns", s.maxTurns().toString()));
		}
		return args;
	}
}
