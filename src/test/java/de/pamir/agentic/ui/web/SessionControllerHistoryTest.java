package de.pamir.agentic.ui.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionControllerHistoryTest {

	@Test
	void clampsHistoryLimitToOneThroughTwenty() {
		assertThat(SessionController.clampHistoryLimit(-3)).isEqualTo(1);
		assertThat(SessionController.clampHistoryLimit(0)).isEqualTo(1);
		assertThat(SessionController.clampHistoryLimit(5)).isEqualTo(5);
		assertThat(SessionController.clampHistoryLimit(999)).isEqualTo(20);
	}
}
