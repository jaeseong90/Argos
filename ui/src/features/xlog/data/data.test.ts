// SPDX-License-Identifier: Apache-2.0
//
// XLog screen data layer (WO-33): 64-bit tx_id strings into the buffer's Uint32 halves without
// precision loss (C-175, never Number()), API columns straight into the TypedArrays with local
// dictionary indices (docs/15 §7.1, §7.4), URL selection <-> canvas rectangle (inclusive whole-ms
// bounds, the same as the server search), request parameters of the points API and the topic,
// events and outages as overlay marks, and the static load's commit order (the canvas never draws
// new points with the old range or dictionary).
import { act, render } from '@testing-library/react';
import { createElement } from 'react';
import { I18nextProvider } from 'react-i18next';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout } from '../../../../test/canvasMock';
import { toXlogPointFilters } from '../../../api/transactionSearch';
import { untrusted } from '../../../api/untrusted';
import { createI18n } from '../../../i18n';
import type { Filter } from '../../../state/urlState';
import { XLogEngine, type EngineProps } from '../../../xlog/engine';
import type { FrameSample } from '../../../xlog/perf';
import { XLogBuffer } from '../../../xlog/XLogBuffer';
import { XLogCanvas } from '../../../xlog/XLogCanvas';

import { appendPointColumns, type XLogPointColumns } from './columns';
import { LocalDictionary } from './dictionary';
import { eventMarkers, metaOutages } from './marks';
import { rectFromSelection, SEL_UNBOUNDED_MS, selectionFromRect } from './selection';
import { formatU64, parseU64, parseU64Into, U64_MAX_DECIMAL } from './u64';
import { useXLogPoints } from './useXLogPoints';
import type { AppEvent } from './xlogApi';
import {
  requestKey,
  xlogRequest,
  xlogTopicParams,
  XLOG_POINT_LIMIT,
  type XLogRequestQuery,
} from './xlogQuery';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

/** The points API of the load-order test: calls kept, answered by the test. */
const api = vi.hoisted(() => ({
  requests: [] as { resolve(value: unknown): void }[],
}));
vi.mock('./xlogApi', async (importOriginal) => ({
  ...(await importOriginal<Record<string, unknown>>()),
  fetchXLogPoints: () =>
    new Promise((resolve) => {
      api.requests.push({ resolve });
    }),
}));

/** A deterministic pseudo-random generator (xorshift32) for the 64-bit round trips. */
function random(seed: number): () => number {
  let x = seed >>> 0 || 1;
  return () => {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return x;
  };
}

describe('64-bit ids (ADR-0010 C-175)', () => {
  it('T-UI-001 parses decimal tx_ids into exact halves (checked against BigInt)', () => {
    const next = random(42);
    const hi = new Uint32Array(1);
    const lo = new Uint32Array(1);
    const samples = ['0', '1', '4294967295', '4294967296', '9007199254740993', U64_MAX_DECIMAL];
    for (let i = 0; i < 2000; i += 1) {
      samples.push(((BigInt(next()) << 32n) | BigInt(next())).toString());
    }
    // 2026 tx_ids are 18 digits (C-175 (3)): the precision a Number would lose.
    samples.push('733415239876543210', '733415239876543211');
    for (const text of samples) {
      expect(parseU64Into(text, hi, lo, 0), text).toBe(true);
      const value = (BigInt(hi[0] ?? 0) << 32n) | BigInt(lo[0] ?? 0);
      expect(value.toString(), text).toBe(BigInt(text).toString());
      expect(formatU64(hi[0] ?? 0, lo[0] ?? 0)).toBe(BigInt(text).toString());
    }
    expect(parseU64('733415239876543210')).not.toEqual(parseU64('733415239876543211'));
  });

  it('refuses what is not an unsigned 64-bit decimal', () => {
    for (const text of [
      '',
      '-1',
      '12a',
      '1.5',
      ' 1',
      '18446744073709551616',
      '123456789012345678901',
    ]) {
      expect(parseU64(text), text).toBeNull();
    }
  });
});

const COLUMNS: XLogPointColumns = {
  tx_id: ['733415239876543210', '733415239876543211', 'bad', '733415239876543212'],
  ts: [1000, 2000, 3000, 4000],
  elapsed_ms: [10, 2000, 30, 400],
  status: [0, 1, 0, 2],
  instance_id: [12, 13, 12, 14],
  service_id: ['18446744073709551615', '17', '17', '18446744073709551615'],
  kind: [1, 1, 1, 2],
};

describe('API columns into the buffer (docs/15 §7.1)', () => {
  it('T-UI-001 copies the columns with local indices and exact tx_id halves', () => {
    const buffer = new XLogBuffer(10);
    const dictionary = new LocalDictionary();
    dictionary.nameServices({
      '18446744073709551615': untrusted('GET /a'),
      '17': untrusted('GET /b'),
    });
    dictionary.nameInstances({ '12': untrusted('web-01'), '13': untrusted('web-02') });
    const stats = appendPointColumns(buffer, dictionary, COLUMNS);
    expect(stats).toMatchObject({ rows: 4, appended: 3, skipped: 0, invalid: 1 });
    expect(buffer.size).toBe(3);
    expect([...buffer.ts.subarray(0, 3)]).toEqual([1000, 2000, 4000]);
    expect([...buffer.elapsed.subarray(0, 3)]).toEqual([10, 2000, 400]);
    expect([...buffer.status.subarray(0, 3)]).toEqual([0, 1, 2]);
    expect([...buffer.kind.subarray(0, 3)]).toEqual([1, 1, 2]);
    // Local indices resolve back to the keys and names.
    const services = [...buffer.service.subarray(0, 3)];
    expect(services.map((i) => dictionary.serviceKey(i))).toEqual([
      '18446744073709551615',
      '17',
      '18446744073709551615',
    ]);
    expect(services.map((i) => dictionary.serviceName(i))).toEqual(['GET /a', 'GET /b', 'GET /a']);
    expect([...buffer.instance.subarray(0, 3)].map((i) => dictionary.instanceId(i))).toEqual([
      12, 13, 14,
    ]);
    // An instance without a name stays unnamed ("Unknown (#n)" in the UI), never invented.
    expect(dictionary.instanceName(dictionary.findInstance(14))).toBeNull();
    expect(formatU64(buffer.txIdHi[2] ?? 0, buffer.txIdLo[2] ?? 0)).toBe('733415239876543212');
    // No HTTP status or error class column in the XLog API (ADR-0010 C-202).
    expect([...buffer.httpStatus.subarray(0, 3)]).toEqual([0, 0, 0]);
    expect([...buffer.errorClass.subarray(0, 3)]).toEqual([0, 0, 0]);
    // Named first, in the dictionary's key order (integer-like keys first in JavaScript objects).
    expect(dictionary.serviceIds).toEqual(['17', '18446744073709551615']);
    expect(dictionary.instanceIds).toEqual([12, 13, 14]);
  });

  it('skips the rows `accept` refuses (realtime de-duplication) and cuts uneven columns', () => {
    const buffer = new XLogBuffer(10);
    const dictionary = new LocalDictionary();
    const seen = new Set(['733415239876543211']);
    const stats = appendPointColumns(
      buffer,
      dictionary,
      { ...COLUMNS, kind: [1, 1, 1] },
      { accept: (txId) => !seen.has(txId) },
    );
    expect(stats).toMatchObject({ rows: 3, appended: 1, skipped: 1, invalid: 1 });
    expect(formatU64(buffer.txIdHi[0] ?? 0, buffer.txIdLo[0] ?? 0)).toBe('733415239876543210');
  });

  it('extends one dictionary in place and keeps every earlier index', () => {
    const dictionary = new LocalDictionary();
    const a = dictionary.serviceIndex('1');
    const version = dictionary.version;
    dictionary.nameServices({ '2': 'second', '1': 'first' });
    expect(dictionary.serviceIndex('1')).toBe(a);
    expect(dictionary.services).toEqual(['first', 'second']);
    expect(dictionary.version).toBeGreaterThan(version);
    const services = dictionary.services;
    dictionary.nameServices({ '3': 'third' });
    // The same array object for the life of the dictionary (the canvas keeps its ranking).
    expect(dictionary.services).toBe(services);
    expect(dictionary.findService('4')).toBe(-1);
    dictionary.nameInstances({ x: 'not an id', '0': 'zero' });
    expect(dictionary.instanceCount).toBe(0);
  });
});

describe('URL selection <-> canvas rectangle (docs/14 §6 `sel`)', () => {
  it('T-UI-001 rounds a drag outwards to whole milliseconds, inclusive on both ends', () => {
    expect(selectionFromRect({ t1: 1000.4, t2: 2999.2, e1: 10.7, e2: 99.1 })).toEqual({
      tsFrom: 1000,
      tsTo: 3000,
      msMin: 10,
      msMax: 100,
    });
    // A drag to the top edge selects everything above: no upper bound.
    expect(selectionFromRect({ t1: 5000, t2: 6000, e1: 0, e2: Infinity })).toEqual({
      tsFrom: 5000,
      tsTo: 6000,
      msMin: 0,
      msMax: SEL_UNBOUNDED_MS,
    });
    // A click-wide band still has tsFrom < tsTo (URL schema).
    expect(selectionFromRect({ t1: 7000, t2: 7000, e1: 5, e2: 5 })).toEqual({
      tsFrom: 7000,
      tsTo: 7001,
      msMin: 5,
      msMax: 5,
    });
    expect(selectionFromRect({ t1: Number.NaN, t2: 1, e1: 0, e2: 1 })).toBeNull();
  });

  it('T-UI-001 restores the canvas rectangle from the URL (deep link 5)', () => {
    expect(rectFromSelection({ tsFrom: 1000, tsTo: 3000, msMin: 10, msMax: 100 })).toEqual({
      t1: 1000,
      t2: 3000,
      e1: 10,
      e2: 100,
    });
    expect(
      rectFromSelection({ tsFrom: 1000, tsTo: 3000, msMin: 0, msMax: SEL_UNBOUNDED_MS }).e2,
    ).toBe(Number.POSITIVE_INFINITY);
    const sel = selectionFromRect({ t1: 1234.5, t2: 5678.9, e1: 1.5, e2: 9.5 });
    expect(sel).not.toBeNull();
    if (sel !== null) {
      expect(selectionFromRect(rectFromSelection(sel))).toEqual(sel);
    }
  });
});

function filters(...items: [string, Filter['op'], string][]): Filter[] {
  return items.map(([field, op, value]) => ({ field, op, value: untrusted(value) }));
}

describe('points request and topic parameters (docs/14 §4.5, §5.2)', () => {
  const range = { from: Date.UTC(2026, 10, 15, 0, 0), to: Date.UTC(2026, 10, 15, 0, 15) };

  it('T-UI-001 maps kind and the scatter filters (service[], instance[], error, elapsed range)', () => {
    const point = toXlogPointFilters(
      filters(
        ['service', 'eq', '18446744073709551615'],
        ['instance', 'eq', '12'],
        ['instance', 'eq', '13'],
        ['error', 'eq', 'true'],
        ['elapsed_ms', 'gte', '500'],
        ['elapsed_ms', 'lt', '3000'],
        ['status', 'eq', '500'],
      ),
    );
    expect(xlogRequest(range, 'BATCH', point)).toEqual({
      from: '2026-11-15T00:00:00.000Z',
      to: '2026-11-15T00:15:00.000Z',
      limit: XLOG_POINT_LIMIT,
      kind: 'BATCH',
      service: ['18446744073709551615'],
      instance: [12, 13],
      error: true,
      min_ms: 500,
      max_ms: 2999,
    });
    // HTTP status is not a points filter: listed, not sent (ADR-0010 C-202).
    expect(point.unapplied.map((u) => [u.filter.field, u.reason])).toEqual([
      ['status', 'not_supported_here'],
    ]);
    // The topic takes only its docs/14 §5.2 keys; error and the response time range are applied
    // to the batches on the client (live/XLogLiveController.ts livePointAccepts).
    expect(xlogTopicParams(3, 'prod', 'BATCH', point)).toEqual({
      app: 3,
      env: 'prod',
      kind: 'BATCH',
      service: ['18446744073709551615'],
      instance: [12, 13],
    });
  });

  it('sends only what is set; the colour basis never changes the request', () => {
    const none = toXlogPointFilters([]);
    expect(xlogRequest(range, 'HTTP', none)).toEqual({
      from: '2026-11-15T00:00:00.000Z',
      to: '2026-11-15T00:15:00.000Z',
      limit: XLOG_POINT_LIMIT,
    });
    expect(xlogTopicParams(3, undefined, 'HTTP', none)).toEqual({
      app: 3,
      kind: 'HTTP',
      service: [],
      instance: [],
    });
    expect(requestKey(3, xlogRequest(range, 'HTTP', none))).toBe(
      requestKey(3, xlogRequest(range, 'HTTP', none)),
    );
    expect(requestKey(3, xlogRequest(range, 'HTTP', none))).not.toBe(
      requestKey(4, xlogRequest(range, 'HTTP', none)),
    );
  });
});

describe('overlay marks from the API (docs/14 §4.12, §2.5 outages)', () => {
  it('T-UI-001 turns events into markers and keeps collected titles as they are', () => {
    const events: AppEvent[] = [
      {
        event_id: 'e1',
        ts: '2026-11-15T00:05:00.000Z',
        type: 'deploy',
        title: untrusted('<b>v2</b>'),
        version: untrusted('2.0.0'),
        detail: null,
        detail_truncated: false,
        instance_id: null,
        actor: null,
      },
      {
        event_id: 'e2',
        ts: 'not a time',
        type: 'start',
        title: null,
        version: null,
        detail: null,
        detail_truncated: false,
        instance_id: 12,
        actor: null,
      },
      {
        event_id: 'e3',
        ts: '2026-11-15T00:06:00.000Z',
        type: 'something-new',
        title: null,
        version: null,
        detail: null,
        detail_truncated: false,
        instance_id: null,
        actor: null,
      },
    ];
    expect(eventMarkers(events)).toEqual([
      {
        id: 'e1',
        ts: Date.UTC(2026, 10, 15, 0, 5),
        type: 'deploy',
        title: '<b>v2</b>',
        version: '2.0.0',
        severity: null,
      },
      {
        id: 'e3',
        ts: Date.UTC(2026, 10, 15, 0, 6),
        type: 'custom',
        title: null,
        version: null,
        severity: null,
      },
    ]);
  });

  it('T-UI-001 reads meta.outages, an open outage as ongoing (C-5)', () => {
    expect(
      metaOutages({
        request_id: 'r',
        generated_at: '2026-11-15T00:00:00Z',
        outages: [
          { from: '2026-11-15T00:01:00Z', to: '2026-11-15T00:02:00Z', reason: 'server_down' },
          { from: '2026-11-15T00:10:00Z', reason: 'instance_down' },
          { from: 'bad', reason: 'x' },
        ],
      }),
    ).toEqual([
      {
        from: Date.UTC(2026, 10, 15, 0, 1),
        to: Date.UTC(2026, 10, 15, 0, 2),
        reason: 'server_down',
      },
      { from: Date.UTC(2026, 10, 15, 0, 10), to: null, reason: 'instance_down' },
    ]);
    expect(metaOutages(null)).toEqual([]);
  });
});

/** A points response of `txIds` (all in `range`, services by key), with names. */
function response(
  txIds: readonly string[],
  services: readonly string[],
  names: Record<string, string>,
  range: { from: number; to: number },
) {
  const step = (range.to - range.from) / (txIds.length + 1);
  return {
    data: {
      tx_id: [...txIds],
      ts: txIds.map((_, i) => range.from + step * (i + 1)),
      elapsed_ms: txIds.map(() => 100),
      status: txIds.map(() => 0),
      instance_id: txIds.map(() => 1),
      service_id: [...services],
      kind: txIds.map(() => 1),
      dictionary: { service: names, instance: { '1': 'web-01' } },
    },
    meta: { request_id: 'r', generated_at: '2026-11-15T00:00:00Z', shown: txIds.length },
  };
}

interface DrawnFrame {
  readonly from: number;
  readonly to: number;
  readonly services: readonly (string | null)[];
  /** First tx_id in the buffer when the frame was drawn. */
  readonly firstTx: string;
  /** Legend entries of the colour mapping the engine drew with. */
  readonly legend: number;
}

describe('static load commit order (useXLogPoints, docs/15 §7.1)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    api.requests.length = 0;
  });

  it('T-UI-001 draws the first frame after a load with the new range and dictionary, never new points with the old ones', async () => {
    installCanvasMock();
    mockLayout(1000, 400);
    // Animation frames run only when the test says so (between the response and React's commit).
    const queued = new Map<number, FrameRequestCallback>();
    let nextFrame = 0;
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      nextFrame += 1;
      queued.set(nextFrame, callback);
      return nextFrame;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((handle) => {
      queued.delete(handle);
    });
    const flushFrames = (): void => {
      const callbacks = [...queued.values()];
      queued.clear();
      for (const callback of callbacks) {
        callback(performance.now());
      }
    };
    const update = vi.spyOn(XLogEngine.prototype, 'update');
    const buffer = new XLogBuffer(100);
    // The earliest moment a frame can follow a buffer change: right after the code that changed
    // it (subscribed before the canvas, so this runs before React's own reaction to the change).
    // The canvas must already have the props of the points it holds by then.
    const unsubscribe = buffer.subscribe(() => {
      queueMicrotask(flushFrames);
    });
    const frames: DrawnFrame[] = [];
    const rangeA = { from: Date.UTC(2026, 10, 15, 1, 0), to: Date.UTC(2026, 10, 15, 1, 15) };
    const rangeB = { from: Date.UTC(2026, 10, 15, 2, 0), to: Date.UTC(2026, 10, 15, 2, 30) };
    const queryOf = (range: { from: number; to: number }): XLogRequestQuery =>
      xlogRequest(range, 'HTTP', toXlogPointFilters([]));
    const queryA = queryOf(rangeA);
    const queryB = queryOf(rangeB);

    // The XLog screen's wiring: the hook's dictionary and range are the canvas props. Every render
    // gives the canvas a new onFrame closure, so a frame reports the props it was drawn with.
    function Harness({ query }: { query: XLogRequestQuery }) {
      const data = useXLogPoints({ appId: 1, query, buffer });
      if (data.range === null) {
        return null;
      }
      const { from, to } = data.range;
      const services = data.dictionary.services;
      const onFrame = (sample: FrameSample): void => {
        if (!sample.full) {
          return;
        }
        const props: EngineProps | undefined = update.mock.lastCall?.[0];
        frames.push({
          from,
          to,
          services: [...services],
          firstTx: buffer.size === 0 ? '' : formatU64(buffer.txIdHi[0] ?? 0, buffer.txIdLo[0] ?? 0),
          legend: props?.mapping.legend.length ?? -1,
        });
      };
      return createElement(XLogCanvas, {
        buffer,
        dictionary: data.dictionary,
        from,
        to,
        colorBy: 'service',
        height: 400,
        onFrame,
      });
    }
    const i18n = createI18n('en');
    const tree = (query: XLogRequestQuery) =>
      createElement(I18nextProvider, { i18n }, createElement(Harness, { query }));

    const view = render(tree(queryA));
    await act(async () => {
      api.requests[0]?.resolve(response(['11', '12'], ['1', '1'], { '1': 'GET /a' }, rangeA));
      await Promise.resolve();
    });
    act(() => {
      flushFrames();
    });
    expect(frames.at(-1)).toMatchObject({ from: rangeA.from, to: rangeA.to, firstTx: '11' });

    // A new range: the old points stay until the response arrives.
    view.rerender(tree(queryB));
    expect(api.requests).toHaveLength(2);
    const before = frames.length;
    // The response is handled as in a browser: React renders on its own scheduler (a task after
    // the response), and an animation frame can come in between. Outside act() for that phase.
    const environment = globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean };
    const actEnvironment = environment.IS_REACT_ACT_ENVIRONMENT;
    environment.IS_REACT_ACT_ENVIRONMENT = false;
    try {
      api.requests[1]?.resolve(
        response(
          ['21', '22', '23', '24'],
          ['5', '5', '5', '6'],
          { '5': 'GET /x', '6': 'GET /y' },
          rangeB,
        ),
      );
      // The response has been handled; React has not committed it yet: an animation frame now
      // must not draw the new points with the old range (the copy waits for the commit).
      for (let i = 0; i < 5; i += 1) {
        await Promise.resolve();
      }
      flushFrames();
      // React's own render and commit, then the next animation frame.
      for (let i = 0; i < 50 && buffer.size !== 4; i += 1) {
        await new Promise((resolve) => setTimeout(resolve, 1));
      }
      expect(buffer.size).toBe(4);
      flushFrames();
    } finally {
      environment.IS_REACT_ACT_ENVIRONMENT = actEnvironment;
    }
    const after = frames.slice(before);
    const withB = after.filter((frame) => frame.firstTx === '21');
    expect(withB.length, 'a full frame after the load').toBeGreaterThan(0);
    for (const frame of after) {
      if (frame.firstTx === '21') {
        // New points: the new range, the new dictionary, a ranking of the new points (2 services).
        expect(frame).toMatchObject({ from: rangeB.from, to: rangeB.to, legend: 2 });
        expect(frame.services).toEqual(['GET /x', 'GET /y']);
      } else {
        // Old points: still the old range and dictionary.
        expect(frame).toMatchObject({ from: rangeA.from, to: rangeA.to, firstTx: '11' });
        expect(frame.services).toEqual(['GET /a']);
      }
    }
    unsubscribe();
    view.unmount();
  });
});
