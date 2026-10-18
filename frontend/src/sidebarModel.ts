import type { SessionSummary } from './protocol';
import type { SessionView } from './store/store';

export interface NeedsYouItem {
  id: string;
  /** unique across sessions — a sidecar numbers its requests p1, p2… per process, so requestId alone collides */
  key: string;
  requestId: string;
  toolName: string;
  isPlan: boolean;
  input: Record<string, unknown>;
}

export interface SidebarRow {
  id: string;
  /** 1 = a child session nested under its parent (phase 7.4 orchestration) */
  depth: 0 | 1;
  minimized: boolean;
}

export interface SidebarGroup {
  key: string;
  label: string;
  rows: SidebarRow[];
}

export interface SidebarModel {
  needsYou: NeedsYouItem[];
  groups: SidebarGroup[];
  /** every row id, top to bottom — the focus-mode order for j/k and 1–9 */
  order: string[];
}

const SYSTEM_KEY = '\u0000system';

function serviceLabel(v: SessionView | undefined): string {
  const path = v?.servicePath ?? v?.repoPath;
  return path ? path.split('/').pop() || path : '…';
}

/**
 * Pure layout of the phase-16 sidebar. `ids` is creation order (the Dashboard's
 * sessionIds, then any shown system sessions) and stays the row order inside a group, so
 * rows never jump around as states change. A child nests under its parent — in the
 * parent's group, even when its own service differs — while that parent is open; an
 * orphaned child falls back to top level in its own service's group.
 */
export function buildSidebarModel(
  ids: string[],
  views: Record<string, SessionView>,
  minimizedIds: string[],
  systemIds: ReadonlySet<string>,
): SidebarModel {
  const open = new Set(ids);
  const minimized = new Set(minimizedIds);
  const childrenOf = new Map<string, string[]>();
  for (const id of ids) {
    const parent = views[id]?.parentSessionId;
    if (parent && parent !== id && open.has(parent)) {
      childrenOf.set(parent, [...(childrenOf.get(parent) ?? []), id]);
    }
  }
  const nested = new Set([...childrenOf.values()].flat());

  const groups = new Map<string, SidebarGroup>();
  for (const id of ids) {
    if (nested.has(id)) continue;
    const isSystem = systemIds.has(id);
    const label = isSystem ? 'System' : serviceLabel(views[id]);
    const key = isSystem ? SYSTEM_KEY : label;
    const group = groups.get(key) ?? { key, label, rows: [] };
    group.rows.push({ id, depth: 0, minimized: minimized.has(id) });
    for (const child of childrenOf.get(id) ?? []) {
      group.rows.push({ id: child, depth: 1, minimized: minimized.has(child) });
    }
    groups.set(key, group);
  }
  const sorted = [...groups.values()].sort((a, b) => {
    if (a.key === SYSTEM_KEY) return 1;
    if (b.key === SYSTEM_KEY) return -1;
    return a.label.localeCompare(b.label);
  });

  const needsYou = ids
    .filter((id) => views[id]?.pendingPermission)
    .sort((a, b) => sinceMs(views[a]) - sinceMs(views[b]))
    .map((id) => {
      const p = views[id]!.pendingPermission!;
      return { id, key: `${id}:${p.requestId}`, requestId: p.requestId, toolName: p.toolName, isPlan: p.plan !== null, input: p.input };
    });

  return { needsYou, groups: sorted, order: sorted.flatMap((g) => g.rows.map((r) => r.id)) };
}

function sinceMs(v: SessionView | undefined): number {
  return v?.stateSince ? new Date(v.stateSince).getTime() : Number.MAX_SAFE_INTEGER;
}

/**
 * Sidebar History (phase 23): the newest-closed sessions, minus the ones this browser hid.
 * The backend already orders and caps; re-sorting and re-capping here keeps the section right
 * when a just-closed row is merged in locally and when hiding one should pull the next one up.
 */
export function selectHistory(
  summaries: SessionSummary[],
  hiddenIds: ReadonlySet<string>,
  limit: number,
): SessionSummary[] {
  const closedMs = (s: SessionSummary) => new Date(s.closedAt ?? s.updatedAt).getTime();
  return summaries
    .filter((s) => s.kind !== 'system' && !hiddenIds.has(s.id))
    .sort((a, b) => closedMs(b) - closedMs(a))
    .slice(0, limit);
}

/** Compact relative age for the sidebar: <1m, 4m, 2h, 3d. */
export function shortAge(iso: string | null, now: number): string {
  if (!iso) return '';
  const s = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000));
  if (s < 60) return '<1m';
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

/**
 * Stored sidebar fold → a valid one. Phase 17 dropped the `hidden` state; an old stored
 * `hidden` (or anything unknown/absent) falls back to the width-based default or `rail`.
 */
export function normalizeFold(stored: string | null, windowWidth: number): 'expanded' | 'rail' {
  if (stored === 'expanded' || stored === 'rail') return stored;
  if (stored === 'hidden') return 'rail';
  return windowWidth < 1100 ? 'rail' : 'expanded';
}
