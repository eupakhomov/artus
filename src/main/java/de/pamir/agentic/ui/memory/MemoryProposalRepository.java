package de.pamir.agentic.ui.memory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * A computed-but-not-yet-applied reflection, held for human approval (decision 14,
 * docs/plan/phase-5.3-memory-reflection.md). The partial unique index on
 * (session_id) WHERE status='PENDING' enforces at most one pending proposal per session at
 * the DB level — {@link #insert} surfaces a collision as {@link IllegalStateException}. Phase 21
 * widened that to one pending proposal per session per {@code kind}.
 */
@Repository
public class MemoryProposalRepository {

	public static final String KIND_MEMORY = "memory";
	public static final String KIND_SKILL = "skill";
	public static final String KIND_REMEMBER = "remember";

	/** {@code reflectedSeq} is null for proposals that must not mark the session reflected (skill, remember-this). */
	public record Proposal(UUID id, UUID sessionId, String sessionName, String servicePath, Long reflectedSeq,
							String episode, JsonNode ops, String status, Instant createdAt, Instant decidedAt,
							String kind) {
	}

	private static final String SELECT = "SELECT id, session_id, session_name, service_path, reflected_seq, "
			+ "episode, ops, status, created_at, decided_at, kind FROM memory_proposal";

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<Proposal> rowMapper;

	public MemoryProposalRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = this::mapRow;
	}

	public Proposal insert(UUID sessionId, String sessionName, String servicePath, long reflectedSeq,
							String episode, JsonNode ops) {
		return insert(KIND_MEMORY, sessionId, sessionName, servicePath, reflectedSeq, episode, ops);
	}

	public Proposal insert(String kind, UUID sessionId, String sessionName, String servicePath, Long reflectedSeq,
							String episode, JsonNode ops) {
		UUID id = UUID.randomUUID();
		try {
			jdbc.sql("""
							INSERT INTO memory_proposal (id, session_id, session_name, service_path, reflected_seq,
								episode, ops, kind)
							VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""")
					.params(id, sessionId, sessionName, servicePath, reflectedSeq, episode, mapper.writeValueAsString(ops), kind)
					.update();
		} catch (DuplicateKeyException e) {
			throw new IllegalStateException("a " + kind + " proposal is already pending for this session");
		}
		return get(id);
	}

	public Proposal get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("memory proposal " + id + " not found"));
	}

	public Optional<Proposal> find(UUID id) {
		return jdbc.sql(SELECT + " WHERE id = ?").params(id).query(rowMapper).optional();
	}

	public Optional<Proposal> findPendingForSession(UUID sessionId) {
		return findPendingForSession(sessionId, KIND_MEMORY);
	}

	public Optional<Proposal> findPendingForSession(UUID sessionId, String kind) {
		return jdbc.sql(SELECT + " WHERE session_id = ? AND status = 'PENDING' AND kind = ?")
				.params(sessionId, kind).query(rowMapper).optional();
	}

	public List<Proposal> findByStatus(String status) {
		return jdbc.sql(SELECT + " WHERE status = ? ORDER BY created_at").params(status).query(rowMapper).list();
	}

	public long countPending() {
		return jdbc.sql("SELECT count(*) FROM memory_proposal WHERE status = 'PENDING'").query(Long.class).single();
	}

	public void decide(UUID id, String status) {
		int updated = jdbc.sql("UPDATE memory_proposal SET status = ?, decided_at = now() "
						+ "WHERE id = ? AND status = 'PENDING'")
				.params(status, id).update();
		if (updated == 0) {
			throw new IllegalStateException("proposal " + id + " is not pending (already decided, or unknown)");
		}
	}

	private Proposal mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Proposal(
				rs.getObject("id", UUID.class),
				rs.getObject("session_id", UUID.class),
				rs.getString("session_name"),
				rs.getString("service_path"),
				rs.getObject("reflected_seq", Long.class),
				rs.getString("episode"),
				readNode(rs.getString("ops")),
				rs.getString("status"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant(),
				rs.getString("kind"));
	}

	private JsonNode readNode(String value) {
		try {
			return mapper.readTree(value);
		} catch (RuntimeException e) {
			throw new IllegalStateException("bad JSONB payload", e);
		}
	}
}
