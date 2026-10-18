import { useEffect, useState } from 'react';
import { api } from '../api/rest';
import type { MemoryDoc, MemoryDocDetail, MemoryProposal, MemoryProposedOp, MemorySearchHit, SkillProposalMeta } from '../protocol';
import { MemoryDoc as MemoryDocIcon, MemoryDocEditable as MemoryDocEditableIcon } from '../icons';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';

function splitTags(text: string): string[] {
  return text.split(',').map((t) => t.trim()).filter(Boolean);
}

interface OpDraft extends MemoryProposedOp {
  included: boolean;
}

/** The transcript blocks a proposal (or one op) cites, quoted — expand to audit what it was based on. */
function EvidenceQuotes({ seqs, evidence }: { seqs: number[] | undefined; evidence: Record<string, string> }) {
  if (!seqs || seqs.length === 0) return null;
  return (
    <details className="evidence">
      <summary>based on {seqs.length} transcript block{seqs.length > 1 ? 's' : ''}</summary>
      {seqs.map((seq) => (
        <blockquote key={seq}><span className="idle">#{seq}</span> {evidence[String(seq)] ?? '(not resolved)'}</blockquote>
      ))}
    </details>
  );
}

type ProposalCardProps = {
  proposal: MemoryProposal;
  onDecided: () => void;
  onError: (msg: string) => void;
};

function ProposalCard(props: ProposalCardProps) {
  return props.proposal.kind === 'skill'
    ? <SkillProposalCard {...props} />
    : <MemoryProposalCard {...props} />;
}

/** A skill proposal: the SKILL.md body is `episode`, the name/description/tags are `ops` — all editable before approval. */
function SkillProposalCard({ proposal, onDecided, onError }: ProposalCardProps) {
  const meta = proposal.ops as SkillProposalMeta;
  const [name, setName] = useState(meta.name);
  const [description, setDescription] = useState(meta.description);
  const [tags, setTags] = useState((meta.tags ?? []).join(', '));
  const [content, setContent] = useState(proposal.episode);
  const [busy, setBusy] = useState(false);

  const approve = () => {
    setBusy(true);
    api.memoryApproveProposal(proposal.id, {
      episode: content,
      ops: { name: name.trim(), description: description.trim(), tags: splitTags(tags) },
    })
      .then(onDecided)
      .catch((e) => { onError(String((e as Error).message ?? e)); setBusy(false); });
  };

  const discard = () => {
    setBusy(true);
    api.memoryDiscardProposal(proposal.id).then(onDecided).catch((e) => { onError(String((e as Error).message ?? e)); setBusy(false); });
  };

  return (
    <div className="stale-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <span className="chip">skill</span>
        <span className="name">{proposal.sessionName}</span>
        <span className="chip">{proposal.servicePath}</span>
        <span className="idle">{new Date(proposal.createdAt).toLocaleString()}</span>
      </div>
      <input value={name} placeholder="skill-name (kebab-case)" onChange={(e) => setName(e.target.value)} />
      <input value={description} placeholder="when should an agent use this skill?" onChange={(e) => setDescription(e.target.value)} />
      <input value={tags} placeholder="tags, comma separated" onChange={(e) => setTags(e.target.value)} />
      <textarea rows={12} value={content} onChange={(e) => setContent(e.target.value)} />
      <EvidenceQuotes seqs={meta.evidence} evidence={proposal.evidence} />
      <div className="proposal-actions">
        <button disabled={busy} onClick={discard}>Discard</button>
        <button
          className="primary"
          disabled={busy || !name.trim() || !content.trim()}
          title={!name.trim() || !content.trim() ? 'a skill needs a name and a body' : undefined}
          onClick={approve}
        >Approve</button>
      </div>
    </div>
  );
}

function MemoryProposalCard({ proposal, onDecided, onError }: ProposalCardProps) {
  const [episode, setEpisode] = useState(proposal.episode);
  const [ops, setOps] = useState<OpDraft[]>((proposal.ops as MemoryProposedOp[]).map((o) => ({ ...o, included: true })));
  const [busy, setBusy] = useState(false);

  const approve = () => {
    setBusy(true);
    const included = ops.filter((o) => o.included).map(({ included: _included, ...op }) => op);
    api.memoryApproveProposal(proposal.id, { episode, ops: included })
      .then(onDecided)
      .catch((e) => { onError(String((e as Error).message ?? e)); setBusy(false); });
  };

  const discard = () => {
    setBusy(true);
    api.memoryDiscardProposal(proposal.id).then(onDecided).catch((e) => { onError(String((e as Error).message ?? e)); setBusy(false); });
  };

  return (
    <div className="stale-row" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        {proposal.kind === 'remember' && <span className="chip">remember</span>}
        <span className="name">{proposal.sessionName}</span>
        <span className="chip">{proposal.servicePath}</span>
        <span className="idle">{new Date(proposal.createdAt).toLocaleString()}</span>
      </div>
      {proposal.kind !== 'remember' && <textarea rows={2} value={episode} onChange={(e) => setEpisode(e.target.value)} />}
      {ops.length === 0 && <div className="note">no semantic memory changes proposed — episode only</div>}
      {ops.map((op, i) => (
        <div key={i} style={{ background: 'var(--panel2)', borderRadius: 6, padding: 8, opacity: op.included ? 1 : 0.5 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input
              type="checkbox"
              checked={op.included}
              onChange={(e) => setOps((prev) => prev.map((o, j) => (j === i ? { ...o, included: e.target.checked } : o)))}
            />
            <span className="chip">{op.op}</span>
            <span className="chip">{op.scope}</span>
            <strong>{op.name}</strong>
          </div>
          <input
            style={{ width: '100%', marginTop: 6 }}
            value={op.description ?? ''}
            placeholder="description"
            disabled={!op.included}
            onChange={(e) => setOps((prev) => prev.map((o, j) => (j === i ? { ...o, description: e.target.value } : o)))}
          />
          {op.op !== 'archive' && (
            <textarea
              style={{ width: '100%', marginTop: 6 }}
              rows={3}
              value={op.content ?? ''}
              disabled={!op.included}
              onChange={(e) => setOps((prev) => prev.map((o, j) => (j === i ? { ...o, content: e.target.value } : o)))}
            />
          )}
          <EvidenceQuotes seqs={op.evidence} evidence={proposal.evidence} />
        </div>
      ))}
      <div className="proposal-actions">
        <button disabled={busy} onClick={discard}>Discard</button>
        <button className="primary" disabled={busy} onClick={approve}>Approve</button>
      </div>
    </div>
  );
}

function renderLink(text: string, dangling: boolean, onClick: () => void) {
  return (
    <span
      className={`chip clickable${dangling ? '' : ''}`}
      style={dangling ? { opacity: 0.5, fontStyle: 'italic' } : undefined}
      title={dangling ? `${text} — not written yet` : text}
      onClick={onClick}
    >
      {dangling ? `${text} (not written)` : text}
    </span>
  );
}

export default function MemoryDialog({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<'browse' | 'pending'>('browse');
  const [pending, setPending] = useState<MemoryProposal[] | null>(null);
  const [query, setQuery] = useState('');
  const [kind, setKind] = useState<'all' | 'semantic' | 'episodic'>('all');
  const [servicePath, setServicePath] = useState('');
  const [hits, setHits] = useState<MemorySearchHit[] | null>(null);
  const [docs, setDocs] = useState<MemoryDoc[] | null>(null);
  const [statusFilter, setStatusFilter] = useState('ACTIVE');
  const [selected, setSelected] = useState<MemoryDocDetail | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState({ description: '', tags: '', content: '' });
  const [creating, setCreating] = useState(false);
  const [createDraft, setCreateDraft] = useState({ scope: 'service', servicePath: '', name: '', description: '', tags: '', content: '' });
  const [error, setError] = useState('');

  const refreshDocs = () => {
    api.memoryDocs({ status: statusFilter, servicePath: servicePath || undefined })
      .then(setDocs)
      .catch((e) => setError(String((e as Error).message ?? e)));
  };

  useEffect(refreshDocs, [statusFilter, servicePath]);

  const refreshPending = () => {
    api.memoryProposals('PENDING').then(setPending).catch((e) => setError(String((e as Error).message ?? e)));
  };

  useEffect(refreshPending, []);

  const runSearch = () => {
    if (!query.trim()) { setHits(null); return; }
    api.memorySearch(query.trim(), { kind, servicePath: servicePath || undefined })
      .then((h) => { setHits(h); setError(''); })
      .catch((e) => setError(String((e as Error).message ?? e)));
  };

  const openDoc = (id: string) => {
    api.memoryDoc(id).then((d) => {
      setSelected(d);
      setEditing(false);
      setDraft({ description: d.doc.description, tags: d.doc.tags.join(', '), content: d.content });
      setError('');
    }).catch((e) => setError(String((e as Error).message ?? e)));
  };

  const saveEdit = () => {
    if (!selected) return;
    api.memoryUpdateDoc(selected.doc.id, { description: draft.description, tags: splitTags(draft.tags), content: draft.content })
      .then(() => { openDoc(selected.doc.id); setEditing(false); refreshDocs(); })
      .catch((e) => setError(String((e as Error).message ?? e)));
  };

  const archive = (id: string) => {
    api.memoryArchiveDoc(id).then(() => { setSelected(null); refreshDocs(); }).catch((e) => setError(String((e as Error).message ?? e)));
  };

  const restore = (id: string) => {
    api.memoryRestoreDoc(id).then(() => { openDoc(id); refreshDocs(); }).catch((e) => setError(String((e as Error).message ?? e)));
  };

  const createDoc = () => {
    api.memoryCreateDoc({
      scope: createDraft.scope,
      servicePath: createDraft.scope === 'service' ? createDraft.servicePath : undefined,
      name: createDraft.name,
      description: createDraft.description,
      tags: splitTags(createDraft.tags),
      content: createDraft.content,
    }).then((d) => {
      setCreating(false);
      setCreateDraft({ scope: 'service', servicePath: '', name: '', description: '', tags: '', content: '' });
      refreshDocs();
      openDoc(d.id);
    }).catch((e) => setError(String((e as Error).message ?? e)));
  };

  const shown = hits !== null
    ? hits
    : (docs ?? []).map((d): MemorySearchHit => ({
      kind: 'semantic', id: d.id, name: d.name, scope: d.scope, servicePath: d.servicePath,
      description: d.description, tags: d.tags, sessionName: null, ts: null, score: 0,
    }));

  const backdropDismiss = useBackdropDismiss(onClose);

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal wide" onClick={(e) => e.stopPropagation()}>
        <h2 style={{ display: 'flex', alignItems: 'center' }}>
          Memory
          <div className="grouping-toggle">
            <button className={view === 'browse' ? 'active' : ''} onClick={() => setView('browse')}>Browse</button>
            <button className={view === 'pending' ? 'active' : ''} onClick={() => setView('pending')}>
              Pending{pending && pending.length > 0 ? ` (${pending.length})` : ''}
            </button>
          </div>
        </h2>

        {view === 'pending' && (
          <div className="stale-list">
            {error && <div className="error-text">{error}</div>}
            {pending !== null && pending.length === 0 && (
              <div style={{ color: 'var(--muted)' }}>nothing awaiting approval</div>
            )}
            {(pending ?? []).map((p) => (
              <ProposalCard
                key={p.id}
                proposal={p}
                onDecided={() => { refreshPending(); refreshDocs(); }}
                onError={setError}
              />
            ))}
          </div>
        )}

        {view === 'browse' && <>
        <div className="usage-filters">
          <input
            style={{ flex: 1 }}
            placeholder="search memory — press Enter (leave empty to just browse)"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') runSearch(); }}
          />
          <select value={kind} onChange={(e) => { setKind(e.target.value as typeof kind); }}>
            <option value="all">all memory</option>
            <option value="semantic">semantic only</option>
            <option value="episodic">episodes only</option>
          </select>
          <input
            style={{ width: 220 }}
            placeholder="filter by service path"
            value={servicePath}
            onChange={(e) => setServicePath(e.target.value)}
          />
          {hits === null && (
            <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
              <option value="ACTIVE">active</option>
              <option value="ARCHIVED">archived</option>
              <option value="">all</option>
            </select>
          )}
          {hits !== null && <button onClick={() => { setHits(null); setQuery(''); }}>Clear search</button>}
          <button onClick={() => setCreating((v) => !v)}>+ New</button>
        </div>

        {error && <div className="error-text">{error}</div>}

        {creating && (
          <div className="form-grid" style={{ marginBottom: 14, padding: 10, background: 'var(--panel2)', borderRadius: 8 }}>
            <label>Scope</label>
            <select value={createDraft.scope} onChange={(e) => setCreateDraft((d) => ({ ...d, scope: e.target.value }))}>
              <option value="service">service</option>
              <option value="ecosystem">ecosystem</option>
            </select>
            {createDraft.scope === 'service' && (
              <>
                <label>Service path</label>
                <input value={createDraft.servicePath} onChange={(e) => setCreateDraft((d) => ({ ...d, servicePath: e.target.value }))} />
              </>
            )}
            <label>Name (slug)</label>
            <input placeholder="kebab-case-slug" value={createDraft.name} onChange={(e) => setCreateDraft((d) => ({ ...d, name: e.target.value }))} />
            <label>Description</label>
            <input value={createDraft.description} onChange={(e) => setCreateDraft((d) => ({ ...d, description: e.target.value }))} />
            <label>Tags</label>
            <input placeholder="comma, separated" value={createDraft.tags} onChange={(e) => setCreateDraft((d) => ({ ...d, tags: e.target.value }))} />
            <label>Content</label>
            <textarea
              className="full" rows={4} value={createDraft.content}
              placeholder="markdown body — link related memories with [[slug]]"
              onChange={(e) => setCreateDraft((d) => ({ ...d, content: e.target.value }))}
            />
            <span className="full" style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setCreating(false)}>Cancel</button>
              <button className="primary" disabled={!createDraft.name.match(/^[a-z0-9][a-z0-9-]*$/)} onClick={createDoc}>Create</button>
            </span>
          </div>
        )}

        <div style={{ display: 'flex', gap: 16 }}>
          <div className="stale-list" style={{ flex: '1 1 45%', minWidth: 0 }}>
            {shown.length === 0 && <div style={{ color: 'var(--muted)' }}>nothing here yet</div>}
            {shown.map((h) => (
              <div
                key={`${h.kind}-${h.id}`}
                className="stale-row"
                style={{ cursor: h.kind === 'semantic' ? 'pointer' : 'default', flexDirection: 'column', alignItems: 'flex-start' }}
                onClick={() => h.kind === 'semantic' && openDoc(h.id)}
              >
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', width: '100%' }}>
                  <span title={h.kind}>{h.kind === 'semantic' ? <MemoryDocIcon /> : <MemoryDocEditableIcon />}</span>
                  <span className="name">{h.name ?? h.sessionName ?? '(episode)'}</span>
                  {h.scope && <span className="chip">{h.scope}</span>}
                  {h.ts && <span className="idle">{new Date(h.ts).toLocaleString()}</span>}
                </div>
                <div className="note" style={{ marginLeft: 22 }}>{h.description}</div>
                {h.tags.length > 0 && (
                  <div className="chip-row" style={{ marginLeft: 22 }}>
                    {h.tags.map((t) => <span key={t} className="chip">{t}</span>)}
                  </div>
                )}
              </div>
            ))}
          </div>

          <div style={{ flex: '1 1 55%', minWidth: 0, borderLeft: '1px solid var(--border)', paddingLeft: 16 }}>
            {!selected && <div style={{ color: 'var(--muted)' }}>select a memory to view it</div>}
            {selected && !editing && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <h3 style={{ margin: 0 }}>{selected.doc.name}</h3>
                  <span className="chip">{selected.doc.scope}</span>
                  {selected.doc.status === 'ARCHIVED' && <span className="chip" style={{ color: 'var(--red)' }}>archived</span>}
                </div>
                {selected.doc.servicePath && <div className="note">{selected.doc.servicePath}</div>}
                <p>{selected.doc.description}</p>
                <div className="chip-row">{selected.doc.tags.map((t) => <span key={t} className="chip">{t}</span>)}</div>
                <pre style={{ whiteSpace: 'pre-wrap', background: 'var(--panel2)', padding: 10, borderRadius: 8, maxHeight: 260, overflowY: 'auto' }}>
                  {selected.content}
                </pre>
                {selected.totalPages > 1 && (
                  <div className="pagination">
                    <button disabled={selected.page <= 1} onClick={() => api.memoryDoc(selected.doc.id, selected.page - 1).then(setSelected)}>‹ Prev</button>
                    <span>{selected.page} / {selected.totalPages}</span>
                    <button disabled={selected.page >= selected.totalPages} onClick={() => api.memoryDoc(selected.doc.id, selected.page + 1).then(setSelected)}>Next ›</button>
                  </div>
                )}
                {selected.outgoing.length > 0 && (
                  <>
                    <div className="note">Links to</div>
                    <div className="chip-row">
                      {selected.outgoing.map((l) => renderLink(l.name ?? l.slug, l.dangling, () => l.docId && openDoc(l.docId)))}
                    </div>
                  </>
                )}
                {selected.backlinks.length > 0 && (
                  <>
                    <div className="note">Linked from</div>
                    <div className="chip-row">
                      {selected.backlinks.map((l) => renderLink(l.name ?? l.slug, l.dangling, () => l.docId && openDoc(l.docId)))}
                    </div>
                  </>
                )}
                <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                  <button onClick={() => setEditing(true)}>Edit</button>
                  {selected.doc.status === 'ACTIVE'
                    ? <button onClick={() => archive(selected.doc.id)}>Archive</button>
                    : <button onClick={() => restore(selected.doc.id)}>Restore</button>}
                </div>
              </>
            )}
            {selected && editing && (
              <div className="form-grid">
                <label>Description</label>
                <input value={draft.description} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} />
                <label>Tags</label>
                <input value={draft.tags} onChange={(e) => setDraft((d) => ({ ...d, tags: e.target.value }))} />
                <label>Content</label>
                <textarea className="full" rows={8} value={draft.content} onChange={(e) => setDraft((d) => ({ ...d, content: e.target.value }))} />
                <span className="full" style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                  <button onClick={() => setEditing(false)}>Cancel</button>
                  <button className="primary" onClick={saveEdit}>Save</button>
                </span>
              </div>
            )}
          </div>
        </div>
        </>}

        <div className="actions">
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
