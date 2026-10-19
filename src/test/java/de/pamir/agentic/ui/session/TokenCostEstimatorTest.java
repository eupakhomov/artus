package de.pamir.agentic.ui.session;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure math, no Spring/DB (phase 25 step 3). */
class TokenCostEstimatorTest {

	private final JsonMapper mapper = new JsonMapper();

	private final JsonNode pricing = mapper.readTree("""
			{"claude-sonnet-5": {"inputPer1M": 2, "outputPer1M": 10, "cacheReadPer1M": 0.2, "cacheWrite5mPer1M": 2.5, "cacheWrite1hPer1M": 4},
			 "claude-sonnet-5-5": {"inputPer1M": 3, "outputPer1M": 10},
			 "default": {"inputPer1M": 100}}
			""");

	@Test
	void pricesEveryTokenClassAndSumsModels() {
		JsonNode byModel = mapper.readTree("""
				{"claude-sonnet-5[1m]": {"input": 1000000, "output": 100000, "cacheRead": 1000000, "cacheWrite5m": 1000000, "cacheWrite1h": 1000000},
				 "claude-haiku-4-5": {"input": 10000}}
				""");

		// sonnet: 2 + 1 + 0.2 + 2.5 + 4 = 9.7; haiku falls back to "default": 100 * 0.01 = 1
		assertThat(TokenCostEstimator.estimate(pricing, byModel)).isEqualByComparingTo(new BigDecimal("10.7"));
	}

	@Test
	void theLongestMatchingPrefixWins() {
		assertThat(TokenCostEstimator.ratesFor(pricing, "claude-sonnet-5-5").path("inputPer1M").asInt()).isEqualTo(3);
		assertThat(TokenCostEstimator.ratesFor(pricing, "claude-sonnet-5").path("inputPer1M").asInt()).isEqualTo(2);
	}

	@Test
	void noRowAndNoDefaultCostsNothing() {
		JsonNode byModel = mapper.readTree("{\"gpt\": {\"input\": 1000000}}");

		assertThat(TokenCostEstimator.estimate(mapper.readTree("{}"), byModel)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(TokenCostEstimator.estimate(pricing, null)).isEqualByComparingTo(BigDecimal.ZERO);
	}
}
