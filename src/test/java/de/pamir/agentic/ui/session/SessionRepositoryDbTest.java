package de.pamir.agentic.ui.session;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SessionRepository} against a live Postgres (docs/plan/phase-9-production-hardening.md
 * T3) — cutoff logic that's easy to get backwards in SQL and worth locking in against the real
 * engine rather than a fake. Each test runs in its own transaction, rolled back afterward, so
 * nothing here touches the persistent dev database.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class SessionRepositoryDbTest {

	@Autowired
	private SessionRepository sessions;

	private final ObjectMapper mapper = new JsonMapper();

	private SessionEntity insertSession(SessionState state) {
		UUID id = UUID.randomUUID();
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(state).build();
		sessions.insert(entity);
		return entity;
	}

	@Test
	void findAwaitingPrCheckHonoursCutoffAndStatus() {
		Instant now = Instant.now();
		Instant past = now.minus(1, ChronoUnit.HOURS);
		Instant future = now.plus(1, ChronoUnit.HOURS);

		SessionEntity neverChecked = insertSession(SessionState.IDLE);
		sessions.attachPr(neverChecked.id(), "https://example.com/pr/1", "sha1");

		SessionEntity dueForRecheck = insertSession(SessionState.IDLE);
		sessions.attachPr(dueForRecheck.id(), "https://example.com/pr/2", "sha2");
		sessions.updatePrCheck(dueForRecheck.id(), "PENDING", "sha2", past);

		SessionEntity notYetDue = insertSession(SessionState.IDLE);
		sessions.attachPr(notYetDue.id(), "https://example.com/pr/3", "sha3");
		sessions.updatePrCheck(notYetDue.id(), "PENDING", "sha3", future);

		SessionEntity alreadyResolved = insertSession(SessionState.IDLE);
		sessions.attachPr(alreadyResolved.id(), "https://example.com/pr/4", "sha4");
		sessions.updatePrCheck(alreadyResolved.id(), "SUCCESS", "sha4", past);

		SessionEntity noPr = insertSession(SessionState.IDLE);

		List<UUID> awaiting = sessions.findAwaitingPrCheck(now).stream().map(SessionEntity::id).toList();

		assertThat(awaiting).contains(neverChecked.id(), dueForRecheck.id());
		assertThat(awaiting).doesNotContain(notYetDue.id(), alreadyResolved.id(), noPr.id());
	}

	@Test
	void resetPrCheckPendingOnlyAffectsSessionsWithAPr() {
		SessionEntity withPr = insertSession(SessionState.IDLE);
		sessions.attachPr(withPr.id(), "https://example.com/pr/5", "sha5");
		sessions.updatePrCheck(withPr.id(), "SUCCESS", "sha5", Instant.now());

		SessionEntity withoutPr = insertSession(SessionState.IDLE);

		sessions.resetPrCheckPending(withPr.id());
		sessions.resetPrCheckPending(withoutPr.id());

		assertThat(sessions.get(withPr.id()).prCheckStatus()).isEqualTo("PENDING");
		assertThat(sessions.get(withoutPr.id()).prCheckStatus()).isNull();
	}

	@Test
	void nullServicePathResolvesToRepoPathAndCwdPathResolvesToWorktreePath() {
		UUID id = UUID.randomUUID();
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").worktreePath("/wt/" + id) // servicePath left unset (null)
				.branch("b-" + id).baseBranch("main")
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build();
		sessions.insert(entity);

		SessionEntity loaded = sessions.get(id);

		assertThat(loaded.servicePath()).isEqualTo("/repo");
		assertThat(loaded.cwdPath()).isEqualTo("/wt/" + id);
	}

	@Test
	void nonNullServicePathResolvesCwdPathToTheMatchingWorktreeSubfolder() {
		UUID id = UUID.randomUUID();
		String worktree = "/wt/" + id;
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo/mono").servicePath("/repo/mono/packages/foo").worktreePath(worktree)
				.branch("b-" + id).baseBranch("main")
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build();
		sessions.insert(entity);

		SessionEntity loaded = sessions.get(id);

		assertThat(loaded.servicePath()).isEqualTo("/repo/mono/packages/foo");
		assertThat(loaded.cwdPath()).isEqualTo(worktree + "/packages/foo");
	}

	@Test
	void servicePathAndCwdPathAreSerializedIntoTheJsonRepresentation() throws Exception {
		UUID id = UUID.randomUUID();
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo/mono").servicePath("/repo/mono/packages/foo").worktreePath("/wt/" + id)
				.branch("b-" + id).baseBranch("main")
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build();

		String json = mapper.writeValueAsString(entity);

		assertThat(json).contains("\"servicePath\":\"/repo/mono/packages/foo\"");
		assertThat(json).contains("\"cwdPath\":\"/wt/" + id + "/packages/foo\"");
	}

	@Test
	void countByStatesCountsOnlyMatchingLiveStates() {
		insertSession(SessionState.IDLE);
		insertSession(SessionState.RUNNING);
		SessionEntity closed = insertSession(SessionState.CLOSED);

		long liveCount = sessions.countByStates(List.copyOf(SessionState.LIVE));
		long closedCount = sessions.countByStates(List.of(SessionState.CLOSED));

		assertThat(liveCount).isGreaterThanOrEqualTo(2);
		assertThat(closedCount).isGreaterThanOrEqualTo(1);
		assertThat(sessions.get(closed.id()).state()).isEqualTo(SessionState.CLOSED);
	}

	@Test
	void codeIntelRoundTripsThroughInsert() {
		UUID id = UUID.randomUUID();
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).codeIntel("graphify").build();

		sessions.insert(entity);

		assertThat(sessions.get(id).codeIntel()).isEqualTo("graphify");
	}

	@Test
	void contextTokensAndWindowAreNullUntilTheFirstUpdateThenRoundTrip() {
		SessionEntity inserted = insertSession(SessionState.IDLE);
		assertThat(inserted.contextTokens()).isNull();
		assertThat(inserted.contextWindow()).isNull();

		sessions.updateContextUsage(inserted.id(), 38000, 200000);

		SessionEntity loaded = sessions.get(inserted.id());
		assertThat(loaded.contextTokens()).isEqualTo(38000);
		assertThat(loaded.contextWindow()).isEqualTo(200000);
	}

	@Test
	void findRecentlyClosedOrdersByCloseTimeExcludesSystemAndHonoursLimit() throws Exception {
		SessionEntity first = insertSession(SessionState.IDLE);
		SessionEntity second = insertSession(SessionState.IDLE);
		SessionEntity open = insertSession(SessionState.IDLE);
		UUID systemId = UUID.randomUUID();
		sessions.insert(SessionEntity.builder().id(systemId).name("sys-" + systemId).provider("claude").kind("system")
				.repoPath("/repo").branch("b-" + systemId).baseBranch("main").worktreePath("/wt/" + systemId)
				.state(SessionState.IDLE).build());

		sessions.markClosed(first.id());
		Thread.sleep(5);
		sessions.markClosed(systemId);
		sessions.markClosed(second.id());

		List<UUID> recent = sessions.findRecentlyClosed(100).stream().map(SessionEntity::id).toList();

		assertThat(recent).doesNotContain(systemId, open.id());
		assertThat(recent.indexOf(second.id())).isLessThan(recent.indexOf(first.id()));
		assertThat(sessions.findRecentlyClosed(1)).hasSize(1);
		assertThat(sessions.get(first.id()).closedAt()).isNotNull();
	}
}
