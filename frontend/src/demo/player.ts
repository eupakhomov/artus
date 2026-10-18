import type { Envelope } from '../protocol';
import type { DemoFixture, TailEvent } from './types';

export interface Clock {
  setTimeout(fn: () => void, ms: number): unknown;
  clearTimeout(handle: unknown): void;
  now(): number;
}

export const realClock: Clock = {
  setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
  clearTimeout: (h) => globalThis.clearTimeout(h as number),
  now: () => Date.now(),
};

/** pacing: recorded gaps are capped, stream deltas play at a fixed readable cadence */
export const MAX_GAP_MS = 1500;
export const DELTA_GAP_MS = 35;
export const MIN_GAP_MS = 60;
/** a prompt that isn't a pause point gets its recorded answer this fast, so it only flashes in "Needs you" */
export const PASS_THROUGH_ANSWER_MS = 400;

export const RECORDING_NOTE = 'This is a recording — install Artus to talk to an agent.';

const isResponseTo = (e: Envelope | undefined, requestId: string): boolean =>
  e?.type === 'permission_response' && e.payload['requestId'] === requestId;

/**
 * Replays one fixture's journal. Pure scheduling logic (clock injected) so ordering, the seeded/live split,
 * pause-on-decision and the deny branch are unit-testable. Emitted seqs are strictly monotonic: events keep
 * their recorded seq until something invented takes one, after which an offset keeps them increasing.
 *
 * A pause point (`script.pauseOn`, default every live `permission_request`) waits for the viewer: what was recorded while
 * the real prompt was pending (WAITING_INPUT, a parallel tool call) still plays, then the player holds right before the
 * recorded response. Prompts that aren't pause points play through with their recorded answer.
 */
export class SessionPlayer {
  private readonly listeners = new Set<(e: Envelope) => void>();
  /** everything emitted so far — a (re)attaching consumer gets it replayed, like the real WS's afterSeq=0 */
  private readonly log: Envelope[] = [];
  private cursor = 0;
  private seedEnd = 0;
  private offset = 0;
  private lastSeq = 0;
  private timer: unknown = null;
  private paused = false;
  private started = false;
  private waitingFor: string | null = null;
  /** reached the recorded response of the pending pause point — nothing more plays until the viewer decides */
  private held = false;
  private speed = 1;

  constructor(readonly fixture: DemoFixture, private readonly clock: Clock = realClock) {}

  get waitingForDecision(): string | null { return this.waitingFor; }
  get finished(): boolean { return this.started && this.cursor >= this.fixture.events.length && this.waitingFor === null; }
  get isPaused(): boolean { return this.paused; }

  /** Subscribes and replays history; the first attach starts the recording. Returns the unsubscribe. */
  attach(listener: (e: Envelope) => void): () => void {
    for (const e of this.log) listener(e);
    if (this.started) listener({ seq: 0, type: 'replay_complete', payload: {} });
    this.listeners.add(listener);
    if (!this.started) this.start();
    return () => { this.listeners.delete(listener); };
  }

  private start(): void {
    this.started = true;
    const events = this.fixture.events;
    const seedUntil = this.fixture.script?.seedUntilSeq ?? events[events.length - 1]?.seq ?? 0;
    while (this.cursor < events.length && events[this.cursor]!.seq <= seedUntil) {
      this.emitRecorded(events[this.cursor]!, false);
      this.cursor++;
    }
    this.seedEnd = this.cursor;
    for (const l of this.listeners) l({ seq: 0, type: 'replay_complete', payload: {} });
    this.schedule();
  }

  setSpeed(speed: number): void {
    this.speed = speed;
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; this.schedule(); }
  }
  pause(): void {
    this.paused = true;
    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
  }
  resume(): void {
    if (!this.paused) return;
    this.paused = false;
    this.schedule();
  }
  private gapBefore(e: Envelope): number {
    if (e.type === 'stream_delta') return DELTA_GAP_MS;
    if (e.type === 'permission_response') return PASS_THROUGH_ANSWER_MS;
    const prev = this.fixture.events[this.cursor - 1];
    const recorded = prev?.ts && e.ts ? Date.parse(e.ts) - Date.parse(prev.ts) : MAX_GAP_MS;
    return Math.max(MIN_GAP_MS, Math.min(Number.isFinite(recorded) ? recorded : MAX_GAP_MS, MAX_GAP_MS));
  }

  private schedule(): void {
    if (this.paused || this.held || this.timer !== null) return;
    const e = this.fixture.events[this.cursor];
    if (!e) return;
    if (this.waitingFor !== null && isResponseTo(e, this.waitingFor)) {
      this.held = true;
      return;
    }
    this.timer = this.clock.setTimeout(() => {
      this.timer = null;
      this.step();
    }, this.gapBefore(e) / this.speed);
  }

  private step(): void {
    const e = this.fixture.events[this.cursor];
    if (!e) return;
    this.cursor++;
    this.emitRecorded(e, true);
    const requestId = e.payload['requestId'] as string;
    if (e.type === 'permission_request' && this.isPausePoint(requestId)) {
      this.waitingFor = requestId; // the viewer's decision (respond()) releases it
      // no recorded answer to play up to: hold right here
      if (!this.fixture.events.some((x, i) => i >= this.cursor && isResponseTo(x, requestId))) this.held = true;
    }
    this.schedule();
  }

  private isPausePoint(requestId: string): boolean {
    const pauseOn = this.fixture.script?.pauseOn;
    return !pauseOn || pauseOn.includes(requestId);
  }

  private emitRecorded(e: Envelope, live: boolean): void {
    let seq = e.seq + this.offset;
    // an invented event (deny tail, viewer echo, rename/mark, warning) may have taken this seq: shift the rest of the recording
    // past it, or the WS consumer's `seq > lastSeq` dedupe would silently drop the recorded event
    if (seq <= this.lastSeq) {
      this.offset = this.lastSeq + 1 - e.seq;
      seq = this.lastSeq + 1;
    }
    this.push({ ...e, seq, ts: live ? new Date(this.clock.now()).toISOString() : e.ts });
  }

  private emitNew(type: string, payload: Record<string, unknown>): void {
    this.push({ seq: this.lastSeq + 1, type, payload, ts: new Date(this.clock.now()).toISOString() });
  }

  private push(e: Envelope): void {
    this.lastSeq = e.seq;
    this.log.push(e);
    for (const l of [...this.listeners]) l(e);
  }

  /**
   * The viewer's `permission_response`. Allow plays the rest of the recording; deny ends it (the timeline diverged)
   * with the fixture's denied tail, closed by an IDLE state.
   */
  respond(cmd: Record<string, unknown>): void {
    const requestId = cmd['requestId'] as string;
    if (this.waitingFor === null || requestId !== this.waitingFor) return;
    const behavior = cmd['behavior'] === 'deny' ? 'deny' : 'allow';
    const echo: Record<string, unknown> = { requestId, behavior };
    if (cmd['updatedInput']) echo['updatedInput'] = cmd['updatedInput'];
    if (cmd['message']) echo['message'] = cmd['message'];

    if (this.timer !== null) { this.clock.clearTimeout(this.timer); this.timer = null; }
    const events = this.fixture.events;
    // answered before the player got there: what was recorded during the wait still happened first
    const recorded = events.findIndex((x, i) => i >= this.cursor && isResponseTo(x, requestId));
    if (recorded !== -1) {
      while (this.cursor < recorded) this.emitRecorded(events[this.cursor++]!, true);
      this.cursor++; // the recorded answer is replaced by the viewer's
    }
    this.waitingFor = null;
    this.held = false;
    this.emitNew('permission_response', echo);

    if (behavior === 'deny') {
      this.cursor = events.length;
      const tail = this.fixture.script?.deny?.[requestId] ?? DEFAULT_DENY_TAIL;
      for (const t of tail) this.emitNew(t.type, t.payload);
      const last = tail[tail.length - 1];
      if (!(last?.type === 'state_changed' && last.payload['state'] === 'IDLE')) this.emitNew('state_changed', { state: 'IDLE' });
    }
    this.schedule();
  }

  /** A typed prompt: answered with the fixed recording note once the script is done, refused while it still plays. */
  userMessage(text: string): void {
    if (!this.finished) {
      this.emitNew('warning', { message: 'The demo is still replaying a recording — message not delivered.' });
      return;
    }
    this.emitNew('user_message', { text });
    this.emitNew('state_changed', { state: 'RUNNING' });
    this.emitNew('stream_delta', { deltaType: 'text', text: RECORDING_NOTE });
    this.emitNew('assistant_message', { content: [{ type: 'text', text: RECORDING_NOTE }] });
    this.emitNew('turn_complete', { stopReason: 'end_turn', costUsd: 0, durationMs: 0, model: this.fixture.detail.session.model });
    this.emitNew('state_changed', { state: 'IDLE' });
  }

  /** an out-of-band journal event (a rename, a mark, a budget change) so the open widget reflects a demo mutation */
  inject(type: string, payload: Record<string, unknown>): void { this.emitNew(type, payload); }

  setPermissionMode(mode: string): void { this.emitNew('permission_mode_changed', { mode }); }
  setModel(model: string): void { this.emitNew('model_changed', { model }); }
  unsupported(what: string): void { this.emitNew('warning', { message: `${what} isn't available in the demo recording.` }); }

  /** how many recorded events remain (tests/DemoBar progress) */
  get remaining(): number { return Math.max(0, this.fixture.events.length - this.cursor); }
  get seeded(): number { return this.seedEnd; }
}

const DEFAULT_DENY_TAIL: TailEvent[] = [
  { type: 'state_changed', payload: { state: 'RUNNING' } },
  { type: 'stream_delta', payload: { deltaType: 'text', text: 'Understood — I will not run that. Let me know how you would like to proceed.' } },
  { type: 'assistant_message', payload: { content: [{ type: 'text', text: 'Understood — I will not run that. Let me know how you would like to proceed.' }] } },
  { type: 'turn_complete', payload: { stopReason: 'end_turn', costUsd: 0.004, durationMs: 1800, model: null } },
];
