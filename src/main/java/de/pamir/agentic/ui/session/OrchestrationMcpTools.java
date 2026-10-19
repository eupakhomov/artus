package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.git.GitWorktreeService;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Multi-service fan-out (docs/plan/phase-7-ux-and-orchestration.md 7.4): a session that's not
 * itself a child can decompose a cross-service task into one child session per affected
 * service and synthesize their reports. Same in-process MCP server as memory (decision 12a,
 * phase-5.3-memory-reflection.md) — spawn is human-gated by the normal tool-permission prompt
 * because {@code allowedTools} only pre-approves the three read-only memory tools by name, not
 * this whole server (see {@code SessionService.create}). Depth 1 only: enforced here, not by
 * hiding tools per session (the server's tool list is static/application-wide).
 */
@Component
public class OrchestrationMcpTools {

	/** Fixed internal cap — how many children one parent may ever spawn (lifetime, not concurrent). */
	private static final int MAX_CHILDREN = 5;

	public record ChildInfo(String id, String name, String servicePath, String state, BigDecimal costToDate,
							 boolean reported) {
	}

	private final SettingsService settings;
	private final SessionRepository sessions;
	private final SessionService sessionService;
	private final GitWorktreeService worktrees;
	private final EventJournal journal;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;

	public OrchestrationMcpTools(SettingsService settings, SessionRepository sessions, SessionService sessionService,
								  GitWorktreeService worktrees, EventJournal journal, JournalPublisher journalPublisher,
								  ObjectMapper mapper) {
		this.settings = settings;
		this.sessions = sessions;
		this.sessionService = sessionService;
		this.worktrees = worktrees;
		this.journal = journal;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
	}

	@McpTool(name = "list_services",
			annotations = @McpTool.McpAnnotations(title = "List services", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "List the sibling services (git repos or monorepo packages) under this session's "
					+ "ecosystem folder — candidates for spawn_child_session. Errors if no ecosystem folder is "
					+ "configured for this session.")
	public List<GitWorktreeService.ServiceInfo> listServices(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId) {
		SessionEntity session = sessionOf(sessionId);
		if (session.ecosystemPath() == null || session.ecosystemPath().isBlank()) {
			throw new IllegalStateException("no ecosystem folder is configured for this session");
		}
		return worktrees.findServices(Path.of(session.ecosystemPath()), monorepoGlobs(),
				settings.current().monorepoDetectionEnabled());
	}

	@McpTool(name = "spawn_child_session",
			annotations = @McpTool.McpAnnotations(title = "Spawn child session", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
			description = "Spawn a child session on another service (from list_services) to work on part of a "
					+ "cross-service task, in its own worktree/branch — a monorepo package gets its own worktree "
					+ "of the same repo, on a new branch. The child reports back via report_result, which wakes "
					+ "this session with the result. Refused if this session is itself a child (depth 1 only), "
					+ "at the session limit, or above the per-parent child cap.")
	public Map<String, String> spawnChildSession(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "Absolute path to the service, from list_services")
			String servicePath,
			@McpToolParam(required = true, description = "New branch name to create for the child on that repo")
			String branch,
			@McpToolParam(required = true, description = "The task for the child session to carry out")
			String prompt,
			@McpToolParam(required = false, description = "Model override (e.g. sonnet/opus/haiku) — defaults to this session's own model")
			String model) {
		SessionEntity parent = sessionOf(sessionId);
		if (parent.parentSessionId() != null) {
			throw new IllegalStateException("child sessions cannot spawn their own children (depth 1 only)");
		}
		if (sessions.countChildren(parent.id()) >= MAX_CHILDREN) {
			throw new IllegalStateException(
					"this session already has the maximum of " + MAX_CHILDREN + " children");
		}
		Path service = Path.of(servicePath).toAbsolutePath().normalize();
		Path repoRoot = worktrees.repoRootOf(service)
				.orElseThrow(() -> new IllegalArgumentException("not a known service under this session's ecosystem: " + servicePath));
		Path ecosystemRoot = parent.ecosystemPath() == null || parent.ecosystemPath().isBlank()
				? null : Path.of(parent.ecosystemPath());
		if (!worktrees.isKnownService(ecosystemRoot, monorepoGlobs(), settings.current().monorepoDetectionEnabled(), repoRoot, service)) {
			throw new IllegalArgumentException("not a known service under this session's ecosystem: " + servicePath);
		}
		String baseBranch = worktrees.defaultBranch(repoRoot);
		String childPrompt = prompt + "\n\nYou are a child session of \"" + parent.name() + "\" working on this "
				+ "service as part of a larger cross-service task. When your task is done, call report_result "
				+ "(with your own sessionId, given in your system prompt) with a concise summary of what you did.";
		ObjectNode overrides = mapper.createObjectNode();
		overrides.put("provider", parent.provider());
		if (model != null && !model.isBlank()) {
			overrides.put("model", model);
		}
		if (parent.ecosystemPath() != null) {
			overrides.put("ecosystemPath", parent.ecosystemPath());
		}
		overrides.put("kickoffPrompt", childPrompt);
		String childName = service.getFileName() + ": " + branch;
		SessionEntity child = sessionService.create(new SessionService.CreateOptions(childName, branch, baseBranch,
				repoRoot.toString(), null, overrides, null, false).withParent(parent.id()).withServicePath(servicePath));
		// phase 25: the parent's transcript says a child exists and where, not just the model's prose
		record(parent.id(), "child_spawned", mapper.createObjectNode()
				.put("childId", child.id().toString())
				.put("childName", child.name())
				.put("service", service.getFileName().toString()));
		return Map.of("childId", child.id().toString(), "name", child.name());
	}

	@McpTool(name = "check_children",
			annotations = @McpTool.McpAnnotations(title = "Check child sessions", readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = "List every child ever spawned from this session, with state/cost/whether it has "
					+ "already called report_result — for recovery if a child stalls instead of reporting.")
	public List<ChildInfo> checkChildren(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId) {
		SessionEntity parent = sessionOf(sessionId);
		return sessions.findByParent(parent.id()).stream()
				.map(c -> new ChildInfo(c.id().toString(), c.name(), c.servicePath(), c.state().name(),
						journal.costToDate(c.id()), journal.hasEventType(c.id(), "child_reported")))
				.toList();
	}

	@McpTool(name = "report_result",
			annotations = @McpTool.McpAnnotations(title = "Report result to parent", readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false),
			description = "Child sessions only: send a summary of completed work back to the parent session "
					+ "that spawned you. Wakes the parent (even if parked) with your report; you stay open "
					+ "afterward for human review.")
	public String reportResult(
			@McpToolParam(required = true, description = "Your session id, given in your system prompt")
			String sessionId,
			@McpToolParam(required = true, description = "Concise summary of what you did and the outcome")
			String summary) {
		SessionEntity child = sessionOf(sessionId);
		if (child.parentSessionId() == null) {
			throw new IllegalStateException("this session has no parent — report_result is for child sessions only");
		}
		SessionEntity parent = sessions.get(child.parentSessionId());
		String service = Path.of(child.servicePath()).getFileName().toString();
		ObjectNode payload = mapper.createObjectNode()
				.put("childId", child.id().toString())
				.put("childName", child.name())
				.put("service", service)
				.put("summary", summary);
		record(child.id(), "child_reported", payload);
		record(parent.id(), "child_reported", payload);
		// phase 25: the report starts a turn on the parent — one that hid itself when it parked
		// would otherwise digest the child's result where nobody sees it
		sessionService.unhide(parent.id());
		sessionService.sendUserMessage(parent.id(), "[child report — " + child.name() + " / " + service + "]: " + summary);
		return "reported to parent \"" + parent.name() + "\"";
	}

	/** Journal + fan out, both sessions see this live. */
	private void record(UUID id, String type, ObjectNode payload) {
		journalPublisher.record(id, type, payload);
	}

	private List<String> monorepoGlobs() {
		return Arrays.stream(settings.current().monorepoServiceGlobs().split(","))
				.map(String::strip).filter(g -> !g.isEmpty()).toList();
	}

	private SessionEntity sessionOf(String sessionId) {
		UUID id;
		try {
			id = UUID.fromString(sessionId);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("sessionId is not a valid session id: " + sessionId);
		}
		try {
			return sessions.get(id);
		} catch (NoSuchElementException e) {
			throw new NoSuchElementException("no session found for sessionId: " + sessionId);
		}
	}
}
