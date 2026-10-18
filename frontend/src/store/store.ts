import { create } from 'zustand';
import type {
  Capabilities, ContextBreakdown, Envelope, PermissionMode, PrCheckStatus, QueuedMessage, SessionState, SessionType,
} from '../protocol';
import type { WsStatus } from '../api/ws';

// ---------------------------------------------------------------------------
// Transcript view model: WS events reduce into a flat item list per session.
// ---------------------------------------------------------------------------

export type TranscriptItem =
  | { kind: 'user'; text: string; /** journal seq — the identity a memory mark points at (phase 21) */ seq?: number }
  | { kind: 'thinking'; text: string; estimatedTokens: number; done: boolean }
  | { kind: 'text'; text: string; done: boolean; /** set once the finalizing assistant_message is journaled */ seq?: number }
  | {
      kind: 'tool'; toolUseId: string; name: string; input: unknown; output?: string; isError?: boolean; truncated?: boolean;
      /** a Task call's own subagent activity (thinking/text/tool calls), nested one level — see reduce()'s parentToolUseId routing */
      items?: TranscriptItem[];
    }
  | { kind: 'permission'; requestId: string; toolName: string; input: Record<string, unknown>; plan: string | null; decision: 'allow' | 'deny' | null }
  | { kind: 'turn_footer'; stopReason: string; costUsd: number; durationMs: number; model: string | null }
  | { kind: 'note'; level: 'info' | 'warn' | 'error'; text: string }
  | { kind: 'context_compacted'; preTokens: number; postTokens: number; trigger: 'manual' | 'auto' };

export type BlockMark = 'important' | 'excluded';

export interface SessionView {
  state: SessionState;
  /** ts of the latest state_changed (phase 16 sidebar's "time in state"); replayed envelopes keep their original ts */
  stateSince: string | null;
  permissionMode: PermissionMode;
  capabilities: Capabilities | null;
  model: string | null;
  name: string | null;
  costBudgetUsd: number | null;
  transcript: TranscriptItem[];
  /** memory-curation marks by target journal seq, folded from block_marked events (phase 21) */
  marks: Record<number, BlockMark>;
  /** the latest `diagram_generated` (phase 22): the widget's Visualize button knows a diagram exists; staleness comes from the status endpoint */
  diagram: { seq: number; fingerprint: string; diagramType: string } | null;
  queued: QueuedMessage[];
  costToDate: number;
  wsStatus: WsStatus;
  pendingPermission: { requestId: string; toolName: string; input: Record<string, unknown>; plan: string | null } | null;
  // seeded once from the REST entity fetch (SessionWidget) — 7.2's Exposé cards read these
  // straight from the store so they need no connection of their own
  repoPath: string | null;
  /** The service identity — what the chip shows (phase 11); equals repoPath for a polyrepo session */
  servicePath: string | null;
  branch: string | null;
  /** 'development' (default) or 'review' — the Exposé card's review chip (phase 15) */
  sessionType: SessionType | null;
  // --- context usage (phase 12 track C) ---
  contextTokens: number | null;
  contextWindow: number | null;
  autoCompactAt: number | null;
  /** what the context is made of (Claude sidecars only); null until a snapshot carries one */
  contextBreakdown: ContextBreakdown | null;
  /** false once the warn threshold has been crossed and the suggestion card has fired for it;
   * re-armed by a context_compacted. Distinct from ctxSuggestionVisible so a dismiss doesn't
   * re-show on the next context_usage while still above threshold — see evaluateContextWarn. */
  ctxWarnArmed: boolean;
  ctxSuggestionVisible: boolean;
  /** phase 13: the session's graphify graph-build state (journaled `code_intel_status`); null until the first event */
  codeIntelStatus: CodeIntelStatus | null;
  // --- phase 16: seeded from the REST entity, so the sidebar needs no fetch of its own ---
  prUrl: string | null;
  /** kept current by pr_status_changed; the widget's PR chip and the sidebar both read it */
  prCheckStatus: PrCheckStatus | null;
  parentSessionId: string | null;
}

export interface CodeIntelStatus {
  tool: string;
  status: 'BUILDING' | 'READY' | 'FAILED';
  nodes: number | null;
  edges: number | null;
  message: string | null;
  /** event timestamp — the chip's "built X ago" */
  at: string;
}

const emptyView = (): SessionView => ({
  state: 'CREATING',
  stateSince: null,
  permissionMode: 'default',
  capabilities: null,
  model: null,
  name: null,
  costBudgetUsd: null,
  transcript: [],
  marks: {},
  diagram: null,
  queued: [],
  costToDate: 0,
  wsStatus: 'connecting',
  pendingPermission: null,
  repoPath: null,
  servicePath: null,
  branch: null,
  sessionType: null,
  contextTokens: null,
  contextWindow: null,
  autoCompactAt: null,
  contextBreakdown: null,
  ctxWarnArmed: true,
  ctxSuggestionVisible: false,
  codeIntelStatus: null,
  prUrl: null,
  prCheckStatus: null,
  parentSessionId: null,
});

function last<T>(arr: T[]): T | undefined {
  return arr[arr.length - 1];
}

/**
 * The four event types below all append to or patch "the current list of transcript
 * items" — which is either the top-level transcript, or (when the event carries a
 * parentToolUseId) a Task call's nested subagent activity. Shared here so both scopes
 * get identical behavior; mutates and returns `arr`.
 */
function applyBranchEvent(arr: TranscriptItem[], type: string, p: Record<string, unknown>, seq?: number): TranscriptItem[] {
  switch (type) {
    case 'stream_delta': {
      const deltaType = p['deltaType'] as 'text' | 'thinking';
      const text = p['text'] as string;
      const tail = last(arr);
      if (deltaType === 'thinking') {
        if (tail?.kind === 'thinking' && !tail.done) arr[arr.length - 1] = { ...tail, text: tail.text + text };
        else arr.push({ kind: 'thinking', text, estimatedTokens: 0, done: false });
      } else {
        if (tail?.kind === 'text' && !tail.done) arr[arr.length - 1] = { ...tail, text: tail.text + text };
        else {
          markThinkingDone(arr);
          arr.push({ kind: 'text', text, done: false });
        }
      }
      return arr;
    }
    case 'assistant_message': {
      // close the streamed text block; recover content when deltas were coalesced away
      const tail = last(arr);
      if (tail?.kind === 'text' && !tail.done) arr[arr.length - 1] = { ...tail, done: true, seq };
      else {
        const blocks = (p['content'] as { type: string; text?: string; thinking?: string }[] | undefined) ?? [];
        const thinkingText = blocks.filter((b) => b.type === 'thinking' && b.thinking).map((b) => b.thinking).join('');
        if (thinkingText) arr.push({ kind: 'thinking', text: thinkingText, estimatedTokens: 0, done: true });
        const text = blocks.filter((b) => b.type === 'text' && b.text).map((b) => b.text).join('');
        if (text) arr.push({ kind: 'text', text, done: true, seq });
      }
      markThinkingDone(arr);
      return arr;
    }
    case 'tool_started':
      arr.push({ kind: 'tool', toolUseId: p['toolUseId'] as string, name: p['name'] as string, input: p['input'] });
      return arr;
    case 'tool_result': {
      const id = p['toolUseId'] as string;
      for (let i = arr.length - 1; i >= 0; i--) {
        const item = arr[i]!;
        if (item.kind === 'tool' && item.toolUseId === id) {
          arr[i] = { ...item, output: p['output'] as string, isError: p['isError'] as boolean, truncated: p['truncated'] as boolean };
          break;
        }
      }
      return arr;
    }
    default:
      return arr;
  }
}

/** Index of the top-level 'tool' item with this toolUseId, or -1 — used to route a
 * subagent event (parentToolUseId set) into that Task call's nested items. */
function findTopLevelToolIndex(arr: TranscriptItem[], toolUseId: string): number {
  for (let i = arr.length - 1; i >= 0; i--) {
    const item = arr[i]!;
    if (item.kind === 'tool' && item.toolUseId === toolUseId) return i;
  }
  return -1;
}

/** Applies one journal event to a session view (mutates a fresh copy). */
function reduce(view: SessionView, e: Envelope): SessionView {
  const v: SessionView = { ...view, transcript: [...view.transcript] };
  const t = v.transcript;
  const p = e.payload;

  switch (e.type) {
    case 'state_changed':
      v.state = p['state'] as SessionState;
      v.stateSince = e.ts ?? new Date().toISOString();
      if (v.state === 'IDLE' || v.state === 'CRASHED') v.pendingPermission = null;
      break;
    case 'ready':
      v.capabilities = p['capabilities'] as Capabilities;
      break;
    case 'system_init':
      v.model = (p['model'] as string) ?? v.model;
      break;
    case 'user_message':
      t.push({ kind: 'user', text: p['text'] as string, seq: e.seq });
      break;
    case 'block_marked': {
      const marks = { ...v.marks };
      const target = p['targetSeq'] as number;
      const mark = p['mark'];
      if (mark === 'important' || mark === 'excluded') marks[target] = mark;
      else delete marks[target];
      v.marks = marks;
      break;
    }
    case 'diagram_generated':
      v.diagram = { seq: e.seq, fingerprint: p['fingerprint'] as string, diagramType: p['diagramType'] as string };
      break;
    case 'stream_delta':
    case 'assistant_message':
    case 'tool_started':
    case 'tool_result': {
      // A subagent's own thinking/text/tool calls (Task tool) carry parentToolUseId —
      // route those into the owning Task call's nested `items` instead of top level.
      // Falls back to flat top-level if the parent isn't found (defensive: never drop
      // an event over a lookup miss).
      const parentToolUseId = p['parentToolUseId'] as string | undefined;
      const parentIdx = parentToolUseId ? findTopLevelToolIndex(t, parentToolUseId) : -1;
      if (parentIdx !== -1) {
        const parent = t[parentIdx] as Extract<TranscriptItem, { kind: 'tool' }>;
        const items = applyBranchEvent(parent.items ? [...parent.items] : [], e.type, p, e.seq);
        t[parentIdx] = { ...parent, items };
      } else {
        applyBranchEvent(t, e.type, p, e.seq);
      }
      break;
    }
    case 'thinking_progress': {
      // No parentToolUseId is available for this event type (the underlying SDK message
      // carries none) — resolve the open thinking item positionally instead: if the last
      // top-level item is an in-flight Task call, its own preceding stream_delta(thinking)
      // already nested the open thinking item under `items`, so that's unambiguous.
      const estimatedTokens = p['estimatedTokens'] as number;
      const topTail = last(t);
      if (topTail?.kind === 'tool' && topTail.output === undefined && topTail.items) {
        const nestedTail = last(topTail.items);
        if (nestedTail?.kind === 'thinking' && !nestedTail.done) {
          const items = [...topTail.items];
          items[items.length - 1] = { ...nestedTail, estimatedTokens };
          t[t.length - 1] = { ...topTail, items };
        }
      } else if (topTail?.kind === 'thinking' && !topTail.done) {
        t[t.length - 1] = { ...topTail, estimatedTokens };
      }
      break;
    }
    case 'permission_request': {
      const input = p['input'] as Record<string, unknown>;
      const toolName = p['toolName'] as string;
      const plan = toolName === 'ExitPlanMode' && typeof input['plan'] === 'string' ? (input['plan'] as string) : null;
      const requestId = p['requestId'] as string;
      t.push({ kind: 'permission', requestId, toolName, input, plan, decision: null });
      v.pendingPermission = { requestId, toolName, input, plan };
      break;
    }
    case 'permission_response': {
      const id = p['requestId'] as string;
      const updatedInput = p['updatedInput'] as Record<string, unknown> | undefined;
      for (let i = t.length - 1; i >= 0; i--) {
        const item = t[i]!;
        if (item.kind === 'permission' && item.requestId === id) {
          t[i] = { ...item, decision: p['behavior'] as 'allow' | 'deny', input: updatedInput ?? item.input };
          break;
        }
      }
      if (v.pendingPermission?.requestId === id) v.pendingPermission = null;
      break;
    }
    case 'permission_mode_changed':
      v.permissionMode = p['mode'] as PermissionMode;
      break;
    case 'model_changed':
      v.model = p['model'] as string;
      break;
    case 'turn_complete': {
      markThinkingDone(t);
      const tail = last(t);
      if (tail?.kind === 'text' && !tail.done) t[t.length - 1] = { ...tail, done: true };
      const cost = (p['costUsd'] as number) ?? 0;
      t.push({
        kind: 'turn_footer',
        stopReason: p['stopReason'] as string,
        costUsd: cost,
        durationMs: p['durationMs'] as number,
        model: (p['model'] as string) ?? null,
      });
      v.costToDate = view.costToDate + cost;
      break;
    }
    case 'queue_updated':
      v.queued = (p['queued'] as QueuedMessage[]) ?? [];
      break;
    case 'warning':
      t.push({ kind: 'note', level: 'warn', text: p['message'] as string });
      break;
    case 'error':
      t.push({ kind: 'note', level: p['fatal'] ? 'error' : 'warn', text: p['message'] as string });
      break;
    case 'interrupt':
      t.push({ kind: 'note', level: 'info', text: 'interrupted by user' });
      break;
    case 'reflection_proposed':
      t.push({
        kind: 'note', level: 'info',
        text: p['kind'] === 'skill' || p['kind'] === 'remember'
          ? `${p['episode']} — pending approval`
          : `reflection proposed — pending approval: ${p['episode']}`,
      });
      break;
    case 'skill_extracted':
      t.push({ kind: 'note', level: 'info', text: `skill added to the library: ${p['name']}` });
      break;
    case 'reflection_complete': {
      const created = (p['created'] as string[]) ?? [];
      const updated = (p['updated'] as string[]) ?? [];
      const archived = (p['archived'] as string[]) ?? [];
      const parts = [
        created.length ? `${created.length} created` : null,
        updated.length ? `${updated.length} updated` : null,
        archived.length ? `${archived.length} archived` : null,
      ].filter(Boolean);
      const detail = parts.length ? ` (${parts.join(', ')})` : '';
      t.push({
        kind: 'note', level: 'info',
        text: p['episode'] ? `reflection applied${detail}: ${p['episode']}` : `memory saved${detail}`,
      });
      break;
    }
    case 'reflection_discarded':
      t.push({ kind: 'note', level: 'info', text: p['reason'] ? `reflection discarded (${p['reason']})` : 'reflection discarded' });
      break;
    case 'session_renamed':
      v.name = p['name'] as string;
      break;
    case 'budget_updated':
      v.costBudgetUsd = p['costBudgetUsd'] == null ? null : Number(p['costBudgetUsd']);
      break;
    case 'budget_exhausted':
      t.push({
        kind: 'note', level: 'warn',
        text: `cost budget exhausted ($${p['costToDate']} of $${p['costBudgetUsd']}) — raise the budget to continue`,
      });
      break;
    case 'context_usage':
      v.contextTokens = p['tokens'] as number;
      v.contextWindow = p['window'] as number;
      v.autoCompactAt = p['autoCompactAt'] == null ? null : (p['autoCompactAt'] as number);
      v.contextBreakdown = p['breakdown'] == null ? null : (p['breakdown'] as ContextBreakdown);
      break;
    case 'code_intel_status': {
      const status = p['status'] as CodeIntelStatus['status'];
      const nodes = p['nodes'] == null ? null : (p['nodes'] as number);
      const edges = p['edges'] == null ? null : (p['edges'] as number);
      const message = p['message'] == null ? null : (p['message'] as string);
      v.codeIntelStatus = { tool: p['tool'] as string, status, nodes, edges, message, at: e.ts ?? new Date().toISOString() };
      // BUILDING is chip-only (a refresh after every turn would otherwise spam the transcript);
      // a result line lands once per outcome so it's visible after the fact
      if (status === 'READY') {
        const counts = nodes != null && edges != null ? ` — ${nodes} nodes / ${edges} edges` : '';
        t.push({ kind: 'note', level: 'info', text: `${p['tool']} graph ready${counts}` });
      } else if (status === 'FAILED') {
        t.push({ kind: 'note', level: 'warn', text: `${p['tool']} graph build failed: ${message ?? 'unknown error'}` });
      }
      break;
    }
    case 'pr_status_changed':
      v.prCheckStatus = (p['status'] as PrCheckStatus | undefined) ?? v.prCheckStatus;
      v.prUrl = (p['url'] as string | undefined) ?? v.prUrl;
      break;
    case 'context_compacted':
      t.push({
        kind: 'context_compacted',
        preTokens: p['preTokens'] as number,
        postTokens: p['postTokens'] as number,
        trigger: p['trigger'] as 'manual' | 'auto',
      });
      v.ctxWarnArmed = true;
      v.ctxSuggestionVisible = false;
      break;
    default:
      break; // exiting, command echoes, future types
  }
  return v;
}

function markThinkingDone(t: TranscriptItem[]): void {
  for (let i = t.length - 1; i >= 0; i--) {
    const item = t[i]!;
    if (item.kind === 'thinking' && !item.done) {
      t[i] = { ...item, done: true };
      return;
    }
    if (item.kind === 'user') return;
    // an in-flight Task call's own open thinking, one level deep (see reduce()'s
    // parentToolUseId routing — a nested tool item never itself carries `items`)
    if (item.kind === 'tool' && item.output === undefined && item.items) {
      markThinkingDone(item.items);
      return;
    }
  }
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

interface Store {
  views: Record<string, SessionView>;
  apply: (sessionId: string, e: Envelope) => void;
  setWsStatus: (sessionId: string, status: WsStatus) => void;
  seed: (sessionId: string, seedFn: (v: SessionView) => SessionView) => void;
  remove: (sessionId: string) => void;
  // 7.1 keyboard shortcuts: the widget hotkeys act on ("y"/"d", "g", Enter/"i", …)
  focusedId: string | null;
  setFocused: (sessionId: string | null) => void;
  /**
   * Called by the widget whenever contextTokens/contextWindow or the Settings warn
   * threshold change. A no-op unless usage is at/above thresholdPercent AND the session
   * hasn't already fired for this crossing (ctxWarnArmed) — so re-renders while sitting
   * above threshold, or a reading that dips back down, never re-trigger; only a
   * context_compacted (ctxWarnArmed = true) allows the next crossing to fire.
   */
  evaluateContextWarn: (sessionId: string, thresholdPercent: number) => void;
  dismissContextWarn: (sessionId: string) => void;
}

export const useStore = create<Store>((set) => ({
  views: {},
  apply: (sessionId, e) =>
    set((s) => ({ views: { ...s.views, [sessionId]: reduce(s.views[sessionId] ?? emptyView(), e) } })),
  setWsStatus: (sessionId, status) =>
    set((s) => ({ views: { ...s.views, [sessionId]: { ...(s.views[sessionId] ?? emptyView()), wsStatus: status } } })),
  seed: (sessionId, seedFn) =>
    set((s) => ({ views: { ...s.views, [sessionId]: seedFn(s.views[sessionId] ?? emptyView()) } })),
  remove: (sessionId) =>
    set((s) => {
      const views = { ...s.views };
      delete views[sessionId];
      return { views };
    }),
  focusedId: null,
  setFocused: (sessionId) => set({ focusedId: sessionId }),
  evaluateContextWarn: (sessionId, thresholdPercent) =>
    set((s) => {
      const v = s.views[sessionId];
      if (!v || v.contextTokens == null || v.contextWindow == null || v.contextWindow <= 0 || !v.ctxWarnArmed) {
        return s;
      }
      const percentage = (v.contextTokens / v.contextWindow) * 100;
      if (percentage < thresholdPercent) return s;
      return { views: { ...s.views, [sessionId]: { ...v, ctxWarnArmed: false, ctxSuggestionVisible: true } } };
    }),
  dismissContextWarn: (sessionId) =>
    set((s) => {
      const v = s.views[sessionId];
      if (!v) return s;
      return { views: { ...s.views, [sessionId]: { ...v, ctxSuggestionVisible: false } } };
    }),
}));
