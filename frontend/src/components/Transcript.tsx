import { memo, useEffect, useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { BlockMark, TranscriptItem } from '../store/store';
import PermissionCard, { type PermissionResponse } from './PermissionCard';
import { AgentAsset, ExcludeFromMemory, MarkImportant, RememberBlock, ToolFailed, ToolOk, ToolRunning } from '../icons';

const Markdown = memo(function Markdown({ text }: { text: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>;
});

function summarizeInput(input: unknown): string {
  const s = JSON.stringify(input) ?? '';
  return s.length > 120 ? s.slice(0, 120) + '…' : s;
}

/**
 * A subagent-launching tool call's own input — untyped at the SDK layer, and the tool's
 * *name* isn't a stable signal either (confirmed empirically: the CLI currently calls it
 * "Agent", not the SDK docs' "Task"). Gate on `subagent_type` being present instead of on
 * a name — a missing/renamed field just falls back to the generic raw-JSON summary below.
 */
function taskSummary(input: unknown): { subagentType: string; description: string } | null {
  if (typeof input !== 'object' || input === null) return null;
  const i = input as Record<string, unknown>;
  if (typeof i['subagent_type'] !== 'string') return null;
  const description = typeof i['description'] === 'string' ? i['description'] : null;
  return { subagentType: i['subagent_type'], description: description ?? '' };
}

/** Memory-curation wiring (phase 21): present only when memory is enabled, so the affordance disappears with it. */
export interface MarkContext {
  marks: Record<number, BlockMark>;
  onMark: (seq: number, mark: BlockMark | null) => void;
  /** turn an important block into a memory proposal now; `remembering` holds the seqs in flight */
  onRemember: (seq: number) => void;
  remembering: ReadonlySet<number>;
}

function MarkActions({ seq, ctx }: { seq: number; ctx: MarkContext }) {
  const current = ctx.marks[seq];
  const toggle = (mark: BlockMark) => ctx.onMark(seq, current === mark ? null : mark);
  return (
    <span className="mark-actions">
      <button
        className={`icon-btn${current === 'important' ? ' active' : ''}`}
        onClick={() => toggle('important')}
        title={current === 'important' ? 'unmark — no longer important for memory' : 'mark important for memory & handoff'}
      ><MarkImportant size={13} /></button>
      <button
        className={`icon-btn${current === 'excluded' ? ' active' : ''}`}
        onClick={() => toggle('excluded')}
        title={current === 'excluded' ? 'include again — allow memory reflection and handoff briefs to see this' : 'exclude from memory & handoff'}
      ><ExcludeFromMemory size={13} /></button>
      {current === 'important' && (
        <button
          className={`icon-btn${ctx.remembering.has(seq) ? ' pulse' : ''}`}
          disabled={ctx.remembering.has(seq)}
          onClick={() => ctx.onRemember(seq)}
          title={ctx.remembering.has(seq) ? 'distilling a memory proposal…' : 'remember this — turn this block into a memory proposal'}
        ><RememberBlock size={13} /></button>
      )}
    </span>
  );
}

function markClass(seq: number | undefined, ctx: MarkContext | undefined): string {
  if (!ctx || seq === undefined) return '';
  const mark = ctx.marks[seq];
  return ` markable${mark ? ` marked-${mark}` : ''}`;
}

function renderItem(
  item: TranscriptItem,
  key: React.Key,
  onPermission: (requestId: string, response: PermissionResponse) => void,
  markCtx?: MarkContext,
): React.ReactNode {
  switch (item.kind) {
    case 'user':
      return (
        <div key={key} className={`t-user${markClass(item.seq, markCtx)}`}>
          {item.text}
          {markCtx && item.seq !== undefined && <MarkActions seq={item.seq} ctx={markCtx} />}
        </div>
      );
    case 'text':
      return (
        <div key={key} className={`t-text${markClass(item.seq, markCtx)}`}>
          <Markdown text={item.text} />
          {markCtx && item.seq !== undefined && <MarkActions seq={item.seq} ctx={markCtx} />}
        </div>
      );
    case 'thinking':
      return (
        <details key={key} className="t-thinking">
          <summary>
            {item.done ? 'thought' : 'thinking…'}
            {item.estimatedTokens > 0 ? ` (~${item.estimatedTokens} tokens)` : ''}
          </summary>
          <div className="body">{item.text}</div>
        </details>
      );
    case 'tool': {
      const task = taskSummary(item.input);
      return (
        <details key={key} className={`t-tool${item.isError ? ' error' : ''}`}>
          <summary>
            {task ? (
              <>
                <AgentAsset size={13} className="task-badge" />
                <span className="tname">{task.subagentType}</span>{' '}
                {task.description}
              </>
            ) : (
              <>
                <span className="tname">{item.name}</span>{' '}
                {summarizeInput(item.input)}
              </>
            )}
            <span className={`tool-status ${item.output === undefined ? 'run' : item.isError ? 'err' : 'ok'}`}>
              {item.output === undefined ? <ToolRunning size={13} /> : item.isError ? <ToolFailed size={13} /> : <ToolOk size={13} />}
            </span>
          </summary>
          <pre>
            {JSON.stringify(item.input, null, 2)}
            {item.output !== undefined ? `\n─── result${item.truncated ? ' (truncated)' : ''} ───\n${item.output}` : ''}
          </pre>
          {item.items?.length ? (
            <div className="t-nested">
              {item.items.map((nested, j) => renderItem(nested, j, onPermission, markCtx))}
            </div>
          ) : null}
        </details>
      );
    }
    case 'permission':
      return (
        <PermissionCard
          key={item.requestId}
          item={item}
          onRespond={(response) => onPermission(item.requestId, response)}
        />
      );
    case 'turn_footer':
      return (
        <div key={key} className="t-footer">
          {item.stopReason} · ${item.costUsd.toFixed(4)} · {(item.durationMs / 1000).toFixed(1)}s
          {item.model ? ` · ${item.model}` : ''}
        </div>
      );
    case 'note':
      return <div key={key} className={`t-note ${item.level}`}>{item.text}</div>;
    case 'context_compacted':
      return (
        <div key={key} className="t-compacted">
          — compacted: {item.preTokens.toLocaleString()} → {item.postTokens.toLocaleString()} —
        </div>
      );
  }
}

export default function Transcript({
  items,
  onPermission,
  markCtx,
}: {
  items: TranscriptItem[];
  onPermission: (requestId: string, response: PermissionResponse) => void;
  markCtx?: MarkContext;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);

  useEffect(() => {
    const el = ref.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [items]);

  // a resize keeps scrollTop but not the distance from the bottom — e.g. the phase-16 focus
  // pane shrinking back into its grid slot — so re-stick if we were following the tail
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const observer = new ResizeObserver(() => {
      if (stickToBottom.current) el.scrollTop = el.scrollHeight;
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  return (
    <div
      className="transcript"
      ref={ref}
      onScroll={(e) => {
        const el = e.currentTarget;
        stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
      }}
    >
      {items.map((item, i) => renderItem(item, i, onPermission, markCtx))}
    </div>
  );
}
