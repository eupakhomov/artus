import { useEffect, useRef, useState } from 'react';
import { api } from '../api/rest';
import { getFontSize, getTheme, setFontSize, setTheme, type FontSize, type Theme } from '../prefs';
import type { AuthStatus, CodeIntel, ProviderView, Settings, SlackStatus } from '../protocol';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import ToolCatalogSettings from './ToolCatalogSettings';

export default function SettingsDialog({ onClose }: { onClose: () => void }) {
  const [theme, setThemeState] = useState<Theme>(getTheme());
  const [fontSize, setFontSizeState] = useState<FontSize>(getFontSize());
  const [settings, setSettings] = useState<Settings | null>(null);
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [specDraft, setSpecDraft] = useState('');
  const [ecosystemRootDraft, setEcosystemRootDraft] = useState('');
  const [monorepoGlobsDraft, setMonorepoGlobsDraft] = useState('');
  const [pollIntervalDraft, setPollIntervalDraft] = useState('');
  const [skillsRootDraft, setSkillsRootDraft] = useState('');
  const [agentsRootDraft, setAgentsRootDraft] = useState('');
  const [syncIntervalDraft, setSyncIntervalDraft] = useState('');
  const [codexPricingDraft, setCodexPricingDraft] = useState('');
  const [codexPricingError, setCodexPricingError] = useState('');
  const [claudePricingDraft, setClaudePricingDraft] = useState('');
  const [claudePricingError, setClaudePricingError] = useState('');
  const [memoryRootDraft, setMemoryRootDraft] = useState('');
  const [memorySyncIntervalDraft, setMemorySyncIntervalDraft] = useState('');
  const [memoryRetentionDraft, setMemoryRetentionDraft] = useState('');
  const [serviceDiscoveryStalenessDraft, setServiceDiscoveryStalenessDraft] = useState('');
  const [contextWarnPercentDraft, setContextWarnPercentDraft] = useState('');
  const [linearToolsDraft, setLinearToolsDraft] = useState('');
  const [mcpSerenaRootDraft, setMcpSerenaRootDraft] = useState('');
  const [mcpUvPathDraft, setMcpUvPathDraft] = useState('');
  const [mcpGraphifyRootDraft, setMcpGraphifyRootDraft] = useState('');
  const [mcpCodegraphRootDraft, setMcpCodegraphRootDraft] = useState('');
  const [mcpSerenaError, setMcpSerenaError] = useState('');
  const [authStatus, setAuthStatus] = useState<AuthStatus | null>(null);
  const [slackStatus, setSlackStatus] = useState<SlackStatus | null>(null);
  /** the graphify root save doubles as its first `uv` env sync (up to 180 s) — pulse the input meanwhile */
  const [mcpGraphifySaving, setMcpGraphifySaving] = useState(false);

  useEffect(() => {
    api.getSettings().then((s) => {
      setSettings(s);
      setSpecDraft(s.ticketImportSpec);
      setEcosystemRootDraft(s.ecosystemRoot);
      setMonorepoGlobsDraft(s.monorepoServiceGlobs);
      setPollIntervalDraft(String(s.prCheckPollIntervalSeconds));
      setSkillsRootDraft(s.librarySkillsRoot);
      setAgentsRootDraft(s.libraryAgentsRoot);
      setSyncIntervalDraft(String(s.librarySyncIntervalMinutes));
      setCodexPricingDraft(s.codexPricing);
      setClaudePricingDraft(s.claudePricing);
      setMemoryRootDraft(s.memoryRoot);
      setMemorySyncIntervalDraft(String(s.memorySyncIntervalMinutes));
      setMemoryRetentionDraft(String(s.memoryRetentionDays));
      setServiceDiscoveryStalenessDraft(String(s.serviceDiscoveryStalenessDays));
      setContextWarnPercentDraft(String(s.contextWarnPercent));
      setLinearToolsDraft(s.linearAllowedTools);
      setMcpSerenaRootDraft(s.mcpSerenaRoot);
      setMcpUvPathDraft(s.mcpUvPath);
      setMcpGraphifyRootDraft(s.mcpGraphifyRoot);
      setMcpCodegraphRootDraft(s.mcpCodegraphRoot);
    }).catch(() => setSettings(null));
    api.listProviders().then(setProviders).catch(() => setProviders([]));
    api.authStatus().then(setAuthStatus).catch(() => setAuthStatus(null));
    api.slackStatus().then(setSlackStatus).catch(() => setSlackStatus(null));
  }, []);

  const toggleOAuth = () => {
    if (!settings) return;
    const next = { ...settings, linearOAuthEnabled: !settings.linearOAuthEnabled };
    setSettings(next);
    void api.updateSettings({ linearOAuthEnabled: next.linearOAuthEnabled }).catch(() => setSettings(settings));
  };

  const toggleAuthRequired = () => {
    if (!settings) return;
    const next = { ...settings, authTokenRequired: !settings.authTokenRequired };
    setSettings(next);
    void api.updateSettings({ authTokenRequired: next.authTokenRequired }).catch(() => setSettings(settings));
  };

  const togglePrChecks = () => {
    if (!settings) return;
    const next = { ...settings, prChecksEnabled: !settings.prChecksEnabled };
    setSettings(next);
    void api.updateSettings({ prChecksEnabled: next.prChecksEnabled }).catch(() => setSettings(settings));
  };

  const savePollInterval = () => {
    if (!settings) return;
    const seconds = Number(pollIntervalDraft);
    if (!Number.isFinite(seconds) || seconds === settings.prCheckPollIntervalSeconds) return;
    void api.updateSettings({ prCheckPollIntervalSeconds: seconds })
      .then((s) => { setSettings(s); setPollIntervalDraft(String(s.prCheckPollIntervalSeconds)); })
      .catch(() => setPollIntervalDraft(String(settings.prCheckPollIntervalSeconds)));
  };

  const saveSpec = () => {
    if (!settings || specDraft === settings.ticketImportSpec) return;
    void api.updateSettings({ ticketImportSpec: specDraft })
      .then(setSettings)
      .catch(() => setSpecDraft(settings.ticketImportSpec));
  };

  const saveEcosystemRoot = () => {
    if (!settings || ecosystemRootDraft === settings.ecosystemRoot) return;
    void api.updateSettings({ ecosystemRoot: ecosystemRootDraft })
      .then(setSettings)
      .catch(() => setEcosystemRootDraft(settings.ecosystemRoot));
  };

  const toggleMonorepoDetection = () => {
    if (!settings) return;
    const next = { ...settings, monorepoDetectionEnabled: !settings.monorepoDetectionEnabled };
    setSettings(next);
    void api.updateSettings({ monorepoDetectionEnabled: next.monorepoDetectionEnabled }).catch(() => setSettings(settings));
  };

  const saveMonorepoGlobs = () => {
    if (!settings || monorepoGlobsDraft === settings.monorepoServiceGlobs) return;
    void api.updateSettings({ monorepoServiceGlobs: monorepoGlobsDraft })
      .then(setSettings)
      .catch(() => setMonorepoGlobsDraft(settings.monorepoServiceGlobs));
  };

  const saveSkillsRoot = () => {
    if (!settings || skillsRootDraft === settings.librarySkillsRoot) return;
    void api.updateSettings({ librarySkillsRoot: skillsRootDraft })
      .then((s) => { setSettings(s); setSkillsRootDraft(s.librarySkillsRoot); })
      .catch(() => setSkillsRootDraft(settings.librarySkillsRoot));
  };

  const saveAgentsRoot = () => {
    if (!settings || agentsRootDraft === settings.libraryAgentsRoot) return;
    void api.updateSettings({ libraryAgentsRoot: agentsRootDraft })
      .then((s) => { setSettings(s); setAgentsRootDraft(s.libraryAgentsRoot); })
      .catch(() => setAgentsRootDraft(settings.libraryAgentsRoot));
  };

  const saveSyncInterval = () => {
    if (!settings) return;
    const minutes = Number(syncIntervalDraft);
    if (!Number.isFinite(minutes) || minutes === settings.librarySyncIntervalMinutes) return;
    void api.updateSettings({ librarySyncIntervalMinutes: minutes })
      .then((s) => { setSettings(s); setSyncIntervalDraft(String(s.librarySyncIntervalMinutes)); })
      .catch(() => setSyncIntervalDraft(String(settings.librarySyncIntervalMinutes)));
  };

  const saveDefaultProvider = (id: string) => {
    if (!settings) return;
    const previous = settings.defaultProvider;
    setSettings({ ...settings, defaultProvider: id });
    void api.updateSettings({ defaultProvider: id }).catch(() => setSettings({ ...settings, defaultProvider: previous }));
  };

  const saveSystemProvider = (id: string) => {
    if (!settings) return;
    const previous = settings.systemProvider;
    setSettings({ ...settings, systemProvider: id });
    void api.updateSettings({ systemProvider: id }).catch(() => setSettings({ ...settings, systemProvider: previous }));
  };

  const saveLinearTools = () => {
    if (!settings || linearToolsDraft === settings.linearAllowedTools) return;
    void api.updateSettings({ linearAllowedTools: linearToolsDraft })
      .then((s) => { setSettings(s); setLinearToolsDraft(s.linearAllowedTools); })
      .catch(() => setLinearToolsDraft(settings.linearAllowedTools));
  };

  const saveContextWarnPercent = () => {
    if (!settings) return;
    const percent = Number(contextWarnPercentDraft);
    if (!Number.isFinite(percent) || percent === settings.contextWarnPercent) return;
    void api.updateSettings({ contextWarnPercent: percent })
      .then((s) => { setSettings(s); setContextWarnPercentDraft(String(s.contextWarnPercent)); })
      .catch(() => setContextWarnPercentDraft(String(settings.contextWarnPercent)));
  };

  const saveCodexPricing = () => {
    if (!settings || codexPricingDraft === settings.codexPricing) return;
    setCodexPricingError('');
    void api.updateSettings({ codexPricing: codexPricingDraft })
      .then((s) => { setSettings(s); setCodexPricingDraft(s.codexPricing); })
      .catch((e: unknown) => setCodexPricingError(e instanceof Error ? e.message : String(e)));
  };

  const saveClaudePricing = () => {
    if (!settings || claudePricingDraft === settings.claudePricing) return;
    setClaudePricingError('');
    void api.updateSettings({ claudePricing: claudePricingDraft })
      .then((s) => { setSettings(s); setClaudePricingDraft(s.claudePricing); })
      .catch((e: unknown) => setClaudePricingError(e instanceof Error ? e.message : String(e)));
  };

  const saveMcpSerenaRoot = () => {
    if (!settings || mcpSerenaRootDraft === settings.mcpSerenaRoot) return;
    setMcpSerenaError('');
    void api.updateSettings({ mcpSerenaRoot: mcpSerenaRootDraft })
      .then((s) => { setSettings(s); setMcpSerenaRootDraft(s.mcpSerenaRoot); })
      .catch((e: unknown) => setMcpSerenaError(e instanceof Error ? e.message : String(e)));
  };

  const saveMcpUvPath = () => {
    if (!settings || mcpUvPathDraft === settings.mcpUvPath) return;
    setMcpSerenaError('');
    void api.updateSettings({ mcpUvPath: mcpUvPathDraft })
      .then((s) => { setSettings(s); setMcpUvPathDraft(s.mcpUvPath); })
      .catch((e: unknown) => setMcpSerenaError(e instanceof Error ? e.message : String(e)));
  };

  const saveMcpGraphifyRoot = () => {
    if (!settings || mcpGraphifyRootDraft === settings.mcpGraphifyRoot) return;
    setMcpSerenaError('');
    setMcpGraphifySaving(true);
    void api.updateSettings({ mcpGraphifyRoot: mcpGraphifyRootDraft })
      .then((s) => { setSettings(s); setMcpGraphifyRootDraft(s.mcpGraphifyRoot); })
      .catch((e: unknown) => setMcpSerenaError(e instanceof Error ? e.message : String(e)))
      .finally(() => setMcpGraphifySaving(false));
  };

  const saveMcpCodegraphRoot = () => {
    if (!settings || mcpCodegraphRootDraft === settings.mcpCodegraphRoot) return;
    setMcpSerenaError('');
    void api.updateSettings({ mcpCodegraphRoot: mcpCodegraphRootDraft })
      .then((s) => { setSettings(s); setMcpCodegraphRootDraft(s.mcpCodegraphRoot); })
      .catch((e: unknown) => setMcpSerenaError(e instanceof Error ? e.message : String(e)));
  };

  const saveCodeIntel = (tool: CodeIntel) => {
    if (!settings) return;
    const previous = settings.codeIntel;
    setMcpSerenaError('');
    setSettings({ ...settings, codeIntel: tool });
    void api.updateSettings({ codeIntel: tool })
      .then(setSettings)
      .catch((e: unknown) => {
        setSettings({ ...settings, codeIntel: previous });
        setMcpSerenaError(e instanceof Error ? e.message : String(e));
      });
  };

  const toggleVectorize = () => {
    if (!settings) return;
    const next = { ...settings, libraryVectorize: !settings.libraryVectorize };
    setSettings(next);
    void api.updateSettings({ libraryVectorize: next.libraryVectorize }).catch(() => setSettings(settings));
  };

  const toggleLibrarySync = () => {
    if (!settings) return;
    const next = { ...settings, librarySyncEnabled: !settings.librarySyncEnabled };
    setSettings(next);
    void api.updateSettings({ librarySyncEnabled: next.librarySyncEnabled }).catch(() => setSettings(settings));
  };

  const toggleMemoryEnabled = () => {
    if (!settings) return;
    const next = { ...settings, memoryEnabled: !settings.memoryEnabled };
    setSettings(next);
    void api.updateSettings({ memoryEnabled: next.memoryEnabled }).catch(() => setSettings(settings));
  };

  const toggleReflectionDefault = () => {
    if (!settings) return;
    const next = { ...settings, memoryReflectionDefault: !settings.memoryReflectionDefault };
    setSettings(next);
    void api.updateSettings({ memoryReflectionDefault: next.memoryReflectionDefault }).catch(() => setSettings(settings));
  };

  const toggleHideOnPark = () => {
    if (!settings) return;
    const next = { ...settings, hideOnPark: !settings.hideOnPark };
    setSettings(next);
    void api.updateSettings({ hideOnPark: next.hideOnPark }).catch(() => setSettings(settings));
  };

  const toggleAutoTitle = () => {
    if (!settings) return;
    const next = { ...settings, autoTitle: !settings.autoTitle };
    setSettings(next);
    void api.updateSettings({ autoTitle: next.autoTitle }).catch(() => setSettings(settings));
  };

  const toggleSlackDefault = () => {
    if (!settings) return;
    const next = { ...settings, slackDefaultEnabled: !settings.slackDefaultEnabled };
    setSettings(next);
    void api.updateSettings({ slackDefaultEnabled: next.slackDefaultEnabled }).catch(() => setSettings(settings));
  };

  const toggleApprovalRequired = () => {
    if (!settings) return;
    const next = { ...settings, memoryReflectionApprovalRequired: !settings.memoryReflectionApprovalRequired };
    setSettings(next);
    void api.updateSettings({ memoryReflectionApprovalRequired: next.memoryReflectionApprovalRequired }).catch(() => setSettings(settings));
  };

  const saveMemoryRoot = () => {
    if (!settings || memoryRootDraft === settings.memoryRoot) return;
    void api.updateSettings({ memoryRoot: memoryRootDraft })
      .then((s) => { setSettings(s); setMemoryRootDraft(s.memoryRoot); })
      .catch(() => setMemoryRootDraft(settings.memoryRoot));
  };

  const saveReflectionModel = (model: string) => {
    if (!settings) return;
    const previous = settings.memoryReflectionModel;
    setSettings({ ...settings, memoryReflectionModel: model });
    void api.updateSettings({ memoryReflectionModel: model })
      .catch(() => setSettings({ ...settings, memoryReflectionModel: previous }));
  };

  const saveMemorySyncInterval = () => {
    if (!settings) return;
    const minutes = Number(memorySyncIntervalDraft);
    if (!Number.isFinite(minutes) || minutes === settings.memorySyncIntervalMinutes) return;
    void api.updateSettings({ memorySyncIntervalMinutes: minutes })
      .then((s) => { setSettings(s); setMemorySyncIntervalDraft(String(s.memorySyncIntervalMinutes)); })
      .catch(() => setMemorySyncIntervalDraft(String(settings.memorySyncIntervalMinutes)));
  };

  const saveMemoryRetention = () => {
    if (!settings) return;
    const days = Number(memoryRetentionDraft);
    if (!Number.isFinite(days) || days === settings.memoryRetentionDays) return;
    void api.updateSettings({ memoryRetentionDays: days })
      .then((s) => { setSettings(s); setMemoryRetentionDraft(String(s.memoryRetentionDays)); })
      .catch(() => setMemoryRetentionDraft(String(settings.memoryRetentionDays)));
  };

  const toggleServiceDiscoveryEnabled = () => {
    if (!settings) return;
    const next = { ...settings, serviceDiscoveryEnabled: !settings.serviceDiscoveryEnabled };
    setSettings(next);
    void api.updateSettings({ serviceDiscoveryEnabled: next.serviceDiscoveryEnabled }).catch(() => setSettings(settings));
  };

  const saveServiceDiscoveryStaleness = () => {
    if (!settings) return;
    const days = Number(serviceDiscoveryStalenessDraft);
    if (!Number.isFinite(days) || days === settings.serviceDiscoveryStalenessDays) return;
    void api.updateSettings({ serviceDiscoveryStalenessDays: days })
      .then((s) => { setSettings(s); setServiceDiscoveryStalenessDraft(String(s.serviceDiscoveryStalenessDays)); })
      .catch(() => setServiceDiscoveryStalenessDraft(String(settings.serviceDiscoveryStalenessDays)));
  };

  const saveServiceDiscoveryModel = (model: string) => {
    if (!settings) return;
    const previous = settings.serviceDiscoveryModel;
    setSettings({ ...settings, serviceDiscoveryModel: model });
    void api.updateSettings({ serviceDiscoveryModel: model })
      .catch(() => setSettings({ ...settings, serviceDiscoveryModel: previous }));
  };

  const saveVisualizeModel = (model: string) => {
    if (!settings) return;
    const previous = settings.visualizeModel;
    setSettings({ ...settings, visualizeModel: model });
    void api.updateSettings({ visualizeModel: model })
      .catch(() => setSettings({ ...settings, visualizeModel: previous }));
  };

  const backdropDismiss = useBackdropDismiss(onClose);

  // section index on the left: built from the rendered <h3>s (several sections only exist
  // once settings/provider data has loaded), the active one tracked from the scroll position
  const modalRef = useRef<HTMLDivElement>(null);
  const [sections, setSections] = useState<string[]>([]);
  const [activeSection, setActiveSection] = useState('');
  useEffect(() => {
    const heads = [...(modalRef.current?.querySelectorAll('h3.settings-h') ?? [])].map((h) => h.textContent ?? '');
    setSections(heads);
    setActiveSection((cur) => cur || heads[0] || '');
  }, [settings !== null]);
  const headingFor = (name: string) =>
    [...(modalRef.current?.querySelectorAll<HTMLElement>('h3.settings-h') ?? [])].find((h) => h.textContent === name);
  const onModalScroll = () => {
    const modal = modalRef.current;
    if (!modal) return;
    const top = modal.getBoundingClientRect().top + 60;
    let current = sections[0] ?? '';
    for (const name of sections) {
      const h = headingFor(name);
      if (h && h.getBoundingClientRect().top <= top) current = name;
    }
    // scrolled to the very end: the last short sections can never reach the top
    if (modal.scrollTop + modal.clientHeight >= modal.scrollHeight - 2) current = sections[sections.length - 1] ?? current;
    setActiveSection(current);
  };

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal wide settings" ref={modalRef} onScroll={onModalScroll} onClick={(e) => e.stopPropagation()}>
        <h2>Settings</h2>
        <div className="settings-layout">
        <nav className="settings-nav">
          {sections.map((name) => (
            <button
              key={name}
              className={activeSection === name ? 'active' : ''}
              onClick={() => {
                setActiveSection(name);
                headingFor(name)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
              }}
            >{name}</button>
          ))}
        </nav>
        <div className="settings-body">
        <h3 className="settings-h">Appearance</h3>
        <div className="form-grid">
          <label>Theme</label>
          <select
            value={theme}
            onChange={(e) => {
              const next = e.target.value as Theme;
              setThemeState(next);
              setTheme(next);
            }}
          >
            <option value="system">System</option>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
          </select>
          <label>Font size</label>
          <select
            value={fontSize}
            onChange={(e) => {
              const next = e.target.value as FontSize;
              setFontSizeState(next);
              setFontSize(next);
            }}
          >
            <option value="small">Small</option>
            <option value="medium">Medium</option>
            <option value="large">Large</option>
          </select>
        </div>

        {settings && (
          <>
            <h3 className="settings-h">Sessions</h3>
            <div className="form-grid">
              <label>Ecosystem root</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={ecosystemRootDraft}
                onChange={(e) => setEcosystemRootDraft(e.target.value)}
                onBlur={saveEcosystemRoot}
                placeholder="parent folder of your services; empty = no default wider context"
                title="default read-only context folder + service discovery root, overridable per session"
              />

              <label>Monorepo detection</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.monorepoDetectionEnabled} onChange={toggleMonorepoDetection} />
                auto-split a repo into its workspace packages as separate services; off = every repo under
                the ecosystem root is one service, even if it has a workspace manifest
              </label>

              <label>Monorepo package globs</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={monorepoGlobsDraft}
                onChange={(e) => setMonorepoGlobsDraft(e.target.value)}
                onBlur={saveMonorepoGlobs}
                placeholder="packages/*,services/*,apps/*,libs/*"
                title="comma-separated glob fallback for detecting a monorepo's packages when it has no workspace manifest"
              />

              <label>Default provider</label>
              <select value={settings.defaultProvider} onChange={(e) => saveDefaultProvider(e.target.value)}>
                {(providers.length ? providers.map((p) => p.id) : [settings.defaultProvider]).map((id) => (
                  <option key={id} value={id}>{id}</option>
                ))}
              </select>

              <label>System session provider</label>
              <select
                value={settings.systemProvider}
                onChange={(e) => saveSystemProvider(e.target.value)}
                title="drives ticket import, reflection, service discovery, commit/PR drafting, and handoff briefs; a Codex system session gets no MCP tool pre-approval and may time out on Linear/memory-tool turns"
              >
                <option value="">(follow default provider)</option>
                {providers.map((p) => <option key={p.id} value={p.id}>{p.id}</option>)}
              </select>

              <label>Auto-title</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.autoTitle} onChange={toggleAutoTitle} />
                rename a session from its first message (only while its name is still the branch)
              </label>

              <label>Hide when parked</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.hideOnPark} onChange={toggleHideOnPark} />
                take sessions off the dashboard once they park after idling (they stay under Hidden in the sidebar)
              </label>

              <label>Context warning at</label>
              <span className="with-unit">
              <input
                type="number"
                min={30}
                max={95}
                value={contextWarnPercentDraft}
                onChange={(e) => setContextWarnPercentDraft(e.target.value)}
                onBlur={saveContextWarnPercent}
                title="widget ctx chip turns amber and a compact suggestion appears once a session crosses this percentage of its context window (30-95)"
              /> %
            </span>
            </div>

            <h3 className="settings-h">Claude</h3>
            <div className="form-grid">
              <label>Live cost pricing</label>
              <textarea
                className="full"
                style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
                rows={4}
                value={claudePricingDraft}
                onChange={(e) => setClaudePricingDraft(e.target.value)}
                onBlur={saveClaudePricing}
                title='$ per million tokens, used only for the "~$" estimate shown while a turn runs — the cost recorded when a turn ends comes from Claude itself. A model id uses its longest matching key prefix; "default" covers the rest. Empty restores the shipped table.'
              />
              {claudePricingError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{claudePricingError}</div>}
            </div>

            <h3 className="settings-h">Codex</h3>
            <div className="form-grid">
              <label>Pricing</label>
              <textarea
                className="full"
                style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
                rows={4}
                value={codexPricingDraft}
                onChange={(e) => setCodexPricingDraft(e.target.value)}
                onBlur={saveCodexPricing}
                title='per-model $-per-million-tokens estimate; Codex reports no per-turn USD itself. "default" is the fallback for a model with no specific row.'
              />
              {codexPricingError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{codexPricingError}</div>}
            </div>

            <h3 className="settings-h">MCP servers</h3>
            <div className="form-grid">
              <label>Code intelligence</label>
              <select
                value={settings.codeIntel}
                onChange={(e) => saveCodeIntel(e.target.value as CodeIntel)}
                title="which code-intelligence MCP tool a session may opt into (one per install; the create dialog's checkbox names it). A tool needs its root below."
              >
                <option value="none">none</option>
                <option value="serena" disabled={!settings.mcpSerenaRoot}>Serena (symbolic code tools)</option>
                <option value="graphify" disabled={!settings.mcpGraphifyRoot}>Graphify (knowledge graph)</option>
                <option value="codegraph" disabled={!settings.mcpCodegraphRoot}>CodeGraph (code graph, self-refreshing)</option>
              </select>

              <label>Serena root</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={mcpSerenaRootDraft}
                onChange={(e) => setMcpSerenaRootDraft(e.target.value)}
                onBlur={saveMcpSerenaRoot}
                placeholder="path to a Serena checkout; empty = Serena unavailable"
                title="Serena (symbolic code tools) MCP server checkout root — selectable above once set"
              />

              <label>Graphify root</label>
              <input
                className={mcpGraphifySaving ? 'full pulse' : 'full'}
                style={{ gridColumn: '2 / -1' }}
                value={mcpGraphifyRootDraft}
                disabled={mcpGraphifySaving}
                onChange={(e) => setMcpGraphifyRootDraft(e.target.value)}
                onBlur={saveMcpGraphifyRoot}
                placeholder="path to a graphify checkout; empty = graphify unavailable"
                title="graphify (knowledge-graph code tools) checkout root, driven via uv — selectable above once set. The first save also syncs its Python env, which can take a minute or two."
              />

              <label>uv path</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={mcpUvPathDraft}
                onChange={(e) => setMcpUvPathDraft(e.target.value)}
                onBlur={saveMcpUvPath}
                placeholder="uv"
                title="uv command/path, for hosts where it isn't on the backend's PATH"
              />

              <label>CodeGraph root</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={mcpCodegraphRootDraft}
                onChange={(e) => setMcpCodegraphRootDraft(e.target.value)}
                onBlur={saveMcpCodegraphRoot}
                placeholder="path to a codegraph checkout; empty = CodeGraph unavailable"
                title="CodeGraph MCP server checkout root, run on the backend's own node (no uv) — selectable above once set"
              />
              {mcpSerenaError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{mcpSerenaError}</div>}
            </div>

            <ToolCatalogSettings />

            <h3 className="settings-h">Skill library</h3>
            <div className="form-grid">
              <label>Skills folder</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={skillsRootDraft}
                onChange={(e) => setSkillsRootDraft(e.target.value)}
                onBlur={saveSkillsRoot}
                title="managed skill folder — import destination and the root the create-dialog picker scans"
              />

              <label>Agents folder</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={agentsRootDraft}
                onChange={(e) => setAgentsRootDraft(e.target.value)}
                onBlur={saveAgentsRoot}
                title="managed agent folder — import destination for agent assets"
              />

              <label>Vectorize</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input
                  type="checkbox"
                  checked={settings.libraryVectorize}
                  disabled={!settings.voyageConfigured}
                  onChange={toggleVectorize}
                />
                {settings.voyageConfigured
                  ? 'embed content on import & sync for semantic search (Voyage AI)'
                  : 'requires ARTUS_VOYAGE_API_KEY (env var only)'}
              </label>

              <label>Source sync</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.librarySyncEnabled} onChange={toggleLibrarySync} />
                periodically re-check synced sources; update changed assets, archive removed ones
              </label>

              <label>Sync interval</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={5}
                  style={{ width: 90 }}
                  value={syncIntervalDraft}
                  onChange={(e) => setSyncIntervalDraft(e.target.value)}
                  onBlur={saveSyncInterval}
                />
                minutes (minimum 5)
              </span>
            </div>

            <h3 className="settings-h">Slack</h3>
            <div className="form-grid">
              <label>Mirror by default</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.slackDefaultEnabled} onChange={toggleSlackDefault} disabled={!slackStatus?.enabled} />
                new sessions start mirrored into a Slack thread (always overridable per session)
              </label>
              <div style={{ gridColumn: '1 / -1', color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
                {slackStatus === null
                  ? 'Checking Slack…'
                  : !slackStatus.enabled
                    ? 'Slack is not enabled on the server — set ARTUS_SLACK_ENABLED and the Slack tokens, then restart (see docs/SLACK.md).'
                    : slackStatus.connected ? 'Slack is connected.' : 'Slack is enabled but not connected — check the backend log.'}
              </div>
            </div>

            <h3 className="settings-h">Memory</h3>
            <div className="form-grid">
              <label>Enabled</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.memoryEnabled} onChange={toggleMemoryEnabled} />
                inject the memory tools + recent-activity window into every session
              </label>

              <label>Reflect by default</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.memoryReflectionDefault} onChange={toggleReflectionDefault} />
                new sessions start with reflection enabled (always overridable per session)
              </label>

              <label>Require approval</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.memoryReflectionApprovalRequired} onChange={toggleApprovalRequired} />
                a reflection is held for explicit approve/discard (Memory → Pending) instead of writing immediately
              </label>

              <label>Reflection tier</label>
              <select value={settings.memoryReflectionModel} onChange={(e) => saveReflectionModel(e.target.value)}>
                <option value="cheap">cheap (default)</option>
                <option value="standard">standard (higher quality)</option>
                <option value="premium">premium</option>
              </select>

              <label>Memory folder</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1' }}
                value={memoryRootDraft}
                onChange={(e) => setMemoryRootDraft(e.target.value)}
                onBlur={saveMemoryRoot}
                title="Markdown files are the source of truth — this folder is a valid Obsidian vault"
              />

              <label>Sync interval</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={1}
                  style={{ width: 90 }}
                  value={memorySyncIntervalDraft}
                  onChange={(e) => setMemorySyncIntervalDraft(e.target.value)}
                  onBlur={saveMemorySyncInterval}
                />
                minutes — how often hand-edited files are picked up
              </span>

              <label>Journal retention</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={0}
                  style={{ width: 90 }}
                  value={memoryRetentionDraft}
                  onChange={(e) => setMemoryRetentionDraft(e.target.value)}
                  onBlur={saveMemoryRetention}
                />
                days before a closed, reflected session's raw journal is pruned (0 = never)
              </span>
            </div>

            <h3 className="settings-h">Service discovery</h3>
            <div className="form-grid">
              <label>Enabled</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.serviceDiscoveryEnabled} onChange={toggleServiceDiscoveryEnabled} />
                describe each ecosystem service at session close, and expose service_description/find_service to agents
              </label>

              <label>Staleness</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={1}
                  style={{ width: 90 }}
                  value={serviceDiscoveryStalenessDraft}
                  onChange={(e) => setServiceDiscoveryStalenessDraft(e.target.value)}
                  onBlur={saveServiceDiscoveryStaleness}
                />
                days before a service's description is regenerated
              </span>

              <label>Discovery tier</label>
              <select value={settings.serviceDiscoveryModel} onChange={(e) => saveServiceDiscoveryModel(e.target.value)}>
                <option value="cheap">cheap (default)</option>
                <option value="standard">standard (higher quality)</option>
                <option value="premium">premium</option>
              </select>
            </div>

            <h3 className="settings-h">Visualize</h3>
            <div className="form-grid">
              <label>Diagram tier</label>
              <select value={settings.visualizeModel} onChange={(e) => saveVisualizeModel(e.target.value)}>
                <option value="cheap">cheap (faster, simpler diagrams)</option>
                <option value="standard">standard (default)</option>
                <option value="premium">premium</option>
              </select>
              <span className="full" style={{ color: 'var(--muted)', fontSize: 'calc(12.5px * var(--font-scale))' }}>
                Model tier for the session widget's Visualize button, on the system-session provider.
              </span>
            </div>

            <h3 className="settings-h">Access</h3>
            <div className="form-grid">
              <label>Access token</label>
              <span style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                  <input type="checkbox" checked={settings.authTokenRequired} onChange={toggleAuthRequired} />
                  require the access token (applies on next restart)
                </label>
                <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>
                  {authStatus === null
                    ? 'running state unknown'
                    : !authStatus.tokenConfigured
                      ? 'No token is configured (ARTUS_TOKEN), so the token is off whatever this says.'
                      : authStatus.required === settings.authTokenRequired
                        ? `Running with the token ${authStatus.required ? 'required' : 'off'}.`
                        : `Currently running with the token ${authStatus.required ? 'required' : 'off'} — restart to apply.`}
                  {' '}With the token off, only local clients are accepted (artus.auth.trusted-cidrs, allowed-hosts);
                  for remote access use an SSH tunnel, Tailscale, or an authenticating reverse proxy.
                </span>
              </span>
            </div>

            <h3 className="settings-h">PR checks</h3>
            <div className="form-grid">
              <label>Enabled</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.prChecksEnabled} onChange={togglePrChecks} />
                poll GitHub for CI status on sessions with an open PR, and notify when it resolves
              </label>

              <label>Poll interval</label>
              <span style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <input
                  type="number"
                  min={30}
                  style={{ width: 90 }}
                  value={pollIntervalDraft}
                  onChange={(e) => setPollIntervalDraft(e.target.value)}
                  onBlur={savePollInterval}
                />
                seconds (minimum 30)
              </span>
            </div>

            <h3 className="settings-h">Linear integration</h3>
            <div className="form-grid">
              <label>API key</label>
              <span style={{ color: 'var(--muted)', fontSize: 12.5 }}>
                {settings.linearApiKeyConfigured
                  ? 'configured via ARTUS_LINEAR_API_KEY'
                  : 'not set (env var only — see docs/DEPLOY.md)'}
              </span>

              <label>OAuth</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={settings.linearOAuthEnabled} onChange={toggleOAuth} />
                use the ambient <code>claude</code> CLI's cached OAuth credential
              </label>

              <label>Allowed tools</label>
              <input
                className="full"
                style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
                value={linearToolsDraft}
                onChange={(e) => setLinearToolsDraft(e.target.value)}
                onBlur={saveLinearTools}
                title="Linear MCP tools new sessions may call, comma-separated; everything else is denied. Applies to sessions created after saving."
                placeholder="get_issue,list_issues,list_comments,get_attachment,extract_images,save_comment"
              />

              <label>Branch naming</label>
              <textarea
                className="full"
                style={{ gridColumn: '2 / -1' }}
                rows={3}
                value={specDraft}
                onChange={(e) => setSpecDraft(e.target.value)}
                onBlur={saveSpec}
                placeholder={'optional guidance appended to the ticket-import prompt, e.g. '
                  + '"keep the ticket number uppercase" or "format as feat(TICKET)-description / fix(TICKET)-description"'}
              />
            </div>
          </>
        )}
        </div>
        </div>

        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
