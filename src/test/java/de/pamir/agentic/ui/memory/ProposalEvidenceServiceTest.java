package de.pamir.agentic.ui.memory;

import de.pamir.agentic.ui.journal.EventJournal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure parts of {@link ProposalEvidenceService}: which seqs a proposal cites and how a block is quoted. */
class ProposalEvidenceServiceTest {

	private final JsonMapper mapper = new JsonMapper();

	@Test
	void collectsSeqsFromEveryOpOfAMemoryProposalWithoutDuplicates() throws Exception {
		var ops = mapper.readTree("[{\"name\":\"a\",\"evidence\":[3,5]},{\"name\":\"b\",\"evidence\":[5,9]},{\"name\":\"c\"}]");

		assertThat(ProposalEvidenceService.collectSeqs(ops)).containsExactly(3L, 5L, 9L);
	}

	@Test
	void collectsSeqsFromASkillProposalsMetaObject() throws Exception {
		var meta = mapper.readTree("{\"name\":\"x\",\"evidence\":[2,4]}");

		assertThat(ProposalEvidenceService.collectSeqs(meta)).containsExactly(2L, 4L);
	}

	@Test
	void ignoresMalformedEvidenceAndCapsTheCount() throws Exception {
		assertThat(ProposalEvidenceService.collectSeqs(mapper.readTree("[{\"evidence\":\"3\"},{\"evidence\":[\"x\",7]}]")))
				.containsExactly(7L);
		StringBuilder many = new StringBuilder("{\"evidence\":[");
		for (int i = 1; i <= 100; i++) {
			many.append(i).append(i < 100 ? "," : "");
		}
		many.append("]}");
		assertThat(ProposalEvidenceService.collectSeqs(mapper.readTree(many.toString())))
				.hasSize(ProposalEvidenceService.MAX_SEQS_PER_OP);
		assertThat(ProposalEvidenceService.collectSeqs(null)).isEmpty();
	}

	@Test
	void quotesAUserBlockFlattenedAndTruncated() {
		String long400 = ("word ".repeat(100)).strip();
		var event = new EventJournal.JournalEvent(1, Instant.now(), "user_message",
				mapper.createObjectNode().put("text", "line one\n\n  " + long400));

		String snippet = ProposalEvidenceService.snippet(event);

		assertThat(snippet).startsWith("line one word word").doesNotContain("\n").endsWith("…");
		assertThat(snippet.length()).isLessThanOrEqualTo(ProposalEvidenceService.SNIPPET_CHARS + 1);
	}

	@Test
	void aNonMessageEventResolvesToThePlaceholder() {
		var event = new EventJournal.JournalEvent(1, Instant.now(), "tool_started", mapper.createObjectNode().put("name", "Bash"));

		assertThat(ProposalEvidenceService.snippet(event)).isEqualTo(ProposalEvidenceService.NOT_FOUND);
	}

	@Test
	void acceptsAnchorStyleStringsTheModelEchoes() throws Exception {
		var ops = mapper.readTree("[{\"evidence\":[\"[#322]\",\"#7\",\"41\",12,\"nope\"]}]");

		assertThat(ProposalEvidenceService.collectSeqs(ops)).containsExactly(322L, 7L, 41L, 12L);
	}

	@Test
	void normalizeRewritesEvidenceToPlainIntegersInPlace() throws Exception {
		var ops = mapper.readTree("[{\"name\":\"a\",\"evidence\":[\"[#322]\",5]},{\"name\":\"b\"}]");

		ProposalEvidenceService.normalizeEvidence(ops);

		assertThat(ops.get(0).get("evidence").toString()).isEqualTo("[322,5]");
		assertThat(ops.get(1).has("evidence")).isFalse();
	}

	@Test
	void theCapAppliesPerOpSoLaterOpsKeepTheirCitations() throws Exception {
		StringBuilder ops = new StringBuilder("[");
		for (int op = 0; op < 3; op++) {
			ops.append(op > 0 ? "," : "").append("{\"evidence\":[");
			for (int i = 0; i < 20; i++) {
				ops.append(i > 0 ? "," : "").append(op * 100 + i);
			}
			ops.append("]}");
		}
		ops.append("]");

		assertThat(ProposalEvidenceService.collectSeqs(mapper.readTree(ops.toString()))).hasSize(60);
	}
}
