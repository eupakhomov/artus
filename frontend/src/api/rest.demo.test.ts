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
    expect(await api.memoryPendingCount()).toBe(2);
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

describe('memory and library demo data', () => {
  it('serves the vault with computed links, backlinks and a dangling reference', async () => {
    const docs = await api.memoryDocs({ status: 'ACTIVE' });
    expect(docs.length).toBe(4);
    expect(docs.every((d) => d.status === 'ACTIVE')).toBe(true);
    expect((await api.memoryDocs({ status: 'ARCHIVED' })).map((d) => d.name)).toEqual(['old-release-notes']);
    const layout = docs.find((d) => d.name === 'http-service-layout')!;
    const detail = await api.memoryDoc(layout.id);
    expect(detail.outgoing.find((l) => l.slug === 'release-checklist')?.dangling).toBe(true);
    expect(detail.outgoing.find((l) => l.slug === 'git-workflow')?.dangling).toBe(false);
    const git = await api.memoryDoc(docs.find((d) => d.name === 'git-workflow')!.id);
    expect(git.backlinks.map((l) => l.slug)).toContain('testing-conventions');
    await expect(api.memoryDoc('nope')).rejects.toMatchObject({ status: 404 });
  });

  it('serves one memory proposal and one skill proposal, and searches notes and episodes', async () => {
    const pending = await api.memoryProposals('PENDING');
    expect(pending.map((p) => p.kind).sort()).toEqual(['memory', 'skill']);
    expect(await api.memoryProposals('APPROVED')).toEqual([]);
    const hits = await api.memorySearch('timezone', { kind: 'all' });
    expect(hits.some((h) => h.kind === 'semantic' && h.name === 'dates-and-timezones')).toBe(true);
    expect(hits.some((h) => h.kind === 'episodic')).toBe(true);
    expect(await api.memorySearch('zzzz')).toEqual([]);
  });

  it('every evidence seq a proposal cites is quoted', async () => {
    for (const p of await api.memoryProposals('PENDING')) {
      const ops = Array.isArray(p.ops) ? p.ops : [p.ops];
      for (const seq of ops.flatMap((o) => o.evidence ?? [])) expect(p.evidence[String(seq)], `${p.id} #${seq}`).toBeTruthy();
    }
  });

  it('filters the library by kind and status, and serves content and sources', async () => {
    const all = await api.libraryAssets({ status: 'ACTIVE' });
    expect(all.filter((a) => a.kind === 'skill').length).toBe(3);
    expect(all.filter((a) => a.kind === 'agent').length).toBe(2);
    expect((await api.libraryAssets({ kind: 'agent', status: 'ACTIVE' })).every((a) => a.kind === 'agent')).toBe(true);
    expect((await api.libraryAssets({ status: 'ARCHIVED' })).length).toBe(1);
    expect((await api.libraryAssets({ status: 'ACTIVE', q: 'review' })).map((a) => a.name)).toContain('pr-reviewer');
    const content = await api.libraryAssetContent(all[0]!.id);
    expect(content.content).toContain(all[0]!.name);
    const sources = await api.librarySources();
    expect(sources.length).toBe(2);
    expect(sources[0]!.assetCount).toBe(3);
    expect(sources[0]!.discoveries.length).toBe(1);
    expect((await api.librarySearch('commit')).map((h) => h.asset.name)).toContain('write-commit-message');
  });

  it('serves templates whose skills and agents resolve to library entries', async () => {
    const templates = await api.listTemplates();
    expect(templates.map((t) => t.name)).toEqual(['Bug fix', 'Review', 'Release']);
    const library = await api.libraryAssets({});
    for (const t of templates) {
      for (const a of t.assets) expect(library.find((l) => l.id === a.id)?.name, `${t.name} → ${a.id}`).toBe(a.name);
    }
    expect(templates[0]!.assets.map((a) => a.kind).sort()).toEqual(['agent', 'skill']);
    expect(templates[2]!.assets.some((a) => a.status === 'ARCHIVED')).toBe(true);
    await expect(api.createTemplate({ name: 'x', description: '', config: {}, skillAssetIds: [], agentAssetIds: [] })).rejects.toMatchObject({ status: 501 });
  });

  it('keeps every write a 501', async () => {
    await expect(api.memoryDiscardProposal('x')).rejects.toMatchObject({ status: 501 });
    await expect(api.libraryDeleteAsset('x')).rejects.toMatchObject({ status: 501 });
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
