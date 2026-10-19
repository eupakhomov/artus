package de.pamir.agentic.ui.tools;

import de.pamir.agentic.ui.config.SettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Persisted tool catalog (docs/plan/phase-26-tool-catalog.md): the {@code tools.catalog} setting,
 * its test probe and the read-only import scan. Non-secret only — secrets are {@code ${VAR}}
 * references expanded from this process's environment (see {@link ToolCatalog}).
 */
@Service
public class ToolCatalogService {

	private static final Logger log = LoggerFactory.getLogger(ToolCatalogService.class);
	static final String KEY = "tools.catalog";
	private static final Duration TEST_BUDGET = Duration.ofSeconds(30);

	private final SettingsRepository repo;
	private final ObjectMapper mapper;
	private final Function<String, String> env;
	private final Path home;

	@Autowired
	public ToolCatalogService(SettingsRepository repo, ObjectMapper mapper) {
		this(repo, mapper, System::getenv, Path.of(System.getProperty("user.home")));
	}

	ToolCatalogService(SettingsRepository repo, ObjectMapper mapper, Function<String, String> env, Path home) {
		this.repo = repo;
		this.mapper = mapper;
		this.env = env;
		this.home = home;
	}

	public Function<String, String> env() {
		return env;
	}

	public List<ToolEntry> entries() {
		String raw = repo.get(KEY).orElse("");
		try {
			return ToolCatalog.parse(raw, mapper);
		} catch (IllegalArgumentException e) {
			log.warn("stored tool catalog is invalid, treating as empty: {}", e.getMessage());
			return List.of();
		}
	}

	/** Replaces the catalog; throws {@link IllegalArgumentException} (400) on any invalid entry. */
	public List<ToolEntry> replace(JsonNode json) {
		List<ToolEntry> parsed = ToolCatalog.parse(json.toString(), mapper);
		repo.set(KEY, mapper.valueToTree(parsed).toString());
		return parsed;
	}

	/** Names of environment variables an entry references that this host has not set. */
	public List<String> missingEnv(ToolEntry e) {
		if (e.isPlugin() || e.server() == null) {
			return List.of();
		}
		return ToolCatalog.missingVars(e.server(), env);
	}

	public ToolCatalog.Resolution resolve(List<String> selected, boolean pluginDirsSupported, boolean allowedToolsSupported) {
		return ToolCatalog.resolve(selected, entries(), pluginDirsSupported, allowedToolsSupported, env, mapper);
	}

	/** Starts one catalog server and lists its tools; a plugin tests each of its servers. */
	public Map<String, McpProbe.Result> test(String name) {
		ToolEntry e = entries().stream().filter(x -> x.name().equals(name)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown tool '" + name + "'"));
		McpProbe probe = new McpProbe(mapper, TEST_BUDGET);
		Map<String, McpProbe.Result> out = new java.util.LinkedHashMap<>();
		if (e.isPlugin()) {
			ObjectNode servers = ToolCatalog.pluginServers(Path.of(e.path()), mapper);
			for (var s : servers.properties()) {
				out.put(s.getKey(), probeExpanded(probe, s.getValue(), Path.of(e.path())));
			}
			return out;
		}
		List<String> missing = missingEnv(e);
		if (!missing.isEmpty()) {
			out.put(e.name(), new McpProbe.Result(false, List.of(), "environment variable "
					+ String.join(", ", missing) + " is not set on the Artus host"));
			return out;
		}
		out.put(e.name(), probe.probe(ToolCatalog.expand(e.server(), env, mapper)));
		return out;
	}

	private McpProbe.Result probeExpanded(McpProbe probe, JsonNode server, Path pluginDir) {
		JsonNode withRoot = ToolCatalog.expand(server, v -> "CLAUDE_PLUGIN_ROOT".equals(v) ? pluginDir.toString() : env.apply(v), mapper);
		return probe.probe(withRoot);
	}

	/** One importable thing found on this host: a user-level MCP server or an installed plugin. */
	public record Candidate(String name, String kind, String source, String path, JsonNode server, List<String> servers,
							String origin) {
	}

	/**
	 * Read-only scan (decision 3): {@code ~/.claude.json} {@code mcpServers}, Claude plugins from
	 * {@code ~/.claude/plugins/installed_plugins.json}, Codex plugins under {@code ~/.codex/.tmp/plugins/plugins}
	 * (the marketplace snapshot) and {@code ~/.codex/plugins/cache}. Nothing is written.
	 */
	public List<Candidate> scan() {
		List<Candidate> out = new ArrayList<>();
		scanClaudeUserServers(out);
		scanClaudePlugins(out);
		scanCodexPlugins(out, home.resolve(".codex/.tmp/plugins/plugins"), "codex marketplace");
		return out;
	}

	private JsonNode readJson(Path file) {
		try {
			return Files.isRegularFile(file) ? mapper.readTree(Files.readString(file)) : null;
		} catch (IOException | RuntimeException e) {
			log.warn("import scan: cannot read {}: {}", file, e.getMessage());
			return null;
		}
	}

	private void scanClaudeUserServers(List<Candidate> out) {
		JsonNode root = readJson(home.resolve(".claude.json"));
		if (root == null) {
			return;
		}
		root.path("mcpServers").properties().forEach(e -> {
			JsonNode s = e.getValue();
			if (s.isObject() && (s.hasNonNull("command") || s.hasNonNull("url"))) {
				out.add(new Candidate(e.getKey(), ToolEntry.MCP, "claude", null, s, List.of(e.getKey()), "~/.claude.json"));
			}
		});
	}

	private void scanClaudePlugins(List<Candidate> out) {
		JsonNode root = readJson(home.resolve(".claude/plugins/installed_plugins.json"));
		if (root == null) {
			return;
		}
		root.path("plugins").properties().forEach(e -> {
			String name = e.getKey().contains("@") ? e.getKey().substring(0, e.getKey().indexOf('@')) : e.getKey();
			for (JsonNode install : e.getValue()) {
				String path = install.path("installPath").asText("");
				if (path.isEmpty() || !Files.isDirectory(Path.of(path))) {
					continue;
				}
				List<String> servers = serverNames(Path.of(path));
				// a plugin without an MCP server still brings skills/commands, which is why it is listed
				out.add(new Candidate(name, ToolEntry.PLUGIN, "claude", path, null, servers, e.getKey()));
				break;
			}
		});
	}

	private void scanCodexPlugins(List<Candidate> out, Path dir, String origin) {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> children = Files.list(dir)) {
			children.sorted().filter(Files::isDirectory).forEach(p -> {
				List<String> servers = serverNames(p);
				if (!servers.isEmpty()) {
					out.add(new Candidate(p.getFileName().toString(), ToolEntry.PLUGIN, "codex", p.toString(), null,
							servers, origin));
				}
			});
		} catch (IOException e) {
			log.warn("import scan: cannot list {}: {}", dir, e.getMessage());
		}
	}

	private List<String> serverNames(Path pluginDir) {
		try {
			ArrayNode names = mapper.createArrayNode();
			ToolCatalog.pluginServers(pluginDir, mapper).properties().forEach(e -> names.add(e.getKey()));
			List<String> out = new ArrayList<>();
			names.forEach(n -> out.add(n.asText()));
			return out;
		} catch (IllegalArgumentException e) {
			return List.of();
		}
	}
}
