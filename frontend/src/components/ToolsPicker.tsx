import { useEffect, useState } from 'react';
import { api } from '../api/rest';
import type { Capabilities, ToolCatalogRow } from '../protocol';
import { BUILTIN_TOOLS } from '../toolsModel';

/** Loads the tool catalog once; an unreachable endpoint (or the demo build) reads as an empty catalog. */
export function useToolCatalog(): ToolCatalogRow[] {
  const [rows, setRows] = useState<ToolCatalogRow[]>([]);
  useEffect(() => {
    api.toolCatalog().then(setRows).catch(() => setRows([]));
  }, []);
  return rows;
}

/**
 * Phase 26: catalog tools (checkboxes) plus the three built-in tools blocked by default. Rows render
 * as form-grid pairs so it drops into the create dialog and the template editor alike.
 */
export default function ToolsPicker({ catalog, selected, onSelected, builtins, onBuiltins, capabilities }: {
  catalog: ToolCatalogRow[];
  selected: string[];
  onSelected: (names: string[]) => void;
  builtins: Set<string>;
  onBuiltins: (ids: Set<string>) => void;
  capabilities?: Capabilities;
}) {
  const toggle = (name: string, on: boolean) =>
    onSelected(on ? [...selected, name] : selected.filter((n) => n !== name));
  const toggleBuiltin = (id: string, on: boolean) => {
    const next = new Set(builtins);
    if (on) next.add(id); else next.delete(id);
    onBuiltins(next);
  };
  const showBuiltins = !capabilities || capabilities.toolPresets !== false;
  if (catalog.length === 0 && !showBuiltins) return null;
  return (
    <>
      <label>Tools</label>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {catalog.map(({ entry, missingEnv }) => (
          <label key={entry.name} style={{ display: 'flex', alignItems: 'baseline', gap: 6, fontWeight: 'normal' }}
                 title={entry.kind === 'plugin' ? `${entry.source} plugin at ${entry.path}` : entry.description || entry.name}>
            <input type="checkbox" checked={selected.includes(entry.name)}
                   onChange={(e) => toggle(entry.name, e.target.checked)} />
            <span>{entry.name}</span>
            <span style={{ color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
              {entry.kind === 'plugin' ? 'plugin' : 'MCP'}{entry.description ? ` — ${entry.description}` : ''}
            </span>
            {missingEnv.length > 0 && (
              <span className="chip" style={{ color: 'var(--amber)' }}
                    title="set on the machine running Artus, then restart it, or this tool cannot start">
                needs {missingEnv.map((v) => '${' + v + '}').join(' ')}
              </span>
            )}
          </label>
        ))}
        {catalog.length === 0 && (
          <span style={{ color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
            no catalog tools — add some in Settings → Tools
          </span>
        )}
        {showBuiltins && (
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', marginTop: 2 }}
               title="built-in tools that are blocked unless ticked (their schemas cost context on every first request)">
            {BUILTIN_TOOLS.map((t) => (
              <label key={t.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontWeight: 'normal' }}>
                <input type="checkbox" checked={builtins.has(t.id)} onChange={(e) => toggleBuiltin(t.id, e.target.checked)} />
                {t.label}
              </label>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
