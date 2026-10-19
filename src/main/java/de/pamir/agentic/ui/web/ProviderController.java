package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.session.ModelCatalog;
import de.pamir.agentic.ui.session.ProviderCapabilities;
import de.pamir.agentic.ui.session.ProviderCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-provider capability announcements, used by the create dialog/template editor to gate
 * controls (hide "plan mode", grey out "thinking", …) for a provider that has no session
 * running yet to ask via its live {@code ready} event — once a session exists, the widget uses
 * the authoritative live value from {@code SessionEntity.capabilities} instead (see
 * docs/PROTOCOL.md's capabilities handshake). Sourced from {@link ProviderCatalog} (each
 * adapter's own {@code capabilities.json} — no capability data is hardcoded here, per
 * docs/plan/phase-10-review-followups.md R1). {@code models} is sourced from {@link
 * ModelCatalog} rather than duplicated in {@code capabilities.json}, since backend-initiated
 * system turns (see SessionService/ReflectionService/ServiceDiscoveryService) need the same
 * per-provider/tier data.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

	private static final Logger log = LoggerFactory.getLogger(ProviderController.class);

	public record ModelInfo(String id, String label, String tier) {
	}

	public record Capabilities(List<String> permissionModes, boolean thinking, boolean effort, boolean planMode,
								boolean resume, boolean skills, boolean agents, boolean mcp, boolean interrupt,
								boolean fallbackModel, boolean updatedInput, boolean modelSwitch,
								List<ModelInfo> models,
								/** Whether the provider honours allowedTools/disallowedTools (gates the built-in tool toggles). */
								boolean toolPresets) {
	}

	public record ProviderView(String id, Capabilities capabilities) {
	}

	private static List<ModelInfo> models(String provider) {
		return ModelCatalog.models(provider).stream()
				.map(m -> new ModelInfo(m.id(), m.label(), m.tier()))
				.toList();
	}

	private static Capabilities toCapabilities(String providerId, ProviderCapabilities caps) {
		return new Capabilities(caps.permissionModes(), caps.thinking(), caps.effort(), caps.planMode(),
				caps.resume(), caps.skills(), caps.agents(), caps.mcp(), caps.interrupt(), caps.fallbackModel(),
				caps.updatedInput(), caps.modelSwitch(), models(providerId), caps.supports("disallowedTools"));
	}

	private final AppProperties props;
	private final ProviderCatalog catalog;

	public ProviderController(AppProperties props, ProviderCatalog catalog) {
		this.props = props;
		this.catalog = catalog;
	}

	@GetMapping
	public List<ProviderView> list() {
		List<ProviderView> views = new ArrayList<>();
		for (String id : props.providers().keySet()) {
			try {
				views.add(new ProviderView(id, toCapabilities(id, catalog.get(id))));
			} catch (RuntimeException e) {
				// A configured-but-unbuilt adapter (its capabilities.json is missing) simply
				// doesn't show up as a create option, rather than 500ing the whole listing —
				// see ProviderCatalog's Javadoc.
				log.warn("provider '{}' omitted from /api/providers: {}", id, e.getMessage());
			}
		}
		views.sort((a, b) -> a.id().compareTo(b.id()));
		return views;
	}
}
