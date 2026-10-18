package de.pamir.agentic.ui.web;

import tools.jackson.databind.JsonNode;
import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import de.pamir.agentic.ui.memory.BlockMarkService;
import de.pamir.agentic.ui.memory.ReflectionService;
import de.pamir.agentic.ui.session.HandoffService;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	public record CreateSessionRequest(String name, String branch, String baseBranch, String repoPath,
									   String servicePath, UUID templateId, JsonNode overrides,
									   Map<String, String> kickoffValues, Boolean syncBaseBranch, UUID continuedFromId,
									   /** 'development' (default, when null) or 'review' — docs/plan/phase-15-review-sessions.md */
									   String sessionType,
									   /** PR to attach when a review session was created PR-first (proposal 1) */
									   String prUrl,
									   /** Cosmetic only — not persisted; carried for API completeness (proposal 12) */
									   String prTitle) {
	}

	public record SessionSummary(UUID id, String name, String provider, String repoPath, String servicePath,
								 String branch, String model, String permissionMode, String state, String kind,
								 String sessionType, BigDecimal costToDate, Instant updatedAt, long lastSeq) {
	}

	private final SessionService service;
	private final SessionRepository sessions;
	private final EventJournal journal;
	private final ReflectionService reflection;
	private final HandoffService handoff;
	private final BlockMarkService blockMarks;

	public SessionController(SessionService service, SessionRepository sessions, EventJournal journal,
							  ReflectionService reflection, HandoffService handoff, BlockMarkService blockMarks) {
		this.service = service;
		this.sessions = sessions;
		this.journal = journal;
		this.reflection = reflection;
		this.handoff = handoff;
		this.blockMarks = blockMarks;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public SessionEntity create(@RequestBody CreateSessionRequest request) {
		if (request.name() == null || request.branch() == null || request.baseBranch() == null) {
			throw new IllegalArgumentException("name, branch and baseBranch are required");
		}
		SessionService.CreateOptions options = new SessionService.CreateOptions(request.name(), request.branch(),
				request.baseBranch(), request.repoPath(), request.templateId(), request.overrides(),
				request.kickoffValues(), Boolean.TRUE.equals(request.syncBaseBranch()))
				.withContinuedFrom(request.continuedFromId())
				.withServicePath(request.servicePath())
				.withSessionType(request.sessionType())
				.withPrUrl(request.prUrl());
		return service.create(options);
	}

	/** Backs the "quick session" dialog: the config a new session would inherit if created now. */
	@GetMapping("/last-config")
	public JsonNode lastConfig() {
		return service.lastSessionConfig();
	}

	private static final EventJournal.SessionStats NO_EVENTS = new EventJournal.SessionStats(0L, BigDecimal.ZERO);

	@GetMapping
	public List<SessionSummary> list() {
		Map<UUID, EventJournal.SessionStats> stats = journal.statsForAll();
		return sessions.findAll().stream()
				.map(s -> {
					EventJournal.SessionStats st = stats.getOrDefault(s.id(), NO_EVENTS);
					return new SessionSummary(s.id(), s.name(), s.provider(), s.repoPath(), s.servicePath(), s.branch(),
							s.model(), s.permissionMode(), s.state().name(), s.kind(), s.sessionType(), st.costToDate(),
							s.updatedAt(), st.lastSeq());
				})
				.toList();
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		Map<String, Object> result = new HashMap<>();
		result.put("session", session);
		result.put("queued", sessions.queued(id));
		result.put("lastSeq", journal.lastSeq(id));
		result.put("costToDate", journal.costToDate(id));
		if (session.continuedFromId() != null) {
			sessions.find(session.continuedFromId()).ifPresent(source -> result.put("continuedFromName", source.name()));
		}
		if (session.parentSessionId() != null) {
			sessions.find(session.parentSessionId()).ifPresent(parent -> result.put("parentName", parent.name()));
		}
		return result;
	}

	@GetMapping("/{id}/events")
	public List<EventJournal.JournalEvent> events(@PathVariable UUID id,
												  @RequestParam(defaultValue = "0") long afterSeq) {
		sessions.get(id);
		return journal.readAfter(id, afterSeq);
	}

	@GetMapping(value = "/{id}/export.md", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> export(@PathVariable UUID id) {
		SessionEntity session = sessions.get(id);
		String markdown = TranscriptDigest.renderMarkdown(session.name(), journal.readAfter(id, 0));
		String filename = session.name().replaceAll("[^a-zA-Z0-9._-]+", "-") + ".md";
		return ResponseEntity.ok()
				.contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(filename).build().toString())
				.body(markdown);
	}

	@PostMapping(value = "/{id}/handoff-summary", produces = "text/markdown;charset=UTF-8")
	public ResponseEntity<String> handoffSummary(@PathVariable UUID id) {
		return ResponseEntity.ok().contentType(MediaType.valueOf("text/markdown;charset=UTF-8"))
				.body(handoff.summarize(id));
	}

	@PostMapping("/{id}/resume")
	public SessionEntity resume(@PathVariable UUID id) {
		return service.resume(id);
	}

	public record DuplicateSessionRequest(String branch, String name, Boolean syncBaseBranch) {
	}

	@PostMapping("/{id}/duplicate")
	@ResponseStatus(HttpStatus.CREATED)
	public SessionEntity duplicate(@PathVariable UUID id, @RequestBody DuplicateSessionRequest request) {
		if (request.branch() == null || request.branch().isBlank()) {
			throw new IllegalArgumentException("branch is required");
		}
		return service.duplicate(id, request.branch(), request.name(), Boolean.TRUE.equals(request.syncBaseBranch()));
	}

	public record PatchSessionRequest(BigDecimal costBudgetUsd, String name, Boolean reflectionEnabled) {
	}

	@PatchMapping("/{id}")
	public SessionEntity patch(@PathVariable UUID id, @RequestBody PatchSessionRequest request) {
		if (request.costBudgetUsd() != null) {
			service.updateCostBudget(id, request.costBudgetUsd());
		}
		if (request.name() != null && !request.name().isBlank()) {
			service.rename(id, request.name().strip());
		}
		if (request.reflectionEnabled() != null) {
			service.updateReflectionEnabled(id, request.reflectionEnabled());
		}
		return sessions.get(id);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> close(@PathVariable UUID id,
									  @RequestParam(defaultValue = "fail") String dirty,
									  @RequestParam(required = false) String commitMessage) {
		service.close(id, dirty, commitMessage);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/reflect")
	public ResponseEntity<Void> reflect(@PathVariable UUID id) {
		reflection.reflect(id);
		return ResponseEntity.noContent().build();
	}

	/** Distills the session's demonstrated procedure into a pending skill proposal (phase 21). */
	@PostMapping("/{id}/extract-skill")
	public ResponseEntity<Void> extractSkill(@PathVariable UUID id) {
		reflection.extractSkill(id);
		return ResponseEntity.noContent().build();
	}

	public record RememberRequest(Long targetSeq) {
	}

	/** Turns one marked block into a small pending memory proposal — no episode, session not marked reflected (phase 21). */
	@PostMapping("/{id}/remember")
	public ResponseEntity<Void> remember(@PathVariable UUID id, @RequestBody RememberRequest request) {
		if (request.targetSeq() == null) {
			throw new IllegalArgumentException("targetSeq is required");
		}
		reflection.remember(id, request.targetSeq());
		return ResponseEntity.noContent().build();
	}

	public record MarkRequest(Long targetSeq, String mark) {
	}

	/** Marks a user/assistant block important or excluded for memory curation; a null mark clears it (phase 21). */
	@PostMapping("/{id}/marks")
	public ResponseEntity<Void> mark(@PathVariable UUID id, @RequestBody MarkRequest request) {
		if (request.targetSeq() == null) {
			throw new IllegalArgumentException("targetSeq is required");
		}
		blockMarks.mark(id, request.targetSeq(), request.mark());
		return ResponseEntity.noContent().build();
	}

	/** 409 (via ApiExceptionHandler) when the session isn't IDLE/PARKED — see SessionService.compact. */
	@PostMapping("/{id}/compact")
	public ResponseEntity<Void> compact(@PathVariable UUID id) {
		service.compact(id);
		return ResponseEntity.accepted().build();
	}

	@DeleteMapping("/{id}/queue/{pos}")
	public ResponseEntity<Void> deleteQueued(@PathVariable UUID id, @PathVariable long pos) {
		return service.deleteQueued(id, pos) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
