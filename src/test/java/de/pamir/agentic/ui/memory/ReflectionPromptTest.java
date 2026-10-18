package de.pamir.agentic.ui.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure prompt assembly of {@link ReflectionService} — phase 21 decisions 4 and 9. */
class ReflectionPromptTest {

	private static MemoryEpisodeRepository.Episode episode(String summary) {
		return new MemoryEpisodeRepository.Episode(UUID.randomUUID(), UUID.randomUUID(), "s", "/repo", Instant.now(), summary);
	}

	@Test
	void firstReflectionCarriesNoPriorEpisodeBlock() {
		String prompt = ReflectionService.buildPrompt("/repo", "digest text", List.of(), List.of());

		assertThat(prompt).doesNotContain("reflected on before").contains("digest text").contains("(none yet)");
	}

	@Test
	void reReflectionListsEarlierEpisodesAndAsksNotToRestateThem() {
		String prompt = ReflectionService.buildPrompt("/repo", "digest", List.of(),
				List.of(episode("Fixed the flaky test by pinning the clock."), episode("Added a retry.")));

		assertThat(prompt).contains("reflected on before")
				.contains("- Fixed the flaky test by pinning the clock.")
				.contains("- Added a retry.")
				.contains("Do not restate them");
	}

	@Test
	void promptExplainsMarkAndExclusionConventions() {
		String prompt = ReflectionService.buildPrompt("/repo", "digest", List.of(), List.of());

		assertThat(prompt).contains("★ MARKED").contains("[excluded from memory by the user]");
	}

	@Test
	void skillPromptListsExistingSkillsAndAllowsDecliningToExtract() {
		String prompt = ReflectionService.buildSkillPrompt("the digest", List.of("deploy-checklist", "pr-review"));

		assertThat(prompt).contains("deploy-checklist, pr-review").contains("the digest")
				.contains("{\"skill\": null").contains("\"evidence\"");
	}

	@Test
	void skillPromptMarksAnEmptyLibrary() {
		assertThat(ReflectionService.buildSkillPrompt("d", List.of())).contains("(none)");
	}

	@Test
	void reflectionPromptAsksForEvidenceAnchors() {
		assertThat(ReflectionService.buildPrompt("/repo", "d", List.of(), List.of())).contains("\"evidence\"");
	}

	@Test
	void rememberPromptCarriesTheBlockAndItsSeq() {
		String prompt = ReflectionService.buildRememberPrompt("/repo", 42, "always use pnpm here", List.of());

		assertThat(prompt).contains("Block #42:").contains("always use pnpm here").contains("\"evidence\": [42]")
				.contains("{\"semantic\": []}");
	}

	private static MemoryProposalRepository.Proposal proposal(String kind, String episode, tools.jackson.databind.JsonNode ops) {
		return new MemoryProposalRepository.Proposal(UUID.randomUUID(), UUID.randomUUID(), "s", "/repo", null, episode, ops,
				"PENDING", Instant.now(), null, kind);
	}

	@Test
	void discardNoteForASkillNamesItInsteadOfJournalingTheWholeBody() throws Exception {
		var ops = new tools.jackson.databind.json.JsonMapper().readTree("{\"name\":\"deploy-checklist\"}");

		String note = ReflectionService.discardNote(proposal("skill", "# huge body ".repeat(500), ops));

		assertThat(note).isEqualTo("skill proposal: deploy-checklist");
	}

	@Test
	void discardNoteTruncatesALongMemoryEpisode() {
		var ops = new tools.jackson.databind.json.JsonMapper().createArrayNode();

		assertThat(ReflectionService.discardNote(proposal("memory", "e".repeat(1_000), ops)).length()).isLessThan(250);
		assertThat(ReflectionService.discardNote(proposal("memory", "short episode", ops))).isEqualTo("short episode");
	}
}
