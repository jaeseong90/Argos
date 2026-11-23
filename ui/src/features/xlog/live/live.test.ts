// SPDX-License-Identifier: Apache-2.0
//
// Realtime XLog (docs/15 §5, §7.5, §10; docs/14 §5.2, §5.3; WO-33 item 4): initial REST load over
// the last 5 minutes, 100 ms batches appended, tx_id de-duplication across REST and push with a
// bounded set, window moved every second with eviction, pause stops only the window, resume and
// reconnect fill [last received, now] through REST, D-15 late points are not shown live, invalid
// payloads are dropped and counted. Fake timers and a fake REST call; no React.
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fakeSockets, type FakeSocket } from '../../../../test/fakeWebSocket';
import type { ResponseMeta } from '../../../api/client';
import { toXlogPointFilters } from '../../../api/transactionSearch';
import { untrusted } from '../../../api/untrusted';
import { INITIAL_CONNECTION_STATE, useConnectionStore } from '../../../realtime/connectionStore';
import type { PushFrame } from '../../../realtime/frames';
import { POLL_AFTER_S, setWsManager } from '../../../realtime/hooks';
import { WsManager } from '../../../realtime/WsManager';
import type { Filter } from '../../../state/urlState';
import { XLogBuffer } from '../../../xlog/XLogBuffer';
import type { XLogPoints } from '../data/xlogApi';
import { xlogTopicParams } from '../data/xlogQuery';

import { BoundedIdSet } from './boundedIdSet';
import { MAX_PUSH_ROWS, parseXLogPush } from './pushData';
import { useXLogLive } from './useXLogLive';
import {
  FILL_OVERLAP_MS,
  LATE_MS,
  LIVE_TICK_MS,
  LIVE_WINDOW_MS,
  livePointAccepts,
  MAX_PENDING_PUSHES,
  POLL_INTERVAL_MS,
  XLogLiveController,
  type LiveControllerOptions,
  type LiveLoad,
  type LiveWindow,
} from './XLogLiveController';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

/**
 * The points API of the hook tests (useXLogLive): every call is kept with its query and answered by
 * the test. The controller tests pass their own fetch and do not use it.
 */
const api = vi.hoisted(() => ({
  requests: [] as {
    readonly appId: number;
    readonly query: Readonly<Record<string, unknown>>;
    resolve(value: unknown): void;
    reject(error: unknown): void;
  }[],
}));
vi.mock('../data/xlogApi', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  fetchXLogPoints: (appId: number, query: Record<string, unknown>) =>
    new Promise((resolve, reject) => {
      api.requests.push({ appId, query, resolve, reject });
    }),
}));

const NOW = Date.UTC(2026, 10, 15, 3, 0, 0, 400);
const META: ResponseMeta = { request_id: 'r', generated_at: '2026-11-15T03:00:00Z' };

interface Row {
  readonly tx: string;
  readonly ts: number;
  readonly elapsed?: number;
  readonly status?: number;
  readonly service?: string;
  readonly instance?: number;
}

function columns(rows: readonly Row[]) {
  return {
    tx_id: rows.map((r) => r.tx),
    ts: rows.map((r) => r.ts),
    elapsed_ms: rows.map((r) => r.elapsed ?? 100),
    status: rows.map((r) => r.status ?? 0),
    instance_id: rows.map((r) => r.instance ?? 1),
    service_id: rows.map((r) => r.service ?? '7'),
    kind: rows.map(() => 1),
  };
}

function points(
  rows: readonly Row[],
  names: Record<string, string> = { '7': 'GET /a' },
): XLogPoints {
  return {
    data: { ...columns(rows), dictionary: { service: names, instance: { '1': 'web-01' } } },
    meta: META,
  } as unknown as XLogPoints;
}

function push(
  rows: readonly Row[],
  t: number,
  extra: Partial<Pick<PushFrame, 'dict' | 'dropped'>> = {},
): PushFrame {
  return {
    op: 'push',
    id: 's1',
    topic: 'xlog',
    t: new Date(t).toISOString(),
    data: columns(rows),
    ...extra,
  };
}

interface Deferred {
  readonly range: LiveWindow;
  readonly signal: AbortSignal;
  resolve(value: XLogPoints): void;
  reject(error: unknown): void;
}

interface Harness {
  controller: XLogLiveController;
  buffer: XLogBuffer;
  requests: Deferred[];
  windows: LiveWindow[];
  loads: LiveLoad[];
  errors: unknown[];
  /** tx_ids in the buffer, oldest first. */
  txIds: () => string[];
}

function setup(
  capacity = 1000,
  extra: Partial<Pick<LiveControllerOptions, 'onData' | 'pointFilter'>> = {},
): Harness {
  const buffer = new XLogBuffer(capacity);
  const requests: Deferred[] = [];
  const windows: LiveWindow[] = [];
  const loads: LiveLoad[] = [];
  const errors: unknown[] = [];
  const controller = new XLogLiveController({
    buffer,
    fetchPoints: (range, signal) =>
      new Promise<XLogPoints>((resolve, reject) => {
        requests.push({ range, signal, resolve, reject });
      }),
    onWindow: (window) => windows.push(window),
    onLoad: (load) => loads.push(load),
    onError: (error) => errors.push(error),
    ...extra,
  });
  return {
    controller,
    buffer,
    requests,
    windows,
    loads,
    errors,
    txIds: () => {
      const out: string[] = [];
      for (let logical = 0; logical < buffer.size; logical += 1) {
        const p = buffer.physical(logical);
        out.push(
          ((BigInt(buffer.txIdHi[p] ?? 0) << 32n) | BigInt(buffer.txIdLo[p] ?? 0)).toString(),
        );
      }
      return out;
    },
  };
}

/** Lets the promise callbacks of a resolved fake request run. */
async function settle(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('XLogLiveController (docs/15 §7.5)', () => {
  it('T-UI-001 loads the last 5 minutes through REST, then shows the window ending at the next second', async () => {
    const h = setup();
    h.controller.start();
    expect(h.windows).toEqual([{ from: NOW + 600 - LIVE_WINDOW_MS, to: NOW + 600 }]);
    expect(h.requests).toHaveLength(1);
    expect(h.requests[0]?.range).toEqual({ from: NOW - LIVE_WINDOW_MS, to: NOW });
    h.requests[0]?.resolve(
      points([
        { tx: '100', ts: NOW - 1000 },
        { tx: '101', ts: NOW - 500 },
      ]),
    );
    await settle();
    expect(h.loads).toHaveLength(1);
    expect(h.loads[0]?.dictionary.serviceName(0)).toBe('GET /a');
    expect(h.txIds()).toEqual(['100', '101']);
    h.controller.stop();
  });

  it('T-UI-001 appends batches and drops tx_ids already received through REST or push (no duplicate)', async () => {
    const h = setup();
    h.controller.start();
    // A batch arrives while the initial load runs: kept and applied after it.
    h.controller.onPush(
      push(
        [
          { tx: '101', ts: NOW - 500 },
          { tx: '102', ts: NOW - 100 },
        ],
        NOW,
      ),
    );
    h.requests[0]?.resolve(
      points([
        { tx: '100', ts: NOW - 1000 },
        { tx: '101', ts: NOW - 500 },
      ]),
    );
    await settle();
    expect(h.txIds()).toEqual(['100', '101', '102']);
    // The same batch again (a resend after a reconnect) adds nothing.
    h.controller.onPush(
      push(
        [
          { tx: '102', ts: NOW - 100 },
          { tx: '103', ts: NOW + 50 },
        ],
        NOW + 100,
      ),
    );
    expect(h.txIds()).toEqual(['100', '101', '102', '103']);
    expect(h.controller.stats).toMatchObject({
      pushes: 2,
      received: 4,
      appended: 4,
      duplicates: 2,
    });
    h.controller.stop();
  });

  it('extends the dictionary with the names of a batch without replacing it', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.resolve(points([{ tx: '1', ts: NOW - 1000 }]));
    await settle();
    const dictionary = h.controller.currentDictionary;
    h.controller.onPush(
      push([{ tx: '2', ts: NOW, service: '99' }], NOW, { dict: { service: { '99': 'GET /new' } } }),
    );
    expect(h.controller.currentDictionary).toBe(dictionary);
    expect(dictionary?.serviceName(dictionary.findService('99'))).toBe('GET /new');
    expect(dictionary?.serviceName(dictionary.findService('7'))).toBe('GET /a');
    h.controller.stop();
  });

  it('T-UI-001 does not show points more than 60 s older than their batch live (D-15)', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.resolve(points([]));
    await settle();
    h.controller.onPush(
      push(
        [
          { tx: '1', ts: NOW - LATE_MS - 1 },
          { tx: '2', ts: NOW - LATE_MS },
        ],
        NOW,
      ),
    );
    expect(h.txIds()).toEqual(['2']);
    expect(h.controller.stats.late).toBe(1);
    h.controller.stop();
  });

  it('T-UI-001 moves the window every second and evicts the points that left it', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.resolve(
      points([
        { tx: '1', ts: NOW - LIVE_WINDOW_MS + 800 },
        { tx: '2', ts: NOW - LIVE_WINDOW_MS + 2500 },
        { tx: '3', ts: NOW - 10 },
      ]),
    );
    await settle();
    expect(h.txIds()).toEqual(['1', '2', '3']);
    vi.advanceTimersByTime(1000);
    // now = NOW + 1000 -> window end NOW + 1600, start NOW + 1600 - 5 min: point 1 left it.
    expect(h.windows.at(-1)).toEqual({
      from: NOW + 1600 - LIVE_WINDOW_MS,
      to: NOW + 1600,
    });
    expect(h.txIds()).toEqual(['2', '3']);
    vi.advanceTimersByTime(2000);
    expect(h.windows).toHaveLength(4);
    expect(h.txIds()).toEqual(['3']);
    expect(h.buffer.evicted).toBe(2);
    h.controller.stop();
  });

  it('T-UI-001 pause stops the window only: batches keep arriving, the paused window keeps its points', async () => {
    const h = setup();
    h.controller.start();
    // Inside the first window, but older than "now - 5 min" once 5 s have passed.
    h.requests[0]?.resolve(points([{ tx: '1', ts: NOW - LIVE_WINDOW_MS + 1000 }]));
    await settle();
    h.controller.pause();
    expect(h.controller.isPaused).toBe(true);
    const shown = h.windows.length;
    vi.advanceTimersByTime(5000);
    expect(h.windows).toHaveLength(shown);
    h.controller.onPush(push([{ tx: '2', ts: NOW + 4000 }], NOW + 4000));
    // The paused window still shows point 1 (it started before it), and point 2 arrived.
    expect(h.txIds()).toEqual(['1', '2']);
    h.controller.stop();
  });

  it('T-UI-001 resume moves the window to now and fills [last received, now] through REST, de-duplicated', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.resolve(points([{ tx: '1', ts: NOW - 1000 }]));
    await settle();
    h.controller.onPush(push([{ tx: '2', ts: NOW + 200 }], NOW + 300));
    h.controller.pause();
    vi.advanceTimersByTime(20_000);
    h.controller.resume();
    // now = NOW + 20 s = 03:00:20.400 -> the window ends at the next whole second.
    expect(h.windows.at(-1)).toEqual({ from: NOW + 20_600 - LIVE_WINDOW_MS, to: NOW + 20_600 });
    expect(h.requests).toHaveLength(2);
    expect(h.requests[1]?.range).toEqual({ from: NOW + 300 - FILL_OVERLAP_MS, to: NOW + 20_000 });
    // The gap response repeats point 2 and holds two new ones.
    h.requests[1]?.resolve(
      points([
        { tx: '2', ts: NOW + 200 },
        { tx: '3', ts: NOW + 5000 },
        { tx: '4', ts: NOW + 15_000 },
      ]),
    );
    await settle();
    expect(h.txIds()).toEqual(['1', '2', '3', '4']);
    expect(h.controller.stats.fills).toBe(1);
    h.controller.stop();
  });

  it('T-UI-001 fills the gap after a reconnect (WsManager onResync) from the last push time', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.resolve(points([]));
    await settle();
    vi.advanceTimersByTime(30_000);
    h.controller.resync(NOW + 10_000);
    expect(h.requests[1]?.range).toEqual({
      from: NOW + 10_000 - FILL_OVERLAP_MS,
      to: NOW + 30_000,
    });
    // A second resync while the first fill runs is merged into one later request.
    h.controller.resync(NOW + 5_000);
    expect(h.requests).toHaveLength(2);
    h.requests[1]?.resolve(points([{ tx: '9', ts: NOW + 12_000 }]));
    await settle();
    expect(h.requests).toHaveLength(3);
    expect(h.requests[2]?.range.from).toBe(NOW + 5_000 - FILL_OVERLAP_MS - FILL_OVERLAP_MS);
    // An hour without a connection fills only the window (5 minutes).
    h.requests[2]?.resolve(points([]));
    await settle();
    h.controller.resync(NOW - 3_600_000);
    expect(h.requests[3]?.range.from).toBe(NOW + 30_000 - LIVE_WINDOW_MS - FILL_OVERLAP_MS);
    h.controller.stop();
  });

  it('drops and counts invalid batches; reports a failed initial load; stop aborts and ends the ticks', async () => {
    const h = setup();
    h.controller.start();
    h.requests[0]?.reject(new Error('boom'));
    await settle();
    expect(h.errors).toHaveLength(1);

    const ok = setup();
    ok.controller.start();
    ok.requests[0]?.resolve(points([]));
    await settle();
    ok.controller.onPush({ ...push([], NOW), data: { tx_id: ['x'] } });
    ok.controller.onPush({ ...push([{ tx: '1', ts: NOW }], NOW), t: 'never' });
    expect(ok.controller.stats.invalidPushes).toBe(2);
    ok.controller.onPush(push([{ tx: '1', ts: NOW }], NOW, { dropped: 25 }));
    expect(ok.controller.stats.serverDropped).toBe(25);
    vi.advanceTimersByTime(3000);
    ok.controller.resync(null);
    const fill = ok.requests[1];
    expect(fill?.range).toEqual({ from: NOW - FILL_OVERLAP_MS, to: NOW + 3000 });
    ok.controller.stop();
    expect(fill?.signal.aborted).toBe(true);
    const windows = ok.windows.length;
    vi.advanceTimersByTime(5000);
    expect(ok.windows).toHaveLength(windows);
  });

  it('keeps at most MAX_PENDING_PUSHES batches while the initial load runs', async () => {
    const h = setup();
    h.controller.start();
    for (let i = 0; i < MAX_PENDING_PUSHES + 5; i += 1) {
      h.controller.onPush(push([{ tx: String(1000 + i), ts: NOW }], NOW));
    }
    h.requests[0]?.resolve(points([]));
    await settle();
    expect(h.controller.stats.droppedPending).toBe(5);
    expect(h.buffer.size).toBe(MAX_PENDING_PUSHES);
    h.controller.stop();
  });

  it('keeps at most one ring of rows in the pending batches (docs/15 §14 tab heap bound)', async () => {
    // Ring of 10 points: 4 batches of 4 rows claim 16 rows; the oldest go until 8 rows remain.
    const h = setup(10);
    h.controller.start();
    for (let b = 0; b < 4; b += 1) {
      const rows = [0, 1, 2, 3].map((i) => ({ tx: String(100 + b * 4 + i), ts: NOW }));
      h.controller.onPush(push(rows, NOW));
    }
    expect(h.controller.stats.droppedPending).toBe(2);
    h.requests[0]?.resolve(points([]));
    await settle();
    expect(h.txIds()).toEqual(['108', '109', '110', '111', '112', '113', '114', '115']);
    h.controller.stop();
  });

  it('T-UI-001 applies the error filter to pushed batches like the points API (the topic has no such parameter)', async () => {
    const h = setup(1000, { pointFilter: { error: true } });
    h.controller.start();
    h.requests[0]?.resolve(points([]));
    await settle();
    h.controller.onPush(
      push(
        [
          { tx: '1', ts: NOW, status: 0 },
          { tx: '2', ts: NOW, status: 2 },
          { tx: '3', ts: NOW, status: 1 },
        ],
        NOW,
      ),
    );
    expect(h.txIds()).toEqual(['2']);
    expect(h.controller.stats.filtered).toBe(2);
    h.controller.stop();

    const ok = setup(1000, { pointFilter: { error: false } });
    ok.controller.start();
    ok.requests[0]?.resolve(points([]));
    await settle();
    ok.controller.onPush(
      push(
        [
          { tx: '1', ts: NOW, status: 0 },
          { tx: '2', ts: NOW, status: 2 },
          { tx: '3', ts: NOW, status: 1 },
        ],
        NOW,
      ),
    );
    expect(ok.txIds()).toEqual(['1', '3']);
    ok.controller.stop();
  });

  it('T-UI-001 drops pushed points outside the response time filter (inclusive bounds, as min_ms/max_ms)', async () => {
    const h = setup(1000, { pointFilter: { minMs: 200, maxMs: 300 } });
    h.controller.start();
    h.requests[0]?.resolve(points([]));
    await settle();
    h.controller.onPush(
      push(
        [
          { tx: '1', ts: NOW, elapsed: 199 },
          { tx: '2', ts: NOW, elapsed: 200 },
          { tx: '3', ts: NOW, elapsed: 300 },
          { tx: '4', ts: NOW, elapsed: 301 },
        ],
        NOW,
      ),
    );
    expect(h.txIds()).toEqual(['2', '3']);
    expect(h.controller.stats.filtered).toBe(2);
    // The rule itself: no filter keeps everything; an unknown response time never passes a bound.
    expect(livePointAccepts({}, 5, 0)).toBe(true);
    expect(livePointAccepts({ minMs: 10 }, Number.NaN, 0)).toBe(false);
    h.controller.stop();
  });

  it('T-UI-001 reports the new point count once per tick while paused (the window does not move)', async () => {
    const sizes: number[] = [];
    const h = setup(1000, { onData: (size) => sizes.push(size) });
    h.controller.start();
    h.requests[0]?.resolve(points([{ tx: '1', ts: NOW - 1000 }]));
    await settle();
    h.controller.pause();
    vi.advanceTimersByTime(LIVE_TICK_MS);
    // Nothing arrived: no report.
    expect(sizes).toEqual([]);
    h.controller.onPush(push([{ tx: '2', ts: NOW + 1100 }], NOW + 1100));
    h.controller.onPush(push([{ tx: '3', ts: NOW + 1200 }], NOW + 1200));
    vi.advanceTimersByTime(LIVE_TICK_MS);
    // Within one second of the batches, one report with the new count.
    expect(sizes).toEqual([3]);
    vi.advanceTimersByTime(LIVE_TICK_MS);
    expect(sizes).toEqual([3]);
    h.controller.stop();
  });
});

describe('xlog topic parameters (docs/14 §5.2)', () => {
  it('T-UI-001 subscribes with the topic keys only: no error, min_ms or max_ms', () => {
    const filters: Filter[] = [
      { field: 'service', op: 'eq', value: untrusted('17') },
      { field: 'error', op: 'eq', value: untrusted('true') },
      { field: 'elapsed_ms', op: 'gte', value: untrusted('500') },
      { field: 'elapsed_ms', op: 'lt', value: untrusted('3000') },
    ];
    const params = xlogTopicParams(3, 'prod', 'HTTP', toXlogPointFilters(filters));
    expect(params).toEqual({ app: 3, env: 'prod', kind: 'HTTP', service: ['17'], instance: [] });
    expect(Object.keys(params).sort()).toEqual(['app', 'env', 'instance', 'kind', 'service']);
  });
});

/** Answers the client's pings so the manager keeps the connection (a pong is not a data frame). */
function answerPings(socket: FakeSocket, answered: { count: number }): void {
  const pings = socket.framesOf('ping');
  for (const ping of pings.slice(answered.count)) {
    socket.serverSend({ op: 'pong', t: ping.t });
  }
  answered.count = pings.length;
}

function pushFrame(id: unknown, rows: readonly Row[], t: number): Record<string, unknown> {
  return { op: 'push', id, topic: 'xlog', t: new Date(t).toISOString(), data: columns(rows) };
}

function bufferTxIds(buffer: XLogBuffer): string[] {
  const out: string[] = [];
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    out.push(((BigInt(buffer.txIdHi[p] ?? 0) << 32n) | BigInt(buffer.txIdLo[p] ?? 0)).toString());
  }
  return out;
}

describe('useXLogLive (docs/15 §10)', () => {
  beforeEach(() => {
    api.requests.length = 0;
    useConnectionStore.setState(INITIAL_CONNECTION_STATE);
  });

  afterEach(() => {
    setWsManager(null);
  });

  it('T-UI-001 polls the points through REST after 30 s without a realtime message, stops at the next data frame, no duplicate', async () => {
    const sockets = fakeSockets();
    setWsManager(new WsManager({ createSocket: sockets.factory, visibility: null }));
    const buffer = new XLogBuffer(1000);
    const filters = toXlogPointFilters([
      { field: 'error', op: 'eq', value: untrusted('false') },
      { field: 'elapsed_ms', op: 'gte', value: untrusted('50') },
    ]);
    const view = renderHook(() =>
      useXLogLive({ enabled: true, appId: 1, env: undefined, kind: 'HTTP', filters, buffer }),
    );
    // The initial REST load carries every scatter filter; the subscription only the topic's keys.
    expect(api.requests).toHaveLength(1);
    expect(api.requests[0]?.query).toMatchObject({ error: false, min_ms: 50 });
    await act(async () => {
      api.requests[0]?.resolve(points([{ tx: '1', ts: NOW - 1000 }]));
      await settle();
    });
    const socket = sockets.last();
    const pings = { count: 0 };
    act(() => {
      socket.serverOpen();
      socket.ackAll();
    });
    const subscribe = socket.framesOf('subscribe').find((f) => f.topic === 'xlog');
    expect(subscribe?.params).toEqual({ app: 1, kind: 'HTTP', service: [], instance: [] });
    act(() => {
      socket.serverSend(pushFrame(subscribe?.id, [{ tx: '2', ts: NOW }], NOW));
    });
    expect(bufferTxIds(buffer)).toEqual(['1', '2']);

    // 29 s of silence: no polling yet.
    for (let s = 0; s < POLL_AFTER_S - 1; s += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
        answerPings(socket, pings);
      });
    }
    expect(api.requests).toHaveLength(1);
    expect(view.result.current.polling).toBe(false);
    // 30 s: the REST poll of [last received, now] starts at once.
    act(() => {
      vi.advanceTimersByTime(1000);
      answerPings(socket, pings);
    });
    expect(api.requests).toHaveLength(2);
    expect(view.result.current.polling).toBe(true);
    const firstPoll = api.requests[1];
    expect(firstPoll?.query).toMatchObject({
      from: new Date(NOW - FILL_OVERLAP_MS).toISOString(),
      to: new Date(NOW + POLL_AFTER_S * 1000).toISOString(),
      error: false,
      min_ms: 50,
    });
    await act(async () => {
      firstPoll?.resolve(
        points([
          { tx: '2', ts: NOW },
          { tx: '3', ts: NOW + 20_000 },
        ]),
      );
      await settle();
    });
    expect(bufferTxIds(buffer)).toEqual(['1', '2', '3']);
    // Every POLL_INTERVAL_MS while silent.
    act(() => {
      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      answerPings(socket, pings);
    });
    expect(api.requests).toHaveLength(3);
    await act(async () => {
      api.requests[2]?.resolve(
        points([
          { tx: '3', ts: NOW + 20_000 },
          { tx: '4', ts: NOW + 35_000 },
        ]),
      );
      await settle();
    });
    // A data frame ends the silence: polling stops.
    act(() => {
      socket.serverSend(
        pushFrame(
          subscribe?.id,
          [
            { tx: '4', ts: NOW + 35_000 },
            { tx: '5', ts: NOW + 41_000 },
          ],
          NOW + 41_000,
        ),
      );
    });
    expect(view.result.current.polling).toBe(false);
    // Two poll intervals without a poll (a new silence of 30 s would start it again).
    for (let s = 0; s < 2 * (POLL_INTERVAL_MS / 1000); s += 1) {
      act(() => {
        vi.advanceTimersByTime(1000);
        answerPings(socket, pings);
      });
    }
    expect(api.requests).toHaveLength(3);
    // No point twice: the overlaps of polls and pushes were dropped by tx_id.
    expect(bufferTxIds(buffer)).toEqual(['1', '2', '3', '4', '5']);
    expect(view.result.current.stats()).toMatchObject({ polls: 2, duplicates: 3 });
    view.unmount();
  });
});

describe('BoundedIdSet (docs/15 §14: bounded memory)', () => {
  it('forgets the oldest id beyond its capacity', () => {
    const set = new BoundedIdSet(3);
    expect(set.add('a')).toBe(true);
    expect(set.add('a')).toBe(false);
    set.add('b');
    set.add('c');
    set.add('d');
    expect(set.size).toBe(3);
    expect(set.has('a')).toBe(false);
    expect(set.has('d')).toBe(true);
    expect(() => new BoundedIdSet(0)).toThrow(RangeError);
  });
});

describe('xlog push payload (docs/14 §5.2)', () => {
  it('accepts equal-length point columns and refuses anything else', () => {
    expect(parseXLogPush(columns([{ tx: '1', ts: 1 }]))).not.toBeNull();
    expect(parseXLogPush({ ...columns([{ tx: '1', ts: 1 }]), ts: [] })).toBeNull();
    expect(parseXLogPush({ ...columns([{ tx: '1', ts: 1 }]), tx_id: [1] })).toBeNull();
    expect(parseXLogPush({ ...columns([{ tx: '1', ts: 1 }]), service_id: ['-1'] })).toBeNull();
    const many = Array.from({ length: MAX_PUSH_ROWS + 1 }, (_, i) => ({ tx: String(i), ts: i }));
    expect(parseXLogPush(columns(many))).toBeNull();
    expect(parseXLogPush(null)).toBeNull();
  });
});
