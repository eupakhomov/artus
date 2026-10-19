package de.pamir.agentic.ui.tools;

import com.fasterxml.jackson.annotation.JsonIgnore;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * One tool-catalog entry (docs/plan/phase-26-tool-catalog.md decision 1). {@code kind} is {@code
 * mcp} (a named server: {@code server} is the Claude-shaped block, {@code command}/{@code args}/
 * {@code env} or {@code url}/{@code headers}) or {@code plugin} (a reference to an installed
 * plugin directory: {@code source} {@code claude}|{@code codex}, {@code path}). Secrets appear
 * only as {@code ${ENV_VAR}} references inside {@code server}.
 */
public record ToolEntry(String name, String kind, String description, JsonNode server, List<String> enabledTools,
						boolean preApprove, String source, String path) {

	public static final String MCP = "mcp";
	public static final String PLUGIN = "plugin";

	public ToolEntry {
		description = description == null ? "" : description;
		enabledTools = enabledTools == null ? List.of() : List.copyOf(enabledTools);
	}

	@JsonIgnore
	public boolean isPlugin() {
		return PLUGIN.equals(kind);
	}
}
