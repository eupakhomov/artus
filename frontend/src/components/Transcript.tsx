import { createContext, memo, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import type { BlockMark, TranscriptItem } from '../store/store';
import PermissionCard, { type PermissionResponse } from './PermissionCard';
import { AgentAsset, ExcludeFromMemory, MarkImportant, PopOut, RememberBlock, ToolFailed, ToolOk, ToolRunning } from '../icons';
import { useBackdropDismiss } from '../hooks/useBackdropDismiss';
import { describeToolCall, formatDuration, toolCallDetail } from '../activity';

const Markdown = memo(function Markdown({ text }: { text: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]}>{text}</ReactMarkdown>;
});

/** The session's worktree, so tool rows can show paths relative to it ('' = show them as-is). */
const CwdContext = createContext('');

function summarizeInput(input: unknown, cwd: string): string {
  const s = toolCallDetail(input, cwd) ?? JSON.stringify(input) ?? '';
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

/** While a subagent runs, its nested list shows only this many latest items (phase 25). */
const NESTED_TAIL = 8;

/** The latest nested tool call of a subagent, as a one-line label. */
function latestNestedCall(items: TranscriptItem[] | undefined, cwd: string): string | null {
  for (let i = (items?.length ?? 0) - 1; i >= 0; i--) {
    const it = items![i]!;
    if (it.kind === 'tool') return describeToolCall(it.name, it.input, 48, cwd);
  }
  return null;
}

function ToolRow({ item, onPermission, markCtx }: {
  item: Extract<TranscriptItem, { kind: 'tool' }>;
  onPermission: ((requestId: string, response: PermissionResponse) => void) | undefined;
  markCtx?: MarkContext;
}) {
  const cwd = useContext(CwdContext);
  const [showAll, setShowAll] = useState(false);
  const [poppedOut, setPoppedOut] = useState(false);
  const task = taskSummary(item.input);
  // a backgrounded subagent's call returns at once while the subagent keeps going — its task says so
  const running = item.task ? item.task.status === 'running' : item.output === undefined;
  const failed = item.isError || item.task?.status === 'failed' || item.task?.status === 'stopped';
  const nested = item.items ?? [];
  const hidden = running && !showAll ? Math.max(0, nested.length - NESTED_TAIL) : 0;
  const latest = task && running ? latestNestedCall(nested, cwd) ?? item.task?.lastTool ?? null : null;
  const toolUses = item.task?.toolUses || nested.filter((n) => n.kind === 'tool').length;
  const now = useNow(running && item.task?.startedAt != null);
  // progress events only come with each nested tool call, so a running task's clock ticks locally
  const durationMs = running && item.task?.startedAt
    ? Math.max(item.task.durationMs, now - Date.parse(item.task.startedAt))
    : item.task?.durationMs ?? 0;
  return (
    <>
    {/* a Task row opens while its subagent runs and folds back when it ends; other rows stay as the user leaves them */}
    <details className={`t-tool${failed ? ' error' : ''}`} open={task ? running : undefined}>
      <summary>
        {task ? (
          <>
            <AgentAsset size={13} className="task-badge" />
            <span className="tname">{task.subagentType}</span>{' '}
            {task.description}
            {item.task?.backgrounded && <span className="task-meta"> · background</span>}
            {latest && <span className="task-meta"> · {latest}</span>}
            {toolUses > 0 && <span className="task-meta"> · {toolUses} tool{toolUses === 1 ? '' : 's'}</span>}
            {durationMs > 0 && <span className="task-meta"> · {formatDuration(durationMs)}</span>}
          </>
        ) : (
          <>
            <span className="tname">{item.name}</span>{' '}
            <span className="tinput">{summarizeInput(item.input, cwd)}</span>
          </>
        )}
        <span className={`tool-status ${running ? 'run' : failed ? 'err' : 'ok'}`}>
          {running ? <ToolRunning size={13} /> : failed ? <ToolFailed size={13} /> : <ToolOk size={13} />}
        </span>
        {task && nested.length > 0 && (
          <button
            className="icon-btn task-popout"
            title="open the subagent's full transcript"
            onClick={(e) => { e.preventDefault(); e.stopPropagation(); setPoppedOut(true); }}
          ><PopOut size={13} /></button>
        )}
      </summary>
      <pre>
        {JSON.stringify(item.input, null, 2)}
        {item.output !== undefined ? `\n─── result${item.truncated ? ' (truncated)' : ''} ───\n${item.output}` : ''}
        {item.task?.summary ? `\n─── task ${item.task.status} ───\n${item.task.summary}` : ''}
      </pre>
      {nested.length ? (
        <div className="t-nested">
          {hidden > 0 && (
            <button className="t-nested-more" onClick={() => setShowAll(true)}>show {hidden} earlier steps</button>
          )}
          {nested.slice(hidden).map((n, j) => renderItem(n, hidden + j, onPermission, markCtx))}
        </div>
      ) : null}
    </details>
    {poppedOut && (
      <SubagentDialog title={task ? `${task.subagentType} — ${task.description}` : item.name} items={nested} onClose={() => setPoppedOut(false)} />
    )}
    </>
  );
}

/** Wall-clock ms, re-read every second while `active`. */
function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [active]);
  return now;
}

/** A subagent's whole nested transcript, read-only, in a dialog (phase 25). */
function SubagentDialog({ title, items, onClose }: { title: string; items: TranscriptItem[]; onClose: () => void }) {
  const backdropDismiss = useBackdropDismiss(onClose);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  // portalled: grid widgets are CSS-transformed, which would pin a fixed backdrop to the widget
  return createPortal(
    <div className="modal-backdrop" {...backdropDismiss}>
      <div className="modal closed-session" style={{ width: 'min(860px, 94vw)' }} onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        <div className="closed-transcript"><Transcript items={items} /></div>
        <div className="actions"><button onClick={onClose}>Close</button></div>
      </div>
    </div>,
    document.body,
  );
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
  onPermission: ((requestId: string, response: PermissionResponse) => void) | undefined,
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
    case 'tool':
      return <ToolRow key={key} item={item} onPermission={onPermission} markCtx={markCtx} />;
    case 'permission':
      if (!onPermission) {
        // read-only (closed-session dialog): nobody can answer, so just record what was asked
        return (
          <div key={key} className="t-note info">
            {item.decision ? `${item.decision === 'allow' ? 'allowed' : 'denied'}: ` : 'unanswered: '}{item.toolName}
          </div>
        );
      }
      return (
        <PermissionCard
          key={item.requestId}
          item={item}
          onRespond={(response) => onPermission(item.requestId, response)}
        />
      );
    case 'turn_footer':
      return (
        <div key={key} className="t-footer" title="cost of this turn — the header shows the session total">
          {item.stopReason} · ${item.costUsd.toFixed(4)} this turn · {(item.durationMs / 1000).toFixed(1)}s
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
  cwd = '',
}: {
  items: TranscriptItem[];
  /** omit for a read-only transcript (no permission buttons) */
  onPermission?: (requestId: string, response: PermissionResponse) => void;
  markCtx?: MarkContext;
  /** the session's worktree — tool rows show paths relative to it */
  cwd?: string;
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
    <CwdContext.Provider value={cwd}>
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
    </CwdContext.Provider>
  );
}
