package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.session.VisualizeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Phase 22 Visualize: generate / repair / read a session's code-logic diagram. */
@RestController
@RequestMapping("/api/sessions/{id}/visualize")
public class VisualizeController {

	public record GenerateRequest(String type) {
	}

	public record RepairRequest(String mermaid, String error) {
	}

	private final VisualizeService visualize;

	public VisualizeController(VisualizeService visualize) {
		this.visualize = visualize;
	}

	@GetMapping
	public VisualizeService.Status status(@PathVariable UUID id) {
		return visualize.status(id);
	}

	@PostMapping
	public VisualizeService.Diagram generate(@PathVariable UUID id, @RequestBody(required = false) GenerateRequest body) {
		return visualize.generate(id, body == null ? null : body.type());
	}

	@PostMapping("/repair")
	public VisualizeService.Diagram repair(@PathVariable UUID id, @RequestBody RepairRequest body) {
		return visualize.repair(id, body.mermaid(), body.error());
	}
}
