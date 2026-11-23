// SPDX-License-Identifier: Apache-2.0
//
// WebSocket fixture of the E2E stack (docs/18 §13 "ws", ADR-0010 C-167 (3)). page.routeWebSocket on
// the /ws URL sees every socket of the page in one of two modes:
//
// mode 'stub' (default): the fixture implements the server side of docs/14 §5
// (e2e/fixtures/wsProtocol.ts); the REST API and the databases stay real. Controls:
//   pushXlog(points)   push frames to every xlog subscription of the open connection
//   push(topic, data)  push frames to every subscription of `topic`
//   pause(ms)          answer nothing (no ack, pong or push) for `ms` (then resumes)
//   drop()             end the connection as a server restart would (close 1012): the client
//                      must reconnect with back-off
//   close(code)        close with a docs/14 §5.3 code (4401: the client must not reconnect)
//   reauth()           the reauth frame of C-167 (2), then close 4401
//
// mode 'server' (WO-22, test.use({ wsMode: 'server' }) of e2e/fixtures/stack.ts): every socket is
// forwarded to the real /ws of the stack (route.connectToServer: the browser opens the server
// socket, with the page's cookies and Origin). Both directions are forwarded frame by frame and
// recorded with their arrival time: serverFrames() lists what the server sent (each parsed with the
// UI's own parser, src/realtime/frames.ts), connections() says who closed with which code. A close
// of one side is forwarded to the other with its code. The stub controls throw in this mode.
//
// In both modes every client frame is validated against docs/14 §5.2; in server mode a server frame
// that the UI parser rejects is a violation too. A violation fails the test at teardown.
import type { Page, WebSocketRoute } from '@playwright/test';

import { parseServerFrame, type ServerFrame as ParsedServerFrame } from '../../src/realtime/frames';

import {
  CLOSE,
  push,
  reauthFrame,
  WsConnectionState,
  xlogPush,
  type Subscription,
  type Topic,
  type XlogPoint,
} from './wsProtocol';

export type WsMode = 'stub' | 'server';

/** A close of a connection: its code (undefined when none was given) and the side that closed. */
export interface WsClose {
  readonly code: number | undefined;
  readonly reason: string;
  readonly by: 'client' | 'server';
  /** Epoch ms when the fixture saw the close. */
  readonly at: number;
}

/** A frame the real server sent (server mode). */
export interface RecordedServerFrame {
  /** Index of the connection in connections(). */
  readonly connection: number;
  /** Epoch ms when the fixture received it. */
  readonly at: number;
  readonly text: string;
  /** The frame as the UI parses it (src/realtime/frames.ts), null when the UI rejects it. */
  readonly frame: ParsedServerFrame | null;
}

interface Connection {
  readonly index: number;
  readonly route: WebSocketRoute;
  readonly state: WsConnectionState;
  readonly openedAt: number;
  /** Subscription ids the real server acknowledged (server mode). */
  readonly acked: Set<string>;
  closed: WsClose | null;
}

export interface WsStub {
  readonly mode: WsMode;
  /** Connections opened by the page so far (including closed ones), oldest first. */
  connections(): readonly { openedAt: number; closed: WsClose | null; url: string }[];
  /** Number of connections opened so far. */
  connectionCount(): number;
  /** The subscriptions of the newest open connection (server mode: those the server acked). */
  subscriptions(topic?: Topic): Subscription[];
  /** Resolves when the newest open connection has a subscription of `topic` (any topic if undefined). */
  waitForSubscription(topic?: Topic, timeoutMs?: number): Promise<Subscription>;
  /** Resolves when `count` connections have been opened. */
  waitForConnections(count: number, timeoutMs?: number): Promise<void>;
  /** Server mode: every frame the real server sent, in arrival order. */
  serverFrames(): readonly RecordedServerFrame[];
  /** Server mode: resolves with the first recorded server frame that matches. */
  waitForServerFrame(
    match: (frame: RecordedServerFrame) => boolean,
    timeoutMs?: number,
    what?: string,
  ): Promise<RecordedServerFrame>;
  /** Resolves when connection `index` is closed (by either side). */
  waitForClose(index: number, timeoutMs?: number): Promise<WsClose>;
  pushXlog(points: readonly XlogPoint[], dict?: Readonly<Record<string, string>>): number;
  push(topic: Topic, data: unknown): number;
  pause(ms: number): void;
  drop(): void;
  close(code: number, reason?: string): void;
  reauth(): void;
  /** Frames that broke docs/14 §5.2 (and, in server mode, server frames the UI rejects). */
  violations(): string[];
}

/** URL pattern of the Argos WebSocket endpoint (docs/14 §5.1: /ws). */
export const WS_URL = /^wss?:\/\/[^/]+\/ws(\?.*)?$/;

async function poll(check: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() > deadline) {
      throw new Error(`ws fixture: timed out after ${String(timeoutMs)} ms waiting for ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

function text(message: string | Buffer): string {
  return typeof message === 'string' ? message : message.toString('utf8');
}

/** Installs the fixture on `page` (call before the page navigates). */
export async function installWsStub(page: Page, mode: WsMode = 'stub'): Promise<WsStub> {
  const all: Connection[] = [];
  const recorded: RecordedServerFrame[] = [];
  const serverViolations: string[] = [];
  let pauseTimer: NodeJS.Timeout | null = null;
  const open = (): Connection | undefined => [...all].reverse().find((c) => c.closed === null);

  const forward = (connection: Connection): void => {
    const { route, state } = connection;
    const server = route.connectToServer();
    route.onMessage((message) => {
      // Validation only: the stub's answers of `receive` are dropped, the server answers.
      state.receive(message);
      server.send(message);
    });
    server.onMessage((message) => {
      const body = text(message);
      const frame = parseServerFrame(body);
      recorded.push({ connection: connection.index, at: Date.now(), text: body, frame });
      if (frame === null) {
        serverViolations.push(
          `server frame rejected by the UI parser (src/realtime/frames.ts): ${body.slice(0, 200)}`,
        );
      } else if (frame.op === 'ack') {
        connection.acked.add(frame.id);
      } else if (frame.op === 'error' && frame.id !== undefined) {
        connection.acked.delete(frame.id);
        state.subscriptions.delete(frame.id);
      }
      route.send(message);
    });
    route.onClose((code, reason) => {
      connection.closed ??= { code, reason: reason ?? '', by: 'client', at: Date.now() };
      void server.close({ code, reason });
    });
    server.onClose((code, reason) => {
      connection.closed ??= { code, reason: reason ?? '', by: 'server', at: Date.now() };
      void route.close({ code, reason });
    });
  };

  await page.routeWebSocket(WS_URL, (route) => {
    const connection: Connection = {
      index: all.length,
      route,
      state: new WsConnectionState(),
      openedAt: Date.now(),
      acked: new Set(),
      closed: null,
    };
    all.push(connection);
    if (mode === 'server') {
      forward(connection);
      return;
    }
    connection.state.paused = pauseTimer !== null;
    route.onMessage((message) => {
      for (const frame of connection.state.receive(message)) {
        route.send(frame);
      }
    });
    route.onClose((code, reason) => {
      connection.closed ??= { code, reason: reason ?? '', by: 'client', at: Date.now() };
    });
  });

  const stubOnly = (control: string): void => {
    if (mode === 'server') {
      throw new Error(
        `ws fixture: ${control}() is a stub control; the real server answers in wsMode 'server'`,
      );
    }
  };

  const subscriptionsOf = (connection: Connection | undefined, topic?: Topic): Subscription[] => {
    if (connection === undefined) {
      return [];
    }
    const subs = connection.state.of(topic);
    return mode === 'server' ? subs.filter((s) => connection.acked.has(s.id)) : subs;
  };

  const sendToTopic = (topic: Topic, build: (s: Subscription) => string): number => {
    const connection = open();
    if (connection === undefined || connection.state.paused) {
      return 0;
    }
    let sent = 0;
    for (const subscription of connection.state.of(topic)) {
      connection.route.send(build(subscription));
      sent += 1;
    }
    return sent;
  };

  const closeOpen = (code: number, reason: string): void => {
    const connection = open();
    if (connection === undefined) {
      return;
    }
    connection.closed = { code, reason, by: 'server', at: Date.now() };
    void connection.route.close({ code, reason });
  };

  return {
    mode,
    connections: () =>
      all.map((c) => ({ openedAt: c.openedAt, closed: c.closed, url: c.route.url() })),
    connectionCount: () => all.length,
    subscriptions: (topic) => subscriptionsOf(open(), topic),
    async waitForSubscription(topic, timeoutMs = 15_000) {
      let found: Subscription | undefined;
      await poll(
        () => {
          found = subscriptionsOf(open(), topic)[0];
          return found !== undefined;
        },
        timeoutMs,
        `a subscription${topic === undefined ? '' : ` of topic ${topic}`}${mode === 'server' ? ' acknowledged by the server' : ''}`,
      );
      if (found === undefined) {
        throw new Error('unreachable');
      }
      return found;
    },
    async waitForConnections(count, timeoutMs = 15_000) {
      await poll(() => all.length >= count, timeoutMs, `${String(count)} connection(s)`);
    },
    serverFrames: () => [...recorded],
    async waitForServerFrame(match, timeoutMs = 15_000, what = 'a matching server frame') {
      let found: RecordedServerFrame | undefined;
      await poll(
        () => {
          found = recorded.find(match);
          return found !== undefined;
        },
        timeoutMs,
        what,
      );
      if (found === undefined) {
        throw new Error('unreachable');
      }
      return found;
    },
    async waitForClose(index, timeoutMs = 15_000) {
      await poll(() => all[index]?.closed != null, timeoutMs, `connection ${String(index)} closed`);
      const closed = all[index]?.closed;
      if (closed == null) {
        throw new Error('unreachable');
      }
      return closed;
    },
    pushXlog: (points, dict = {}) => {
      stubOnly('pushXlog');
      return sendToTopic('xlog', (s) => xlogPush(s, points, dict));
    },
    push: (topic, data) => {
      stubOnly('push');
      return sendToTopic(topic, (s) => push(s, data));
    },
    pause(ms) {
      stubOnly('pause');
      for (const c of all) {
        c.state.paused = true;
      }
      if (pauseTimer !== null) {
        clearTimeout(pauseTimer);
      }
      pauseTimer = setTimeout(() => {
        pauseTimer = null;
        for (const c of all) {
          c.state.paused = false;
        }
      }, ms);
    },
    drop: () => {
      stubOnly('drop');
      closeOpen(CLOSE.serverRestart, 'server restart');
    },
    close: (code, reason = '') => {
      stubOnly('close');
      closeOpen(code, reason);
    },
    reauth() {
      stubOnly('reauth');
      const connection = open();
      if (connection === undefined) {
        return;
      }
      connection.route.send(reauthFrame());
      closeOpen(CLOSE.unauthenticated, 'reauth');
    },
    violations: () => [...all.flatMap((c) => c.state.violations), ...serverViolations],
  };
}
