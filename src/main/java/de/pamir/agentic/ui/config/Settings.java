package de.pamir.agentic.ui.config;

/**
 * Typed snapshot of every persisted, UI-editable setting (Settings dialog) except per-provider
 * pricing ({@link SettingsService#pricingFor}, dynamically keyed on the provider id, so it can't
 * live as a fixed component here — see docs/plan/phase-10-review-followups.md R8c). {@code
 * systemProvider} holds the raw override (blank = "follow {@code defaultProvider}"), matching what
 * the Settings dialog needs to round-trip — {@link SettingsService#systemProvider()} resolves the
 * fallback for internal callers.
 */
public record Settings(
		boolean linearOAuthEnabled,
		String ticketImportSpec,
		String ecosystemRoot,
		String monorepoServiceGlobs,
		boolean monorepoDetectionEnabled,
		boolean prChecksEnabled,
		int prCheckPollIntervalSeconds,
		String librarySkillsRoot,
		String libraryAgentsRoot,
		boolean libraryVectorize,
		boolean librarySyncEnabled,
		int librarySyncIntervalMinutes,
		String defaultProvider,
		String systemProvider,
		String memoryRoot,
		boolean memoryEnabled,
		boolean memoryReflectionDefault,
		String memoryReflectionModel,
		int memorySyncIntervalMinutes,
		int memoryRetentionDays,
		boolean memoryReflectionApprovalRequired,
		boolean serviceDiscoveryEnabled,
		int serviceDiscoveryStalenessDays,
		String serviceDiscoveryModel,
		/** Widget chip warn threshold, 30-95 (docs/plan/phase-12-linear-cache-serena-context.md decision 10) */
		int contextWarnPercent,
		/** Serena checkout root; empty = Serena unavailable (docs/plan/phase-12-linear-cache-serena-context.md Track B) */
		String mcpSerenaRoot,
		/** {@code uv} command/path, for hosts where it isn't on the backend's PATH; defaults to "uv" */
		String mcpUvPath,
		/** graphify checkout root; empty = graphify unavailable (docs/plan/phase-13-graphify.md Step 1) */
		String mcpGraphifyRoot,
		/** codegraph checkout root; empty = codegraph unavailable (docs/plan/phase-14-codegraph.md Step 1) */
		String mcpCodegraphRoot,
		/**
		 * Which code-intelligence MCP tool sessions may opt into: {@code none}/{@code serena}/{@code
		 * graphify}/{@code codegraph} — one per install (phase-13 decision 1). Always resolved here:
		 * an unset selector reads {@code serena} when a Serena root is configured, else {@code none}
		 * (decision 12).
		 */
		String codeIntel,
		/** Comma-separated Linear MCP tool names sessions may call; blank = {@link SettingsService#DEFAULT_LINEAR_ALLOWED_TOOLS}. Baked into each new session. */
		String linearAllowedTools,
		/**
		 * Whether the bearer token is enforced (phase 17 B). Read once at startup by {@link
		 * AuthTokenService}, so a change applies on the next restart. Default: on when a token is
		 * configured, off otherwise.
		 */
		boolean authTokenRequired,
		/** Phase 18: whether new sessions start with Slack mirroring on (the create dialog can still override). */
		boolean slackDefaultEnabled,
		/** Phase 22: model tier ({@code cheap}/{@code standard}/{@code premium}) for Visualize diagram turns. */
		String visualizeModel,
		/** Phase 25: idle parking also hides the session from the dashboard (it stays resumable from the sidebar). */
		boolean hideOnPark,
		/** Phase 25: rename a session after its first turn while its name is still the branch name. */
		boolean autoTitle) {

	/** Pre-phase-25 arity (no {@code hideOnPark}/{@code autoTitle}); keeps fixed-settings test fixtures compiling. */
	public Settings(boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
					String monorepoServiceGlobs, boolean monorepoDetectionEnabled, boolean prChecksEnabled,
					int prCheckPollIntervalSeconds, String librarySkillsRoot, String libraryAgentsRoot,
					boolean libraryVectorize, boolean librarySyncEnabled, int librarySyncIntervalMinutes,
					String defaultProvider, String systemProvider, String memoryRoot, boolean memoryEnabled,
					boolean memoryReflectionDefault, String memoryReflectionModel, int memorySyncIntervalMinutes,
					int memoryRetentionDays, boolean memoryReflectionApprovalRequired,
					boolean serviceDiscoveryEnabled, int serviceDiscoveryStalenessDays,
					String serviceDiscoveryModel, int contextWarnPercent, String mcpSerenaRoot, String mcpUvPath,
					String mcpGraphifyRoot, String mcpCodegraphRoot, String codeIntel, String linearAllowedTools,
					boolean authTokenRequired, boolean slackDefaultEnabled, String visualizeModel) {
		this(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs, monorepoDetectionEnabled,
				prChecksEnabled, prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize,
				librarySyncEnabled, librarySyncIntervalMinutes, defaultProvider, systemProvider, memoryRoot,
				memoryEnabled, memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes,
				memoryRetentionDays, memoryReflectionApprovalRequired, serviceDiscoveryEnabled,
				serviceDiscoveryStalenessDays, serviceDiscoveryModel, contextWarnPercent, mcpSerenaRoot, mcpUvPath,
				mcpGraphifyRoot, mcpCodegraphRoot, codeIntel, linearAllowedTools, authTokenRequired,
				slackDefaultEnabled, visualizeModel, false, true);
	}

	/** Pre-phase-22 arity (no {@code visualizeModel}); keeps fixed-settings test fixtures compiling. */
	public Settings(boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
					String monorepoServiceGlobs, boolean monorepoDetectionEnabled, boolean prChecksEnabled,
					int prCheckPollIntervalSeconds, String librarySkillsRoot, String libraryAgentsRoot,
					boolean libraryVectorize, boolean librarySyncEnabled, int librarySyncIntervalMinutes,
					String defaultProvider, String systemProvider, String memoryRoot, boolean memoryEnabled,
					boolean memoryReflectionDefault, String memoryReflectionModel, int memorySyncIntervalMinutes,
					int memoryRetentionDays, boolean memoryReflectionApprovalRequired,
					boolean serviceDiscoveryEnabled, int serviceDiscoveryStalenessDays,
					String serviceDiscoveryModel, int contextWarnPercent, String mcpSerenaRoot, String mcpUvPath,
					String mcpGraphifyRoot, String mcpCodegraphRoot, String codeIntel, String linearAllowedTools,
					boolean authTokenRequired, boolean slackDefaultEnabled) {
		this(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs, monorepoDetectionEnabled,
				prChecksEnabled, prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize,
				librarySyncEnabled, librarySyncIntervalMinutes, defaultProvider, systemProvider, memoryRoot,
				memoryEnabled, memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes,
				memoryRetentionDays, memoryReflectionApprovalRequired, serviceDiscoveryEnabled,
				serviceDiscoveryStalenessDays, serviceDiscoveryModel, contextWarnPercent, mcpSerenaRoot, mcpUvPath,
				mcpGraphifyRoot, mcpCodegraphRoot, codeIntel, linearAllowedTools, authTokenRequired,
				slackDefaultEnabled, "standard");
	}

	/** Pre-phase-17 arity (no {@code authTokenRequired}); keeps fixed-settings test fixtures compiling. */
	public Settings(boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
					String monorepoServiceGlobs, boolean monorepoDetectionEnabled, boolean prChecksEnabled,
					int prCheckPollIntervalSeconds, String librarySkillsRoot, String libraryAgentsRoot,
					boolean libraryVectorize, boolean librarySyncEnabled, int librarySyncIntervalMinutes,
					String defaultProvider, String systemProvider, String memoryRoot, boolean memoryEnabled,
					boolean memoryReflectionDefault, String memoryReflectionModel, int memorySyncIntervalMinutes,
					int memoryRetentionDays, boolean memoryReflectionApprovalRequired,
					boolean serviceDiscoveryEnabled, int serviceDiscoveryStalenessDays,
					String serviceDiscoveryModel, int contextWarnPercent, String mcpSerenaRoot, String mcpUvPath,
					String mcpGraphifyRoot, String mcpCodegraphRoot, String codeIntel, String linearAllowedTools) {
		this(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs, monorepoDetectionEnabled,
				prChecksEnabled, prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize,
				librarySyncEnabled, librarySyncIntervalMinutes, defaultProvider, systemProvider, memoryRoot,
				memoryEnabled, memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes,
				memoryRetentionDays, memoryReflectionApprovalRequired, serviceDiscoveryEnabled,
				serviceDiscoveryStalenessDays, serviceDiscoveryModel, contextWarnPercent, mcpSerenaRoot, mcpUvPath,
				mcpGraphifyRoot, mcpCodegraphRoot, codeIntel, linearAllowedTools, false, false);
	}

	/** Pre-phase-18 arity (no {@code slackDefaultEnabled}); keeps fixed-settings test fixtures compiling. */
	public Settings(boolean linearOAuthEnabled, String ticketImportSpec, String ecosystemRoot,
					String monorepoServiceGlobs, boolean monorepoDetectionEnabled, boolean prChecksEnabled,
					int prCheckPollIntervalSeconds, String librarySkillsRoot, String libraryAgentsRoot,
					boolean libraryVectorize, boolean librarySyncEnabled, int librarySyncIntervalMinutes,
					String defaultProvider, String systemProvider, String memoryRoot, boolean memoryEnabled,
					boolean memoryReflectionDefault, String memoryReflectionModel, int memorySyncIntervalMinutes,
					int memoryRetentionDays, boolean memoryReflectionApprovalRequired,
					boolean serviceDiscoveryEnabled, int serviceDiscoveryStalenessDays,
					String serviceDiscoveryModel, int contextWarnPercent, String mcpSerenaRoot, String mcpUvPath,
					String mcpGraphifyRoot, String mcpCodegraphRoot, String codeIntel, String linearAllowedTools,
					boolean authTokenRequired) {
		this(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs, monorepoDetectionEnabled,
				prChecksEnabled, prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize,
				librarySyncEnabled, librarySyncIntervalMinutes, defaultProvider, systemProvider, memoryRoot,
				memoryEnabled, memoryReflectionDefault, memoryReflectionModel, memorySyncIntervalMinutes,
				memoryRetentionDays, memoryReflectionApprovalRequired, serviceDiscoveryEnabled,
				serviceDiscoveryStalenessDays, serviceDiscoveryModel, contextWarnPercent, mcpSerenaRoot, mcpUvPath,
				mcpGraphifyRoot, mcpCodegraphRoot, codeIntel, linearAllowedTools, authTokenRequired, false);
	}
}
