import { useEffect, useRef, useState } from 'react';
import { ticketWaitCopy } from '../ticketCopy';
import { api, ApiError } from '../api/rest';
import { pickDefaultBranch, type PrInfo, type ProviderView, type ServicesResponse, type SessionType, type Template } from '../protocol';
import { useTicketImport, type TicketImportOutcome } from '../hooks/useTicketImport';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import TicketPickerDialog from './TicketPickerDialog';

/**
 * Minimal create flow: service + branch + prompt (optionally filled in from a Linear ticket
 * when import is configured), plus an optional template pick. Everything a chosen template
 * doesn't specify (model, permission mode, tools, MCP servers, skills, agents, instructions,
 * ecosystem, ...) falls back to whatever the most recently created session had configured
 * (SessionService.lastSessionConfig, the same "snapshot a session's effective config" logic
 * the per-session Duplicate button uses); with no template picked, everything comes from that
 * snapshot, same as before templates were added here.
 */
export default function QuickSessionDialog({
  onCreated,
  onCancel,
}: {
  onCreated: (id: string, draftInput?: string) => void;
  onCancel: () => void;
}) {
  const [servicesInfo, setServicesInfo] = useState<ServicesResponse | null>(null);
  const [servicePath, setServicePath] = useState('');
  const [templates, setTemplates] = useState<Template[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [ticketImportEnabled, setTicketImportEnabled] = useState(false);
  const [ticketImportWarm, setTicketImportWarm] = useState(false);
  const [ticketImportQueued, setTicketImportQueued] = useState(false);
  // recommendedModel is generated against the *default* provider's catalog (TicketImportService
  // uses SettingsService.defaultProvider()), which is what a new session created here also gets
  // absent an explicit override in lastSessionConfig — see ModelCatalog (P3).
  const [defaultProviderModelIds, setDefaultProviderModelIds] = useState<string[]>([]);

  const [sessionType, setSessionType] = useState<SessionType>('development');
  const [branchName, setBranchName] = useState('');
  const [prompt, setPrompt] = useState('');
  const [resolvedTicketRef, setResolvedTicketRef] = useState<string | null>(null);
  const [recommendedModel, setRecommendedModel] = useState<string | null>(null);
  // review sessions only (proposal 1/12): PR-first target picking, with a plain-branch fallback
  const [prs, setPrs] = useState<PrInfo[]>([]);
  const [prsLoading, setPrsLoading] = useState(false);
  const [prsError, setPrsError] = useState('');
  const [selectedPrNumber, setSelectedPrNumber] = useState<number | null>(null);
  const [prUrl, setPrUrl] = useState<string | null>(null);
  const [reviewBranches, setReviewBranches] = useState<string[]>([]);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const branchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const raf = requestAnimationFrame(() => branchInputRef.current?.focus());
    return () => cancelAnimationFrame(raf);
  }, []);

  useEffect(() => {
    api.services().then((info) => {
      setServicesInfo(info);
      setServicePath(info.defaultRepoPath);
    }).catch(() => setServicesInfo(null));
    api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
    api.ticketImportEnabled().then((r) => { setTicketImportEnabled(r.enabled); setTicketImportWarm(r.warm); setTicketImportQueued(r.busy); })
      .catch(() => setTicketImportEnabled(false));
    Promise.all([api.getSettings(), api.listProviders()]).then(([settings, providers]) => {
      const models = providers.find((p: ProviderView) => p.id === settings.defaultProvider)?.capabilities.models ?? [];
      setDefaultProviderModelIds(models.map((m) => m.id));
    }).catch(() => setDefaultProviderModelIds([]));
  }, []);

  const ticketImport = useTicketImport(defaultProviderModelIds);
  const { ticketRef, importBusy, importError, showTicketPicker, setShowTicketPicker,
    recentTickets, pickerBusy, pickerError } = ticketImport;

  const applyImportOutcome = (outcome: TicketImportOutcome) => {
    setBranchName(outcome.branchName);
    setPrompt(outcome.prompt);
    setResolvedTicketRef(outcome.ticketRef);
    setRecommendedModel(outcome.recommendedModel);
  };
  const importTicket = (refOverride?: string) => ticketImport.importTicket(refOverride, applyImportOutcome);
  const pickTicket = (ref: string) => ticketImport.pickTicket(ref, applyImportOutcome);

  // the picker's value is the service's own path; its repoPath (the git root) is what branches()
  // and the actual worktree source need (docs/plan/phase-11-monorepo.md Step 6)
  const selectedService = (servicesInfo?.services ?? []).find((s) => s.path === servicePath) ?? null;
  const selectedRepoPath = selectedService?.repoPath || servicePath;

  const template = templates.find((t) => t.id === templateId) ?? null;

  // flipping to Review (proposal 12) fetches the repo's open PRs plus a remote-inclusive branch
  // list for the fallback picker
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

  useEffect(() => {
    if (sessionType === 'development') {
      setSelectedPrNumber(null);
      setPrUrl(null);
    }
  }, [sessionType]);

  const pickPr = (num: number | null) => {
    setSelectedPrNumber(num);
    const pr = prs.find((p) => p.number === num) ?? null;
    setPrUrl(pr?.url ?? null);
    if (pr) setBranchName(pr.headRefName);
  };

  const pickReviewBranch = (b: string) => {
    setBranchName(b);
    setSelectedPrNumber(null);
    setPrUrl(null);
  };

  const selectedPr = prs.find((p) => p.number === selectedPrNumber) ?? null;

  const create = async () => {
    setError('');
    setBusy(true);
    try {
      const [lastConfig, branches] = await Promise.all([api.lastSessionConfig(), api.branches(selectedRepoPath)]);
      const overrides: Record<string, unknown> = { ...(lastConfig as Record<string, unknown>) };
      if (template) {
        // the template's own config/assets take priority — drop the matching keys from the
        // last-session snapshot so it fills gaps only, instead of clobbering what the template
        // picked (skillSources/agentSources never appear in template.config itself: they're
        // merged in server-side from template.assets, but only when overrides doesn't already
        // set those keys — see SessionConfigFactory.prepare)
        for (const key of Object.keys(template.config)) delete overrides[key];
        delete overrides['skillSources'];
        delete overrides['agentSources'];
      }
      if (resolvedTicketRef) overrides['ticketRef'] = resolvedTicketRef;
      if (recommendedModel) overrides['model'] = recommendedModel;
      // the prompt typed here always lands as an unsent draft below, never as an auto-fired
      // kickoff turn — blank out a template's own kickoffPrompt so it doesn't ALSO auto-fire
      overrides['kickoffPrompt'] = '';
      const tplBaseBranch = typeof template?.config['baseBranch'] === 'string' ? template.config['baseBranch'] as string : null;
      const baseBranch = sessionType === 'review'
        ? (selectedPr?.baseRefName ?? tplBaseBranch ?? pickDefaultBranch(branches))
        : (tplBaseBranch || pickDefaultBranch(branches));
      const created = await api.createSession({
        name: sessionType === 'review' ? `review: ${branchName}` : branchName,
        branch: branchName,
        baseBranch,
        repoPath: selectedRepoPath,
        servicePath,
        templateId: template?.id ?? null,
        overrides,
        syncBaseBranch: true,
        sessionType,
        prUrl: sessionType === 'review' ? prUrl : null,
        prTitle: sessionType === 'review' ? (selectedPr?.title ?? null) : null,
      });
      onCreated(created.id, prompt.trim() || undefined);
    } catch (e) {
      setError(e instanceof ApiError ? `${e.status}: ${e.message}` : String(e));
    } finally {
      setBusy(false);
    }
  };

  // the prompt only ever lands as an unsent draft in the new session's composer, so it's optional
  const ready = !!servicePath && !!branchName.trim();
  const backdropDismiss = useBackdropDismiss(() => { if (!busy && !importBusy) onCancel(); });

  return (
    <>
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h2>Quick session</h2>
        <p style={{ color: 'var(--muted)' }}>
          Model, permissions, MCP servers, skills, and agents are copied from your most recently
          created session, or from the template below where it specifies one.
        </p>
        <div className="form-grid">
          <label>Service</label>
          <select value={servicePath} onChange={(e) => setServicePath(e.target.value)}>
            {(servicesInfo?.services ?? []).map((s) => (
              <option key={s.path} value={s.path}>{s.name}{s.monorepo ? ' · monorepo' : ''}</option>
            ))}
          </select>

          {templates.length > 0 && (
            <>
              <label>Template</label>
              <select value={templateId} onChange={(e) => setTemplateId(e.target.value)}>
                <option value="">— last session —</option>
                {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
            </>
          )}

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

          {sessionType === 'development' && ticketImportEnabled && (
            <>
              <label>Ticket</label>
              <div className="row" style={{ display: 'flex', gap: 6 }}>
                <input
                  style={{ flex: 1 }}
                  value={ticketRef}
                  onChange={(e) => ticketImport.setTicketRef(e.target.value)}
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
                value={selectedPrNumber === null ? branchName : ''}
                onChange={(e) => pickReviewBranch(e.target.value)}
              >
                <option value="">— pick a branch —</option>
                {reviewBranches.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </>
          ) : (
            <>
              <label title="the new worktree branch to create — branched off the repo's default branch (or the template's, if it sets one)">Branch</label>
              <input
                ref={branchInputRef}
                style={{ gridColumn: '2 / -1' }}
                value={branchName}
                onChange={(e) => setBranchName(e.target.value)}
                placeholder="feat/my-feature"
                title="the new worktree branch to create — branched off the repo's default branch (or the template's, if it sets one)"
              />
            </>
          )}

          <label>Prompt</label>
          <textarea
            style={{ gridColumn: '2 / -1' }}
            rows={3}
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            placeholder={
              sessionType === 'review'
                ? 'what to focus the review on — optional'
                : ticketImportEnabled ? 'optional — filled in automatically by ticket import' : 'optional — lands as a draft in the session'
            }
          />
        </div>
        {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={busy || !ready} onClick={() => void create()}>
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
    </>
  );
}
