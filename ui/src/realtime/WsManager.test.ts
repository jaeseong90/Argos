// SPDX-License-Identifier: Apache-2.0
//
// WsManager (docs/15 §10, DSN-15-10, docs/14 §5 normative per ADR-0010 C-167): one test per rule
// of the header of WsManager.ts, with a fake WebSocket and fake timers. The real server is WO-22;
// the E2E suite drives the same client against a docs/14 §5 stub (e2e/specs/realtime.spec.ts).
// Requirements (test/README.md §5): { type: 'req', description: 'SEC-44' }, { type: 'req', description: 'DSN-15-10' }
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FakeVisibility, fakeSockets, type FakeSockets } from '../../test/fakeWebSocket';

import { INITIAL_CONNECTION_STATE, type ConnectionState } from './connectionStore';
import {
  CLOSE_NO_PONG,
  CLOSE_NORMAL,
  MAX_SUBSCRIPTIONS,
  parseServerFrame,
  type PushFrame,
} from './frames';
import {
  BACKOFF_INITIAL_MS,
  BACKOFF_MAX_MS,
  backoffDelay,
  defaultSocketUrl,
  HIDDEN_UNSUBSCRIBE_MS,
  IDLE_CLOSE_MS,
  PING_INTERVAL_MS,
  PONG_TIMEOUT_MS,
  subscriptionKey,
  WsManager,
  type TopicHandlers,
} from './WsManager';

const T0 = Date.UTC(2026, 10, 14, 1, 0, 0);

interface Harness {
  readonly manager: WsManager;
  readonly sockets: FakeSockets;
  readonly visibility: FakeVisibility;
  readonly state: () => ConnectionState;
  readonly expired: ReturnType<typeof vi.fn>;
}

let current: Harness | null = null;

function harness(options: { random?: () => number } = {}): Harness {
  const sockets = fakeSockets();
  const visibility = new FakeVisibility();
  let state: ConnectionState = INITIAL_CONNECTION_STATE;
  const expired = vi.fn();
  const manager = new WsManager({
    createSocket: sockets.factory,
    url: () => 'wss://argos.example/ws',
    visibility,
    random: options.random ?? (() => 0.5),
    now: () => Date.now(),
    onSessionExpired: expired,
    setState: (patch) => {
      state = { ...state, ...patch };
    },
    getState: () => state,
  });
  current = { manager, sockets, visibility, state: () => state, expired };
  return current;
}

function handlers() {
  return {
    onData: vi.fn<(frame: PushFrame) => void>(),
    onResync: vi.fn<(lastTs: number | null) => void>(),
    onError: vi.fn<(error: { code: string; message?: string | undefined }) => void>(),
  } satisfies TopicHandlers;
}

/** Subscribes, opens the socket and acks: the usual connected state. */
function connected(h: Harness, topic: 'counter' | 'xlog' = 'counter', params = { app: 3 }) {
  const subscriber = handlers();
  const unsubscribe = h.manager.subscribe(topic, params, subscriber);
  const socket = h.sockets.last();
  socket.serverOpen();
  socket.ackAll();
  return { subscriber, unsubscribe, socket };
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});

afterEach(() => {
  current?.manager.dispose();
  current = null;
  vi.useRealTimers();
});

describe('T-SEC-44 WsManager: connection and subscriptions (docs/14 §5.1-§5.2)', () => {
  it('T-SEC-44 connects lazily on the first subscription to ws(s)://<host>/ws of the page', () => {
    const h = harness();
    expect(h.sockets.sockets).toHaveLength(0);
    h.manager.subscribe('counter', { app: 3 }, handlers());
    expect(h.sockets.sockets).toHaveLength(1);
    expect(h.sockets.last().url).toBe('wss://argos.example/ws');
    expect(h.state().status).toBe('connecting');
    h.sockets.last().serverOpen();
    expect(h.state().status).toBe('open');
    // The default URL follows the page: http -> ws, https -> wss, same host, path /ws.
    expect(defaultSocketUrl()).toBe(
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`,
    );
  });

  it('T-SEC-44 sends one subscribe frame per topic + params and unsubscribe after the last release', () => {
    const h = harness();
    const a = h.manager.subscribe('counter', { app: 3 }, handlers());
    const b = h.manager.subscribe('counter', { app: 3 }, handlers());
    const c = h.manager.subscribe('xlog', { app: 3, kind: 'HTTP' }, handlers());
    const socket = h.sockets.last();
    socket.serverOpen();
    const subscribes = socket.framesOf('subscribe');
    expect(subscribes).toEqual([
      { op: 'subscribe', id: 's1', topic: 'counter', params: { app: 3 } },
      { op: 'subscribe', id: 's2', topic: 'xlog', params: { app: 3, kind: 'HTTP' } },
    ]);
    expect(h.manager.subscriptionCount).toBe(2);
    a();
    expect(socket.framesOf('unsubscribe')).toEqual([]);
    b();
    b(); // releasing twice is harmless
    expect(socket.framesOf('unsubscribe')).toEqual([{ op: 'unsubscribe', id: 's1' }]);
    c();
    expect(socket.framesOf('unsubscribe')).toHaveLength(2);
    expect(h.manager.subscriptionCount).toBe(0);
    // Key order of params does not matter.
    expect(subscriptionKey('xlog', { kind: 'HTTP', app: 3 })).toBe(
      subscriptionKey('xlog', { app: 3, kind: 'HTTP' }),
    );
  });

  it('T-SEC-44 refuses a 21st distinct subscription on the client and sends nothing for it', () => {
    const h = harness();
    for (let app = 1; app <= MAX_SUBSCRIPTIONS; app += 1) {
      h.manager.subscribe('counter', { app }, handlers());
    }
    const socket = h.sockets.last();
    socket.serverOpen();
    expect(socket.framesOf('subscribe')).toHaveLength(20);
    const refused = handlers();
    const release = h.manager.subscribe('counter', { app: 21 }, refused);
    expect(refused.onError).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'limit_exceeded' }),
    );
    expect(socket.framesOf('subscribe')).toHaveLength(20);
    expect(h.state().refusedSubscriptions).toBe(1);
    release();
    // Another subscriber of an existing key is not a new subscription and is accepted.
    const shared = handlers();
    h.manager.subscribe('counter', { app: 1 }, shared);
    expect(shared.onError).not.toHaveBeenCalled();
  });

  it('T-SEC-44 refuses a client frame larger than 4 KB (docs/14 §5.1)', () => {
    const h = harness();
    const subscriber = handlers();
    h.manager.subscribe('xlog', { app: 3, service: ['x'.repeat(5000)] }, subscriber);
    const socket = h.sockets.last();
    socket.serverOpen();
    expect(socket.framesOf('subscribe')).toEqual([]);
    expect(subscriber.onError).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'limit_exceeded' }),
    );
  });

  it('T-SEC-44 delivers push frames to every subscriber of the subscription and records the time', () => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    const second = handlers();
    h.manager.subscribe('counter', { app: 3 }, second);
    socket.serverSend({
      op: 'push',
      id: 's1',
      topic: 'counter',
      t: '2026-11-14T01:00:05.000Z',
      data: { tps: 1 },
    });
    expect(subscriber.onData).toHaveBeenCalledTimes(1);
    expect(second.onData).toHaveBeenCalledTimes(1);
    expect(subscriber.onData.mock.calls[0]?.[0]).toMatchObject({ data: { tps: 1 } });
    expect(h.state().lastMessageAt).toBe(T0);
  });

  it('T-SEC-44 a server error frame ends that subscription and reports the code', () => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    socket.serverSend({ op: 'error', id: 's1', code: 'forbidden', message: 'no access' });
    expect(subscriber.onError).toHaveBeenCalledWith({ code: 'forbidden', message: 'no access' });
    expect(h.manager.subscriptionCount).toBe(0);
  });

  it('T-SEC-44 closes the connection 30 s after the last subscription is released', () => {
    const h = harness();
    const { unsubscribe, socket } = connected(h);
    unsubscribe();
    vi.advanceTimersByTime(IDLE_CLOSE_MS - 1);
    expect(socket.closedBy).toBeNull();
    vi.advanceTimersByTime(1);
    expect(socket.closedBy).toEqual({ code: CLOSE_NORMAL, reason: 'idle' });
    expect(h.state().status).toBe('idle');
  });
});

describe('T-SEC-44 WsManager: reconnect (docs/15 §10, docs/14 §5.3)', () => {
  it('T-SEC-44 back-off doubles from 1 s to 30 s with ±20 % jitter', () => {
    const exact = () => 0.5;
    expect([0, 1, 2, 3, 4, 5, 6, 12].map((n) => backoffDelay(n, exact))).toEqual([
      1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000,
    ]);
    expect(backoffDelay(0, () => 0)).toBe(BACKOFF_INITIAL_MS * 0.8);
    expect(backoffDelay(0, () => 0.999999)).toBeCloseTo(BACKOFF_INITIAL_MS * 1.2, -1);
    expect(backoffDelay(10, () => 0)).toBe(BACKOFF_MAX_MS * 0.8);
    expect(backoffDelay(10, () => 0.999999)).toBeLessThanOrEqual(BACKOFF_MAX_MS * 1.2);
  });

  it('T-SEC-44 reconnects after a drop with the back-off delay of each attempt', () => {
    const h = harness();
    const { socket } = connected(h);
    socket.serverClose(1012);
    expect(h.state().status).toBe('closed');
    vi.advanceTimersByTime(999);
    expect(h.sockets.sockets).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(h.sockets.sockets).toHaveLength(2);
    // The second attempt fails too: the next delay is 2 s.
    h.sockets.last().serverClose(1006);
    vi.advanceTimersByTime(1999);
    expect(h.sockets.sockets).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(h.sockets.sockets).toHaveLength(3);
    // A successful open resets the back-off.
    h.sockets.last().serverOpen();
    h.sockets.last().serverClose(1006);
    vi.advanceTimersByTime(1000);
    expect(h.sockets.sockets).toHaveLength(4);
  });

  it('T-SEC-44 a subscription made during the back-off waits for the scheduled attempt', () => {
    const h = harness();
    h.manager.subscribe('counter', { app: 3 }, handlers());
    // Three failed connection attempts: the next one is scheduled 4 s later (attempt 2).
    h.sockets.last().serverClose(1006);
    vi.advanceTimersByTime(1000);
    h.sockets.last().serverClose(1006);
    vi.advanceTimersByTime(2000);
    expect(h.sockets.sockets).toHaveLength(3);
    h.sockets.last().serverClose(1006);
    // A screen change subscribes to a new topic while the reconnect is pending.
    h.manager.subscribe('xlog', { app: 3 }, handlers());
    expect(h.sockets.sockets).toHaveLength(3);
    vi.advanceTimersByTime(3999);
    expect(h.sockets.sockets).toHaveLength(3);
    vi.advanceTimersByTime(1);
    expect(h.sockets.sockets).toHaveLength(4);
    const next = h.sockets.last();
    next.serverOpen();
    expect(
      next
        .framesOf('subscribe')
        .map((f) => f.topic)
        .sort(),
    ).toEqual(['counter', 'xlog']);
  });

  it('T-SEC-44 after a reconnect resubscribes everything and calls onResync with the last push time', () => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    const other = handlers();
    h.manager.subscribe('xlog', { app: 3 }, other);
    socket.serverSend({
      op: 'push',
      id: 's1',
      topic: 'counter',
      t: '2026-11-14T01:00:05.000Z',
      data: {},
    });
    socket.serverClose(1006);
    vi.advanceTimersByTime(1000);
    const next = h.sockets.last();
    expect(next).not.toBe(socket);
    expect(subscriber.onResync).not.toHaveBeenCalled();
    next.serverOpen();
    expect(
      next
        .framesOf('subscribe')
        .map((f) => f.topic)
        .sort(),
    ).toEqual(['counter', 'xlog']);
    expect(subscriber.onResync).toHaveBeenCalledWith(Date.parse('2026-11-14T01:00:05.000Z'));
    // No push arrived for xlog: the gap starts at "unknown" (null) and the screen refetches all.
    expect(other.onResync).toHaveBeenCalledWith(null);
  });

  it('T-SEC-44 close 4401 does not reconnect and reports the expired session', () => {
    const h = harness();
    const { socket } = connected(h);
    socket.serverClose(4401);
    vi.advanceTimersByTime(120_000);
    expect(h.sockets.sockets).toHaveLength(1);
    expect(h.expired).toHaveBeenCalledTimes(1);
    // A new subscription does not open a socket for the ended session either.
    h.manager.subscribe('counter', { app: 4 }, handlers());
    vi.advanceTimersByTime(60_000);
    expect(h.sockets.sockets).toHaveLength(1);
    // After a new login the manager connects again.
    h.manager.resetSession();
    expect(h.sockets.sockets).toHaveLength(2);
  });

  it('T-SEC-44 the reauth frame (ADR-0010 C-167 (2)) closes, does not reconnect and expires the session', () => {
    const h = harness();
    const { socket } = connected(h);
    socket.serverSend({ op: 'reauth' });
    expect(socket.closedBy?.code).toBe(CLOSE_NORMAL);
    expect(h.expired).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(120_000);
    expect(h.sockets.sockets).toHaveLength(1);
  });

  it('T-SEC-44 close 4403 (origin mismatch) is not retried', () => {
    const h = harness();
    const { socket } = connected(h);
    socket.serverClose(4403);
    vi.advanceTimersByTime(120_000);
    expect(h.sockets.sockets).toHaveLength(1);
    expect(h.expired).not.toHaveBeenCalled();
  });

  it('T-SEC-44 shutdown (logout) closes the socket and nothing reconnects until a new login', () => {
    const h = harness();
    const { socket } = connected(h);
    h.manager.shutdown();
    expect(socket.closedBy).toEqual({ code: CLOSE_NORMAL, reason: 'logout' });
    expect(h.state().status).toBe('closed');
    h.manager.subscribe('counter', { app: 9 }, handlers());
    vi.advanceTimersByTime(120_000);
    expect(h.sockets.sockets).toHaveLength(1);
    h.manager.resetSession();
    expect(h.sockets.sockets).toHaveLength(2);
  });
});

describe('T-SEC-44 WsManager: heartbeat and visibility (docs/15 §10)', () => {
  it('T-SEC-44 pings every 30 s; a pong within 10 s keeps the connection and records the round trip', () => {
    const h = harness();
    const { socket } = connected(h);
    vi.advanceTimersByTime(PING_INTERVAL_MS - 1);
    expect(socket.framesOf('ping')).toHaveLength(0);
    vi.advanceTimersByTime(1);
    const ping = socket.framesOf('ping')[0];
    expect(ping).toEqual({ op: 'ping', t: T0 + PING_INTERVAL_MS });
    vi.advanceTimersByTime(250);
    socket.serverSend({ op: 'pong', t: ping?.t, server_t: '2026-11-14T01:00:30.200Z' });
    expect(h.state().rttMs).toBe(250);
    vi.advanceTimersByTime(PONG_TIMEOUT_MS);
    expect(socket.closedBy).toBeNull();
    vi.advanceTimersByTime(PING_INTERVAL_MS);
    expect(socket.framesOf('ping')).toHaveLength(2);
  });

  it('T-SEC-44 reconnects when no pong arrives within 10 s of a ping', () => {
    const h = harness();
    const { socket } = connected(h);
    vi.advanceTimersByTime(PING_INTERVAL_MS + PONG_TIMEOUT_MS - 1);
    expect(socket.closedBy).toBeNull();
    vi.advanceTimersByTime(1);
    expect(socket.closedBy?.code).toBe(CLOSE_NO_PONG);
    vi.advanceTimersByTime(BACKOFF_INITIAL_MS);
    expect(h.sockets.sockets).toHaveLength(2);
  });

  it('T-SEC-44 a pong does not count as fresh data; ack, push and notice do', () => {
    const h = harness();
    const { socket } = connected(h);
    expect(h.state().lastMessageAt).toBe(T0);
    vi.advanceTimersByTime(PING_INTERVAL_MS);
    socket.serverSend({ op: 'pong', t: Date.now() });
    expect(h.state().lastMessageAt).toBe(T0);
    socket.serverSend({ op: 'notice', code: 'backpressure', topic: 'xlog', dropped: 12 });
    expect(h.state().lastMessageAt).toBe(T0 + PING_INTERVAL_MS);
    expect(h.state().serverDropped).toBe(12);
  });

  it('T-SEC-44 hidden for 30 s unsubscribes everything but keeps the connection; visible resubscribes and resyncs', () => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    h.visibility.set('hidden');
    vi.advanceTimersByTime(HIDDEN_UNSUBSCRIBE_MS - 1);
    expect(socket.framesOf('unsubscribe')).toHaveLength(0);
    vi.advanceTimersByTime(1);
    expect(socket.framesOf('unsubscribe')).toEqual([{ op: 'unsubscribe', id: 's1' }]);
    expect(socket.closedBy).toBeNull();
    expect(h.manager.subscriptionCount).toBe(1);
    h.visibility.set('visible');
    expect(socket.framesOf('subscribe')).toHaveLength(2);
    expect(subscriber.onResync).toHaveBeenCalledTimes(1);
  });

  it('T-SEC-44 a page hidden for less than 30 s keeps its subscriptions', () => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    h.visibility.set('hidden');
    vi.advanceTimersByTime(HIDDEN_UNSUBSCRIBE_MS - 1000);
    h.visibility.set('visible');
    vi.advanceTimersByTime(HIDDEN_UNSUBSCRIBE_MS);
    expect(socket.framesOf('unsubscribe')).toHaveLength(0);
    expect(subscriber.onResync).not.toHaveBeenCalled();
  });
});

describe('T-SEC-44 WsManager: frame validation (docs/15 §10)', () => {
  it.each([
    ['not JSON', '{oops'],
    ['unknown op', { op: 'data', id: 's1' }],
    ['push without t', { op: 'push', id: 's1', topic: 'counter', data: {} }],
    ['push of an unknown topic', { op: 'push', id: 's1', topic: 'nope', t: 'x', data: {} }],
    ['pong with a string time', { op: 'pong', t: 'now' }],
    ['a JSON array', [1, 2, 3]],
  ])('T-SEC-44 drops and counts an invalid frame: %s', (_name, frame) => {
    const h = harness();
    const { subscriber, socket } = connected(h);
    const before = h.state().lastMessageAt;
    vi.advanceTimersByTime(5000);
    socket.serverSend(frame);
    expect(h.state().invalidFrames).toBe(1);
    expect(h.state().lastMessageAt).toBe(before);
    expect(subscriber.onData).not.toHaveBeenCalled();
  });

  it('T-SEC-44 parses the docs/14 §5.2 server frames', () => {
    expect(parseServerFrame('{"op":"ack","id":"s1","topic":"xlog","since":"x"}')?.op).toBe('ack');
    expect(parseServerFrame('{"op":"notice","code":"backpressure","dropped":3}')?.op).toBe(
      'notice',
    );
    expect(parseServerFrame('{"op":"reauth"}')?.op).toBe('reauth');
    expect(parseServerFrame(42)).toBeNull();
  });
});
