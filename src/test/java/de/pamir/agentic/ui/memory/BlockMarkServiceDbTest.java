package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import de.pamir.agentic.ui.session.SessionEntity;
import de.pamir.agentic.ui.session.SessionRepository;
import de.pamir.agentic.ui.session.SessionState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link BlockMarkService} validation + journaling against a live Postgres (phase 21 decision 3). Rolled back per test. */
@Tag("integration")
@SpringBootTest
@Transactional
class BlockMarkServiceDbTest {

	@Autowired
	private BlockMarkService marks;

	@Autowired
	private EventJournal journal;

	@Autowired
	private SessionRepository sessions;

	@Autowired
	private ObjectMapper mapper;

	private UUID newSession() {
		UUID id = UUID.randomUUID();
		sessions.insert(SessionEntity.builder()
				.id(id).name("t-" + id).provider("claude")
				.repoPath("/repo").branch("b-" + id).baseBranch("main").worktreePath("/wt/" + id)
				.skillSources(mapper.createArrayNode()).agentSources(mapper.createArrayNode())
				.state(SessionState.IDLE).build());
		return id;
	}

	@Test
	void marksAUserMessageAndClearsItAgain() {
		UUID id = newSession();
		journal.append(id, "user_message", mapper.createObjectNode().put("text", "remember this"));

		marks.mark(id, 1, "important");
		assertThat(TranscriptDigest.marks(journal.readAfter(id, 0))).containsEntry(1L, "important");

		marks.mark(id, 1, null);
		assertThat(TranscriptDigest.marks(journal.readAfter(id, 0))).isEmpty();
	}

	@Test
	void unknownSeqIsNotFound() {
		UUID id = newSession();

		assertThatThrownBy(() -> marks.mark(id, 42, "important")).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void toolEventsAndBogusMarksAreRejected() {
		UUID id = newSession();
		journal.append(id, "tool_started", mapper.createObjectNode().put("name", "Bash"));
		journal.append(id, "user_message", mapper.createObjectNode().put("text", "hi"));

		assertThatThrownBy(() -> marks.mark(id, 1, "important")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> marks.mark(id, 2, "starred")).isInstanceOf(IllegalArgumentException.class);
	}
}
