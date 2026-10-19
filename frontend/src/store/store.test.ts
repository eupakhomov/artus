import { beforeEach, describe, expect, it } from 'vitest';
import { useStore } from './store';
import type { Envelope } from '../protocol';

// docs/plan/phase-9-production-hardening.md T6: event application in store/store.ts.
// `reduce` itself isn't exported, so these drive it the same way the app does — through
// the store's public apply()/seed() actions — and assert on the resulting SessionView.

const SID = 's1';

function env(type: string, payload: Record<string, unknown> = {}, seq = 1): Envelope {
  return { seq, type, payload };
}

beforeEach(() => {
  useStore.setState({ views: {}, focusedId: null });
});

describe('state_changed', () => {
  it('updates state', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    expect(useStore.getState().views[SID]?.state).toBe('RUNNING');
  });

  it('clears a pending permission when the session goes IDLE', () => {
    useStore.getState().apply(SID, env('permission_request', {
      requestId: 'r1', toolName: 'Bash', input: {},
    }));
    expect(useStore.getState().views[SID]?.pendingPermission).not.toBeNull();

    useStore.getState().apply(SID, env('state_changed', { state: 'IDLE' }));

    expect(useStore.getState().views[SID]?.pendingPermission).toBeNull();
  });

  it('clears a pending permission on CRASHED too', () => {
    useStore.getState().apply(SID, env('permission_request', { requestId: 'r1', toolName: 'Bash', input: {} }));
    useStore.getState().apply(SID, env('state_changed', { state: 'CRASHED' }));
    expect(useStore.getState().views[SID]?.pendingPermission).toBeNull();
  });
});

describe('queue_updated', () => {
  it('replaces the queued list', () => {
    useStore.getState().apply(SID, env('queue_updated', { queued: [{ pos: 1, text: 'a' }] }));
    expect(useStore.getState().views[SID]?.queued).toEqual([{ pos: 1, text: 'a' }]);

    useStore.getState().apply(SID, env('queue_updated', { queued: [] }));
    expect(useStore.getState().views[SID]?.queued).toEqual([]);
  });
});

describe('reflection events', () => {
  it('reflection_proposed adds an info note with the episode text', () => {
    useStore.getState().apply(SID, env('reflection_proposed', { episode: 'did a thing' }));
    const t = useStore.getState().views[SID]!.transcript;
    expect(t.at(-1)).toMatchObject({ kind: 'note', level: 'info' });
    expect((t.at(-1) as { text: string }).text).toContain('did a thing');
  });

  it('reflection_complete summarizes created/updated/archived counts', () => {
    useStore.getState().apply(SID, env('reflection_complete', {
      episode: 'summary', created: ['a', 'b'], updated: ['c'], archived: [],
    }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { text: string };
    expect(note.text).toContain('2 created');
    expect(note.text).toContain('1 updated');
    expect(note.text).not.toContain('archived');
  });

  it('reflection_complete with no ops omits the parenthetical', () => {
    useStore.getState().apply(SID, env('reflection_complete', { episode: 'nothing to do' }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { text: string };
    expect(note.text).toBe('reflection applied: nothing to do');
  });

  it('reflection_discarded adds a plain note', () => {
    useStore.getState().apply(SID, env('reflection_discarded'));
    expect(useStore.getState().views[SID]!.transcript.at(-1)).toMatchObject({
      kind: 'note', level: 'info', text: 'reflection discarded',
    });
  });
});

describe('budget events', () => {
  it('budget_updated sets costBudgetUsd, null clears it', () => {
    useStore.getState().apply(SID, env('budget_updated', { costBudgetUsd: '5.50' }));
    expect(useStore.getState().views[SID]?.costBudgetUsd).toBe(5.5);

    useStore.getState().apply(SID, env('budget_updated', { costBudgetUsd: null }));
    expect(useStore.getState().views[SID]?.costBudgetUsd).toBeNull();
  });

  it('budget_exhausted appends a warning note with both amounts', () => {
    useStore.getState().apply(SID, env('budget_exhausted', { costToDate: '10.00', costBudgetUsd: '10.00' }));
    const note = useStore.getState().views[SID]!.transcript.at(-1) as { level: string; text: string };
    expect(note.level).toBe('warn');
    expect(note.text).toContain('10.00');
  });
});

describe('transcript reduction', () => {
  it('coalesces consecutive text stream_delta events into one item until done', () => {
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'Hel' }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'lo' }));
    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ kind: 'text', text: 'Hello', done: false });
  });

  it('turn_complete closes an open text block, appends a footer, and accumulates cost', () => {
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'hi' }));
    useStore.getState().apply(SID, env('turn_complete', { stopReason: 'end_turn', costUsd: 0.02, durationMs: 100 }));
    useStore.getState().apply(SID, env('turn_complete', { stopReason: 'end_turn', costUsd: 0.03, durationMs: 50 }));

    const view = useStore.getState().views[SID]!;
    expect(view.transcript[0]).toMatchObject({ kind: 'text', done: true });
    expect(view.transcript.filter((i) => i.kind === 'turn_footer')).toHaveLength(2);
    expect(view.costToDate).toBeCloseTo(0.05);
  });

  it('tool_result attaches output to the matching tool_started by toolUseId', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu1', name: 'Bash', input: { cmd: 'ls' } }));
    useStore.getState().apply(SID, env('tool_result', { toolUseId: 'tu1', output: 'file.txt', isError: false }));

    const tool = useStore.getState().views[SID]!.transcript[0];
    expect(tool).toMatchObject({ kind: 'tool', toolUseId: 'tu1', output: 'file.txt', isError: false });
  });

  it('permission_response resolves the matching pending permission and clears it', () => {
    useStore.getState().apply(SID, env('permission_request', { requestId: 'r1', toolName: 'Bash', input: {} }));
    useStore.getState().apply(SID, env('permission_response', { requestId: 'r1', behavior: 'allow' }));

    const view = useStore.getState().views[SID]!;
    expect(view.pendingPermission).toBeNull();
    expect(view.transcript[0]).toMatchObject({ kind: 'permission', decision: 'allow' });
  });
});

describe('nested subagent transcript', () => {
  it('routes a tool_started with parentToolUseId under the owning Task call, not top level', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: { description: 'explore' } }));
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_read', name: 'Read', input: { file_path: 'a.ts' }, parentToolUseId: 'tu_task' }));

    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    const task = t[0] as { kind: 'tool'; items?: unknown[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ kind: 'tool', toolUseId: 'tu_read', name: 'Read' });
  });

  it('resolves a nested tool_result against the nested tool_started, not a same-id top-level one', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: {} }));
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_shared', name: 'Bash', input: { command: 'ls' }, parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('tool_result', { toolUseId: 'tu_shared', output: 'ok', isError: false, parentToolUseId: 'tu_task' }));

    const task = useStore.getState().views[SID]!.transcript[0] as { items?: { toolUseId: string; output?: string }[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ toolUseId: 'tu_shared', output: 'ok' });
  });

  it('coalesces nested thinking deltas and applies thinking_progress to the nested item', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_task', name: 'Task', input: {} }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'thinking', text: 'pon', parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'thinking', text: 'dering', parentToolUseId: 'tu_task' }));
    useStore.getState().apply(SID, env('thinking_progress', { estimatedTokens: 42, estimatedTokensDelta: 42 }));

    const task = useStore.getState().views[SID]!.transcript[0] as { items?: { kind: string; text: string; estimatedTokens: number; done: boolean }[] };
    expect(task.items).toHaveLength(1);
    expect(task.items![0]).toMatchObject({ kind: 'thinking', text: 'pondering', estimatedTokens: 42, done: false });

    // top-level transcript is unaffected by the nested progress update
    expect(useStore.getState().views[SID]!.transcript).toHaveLength(1);
  });

  it('falls back to flat top-level when parentToolUseId matches no known tool call', () => {
    useStore.getState().apply(SID, env('tool_started', { toolUseId: 'tu_orphan', name: 'Read', input: {}, parentToolUseId: 'tu_missing' }));

    const t = useStore.getState().views[SID]!.transcript;
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ kind: 'tool', toolUseId: 'tu_orphan' });
  });
});

describe('store actions outside reduce', () => {
  it('setWsStatus updates only wsStatus, preserving the rest of the view', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    useStore.getState().setWsStatus(SID, 'open');
    const view = useStore.getState().views[SID]!;
    expect(view.wsStatus).toBe('open');
    expect(view.state).toBe('RUNNING');
  });

  it('remove deletes the view entirely', () => {
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    useStore.getState().remove(SID);
    expect(useStore.getState().views[SID]).toBeUndefined();
  });

  it('seed applies a transform function, defaulting to an empty view', () => {
    useStore.getState().seed(SID, (v) => ({ ...v, name: 'seeded' }));
    expect(useStore.getState().views[SID]?.name).toBe('seeded');
  });

  // phase 15: the Exposé card's review chip reads this from the store, seeded once from the
  // REST entity fetch the same way servicePath/branch already are (SessionWidget's effect)
  it('sessionType defaults to null and can be seeded like servicePath/branch', () => {
    expect(useStore.getState().views[SID]).toBeUndefined();
    useStore.getState().seed(SID, (v) => ({ ...v, sessionType: 'review' }));
    expect(useStore.getState().views[SID]?.sessionType).toBe('review');
  });
});

describe('context_usage', () => {
  it('stores the breakdown when present and clears it when a later snapshot has none', () => {
    const breakdown = {
      categories: [{ name: 'Messages', tokens: 900 }],
      mcpServers: [{ name: 'linear', tokens: 300, deferred: true }],
      topTools: [],
    };
    useStore.getState().apply(SID, env('context_usage', { tokens: 1000, window: 200000, breakdown }));
    expect(useStore.getState().views[SID]!.contextBreakdown).toEqual(breakdown);
    useStore.getState().apply(SID, env('context_usage', { tokens: 1100, window: 200000 }));
    expect(useStore.getState().views[SID]!.contextBreakdown).toBeNull();
  });

  it('records tokens/window/autoCompactAt', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 38000, window: 200000, autoCompactAt: 178000 }));
    const v = useStore.getState().views[SID]!;
    expect(v.contextTokens).toBe(38000);
    expect(v.contextWindow).toBe(200000);
    expect(v.autoCompactAt).toBe(178000);
  });

  it('leaves autoCompactAt null when the provider does not report one', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 100, window: 1000 }));
    expect(useStore.getState().views[SID]!.autoCompactAt).toBeNull();
  });
});

describe('context_compacted', () => {
  it('pushes a transcript divider and re-arms the warn flag', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 190000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);

    useStore.getState().apply(SID, env('context_compacted', { preTokens: 190000, postTokens: 20000, trigger: 'manual' }));

    const v = useStore.getState().views[SID]!;
    expect(v.transcript.at(-1)).toEqual({ kind: 'context_compacted', preTokens: 190000, postTokens: 20000, trigger: 'manual' });
    expect(v.ctxWarnArmed).toBe(true);
    expect(v.ctxSuggestionVisible).toBe(false);
  });
});

describe('evaluateContextWarn / dismissContextWarn', () => {
  it('does nothing below the threshold', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 50000, window: 200000 })); // 25%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);
  });

  it('fires once on crossing and does not re-fire on a later evaluation while still above threshold', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);
    expect(useStore.getState().views[SID]!.ctxWarnArmed).toBe(false);

    useStore.getState().dismissContextWarn(SID);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);

    // still above threshold, no compaction happened — must not re-show
    useStore.getState().apply(SID, env('context_usage', { tokens: 160000, window: 200000 })); // 80%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);
  });

  it('a lower reading after a crossing does not re-trigger, even if it rises again, without a compaction', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    useStore.getState().dismissContextWarn(SID);

    useStore.getState().apply(SID, env('context_usage', { tokens: 100000, window: 200000 })); // 50%, dips below
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false);

    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 })); // back to 75%
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(false); // still armed=false — no compaction happened
  });

  it('re-arms after a compaction, so the next crossing fires again', () => {
    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    useStore.getState().apply(SID, env('context_compacted', { preTokens: 150000, postTokens: 20000, trigger: 'manual' }));

    useStore.getState().apply(SID, env('context_usage', { tokens: 150000, window: 200000 }));
    useStore.getState().evaluateContextWarn(SID, 70);
    expect(useStore.getState().views[SID]!.ctxSuggestionVisible).toBe(true);
  });
});

describe('code_intel_status', () => {
  it('tracks BUILDING chip-only, without a transcript line', () => {
    useStore.getState().apply(SID, env('code_intel_status', { tool: 'graphify', status: 'BUILDING' }));
    const v = useStore.getState().views[SID]!;
    expect(v.codeIntelStatus).toMatchObject({ tool: 'graphify', status: 'BUILDING', nodes: null, edges: null, message: null });
    expect(v.transcript.some((i) => i.kind === 'note')).toBe(false);
  });

  it('records READY with counts and one transcript line', () => {
    useStore.getState().apply(SID, env('code_intel_status', { tool: 'graphify', status: 'BUILDING' }));
    useStore.getState().apply(SID, env('code_intel_status', { tool: 'graphify', status: 'READY', nodes: 2572, edges: 7685, durationMs: 80123 }, 2));
    const v = useStore.getState().views[SID]!;
    expect(v.codeIntelStatus).toMatchObject({ status: 'READY', nodes: 2572, edges: 7685 });
    expect(v.transcript.at(-1)).toEqual({ kind: 'note', level: 'info', text: 'graphify graph ready — 2572 nodes / 7685 edges' });
  });

  it('records FAILED with the message as a warning line', () => {
    useStore.getState().apply(SID, env('code_intel_status', { tool: 'graphify', status: 'FAILED', message: 'timed out after 15 min' }));
    const v = useStore.getState().views[SID]!;
    expect(v.codeIntelStatus).toMatchObject({ status: 'FAILED', message: 'timed out after 15 min' });
    expect(v.transcript.at(-1)).toEqual({ kind: 'note', level: 'warn', text: 'graphify graph build failed: timed out after 15 min' });
  });
});

describe('phase 16 sidebar fields', () => {
  it('stamps stateSince from the envelope ts, so a replayed transition keeps its original time', () => {
    useStore.getState().apply(SID, { seq: 3, ts: '2026-10-01T10:00:00Z', type: 'state_changed', payload: { state: 'WAITING_INPUT' } });
    expect(useStore.getState().views[SID]?.stateSince).toBe('2026-10-01T10:00:00Z');
  });

  it('falls back to now when an envelope carries no ts', () => {
    const before = Date.now();
    useStore.getState().apply(SID, env('state_changed', { state: 'RUNNING' }));
    const since = new Date(useStore.getState().views[SID]!.stateSince!).getTime();
    expect(since).toBeGreaterThanOrEqual(before);
  });

  it('pr_status_changed updates status and url, keeping the url when the payload omits it', () => {
    useStore.getState().seed(SID, (v) => ({ ...v, prUrl: 'https://github.com/o/r/pull/1', prCheckStatus: 'PENDING' }));
    useStore.getState().apply(SID, env('pr_status_changed', { status: 'FAILURE', previousStatus: 'PENDING' }));
    const v = useStore.getState().views[SID]!;
    expect(v.prCheckStatus).toBe('FAILURE');
    expect(v.prUrl).toBe('https://github.com/o/r/pull/1');
  });
});

// phase 21: memory-curation marks — seq identity on markable blocks and the block_marked fold.
describe('block marks', () => {
  it('stamps the journal seq on user messages', () => {
    useStore.getState().apply(SID, env('user_message', { text: 'hi' }, 7));
    expect(useStore.getState().views[SID]?.transcript[0]).toMatchObject({ kind: 'user', seq: 7 });
  });

  it('gives a streamed text block its seq when the assistant_message finalizes it', () => {
    useStore.getState().apply(SID, env('stream_delta', { deltaType: 'text', text: 'partial' }, 2));
    expect(useStore.getState().views[SID]?.transcript[0]).toMatchObject({ kind: 'text', done: false });
    expect((useStore.getState().views[SID]?.transcript[0] as { seq?: number }).seq).toBeUndefined();

    useStore.getState().apply(SID, env('assistant_message', { content: [{ type: 'text', text: 'partial' }] }, 3));

    expect(useStore.getState().views[SID]?.transcript[0]).toMatchObject({ kind: 'text', done: true, seq: 3 });
  });

  it('gives coalesced assistant text (no deltas) its seq too', () => {
    useStore.getState().apply(SID, env('assistant_message', { content: [{ type: 'text', text: 'whole' }] }, 4));
    expect(useStore.getState().views[SID]?.transcript[0]).toMatchObject({ kind: 'text', seq: 4 });
  });

  it('folds block_marked events: last wins, null clears', () => {
    const { apply } = useStore.getState();
    apply(SID, env('block_marked', { targetSeq: 1, mark: 'important' }, 5));
    apply(SID, env('block_marked', { targetSeq: 2, mark: 'excluded' }, 6));
    expect(useStore.getState().views[SID]?.marks).toEqual({ 1: 'important', 2: 'excluded' });

    apply(SID, env('block_marked', { targetSeq: 1, mark: 'excluded' }, 7));
    apply(SID, env('block_marked', { targetSeq: 2, mark: null }, 8));
    expect(useStore.getState().views[SID]?.marks).toEqual({ 1: 'excluded' });
  });
});

describe('diagram_generated (phase 22)', () => {
  it('keeps only the latest diagram, by journal order', () => {
    const { apply } = useStore.getState();
    apply(SID, env('diagram_generated', { diagramType: 'flowchart', mermaid: 'flowchart TD', fingerprint: 'a' }, 3));
    apply(SID, env('diagram_generated', { diagramType: 'sequence', mermaid: 'sequenceDiagram', fingerprint: 'b' }, 9));
    expect(useStore.getState().views[SID]?.diagram).toEqual({ seq: 9, fingerprint: 'b', diagramType: 'sequence' });
  });
});

describe('phase 21 reflection notes', () => {
  const notes = () => (useStore.getState().views[SID]?.transcript ?? []).filter((i) => i.kind === 'note') as { text: string }[];

  it('a skill proposal and a remember proposal read as pending approval', () => {
    useStore.getState().apply(SID, env('reflection_proposed', { kind: 'skill', episode: 'skill proposed: deploy-checklist' }, 1));
    useStore.getState().apply(SID, env('reflection_proposed', { kind: 'remember', episode: 'remember block #4: 1 memory op(s) proposed' }, 2));
    expect(notes().map((n) => n.text)).toEqual([
      'skill proposed: deploy-checklist — pending approval',
      'remember block #4: 1 memory op(s) proposed — pending approval',
    ]);
  });

  it('skill_extracted names the new library skill', () => {
    useStore.getState().apply(SID, env('skill_extracted', { name: 'deploy-checklist' }));
    expect(notes()[0]?.text).toBe('skill added to the library: deploy-checklist');
  });

  it('a superseded reflection says why; an episode-less apply reads as a saved memory', () => {
    useStore.getState().apply(SID, env('reflection_discarded', { reason: 'superseded by refresh' }, 1));
    useStore.getState().apply(SID, env('reflection_complete', { episode: '', created: ['a-note'] }, 2));
    expect(notes().map((n) => n.text)).toEqual(['reflection discarded (superseded by refresh)', 'memory saved (1 created)']);
  });
});

// phase 25: live turn, tasks and background work
describe('live turn', () => {
  const apply = (type: string, payload: Record<string, unknown> = {}) => useStore.getState().apply(SID, env(type, payload));
  const view = () => useStore.getState().views[SID]!;

  it('tracks activity, tool count and the estimated cost until turn_complete', () => {
    apply('system_init', { model: 'claude-sonnet-5' });
    expect(view().liveTurn).toMatchObject({ activity: null, toolCount: 0, estimatedCostUsd: null });

    apply('stream_delta', { deltaType: 'thinking', text: 'hm' });
    expect(view().liveTurn?.activity).toEqual({ kind: 'thinking' });
    apply('tool_started', { toolUseId: 't1', name: 'Bash', input: { command: 'npm test' } });
    expect(view().liveTurn?.activity).toEqual({ kind: 'tool', name: 'Bash', input: { command: 'npm test' }, subagent: null });
    apply('turn_usage', { byModel: {}, estimatedCostUsd: 0.42 });
    apply('tool_result', { toolUseId: 't1', output: 'ok', isError: false, truncated: false });
    expect(view().liveTurn).toMatchObject({ activity: { kind: 'thinking' }, toolCount: 1, estimatedCostUsd: 0.42 });

    apply('turn_complete', { stopReason: 'success', costUsd: 0.4, durationMs: 1, model: 'm' });
    expect(view().liveTurn).toBeNull();
  });

  it('names the subagent that made a nested call', () => {
    apply('system_init', {});
    apply('tool_started', { toolUseId: 'task', name: 'Agent', input: { subagent_type: 'Explore', description: 'map auth' } });
    apply('tool_started', { toolUseId: 'n1', name: 'Grep', input: { pattern: 'login' }, parentToolUseId: 'task' });
    expect(view().liveTurn?.activity).toEqual({ kind: 'tool', name: 'Grep', input: { pattern: 'login' }, subagent: 'Explore' });
    expect(view().liveTurn?.toolCount).toBe(2);
  });

  it('is dropped when the session stops running', () => {
    apply('system_init', {});
    apply('state_changed', { state: 'CRASHED' });
    expect(view().liveTurn).toBeNull();
  });

  it('ignores a backgrounded subagent working while idle, and restarts the clock when the provider opens a turn', () => {
    apply('state_changed', { state: 'IDLE' });
    useStore.getState().apply(SID, { seq: 2, ts: '2026-10-19T10:00:00Z', type: 'tool_started', payload: { toolUseId: 'n1', name: 'Read', input: {}, parentToolUseId: 'task' } });
    apply('turn_usage', { byModel: {}, estimatedCostUsd: 0.01 });
    expect(view().liveTurn).toBeNull();

    // the provider's own turn: system_init arrives while still IDLE, RUNNING follows
    useStore.getState().apply(SID, { seq: 3, ts: '2026-10-19T10:00:06Z', type: 'system_init', payload: {} });
    apply('state_changed', { state: 'RUNNING' });
    expect(view().liveTurn).toMatchObject({ startedAt: '2026-10-19T10:00:06Z', toolCount: 0, estimatedCostUsd: null });
  });

  it('adds no footer for the empty turn a resumed CLI opens', () => {
    apply('turn_complete', { stopReason: 'success', costUsd: 0, numTurns: 0, durationMs: 35, model: 'm' });
    expect(view().transcript.some((i) => i.kind === 'turn_footer')).toBe(false);
    apply('turn_complete', { stopReason: 'success', costUsd: 0.01, numTurns: 1, durationMs: 900, model: 'm' });
    expect(view().transcript.filter((i) => i.kind === 'turn_footer')).toHaveLength(1);
  });
});

describe('tasks', () => {
  const apply = (type: string, payload: Record<string, unknown> = {}) => useStore.getState().apply(SID, env(type, payload));
  const taskItem = () => useStore.getState().views[SID]!.transcript.find((i) => i.kind === 'tool');

  it('keeps a backgrounded subagent running after its call returned, and notes its end', () => {
    apply('tool_started', { toolUseId: 'tu', name: 'Agent', input: { subagent_type: 'Explore', description: 'List files' } });
    apply('task_started', { taskId: 'k', toolUseId: 'tu', description: 'List files', subagentType: 'Explore', backgrounded: true });
    apply('tool_result', { toolUseId: 'tu', output: 'running in the background', isError: false, truncated: false });
    apply('task_progress', { taskId: 'k', toolUseId: 'tu', description: 'Running ls', lastTool: 'Bash', toolUses: 3, tokens: 10, durationMs: 4000 });
    expect(taskItem()).toMatchObject({ task: { status: 'running', backgrounded: true, lastTool: 'Bash', toolUses: 3, durationMs: 4000 } });

    apply('task_done', { taskId: 'k', toolUseId: 'tu', status: 'completed', summary: '3 files' });
    expect(taskItem()).toMatchObject({ task: { status: 'completed', summary: '3 files' } });
    expect(useStore.getState().views[SID]!.transcript.at(-1)).toEqual({ kind: 'note', level: 'info', text: 'background task completed: List files' });
  });

  it('does not note the end of a foreground subagent', () => {
    apply('tool_started', { toolUseId: 'tu', name: 'Agent', input: { subagent_type: 'Explore', description: 'x' } });
    apply('task_started', { taskId: 'k', toolUseId: 'tu', description: 'x', backgrounded: false });
    apply('task_done', { taskId: 'k', toolUseId: 'tu', status: 'completed', summary: '' });
    expect(useStore.getState().views[SID]!.transcript.some((i) => i.kind === 'note')).toBe(false);
  });

  it('does not note a subagent\'s own long-running foreground command', () => {
    // the CLI reports those as tasks too, linked to the nested call — not a top-level row
    apply('tool_started', { toolUseId: 'tu', name: 'Agent', input: { subagent_type: 'general-purpose', description: 'x' } });
    apply('tool_started', { toolUseId: 'n1', name: 'Bash', input: { command: 'sleep 5' }, parentToolUseId: 'tu' });
    apply('task_started', { taskId: 'b', toolUseId: 'n1', description: 'sleep 5', kind: 'local_bash', backgrounded: false });
    apply('task_done', { taskId: 'b', toolUseId: 'n1', status: 'completed', summary: 'sleep 5' });
    expect(useStore.getState().views[SID]!.transcript.some((i) => i.kind === 'note')).toBe(false);
  });

  it('stops a running task when its sidecar goes away, without a second note when the resumed CLI says so', () => {
    apply('tool_started', { toolUseId: 'tu', name: 'Bash', input: { command: 'sleep 300', run_in_background: true } });
    apply('task_started', { taskId: 'k', toolUseId: 'tu', description: 'sleep 300', kind: 'local_bash', backgrounded: true });
    apply('tool_result', { toolUseId: 'tu', output: 'running in background', isError: false, truncated: false });
    apply('state_changed', { state: 'CRASHED' });
    expect(taskItem()).toMatchObject({ task: { status: 'stopped' } });

    apply('task_done', { taskId: 'k', toolUseId: 'tu', status: 'stopped', summary: 'No completion record was found' });
    expect(useStore.getState().views[SID]!.transcript.some((i) => i.kind === 'note')).toBe(false);
  });

  it('replaces the background set and clears it when the sidecar goes away', () => {
    apply('background_tasks', { tasks: [{ taskId: 'k', kind: 'local_bash', description: 'sleep 120' }] });
    expect(useStore.getState().views[SID]!.backgroundTasks).toHaveLength(1);
    apply('state_changed', { state: 'PARKED' });
    expect(useStore.getState().views[SID]!.backgroundTasks).toEqual([]);
  });
});

describe('session_renamed', () => {
  it('notes an automatic rename but not a manual one', () => {
    useStore.getState().apply(SID, env('session_renamed', { name: 'Fix login', auto: true, from: 'feat/x' }));
    useStore.getState().apply(SID, env('session_renamed', { name: 'Mine', auto: false }));
    const notes = useStore.getState().views[SID]!.transcript.filter((i) => i.kind === 'note');
    expect(notes).toHaveLength(1);
    expect(notes[0]).toMatchObject({ text: expect.stringContaining('renamed to "Fix login" (was feat/x)') });
    expect(useStore.getState().views[SID]!.name).toBe('Mine');
  });
});

describe('child session notes', () => {
  it('says when a child was spawned and when it needs attention', () => {
    useStore.getState().apply(SID, env('child_spawned', { childId: 'c', childName: 'api: feat', service: 'api' }));
    useStore.getState().apply(SID, env('child_attention', { childId: 'c', childName: 'api: feat', state: 'WAITING_INPUT' }));
    const notes = useStore.getState().views[SID]!.transcript;
    expect(notes[0]).toEqual({ kind: 'note', level: 'info', text: 'spawned child session api: feat (api)' });
    expect(notes[1]).toMatchObject({ level: 'warn', text: expect.stringContaining('waiting for approval') });
  });
});
