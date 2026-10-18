import { useEffect, useState } from 'react';
import { useStore, type SessionView } from '../store/store';
import { getWidget } from '../hotkeys/widgetRegistry';
import { needsFullCard, permissionResponseFor, permissionSummary, toolLabel } from '../permissions';
import { shortAge, type NeedsYouItem, type SidebarModel, type SidebarRow } from '../sidebarModel';
import type { SessionSummary } from '../protocol';
import { Close, ContinuedFrom, MinimizeToDock, NeedsYou, PullRequest } from '../icons';
import { PR_STATUS_LABEL } from './SessionWidget';

export type SidebarFold = 'expanded' | 'rail';

/** a response the backend never acknowledges (e.g. sent into a dropped WS) re-enables the buttons after this */
const IN_FLIGHT_TIMEOUT_MS = 10_000;

function nameOf(id: string, v: SessionView | undefined): string {
  return v?.name ?? id.slice(0, 8);
}

/**
 * Phase 16 session sidebar: every open session's live state from the store (no
 * connections of its own), a pinned "Needs you" list with inline Allow/Deny for plain
 * tool permissions, and a rail mode that keeps only the state dots.
 */
export default function Sidebar({
  model,
  fold,
  showHints,
  onSelect,
  history,
  onOpenHistory,
  onHideHistory,
}: {
  model: SidebarModel;
  fold: SidebarFold;
  /** focus mode: show the 1–9 hotkey hint on the first nine rows */
  showHints: boolean;
  onSelect: (id: string) => void;
  /** phase 23: most recently closed sessions, already filtered/capped */
  history: SessionSummary[];
  onOpenHistory: (session: SessionSummary) => void;
  onHideHistory: (id: string) => void;
}) {
  const views = useStore((s) => s.views);
  const focusedId = useStore((s) => s.focusedId);
  const [now, setNow] = useState(() => Date.now());
  const [inFlight, setInFlight] = useState<ReadonlySet<string>>(new Set());

  // one shared tick for every row's "time in state", not a timer per row
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(timer);
  }, []);


  const respond = (item: NeedsYouItem, decision: 'allow' | 'deny') => {
    const response = permissionResponseFor(item.toolName, item.isPlan, decision);
    const widget = getWidget(item.id);
    if (!response || !widget) return;
    widget.respondPermission(item.requestId, response);
    setInFlight((cur) => new Set(cur).add(item.key));
    setTimeout(() => setInFlight((cur) => {
      const next = new Set(cur);
      next.delete(item.key);
      return next;
    }), IN_FLIGHT_TIMEOUT_MS);
  };

  if (fold === 'rail') {
    const oldest = model.needsYou[0];
    return (
      <aside className="sidebar rail">
        {oldest && (
          <button
            className="icon-btn with-badge sb-rail-needs"
            title={`${model.needsYou.length} waiting for you — open the oldest (a)`}
            onClick={() => onSelect(oldest.id)}
          >
            <NeedsYou /><span className="count-badge">{model.needsYou.length}</span>
          </button>
        )}
        {model.order.map((id) => {
          const v = views[id];
          return (
            <button
              key={id}
              className={`sb-rail-dot${focusedId === id ? ' focused' : ''}`}
              title={`${nameOf(id, v)} — ${(v?.state ?? 'CREATING').toLowerCase()}`}
              onClick={() => onSelect(id)}
            >
              <span className={`dot ${v?.state ?? 'CREATING'}`} />
            </button>
          );
        })}
      </aside>
    );
  }

  return (
    <aside className="sidebar expanded">
      {model.needsYou.length > 0 && (
        <section className="sb-section">
          <div className="sb-heading needs">
            <NeedsYou />Needs you<span className="count-badge">{model.needsYou.length}</span>
          </div>
          {model.needsYou.map((item) => {
            const sent = inFlight.has(item.key);
            const reachable = !!getWidget(item.id);
            const summary = permissionSummary(item.toolName, item.input);
            return (
              <div key={item.key} className="sb-need">
                <button className="sb-need-head" title="open this session" onClick={() => onSelect(item.id)}>
                  <span className="sb-name">{nameOf(item.id, views[item.id])}</span>
                  <span className="sb-age waiting">{shortAge(views[item.id]?.stateSince ?? null, now)}</span>
                </button>
                <div className="sb-need-summary" title={`${item.toolName}: ${summary}`}>
                  <span className="sb-tool">{toolLabel(item.toolName, item.isPlan)}</span>
                  {!item.isPlan && <code>{summary}</code>}
                </div>
                <div className="sb-need-actions">
                  {needsFullCard(item.toolName, item.isPlan) ? (
                    <button onClick={() => onSelect(item.id)} title="read it on the full card first">Open</button>
                  ) : (
                    <>
                      <button
                        className={`primary${sent ? ' pulse' : ''}`}
                        disabled={sent || !reachable}
                        onClick={() => respond(item, 'allow')}
                      >Allow</button>
                      <button
                        className={sent ? 'pulse' : undefined}
                        disabled={sent || !reachable}
                        onClick={() => respond(item, 'deny')}
                      >Deny</button>
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </section>
      )}
      {model.groups.map((group) => (
        <section key={group.key} className="sb-section">
          <div className="sb-heading">{group.label}</div>
          {group.rows.map((row) => (
            <SessionRow
              key={row.id}
              row={row}
              view={views[row.id]}
              focused={focusedId === row.id}
              hint={showHints ? model.order.indexOf(row.id) + 1 : 0}
              now={now}
              onSelect={onSelect}
            />
          ))}
        </section>
      ))}
      {history.length > 0 && (
        <section className="sb-section sb-history">
          <div className="sb-heading"><ContinuedFrom />History</div>
          {history.map((s) => (
            <div key={s.id} className="sb-row sb-history-row" title={`${s.name} · ${s.branch} — closed, click to open`}>
              <button className="sb-history-main" onClick={() => onOpenHistory(s)}>
                <span className="sb-name">{s.name}</span>
                {s.sessionType === 'review' && <span className="chip">review</span>}
                {s.prUrl && <span className="sb-pr pr-CLOSED"><PullRequest /></span>}
                <span className="sb-age">{shortAge(s.closedAt ?? s.updatedAt, now)}</span>
                <span className="sb-cost">${s.costToDate.toFixed(2)}</span>
              </button>
              <button className="icon-btn sb-history-hide" title="hide from History (this browser)" onClick={() => onHideHistory(s.id)}>
                <Close />
              </button>
            </div>
          ))}
        </section>
      )}
    </aside>
  );
}

function SessionRow({
  row, view, focused, hint, now, onSelect,
}: {
  row: SidebarRow;
  view: SessionView | undefined;
  focused: boolean;
  /** 1–9 hotkey hint, 0 = none */
  hint: number;
  now: number;
  onSelect: (id: string) => void;
}) {
  const state = view?.state ?? 'CREATING';
  const service = (view?.servicePath ?? '').split('/').pop();
  const title = [
    nameOf(row.id, view),
    row.depth === 1 && service ? service : null,
    view?.branch,
    view?.model,
    state.toLowerCase(),
    row.minimized ? 'minimized' : null,
  ].filter(Boolean).join(' · ');
  const cls = `sb-row${row.depth === 1 ? ' child' : ''}${focused ? ' focused' : ''}${row.minimized ? ' minimized' : ''}`;
  return (
    <button className={cls} title={title} onClick={() => onSelect(row.id)}>
      {hint > 0 && hint <= 9 && <span className="sb-hint">{hint}</span>}
      <span className={`dot ${state}`} />
      <span className="sb-name">{nameOf(row.id, view)}</span>
      {row.minimized && <MinimizeToDock />}
      {view?.sessionType === 'review' && <span className="chip">review</span>}
      {view?.prUrl && (
        <span className={`sb-pr pr-${view.prCheckStatus ?? 'PENDING'}`} title={`PR — ${PR_STATUS_LABEL[view.prCheckStatus ?? 'PENDING']}`}>
          <PullRequest />
        </span>
      )}
      <span className={`sb-age${state === 'WAITING_INPUT' ? ' waiting' : ''}`}>{shortAge(view?.stateSince ?? null, now)}</span>
      <span className="sb-cost">${(view?.costToDate ?? 0).toFixed(2)}</span>
    </button>
  );
}
