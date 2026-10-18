package de.pamir.agentic.ui.journal;

import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EventJournal} against a live Postgres (docs/plan/phase-9-production-hardening.md T3):
 * the stream_delta coalescing delete and the turn_complete cost sum, both of which depend on the
 * real batching/flush behaviour, not just the SQL text. Rolled back after each test.
 */
@Tag("integration")
@SpringBootTest
@Transactional
class EventJournalDbTest {

	@Autowired
	private EventJournal journal;

	@Autowired
	private SessionRepository sessions;

	@Autowired
	private ObjectMapper mapper;

	/** session_event.session_id has a FK to session(id); the journal needs a real parent row. */
	private UUID newSession() {
		UUID id = UUID.randomUUID();
		SessionEntity entity = SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build();
		sessions.insert(entity);
		return id;
	}

	private ObjectNode turnComplete(double costUsd) {
		return mapper.createObjectNode().put("costUsd", costUsd).put("model", "test-model");
	}

	private ObjectNode delta(String text) {
		return mapper.createObjectNode().put("text", text);
	}

	@Test
	void deleteDeltasBeforeDropsOnlyStreamDeltaRowsBelowCutoff() {
		UUID sessionId = newSession();
		journal.append(sessionId, "stream_delta", delta("a"));  // seq 1
		journal.append(sessionId, "stream_delta", delta("b"));  // seq 2
		journal.append(sessionId, "turn_complete", turnComplete(1.5)); // seq 3, flushes 1-3
		journal.append(sessionId, "stream_delta", delta("c"));  // seq 4, buffered
		journal.append(sessionId, "stream_delta", delta("d"));  // seq 5, buffered

		int deleted = journal.deleteDeltasBefore(sessionId, 4);

		assertThat(deleted).isEqualTo(2); // seq 1 and 2

		var remaining = journal.readAfter(sessionId, 0);
		assertThat(remaining).extracting(EventJournal.JournalEvent::seq).containsExactly(3L, 4L, 5L);
		assertThat(remaining).extracting(EventJournal.JournalEvent::type)
				.containsExactly("turn_complete", "stream_delta", "stream_delta");
	}

	@Test
	void costToDateSumsTurnCompletePayloadsOnly() {
		UUID sessionId = newSession();
		journal.append(sessionId, "stream_delta", delta("ignored, not a turn_complete"));
		journal.append(sessionId, "turn_complete", turnComplete(1.5));
		journal.append(sessionId, "turn_complete", turnComplete(2.25));

		assertThat(journal.costToDate(sessionId)).isEqualByComparingTo(new BigDecimal("3.75"));
	}

	@Test
	void costToDateIsZeroWithNoTurns() {
		UUID sessionId = newSession();

		assertThat(journal.costToDate(sessionId)).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void hasEventTypeSeesEventsAcrossFlushAndBuffer() {
		UUID sessionId = newSession();
		journal.append(sessionId, "stream_delta", delta("buffered, not yet flushed"));

		assertThat(journal.hasEventType(sessionId, "stream_delta")).isTrue();
		assertThat(journal.hasEventType(sessionId, "turn_complete")).isFalse();
	}

	@Test
	void countEventTypeCountsAcrossFlushAndBuffer() {
		UUID sessionId = newSession();
		journal.append(sessionId, "turn_complete", turnComplete(1.0)); // flushes
		journal.append(sessionId, "turn_complete", turnComplete(1.0)); // flushes
		journal.append(sessionId, "stream_delta", delta("buffered, not yet flushed"));

		assertThat(journal.countEventType(sessionId, "turn_complete")).isEqualTo(2);
		assertThat(journal.countEventType(sessionId, "user_message")).isZero();
	}

	@Test
	void firstEventOfTypeReturnsTheEarliestMatchingRow() {
		UUID sessionId = newSession();
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "first"));
		journal.append(sessionId, "turn_complete", turnComplete(1.0));
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "second"));

		var first = journal.firstEventOfType(sessionId, "user_message");

		assertThat(first).isPresent();
		assertThat(first.get().payload().path("text").asText()).isEqualTo("first");
		assertThat(journal.firstEventOfType(sessionId, "session_renamed")).isEmpty();
	}

	@Test
	void statsForAllAggregatesLastSeqAndCostAcrossSessionsInOneQuery() {
		UUID a = newSession();
		UUID b = newSession();
		UUID untouched = newSession();
		journal.append(a, "turn_complete", turnComplete(1.5));
		journal.append(a, "turn_complete", turnComplete(2.25));
		journal.append(b, "stream_delta", delta("buffered, not yet flushed"));
		journal.append(b, "turn_complete", turnComplete(4.0)); // flushes both b rows

		var stats = journal.statsForAll();

		assertThat(stats.get(a).lastSeq()).isEqualTo(2L);
		assertThat(stats.get(a).costToDate()).isEqualByComparingTo(new BigDecimal("3.75"));
		assertThat(stats.get(b).lastSeq()).isEqualTo(2L);
		assertThat(stats.get(b).costToDate()).isEqualByComparingTo(new BigDecimal("4.0"));
		assertThat(stats).doesNotContainKey(untouched);
		assertThat(stats.get(a).lastSeq()).isEqualTo(journal.lastSeq(a));
		assertThat(stats.get(a).costToDate()).isEqualByComparingTo(journal.costToDate(a));
	}

	@Test
	void releaseDropsInMemoryStateWithoutDeletingRowsSoHistoryStillReads() {
		UUID sessionId = newSession();
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "hi"));
		journal.append(sessionId, "stream_delta", delta("buffered, not yet flushed"));

		journal.release(sessionId);

		var history = journal.readAfter(sessionId, 0);
		assertThat(history).extracting(EventJournal.JournalEvent::type)
				.containsExactly("user_message", "stream_delta");
		assertThat(journal.lastSeq(sessionId)).isEqualTo(2L);
	}

	@Test
	void nulCharacterInAPayloadIsReplacedAndDoesNotBlockLaterEvents() {
		UUID sessionId = newSession();
		journal.append(sessionId, "tool_result", mapper.createObjectNode()
				.put("text", "a\0b")
				.set("nested", mapper.createArrayNode().add("x\0y")));
		journal.append(sessionId, "turn_complete", turnComplete(1.0));

		var events = journal.readAfter(sessionId, 0);
		assertThat(events).extracting(EventJournal.JournalEvent::seq).containsExactly(1L, 2L);
		assertThat(events.get(0).payload().path("text").asText()).isEqualTo("a\uFFFDb");
		assertThat(events.get(0).payload().path("nested").get(0).asText()).isEqualTo("x\uFFFDy");
		assertThat(journal.costToDate(sessionId)).isEqualByComparingTo(new BigDecimal("1.0"));
	}

	@Test
	void backslashFollowedByU0000TextIsKeptVerbatim() {
		UUID sessionId = newSession();
		String source = "const SYSTEM_KEY = '\\u0000system';"; // backslash, u, 0000 as plain characters
		journal.append(sessionId, "tool_result", mapper.createObjectNode().put("text", source));

		assertThat(journal.readAfter(sessionId, 0).get(0).payload().path("text").asText()).isEqualTo(source);
	}

	@Test
	void findBySeqReadsAcrossFlushAndBuffer() {
		UUID sessionId = newSession();
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "hi")); // flushes
		journal.append(sessionId, "stream_delta", delta("buffered"));

		assertThat(journal.findBySeq(sessionId, 1).orElseThrow().type()).isEqualTo("user_message");
		assertThat(journal.findBySeq(sessionId, 2).orElseThrow().type()).isEqualTo("stream_delta");
		assertThat(journal.findBySeq(sessionId, 99)).isEmpty();
	}

	@Test
	void hasEventsAfterOnlyCountsTheGivenTypesPastTheCutoff() {
		UUID sessionId = newSession();
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "hi")); // 1
		journal.append(sessionId, "reflection_complete", mapper.createObjectNode());            // 2
		journal.append(sessionId, "state_changed", mapper.createObjectNode());                  // 3

		var relevant = java.util.Set.of("user_message", "block_marked");
		assertThat(journal.hasEventsAfter(sessionId, 0, relevant)).isTrue();
		assertThat(journal.hasEventsAfter(sessionId, 1, relevant)).isFalse();

		journal.append(sessionId, "block_marked", mapper.createObjectNode().put("targetSeq", 1).put("mark", "important")); // 4
		assertThat(journal.hasEventsAfter(sessionId, 1, relevant)).isTrue();
		assertThat(journal.hasEventsAfter(sessionId, 4, relevant)).isFalse();
		assertThat(journal.hasEventsAfter(sessionId, 0, java.util.Set.of())).isFalse();
	}

	@Test
	void findBySeqsFetchesSeveralInOneQueryAndOmitsMissingOnes() {
		UUID sessionId = newSession();
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "a")); // 1
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "b")); // 2
		journal.append(sessionId, "user_message", mapper.createObjectNode().put("text", "c")); // 3

		var found = journal.findBySeqs(sessionId, java.util.List.of(1L, 3L, 99L));

		assertThat(found).containsOnlyKeys(1L, 3L);
		assertThat(found.get(3L).payload().path("text").asText()).isEqualTo("c");
		assertThat(journal.findBySeqs(sessionId, java.util.List.of())).isEmpty();
	}
}
