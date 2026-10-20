package de.pamir.agentic.ui.tools;

import de.pamir.agentic.ui.config.SettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The read-only import scan (phase 26 decision 3) and its secret redaction (decision 2); no DB involved. */
class ToolCatalogServiceTest {

	private final ObjectMapper mapper = new JsonMapper();

	private ToolCatalogService serviceAt(Path home) {
		return new ToolCatalogService(null, mapper, Map.<String, String>of()::get, home);
	}

	@Test
	void userLevelServersGetTheirSecretsReplacedByPlaceholders(@TempDir Path home) throws Exception {
		Files.writeString(home.resolve(".claude.json"), """
				{"mcpServers":{"bq":{"command":"npx","args":["bq"],"env":{"API_KEY":"sk-live-1","KEEP":"${ALREADY}"}},
				 "dd":{"type":"http","url":"https://x","headers":{"DD-API-KEY":"abc"}},
				 "junk":{"note":"neither command nor url"}}}""");

		List<ToolCatalogService.Candidate> found = serviceAt(home).scan();

		assertThat(found).extracting(ToolCatalogService.Candidate::name).containsExactly("bq", "dd");
		ToolCatalogService.Candidate bq = found.getFirst();
		assertThat(bq.server().path("env").path("API_KEY").asText()).isEqualTo("${API_KEY}");
		assertThat(bq.server().path("env").path("KEEP").asText()).isEqualTo("${ALREADY}");
		assertThat(bq.redacted()).containsExactly("API_KEY");
		assertThat(found.get(1).server().path("headers").path("DD-API-KEY").asText()).isEqualTo("${DD_DD_API_KEY}");
		assertThat(found.get(1).redacted()).containsExactly("DD_DD_API_KEY");
		assertThat(found.get(1).server().toString()).doesNotContain("abc");
	}

	@Test
	void findsBothCodexPluginLayouts(@TempDir Path home) throws Exception {
		Path snapshot = home.resolve(".codex/.tmp/plugins/plugins/datadog");
		Files.createDirectories(snapshot);
		Files.writeString(snapshot.resolve(".mcp.json"), "{\"mcpServers\":{\"datadog\":{\"type\":\"http\",\"url\":\"https://d\"}}}");
		Path cached = home.resolve(".codex/plugins/cache/openai-curated-remote/airtable/1.2.0");
		Files.createDirectories(cached);
		Files.writeString(cached.resolve(".mcp.json"), "{\"airtable\":{\"url\":\"https://a\"}}");
		Files.createDirectories(home.resolve(".codex/plugins/cache/openai-curated-remote/skills-only/0.1.0"));

		List<ToolCatalogService.Candidate> found = serviceAt(home).scan();

		assertThat(found).extracting(ToolCatalogService.Candidate::name).containsExactly("datadog", "airtable");
		assertThat(found.get(1).path()).isEqualTo(cached.toString());
		assertThat(found.get(1).servers()).containsExactly("airtable");
	}

	@Test
	void testingASkillsOnlyPluginReportsThatInsteadOfNothing(@TempDir Path dir) {
		String catalog = "[{\"name\":\"fd\",\"kind\":\"plugin\",\"source\":\"claude\",\"path\":\""
				+ dir.toString().replace("\\", "\\\\") + "\"}]";
		SettingsRepository repo = new SettingsRepository(null) {
			@Override
			public Optional<String> get(String key) {
				return Optional.of(catalog);
			}
		};
		ToolCatalogService service = new ToolCatalogService(repo, mapper, Map.<String, String>of()::get, dir);

		Map<String, McpProbe.Result> result = service.test("fd");

		assertThat(result).containsOnlyKeys("fd");
		assertThat(result.get("fd").ok()).isFalse();
		assertThat(result.get("fd").error()).contains("no MCP servers in " + dir);
	}
}
