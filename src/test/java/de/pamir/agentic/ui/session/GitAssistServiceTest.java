package de.pamir.agentic.ui.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resolveTicketRef() touches only its SessionEntity argument, not any injected dependency —
 * see docs/plan/phase-9-production-hardening.md T1.
 */
class GitAssistServiceTest {

	private final GitAssistService svc = new GitAssistService(null, null, null);

	private static SessionEntity sessionWith(String ticketRef, String branch) {
		return new SessionEntity(
				UUID.randomUUID(), "name", "claude", null, "/repo", null, null, List.of(),
				branch, "main", "/worktree", null, null, null, "default", List.of(), List.of(),
				null, null, null, null, null, null, null, null, null, null, null,
				SessionState.RUNNING, "user", "development", ticketRef, null, null,
				null, null, null, null, false, null, null, null, null, false, null, null, null, null, null, false);
	}

	@Test
	void prefersTheSessionsExplicitTicketRefOverAnythingInTheBranchName() {
		SessionEntity session = sessionWith("ENG-999", "eng-123-something");

		assertThat(svc.resolveTicketRef(session)).isEqualTo("ENG-999");
	}

	@Test
	void extractsAndUppercasesATicketRefFromTheBranchNameWhenNoneIsSetExplicitly() {
		SessionEntity session = sessionWith(null, "feature/eng-123-fix-login");

		assertThat(svc.resolveTicketRef(session)).isEqualTo("ENG-123");
	}

	@Test
	void returnsNullWhenNoTicketRefIsFoundAnywhere() {
		SessionEntity session = sessionWith(null, "misc-cleanup");

		assertThat(svc.resolveTicketRef(session)).isNull();
	}

	@Test
	void returnsNullWhenTheBranchIsNull() {
		SessionEntity session = sessionWith(null, null);

		assertThat(svc.resolveTicketRef(session)).isNull();
	}
}
