import { useEffect, useMemo, useRef, useState } from 'react';
import { ticketWaitCopy } from '../ticketCopy';
import { api, ApiError } from '../api/rest';
import { assetStub, pickDefaultBranch, placeholdersOf, type AssetKind, type LibraryAsset, type PermissionMode, type PrInfo, type ProviderView, type ServicesResponse, type SessionSummary, type SessionType, type Settings, type SlackStatus, type Template } from '../protocol';
import AssetPickerDialog from './AssetPickerDialog';
import ModelSelect from './ModelSelect';
import { MODE_CYCLE, MODE_LABEL } from './SessionWidget';
import TicketPickerDialog from './TicketPickerDialog';
import ContinuationPickerDialog from './ContinuationPickerDialog';
import { useTicketImport, type TicketImportOutcome } from '../hooks/useTicketImport';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import { Close, ContinuedFrom } from '../icons';

// "continue from" hands off from a session already on some-branch — default the new one to
// some-branch-1 (or bump the trailing number on a repeat handoff) rather than leaving it blank
function nextBranchName(previous: string): string {
  const match = previous.match(/^(.*)-(\d+)$/);
  return match ? `${match[1]}-${Number(match[2]) + 1}` : `${previous}-1`;
}

const MODE_DESCRIPTION: Record<PermissionMode, string> = {
  default: 'ask for edits & commands',
  acceptEdits: 'auto-accept edits, still ask for commands',
  plan: 'plan first, no edits or commands without approval',
  bypassPermissions: 'bypass all approval (including Bash) — no prompts at all',
};

export default function CreateSessionDialog({
  onCreated,
  onCancel,
  continueFrom,
}: {
  onCreated: (id: string, draftInput?: string) => void;
  onCancel: () => void;
  /** open already continuing from this session (phase 23 History) — same path as the "Continue from…" picker */
  continueFrom?: SessionSummary;
}) {
  const [servicesInfo, setServicesInfo] = useState<ServicesResponse | null>(null);
  const [branches, setBranches] = useState<string[]>([]);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);

  const [name, setName] = useState('');
  const [servicePath, setServicePath] = useState('');
  const [sessionType, setSessionType] = useState<SessionType>('development');
  const [branch, setBranch] = useState('');
  const branchRef = useRef<HTMLInputElement>(null);
  const [baseBranch, setBaseBranch] = useState('main');
  const [syncBaseBranch, setSyncBaseBranch] = useState(true);
  // review sessions only (proposal 1/12): PR-first target picking, with a plain-branch fallback
  const [prs, setPrs] = useState<PrInfo[]>([]);
  const [prsLoading, setPrsLoading] = useState(false);
  const [prsError, setPrsError] = useState('');
  const [selectedPrNumber, setSelectedPrNumber] = useState<number | null>(null);
  const [prUrl, setPrUrl] = useState<string | null>(null);
  const [prTitle, setPrTitle] = useState<string | null>(null);
  const [reviewBranches, setReviewBranches] = useState<string[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [provider, setProvider] = useState('claude');
  const [model, setModel] = useState('');
  const [recommendedModel, setRecommendedModel] = useState<string | null>(null);
  const [permissionMode, setPermissionMode] = useState('acceptEdits');
  const [thinking, setThinking] = useState('');
  const [effort, setEffort] = useState('');
  const [instructions, setInstructions] = useState('');
  const [ecosystemPath, setEcosystemPath] = useState('');
  const [reflectionEnabled, setReflectionEnabled] = useState(false);
  /** phase 18: mirror this session into Slack; the checkbox shows only when the server has Slack enabled */
  const [slackEnabled, setSlackEnabled] = useState(false);
  const [slackStatus, setSlackStatus] = useState<SlackStatus | null>(null);
  /** phase 13: one flag; which tool it attaches is the global Settings selector (`settings.codeIntel`) */
  const [codeIntelEnabled, setCodeIntelEnabled] = useState(false);
  const [selectedSkillAssets, setSelectedSkillAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [selectedAgentAssets, setSelectedAgentAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [extraSkill, setExtraSkill] = useState('');
  const [extraAgent, setExtraAgent] = useState('');
  const [assetPickerKind, setAssetPickerKind] = useState<AssetKind | null>(null);
  const [kickoffValues, setKickoffValues] = useState<Record<string, string>>({});
  const [advanced, setAdvanced] = useState('');
  const [initialPrompt, setInitialPrompt] = useState('');
  // the canonical ref returned by import (e.g. "ENG-123"), distinct from the raw text typed into
  // the ticket-import field (which may be a pasted URL) — this is what gets persisted on the session
  const [resolvedTicketRef, setResolvedTicketRef] = useState<string | null>(null);
  const [ticketImportEnabled, setTicketImportEnabled] = useState(false);
  const [ticketImportWarm, setTicketImportWarm] = useState(false);
  const [ticketImportQueued, setTicketImportQueued] = useState(false);
  // ticket-derived prompts land unsent in the new session's compose box (reviewed & sent by
  // hand there) instead of auto-firing as a kickoff turn the moment the sidecar is ready
  const [promptFromTicket, setPromptFromTicket] = useState(false);

  // 7.3 continuation: like ticket import, the fetched brief lands unsent in the compose box
  // (draft, never auto-fired) — see docs/plan/phase-7-ux-and-orchestration.md 7.3
  const [promptFromContinuation, setPromptFromContinuation] = useState(false);
  const [continuedFromId, setContinuedFromId] = useState<string | null>(null);
  const [continuedFromName, setContinuedFromName] = useState<string | null>(null);
  const [fullTranscript, setFullTranscript] = useState(false);
  const [showContinuationPicker, setShowContinuationPicker] = useState(false);
  const [continuationSessions, setContinuationSessions] = useState<SessionSummary[] | null>(null);
  const [continuationBusy, setContinuationBusy] = useState(false);
  const [continuationError, setContinuationError] = useState('');
  const [applyingContinuation, setApplyingContinuation] = useState(false);

  // deferred (not a plain `autoFocus` prop) — this dialog can be opened by the "n" hotkey,
  // and focusing synchronously during React's commit races a same-tick trailing key event
  // that can otherwise land the keystroke straight into this field
  useEffect(() => {
    const raf = requestAnimationFrame(() => branchRef.current?.focus());
    return () => cancelAnimationFrame(raf);
  }, []);

  useEffect(() => {
    api.services().then((info) => {
      setServicesInfo(info);
      setServicePath(info.defaultRepoPath);
      setEcosystemPath(info.ecosystemRoot);
    }).catch(() => setServicesInfo(null));
    api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
    api.listProviders().then(setProviders).catch(() => setProviders([]));
    api.getSettings().then((s) => {
      setSettings(s);
      setProvider(s.defaultProvider);
      setReflectionEnabled(s.memoryReflectionDefault);
      setSlackEnabled(s.slackDefaultEnabled);
    }).catch(() => setSettings(null));
    api.slackStatus().then(setSlackStatus).catch(() => setSlackStatus(null));
    api.ticketImportEnabled().then((r) => { setTicketImportEnabled(r.enabled); setTicketImportWarm(r.warm); setTicketImportQueued(r.busy); })
      .catch(() => setTicketImportEnabled(false));
  }, []);

  const activeCapabilities = providers.find((p) => p.id === provider)?.capabilities;
  const modelIds = useMemo(() => (activeCapabilities?.models ?? []).map((m) => m.id), [activeCapabilities]);

  // the picker's value is the service's own path; its repoPath (the git root — equal to it for a
  // plain polyrepo service, the monorepo root for a package) is what branches() and the actual
  // worktree source need (docs/plan/phase-11-monorepo.md Step 6)
  const selectedService = useMemo(
    () => (servicesInfo?.services ?? []).find((s) => s.path === servicePath) ?? null,
    [servicesInfo, servicePath],
  );
  const selectedRepoPath = selectedService?.repoPath || servicePath;

  // keep permission mode / model within what the selected provider actually supports —
  // capabilities gate the controls, never a provider-name check (docs/PROTOCOL.md)
  useEffect(() => {
    if (!activeCapabilities) return;
    if (!(activeCapabilities.permissionModes as string[]).includes(permissionMode)) {
      setPermissionMode(activeCapabilities.permissionModes.includes('acceptEdits') ? 'acceptEdits' : 'default');
    }
    if (!activeCapabilities.thinking) setThinking('');
    if (!activeCapabilities.agents) {
      setSelectedAgentAssets(new Map());
      setExtraAgent('');
    }
    if (activeCapabilities.models.length > 0 && !modelIds.includes(model)) {
      // switched to a provider with a fixed catalog the current model isn't in (including
      // a first load where model is still '') — default to its "standard" tier, if it has one
      const standard = activeCapabilities.models.find((m) => m.tier === 'standard') ?? activeCapabilities.models[0];
      setModel(standard?.id ?? '');
      setRecommendedModel(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [provider, activeCapabilities]);

  const ticketImport = useTicketImport(modelIds);
  const { ticketRef, setTicketRef, importBusy, importError, showTicketPicker, setShowTicketPicker,
    recentTickets, pickerBusy, pickerError } = ticketImport;

  const applyImportOutcome = (outcome: TicketImportOutcome) => {
    setBranch(outcome.branchName);
    setInitialPrompt(outcome.prompt);
    setPromptFromTicket(true);
    setResolvedTicketRef(outcome.ticketRef);
    if (outcome.recommendedModel) {
      setModel(outcome.recommendedModel);
      setRecommendedModel(outcome.recommendedModel);
    }
  };
  const importTicket = (refOverride?: string) => ticketImport.importTicket(refOverride, applyImportOutcome);
  const pickTicket = (ref: string) => ticketImport.pickTicket(ref, applyImportOutcome);

  const browseContinuations = async () => {
    setContinuationError('');
    setContinuationBusy(true);
    setShowContinuationPicker(true);
    try {
      const list = await api.listSessions();
      setContinuationSessions(list);
    } catch (e) {
      setContinuationError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setContinuationBusy(false);
    }
  };

  const pickContinuation = async (source: SessionSummary) => {
    setShowContinuationPicker(false);
    setContinuationError('');
    setApplyingContinuation(true);
    try {
      const [detail, text] = await Promise.all([
        api.sessionDetail(source.id),
        fullTranscript ? api.exportTranscript(source.id) : api.handoffSummary(source.id),
      ]);
      setServicePath(detail.session.servicePath);
      if (detail.session.branch) setBranch(nextBranchName(detail.session.branch));
      setProvider(detail.session.provider);
      if (detail.session.model) setModel(detail.session.model);
      setPermissionMode(detail.session.permissionMode);
      setInitialPrompt(text);
      setPromptFromContinuation(true);
      setContinuedFromId(source.id);
      setContinuedFromName(detail.session.name);
    } catch (e) {
      setContinuationError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setApplyingContinuation(false);
    }
  };

  // runs once at mount (the dialog is mounted fresh per open); a failure surfaces via continuationError
  useEffect(() => {
    if (continueFrom) void pickContinuation(continueFrom);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedRepoPath) return;
    api.branches(selectedRepoPath).then((list) => {
      setBranches(list);
      if (list.length > 0 && !list.includes(baseBranch)) {
        setBaseBranch(pickDefaultBranch(list));
      }
    }).catch(() => setBranches([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedRepoPath]);

  // flipping to Review (proposal 12) fetches the repo's open PRs plus a remote-inclusive branch
  // list for the fallback picker — a branch under review usually only exists on the remote
  useEffect(() => {
    if (sessionType !== 'review' || !selectedRepoPath) return;
    setPrsLoading(true);
    setPrsError('');
    api.listPrs(selectedRepoPath)
      .then(setPrs)
      .catch((e) => setPrsError(e instanceof ApiError ? e.message : String(e)))
      .finally(() => setPrsLoading(false));
    api.branches(selectedRepoPath, true).then(setReviewBranches).catch(() => setReviewBranches([]));
  }, [sessionType, selectedRepoPath]);

  const pickPr = (num: number | null) => {
    setSelectedPrNumber(num);
    const pr = prs.find((p) => p.number === num) ?? null;
    setPrUrl(pr?.url ?? null);
    setPrTitle(pr?.title ?? null);
    if (pr) {
      setBranch(pr.headRefName);
      setBaseBranch(pr.baseRefName);
      if (!name.trim()) setName(`review: ${pr.headRefName}`);
    }
  };

  const pickReviewBranch = (b: string) => {
    setBranch(b);
    setSelectedPrNumber(null);
    setPrUrl(null);
    setPrTitle(null);
    if (b && !name.trim()) setName(`review: ${b}`);
  };

  useEffect(() => {
    if (sessionType === 'development') {
      setSelectedPrNumber(null);
      setPrUrl(null);
      setPrTitle(null);
    } else {
      // name/base-branch (the review target's identity) live in Advanced — surface them by default
      // rather than leaving a reviewer to discover the collapsed section
      setAdvancedOpen(true);
    }
  }, [sessionType]);

  const template = templates.find((t) => t.id === templateId);
  const kickoffPrompt = typeof template?.config['kickoffPrompt'] === 'string'
    ? (template.config['kickoffPrompt'] as string)
    : null;
  const placeholders = useMemo(() => (kickoffPrompt ? placeholdersOf(kickoffPrompt) : []), [kickoffPrompt]);

  // pre-populate from the chosen template's own defaults; the user can still add/remove on top
  useEffect(() => {
    if (!template) return;
    const tplBaseBranch = typeof template.config['baseBranch'] === 'string' ? template.config['baseBranch'] as string : null;
    if (tplBaseBranch) setBaseBranch(tplBaseBranch);
    setSelectedSkillAssets(new Map(template.assets.filter((a) => a.kind === 'skill').map((a) => [a.id, assetStub(a)])));
    setSelectedAgentAssets(new Map(template.assets.filter((a) => a.kind === 'agent').map((a) => [a.id, assetStub(a)])));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [templateId]);

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const overrides: Record<string, unknown> = { provider, permissionMode };
      if (model.trim()) overrides['model'] = model.trim();
      if (thinking) overrides['thinking'] = thinking;
      if (effort) overrides['effort'] = effort;
      if (instructions.trim()) overrides['instructions'] = instructions.trim();
      overrides['ecosystemPath'] = ecosystemPath.trim() || null;
      overrides['reflectionEnabled'] = reflectionEnabled;
      if (slackStatus?.enabled) overrides['slackEnabled'] = slackEnabled;
      overrides['codeIntelEnabled'] = codeIntelEnabled;
      if (promptFromTicket && resolvedTicketRef) overrides['ticketRef'] = resolvedTicketRef;
      const sniffSource = (raw: string) =>
        raw.startsWith('http') || raw.endsWith('.git') ? { type: 'repo', ref: raw } : { type: 'dir', ref: raw };
      const skillSources = [
        ...[...selectedSkillAssets.values()].map((a) => ({ type: 'dir', ref: a.location })),
        ...(extraSkill.trim() ? [sniffSource(extraSkill.trim())] : []),
      ];
      if (skillSources.length > 0) overrides['skillSources'] = skillSources;
      const agentSources = [
        ...[...selectedAgentAssets.values()].map((a) => ({ type: 'file', ref: a.location })),
        ...(extraAgent.trim() ? [sniffSource(extraAgent.trim())] : []),
      ];
      if (agentSources.length > 0) overrides['agentSources'] = agentSources;
      const draftPromptPending = promptFromTicket || promptFromContinuation;
      const draftInput = draftPromptPending ? initialPrompt.trim() : '';
      if (draftPromptPending) {
        // explicit '' (not omitted) so a template's own kickoffPrompt can't leak through the
        // config merge and auto-fire in place of the text we're intentionally landing as a draft
        overrides['kickoffPrompt'] = '';
      } else if (initialPrompt.trim()) {
        overrides['kickoffPrompt'] = initialPrompt.trim();
      }
      if (advanced.trim()) Object.assign(overrides, JSON.parse(advanced) as Record<string, unknown>);

      const created = await api.createSession({
        name: name.trim() || (sessionType === 'review' ? `review: ${branch.trim()}` : branch.trim()),
        branch: branch.trim(),
        baseBranch,
        repoPath: selectedRepoPath,
        servicePath,
        templateId: templateId || null,
        overrides,
        kickoffValues,
        syncBaseBranch,
        continuedFromId: continuedFromId || null,
        sessionType,
        prUrl: sessionType === 'review' ? prUrl : null,
        prTitle: sessionType === 'review' ? prTitle : null,
      });
      onCreated(created.id, draftInput || undefined);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));
      setAdvancedOpen(true);
    } finally {
      setBusy(false);
    }
  };

  const backdropDismiss = useBackdropDismiss(() => { if (!busy && !importBusy) onCancel(); });

  return (
    <>
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>New Session</h2>
        <div className="form-grid">
          <label>Service</label>
          <select value={servicePath} onChange={(e) => setServicePath(e.target.value)}>
            {(servicesInfo?.services ?? []).map((s) => (
              <option key={s.path} value={s.path}>{s.name}{s.monorepo ? ' · monorepo' : ''}</option>
            ))}
          </select>

          <label>Type</label>
          <div className="chip-row full" style={{ gridColumn: '2 / -1' }}>
            {(['development', 'review'] as SessionType[]).map((t) => (
              <span
                key={t}
                className={`chip clickable${sessionType === t ? ' selected' : ''}`}
                title={t === 'review' ? 'review an existing PR/branch — commit/push/PR are disabled' : 'develop on a new branch'}
                onClick={() => setSessionType(t)}
              >
                {t === 'development' ? 'Development' : 'Review'}
              </span>
            ))}
          </div>

          <label className="full" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <input type="checkbox" checked={syncBaseBranch} onChange={(e) => setSyncBaseBranch(e.target.checked)} />
            Update {baseBranch || 'base branch'} from origin before branching
          </label>

          {sessionType === 'review' ? (
            <>
              <label>Pull request</label>
              <select
                value={selectedPrNumber ?? ''}
                onChange={(e) => pickPr(e.target.value ? Number(e.target.value) : null)}
              >
                <option value="">{prsLoading ? 'loading PRs…' : '— pick an open PR —'}</option>
                {prs.map((pr) => (
                  <option key={pr.number} value={pr.number}>
                    #{pr.number} {pr.title} ({pr.headRefName} → {pr.baseRefName}){pr.isDraft ? ' [draft]' : ''}
                  </option>
                ))}
              </select>
              {prsError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{prsError}</div>}

              <label>or review a branch</label>
              <select
                value={selectedPrNumber === null ? branch : ''}
                onChange={(e) => pickReviewBranch(e.target.value)}
              >
                <option value="">— pick a branch —</option>
                {reviewBranches.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </>
          ) : (
            <>
              <label>Branch</label>
              <input ref={branchRef} value={branch} onChange={(e) => setBranch(e.target.value)} list="branches" placeholder="feat/my-feature" />
              <datalist id="branches">{branches.map((b) => <option key={b} value={b} />)}</datalist>
            </>
          )}

          {sessionType === 'development' && ticketImportEnabled && (
            <>
              <label>Import ticket</label>
              <div className="row" style={{ display: 'flex', gap: 6 }}>
                <input
                  style={{ flex: 1 }}
                  value={ticketRef}
                  onChange={(e) => setTicketRef(e.target.value)}
                  placeholder="Linear ticket, e.g. ENG-123 or a URL — leave blank to browse tickets assigned to you"
                  disabled={importBusy || pickerBusy}
                />
                {importBusy || pickerBusy ? (
                  <button onClick={() => ticketImport.cancel()}>
                    Cancel
                  </button>
                ) : (
                  <button onClick={() => void (ticketRef.trim() ? importTicket() : ticketImport.browseRecentTickets())}>
                    {ticketRef.trim() ? 'Fetch' : 'Browse'}
                  </button>
                )}
              </div>
              {importBusy && (
                <div className="full" style={{ gridColumn: '2 / -1', color: 'var(--muted)', fontSize: 12.5 }}>
                  {ticketWaitCopy(ticketImportWarm, ticketImportQueued)}
                </div>
              )}
              {importError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{importError}</div>}
            </>
          )}

          <label>Continue from…</label>
          <div className="row" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button onClick={() => void browseContinuations()} disabled={applyingContinuation}>
              {applyingContinuation ? 'Fetching handoff…' : 'Browse recent sessions'}
            </button>
            {continuedFromName && (
              <span className="chip">
                <ContinuedFrom />{continuedFromName}
                <button
                  style={{ marginLeft: 6, border: 'none', background: 'none', padding: 0, color: 'inherit' }}
                  title="clear continuation"
                  onClick={() => { setContinuedFromId(null); setContinuedFromName(null); setPromptFromContinuation(false); }}
                ><Close /></button>
              </span>
            )}
          </div>
          {continuationError && <div className="error-text full" style={{ gridColumn: '2 / -1' }}>{continuationError}</div>}

          <label>Initial prompt</label>
          <textarea
            style={{ gridColumn: '2 / -1' }}
            rows={2}
            value={initialPrompt}
            onChange={(e) => setInitialPrompt(e.target.value)}
            placeholder="optional — overrides the template's kickoff prompt; filled in automatically by ticket import"
          />

          <label>Provider</label>
          <select value={provider} onChange={(e) => setProvider(e.target.value)}>
            {(providers.length ? providers : [{ id: provider, capabilities: activeCapabilities }]).map((p) => (
              <option key={p.id} value={p.id}>{p.id}</option>
            ))}
          </select>

          <label>Model</label>
          <ModelSelect
            value={model}
            onChange={(v) => { setRecommendedModel(null); setModel(v); }}
            capabilities={activeCapabilities}
          />
          {recommendedModel && recommendedModel === model && (
            <div className="full" style={{ gridColumn: '2 / -1', color: 'var(--muted)', fontSize: 12.5 }}>
              recommended by ticket import
            </div>
          )}

          <label>Permissions</label>
          <div className="chip-row full" style={{ gridColumn: '2 / -1' }}>
            {(activeCapabilities?.permissionModes ?? MODE_CYCLE).map((m) => (
              <span
                key={m}
                className={`chip clickable mode-${m}${permissionMode === m ? ' selected' : ''}`}
                title={MODE_DESCRIPTION[m]}
                onClick={() => setPermissionMode(m)}
              >
                {MODE_LABEL[m]}
              </span>
            ))}
          </div>
        </div>

        <details
          className="advanced-toggle"
          open={advancedOpen}
          onToggle={(e) => setAdvancedOpen(e.currentTarget.open)}
        >
          <summary>Advanced options</summary>
          <div className="form-grid">
            <label>Name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="defaults to branch name" />

            <label>Base branch</label>
            <select
              value={baseBranch}
              disabled={sessionType === 'review' && selectedPrNumber !== null}
              title={sessionType === 'review' && selectedPrNumber !== null ? 'derived from the picked PR' : undefined}
              onChange={(e) => setBaseBranch(e.target.value)}
            >
              {(branches.length ? branches : ['main']).map((b) => <option key={b} value={b}>{b}</option>)}
              {sessionType === 'review' && !branches.includes(baseBranch) && <option value={baseBranch}>{baseBranch}</option>}
            </select>

            <label>Template</label>
            <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
              <option value="">— none —</option>
              {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>

            {(!activeCapabilities || activeCapabilities.thinking) && (
              <>
                <label>Thinking</label>
                <select value={thinking} onChange={(e) => setThinking(e.target.value)}>
                  <option value="">provider default</option>
                  <option value="off">off</option>
                  <option value="adaptive">adaptive</option>
                  <option value="16000">budget 16k</option>
                </select>
              </>
            )}

            <label>Effort</label>
            <select value={effort} onChange={(e) => setEffort(e.target.value)}>
              <option value="">provider default</option>
              {['low', 'medium', 'high', 'xhigh', 'max'].map((l) => <option key={l} value={l}>{l}</option>)}
            </select>

            <label>Ecosystem</label>
            <input
              value={ecosystemPath}
              onChange={(e) => setEcosystemPath(e.target.value)}
              placeholder="read-only context folder; empty = no wider context"
              title="parent folder attached read-only so the agent can read sibling services; in a monorepo the session's own worktree is attached instead"
            />

            <label>Reflection</label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
              <input type="checkbox" checked={reflectionEnabled} onChange={(e) => setReflectionEnabled(e.target.checked)} />
              distill this session into long-term memory when it closes (or via the widget's brain button anytime)
            </label>

            {slackStatus?.enabled && (
              <>
                <label>Slack</label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                  <input type="checkbox" checked={slackEnabled} onChange={(e) => setSlackEnabled(e.target.checked)} />
                  mirror this session into a Slack thread (prompts, tool commands and plans are posted there)
                </label>
              </>
            )}

            {settings && settings.codeIntel !== 'none' && (
              <>
                <label>Code intelligence</label>
                <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                  <input type="checkbox" checked={codeIntelEnabled} onChange={(e) => setCodeIntelEnabled(e.target.checked)} />
                  {settings.codeIntel === 'serena'
                    ? 'Serena — symbolic code tools (find_symbol, references, …) via a per-session MCP server'
                    : settings.codeIntel === 'graphify'
                    ? 'Graphify — knowledge-graph tools (query_graph, get_neighbors, …) over a per-session code graph built in the background'
                    : 'CodeGraph — code graph with verbatim source, call paths and blast radius, indexed at session start and kept fresh by its own watcher'}
                </label>
              </>
            )}

            <label>Skills</label>
            <AttachedAssetsRow
              assets={[...selectedSkillAssets.values()]}
              onBrowse={() => setAssetPickerKind('skill')}
              buttonLabel="Add skills…"
            />

            <label>Extra skill</label>
            <input
              value={extraSkill}
              onChange={(e) => setExtraSkill(e.target.value)}
              placeholder="path or git URL of a skill source"
            />

            {(!activeCapabilities || activeCapabilities.agents) && (
              <>
                <label>Agents</label>
                <AttachedAssetsRow
                  assets={[...selectedAgentAssets.values()]}
                  onBrowse={() => setAssetPickerKind('agent')}
                  buttonLabel="Add agents…"
                />

                <label>Extra agent</label>
                <input
                  value={extraAgent}
                  onChange={(e) => setExtraAgent(e.target.value)}
                  placeholder="path or git URL of an agent source"
                />
              </>
            )}

            <label>Instructions</label>
            <textarea
              className="full"
              style={{ gridColumn: '2 / -1' }}
              rows={2}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
              placeholder="extra system instructions for this session"
            />

            {placeholders.map((ph) => (
              <div key={ph} style={{ display: 'contents' }}>
                <label>{ph}</label>
                <input
                  value={kickoffValues[ph] ?? ''}
                  onChange={(e) => setKickoffValues({ ...kickoffValues, [ph]: e.target.value })}
                  placeholder={`kickoff value for {{${ph}}}`}
                />
              </div>
            ))}

            <label>Raw overrides</label>
            <textarea
              style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
              rows={2}
              value={advanced}
              onChange={(e) => setAdvanced(e.target.value)}
              placeholder='extra overrides JSON, e.g. {"maxTurns": 30, "contextDirs": ["/path"]}'
            />
          </div>
        </details>
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !branch.trim()} onClick={() => void create()}>
            {busy ? 'Creating…' : 'Create'}
          </button>
        </div>
      </div>
    </div>
    {showTicketPicker && (
      <TicketPickerDialog
        list={recentTickets}
        busy={pickerBusy}
        error={pickerError}
        waitCopy={ticketWaitCopy(ticketImportWarm, ticketImportQueued)}
        onRefresh={() => void ticketImport.browseRecentTickets(true)}
        onPick={pickTicket}
        onClose={() => { ticketImport.cancel(); setShowTicketPicker(false); }}
      />
    )}
    {showContinuationPicker && (
      <ContinuationPickerDialog
        sessions={continuationSessions}
        busy={continuationBusy}
        error={continuationError}
        fullTranscript={fullTranscript}
        onToggleFullTranscript={setFullTranscript}
        onPick={(s) => void pickContinuation(s)}
        onClose={() => setShowContinuationPicker(false)}
      />
    )}
    {assetPickerKind && (
      <AssetPickerDialog
        kind={assetPickerKind}
        semanticAvailable={!!settings?.voyageConfigured && !!settings?.libraryVectorize}
        selected={assetPickerKind === 'skill' ? selectedSkillAssets : selectedAgentAssets}
        onConfirm={(picked) => {
          if (assetPickerKind === 'skill') setSelectedSkillAssets(picked); else setSelectedAgentAssets(picked);
          setAssetPickerKind(null);
        }}
        onClose={() => setAssetPickerKind(null)}
      />
    )}
    </>
  );
}

/** First few attached asset names as chips, a "+N more" chip, and a browse button. */
export function AttachedAssetsRow({ assets, onBrowse, buttonLabel }: {
  assets: LibraryAsset[];
  onBrowse: () => void;
  buttonLabel: string;
}) {
  const shown = assets.slice(0, 3);
  const rest = assets.length - shown.length;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
      {shown.map((a) => <span key={a.id} className="chip" title={a.description}>{a.name}</span>)}
      {rest > 0 && <span className="chip">+{rest} more</span>}
      <button onClick={onBrowse}>{buttonLabel}</button>
    </div>
  );
}
