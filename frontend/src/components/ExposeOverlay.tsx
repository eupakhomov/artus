import { dotClass, stateLabel } from '../protocol';
import type { SessionView, TranscriptItem } from '../store/store';
import { Close, MinimizeToDock } from '../icons';

function previewLine(item: TranscriptItem): string | null {
  switch (item.kind) {
    case 'user': return `you: ${item.text}`;
    case 'text': return item.done ? item.text : null;
    case 'tool': return `${item.name}()`;
    case 'note': return item.text;
    default: return null;
  }
}

function lastLines(transcript: TranscriptItem[], n: number): string[] {
  const out: string[] = [];
  for (let i = transcript.length - 1; i >= 0 && out.length < n; i--) {
    const line = previewLine(transcript[i]!);
    if (line) out.unshift(line.length > 100 ? line.slice(0, 100) + '…' : line);
  }
  return out;
}

/**
 * Full-screen overlay with one live card per open session — sourced entirely from the
 * Zustand store (already fed by each widget's own WS), so this opens zero connections
 * of its own and stays live while hidden (minimized) sessions keep streaming underneath.
 */
export default function ExposeOverlay({
  ids,
  views,
  minimizedIds,
  onSelect,
  onClose,
}: {
  ids: string[];
  views: Record<string, SessionView>;
  minimizedIds: string[];
  onSelect: (id: string) => void;
  onClose: () => void;
}) {
  return (
    <div className="expose-backdrop" onClick={onClose}>
      <button className="expose-close icon-btn" onClick={onClose} title="close (Esc)"><Close /></button>
      <div className="expose-grid" onClick={(e) => e.stopPropagation()}>
        {ids.map((id) => {
          const v = views[id];
          const pending = !!v?.pendingPermission;
          const minimized = minimizedIds.includes(id);
          return (
            <div
              key={id}
              className={`expose-card${pending ? ' pending' : ''}`}
              onClick={() => onSelect(id)}
            >
              <div className="expose-head">
                <span className={dotClass(v?.state ?? 'CREATING', v?.crashReason)} title={stateLabel(v?.state ?? 'CREATING', v?.crashReason)} />
                <span className="expose-name">{v?.name ?? id.slice(0, 8)}</span>
                {minimized && <span className="chip" title="minimized"><MinimizeToDock /></span>}
              </div>
              <div className="chip-row" style={{ marginTop: 4 }}>
                {v?.branch && <span className="chip">{v.branch}</span>}
                {v?.servicePath && <span className="chip" title={v.servicePath}>{v.servicePath.split('/').pop()}</span>}
                {v?.sessionType === 'review' && <span className="chip" title="review session">review</span>}
                <span className="chip">${(v?.costToDate ?? 0).toFixed(3)}</span>
              </div>
              <div className="expose-preview">
                {lastLines(v?.transcript ?? [], 3).map((l, i) => (
                  <div className="expose-line" key={i}>{l}</div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
