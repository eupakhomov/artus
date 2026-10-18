package de.pamir.agentic.ui.journal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure event-list rendering, no Spring/DB — see docs/plan/phase-9-production-hardening.md T1. */
class TranscriptDigestTest {

	private final JsonMapper mapper = new JsonMapper();
	private final Instant t0 = Instant.parse("2026-09-06T10:00:00Z");

	@Test
	void rendersUserAssistantToolAndTurnEventsWithACostFooter() {
		ObjectNode userMsg = mapper.createObjectNode().put("text", "Fix the bug");

		ObjectNode assistantContent = mapper.createObjectNode();
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", "I'll look into it."));
		assistantContent.set("content", content);

		ObjectNode toolStart = mapper.createObjectNode().put("name", "Bash");
		toolStart.set("input", mapper.createObjectNode().put("command", "ls"));

		ObjectNode toolErr = mapper.createObjectNode().put("isError", true).put("output", "boom");

		ObjectNode denied = mapper.createObjectNode().put("behavior", "deny").put("message", "no");

		ObjectNode turnComplete = mapper.createObjectNode().put("costUsd", "0.05");

		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "user_message", userMsg),
				new EventJournal.JournalEvent(2, t0, "assistant_message", assistantContent),
				new EventJournal.JournalEvent(3, t0, "tool_started", toolStart),
				new EventJournal.JournalEvent(4, t0, "tool_result", toolErr),
				new EventJournal.JournalEvent(5, t0, "permission_response", denied),
				new EventJournal.JournalEvent(6, t0, "turn_complete", turnComplete));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("User: Fix the bug");
		assertThat(digest).contains("Assistant: I'll look into it.");
		assertThat(digest).contains("Bash(");
		assertThat(digest).contains("tool error: boom");
		assertThat(digest).contains("permission denied: no");
		assertThat(digest).contains("1 turn(s), total cost $0.05");
	}

	@Test
	void skipsEventTypesThatCarryNoDigestSignal() {
		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "ready", mapper.createObjectNode()),
				new EventJournal.JournalEvent(2, t0, "state_changed", mapper.createObjectNode()));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("0 turn(s), total cost $0");
	}

	@Test
	void capsAVeryLongDigestKeepingHeadAndTail() {
		String hugeText = "x".repeat(150_000);
		ObjectNode payload = mapper.createObjectNode().put("text", hugeText);
		List<EventJournal.JournalEvent> events = List.of(new EventJournal.JournalEvent(1, t0, "user_message", payload));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("characters omitted");
		assertThat(digest.length()).isLessThan(hugeText.length());
	}

	@Test
	void renderMarkdownIncludesATitleHeaderModelAndCostFooter() {
		ObjectNode userMsg = mapper.createObjectNode().put("text", "Do the thing");
		ObjectNode turnComplete = mapper.createObjectNode().put("model", "claude-sonnet-5").put("costUsd", "0.10");

		List<EventJournal.JournalEvent> events = List.of(
				new EventJournal.JournalEvent(1, t0, "user_message", userMsg),
				new EventJournal.JournalEvent(2, t0, "turn_complete", turnComplete));

		String markdown = TranscriptDigest.renderMarkdown("My Session", events);

		assertThat(markdown).startsWith("# My Session");
		assertThat(markdown).contains("**User**");
		assertThat(markdown).contains("last model `claude-sonnet-5`");
		assertThat(markdown).contains("total cost $0.10");
	}

	private EventJournal.JournalEvent userMsg(long seq, String text) {
		return new EventJournal.JournalEvent(seq, t0, "user_message", mapper.createObjectNode().put("text", text));
	}

	private EventJournal.JournalEvent assistantMsg(long seq, String text) {
		ObjectNode payload = mapper.createObjectNode();
		ArrayNode content = mapper.createArrayNode();
		content.add(mapper.createObjectNode().put("type", "text").put("text", text));
		payload.set("content", content);
		return new EventJournal.JournalEvent(seq, t0, "assistant_message", payload);
	}

	private EventJournal.JournalEvent mark(long seq, long target, String mark) {
		ObjectNode payload = mapper.createObjectNode().put("targetSeq", target);
		if (mark == null) {
			payload.putNull("mark");
		} else {
			payload.put("mark", mark);
		}
		return new EventJournal.JournalEvent(seq, t0, "block_marked", payload);
	}

	@Test
	void markFoldLetsTheLastMarkWinAndNullClears() {
		var marks = TranscriptDigest.marks(List.of(
				mark(3, 1, "important"), mark(4, 1, "excluded"), mark(5, 2, "important"), mark(6, 2, null)));

		assertThat(marks).containsOnlyKeys(1L).containsEntry(1L, "excluded");
	}

	@Test
	void unmarkedDigestOnlyGainsSeqAnchors() {
		String digest = TranscriptDigest.render(List.of(userMsg(1, "hello"), assistantMsg(2, "hi")));

		assertThat(digest).contains("[#1] User: hello").contains("[#2] Assistant: hi");
		assertThat(digest).doesNotContain("MARKED").doesNotContain("Marked as important");
	}

	@Test
	void excludedBlockContentNeverReachesTheDigest() {
		String digest = TranscriptDigest.render(List.of(
				userMsg(1, "my password is hunter2"), assistantMsg(2, "noted"), mark(3, 1, "excluded")));

		assertThat(digest).doesNotContain("hunter2");
		assertThat(digest).contains("[#1] User: [excluded from memory by the user]").contains("[#2] Assistant: noted");
	}

	@Test
	void importantBlockIsStarredAndSurvivesTheCapInAnAppendix() {
		List<EventJournal.JournalEvent> events = new java.util.ArrayList<>();
		events.add(userMsg(1, "the key insight: use approach B"));
		events.add(userMsg(2, "y".repeat(150_000)));
		events.add(mark(3, 1, "important"));

		String digest = TranscriptDigest.render(events);

		assertThat(digest).contains("[#1] ★ MARKED User: the key insight");
		assertThat(digest).contains("## Marked as important by the user");
		assertThat(digest.substring(digest.indexOf("## Marked as important"))).contains("the key insight: use approach B");
	}

	@Test
	void importantBlockLongerThanTheAppendixCapIsTruncated() {
		String digest = TranscriptDigest.render(List.of(userMsg(1, "z".repeat(10_000)), mark(2, 1, "important")));

		String appendix = digest.substring(digest.indexOf("## Marked as important"));
		assertThat(appendix.length()).isLessThan(5_000);
	}

	@Test
	void markdownExportAnnotatesMarksButHidesNothing() {
		String md = TranscriptDigest.renderMarkdown("s", List.of(
				userMsg(1, "keep me"), userMsg(2, "secret thing"), mark(3, 1, "important"), mark(4, 2, "excluded")));

		assertThat(md).contains("★ marked important").contains("(excluded from memory)");
		assertThat(md).contains("keep me").contains("secret thing");
	}

	@Test
	void manyImportantBlocksStayWithinTheAppendixBudget() {
		List<EventJournal.JournalEvent> events = new java.util.ArrayList<>();
		for (int i = 1; i <= 50; i++) {
			events.add(userMsg(i, "w".repeat(3_000)));
		}
		for (int i = 1; i <= 50; i++) {
			events.add(mark(100 + i, i, "important"));
		}

		String digest = TranscriptDigest.render(events);
		String appendix = digest.substring(digest.indexOf("## Marked as important"));

		assertThat(appendix.length()).isLessThanOrEqualTo(TranscriptDigest.MARKED_APPENDIX_CHARS + 200);
		assertThat(appendix).contains("more marked block(s) omitted");
	}

	@Test
	void aFewImportantBlocksAreAllKeptWithNoOmissionNote() {
		String digest = TranscriptDigest.render(List.of(userMsg(1, "one"), userMsg(2, "two"),
				mark(3, 1, "important"), mark(4, 2, "important")));

		assertThat(digest).contains("[#1] User: one").contains("[#2] User: two").doesNotContain("omitted");
	}
}
