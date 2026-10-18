import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { transcriptFromEvents, useStore } from '../store/store';
import { SessionPlayer } from './player';
import { applyScript, fixtureName, loadFixtures, shiftFixture } from './fixtures';
import type { DemoFixture, DemoScript } from './types';

const dir = fileURLToPath(new URL('../../demo/fixtures/', import.meta.url));
const files = readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
const scripts = JSON.parse(readFileSync(fileURLToPath(new URL('../../demo/scripts.json', import.meta.url)), 'utf8')) as Record<string, DemoScript>;
const raw = files.map((f) => applyScript(JSON.parse(readFileSync(dir + f, 'utf8')) as DemoFixture, scripts[fixtureName(f)]));

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
      // a review session is a detached checkout of the reviewed branch: git reports no branch name
      expect(f.git?.status.branch, f.summary.name).toBe(f.summary.sessionType === 'review' ? '' : f.summary.branch);
      expect(f.git?.log.length, f.summary.name).toBeGreaterThan(0);
    }
  });

  it('every scripts.json key names a fixture, and every live open session has a script', () => {
    const names = files.map(fixtureName);
    for (const key of Object.keys(scripts)) expect(names, key).toContain(key);
    for (const [i, f] of raw.entries()) if (f.summary.state !== 'CLOSED') expect(scripts[fixtureName(files[i]!)], f.summary.name).toBeDefined();
  });

  it('every pause point exists live in its recording and has a deny branch', () => {
    for (const f of raw.filter((x) => x.summary.state !== 'CLOSED')) { // closed sessions are read-only: never played
      const live = f.events.filter((x) => x.type === 'permission_request' && x.seq > (f.script?.seedUntilSeq ?? 0))
        .map((x) => x.payload['requestId'] as string);
      for (const id of f.script?.pauseOn ?? live) {
        expect(live, `${f.summary.name}: ${id}`).toContain(id);
        expect(f.script?.deny?.[id], `${f.summary.name}: ${id}`).toBeDefined();
      }
    }
  });

  it('a scripted session ends in its scripted state, with no crash tail', () => {
    for (const f of raw.filter((x) => x.script?.state)) {
      expect(f.summary.state).toBe(f.script!.state);
      expect(f.events.some((e) => e.type === 'exiting' || (e.type === 'state_changed' && e.payload['state'] === 'CRASHED')), f.summary.name).toBe(false);
    }
  });

  it.each(files.map((f, i) => [f, raw[i]!] as const))('%s plays to the end when every pause point is denied', (_name, f) => {
    const timers: (() => void)[] = [];
    const player = new SessionPlayer(f, { setTimeout: (fn) => { timers.push(fn); return timers.length; }, clearTimeout: () => {}, now: () => Date.now() });
    const got: string[] = [];
    player.attach((e) => got.push(e.type));
    for (let guard = 0; guard < 10_000 && !player.finished; guard++) {
      if (player.waitingForDecision) player.respond({ requestId: player.waitingForDecision, behavior: 'deny' });
      timers.shift()?.();
    }
    expect(player.finished).toBe(true);
    if (f.script?.pauseOn?.length) expect(got.at(-1)).toBe('state_changed'); // a deny tail always closes on IDLE
  });

  it('rebases timestamps so the latest event is just before now', () => {
    const now = Date.parse('2030-01-01T00:00:00Z');
    const loaded = loadFixtures(now);
    const latest = Math.max(...loaded.flatMap((f) => f.events.map((e) => Date.parse(e.ts!))));
    expect(now - latest).toBeLessThan(10_000);
    expect(now - latest).toBeGreaterThan(0);
  });

  it('shiftFixture does not mutate its input', () => {
    const f = raw[0]!;
    const before = f.events[0]!.ts;
    shiftFixture(f, 1000);
    expect(f.events[0]!.ts).toBe(before);
  });
});
