package de.pamir.agentic.ui.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCatalogTest {

	private final ObjectMapper mapper = new JsonMapper();

	private List<ToolEntry> parse(String json) {
		return ToolCatalog.parse(json, mapper);
	}

	private static final String BQ = """
			[{"name":"bq","kind":"mcp","description":"BigQuery","server":{"command":"npx","args":["bq-mcp"],
			  "env":{"TOKEN":"${BQ_TOKEN}"}},"enabledTools":["query"],"preApprove":true}]""";

	@Test
	void parsesAnMcpEntry() {
		ToolEntry e = parse(BQ).getFirst();

		assertThat(e.name()).isEqualTo("bq");
		assertThat(e.enabledTools()).containsExactly("query");
		assertThat(e.preApprove()).isTrue();
	}

	@Test
	void rejectsReservedDuplicateAndBadNames() {
		assertThatThrownBy(() -> parse("[{\"name\":\"linear\",\"server\":{\"url\":\"https://x\"}}]"))
				.hasMessageContaining("reserved");
		assertThatThrownBy(() -> parse("[{\"name\":\"a\",\"server\":{\"url\":\"https://x\"}},{\"name\":\"A\",\"server\":{\"url\":\"https://y\"}}]"))
				.hasMessageContaining("duplicate");
		assertThatThrownBy(() -> parse("[{\"name\":\"has space\",\"server\":{\"url\":\"https://x\"}}]"))
				.hasMessageContaining("must be 1-64");
	}

	@Test
	void requiresExactlyOneOfCommandAndUrl() {
		assertThatThrownBy(() -> parse("[{\"name\":\"a\",\"server\":{}}]")).hasMessageContaining("exactly one");
		assertThatThrownBy(() -> parse("[{\"name\":\"a\",\"server\":{\"command\":\"x\",\"url\":\"https://x\"}}]"))
				.hasMessageContaining("exactly one");
	}

	@Test
	void rejectsAMalformedVarReference() {
		assertThatThrownBy(() -> parse("[{\"name\":\"a\",\"server\":{\"url\":\"https://x\",\"headers\":{\"K\":\"${oops\"}}}]"))
				.hasMessageContaining("malformed");
	}

	@Test
	void expandsDefinedVarsAndLeavesUndefinedOnes() {
		JsonNode server = parse(BQ).getFirst().server();

		JsonNode withVar = ToolCatalog.expand(server, Map.of("BQ_TOKEN", "s3cret")::get, mapper);
		JsonNode without = ToolCatalog.expand(server, v -> null, mapper);

		assertThat(withVar.path("env").path("TOKEN").asText()).isEqualTo("s3cret");
		assertThat(without.path("env").path("TOKEN").asText()).isEqualTo("${BQ_TOKEN}");
	}

	@Test
	void resolveKeepsSecretsAsReferencesAndAddsPreApproval() {
		var res = ToolCatalog.resolve(List.of("bq"), parse(BQ), true, true, Map.of("BQ_TOKEN", "x")::get, mapper);

		assertThat(res.servers().path("bq").path("env").path("TOKEN").asText()).isEqualTo("${BQ_TOKEN}");
		assertThat(res.servers().path("bq").path("enabledTools")).hasSize(1);
		assertThat(res.allowedTools()).containsExactly("mcp__bq__query");
	}

	@Test
	void resolveSkipsPreApprovalWhereAllowedToolsAreUnsupported() {
		var res = ToolCatalog.resolve(List.of("bq"), parse(BQ), false, false, v -> "x", mapper);

		assertThat(res.allowedTools()).isEmpty();
	}

	@Test
	void anUnsetSecretFailsNamingTheEntryAndVariable() {
		assertThatThrownBy(() -> ToolCatalog.resolve(List.of("bq"), parse(BQ), true, true, v -> null, mapper))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'bq'").hasMessageContaining("BQ_TOKEN");
	}

	@Test
	void anUnknownNameFails() {
		assertThatThrownBy(() -> ToolCatalog.resolve(List.of("nope"), List.of(), true, true, v -> "x", mapper))
				.hasMessageContaining("unknown tool 'nope'");
	}

	@Test
	void aClaudeSessionGetsThePluginDirAndACodexSessionItsServers(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(".mcp.json"),
				"{\"mcpServers\":{\"datadog\":{\"type\":\"http\",\"url\":\"https://mcp.datadoghq.com/v1/mcp\"}}}");
		List<ToolEntry> catalog = parse("[{\"name\":\"dd\",\"kind\":\"plugin\",\"source\":\"codex\",\"path\":\""
				+ dir.toString().replace("\\", "\\\\") + "\"}]");

		var claude = ToolCatalog.resolve(List.of("dd"), catalog, true, true, v -> null, mapper);
		var codex = ToolCatalog.resolve(List.of("dd"), catalog, false, false, v -> null, mapper);

		assertThat(claude.pluginDirs()).containsExactly(dir.toString());
		assertThat(claude.servers()).isEmpty();
		assertThat(codex.pluginDirs()).isEmpty();
		assertThat(codex.servers().path("datadog").path("url").asText()).isEqualTo("https://mcp.datadoghq.com/v1/mcp");
	}

	@Test
	void readsTheBareAndTheWrappedPluginLayouts(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(".mcp.json"), "{\"playwright\":{\"command\":\"npx\",\"args\":[\"@playwright/mcp@latest\"]}}");

		assertThat(ToolCatalog.pluginServers(dir, mapper).properties()).extracting(Map.Entry::getKey)
				.containsExactly("playwright");
		assertThat(ToolCatalog.pluginServers(dir.resolve("missing"), mapper)).isEmpty();
	}

	@Test
	void pluginRootVariableIsFilledForCarriedServers(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(".mcp.json"), "{\"s\":{\"command\":\"${CLAUDE_PLUGIN_ROOT}/bin/run\"}}");
		List<ToolEntry> catalog = parse("[{\"name\":\"p\",\"kind\":\"plugin\",\"source\":\"claude\",\"path\":\""
				+ dir.toString().replace("\\", "\\\\") + "\"}]");

		var res = ToolCatalog.resolve(List.of("p"), catalog, false, false, v -> null, mapper);

		assertThat(res.servers().path("s").path("command").asText()).isEqualTo(dir + "/bin/run");
	}
}
