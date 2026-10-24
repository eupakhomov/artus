package de.pamir.agentic.ui.session;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/**
 * Per-provider model catalog, mirroring each adapter's own {@code ready.capabilities.models}
 * (sidecar/src/protocol.ts's CLAUDE_CAPABILITIES, sidecar-codex/src/protocol.ts's
 * CODEX_CAPABILITIES — see docs/plan/phase-9-production-hardening.md P3). {@link ProviderController}
 * exposes this over {@code GET /api/providers} for the create dialog/template editor; {@link #byTier}
 * additionally lets backend-initiated system turns (system session default model, reflection,
 * service discovery, auto-titling) pick a model by role ("cheap"/"standard"/"premium") instead of
 * a hardcoded Claude alias, so they degrade sanely on a Codex-only install (see P1/P2).
 */
public final class ModelCatalog {

	private ModelCatalog() {
	}

	public record ModelInfo(String id, String label, String tier) {
	}

	private static final List<ModelInfo> CLAUDE_MODELS = List.of(
			new ModelInfo("haiku", "haiku", "cheap"),
			new ModelInfo("sonnet", "sonnet", "standard"),
			new ModelInfo("opus", "opus", "premium"),
			new ModelInfo("sonnet[1m]", "sonnet (1M context)", "extended"),
			new ModelInfo("opus[1m]", "opus (1M context)", "extended"));

	/**
	 * Codex resolves its own default model/alias with no fixed enumeration we can offer up front
	 * (docs/plan/phase-5.13-codex-provider.md: "no hardcoded Codex model list") — empty means the
	 * frontend falls back to a free-text model field, and {@link #byTier} always misses for it.
	 */
	private static final List<ModelInfo> CODEX_MODELS = List.of();

	/** Lists the adapters reported live (models_available); they win over the static fallback. */
	private static final Map<String, List<ModelInfo>> LIVE = new ConcurrentHashMap<>();

	private static final Map<String, List<ModelInfo>> BY_PROVIDER = Map.of(
			"claude", CLAUDE_MODELS,
			"codex", CODEX_MODELS);

	/** An unrecognized provider id falls back to Claude's catalog, matching ProviderController's capability fallback. */
	public static List<ModelInfo> models(String provider) {
		List<ModelInfo> live = LIVE.get(provider);
		return live != null ? live : BY_PROVIDER.getOrDefault(provider, CLAUDE_MODELS);
	}

	public static void updateLive(String provider, List<ModelInfo> models) {
		if (models.isEmpty()) {
			LIVE.remove(provider);
		} else {
			LIVE.put(provider, List.copyOf(models));
		}
	}

	/**
	 * Resolves a tier name ("cheap"/"standard"/"premium") to a concrete model id for a provider.
	 * Empty when the provider has no fixed catalog (Codex) or no model of that tier — callers
	 * should treat that as "no override", same as an unspecified model today (the provider picks
	 * its own default).
	 */
	public static Optional<String> byTier(String provider, String tier) {
		String t = tier == null ? "" : tier.strip().toLowerCase(Locale.ROOT);
		return models(provider).stream().filter(m -> m.tier().equals(t)).map(ModelInfo::id).findFirst();
	}

	/**
	 * Maps a requested model onto this provider's catalog: an exact id passes through, a concrete
	 * resolved id the provider reported ("claude-opus-4-6") maps back to the alias covering it
	 * ("opus"). Empty when the provider has a catalog and nothing in it matches. A provider
	 * without a fixed catalog (Codex) accepts anything as-is.
	 */
	public static Optional<String> normalize(String provider, String model) {
		List<ModelInfo> catalog = models(provider);
		if (catalog.isEmpty()) {
			return Optional.of(model);
		}
		String m = model.strip().toLowerCase(Locale.ROOT);
		Optional<String> exact = catalog.stream().map(ModelInfo::id).filter(m::equals).findFirst();
		if (exact.isPresent()) {
			return exact;
		}
		// a resolved id ("claude-sonnet-5[1m]") maps to the longest alias it contains, keeping the 1M flavour
		boolean extended = m.contains("[1m]");
		return catalog.stream().map(ModelInfo::id)
				.filter(id -> id.endsWith("[1m]") == extended)
				.filter(id -> m.contains(id.replace("[1m]", "")))
				.max(java.util.Comparator.comparingInt(String::length));
	}
}
