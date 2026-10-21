package de.pamir.agentic.ui.integration;

import tools.jackson.databind.JsonNode;
import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.session.ModelCatalog;
import de.pamir.agentic.ui.session.SystemTurnClient;
import de.pamir.agentic.ui.session.SystemTurnLane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Generates a branch name + kickoff prompt from a ticket, via the interactive system session
 * (the cheap tier of {@link SettingsService#defaultProvider()}'s model catalog + the Linear MCP
 * server). Provider-specific today (Linear only); named generically since "ticket system"
 * naming, not "linear", is what should survive a second provider later.
 */
@Service
public class TicketImportService {

	private static final Logger log = LoggerFactory.getLogger(TicketImportService.class);

	private static final Duration TIMEOUT = Duration.ofSeconds(45);
	/** Cap on the inlined issue text (description + comments) in the API-key import prompt. */
	static final int MAX_INLINED_CHARS = 20_000;

	/** Per-tier phrasing for the recommendedModel guidance clause; a tier with no model in the
	 * catalog (e.g. a provider offering only "cheap"/"standard") is simply skipped. */
	private static final Map<String, String> TIER_COMPLEXITY_PHRASE = Map.of(
			"cheap", "trivial/mechanical changes (typo, copy tweak, a config value, a tiny well-defined fix)",
			"standard", "typical, well-scoped feature or bug work (the default for most tickets)",
			"premium", "complex, ambiguous, or high-risk work (architecture/design changes, tricky concurrency "
					+ "or security issues, large multi-system refactors)");

	public record TicketImportResult(String branchName, String prompt, String recommendedModel, String ticketRef) {
	}

	public record TicketSummary(String ref, String title, String status) {
	}

	/** {@code cached}: true when served from {@link TicketCache} without a system turn. */
	public record TicketList(List<TicketSummary> tickets, Instant fetchedAt, boolean cached) {
	}

	private final SystemTurnClient systemTurnClient;
	private final AppProperties props;
	private final SettingsService settings;
	private final TicketCache cache;
	private final LinearApiClient linearApi; // null = no direct-API fast path (tests, or never wired)

	// Explicit @Autowired: with the test-only constructor below also present, Spring can't apply
	// its usual "exactly one constructor" auto-detection — see ProviderCatalog for the same pattern.
	@Autowired
	public TicketImportService(SystemTurnClient systemTurnClient, AppProperties props, SettingsService settings,
							   LinearApiClient linearApi) {
		this(systemTurnClient, props, settings, linearApi, Clock.systemUTC());
	}

	TicketImportService(SystemTurnClient systemTurnClient, AppProperties props, SettingsService settings) {
		this(systemTurnClient, props, settings, null, Clock.systemUTC());
	}

	/** Test-only: injects a fixed/controllable clock so TTL expiry can be tested deterministically. */
	TicketImportService(SystemTurnClient systemTurnClient, AppProperties props, SettingsService settings, Clock clock) {
		this(systemTurnClient, props, settings, null, clock);
	}

	TicketImportService(SystemTurnClient systemTurnClient, AppProperties props, SettingsService settings,
						LinearApiClient linearApi, Clock clock) {
		this.linearApi = linearApi;
		this.systemTurnClient = systemTurnClient;
		this.props = props;
		this.settings = settings;
		this.cache = new TicketCache(clock);
	}

	public boolean enabled() {
		return (props.linearApiKey() != null && !props.linearApiKey().isBlank()) || settings.current().linearOAuthEnabled();
	}

	public TicketImportResult importTicket(String ticketRef) {
		if (!enabled()) {
			throw new IllegalStateException(
					"Linear integration is not configured (set ARTUS_LINEAR_API_KEY, or enable OAuth in Settings)");
		}
		if (ticketRef == null || ticketRef.isBlank()) {
			throw new IllegalArgumentException("ticketRef is required");
		}
		String key = ticketRef.strip().toUpperCase(Locale.ROOT);
		TicketImportResult cached = cache.cachedImport(key);
		if (cached != null) {
			return cached;
		}
		TicketImportResult result = fetchTicketImport(ticketRef);
		cache.cacheImport(key, result);
		String canonicalKey = result.ticketRef();
		if (canonicalKey != null && !canonicalKey.equals(key)) {
			// A URL and its short identifier both resolve to the same ticket — cache both spellings
			// so the second one (e.g. picking the same ticket again from the list) is also a hit.
			cache.cacheImport(canonicalKey, result);
		}
		return result;
	}

	private TicketImportResult fetchTicketImport(String ticketRef) {
		String spec = settings.current().ticketImportSpec();
		String guidance = spec.isBlank() ? "" : "Follow these additional guidelines when choosing the branch name "
				+ "and/or writing the prompt: " + spec.strip() + " ";
		List<ModelCatalog.ModelInfo> models = ModelCatalog.models(settings.current().defaultProvider()).stream()
				.filter(m -> !"extended".equals(m.tier())).toList();
		Set<String> validModels = models.stream().map(ModelCatalog.ModelInfo::id).collect(Collectors.toSet());
		LinearApiClient.Issue issue = fetchIssueDirect(ticketRef);
		if (issue != null) {
			// API-key fast path: the issue and its comments are inlined, so this is a single LLM
			// call with no MCP tool round trips. The canonical identifier is known, so set it
			// rather than hoping the model echoes it.
			String inlined = ("Here is the complete Linear issue \"%s\", with all of its comments (oldest first):\n\n%s\n\n"
					+ "Do NOT call any tools — everything you need is above. Read the comment thread for any clarifications, scope changes, decisions, or "
					+ "blocking questions/answers raised after the ticket was created — the description alone may "
					+ "be stale — and factor anything relevant into the prompt you write. "
					+ "%s"
					+ "Then respond with ONLY a single JSON object — no markdown fences, no commentary — "
					+ "of the form: {\"branchName\": \"kebab-case-git-safe-branch-name\", \"prompt\": \"a clear, "
					+ "actionable initial instruction for an engineer/agent implementing this ticket, including its "
					+ "key requirements\"%s}. branchName must be short, "
					+ "kebab-case, git-ref-safe, and include the ticket identifier, e.g. \"ENG-123-fix-login-bug\" OR "
					+ "\"eng-123-fix-login-bug\".%s")
					.formatted(issue.identifier(), issue.render(MAX_INLINED_CHARS), guidance,
							recommendedModelField(models), recommendedModelGuidance(models));
			TicketImportResult parsed = parse(systemTurnClient.json(inlined, SystemTurnLane.INTERACTIVE, TIMEOUT), validModels);
			return new TicketImportResult(parsed.branchName(), parsed.prompt(), parsed.recommendedModel(),
					issue.identifier().toUpperCase(Locale.ROOT));
		}
		String prompt = ("You have access to Linear via MCP tools. Fetch the Linear issue referenced by \"%s\" "
				+ "(it may be a short identifier like ENG-123 or a full Linear issue URL), along with its "
				+ "comments. Read the comment thread for any clarifications, scope changes, decisions, or "
				+ "blocking questions/answers raised after the ticket was created — the description alone may "
				+ "be stale — and factor anything relevant into the prompt you write. "
				+ "%s"
				+ "Then respond with ONLY a single JSON object — no markdown fences, no commentary — "
				+ "of the form: {\"branchName\": \"kebab-case-git-safe-branch-name\", \"prompt\": \"a clear, "
				+ "actionable initial instruction for an engineer/agent implementing this ticket, including its "
				+ "key requirements\"%s}. branchName must be short, "
				+ "kebab-case, git-ref-safe, and include the ticket identifier, e.g. \"ENG-123-fix-login-bug\" OR "
				+ "\"eng-123-fix-login-bug\".%s")
				.formatted(ticketRef.strip(), guidance, recommendedModelField(models), recommendedModelGuidance(models));
		return parse(systemTurnClient.json(prompt, SystemTurnLane.INTERACTIVE, TIMEOUT), validModels);
	}

	/** The issue via the direct Linear API, or null (OAuth mode / no client / API failure → system-turn path). */
	private LinearApiClient.Issue fetchIssueDirect(String ticketRef) {
		if (linearApi == null || !linearApi.configured()) {
			return null;
		}
		try {
			return linearApi.fetchIssue(ticketRef);
		} catch (LinearApiClient.LinearApiException e) {
			log.warn("direct Linear API issue fetch failed, falling back to the system-turn path: {}", e.getMessage());
			return null;
		}
	}

	private static String recommendedModelField(List<ModelCatalog.ModelInfo> models) {
		if (models.isEmpty()) {
			return "";
		}
		String idsPiped = models.stream().map(ModelCatalog.ModelInfo::id).collect(Collectors.joining("|"));
		return ", \"recommendedModel\": \"" + idsPiped + "\"";
	}

	private static String recommendedModelGuidance(List<ModelCatalog.ModelInfo> models) {
		if (models.isEmpty()) {
			return "";
		}
		String idsQuoted = models.stream().map(m -> "\"" + m.id() + "\"").collect(Collectors.joining(", "));
		List<String> clauses = new ArrayList<>();
		for (ModelCatalog.ModelInfo m : models) {
			String phrase = TIER_COMPLEXITY_PHRASE.get(m.tier());
			if (phrase != null) {
				clauses.add("\"" + m.id() + "\" for " + phrase);
			}
		}
		return " recommendedModel must be exactly one of " + idsQuoted
				+ ", chosen by the ticket's apparent complexity: " + String.join("; ", clauses) + ".";
	}

	/**
	 * @param refresh bypasses the 15-minute cache and forces a fresh system turn.
	 */
	public TicketList listMyTickets(boolean refresh) {
		if (!enabled()) {
			throw new IllegalStateException(
					"Linear integration is not configured (set ARTUS_LINEAR_API_KEY, or enable OAuth in Settings)");
		}
		boolean oauthMode = settings.current().linearOAuthEnabled();
		if (!refresh) {
			TicketList fresh = cache.freshList(oauthMode);
			if (fresh != null) {
				return fresh;
			}
		}
		return cache.fetchList(oauthMode, this::fetchTicketList);
	}

	private List<TicketSummary> fetchTicketList() {
		if (linearApi != null && linearApi.configured()) {
			try {
				return linearApi.listAssigned().stream()
						.map(i -> new TicketSummary(i.identifier(), i.title(), i.state())).toList();
			} catch (LinearApiClient.LinearApiException e) {
				log.warn("direct Linear API ticket list failed, falling back to the system-turn path: {}", e.getMessage());
			}
		}
		String prompt = "You have access to Linear via MCP tools. List up to 20 issues currently assigned to me "
				+ "(the authenticated Linear user), ordered by most recently updated first, excluding any issue "
				+ "in a completed or canceled state. Then respond with ONLY a JSON array — no markdown fences, "
				+ "no commentary — of objects of the form {\"ref\": \"ENG-123\", \"title\": \"...\", \"status\": "
				+ "\"...\"}. If there are no matching issues, respond with an empty array [].";
		return parseTickets(systemTurnClient.json(prompt, SystemTurnLane.INTERACTIVE, TIMEOUT));
	}

	// package-private (not private): unit-tested directly without mocking the system-session
	// dependencies parse()/parseTickets() don't touch — see docs/plan/phase-9-production-hardening.md T1
	List<TicketSummary> parseTickets(JsonNode node) {
		if (!node.isArray()) {
			node = node.path("tickets");
		}
		List<TicketSummary> tickets = new ArrayList<>();
		for (JsonNode item : node) {
			String ref = item.path("ref").asText("").strip();
			String title = item.path("title").asText("").strip();
			if (ref.isBlank() || title.isBlank()) {
				continue;
			}
			tickets.add(new TicketSummary(ref, title, item.path("status").asText("").strip()));
		}
		return tickets;
	}

	static TicketImportResult parse(JsonNode node, Set<String> validModels) {
		String branchName = sanitizeBranch(node.path("branchName").asText(""));
		String promptText = node.path("prompt").asText("").strip();
		if (branchName.isBlank() || promptText.isBlank()) {
			throw new IllegalStateException("ticket import response missing branchName/prompt: " + node);
		}
		String recommendedModel = node.path("recommendedModel").asText("").strip().toLowerCase(Locale.ROOT);
		if (!validModels.contains(recommendedModel)) {
			recommendedModel = null;
		}
		String canonicalRef = node.path("ticketRef").asText("").strip().toUpperCase(Locale.ROOT);
		return new TicketImportResult(branchName, promptText, recommendedModel, canonicalRef.isBlank() ? null : canonicalRef);
	}

	static String sanitizeBranch(String s) {
		String out = s.strip().replaceAll("[^A-Za-z0-9/_-]", "-").replaceAll("-{2,}", "-");
		out = out.replaceAll("^[-/]+", "").replaceAll("[-/]+$", "");
		return out.length() > 60 ? out.substring(0, 60) : out;
	}
}
