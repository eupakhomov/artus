import { describe, expect, it } from 'vitest';
import { classifyStreamFailure, newTranslateState, translateSdkMessage } from '../src/session.js';

function state(currentModel = '', lastTotalCostUsd = 0) {
  return { ...newTranslateState(currentModel), lastTotalCostUsd };
}

describe('translateSdkMessage', () => {
  it('translates system/init into system_init and records the model', () => {
    const s = state();
    const { events, logs } = translateSdkMessage(
      {
        type: 'system',
        subtype: 'init',
        session_id: 'abc123',
        model: 'sonnet',
        cwd: '/work/s1',
        tools: ['Edit', 'Bash'],
        mcp_servers: [{ name: 'linear', status: 'connected' }],
        permissionMode: 'default',
      },
      s,
    );
    expect(logs).toEqual([]);
    expect(events).toEqual([
      {
        type: 'system_init',
        providerSessionId: 'abc123',
        model: 'sonnet',
        cwd: '/work/s1',
        tools: ['Edit', 'Bash'],
        mcpServers: [{ name: 'linear', status: 'connected' }],
        permissionMode: 'default',
      },
    ]);
    expect(s.currentModel).toBe('sonnet');
  });

  it('translates system/thinking_tokens into thinking_progress', () => {
    const { events } = translateSdkMessage(
      { type: 'system', subtype: 'thinking_tokens', estimated_tokens: 120, estimated_tokens_delta: 20 },
      state(),
    );
    expect(events).toEqual([{ type: 'thinking_progress', estimatedTokens: 120, estimatedTokensDelta: 20 }]);
  });

  it('ignores unrecognized system subtypes', () => {
    const { events, logs } = translateSdkMessage({ type: 'system', subtype: 'something_else' }, state());
    expect(events).toEqual([]);
    expect(logs).toEqual([]);
  });

  it('translates a text content_block_delta into a stream_delta', () => {
    const { events } = translateSdkMessage(
      { type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } } },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'text', text: 'hi' }]);
  });

  it('translates a thinking content_block_delta into a stream_delta', () => {
    const { events } = translateSdkMessage(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'pondering' } },
      },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'thinking', text: 'pondering' }]);
  });

  it('carries parentToolUseId on a stream_delta produced inside a subagent', () => {
    const { events } = translateSdkMessage(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } },
        parent_tool_use_id: 'tu_task',
      },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'text', text: 'hi', parentToolUseId: 'tu_task' }]);
  });

  it('omits parentToolUseId (not just undefined) when the SDK reports null', () => {
    const { events } = translateSdkMessage(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'hi' } },
        parent_tool_use_id: null,
      },
      state(),
    );
    expect(events).toEqual([{ type: 'stream_delta', deltaType: 'text', text: 'hi' }]);
    expect(events[0]).not.toHaveProperty('parentToolUseId');
  });

  it('logs unmapped stream deltas instead of emitting an event', () => {
    const { events, logs } = translateSdkMessage(
      { type: 'stream_event', event: { type: 'content_block_delta', delta: { type: 'input_json_delta' } } },
      state(),
    );
    expect(events).toEqual([]);
    expect(logs).toHaveLength(1);
    expect(logs[0]).toContain('unmapped stream delta');
  });

  it('translates an assistant message into assistant_message plus tool_started per tool_use block', () => {
    const { events } = translateSdkMessage(
      {
        type: 'assistant',
        message: {
          content: [
            { type: 'text', text: 'ok' },
            { type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'ls' } },
          ],
        },
      },
      state(),
    );
    expect(events).toEqual([
      {
        type: 'assistant_message',
        content: [
          { type: 'text', text: 'ok' },
          { type: 'tool_use', id: 'tu1', name: 'Bash', input: { command: 'ls' } },
        ],
      },
      { type: 'tool_started', toolUseId: 'tu1', name: 'Bash', input: { command: 'ls' } },
    ]);
  });

  it('carries parentToolUseId through assistant_message and tool_started when produced inside a subagent', () => {
    const { events } = translateSdkMessage(
      {
        type: 'assistant',
        message: { content: [{ type: 'tool_use', id: 'tu2', name: 'Read', input: { file_path: 'x.ts' } }] },
        parent_tool_use_id: 'tu_task',
      },
      state(),
    );
    expect(events).toEqual([
      {
        type: 'assistant_message',
        content: [{ type: 'tool_use', id: 'tu2', name: 'Read', input: { file_path: 'x.ts' } }],
        parentToolUseId: 'tu_task',
      },
      { type: 'tool_started', toolUseId: 'tu2', name: 'Read', input: { file_path: 'x.ts' }, parentToolUseId: 'tu_task' },
    ]);
  });

  it('translates a user message tool_result into tool_result, truncating long output', () => {
    const long = 'x'.repeat(17 * 1024);
    const { events } = translateSdkMessage(
      {
        type: 'user',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu1', content: long, is_error: false }] },
      },
      state(),
    );
    expect(events).toHaveLength(1);
    const ev = events[0] as { type: string; truncated: boolean; output: string; isError: boolean };
    expect(ev.type).toBe('tool_result');
    expect(ev.isError).toBe(false);
    expect(ev.truncated).toBe(true);
    expect(ev.output.length).toBe(16384);
  });

  it('carries parentToolUseId on a tool_result produced inside a subagent', () => {
    const { events } = translateSdkMessage(
      {
        type: 'user',
        message: { content: [{ type: 'tool_result', tool_use_id: 'tu2', content: 'ok', is_error: false }] },
        parent_tool_use_id: 'tu_task',
      },
      state(),
    );
    expect(events).toEqual([
      { type: 'tool_result', toolUseId: 'tu2', isError: false, output: 'ok', truncated: false, parentToolUseId: 'tu_task' },
    ]);
  });

  it('ignores a user message with non-array content', () => {
    const { events } = translateSdkMessage({ type: 'user', message: { content: 'plain text' } }, state());
    expect(events).toEqual([]);
  });

  it('translates a result message into turn_complete, stamping the tracked model', () => {
    const { events } = translateSdkMessage(
      {
        type: 'result',
        subtype: 'success',
        usage: { input_tokens: 1 },
        total_cost_usd: 0.02,
        duration_ms: 500,
        num_turns: 3,
      },
      state('opus'),
    );
    expect(events).toEqual([
      {
        type: 'turn_complete',
        stopReason: 'success',
        usage: { input_tokens: 1 },
        costUsd: 0.02,
        sessionCostUsd: 0.02,
        durationMs: 500,
        numTurns: 3,
        model: 'opus',
      },
    ]);
  });

  it('reports each turn its own cost, not the SDK running total', () => {
    const s = state('opus');
    const costs = [1, 2, 4, 5].map((total) => {
      const { events } = translateSdkMessage({ type: 'result', subtype: 'success', usage: {}, total_cost_usd: total, duration_ms: 1, num_turns: 1 }, s);
      return (events[0] as { costUsd: number }).costUsd;
    });
    expect(costs).toEqual([1, 1, 2, 1]);
    expect(s.lastTotalCostUsd).toBe(5);
  });

  it('counts each model call once although the SDK repeats its usage per content block', () => {
    const s = state('sonnet');
    const usage = { input_tokens: 10, output_tokens: 3, cache_read_input_tokens: 100, cache_creation_input_tokens: 50, cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 50 } };
    const start = translateSdkMessage({ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1', model: 'claude-sonnet-5', usage } } }, s);
    expect(start.events).toEqual([
      { type: 'turn_usage', byModel: { 'claude-sonnet-5': { input: 10, output: 3, cacheRead: 100, cacheWrite5m: 0, cacheWrite1h: 50 } } },
    ]);
    // split assistant messages repeating the same partial usage add nothing — as seen live, they
    // carry the cache-write total without the 5m/1h breakdown, which must not be re-counted as 5m
    const { cache_creation: _, ...noBreakdown } = usage;
    for (const block of ['thinking', 'tool_use']) {
      const { events } = translateSdkMessage({ type: 'assistant', message: { id: 'm1', model: 'claude-sonnet-5', usage: noBreakdown, content: [{ type: block }] } }, s);
      expect(events.some((e) => e.type === 'turn_usage')).toBe(false);
    }
    expect(s.usage.byModel()['claude-sonnet-5']).toMatchObject({ cacheWrite5m: 0, cacheWrite1h: 50 });
    const delta = translateSdkMessage({ type: 'stream_event', event: { type: 'message_delta', usage: { ...usage, output_tokens: 400 } } }, s);
    expect(delta.events).toEqual([
      { type: 'turn_usage', byModel: { 'claude-sonnet-5': { input: 10, output: 400, cacheRead: 100, cacheWrite5m: 0, cacheWrite1h: 50 } } },
    ]);
    // a subagent call on another model is only seen as an assistant message
    const sub = translateSdkMessage({ type: 'assistant', parent_tool_use_id: 'tu', message: { id: 'm2', model: 'claude-haiku-4-5', usage: { input_tokens: 8, output_tokens: 2, cache_creation_input_tokens: 900 }, content: [] } }, s);
    expect(sub.events.at(-1)).toEqual({
      type: 'turn_usage',
      byModel: {
        'claude-sonnet-5': { input: 10, output: 400, cacheRead: 100, cacheWrite5m: 0, cacheWrite1h: 50 },
        'claude-haiku-4-5': { input: 8, output: 2, cacheRead: 0, cacheWrite5m: 900, cacheWrite1h: 0 },
      },
    });
    translateSdkMessage({ type: 'result', subtype: 'success', usage: {}, total_cost_usd: 1, duration_ms: 1, num_turns: 1 }, s);
    expect(s.usage.byModel()).toEqual({});
  });

  it('takes the cache-write split from a breakdown that arrives after the bare total', () => {
    const s = state('sonnet');
    translateSdkMessage({ type: 'assistant', message: { id: 'm1', model: 'claude-sonnet-5', usage: { input_tokens: 10, cache_creation_input_tokens: 8641 }, content: [] } }, s);
    expect(s.usage.byModel()['claude-sonnet-5']).toMatchObject({ cacheWrite5m: 8641, cacheWrite1h: 0 });
    translateSdkMessage({ type: 'stream_event', event: { type: 'message_start', message: { id: 'm1', model: 'claude-sonnet-5', usage: { input_tokens: 10, cache_creation_input_tokens: 8641, cache_creation: { ephemeral_5m_input_tokens: 0, ephemeral_1h_input_tokens: 8641 } } } } }, s);
    expect(s.usage.byModel()['claude-sonnet-5']).toMatchObject({ cacheWrite5m: 0, cacheWrite1h: 8641 });
  });

  it('translates task lifecycle messages and hides housekeeping tasks', () => {
    const s = state();
    expect(translateSdkMessage({ type: 'system', subtype: 'task_started', task_id: 't1', tool_use_id: 'tu', description: 'List files', task_type: 'local_agent', subagent_type: 'Explore', is_backgrounded: true }, s).events)
      .toEqual([{ type: 'task_started', taskId: 't1', toolUseId: 'tu', description: 'List files', kind: 'local_agent', subagentType: 'Explore', backgrounded: true }]);
    expect(translateSdkMessage({ type: 'system', subtype: 'task_started', task_id: 't2', description: 'x', skip_transcript: true }, s).events).toEqual([]);
    expect(translateSdkMessage({ type: 'system', subtype: 'task_progress', task_id: 't1', tool_use_id: 'tu', description: 'Running ls', last_tool_name: 'Bash', usage: { total_tokens: 900, tool_uses: 2, duration_ms: 1500 } }, s).events)
      .toEqual([{ type: 'task_progress', taskId: 't1', toolUseId: 'tu', description: 'Running ls', lastTool: 'Bash', toolUses: 2, tokens: 900, durationMs: 1500 }]);
    expect(translateSdkMessage({ type: 'system', subtype: 'task_notification', task_id: 't1', tool_use_id: 'tu', status: 'completed', summary: 'done', output_file: '/x' }, s).events)
      .toEqual([{ type: 'task_done', taskId: 't1', toolUseId: 'tu', status: 'completed', summary: 'done' }]);
    expect(translateSdkMessage({ type: 'system', subtype: 'background_tasks_changed', tasks: [{ task_id: 't1', task_type: 'local_bash', description: 'sleep' }] }, s).events)
      .toEqual([{ type: 'background_tasks', tasks: [{ taskId: 't1', kind: 'local_bash', description: 'sleep' }] }]);
  });

  it('treats a running total that went down as a reset', () => {
    const { events } = translateSdkMessage(
      { type: 'result', subtype: 'success', usage: {}, total_cost_usd: 0.5, duration_ms: 1, num_turns: 1 },
      state('opus', 3),
    );
    expect(events[0]).toMatchObject({ costUsd: 0.5, sessionCostUsd: 0.5 });
  });

  it('surfaces a non-nominal rate_limit_event as a non-fatal error', () => {
    const { events } = translateSdkMessage(
      { type: 'rate_limit_event', rate_limit: { status: 'rejected' } },
      state(),
    );
    expect(events).toHaveLength(1);
    expect(events[0]).toMatchObject({ type: 'error', fatal: false });
  });

  it('stays silent on a nominal rate_limit_event', () => {
    const { events } = translateSdkMessage({ type: 'rate_limit_event', rate_limit: { status: 'allowed' } }, state());
    expect(events).toEqual([]);
  });

  it('translates system/compact_boundary into context_compacted', () => {
    const { events, logs } = translateSdkMessage(
      {
        type: 'system',
        subtype: 'compact_boundary',
        compact_metadata: { trigger: 'manual', pre_tokens: 17707, post_tokens: 1973 },
      },
      state(),
    );
    expect(logs).toEqual([]);
    expect(events).toEqual([{ type: 'context_compacted', preTokens: 17707, postTokens: 1973, trigger: 'manual' }]);
  });

  it('defaults post_tokens to pre_tokens when the SDK omits it', () => {
    const { events } = translateSdkMessage(
      { type: 'system', subtype: 'compact_boundary', compact_metadata: { trigger: 'auto', pre_tokens: 500 } },
      state(),
    );
    expect(events).toEqual([{ type: 'context_compacted', preTokens: 500, postTokens: 500, trigger: 'auto' }]);
  });

  it('surfaces a failed compact as a non-fatal error', () => {
    const { events } = translateSdkMessage(
      { type: 'system', subtype: 'status', status: null, compact_result: 'failed', compact_error: 'Not enough messages to compact.' },
      state(),
    );
    expect(events).toEqual([
      { type: 'error', message: 'compact failed: Not enough messages to compact.', fatal: false },
    ]);
  });

  it('ignores an in-progress compacting status', () => {
    const { events, logs } = translateSdkMessage({ type: 'system', subtype: 'status', status: 'compacting' }, state());
    expect(events).toEqual([]);
    expect(logs).toEqual([]);
  });

  it('logs unhandled message types', () => {
    const { events, logs } = translateSdkMessage({ type: 'some_future_type' }, state());
    expect(events).toEqual([]);
    expect(logs).toEqual(['unhandled SDK message type: some_future_type']);
  });
});

describe('classifyStreamFailure', () => {
  const sdkText = new Error('Claude Code returned an error result: [ede_diagnostic] result_type=user last_content_type=n/a stop_reason=null');

  it('treats any stream error after a requested shutdown as an expected end, not a fatal error', () => {
    const outcome = classifyStreamFailure(sdkText, 'shutdown');
    expect(outcome).toMatchObject({ kind: 'expected', reason: 'shutdown' });
    // the original text is kept for the stderr log, but only its first line
    expect((outcome as { note: string }).note).toContain('returned an error result');
    expect((outcome as { note: string }).note).not.toContain('\n');
  });

  it('does the same when the backend simply closed stdin', () => {
    expect(classifyStreamFailure(new Error('boom'), 'stdin_closed')).toMatchObject({ kind: 'expected', reason: 'stdin_closed' });
  });

  it('is still fatal when nobody asked the sidecar to stop, keeping the stack', () => {
    const outcome = classifyStreamFailure(sdkText, undefined);
    expect(outcome.kind).toBe('fatal');
    expect((outcome as { message: string }).message).toContain('returned an error result');
    expect((outcome as { message: string }).message).toContain('Error:'); // the stack's first line survives
  });

  it('copes with a thrown non-Error', () => {
    expect(classifyStreamFailure('plain string', undefined)).toEqual({ kind: 'fatal', message: 'plain string' });
    expect(classifyStreamFailure({ code: 7 }, 'shutdown').kind).toBe('expected');
  });
});
