// SPDX-License-Identifier: Apache-2.0
//
// Test double of the browser WebSocket for WsManager tests (docs/15 §15): records the frames the
// client sends and lets the test play the server (open, frames, close codes). Used with fake
// timers; never shipped.
import type { SocketFactory, SocketLike, VisibilitySource } from '../src/realtime/WsManager';

export class FakeSocket implements SocketLike {
  readyState = 0;
  onopen: ((event: unknown) => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  readonly sent: string[] = [];
  closedBy: { code: number | undefined; reason: string | undefined } | null = null;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
  }

  send(data: string): void {
    if (this.readyState !== 1) {
      throw new Error('send on a socket that is not open');
    }
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    this.closedBy = { code, reason };
    this.readyState = 3;
  }

  /** Client frames as objects. */
  frames(): Record<string, unknown>[] {
    return this.sent.map((text) => JSON.parse(text) as Record<string, unknown>);
  }

  /** Client frames of one op. */
  framesOf(op: string): Record<string, unknown>[] {
    return this.frames().filter((frame) => frame.op === op);
  }

  // Server side -------------------------------------------------------------------------------

  serverOpen(): void {
    this.readyState = 1;
    this.onopen?.({});
  }

  serverSend(frame: unknown): void {
    this.onmessage?.({ data: typeof frame === 'string' ? frame : JSON.stringify(frame) });
  }

  serverClose(code: number): void {
    this.readyState = 3;
    this.onclose?.({ code });
  }

  /** Acks every subscribe frame sent so far that was not acked yet. */
  ackAll(since = '2026-11-14T00:00:00.000Z'): void {
    for (const frame of this.framesOf('subscribe')) {
      this.serverSend({ op: 'ack', id: frame.id, topic: frame.topic, since });
    }
  }
}

export interface FakeSockets {
  readonly factory: SocketFactory;
  readonly sockets: FakeSocket[];
  /** The newest socket (throws if none). */
  last(): FakeSocket;
}

export function fakeSockets(): FakeSockets {
  const sockets: FakeSocket[] = [];
  return {
    sockets,
    factory: (url) => {
      const socket = new FakeSocket(url);
      sockets.push(socket);
      return socket;
    },
    last: () => {
      const socket = sockets.at(-1);
      if (socket === undefined) {
        throw new Error('no socket was created');
      }
      return socket;
    },
  };
}

/** A controllable document.visibilityState. */
export class FakeVisibility implements VisibilitySource {
  visibilityState = 'visible';
  private readonly listeners = new Set<() => void>();

  addEventListener(_type: 'visibilitychange', listener: () => void): void {
    this.listeners.add(listener);
  }

  removeEventListener(_type: 'visibilitychange', listener: () => void): void {
    this.listeners.delete(listener);
  }

  set(state: 'visible' | 'hidden'): void {
    this.visibilityState = state;
    for (const listener of this.listeners) {
      listener();
    }
  }
}
