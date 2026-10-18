import { useEffect, useMemo, useState } from 'react';
import { api, ApiError } from '../api/rest';
import type { SessionSummary } from '../protocol';
import { transcriptFromEvents } from '../store/store';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import Transcript from './Transcript';

function formatDate(iso: string | null): string {
  if (!iso) return 'unknown';
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

/**
 * Phase 23: read-only look at a closed session from the sidebar History. The worktree is gone,
 * so the only ways forward are exporting the transcript or continuing from it in a new session.
 */
export default function ClosedSessionDialog({
  session,
  onContinue,
  onClose,
}: {
  session: SessionSummary;
  onContinue: (session: SessionSummary) => void;
  onClose: () => void;
}) {
  const pruned = session.lastSeq === 0;
  const [events, setEvents] = useState<Awaited<ReturnType<typeof api.sessionEvents>> | null>(null);
  const [error, setError] = useState('');
  const [exporting, setExporting] = useState(false);
  const backdropDismiss = useBackdropDismiss(onClose);

  useEffect(() => {
    if (pruned) return;
    let live = true;
    api.sessionEvents(session.id)
      .then((list) => { if (live) setEvents(list); })
      .catch((e) => { if (live) setError(e instanceof ApiError ? e.message : String(e)); });
    return () => { live = false; };
  }, [session.id, pruned]);

  const items = useMemo(() => (events ? transcriptFromEvents(events) : []), [events]);

  const exportMarkdown = async () => {
    setExporting(true);
    setError('');
    try {
      const text = await api.exportTranscript(session.id);
      const url = URL.createObjectURL(new Blob([text], { type: 'text/markdown;charset=UTF-8' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `${session.name.replace(/[^a-zA-Z0-9._-]+/g, '-')}.md`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal closed-session" style={{ width: 'min(860px, 94vw)' }} onClick={(e) => e.stopPropagation()}>
        <h2>{session.name}</h2>
        <div className="closed-facts">
          <span className="chip" title="branch">{session.branch}</span>
          <span className="chip">{session.provider}</span>
          {session.model && <span className="chip">{session.model}</span>}
          {session.sessionType === 'review' && <span className="chip">review</span>}
          <span className="chip">${session.costToDate.toFixed(3)}</span>
          <span className="closed-when">closed {formatDate(session.closedAt ?? session.updatedAt)}</span>
        </div>
        {error && <div className="error-text">{error}</div>}
        {pruned ? (
          <div className="closed-empty">journal pruned — nothing left to show or hand off</div>
        ) : events === null && !error ? (
          <div className="closed-empty">loading transcript…</div>
        ) : items.length === 0 ? (
          <div className="closed-empty">no conversation was recorded for this session</div>
        ) : (
          <div className="closed-transcript"><Transcript items={items} /></div>
        )}
        <div className="actions">
          <button className={exporting ? 'pulse' : undefined} disabled={pruned || exporting} onClick={() => void exportMarkdown()}>
            Export
          </button>
          <button className="primary" disabled={pruned} onClick={() => onContinue(session)}>Continue from this</button>
          <button onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
