package de.pamir.agentic.ui.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure logic of the tool catalog (docs/plan/phase-26-tool-catalog.md): validation, {@code ${VAR}}
 * handling and resolution of a selection into the pieces a session needs. No I/O beyond reading
 * a plugin's {@code .mcp.json}; persistence is {@link ToolCatalogService}.
 */
public final class ToolCatalog {

	/** Managed MCP entries the backend layers itself — a catalog entry may not shadow them (decision 7). */
	public static final Set<String> RESERVED_NAMES = Set.of("memory", "linear", "serena", "graphify", "codegraph");

	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
	private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
	private static final String PLUGIN_ROOT_VAR = "CLAUDE_PLUGIN_ROOT";

	private ToolCatalog() {
	}

	/** What a selection resolves to; {@code servers} still holds unexpanded {@code ${VAR}} references. */
	public record Resolution(ObjectNode servers, List<String> allowedTools, List<String> pluginDirs) {
	}

	/** Parses and validates the stored/posted JSON array; throws {@link IllegalArgumentException} with the reason. */
	public static List<ToolEntry> parse(String json, ObjectMapper mapper) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		JsonNode root;
		try {
			root = mapper.readTree(json);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("tool catalog is not valid JSON: " + e.getMessage());
		}
		if (!root.isArray()) {
			throw new IllegalArgumentException("tool catalog must be a JSON array");
		}
		List<ToolEntry> out = new ArrayList<>();
		for (JsonNode n : root) {
			List<String> enabled = new ArrayList<>();
			if (n.path("enabledTools").isArray()) {
				n.get("enabledTools").forEach(t -> {
					if (!t.asText("").isBlank()) {
						enabled.add(t.asText().strip());
					}
				});
			}
			out.add(new ToolEntry(n.path("name").asText("").strip(), n.path("kind").asText(ToolEntry.MCP),
					n.path("description").asText(""), n.hasNonNull("server") ? n.get("server") : null, enabled,
					n.path("preApprove").asBoolean(false), n.hasNonNull("source") ? n.get("source").asText() : null,
					n.hasNonNull("path") ? n.get("path").asText() : null));
		}
		validate(out);
		return out;
	}

	public static void validate(List<ToolEntry> entries) {
		Set<String> seen = new HashSet<>();
		for (ToolEntry e : entries) {
			if (!NAME.matcher(e.name()).matches()) {
				throw new IllegalArgumentException("tool name '" + e.name()
						+ "' must be 1-64 letters, digits, '-' or '_' (it becomes the mcp__<name>__ tool prefix)");
			}
			if (RESERVED_NAMES.contains(e.name().toLowerCase())) {
				throw new IllegalArgumentException("tool name '" + e.name() + "' is reserved for a built-in integration");
			}
			if (!seen.add(e.name().toLowerCase())) {
				throw new IllegalArgumentException("duplicate tool name '" + e.name() + "'");
			}
			if (e.isPlugin()) {
				if (!"claude".equals(e.source()) && !"codex".equals(e.source())) {
					throw new IllegalArgumentException("tool '" + e.name() + "': plugin source must be 'claude' or 'codex'");
				}
				if (e.path() == null || e.path().isBlank() || !Path.of(e.path()).isAbsolute()) {
					throw new IllegalArgumentException("tool '" + e.name() + "': plugin path must be an absolute directory");
				}
			} else if (ToolEntry.MCP.equals(e.kind())) {
				JsonNode s = e.server();
				if (s == null || !s.isObject()) {
					throw new IllegalArgumentException("tool '" + e.name() + "': missing server block");
				}
				boolean stdio = s.hasNonNull("command");
				boolean http = s.hasNonNull("url");
				if (stdio == http) {
					throw new IllegalArgumentException("tool '" + e.name() + "': server needs exactly one of 'command' or 'url'");
				}
				checkVarSyntax(e.name(), s);
			} else {
				throw new IllegalArgumentException("tool '" + e.name() + "': unknown kind '" + e.kind() + "'");
			}
		}
	}

	/** A stray '${' that is not a well-formed reference would be sent verbatim to the server — reject it. */
	private static void checkVarSyntax(String name, JsonNode node) {
		if (node.isString()) {
			String text = node.asText();
			if (VAR.matcher(text).replaceAll("").contains("${")) {
				throw new IllegalArgumentException("tool '" + name + "': malformed ${VAR} reference in '" + text + "'");
			}
		}
		node.forEach(child -> checkVarSyntax(name, child));
	}

	/** Names of environment variables referenced anywhere in {@code node} (insertion order). */
	public static Set<String> referencedVars(JsonNode node) {
		Set<String> out = new LinkedHashSet<>();
		collectVars(node, out);
		return out;
	}

	private static void collectVars(JsonNode node, Set<String> out) {
		if (node == null) {
			return;
		}
		if (node.isString()) {
			Matcher m = VAR.matcher(node.asText());
			while (m.find()) {
				out.add(m.group(1));
			}
		}
		node.forEach(child -> collectVars(child, out));
	}

	/** Variables referenced by {@code node} that {@code env} does not define (or defines empty). */
	public static List<String> missingVars(JsonNode node, Function<String, String> env) {
		return referencedVars(node).stream().filter(v -> {
			String value = env.apply(v);
			return value == null || value.isEmpty();
		}).toList();
	}

	/**
	 * Copy of {@code node} with every {@code ${VAR}} that {@code env} defines replaced; an undefined
	 * variable is left as written (the caller decides whether that is an error).
	 */
	public static JsonNode expand(JsonNode node, Function<String, String> env, ObjectMapper mapper) {
		if (node == null) {
			return null;
		}
		if (node.isString()) {
			Matcher m = VAR.matcher(node.asText());
			StringBuilder sb = new StringBuilder();
			while (m.find()) {
				String value = env.apply(m.group(1));
				m.appendReplacement(sb, Matcher.quoteReplacement(value == null || value.isEmpty() ? m.group() : value));
			}
			m.appendTail(sb);
			return mapper.getNodeFactory().stringNode(sb.toString());
		}
		if (node.isObject()) {
			ObjectNode out = mapper.createObjectNode();
			node.properties().forEach(e -> out.set(e.getKey(), expand(e.getValue(), env, mapper)));
			return out;
		}
		if (node.isArray()) {
			ArrayNode out = mapper.createArrayNode();
			node.forEach(c -> out.add(expand(c, env, mapper)));
			return out;
		}
		return node;
	}

	/**
	 * Resolves the selected names against the catalog. A Claude session ({@code pluginDirsSupported})
	 * passes plugin directories to the SDK, which loads their servers and skills itself; any other
	 * provider gets the plugin's {@code .mcp.json} servers carried as ordinary entries. Throws
	 * {@link IllegalArgumentException} for an unknown name, an unreadable plugin, a name clash or an
	 * unset secret variable (naming the entry).
	 */
	public static Resolution resolve(List<String> selected, List<ToolEntry> catalog, boolean pluginDirsSupported,
									 boolean allowedToolsSupported, Function<String, String> env, ObjectMapper mapper) {
		Map<String, ToolEntry> byName = new LinkedHashMap<>();
		catalog.forEach(e -> byName.put(e.name(), e));
		ObjectNode servers = mapper.createObjectNode();
		List<String> allowed = new ArrayList<>();
		List<String> pluginDirs = new ArrayList<>();
		for (String name : new LinkedHashSet<>(selected)) {
			ToolEntry e = byName.get(name);
			if (e == null) {
				throw new IllegalArgumentException("unknown tool '" + name + "' (not in the tool catalog — Settings → Tools)");
			}
			if (e.isPlugin()) {
				Path dir = Path.of(e.path());
				if (!Files.isDirectory(dir)) {
					throw new IllegalArgumentException("tool '" + name + "': plugin directory not found: " + e.path());
				}
				if (pluginDirsSupported) {
					pluginDirs.add(e.path());
					continue;
				}
				for (var s : pluginServers(dir, mapper).properties()) {
					if (servers.has(s.getKey())) {
						throw new IllegalArgumentException("tool '" + name + "': server '" + s.getKey() + "' is already provided by another tool");
					}
					if (RESERVED_NAMES.contains(s.getKey().toLowerCase())) {
						throw new IllegalArgumentException("tool '" + name + "': server name '" + s.getKey() + "' is reserved");
					}
					servers.set(s.getKey(), withPluginRoot(s.getValue(), dir, mapper));
				}
			} else {
				if (servers.has(e.name())) {
					throw new IllegalArgumentException("tool '" + name + "': server '" + e.name() + "' is already provided by another tool");
				}
				ObjectNode block = (ObjectNode) e.server().deepCopy();
				if (!e.enabledTools().isEmpty()) {
					ArrayNode enabled = block.putArray("enabledTools");
					e.enabledTools().forEach(enabled::add);
				}
				servers.set(e.name(), block);
				if (e.preApprove() && allowedToolsSupported) {
					if (e.enabledTools().isEmpty()) {
						allowed.add("mcp__" + e.name());
					} else {
						e.enabledTools().forEach(t -> allowed.add("mcp__" + e.name() + "__" + t));
					}
				}
			}
			List<String> missing = missingVars(e.isPlugin() ? servers : servers.get(e.name()), env);
			if (!missing.isEmpty()) {
				throw new IllegalArgumentException("tool '" + name + "' needs environment variable"
						+ (missing.size() > 1 ? "s " : " ") + String.join(", ", missing) + " on the Artus host");
			}
		}
		return new Resolution(servers, allowed, pluginDirs);
	}

	/** The servers in a plugin's {@code .mcp.json}, in either the bare or the {@code mcpServers}-wrapped layout. */
	public static ObjectNode pluginServers(Path pluginDir, ObjectMapper mapper) {
		Path file = pluginDir.resolve(".mcp.json");
		if (!Files.isRegularFile(file)) {
			return mapper.createObjectNode();
		}
		try {
			JsonNode root = mapper.readTree(Files.readString(file));
			JsonNode servers = root.has("mcpServers") ? root.get("mcpServers") : root;
			ObjectNode out = mapper.createObjectNode();
			servers.properties().forEach(e -> {
				if (e.getValue().isObject()) {
					out.set(e.getKey(), e.getValue());
				}
			});
			return out;
		} catch (java.io.IOException | RuntimeException e) {
			throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage());
		}
	}

	private static JsonNode withPluginRoot(JsonNode server, Path dir, ObjectMapper mapper) {
		return expand(server, v -> PLUGIN_ROOT_VAR.equals(v) ? dir.toString() : null, mapper);
	}
}
