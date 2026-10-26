import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { api, ApiError } from '../api/rest';
import { WsSession } from '../api/ws';
import { catalogModelFor, contextUsageTitle, dotClass, isInterrupted, stateLabel } from '../protocol';
import type { ChangeList, Envelope, PermissionMode, PrCheckStatus, SessionEntity } from '../protocol';
import { type CodeIntelStatus, type LiveTurn, useStore } from '../store/store';
import { describeActivity, formatDuration } from '../activity';
import { notify, notificationForEvent } from '../notify';
import { registerWidget, unregisterWidget } from '../hotkeys/widgetRegistry';
import {
  ChildOf, Close, ContinuedFrom, DownloadIcon, Duplicate, EcosystemContext, GitPanelIcon,
  BackgroundTasks, ChangesIcon, HideFromDashboard, Diagram, ExtractSkill, InfoTag, Interrupt, LinkedTicket, LiveActivity, Maximize, Memory, MinimizeToDock, MoreActions, PullRequest, Restore, Resume, SystemSession,
} from '../icons';
import Transcript from './Transcript';
import CloseDialog from './CloseDialog';
import DuplicateDialog from './DuplicateDialog';
import GitPanel from './GitPanel';
import { appendToComposer } from '../changes/selection';
const ChangesPanel = lazy(() => import('./ChangesPanel'));
const DiagramDialog = lazy(() => import('../diagram/DiagramDialog'));
import ContextSuggestionCard from './ContextSuggestionCard';

export const MODE_CYCLE: PermissionMode[] = ['default', 'acceptEdits', 'plan', 'bypassPermissions'];
export const MODE_LABEL: Record<PermissionMode, string> = {
  default: 'Ask edits',
  acceptEdits: 'Auto-accept',
  plan: 'Plan',
  bypassPermissions: 'Bypass',
};

// one glyph for every PR state — the *colour* carries the status (see .chip.pr-* in
// styles.css), instead of six differently-coloured emoji competing in the header
export const PR_STATUS_LABEL: Record<PrCheckStatus, string> = {
  PENDING: 'checks running',
  SUCCESS: 'checks passed',
  FAILURE: 'checks failed',
  MERGED: 'merged',
  CLOSED: 'closed',
  ERROR: 'check error',
};

export default function SessionWidget({
  sessionId,
  initialInput,
  onClosed,
  onDuplicated,
  isMaximized,
  canMaximize,
  onToggleMaximize,
  onToggleMinimize,
  onRequestFocus,
  onOpenSession,
  onHide,
}: {
  sessionId: string;
  initialInput?: string;
  onClosed: () => void;
  onDuplicated: (id: string) => void;
  isMaximized: boolean;
  /** false in focus layout mode, where the active session already fills the main column */
  canMaximize: boolean;
  onToggleMaximize: () => void;
  onToggleMinimize: () => void;
  /** restore + focus this session — what a click on its desktop notification does (phase 16) */
  onRequestFocus: () => void;
  /** restore + focus another session — the parent/children chips' jump (phase 25) */
  onOpenSession: (id: string) => void;
  /** keep this session off the dashboard; it stays alive in the sidebar's Hidden section (phase 25) */
  onHide: () => void;
}) {
  const view = useStore((s) => s.views[sessionId]);
  // phase 25: the children this session spawned, as one string so an unrelated session's
  // stream doesn't re-render this widget
  const childrenKey = useStore((s) => Object.entries(s.views)
    .filter(([, v]) => v.parentSessionId === sessionId)
    .map(([id, v]) => [id, v.state, v.costToDate.toFixed(3), v.name ?? ''].join('\u0001'))
    .join('\u0002'));
  const children = useMemo(() => (childrenKey ? childrenKey.split('\u0002') : []).map((row) => {
    const [id, state, cost, name] = row.split('\u0001') as [string, string, string, string];
    return { id, state, cost, name: name || id.slice(0, 8) };
  }), [childrenKey]);
  /** where the children popover opens (viewport coords of the chip), null when closed */
  const [childrenAnchor, setChildrenAnchor] = useState<{ top: number; left: number } | null>(null);
  const apply = useStore((s) => s.apply);
  const setWsStatus = useStore((s) => s.setWsStatus);
  const seed = useStore((s) => s.seed);
  const focusedId = useStore((s) => s.focusedId);
  const setFocused = useStore((s) => s.setFocused);
  const evaluateContextWarn = useStore((s) => s.evaluateContextWarn);
  const dismissContextWarn = useStore((s) => s.dismissContextWarn);
  const [entity, setEntity] = useState<SessionEntity | null>(null);
  const [continuedFromName, setContinuedFromName] = useState<string | null>(null);
  const [parentName, setParentName] = useState<string | null>(null);
  const [input, setInput] = useState(initialInput ?? '');
  const [closing, setClosing] = useState(false);
  const [duplicating, setDuplicating] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<{ top: number; right: number } | null>(null);
  const showMenu = menuAnchor !== null;
  const [showGit, setShowGit] = useState(false);
  const [showChanges, setShowChanges] = useState(false);
  const [showDiagram, setShowDiagram] = useState(false);
  const [diagramStale, setDiagramStale] = useState(false);
  const [changes, setChanges] = useState<ChangeList | null>(null);
  const [changesLoading, setChangesLoading] = useState(false);
  const [changesError, setChangesError] = useState('');
  const [widgetW, setWidgetW] = useState(0);
  const widgetRef = useRef<HTMLDivElement>(null);
  const changesSeq = useRef(0);
  const [actionError, setActionError] = useState('');
  const [reflecting, setReflecting] = useState(false);
  const [extractingSkill, setExtractingSkill] = useState(false);
  const [remembering, setRemembering] = useState<ReadonlySet<number>>(new Set());
  const [compacting, setCompacting] = useState(false);
  const [contextWarnPercent, setContextWarnPercent] = useState(70);
  const [memoryEnabled, setMemoryEnabled] = useState(false);
  const wsRef = useRef<WsSession | null>(null);
  const nameRef = useRef<string>('');
  const focusedRef = useRef<string | null>(null);
  const liveRef = useRef(false);
  const requestFocusRef = useRef(onRequestFocus);
  requestFocusRef.current = onRequestFocus;
  const composerRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    api.sessionDetail(sessionId).then((d) => {
      setEntity(d.session);
      setContinuedFromName(d.continuedFromName ?? null);
      setParentName(d.parentName ?? null);
      seed(sessionId, (v) => ({
        ...v,
        permissionMode: d.session.permissionMode,
        capabilities: v.capabilities ?? d.session.capabilities,
        model: v.model ?? d.session.model,
        name: v.name ?? d.session.name,
        costBudgetUsd: v.costBudgetUsd ?? d.session.costBudgetUsd,
        repoPath: v.repoPath ?? d.session.repoPath,
        servicePath: v.servicePath ?? d.session.servicePath,
        branch: v.branch ?? d.session.branch,
        sessionType: v.sessionType ?? d.session.sessionType,
        // right after a reload, before any turn has produced a fresh context_usage event
        contextTokens: v.contextTokens ?? d.session.contextTokens,
        contextWindow: v.contextWindow ?? d.session.contextWindow,
        prUrl: v.prUrl ?? d.session.prUrl,
        prCheckStatus: v.prCheckStatus ?? d.session.prCheckStatus,
        parentSessionId: d.session.parentSessionId,
      }));
    }).catch(() => setEntity(null));
    const onEvent = (e: Envelope) => {
      apply(sessionId, e);
      // desktop notifications only for live events (not replay) on an unwatched tab
      if (!liveRef.current) {
        if (e.type === 'replay_complete' || e.seq === 0) liveRef.current = true;
        return;
      }
      const who = nameRef.current || 'session';
      // the POST only awaits the 202 ack — the pulse should track actual completion
      // (success, its own failure, or the turn it rides on wrapping up either way)
      if (e.type === 'context_compacted' || e.type === 'turn_complete' || e.type === 'error') {
        setCompacting(false);
      }
      const n = notificationForEvent(who, e);
      // the browser tab having focus doesn't mean THIS session is the one being watched —
      // it could be a different widget, a minimized one, or one scrolled off-screen
      if (n) notify(n.title, n.body, { evenIfTabFocused: focusedRef.current !== sessionId, onClick: () => requestFocusRef.current() });
    };
    const ws = new WsSession(sessionId, onEvent, (s) => {
      setWsStatus(sessionId, s);
      if (s !== 'open') liveRef.current = false;
    });
    wsRef.current = ws;
    return () => ws.stop();
  }, [sessionId, apply, setWsStatus, seed]);

  const send = useCallback((cmd: Record<string, unknown>) => wsRef.current?.send(cmd) ?? false, []);

  useEffect(() => {
    api.getSettings().then((s) => {
      setContextWarnPercent(s.contextWarnPercent);
      setMemoryEnabled(s.memoryEnabled);
    }).catch(() => {});
  }, []);

  useEffect(() => {
    evaluateContextWarn(sessionId, contextWarnPercent);
  }, [sessionId, contextWarnPercent, view?.contextTokens, view?.contextWindow, evaluateContextWarn]);

  const compact = useCallback(async () => {
    setActionError('');
    setCompacting(true);
    try {
      await api.compactSession(sessionId);
      // 202 just means the command was accepted; the pulse clears on context_compacted/
      // turn_complete/error above, tracking actual completion rather than the POST round trip
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e));
      setCompacting(false);
    }
  }, [sessionId]);

  // exposes actions the global hotkey listener (Dashboard) can't reach otherwise — there's
  // no shared React tree between it and this widget's composer/git-panel/WS state
  useEffect(() => {
    registerWidget(sessionId, {
      focusComposer: () => composerRef.current?.focus(),
      toggleGit: () => setShowGit((v) => !v),
      toggleChanges: () => setShowChanges((v) => !v),
      respondPermission: (requestId, response) => send({ type: 'permission_response', requestId, ...response }),
    });
    return () => unregisterWidget(sessionId);
  }, [sessionId, send]);

  const submit = useCallback(() => {
    const text = input.trim();
    if (!text) return;
    // keep the text in the composer if the socket is down, instead of losing it
    if (send({ type: 'user_message', text })) setInput('');
  }, [input, send]);

  const cycleMode = useCallback(() => {
    if (!view) return;
    const supported = MODE_CYCLE.filter((m) => view.capabilities?.permissionModes.includes(m) ?? true);
    const current = supported.indexOf(view.permissionMode);
    const next = supported[(current + 1) % supported.length]!;
    send({ type: 'set_permission_mode', mode: next });
  }, [view, send]);

  // Claude's SDK switches model in place (same conversation, next turn uses the new one) and
  // Codex applies it as a per-turn override, so "handover" is just set_model.
  const changeModel = useCallback((id: string) => send({ type: 'set_model', model: id }), [send]);
  const canCycleModel = (view?.capabilities?.modelSwitch ?? true) && (view?.capabilities?.models?.length ?? 0) > 0;

  const resume = useCallback(async () => {
    setActionError('');
    try {
      await api.resumeSession(sessionId);
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e));
    }
  }, [sessionId]);

  const downloadTranscript = useCallback(async () => {
    setActionError('');
    try {
      const markdown = await api.exportTranscript(sessionId);
      const blob = new Blob([markdown], { type: 'text/markdown' });
      const url = URL.createObjectURL(blob);
      const name = (view?.name ?? entity?.name ?? sessionId).replace(/[^a-zA-Z0-9._-]+/g, '-');
      const a = document.createElement('a');
      a.href = url;
      a.download = `${name}.md`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      setActionError(e instanceof ApiError ? e.message : String(e));
    }
  }, [sessionId, view?.name, entity?.name]);

  const state = view?.state ?? 'CREATING';
  const crashReason = view?.crashReason ?? null;
  const running = state === 'RUNNING' || state === 'WAITING_INPUT';
  const budget = view?.costBudgetUsd ?? entity?.costBudgetUsd ?? null;
  const ctxPercent = view?.contextTokens != null && view?.contextWindow ? Math.round((view.contextTokens / view.contextWindow) * 100) : 0;
  const infoParts = [
    entity?.kind === 'system' ? 'system' : entity?.servicePath?.split('/').pop(),
    entity?.kind !== 'system' ? entity?.branch : undefined,
    view?.model ?? entity?.model,
  ].filter(Boolean) as string[];
  nameRef.current = view?.name ?? entity?.name ?? '';
  focusedRef.current = focusedId;
  const widgetClass = useMemo(() => {
    let cls = 'widget';
    if (state === 'WAITING_INPUT') cls += ' waiting';
    // a backend restart only interrupted it: no red frame, the amber dot + Resume say enough
    else if ((state === 'CRASHED' && !isInterrupted(state, crashReason)) || state === 'FAILED') cls += ' crashed';
    if (focusedId === sessionId) cls += ' focused';
    return cls;
  }, [state, crashReason, focusedId, sessionId]);

  const refreshChanges = useCallback(async () => {
    const seq = ++changesSeq.current;
    setChangesLoading(true);
    try {
      const list = await api.sessionChanges(sessionId);
      if (seq === changesSeq.current) {
        setChanges(list);
        setChangesError('');
      }
    } catch (e) {
      if (seq === changesSeq.current) setChangesError(e instanceof ApiError ? e.message : String(e));
    } finally {
      if (seq === changesSeq.current) setChangesLoading(false);
    }
  }, [sessionId]);

  // the header badge and the open panel follow the branch: re-list whenever a turn is over (not
  // while it runs — the agent is mid-edit), and every few seconds while the panel is open
  const isSystem = entity?.kind === 'system';
  const settled = state === 'IDLE' || state === 'PARKED' || state === 'WAITING_INPUT';
  useEffect(() => {
    if (isSystem || !entity || !settled) return;
    void refreshChanges();
  }, [isSystem, entity, settled, refreshChanges]);
  // Visualize (phase 22): the button's outdated dot, only for a session that already has a diagram
  // and only once its turn is over (the diff is in flux while it runs)
  const diagramSeq = view?.diagram?.seq ?? null;
  useEffect(() => {
    if (isSystem || diagramSeq === null || showDiagram || (state !== 'IDLE' && state !== 'PARKED')) return;
    let live = true;
    api.visualizeStatus(sessionId).then((st) => { if (live) setDiagramStale(st.stale); }).catch(() => {});
    return () => { live = false; };
  }, [isSystem, sessionId, diagramSeq, state, showDiagram]);
  useEffect(() => {
    if (!showChanges) return;
    void refreshChanges();
    const t = setInterval(() => void refreshChanges(), 8000);
    return () => clearInterval(t);
  }, [showChanges, refreshChanges]);
  useEffect(() => {
    const el = widgetRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWidgetW(el.getBoundingClientRect().width));
    ro.observe(el);
    return () => ro.disconnect();
  }, [view === undefined]);
  const changesOverlay = widgetW < 900;
  const askAgent = useCallback((text: string) => {
    setInput((cur) => appendToComposer(cur, text));
    if (changesOverlay) setShowChanges(false); // the composer is under the overlay; show it
    requestAnimationFrame(() => {
      const el = composerRef.current;
      if (!el) return;
      el.focus();
      el.setSelectionRange(el.value.length, el.value.length);
    });
  }, [changesOverlay]);

  if (!view) return <div className="widget"><div className="overlay">loading…</div></div>;

  return (
    <div className={widgetClass} ref={widgetRef} onClick={() => setFocused(sessionId)}>
      <div
        className="widget-header"
        onDoubleClick={(e) => {
          // only the empty drag area toggles maximize — the name label has its own
          // double-click (rename) and stops propagation before this ever runs
          if (e.target === e.currentTarget || (e.target as HTMLElement).classList.contains('header-info')) {
            onToggleMaximize();
          }
        }}
      >
        <div className="header-info">
          <span className={dotClass(state, view.crashReason)} title={stateLabel(state, view.crashReason)} />
          <span
            className="name"
            title={`${view.name ?? entity?.name ?? ''} — double-click to rename`}
            onDoubleClick={(e) => {
              e.stopPropagation();
              const next = prompt('Session name:', view.name ?? entity?.name ?? '');
              if (next?.trim()) void api.patchSession(sessionId, { name: next.trim() });
            }}
          >
            {view.name ?? entity?.name ?? sessionId.slice(0, 8)}
          </span>
          <span className="info-chips-full">
            {entity?.kind === 'system' && (
              <span className="chip" title="backend-initiated system session"><SystemSession />system</span>
            )}
            {entity?.kind !== 'system' && entity?.servicePath && (
              <span className="chip" title={entity.servicePath}>
                {entity.servicePath.split('/').pop()}
              </span>
            )}
            {entity?.kind !== 'system' && <span className="chip" title="branch">{entity?.branch}</span>}
            {entity?.sessionType === 'review' && (
              <span className="chip" title="review session — commit/push disabled">review</span>
            )}
            {(view.model ?? entity?.model) && (canCycleModel ? (
              <select
                className="chip model-select"
                title="Switch model (applies from the next turn)"
                value={catalogModelFor(view.capabilities?.models ?? [], view.model ?? entity?.model)?.id ?? ''}
                onChange={(e) => changeModel(e.target.value)}
                onMouseDown={(e) => e.stopPropagation()}
              >
                <option value="" disabled>{view.model ?? entity?.model}</option>
                {(view.capabilities?.models ?? []).map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
              </select>
            ) : (
              <span className="chip">{view.model ?? entity?.model}</span>
            ))}
          </span>
          {infoParts.length > 0 && (
            // collapsed stand-in for the three chips above — shown instead of them (see
            // .info-chips-full/.info-chips-collapsed) once the header runs out of room
            <span className="chip info-chips-collapsed" title={infoParts.join(' · ')}><InfoTag /></span>
          )}
          {entity?.ecosystemPath && (
            <span className="chip" title={`read-only context: ${entity.ecosystemPath}`}><EcosystemContext /></span>
          )}
          {entity?.codeIntel && (
            <span className={codeIntelChipClass(view?.codeIntelStatus ?? null)} title={codeIntelChipTitle(entity.codeIntel, view?.codeIntelStatus ?? null)}>
              {entity.codeIntel}
            </span>
          )}
          {entity?.tools && entity.tools.length > 0 && (
            <span className="chip" title={`catalog tools: ${entity.tools.join(', ')}`}>
              {entity.tools.length === 1 ? entity.tools[0] : `${entity.tools.length} tools`}
            </span>
          )}
          {entity?.ticketRef && (
            <span className="chip" title="linked ticket"><LinkedTicket />{entity.ticketRef}</span>
          )}
          {entity?.continuedFromId && (
            <span className="chip" title={`continued from: ${continuedFromName ?? entity.continuedFromId}`}>
              <ContinuedFrom />{continuedFromName ?? 'continued'}
            </span>
          )}
          {entity?.parentSessionId && (
            <span
              className="chip clickable"
              title={`child of: ${parentName ?? entity.parentSessionId} — click to open it`}
              onMouseDown={(e) => e.stopPropagation()}
              onClick={() => onOpenSession(entity.parentSessionId!)}
            >
              <ChildOf />{parentName ?? 'parent'}
            </span>
          )}
          {children.length > 0 && (
            <span
              className={`chip clickable${children.some((c) => c.state === 'WAITING_INPUT' || c.state === 'CRASHED') ? ' children-attention' : ''}`}
              title="child sessions this session spawned — click for their state"
              onMouseDown={(e) => e.stopPropagation()}
              onClick={(e) => {
                const r = e.currentTarget.getBoundingClientRect();
                setChildrenAnchor((cur) => (cur ? null : { top: r.bottom + 4, left: r.left }));
              }}
            >
              <ChildOf />{childrenSummary(children)}
            </span>
          )}
          {childrenAnchor && children.length > 0 && (
            <ChildrenPopover
              anchor={childrenAnchor}
              items={children}
              onOpen={(id) => { setChildrenAnchor(null); onOpenSession(id); }}
              onClose={() => setChildrenAnchor(null)}
            />
          )}
          {view.prUrl && (
            <a
              className={`chip clickable pr-${view.prCheckStatus ?? 'PENDING'}`}
              href={view.prUrl}
              target="_blank"
              rel="noreferrer"
              title={`PR — ${PR_STATUS_LABEL[view.prCheckStatus ?? 'PENDING']}; click to open on GitHub`}
              onMouseDown={(e) => e.stopPropagation()}
            >
              <PullRequest />PR
            </a>
          )}
        </div>
        <span
          className={`chip clickable mode-${view.permissionMode}`}
          title={view.permissionMode === 'bypassPermissions'
            ? 'ALL tool calls auto-approve, including Bash — no approval prompts at all. Click to switch mode.'
            : 'click to switch permission mode'}
          onClick={cycleMode}
          onMouseDown={(e) => e.stopPropagation()}
        >
          {MODE_LABEL[view.permissionMode]}
        </span>
        <span
          className={`chip${budget !== null ? ' clickable' : ''}${running ? ' pulse' : ''}`}
          title={
            (running
              ? view.liveTurn?.estimatedCostUsd != null
                ? `cost as of the last completed turn, plus ~$${view.liveTurn.estimatedCostUsd.toFixed(3)} estimated for the turn in flight (from token counts and the Settings price table; the real figure lands when it finishes). `
                : 'cost as of the last completed turn — updates once the current turn finishes. '
              : '')
            + (budget !== null
              ? `$${view.costToDate.toFixed(3)} of $${budget} budget — click to change`
              : 'session cost to date')
          }
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => {
            if (budget === null) return;
            const next = prompt('Cost budget (USD):', String(budget));
            if (next && !Number.isNaN(Number(next))) void api.patchSession(sessionId, { costBudgetUsd: Number(next) });
          }}
        >
          ${view.costToDate.toFixed(3)}{budget !== null ? ` / $${budget}` : ''}
          {running && view.liveTurn?.estimatedCostUsd != null && ` + ~$${view.liveTurn.estimatedCostUsd.toFixed(3)}`}
        </span>
        {view.contextTokens != null && view.contextWindow != null && view.contextWindow > 0 && (
          <span
            className={`chip${ctxPercent >= 90 ? ' ctx-danger' : ctxPercent >= contextWarnPercent ? ' ctx-warn' : ''}`}
            title={contextUsageTitle(view.contextTokens, view.contextWindow, view.autoCompactAt, view.contextBreakdown)}
            onMouseDown={(e) => e.stopPropagation()}
          >
            ctx {ctxPercent}%
          </span>
        )}
        {canMaximize && (
          <button
            className="icon-btn"
            onMouseDown={(e) => e.stopPropagation()}
            onClick={onToggleMaximize}
            title={isMaximized ? 'restore (f)' : 'maximize (f)'}
          >{isMaximized ? <Restore /> : <Maximize />}</button>
        )}
        <button
          className="icon-btn"
          onMouseDown={(e) => e.stopPropagation()}
          onClick={onToggleMinimize}
          title="minimize (x)"
        ><MinimizeToDock /></button>
        {entity?.kind !== 'system' && (
          <button
            className={`icon-btn${showGit ? ' active' : ''}`}
            onMouseDown={(e) => e.stopPropagation()}
            onClick={() => setShowGit((v) => !v)}
            title="git panel (g)"
          ><GitPanelIcon /></button>
        )}
        {entity?.kind !== 'system' && (
          <button
            className={`icon-btn with-badge${showChanges ? ' active' : ''}`}
            onMouseDown={(e) => e.stopPropagation()}
            onClick={() => setShowChanges((v) => !v)}
            title={changes && changes.files.length > 0 ? `changes — ${changes.files.length} file(s) vs ${changes.baseBranch} (c)` : 'changes (c)'}
          >
            <ChangesIcon />
            {changes && changes.files.length > 0 && <span className="count-badge">{changes.files.length}</span>}
          </button>
        )}
        {entity?.kind !== 'system' && (
          <button
            className="icon-btn with-badge"
            disabled={running}
            onMouseDown={(e) => e.stopPropagation()}
            onClick={() => setShowDiagram(true)}
            title={running
              ? 'Visualize — wait for the turn to finish'
              : diagramStale ? 'Visualize — diagram of the code logic (outdated: the branch changed since)'
                : 'Visualize — diagram of the code logic on this branch (conversation-derived if there are no changes)'}
          >
            <Diagram />
            {diagramStale && <span className="dot" style={{ background: 'var(--amber)' }} />}
          </button>
        )}
        <button
          // the occasional actions live behind this; it pulses while one of them is in flight
          // (reflect/extract can take minutes) so the feedback survives the menu closing
          className={`icon-btn${showMenu ? ' active' : ''}${reflecting || extractingSkill ? ' busy' : ''}`}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={(e) => {
            const r = e.currentTarget.getBoundingClientRect();
            setMenuAnchor((cur) => (cur ? null : { top: r.bottom + 4, right: window.innerWidth - r.right }));
          }}
          title={reflecting ? 'more actions — reflecting…' : extractingSkill ? 'more actions — extracting a skill…' : 'more actions'}
        ><MoreActions /></button>
        {menuAnchor && (
          <ActionMenu
            anchor={menuAnchor}
            onClose={() => setMenuAnchor(null)}
            items={[
              ...(entity?.kind !== 'system' ? [
                {
                  icon: <HideFromDashboard />,
                  label: 'Hide from dashboard',
                  hotkey: 'h',
                  title: 'keeps running; find it under Hidden in the sidebar. It comes back by itself when it needs you',
                  onClick: onHide,
                },
                { icon: <Duplicate />, label: 'Duplicate session', onClick: () => setDuplicating(true) },
                {
                  icon: <Memory />,
                  label: reflecting ? 'Reflecting…' : 'Reflect now',
                  title: reflecting ? 'distilling this conversation (can take up to 45s)…' : 'distill this conversation into long-term memory',
                  disabled: reflecting,
                  onClick: () => {
                    setActionError('');
                    setReflecting(true);
                    api.reflectSession(sessionId)
                      .catch((e) => setActionError(e instanceof ApiError ? e.message : String(e)))
                      .finally(() => setReflecting(false));
                  },
                },
                {
                  icon: <ExtractSkill />,
                  label: extractingSkill ? 'Extracting skill…' : 'Extract skill',
                  title: extractingSkill
                    ? 'distilling a reusable skill (can take up to 3 min)…'
                    : 'turn the procedure this session followed into a reusable skill (opens a proposal to approve)',
                  disabled: extractingSkill,
                  onClick: () => {
                    setActionError('');
                    setExtractingSkill(true);
                    api.extractSkill(sessionId)
                      .catch((e) => setActionError(e instanceof ApiError ? e.message : String(e)))
                      .finally(() => setExtractingSkill(false));
                  },
                },
              ] : []),
              { icon: <DownloadIcon />, label: 'Download transcript', title: 'Markdown', onClick: () => void downloadTranscript() },
            ]}
          />
        )}
        <button
          className="icon-btn"
          onMouseDown={(e) => e.stopPropagation()}
          onClick={() => setClosing(true)}
          title="close session"
        ><Close /></button>
      </div>
      <div className="widget-content">
      <div className="widget-body">
        {view.wsStatus !== 'open' && state !== 'CLOSED' && (
          <div className="overlay">disconnected — reconnecting…</div>
        )}
        {showGit && (
          <GitPanel
            sessionId={sessionId}
            sessionType={entity?.sessionType ?? 'development'}
            onClose={() => setShowGit(false)}
            onOpenChanges={() => setShowChanges(true)}
            prUrl={view.prUrl}
            prCheckStatus={view.prCheckStatus}
            onPrCreated={(url) => seed(sessionId, (v) => ({ ...v, prUrl: url, prCheckStatus: 'PENDING' }))}
          />
        )}
        {view.backgroundTasks.length > 0 && (
          <div className="bg-tasks" title="the agent started these and they are still running — it picks their results up when they finish">
            <BackgroundTasks size={13} />
            <span>{view.backgroundTasks.length} running in background: {view.backgroundTasks.map((b) => b.description || b.kind).join(' · ')}</span>
          </div>
        )}
        <Transcript
          items={view.transcript}
          cwd={entity?.worktreePath}
          onPermission={(requestId, response) => send({ type: 'permission_response', requestId, ...response })}
          markCtx={memoryEnabled && entity?.kind !== 'system' ? {
            marks: view.marks,
            onMark: (seq, mark) => {
              setActionError('');
              api.setMark(sessionId, seq, mark).catch((e) => setActionError(e instanceof ApiError ? e.message : String(e)));
            },
            remembering,
            onRemember: (seq) => {
              setActionError('');
              setRemembering((prev) => new Set(prev).add(seq));
              api.rememberBlock(sessionId, seq)
                .catch((e) => setActionError(e instanceof ApiError ? e.message : String(e)))
                .finally(() => setRemembering((prev) => { const next = new Set(prev); next.delete(seq); return next; }));
            },
          } : undefined}
        />
        {view.ctxSuggestionVisible && (
          <ContextSuggestionCard
            percentage={ctxPercent}
            canCompact={view.capabilities?.compact !== false}
            compacting={compacting}
            onCompact={() => void compact()}
            onDismiss={() => dismissContextWarn(sessionId)}
          />
        )}
        {running && view.liveTurn && <LiveTurnLine turn={view.liveTurn} waiting={state === 'WAITING_INPUT'} />}
        <div className="inputbar">
          {actionError && <div className="error-text">{actionError}</div>}
          {view.queued.length > 0 && (
            <div className="queue-chips">
              {state === 'WAITING_INPUT' && (
                <div className="t-note warn" style={{ width: '100%' }}>
                  waiting for your approval above — queued messages send once you respond
                </div>
              )}
              {view.queued.map((q) => (
                <span key={q.pos} className="queue-chip" title={q.text}>
                  {q.text.length > 40 ? q.text.slice(0, 40) + '…' : q.text}
                  <button className="icon-btn" title="remove from queue" onClick={() => void api.deleteQueued(sessionId, q.pos)}><Close size={12} /></button>
                </span>
              ))}
            </div>
          )}
          <div className="row">
            <textarea
              ref={composerRef}
              placeholder={state === 'PARKED' ? 'parked — sending wakes the session…' : running ? 'type to queue a message…' : 'message…'}
              value={input}
              rows={Math.min(5, input.split('\n').length)}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  submit();
                }
              }}
              disabled={state === 'CRASHED' || state === 'FAILED' || state === 'CLOSED'}
            />
            {running && view.capabilities?.interrupt !== false && (
              <button className="danger with-icon" title="interrupt" onClick={() => send({ type: 'interrupt' })}>
                <Interrupt fill="currentColor" />
              </button>
            )}
            {state === 'CRASHED'
              // the composer is disabled while crashed, so its button becomes the way back
              ? <button className="primary with-icon" title="restart the sidecar and continue this conversation" onClick={() => void resume()}><Resume />Resume</button>
              : <button className="primary" onClick={submit} disabled={state === 'FAILED'}>Send</button>}
          </div>
        </div>
      </div>
      {showChanges && entity?.kind !== 'system' && (
        <Suspense fallback={<div className={`changes-panel${changesOverlay ? ' overlay' : ''}`}><div className="changes-empty">loading…</div></div>}>
          <ChangesPanel
            sessionId={sessionId}
            changes={changes}
            loading={changesLoading}
            error={changesError}
            onRefresh={() => void refreshChanges()}
            onClose={() => setShowChanges(false)}
            onAsk={askAgent}
            overlay={changesOverlay}
          />
        </Suspense>
      )}
      </div>
      {showDiagram && (
        <Suspense fallback={null}>
          <DiagramDialog sessionId={sessionId} onClose={() => setShowDiagram(false)} />
        </Suspense>
      )}
      {closing && (
        <CloseDialog
          sessionId={sessionId}
          sessionType={entity?.sessionType ?? 'development'}
          onClosed={onClosed}
          onCancel={() => setClosing(false)}
        />
      )}
      {duplicating && (
        <DuplicateDialog
          sessionId={sessionId}
          sessionType={entity?.sessionType ?? 'development'}
          defaultBranch={entity?.branch ?? ''}
          onDuplicated={(id) => { setDuplicating(false); onDuplicated(id); }}
          onCancel={() => setDuplicating(false)}
        />
      )}
    </div>
  );
}

/** phase 13: colour = state — pulse while the graph builds, plain when ready, --red on failure (no status yet = plain). */
/**
 * Portalled: the widget header clips overflow, and grid widgets are CSS-transformed, which would
 * pin a fixed element to the widget instead of the viewport.
 */
function ChildrenPopover({ anchor, items, onOpen, onClose }: {
  anchor: { top: number; left: number };
  items: { id: string; state: string; cost: string; name: string }[];
  onOpen: (id: string) => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) onClose(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    return () => { window.removeEventListener('mousedown', onDown); window.removeEventListener('keydown', onKey); };
  }, [onClose]);
  return createPortal(
    <div ref={ref} className="children-popover" style={{ top: anchor.top, left: anchor.left }}>
      {items.map((c) => (
        <button key={c.id} className="children-row" onClick={() => onOpen(c.id)} title="open this child session">
          <span className={`dot ${c.state}`} />
          <span className="children-name">{c.name}</span>
          <span className="children-meta">{c.state.toLowerCase().replace('_', ' ')} · ${c.cost}</span>
        </button>
      ))}
    </div>,
    document.body,
  );
}

type ActionMenuItem = { icon: ReactNode; label: string; title?: string; hotkey?: string; disabled?: boolean; onClick: () => void };

/** The header's overflow menu — right-aligned under its button, closes on pick/outside click/Escape. */
function ActionMenu({ anchor, items, onClose }: {
  anchor: { top: number; right: number };
  items: ActionMenuItem[];
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) onClose(); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    // it's pinned where its button was, so a page scroll would detach the two (a transcript
    // scrolling — streaming output — doesn't move the header, and doesn't reach this listener)
    const onScroll = () => onClose();
    window.addEventListener('mousedown', onDown);
    window.addEventListener('keydown', onKey);
    window.addEventListener('scroll', onScroll);
    return () => {
      window.removeEventListener('mousedown', onDown);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('scroll', onScroll);
    };
  }, [onClose]);
  return createPortal(
    <div ref={ref} className="action-menu" role="menu" style={{ top: anchor.top, right: anchor.right }}>
      {items.map((it) => (
        <button
          key={it.label}
          role="menuitem"
          className="action-menu-row"
          disabled={it.disabled}
          title={it.title}
          onClick={() => { onClose(); it.onClick(); }}
        >
          {it.icon}
          <span className="action-menu-label">{it.label}</span>
          {it.hotkey && <kbd>{it.hotkey}</kbd>}
        </button>
      ))}
    </div>,
    document.body,
  );
}

function childrenSummary(children: { state: string }[]): string {
  const live = children.filter((c) => c.state === 'RUNNING' || c.state === 'STARTING' || c.state === 'PROVISIONING').length;
  const waiting = children.filter((c) => c.state === 'WAITING_INPUT').length;
  const crashed = children.filter((c) => c.state === 'CRASHED').length;
  const parts = [`${children.length} child${children.length === 1 ? '' : 'ren'}`];
  if (live) parts.push(`${live} running`);
  if (waiting) parts.push(`${waiting} waiting`);
  if (crashed) parts.push(`${crashed} crashed`);
  return parts.join(' · ');
}

/** What the turn in flight is doing, how long it has run, and what it has cost so far (phase 25). */
function LiveTurnLine({ turn, waiting }: { turn: LiveTurn; waiting: boolean }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const label = waiting ? 'waiting for your approval' : describeActivity(turn.activity);
  return (
    <div className={`live-turn${waiting ? ' waiting' : ''}`} title={label}>
      <LiveActivity size={13} className="live-icon" />
      <span className="live-label">{label}</span>
      <span className="live-meta">
        {formatDuration(now - Date.parse(turn.startedAt))}
        {turn.toolCount > 0 && ` · ${turn.toolCount} tool${turn.toolCount === 1 ? '' : 's'}`}
        {turn.estimatedCostUsd != null && ` · ~$${turn.estimatedCostUsd.toFixed(3)}`}
      </span>
    </div>
  );
}

function codeIntelChipClass(status: CodeIntelStatus | null): string {
  if (!status) return 'chip';
  if (status.status === 'BUILDING') return 'chip pulse';
  if (status.status === 'FAILED') return 'chip code-intel-failed';
  return 'chip';
}

function codeIntelChipTitle(tool: string, status: CodeIntelStatus | null): string {
  if (!status) return `${tool} MCP server attached`;
  if (status.status === 'BUILDING') return `${tool}: building the code graph…`;
  if (tool === 'codegraph') {
    if (status.status === 'FAILED') return `codegraph: index build failed — ${status.message ?? 'unknown error'} (use Read/Grep for the rest of the session)`;
    const counts = status.nodes != null && status.edges != null ? `${status.nodes} nodes / ${status.edges} edges, ` : '';
    return `codegraph: index ${counts}built ${agoText(status.at)} — kept fresh by codegraph's watcher`;
  }
  if (status.status === 'FAILED') return `${tool}: graph build failed — ${status.message ?? 'unknown error'} (retried after the next turn)`;
  const counts = status.nodes != null && status.edges != null ? `${status.nodes} nodes / ${status.edges} edges, ` : '';
  return `${tool}: graph ${counts}built ${agoText(status.at)}`;
}

function agoText(iso: string): string {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  return `${Math.round(s / 3600)} h ago`;
}
