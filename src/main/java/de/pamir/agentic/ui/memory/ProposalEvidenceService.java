package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.journal.EventJournal;
import de.pamir.agentic.ui.journal.TranscriptDigest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the {@code evidence} seqs a reflection or skill proposal cites into short quoted
 * snippets of the transcript blocks they point at, so approving a proposal is an audit rather than
 * an act of faith (phase 21 decision 13). A seq that no longer resolves yields a placeholder, never
 * an error.
 */
@Service
public class ProposalEvidenceService {

	static final int SNIPPET_CHARS = 300;
	/** Seqs one op (or a skill's meta) may cite. */
	static final int MAX_SEQS_PER_OP = 30;
	/** Seqs resolved for one proposal in total; anything past it is left out of the map (the card says "not resolved"). */
	static final int MAX_RESOLVED = 200;
	static final String NOT_FOUND = "(block not found)";

	private final EventJournal journal;

	public ProposalEvidenceService(EventJournal journal) {
		this.journal = journal;
	}

	/** seq -> quoted snippet for every seq cited anywhere in the proposal's ops. */
	public Map<Long, String> resolve(UUID sessionId, JsonNode ops) {
		List<Long> seqs = collectSeqs(ops).stream().limit(MAX_RESOLVED).toList();
		Map<Long, EventJournal.JournalEvent> events = journal.findBySeqs(sessionId, seqs);
		Map<Long, String> snippets = new LinkedHashMap<>();
		for (long seq : seqs) {
			EventJournal.JournalEvent event = events.get(seq);
			snippets.put(seq, event == null ? NOT_FOUND : snippet(event));
		}
		return snippets;
	}

	static String snippet(EventJournal.JournalEvent event) {
		String text = TranscriptDigest.blockText(event);
		if (text == null || text.isBlank()) {
			return NOT_FOUND;
		}
		String flat = text.strip().replaceAll("\\s+", " ");
		return flat.length() > SNIPPET_CHARS ? flat.substring(0, SNIPPET_CHARS) + "…" : flat;
	}

	/** Seqs from a memory proposal's op array (each op's {@code evidence}) or a skill proposal's meta object. */
	static Set<Long> collectSeqs(JsonNode ops) {
		Set<Long> seqs = new LinkedHashSet<>();
		if (ops == null) {
			return seqs;
		}
		if (ops.isArray()) {
			for (JsonNode op : ops) {
				addAll(seqs, op.path("evidence"));
			}
		} else {
			addAll(seqs, ops.path("evidence"));
		}
		return seqs;
	}

	private static final java.util.regex.Pattern ANCHOR = java.util.regex.Pattern.compile("\\[?#?(\\d{1,18})]?");

	/**
	 * Models echo the digest's {@code [#322]} anchors as strings as often as they emit plain numbers,
	 * so both are accepted; anything else is ignored.
	 */
	static Long parseSeq(JsonNode node) {
		if (node.isIntegralNumber()) {
			return node.asLong();
		}
		if (node.isString()) {
			var m = ANCHOR.matcher(node.asText().strip());
			if (m.matches()) {
				return Long.parseLong(m.group(1));
			}
		}
		return null;
	}

	/** Rewrites every {@code evidence} array under the proposal's ops into plain integers (what the UI and resolver expect). */
	public static void normalizeEvidence(JsonNode ops) {
		if (ops == null) {
			return;
		}
		if (ops.isArray()) {
			for (JsonNode op : ops) {
				normalizeEvidence(op);
			}
		} else if (ops instanceof tools.jackson.databind.node.ObjectNode object && object.path("evidence").isArray()) {
			var cleaned = object.arrayNode();
			for (long seq : collectSeqs(object)) {
				cleaned.add(seq);
			}
			object.set("evidence", cleaned);
		}
	}

	private static void addAll(Set<Long> seqs, JsonNode evidence) {
		if (!evidence.isArray()) {
			return;
		}
		int taken = 0;
		for (JsonNode node : evidence) {
			if (taken >= MAX_SEQS_PER_OP) {
				return;
			}
			taken++;
			Long seq = parseSeq(node);
			if (seq != null) {
				seqs.add(seq);
			}
		}
	}
}
