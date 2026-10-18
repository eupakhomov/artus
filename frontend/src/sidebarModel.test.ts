import { beforeEach, describe, expect, it } from 'vitest';
import { useStore, type SessionView } from './store/store';
import { buildSidebarModel, normalizeFold, selectHistory, shortAge } from './sidebarModel';
import type { SessionSummary } from './protocol';

// views are built through the store's own seed(), so they start from the real emptyView()
function view(id: string, patch: Partial<SessionView>): void {
  useStore.getState().seed(id, (v) => ({ ...v, ...patch }));
}

const views = () => useStore.getState().views;
const NONE = new Set<string>();

beforeEach(() => {
  useStore.setState({ views: {}, focusedId: null });
});

describe('buildSidebarModel', () => {
  it('groups by service name, sorted, keeping creation order inside a group', () => {
    view('a', { servicePath: '/p/web' });
    view('b', { servicePath: '/p/api' });
    view('c', { servicePath: '/p/web' });
    const m = buildSidebarModel(['a', 'b', 'c'], views(), [], NONE);
    expect(m.groups.map((g) => g.label)).toEqual(['api', 'web']);
    expect(m.groups[1]!.rows.map((r) => r.id)).toEqual(['a', 'c']);
    expect(m.order).toEqual(['b', 'a', 'c']);
  });

  it('nests a child under its open parent, even across services', () => {
    view('p', { servicePath: '/p/api' });
    view('c', { servicePath: '/p/web', parentSessionId: 'p' });
    const m = buildSidebarModel(['p', 'c'], views(), [], NONE);
    expect(m.groups).toHaveLength(1);
    expect(m.groups[0]!.rows).toEqual([
      { id: 'p', depth: 0, minimized: false },
      { id: 'c', depth: 1, minimized: false },
    ]);
  });

  it('puts an orphaned child at top level in its own service group', () => {
    view('c', { servicePath: '/p/web', parentSessionId: 'gone' });
    const m = buildSidebarModel(['c'], views(), [], NONE);
    expect(m.groups[0]).toMatchObject({ label: 'web', rows: [{ id: 'c', depth: 0 }] });
  });

  it('puts the system session in a System group last, and marks minimized rows', () => {
    view('s', { servicePath: '/p/aaa' });
    view('a', { servicePath: '/p/zzz' });
    const m = buildSidebarModel(['a', 's'], views(), ['a'], new Set(['s']));
    expect(m.groups.map((g) => g.label)).toEqual(['zzz', 'System']);
    expect(m.groups[0]!.rows[0]!.minimized).toBe(true);
  });

  it('lists pending permissions oldest first', () => {
    const pending = (requestId: string) => ({ requestId, toolName: 'Bash', input: { command: 'ls' }, plan: null });
    view('new', { pendingPermission: pending('r2'), stateSince: '2026-10-01T10:05:00Z' });
    view('old', { pendingPermission: pending('r1'), stateSince: '2026-10-01T10:00:00Z' });
    view('idle', {});
    const m = buildSidebarModel(['new', 'old', 'idle'], views(), [], NONE);
    expect(m.needsYou.map((n) => n.requestId)).toEqual(['r1', 'r2']);
    expect(m.needsYou[0]).toMatchObject({ id: 'old', toolName: 'Bash', isPlan: false });
  });

  it('keys items by session too — two sidecars both number their first request p1', () => {
    const pending = { requestId: 'p1', toolName: 'Bash', input: {}, plan: null };
    view('x', { pendingPermission: pending });
    view('y', { pendingPermission: pending });
    const keys = buildSidebarModel(['x', 'y'], views(), [], NONE).needsYou.map((n) => n.key);
    expect(new Set(keys).size).toBe(2);
  });
});

describe('shortAge', () => {
  const now = new Date('2026-10-01T12:00:00Z').getTime();
  it('formats compactly', () => {
    expect(shortAge('2026-10-01T11:59:30Z', now)).toBe('<1m');
    expect(shortAge('2026-10-01T11:56:00Z', now)).toBe('4m');
    expect(shortAge('2026-10-01T10:00:00Z', now)).toBe('2h');
    expect(shortAge('2026-09-28T12:00:00Z', now)).toBe('3d');
    expect(shortAge(null, now)).toBe('');
  });
});

describe('normalizeFold', () => {
  it('keeps the two valid states', () => {
    expect(normalizeFold('expanded', 500)).toBe('expanded');
    expect(normalizeFold('rail', 2000)).toBe('rail');
  });
  it('migrates the removed hidden state to rail', () => {
    expect(normalizeFold('hidden', 2000)).toBe('rail');
  });
  it('defaults by window width when nothing valid is stored', () => {
    expect(normalizeFold(null, 1000)).toBe('rail');
    expect(normalizeFold(null, 1400)).toBe('expanded');
    expect(normalizeFold('garbage', 1400)).toBe('expanded');
  });
});

describe('selectHistory', () => {
  const s = (id: string, closedAt: string | null, kind: 'user' | 'system' = 'user') =>
    ({ id, kind, closedAt, updatedAt: '2026-01-01T00:00:00Z' }) as SessionSummary;

  it('sorts newest close first, drops system and hidden ones, and caps', () => {
    const list = [
      s('a', '2026-10-01T10:00:00Z'),
      s('b', '2026-10-01T12:00:00Z'),
      s('sys', '2026-10-01T13:00:00Z', 'system'),
      s('c', '2026-10-01T11:00:00Z'),
      s('d', '2026-10-01T09:00:00Z'),
    ];
    expect(selectHistory(list, new Set(['c']), 2).map((x) => x.id)).toEqual(['b', 'a']);
  });

  it('falls back to updatedAt for rows closed before closedAt existed', () => {
    const list = [s('old', null), s('new', '2026-10-01T10:00:00Z')];
    expect(selectHistory(list, new Set(), 5).map((x) => x.id)).toEqual(['new', 'old']);
  });
});
