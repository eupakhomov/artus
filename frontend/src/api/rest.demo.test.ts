import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { api, ApiError } from './rest.demo';

describe('rest.demo', () => {
  it('lists open sessions and closed history separately', async () => {
    const open = await api.listSessions();
    expect(open.length).toBeGreaterThanOrEqual(5);
    expect(open.every((s) => s.state !== 'CLOSED')).toBe(true);
    const history = await api.sessionHistory(5);
    expect(history.length).toBe(2);
    expect(history.every((s) => s.state === 'CLOSED' && s.closedAt)).toBe(true);
    expect(Date.parse(history[0]!.closedAt!)).toBeGreaterThanOrEqual(Date.parse(history[1]!.closedAt!));
  });

  it('reflects a rename in the next read', async () => {
    const [first] = await api.listSessions();
    await api.patchSession(first!.id, { name: 'Renamed in demo' });
    expect((await api.sessionDetail(first!.id)).session.name).toBe('Renamed in demo');
  });

  it('serves changes, settings and diagram status from fixtures', async () => {
    const open = await api.listSessions();
    const withChanges = open.find((s) => s.branch === 'fix/date-parsing')!;
    const changes = await api.sessionChanges(withChanges.id);
    expect(changes.files.length).toBeGreaterThan(0);
    expect((await api.sessionChangedFile(withChanges.id, changes.files[0]!.path)).content).toContain('UTC');
    expect((await api.visualizeStatus(withChanges.id)).latest?.diagramType).toBe('flowchart');
    expect((await api.getSettings()).authTokenRequired).toBe(false);
    expect(await api.memoryPendingCount()).toBe(0);
  });

  it('rejects anything not implemented with a 501 ApiError', async () => {
    await expect(api.libraryScan('dir', '/x')).rejects.toMatchObject({ status: 501 });
    await expect(api.createSession({})).rejects.toBeInstanceOf(ApiError);
    await expect(api.raw('POST', '/api/sessions/x/git/commit', {})).rejects.toMatchObject({ status: 501 });
  });

  it('serves the recorded Git panel reads through api.raw', async () => {
    const open = await api.listSessions();
    const dev = open.find((s) => s.branch === 'fix/date-parsing')!;
    const status = await api.raw<{ branch: string; dirty: string[] }>('GET', `/api/sessions/${dev.id}/git/status`);
    expect(status.branch).toBe('fix/date-parsing');
    expect(status.dirty.length).toBeGreaterThan(0);
    const log = await api.raw<{ subject: string }[]>('GET', `/api/sessions/${dev.id}/git/log`);
    expect(log.length).toBeGreaterThan(0);
    expect((await api.raw<{ diff: string }>('GET', `/api/sessions/${dev.id}/git/diff`)).diff).toContain('getUTCDate');
    await expect(api.raw('POST', `/api/sessions/${dev.id}/git/push`, {})).rejects.toMatchObject({ status: 501 });
  });
});

describe('demo bundle sources never touch the network', () => {
  const roots = ['../demo/', '../api/'].map((p) => fileURLToPath(new URL(p, import.meta.url)));
  const sources = roots.flatMap((r) => readdirSync(r)
    .filter((f) => /\.(ts|tsx)$/.test(f) && !f.endsWith('.test.ts') && (r.endsWith('/demo/') || f.includes('.demo.')))
    .map((f) => [f, readFileSync(r + f, 'utf8')] as const));
  it.each(sources)('%s has no fetch/WebSocket/XHR', (_f, src) => {
    const code = src.replace(/\/\/.*$/gm, '').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(code).not.toMatch(/\bfetch\s*\(|new\s+WebSocket|XMLHttpRequest|EventSource/);
  });
});
