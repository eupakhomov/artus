import type { ChangeList, ChangedFileView, DiagramView, Envelope, SessionDetail, SessionSummary } from '../protocol';

/** The Git panel's reads (`GET /api/sessions/{id}/git/status|log|diff`), recorded as the backend returned them. */
export interface DemoGit {
  status: { branch: string; dirty: string[]; upstream: string | null; ahead: number; behind: number; aheadOfBase: number };
  log: { hash: string; subject: string; author: string; date: string }[];
  diff?: string;
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
  script?: {
    seedUntilSeq: number;
    /** per permission requestId: the events that replace the recorded tail up to and including its turn_complete when the viewer denies */
    deny?: Record<string, TailEvent[]>;
  };
  changes?: ChangeList;
  git?: DemoGit;
  files?: Record<string, ChangedFileView>;
  diagram?: DiagramView;
  /** the diagram's current fingerprint (differs from diagram.fingerprint = outdated chip) */
  currentFingerprint?: string;
}
