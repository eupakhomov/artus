import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/rest';
import { Close } from '../icons';
import { assetStub, type AssetKind, type LibraryAsset, type ProviderView, type Settings, type Template } from '../protocol';
import AssetPickerDialog from './AssetPickerDialog';
import { AttachedAssetsRow } from './CreateSessionDialog';
import ModelSelect from './ModelSelect';
import ToolsPicker, { useToolCatalog } from './ToolsPicker';
import { builtinOnly, disallowedFor, enabledFrom } from '../toolsModel';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

const PROMOTED_KEYS = ['provider', 'model', 'permissionMode', 'baseBranch', 'kickoffPrompt', 'instructions', 'mcpConfig', 'envVars', 'tools'];

type EnvRow = { key: string; value: string };

function envRowsFrom(config: Record<string, unknown>): EnvRow[] {
  const envVars = config['envVars'];
  if (!envVars || typeof envVars !== 'object') return [];
  return Object.entries(envVars as Record<string, unknown>).map(([key, value]) => ({ key, value: String(value) }));
}

export default function TemplateManager({ onClose }: { onClose: () => void }) {
  const [templates, setTemplates] = useState<Template[]>([]);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [editing, setEditing] = useState<Template | null>(null);

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [providers, setProviders] = useState<ProviderView[]>([]);
  const [provider, setProvider] = useState('');
  const [model, setModel] = useState('');
  const [permissionMode, setPermissionMode] = useState('');
  const [baseBranch, setBaseBranch] = useState('');
  const [kickoffPrompt, setKickoffPrompt] = useState('');
  const [instructions, setInstructions] = useState('');
  const [mcpConfig, setMcpConfig] = useState('');
  const [envRows, setEnvRows] = useState<EnvRow[]>([]);
  const [selectedSkillAssets, setSelectedSkillAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [selectedAgentAssets, setSelectedAgentAssets] = useState<Map<string, LibraryAsset>>(new Map());
  const [assetPickerKind, setAssetPickerKind] = useState<AssetKind | null>(null);
  const toolCatalog = useToolCatalog();
  const [pickedTools, setPickedTools] = useState<string[]>([]);
  const [enabledBuiltins, setEnabledBuiltins] = useState<Set<string>>(new Set());
  const [advanced, setAdvanced] = useState('{}');
  const [error, setError] = useState('');

  const load = () => api.listTemplates().then(setTemplates).catch(() => setTemplates([]));
  useEffect(() => {
    void load();
    api.getSettings().then(setSettings).catch(() => setSettings(null));
    api.listProviders().then(setProviders).catch(() => setProviders([]));
  }, []);

  const activeCapabilities = providers.find((p) => p.id === provider)?.capabilities;

  const startEdit = (t: Template | null) => {
    setEditing(t);
    setName(t?.name ?? '');
    setDescription(t?.description ?? '');
    const config = t?.config ?? {};
    setProvider(typeof config['provider'] === 'string' ? config['provider'] as string : '');
    setModel(typeof config['model'] === 'string' ? config['model'] as string : '');
    setPermissionMode(typeof config['permissionMode'] === 'string' ? config['permissionMode'] as string : '');
    setBaseBranch(typeof config['baseBranch'] === 'string' ? config['baseBranch'] as string : '');
    setKickoffPrompt(typeof config['kickoffPrompt'] === 'string' ? config['kickoffPrompt'] as string : '');
    setInstructions(typeof config['instructions'] === 'string' ? config['instructions'] as string : '');
    setMcpConfig(config['mcpConfig'] ? JSON.stringify(config['mcpConfig'], null, 2) : '');
    setEnvRows(envRowsFrom(config));
    setSelectedSkillAssets(new Map((t?.assets ?? []).filter((a) => a.kind === 'skill').map((a) => [a.id, assetStub(a)])));
    setSelectedAgentAssets(new Map((t?.assets ?? []).filter((a) => a.kind === 'agent').map((a) => [a.id, assetStub(a)])));
    setPickedTools(Array.isArray(config['tools']) ? (config['tools'] as unknown[]).filter((x): x is string => typeof x === 'string') : []);
    const builtinsManaged = !('disallowedTools' in config) || builtinOnly(config['disallowedTools']);
    setEnabledBuiltins(builtinsManaged ? enabledFrom(config['disallowedTools']) : new Set());
    const rest = Object.fromEntries(Object.entries(config).filter(([k]) =>
      !PROMOTED_KEYS.includes(k) && !(k === 'disallowedTools' && builtinsManaged)));
    setAdvanced(Object.keys(rest).length > 0 ? JSON.stringify(rest, null, 2) : '{}');
    setError('');
  };

  const save = async () => {
    setError('');
    try {
      const config: Record<string, unknown> = JSON.parse(advanced || '{}');
      if (provider) config['provider'] = provider;
      if (model) config['model'] = model;
      if (permissionMode) config['permissionMode'] = permissionMode;
      if (baseBranch.trim()) config['baseBranch'] = baseBranch.trim();
      if (kickoffPrompt.trim()) config['kickoffPrompt'] = kickoffPrompt.trim();
      if (instructions.trim()) config['instructions'] = instructions.trim();
      if (mcpConfig.trim()) config['mcpConfig'] = JSON.parse(mcpConfig);
      if (pickedTools.length > 0) config['tools'] = pickedTools;
      const disallowed = disallowedFor(enabledBuiltins);
      if (disallowed && !('disallowedTools' in config)) config['disallowedTools'] = disallowed;
      const envEntries = envRows.filter((r) => r.key.trim());
      if (envEntries.length > 0) {
        config['envVars'] = Object.fromEntries(envEntries.map((r) => [r.key.trim(), r.value]));
      }
      const body = {
        name, description, config,
        skillAssetIds: [...selectedSkillAssets.keys()],
        agentAssetIds: [...selectedAgentAssets.keys()],
      };
      if (editing) await api.updateTemplate(editing.id, body);
      else await api.createTemplate(body);
      startEdit(null);
      await load();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    }
  };

  const backdropDismiss = useBackdropDismiss(onClose);

  return (
    <>
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2>Session templates</h2>
        <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 14 }}>
          <tbody>
            {templates.map((t) => (
              <tr key={t.id} style={{ borderBottom: '1px solid var(--border)' }}>
                <td style={{ padding: '6px 4px', fontWeight: 600 }}>{t.name}</td>
                <td style={{ padding: '6px 4px', color: 'var(--muted)' }}>{t.description}</td>
                <td style={{ padding: '6px 4px', color: 'var(--muted)', fontSize: 'calc(12px * var(--font-scale))' }}>
                  {t.assets.length > 0 && `${t.assets.length} asset${t.assets.length > 1 ? 's' : ''}`}
                </td>
                <td style={{ padding: '6px 4px', textAlign: 'right', whiteSpace: 'nowrap' }}>
                  <button onClick={() => startEdit(t)}>Edit</button>{' '}
                  <button className="danger" onClick={() => void api.deleteTemplate(t.id).then(load)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="form-grid">
          <label>Name</label>
          <input value={name} onChange={(e) => setName(e.target.value)} />

          <label>Description</label>
          <input value={description} onChange={(e) => setDescription(e.target.value)} />

          <label>Provider</label>
          <select value={provider} onChange={(e) => { setProvider(e.target.value); setModel(''); setPermissionMode(''); }}>
            <option value="">session default</option>
            {providers.map((p) => <option key={p.id} value={p.id}>{p.id}</option>)}
          </select>

          <label>Model</label>
          <ModelSelect value={model} onChange={setModel} capabilities={activeCapabilities} />

          <label>Permissions</label>
          <select value={permissionMode} onChange={(e) => setPermissionMode(e.target.value)}>
            <option value="">provider default</option>
            {(!activeCapabilities || activeCapabilities.permissionModes.includes('default')) && (
              <option value="default">ask for edits & commands</option>
            )}
            {(!activeCapabilities || activeCapabilities.permissionModes.includes('acceptEdits')) && (
              <option value="acceptEdits">auto-accept edits</option>
            )}
            {(!activeCapabilities || activeCapabilities.permissionModes.includes('plan')) && (
              <option value="plan">plan first</option>
            )}
            {(!activeCapabilities || activeCapabilities.permissionModes.includes('bypassPermissions')) && (
              <option value="bypassPermissions">bypass all approval (including Bash)</option>
            )}
          </select>

          <label>Base branch</label>
          <input
            value={baseBranch}
            onChange={(e) => setBaseBranch(e.target.value)}
            placeholder="optional default, e.g. develop — prefills the create dialog"
          />

          <label>Skills</label>
          <AttachedAssetsRow
            assets={[...selectedSkillAssets.values()]}
            onBrowse={() => setAssetPickerKind('skill')}
            buttonLabel="Add skills…"
          />

          <label>Agents</label>
          <AttachedAssetsRow
            assets={[...selectedAgentAssets.values()]}
            onBrowse={() => setAssetPickerKind('agent')}
            buttonLabel="Add agents…"
          />

          <label>Kickoff prompt</label>
          <textarea
            style={{ gridColumn: '2 / -1' }}
            rows={2}
            value={kickoffPrompt}
            onChange={(e) => setKickoffPrompt(e.target.value)}
            placeholder="auto-sent on session creation; use {{placeholder}} for values filled in per-session"
          />

          <label>Instructions</label>
          <textarea
            style={{ gridColumn: '2 / -1' }}
            rows={2}
            value={instructions}
            onChange={(e) => setInstructions(e.target.value)}
            placeholder="extra system instructions"
          />

          <ToolsPicker
            catalog={toolCatalog}
            selected={pickedTools}
            onSelected={setPickedTools}
            builtins={enabledBuiltins}
            onBuiltins={setEnabledBuiltins}
            capabilities={activeCapabilities}
          />

          <label>MCP servers (JSON)</label>
          <textarea
            style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
            rows={3}
            value={mcpConfig}
            onChange={(e) => setMcpConfig(e.target.value)}
            placeholder='{"my-server": {"command": "npx", "args": ["-y", "my-mcp-server"]}}'
          />

          <label>Env vars</label>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            {envRows.map((row, i) => (
              <div key={i} style={{ display: 'flex', gap: 6 }}>
                <input
                  style={{ flex: 1 }}
                  value={row.key}
                  placeholder="KEY"
                  onChange={(e) => setEnvRows(envRows.map((r, j) => (j === i ? { ...r, key: e.target.value } : r)))}
                />
                <input
                  style={{ flex: 2 }}
                  value={row.value}
                  placeholder="value"
                  onChange={(e) => setEnvRows(envRows.map((r, j) => (j === i ? { ...r, value: e.target.value } : r)))}
                />
                <button className="icon-btn" title="remove" onClick={() => setEnvRows(envRows.filter((_, j) => j !== i))}><Close /></button>
              </div>
            ))}
            <button onClick={() => setEnvRows([...envRows, { key: '', value: '' }])}>+ Add env var</button>
          </div>

          <label>Advanced (raw JSON)</label>
          <textarea
            style={{ gridColumn: '2 / -1', fontFamily: 'monospace' }}
            rows={3}
            value={advanced}
            onChange={(e) => setAdvanced(e.target.value)}
            placeholder='any other config key, e.g. {"maxTurns": 30, "ecosystemPath": "/path"}'
          />
        </div>
        {error && <div className="error-text" style={{ marginTop: 8 }}>{error}</div>}
        <div className="actions">
          {editing && <button onClick={() => startEdit(null)}>New instead</button>}
          <button onClick={onClose}>Close</button>
          <button className="primary" disabled={!name.trim()} onClick={() => void save()}>
            {editing ? 'Update' : 'Create'} template
          </button>
        </div>
      </div>
    </div>
    {assetPickerKind && (
      <AssetPickerDialog
        kind={assetPickerKind}
        semanticAvailable={!!settings?.voyageConfigured && !!settings?.libraryVectorize}
        selected={assetPickerKind === 'skill' ? selectedSkillAssets : selectedAgentAssets}
        onConfirm={(picked) => {
          if (assetPickerKind === 'skill') setSelectedSkillAssets(picked); else setSelectedAgentAssets(picked);
          setAssetPickerKind(null);
        }}
        onClose={() => setAssetPickerKind(null)}
      />
    )}
    </>
  );
}
