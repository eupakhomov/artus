import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { Close } from '../icons';
import type { ToolCandidate, ToolCatalogRow, ToolEntry, ToolProbeResult } from '../protocol';

const BLANK_SERVER = '{\n  "command": "npx",\n  "args": ["-y", "some-mcp-server"],\n  "env": { "API_KEY": "${MY_API_KEY}" }\n}';

type Draft = { original: string | null; entry: ToolEntry; serverText: string; enabledText: string };

function summary(e: ToolEntry): string {
  if (e.kind === 'plugin') return `${e.source} plugin · ${e.path}`;
  const s = e.server ?? {};
  return typeof s['url'] === 'string' ? `http · ${s['url']}` : `stdio · ${[s['command'], ...((s['args'] as string[] | undefined) ?? [])].join(' ')}`;
}

function candidateEntry(c: ToolCandidate): ToolEntry {
  return {
    name: c.name, kind: c.kind, source: c.kind === 'plugin' ? c.source : null, path: c.path, server: c.server,
    description: c.kind === 'plugin' && c.servers.length > 0 ? `MCP: ${c.servers.join(', ')}` : '',
    enabledTools: [], preApprove: false,
  };
}

/** Settings → Tools (phase 26): the catalog sessions and templates pick from. Non-secret only — use ${ENV_VAR}. */
export default function ToolCatalogSettings() {
  const [rows, setRows] = useState<ToolCatalogRow[]>([]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState<string | null>(null);
  const [results, setResults] = useState<Record<string, Record<string, ToolProbeResult>>>({});
  const [candidates, setCandidates] = useState<ToolCandidate[] | null>(null);
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    api.toolCatalog().then(setRows).catch((e) => setError(e instanceof ApiError ? e.message : String(e)));
  }, []);

  const persist = async (entries: ToolEntry[]): Promise<boolean> => {
    setError('');
    setSaving(true);
    try {
      setRows(await api.saveToolCatalog(entries));
      return true;
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      return false;
    } finally {
      setSaving(false);
    }
  };

  const entries = rows.map((r) => r.entry);

  const startEdit = (entry: ToolEntry | null) => {
    const e: ToolEntry = entry ?? { name: '', kind: 'mcp', description: '', server: null, enabledTools: [], preApprove: false, source: null, path: null };
    setDraft({
      original: entry?.name ?? null, entry: e,
      serverText: e.server ? JSON.stringify(e.server, null, 2) : BLANK_SERVER,
      enabledText: e.enabledTools.join(', '),
    });
    setError('');
  };

  const saveDraft = async () => {
    if (!draft) return;
    let server: Record<string, unknown> | null = null;
    if (draft.entry.kind === 'mcp') {
      try {
        server = JSON.parse(draft.serverText) as Record<string, unknown>;
      } catch (e) {
        setError(`server block is not valid JSON: ${String(e)}`);
        return;
      }
    }
    const next: ToolEntry = {
      ...draft.entry, name: draft.entry.name.trim(), server,
      enabledTools: draft.enabledText.split(',').map((t) => t.trim()).filter(Boolean),
    };
    const list = draft.original === null ? [...entries, next] : entries.map((e) => (e.name === draft.original ? next : e));
    if (await persist(list)) setDraft(null);
  };

  const test = async (name: string) => {
    setTesting(name);
    try {
      const res = await api.testTool(name);
      setResults((r) => ({ ...r, [name]: res }));
    } catch (e) {
      setResults((r) => ({ ...r, [name]: { [name]: { ok: false, tools: [], error: e instanceof ApiError ? e.message : String(e) } } }));
    } finally {
      setTesting(null);
    }
  };

  const scan = async () => {
    setScanning(true);
    setError('');
    try {
      setCandidates(await api.importableTools());
      setTicked(new Set());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setScanning(false);
    }
  };

  const importTicked = async () => {
    const add = (candidates ?? []).filter((c) => ticked.has(`${c.kind}:${c.name}`)).map(candidateEntry);
    if (add.length > 0 && await persist([...entries, ...add])) setCandidates(null);
  };

  const small = { color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' } as const;

  return (
    <>
      <h3 className="settings-h">Tools</h3>
      <div style={{ ...small, marginBottom: 8 }}>
        MCP servers and plugins a session can be given in the create dialog or a template. Picked at creation and baked in — editing an
        entry never changes a running session. Put secrets as <code>{'${ENV_VAR}'}</code>: they are read from the environment of the
        machine running Artus, never stored.
      </div>
      {rows.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 8 }}>
          <tbody>
            {rows.map(({ entry, missingEnv }) => {
              const res = results[entry.name];
              return (
                <tr key={entry.name} style={{ borderBottom: '1px solid var(--border)', verticalAlign: 'top' }}>
                  <td style={{ padding: '6px 4px', fontWeight: 600, whiteSpace: 'nowrap' }}>{entry.name}</td>
                  <td style={{ padding: '6px 4px', ...small, wordBreak: 'break-all' }}>
                    {summary(entry)}
                    {entry.enabledTools.length > 0 && <div>only: {entry.enabledTools.join(', ')}</div>}
                    {missingEnv.length > 0 && (
                      <div style={{ color: 'var(--amber)' }}>needs {missingEnv.map((v) => '${' + v + '}').join(' ')} on this host</div>
                    )}
                    {res && Object.entries(res).map(([server, r]) => (
                      <div key={server} style={{ color: r.ok ? 'var(--green)' : 'var(--red)' }}>
                        {server}: {r.ok ? `${r.tools.length} tool${r.tools.length === 1 ? '' : 's'}` : r.error}
                      </div>
                    ))}
                  </td>
                  <td style={{ padding: '6px 4px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                    <button className={testing === entry.name ? 'pulse' : ''} disabled={testing !== null}
                            title="start the server, list its tools, stop it (30 s budget)"
                            onClick={() => void test(entry.name)}>Test</button>{' '}
                    <button onClick={() => startEdit(entry)}>Edit</button>{' '}
                    <button className="icon-btn danger" title="Remove from the catalog" disabled={saving}
                            onClick={() => void persist(entries.filter((e) => e.name !== entry.name))}><Close /></button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {!draft && !candidates && (
        <div style={{ display: 'flex', gap: 6 }}>
          <button onClick={() => startEdit(null)}>Add MCP server</button>
          <button className={scanning ? 'pulse' : ''} disabled={scanning} onClick={() => void scan()}
                  title="read-only scan of ~/.claude.json, installed Claude plugins and the Codex plugin directory">Import…</button>
        </div>
      )}
      {draft && (
        <div className="form-grid" style={{ marginTop: 8 }}>
          <label>Name</label>
          <input value={draft.entry.name} placeholder="becomes the mcp__<name>__ tool prefix"
                 onChange={(e) => setDraft({ ...draft, entry: { ...draft.entry, name: e.target.value } })} />
          <label>Description</label>
          <input value={draft.entry.description}
                 onChange={(e) => setDraft({ ...draft, entry: { ...draft.entry, description: e.target.value } })} />
          {draft.entry.kind === 'plugin' ? (
            <>
              <label>Plugin</label>
              <div style={small}>{draft.entry.source} plugin at {draft.entry.path}</div>
            </>
          ) : (
            <>
              <label>Server (JSON)</label>
              <textarea style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }} rows={6} value={draft.serverText}
                        onChange={(e) => setDraft({ ...draft, serverText: e.target.value })} />
              <label>Only these tools</label>
              <input value={draft.enabledText} placeholder="comma-separated allow-list; empty = every tool the server offers"
                     onChange={(e) => setDraft({ ...draft, enabledText: e.target.value })} />
              <label>Pre-approve</label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={draft.entry.preApprove}
                       onChange={(e) => setDraft({ ...draft, entry: { ...draft.entry, preApprove: e.target.checked } })} />
                skip the approval prompt for this server's tools (Claude only — Codex has no pre-approval)
              </label>
            </>
          )}
          <div className="actions" style={{ gridColumn: '1 / -1' }}>
            <button onClick={() => setDraft(null)}>Cancel</button>
            <button className="primary" disabled={saving || !draft.entry.name.trim()} onClick={() => void saveDraft()}>
              {draft.original === null ? 'Add' : 'Update'}
            </button>
          </div>
        </div>
      )}
      {candidates && (
        <div style={{ marginTop: 8 }}>
          {candidates.length === 0 && <div style={small}>nothing importable found on this host</div>}
          {candidates.map((c) => {
            const key = `${c.kind}:${c.name}`;
            const exists = entries.some((e) => e.name === c.name);
            // the same name from two sources (a plugin and a ~/.claude.json server, two marketplaces) would
            // fail the whole import as a duplicate — only one of them can be ticked at a time
            const clash = !ticked.has(key) && candidates.some((o) => o !== c && o.name === c.name && ticked.has(`${o.kind}:${o.name}`));
            return (
              <label key={key} style={{ display: 'flex', alignItems: 'baseline', gap: 6, fontWeight: 'normal', padding: '2px 0' }}>
                <input type="checkbox" disabled={exists || clash} checked={ticked.has(key)}
                       onChange={(e) => setTicked((t) => { const n = new Set(t); if (e.target.checked) n.add(key); else n.delete(key); return n; })} />
                <span>{c.name}</span>
                <span style={small}>
                  {c.kind === 'plugin' ? `${c.source} plugin` : 'MCP server'} · {c.origin}
                  {c.servers.length > 0 && c.kind === 'plugin' ? ` · servers: ${c.servers.join(', ')}` : ''}
                  {exists ? ' · already in the catalog' : clash ? ' · same name as a ticked row' : ''}
                  {c.redacted.length > 0 ? ` · secrets replaced by ${c.redacted.map((v) => '${' + v + '}').join(' ')}` : ''}
                </span>
              </label>
            );
          })}
          <div className="actions">
            <button onClick={() => setCandidates(null)}>Cancel</button>
            <button className="primary" disabled={saving || ticked.size === 0} onClick={() => void importTicked()}>
              Add {ticked.size || ''} selected
            </button>
          </div>
        </div>
      )}
      {error && <div className="error-text" style={{ marginTop: 6 }}>{error}</div>}
    </>
  );
}
