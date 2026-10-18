package de.pamir.agentic.ui.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read/write git operations on a session worktree, plus PR creation/status via gh. */
@Service
public class GitOpsService {

	private static final Logger log = LoggerFactory.getLogger(GitOpsService.class);

	/**
	 * aheadOfBase: commits on this branch not on baseBranch (-1 if it couldn't be computed,
	 * e.g. baseBranch no longer exists — callers should treat that as "unknown", not "none").
	 */
	public record GitStatus(String branch, List<String> dirty, String upstream, int ahead, int behind, int aheadOfBase) {
	}

	public record LogEntry(String hash, String subject, String author, String date) {
	}

	public enum PrCheckStatus { PENDING, SUCCESS, FAILURE, MERGED, CLOSED, ERROR }

	public record PrCheckResult(PrCheckStatus status, String headSha) {
	}

	private static final Set<String> FAILING_CONCLUSIONS =
			Set.of("FAILURE", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE");
	private static final Set<String> FAILING_STATES = Set.of("FAILURE", "ERROR");

	private final GitCommandRunner git;
	private final GitWorktreeService worktrees;
	private final ObjectMapper mapper;

	public GitOpsService(GitCommandRunner git, GitWorktreeService worktrees, ObjectMapper mapper) {
		this.git = git;
		this.worktrees = worktrees;
		this.mapper = mapper;
	}

	public GitStatus status(Path worktree, String baseBranch) {
		String branch = git.runOrThrow(worktree, "branch", "--show-current").stdout();
		List<String> dirty = worktrees.dirtyFiles(worktree);
		String upstream = null;
		int ahead = 0;
		int behind = 0;
		var up = git.run(worktree, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}");
		if (up.ok()) {
			upstream = up.stdout();
			var counts = git.run(worktree, "rev-list", "--left-right", "--count", "@{upstream}...HEAD");
			if (counts.ok()) {
				String[] parts = counts.stdout().split("\\s+");
				behind = Integer.parseInt(parts[0]);
				ahead = Integer.parseInt(parts[1]);
			}
		}
		// commits not on baseBranch: what a fresh push/PR would actually carry, independent of
		// whether this branch has ever been pushed (ahead/behind above is upstream-relative only)
		int aheadOfBase = -1;
		var baseCount = git.run(worktree, "rev-list", "--count", baseBranch + "..HEAD");
		if (baseCount.ok()) {
			aheadOfBase = Integer.parseInt(baseCount.stdout());
		}
		return new GitStatus(branch, dirty, upstream, ahead, behind, aheadOfBase);
	}

	/** Tracked changes vs HEAD plus content of untracked files. */
	public String diff(Path worktree) {
		StringBuilder out = new StringBuilder(git.runOrThrow(worktree, "diff", "HEAD").stdout());
		var untracked = git.runOrThrow(worktree, "ls-files", "--others", "--exclude-standard");
		for (String file : untracked.stdout().isBlank() ? List.<String>of() : untracked.stdout().lines().toList()) {
			var d = git.run(worktree, "diff", "--no-index", "/dev/null", file);
			out.append('\n').append(d.stdout());
		}
		return out.toString();
	}

	/** Diff of everything this branch would carry into a PR against baseBranch (merge-base diff). */
	public String diffVsBase(Path worktree, String baseBranch) {
		var result = git.run(worktree, "diff", baseBranch + "...HEAD");
		return result.ok() ? result.stdout() : "";
	}

	public List<LogEntry> log(Path worktree, int limit) {
		return parseLog(git.runOrThrow(worktree, "log", "--format=%h%x1f%s%x1f%an%x1f%ad", "--date=relative",
				"-" + limit));
	}

	/** Commits on this branch not on baseBranch, newest first — what a PR against it would contain. */
	public List<LogEntry> logVsBase(Path worktree, String baseBranch, int limit) {
		var result = git.run(worktree, "log", baseBranch + "..HEAD", "--format=%h%x1f%s%x1f%an%x1f%ad",
				"--date=relative", "-" + limit);
		return result.ok() ? parseLog(result) : List.of();
	}

	private List<LogEntry> parseLog(GitCommandRunner.GitResult result) {
		if (result.stdout().isBlank()) {
			return List.of();
		}
		return result.stdout().lines().map(line -> {
			String[] parts = line.split("\\u001f", -1);
			return new LogEntry(parts[0], parts[1], parts[2], parts[3]);
		}).toList();
	}

	public void commitAll(Path worktree, String message) {
		worktrees.commitAll(worktree, message);
	}

	public String headSha(Path worktree) {
		return git.runOrThrow(worktree, "rev-parse", "HEAD").stdout();
	}

	public String push(Path worktree, String branch) {
		var remotes = git.runOrThrow(worktree, "remote");
		if (remotes.stdout().isBlank()) {
			throw new GitException("this repository has no git remote configured");
		}
		git.runOrThrow(worktree, "push", "-u", "origin", branch);
		return "pushed " + branch + " to origin";
	}

	/** Pushes, then creates a PR via the gh CLI (uses the user's gh auth). Returns the PR URL. */
	public String createPullRequest(Path worktree, String branch, String title, String body) {
		push(worktree, branch);
		GhResult result = runGh(worktree, Duration.ofSeconds(60), "pr", "create",
				"--title", title, "--body", body, "--head", branch);
		if (!result.ok()) {
			throw new IllegalStateException("gh pr create failed: " + (result.stderr().isBlank() ? result.stdout() : result.stderr()));
		}
		// gh prints the PR URL as the last stdout line
		return result.stdout().lines().reduce((a, b) -> b).orElse(result.stdout());
	}

	/** Aggregate check-suite + merge status for a PR, via the same ambient gh auth used to create it. */
	public PrCheckResult checkPrStatus(Path worktree, String prUrl) {
		try {
			GhResult result = runGh(worktree, Duration.ofSeconds(30), "pr", "view", prUrl,
					"--json", "state,headRefOid,statusCheckRollup");
			if (!result.ok()) {
				throw new IllegalStateException("gh pr view failed: " + (result.stderr().isBlank() ? result.stdout() : result.stderr()));
			}
			JsonNode json = mapper.readTree(result.stdout());
			String headSha = json.path("headRefOid").asText(null);
			String state = json.path("state").asText("OPEN");
			if ("MERGED".equals(state)) {
				return new PrCheckResult(PrCheckStatus.MERGED, headSha);
			}
			if ("CLOSED".equals(state)) {
				return new PrCheckResult(PrCheckStatus.CLOSED, headSha);
			}
			return new PrCheckResult(aggregateChecks(json.path("statusCheckRollup")), headSha);
		} catch (RuntimeException e) {
			log.warn("gh pr view failed for {}: {}", prUrl, e.getMessage());
			return new PrCheckResult(PrCheckStatus.ERROR, null);
		}
	}

	// --- PR listing / resolution / review submission (docs/plan/phase-15-review-sessions.md) ---

	public record PrInfo(int number, String title, String headRefName, String baseRefName, String url,
						  String author, boolean isDraft) {
	}

	private static final String PR_LIST_FIELDS = "number,title,headRefName,baseRefName,url,author,isDraft";

	/** Open PRs for the review create-dialog's PR picker (proposal 1). */
	public List<PrInfo> listOpenPrs(Path repo) {
		GhResult result = runGh(repo, Duration.ofSeconds(30), "pr", "list", "--json", PR_LIST_FIELDS);
		if (!result.ok()) {
			throw new IllegalStateException("gh pr list failed: " + (result.stderr().isBlank() ? result.stdout() : result.stderr()));
		}
		try {
			List<PrInfo> prs = new ArrayList<>();
			for (JsonNode n : mapper.readTree(result.stdout())) {
				prs.add(toPrInfo(n));
			}
			return prs;
		} catch (JacksonException e) {
			throw new IllegalStateException("could not parse gh pr list output: " + e.getMessage());
		}
	}

	/**
	 * Lazily resolves the PR for a branch-first review session (proposal 1's fallback), or for
	 * {@code submit_pr_review} when the session wasn't created PR-first — empty (not an exception)
	 * when the branch has no open PR, so callers can surface their own readable message.
	 */
	public Optional<PrInfo> resolvePr(Path worktree, String branch) {
		validateBranchName(branch);
		GhResult result = runGh(worktree, Duration.ofSeconds(30), "pr", "view", "--json", PR_LIST_FIELDS, "--", branch);
		if (!result.ok()) {
			return Optional.empty();
		}
		try {
			return Optional.of(toPrInfo(mapper.readTree(result.stdout())));
		} catch (JacksonException e) {
			log.warn("could not parse gh pr view output for branch {}: {}", branch, e.getMessage());
			return Optional.empty();
		}
	}

	private static final Pattern SAFE_BRANCH_NAME = Pattern.compile("[A-Za-z0-9._/-]+");

	/**
	 * A branch name reaching {@code gh} argv here can originate from a PR's {@code headRefName}
	 * (GitHub-controlled, proposal 1's picker) or the remote-branch fallback picker — less trusted
	 * than a value the backend itself constructed. Reject anything that could be interpreted as a
	 * flag (leading {@code -}) or falls outside a normal branch-name charset before it ever reaches
	 * process argv, mirroring {@code GitWorktreeService}'s own validator for the same threat.
	 */
	private static void validateBranchName(String branch) {
		if (branch == null || branch.isBlank() || branch.startsWith("-") || !SAFE_BRANCH_NAME.matcher(branch).matches()) {
			throw new IllegalArgumentException("invalid branch name: " + branch);
		}
	}

	private PrInfo toPrInfo(JsonNode n) {
		return new PrInfo(n.path("number").asInt(), n.path("title").asText(""), n.path("headRefName").asText(""),
				n.path("baseRefName").asText(""), n.path("url").asText(""),
				n.path("author").path("login").asText(""), n.path("isDraft").asBoolean(false));
	}

	private static final Pattern GITHUB_PR_URL =
			Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/pull/(\\d+)/?");

	public record PrRef(String owner, String repo, int number) {
	}

	/**
	 * Parses a PR URL into owner/repo/number for the {@code gh api repos/{owner}/{repo}/pulls/{n}}
	 * endpoint — pure/static (unit-tested directly, no process execution). GitHub Enterprise/
	 * non-github.com remotes are out of scope, same as the rest of this class's gh integration.
	 */
	public static PrRef parsePrUrl(String prUrl) {
		Matcher m = GITHUB_PR_URL.matcher(prUrl == null ? "" : prUrl);
		if (!m.matches()) {
			throw new IllegalArgumentException(
					"cannot parse PR URL (expected https://github.com/<owner>/<repo>/pull/<number>): " + prUrl);
		}
		return new PrRef(m.group(1), m.group(2), Integer.parseInt(m.group(3)));
	}

	/**
	 * One PR in full, for starting a review from outside the dashboard: {@code state} is GitHub's
	 * OPEN/CLOSED/MERGED, {@code crossRepository} is true for a PR from a fork (whose head branch does
	 * not exist on {@code origin}), {@code files} the changed paths (GitHub returns at most the first
	 * 100, which is enough to pick a monorepo package but not a complete list).
	 */
	public record PrDetails(PrInfo pr, String state, boolean crossRepository, List<String> files) {
	}

	private static final String PR_VIEW_FIELDS = PR_LIST_FIELDS + ",state,isCrossRepository,files";

	/** {@code gh pr view} for a PR in {@code ref}'s repository, run from any directory (the repo is named explicitly). */
	public PrDetails viewPr(Path cwd, PrRef ref) {
		GhResult result = runGh(cwd, Duration.ofSeconds(30), "pr", "view", String.valueOf(ref.number()),
				"--repo", ref.owner() + "/" + ref.repo(), "--json", PR_VIEW_FIELDS);
		if (!result.ok()) {
			String why = result.stderr().isBlank() ? result.stdout() : result.stderr();
			throw new IllegalStateException("gh pr view failed: " + why.strip());
		}
		try {
			return parsePrDetails(mapper.readTree(result.stdout()));
		} catch (JacksonException e) {
			throw new IllegalStateException("could not parse gh pr view output: " + e.getMessage());
		}
	}

	PrDetails parsePrDetails(JsonNode n) {
		List<String> files = new ArrayList<>();
		for (JsonNode f : n.path("files")) {
			String path = f.path("path").asText("");
			if (!path.isBlank()) {
				files.add(path);
			}
		}
		return new PrDetails(toPrInfo(n), n.path("state").asText(""), n.path("isCrossRepository").asBoolean(false), files);
	}

	private static final Pattern REMOTE_SLUG = Pattern.compile(
			"^(?:[a-z][a-z0-9+.-]*://)?(?:[^@/\\s]+@)?[^/:\\s]+(?::\\d+)?[:/]([^/\\s]+)/([^/\\s]+?)(?:\\.git)?/?$",
			Pattern.CASE_INSENSITIVE);

	/**
	 * {@code owner/repo} of a clone's {@code origin} (SSH {@code git@host:o/r.git}, HTTPS, with or
	 * without {@code .git}), or empty when there is no origin or it is not shaped like that. Used to
	 * match a PR's repository to a local checkout; compare the result case-insensitively.
	 */
	public Optional<String> originSlug(Path repo) {
		GitCommandRunner.GitResult result = git.run(repo, "remote", "get-url", "origin");
		return result.ok() ? slugOf(result.stdout().strip()) : Optional.empty();
	}

	static Optional<String> slugOf(String remoteUrl) {
		Matcher m = REMOTE_SLUG.matcher(remoteUrl == null ? "" : remoteUrl);
		return m.matches() ? Optional.of(m.group(1) + "/" + m.group(2)) : Optional.empty();
	}

	public record ReviewComment(String path, int line, String side, Integer startLine, String body) {
	}

	/**
	 * Posts one review (summary + inline comments) atomically via {@code gh api …/reviews}
	 * (docs/plan/phase-15-review-sessions.md proposal 9) — the whole payload goes over stdin
	 * ({@code --input -}) since {@code -f} flags can't express the comments array. GitHub validates
	 * every inline comment's path/line against the diff; a 422 surfaces its body verbatim so the
	 * caller (the agent, via the tool error) can fix its line anchors and retry.
	 */
	public void submitPrReview(Path worktree, PrRef pr, String event, String body, List<ReviewComment> comments) {
		ObjectNode payload = mapper.createObjectNode();
		payload.put("event", event);
		payload.put("body", body == null ? "" : body);
		if (!comments.isEmpty()) {
			ArrayNode arr = payload.putArray("comments");
			for (ReviewComment c : comments) {
				ObjectNode cn = arr.addObject();
				cn.put("path", c.path());
				cn.put("line", c.line());
				cn.put("side", c.side() == null || c.side().isBlank() ? "RIGHT" : c.side());
				// GitHub 422s a start_line that doesn't precede line; a single-line comment simply omits it
				if (c.startLine() != null && c.startLine() < c.line()) {
					cn.put("start_line", c.startLine());
				}
				cn.put("body", c.body());
			}
		}
		String json;
		try {
			json = mapper.writeValueAsString(payload);
		} catch (JacksonException e) {
			throw new IllegalStateException("failed to serialize review payload: " + e.getMessage());
		}
		GhResult result = runGhWithStdin(worktree, Duration.ofSeconds(30), json, "api",
				"repos/" + pr.owner() + "/" + pr.repo() + "/pulls/" + pr.number() + "/reviews",
				"-X", "POST", "--input", "-");
		if (!result.ok()) {
			throw new IllegalStateException("gh api pulls/reviews failed: " + reviewFailureDetail(result));
		}
	}

	/**
	 * gh prints only "gh: Unprocessable Entity (HTTP 422)" on stderr; GitHub's actual reasons ("Review Can not request
	 * changes on your own pull request", a comment line outside the diff…) are in the JSON body on stdout. The agent needs
	 * those to correct its call, so they lead the message.
	 */
	String reviewFailureDetail(GhResult result) {
		List<String> reasons = new ArrayList<>();
		try {
			JsonNode body = result.stdout().isBlank() ? null : mapper.readTree(result.stdout());
			if (body != null) {
				for (JsonNode e : body.path("errors")) {
					String reason = e.isTextual() ? e.asText() : e.path("message").asText("");
					if (!reason.isBlank()) reasons.add(reason);
				}
				if (reasons.isEmpty() && !body.path("message").asText("").isBlank()) reasons.add(body.path("message").asText());
			}
		} catch (JacksonException e) {
			// not JSON — fall through to the raw output
		}
		String raw = result.stderr().isBlank() ? result.stdout() : result.stderr();
		return reasons.isEmpty() ? raw : String.join("; ", reasons) + " (" + raw + ")";
	}

	// --- gh process plumbing ---

	record GhResult(int exitCode, String stdout, String stderr) {
		boolean ok() {
			return exitCode == 0;
		}
	}

	/** Package-private: extracted from the create-PR/check-status paths above (proposal 11) so listOpenPrs/resolvePr/submitPrReview share the same process/timeout/error plumbing. */
	GhResult runGh(Path cwd, Duration timeout, String... args) {
		return runGhInternal(cwd, timeout, null, args);
	}

	GhResult runGhWithStdin(Path cwd, Duration timeout, String stdin, String... args) {
		return runGhInternal(cwd, timeout, stdin, args);
	}

	private GhResult runGhInternal(Path cwd, Duration timeout, String stdin, String... args) {
		List<String> command = new ArrayList<>();
		command.add("gh");
		command.addAll(List.of(args));
		try {
			Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
			if (stdin != null) {
				try (OutputStream out = process.getOutputStream()) {
					out.write(stdin.getBytes(StandardCharsets.UTF_8));
				}
			}
			String stdout = new String(process.getInputStream().readAllBytes()).strip();
			String stderr = new String(process.getErrorStream().readAllBytes()).strip();
			if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IllegalStateException("gh " + String.join(" ", args) + " timed out");
			}
			return new GhResult(process.exitValue(), stdout, stderr);
		} catch (IOException e) {
			throw new IllegalStateException(
					"gh CLI not available (" + e.getMessage() + "); install gh and run `gh auth login`");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while running gh " + String.join(" ", args));
		}
	}

	/**
	 * Each rollup entry is either a CheckRun (status/conclusion) or a legacy StatusContext
	 * (state only). An empty rollup means no checks have reported yet — treated as pending
	 * rather than success, since a repo with real CI will populate it shortly.
	 */
	private PrCheckStatus aggregateChecks(JsonNode rollup) {
		if (!rollup.isArray() || rollup.isEmpty()) {
			return PrCheckStatus.PENDING;
		}
		boolean anyPending = false;
		for (JsonNode check : rollup) {
			String conclusion = check.path("conclusion").asText(null);
			if (conclusion != null) {
				if (FAILING_CONCLUSIONS.contains(conclusion)) {
					return PrCheckStatus.FAILURE;
				}
				if (!"COMPLETED".equals(check.path("status").asText(null))) {
					anyPending = true;
				}
				continue;
			}
			String state = check.path("state").asText(null);
			if (FAILING_STATES.contains(state)) {
				return PrCheckStatus.FAILURE;
			}
			if (state == null || "PENDING".equals(state) || "EXPECTED".equals(state)) {
				anyPending = true;
			}
		}
		return anyPending ? PrCheckStatus.PENDING : PrCheckStatus.SUCCESS;
	}
}
