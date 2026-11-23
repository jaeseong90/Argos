// SPDX-License-Identifier: Apache-2.0
//
// Instance API helpers (docs/14 §4.10, WO-37): series names go to the metrics call in chunks of at
// most 50 (the server limit), the chunk answers merge on one grid in request order, a bucket a
// chunk lacks stays null (D-09, never 0), and the events call asks for the instance screen's types.
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../test/apiStub';
import { grid, metricName, metricsBody, series } from '../features/instances/testing/fixtures';

import {
  chunkNames,
  getMetricSeries,
  listInstanceEvents,
  mergeMetricChunks,
  METRIC_NAMES_PER_CALL,
  type InstanceMetrics,
} from './instances';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U08' };
export const REQ_MISSING = { type: 'req', description: 'D-23' };

const FROM = Date.parse('2026-11-20T11:00:00.000Z');
const TO = Date.parse('2026-11-20T12:00:00.000Z');
const METRICS = 'GET /api/v1/instances/7/metrics';

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

function meta(extra: Record<string, unknown> = {}) {
  return { request_id: 'r', generated_at: '2026-11-20T12:00:00.000Z', ...extra };
}

describe('chunkNames', () => {
  it('splits 120 names into chunks of at most 50, in order, without duplicates', () => {
    const names = Array.from({ length: 120 }, (_, i) => `m_${String(i)}`);
    const chunks = chunkNames([...names, 'm_0', 'm_5']);
    expect(METRIC_NAMES_PER_CALL).toBe(50);
    expect(chunks.map((c) => c.length)).toEqual([50, 50, 20]);
    expect(chunks.flat()).toEqual(names);
    expect(chunkNames([])).toEqual([]);
  });
});

describe('mergeMetricChunks', () => {
  it('keeps the request order and the first grid', () => {
    const ts = grid(FROM, 3);
    const a = metricName('jvm_threads_live');
    const b = metricName('jvm_threads_daemon');
    const merged = mergeMetricChunks([
      { data: metricsBody(ts, [series(a, ts, [1, 2, 3])]), meta: meta() },
      { data: metricsBody(ts, [series(b, ts, [4, null, 6])]), meta: meta({ truncated: true }) },
    ]);
    expect(merged?.data.ts).toEqual(ts);
    expect(merged?.data.series.map((s) => s.name)).toEqual([a.name, b.name]);
    expect(merged?.data.series[1]?.value).toEqual([4, null, 6]);
    expect(merged?.meta.truncated).toBe(true);
  });

  it('D-23 aligns a chunk on another grid by bucket start; missing buckets stay null, not 0', () => {
    const ts = grid(FROM, 3);
    const shifted = grid(FROM + 60_000, 2);
    const a = metricName('jvm_threads_live');
    const b = metricName('jvm_threads_peak');
    const merged = mergeMetricChunks([
      { data: metricsBody(ts, [series(a, ts, [1, 2, 3])]), meta: meta() },
      { data: metricsBody(shifted, [series(b, shifted, [7, 8])]), meta: meta() },
    ]);
    expect(merged?.data.series[1]?.value).toEqual([null, 7, 8]);
    expect(merged?.data.series[1]?.ts).toEqual(ts);
    expect(merged?.meta.truncated).toBeUndefined();
  });

  it('answers null for no chunk', () => {
    expect(mergeMetricChunks([])).toBeNull();
  });
});

describe('getMetricSeries', () => {
  it('asks 120 names in three calls of at most 50 names with the same range and merges them', async () => {
    const ts = grid(FROM, 2);
    stub.on(METRICS, (request) => {
      const names = request.query.getAll('names');
      const body: InstanceMetrics = metricsBody(
        ts,
        names.map((name) => series(metricName(name), ts, [1, 2])),
      );
      return envelope(body, { source: 'raw' });
    });
    const names = Array.from({ length: 120 }, (_, i) => `argos_test_${String(i)}`);
    const answer = await getMetricSeries(7, names, { from: FROM, to: TO });
    const calls = stub.calls(METRICS);
    expect(calls).toHaveLength(3);
    for (const call of calls) {
      expect(call.query.getAll('names').length).toBeLessThanOrEqual(50);
      expect(call.query.get('from')).toBe(new Date(FROM).toISOString());
      expect(call.query.get('to')).toBe(new Date(TO).toISOString());
    }
    expect(calls.flatMap((c) => c.query.getAll('names'))).toEqual(names);
    expect(answer?.data.series.map((s) => s.name)).toEqual(names);
    expect(answer?.meta.source).toBe('raw');
  });

  it('sends no request without names', async () => {
    const answer = await getMetricSeries(7, [], { from: FROM, to: TO });
    expect(answer).toBeNull();
    expect(stub.calls(METRICS)).toHaveLength(0);
  });
});

describe('listInstanceEvents', () => {
  it('asks the deploy, start, stop and config events of the range', async () => {
    stub.on('GET /api/v1/apps/1/events', () => envelope([]));
    await listInstanceEvents(1, { from: FROM, to: TO });
    const call = stub.calls('GET /api/v1/apps/1/events')[0];
    expect(call?.query.getAll('type')).toEqual(['deploy', 'start', 'stop', 'config']);
    expect(call?.query.get('from')).toBe(new Date(FROM).toISOString());
  });
});
