package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.journal.EventJournal;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link EventJournal} double — overrides every method SessionService's tested paths
 * call, bypassing the real jdbc-backed implementation entirely (whose constructor also starts a
 * scheduled flush task; harmless here since the superclass's own per-session buffer, which that
 * task drains, never receives anything — every append this fake handles is short-circuited
 * before touching it). See docs/plan/phase-9-production-hardening.md T2.
 */
final class FakeEventJournal extends EventJournal {

	private final Map<UUID, Long> seqs = new ConcurrentHashMap<>();
	private final Map<UUID, BigDecimal> costs = new ConcurrentHashMap<>();
	private final Map<UUID, List<JournalEvent>> journaled = new ConcurrentHashMap<>();
	/** Set whenever {@link #readAfter} is called — lets a test assert it was (or wasn't) hit. */
	boolean readAfterCalled;

	FakeEventJournal(AppProperties props) {
		super(null, null, props);
	}

	void setCostToDate(UUID id, BigDecimal cost) {
		costs.put(id, cost);
	}

	/** Everything appended for this session, in order (readAfter stays a stub — see its own doc). */
	List<JournalEvent> journaled(UUID id) {
		return journaled.getOrDefault(id, List.of());
	}

	@Override
	public JournalEvent append(UUID sessionId, String type, tools.jackson.databind.JsonNode payload) {
		long seq = seqs.merge(sessionId, 1L, Long::sum);
		JournalEvent event = new JournalEvent(seq, java.time.Instant.now(), type, payload);
		journaled.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(event);
		return event;
	}

	@Override
	public long lastSeq(UUID sessionId) {
		return seqs.getOrDefault(sessionId, 0L);
	}

	@Override
	public int deleteDeltasBefore(UUID sessionId, long beforeSeq) {
		return 0;
	}

	@Override
	public BigDecimal costToDate(UUID sessionId) {
		return costs.getOrDefault(sessionId, BigDecimal.ZERO);
	}

	@Override
	public List<JournalEvent> readAfter(UUID sessionId, long afterSeq) {
		readAfterCalled = true;
		return List.of();
	}

	@Override
	public long countEventType(UUID sessionId, String type) {
		return journaled.getOrDefault(sessionId, List.of()).stream().filter(e -> e.type().equals(type)).count();
	}

	@Override
	public Optional<JournalEvent> firstEventOfType(UUID sessionId, String type) {
		return journaled.getOrDefault(sessionId, List.of()).stream().filter(e -> e.type().equals(type)).findFirst();
	}

	@Override
	public boolean hasEventType(UUID sessionId, String type) {
		return journaled.getOrDefault(sessionId, List.of()).stream().anyMatch(e -> e.type().equals(type));
	}
}
