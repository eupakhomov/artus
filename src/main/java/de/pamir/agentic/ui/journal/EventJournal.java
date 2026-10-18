package de.pamir.agentic.ui.journal;

import de.pamir.agentic.ui.config.AppProperties;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Append-only per-session event journal with backend-assigned monotonic seq.
 * stream_delta events are buffered and batch-inserted (they dominate volume);
 * any other event type flushes the buffer first so insertion order equals seq order.
 */
@Component
public class EventJournal {

	public record JournalEvent(long seq, Instant ts, String type, JsonNode payload) {
	}

	private static final Logger log = LoggerFactory.getLogger(EventJournal.class);

	/**
	 * A real NUL character serializes to the six-character JSON escape (backslash, u, 0, 0, 0, 0), which
	 * Postgres jsonb refuses ("cannot be converted to text"). Matches only an escape that starts a new
	 * escape sequence, so a backslash in the data followed by u0000 (e.g. source code quoted by the
	 * agent) is left alone.
	 */
	private static final Pattern NUL_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

	private static final int FLUSH_THRESHOLD = 50;
	private static final long FLUSH_INTERVAL_MS = 250;

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final int payloadCapBytes;
	private final Map<UUID, PerSession> sessions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService flusher =
			Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("journal-flusher").factory());

	public EventJournal(JdbcClient jdbc, ObjectMapper mapper, AppProperties props) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.payloadCapBytes = props.journalPayloadCapBytes();
		flusher.scheduleWithFixedDelay(this::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/** Assigns the next seq, journals the event (possibly buffered), returns the envelope. */
	public JournalEvent append(UUID sessionId, String type, JsonNode payload) {
		PerSession ps = sessions.computeIfAbsent(sessionId, this::load);
		synchronized (ps) {
			JournalEvent event = new JournalEvent(++ps.lastSeq, Instant.now(), type, cap(payload));
			ps.buffer.add(event);
			if (!"stream_delta".equals(type) || ps.buffer.size() >= FLUSH_THRESHOLD) {
				flush(sessionId, ps);
			}
			return event;
		}
	}

	/** Persist everything buffered for this session (called before any replay read). */
	public void flush(UUID sessionId) {
		PerSession ps = sessions.get(sessionId);
		if (ps != null) {
			synchronized (ps) {
				flush(sessionId, ps);
			}
		}
	}

	public long lastSeq(UUID sessionId) {
		PerSession ps = sessions.computeIfAbsent(sessionId, this::load);
		synchronized (ps) {
			return ps.lastSeq;
		}
	}

	/** Cheap existence check (e.g. 7.4's check_children "reported?" column) — no row materialization. */
	public boolean hasEventType(UUID sessionId, String type) {
		flush(sessionId);
		return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM session_event WHERE session_id = ? AND type = ?)")
				.params(sessionId, type)
				.query(Boolean.class).single());
	}

	/**
	 * Cheap count of one event type for a session (e.g. counting turn_complete rows without
	 * materializing the whole transcript — see docs/plan/phase-10-review-followups.md R3,
	 * sibling of {@link #hasEventType}).
	 */
	public long countEventType(UUID sessionId, String type) {
		flush(sessionId);
		return jdbc.sql("SELECT count(*) FROM session_event WHERE session_id = ? AND type = ?")
				.params(sessionId, type)
				.query(Long.class).single();
	}

	/** First event of a given type for a session, if any — see docs/plan/phase-10-review-followups.md R3. */
	public Optional<JournalEvent> firstEventOfType(UUID sessionId, String type) {
		flush(sessionId);
		return jdbc.sql("""
						SELECT seq, ts, type, payload FROM session_event
						WHERE session_id = ? AND type = ? ORDER BY seq LIMIT 1""")
				.params(sessionId, type)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.optional();
	}

	/** Most recent event of a given type for a session, if any (phase 22: the latest diagram). */
	public Optional<JournalEvent> lastEventOfType(UUID sessionId, String type) {
		flush(sessionId);
		return jdbc.sql("""
						SELECT seq, ts, type, payload FROM session_event
						WHERE session_id = ? AND type = ? ORDER BY seq DESC LIMIT 1""")
				.params(sessionId, type)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.optional();
	}

	/** Whether any event of one of the given types exists after {@code afterSeq} — no row materialization. */
	public boolean hasEventsAfter(UUID sessionId, long afterSeq, java.util.Collection<String> types) {
		if (types.isEmpty()) {
			return false;
		}
		flush(sessionId);
		String placeholders = String.join(", ", java.util.Collections.nCopies(types.size(), "?"));
		List<Object> params = new ArrayList<>(List.of(sessionId, afterSeq));
		params.addAll(types);
		return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS(SELECT 1 FROM session_event WHERE session_id = ? AND seq > ? "
						+ "AND type IN (" + placeholders + "))")
				.params(params)
				.query(Boolean.class).single());
	}

	/** One event by its seq, if it exists (and has not been pruned). */
	public Optional<JournalEvent> findBySeq(UUID sessionId, long seq) {
		flush(sessionId);
		return jdbc.sql("SELECT seq, ts, type, payload FROM session_event WHERE session_id = ? AND seq = ?")
				.params(sessionId, seq)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.optional();
	}

	/** Several events by seq in one query — missing/pruned seqs are simply absent from the result. */
	public Map<Long, JournalEvent> findBySeqs(UUID sessionId, java.util.Collection<Long> seqs) {
		if (seqs.isEmpty()) {
			return Map.of();
		}
		flush(sessionId);
		String placeholders = String.join(", ", java.util.Collections.nCopies(seqs.size(), "?"));
		List<Object> params = new ArrayList<>(List.of(sessionId));
		params.addAll(seqs);
		Map<Long, JournalEvent> found = new java.util.LinkedHashMap<>();
		jdbc.sql("SELECT seq, ts, type, payload FROM session_event WHERE session_id = ? AND seq IN (" + placeholders + ")")
				.params(params)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.list()
				.forEach(e -> found.put(e.seq(), e));
		return found;
	}

	public List<JournalEvent> readAfter(UUID sessionId, long afterSeq) {
		flush(sessionId);
		return jdbc.sql("SELECT seq, ts, type, payload FROM session_event WHERE session_id = ? AND seq > ? ORDER BY seq")
				.params(sessionId, afterSeq)
				.query((rs, i) -> new JournalEvent(
						rs.getLong("seq"),
						rs.getTimestamp("ts").toInstant(),
						rs.getString("type"),
						readNode(rs.getString("payload"))))
				.list();
	}

	/**
	 * Coalescing: once a turn is complete, its stream_delta rows are redundant with the
	 * journaled assistant_message events — drop them so long sessions stay bounded.
	 */
	public int deleteDeltasBefore(UUID sessionId, long beforeSeq) {
		flush(sessionId);
		return jdbc.sql("DELETE FROM session_event WHERE session_id = ? AND type = 'stream_delta' AND seq < ?")
				.params(sessionId, beforeSeq).update();
	}

	/**
	 * Deletes every journal row for a session (memory retention pruning — see
	 * docs/plan/phase-5.3-memory-reflection.md decision 8: once a session's reflection has
	 * distilled it into durable memory, the raw transcript is disposable). The session row
	 * itself is untouched.
	 */
	public int pruneAll(UUID sessionId) {
		flush(sessionId);
		int deleted = jdbc.sql("DELETE FROM session_event WHERE session_id = ?").params(sessionId).update();
		sessions.remove(sessionId);
		return deleted;
	}

	/**
	 * Drops the in-memory per-session bookkeeping (flushing first) without touching the journal
	 * rows themselves — call once a session is CLOSED and will never be journaled to again, so
	 * {@link #sessions} doesn't grow unbounded (docs/plan/phase-10-review-followups.md R4). Safe
	 * to call at any time: {@link #readAfter}/{@link #lastSeq} on a released session just
	 * lazily {@link #load} again from {@code max(seq)}, exactly as they do for a never-seen one.
	 */
	public void release(UUID sessionId) {
		flush(sessionId);
		sessions.remove(sessionId);
	}

	/** Defense in depth: no single journal row grows beyond the cap. */
	private JsonNode cap(JsonNode payload) {
		String serialized = write(payload);
		if (serialized.contains("\\u0000")) {
			String replaced = NUL_ESCAPE.matcher(serialized).replaceAll("$1\\\\uFFFD");
			if (!replaced.equals(serialized)) {
				serialized = replaced;
				payload = readNode(serialized);
			}
		}
		if (serialized.length() <= payloadCapBytes) {
			return payload;
		}
		var trimmed = mapper.createObjectNode();
		trimmed.put("truncated", true);
		trimmed.put("originalBytes", serialized.length());
		trimmed.put("preview", serialized.substring(0, 4096));
		return trimmed;
	}

	/** Sum of turn_complete costUsd for the session (0 if none). */
	public BigDecimal costToDate(UUID sessionId) {
		flush(sessionId);
		return jdbc.sql("""
						SELECT coalesce(sum((payload->>'costUsd')::numeric), 0)
						FROM session_event WHERE session_id = ? AND type = 'turn_complete'""")
				.params(sessionId).query(BigDecimal.class).single();
	}

	public record TurnUsage(UUID sessionId, String sessionName, Instant ts, String model, BigDecimal costUsd) {
	}

	public record SessionStats(long lastSeq, BigDecimal costToDate) {
	}

	/**
	 * {@link #lastSeq}/{@link #costToDate} for every session that has at least one journal row,
	 * in one query — replaces the N+1 pair `SessionController.list()` used to run per session
	 * (docs/plan/phase-10-review-followups.md R7). A session with no rows at all is simply
	 * absent from the map; callers default it to {@code lastSeq=0, costToDate=0}.
	 */
	public Map<UUID, SessionStats> statsForAll() {
		flushAll();
		try (var stream = jdbc.sql("""
						SELECT session_id, max(seq) AS last_seq,
							   coalesce(sum((payload->>'costUsd')::numeric) FILTER (WHERE type = 'turn_complete'), 0) AS cost
						FROM session_event GROUP BY session_id""")
				.query((rs, i) -> Map.entry(rs.getObject("session_id", UUID.class),
						new SessionStats(rs.getLong("last_seq"), rs.getBigDecimal("cost"))))
				.stream()) {
			return stream.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		}
	}

	/** Per-turn cost/model rows across all sessions since {@code since}, for the usage dashboard. */
	public List<TurnUsage> usageSince(Instant since) {
		flushAll();
		return jdbc.sql("""
						SELECT se.session_id, s.name AS session_name, se.ts,
							   se.payload->>'model' AS model, (se.payload->>'costUsd')::numeric AS cost_usd
						FROM session_event se JOIN session s ON s.id = se.session_id
						WHERE se.type = 'turn_complete' AND se.ts >= ?
						ORDER BY se.ts""")
				.params(Timestamp.from(since))
				.query((rs, i) -> new TurnUsage(rs.getObject("session_id", UUID.class), rs.getString("session_name"),
						rs.getTimestamp("ts").toInstant(), rs.getString("model"), rs.getBigDecimal("cost_usd")))
				.list();
	}

	@PreDestroy
	void shutdown() {
		flusher.shutdown();
		flushAll();
	}

	private void flushAll() {
		for (var entry : sessions.entrySet()) {
			synchronized (entry.getValue()) {
				flush(entry.getKey(), entry.getValue());
			}
		}
	}

	private void flush(UUID sessionId, PerSession ps) {
		if (ps.buffer.isEmpty()) {
			return;
		}
		// row-by-row inside one loop; volume is bounded by FLUSH_THRESHOLD. Each event leaves the buffer
		// as soon as it is dealt with, so a failure part-way never re-inserts the rows before it.
		for (var it = ps.buffer.iterator(); it.hasNext(); ) {
			JournalEvent e = it.next();
			try {
				jdbc.sql("INSERT INTO session_event (session_id, seq, ts, type, payload) VALUES (?, ?, ?, ?, ?::jsonb)")
						.params(sessionId, e.seq(), Timestamp.from(e.ts()), e.type(), write(e.payload()))
						.update();
			} catch (DataIntegrityViolationException ex) {
				// retrying can never succeed; dropping the one event beats wedging every later flush
				log.error("session {}: dropping journal event seq {} ({}): {}", sessionId, e.seq(), e.type(),
						ex.getMostSpecificCause().getMessage());
			}
			it.remove();
		}
	}

	private PerSession load(UUID sessionId) {
		Long max = jdbc.sql("SELECT max(seq) FROM session_event WHERE session_id = ?")
				.params(sessionId).query(Long.class).optional().orElse(null);
		PerSession ps = new PerSession();
		ps.lastSeq = max == null ? 0 : max;
		return ps;
	}

	private String write(JsonNode node) {
		try {
			return mapper.writeValueAsString(node);
		} catch (JacksonException e) {
			throw new IllegalStateException("JSON serialization failed", e);
		}
	}

	private JsonNode readNode(String value) {
		try {
			return mapper.readTree(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB payload", e);
		}
	}

	private static final class PerSession {
		long lastSeq;
		final List<JournalEvent> buffer = new ArrayList<>();
	}
}
