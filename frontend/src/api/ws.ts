import type { Envelope } from '../protocol';
import { token } from './rest';

export type WsStatus = 'connecting' | 'open' | 'closed';

/**
 * Reconnecting WebSocket for one session. Always reconnects with the highest seq
 * seen so far, so the store receives a gapless, duplicate-free stream.
 */
export class WsSession {
  private ws: WebSocket | null = null;
  private lastSeq = 0;
  private backoffMs = 1000;
  private stopped = false;
  private reconnectTimer: number | null = null;

  constructor(
    private readonly sessionId: string,
    private readonly onEvent: (e: Envelope) => void,
    private readonly onStatus: (s: WsStatus) => void,
  ) {
    this.connect();
  }

  private connect(): void {
    if (this.stopped) return;
    this.onStatus('connecting');
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const url = `${scheme}://${location.host}/ws/sessions/${this.sessionId}?afterSeq=${this.lastSeq}`;
    const protocols = ['artus.v1'];
    const t = token();
    if (t) protocols.push(`bearer.${t}`);
    const ws = new WebSocket(url, protocols);
    this.ws = ws;

    ws.onopen = () => {
      this.backoffMs = 1000;
      this.onStatus('open');
    };
    ws.onmessage = (msg) => {
      const envelope = JSON.parse(msg.data as string) as Envelope;
      if (envelope.type === 'replay_complete') {
        this.onEvent(envelope); // marker for consumers (e.g. notification gating); not seq-tracked
        return;
      }
      if (envelope.seq > this.lastSeq) {
        this.lastSeq = envelope.seq;
        this.onEvent(envelope);
      }
    };
    ws.onclose = () => {
      this.onStatus('closed');
      if (!this.stopped) {
        this.reconnectTimer = window.setTimeout(() => this.connect(), this.backoffMs);
        this.backoffMs = Math.min(this.backoffMs * 2, 30_000);
      }
    };
    ws.onerror = () => ws.close();
  }

  /** false when the socket isn't open, so callers can keep what they tried to send */
  send(command: Record<string, unknown>): boolean {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(command));
      return true;
    }
    return false;
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.ws?.close();
  }
}
