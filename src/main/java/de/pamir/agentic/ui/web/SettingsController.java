package de.pamir.agentic.ui.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.pamir.agentic.ui.config.AppProperties;
import de.pamir.agentic.ui.config.Settings;
import de.pamir.agentic.ui.config.SettingsPatch;
import de.pamir.agentic.ui.config.SettingsService;
import de.pamir.agentic.ui.integration.CodegraphService;
import de.pamir.agentic.ui.integration.GraphifyService;
import de.pamir.agentic.ui.integration.SerenaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Persisted, UI-editable settings (Settings dialog). Secrets (the Linear API key) never appear here. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	public record SettingsView(@JsonUnwrapped Settings settings, boolean linearApiKeyConfigured,
								boolean voyageConfigured, String codexPricing, String claudePricing) {
	}

	private final SettingsService settings;
	private final AppProperties props;
	private final SerenaService serena;
	private final GraphifyService graphify;
	private final CodegraphService codegraph;

	public SettingsController(SettingsService settings, AppProperties props, SerenaService serena,
							  GraphifyService graphify, CodegraphService codegraph) {
		this.settings = settings;
		this.props = props;
		this.serena = serena;
		this.graphify = graphify;
		this.codegraph = codegraph;
	}

	@GetMapping
	public SettingsView get() {
		return view();
	}

	@PatchMapping
	public SettingsView update(@RequestBody SettingsPatch patch) {
		// Validated against the patch's own (not-yet-persisted) values, not settings.current(), so a
		// bad root/uv path never lands in the DB — see SerenaService.validate's own contract (a blank
		// root always passes; clearing/disabling Serena must never be blocked).
		Settings current = settings.current();
		String uvPath = patch.mcpUvPath() != null ? patch.mcpUvPath() : current.mcpUvPath();
		if (patch.mcpSerenaRoot() != null) {
			serena.validate(patch.mcpSerenaRoot(), uvPath);
		}
		if (patch.mcpGraphifyRoot() != null) {
			graphify.validate(patch.mcpGraphifyRoot(), uvPath);
		}
		if (patch.mcpCodegraphRoot() != null) {
			codegraph.validate(patch.mcpCodegraphRoot());
		}
		validateCodeIntel(patch, current);
		settings.apply(patch);
		return view();
	}

	/**
	 * The code-intelligence selector is checked against the roots as they will be *after* the patch
	 * (phase-13 Step 1): a tool needs its root, and blanking the selected tool's root is refused.
	 * An install that never touched the selector (decision 12's computed default) is exempt from
	 * the second rule — blanking its Serena root just flips the default back to {@code none}.
	 */
	private void validateCodeIntel(SettingsPatch patch, Settings current) {
		String serenaRoot = patch.mcpSerenaRoot() != null ? patch.mcpSerenaRoot() : current.mcpSerenaRoot();
		String graphifyRoot = patch.mcpGraphifyRoot() != null ? patch.mcpGraphifyRoot() : current.mcpGraphifyRoot();
		String codegraphRoot = patch.mcpCodegraphRoot() != null ? patch.mcpCodegraphRoot() : current.mcpCodegraphRoot();
		String selector = patch.codeIntel() != null && !patch.codeIntel().isBlank()
				? patch.codeIntel()
				: settings.storedCodeIntel().orElseGet(() -> SettingsService.defaultCodeIntel(serenaRoot));
		SettingsService.validateCodeIntel(selector, serenaRoot, graphifyRoot, codegraphRoot);
	}

	private SettingsView view() {
		boolean apiKeyConfigured = props.linearApiKey() != null && !props.linearApiKey().isBlank();
		boolean voyageConfigured = props.voyageApiKey() != null && !props.voyageApiKey().isBlank();
		return new SettingsView(settings.current(), apiKeyConfigured, voyageConfigured, settings.pricingFor("codex"),
				settings.pricingFor("claude"));
	}
}
