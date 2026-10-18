import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import GridLayout, { type Layout } from 'react-grid-layout';
import { api } from '../api/rest';
import type { SessionSummary } from '../protocol';
import SessionWidget from './SessionWidget';
import CreateSessionDialog from './CreateSessionDialog';
import QuickSessionDialog from './QuickSessionDialog';
import TemplateManager from './TemplateManager';
import SettingsDialog from './SettingsDialog';
import UsageDashboard from './UsageDashboard';
import LibraryDialog from './LibraryDialog';
import MemoryDialog from './MemoryDialog';
import ServiceDiscoveryDialog from './ServiceDiscoveryDialog';
import HotkeyCheatsheet from './HotkeyCheatsheet';
import ExposeOverlay from './ExposeOverlay';
import Sidebar, { type SidebarFold } from './Sidebar';
import { buildSidebarModel, normalizeFold } from '../sidebarModel';
import { useStore } from '../store/store';
import { notificationsEnabled, notify, toggleNotifications } from '../notify';
import { useHotkeys } from '../hotkeys/useHotkeys';
import {
  Expose, FocusMode, Memory, New, NotifyOff, NotifyOn, QuickSession, Refresh, ServiceDiscovery,
  SettingsIcon, Shortcuts, SidebarToggle, SkillLibrary, SystemSession, Templates, Usage,
} from '../icons';

const LAYOUT_KEY = 'artus.layout';
const MINIMIZED_KEY = 'artus.minimized';
const SIDEBAR_KEY = 'artus.sidebar';
const LAYOUT_MODE_KEY = 'artus.layoutMode';
const COLS = 12;
const FOLD_CYCLE: SidebarFold[] = ['expanded', 'rail'];

type LayoutMode = 'grid' | 'focus';

function loadFold(): SidebarFold {
  const fold = normalizeFold(localStorage.getItem(SIDEBAR_KEY), window.innerWidth);
  localStorage.setItem(SIDEBAR_KEY, fold);
  return fold;
}

function loadLayoutMode(): LayoutMode {
  return localStorage.getItem(LAYOUT_MODE_KEY) === 'focus' ? 'focus' : 'grid';
}

function loadMinimized(): string[] {
  try {
    return JSON.parse(localStorage.getItem(MINIMIZED_KEY) ?? '[]') as string[];
  } catch {
    return [];
  }
}

function NotifyToggle() {
  const [on, setOn] = useState(notificationsEnabled());
  return (
    <button
      className={`icon-btn${on ? ' active' : ''}`}
      title={on ? 'desktop notifications on (finished / needs input / crashed)' : 'enable desktop notifications'}
      onClick={() => void toggleNotifications().then(setOn)}
    >
      {on ? <NotifyOn /> : <NotifyOff />}
    </button>
  );
}

function loadLayout(): Layout[] {
  try {
    return JSON.parse(localStorage.getItem(LAYOUT_KEY) ?? '[]') as Layout[];
  } catch {
    return [];
  }
}

export default function Dashboard({ initialSessions }: { initialSessions: SessionSummary[] }) {
  // oldest first: the API lists newest first, but a session created on this page is
  // appended at the end — reversing keeps one creation order, which the sidebar relies on
  // so rows don't reshuffle across a reload
  const [sessionIds, setSessionIds] = useState<string[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED' && s.kind !== 'system').map((s) => s.id).reverse(),
  );
  const [systemSessions, setSystemSessions] = useState<SessionSummary[]>(
    initialSessions.filter((s) => s.state !== 'CLOSED' && s.kind === 'system'),
  );
  const [showSystem, setShowSystem] = useState(() => localStorage.getItem('artus.showSystem') === '1');
  const [layout, setLayout] = useState<Layout[]>(loadLayout());
  const [showCreate, setShowCreate] = useState(false);
  const [showQuickCreate, setShowQuickCreate] = useState(false);
  const [showTemplates, setShowTemplates] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showUsage, setShowUsage] = useState(false);
  const [showLibrary, setShowLibrary] = useState(false);
  const [showMemory, setShowMemory] = useState(false);
  const [showServiceDiscovery, setShowServiceDiscovery] = useState(false);
  const [showCheatsheet, setShowCheatsheet] = useState(false);
  const [showExpose, setShowExpose] = useState(false);
  // maximize is transient (not persisted); minimize survives reload like layout does
  const [maximizedId, setMaximizedId] = useState<string | null>(null);
  const [minimizedIds, setMinimizedIds] = useState<string[]>(loadMinimized());
  const [staleCount, setStaleCount] = useState(0);
  const [discoveryCount, setDiscoveryCount] = useState(0);
  const [pendingMemoryCount, setPendingMemoryCount] = useState(0);
  const [width, setWidth] = useState(window.innerWidth - 24);
  const [fold, setFold] = useState<SidebarFold>(loadFold);
  const [layoutMode, setLayoutMode] = useState<LayoutMode>(loadLayoutMode);
  const layoutModeRef = useRef(layoutMode);
  layoutModeRef.current = layoutMode;
  const gridWrapRef = useRef<HTMLDivElement>(null);
  const topbarRef = useRef<HTMLDivElement>(null);
  // seeds a just-created session's compose box (e.g. an edited-but-unsent ticket import
  // draft); read once by SessionWidget's initial state, no cleanup needed afterward
  const [pendingDraft, setPendingDraft] = useState<{ id: string; text: string } | null>(null);
  const removeView = useStore((s) => s.remove);
  const focusedId = useStore((s) => s.focusedId);
  const setFocused = useStore((s) => s.setFocused);

  // the grid follows its own column's width, so a sidebar fold reflows it without a reload
  useEffect(() => {
    const el = gridWrapRef.current;
    if (!el) return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry && entry.contentRect.width > 0) setWidth(Math.floor(entry.contentRect.width));
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // the sticky sidebar and the focus-mode pane sit right under the topbar, whose height
  // varies with the font-size setting
  useEffect(() => {
    const el = topbarRef.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      document.documentElement.style.setProperty('--topbar-h', `${el.offsetHeight}px`);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const refreshStale = useCallback(() => {
    void api.staleSessions().then((list) => setStaleCount(list.length)).catch(() => {});
  }, []);
  useEffect(() => refreshStale(), [refreshStale]);

  // library source-sync discoveries drive the 📚 badge; a rising count on a background
  // tab also fires a desktop notification (sync is server-side, so polling is the transport)
  const prevDiscoveries = useRef(-1);
  const refreshDiscoveries = useCallback(() => {
    void api.librarySources().then((sources) => {
      const total = sources.reduce((n, s) => n + s.discoveries.length, 0);
      if (prevDiscoveries.current >= 0 && total > prevDiscoveries.current) {
        const withNew = sources.filter((s) => s.discoveries.length > 0).map((s) => s.ref).join(', ');
        notify('Skill library', `${total} new file(s) found in ${withNew}`);
      }
      prevDiscoveries.current = total;
      setDiscoveryCount(total);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    refreshDiscoveries();
    const timer = setInterval(refreshDiscoveries, 60_000);
    return () => clearInterval(timer);
  }, [refreshDiscoveries]);

  // pending memory-reflection proposals drive the 🧠 badge, same polling shape as 📚 above
  const prevPending = useRef(-1);
  const refreshPendingMemory = useCallback(() => {
    void api.memoryPendingCount().then((count) => {
      if (prevPending.current >= 0 && count > prevPending.current) {
        notify('Memory', `${count} proposal${count > 1 ? 's' : ''} awaiting approval`);
      }
      prevPending.current = count;
      setPendingMemoryCount(count);
    }).catch(() => {});
  }, []);
  useEffect(() => {
    refreshPendingMemory();
    const timer = setInterval(refreshPendingMemory, 60_000);
    return () => clearInterval(timer);
  }, [refreshPendingMemory]);

  const views = useStore((s) => s.views);
  useEffect(() => {
    const anyRunning = Object.values(views).some((v) => v.state === 'RUNNING' || v.state === 'WAITING_INPUT');
    if (!anyRunning) return;
    const guard = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', guard);
    return () => window.removeEventListener('beforeunload', guard);
  }, [views]);

  // views[id] only exists once that session's widget has mounted its own WebSocket (see
  // SessionWidget); fall back to the last-fetched summary state until then, so the topbar
  // dot doesn't go stale after e.g. a resume that happened while the panel was open.
  const systemState = systemSessions[0] && (views[systemSessions[0].id]?.state ?? systemSessions[0].state);

  const visibleIds = useMemo(
    () => [...sessionIds, ...(showSystem ? systemSessions.map((s) => s.id) : [])],
    [sessionIds, systemSessions, showSystem],
  );

  const fullLayout = useMemo(() => {
    const known = new Map(layout.map((l) => [l.i, l]));
    const NEW_H = 14;
    const PER_ROW = COLS / 6;
    const newIds = visibleIds.filter((id) => !known.has(id));
    // new widgets land at the top (most visible spot) rather than below the fold at the
    // bottom — push every already-known widget down to make room instead of appending after
    const rowsAdded = Math.ceil(newIds.length / PER_ROW);
    let newIndex = 0;
    return visibleIds.map((id) => {
      const existing = known.get(id);
      if (existing) return rowsAdded > 0 ? { ...existing, y: existing.y + rowsAdded * NEW_H } : existing;
      const placed = { i: id, x: (newIndex % PER_ROW) * 6, y: Math.floor(newIndex / PER_ROW) * NEW_H, w: 6, h: NEW_H, minW: 3, minH: 6 };
      newIndex++;
      return placed;
    });
  }, [visibleIds, layout]);

  const onLayoutChange = useCallback((next: Layout[]) => {
    // focus mode hides every item but one — never let react-grid-layout persist whatever
    // it makes of that, so switching back to grid restores the saved layout exactly
    if (layoutModeRef.current === 'focus') return;
    setLayout(next);
    localStorage.setItem(LAYOUT_KEY, JSON.stringify(next));
  }, []);

  const onCreated = useCallback((id: string, draftInput?: string) => {
    setSessionIds((ids) => [...ids, id]);
    setPendingDraft(draftInput ? { id, text: draftInput } : null);
    setShowCreate(false);
    setShowQuickCreate(false);
    setFocused(id);
    // new widgets land at the top of the grid (see fullLayout) — scroll there too, in case
    // the page was scrolled down when this fired, so the new session is actually visible
    window.scrollTo({ top: 0, behavior: 'smooth' });
    // the create flow (e.g. ticket import) may have spun up the system session before this
    // page ever loaded it — refresh so the topbar 🤖 toggle reflects it immediately
    void api.listSessions().then((list) => {
      setSystemSessions(list.filter((s) => s.kind === 'system' && s.state !== 'CLOSED'));
    });
  }, [setFocused]);

  const onClosed = useCallback((id: string) => {
    setSessionIds((ids) => ids.filter((x) => x !== id));
    setSystemSessions((list) => list.filter((s) => s.id !== id));
    removeView(id);
  }, [removeView]);

  const refresh = useCallback(async () => {
    const list = await api.listSessions();
    const live = list.filter((s) => s.state !== 'CLOSED');
    setSessionIds(live.filter((s) => s.kind !== 'system').map((s) => s.id).reverse());
    setSystemSessions(live.filter((s) => s.kind === 'system'));
  }, []);

  const closeTopDialog = useCallback((): boolean => {
    if (showCheatsheet) { setShowCheatsheet(false); return true; }
    if (showCreate) { setShowCreate(false); return true; }
    if (showQuickCreate) { setShowQuickCreate(false); return true; }
    if (showTemplates) { setShowTemplates(false); return true; }
    if (showSettings) { setShowSettings(false); return true; }
    if (showUsage) { setShowUsage(false); return true; }
    if (showLibrary) { setShowLibrary(false); return true; }
    if (showMemory) { setShowMemory(false); return true; }
    if (showServiceDiscovery) { setShowServiceDiscovery(false); return true; }
    return false;
  }, [showCheatsheet, showCreate, showQuickCreate, showTemplates, showSettings, showUsage, showLibrary, showMemory,
    showServiceDiscovery]);

  const toggleMaximize = useCallback((id: string) => {
    if (layoutModeRef.current === 'focus') return; // the active session already fills the main column
    setMaximizedId((cur) => (cur === id ? null : id));
  }, []);

  const toggleMinimize = useCallback((id: string) => {
    setMinimizedIds((cur) => {
      const next = cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id];
      localStorage.setItem(MINIMIZED_KEY, JSON.stringify(next));
      return next;
    });
    setMaximizedId((cur) => (cur === id ? null : cur));
  }, []);

  const restore = useCallback((id: string) => {
    setMinimizedIds((cur) => {
      if (!cur.includes(id)) return cur;
      const next = cur.filter((x) => x !== id);
      localStorage.setItem(MINIMIZED_KEY, JSON.stringify(next));
      return next;
    });
    setFocused(id);
  }, [setFocused]);

  const selectFromExpose = useCallback((id: string) => {
    restore(id);
    setShowExpose(false);
  }, [restore]);

  const systemIds = useMemo(() => new Set(systemSessions.map((s) => s.id)), [systemSessions]);
  const sidebarModel = useMemo(
    () => buildSidebarModel(visibleIds, views, minimizedIds, systemIds),
    [visibleIds, views, minimizedIds, systemIds],
  );

  // focus mode shows the focused session; if that's gone or minimized, the next available
  // one after it in sidebar order (so minimizing the active session moves on, not back to the top)
  const activeId = useMemo(() => {
    if (layoutMode !== 'focus') return null;
    const order = sidebarModel.order;
    const available = (id: string) => !minimizedIds.includes(id);
    if (focusedId && order.includes(focusedId) && available(focusedId)) return focusedId;
    const start = focusedId ? order.indexOf(focusedId) : -1;
    for (let k = 1; k <= order.length; k++) {
      const candidate = order[(start + k + order.length) % order.length]!;
      if (available(candidate)) return candidate;
    }
    return null;
  }, [layoutMode, sidebarModel.order, minimizedIds, focusedId]);
  // y/d/g/Enter act on focusedId, so it must be the session actually on screen
  useEffect(() => {
    if (activeId && activeId !== focusedId) setFocused(activeId);
  }, [activeId, focusedId, setFocused]);

  /** the one "take me to this session" path: sidebar rows, notification clicks, and `a` */
  const restoreAndFocus = useCallback((id: string) => {
    restore(id);
    // grid mode: a maximized widget would cover the target — hand maximize over to it
    setMaximizedId((cur) => (cur && cur !== id ? id : cur));
    if (layoutModeRef.current === 'grid') {
      requestAnimationFrame(() => {
        document.querySelector(`[data-session-id="${id}"]`)?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    }
  }, [restore]);

  const cycleFold = useCallback(() => {
    setFold((cur) => {
      const next = FOLD_CYCLE[(FOLD_CYCLE.indexOf(cur) + 1) % FOLD_CYCLE.length]!;
      localStorage.setItem(SIDEBAR_KEY, next);
      return next;
    });
  }, []);

  const toggleLayoutMode = useCallback(() => {
    setLayoutMode((cur) => {
      const next: LayoutMode = cur === 'grid' ? 'focus' : 'grid';
      localStorage.setItem(LAYOUT_MODE_KEY, next);
      return next;
    });
    setMaximizedId(null);
  }, []);

  const jumpToNeedsYou = useCallback(() => {
    const oldest = sidebarModel.needsYou[0];
    if (oldest) restoreAndFocus(oldest.id);
  }, [sidebarModel.needsYou, restoreAndFocus]);

  const exitOverlay = useCallback((): boolean => {
    if (showExpose) { setShowExpose(false); return true; }
    if (maximizedId) { setMaximizedId(null); return true; }
    return false;
  }, [showExpose, maximizedId]);

  useHotkeys({
    // grid: on-screen grid order; focus: sidebar order, skipping minimized (they can't be shown)
    orderedIds: () => (layoutMode === 'focus'
      ? sidebarModel.order.filter((id) => !minimizedIds.includes(id))
      : [...fullLayout].sort((a, b) => a.y - b.y || a.x - b.x).map((l) => l.i)),
    openCreate: () => setShowCreate(true),
    openQuickCreate: () => setShowQuickCreate(true),
    openMemory: () => setShowMemory(true),
    openLibrary: () => setShowLibrary(true),
    openUsage: () => setShowUsage(true),
    openTemplates: () => setShowTemplates(true),
    openSettings: () => setShowSettings(true),
    openCheatsheet: () => setShowCheatsheet(true),
    toggleMaximizeFocused: () => { if (focusedId) toggleMaximize(focusedId); },
    toggleMinimizeFocused: () => { if (focusedId) toggleMinimize(focusedId); },
    openExpose: () => setShowExpose(true),
    cycleSidebar: cycleFold,
    toggleLayoutMode,
    jumpToNeedsYou,
    anyDialogOpen: () =>
      showCreate || showQuickCreate || showTemplates || showSettings || showUsage || showLibrary || showMemory
        || showServiceDiscovery || showCheatsheet || showExpose,
    closeTopDialog,
    exitOverlay,
  });

  return (
    <>
      <div className="topbar" ref={topbarRef}>
        <h1 className="brand" title="Artus"><img src="/logo.svg" alt="Artus" className="brand-logo" /></h1>
        <button
          className={`icon-btn${fold === 'expanded' ? ' active' : ''}`}
          title={`Sidebar: ${fold} — click to toggle (b)`}
          onClick={cycleFold}
        ><SidebarToggle /></button>
        <span className="topbar-spacer" />
        <NotifyToggle />
        <button className="icon-btn" title="Refresh session list" onClick={() => void refresh()}><Refresh /></button>
        <button className="icon-btn" title="Templates (t)" onClick={() => setShowTemplates(true)}><Templates /></button>
        <button
          className={`icon-btn with-badge${showSystem ? ' active' : ''}`}
          disabled={systemSessions.length === 0}
          title={
            systemSessions.length === 0
              ? 'no system session yet (created by backend tasks like ticket import)'
              : showSystem ? 'hide system session' : `show system session (${systemState!.toLowerCase()})`
          }
          onClick={() => setShowSystem((v) => {
            const next = !v;
            localStorage.setItem('artus.showSystem', next ? '1' : '0');
            return next;
          })}
        >
          <SystemSession />{systemState && <span className={`dot ${systemState}`} />}
        </button>
        <button
          className="icon-btn with-badge"
          title={staleCount > 0 ? `Usage (u) — ${staleCount} idle session${staleCount > 1 ? 's' : ''} to clean up` : 'Usage (u)'}
          onClick={() => setShowUsage(true)}
        >
          <Usage />{staleCount > 0 && <span className="count-badge">{staleCount}</span>}
        </button>
        <button
          className="icon-btn with-badge"
          title={discoveryCount > 0 ? `Skill library (l) — ${discoveryCount} new file(s) in synced sources` : 'Skill library (l)'}
          onClick={() => setShowLibrary(true)}
        >
          <SkillLibrary />{discoveryCount > 0 && <span className="count-badge">{discoveryCount}</span>}
        </button>
        <button
          className="icon-btn with-badge"
          title={pendingMemoryCount > 0 ? `Memory (m) — ${pendingMemoryCount} reflection(s) awaiting approval` : 'Memory (m)'}
          onClick={() => setShowMemory(true)}
        >
          <Memory />{pendingMemoryCount > 0 && <span className="count-badge">{pendingMemoryCount}</span>}
        </button>
        <button className="icon-btn" title="Service discovery" onClick={() => setShowServiceDiscovery(true)}>
          <ServiceDiscovery />
        </button>
        <button className="icon-btn" title="Keyboard shortcuts (?)" onClick={() => setShowCheatsheet(true)}><Shortcuts /></button>
        <button
          className={`icon-btn${layoutMode === 'focus' ? ' active' : ''}`}
          title={layoutMode === 'grid' ? 'Focus layout — one session at a time (v)' : 'Focus layout on — back to grid (v)'}
          onClick={toggleLayoutMode}
        ><FocusMode /></button>
        <button className="icon-btn" title="Exposé — all sessions (e)" onClick={() => setShowExpose(true)}><Expose /></button>
        <button className="icon-btn" title="Settings (,)" onClick={() => setShowSettings(true)}><SettingsIcon /></button>
        <button className="icon-btn" title="Quick session — service + branch + prompt (q)" onClick={() => setShowQuickCreate(true)}>
          <QuickSession />
        </button>
        {/* the one deliberate accent in the topbar: the primary action keeps its label */}
        <button className="primary with-icon" onClick={() => setShowCreate(true)}><New />New Session</button>
      </div>
      <div className={`shell sidebar-${fold}`}>
      <Sidebar model={sidebarModel} fold={fold} showHints={layoutMode === 'focus'} onSelect={restoreAndFocus} />
      <div className={`grid-wrap${layoutMode === 'focus' ? ' focus-mode' : ''}`} ref={gridWrapRef}>
        <GridLayout
          className="layout"
          layout={fullLayout}
          cols={COLS}
          rowHeight={30}
          width={width}
          onLayoutChange={onLayoutChange}
          draggableHandle=".widget-header"
        >
          {visibleIds.map((id) => (
            <div
              key={id}
              data-session-id={id}
              className={`grid-item-wrap${maximizedId === id ? ' maximized' : ''}${minimizedIds.includes(id) ? ' minimized' : ''}${activeId === id ? ' active' : ''}`}
            >
              <SessionWidget
                sessionId={id}
                initialInput={pendingDraft?.id === id ? pendingDraft.text : undefined}
                onClosed={() => onClosed(id)}
                onDuplicated={onCreated}
                isMaximized={maximizedId === id}
                canMaximize={layoutMode === 'grid'}
                onToggleMaximize={() => toggleMaximize(id)}
                onToggleMinimize={() => toggleMinimize(id)}
                onRequestFocus={() => restoreAndFocus(id)}
              />
            </div>
          ))}
        </GridLayout>
      </div>
      </div>
      {showCreate && <CreateSessionDialog onCreated={onCreated} onCancel={() => setShowCreate(false)} />}
      {showQuickCreate && <QuickSessionDialog onCreated={onCreated} onCancel={() => setShowQuickCreate(false)} />}
      {showTemplates && <TemplateManager onClose={() => setShowTemplates(false)} />}
      {showSettings && <SettingsDialog onClose={() => setShowSettings(false)} />}
      {showUsage && <UsageDashboard onClose={() => { setShowUsage(false); refreshStale(); }} />}
      {showLibrary && <LibraryDialog onClose={() => { setShowLibrary(false); refreshDiscoveries(); }} />}
      {showMemory && <MemoryDialog onClose={() => { setShowMemory(false); refreshPendingMemory(); }} />}
      {showServiceDiscovery && <ServiceDiscoveryDialog onClose={() => setShowServiceDiscovery(false)} />}
      {showCheatsheet && <HotkeyCheatsheet onClose={() => setShowCheatsheet(false)} />}
      {showExpose && (
        <ExposeOverlay
          ids={visibleIds}
          views={views}
          minimizedIds={minimizedIds}
          onSelect={selectFromExpose}
          onClose={() => setShowExpose(false)}
        />
      )}
    </>
  );
}
