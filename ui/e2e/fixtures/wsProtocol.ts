// SPDX-License-Identifier: Apache-2.0
//
// Server side of the WebSocket protocol of docs/14 §5 (normative per ADR-0010 C-167 (1)) for the E2E
// stub: validates every client frame (§5.2), answers subscribe with ack (or error), ping with pong,
// keeps the subscriptions of a connection (at most 20, §5.1) and builds push, notice, error and the
// reauth frame of C-167 (2). Pure logic, no Playwright: the fixture (e2e/fixtures/ws.ts) sends what
// `receive` returns. The real server is WO-22; this stub never ships (ui/e2e only).
import { z } from 'zod';

/** Topics of docs/14 §5.3. */
export const TOPICS = ['xlog', 'counter', 'active', 'alert', 'overview', 'admin'] as const;
export type Topic = (typeof TOPICS)[number];

/** Close codes of docs/14 §5.3. */
export const CLOSE = {
  unauthenticated: 4401,
  originMismatch: 4403,
  timeout: 4408,
  tooManyConnections: 4429,
  serverRestart: 1012,
} as const;

/** At most 20 subscriptions per connection, client frames up to 4 KB (docs/14 §5.1). */
export const MAX_SUBSCRIPTIONS = 20;
export const MAX_CLIENT_FRAME_BYTES = 4096;

const SubscribeFrame = z.strictObject({
  op: z.literal('subscribe'),
  id: z.string().min(1).max(64),
  topic: z.string().min(1).max(32),
  params: z.record(z.string(), z.unknown()),
});
const UnsubscribeFrame = z.strictObject({
  op: z.literal('unsubscribe'),
  id: z.string().min(1).max(64),
});
const PingFrame = z.strictObject({ op: z.literal('ping'), t: z.number().int().nonnegative() });
export const ClientFrame = z.discriminatedUnion('op', [
  SubscribeFrame,
  UnsubscribeFrame,
  PingFrame,
]);
export type ClientFrame = z.infer<typeof ClientFrame>;

export interface Subscription {
  readonly id: string;
  readonly topic: Topic;
  readonly params: Readonly<Record<string, unknown>>;
}

/** A server frame as JSON text. */
export type ServerFrame = string;

/** What one connection has seen; `violations` are frames that break §5.2 (the test fails on them). */
export class WsConnectionState {
  readonly subscriptions = new Map<string, Subscription>();
  readonly frames: ClientFrame[] = [];
  readonly violations: string[] = [];
  /** Answers are withheld while the stub is paused (no ack, pong or push). */
  paused = false;

  /** Handles one client message; returns the frames to send back. */
  receive(message: string | Buffer, now: Date = new Date()): ServerFrame[] {
    const text = typeof message === 'string' ? message : message.toString('utf8');
    if (Buffer.byteLength(text, 'utf8') > MAX_CLIENT_FRAME_BYTES) {
      this.violations.push(
        `client frame of ${String(Buffer.byteLength(text, 'utf8'))} bytes (limit 4 KB)`,
      );
      return [];
    }
    let json: unknown;
    try {
      json = JSON.parse(text);
    } catch {
      this.violations.push(`client frame is not JSON: ${text.slice(0, 120)}`);
      return [];
    }
    const parsed = ClientFrame.safeParse(json);
    if (!parsed.success) {
      this.violations.push(`client frame breaks docs/14 §5.2: ${text.slice(0, 200)}`);
      return [];
    }
    const frame = parsed.data;
    this.frames.push(frame);
    if (this.paused) {
      return [];
    }
    switch (frame.op) {
      case 'ping':
        return [JSON.stringify({ op: 'pong', t: frame.t, server_t: now.toISOString() })];
      case 'unsubscribe':
        this.subscriptions.delete(frame.id);
        return [];
      case 'subscribe': {
        if (!(TOPICS as readonly string[]).includes(frame.topic)) {
          return [errorFrame(frame.id, 'topic_unknown', 'unknown topic')];
        }
        if (!this.subscriptions.has(frame.id) && this.subscriptions.size >= MAX_SUBSCRIPTIONS) {
          return [
            errorFrame(frame.id, 'limit_exceeded', 'at most 20 subscriptions per connection'),
          ];
        }
        this.subscriptions.set(frame.id, {
          id: frame.id,
          topic: frame.topic as Topic,
          params: frame.params,
        });
        return [
          JSON.stringify({ op: 'ack', id: frame.id, topic: frame.topic, since: now.toISOString() }),
        ];
      }
    }
  }

  /** Subscriptions of `topic` (all topics when undefined). */
  of(topic?: Topic): Subscription[] {
    return [...this.subscriptions.values()].filter((s) => topic === undefined || s.topic === topic);
  }
}

export function errorFrame(id: string, code: string, message: string): ServerFrame {
  return JSON.stringify({ op: 'error', id, code, message });
}

/** One XLog point of a push (docs/14 §4.5 columns). */
export interface XlogPoint {
  readonly tx_id: string;
  readonly ts: string;
  readonly elapsed_ms: number;
  readonly status: string;
  readonly instance_id: number;
  readonly service_id: string;
  readonly kind: string;
}

/** A push frame of the xlog topic for `subscription` (columnar, docs/14 §5.2). */
export function xlogPush(
  subscription: Subscription,
  points: readonly XlogPoint[],
  dict: Readonly<Record<string, string>>,
  now: Date = new Date(),
): ServerFrame {
  return JSON.stringify({
    op: 'push',
    id: subscription.id,
    topic: 'xlog',
    t: now.toISOString(),
    data: {
      tx_id: points.map((p) => p.tx_id),
      ts: points.map((p) => p.ts),
      elapsed_ms: points.map((p) => p.elapsed_ms),
      status: points.map((p) => p.status),
      instance_id: points.map((p) => p.instance_id),
      service_id: points.map((p) => p.service_id),
      kind: points.map((p) => p.kind),
    },
    dict: { service: dict },
    dropped: 0,
  });
}

/** A push frame of any topic with a given data object. */
export function push(
  subscription: Subscription,
  data: unknown,
  now: Date = new Date(),
): ServerFrame {
  return JSON.stringify({
    op: 'push',
    id: subscription.id,
    topic: subscription.topic,
    t: now.toISOString(),
    data,
  });
}

/** notice backpressure (docs/14 §5.2). */
export function notice(topic: Topic, dropped: number): ServerFrame {
  return JSON.stringify({ op: 'notice', code: 'backpressure', topic, dropped });
}

/** The reauth frame of ADR-0010 C-167 (2): the session expired or was revoked. */
export function reauthFrame(): ServerFrame {
  return JSON.stringify({ op: 'reauth' });
}
