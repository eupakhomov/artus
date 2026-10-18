package de.pamir.agentic.ui.library;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure SKILL.md rendering behind {@link LibraryService#createSkill} — phase 21 decision 12. */
class LibraryServiceSkillTest {

	@Test
	void rendersFrontmatterThenTheBody() {
		String md = LibraryService.renderSkillMd("release-notes", "Use when drafting release notes.", "## Steps\n1. Do it\n");

		assertThat(md).startsWith("---\nname: release-notes\ndescription: \"Use when drafting release notes.\"\n---\n\n");
		assertThat(md).endsWith("## Steps\n1. Do it\n");
	}

	@Test
	void descriptionIsKeptOnOneYamlSafeLine() {
		String md = LibraryService.renderSkillMd("x", "line one\n  line \"two\"\\", "body");

		assertThat(md).contains("description: \"line one line \\\"two\\\"\\\\\"\n");
	}

	@Test
	void skillNameValidatorAcceptsKebabSlugsOnly() {
		assertThat(LibraryService.isValidSkillName("release-notes-2")).isTrue();
		assertThat(LibraryService.isValidSkillName("-leading")).isFalse();
		assertThat(LibraryService.isValidSkillName("Upper")).isFalse();
		assertThat(LibraryService.isValidSkillName("has space")).isFalse();
		assertThat(LibraryService.isValidSkillName("../escape")).isFalse();
		assertThat(LibraryService.isValidSkillName("a".repeat(64))).isFalse();
		assertThat(LibraryService.isValidSkillName(null)).isFalse();
	}
}
