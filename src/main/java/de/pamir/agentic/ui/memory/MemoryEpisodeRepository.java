package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.library.PgVector;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Episodic memory: an append-only, DB-native log of "what a session did" (see MemoryRepository for semantic docs). */
@Repository
public class MemoryEpisodeRepository {

	public record Episode(UUID id, UUID sessionId, String sessionName, String servicePath, Instant ts,
						   String summary) {
	}

	public record SearchHit(Episode episode, double score) {
	}

	private static final String SELECT = "SELECT id, session_id, session_name, service_path, ts, summary "
			+ "FROM memory_episode";
	private static final int ARM_LIMIT = 50;
	private static final double RRF_K = 60.0;

	private final JdbcClient jdbc;
	private final RowMapper<Episode> rowMapper;

	public MemoryEpisodeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.rowMapper = this::mapRow;
	}

	public Episode insert(UUID sessionId, String sessionName, String servicePath, String summary) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO memory_episode (id, session_id, session_name, service_path, summary) "
						+ "VALUES (?, ?, ?, ?, ?)")
				.params(id, sessionId, sessionName, servicePath, summary).update();
		return jdbc.sql(SELECT + " WHERE id = ?").params(id).query(rowMapper).single();
	}

	public void upsertEmbedding(UUID id, float[] embedding, String model) {
		jdbc.sql("UPDATE memory_episode SET embedding = ?::vector, embedding_model = ? WHERE id = ?")
				.params(PgVector.literal(embedding), model, id).update();
	}

	/** Most recent episodes for a service — the automatic context window injected at session spawn. */
	public List<Episode> recentByService(String servicePath, int limit) {
		return jdbc.sql(SELECT + " WHERE service_path = ? ORDER BY ts DESC LIMIT ?")
				.params(servicePath, limit).query(rowMapper).list();
	}

	/** Every episode already recorded for one session, oldest first — fed to a re-reflection so it doesn't restate them. */
	public List<Episode> findBySession(UUID sessionId) {
		return jdbc.sql(SELECT + " WHERE session_id = ? ORDER BY ts").params(sessionId).query(rowMapper).list();
	}

	public List<Episode> findByService(String servicePath, Integer limit, Integer offset) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE service_path = ? ORDER BY ts DESC");
		List<Object> params = new ArrayList<>(List.of(servicePath));
		if (limit != null) {
			sql.append(" LIMIT ?");
			params.add(limit);
		}
		if (offset != null) {
			sql.append(" OFFSET ?");
			params.add(offset);
		}
		return jdbc.sql(sql.toString()).params(params).query(rowMapper).list();
	}

	/** Dense + sparse hybrid search over episode summaries, fused with RRF (see MemoryRepository.hybridSearch). */
	public List<SearchHit> hybridSearch(String queryText, float[] queryEmbedding, String servicePath, int limit) {
		StringBuilder filter = new StringBuilder();
		List<Object> filterParams = new ArrayList<>();
		if (servicePath != null && !servicePath.isBlank()) {
			filter.append(" AND service_path = ?");
			filterParams.add(servicePath);
		}
		String filterSql = filter.toString();

		StringBuilder sql = new StringBuilder("WITH ");
		List<Object> params = new ArrayList<>();
		List<String> arms = new ArrayList<>();
		if (queryEmbedding != null) {
			sql.append("""
							dense AS (
								SELECT id, rnk FROM (
									SELECT id, row_number() OVER (ORDER BY embedding <=> ?::vector) AS rnk
									FROM memory_episode WHERE embedding IS NOT NULL""").append(filterSql).append("""
								) x ORDER BY rnk LIMIT %d
							),
							""".formatted(ARM_LIMIT));
			params.add(PgVector.literal(queryEmbedding));
			params.addAll(filterParams);
			arms.add("dense");
		}
		sql.append("""
						sparse AS (
							SELECT id, rnk FROM (
								SELECT id, row_number() OVER (
									ORDER BY ts_rank_cd(tsv, websearch_to_tsquery('english', ?)) DESC) AS rnk
								FROM memory_episode WHERE tsv @@ websearch_to_tsquery('english', ?)""")
				.append(filterSql).append("""
						) x ORDER BY rnk LIMIT %d
					),
					""".formatted(ARM_LIMIT));
		params.add(queryText);
		params.add(queryText);
		params.addAll(filterParams);
		arms.add("sparse");

		sql.append("fused AS (SELECT id, SUM(1.0 / (").append(RRF_K).append(" + rnk)) AS score FROM (")
				.append(String.join(" UNION ALL ", arms.stream().map(a -> "SELECT * FROM " + a).toList()))
				.append(") u GROUP BY id) ")
				.append("SELECT e.id, e.session_id, e.session_name, e.service_path, e.ts, e.summary, ")
				.append("f.score AS score FROM fused f JOIN memory_episode e ON e.id = f.id ")
				.append("ORDER BY f.score DESC LIMIT ?");
		params.add(limit);
		return jdbc.sql(sql.toString()).params(params)
				.query((rs, n) -> new SearchHit(mapRow(rs, n), rs.getDouble("score"))).list();
	}

	private Episode mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Episode(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
				rs.getString("session_name"), rs.getString("service_path"), rs.getTimestamp("ts").toInstant(),
				rs.getString("summary"));
	}

}
