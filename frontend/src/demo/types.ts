import type { ChangeList, ChangedFileView, DiagramView, Envelope, MemoryProposal, SessionDetail, SessionState, SessionSummary } from '../protocol';

/** The Git panel's reads (`GET /api/sessions/{id}/git/status|log|diff`), recorded as the backend returned them. */
export interface DemoGit {
  status: { branch: string; dirty: string[]; upstream: string | null; ahead: number; behind: number; aheadOfBase: number };
  log: { hash: string; subject: string; author: string; date: string }[];
  diff?: string;
}

/**
 * Everything hand-made about a recording lives in frontend/demo/scripts.json, so a re-capture (which rewrites the fixture
 * files) is one command. Applied by fixtures.ts `applyScript`.
 */
export interface DemoScript {
  /** events up to this seq are seeded instantly; the rest play live */
  seedUntilSeq?: number;
  /** permission requestIds the viewer decides; any other prompt plays through with its recorded answer. Absent = all */
  pauseOn?: string[];
  /** per pause point: what plays when the viewer denies — the rest of the recording is dropped */
  deny?: Record<string, TailEvent[]>;
  /** drop everything after this seq (e.g. the "backend restarted" tail of a session captured after a restart) */
  endAtSeq?: number;
  /** individual events to drop (environment-only notices) */
  dropSeqs?: number[];
  /** override the captured session state (a session captured CRASHED that should show as IDLE) */
  state?: SessionState;
}

/** An event the player invents (deny tail): everything but the seq/ts it assigns. */
export type TailEvent = { type: string; payload: Record<string, unknown> };

/**
 * One staged session, in the format `scripts/capture-demo.mjs` writes (a scrubbed `GET /api/sessions/{id}`,
 * `/events`, `/changes`) plus the hand-added `script` block. See docs/plan/phase-24-demo-mode.md.
 */
export interface DemoFixture {
  summary: SessionSummary;
  detail: SessionDetail;
  /** the full journal; events with seq <= script.seedUntilSeq are seeded instantly, the rest play live */
  events: Envelope[];
  /** the hand-made playback script, merged in at load from frontend/demo/scripts.json (keyed by fixture file name) */
  script?: DemoScript;
  changes?: ChangeList;
  git?: DemoGit;
  files?: Record<string, ChangedFileView>;
  diagram?: DiagramView;
  /** the diagram's current fingerprint (differs from diagram.fingerprint = outdated chip) */
  currentFingerprint?: string;
}

/** frontend/demo/memory.json: the hand-made memory vault (ages are relative to "now"; links are `[[slug]]` in the content). */
export interface DemoMemoryFile {
  docs: {
    id: string; scope: 'ecosystem' | 'service'; servicePath: string | null; name: string; description: string; tags: string[];
    status: 'ACTIVE' | 'ARCHIVED'; ageDays: number; content: string;
  }[];
  proposals: ({ ageMinutes: number } & Omit<MemoryProposal, 'createdAt' | 'decidedAt'>)[];
  episodes: { id: string; sessionId: string; sessionName: string; servicePath: string; ageDays: number; summary: string }[];
}

/** frontend/demo/templates.json: hand-made session templates; `assetIds` are library.json asset ids (live references, as in V8). */
export interface DemoTemplatesFile {
  templates: { id: string; name: string; description: string; config: Record<string, unknown>; assetIds: string[] }[];
}

/** frontend/demo/library.json: the hand-made skill & agent library. */
export interface DemoLibraryFile {
  sources: {
    id: string; type: 'dir' | 'repo'; ref: string; syncEnabled: boolean; syncedMinutesAgo: number; lastSyncStatus: string | null;
    discoveries: { path: string; kind: 'skill' | 'agent'; firstSeenMinutesAgo: number }[];
  }[];
  assets: {
    id: string; sourceId: string | null; kind: 'skill' | 'agent'; name: string; description: string; sourcePath: string | null;
    tags: string[]; status: 'ACTIVE' | 'ARCHIVED'; ageDays: number; content: string;
  }[];
}
