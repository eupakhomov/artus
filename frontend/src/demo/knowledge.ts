import type {
  LibraryAsset, LibraryAssetContent, LibrarySearchHit, LibrarySource, MemoryDoc, MemoryDocDetail, MemoryEpisode, MemoryLinkRef,
  MemoryProposal, MemorySearchHit,
} from '../protocol';
import type { DemoLibraryFile, DemoMemoryFile } from './types';

// phase 24 / website W0.2: the memory vault and skill library the demo's dialogs browse. Hand-made (frontend/demo/memory.json,
// library.json), not recorded; ages are relative so the data always reads "recent". Read-only: every write stays a 501.

const DAY = 86_400_000;
const MIN = 60_000;
const iso = (now: number, ageMs: number) => new Date(now - ageMs).toISOString();
const LINK = /\[\[([a-z0-9][a-z0-9-]*)\]\]/g;

export interface DemoKnowledge {
  memoryDocs: (filters?: { scope?: string; servicePath?: string; status?: string; q?: string }) => MemoryDoc[];
  memoryDoc: (id: string) => MemoryDocDetail | null;
  memoryProposals: () => MemoryProposal[];
  memoryEpisodes: (servicePath?: string) => MemoryEpisode[];
  memorySearch: (q: string, opts?: { kind?: 'semantic' | 'episodic' | 'all'; servicePath?: string }) => MemorySearchHit[];
  libraryAssets: (filters?: { kind?: string; status?: string; q?: string }) => LibraryAsset[];
  libraryAssetContent: (id: string) => LibraryAssetContent | null;
  librarySources: () => LibrarySource[];
  librarySearch: (q: string, k?: number) => LibrarySearchHit[];
}

/** Whitespace-separated terms, all of which must occur (case-insensitive) in the haystack. */
function matches(q: string | undefined, haystack: string): boolean {
  const terms = (q ?? '').toLowerCase().split(/\s+/).filter(Boolean);
  const h = haystack.toLowerCase();
  return terms.every((t) => h.includes(t));
}

/** Fraction of query terms found: a stand-in for the real hybrid score, good enough to order a few dozen items. */
function score(q: string, haystack: string): number {
  const terms = q.toLowerCase().split(/\s+/).filter(Boolean);
  const h = haystack.toLowerCase();
  return terms.length ? terms.filter((t) => h.includes(t)).length / terms.length : 0;
}

export function buildKnowledge(memory: DemoMemoryFile, library: DemoLibraryFile, now: number = Date.now()): DemoKnowledge {
  const docs: (MemoryDoc & { content: string })[] = memory.docs.map(({ ageDays, ...d }) => ({
    ...d,
    createdAt: iso(now, ageDays * DAY),
    updatedAt: iso(now, Math.max(0, ageDays - 1) * DAY),
  }));
  const bySlug = new Map(docs.map((d) => [d.name, d]));
  const strip = ({ content: _content, ...doc }: MemoryDoc & { content: string }): MemoryDoc => doc;
  const ref = (slug: string): MemoryLinkRef => {
    const target = bySlug.get(slug);
    return { slug, docId: target?.id ?? null, name: target?.name ?? null, description: target?.description ?? null, dangling: !target };
  };
  const slugsIn = (content: string) => [...new Set([...content.matchAll(LINK)].map((m) => m[1]!))];

  const proposals: MemoryProposal[] = memory.proposals.map(({ ageMinutes, ...p }) => ({
    ...p, createdAt: iso(now, ageMinutes * MIN), decidedAt: null,
  }) as MemoryProposal);
  const episodes: MemoryEpisode[] = memory.episodes.map(({ ageDays, ...e }) => ({ ...e, ts: iso(now, ageDays * DAY) }));

  const assets: (LibraryAsset & { content: string })[] = library.assets.map(({ ageDays, content, ...a }) => ({
    ...a,
    location: `${library.sources.find((s) => s.id === a.sourceId)?.ref ?? ''}/${a.sourcePath ?? ''}`,
    contentHash: `demo${a.id.slice(-4)}`,
    createdAt: iso(now, ageDays * DAY),
    updatedAt: iso(now, ageDays * DAY),
    content,
  }));
  const plainAsset = ({ content: _c, ...a }: LibraryAsset & { content: string }): LibraryAsset => a;
  const sources: LibrarySource[] = library.sources.map(({ syncedMinutesAgo, discoveries, ...s }) => ({
    ...s,
    lastSyncedAt: iso(now, syncedMinutesAgo * MIN),
    lastSyncError: null,
    assetCount: assets.filter((a) => a.sourceId === s.id && a.status === 'ACTIVE').length,
    discoveries: discoveries.map(({ firstSeenMinutesAgo, ...d }) => ({ ...d, firstSeenAt: iso(now, firstSeenMinutesAgo * MIN) })),
  }));

  const docText = (d: MemoryDoc & { content: string }) => `${d.name} ${d.description} ${d.tags.join(' ')} ${d.content}`;
  const assetText = (a: LibraryAsset & { content: string }) => `${a.name} ${a.description} ${a.tags.join(' ')}`;

  return {
    memoryDocs: (f = {}) => docs
      .filter((d) => (!f.status || d.status === f.status) && (!f.scope || d.scope === f.scope)
        && (!f.servicePath || (d.servicePath ?? '').includes(f.servicePath)) && matches(f.q, docText(d)))
      .sort((a, b) => a.name.localeCompare(b.name)).map(strip),
    memoryDoc: (id) => {
      const d = docs.find((x) => x.id === id);
      if (!d) return null;
      return {
        doc: strip(d), content: d.content, page: 1, totalPages: 1,
        outgoing: slugsIn(d.content).map(ref),
        backlinks: docs.filter((o) => o.id !== d.id && slugsIn(o.content).includes(d.name)).map((o) => ref(o.name)),
      };
    },
    memoryProposals: () => proposals,
    memoryEpisodes: (servicePath) => episodes.filter((e) => !servicePath || e.servicePath.includes(servicePath)),
    memorySearch: (q, opts = {}) => {
      const kind = opts.kind ?? 'all';
      const hits: MemorySearchHit[] = [];
      if (kind !== 'episodic') {
        for (const d of docs) {
          if (d.status !== 'ACTIVE' || (opts.servicePath && !(d.servicePath ?? '').includes(opts.servicePath))) continue;
          const s = score(q, docText(d));
          if (s > 0) hits.push({ kind: 'semantic', id: d.id, name: d.name, scope: d.scope, servicePath: d.servicePath,
            description: d.description, tags: d.tags, sessionName: null, ts: null, score: s });
        }
      }
      if (kind !== 'semantic') {
        for (const e of episodes) {
          if (opts.servicePath && !e.servicePath.includes(opts.servicePath)) continue;
          const s = score(q, `${e.sessionName} ${e.summary}`);
          if (s > 0) hits.push({ kind: 'episodic', id: e.id, name: null, scope: null, servicePath: e.servicePath,
            description: e.summary, tags: [], sessionName: e.sessionName, ts: e.ts, score: s });
        }
      }
      return hits.sort((a, b) => b.score - a.score);
    },
    libraryAssets: (f = {}) => assets
      .filter((a) => (!f.kind || a.kind === f.kind) && (!f.status || a.status === f.status) && matches(f.q, assetText(a)))
      .map(plainAsset),
    libraryAssetContent: (id) => {
      const a = assets.find((x) => x.id === id);
      return a ? { content: a.content, truncated: false, sourceFile: a.sourcePath ?? a.name } : null;
    },
    librarySources: () => sources,
    librarySearch: (q, k = 10) => assets
      .filter((a) => a.status === 'ACTIVE')
      .map((a): LibrarySearchHit => ({ asset: plainAsset(a), score: score(q, assetText(a)) }))
      .filter((h) => h.score > 0).sort((a, b) => b.score - a.score).slice(0, k),
  };
}

const memoryModules = import.meta.glob('../../demo/memory.json', { eager: true, import: 'default' }) as Record<string, DemoMemoryFile>;
const libraryModules = import.meta.glob('../../demo/library.json', { eager: true, import: 'default' }) as Record<string, DemoLibraryFile>;

let cached: DemoKnowledge | null = null;
/** The bundled memory vault and library (built once; ages relative to the first call). */
export function demoKnowledge(): DemoKnowledge {
  cached ??= buildKnowledge(Object.values(memoryModules)[0]!, Object.values(libraryModules)[0]!);
  return cached;
}
