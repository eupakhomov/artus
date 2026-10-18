import { describe, expect, it } from 'vitest';
import type { Envelope } from '../protocol';
import { SessionPlayer, type Clock } from './player';
import type { DemoFixture } from './types';

/** manual clock: timers fire only when the test advances time */
function fakeClock() {
  let now = Date.parse('2026-01-01T00:00:00Z');
  let nextId = 1;
  const timers = new Map<number, { at: number; fn: () => void }>();
  const clock: Clock = {
    setTimeout: (fn, ms) => { const id = nextId++; timers.set(id, { at: now + ms, fn }); return id; },
    clearTimeout: (h) => { timers.delete(h as number); },
    now: () => now,
  };
  const advance = (ms: number) => {
    const end = now + ms;
    for (;;) {
      const due = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      timers.delete(due[0]);
      now = due[1].at;
      due[1].fn();
    }
    now = end;
  };
  return { clock, advance, pending: () => timers.size };
}

const ev = (seq: number, type: string, payload: Record<string, unknown> = {}, ts?: string): Envelope => ({ seq, type, payload, ...(ts ? { ts } : {}) });

function fixture(events: Envelope[], script?: DemoFixture['script']): DemoFixture {
  return {
    summary: { id: 's1' } as DemoFixture['summary'],
    detail: { session: { model: 'sonnet' } } as DemoFixture['detail'],
    events, script,
  };
}

const journal = (): Envelope[] => [
  ev(1, 'state_changed', { state: 'IDLE' }, '2026-01-01T00:00:00Z'),
  ev(2, 'user_message', { text: 'go' }, '2026-01-01T00:00:01Z'),
  ev(3, 'permission_request', { requestId: 'r1', toolName: 'Bash', input: {} }, '2026-01-01T00:00:02Z'),
  ev(4, 'permission_response', { requestId: 'r1', behavior: 'allow' }, '2026-01-01T00:00:03Z'),
  ev(5, 'tool_result', { toolUseId: 't', output: 'ok' }, '2026-01-01T00:00:04Z'),
  ev(6, 'turn_complete', { costUsd: 0.1 }, '2026-01-01T00:00:05Z'),
  ev(7, 'state_changed', { state: 'IDLE' }, '2026-01-01T00:00:06Z'),
];

function run(f: DemoFixture) {
  const c = fakeClock();
  const p = new SessionPlayer(f, c.clock);
  const got: Envelope[] = [];
  p.attach((e) => got.push(e));
  return { p, got, ...c };
}

describe('SessionPlayer', () => {
  it('emits the seeded part instantly, then replay_complete, then the live part in order', () => {
    const { got, advance } = run(fixture(journal(), { seedUntilSeq: 2 }));
    expect(got.map((e) => e.type)).toEqual(['state_changed', 'user_message', 'replay_complete']);
    advance(10_000);
    expect(got.map((e) => e.seq).filter((s) => s > 0)).toEqual([1, 2, 3]); // paused at the permission request
  });

  it('pauses at a permission_request and continues the recorded tail on allow', () => {
    const { p, got, advance } = run(fixture(journal(), { seedUntilSeq: 2 }));
    advance(10_000);
    expect(p.waitingForDecision).toBe('r1');
    p.respond({ requestId: 'r1', behavior: 'allow' });
    advance(20_000);
    expect(got.filter((e) => e.seq > 0).map((e) => e.type)).toEqual([
      'state_changed', 'user_message', 'permission_request', 'permission_response', 'tool_result', 'turn_complete', 'state_changed']);
    const seqs = got.filter((e) => e.seq > 0).map((e) => e.seq);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(new Set(seqs).size).toBe(seqs.length);
    expect(p.finished).toBe(true);
  });

  it('on deny echoes the denial, skips the allowed tail and plays the recorded denied tail with increasing seqs', () => {
    const f = fixture(journal(), { seedUntilSeq: 2, deny: { r1: [
      { type: 'assistant_message', payload: { content: [] } },
      { type: 'turn_complete', payload: { costUsd: 0 } }] } });
    const { p, got, advance } = run(f);
    advance(10_000);
    p.respond({ requestId: 'r1', behavior: 'deny', message: 'no' });
    advance(20_000);
    const live = got.filter((e) => e.seq > 0);
    expect(live.map((e) => e.type)).toEqual([
      'state_changed', 'user_message', 'permission_request', 'permission_response', 'assistant_message', 'turn_complete', 'state_changed']);
    expect(live[3]!.payload).toMatchObject({ behavior: 'deny', message: 'no' });
    expect(live.some((e) => e.type === 'tool_result')).toBe(false);
    const seqs = live.map((e) => e.seq);
    expect(seqs.every((s, i) => i === 0 || s > seqs[i - 1]!)).toBe(true);
  });

  it('an event injected mid-playback never swallows the next recorded event', () => {
    const { p, got, advance } = run(fixture(journal(), { seedUntilSeq: 1 }));
    p.inject('session_renamed', { name: 'x' }); // takes seq 2, which the recorded user_message also has
    p.setPermissionMode('plan');
    advance(10_000);
    const live = got.filter((e) => e.seq > 0);
    expect(live.map((e) => e.type)).toEqual(['state_changed', 'session_renamed', 'permission_mode_changed', 'user_message', 'permission_request']);
    expect(live.every((e, i) => i === 0 || e.seq > live[i - 1]!.seq)).toBe(true);
    expect(p.waitingForDecision).toBe('r1');
  });

  it('allow without a recorded echo still keeps the tail', () => {
    const events = journal().filter((e) => e.type !== 'permission_response');
    const { p, got, advance } = run(fixture(events, { seedUntilSeq: 2 }));
    advance(10_000);
    p.respond({ requestId: 'r1', behavior: 'allow' });
    advance(20_000);
    const live = got.filter((e) => e.seq > 0);
    expect(live.map((e) => e.type)).toEqual([
      'state_changed', 'user_message', 'permission_request', 'permission_response', 'tool_result', 'turn_complete', 'state_changed']);
    expect(live.every((e, i) => i === 0 || e.seq > live[i - 1]!.seq)).toBe(true);
  });

  it('plays what was recorded during the wait, then holds right before the recorded answer', () => {
    const events = [
      ev(1, 'user_message', { text: 'go' }, '2026-01-01T00:00:00Z'),
      ev(2, 'permission_request', { requestId: 'r1' }, '2026-01-01T00:00:01Z'),
      ev(3, 'state_changed', { state: 'WAITING_INPUT' }, '2026-01-01T00:00:01Z'),
      ev(4, 'tool_started', { toolUseId: 'parallel' }, '2026-01-01T00:00:02Z'),
      ev(5, 'permission_response', { requestId: 'r1', behavior: 'allow' }, '2026-01-01T00:00:09Z'),
      ev(6, 'tool_result', { toolUseId: 't' }, '2026-01-01T00:00:10Z'),
    ];
    const { p, got, advance } = run(fixture(events, { seedUntilSeq: 1 }));
    advance(60_000);
    expect(got.filter((e) => e.seq > 0).map((e) => e.type)).toEqual(['user_message', 'permission_request', 'state_changed', 'tool_started']);
    p.respond({ requestId: 'r1', behavior: 'allow' });
    advance(10_000);
    expect(got.filter((e) => e.seq > 0).map((e) => e.type).slice(-2)).toEqual(['permission_response', 'tool_result']);
  });

  it('answering early flushes what was recorded before the answer', () => {
    const events = [
      ev(1, 'permission_request', { requestId: 'r1' }, '2026-01-01T00:00:00Z'),
      ev(2, 'state_changed', { state: 'WAITING_INPUT' }, '2026-01-01T00:00:01Z'),
      ev(3, 'permission_response', { requestId: 'r1', behavior: 'allow' }, '2026-01-01T00:00:02Z'),
    ];
    const { p, got, advance } = run(fixture(events, { seedUntilSeq: 0 }));
    advance(1_500); // the request is out (first event: the capped gap); WAITING_INPUT is still 1 s away
    expect(got.filter((e) => e.seq > 0).map((e) => e.type)).toEqual(['permission_request']);
    p.respond({ requestId: 'r1', behavior: 'deny' });
    expect(got.filter((e) => e.seq > 0).map((e) => e.type)).toEqual(
      ['permission_request', 'state_changed', 'permission_response', 'state_changed', 'stream_delta', 'assistant_message', 'turn_complete', 'state_changed']);
  });

  it('a prompt that is not a pause point is answered quickly, whatever the recorded wait', () => {
    const events = [
      ev(1, 'permission_request', { requestId: 'r1' }, '2026-01-01T00:00:00Z'),
      ev(2, 'permission_response', { requestId: 'r1', behavior: 'allow' }, '2026-01-01T00:00:30Z'),
    ];
    const { got, advance } = run(fixture(events, { seedUntilSeq: 1, pauseOn: [] }));
    advance(450);
    expect(got.filter((e) => e.seq > 0).map((e) => e.type)).toEqual(['permission_request', 'permission_response']);
  });

  it('only pauseOn prompts wait; the others play through with their recorded answer', () => {
    const events = [
      ev(1, 'permission_request', { requestId: 'r1' }, '2026-01-01T00:00:00Z'),
      ev(2, 'permission_response', { requestId: 'r1', behavior: 'allow' }, '2026-01-01T00:00:01Z'),
      ev(3, 'permission_request', { requestId: 'r2' }, '2026-01-01T00:00:02Z'),
      ev(4, 'permission_response', { requestId: 'r2', behavior: 'allow' }, '2026-01-01T00:00:03Z'),
    ];
    const { p, got, advance } = run(fixture(events, { seedUntilSeq: 0, pauseOn: ['r2'] }));
    advance(60_000);
    expect(got.filter((e) => e.seq > 0).map((e) => e.payload['requestId'])).toEqual(['r1', 'r1', 'r2']);
    expect(p.waitingForDecision).toBe('r2');
  });

  it('ignores a response for a different request id', () => {
    const { p, advance } = run(fixture(journal(), { seedUntilSeq: 2 }));
    advance(10_000);
    p.respond({ requestId: 'other', behavior: 'allow' });
    expect(p.waitingForDecision).toBe('r1');
  });

  it('pause stops the script and resume continues it; speed shortens the gaps', () => {
    const events = [ev(1, 'a', {}, '2026-01-01T00:00:00Z'), ev(2, 'b', {}, '2026-01-01T00:00:01Z'), ev(3, 'c', {}, '2026-01-01T00:00:02Z')];
    const { p, got, advance } = run(fixture(events, { seedUntilSeq: 1 }));
    p.pause();
    advance(60_000);
    expect(got.filter((e) => e.seq > 0)).toHaveLength(1);
    p.resume();
    p.setSpeed(4);
    advance(300); // 1000 ms gap / 4 = 250 ms
    expect(got.filter((e) => e.seq > 0).map((e) => e.seq)).toEqual([1, 2]);
  });

  it('caps long recorded gaps and gives stream deltas a fixed cadence', () => {
    const events = [ev(1, 'a', {}, '2026-01-01T00:00:00Z'), ev(2, 'b', {}, '2026-01-01T01:00:00Z'), ev(3, 'stream_delta', { deltaType: 'text', text: 'x' }, '2026-01-01T01:00:00Z')];
    const { got, advance } = run(fixture(events, { seedUntilSeq: 1 }));
    advance(1_510);
    expect(got.filter((e) => e.seq > 0)).toHaveLength(2);
    advance(30);
    expect(got.filter((e) => e.seq > 0)).toHaveLength(3);
  });

  it('replays history to a later attach, followed by replay_complete', () => {
    const { p, advance } = run(fixture(journal(), { seedUntilSeq: 2 }));
    advance(10_000);
    const late: Envelope[] = [];
    p.attach((e) => late.push(e));
    expect(late.map((e) => e.type).slice(-2)).toEqual(['permission_request', 'replay_complete']);
  });

  it('answers a typed prompt with the recording note only once the script is done', () => {
    const { p, got, advance } = run(fixture(journal(), { seedUntilSeq: 2 }));
    p.userMessage('hello');
    expect(got.at(-1)!.type).toBe('warning');
    advance(10_000);
    p.respond({ requestId: 'r1', behavior: 'allow' });
    advance(20_000);
    p.userMessage('hello');
    expect(got.some((e) => e.type === 'user_message' && e.payload['text'] === 'hello')).toBe(true);
    expect(got.at(-1)).toMatchObject({ type: 'state_changed', payload: { state: 'IDLE' } });
  });
});
