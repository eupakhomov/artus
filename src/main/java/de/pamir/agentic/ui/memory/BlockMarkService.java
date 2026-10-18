package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.JournalPublisher;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Marks a transcript block as important to (or excluded from) memory curation by journaling a
 * {@code block_marked} event — see docs/plan/phase-21-memory-curation.md decisions 1-3. The mark
 * set is a fold over those events ({@link TranscriptDigest#marks}), so replay, restart and
 * multi-tab sync need no state of their own.
 */
@Service
public class BlockMarkService {

	private static final Set<String> MARKABLE_TYPES = Set.of("user_message", "assistant_message");

	private final SessionRepository sessions;
	private final EventJournal journal;
	private final JournalPublisher publisher;
	private final ObjectMapper mapper;

	public BlockMarkService(SessionRepository sessions, EventJournal journal, JournalPublisher publisher,
							ObjectMapper mapper) {
		this.sessions = sessions;
		this.journal = journal;
		this.publisher = publisher;
		this.mapper = mapper;
	}

	/** @param mark {@code "important"}, {@code "excluded"}, or {@code null} to clear */
	public void mark(UUID sessionId, long targetSeq, String mark) {
		SessionEntity session = sessions.get(sessionId);
		if ("system".equals(session.kind())) {
			throw new IllegalArgumentException("system sessions have no memory curation");
		}
		if (mark != null && !TranscriptDigest.MARK_IMPORTANT.equals(mark) && !TranscriptDigest.MARK_EXCLUDED.equals(mark)) {
			throw new IllegalArgumentException("mark must be \"important\", \"excluded\" or null");
		}
		var target = journal.findBySeq(sessionId, targetSeq)
				.orElseThrow(() -> new NoSuchElementException("no event with seq " + targetSeq + " in session " + sessionId));
		if (!MARKABLE_TYPES.contains(target.type())) {
			throw new IllegalArgumentException("only user and assistant messages can be marked, not " + target.type());
		}
		ObjectNode payload = mapper.createObjectNode().put("targetSeq", targetSeq);
		if (mark == null) {
			payload.putNull("mark");
		} else {
			payload.put("mark", mark);
		}
		publisher.record(sessionId, "block_marked", payload);
	}
}
