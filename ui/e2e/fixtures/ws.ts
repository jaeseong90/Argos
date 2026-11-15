// SPDX-License-Identifier: Apache-2.0
//
// WebSocket fixture of the E2E stack (docs/18 §13 "ws", ADR-0010 C-167 (3)): page.routeWebSocket on
// the /ws URL implements the server side of docs/14 §5 (e2e/fixtures/wsProtocol.ts), because the
// WebSocket server (WO-22) is not part of the slice. It is the only stubbed interface of the stack:
// the REST API and the databases are real. Controls:
//   pushXlog(points)   push frames to every xlog subscription of the open connection
//   push(topic, data)  push frames to every subscription of `topic`
//   pause(ms)          answer nothing (no ack, pong or push) for `ms` (then resumes)
//   drop()             end the connection as a server restart would (close 1012): the client
//                      must reconnect with back-off
//   close(code)        close with a docs/14 §5.3 code (4401: the client must not reconnect)
//   reauth()           the reauth frame of C-167 (2), then close 4401
// Every client frame is validated against docs/14 §5.2; a violation fails the test at teardown.
// Pass-through mode (`mode: 'server'`, connectToServer()) is reserved for WO-22: the same specs then
// talk to the real /ws.
import type { Page, WebSocketRoute } from '@playwright/test';

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

interface Connection {
  readonly route: WebSocketRoute;
  readonly state: WsConnectionState;
  readonly openedAt: number;
  closed: { code: number | undefined; by: 'client' | 'server' } | null;
}

export interface WsStub {
  /** Connections opened by the page so far (including closed ones), oldest first. */
  connections(): readonly { openedAt: number; closed: Connection['closed']; url: string }[];
  /** Number of connections opened so far. */
  connectionCount(): number;
  /** The subscriptions of the newest open connection. */
  subscriptions(topic?: Topic): Subscription[];
  /** Resolves when the newest open connection has a subscription of `topic` (any topic if undefined). */
  waitForSubscription(topic?: Topic, timeoutMs?: number): Promise<Subscription>;
  /** Resolves when `count` connections have been opened. */
  waitForConnections(count: number, timeoutMs?: number): Promise<void>;
  pushXlog(points: readonly XlogPoint[], dict?: Readonly<Record<string, string>>): number;
  push(topic: Topic, data: unknown): number;
  pause(ms: number): void;
  drop(): void;
  close(code: number, reason?: string): void;
  reauth(): void;
  /** Frames that broke docs/14 §5.2, over every connection. */
  violations(): string[];
}

/** URL pattern of the Argos WebSocket endpoint (docs/14 §5.1: /ws). */
export const WS_URL = /^wss?:\/\/[^/]+\/ws(\?.*)?$/;

async function poll(check: () => boolean, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (!check()) {
    if (Date.now() > deadline) {
      throw new Error(`ws stub: timed out after ${String(timeoutMs)} ms waiting for ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
}

/** Installs the stub on `page` (call before the page navigates). */
export async function installWsStub(page: Page, mode: WsMode = 'stub'): Promise<WsStub> {
  const all: Connection[] = [];
  let pauseTimer: NodeJS.Timeout | null = null;
  const open = (): Connection | undefined => [...all].reverse().find((c) => c.closed === null);

  await page.routeWebSocket(WS_URL, (route) => {
    const connection: Connection = {
      route,
      state: new WsConnectionState(),
      openedAt: Date.now(),
      closed: null,
    };
    all.push(connection);
    if (mode === 'server') {
      // Reserved for WO-22: forward everything to the real server, keep validating client frames.
      const server = route.connectToServer();
      route.onMessage((message) => {
        connection.state.receive(message);
        server.send(message);
      });
      return;
    }
    connection.state.paused = pauseTimer !== null;
    route.onMessage((message) => {
      for (const frame of connection.state.receive(message)) {
        route.send(frame);
      }
    });
    route.onClose((code) => {
      connection.closed ??= { code, by: 'client' };
    });
  });

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
    connection.closed = { code, by: 'server' };
    void connection.route.close({ code, reason });
  };

  return {
    connections: () =>
      all.map((c) => ({ openedAt: c.openedAt, closed: c.closed, url: c.route.url() })),
    connectionCount: () => all.length,
    subscriptions: (topic) => open()?.state.of(topic) ?? [],
    async waitForSubscription(topic, timeoutMs = 15_000) {
      let found: Subscription | undefined;
      await poll(
        () => {
          found = open()?.state.of(topic)[0];
          return found !== undefined;
        },
        timeoutMs,
        `a subscription${topic === undefined ? '' : ` of topic ${topic}`}`,
      );
      if (found === undefined) {
        throw new Error('unreachable');
      }
      return found;
    },
    async waitForConnections(count, timeoutMs = 15_000) {
      await poll(() => all.length >= count, timeoutMs, `${String(count)} connection(s)`);
    },
    pushXlog: (points, dict = {}) => sendToTopic('xlog', (s) => xlogPush(s, points, dict)),
    push: (topic, data) => sendToTopic(topic, (s) => push(s, data)),
    pause(ms) {
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
      closeOpen(CLOSE.serverRestart, 'server restart');
    },
    close: (code, reason = '') => {
      closeOpen(code, reason);
    },
    reauth() {
      const connection = open();
      if (connection === undefined) {
        return;
      }
      connection.route.send(reauthFrame());
      closeOpen(CLOSE.unauthenticated, 'reauth');
    },
    violations: () => all.flatMap((c) => c.state.violations),
  };
}
