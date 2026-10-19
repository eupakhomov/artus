package de.pamir.agentic.ui.config;

import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Persisted, UI-editable settings (Settings dialog). Deliberately not a secret store: the Linear
 * API key stays in AppProperties/env, never touches app_setting.
 *
 * <p>{@link #current()} returns one {@link Settings} snapshot, built from a single {@link
 * SettingsRepository#all()} query and cached until the next {@link #apply}. Adding a setting is two
 * edits: a component on {@link Settings}/{@link SettingsPatch}, and a row in {@link #fields} below —
 * {@link #current()} and {@link #apply} both pick it up automatically, and so does the controller's
 * JSON view (see docs/plan/phase-10-review-followups.md R8c). Per-provider pricing ({@link
 * #pricingFor}) is dynamically keyed and stays outside this table, as does {@link #systemProvider()}
 * (the resolved form — {@link Settings#systemProvider()} holds the raw override).
 */
@Service
public class SettingsService {

	private static final String LINEAR_OAUTH_KEY = "linear.oauth";
	private static final String LINEAR_ALLOWED_TOOLS_KEY = "linear.allowed-tools";
	/** Read tools plus comment — enough to work from a ticket; everything else stays blocked. */
	public static final String DEFAULT_LINEAR_ALLOWED_TOOLS =
			"get_issue,list_issues,list_comments,get_attachment,extract_images,save_comment";
	private static final String TICKET_IMPORT_SPEC_KEY = "ticket-import.spec";
	private static final String ECOSYSTEM_ROOT_KEY = "ecosystem.root";
	private static final String MONOREPO_SERVICE_GLOBS_KEY = "ecosystem.monorepo-service-globs";
	private static final String DEFAULT_MONOREPO_SERVICE_GLOBS = "packages/*,services/*,apps/*,libs/*";
	private static final String MONOREPO_DETECTION_ENABLED_KEY = "ecosystem.monorepo-detection-enabled";
	private static final String PR_CHECKS_ENABLED_KEY = "pr-checks.enabled";
	private static final String PR_CHECKS_POLL_INTERVAL_KEY = "pr-checks.poll-interval-seconds";
	private static final String LIBRARY_SKILLS_ROOT_KEY = "library.skills-root";
	private static final String LIBRARY_AGENTS_ROOT_KEY = "library.agents-root";
	private static final String LIBRARY_VECTORIZE_KEY = "library.vectorize";
	private static final String LIBRARY_SYNC_ENABLED_KEY = "library.sync-enabled";
	private static final String LIBRARY_SYNC_INTERVAL_KEY = "library.sync-interval-minutes";
	private static final String DEFAULT_PROVIDER_KEY = "session.default-provider";
	private static final String SYSTEM_PROVIDER_KEY = "session.system-provider";
	private static final String PRICING_KEY_SUFFIX = ".pricing";
	private static final Set<String> VALID_TIERS = Set.of("cheap", "standard", "premium");
	/**
	 * memory.reflection-model / service-discovery.model used to store a raw Claude alias
	 * ("haiku"/"sonnet"/"opus") passed straight into set_model; normalizing legacy values here
	 * keeps a pre-existing install's persisted choice meaningful now that these settings are
	 * provider-neutral tier names (see docs/plan/phase-9-production-hardening.md P1/P3).
	 */
	private static final Map<String, String> LEGACY_MODEL_ALIAS_TIER =
			Map.of("haiku", "cheap", "sonnet", "standard", "opus", "premium");
	/**
	 * Seed pricing for "codex", the one provider that ships without a per-turn USD figure today
	 * (see docs/plan/phase-5.13-codex-provider.md Decision 2) — a manually maintained,
	 * Settings-editable estimate, not tied to any real billing API. "default" is the fallback
	 * entry for a model with no specific row. Any other provider declaring {@code
	 * reportsCostUsd: false} (docs/plan/phase-10-review-followups.md R1) starts from an empty
	 * table (no default seed) until someone edits it — {@link
	 * de.pamir.agentic.ui.session.CodexCostEstimator#estimate} already treats a missing rate as
	 * zero cost, so this is safe.
	 */
	private static final String DEFAULT_CODEX_PRICING =
			"{\"default\": {\"inputPer1M\": 2, \"cachedInputPer1M\": 0.5, \"outputPer1M\": 8}}";
	/**
	 * Seed pricing for "claude" (phase 25 step 3): Claude reports its own authoritative per-turn
	 * USD, so this table only prices the live, mid-turn estimate shown while a turn runs
	 * ({@link de.pamir.agentic.ui.session.TokenCostEstimator}). A model id matches its longest key
	 * prefix ({@code claude-sonnet-5} covers {@code claude-sonnet-5[1m]} and {@code
	 * claude-sonnet-5-5}). First-party list prices per million tokens as of 2026-10; cache writes
	 * at 1.25x (5 min) and 2x (1 h) input.
	 */
	private static final String DEFAULT_CLAUDE_PRICING = """
			{
			  "claude-opus-5-5": {"inputPer1M": 4, "outputPer1M": 20, "cacheReadPer1M": 0.2, "cacheWrite5mPer1M": 5, "cacheWrite1hPer1M": 8},
			  "claude-opus-5": {"inputPer1M": 5, "outputPer1M": 25, "cacheReadPer1M": 0.5, "cacheWrite5mPer1M": 6.25, "cacheWrite1hPer1M": 10},
			  "claude-sonnet-5": {"inputPer1M": 2, "outputPer1M": 10, "cacheReadPer1M": 0.2, "cacheWrite5mPer1M": 2.5, "cacheWrite1hPer1M": 4},
			  "claude-haiku-5-5": {"inputPer1M": 0.1, "outputPer1M": 0.5, "cacheReadPer1M": 0.01, "cacheWrite5mPer1M": 0.125, "cacheWrite1hPer1M": 0.2},
			  "claude-haiku-4-5": {"inputPer1M": 1, "outputPer1M": 5, "cacheReadPer1M": 0.1, "cacheWrite5mPer1M": 1.25, "cacheWrite1hPer1M": 2},
			  "default": {"inputPer1M": 2, "outputPer1M": 10, "cacheReadPer1M": 0.2, "cacheWrite5mPer1M": 2.5, "cacheWrite1hPer1M": 4}
			}
			""";
	private static final String EMPTY_PRICING = "{}";
	/** {@link #pricingFor} results per provider, dropped by {@link #setPricingFor}. */
	private final Map<String, String> pricingCache = new ConcurrentHashMap<>();
	private static final String MEMORY_ROOT_KEY = "memory.root";
	private static final String MEMORY_ENABLED_KEY = "memory.enabled";
	private static final String SLACK_DEFAULT_ENABLED_KEY = "slack.default-enabled";
	private static final String HIDE_ON_PARK_KEY = "session.hide-on-park";
	private static final String AUTO_TITLE_KEY = "session.auto-title";
	private static final String MEMORY_REFLECTION_DEFAULT_KEY = "memory.reflection-default";
	private static final String MEMORY_REFLECTION_MODEL_KEY = "memory.reflection-model";
	private static final String MEMORY_SYNC_INTERVAL_KEY = "memory.sync-interval-minutes";
	private static final String MEMORY_RETENTION_DAYS_KEY = "memory.retention-days";
	private static final String MEMORY_APPROVAL_REQUIRED_KEY = "memory.reflection-approval-required";
	private static final String SERVICE_DISCOVERY_ENABLED_KEY = "service-discovery.enabled";
	private static final String SERVICE_DISCOVERY_STALENESS_DAYS_KEY = "service-discovery.staleness-days";
	private static final String SERVICE_DISCOVERY_MODEL_KEY = "service-discovery.model";
	private static final String VISUALIZE_MODEL_KEY = "visualize.model";
	private static final String CONTEXT_WARN_PERCENT_KEY = "session.context-warn-percent";
	private static final String MCP_SERENA_ROOT_KEY = "mcp.serena-root";
	private static final String MCP_UV_PATH_KEY = "mcp.uv-path";
	private static final String MCP_GRAPHIFY_ROOT_KEY = "mcp.graphify-root";
	private static final String MCP_CODEGRAPH_ROOT_KEY = "mcp.codegraph-root";
	private static final String CODE_INTEL_KEY = "mcp.code-intel";
	private static final String AUTH_TOKEN_REQUIRED_KEY = "auth.token-required";
	public static final String CODE_INTEL_NONE = "none";
	public static final String CODE_INTEL_SERENA = "serena";
	public static final String CODE_INTEL_GRAPHIFY = "graphify";
	public static final String CODE_INTEL_CODEGRAPH = "codegraph";
	public static final Set<String> CODE_INTEL_VALUES =
			Set.of(CODE_INTEL_NONE, CODE_INTEL_SERENA, CODE_INTEL_GRAPHIFY, CODE_INTEL_CODEGRAPH);

	/** One row per {@link Settings}/{@link SettingsPatch} component — see the class doc. */
	private record Field<T>(String key, Supplier<T> defaultValue, Function<String, T> parse,
							 Function<T, String> serialize, UnaryOperator<T> normalize,
							 Function<SettingsPatch, T> fromPatch) {

		T resolve(Map<String, String> raw) {
			String v = raw.get(key);
			T value = (v == null || v.isBlank()) ? defaultValue.get() : parse.apply(v);
			return normalize.apply(value);
		}

		void applyIfPresent(SettingsRepository repo, SettingsPatch patch) {
			T v = fromPatch.apply(patch);
			if (v != null) {
				repo.set(key, serialize.apply(normalize.apply(v)));
			}
		}
	}

	private static Field<Boolean> boolField(String key, boolean def, Function<SettingsPatch, Boolean> fromPatch) {
		return new Field<>(key, () -> def, Boolean::parseBoolean, Object::toString, UnaryOperator.identity(),
				fromPatch);
	}

	private static Field<Integer> intField(String key, int def, int min, Function<SettingsPatch, Integer> fromPatch) {
		return new Field<>(key, () -> def, Integer::parseInt, Object::toString, v -> Math.max(v, min), fromPatch);
	}

	private static Field<String> strField(String key, Supplier<String> def, Function<SettingsPatch, String> fromPatch) {
		return new Field<>(key, def, Function.identity(), Function.identity(), v -> v == null ? "" : v, fromPatch);
	}

	private static Field<String> tierField(String key, Function<SettingsPatch, String> fromPatch) {
		return new Field<>(key, () -> "", Function.identity(), Function.identity(), SettingsService::normalizeTier,
				fromPatch);
	}

	/** Like {@link #tierField} but an unset/blank value reads as {@code def} instead of "". */
	private static Field<String> tierField(String key, String def, Function<SettingsPatch, String> fromPatch) {
		return new Field<>(key, () -> def, Function.identity(), Function.identity(),
				v -> v == null || v.isBlank() ? def : normalizeTier(v), fromPatch);
	}

	private final Field<Boolean> linearOAuthEnabled = boolField(LINEAR_OAUTH_KEY, false, SettingsPatch::linearOAuthEnabled);
	private final Field<String> linearAllowedTools =
			strField(LINEAR_ALLOWED_TOOLS_KEY, () -> DEFAULT_LINEAR_ALLOWED_TOOLS, SettingsPatch::linearAllowedTools);
	private final Field<String> ticketImportSpec = strField(TICKET_IMPORT_SPEC_KEY, () -> "", SettingsPatch::ticketImportSpec);
	private final Field<String> ecosystemRoot = strField(ECOSYSTEM_ROOT_KEY, () -> "", SettingsPatch::ecosystemRoot);
	private final Field<String> monorepoServiceGlobs = strField(MONOREPO_SERVICE_GLOBS_KEY,
			() -> DEFAULT_MONOREPO_SERVICE_GLOBS, SettingsPatch::monorepoServiceGlobs);
	/** Off by default: a repo is one poly-repo service unless explicitly opted into workspace detection. */
	private final Field<Boolean> monorepoDetectionEnabled =
			boolField(MONOREPO_DETECTION_ENABLED_KEY, false, SettingsPatch::monorepoDetectionEnabled);
	private final Field<Boolean> prChecksEnabled = boolField(PR_CHECKS_ENABLED_KEY, true, SettingsPatch::prChecksEnabled);
	private final Field<Integer> prCheckPollIntervalSeconds =
			intField(PR_CHECKS_POLL_INTERVAL_KEY, 180, 30, SettingsPatch::prCheckPollIntervalSeconds);
	private final Field<String> librarySkillsRoot;
	private final Field<String> libraryAgentsRoot =
			strField(LIBRARY_AGENTS_ROOT_KEY, () -> System.getProperty("user.home") + "/claude-agents",
					SettingsPatch::libraryAgentsRoot);
	private final Field<Boolean> libraryVectorize = boolField(LIBRARY_VECTORIZE_KEY, false, SettingsPatch::libraryVectorize);
	private final Field<Boolean> librarySyncEnabled = boolField(LIBRARY_SYNC_ENABLED_KEY, true, SettingsPatch::librarySyncEnabled);
	private final Field<Integer> librarySyncIntervalMinutes =
			intField(LIBRARY_SYNC_INTERVAL_KEY, 60, 5, SettingsPatch::librarySyncIntervalMinutes);
	private final Field<String> defaultProvider = new Field<>(DEFAULT_PROVIDER_KEY, () -> "claude",
			Function.identity(), Function.identity(), v -> v == null || v.isBlank() ? "claude" : v,
			SettingsPatch::defaultProvider);
	private final Field<String> systemProviderField = strField(SYSTEM_PROVIDER_KEY, () -> "", SettingsPatch::systemProvider);
	private final Field<String> memoryRoot;
	/** Phase 17 B. Default tracks whether a token is configured, so existing installs behave unchanged. */
	private final Field<Boolean> authTokenRequired;
	private final Field<Boolean> memoryEnabled = boolField(MEMORY_ENABLED_KEY, true, SettingsPatch::memoryEnabled);
	/** Phase 18: new sessions start with Slack mirroring on. Off by default — content leaves the machine. */
	private final Field<Boolean> slackDefaultEnabled =
			boolField(SLACK_DEFAULT_ENABLED_KEY, false, SettingsPatch::slackDefaultEnabled);
	private final Field<Boolean> hideOnPark = boolField(HIDE_ON_PARK_KEY, false, SettingsPatch::hideOnPark);
	private final Field<Boolean> autoTitle = boolField(AUTO_TITLE_KEY, true, SettingsPatch::autoTitle);
	private final Field<Boolean> memoryReflectionDefault =
			boolField(MEMORY_REFLECTION_DEFAULT_KEY, false, SettingsPatch::memoryReflectionDefault);
	private final Field<String> memoryReflectionModel = tierField(MEMORY_REFLECTION_MODEL_KEY, SettingsPatch::memoryReflectionModel);
	private final Field<Integer> memorySyncIntervalMinutes =
			intField(MEMORY_SYNC_INTERVAL_KEY, 5, 1, SettingsPatch::memorySyncIntervalMinutes);
	private final Field<Integer> memoryRetentionDays = intField(MEMORY_RETENTION_DAYS_KEY, 0, 0, SettingsPatch::memoryRetentionDays);
	private final Field<Boolean> memoryReflectionApprovalRequired =
			boolField(MEMORY_APPROVAL_REQUIRED_KEY, true, SettingsPatch::memoryReflectionApprovalRequired);
	private final Field<Boolean> serviceDiscoveryEnabled =
			boolField(SERVICE_DISCOVERY_ENABLED_KEY, true, SettingsPatch::serviceDiscoveryEnabled);
	private final Field<Integer> serviceDiscoveryStalenessDays =
			intField(SERVICE_DISCOVERY_STALENESS_DAYS_KEY, 14, 1, SettingsPatch::serviceDiscoveryStalenessDays);
	private final Field<String> serviceDiscoveryModel = tierField(SERVICE_DISCOVERY_MODEL_KEY, SettingsPatch::serviceDiscoveryModel);
	/** Phase 22: reading a diff and choosing a structure is a reasoning task, so default to {@code standard}. */
	private final Field<String> visualizeModel = tierField(VISUALIZE_MODEL_KEY, "standard", SettingsPatch::visualizeModel);
	/** decision 10: one number, same meaning everywhere — floor 30 (a lower bar is noise), ceiling 95
	 * (past that there's no runway left to act on the nudge). */
	private final Field<Integer> contextWarnPercent = new Field<>(CONTEXT_WARN_PERCENT_KEY, () -> 70,
			Integer::parseInt, Object::toString, v -> Math.min(95, Math.max(30, v)), SettingsPatch::contextWarnPercent);
	private final Field<String> mcpSerenaRoot = strField(MCP_SERENA_ROOT_KEY, () -> "", SettingsPatch::mcpSerenaRoot);
	private final Field<String> mcpUvPath = strField(MCP_UV_PATH_KEY, () -> "uv", SettingsPatch::mcpUvPath);
	private final Field<String> mcpGraphifyRoot = strField(MCP_GRAPHIFY_ROOT_KEY, () -> "", SettingsPatch::mcpGraphifyRoot);
	private final Field<String> mcpCodegraphRoot = strField(MCP_CODEGRAPH_ROOT_KEY, () -> "", SettingsPatch::mcpCodegraphRoot);
	/**
	 * Stored raw: "" = unset. An unknown value normalizes to "" (unset) rather than failing — the
	 * controller rejects it up front; this just keeps a hand-edited row from breaking {@link
	 * #current()}. The resolved value (decision 12's default) is computed in {@link #current()},
	 * since a {@code Field} default can't see another key.
	 */
	private final Field<String> codeIntelField = new Field<>(CODE_INTEL_KEY, () -> "", Function.identity(),
			Function.identity(), v -> normalizeCodeIntel(v).orElse(""), SettingsPatch::codeIntel);

	private final List<Field<?>> fields;

	private final SettingsRepository repo;
	private final AppProperties props;
	private final ObjectMapper mapper;

	/** Cleared by {@link #apply}; single-user/LAN posture makes the invalidation window harmless. */
	private volatile Settings cache;

	public SettingsService(SettingsRepository repo, AppProperties props, ObjectMapper mapper) {
		this.repo = repo;
		this.props = props;
		this.mapper = mapper;
		this.librarySkillsRoot = strField(LIBRARY_SKILLS_ROOT_KEY, () -> props == null ? "" : props.skillsRoot(),
				SettingsPatch::librarySkillsRoot);
		this.authTokenRequired = new Field<>(AUTH_TOKEN_REQUIRED_KEY,
				() -> props != null && props.authToken() != null && !props.authToken().isBlank(),
				Boolean::parseBoolean, Object::toString, UnaryOperator.identity(), SettingsPatch::authTokenRequired);
		this.memoryRoot = strField(MEMORY_ROOT_KEY, () -> props == null ? "" : props.memoryRoot(), SettingsPatch::memoryRoot);
		this.fields = List.of(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs,
				monorepoDetectionEnabled, prChecksEnabled,
				prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize, librarySyncEnabled,
				librarySyncIntervalMinutes, defaultProvider, systemProviderField, memoryRoot, memoryEnabled,
				memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes, memoryRetentionDays,
				memoryReflectionApprovalRequired, serviceDiscoveryEnabled, serviceDiscoveryStalenessDays,
				serviceDiscoveryModel, contextWarnPercent, mcpSerenaRoot, mcpUvPath, mcpGraphifyRoot,
				mcpCodegraphRoot, codeIntelField, linearAllowedTools, authTokenRequired, slackDefaultEnabled, visualizeModel,
				hideOnPark, autoTitle);
	}

	/** One snapshot of every setting in {@link #fields}, cached until the next {@link #apply}. */
	public Settings current() {
		Settings c = cache;
		if (c != null) {
			return c;
		}
		Map<String, String> raw = repo.all();
		String serenaRoot = mcpSerenaRoot.resolve(raw);
		String storedCodeIntel = codeIntelField.resolve(raw);
		Settings built = new Settings(
				linearOAuthEnabled.resolve(raw),
				ticketImportSpec.resolve(raw),
				ecosystemRoot.resolve(raw),
				monorepoServiceGlobs.resolve(raw),
				monorepoDetectionEnabled.resolve(raw),
				prChecksEnabled.resolve(raw),
				prCheckPollIntervalSeconds.resolve(raw),
				librarySkillsRoot.resolve(raw),
				libraryAgentsRoot.resolve(raw),
				libraryVectorize.resolve(raw),
				librarySyncEnabled.resolve(raw),
				librarySyncIntervalMinutes.resolve(raw),
				defaultProvider.resolve(raw),
				systemProviderField.resolve(raw),
				memoryRoot.resolve(raw),
				memoryEnabled.resolve(raw),
				memoryReflectionDefault.resolve(raw),
				memoryReflectionModel.resolve(raw),
				memorySyncIntervalMinutes.resolve(raw),
				memoryRetentionDays.resolve(raw),
				memoryReflectionApprovalRequired.resolve(raw),
				serviceDiscoveryEnabled.resolve(raw),
				serviceDiscoveryStalenessDays.resolve(raw),
				serviceDiscoveryModel.resolve(raw),
				contextWarnPercent.resolve(raw),
				serenaRoot,
				mcpUvPath.resolve(raw),
				mcpGraphifyRoot.resolve(raw),
				mcpCodegraphRoot.resolve(raw),
				storedCodeIntel.isBlank() ? defaultCodeIntel(serenaRoot) : storedCodeIntel,
				linearAllowedTools.resolve(raw),
				authTokenRequired.resolve(raw),
				slackDefaultEnabled.resolve(raw),
				visualizeModel.resolve(raw),
				hideOnPark.resolve(raw),
				autoTitle.resolve(raw));
		cache = built;
		return built;
	}

	/**
	 * The {@code mcp.code-intel} row as stored (empty = never set, so {@link Settings#codeIntel()}
	 * is showing decision 12's computed default). The controller needs the distinction: blanking
	 * the Serena root on an install that never touched the selector just flips that default to
	 * {@code none}, whereas blanking the root of an explicitly selected tool is refused.
	 */
	public Optional<String> storedCodeIntel() {
		return repo.get(CODE_INTEL_KEY).flatMap(SettingsService::normalizeCodeIntel);
	}

	/** Decision 12: an install already using Serena keeps it without a Settings visit; nobody gets graphify by accident. */
	public static String defaultCodeIntel(String serenaRoot) {
		return serenaRoot == null || serenaRoot.isBlank() ? CODE_INTEL_NONE : CODE_INTEL_SERENA;
	}

	/**
	 * Validates a selector against the roots as they will be once a patch is applied (the controller
	 * passes post-patch values): a tool can't be selected without its root, and the selected tool's
	 * root can't be blanked ("select none first"). Throws {@link IllegalArgumentException} (→ 400).
	 */
	public static void validateCodeIntel(String selector, String serenaRoot, String graphifyRoot, String codegraphRoot) {
		String tool = normalizeCodeIntel(selector).orElseThrow(() -> new IllegalArgumentException(
				"Code intelligence must be one of none/serena/graphify/codegraph, got: " + selector));
		boolean serenaBlank = serenaRoot == null || serenaRoot.isBlank();
		boolean graphifyBlank = graphifyRoot == null || graphifyRoot.isBlank();
		boolean codegraphBlank = codegraphRoot == null || codegraphRoot.isBlank();
		if (CODE_INTEL_SERENA.equals(tool) && serenaBlank) {
			throw new IllegalArgumentException(
					"Serena is the selected code-intelligence tool and needs a Serena root — set one, or select none first");
		}
		if (CODE_INTEL_GRAPHIFY.equals(tool) && graphifyBlank) {
			throw new IllegalArgumentException(
					"Graphify is the selected code-intelligence tool and needs a Graphify root — set one, or select none first");
		}
		if (CODE_INTEL_CODEGRAPH.equals(tool) && codegraphBlank) {
			throw new IllegalArgumentException(
					"CodeGraph is the selected code-intelligence tool and needs a CodeGraph root — set one, or select none first");
		}
	}

	/** Case-insensitive, trimmed; empty for blank/unknown so callers decide between "unset" and "invalid". */
	private static Optional<String> normalizeCodeIntel(String raw) {
		String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
		return CODE_INTEL_VALUES.contains(v) ? Optional.of(v) : Optional.empty();
	}

	/** Applies every non-null field of {@code patch}; {@code null} means "leave this setting alone". */
	public void apply(SettingsPatch patch) {
		for (Field<?> field : fields) {
			field.applyIfPresent(repo, patch);
		}
		if (patch.codexPricing() != null) {
			setPricingFor("codex", patch.codexPricing());
		}
		if (patch.claudePricing() != null) {
			setPricingFor("claude", patch.claudePricing());
		}
		cache = null;
	}

	/**
	 * Provider the singleton system session (ticket import, library AI-fill, reflection, service
	 * discovery, commit/PR drafting, handoff briefs — see docs/plan/phase-9-production-hardening.md
	 * P1) spawns as; an empty override (the default) means "follow {@link Settings#defaultProvider()}".
	 * Note a Codex system session gets no MCP tool pre-approval (Codex rejects {@code allowedTools}
	 * outright — decision 10 in phase-5.13-codex-provider.md), so a backend-initiated turn needing
	 * Linear/memory tools has nobody to answer the resulting approval prompt and will simply time out.
	 */
	public String systemProvider() {
		Settings s = current();
		return s.systemProvider().isBlank() ? s.defaultProvider() : s.systemProvider();
	}

	/** Phase 25: whether {@link de.pamir.agentic.ui.session.AutoTitleService} may rename sessions. */
	public boolean autoTitle() {
		return current().autoTitle();
	}

	/**
	 * Per-model $-per-million-tokens rate table used to estimate a turn's cost for any provider
	 * that reports no USD of its own ({@code reportsCostUsd: false} — see {@link
	 * de.pamir.agentic.ui.session.SessionService#applyEstimatedCost}). Key is {@code
	 * "<provider>.pricing"} — for {@code "codex"} this is the same literal {@code codex.pricing}
	 * key used before the R1 generalization, so existing installs need no migration.
	 */
	public String pricingFor(String provider) {
		// read on every turn_usage event while a turn runs (phase 25), so not a DB round trip each time
		return pricingCache.computeIfAbsent(provider, p -> repo.get(p + PRICING_KEY_SUFFIX)
				.filter(v -> !v.isBlank()).orElse(defaultPricing(p)));
	}

	public void setPricingFor(String provider, String json) {
		String key = provider + PRICING_KEY_SUFFIX;
		pricingCache.remove(provider);
		if (json == null || json.isBlank()) {
			repo.set(key, defaultPricing(provider));
			return;
		}
		boolean isObject;
		try {
			isObject = mapper.readTree(json).isObject();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException(provider + " pricing is not valid JSON: " + e.getMessage());
		}
		if (!isObject) {
			throw new IllegalArgumentException(provider + " pricing must be a JSON object");
		}
		repo.set(key, json);
	}

	private static String defaultPricing(String provider) {
		return switch (provider) {
			case "codex" -> DEFAULT_CODEX_PRICING;
			case "claude" -> DEFAULT_CLAUDE_PRICING;
			default -> EMPTY_PRICING;
		};
	}

	/** Accepts a tier name as-is, maps a legacy raw Claude alias to its tier, else defaults to "cheap". */
	private static String normalizeTier(String raw) {
		String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
		if (VALID_TIERS.contains(v)) {
			return v;
		}
		return LEGACY_MODEL_ALIAS_TIER.getOrDefault(v, "cheap");
	}
}
