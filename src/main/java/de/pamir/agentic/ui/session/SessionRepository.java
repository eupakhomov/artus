package de.pamir.agentic.ui.session;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SessionRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final RowMapper<SessionEntity> rowMapper;

	public SessionRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.rowMapper = this::mapRow;
	}

	public void insert(SessionEntity s) {
		jdbc.sql("""
						INSERT INTO session (id, name, provider, provider_config, repo_path, service_path,
							ecosystem_path, context_dirs, branch, base_branch, worktree_path, model, permission_mode,
							allowed_tools, disallowed_tools, mcp_config, env_vars, skill_sources, agent_sources,
							instructions, thinking, effort, max_turns, fallback_model, cost_budget_usd,
							kickoff_prompt, state, kind, session_type, ticket_ref, continued_from_id, parent_session_id,
							reflection_enabled, code_intel, slack_enabled, tools, plugin_dirs)
						VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
							?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
						""")
				.params(s.id(), s.name(), s.provider(), json(s.providerConfig()), s.repoPath(), s.rawServicePath(),
						s.ecosystemPath(), json(s.contextDirs()), s.branch(), s.baseBranch(), s.worktreePath(),
						s.model(), s.permissionMode(), json(s.allowedTools()), json(s.disallowedTools()),
						json(s.mcpConfig()), json(s.envVars()), json(s.skillSources()), json(s.agentSources()),
						s.instructions(), s.thinking(), s.effort(), s.maxTurns(), s.fallbackModel(), s.costBudgetUsd(),
						s.kickoffPrompt(), s.state().name(), s.kind(), s.sessionType(), s.ticketRef(), s.continuedFromId(),
						s.parentSessionId(), s.reflectionEnabled(), s.codeIntel(), s.slackEnabled(), json(s.tools()), json(s.pluginDirs()))
				.update();
	}

	public Optional<SessionEntity> find(UUID id) {
		return jdbc.sql("SELECT * FROM session WHERE id = ?").params(id).query(rowMapper).optional();
	}

	/** The live (not CLOSED/FAILED) system session with this {@code name} — one per
	 * {@link SystemTurnLane}, see SystemSessionService. */
	public Optional<SessionEntity> findSystemSession(String name) {
		return jdbc.sql("SELECT * FROM session WHERE kind = 'system' AND name = ? "
						+ "AND state NOT IN ('CLOSED', 'FAILED') ORDER BY created_at DESC LIMIT 1")
				.param(name).query(rowMapper).optional();
	}

	/** Every live (not CLOSED/FAILED) system session, whatever its name — startup legacy retirement. */
	public List<SessionEntity> findLiveSystemSessions() {
		return jdbc.sql("SELECT * FROM session WHERE kind = 'system' AND state NOT IN ('CLOSED', 'FAILED') "
						+ "ORDER BY created_at")
				.query(rowMapper).list();
	}

	public SessionEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("session " + id + " not found"));
	}

	public List<SessionEntity> findAll() {
		return jdbc.sql("SELECT * FROM session ORDER BY created_at DESC").query(rowMapper).list();
	}

	public List<SessionEntity> findByStates(List<SessionState> states) {
		return jdbc.sql("SELECT * FROM session WHERE state = ANY(?::text[]) ORDER BY created_at")
				.params((Object) states.stream().map(Enum::name).toArray(String[]::new))
				.query(rowMapper).list();
	}

	/** Final CLOSED transition — state and {@code closed_at} in one statement (phase 23). */
	public void markClosed(UUID id) {
		jdbc.sql("UPDATE session SET state = 'CLOSED', closed_at = clock_timestamp(), updated_at = now() WHERE id = ?")
				.params(id).update();
	}

	/** The most recently closed user-facing sessions, newest close first (sidebar History). */
	public List<SessionEntity> findRecentlyClosed(int limit) {
		return jdbc.sql("SELECT * FROM session WHERE state = 'CLOSED' AND kind <> 'system' "
						+ "ORDER BY closed_at DESC NULLS LAST, updated_at DESC LIMIT ?")
				.param(limit).query(rowMapper).list();
	}

	/** Any transition clears {@code crash_reason}; only a CRASHED one may set it (see the 3-arg overload). */
	public void updateState(UUID id, SessionState state) {
		updateState(id, state, null);
	}

	public void updateState(UUID id, SessionState state, String crashReason) {
		jdbc.sql("UPDATE session SET state = ?, crash_reason = ?, updated_at = now() WHERE id = ?")
				.params(state.name(), state == SessionState.CRASHED ? crashReason : null, id).update();
	}

	public void updateProviderSessionId(UUID id, String providerSessionId) {
		jdbc.sql("UPDATE session SET provider_session_id = ?, updated_at = now() WHERE id = ?")
				.params(providerSessionId, id).update();
	}

	public void updateCapabilities(UUID id, JsonNode capabilities) {
		jdbc.sql("UPDATE session SET capabilities = ?::jsonb, updated_at = now() WHERE id = ?")
				.params(json(capabilities), id).update();
	}

	public void updatePermissionMode(UUID id, String mode) {
		jdbc.sql("UPDATE session SET permission_mode = ?, updated_at = now() WHERE id = ?")
				.params(mode, id).update();
	}

	public void updateModel(UUID id, String model) {
		jdbc.sql("UPDATE session SET model = ?, updated_at = now() WHERE id = ?")
				.params(model, id).update();
	}

	public void updateName(UUID id, String name) {
		jdbc.sql("UPDATE session SET name = ?, updated_at = now() WHERE id = ?").params(name, id).update();
	}

	public void updateCostBudget(UUID id, BigDecimal budget) {
		jdbc.sql("UPDATE session SET cost_budget_usd = ?, updated_at = now() WHERE id = ?")
				.params(budget, id).update();
	}

	/** Phase 25: no updated_at bump — hiding is not activity, and idle parking reads updated_at. */
	public void updateHidden(UUID id, boolean hidden) {
		jdbc.sql("UPDATE session SET hidden = ? WHERE id = ?").params(hidden, id).update();
	}

	public void updateReflectionEnabled(UUID id, boolean enabled) {
		jdbc.sql("UPDATE session SET reflection_enabled = ?, updated_at = now() WHERE id = ?")
				.params(enabled, id).update();
	}

	/** Watermark set after a successful reflection so a later trigger with no new turns is a no-op. */
	public void updateReflectedSeq(UUID id, long seq) {
		jdbc.sql("UPDATE session SET reflected_seq = ? WHERE id = ?").params(seq, id).update();
	}

	/** Sessions eligible for journal pruning: CLOSED, reflected, and reflected before the cutoff. */
	public List<SessionEntity> findPrunableClosed(Instant cutoff) {
		return jdbc.sql("SELECT * FROM session WHERE state = 'CLOSED' AND reflected_seq IS NOT NULL "
						+ "AND updated_at <= ? ORDER BY updated_at")
				.params(Timestamp.from(cutoff))
				.query(rowMapper).list();
	}

	/** Called right after a PR is created; seeds the polling loop with a PENDING check. */
	public void attachPr(UUID id, String prUrl, String headSha) {
		jdbc.sql("UPDATE session SET pr_url = ?, pr_head_sha = ?, pr_check_status = 'PENDING', "
						+ "pr_checked_at = NULL, updated_at = now() WHERE id = ?")
				.params(prUrl, headSha, id).update();
	}

	/** Called after a follow-up push to an already-PR'd branch, so the next tick re-checks. */
	public void resetPrCheckPending(UUID id) {
		jdbc.sql("UPDATE session SET pr_check_status = 'PENDING', pr_checked_at = NULL, updated_at = now() "
						+ "WHERE id = ? AND pr_url IS NOT NULL")
				.params(id).update();
	}

	public void updatePrCheck(UUID id, String status, String headSha, Instant checkedAt) {
		jdbc.sql("UPDATE session SET pr_check_status = ?, pr_head_sha = ?, pr_checked_at = ? WHERE id = ?")
				.params(status, headSha, Timestamp.from(checkedAt), id).update();
	}

	/** Latest known context-window snapshot, refreshed after every turn and every compaction
	 * (docs/plan/phase-12-linear-cache-serena-context.md decision 9). */
	public void updateContextUsage(UUID id, int tokens, int window) {
		jdbc.sql("UPDATE session SET context_tokens = ?, context_window = ?, updated_at = now() WHERE id = ?")
				.params(tokens, window, id).update();
	}

	/** Records where this session's Slack thread lives, once its parent message has been posted. */
	public void attachSlackThread(UUID id, String channel, String threadTs) {
		jdbc.sql("UPDATE session SET slack_channel = ?, slack_thread_ts = ? WHERE id = ?")
				.params(channel, threadTs, id).update();
	}

	/** The session whose Slack thread is {@code threadTs} in {@code channel} (a thread reply's {@code thread_ts}). */
	public Optional<SessionEntity> findBySlackThread(String channel, String threadTs) {
		return jdbc.sql("SELECT * FROM session WHERE slack_channel = ? AND slack_thread_ts = ? LIMIT 1")
				.params(channel, threadTs).query(rowMapper).optional();
	}

	/** Sessions with an open PR whose status is still PENDING and due for another check. */
	public List<SessionEntity> findAwaitingPrCheck(Instant cutoff) {
		return jdbc.sql("SELECT * FROM session WHERE pr_url IS NOT NULL AND pr_check_status = 'PENDING' "
						+ "AND (pr_checked_at IS NULL OR pr_checked_at <= ?) ORDER BY created_at")
				.params(Timestamp.from(cutoff))
				.query(rowMapper).list();
	}

	/** All children ever spawned from this parent (any state) — 7.4's check_children tool. */
	public List<SessionEntity> findByParent(UUID parentId) {
		return jdbc.sql("SELECT * FROM session WHERE parent_session_id = ? ORDER BY created_at")
				.params(parentId).query(rowMapper).list();
	}

	/** Lifetime count, not just currently-live — children aren't recycled (see phase-7 doc's out-of-scope list), so MAX_CHILDREN caps how many a parent may ever spawn. */
	public long countChildren(UUID parentId) {
		return jdbc.sql("SELECT count(*) FROM session WHERE parent_session_id = ?")
				.params(parentId).query(Long.class).single();
	}

	public long countByStates(List<SessionState> states) {
		return jdbc.sql("SELECT count(*) FROM session WHERE state = ANY(?::text[])")
				.params((Object) states.stream().map(Enum::name).toArray(String[]::new))
				.query(Long.class).single();
	}

	// --- queue ---

	public record QueuedMessage(long pos, String text) {
	}

	public void enqueue(UUID sessionId, String text) {
		jdbc.sql("INSERT INTO session_queue (session_id, text) VALUES (?, ?)").params(sessionId, text).update();
	}

	public List<QueuedMessage> queued(UUID sessionId) {
		return jdbc.sql("SELECT pos, text FROM session_queue WHERE session_id = ? ORDER BY pos")
				.params(sessionId)
				.query((rs, i) -> new QueuedMessage(rs.getLong("pos"), rs.getString("text"))).list();
	}

	/** Head of the queue without removing it — the caller deletes it only after a successful dispatch. */
	public Optional<QueuedMessage> peekQueue(UUID sessionId) {
		return jdbc.sql("SELECT pos, text FROM session_queue WHERE session_id = ? ORDER BY pos LIMIT 1")
				.params(sessionId)
				.query((rs, i) -> new QueuedMessage(rs.getLong("pos"), rs.getString("text"))).optional();
	}

	public boolean deleteQueued(UUID sessionId, long pos) {
		return jdbc.sql("DELETE FROM session_queue WHERE session_id = ? AND pos = ?")
				.params(sessionId, pos).update() > 0;
	}

	// --- mapping helpers ---

	private String json(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return mapper.writeValueAsString(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("JSON serialization failed", e);
		}
	}

	private JsonNode readNode(String value) {
		if (value == null) {
			return null;
		}
		try {
			return mapper.readTree(value);
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB payload", e);
		}
	}

	private List<String> readStringList(String value) {
		if (value == null) {
			return List.of();
		}
		try {
			return mapper.readValue(value, new TypeReference<>() {
			});
		} catch (JacksonException e) {
			throw new IllegalStateException("bad JSONB list", e);
		}
	}

	private SessionEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new SessionEntity(
				rs.getObject("id", UUID.class),
				rs.getString("name"),
				rs.getString("provider"),
				readNode(rs.getString("provider_config")),
				rs.getString("repo_path"),
				rs.getString("service_path"),
				rs.getString("ecosystem_path"),
				readStringList(rs.getString("context_dirs")),
				rs.getString("branch"),
				rs.getString("base_branch"),
				rs.getString("worktree_path"),
				rs.getString("provider_session_id"),
				readNode(rs.getString("capabilities")),
				rs.getString("model"),
				rs.getString("permission_mode"),
				readStringList(rs.getString("allowed_tools")),
				readStringList(rs.getString("disallowed_tools")),
				readNode(rs.getString("mcp_config")),
				readNode(rs.getString("env_vars")),
				readNode(rs.getString("skill_sources")),
				readNode(rs.getString("agent_sources")),
				rs.getString("instructions"),
				rs.getString("thinking"),
				rs.getString("effort"),
				(Integer) rs.getObject("max_turns"),
				rs.getString("fallback_model"),
				rs.getBigDecimal("cost_budget_usd"),
				rs.getString("kickoff_prompt"),
				SessionState.valueOf(rs.getString("state")),
				rs.getString("kind"),
				rs.getString("session_type"),
				rs.getString("ticket_ref"),
				rs.getObject("continued_from_id", UUID.class),
				rs.getObject("parent_session_id", UUID.class),
				rs.getString("pr_url"),
				rs.getString("pr_head_sha"),
				rs.getString("pr_check_status"),
				rs.getTimestamp("pr_checked_at") == null ? null : rs.getTimestamp("pr_checked_at").toInstant(),
				rs.getBoolean("reflection_enabled"),
				(Long) rs.getObject("reflected_seq"),
				rs.getString("code_intel"),
				(Integer) rs.getObject("context_tokens"),
				(Integer) rs.getObject("context_window"),
				rs.getBoolean("slack_enabled"),
				rs.getString("slack_channel"),
				rs.getString("slack_thread_ts"),
				rs.getTimestamp("created_at").toInstant(),
				rs.getTimestamp("updated_at").toInstant(),
				rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
				rs.getBoolean("hidden"),
				readStringList(rs.getString("tools")),
				readStringList(rs.getString("plugin_dirs")),
				rs.getString("crash_reason"));
	}
}
