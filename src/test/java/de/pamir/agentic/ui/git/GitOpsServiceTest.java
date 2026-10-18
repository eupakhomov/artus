package de.pamir.agentic.ui.git;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code parsePrUrl} is pure/static (docs/plan/phase-15-review-sessions.md Step 2's test note);
 * the process-touching methods use a hand-written {@code runGh} override instead of a mocking
 * framework — this file's convention, matching {@code SerenaServiceTest}'s {@code ProcessRunner}
 * fake and {@code GitCommandRunner}-subclass fakes used elsewhere in this codebase.
 */
class GitOpsServiceTest {

	// --- parsePrUrl (pure static) ---

	@Test
	void parsePrUrlExtractsOwnerRepoAndNumber() {
		GitOpsService.PrRef ref = GitOpsService.parsePrUrl("https://github.com/acme/widget/pull/42");

		assertThat(ref.owner()).isEqualTo("acme");
		assertThat(ref.repo()).isEqualTo("widget");
		assertThat(ref.number()).isEqualTo(42);
	}

	@Test
	void parsePrUrlAcceptsATrailingSlash() {
		GitOpsService.PrRef ref = GitOpsService.parsePrUrl("https://github.com/acme/widget/pull/42/");

		assertThat(ref.number()).isEqualTo(42);
	}

	@Test
	void parsePrUrlRejectsANonGithubUrl() {
		assertThatThrownBy(() -> GitOpsService.parsePrUrl("https://gitlab.com/acme/widget/-/merge_requests/42"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cannot parse PR URL");
	}

	@Test
	void parsePrUrlRejectsNull() {
		assertThatThrownBy(() -> GitOpsService.parsePrUrl(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// --- resolvePr branch-name validation ---

	@Test
	void resolvePrRejectsABranchNameThatLooksLikeAFlag() {
		GitOpsService gitOps = new GitOpsService(null, null, new JsonMapper());

		assertThatThrownBy(() -> gitOps.resolvePr(Path.of("/worktree"), "--upload-pack=evil"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid branch name");
	}

	// --- listOpenPrs / resolvePr / submitPrReview against a faked gh runner ---

	/** Overrides the package-private gh-runner seam so no real process is started. */
	private static GitOpsService gitOpsWithFakeGh(GitOpsService.GhResult result, List<List<String>> capturedArgs) {
		return new GitOpsService(null, null, new JsonMapper()) {
			@Override
			GhResult runGh(Path cwd, Duration timeout, String... args) {
				capturedArgs.add(List.of(args));
				return result;
			}

			@Override
			GhResult runGhWithStdin(Path cwd, Duration timeout, String stdin, String... args) {
				capturedArgs.add(List.of(args));
				capturedArgs.add(List.of(stdin));
				return result;
			}
		};
	}

	@Test
	void listOpenPrsParsesTheGhJsonArray() {
		String json = "[{\"number\":7,\"title\":\"Fix bug\",\"headRefName\":\"feat/x\",\"baseRefName\":\"main\","
				+ "\"url\":\"https://github.com/acme/widget/pull/7\",\"author\":{\"login\":\"alice\"},\"isDraft\":false}]";
		GitOpsService gitOps = gitOpsWithFakeGh(new GitOpsService.GhResult(0, json, ""), new java.util.ArrayList<>());

		List<GitOpsService.PrInfo> prs = gitOps.listOpenPrs(Path.of("/repo"));

		assertThat(prs).hasSize(1);
		assertThat(prs.get(0).number()).isEqualTo(7);
		assertThat(prs.get(0).headRefName()).isEqualTo("feat/x");
		assertThat(prs.get(0).author()).isEqualTo("alice");
	}

	@Test
	void listOpenPrsThrowsAReadableErrorOnGhFailure() {
		GitOpsService gitOps = gitOpsWithFakeGh(new GitOpsService.GhResult(1, "", "gh: not authenticated"), new java.util.ArrayList<>());

		assertThatThrownBy(() -> gitOps.listOpenPrs(Path.of("/repo")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not authenticated");
	}

	@Test
	void resolvePrReturnsEmptyWhenGhFindsNoPr() {
		GitOpsService gitOps = gitOpsWithFakeGh(new GitOpsService.GhResult(1, "", "no pull requests found"), new java.util.ArrayList<>());

		assertThat(gitOps.resolvePr(Path.of("/worktree"), "feat/x")).isEmpty();
	}

	@Test
	void submitPrReviewSendsTheWholePayloadOverStdin() {
		List<List<String>> captured = new java.util.ArrayList<>();
		GitOpsService gitOps = gitOpsWithFakeGh(new GitOpsService.GhResult(0, "", ""), captured);
		GitOpsService.PrRef pr = new GitOpsService.PrRef("acme", "widget", 7);

		gitOps.submitPrReview(Path.of("/worktree"), pr, "COMMENT", "looks good",
				List.of(new GitOpsService.ReviewComment("src/Foo.java", 10, null, null, "nit: typo")));

		List<String> args = captured.get(0);
		assertThat(args).contains("api", "repos/acme/widget/pulls/7/reviews", "--input", "-");
		String stdin = captured.get(1).get(0);
		assertThat(stdin).contains("\"event\":\"COMMENT\"").contains("\"body\":\"looks good\"")
				.contains("\"side\":\"RIGHT\"").contains("src/Foo.java");
	}

	@Test
	void submitPrReviewThrowsWithGhErrorBodyOnFailure() {
		GitOpsService gitOps = gitOpsWithFakeGh(
				new GitOpsService.GhResult(1, "", "422: comment path does not match diff"), new java.util.ArrayList<>());
		GitOpsService.PrRef pr = new GitOpsService.PrRef("acme", "widget", 7);

		assertThatThrownBy(() -> gitOps.submitPrReview(Path.of("/worktree"), pr, "COMMENT", "body", List.of()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("422");
	}

	@Test
	void submitPrReviewSurfacesGitHubsReasonsFromTheJsonBody() {
		String body = "{\"message\":\"Unprocessable Entity\",\"errors\":[\"Review Can not request changes on your own pull request\"],\"status\":\"422\"}";
		GitOpsService gitOps = gitOpsWithFakeGh(
				new GitOpsService.GhResult(1, body, "gh: Unprocessable Entity (HTTP 422)"), new java.util.ArrayList<>());
		GitOpsService.PrRef pr = new GitOpsService.PrRef("acme", "widget", 7);

		assertThatThrownBy(() -> gitOps.submitPrReview(Path.of("/worktree"), pr, "REQUEST_CHANGES", "body", List.of()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Review Can not request changes on your own pull request")
				.hasMessageContaining("HTTP 422");
	}

	@Test
	void submitPrReviewReadsObjectShapedErrorsToo() {
		String body = "{\"message\":\"Validation Failed\",\"errors\":[{\"resource\":\"PullRequestReviewComment\",\"message\":\"line must be part of the diff\"}]}";
		GitOpsService gitOps = new GitOpsService(null, null, new JsonMapper());
		assertThat(gitOps.reviewFailureDetail(new GitOpsService.GhResult(1, body, "gh: Validation Failed (HTTP 422)")))
				.startsWith("line must be part of the diff");
	}

	@Test
	void submitPrReviewOmitsAStartLineThatDoesNotPrecedeTheLine() {
		List<List<String>> captured = new java.util.ArrayList<>();
		GitOpsService gitOps = gitOpsWithFakeGh(new GitOpsService.GhResult(0, "", ""), captured);
		GitOpsService.PrRef pr = new GitOpsService.PrRef("acme", "widget", 7);

		gitOps.submitPrReview(Path.of("/worktree"), pr, "COMMENT", "b", List.of(
				new GitOpsService.ReviewComment("a.js", 7, null, 7, "single line, start == line"),
				new GitOpsService.ReviewComment("a.js", 9, null, 4, "a real range")));

		String stdin = captured.get(1).get(0);
		assertThat(stdin).doesNotContain("\"start_line\":7").contains("\"start_line\":4");
	}

	// --- originSlug parsing / PR detail parsing (Slack-started reviews) ---

	@Test
	void slugOfReadsOwnerAndRepoFromEveryCommonRemoteShape() {
		assertThat(GitOpsService.slugOf("git@github.com:acme/api.git")).contains("acme/api");
		assertThat(GitOpsService.slugOf("https://github.com/acme/api")).contains("acme/api");
		assertThat(GitOpsService.slugOf("https://github.com/acme/api.git")).contains("acme/api");
		assertThat(GitOpsService.slugOf("https://x-access-token:abc@github.com/acme/api.git/")).contains("acme/api");
		assertThat(GitOpsService.slugOf("ssh://git@github.com/acme/my-repo.name.git")).contains("acme/my-repo.name");
		assertThat(GitOpsService.slugOf("https://github.com:443/acme/api")).contains("acme/api");
	}

	@Test
	void slugOfRejectsWhatIsNotARemoteOfThatShape() {
		assertThat(GitOpsService.slugOf("")).isEmpty();
		assertThat(GitOpsService.slugOf(null)).isEmpty();
		assertThat(GitOpsService.slugOf("/local/path/to/repo")).isEmpty();
		assertThat(GitOpsService.slugOf("https://github.com/only-owner")).isEmpty();
	}

	@Test
	void parsePrDetailsCarriesStateForkFlagAndChangedFiles() throws Exception {
		GitOpsService svc = new GitOpsService(null, null, new JsonMapper());
		GitOpsService.PrDetails d = svc.parsePrDetails(new JsonMapper().readTree("""
				{"number":7,"title":"T","headRefName":"feat/x","baseRefName":"main","url":"https://github.com/a/b/pull/7",
				 "author":{"login":"bob"},"isDraft":true,"state":"OPEN","isCrossRepository":true,
				 "files":[{"path":"packages/api/A.java","additions":1},{"path":"README.md"},{"path":""}]}"""));
		assertThat(d.pr().number()).isEqualTo(7);
		assertThat(d.pr().headRefName()).isEqualTo("feat/x");
		assertThat(d.pr().author()).isEqualTo("bob");
		assertThat(d.pr().isDraft()).isTrue();
		assertThat(d.state()).isEqualTo("OPEN");
		assertThat(d.crossRepository()).isTrue();
		assertThat(d.files()).containsExactly("packages/api/A.java", "README.md");
	}

	@Test
	void parsePrDetailsToleratesMissingOptionalFields() throws Exception {
		GitOpsService svc = new GitOpsService(null, null, new JsonMapper());
		GitOpsService.PrDetails d = svc.parsePrDetails(new JsonMapper().readTree("{\"number\":1}"));
		assertThat(d.files()).isEmpty();
		assertThat(d.crossRepository()).isFalse();
		assertThat(d.state()).isEmpty();
	}
}
