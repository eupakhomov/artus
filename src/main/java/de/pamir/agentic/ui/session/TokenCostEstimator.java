package de.pamir.agentic.ui.session;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.Map;

/**
 * Prices a running turn's per-model token counts ({@code turn_usage.byModel}, phase 25 step 3)
 * against the Settings-editable Claude price table, for the live "~$" shown while a turn runs.
 * Only an estimate: the authoritative per-turn figure is still {@code turn_complete.costUsd}.
 */
public final class TokenCostEstimator {

	private TokenCostEstimator() {
	}

	/**
	 * @param pricing {@code {"<model id prefix>"|"default": {"inputPer1M":n, "outputPer1M":n,
	 *                "cacheReadPer1M":n, "cacheWrite5mPer1M":n, "cacheWrite1hPer1M":n}}}
	 * @param byModel {@code {"<model>": {"input":n, "output":n, "cacheRead":n, "cacheWrite5m":n, "cacheWrite1h":n}}}
	 */
	static BigDecimal estimate(JsonNode pricing, JsonNode byModel) {
		if (byModel == null || !byModel.isObject()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (Map.Entry<String, JsonNode> entry : byModel.properties()) {
			JsonNode rates = ratesFor(pricing, entry.getKey());
			if (rates == null) {
				continue;
			}
			JsonNode usage = entry.getValue();
			total = total.add(rate(rates, "inputPer1M").multiply(tokens(usage, "input")))
					.add(rate(rates, "outputPer1M").multiply(tokens(usage, "output")))
					.add(rate(rates, "cacheReadPer1M").multiply(tokens(usage, "cacheRead")))
					.add(rate(rates, "cacheWrite5mPer1M").multiply(tokens(usage, "cacheWrite5m")))
					.add(rate(rates, "cacheWrite1hPer1M").multiply(tokens(usage, "cacheWrite1h")));
		}
		return total.divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
	}

	/** Longest key that prefixes the model id, else "default", else null. */
	static JsonNode ratesFor(JsonNode pricing, String model) {
		JsonNode best = null;
		int bestLength = -1;
		for (Iterator<String> keys = pricing.propertyNames().iterator(); keys.hasNext(); ) {
			String key = keys.next();
			if (!"default".equals(key) && model.startsWith(key) && key.length() > bestLength
					&& pricing.get(key).isObject()) {
				best = pricing.get(key);
				bestLength = key.length();
			}
		}
		if (best != null) {
			return best;
		}
		JsonNode fallback = pricing.path("default");
		return fallback.isObject() ? fallback : null;
	}

	private static BigDecimal tokens(JsonNode usage, String field) {
		return BigDecimal.valueOf(usage.path(field).asLong(0));
	}

	private static BigDecimal rate(JsonNode rates, String field) {
		return rates.hasNonNull(field) ? new BigDecimal(rates.get(field).asText()) : BigDecimal.ZERO;
	}
}
