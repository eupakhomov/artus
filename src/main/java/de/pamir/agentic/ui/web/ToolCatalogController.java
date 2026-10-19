package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.tools.McpProbe;
import de.pamir.agentic.ui.tools.ToolCatalogService;
import de.pamir.agentic.ui.tools.ToolEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** Tool catalog (Settings → Tools; docs/plan/phase-26-tool-catalog.md). Secrets are only {@code ${VAR}} references. */
@RestController
@RequestMapping("/api/tools")
public class ToolCatalogController {

	/** An entry plus the host environment variables it references that are not set (a create-dialog hint). */
	public record EntryView(ToolEntry entry, List<String> missingEnv) {
	}

	public record TestRequest(String name) {
	}

	private final ToolCatalogService service;

	public ToolCatalogController(ToolCatalogService service) {
		this.service = service;
	}

	@GetMapping("/catalog")
	public List<EntryView> catalog() {
		return view(service.entries());
	}

	@PutMapping("/catalog")
	public List<EntryView> replace(@RequestBody JsonNode body) {
		return view(service.replace(body));
	}

	@PostMapping("/test")
	public Map<String, McpProbe.Result> test(@RequestBody TestRequest request) {
		return service.test(request.name());
	}

	@GetMapping("/import")
	public List<ToolCatalogService.Candidate> importScan() {
		return service.scan();
	}

	private List<EntryView> view(List<ToolEntry> entries) {
		return entries.stream().map(e -> new EntryView(e, service.missingEnv(e))).toList();
	}
}
