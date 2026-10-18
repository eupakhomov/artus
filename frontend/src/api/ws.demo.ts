import type { Envelope } from '../protocol';
import { playerFor } from '../demo/registry';

export type WsStatus = 'connecting' | 'open' | 'closed';

/**
 * Demo stand-in for the reconnecting WebSocket (phase 24): same constructor/`send`/`stop`, but it attaches to the
 * session's shared `SessionPlayer` instead of a socket. No network, ever.
 */
export class WsSession {
  private detach: (() => void) | null = null;
  private lastSeq = 0;
  private stopped = false;

  constructor(
    private readonly sessionId: string,
    private readonly onEvent: (e: Envelope) => void,
    private readonly onStatus: (s: WsStatus) => void,
  ) {
    this.onStatus('connecting');
    queueMicrotask(() => this.connect());
  }

  private connect(): void {
    const player = playerFor(this.sessionId);
    if (this.stopped || !player) {
      this.onStatus('closed');
      return;
    }
    this.onStatus('open');
    this.detach = player.attach((envelope) => {
      if (envelope.type === 'replay_complete') { this.onEvent(envelope); return; }
      if (envelope.seq > this.lastSeq) {
        this.lastSeq = envelope.seq;
        this.onEvent(envelope);
      }
    });
  }

  send(command: Record<string, unknown>): void {
    const player = playerFor(this.sessionId);
    if (!player) return;
    switch (command['type']) {
      case 'permission_response': player.respond(command); break;
      case 'user_message': player.userMessage(String(command['text'] ?? '')); break;
      case 'set_permission_mode': player.setPermissionMode(String(command['mode'])); break;
      case 'set_model': player.setModel(String(command['model'])); break;
      case 'interrupt': player.unsupported('Interrupt'); break;
      default: break;
    }
  }

  stop(): void {
    this.stopped = true;
    this.detach?.();
    this.detach = null;
  }
}
