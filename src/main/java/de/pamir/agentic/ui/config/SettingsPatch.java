package de.pamir.agentic.ui.config;

/**
 * A partial update to {@link Settings}: every component is boxed/nullable, {@code null} meaning
 * "leave this setting alone" (mirrors {@link Settings} field-for-field). {@code codexPricing} rides
 * along as an extra field — pricing is provider-keyed, not part of {@link Settings} itself, but the
 * Settings dialog still edits it as one flat field (R1's decision to leave the UI un-generalized).
 * Bound straight from the {@code PATCH /api/settings} request body by Jackson; the nested
 * {@link Builder} exists purely so tests can construct a single-field patch without a 23-argument
 * positional call.
 */
public record SettingsPatch(
		Boolean linearOAuthEnabled,
		String ticketImportSpec,
		String ecosystemRoot,
		String monorepoServiceGlobs,
		Boolean monorepoDetectionEnabled,
		Boolean prChecksEnabled,
		Integer prCheckPollIntervalSeconds,
		String librarySkillsRoot,
		String libraryAgentsRoot,
		Boolean libraryVectorize,
		Boolean librarySyncEnabled,
		Integer librarySyncIntervalMinutes,
		String defaultProvider,
		String systemProvider,
		String codexPricing,
		String memoryRoot,
		Boolean memoryEnabled,
		Boolean memoryReflectionDefault,
		String memoryReflectionModel,
		Integer memorySyncIntervalMinutes,
		Integer memoryRetentionDays,
		Boolean memoryReflectionApprovalRequired,
		Boolean serviceDiscoveryEnabled,
		Integer serviceDiscoveryStalenessDays,
		String serviceDiscoveryModel,
		Integer contextWarnPercent,
		String mcpSerenaRoot,
		String mcpUvPath,
		String mcpGraphifyRoot,
		String mcpCodegraphRoot,
		String codeIntel,
		String linearAllowedTools,
		Boolean authTokenRequired,
		Boolean slackDefaultEnabled,
		String visualizeModel) {

	public static Builder builder() {
		return new Builder();
	}

	/** Test-only convenience — production code binds the record directly from JSON. */
	public static final class Builder {
		private Boolean linearOAuthEnabled;
		private String ticketImportSpec;
		private String ecosystemRoot;
		private String monorepoServiceGlobs;
		private Boolean monorepoDetectionEnabled;
		private Boolean prChecksEnabled;
		private Integer prCheckPollIntervalSeconds;
		private String librarySkillsRoot;
		private String libraryAgentsRoot;
		private Boolean libraryVectorize;
		private Boolean librarySyncEnabled;
		private Integer librarySyncIntervalMinutes;
		private String defaultProvider;
		private String systemProvider;
		private String codexPricing;
		private String memoryRoot;
		private Boolean memoryEnabled;
		private Boolean memoryReflectionDefault;
		private String memoryReflectionModel;
		private Integer memorySyncIntervalMinutes;
		private Integer memoryRetentionDays;
		private Boolean memoryReflectionApprovalRequired;
		private Boolean serviceDiscoveryEnabled;
		private Integer serviceDiscoveryStalenessDays;
		private String serviceDiscoveryModel;
		private Integer contextWarnPercent;
		private String mcpSerenaRoot;
		private String mcpUvPath;
		private String mcpGraphifyRoot;
		private String mcpCodegraphRoot;
		private String codeIntel;
		private String linearAllowedTools;
		private Boolean authTokenRequired;
		private Boolean slackDefaultEnabled;
		private String visualizeModel;

		public Builder linearOAuthEnabled(Boolean v) {
			this.linearOAuthEnabled = v;
			return this;
		}

		public Builder ticketImportSpec(String v) {
			this.ticketImportSpec = v;
			return this;
		}

		public Builder ecosystemRoot(String v) {
			this.ecosystemRoot = v;
			return this;
		}

		public Builder monorepoServiceGlobs(String v) {
			this.monorepoServiceGlobs = v;
			return this;
		}

		public Builder monorepoDetectionEnabled(Boolean v) {
			this.monorepoDetectionEnabled = v;
			return this;
		}

		public Builder prChecksEnabled(Boolean v) {
			this.prChecksEnabled = v;
			return this;
		}

		public Builder prCheckPollIntervalSeconds(Integer v) {
			this.prCheckPollIntervalSeconds = v;
			return this;
		}

		public Builder librarySkillsRoot(String v) {
			this.librarySkillsRoot = v;
			return this;
		}

		public Builder libraryAgentsRoot(String v) {
			this.libraryAgentsRoot = v;
			return this;
		}

		public Builder libraryVectorize(Boolean v) {
			this.libraryVectorize = v;
			return this;
		}

		public Builder librarySyncEnabled(Boolean v) {
			this.librarySyncEnabled = v;
			return this;
		}

		public Builder librarySyncIntervalMinutes(Integer v) {
			this.librarySyncIntervalMinutes = v;
			return this;
		}

		public Builder defaultProvider(String v) {
			this.defaultProvider = v;
			return this;
		}

		public Builder systemProvider(String v) {
			this.systemProvider = v;
			return this;
		}

		public Builder codexPricing(String v) {
			this.codexPricing = v;
			return this;
		}

		public Builder memoryRoot(String v) {
			this.memoryRoot = v;
			return this;
		}

		public Builder memoryEnabled(Boolean v) {
			this.memoryEnabled = v;
			return this;
		}

		public Builder memoryReflectionDefault(Boolean v) {
			this.memoryReflectionDefault = v;
			return this;
		}

		public Builder memoryReflectionModel(String v) {
			this.memoryReflectionModel = v;
			return this;
		}

		public Builder memorySyncIntervalMinutes(Integer v) {
			this.memorySyncIntervalMinutes = v;
			return this;
		}

		public Builder memoryRetentionDays(Integer v) {
			this.memoryRetentionDays = v;
			return this;
		}

		public Builder memoryReflectionApprovalRequired(Boolean v) {
			this.memoryReflectionApprovalRequired = v;
			return this;
		}

		public Builder serviceDiscoveryEnabled(Boolean v) {
			this.serviceDiscoveryEnabled = v;
			return this;
		}

		public Builder serviceDiscoveryStalenessDays(Integer v) {
			this.serviceDiscoveryStalenessDays = v;
			return this;
		}

		public Builder serviceDiscoveryModel(String v) {
			this.serviceDiscoveryModel = v;
			return this;
		}

		public Builder contextWarnPercent(Integer v) {
			this.contextWarnPercent = v;
			return this;
		}

		public Builder mcpSerenaRoot(String v) {
			this.mcpSerenaRoot = v;
			return this;
		}

		public Builder mcpUvPath(String v) {
			this.mcpUvPath = v;
			return this;
		}

		public Builder mcpGraphifyRoot(String v) {
			this.mcpGraphifyRoot = v;
			return this;
		}

		public Builder mcpCodegraphRoot(String v) {
			this.mcpCodegraphRoot = v;
			return this;
		}

		public Builder codeIntel(String v) {
			this.codeIntel = v;
			return this;
		}

		public Builder linearAllowedTools(String v) {
			this.linearAllowedTools = v;
			return this;
		}

		public Builder authTokenRequired(Boolean v) {
			this.authTokenRequired = v;
			return this;
		}

		public Builder slackDefaultEnabled(Boolean v) {
			this.slackDefaultEnabled = v;
			return this;
		}

		public Builder visualizeModel(String v) {
			this.visualizeModel = v;
			return this;
		}

		public SettingsPatch build() {
			return new SettingsPatch(linearOAuthEnabled, ticketImportSpec, ecosystemRoot, monorepoServiceGlobs,
					monorepoDetectionEnabled, prChecksEnabled,
					prCheckPollIntervalSeconds, librarySkillsRoot, libraryAgentsRoot, libraryVectorize,
					librarySyncEnabled, librarySyncIntervalMinutes, defaultProvider, systemProvider, codexPricing,
					memoryRoot, memoryEnabled, memoryReflectionDefault, memoryReflectionModel,
					memorySyncIntervalMinutes, memoryRetentionDays, memoryReflectionApprovalRequired,
					serviceDiscoveryEnabled, serviceDiscoveryStalenessDays, serviceDiscoveryModel, contextWarnPercent,
					mcpSerenaRoot, mcpUvPath, mcpGraphifyRoot, mcpCodegraphRoot, codeIntel, linearAllowedTools,
					authTokenRequired, slackDefaultEnabled, visualizeModel);
		}
	}
}
