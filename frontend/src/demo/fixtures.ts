import type { DemoFixture, DemoScript } from './types';

const ISO = /^\d{4}-\d{2}-\d{2}T/;

/** Shifts every ISO timestamp in a fixture by `ms`, so the recording's "time in state"/ages read relative to the visitor's now. */
export function shiftFixture(f: DemoFixture, ms: number): DemoFixture {
  const shift = (v: string | null | undefined): string | null =>
    v && ISO.test(v) ? new Date(Date.parse(v) + ms).toISOString() : (v ?? null);
  const copy = structuredClone(f);
  copy.summary.updatedAt = shift(copy.summary.updatedAt) ?? copy.summary.updatedAt;
  copy.summary.closedAt = shift(copy.summary.closedAt);
  copy.detail.session.prCheckedAt = shift(copy.detail.session.prCheckedAt);
  for (const e of copy.events) if (e.ts) e.ts = shift(e.ts) ?? e.ts;
  // git log dates are relative ("2 weeks ago", `--date=relative`) and need no rebasing
  return copy;
}

/** Latest timestamp anywhere in the set (the anchor that gets moved to "now"). */
export function latestTimestamp(fixtures: DemoFixture[]): number {
  let max = 0;
  for (const f of fixtures) {
    for (const v of [f.summary.updatedAt, f.summary.closedAt, ...f.events.map((e) => e.ts)]) {
      const t = v ? Date.parse(v) : NaN;
      if (Number.isFinite(t) && t > max) max = t;
    }
  }
  return max;
}

/** Applies a scripts.json entry: trims/drops events, overrides the state, attaches the playback script. Pure. */
export function applyScript(f: DemoFixture, script: DemoScript | undefined): DemoFixture {
  if (!script) return f;
  const drop = new Set(script.dropSeqs ?? []);
  const events = f.events.filter((e) => !drop.has(e.seq) && (script.endAtSeq === undefined || e.seq <= script.endAtSeq));
  const last = events[events.length - 1];
  const summary = { ...f.summary, ...(script.state ? { state: script.state } : {}), ...(last ? { lastSeq: last.seq } : {}) };
  const session = script.state ? { ...f.detail.session, state: script.state } : f.detail.session;
  return { ...f, summary, detail: { ...f.detail, session, ...(last ? { lastSeq: last.seq } : {}) }, events, script };
}

/** `../../demo/fixtures/02-review.json` -> `02-review` (the scripts.json key) */
export const fixtureName = (path: string): string => path.replace(/^.*\//, '').replace(/\.json$/, '');

const modules = import.meta.glob('../../demo/fixtures/*.json', { eager: true, import: 'default' }) as Record<string, DemoFixture>;
const scriptModules = import.meta.glob('../../demo/scripts.json', { eager: true, import: 'default' }) as Record<string, Record<string, DemoScript>>;
const scripts: Record<string, DemoScript> = Object.values(scriptModules)[0] ?? {};

/** The bundled fixtures (files sorted by name, so `01-…` orders the sidebar) with their scripts applied, rebased to now. */
export function loadFixtures(now: number = Date.now()): DemoFixture[] {
  const raw = Object.keys(modules).sort().map((k) => applyScript(modules[k]!, scripts[fixtureName(k)]));
  const anchor = latestTimestamp(raw);
  const shift = anchor > 0 ? now - anchor - 5_000 : 0;
  return raw.map((f) => shiftFixture(f, shift));
}
