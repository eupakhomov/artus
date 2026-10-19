package de.pamir.agentic.ui.session;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitCommandRunner;
import de.pamir.agentic.ui.integration.CodegraphService;
import de.pamir.agentic.ui.integration.GraphifyService;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.discovery.ServiceDiscoveryRequested;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.memory.ReflectionRequested;
import de.pamir.agentic.ui.process.SidecarManager;
import de.pamir.agentic.ui.provision.AssetProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

	private static final Logger log = LoggerFactory.getLogger(SessionService.class);

	private final AppProperties props;
	private final SettingsService settings;
	private final SessionRepository sessions;
	private final GitWorktreeService worktrees;
	private final GitCommandRunner git;
	private final AssetProvisioningService assets;
	private final SidecarManager sidecars;
	private final EventJournal journal;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;
	private final ApplicationEventPublisher events;
	private final SessionConfigFactory configFactory;
	private final SystemSessionService systemSessionService;
	private final AutoTitleService autoTitleService;
	private final ProviderCatalog catalog;
	/** Graph build lifecycle for {@code codeIntel == "graphify"} sessions (docs/plan/phase-13-graphify.md Step 3); null in unit tests. */
	private final GraphifyService graphify;
	/** Index-at-create for {@code codeIntel == "codegraph"} sessions (docs/plan/phase-14-codegraph.md Step 2); null in unit tests. */
	private final CodegraphService codegraph;
	private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
	/** Requests each live session is blocked on (phase 18): lets the dashboard and Slack race for one decision safely. */
	private final PendingPermissions pendingPermissions = new PendingPermissions();

	public PendingPermissions pendingPermissions() {
		return pendingPermissions;
	}

	/** Guards the enforceSessionLimit()+insert critical section — see {@link #enforceSessionLimitAndInsert}. */
	private final Object sessionLimitLock = new Object();
	/** Sessions woken by {@link #compact} for being PARKED — the compact command fires once
	 * {@link #becomeIdleAndDrainQueue} sees the freshly-spawned sidecar report ready, ahead of
	 * any queued text turn. */
	private final Set<UUID> pendingCompactAfterWake = ConcurrentHashMap.newKeySet();
	/** Live provider background tasks per session, from the latest {@code background_tasks} event (phase 25). */
	private final Map<UUID, Integer> backgroundTaskCounts = new ConcurrentHashMap<>();
	/** Per child: the state its parent was last told about ({@code child_attention}), until the child's turn settles (phase 25). */
	private final Map<UUID, SessionState> childAttentionNoted = new ConcurrentHashMap<>();

	public SessionService(AppProperties props, SettingsService settings, SessionRepository sessions,
						  GitWorktreeService worktrees, GitCommandRunner git,
						  AssetProvisioningService assets, SidecarManager sidecars, EventJournal journal,
						  JournalPublisher journalPublisher, ObjectMapper mapper,
						  ApplicationEventPublisher events,
						  SessionConfigFactory configFactory, SystemSessionService systemSessionService,
						  AutoTitleService autoTitleService, ProviderCatalog catalog, GraphifyService graphify,
						  CodegraphService codegraph) {
		this.props = props;
		this.settings = settings;
		this.sessions = sessions;
		this.worktrees = worktrees;
		this.git = git;
		this.assets = assets;
		this.sidecars = sidecars;
		this.journal = journal;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
		this.events = events;
		this.configFactory = configFactory;
		this.systemSessionService = systemSessionService;
		this.autoTitleService = autoTitleService;
		this.catalog = catalog;
		this.graphify = graphify;
		this.codegraph = codegraph;
	}

	// ------------------------------------------------------------------ creation

	/**
	 * Options for {@link #create(CreateOptions)} — replaces a three-deep overload ladder that had
	 * grown one trailing optional param per phase (7.3's continuedFromId, 7.4's parentSessionId;
	 * see docs/plan/phase-9-production-hardening.md S4). The 8-arg constructor covers the common
	 * case (plain create, no continuation/parent link); {@link #withContinuedFrom}/{@link
	 * #withParent} attach those independently so callers that need one don't have to spell out
	 * the other as {@code null}.
	 */
	public record CreateOptions(String name, String branch, String baseBranch, String repoPath, UUID templateId,
								JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch,
								UUID continuedFromId, UUID parentSessionId, String servicePath, String sessionType,
								String prUrl) {

		public CreateOptions(String name, String branch, String baseBranch, String repoPath, UUID templateId,
							  JsonNode overrides, Map<String, String> kickoffValues, boolean syncBaseBranch) {
			this(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues, syncBaseBranch, null, null,
					null, null, null);
		}

		/** With an explicit continuation provenance link (7.3's "continue from" picker). */
		public CreateOptions withContinuedFrom(UUID continuedFromId) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId, servicePath, sessionType, prUrl);
		}

		/** With an explicit parent link (7.4's spawn_child_session tool). */
		public CreateOptions withParent(UUID parentSessionId) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId, servicePath, sessionType, prUrl);
		}

		/**
		 * With a service subfolder (docs/plan/phase-11-monorepo.md Step 3) — a folder inside {@code
		 * repoPath}'s git repo, or {@code repoPath} itself; {@link SessionConfigFactory#prepare}
		 * resolves {@code repoPath} from it when blank and validates it's a known service otherwise.
		 */
		public CreateOptions withServicePath(String servicePath) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId, servicePath, sessionType, prUrl);
		}

		/**
		 * Explicit session type from the create request (docs/plan/phase-15-review-sessions.md
		 * decision 6/7) — wins over a template's own {@code sessionType} config key in {@link
		 * SessionConfigFactory#prepare}; null resolves from the merged config, defaulting to
		 * {@code development}.
		 */
		public CreateOptions withSessionType(String sessionType) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId, servicePath, sessionType, prUrl);
		}

		/**
		 * PR to attach at creation (proposal 1: PR-first review target picking) — {@code branch}/
		 * {@code baseBranch} are still derived by the caller (dialog) from the picked PR's head/base;
		 * this is only the URL to record once the review worktree exists.
		 */
		public CreateOptions withPrUrl(String prUrl) {
			return new CreateOptions(name, branch, baseBranch, repoPath, templateId, overrides, kickoffValues,
					syncBaseBranch, continuedFromId, parentSessionId, servicePath, sessionType, prUrl);
		}
	}

	public SessionEntity create(CreateOptions options) {
		UUID id = UUID.randomUUID();
		Path worktree = Path.of(props.worktreeRoot()).resolve(id.toString());
		SessionConfigFactory.Prepared prepared = configFactory.prepare(id, worktree, options);
		SessionEntity entity = prepared.entity();
		enforceSessionLimitAndInsert(entity);
		record(id, "state_changed", mapper.createObjectNode().put("state", "CREATING"));
		for (String warning : prepared.warnings()) {
			record(id, "warning", mapper.createObjectNode().put("message", warning));
		}

		try {
			transition(id, SessionState.PROVISIONING);
			if (options.syncBaseBranch()) {
				worktrees.syncBaseBranch(Path.of(entity.repoPath()), options.baseBranch());
			}
			if ("review".equals(entity.sessionType())) {
				// proposal 5: a detached checkout of the reviewed branch's tip — sidesteps "already
				// checked out elsewhere" (the likely reviewer scenario: a live dev session owns the
				// branch) and makes "the branch ref is never moved" structural, not just promised.
				worktrees.createReviewWorktree(Path.of(entity.repoPath()), worktree, options.branch());
				if (options.prUrl() != null && !options.prUrl().isBlank()) {
					String headSha = git.runOrThrow(worktree, "rev-parse", "HEAD").stdout();
					sessions.attachPr(id, options.prUrl(), headSha);
				}
			} else {
				worktrees.createWorktree(Path.of(entity.repoPath()), worktree, options.branch(), options.baseBranch());
			}
			// decision A (docs/plan/phase-11-monorepo.md Step 0): assets materialize at the service
			// cwd, which equals the worktree root for a polyrepo session (servicePath == repoPath) —
			// byte-identical to before this phase for every existing session.
			Path assetsRoot = Path.of(entity.cwdPath());
			excludeProvisionedAssets(worktree, assetsRoot);
			for (var warning : assets.provision(assetsRoot, entity.skillSources(), entity.agentSources())) {
				record(id, "warning", mapper.createObjectNode().put("message", warning.message()));
			}
			// phase-14 decision 2: codegraph indexes synchronously, right here — before the MCP
			// config (with its codegraph entry) is written, so the server never starts pointed at a
			// directory that isn't indexed yet. Unlike graphify's fire-and-forget build below, this
			// blocks; failure/timeout never throws (SessionEntity stays usable, chip goes --red).
			if (codegraph != null) {
				codegraph.index(entity);
			}
			writeMcpConfig(entity);
			// decision 2: the graph builds in the background from here on — the session is usable
			// immediately, its MCP server tolerates the graph appearing later (Step 0)
			if (graphify != null) {
				graphify.build(entity);
			}

			transition(id, SessionState.STARTING);
			SessionEntity persisted = sessions.get(id);
			spawn(persisted, false);
		} catch (RuntimeException e) {
			record(id, "error", mapper.createObjectNode().put("message", e.getMessage()).put("fatal", true));
			transition(id, SessionState.FAILED);
			throw e;
		}
		if (entity.kickoffPrompt() != null && !entity.kickoffPrompt().isBlank()) {
			sessions.enqueue(id, entity.kickoffPrompt());
			recordQueue(id);
		}
		return sessions.get(id);
	}

	public SessionEntity resume(UUID id) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			if (session.state() != SessionState.CRASHED) {
				throw new IllegalStateException("session is " + session.state() + ", only CRASHED sessions can be resumed");
			}
			// no providerSessionId means the sidecar crashed before its first turn (no conversation
			// to resume yet) — buildArgs omits --resume in that case and spawns fresh, which is correct
			enforceSessionLimit(session.kind());
			transition(id, SessionState.STARTING);
			spawn(session, true);
			if (graphify != null) {
				graphify.ensureBuilt(session);
			}
			if (codegraph != null) {
				codegraph.ensureIndexed(session);
			}
			return sessions.get(id);
		}
	}

	// ------------------------------------------------------------------ inbound commands

	public void sendUserMessage(UUID id, String text) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			SessionState state = session.state();
			if (state == SessionState.IDLE && sidecars.hasLiveHandle(id)) {
				requireBudget(session);
				dispatch(id, text);
			} else if (state == SessionState.PARKED) {
				requireBudget(session);
				sessions.enqueue(id, text);
				recordQueue(id);
				wake(session);
			} else if (state == SessionState.CREATING || state == SessionState.PROVISIONING
					|| state == SessionState.STARTING || state == SessionState.RUNNING
					|| state == SessionState.WAITING_INPUT) {
				sessions.enqueue(id, text);
				recordQueue(id);
			} else {
				throw new IllegalStateException("session is " + state + " and does not accept messages");
			}
		}
	}

	public void updateCostBudget(UUID id, BigDecimal budget) {
		sessions.get(id);
		sessions.updateCostBudget(id, budget);
		record(id, "budget_updated", mapper.createObjectNode()
				.put("costBudgetUsd", budget == null ? null : budget.toPlainString()));
	}

	public void rename(UUID id, String name) {
		sessions.get(id);
		sessions.updateName(id, name);
		record(id, "session_renamed", mapper.createObjectNode().put("name", name).put("auto", false));
	}

	public void updateReflectionEnabled(UUID id, boolean enabled) {
		sessions.get(id);
		sessions.updateReflectionEnabled(id, enabled);
		record(id, "reflection_setting_changed", mapper.createObjectNode().put("reflectionEnabled", enabled));
	}

	/** The dashboard's decision: always forwarded (as before), and it also settles the pending request. */
	public void respondPermission(UUID id, JsonNode command) {
		pendingPermissions.take(id, command.path("requestId").asText(""));
		deliverPermissionResponse(id, command);
	}

	/**
	 * A decision that must not double-send: delivered only if the request is still pending, in which
	 * case it is settled atomically. Returns false when it was already decided (by the dashboard, or
	 * by another Slack click) — the caller reports that instead of sending a second response.
	 */
	public boolean respondPermissionIfPending(UUID id, JsonNode command) {
		var taken = pendingPermissions.take(id, command.path("requestId").asText(""));
		if (taken.isEmpty()) {
			return false;
		}
		try {
			deliverPermissionResponse(id, command);
		} catch (RuntimeException e) {
			// not delivered (e.g. the sidecar just died): the request is still open, don't lose it
			pendingPermissions.add(id, taken.get().requestId(), taken.get().toolName(), taken.get().input());
			throw e;
		}
		return true;
	}

	/**
	 * Journals the decision as received (including who made it, for a Slack-originated one) but forwards
	 * the sidecar only the protocol fields — the audit fields are ours, not part of the adapter protocol.
	 */
	private void deliverPermissionResponse(UUID id, JsonNode command) {
		record(id, "permission_response", command);
		JsonNode forSidecar = command;
		if (command instanceof ObjectNode obj) {
			ObjectNode copy = obj.deepCopy();
			copy.remove("source");
			copy.remove("slackUser");
			forSidecar = copy;
		}
		sidecars.handle(id).send(forSidecar.toString());
		synchronized (lock(id)) {
			if (sessions.get(id).state() == SessionState.WAITING_INPUT) {
				transition(id, SessionState.RUNNING);
			}
		}
	}

	/** Journals who asked for something done from outside the dashboard (Slack): an audit trail, not a state change. */
	public void recordExternalInput(UUID id, ObjectNode payload) {
		record(id, "slack_input", payload);
	}

	public void interrupt(UUID id) {
		record(id, "interrupt", mapper.createObjectNode());
		sidecars.handle(id).send("{\"type\":\"interrupt\"}");
	}

	public void setPermissionMode(UUID id, String mode) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "set_permission_mode").put("mode", mode).toString());
	}

	public void setModel(UUID id, String model) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "set_model").put("model", model).toString());
	}

	/** In-place context compaction (docs/plan/phase-12-linear-cache-serena-context.md decision
	 * 9): only meaningful for an idle sidecar, so this refuses outright rather than queuing
	 * behind in-flight work the way {@link #sendUserMessage} does. A PARKED session is woken
	 * first and the compact command fires once it reports ready ({@link #becomeIdleAndDrainQueue}). */
	public void compact(UUID id) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			SessionState state = session.state();
			if (state == SessionState.IDLE && sidecars.hasLiveHandle(id)) {
				sidecars.handle(id).send(mapper.createObjectNode().put("type", "compact").toString());
			} else if (state == SessionState.PARKED) {
				pendingCompactAfterWake.add(id);
				wake(session);
			} else {
				throw new IllegalStateException("session is " + state + " and cannot be compacted right now");
			}
		}
	}

	public boolean deleteQueued(UUID id, long pos) {
		boolean removed = sessions.deleteQueued(id, pos);
		if (removed) {
			recordQueue(id);
		}
		return removed;
	}

	// ------------------------------------------------------------------ close

	public void close(UUID id, String dirtyMode, String commitMessage) {
		synchronized (lock(id)) {
			SessionEntity session = sessions.get(id);
			if (session.state() == SessionState.CLOSED) {
				return;
			}
			// A concurrent close holds this lock, so CLOSING seen here means an earlier close died
			// part-way (failed worktree removal, backend killed mid-close) — retry it instead of
			// leaving the session stuck: CLOSING rejects messages and nothing else ever resolves it.
			boolean retry = session.state() == SessionState.CLOSING;
			if ("system".equals(session.kind())) {
				if (!retry) {
					transition(id, SessionState.CLOSING);
				}
				sidecars.terminate(id);
				deleteRecursively(Path.of(session.worktreePath()));
				deleteGraph(id);
				transition(id, SessionState.CLOSED);
				releaseSessionState(id);
				return;
			}
			Path worktree = Path.of(session.worktreePath());
			// a retry has already been through the dirty handling (commit/stash/discard runs before
			// removal), and the half-removed worktree may no longer be a git repo to ask
			List<String> dirty = retry ? List.of() : worktrees.dirtyFiles(worktree);
			if (!dirty.isEmpty() && (dirtyMode == null || dirtyMode.equals("fail"))) {
				throw new DirtyWorktreeException(dirty);
			}
			if ("commit".equals(dirtyMode) && "review".equals(session.sessionType())) {
				// proposal 10: a review session never commits — the reviewed branch ref is structurally
				// immovable (detached checkout), stash/discard stay available for scratch notes
				throw new IllegalStateException("review sessions cannot commit — use stash or discard");
			}
			if (!retry) {
				transition(id, SessionState.CLOSING);
			}
			sidecars.terminate(id);
			if (!dirty.isEmpty()) {
				switch (dirtyMode) {
					case "commit" -> worktrees.commitAll(worktree,
							commitMessage != null && !commitMessage.isBlank() ? commitMessage : "WIP from Artus session " + session.name());
					case "stash" -> worktrees.stashAll(worktree, "Artus close: " + session.name());
					case "discard" -> { /* worktree remove --force discards */ }
					default -> throw new IllegalArgumentException("unknown dirty mode: " + dirtyMode);
				}
			}
			worktrees.removeWorktree(Path.of(session.repoPath()), worktree);
			deleteGraph(id);
			transition(id, SessionState.CLOSED);
			releaseSessionState(id);
			if (session.reflectionEnabled()) {
				events.publishEvent(new ReflectionRequested(id));
			}
			if (settings.current().serviceDiscoveryEnabled()) {
				events.publishEvent(new ServiceDiscoveryRequested(id, session.servicePath(), session.repoPath()));
			}
		}
	}

	/** Best-effort: a failed graph-dir removal is logged inside, never blocks a close. */
	private void deleteGraph(UUID id) {
		if (graphify != null) {
			graphify.delete(id);
		}
	}

	private static void deleteRecursively(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		} catch (IOException e) {
			log.warn("could not clean up scratch dir {}: {}", dir, e.getMessage());
		}
	}

	// ------------------------------------------------------------------ sidecar event handling

	// package-private (not private): unit-tested directly — pending-permission tracking, phase 18
	void onSidecarEvent(UUID id, JsonNode event) {
		String type = event.path("type").asText("unknown");
		// tracked before the event is journaled/published, so nothing that reacts to it (a Slack click)
		// can arrive ahead of the bookkeeping
		if ("permission_request".equals(type)) {
			pendingPermissions.add(id, event.path("requestId").asText(""), event.path("toolName").asText(""), event.get("input"));
		} else if ("turn_complete".equals(type)) {
			pendingPermissions.clear(id); // whatever was outstanding died with the turn (an interrupt denies them)
		}
		if ("turn_complete".equals(type) && event instanceof ObjectNode turnComplete) {
			applyEstimatedCost(id, turnComplete);
		} else if ("turn_usage".equals(type) && event instanceof ObjectNode turnUsage) {
			applyLiveEstimate(id, turnUsage);
		}
		record(id, type, event);
		switch (type) {
			case "ready" -> {
				// the adapter accepts input from here on; system_init only arrives with the first turn
				sessions.updateCapabilities(id, event.get("capabilities"));
				synchronized (lock(id)) {
					if (sessions.get(id).state() == SessionState.STARTING) {
						becomeIdleAndDrainQueue(id);
					}
				}
			}
			case "system_init" -> {
				sessions.updateProviderSessionId(id, event.path("providerSessionId").asText());
				sessions.updateModel(id, event.path("model").asText());
				// system_init opens every turn. Arriving while IDLE, nobody sent a message: the
				// provider started the turn itself, e.g. to read a finished background task's
				// result (phase 25 step 4) — so the session is running again.
				synchronized (lock(id)) {
					if (sessions.get(id).state() == SessionState.IDLE) {
						transition(id, SessionState.RUNNING);
					}
				}
			}
			case "permission_request" -> transition(id, SessionState.WAITING_INPUT);
			case "permission_mode_changed" -> sessions.updatePermissionMode(id, event.path("mode").asText());
			case "model_changed" -> sessions.updateModel(id, event.path("model").asText());
			case "context_usage" -> sessions.updateContextUsage(id, event.path("tokens").asInt(), event.path("window").asInt());
			case "assistant_message" -> systemSessionService.onAssistantMessage(id, event.get("content"));
			case "background_tasks" -> {
				int count = event.path("tasks").size();
				if (count > 0) {
					backgroundTaskCounts.put(id, count);
				} else {
					backgroundTaskCounts.remove(id);
				}
			}
			case "turn_complete" -> {
				// drain first: best-effort housekeeping below must never be able to block it
				synchronized (lock(id)) {
					becomeIdleAndDrainQueue(id);
				}
				try {
					journal.deleteDeltasBefore(id, journal.lastSeq(id));
					autoTitleService.maybeAutoTitle(id);
					if (graphify != null) {
						graphify.refreshAfterTurn(sessions.get(id)); // decision 8: coalesced, non-blocking
					}
				} catch (RuntimeException e) {
					log.warn("post-turn housekeeping failed for {}: {}", id, e.getMessage());
				}
				systemSessionService.completeTurn(id);
			}
			case "error" -> {
				if (event.path("fatal").asBoolean(false)) {
					systemSessionService.failTurn(id, new IllegalStateException(event.path("message").asText("system session error")));
				}
			}
			default -> { /* journaled above; no state effect */ }
		}
	}

	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T2
	void onSidecarExit(UUID id, int code, List<String> stderrTail, boolean shutdownRequested) {
		pendingPermissions.clear(id);
		backgroundTaskCounts.remove(id); // they died with the process
		synchronized (lock(id)) {
			SessionEntity session = sessions.find(id).orElse(null);
			if (session == null) {
				return;
			}
			SessionState state = session.state();
			if (state == SessionState.CLOSING || state == SessionState.CLOSED || state == SessionState.FAILED) {
				return;
			}
			if (shutdownRequested) {
				return; // expected exit; the close flow owns the state
			}
			ObjectNode payload = mapper.createObjectNode()
					.put("message", "sidecar exited unexpectedly with code " + code)
					.put("fatal", true);
			payload.set("stderrTail", mapper.valueToTree(stderrTail));
			record(id, "error", payload);
			transition(id, SessionState.CRASHED);
			systemSessionService.failTurn(id, new IllegalStateException("system session crashed (exit " + code + ")"));
		}
	}

	// ------------------------------------------------------------------ internals

	/** Package-private: also called by {@link SystemSessionService#createSystemSession}. */
	void spawn(SessionEntity session, boolean resume) {
		String extraSystemPrompt = configFactory.extraSystemPrompt(session);
		sidecars.spawn(session, mcpConfigPath(session.id()), resume, extraSystemPrompt,
				event -> onSidecarEvent(session.id(), event),
				(handle, code) -> onSidecarExit(session.id(), code, handle.stderrTail(), handle.isShutdownRequested()));
	}

	/**
	 * Sends to the sidecar before journaling "sent" — if the handle is dead or the write
	 * fails, nothing is recorded and the caller can safely leave the message queued for a
	 * retry, rather than showing a transcript entry that was never actually delivered.
	 */
	private void dispatch(UUID id, String text) {
		sidecars.handle(id).send(mapper.createObjectNode()
				.put("type", "user_message").put("text", text).toString());
		record(id, "user_message", mapper.createObjectNode().put("text", text));
		transition(id, SessionState.RUNNING);
	}

	// package-private (not private): unit-tested directly — see docs/plan/phase-9-production-hardening.md T2
	void becomeIdleAndDrainQueue(UUID id) {
		if (isTerminal(sessions.get(id).state())) {
			// A concurrent close()/crash may have already finalized this session while its
			// current turn was still in flight — close() blocks on sidecars.terminate() for
			// up to ~7s while the sidecar finishes that turn before honoring the shutdown, so
			// a "turn_complete" for an already-CLOSED (worktree deleted) or CRASHED session is
			// a real sequence, not a bug at the call site. Don't resurrect it back to IDLE out
			// from under the close/crash — the caller still gets its result via completeTurn().
			return;
		}
		transition(id, SessionState.IDLE);
		if (pendingCompactAfterWake.remove(id)) {
			sidecars.handle(id).send(mapper.createObjectNode().put("type", "compact").toString());
			return; // its own turn_complete re-enters here to drain anything queued behind it
		}
		SessionEntity session = sessions.get(id);
		if (budgetExhausted(session)) {
			if (!sessions.queued(id).isEmpty()) {
				record(id, "budget_exhausted", budgetPayload(session));
			}
			return; // queued messages stay queued until the budget is raised
		}
		// peek (not pop): only removed from the queue once dispatch actually succeeds, so a
		// dead sidecar handle or transient send failure can't silently drop the message
		sessions.peekQueue(id).ifPresent(next -> {
			try {
				dispatch(id, next.text());
			} catch (RuntimeException e) {
				record(id, "error", mapper.createObjectNode()
						.put("message", "failed to send queued message, will retry: " + e.getMessage())
						.put("fatal", false));
				return;
			}
			sessions.deleteQueued(id, next.pos());
			recordQueue(id);
		});
	}

	private static boolean isTerminal(SessionState state) {
		return state == SessionState.CLOSING || state == SessionState.CLOSED
				|| state == SessionState.CRASHED || state == SessionState.FAILED;
	}

	/**
	 * A provider whose adapter declares {@code reportsCostUsd: false} (Codex today — see
	 * docs/plan/phase-5.13-codex-provider.md Decision 2, generalized in
	 * docs/plan/phase-10-review-followups.md R1) always reports {@code costUsd: 0} in its
	 * {@code turn_complete}; this rewrites the journaled event's costUsd in place from the raw
	 * {@code usage} token counts against that provider's Settings-editable price table
	 * ({@link SettingsService#pricingFor}), so the cost budget guard and usage dashboard don't
	 * need to know the number is estimated. No-op for a provider that reports its own cost.
	 */
	private void applyEstimatedCost(UUID id, ObjectNode turnComplete) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null || catalog.get(session.provider()).reportsCostUsd()) {
			return;
		}
		try {
			JsonNode pricing = mapper.readTree(settings.pricingFor(session.provider()));
			BigDecimal estimated = CodexCostEstimator.estimate(pricing, turnComplete.path("model").asText(""),
					turnComplete.path("usage"));
			turnComplete.put("costUsd", estimated);
		} catch (RuntimeException e) {
			log.warn("cost estimate failed for session {}: {}", id, e.getMessage());
		}
	}

	/**
	 * Prices a {@code turn_usage} event's per-model token counts into {@code estimatedCostUsd}
	 * (phase 25 step 3) — the live, mid-turn "~$" the widget shows until {@code turn_complete}
	 * brings the real figure. Best effort: a broken price table only loses the estimate.
	 */
	private void applyLiveEstimate(UUID id, ObjectNode turnUsage) {
		SessionEntity session = sessions.find(id).orElse(null);
		if (session == null) {
			return;
		}
		try {
			JsonNode pricing = mapper.readTree(settings.pricingFor(session.provider()));
			turnUsage.put("estimatedCostUsd", TokenCostEstimator.estimate(pricing, turnUsage.path("byModel")));
		} catch (RuntimeException e) {
			log.warn("live cost estimate failed for session {}: {}", id, e.getMessage());
		}
	}

	/**
	 * Whether the session's provider still runs background work (a backgrounded subagent or shell
	 * command) — idle parking must not kill it; the provider starts a turn of its own when it ends.
	 */
	public boolean hasBackgroundTasks(UUID id) {
		return backgroundTaskCounts.containsKey(id);
	}

	private boolean budgetExhausted(SessionEntity session) {
		return session.costBudgetUsd() != null
				&& journal.costToDate(session.id()).compareTo(session.costBudgetUsd()) >= 0;
	}

	private void requireBudget(SessionEntity session) {
		if (budgetExhausted(session)) {
			record(session.id(), "budget_exhausted", budgetPayload(session));
			throw new IllegalStateException("cost budget exhausted ($" + session.costBudgetUsd()
					+ "); raise the budget to continue");
		}
	}

	private ObjectNode budgetPayload(SessionEntity session) {
		return mapper.createObjectNode()
				.put("costBudgetUsd", session.costBudgetUsd().toPlainString())
				.put("costToDate", journal.costToDate(session.id()).toPlainString());
	}

	/** Package-private: also called by {@link SystemSessionService#createSystemSession} and {@link SessionHousekeeping}. */
	void transition(UUID id, SessionState state) {
		if (state == SessionState.CLOSED) {
			sessions.markClosed(id);
		} else {
			sessions.updateState(id, state);
		}
		record(id, "state_changed", mapper.createObjectNode().put("state", state.name()));
		if (state == SessionState.WAITING_INPUT || state == SessionState.CRASHED) {
			noteChildAttention(id, state);
			unhide(id); // it needs the user now — hidden would mean nobody sees it
		} else if (state != SessionState.RUNNING) {
			childAttentionNoted.remove(id); // RUNNING keeps it: several prompts in one turn are one stall
		}
	}

	/**
	 * Phase 25: keep a session off the dashboard (or bring it back). Nothing else about it changes —
	 * it still runs, parks, resumes and mirrors to Slack. Journaled so open tabs follow.
	 */
	public void setHidden(UUID id, boolean hidden) {
		SessionEntity session = sessions.get(id);
		if (session.hidden() == hidden) {
			return;
		}
		sessions.updateHidden(id, hidden);
		record(id, "visibility_changed", mapper.createObjectNode().put("hidden", hidden));
	}

	/** Brings a hidden session back onto the dashboard; no-op when it is not hidden. */
	public void unhide(UUID id) {
		sessions.find(id).filter(SessionEntity::hidden).ifPresent(s -> setHidden(id, false));
	}

	/** Called by idle parking once a session is PARKED: the "Hide sessions when they park" setting (phase 25). */
	void onParked(UUID id) {
		if (settings.current().hideOnPark()) {
			sessions.find(id).filter(s -> "user".equals(s.kind())).ifPresent(s -> setHidden(id, true));
		}
	}

	/**
	 * A spawned child that stops for a human (an approval prompt) or dies leaves its parent waiting
	 * on a report that may never come — say so on the parent's transcript (phase 25). A note only:
	 * nothing is sent to the parent's model, the human decides what to do.
	 */
	private void noteChildAttention(UUID childId, SessionState state) {
		SessionEntity child = sessions.find(childId).orElse(null);
		if (child == null || child.parentSessionId() == null
				// once reported, the parent no longer waits on it — the human is now driving the child
				|| journal.hasEventType(childId, "child_reported")
				// one note per stall: a child asking for several approvals in one turn is one stall
				|| childAttentionNoted.put(childId, state) == state) {
			return;
		}
		record(child.parentSessionId(), "child_attention", mapper.createObjectNode()
				.put("childId", childId.toString()).put("childName", child.name()).put("state", state.name()));
	}

	/** Journal + fan out. The journal assigns seq; subscribers see exactly what replay will. */
	private void record(UUID id, String type, JsonNode payload) {
		journalPublisher.record(id, type, payload);
	}

	private void recordQueue(UUID id) {
		ObjectNode payload = mapper.createObjectNode();
		payload.set("queued", mapper.valueToTree(sessions.queued(id)));
		record(id, "queue_updated", payload);
	}

	/**
	 * Package-private: also called (standalone, no insert to pair it with) by {@link #resume}.
	 * The singleton system session is exempt — it's a single, always-needed, backend-managed
	 * resource (ticket import, commit-message drafting, service discovery, ...), not a slot
	 * competing with user sessions. Without this, reviving it after a crash/restart throws "max
	 * concurrent sessions reached" outright whenever the user already has the configured number
	 * of regular sessions open — i.e. during completely normal use.
	 */
	void enforceSessionLimit(String kind) {
		if ("system".equals(kind)) {
			return;
		}
		long live = sessions.countByStates(List.copyOf(SessionState.LIVE));
		if (live >= props.maxSessions()) {
			throw new IllegalStateException("max concurrent sessions reached (" + props.maxSessions() + ")");
		}
	}

	/**
	 * Package-private: also called by {@link SystemSessionService#createSystemSession}. Checking
	 * the live-session count and inserting the new row must be atomic — otherwise two concurrent
	 * creates (7.4's spawn_child_session fans a parent out into several) can both read a count
	 * under the limit and both insert, overshooting it (docs/plan/phase-9-production-hardening.md
	 * O1). The lock only spans the count query + insert, not the rest of provisioning.
	 */
	void enforceSessionLimitAndInsert(SessionEntity entity) {
		synchronized (sessionLimitLock) {
			enforceSessionLimit(entity.kind());
			sessions.insert(entity);
		}
	}

	/** Snapshots a session's effective config into a new session on a new branch. */
	public SessionEntity duplicate(UUID sourceId, String branch, String name, boolean syncBaseBranch) {
		SessionEntity source = sessions.get(sourceId);
		if ("system".equals(source.kind())) {
			throw new IllegalArgumentException("cannot duplicate a system session");
		}
		ObjectNode overrides = configFactory.configOverridesFrom(source);
		String sessionName = name != null && !name.isBlank() ? name : branch;
		// sessionType is identity, not tunable config (proposal 7) — carried explicitly here rather
		// than through configOverridesFrom, same as name/branch/repo; a duplicate of a review session
		// reviews the same branch/PR again
		return create(new CreateOptions(sessionName, branch, source.baseBranch(), source.repoPath(), null, overrides,
				null, syncBaseBranch).withSessionType(source.sessionType()).withPrUrl(source.prUrl()));
	}

	/**
	 * The most recently created non-system session's effective config, as an overrides object
	 * shaped for {@link #create}. Backs the "quick session" create flow (ticket + service only,
	 * everything else copied from whatever was last set up) — an empty object if there is no
	 * prior session yet (a session created from it then just gets ordinary provider defaults).
	 */
	public JsonNode lastSessionConfig() {
		return sessions.findAll().stream()
				.filter(s -> !"system".equals(s.kind()))
				.findFirst()
				.<JsonNode>map(configFactory::configOverridesFrom)
				.orElseGet(mapper::createObjectNode);
	}

	/**
	 * Skills/agents symlinks and MCP artifacts must not pollute git status. {@code assetsRoot} is
	 * where {@link AssetProvisioningService#provision} actually materializes them — the worktree
	 * root for a polyrepo session, or its service subfolder for a monorepo one — so the written
	 * lines are relative to it, not always the bare {@code .claude/skills/} of before this phase
	 * (which they still are whenever {@code assetsRoot == worktree}, i.e. every polyrepo session).
	 * The pid file always lives at the worktree root regardless (see {@link
	 * de.pamir.agentic.ui.process.SidecarManager#pidFile}), so that line is never prefixed.
	 *
	 * <p>Also unconditionally lists {@code .serena/} and {@code .codegraph/} (at the same {@code
	 * assetsRoot}-relative prefix — both tools' project/index root is that same cwd) even for a
	 * session that never enables either, matching this method's existing precedent of always
	 * listing tool-generated paths regardless of whether this particular session uses them:
	 * harmless when the path never exists. Confirmed live (docs/plan/phase-12-linear-cache-serena-
	 * context.md Track B manual test) that Serena's own {@code .serena/.gitignore} (written inside
	 * that directory) does NOT keep the directory itself out of {@code git status} — only entries
	 * *inside* it — so this repo-level exclude line is required, not optional polish; codegraph's
	 * own {@code .gitignore} inside {@code .codegraph/} has the same limitation (docs/plan/
	 * phase-14-codegraph.md posture 4). Graphify (the third {@code codeIntel} tool) needs no line:
	 * its graph lives outside the worktree entirely (docs/plan/phase-13-graphify.md decision 6).
	 */
	private void excludeProvisionedAssets(Path worktree, Path assetsRoot) {
		try {
			var result = git.run(worktree, "rev-parse", "--git-path", "info/exclude");
			if (result.ok()) {
				Path exclude = Path.of(result.stdout());
				if (!exclude.isAbsolute()) {
					exclude = worktree.resolve(result.stdout());
				}
				Files.createDirectories(exclude.getParent());
				String rel = worktree.relativize(assetsRoot).toString();
				String prefix = rel.isEmpty() ? "" : rel + "/";
				Files.writeString(exclude,
						"\n" + prefix + ".claude/skills/\n" + prefix + ".claude/agents/\n" + prefix + ".serena/\n"
								+ prefix + ".codegraph/\n.artus.pid\n",
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		} catch (IOException e) {
			log.warn("could not write per-worktree exclude: {}", e.getMessage());
		}
	}

	/** Package-private: also called by {@link SystemSessionService#createSystemSession}. */
	void writeMcpConfig(SessionEntity session) {
		if (session.mcpConfig() == null || session.mcpConfig().isNull()) {
			return;
		}
		try {
			Path file = mcpConfigPath(session.id());
			Files.createDirectories(file.getParent());
			// re-stamp the live backend token: the persisted one is stale after any backend restart
			Files.writeString(file, configFactory.expandSecrets(configFactory.refreshManagedMcp(session.mcpConfig())).toString());
		} catch (IOException e) {
			throw new IllegalStateException("failed to write mcp config: " + e.getMessage(), e);
		}
	}

	private Path mcpConfigPath(UUID id) {
		return Path.of(props.worktreeRoot()).resolve(".mcp").resolve(id + ".json");
	}

	/** Package-private: also used by {@link SessionHousekeeping}'s parking tick. */
	Object lock(UUID id) {
		return locks.computeIfAbsent(id, k -> new Object());
	}

	/**
	 * Drops the per-session bookkeeping ({@code locks} here, plus the journal's own in-memory
	 * state) once a session is CLOSED and will never be touched again — otherwise both maps grow
	 * unbounded over the process lifetime (docs/plan/phase-10-review-followups.md R4). Called
	 * from inside the {@code synchronized (lock(id))} block that just performed the final CLOSED
	 * transition; removing the map entry here is safe even though the current thread still holds
	 * that object's monitor until the block exits.
	 */
	private void releaseSessionState(UUID id) {
		locks.remove(id);
		journal.release(id);
	}

	/**
	 * Package-private: called by {@link SystemSessionService} after each completed system turn
	 * (phase 19 — fresh conversation per task). Terminates outside {@link #lock} (the dying
	 * sidecar's final events need it), then clears the provider session id and parks under it —
	 * nothing from the old process can land after the clear. Skips a session that is closing or
	 * already finished.
	 */
	void recycleSystemSession(SessionEntity session) {
		UUID id = session.id();
		sidecars.terminate(id);
		synchronized (lock(id)) {
			SessionState state = sessions.get(id).state();
			if (state == SessionState.CLOSING || state == SessionState.CLOSED || state == SessionState.FAILED) {
				return;
			}
			sessions.updateProviderSessionId(id, null);
			transition(id, SessionState.PARKED);
		}
	}

	/** Package-private: test-only visibility into R4's lock-release bookkeeping. */
	boolean hasLock(UUID id) {
		return locks.containsKey(id);
	}

	// ------------------------------------------------------------------ parking

	/** Package-private: also called by {@link SystemSessionService#getOrCreateSystemSession}. */
	void wake(SessionEntity session) {
		log.info("waking parked session {}", session.id());
		transition(session.id(), SessionState.STARTING);
		try {
			spawn(session, true);
		} catch (RuntimeException e) {
			record(session.id(), "error", mapper.createObjectNode()
					.put("message", "wake failed: " + e.getMessage()).put("fatal", true));
			transition(session.id(), SessionState.CRASHED);
			throw e;
		}
		if (graphify != null) {
			graphify.ensureBuilt(session); // decision 11: READY iff the graph survived, else rebuild
		}
		if (codegraph != null) {
			codegraph.ensureIndexed(session);
		}
	}
}
