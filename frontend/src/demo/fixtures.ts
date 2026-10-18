import type { DemoFixture } from './types';

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
  for (const c of copy.git?.log ?? []) c.date = shift(c.date) ?? c.date;
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

const modules = import.meta.glob('../../demo/fixtures/*.json', { eager: true, import: 'default' }) as Record<string, DemoFixture>;

/** The bundled fixtures (files sorted by name, so `01-…` orders the sidebar), rebased to now. */
export function loadFixtures(now: number = Date.now()): DemoFixture[] {
  const raw = Object.keys(modules).sort().map((k) => modules[k]!);
  const anchor = latestTimestamp(raw);
  const shift = anchor > 0 ? now - anchor - 5_000 : 0;
  return raw.map((f) => shiftFixture(f, shift));
}
