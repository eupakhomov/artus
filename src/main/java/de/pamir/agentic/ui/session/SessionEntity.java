package de.pamir.agentic.ui.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of the session table. JSONB columns surface as JsonNode / lists. */
public record SessionEntity(
		UUID id,
		String name,
		String provider,
		JsonNode providerConfig,
		String repoPath,
		/**
		 * Raw stored value; NULL = "same as repoPath" (polyrepo, and every pre-Phase-11 row — see
		 * docs/plan/phase-11-monorepo.md). The record's default public accessor for this component
		 * is overridden below to resolve the NULL ({@link #servicePath()}) so no caller has to
		 * branch; {@link #rawServicePath()} is the raw, possibly-null value, for {@link
		 * SessionRepository}'s insert only.
		 */
		String servicePath,
		String ecosystemPath,
		List<String> contextDirs,
		String branch,
		String baseBranch,
		String worktreePath,
		String providerSessionId,
		JsonNode capabilities,
		String model,
		String permissionMode,
		List<String> allowedTools,
		List<String> disallowedTools,
		JsonNode mcpConfig,
		JsonNode envVars,
		JsonNode skillSources,
		JsonNode agentSources,
		String instructions,
		String thinking,
		String effort,
		Integer maxTurns,
		String fallbackModel,
		BigDecimal costBudgetUsd,
		String kickoffPrompt,
		SessionState state,
		/** 'user' (default) or 'system' — backend-initiated tasks (ticket import, ...), hidden by default in the UI */
		String kind,
		/** 'development' (default) or 'review' — see docs/plan/phase-15-review-sessions.md. A review
		 * session's worktree is a detached checkout of the reviewed branch's tip; commit/push/PR are
		 * blocked at the REST layer ({@link de.pamir.agentic.ui.web.GitSessionController}). */
		String sessionType,
		/** Canonical ticket identifier (e.g. "ENG-123") if this session was created via ticket import; null otherwise */
		String ticketRef,
		/** Source session this one carried a handoff summary/digest from (see docs/plan/phase-7-ux-and-orchestration.md 7.3); null otherwise */
		UUID continuedFromId,
		/** Parent session this one was spawned by via spawn_child_session (7.4); null for ordinary/parent sessions. Depth 1 — a child's own parentSessionId is never set on ITS children, because it can't have any */
		UUID parentSessionId,
		/** GitHub PR URL opened from this session's branch, if any; one PR tracked per session */
		String prUrl,
		/** Head commit the last check result applies to — a mismatch on the next poll means new commits were pushed */
		String prHeadSha,
		/** PENDING | SUCCESS | FAILURE | MERGED | CLOSED | ERROR; null when prUrl is null */
		String prCheckStatus,
		Instant prCheckedAt,
		/** Opt-in end-of-session memory retrospective (see docs/plan/phase-5.3-memory-reflection.md) */
		boolean reflectionEnabled,
		/** Journal seq covered by the last reflection; null = never reflected */
		Long reflectedSeq,
		/** Which code-intelligence MCP server was layered into this session at creation — "serena"
		 * (docs/plan/phase-12-linear-cache-serena-context.md Track B) or "graphify" (docs/plan/
		 * phase-13-graphify.md decision 5); null = none. Baked per session, since the MCP entry is. */
		String codeIntel,
		/** Latest known context-window usage (docs/plan/phase-12-linear-cache-serena-context.md
		 * decision 9); null until the sidecar's first context_usage event. Both null or both set. */
		Integer contextTokens,
		Integer contextWindow,
		/** Phase 18 opt-in: mirror this session into Slack (docs/plan/phase-18-slack.md). */
		boolean slackEnabled,
		/** Channel and parent-message {@code ts} of this session's Slack thread; null until the first post succeeds. */
		String slackChannel,
		String slackThreadTs,
		Instant createdAt,
		Instant updatedAt,
		/** When the user-facing close finished (phase 23); null while open and for rows closed before V21 had no source. */
		Instant closedAt
) {

	/**
	 * Resolved service identity — {@code servicePath} when set, else {@code repoPath} (polyrepo,
	 * and every pre-Phase-11 row). This is what memory/discovery/orchestration scope on; overrides
	 * the record's default accessor for the {@code servicePath} component so no caller branches on
	 * NULL. Explicitly annotated because a plain no-arg method isn't picked up by Jackson's default
	 * bean-property detection the way a record component's own accessor is.
	 */
	@JsonProperty("servicePath")
	public String servicePath() {
		return servicePath == null ? repoPath : servicePath;
	}

	/** Raw {@code service_path} column value (possibly null) — {@link SessionRepository}'s insert only. */
	String rawServicePath() {
		return servicePath;
	}

	/**
	 * This session's sidecar cwd: {@code worktreePath} when {@link #servicePath()} equals {@code
	 * repoPath} (byte-identical to every pre-Phase-11 session), else the worktree-relative
	 * subfolder for the service.
	 */
	@JsonProperty("cwdPath")
	public String cwdPath() {
		String service = servicePath();
		if (service.equals(repoPath)) {
			return worktreePath;
		}
		Path relative = Path.of(repoPath).relativize(Path.of(service));
		return Path.of(worktreePath).resolve(relative).toString();
	}

	public static Builder builder() {
		return new Builder();
	}

	/** A builder pre-seeded with this entity's own fields, for a "copy with one field changed" update. */
	public Builder toBuilder() {
		return builder().id(id).name(name).provider(provider).providerConfig(providerConfig)
				.repoPath(repoPath).servicePath(servicePath).ecosystemPath(ecosystemPath).contextDirs(contextDirs)
				.branch(branch).baseBranch(baseBranch).worktreePath(worktreePath)
				.providerSessionId(providerSessionId).capabilities(capabilities).model(model)
				.permissionMode(permissionMode).allowedTools(allowedTools).disallowedTools(disallowedTools)
				.mcpConfig(mcpConfig).envVars(envVars).skillSources(skillSources).agentSources(agentSources)
				.instructions(instructions).thinking(thinking).effort(effort).maxTurns(maxTurns)
				.fallbackModel(fallbackModel).costBudgetUsd(costBudgetUsd).kickoffPrompt(kickoffPrompt)
				.state(state).kind(kind).sessionType(sessionType).ticketRef(ticketRef).continuedFromId(continuedFromId)
				.parentSessionId(parentSessionId).prUrl(prUrl).prHeadSha(prHeadSha).prCheckStatus(prCheckStatus)
				.prCheckedAt(prCheckedAt).reflectionEnabled(reflectionEnabled).reflectedSeq(reflectedSeq)
				.codeIntel(codeIntel).contextTokens(contextTokens).contextWindow(contextWindow)
				.slackEnabled(slackEnabled).slackChannel(slackChannel).slackThreadTs(slackThreadTs)
				.createdAt(createdAt).updatedAt(updatedAt).closedAt(closedAt);
	}

	/**
	 * Named-setter alternative to the record's ~35-arg positional constructor (see
	 * docs/plan/phase-9-production-hardening.md S3) — used at the two call sites that build a
	 * fresh entity from scratch ({@code SessionConfigFactory.build}, {@code
	 * SystemSessionService.createSystemSession}); {@code SessionRepository.mapRow} keeps the
	 * positional constructor since it's already an unambiguous 1:1 column mapping in field order.
	 * Fields default to {@code null}/empty exactly as the equivalent positional call's long
	 * {@code null, null, ...} runs did.
	 */
	public static final class Builder {
		private UUID id;
		private String name;
		private String provider;
		private JsonNode providerConfig;
		private String repoPath;
		private String servicePath;
		private String ecosystemPath;
		private List<String> contextDirs = List.of();
		private String branch;
		private String baseBranch;
		private String worktreePath;
		private String providerSessionId;
		private JsonNode capabilities;
		private String model;
		private String permissionMode = "default";
		private List<String> allowedTools = List.of();
		private List<String> disallowedTools = List.of();
		private JsonNode mcpConfig;
		private JsonNode envVars;
		// column is NOT NULL — default to empty rather than making every caller (tests especially)
		// spell out ".skillSources(mapper.createArrayNode())" for the common "no sources" case
		private JsonNode skillSources = JsonNodeFactory.instance.arrayNode();
		private JsonNode agentSources = JsonNodeFactory.instance.arrayNode();
		private String instructions;
		private String thinking;
		private String effort;
		private Integer maxTurns;
		private String fallbackModel;
		private BigDecimal costBudgetUsd;
		private String kickoffPrompt;
		private SessionState state = SessionState.CREATING;
		private String kind = "user";
		private String sessionType = "development";
		private String ticketRef;
		private UUID continuedFromId;
		private UUID parentSessionId;
		private String prUrl;
		private String prHeadSha;
		private String prCheckStatus;
		private Instant prCheckedAt;
		private boolean reflectionEnabled;
		private Long reflectedSeq;
		private String codeIntel;
		private Integer contextTokens;
		private Integer contextWindow;
		private boolean slackEnabled;
		private String slackChannel;
		private String slackThreadTs;
		private Instant createdAt;
		private Instant updatedAt;
		private Instant closedAt;

		private Builder() {
		}

		public Builder id(UUID v) { this.id = v; return this; }
		public Builder name(String v) { this.name = v; return this; }
		public Builder provider(String v) { this.provider = v; return this; }
		public Builder providerConfig(JsonNode v) { this.providerConfig = v; return this; }
		public Builder repoPath(String v) { this.repoPath = v; return this; }
		public Builder servicePath(String v) { this.servicePath = v; return this; }
		public Builder ecosystemPath(String v) { this.ecosystemPath = v; return this; }
		public Builder contextDirs(List<String> v) { this.contextDirs = v; return this; }
		public Builder branch(String v) { this.branch = v; return this; }
		public Builder baseBranch(String v) { this.baseBranch = v; return this; }
		public Builder worktreePath(String v) { this.worktreePath = v; return this; }
		public Builder providerSessionId(String v) { this.providerSessionId = v; return this; }
		public Builder capabilities(JsonNode v) { this.capabilities = v; return this; }
		public Builder model(String v) { this.model = v; return this; }
		public Builder permissionMode(String v) { this.permissionMode = v; return this; }
		public Builder allowedTools(List<String> v) { this.allowedTools = v; return this; }
		public Builder disallowedTools(List<String> v) { this.disallowedTools = v; return this; }
		public Builder mcpConfig(JsonNode v) { this.mcpConfig = v; return this; }
		public Builder envVars(JsonNode v) { this.envVars = v; return this; }
		public Builder skillSources(JsonNode v) { this.skillSources = v; return this; }
		public Builder agentSources(JsonNode v) { this.agentSources = v; return this; }
		public Builder instructions(String v) { this.instructions = v; return this; }
		public Builder thinking(String v) { this.thinking = v; return this; }
		public Builder effort(String v) { this.effort = v; return this; }
		public Builder maxTurns(Integer v) { this.maxTurns = v; return this; }
		public Builder fallbackModel(String v) { this.fallbackModel = v; return this; }
		public Builder costBudgetUsd(BigDecimal v) { this.costBudgetUsd = v; return this; }
		public Builder kickoffPrompt(String v) { this.kickoffPrompt = v; return this; }
		public Builder state(SessionState v) { this.state = v; return this; }
		public Builder kind(String v) { this.kind = v; return this; }
		public Builder sessionType(String v) { this.sessionType = v; return this; }
		public Builder ticketRef(String v) { this.ticketRef = v; return this; }
		public Builder continuedFromId(UUID v) { this.continuedFromId = v; return this; }
		public Builder parentSessionId(UUID v) { this.parentSessionId = v; return this; }
		public Builder prUrl(String v) { this.prUrl = v; return this; }
		public Builder prHeadSha(String v) { this.prHeadSha = v; return this; }
		public Builder prCheckStatus(String v) { this.prCheckStatus = v; return this; }
		public Builder prCheckedAt(Instant v) { this.prCheckedAt = v; return this; }
		public Builder reflectionEnabled(boolean v) { this.reflectionEnabled = v; return this; }
		public Builder reflectedSeq(Long v) { this.reflectedSeq = v; return this; }
		public Builder codeIntel(String v) { this.codeIntel = v; return this; }
		public Builder contextTokens(Integer v) { this.contextTokens = v; return this; }
		public Builder contextWindow(Integer v) { this.contextWindow = v; return this; }
		public Builder slackEnabled(boolean v) { this.slackEnabled = v; return this; }
		public Builder slackChannel(String v) { this.slackChannel = v; return this; }
		public Builder slackThreadTs(String v) { this.slackThreadTs = v; return this; }
		public Builder createdAt(Instant v) { this.createdAt = v; return this; }
		public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
		public Builder closedAt(Instant v) { this.closedAt = v; return this; }

		public SessionEntity build() {
			return new SessionEntity(id, name, provider, providerConfig, repoPath, servicePath, ecosystemPath, contextDirs,
					branch, baseBranch, worktreePath, providerSessionId, capabilities, model, permissionMode,
					allowedTools, disallowedTools, mcpConfig, envVars, skillSources, agentSources, instructions,
					thinking, effort, maxTurns, fallbackModel, costBudgetUsd, kickoffPrompt, state, kind, sessionType,
					ticketRef, continuedFromId, parentSessionId, prUrl, prHeadSha, prCheckStatus, prCheckedAt,
					reflectionEnabled, reflectedSeq, codeIntel, contextTokens, contextWindow, slackEnabled, slackChannel, slackThreadTs, createdAt, updatedAt, closedAt);
		}
	}
}
