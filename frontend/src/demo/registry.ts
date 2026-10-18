import { useSyncExternalStore } from 'react';
import { loadFixtures } from './fixtures';
import { SessionPlayer } from './player';
import { parseDemoParams } from './params';
import type { DemoFixture } from './types';

const params = parseDemoParams(typeof location === 'undefined' ? '' : location.search);

let fixtures: DemoFixture[] | null = null;
const players = new Map<string, SessionPlayer>();
const subscribers = new Set<() => void>();
let snapshot = { speed: params.speed as number, paused: !params.autoplay };

/** replaces (never mutates) the snapshot handed to useSyncExternalStore */
function update(next: Partial<typeof snapshot>): void {
  snapshot = { ...snapshot, ...next };
  for (const s of subscribers) s();
}

export function demoFixtures(): DemoFixture[] {
  fixtures ??= loadFixtures();
  return fixtures;
}

/** One player per open fixture, created lazily and shared by every WsSession for that session. */
export function playerFor(sessionId: string): SessionPlayer | null {
  let p = players.get(sessionId);
  if (!p) {
    const f = demoFixtures().find((x) => x.summary.id === sessionId);
    if (!f) return null;
    p = new SessionPlayer(f);
    p.setSpeed(snapshot.speed);
    if (snapshot.paused) p.pause();
    players.set(sessionId, p);
  }
  return p;
}

export function setSpeed(speed: number): void {
  for (const p of players.values()) p.setSpeed(speed);
  update({ speed });
}

export function setPaused(paused: boolean): void {
  for (const p of players.values()) (paused ? p.pause() : p.resume());
  update({ paused });
}

/** Restart = reload: the store, the in-memory REST state and every player start from scratch together. */
export function restart(): void {
  location.reload();
}

export function useDemoControls() {
  return useSyncExternalStore(
    (cb) => { subscribers.add(cb); return () => subscribers.delete(cb); },
    () => snapshot,
  );
}
