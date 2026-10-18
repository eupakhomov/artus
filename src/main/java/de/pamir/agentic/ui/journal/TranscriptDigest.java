package de.pamir.agentic.ui.journal;

import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a session's journal as text, in two shapes sharing the same per-event-type logic:
 * {@link #render} is a capped digest for the reflection prompt (see
 * docs/plan/phase-5.3-memory-reflection.md); {@link #renderMarkdown} is the uncapped, human-
 * facing transcript export (phase-5-extensions.md 5.9, {@code GET
 * /api/sessions/{id}/export.md}). Both: user/assistant text, tool calls collapsed to name +
 * one-line args, errors, permission denials, a costs footer.
 */
public final class TranscriptDigest {

	private static final int MAX_CHARS = 100_000;
	private static final int HEAD_CHARS = 60_000;
	private static final int TAIL_CHARS = 35_000;
	private static final int MARKED_BLOCK_CHARS = 4_000;
	/** Total budget for the important-blocks appendix, which sits outside the head/tail cap. */
	static final int MARKED_APPENDIX_CHARS = 20_000;
	private static final int ARG_SUMMARY_CHARS = 200;
	private static final int ERROR_SUMMARY_CHARS = 300;
	private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());

	public static final String MARK_IMPORTANT = "important";
	public static final String MARK_EXCLUDED = "excluded";
	private static final String EXCLUDED_PLACEHOLDER = "[excluded from memory by the user]";

	private TranscriptDigest() {
	}

	/**
	 * The current mark per target seq: a fold over {@code block_marked} events where the last one
	 * for a target wins and a null/unknown mark clears it (phase 21 decision 1).
	 */
	public static Map<Long, String> marks(List<EventJournal.JournalEvent> events) {
		Map<Long, String> marks = new HashMap<>();
		for (EventJournal.JournalEvent event : events) {
			if (!"block_marked".equals(event.type())) {
				continue;
			}
			JsonNode p = event.payload();
			String mark = p.path("mark").isString() ? p.get("mark").asText() : null;
			if (MARK_IMPORTANT.equals(mark) || MARK_EXCLUDED.equals(mark)) {
				marks.put(p.path("targetSeq").asLong(), mark);
			} else {
				marks.remove(p.path("targetSeq").asLong());
			}
		}
		return marks;
	}

	private record MarkedBlock(long seq, String role, String text) {
	}

	/**
	 * Capped LLM-prompt digest — see docs/plan/phase-5.3-memory-reflection.md. User/assistant lines
	 * carry a {@code [#seq]} anchor (what evidence citations refer to); blocks the user marked
	 * important are starred and repeated in full in an appendix after the cap, blocks marked
	 * excluded are replaced by a placeholder (phase 21 decision 4).
	 */
	public static String render(List<EventJournal.JournalEvent> events) {
		Map<Long, String> marks = marks(events);
		List<MarkedBlock> important = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		BigDecimal totalCost = BigDecimal.ZERO;
		int numTurns = 0;
		for (EventJournal.JournalEvent event : events) {
			JsonNode p = event.payload();
			switch (event.type()) {
				case "user_message" -> appendBlock(sb, important, marks, event.seq(), "User", p.path("text").asText());
				case "assistant_message" -> {
					String text = extractText(p.get("content"));
					if (!text.isBlank()) {
						appendBlock(sb, important, marks, event.seq(), "Assistant", text);
					}
				}
				case "tool_started" -> sb.append("  → ").append(p.path("name").asText())
						.append("(").append(truncate(compact(p.get("input")), ARG_SUMMARY_CHARS)).append(")\n");
				case "tool_result" -> {
					if (p.path("isError").asBoolean(false)) {
						sb.append("  ✗ tool error: ")
								.append(truncate(p.path("output").asText(""), ERROR_SUMMARY_CHARS)).append("\n");
					}
				}
				case "permission_response" -> {
					if ("deny".equals(p.path("behavior").asText())) {
						sb.append("  ✗ permission denied: ").append(p.path("message").asText("")).append("\n");
					}
				}
				case "error" -> sb.append("! error: ").append(p.path("message").asText("")).append("\n");
				case "warning" -> sb.append("! warning: ").append(p.path("message").asText("")).append("\n");
				case "turn_complete" -> {
					numTurns++;
					if (p.hasNonNull("costUsd")) {
						totalCost = totalCost.add(new BigDecimal(p.get("costUsd").asText("0")));
					}
				}
				default -> { /* skip: ready, system_init, stream_delta, permission_request, state_changed, ... */ }
			}
		}
		sb.append("\n---\n").append(numTurns).append(" turn(s), total cost $").append(totalCost).append('\n');
		String capped = cap(sb.toString());
		if (important.isEmpty()) {
			return capped;
		}
		StringBuilder appendix = new StringBuilder("\n## Marked as important by the user\n\n");
		for (int i = 0; i < important.size(); i++) {
			MarkedBlock block = important.get(i);
			String entry = "[#" + block.seq() + "] " + block.role() + ": "
					+ truncate(block.text(), MARKED_BLOCK_CHARS) + "\n\n";
			if (appendix.length() + entry.length() > MARKED_APPENDIX_CHARS) {
				appendix.append("… ").append(important.size() - i).append(" more marked block(s) omitted (appendix budget)\n");
				break;
			}
			appendix.append(entry);
		}
		return capped + appendix;
	}

	private static void appendBlock(StringBuilder sb, List<MarkedBlock> important, Map<Long, String> marks,
									long seq, String role, String text) {
		String mark = marks.get(seq);
		sb.append("[#").append(seq).append("] ");
		if (MARK_EXCLUDED.equals(mark)) {
			sb.append(role).append(": ").append(EXCLUDED_PLACEHOLDER).append("\n\n");
			return;
		}
		if (MARK_IMPORTANT.equals(mark)) {
			sb.append("★ MARKED ");
			important.add(new MarkedBlock(seq, role, text));
		}
		sb.append(role).append(": ").append(text).append("\n\n");
	}

	/** Uncapped Markdown transcript for the download button — 5.9. */
	public static String renderMarkdown(String sessionName, List<EventJournal.JournalEvent> events) {
		Map<Long, String> marks = marks(events);
		StringBuilder sb = new StringBuilder("# ").append(sessionName).append("\n\n");
		BigDecimal totalCost = BigDecimal.ZERO;
		int numTurns = 0;
		String model = null;
		for (EventJournal.JournalEvent event : events) {
			JsonNode p = event.payload();
			String ts = TS_FORMAT.format(event.ts());
			switch (event.type()) {
				case "user_message" -> sb.append("**User** (").append(ts).append(")")
						.append(markNote(marks.get(event.seq()))).append(":\n\n")
						.append(p.path("text").asText()).append("\n\n");
				case "assistant_message" -> {
					String text = extractText(p.get("content"));
					if (!text.isBlank()) {
						sb.append("**Assistant** (").append(ts).append(")")
								.append(markNote(marks.get(event.seq()))).append(":\n\n").append(text).append("\n\n");
					}
				}
				case "tool_started" -> sb.append("> 🔧 `").append(p.path("name").asText())
						.append("(").append(truncate(compact(p.get("input")), ARG_SUMMARY_CHARS)).append(")`\n");
				case "tool_result" -> {
					if (p.path("isError").asBoolean(false)) {
						sb.append("> ✗ tool error: ")
								.append(truncate(p.path("output").asText(""), ERROR_SUMMARY_CHARS)).append('\n');
					}
				}
				case "permission_response" -> {
					if ("deny".equals(p.path("behavior").asText())) {
						sb.append("> ✗ permission denied: ").append(p.path("message").asText("")).append('\n');
					}
				}
				case "error" -> sb.append("> ⚠️ error: ").append(p.path("message").asText("")).append('\n');
				case "warning" -> sb.append("> ⚠️ warning: ").append(p.path("message").asText("")).append('\n');
				case "reflection_complete" -> sb.append("> 🧠 reflection applied: ")
						.append(truncate(p.path("episode").asText(""), ERROR_SUMMARY_CHARS)).append('\n');
				case "reflection_discarded" -> sb.append("> 🧠 reflection discarded\n");
				case "turn_complete" -> {
					numTurns++;
					if (p.hasNonNull("model")) {
						model = p.path("model").asText();
					}
					if (p.hasNonNull("costUsd")) {
						totalCost = totalCost.add(new BigDecimal(p.get("costUsd").asText("0")));
					}
				}
				default -> { /* skip: ready, system_init, stream_delta, permission_request, state_changed, ... */ }
			}
		}
		sb.append("\n---\n\n**").append(numTurns).append(" turn(s)");
		if (model != null) {
			sb.append(", last model `").append(model).append('`');
		}
		sb.append(", total cost $").append(totalCost).append("**\n");
		return sb.toString();
	}

	/** The text of a markable block (user or assistant message), or null for any other event type. */
	public static String blockText(EventJournal.JournalEvent event) {
		return switch (event.type()) {
			case "user_message" -> event.payload().path("text").asText("");
			case "assistant_message" -> extractText(event.payload().get("content"));
			default -> null;
		};
	}

	private static String markNote(String mark) {
		if (MARK_IMPORTANT.equals(mark)) {
			return " ★ marked important";
		}
		return MARK_EXCLUDED.equals(mark) ? " (excluded from memory)" : "";
	}

	private static String cap(String text) {
		if (text.length() <= MAX_CHARS) {
			return text;
		}
		int omitted = text.length() - HEAD_CHARS - TAIL_CHARS;
		return text.substring(0, HEAD_CHARS) + "\n\n... [" + omitted + " characters omitted] ...\n\n"
				+ text.substring(text.length() - TAIL_CHARS);
	}

	private static String extractText(JsonNode content) {
		if (content == null || !content.isArray()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (JsonNode block : content) {
			if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
				sb.append(block.get("text").asText());
			}
		}
		return sb.toString();
	}

	private static String compact(JsonNode node) {
		return node == null ? "" : node.toString();
	}

	private static String truncate(String s, int max) {
		return s.length() > max ? s.substring(0, max) + "…" : s;
	}
}
