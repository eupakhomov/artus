import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { transcriptFromEvents, useStore } from '../store/store';
import { SessionPlayer } from './player';
import { loadFixtures, shiftFixture } from './fixtures';
import type { DemoFixture } from './types';

const dir = fileURLToPath(new URL('../../demo/fixtures/', import.meta.url));
const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
const raw = files.map((f) => JSON.parse(readFileSync(dir + f, 'utf8')) as DemoFixture);

describe('bundled demo fixtures', () => {
  it('has fixtures: live sessions plus closed history', () => {
    expect(raw.length).toBeGreaterThanOrEqual(6);
    expect(raw.some((f) => f.summary.state === 'CLOSED')).toBe(true);
    expect(raw.some((f) => f.summary.sessionType === 'review')).toBe(true);
    expect(raw.some((f) => f.summary.provider === 'codex')).toBe(true);
    expect(raw.some((f) => f.detail.session.parentSessionId)).toBe(true);
  });

  it.each(files.map((f, i) => [f, raw[i]!] as const))('%s folds through the real store reducer', (_name, f) => {
    const seqs = f.events.map((e) => e.seq);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(new Set(seqs).size).toBe(seqs.length);
    expect(transcriptFromEvents(f.events).length).toBeGreaterThan(0);
    // full playback through a SessionPlayer into the live store, answering any prompt
    const timers: (() => void)[] = [];
    const player = new SessionPlayer(f, { setTimeout: (fn) => { timers.push(fn); return timers.length; }, clearTimeout: () => {}, now: () => Date.now() });
    player.attach((e) => useStore.getState().apply(f.summary.id, e));
    for (let guard = 0; guard < 10_000 && !player.finished; guard++) {
      if (player.waitingForDecision) player.respond({ requestId: player.waitingForDecision, behavior: 'allow' });
      timers.shift()?.();
    }
    expect(player.finished).toBe(true);
    expect(useStore.getState().views[f.summary.id]!.pendingPermission).toBeNull();
  });

  it('every open session records its Git panel reads', () => {
    for (const f of raw.filter((x) => x.summary.state !== 'CLOSED')) {
      expect(f.git?.status.branch, f.summary.name).toBe(f.summary.branch);
      expect(f.git?.log.length, f.summary.name).toBeGreaterThan(0);
    }
  });

  it('every pause point has a deny branch', () => {
    for (const f of raw) {
      for (const e of f.events.filter((x) => x.type === 'permission_request' && x.seq > (f.script?.seedUntilSeq ?? 0))) {
        expect(f.script?.deny?.[e.payload['requestId'] as string], `${f.summary.name}`).toBeDefined();
      }
    }
  });

  it('rebases timestamps so the latest event is just before now', () => {
    const now = Date.parse('2030-01-01T00:00:00Z');
    const loaded = loadFixtures(now);
    const latest = Math.max(...loaded.flatMap((f) => f.events.map((e) => Date.parse(e.ts!))));
    expect(now - latest).toBeLessThan(10_000);
    expect(now - latest).toBeGreaterThan(0);
    const commits = loaded.flatMap((f) => f.git?.log ?? []).map((c) => Date.parse(c.date));
    expect(Math.max(...commits)).toBeLessThan(now);
    expect(now - Math.max(...commits)).toBeLessThan(24 * 3_600_000);
  });

  it('shiftFixture does not mutate its input', () => {
    const f = raw[0]!;
    const before = f.events[0]!.ts;
    shiftFixture(f, 1000);
    expect(f.events[0]!.ts).toBe(before);
  });
});
