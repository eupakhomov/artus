package de.pamir.agentic.ui.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fakes SettingsRepository with an in-memory map (same pattern SessionServiceTest uses for
 * SettingsService itself) — the logic under test never touches AppProperties/ObjectMapper, so both
 * are left null. See docs/plan/phase-10-review-followups.md R8c for the typed Settings/SettingsPatch
 * shape these tests exercise, and phase-9-production-hardening.md P1/P3 for why
 * memoryReflectionModel/serviceDiscoveryModel normalize a legacy raw Claude alias to a tier.
 */
class SettingsServiceTest {

	private SettingsService newService() {
		return newService(null);
	}

	private SettingsService newServiceWithToken(String token) {
		return newService(new AppProperties(null, null, null, null, 0, token, "", "", "logs", 30, 65536, 1048576, null));
	}

	private SettingsService newService(AppProperties props) {
		Map<String, String> store = new HashMap<>();
		SettingsRepository repo = new SettingsRepository(null) {
			@Override
			public Optional<String> get(String key) {
				return Optional.ofNullable(store.get(key));
			}

			@Override
			public void set(String key, String value) {
				store.put(key, value);
			}

			@Override
			public Map<String, String> all() {
				return new HashMap<>(store);
			}
		};
		return new SettingsService(repo, props, null);
	}

	@Test
	void authTokenRequiredDefaultsToOffWithoutAConfiguredToken() {
		assertThat(newService().current().authTokenRequired()).isFalse();
	}

	@Test
	void authTokenRequiredDefaultsToOnWhenATokenIsConfigured() {
		SettingsService settings = newServiceWithToken("secret");
		assertThat(settings.current().authTokenRequired()).isTrue();
		settings.apply(SettingsPatch.builder().authTokenRequired(false).build());
		assertThat(settings.current().authTokenRequired()).isFalse();
	}

	@Test
	void memoryReflectionModelDefaultsToCheap() {
		assertThat(newService().current().memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void memoryReflectionModelAcceptsATierNameAsIs() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("standard").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelMapsALegacyClaudeAliasToItsTier() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("sonnet").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("standard");
	}

	@Test
	void memoryReflectionModelFallsBackToCheapForGarbageInput() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryReflectionModel("gpt-5").build());
		assertThat(settings.current().memoryReflectionModel()).isEqualTo("cheap");
	}

	@Test
	void serviceDiscoveryModelMapsALegacyHaikuAliasToCheap() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().serviceDiscoveryModel("haiku").build());
		assertThat(settings.current().serviceDiscoveryModel()).isEqualTo("cheap");
	}

	@Test
	void visualizeModelDefaultsToStandardAndNormalizesLikeTheOtherTiers() {
		SettingsService settings = newService();
		assertThat(settings.current().visualizeModel()).isEqualTo("standard");
		settings.apply(SettingsPatch.builder().visualizeModel("premium").build());
		assertThat(settings.current().visualizeModel()).isEqualTo("premium");
		settings.apply(SettingsPatch.builder().visualizeModel("opus").build());
		assertThat(settings.current().visualizeModel()).isEqualTo("premium");
		settings.apply(SettingsPatch.builder().visualizeModel("gpt-5").build());
		assertThat(settings.current().visualizeModel()).isEqualTo("cheap");
	}

	@Test
	void monorepoServiceGlobsDefaultsToTheStandardFourGlobs() {
		assertThat(newService().current().monorepoServiceGlobs()).isEqualTo("packages/*,services/*,apps/*,libs/*");
	}

	@Test
	void monorepoServiceGlobsRoundTripsThroughApply() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().monorepoServiceGlobs("pkgs/*,tools/*").build());
		assertThat(settings.current().monorepoServiceGlobs()).isEqualTo("pkgs/*,tools/*");
	}

	@Test
	void monorepoDetectionDefaultsToDisabled() {
		assertThat(newService().current().monorepoDetectionEnabled()).isFalse();
	}

	@Test
	void monorepoDetectionRoundTripsThroughApply() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().monorepoDetectionEnabled(true).build());
		assertThat(settings.current().monorepoDetectionEnabled()).isTrue();
	}

	@Test
	void systemProviderDefaultsToFollowingDefaultProvider() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		assertThat(settings.systemProvider()).isEqualTo("codex");
	}

	@Test
	void systemProviderOverridesDefaultProviderWhenExplicitlySet() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		settings.apply(SettingsPatch.builder().systemProvider("claude").build());
		assertThat(settings.systemProvider()).isEqualTo("claude");
	}

	@Test
	void systemProviderOverrideStaysBlankUntilExplicitlySet() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().defaultProvider("codex").build());
		assertThat(settings.current().systemProvider()).isEmpty();
		settings.apply(SettingsPatch.builder().systemProvider("claude").build());
		assertThat(settings.current().systemProvider()).isEqualTo("claude");
	}

	@Test
	void applyIsANoOpForNullPatchFields() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().memoryEnabled(false).build());
		Settings before = settings.current();
		settings.apply(SettingsPatch.builder().build());
		assertThat(settings.current()).isEqualTo(before);
	}

	@Test
	void contextWarnPercentDefaultsToSeventy() {
		assertThat(newService().current().contextWarnPercent()).isEqualTo(70);
	}

	@Test
	void contextWarnPercentRoundTripsThroughApply() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().contextWarnPercent(55).build());
		assertThat(settings.current().contextWarnPercent()).isEqualTo(55);
	}

	@Test
	void contextWarnPercentIsFlooredAtThirty() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().contextWarnPercent(10).build());
		assertThat(settings.current().contextWarnPercent()).isEqualTo(30);
	}

	@Test
	void contextWarnPercentIsCappedAtNinetyFive() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().contextWarnPercent(150).build());
		assertThat(settings.current().contextWarnPercent()).isEqualTo(95);
	}

	@Test
	void mcpSerenaRootDefaultsToEmpty() {
		assertThat(newService().current().mcpSerenaRoot()).isEmpty();
	}

	@Test
	void mcpSerenaRootRoundTripsThroughApply() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().mcpSerenaRoot("/mnt/d/projects/serena").build());
		assertThat(settings.current().mcpSerenaRoot()).isEqualTo("/mnt/d/projects/serena");
	}

	@Test
	void mcpUvPathDefaultsToUv() {
		assertThat(newService().current().mcpUvPath()).isEqualTo("uv");
	}

	@Test
	void mcpUvPathRoundTripsThroughApply() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().mcpUvPath("/opt/uv/bin/uv").build());
		assertThat(settings.current().mcpUvPath()).isEqualTo("/opt/uv/bin/uv");
	}

	// --- phase 13: mcp.graphify-root + mcp.code-intel selector ---

	@Test
	void mcpGraphifyRootDefaultsToEmptyAndRoundTrips() {
		SettingsService settings = newService();
		assertThat(settings.current().mcpGraphifyRoot()).isEmpty();

		settings.apply(SettingsPatch.builder().mcpGraphifyRoot("/mnt/d/projects/graphify").build());
		assertThat(settings.current().mcpGraphifyRoot()).isEqualTo("/mnt/d/projects/graphify");
	}

	@Test
	void codeIntelDefaultsToNoneWithoutASerenaRoot() {
		SettingsService settings = newService();
		assertThat(settings.current().codeIntel()).isEqualTo("none");
		assertThat(settings.storedCodeIntel()).isEmpty();
	}

	@Test
	void codeIntelDefaultsToSerenaWhenASerenaRootIsConfiguredButTheSelectorWasNeverSet() {
		// decision 12: an install already on Serena keeps working with no Settings visit
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().mcpSerenaRoot("/mnt/d/projects/serena").build());
		assertThat(settings.current().codeIntel()).isEqualTo("serena");
		assertThat(settings.storedCodeIntel()).isEmpty();
	}

	@Test
	void codeIntelRoundTripsAnExplicitChoiceOverTheDefault() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().mcpSerenaRoot("/mnt/d/projects/serena").codeIntel("none").build());
		assertThat(settings.current().codeIntel()).isEqualTo("none");
		assertThat(settings.storedCodeIntel()).contains("none");

		settings.apply(SettingsPatch.builder().codeIntel("Graphify").build());
		assertThat(settings.current().codeIntel()).isEqualTo("graphify");
	}

	@Test
	void codeIntelTreatsAnUnknownStoredValueAsUnset() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().codeIntel("bogus").build());
		assertThat(settings.storedCodeIntel()).isEmpty();
		assertThat(settings.current().codeIntel()).isEqualTo("none");
	}

	@Test
	void validateCodeIntelAcceptsNoneRegardlessOfRoots() {
		SettingsService.validateCodeIntel("none", "", "", "");
		SettingsService.validateCodeIntel("none", "/serena", "/graphify", "/codegraph");
	}

	@Test
	void validateCodeIntelRequiresTheSelectedToolsRoot() {
		SettingsService.validateCodeIntel("serena", "/serena", "", "");
		SettingsService.validateCodeIntel("graphify", "", "/graphify", "");
		SettingsService.validateCodeIntel("codegraph", "", "", "/codegraph");

		assertThatThrownBy(() -> SettingsService.validateCodeIntel("serena", "", "/graphify", ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("select none first");
		assertThatThrownBy(() -> SettingsService.validateCodeIntel("graphify", "/serena", " ", ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Graphify root");
		assertThatThrownBy(() -> SettingsService.validateCodeIntel("codegraph", "/serena", "/graphify", ""))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("CodeGraph root");
	}

	@Test
	void validateCodeIntelRejectsAnUnknownSelector() {
		assertThatThrownBy(() -> SettingsService.validateCodeIntel("both", "/serena", "/graphify", "/codegraph"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("none/serena/graphify/codegraph");
	}

	// --- phase 14: mcp.codegraph-root ---

	@Test
	void mcpCodegraphRootDefaultsToEmptyAndRoundTrips() {
		SettingsService settings = newService();
		assertThat(settings.current().mcpCodegraphRoot()).isEmpty();

		settings.apply(SettingsPatch.builder().mcpCodegraphRoot("/mnt/d/projects/codegraph").build());
		assertThat(settings.current().mcpCodegraphRoot()).isEqualTo("/mnt/d/projects/codegraph");
	}

	@Test
	void codeIntelRoundTripsCodegraph() {
		SettingsService settings = newService();
		settings.apply(SettingsPatch.builder().mcpCodegraphRoot("/mnt/d/projects/codegraph").codeIntel("codegraph").build());
		assertThat(settings.current().codeIntel()).isEqualTo("codegraph");
		assertThat(settings.storedCodeIntel()).contains("codegraph");
	}

	@Test
	void currentCachesUntilApply() {
		SettingsService settings = newService();
		Settings first = settings.current();
		Settings second = settings.current();
		assertThat(second).isSameAs(first);
		settings.apply(SettingsPatch.builder().memoryEnabled(false).build());
		Settings third = settings.current();
		assertThat(third).isNotSameAs(first);
	}
}
