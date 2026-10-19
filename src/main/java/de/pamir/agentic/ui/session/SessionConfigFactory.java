package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.integration.CodegraphService;
import de.pamir.agentic.ui.integration.GraphifyService;
import de.pamir.agentic.ui.integration.SerenaService;
import de.pamir.agentic.ui.memory.MemoryEpisodeRepository;
import de.pamir.agentic.ui.tools.ToolCatalog;
import de.pamir.agentic.ui.tools.ToolCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Everything about turning a {@link SessionService.CreateOptions} (or an existing session, for
 * "duplicate"/"last config") into resolved, validated config: template merge,
 * capability-driven unsupported-field validation (see {@link ProviderCatalog}), default
 * MCP-server layering, and the memory/orchestration system-prompt blocks a spawn needs.
 * Extracted from SessionService (see docs/plan/phase-9-production-hardening.md S1) so
 * create-config parsing is one place instead of tangled with provisioning/spawn orchestration —
 * SessionService still owns the actual worktree creation, DB insert, and sidecar spawn.
 */
@Component
public class SessionConfigFactory {

	private final AppProperties props;
	private final SettingsService settings;
	private final TemplateRepository templates;
	private final ObjectMapper mapper;
	private final MemoryEpisodeRepository episodes;
	private final int serverPort;
	private final ProviderCatalog catalog;
	private final GitWorktreeService worktrees;
	private final SerenaService serena;
	private final GraphifyService graphify;
	private final CodegraphService codegraph;
	/** Phase 26 tool catalog; null in unit tests that never select catalog tools. */
	private final ToolCatalogService toolCatalog;

	/** Pre-phase-26 arity (no tool catalog); keeps unit-test construction compiling. */
	public SessionConfigFactory(AppProperties props, SettingsService settings, TemplateRepository templates,
								 ObjectMapper mapper, MemoryEpisodeRepository episodes, int serverPort,
								 ProviderCatalog catalog, GitWorktreeService worktrees, SerenaService serena,
								 GraphifyService graphify, CodegraphService codegraph) {
		this(props, settings, templates, mapper, episodes, serverPort, catalog, worktrees, serena, graphify,
				codegraph, null);
	}

	@Autowired
	public SessionConfigFactory(AppProperties props, SettingsService settings, TemplateRepository templates,
								 ObjectMapper mapper, MemoryEpisodeRepository episodes,
								 @Value("${server.port:8080}") int serverPort, ProviderCatalog catalog,
								 GitWorktreeService worktrees, SerenaService serena, GraphifyService graphify,
								 CodegraphService codegraph, ToolCatalogService toolCatalog) {
		this.toolCatalog = toolCatalog;
		this.props = props;
		this.settings = settings;
		this.templates = templates;
		this.mapper = mapper;
		this.episodes = episodes;
		this.serverPort = serverPort;
		this.catalog = catalog;
		this.worktrees = worktrees;
		this.serena = serena;
		this.graphify = graphify;
		this.codegraph = codegraph;
	}

	/** The resolved entity (still transient — not yet inserted) plus any non-fatal warnings to journal. */
	public record Prepared(SessionEntity entity, List<String> warnings) {
	}

	/** Builds a fresh 'user' session entity (state CREATING) from the given options; does not persist it. */
	public Prepared prepare(UUID id, Path worktree, SessionService.CreateOptions options) {
		Settings s = settings.current();
		ObjectNode config = mergedConfig(options.templateId(), options.overrides());
		// Computed early (independent of the templateId asset-merge block below, which only adds
		// skillSources/agentSources to `config`) — the servicePath resolution needs it to know which
		// ecosystem folder's findServices() result "known service" is checked against.
		String ecosystemPath = config.has("ecosystemPath") ? nullableText(config, "ecosystemPath")
				: nullableIfBlank(s.ecosystemRoot());
		ServiceResolution resolution = resolveRepoAndService(options, config, ecosystemPath, s);
		String repo = resolution.repoPath();
		List<String> warnings = new ArrayList<>();
		if (options.templateId() != null) {
			List<TemplateRepository.TemplateAsset> templateAssets = templates.get(options.templateId()).assets();
			if (options.overrides() == null || !options.overrides().has("skillSources")) {
				config.set("skillSources", combineTemplateSources(config.get("skillSources"), templateAssets,
						"skill", "dir", warnings));
			}
			if (options.overrides() == null || !options.overrides().has("agentSources")) {
				config.set("agentSources", combineTemplateSources(config.get("agentSources"), templateAssets,
						"agent", "file", warnings));
			}
		}
		String provider = text(config, "provider", s.defaultProvider());
		String permissionMode = text(config, "permissionMode", "default");
		JsonNode explicitMcpConfig = config.get("mcpConfig");  // reassigned when catalog tools are layered in below
		List<String> allowedTools = stringList(config, "allowedTools");
		// Absent key = the default block list; an explicit list (even empty) is the session's own choice.
		boolean explicitDisallowed = config.has("disallowedTools");
		List<String> disallowedTools = explicitDisallowed ? stringList(config, "disallowedTools") : List.of();
		String thinking = nullableText(config, "thinking");
		Integer maxTurns = config.hasNonNull("maxTurns") ? config.get("maxTurns").asInt() : null;
		String fallbackModel = nullableText(config, "fallbackModel");
		List<String> contextDirs = stringList(config, "contextDirs");
		String codeIntel = resolveCodeIntel(config, s);
		String sessionType = resolveSessionType(options, config);
		ProviderCapabilities caps = catalog.get(provider);
		// Unsupported controls are rejected at creation time, not silently downgraded (DoD from
		// docs/plan/phase-5.13-codex-provider.md, generalized in
		// docs/plan/phase-10-review-followups.md R1 to any provider's declared capabilities —
		// no provider name appears below, only capability lookups). mcpConfig is never rejected
		// here — the 2026-08-30 MCP follow-up confirmed sidecar-codex can translate the same
		// Claude-shaped config every other session already gets, including the default Linear
		// MCP layering, and no other provider has needed a different answer since.
		if (!caps.permissionModes().contains(permissionMode)) {
			throw new IllegalArgumentException(
					"provider '" + provider + "' does not support permission mode '" + permissionMode + "'");
		}
		if (!caps.supports("allowedTools") && (!allowedTools.isEmpty() || !disallowedTools.isEmpty())) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support allowedTools/disallowedTools");
		}
		if (!caps.supports("thinking") && thinking != null) {
			throw new IllegalArgumentException(
					"provider '" + provider + "' does not support the thinking control (use effort)");
		}
		if (!caps.supports("maxTurns") && maxTurns != null) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support maxTurns");
		}
		if (!caps.supports("fallbackModel") && fallbackModel != null) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support fallbackModel");
		}
		if (!caps.supports("agentSources") && arrayOrEmpty(config, "agentSources").size() > 0) {
			throw new IllegalArgumentException("provider '" + provider + "' does not support agentSources");
		}
		if (!caps.contextDirs()) {
			// Ecosystem/context dirs commonly come from a global default (settings.current().ecosystemRoot()),
			// not explicit per-session intent, so this degrades with a visible warning rather than
			// rejecting creation outright.
			if (ecosystemPath != null || !contextDirs.isEmpty()) {
				warnings.add("provider '" + provider + "' does not support context directories — skipped");
			}
			ecosystemPath = null;
			contextDirs = List.of();
		}
		if (caps.supports("allowedTools")) {
			if (!explicitDisallowed) {
				disallowedTools = DEFAULT_DISALLOWED_TOOLS;
			}
			// allowedTools/disallowedTools are additive presets (bypass or block specific tools
			// without switching the session into allow-list-only mode) — safe to append to
			// regardless of whether the session configured any of its own. A provider that
			// rejects allowedTools outright (checked above) goes through the normal approval
			// flow instead (decision 10, phase-5.13-codex-provider.md). Named explicitly (not
			// the blanket "mcp__memory" server-level grant) since 7.4's orchestration tools —
			// and now 8's service-discovery tools — live on the same MCP server and must NOT be
			// pre-approved by a blanket grant.
			if (s.memoryEnabled()) {
				allowedTools = new ArrayList<>(allowedTools);
				allowedTools.add("mcp__memory__memory_tags");
				allowedTools.add("mcp__memory__memory_search");
				allowedTools.add("mcp__memory__memory_read");
			}
			if (s.serviceDiscoveryEnabled()) {
				// docs/plan/phase-8-service-discovery.md decision 7 — pre-approved independently
				// of memory.enabled, since the two features are toggled separately
				allowedTools = new ArrayList<>(allowedTools);
				allowedTools.add("mcp__memory__service_description");
				allowedTools.add("mcp__memory__find_service");
				allowedTools.add("mcp__memory__list_discovered_services");
			}
			// a copied config (duplicate / quick session) already carries the tools appended above
			allowedTools = new ArrayList<>(new LinkedHashSet<>(allowedTools));
		}
		String cwdPath = computeCwdPath(repo, resolution.servicePath(), worktree);
		List<String> tools = stringList(config, "tools");
		List<String> pluginDirs = List.of();
		if (!tools.isEmpty()) {
			if (toolCatalog == null) {
				throw new IllegalArgumentException("the tool catalog is not available");
			}
			ToolCatalog.Resolution picked = toolCatalog.resolve(tools, caps.supports("pluginDirs"),
					caps.supports("allowedTools"));
			explicitMcpConfig = withCatalogServers(explicitMcpConfig, picked.servers());
			pluginDirs = picked.pluginDirs();
			if (!picked.allowedTools().isEmpty()) {
				allowedTools = new ArrayList<>(new LinkedHashSet<>(
						Stream.concat(allowedTools.stream(), picked.allowedTools().stream()).toList()));
			}
		}
		JsonNode mcpConfig = withDefaultCodeIntelMcp(withDefaultMemoryMcp(withDefaultLinearMcp(explicitMcpConfig)),
				provider, codeIntel, cwdPath, id);

		SessionEntity entity = SessionEntity.builder()
				.id(id).name(options.name())
				.provider(provider).providerConfig(config.get("providerConfig"))
				.repoPath(repo).servicePath(resolution.servicePath()).ecosystemPath(ecosystemPath).contextDirs(contextDirs)
				.branch(options.branch()).baseBranch(options.baseBranch()).worktreePath(worktree.toString())
				.model(nullableText(config, "model")).permissionMode(permissionMode)
				.allowedTools(allowedTools).disallowedTools(disallowedTools)
				.mcpConfig(mcpConfig).envVars(config.get("envVars"))
				.skillSources(arrayOrEmpty(config, "skillSources")).agentSources(arrayOrEmpty(config, "agentSources"))
				.instructions(nullableText(config, "instructions")).thinking(thinking)
				.effort(nullableText(config, "effort")).maxTurns(maxTurns).fallbackModel(fallbackModel)
				.costBudgetUsd(config.hasNonNull("costBudgetUsd") ? new BigDecimal(config.get("costBudgetUsd").asText()) : null)
				.kickoffPrompt(fillPlaceholders(nullableText(config, "kickoffPrompt"), options.kickoffValues()))
				.state(SessionState.CREATING).kind("user").sessionType(sessionType)
				.ticketRef(nullableText(config, "ticketRef"))
				.continuedFromId(options.continuedFromId()).parentSessionId(options.parentSessionId())
				.reflectionEnabled(config.path("reflectionEnabled").asBoolean(s.memoryReflectionDefault()))
				.codeIntel(codeIntel)
				.slackEnabled(config.path("slackEnabled").asBoolean(s.slackDefaultEnabled()))
				.tools(tools).pluginDirs(pluginDirs)
				.build();
		return new Prepared(entity, warnings);
	}

	/**
	 * The per-session code-intelligence flag ({@code codeIntelEnabled}; the phase-12 {@code
	 * serenaEnabled} key is accepted as a legacy alias since templates in the DB still carry it)
	 * resolved to the globally selected tool (docs/plan/phase-13-graphify.md decisions 1 and 5).
	 * A session with the flag off never reads either service; with it on, {@code none} or an
	 * unconfigured selected tool is a 400 — never a silent downgrade.
	 */
	private String resolveCodeIntel(JsonNode config, Settings s) {
		boolean enabled = config.path("codeIntelEnabled").asBoolean(config.path("serenaEnabled").asBoolean(false));
		if (!enabled) {
			return null;
		}
		String tool = s.codeIntel();
		if (SettingsService.CODE_INTEL_NONE.equals(tool)) {
			throw new IllegalArgumentException("Code intelligence is not configured (Settings → MCP servers)");
		}
		boolean configured = switch (tool) {
			case SettingsService.CODE_INTEL_SERENA -> serena.configured();
			case SettingsService.CODE_INTEL_GRAPHIFY -> graphify.configured();
			case SettingsService.CODE_INTEL_CODEGRAPH -> codegraph.configured();
			default -> false;
		};
		if (!configured) {
			throw new IllegalArgumentException(codeIntelLabel(tool) + " is not configured (Settings → MCP servers)");
		}
		return tool;
	}

	/**
	 * The tool's display name for an error message — a plain capitalize-first-letter would read
	 * "Codegraph" for {@code codegraph} (the brand name has an internal capital, like the Settings
	 * selector's own "CodeGraph" label and {@link CodegraphService#validate}'s error text).
	 */
	private static String codeIntelLabel(String tool) {
		if (SettingsService.CODE_INTEL_CODEGRAPH.equals(tool)) {
			return "CodeGraph";
		}
		return Character.toUpperCase(tool.charAt(0)) + tool.substring(1);
	}

	/**
	 * Built-ins blocked unless a session sets its own {@code disallowedTools} — their schemas cost
	 * context on every first request and most sessions never use them.
	 */
	static final List<String> DEFAULT_DISALLOWED_TOOLS = List.of("NotebookEdit", "WebFetch", "WebSearch");

	private static final java.util.Set<String> SESSION_TYPES = java.util.Set.of("development", "review");

	/**
	 * {@code sessionType} is identity, not tunable config (docs/plan/phase-15-review-sessions.md
	 * proposal 7) — like name/branch/repo it's a top-level {@link SessionService.CreateOptions}
	 * field, not something callers put in the {@code overrides} JSON blob, so it's never copied by
	 * {@link #configOverridesFrom}. {@code options.sessionType()} (always sent explicitly by both
	 * dialogs) wins over a template's own {@code sessionType} config key, so a template picked on
	 * top of an explicit dialog toggle can't silently flip it; with neither set, defaults to {@code
	 * development} — byte-identical to every session created before this phase.
	 */
	private static String resolveSessionType(SessionService.CreateOptions options, ObjectNode config) {
		String sessionType = options.sessionType() != null && !options.sessionType().isBlank()
				? options.sessionType()
				: text(config, "sessionType", "development");
		if (!SESSION_TYPES.contains(sessionType)) {
			throw new IllegalArgumentException("unknown sessionType: " + sessionType);
		}
		return sessionType;
	}

	private record ServiceResolution(String repoPath, String servicePath) {
	}

	/**
	 * Resolution order (docs/plan/phase-11-monorepo.md Step 3): a {@code servicePath} — from
	 * {@code options} directly, or (so {@link SessionService#duplicate} and {@code
	 * lastSessionConfig} reproduce it via {@link #configOverridesFrom}) the merged config's
	 * "servicePath" key — resolves {@code repoPath} via {@link GitWorktreeService#repoRootOf} when
	 * {@code options.repoPath()} is blank, else validates the given {@code repoPath} *is* that
	 * root; either way it's rejected unless it's a known service (the repo root itself, or one of
	 * {@link GitWorktreeService#findServices} under the resolved {@code ecosystemPath}). With no
	 * {@code servicePath} at all, behavior is byte-identical to before this phase: {@code repoPath}
	 * as given (or the configured default), just validated as a git repo, and the returned {@code
	 * servicePath} is null (stored as NULL — polyrepo, and every pre-Phase-11 session).
	 */
	private ServiceResolution resolveRepoAndService(SessionService.CreateOptions options, ObjectNode config,
													  String ecosystemPath, Settings s) {
		String servicePathOption = nullableIfBlank(options.servicePath());
		if (servicePathOption == null) {
			servicePathOption = nullableText(config, "servicePath");
		}
		if (servicePathOption == null) {
			String repo = options.repoPath() == null || options.repoPath().isBlank() ? props.repoPath() : options.repoPath();
			if (!Files.exists(Path.of(repo).resolve(".git"))) {
				throw new IllegalArgumentException("not a git repository: " + repo);
			}
			return new ServiceResolution(repo, null);
		}
		String servicePathValue = servicePathOption;
		Path servicePath = Path.of(servicePathValue).toAbsolutePath().normalize();
		Path repoRoot = worktrees.repoRootOf(servicePath)
				.orElseThrow(() -> new IllegalArgumentException("not inside a git repository: " + servicePathValue));
		String repo;
		if (options.repoPath() == null || options.repoPath().isBlank()) {
			repo = repoRoot.toString();
		} else {
			Path givenRoot = Path.of(options.repoPath()).toAbsolutePath().normalize();
			if (!givenRoot.equals(repoRoot)) {
				throw new IllegalArgumentException(
						"repoPath " + options.repoPath() + " is not the git root of servicePath " + servicePathValue);
			}
			repo = options.repoPath();
		}
		Path ecosystemRoot = ecosystemPath == null || ecosystemPath.isBlank() ? null : Path.of(ecosystemPath);
		if (!worktrees.isKnownService(ecosystemRoot, monorepoGlobs(s), s.monorepoDetectionEnabled(), repoRoot, servicePath)) {
			throw new IllegalArgumentException("not a known service under this ecosystem: " + servicePathOption);
		}
		return new ServiceResolution(repo, servicePath.equals(repoRoot) ? null : servicePath.toString());
	}

	private static List<String> monorepoGlobs(Settings s) {
		return Arrays.stream(s.monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}

	private ObjectNode mergedConfig(UUID templateId, JsonNode overrides) {
		ObjectNode config = mapper.createObjectNode();
		if (templateId != null) {
			JsonNode templateConfig = templates.get(templateId).config();
			if (templateConfig instanceof ObjectNode t) {
				config.setAll(t);
			}
		}
		if (overrides instanceof ObjectNode o) {
			config.setAll(o);
		}
		return config;
	}

	/**
	 * Merges a template's live-linked library assets (skipping ARCHIVED ones, which are reported
	 * back via {@code warnings}) with any free-form sources already in the config's own key.
	 */
	ArrayNode combineTemplateSources(JsonNode existing, List<TemplateRepository.TemplateAsset> templateAssets,
									  String kind, String sourceType, List<String> warnings) {
		ArrayNode combined = mapper.createArrayNode();
		if (existing != null && existing.isArray()) {
			combined.addAll((ArrayNode) existing);
		}
		for (TemplateRepository.TemplateAsset asset : templateAssets) {
			if (!kind.equals(asset.kind())) {
				continue;
			}
			if ("ARCHIVED".equals(asset.status())) {
				warnings.add(kind + " '" + asset.name() + "' is archived and was skipped");
				continue;
			}
			combined.add(mapper.createObjectNode().put("type", sourceType).put("ref", asset.location()));
		}
		return combined;
	}

	/** The Linear MCP server block ({"linear": {...}}), or null if Linear integration isn't configured. */
	ObjectNode linearMcpServer() {
		boolean apiKey = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		if (!apiKey && !settings.current().linearOAuthEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode linear = servers.putObject("linear");
		linear.put("type", "http").put("url", "https://mcp.linear.app/mcp");
		if (apiKey) {
			// explicit key wins even if OAuth is also enabled in Settings
			linear.putObject("headers").put("Authorization", "Bearer " + props.linearApiKey());
		}
		// else: no headers — relies on the ambient `claude` CLI's own cached OAuth credential for
		// this server URL (set up once via `claude mcp add` on the backend host, e.g. Google-SSO Linear)
		return servers;
	}

	/**
	 * Layers the Linear MCP server into a regular session's mcpConfig by default when Linear
	 * integration is configured, so the agent can read/update tickets without the user having to
	 * wire it up per session — unless the session's own config already defines a "linear" entry,
	 * which wins. Regular sessions go through the normal permission-approval flow for its tools
	 * (unlike the system session, which pre-approves them — see SystemSessionService).
	 */
	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T1
	JsonNode withDefaultLinearMcp(JsonNode configured) {
		ObjectNode block = linearMcpServer();
		if (block != null) {
			// our own key, stripped by each sidecar: Claude enforces it with a PreToolUse hook, Codex maps it
			// to enabled_tools. Deliberately not in linearMcpServer(): the system session needs the full set.
			ArrayNode enabled = block.path("linear") instanceof ObjectNode linear ? linear.putArray("enabledTools") : null;
			if (enabled != null) {
				String allowed = settings.current().linearAllowedTools();
				Arrays.stream((allowed == null || allowed.isBlank() ? SettingsService.DEFAULT_LINEAR_ALLOWED_TOOLS : allowed).split(","))
						.map(String::trim).filter(t -> !t.isEmpty()).forEach(enabled::add);
			}
		}
		return withDefaultServer(configured, "linear", block);
	}

	/**
	 * Layers the selected catalog servers into a session's mcpConfig; same merge rule as the managed
	 * entries — a key the session's own mcpConfig already defines wins (so a duplicate, which carries
	 * the baked servers, reproduces them as they were).
	 */
	private JsonNode withCatalogServers(JsonNode configured, ObjectNode servers) {
		if (servers.isEmpty()) {
			return configured;
		}
		ObjectNode merged = mapper.createObjectNode();
		merged.setAll(servers);
		if (configured instanceof ObjectNode existing) {
			merged.setAll(existing);
		}
		return merged;
	}

	/**
	 * The shared in-process MCP server block ({"memory": {...}}) — still keyed "memory" for
	 * backward compatibility, but it now also carries the service-discovery tools (decision 6,
	 * docs/plan/phase-8-service-discovery.md) alongside 7.4's orchestration tools, all on the same
	 * Spring AI MCP server (docs/plan/phase-5.3-memory-reflection.md decision 12a). Null only when
	 * BOTH memory and service discovery are disabled — each tool set still self-gates on its own
	 * setting inside its method bodies, so one feature being off holds even when the other keeps
	 * this entry attached. Pointed at ourselves and authenticated with the same dashboard bearer
	 * token (decision 11).
	 */
	private ObjectNode memoryMcpServer() {
		if (!settings.current().memoryEnabled() && !settings.current().serviceDiscoveryEnabled()) {
			return null;
		}
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode memory = servers.putObject("memory");
		memory.put("type", "http").put("url", "http://127.0.0.1:" + serverPort + "/api/mcp/memory");
		if (props.authToken() != null && !props.authToken().isBlank()) {
			memory.putObject("headers").put("Authorization", "Bearer " + props.authToken());
		}
		return servers;
	}

	/** Layers the memory MCP server into a session's mcpConfig, same merge rule as {@link #withDefaultLinearMcp}. */
	JsonNode withDefaultMemoryMcp(JsonNode configured) {
		return withDefaultServer(configured, "memory", memoryMcpServer());
	}

	/** True if {@code entry} is the in-process memory server this backend generates (same URL), as opposed to a user-authored one. */
	private boolean isManagedMemoryEntry(JsonNode entry) {
		return entry != null && entry.path("url").asText("").equals("http://127.0.0.1:" + serverPort + "/api/mcp/memory");
	}

	/**
	 * The memory entry carries this backend's bearer token, which rotates on every restart (unless
	 * ARTUS_TOKEN is pinned), so a persisted copy goes stale and the server then fails its 401
	 * handshake. Re-stamps the current token onto a managed entry; user-authored entries (any other
	 * URL) and configs without one are returned untouched.
	 */
	JsonNode refreshManagedMcp(JsonNode mcpConfig) {
		if (!(mcpConfig instanceof ObjectNode existing) || !isManagedMemoryEntry(existing.get("memory"))) {
			return mcpConfig;
		}
		ObjectNode fresh = memoryMcpServer();
		if (fresh == null) {
			return mcpConfig;
		}
		ObjectNode refreshed = mapper.createObjectNode();
		refreshed.setAll(existing);
		refreshed.set("memory", fresh.get("memory"));
		return refreshed;
	}

	/**
	 * Substitutes {@code ${VAR}} references (catalog secrets, phase 26) from the backend's own
	 * environment at the moment the config file is written — the persisted {@code mcp_config} keeps
	 * the references, so no secret reaches the DB. A variable that is unset is left as written.
	 */
	JsonNode expandSecrets(JsonNode mcpConfig) {
		if (mcpConfig == null || mcpConfig.isNull() || toolCatalog == null) {
			return mcpConfig;
		}
		return ToolCatalog.expand(mcpConfig, toolCatalog.env(), mapper);
	}

	/**
	 * A session's mcpConfig minus the managed memory entry (re-layered with the current token at
	 * prepare()); null if nothing user-authored remains, so a copy doesn't pin a stale token via
	 * the "session's own key wins" rule in {@link #withDefaultServer}.
	 */
	private JsonNode withoutManagedMemoryServer(JsonNode mcpConfig) {
		if (mcpConfig == null || mcpConfig.isNull()) {
			return null;
		}
		if (!(mcpConfig instanceof ObjectNode existing) || !isManagedMemoryEntry(existing.get("memory"))) {
			return mcpConfig;
		}
		ObjectNode rest = existing.deepCopy();
		rest.remove("memory");
		return rest.isEmpty() ? null : rest;
	}

	/**
	 * Layers the session's code-intelligence MCP server — {@code serena}, {@code graphify} or
	 * {@code codegraph} per the resolved {@code codeIntel}, nothing for null — with the same merge
	 * rule as Linear/memory (the session's own entry under that key wins). Serena: docs/plan/
	 * phase-12-linear-cache-serena-context.md Track B decision 2, {@code --context} from the
	 * provider's own declared capability ({@link ProviderCapabilities#serenaContext()}), never a
	 * hardcoded provider name. Graphify: docs/plan/phase-13-graphify.md Step 2. CodeGraph:
	 * docs/plan/phase-14-codegraph.md Step 2.
	 */
	JsonNode withDefaultCodeIntelMcp(JsonNode configured, String provider, String codeIntel, String cwdPath,
									   UUID sessionId) {
		if (codeIntel == null) {
			return configured;
		}
		return switch (codeIntel) {
			case SettingsService.CODE_INTEL_SERENA ->
					withDefaultServer(configured, "serena", serenaMcpServer(provider, cwdPath));
			case SettingsService.CODE_INTEL_GRAPHIFY ->
					withDefaultServer(configured, "graphify", graphifyMcpServer(sessionId));
			case SettingsService.CODE_INTEL_CODEGRAPH ->
					withDefaultServer(configured, "codegraph", codegraphMcpServer(cwdPath));
			default -> configured;
		};
	}

	/**
	 * The graphify knowledge-graph MCP server block (docs/plan/phase-13-graphify.md Step 2):
	 * {@code graphify-mcp <graphDir>/graph.json} through the same reviewed-checkout {@code uv run}
	 * prefix as every other graphify process. No {@code env} — the server needs none. The graph
	 * file need not exist yet: the server starts regardless and its tools return a "not found"
	 * error until the background build (Step 3) writes it.
	 */
	private ObjectNode graphifyMcpServer(UUID sessionId) {
		if (!graphify.configured()) {
			return null;
		}
		List<String> base = graphify.baseCommand();
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode entry = servers.putObject("graphify");
		entry.put("command", base.getFirst());
		ArrayNode args = entry.putArray("args");
		base.subList(1, base.size()).forEach(args::add);
		args.add("graphify-mcp").add(graphify.graphFile(sessionId).toString());
		return servers;
	}

	private ObjectNode serenaMcpServer(String provider, String cwdPath) {
		if (!serena.configured()) {
			return null;
		}
		String context = catalog.get(provider).serenaContext();
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode serenaEntry = servers.putObject("serena");
		serenaEntry.put("command", serena.uvCommand());
		ArrayNode args = serenaEntry.putArray("args");
		args.add("run").add("--directory").add(serena.root()).add("serena").add("start-mcp-server")
				.add("--context").add(context).add("--project").add(cwdPath)
				.add("--open-web-dashboard").add("false");
		return servers;
	}

	/**
	 * The codegraph MCP server block (docs/plan/phase-14-codegraph.md Step 2): {@code node
	 * <root>/dist/bin/codegraph.js serve --mcp --path <cwdPath>} with the posture env (decision 5,
	 * set explicitly here rather than relying on the sidecar's own environment). The index (Step 2's
	 * {@link CodegraphService#index}) runs synchronously before this entry is written, so the graph
	 * already exists by the time the MCP server starts — unlike graphify's out-of-order tolerance,
	 * there's no "not found yet" race to design around here.
	 */
	private ObjectNode codegraphMcpServer(String cwdPath) {
		if (!codegraph.configured()) {
			return null;
		}
		List<String> base = codegraph.baseCommand();
		ObjectNode servers = mapper.createObjectNode();
		ObjectNode entry = servers.putObject("codegraph");
		entry.put("command", base.getFirst());
		ArrayNode args = entry.putArray("args");
		base.subList(1, base.size()).forEach(args::add);
		args.add("serve").add("--mcp").add("--path").add(cwdPath);
		ObjectNode env = entry.putObject("env");
		codegraph.postureEnv().forEach(env::put);
		return servers;
	}

	/**
	 * Mirrors {@link SessionEntity#cwdPath()}'s logic, computed before the entity exists — needed
	 * here because the Serena MCP entry's {@code --project} argument is baked into {@code
	 * mcpConfig} at prepare() time, ahead of the {@code SessionEntity.builder()} call below.
	 */
	private static String computeCwdPath(String repoPath, String servicePath, Path worktree) {
		String service = servicePath == null ? repoPath : servicePath;
		if (service.equals(repoPath)) {
			return worktree.toString();
		}
		Path relative = Path.of(repoPath).relativize(Path.of(service));
		return worktree.resolve(relative).toString();
	}

	/**
	 * Merges a default MCP {@code serverBlock} (itself a one-key {@code {"key": {...}}} object,
	 * or null if that default isn't configured/enabled) into a session's own {@code configured}
	 * mcpConfig under {@code key} — unless the session already defines that key itself, which
	 * always wins. Shared merge rule behind {@link #withDefaultLinearMcp}/{@link
	 * #withDefaultMemoryMcp} (docs/plan/phase-9-production-hardening.md G2).
	 */
	private JsonNode withDefaultServer(JsonNode configured, String key, ObjectNode serverBlock) {
		if (serverBlock == null) {
			return configured;
		}
		if (configured == null || configured.isNull()) {
			return serverBlock;
		}
		if (!(configured instanceof ObjectNode existing) || existing.has(key)) {
			return configured;
		}
		ObjectNode merged = mapper.createObjectNode();
		merged.setAll(existing);
		merged.setAll(serverBlock);
		return merged;
	}

	/**
	 * The episodic-window system-prompt block (docs/plan/phase-5.3-memory-reflection.md
	 * "Automatic episodic window" / decision 12b): announces the session's own id (needed for
	 * every memory tool call) and the last few episodes recorded for this service, if any. Null
	 * when memory is disabled or this isn't a real-repo user session (system sessions skip it).
	 */
	private String memorySystemPromptBlock(SessionEntity session) {
		if (!settings.current().memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Long-term memory tools are available (memory_tags, memory_search, memory_read). ")
				.append("Pass this as `sessionId` in every call: ").append(session.id());
		var recent = episodes.recentByService(session.servicePath(), 5);
		if (!recent.isEmpty()) {
			sb.append("\n\nRecent activity on this service, most recent first:\n");
			for (var ep : recent) {
				sb.append("- ").append(ep.summary()).append('\n');
			}
		}
		return sb.toString();
	}

	/**
	 * 7.4's orchestration-tools announcement — same MCP server as memory (decision 12a), so it's
	 * gated on the same {@code settings.current().memoryEnabled()}. A child session gets the report_result
	 * reminder instead of the spawn tools (it can't spawn — depth 1, enforced in the tool body
	 * too); a plain session with no ecosystem configured gets nothing, since list_services would
	 * just error for it.
	 */
	private String orchestrationSystemPromptBlock(SessionEntity session) {
		if (!settings.current().memoryEnabled() || !"user".equals(session.kind())) {
			return null;
		}
		if (session.parentSessionId() != null) {
			return "You are a child session (sessionId: " + session.id() + ") spawned to work on this service "
					+ "as part of a larger cross-service task. When your task is complete, call report_result "
					+ "with sessionId=" + session.id() + " and a concise summary of what you did — this reaches "
					+ "the parent session automatically.";
		}
		if (session.ecosystemPath() == null || session.ecosystemPath().isBlank()) {
			return null;
		}
		return "Multi-service orchestration tools are available (list_services, spawn_child_session, "
				+ "check_children) for tasks that span several services under this session's ecosystem folder. "
				+ "Pass this as `sessionId` in every call: " + session.id();
	}

	/**
	 * Claude sessions get Serena's own recommended system-prompt override appended (decision 3) —
	 * gated on the provider's declared {@code serenaContext} rather than a literal "claude" check,
	 * since that capability value IS what Serena's override text targets; Codex gets nothing here
	 * (its context differs and the override text is Claude-Code-specific).
	 */
	private String serenaSystemPromptBlock(SessionEntity session) {
		String context = catalog.get(session.provider()).serenaContext();
		if (!"claude-code".equals(context)) {
			return null;
		}
		return serena.ccSystemPromptOverride();
	}

	/**
	 * Serena → its own Claude-Code-only override (above); graphify/codegraph → our provider-neutral
	 * block for every provider (docs/plan/phase-13-graphify.md decision 10 / phase-14-codegraph.md
	 * decision 7 — the sidecars merge it into the system prompt/instructions alike); no tool →
	 * nothing.
	 */
	private String codeIntelSystemPromptBlock(SessionEntity session) {
		if (session.codeIntel() == null) {
			return null;
		}
		return switch (session.codeIntel()) {
			case SettingsService.CODE_INTEL_SERENA -> serenaSystemPromptBlock(session);
			case SettingsService.CODE_INTEL_GRAPHIFY -> GraphifyService.SYSTEM_PROMPT_BLOCK;
			case SettingsService.CODE_INTEL_CODEGRAPH -> CodegraphService.SYSTEM_PROMPT_BLOCK;
			default -> null;
		};
	}

	/**
	 * Proposal 8's review-role block: tells the agent its job is to read/analyze the reviewed
	 * branch against its base and submit findings through {@code submit_pr_review}, never to
	 * modify/commit/push it (that's also enforced structurally — detached checkout — and at the
	 * REST layer, but the agent needs to be told not to even try). Null for a development session.
	 */
	private String reviewSystemPromptBlock(SessionEntity session) {
		if (!"review".equals(session.sessionType())) {
			return null;
		}
		String prPart = session.prUrl() != null && !session.prUrl().isBlank()
				? ", for PR " + session.prUrl()
				: " — no PR is attached yet";
		return "You are a code-review session. Your task is to review the branch `" + session.branch()
				+ "` against `" + session.baseBranch() + "`" + prPart + ". Read and analyze; run builds or tests "
				+ "if useful. Do NOT modify the reviewed code, do not commit, push, or create pull requests — "
				+ "commit/push are disabled for this session. Use the review skills and agents available to you. "
				+ "When your review is complete, submit it with the `submit_pr_review` tool (summary + inline "
				+ "file/line comments); pass this as `sessionId`: " + session.id();
	}

	/** The extra system-prompt text a spawn should append (memory + orchestration + code-intel + review blocks), or null if all are empty. */
	String extraSystemPrompt(SessionEntity session) {
		String joined = Stream
				.of(memorySystemPromptBlock(session), orchestrationSystemPromptBlock(session),
						codeIntelSystemPromptBlock(session), reviewSystemPromptBlock(session))
				.filter(Objects::nonNull)
				.collect(Collectors.joining("\n\n"));
		return joined.isBlank() ? null : joined;
	}

	/** Copies a session's tunable config (not its identity: name/branch/repo/provider) into an overrides object. */
	ObjectNode configOverridesFrom(SessionEntity source) {
		ObjectNode overrides = mapper.createObjectNode();
		if (source.model() != null) {
			overrides.put("model", source.model());
		}
		overrides.put("permissionMode", source.permissionMode());
		if (!source.allowedTools().isEmpty()) {
			overrides.set("allowedTools", mapper.valueToTree(source.allowedTools()));
		}
		// always carried, even empty: an absent key would re-apply the default block list
		overrides.set("disallowedTools", mapper.valueToTree(source.disallowedTools()));
		JsonNode carriedMcp = withoutManagedMemoryServer(source.mcpConfig());
		if (carriedMcp != null) {
			overrides.set("mcpConfig", carriedMcp);
		}
		if (source.envVars() != null && !source.envVars().isNull()) {
			overrides.set("envVars", source.envVars());
		}
		if (source.skillSources() != null && source.skillSources().isArray() && !source.skillSources().isEmpty()) {
			overrides.set("skillSources", source.skillSources());
		}
		if (source.agentSources() != null && source.agentSources().isArray() && !source.agentSources().isEmpty()) {
			overrides.set("agentSources", source.agentSources());
		}
		if (source.instructions() != null) {
			overrides.put("instructions", source.instructions());
		}
		if (source.ecosystemPath() != null) {
			overrides.put("ecosystemPath", source.ecosystemPath());
		}
		// Raw (not the resolved servicePath()), so a polyrepo source (raw null) copies to a fresh
		// session that is ALSO byte-identical polyrepo config — no "servicePath" key at all — while a
		// genuine monorepo source reproduces its own package on duplicate()/quick-session (docs/plan/
		// phase-11-monorepo.md Step 3).
		if (source.rawServicePath() != null) {
			overrides.put("servicePath", source.rawServicePath());
		}
		if (!source.contextDirs().isEmpty()) {
			overrides.set("contextDirs", mapper.valueToTree(source.contextDirs()));
		}
		if (source.thinking() != null) {
			overrides.put("thinking", source.thinking());
		}
		if (source.effort() != null) {
			overrides.put("effort", source.effort());
		}
		if (source.maxTurns() != null) {
			overrides.put("maxTurns", source.maxTurns());
		}
		if (source.fallbackModel() != null) {
			overrides.put("fallbackModel", source.fallbackModel());
		}
		if (source.costBudgetUsd() != null) {
			overrides.put("costBudgetUsd", source.costBudgetUsd().toPlainString());
		}
		if (!source.tools().isEmpty()) {
			overrides.set("tools", mapper.valueToTree(source.tools()));
		}
		overrides.put("reflectionEnabled", source.reflectionEnabled());
		// the tool itself is re-resolved from the global selector at prepare() time (decision 5)
		overrides.put("codeIntelEnabled", source.codeIntel() != null);
		return overrides;
	}

	// package-private static (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T1
	static String fillPlaceholders(String prompt, Map<String, String> values) {
		if (prompt == null || values == null) {
			return prompt;
		}
		String filled = prompt;
		for (var entry : values.entrySet()) {
			filled = filled.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		return filled;
	}

	// --- config JSON accessors ---

	private static String text(ObjectNode node, String field, String fallback) {
		return node.hasNonNull(field) ? node.get(field).asText() : fallback;
	}

	private static String nullableText(ObjectNode node, String field) {
		return node.hasNonNull(field) ? node.get(field).asText() : null;
	}

	private static String nullableIfBlank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private List<String> stringList(ObjectNode node, String field) {
		if (!node.hasNonNull(field) || !node.get(field).isArray()) {
			return List.of();
		}
		return mapper.convertValue(node.get(field),
				mapper.getTypeFactory().constructCollectionType(List.class, String.class));
	}

	private JsonNode arrayOrEmpty(ObjectNode node, String field) {
		return node.hasNonNull(field) && node.get(field).isArray() ? node.get(field) : mapper.createArrayNode();
	}
}
