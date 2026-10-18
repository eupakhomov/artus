import { useRef, useState } from 'react';
import { DIAGRAM_TYPES, type DiagramType } from '../protocol';
import { CopySource, DownloadIcon, PopOut, Refresh } from '../icons';
import DiagramView from './DiagramView';
import { inlineThemeVars } from './render';
import { useDiagram } from './useDiagram';

/** The Visualize body shared by the dialog and the pop-out window. */
export default function DiagramPanel({ sessionId, onPopOut }: { sessionId: string; onPopOut?: () => void }) {
  const { status, latest, outcome, generating, repairing, error, generate } = useDiagram(sessionId);
  const [choice, setChoice] = useState<DiagramType | 'auto'>('auto');
  const [copied, setCopied] = useState(false);
  const stageRef = useRef<HTMLDivElement>(null);
  const stale = status?.stale ?? false;
  const source = outcome?.source ?? latest?.mermaid ?? '';

  const pick = (v: DiagramType | 'auto') => {
    setChoice(v);
    // another type is a new turn, not a re-layout — but re-picking the type the diagram already
    // has (or flipping back to 'auto') must not burn a 90 s paid turn; Regenerate covers those
    if (latest && v !== 'auto' && v !== latest.diagramType) void generate(v);
  };

  const copy = () => {
    void navigator.clipboard?.writeText(source).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  const exportSvg = () => {
    if (outcome?.kind !== 'svg' || !stageRef.current) return;
    const svg = inlineThemeVars(outcome.svg, stageRef.current);
    const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = `diagram-${latest?.diagramType ?? 'session'}.svg`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="diagram-panel">
      <div className="diagram-toolbar">
        <select value={choice} onChange={(e) => pick(e.target.value as DiagramType | 'auto')} disabled={generating} title="Diagram type — picking a different type generates a new diagram">
          <option value="auto">auto (model picks)</option>
          {DIAGRAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <button
          className={`with-icon${generating ? ' pulse' : ''}${!latest || stale ? ' primary' : ''}`}
          disabled={generating}
          onClick={() => void generate(choice)}
          title={generating ? 'generating — one model turn, up to 90s…' : latest ? 'Regenerate from the current changes' : 'Generate a diagram'}
        >
          <Refresh />{generating ? 'Generating…' : latest ? 'Regenerate' : 'Generate'}
        </button>
        {latest && <span className="chip">{latest.diagramType}</span>}
        {latest?.repaired && <span className="chip" title="the first answer did not parse; one repair turn fixed it">repaired</span>}
        {stale && <span className="chip diagram-stale" title="the branch has changed since this diagram was generated">outdated</span>}
        <span style={{ flex: 1 }} />
        <button className="icon-btn" disabled={!source} onClick={copy} title={copied ? 'Copied' : 'Copy Mermaid source'}><CopySource /></button>
        <button className="icon-btn" disabled={outcome?.kind !== 'svg'} onClick={exportSvg} title="Export SVG"><DownloadIcon /></button>
        {onPopOut && <button className="icon-btn" onClick={onPopOut} title="Open in new window"><PopOut /></button>}
      </div>
      {latest?.rationale && <div className="diagram-why">{latest.diagramType} — {latest.rationale}</div>}
      {error && <div className="error-text">{error}</div>}
      <div className="diagram-body">
        {outcome ? <DiagramView outcome={outcome} wrapRef={stageRef} />
          : <div className="changes-empty">{generating || repairing ? (repairing ? 'repairing the diagram…' : 'generating…')
            : status && !latest ? 'No diagram yet — Generate draws one from this session’s changes.' : 'loading…'}</div>}
      </div>
    </div>
  );
}
