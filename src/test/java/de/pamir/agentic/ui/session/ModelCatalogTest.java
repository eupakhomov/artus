package de.pamir.agentic.ui.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelCatalogTest {

	@Test
	void claudeCatalogCoversAllThreeTiers() {
		assertThat(ModelCatalog.byTier("claude", "cheap")).contains("haiku");
		assertThat(ModelCatalog.byTier("claude", "standard")).contains("sonnet");
		assertThat(ModelCatalog.byTier("claude", "premium")).contains("opus");
	}

	@Test
	void codexHasNoFixedCatalog() {
		assertThat(ModelCatalog.models("codex")).isEmpty();
		assertThat(ModelCatalog.byTier("codex", "cheap")).isEmpty();
	}

	@Test
	void anUnknownProviderFallsBackToClaudesCatalog() {
		assertThat(ModelCatalog.models("some-future-provider")).isEqualTo(ModelCatalog.models("claude"));
	}

	@Test
	void byTierIsCaseInsensitiveAndTolerantOfWhitespace() {
		assertThat(ModelCatalog.byTier("claude", " Cheap ")).contains("haiku");
	}

	@Test
	void byTierMissesOnAnUnknownTier() {
		assertThat(ModelCatalog.byTier("claude", "ultra")).isEmpty();
	}

	@Test
	void normalizeMapsAResolvedIdBackToItsAliasAndRejectsUnknowns() {
		assertThat(ModelCatalog.normalize("claude", "opus")).contains("opus");
		assertThat(ModelCatalog.normalize("claude", "claude-opus-4-6")).contains("opus");
		assertThat(ModelCatalog.normalize("claude", "gpt-9")).isEmpty();
		assertThat(ModelCatalog.normalize("codex", "anything")).contains("anything");
	}

	@Test
	void normalizeKeepsTheExtendedContextFlavour() {
		assertThat(ModelCatalog.normalize("claude", "claude-sonnet-5[1m]")).contains("sonnet[1m]");
		assertThat(ModelCatalog.normalize("claude", "claude-sonnet-5")).contains("sonnet");
		assertThat(ModelCatalog.byTier("claude", "premium")).contains("opus");
	}
}
