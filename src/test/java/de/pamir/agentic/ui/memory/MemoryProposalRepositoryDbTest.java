package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemoryProposalRepository}'s partial unique index (session_id) WHERE status='PENDING'
 * against a live Postgres (docs/plan/phase-9-production-hardening.md T3) — proves the DB-level
 * "at most one pending proposal per session" constraint (decision 14, phase-5.3) actually fires
 * and is surfaced as {@link IllegalStateException}, and that a decided proposal frees the slot.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class MemoryProposalRepositoryDbTest {

	@Autowired
	private MemoryProposalRepository proposals;

	@Autowired
	private SessionRepository sessions;

	@Autowired
	private ObjectMapper mapper;

	private UUID newSession() {
		UUID id = UUID.randomUUID();
		sessions.insert(SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build());
		return id;
	}

	private ArrayNode emptyOps() {
		return mapper.createArrayNode();
	}

	@Test
	void secondPendingProposalForTheSameSessionIsRejected() {
		UUID sessionId = newSession();
		proposals.insert(sessionId, "s1", "/repo", 10L, "first episode", emptyOps());

		assertThatThrownBy(() -> proposals.insert(sessionId, "s1", "/repo", 20L, "second episode", emptyOps()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already pending");
	}

	@Test
	void deciderFreesTheSlotForANewPendingProposal() {
		UUID sessionId = newSession();
		var first = proposals.insert(sessionId, "s1", "/repo", 10L, "first episode", emptyOps());

		proposals.decide(first.id(), "APPROVED");

		var second = proposals.insert(sessionId, "s1", "/repo", 20L, "second episode", emptyOps());

		assertThat(second.id()).isNotEqualTo(first.id());
		assertThat(proposals.findPendingForSession(sessionId)).contains(second);
	}

	@Test
	void decidingAnAlreadyDecidedProposalFails() {
		UUID sessionId = newSession();
		var proposal = proposals.insert(sessionId, "s1", "/repo", 10L, "episode", emptyOps());
		proposals.decide(proposal.id(), "DISCARDED");

		assertThatThrownBy(() -> proposals.decide(proposal.id(), "APPROVED"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not pending");
	}

	@Test
	void twoDifferentSessionsCanEachHaveAPendingProposal() {
		UUID sessionA = newSession();
		UUID sessionB = newSession();

		var a = proposals.insert(sessionA, "s-a", "/repo", 1L, "episode a", emptyOps());
		var b = proposals.insert(sessionB, "s-b", "/repo", 1L, "episode b", emptyOps());

		assertThat(proposals.findPendingForSession(sessionA)).contains(a);
		assertThat(proposals.findPendingForSession(sessionB)).contains(b);
	}

	@Test
	void aSkillAndAMemoryProposalCanBePendingTogetherButNotTwoOfAKind() {
		UUID sessionId = newSession();
		var memory = proposals.insert(sessionId, "s1", "/repo", 10L, "episode", emptyOps());
		var skill = proposals.insert(MemoryProposalRepository.KIND_SKILL, sessionId, "s1", "/repo", null,
				"# body", emptyOps());

		assertThat(proposals.findPendingForSession(sessionId)).contains(memory);
		assertThat(proposals.findPendingForSession(sessionId, MemoryProposalRepository.KIND_SKILL)).contains(skill);
		assertThat(skill.reflectedSeq()).isNull();
		assertThatThrownBy(() -> proposals.insert(MemoryProposalRepository.KIND_SKILL, sessionId, "s1", "/repo", null,
				"# other", emptyOps())).isInstanceOf(IllegalStateException.class);
	}
}
