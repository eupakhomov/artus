package de.pamir.agentic.ui.git;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real {@code git} against a temp repo — the parsing and the where-badges are the logic under test. */
class ChangesServiceTest {

	@TempDir
	Path repo;

	private final GitCommandRunner git = new GitCommandRunner();
	private final ChangesService changes = new ChangesService(git);

	@BeforeEach
	void setUp() throws IOException {
		git.runOrThrow(repo, "init", "-q", "-b", "main");
		git.runOrThrow(repo, "config", "user.email", "t@example.com");
		git.runOrThrow(repo, "config", "user.name", "t");
		write("keep.txt", "unchanged\n");
		write("src/Foo.java", "class Foo {\n}\n");
		write("old-name.txt", "a rather long line of text so rename detection has something to hold on to\nsecond\nthird\n");
		write("gone.txt", "bye\n");
		git.runOrThrow(repo, "add", "-A");
		git.runOrThrow(repo, "commit", "-q", "-m", "base");
		git.runOrThrow(repo, "checkout", "-q", "-b", "feature");
	}

	private void write(String rel, String content) throws IOException {
		Path p = repo.resolve(rel);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
	}

	private ChangesService.ChangedFile row(ChangesService.ChangeList list, String path) {
		return list.files().stream().filter(f -> f.path().equals(path)).findFirst()
				.orElseThrow(() -> new AssertionError("no row for " + path + " in " + list.files()));
	}

	@Test
	void cleanBranchHasNoChanges() {
		ChangesService.ChangeList list = changes.list(repo, "main");
		assertThat(list.baseKnown()).isTrue();
		assertThat(list.files()).isEmpty();
	}

	@Test
	void classifiesCommittedStagedUnstagedAndUntracked() throws IOException {
		write("src/Foo.java", "class Foo {\n  int x;\n}\n");
		git.runOrThrow(repo, "commit", "-q", "-am", "committed change");

		write("staged.txt", "new\nfile\n");
		git.runOrThrow(repo, "add", "staged.txt");
		write("keep.txt", "unchanged\nplus one\n");           // unstaged edit
		write("fresh.md", "# hi\n\nthere\n");                  // untracked

		ChangesService.ChangeList list = changes.list(repo, "main");

		assertThat(row(list, "src/Foo.java").where()).containsExactly("committed");
		assertThat(row(list, "src/Foo.java").status()).isEqualTo("modified");
		assertThat(row(list, "src/Foo.java").additions()).isEqualTo(1);
		assertThat(row(list, "staged.txt").where()).containsExactly("staged");
		assertThat(row(list, "staged.txt").status()).isEqualTo("added");
		assertThat(row(list, "keep.txt").where()).containsExactly("unstaged");
		assertThat(row(list, "keep.txt").additions()).isEqualTo(1);
		assertThat(row(list, "fresh.md").where()).containsExactly("untracked");
		assertThat(row(list, "fresh.md").additions()).isEqualTo(3);
	}

	@Test
	void aFileCommittedAndThenEditedCarriesBothBadges() throws IOException {
		write("src/Foo.java", "class Foo {\n  int x;\n}\n");
		git.runOrThrow(repo, "commit", "-q", "-am", "c");
		write("src/Foo.java", "class Foo {\n  int x;\n  int y;\n}\n");

		var r = row(changes.list(repo, "main"), "src/Foo.java");

		assertThat(r.where()).containsExactly("committed", "unstaged");
		assertThat(r.additions()).isEqualTo(2); // against the merge-base, not HEAD
	}

	@Test
	void reportsDeletionsAndRenamesWithTheirOldPath() throws IOException {
		Files.delete(repo.resolve("gone.txt"));
		Files.move(repo.resolve("old-name.txt"), repo.resolve("new-name.txt"));
		git.runOrThrow(repo, "add", "-A");

		ChangesService.ChangeList list = changes.list(repo, "main");

		assertThat(row(list, "gone.txt").status()).isEqualTo("deleted");
		assertThat(row(list, "new-name.txt").status()).isEqualTo("renamed");
		assertThat(row(list, "new-name.txt").oldPath()).isEqualTo("old-name.txt");
		assertThat(list.files()).noneMatch(f -> f.path().equals("old-name.txt"));
	}

	@Test
	void unknownBaseBranchListsOnlyUncommittedWork() throws IOException {
		write("src/Foo.java", "class Foo {\n  int x;\n}\n");
		git.runOrThrow(repo, "commit", "-q", "-am", "c");
		write("keep.txt", "edited\n");

		ChangesService.ChangeList list = changes.list(repo, "no-such-branch");

		assertThat(list.baseKnown()).isFalse();
		assertThat(list.files()).extracting(ChangesService.ChangedFile::path).containsExactly("keep.txt");
	}

	@Test
	void ignoredFilesAreNotListed() throws IOException {
		git.runOrThrow(repo, "config", "core.excludesFile", "/dev/null");
		Files.writeString(repo.resolve(".git/info/exclude"), ".claude/settings.local.json\n");
		write(".claude/settings.local.json", "{}");

		assertThat(changes.list(repo, "main").files()).isEmpty();
	}

	@Test
	void fileViewHasWorkingTreeAndMergeBaseContent() throws IOException {
		write("src/Foo.java", "class Foo {\n  int x;\n}\n");
		git.runOrThrow(repo, "commit", "-q", "-am", "c");
		write("src/Foo.java", "class Foo {\n  int x;\n  int y;\n}\n");

		var view = changes.file(repo, "main", "src/Foo.java");

		assertThat(view.content()).isEqualTo("class Foo {\n  int x;\n  int y;\n}\n");
		assertThat(view.baseContent()).isEqualTo("class Foo {\n}\n"); // trailing newline preserved
		assertThat(view.binary()).isFalse();
	}

	@Test
	void addedFileHasNoBaseAndDeletedFileHasNoContent() throws IOException {
		write("new.txt", "x\n");
		Files.delete(repo.resolve("gone.txt"));

		var added = changes.file(repo, "main", "new.txt");
		assertThat(added.content()).isEqualTo("x\n");
		assertThat(added.baseContent()).isNull();

		var deleted = changes.file(repo, "main", "gone.txt");
		assertThat(deleted.content()).isNull();
		assertThat(deleted.baseContent()).isEqualTo("bye\n");
	}

	@Test
	void renamedFileReadsItsBaseFromTheOldPath() throws IOException {
		Files.move(repo.resolve("old-name.txt"), repo.resolve("new-name.txt"));
		git.runOrThrow(repo, "add", "-A");

		var view = changes.file(repo, "main", "new-name.txt");

		assertThat(view.baseContent()).startsWith("a rather long line");
		assertThat(view.content()).isEqualTo(view.baseContent());
	}

	@Test
	void binaryFilesCarryNoContent() throws IOException {
		Files.write(repo.resolve("blob.bin"), new byte[] {1, 2, 0, 3, 4});
		Files.write(repo.resolve("latin1.txt"), new byte[] {(byte) 0xE9, 'a'}); // invalid UTF-8

		assertThat(changes.file(repo, "main", "blob.bin").binary()).isTrue();
		assertThat(changes.file(repo, "main", "blob.bin").content()).isNull();
		assertThat(changes.file(repo, "main", "latin1.txt").binary()).isTrue();
	}

	@Test
	void oversizeFilesAreTruncatedNotRefused() throws IOException {
		write("big.txt", "é".repeat(ChangesService.MAX_FILE_BYTES)); // 2 bytes per char, cut mid-stream
		var view = changes.file(repo, "main", "big.txt");

		assertThat(view.truncated()).isTrue();
		assertThat(view.binary()).isFalse();
		assertThat(view.size()).isEqualTo(2L * ChangesService.MAX_FILE_BYTES);
		assertThat(view.content()).startsWith("éé");
	}

	@Test
	void unchangedTrackedFileIsNotFound() {
		assertThatThrownBy(() -> changes.file(repo, "main", "keep.txt")).isInstanceOf(NoSuchElementException.class);
	}

	@Test
	void malformedAndEscapingPathsAreRejected() throws IOException {
		for (String bad : new String[] {"", "../outside.txt", "src/../../x", "/etc/passwd", ".git/config",
				"sub/.git/config", ".GIT/HEAD", "C:\\win", "a\0b", "./keep.txt"}) {
			assertThatThrownBy(() -> changes.file(repo, "main", bad))
					.as(bad).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void symlinkedFilesAreNotFollowed() throws IOException {
		Path outside = Files.createTempFile("secret", ".txt");
		Files.writeString(outside, "secret");
		try {
			Files.createSymbolicLink(repo.resolve("link.txt"), outside);
			assertThatThrownBy(() -> changes.file(repo, "main", "link.txt"))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symlink");
		} finally {
			Files.deleteIfExists(outside);
		}
	}

	@Test
	void symlinkedDirectoriesCannotLeadOutOfTheWorktree() throws IOException {
		Path outsideDir = Files.createTempDirectory("outside");
		Files.writeString(outsideDir.resolve("f.txt"), "secret");
		try {
			Files.createSymbolicLink(repo.resolve("dirlink"), outsideDir);
			// untracked symlink to a dir is listed as "dirlink"; a path through it is not a change
			assertThatThrownBy(() -> changes.file(repo, "main", "dirlink/f.txt"))
					.isInstanceOfAny(NoSuchElementException.class, IllegalArgumentException.class);
		} finally {
			Files.deleteIfExists(outsideDir.resolve("f.txt"));
			Files.deleteIfExists(outsideDir);
		}
	}

	@Test
	void missingWorktreeListsNothing() {
		assertThat(changes.list(repo.resolve("nope"), "main").files()).isEmpty();
	}

	@Test
	void unifiedDiffCoversCommittedUnstagedAndUntrackedWithBinaryStub() throws IOException {
		write("src/Foo.java", "class Foo {\n  int x;\n}\n");
		git.runOrThrow(repo, "commit", "-q", "-am", "committed change");
		write("keep.txt", "unchanged\nplus one\n");
		write("fresh.md", "# hi\nthere\n");
		Files.write(repo.resolve("blob.bin"), new byte[] {0, 1, 2, 3});

		ChangesService.DiffSnapshot diff = changes.unifiedDiff(repo, "main", 100_000);

		assertThat(diff.files()).isEqualTo(4);
		assertThat(diff.text()).contains("+  int x;", "+plus one", "+# hi", "+there",
				"Binary files /dev/null and b/blob.bin differ");
		assertThat(diff.omitted()).isZero();
	}

	@Test
	void unifiedDiffRanksSourceFirstAndCapsWithATrailer() throws IOException {
		write("package-lock.json", "x\n".repeat(5000));
		write("src/Bar.java", "class Bar {}\n");
		ChangesService.DiffSnapshot diff = changes.unifiedDiff(repo, "main", 3000);

		assertThat(diff.text()).contains("class Bar").contains("[diff truncated: 1 files omitted]");
		assertThat(diff.text()).doesNotContain("x\nx\nx\nx");
	}

	@Test
	void fingerprintIsStableAcrossNoOpAndChangesOnEditEvenInOmittedFiles() throws IOException {
		write("src/Bar.java", "class Bar {}\n");
		String first = changes.unifiedDiff(repo, "main", 100).fingerprint();
		assertThat(changes.unifiedDiff(repo, "main", 100).fingerprint()).isEqualTo(first);
		write("src/Bar.java", "class Bar { int y; }\n");
		assertThat(changes.unifiedDiff(repo, "main", 100).fingerprint()).isNotEqualTo(first);
	}

	@Test
	void unifiedDiffOfAMissingWorktreeIsEmpty() {
		ChangesService.DiffSnapshot diff = changes.unifiedDiff(repo.resolve("nope"), "main", 1000);
		assertThat(diff.empty()).isTrue();
		assertThat(diff.text()).isEmpty();
	}

	@Test
	void rankKeepsNonTestJavaSourcesInTheTopTier() {
		assertThat(ChangesService.rank("src/Backtest.java")).isZero();
		assertThat(ChangesService.rank("src/Contest.java")).isZero();
		assertThat(ChangesService.rank("src/FooTest.java")).isEqualTo(1);
		assertThat(ChangesService.rank("src/FooTests.java")).isEqualTo(1);
		assertThat(ChangesService.rank("src/test/java/Foo.java")).isEqualTo(1);
	}

	@Test
	void changeSignatureIsStableUntilTheWorktreeChanges() throws IOException {
		String first = changes.changeSignature(repo);
		assertThat(changes.changeSignature(repo)).isEqualTo(first);

		write("src/Foo.java", "class Foo {\n  int signatureChange;\n}\n");
		String second = changes.changeSignature(repo);
		assertThat(second).isNotEqualTo(first);
		assertThat(changes.changeSignature(repo)).isEqualTo(second);

		assertThat(changes.changeSignature(repo.resolve("missing"))).isEqualTo("absent");
	}

	/** The regression that slipped past the listing-level test: an edit to an ALREADY-modified file
	 * keeps the porcelain listing identical, so only the file's mtime+size can carry the change. */
	@Test
	void changeSignatureSeesAnEditToAnAlreadyModifiedFile() throws IOException {
		write("src/Foo.java", "class Foo {\n  int a;\n}\n");
		String first = changes.changeSignature(repo);
		assertThat(changes.changeSignature(repo)).isEqualTo(first);

		write("src/Foo.java", "class Foo {\n  int a;\n  int b;\n}\n");
		assertThat(changes.changeSignature(repo)).isNotEqualTo(first);
	}
}
