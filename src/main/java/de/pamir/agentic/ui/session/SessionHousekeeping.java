package de.pamir.agentic.ui.session;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.process.SidecarManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Idle parking and the startup orphan sweep — extracted out of SessionService (see
 * docs/plan/phase-9-production-hardening.md S1) since neither needs anything from the general
 * message-routing/create/close surface, just read/transition access to sessions and the sidecar
 * manager. One-way dependency on SessionService (its package-private
 * {@code lock}/{@code transition}/{@code wake} session-mechanics) — no cycle, so no {@code @Lazy}
 * needed here (contrast SystemSessionService, which SessionService also depends on).
 */
@Component
public class SessionHousekeeping {

	private static final Logger log = LoggerFactory.getLogger(SessionHousekeeping.class);

	private final AppProperties props;
	private final SessionRepository sessions;
	private final SidecarManager sidecars;
	private final JournalPublisher journalPublisher;
	private final ObjectMapper mapper;
	private final SessionService sessionService;

	public SessionHousekeeping(AppProperties props, SessionRepository sessions,
							   SidecarManager sidecars, JournalPublisher journalPublisher,
							   ObjectMapper mapper, SessionService sessionService) {
		this.props = props;
		this.sessions = sessions;
		this.sidecars = sidecars;
		this.journalPublisher = journalPublisher;
		this.mapper = mapper;
		this.sessionService = sessionService;
	}

	// ------------------------------------------------------------------ parking

	/** IDLE sessions whose sidecar has been quiet past the timeout are parked. */
	@Scheduled(fixedDelay = 60_000)
	void parkIdleSessions() {
		var cutoff = Instant.now().minus(Duration.ofMinutes(props.idleParkMinutes()));
		for (SessionEntity session : sessions.findByStates(List.of(SessionState.IDLE))) {
			if (session.updatedAt() != null && session.updatedAt().isBefore(cutoff)
					// a recycled, re-warmed system session has no conversation (null provider id) but
					// still holds a live sidecar worth parking
					&& (session.providerSessionId() != null || "system".equals(session.kind()))
					&& sidecars.hasLiveHandle(session.id())) {
				synchronized (sessionService.lock(session.id())) {
					if (sessions.get(session.id()).state() != SessionState.IDLE) {
						continue;
					}
					log.info("parking idle session {}", session.id());
					sessionService.transition(session.id(), SessionState.PARKED);
					sidecars.terminate(session.id());
				}
			}
		}
	}

	// ------------------------------------------------------------------ startup sweep

	@EventListener(ApplicationReadyEvent.class)
	void markOrphanedSessionsCrashed() {
		for (SessionEntity session : sessions.findByStates(List.copyOf(SessionState.LIVE))) {
			killOrphanSidecar(session);
			log.info("startup sweep: session {} was {} -> CRASHED", session.id(), session.state());
			journalPublisher.record(session.id(), "error", mapper.createObjectNode()
					.put("message", "backend restarted while session was live").put("fatal", true));
			sessionService.transition(session.id(), SessionState.CRASHED);
		}
	}

	/**
	 * Phase 19: the singleton system session (named {@code system}) was replaced by two per-lane
	 * sessions. Retire any leftover live system row that carries none of the lane names — orphan sidecar
	 * killed, then the normal system-session close (scratch dir removed, CLOSED). The new rows are
	 * created on demand. No-op once nothing legacy remains.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void retireLegacySystemSessions() {
		for (SessionEntity session : sessions.findLiveSystemSessions()) {
			if (SystemSessionService.LANE_NAMES.contains(session.name())) {
				continue;
			}
			log.info("startup sweep: retiring legacy system session {} ('{}', {})",
					session.id(), session.name(), session.state());
			killOrphanSidecar(session);
			try {
				sessionService.close(session.id(), "fail", null);
			} catch (RuntimeException e) {
				log.warn("startup sweep: retiring legacy system session {} failed: {}", session.id(), e.getMessage());
			}
		}
	}

	/**
	 * A close that died part-way (failed worktree removal, backend stopped mid-close) leaves the
	 * session in CLOSING forever: it rejects messages and has no Resume. Nothing can be in flight
	 * at startup, so finish the close here; a failure is logged and the session stays CLOSING for
	 * the user to retry from the close dialog.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void finishInterruptedCloses() {
		for (SessionEntity session : sessions.findByStates(List.of(SessionState.CLOSING))) {
			log.info("startup sweep: session {} was CLOSING -> retrying close", session.id());
			try {
				sessionService.close(session.id(), "fail", null);
			} catch (RuntimeException e) {
				log.warn("startup sweep: retrying close of session {} failed: {}", session.id(), e.getMessage());
			}
		}
	}

	/** A kill -9'd backend leaves sidecars running; their PID files let us reap them. */
	private void killOrphanSidecar(SessionEntity session) {
		Path pidFile = SidecarManager.pidFile(session);
		try {
			if (!Files.exists(pidFile)) {
				return;
			}
			long pid = Long.parseLong(Files.readString(pidFile).strip());
			ProcessHandle.of(pid).ifPresent(handle -> {
				String cmd = handle.info().commandLine().orElse("");
				if (cmd.contains("dist/index.js")) {
					log.info("killing orphan sidecar pid {} for session {}", pid, session.id());
					handle.descendants().forEach(ProcessHandle::destroyForcibly);
					handle.destroyForcibly();
				}
			});
			Files.deleteIfExists(pidFile);
		} catch (IOException | NumberFormatException e) {
			log.warn("orphan check for {} failed: {}", session.id(), e.getMessage());
		}
	}
}
