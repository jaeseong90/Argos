// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import {
  LIST_LIMIT,
  TOP_SERVICES,
  matchesFilter,
  slowestPoints,
  summarizeSelection,
} from './selectionSummary';
import {
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  XLogBuffer,
  type XLogDictionary,
  type XLogPoint,
} from './XLogBuffer';

function p(
  service: number,
  elapsed: number,
  status: number,
  extra: Partial<XLogPoint> = {},
): XLogPoint {
  return {
    ts: 1000 + elapsed,
    elapsed,
    status,
    instance: 0,
    service,
    txIdLo: elapsed,
    txIdHi: service,
    kind: 1,
    httpStatus: status === STATUS_ERROR ? 500 : 200,
    errorClass: 0,
    ...extra,
  };
}

function bufferOf(points: XLogPoint[]): XLogBuffer {
  const buffer = new XLogBuffer(Math.max(1, points.length));
  buffer.append(points);
  return buffer;
}

const dictionary: XLogDictionary = {
  services: Array.from({ length: 15 }, (_, i) => `service-${String(i)}`),
  instances: ['api-1', 'api-2', 'api-3'],
  errorClasses: ['java.sql.SQLTimeoutException', 'java.lang.NullPointerException'],
};

describe('summarizeSelection (docs/15 §7.4, docs/05 §2.1)', () => {
  it('counts the selected points by status', () => {
    const summary = summarizeSelection(
      bufferOf([p(0, 10, STATUS_NORMAL), p(0, 2000, STATUS_SLOW), p(1, 30, STATUS_ERROR)]),
      dictionary,
    );
    expect(summary.count).toBe(3);
    expect(summary.status).toEqual({ normal: 1, slow: 1, error: 1 });
    expect(summary.sampled).toBe(false);
  });

  it('lists the top 10 services by count with average, maximum and errors', () => {
    const points: XLogPoint[] = [];
    // Service s has 15 - s points: service 0 is the most frequent.
    for (let s = 0; s < 15; s += 1) {
      for (let k = 0; k < 15 - s; k += 1) {
        points.push(p(s, 100 * (k + 1), k === 0 ? STATUS_ERROR : STATUS_NORMAL));
      }
    }
    const summary = summarizeSelection(bufferOf(points), dictionary);
    expect(TOP_SERVICES).toBe(10);
    expect(summary.services).toHaveLength(10);
    expect(summary.serviceCount).toBe(15);
    expect(summary.services.map((row) => row.service)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
    const top = summary.services[0];
    expect(top).toEqual({
      service: 0,
      name: 'service-0',
      count: 15,
      // 100, 200, …, 1500 -> average 800
      avgElapsed: 800,
      maxElapsed: 1500,
      errorCount: 1,
    });
    expect(summary.services[9]).toMatchObject({ service: 9, count: 6, maxElapsed: 600 });
  });

  it('breaks count ties by dictionary index, so the order is stable', () => {
    const summary = summarizeSelection(bufferOf([p(3, 1, 0), p(1, 1, 0), p(2, 1, 0)]), dictionary);
    expect(summary.services.map((row) => row.service)).toEqual([1, 2, 3]);
  });

  it('distributes by instance, HTTP status and error class', () => {
    const summary = summarizeSelection(
      bufferOf([
        p(0, 1, STATUS_ERROR, { instance: 2, errorClass: 1 }),
        p(0, 2, STATUS_ERROR, { instance: 2, errorClass: 2 }),
        p(0, 3, STATUS_ERROR, { instance: 1, errorClass: 1 }),
        p(0, 4, STATUS_NORMAL, { instance: 0, httpStatus: 0, kind: 2 }),
      ]),
      dictionary,
    );
    expect(summary.instances).toEqual([
      { instance: 2, name: 'api-3', count: 2, errorCount: 2 },
      { instance: 0, name: 'api-1', count: 1, errorCount: 0 },
      { instance: 1, name: 'api-2', count: 1, errorCount: 1 },
    ]);
    expect(summary.httpStatus).toEqual([
      { code: 500, count: 3 },
      { code: 0, count: 1 },
    ]);
    expect(summary.errorClasses).toEqual([
      { errorClass: 1, name: 'java.sql.SQLTimeoutException', count: 2 },
      { errorClass: 2, name: 'java.lang.NullPointerException', count: 1 },
    ]);
  });

  it('reports error classes as unavailable when the points carry none (docs/15 §18 #5)', () => {
    const summary = summarizeSelection(bufferOf([p(0, 1, STATUS_ERROR)]), {
      ...dictionary,
      errorClasses: null,
    });
    expect(summary.errorClasses).toBeNull();
  });

  it('pools indices outside the dictionary as unknown instead of failing', () => {
    const summary = summarizeSelection(bufferOf([p(99, 1, 0, { instance: 42 })]), dictionary);
    expect(summary.services[0]).toMatchObject({ service: -1, name: null, count: 1 });
    expect(summary.instances[0]).toMatchObject({ instance: -1, name: null, count: 1 });
  });

  it('marks a summary of sampled data (docs/15 §7.4 "표시된 점 기준")', () => {
    expect(summarizeSelection(bufferOf([p(0, 1, 0)]), dictionary, { sampled: true }).sampled).toBe(
      true,
    );
  });

  it('summarises an empty selection', () => {
    const summary = summarizeSelection(new XLogBuffer(1), dictionary);
    expect(summary).toMatchObject({ count: 0, services: [], instances: [], httpStatus: [] });
    expect(summary.errorClasses).toEqual([]);
  });
});

describe('slowestPoints (selected-points list)', () => {
  it('lists points by response time, slowest first, with resolved names', () => {
    const list = slowestPoints(
      bufferOf([p(0, 5, 0), p(1, 50, 2, { errorClass: 2 }), p(2, 20, 1)]),
      dictionary,
      null,
    );
    expect(list.rows.map((row) => row.elapsed)).toEqual([50, 20, 5]);
    expect(list.rows[0]).toMatchObject({
      serviceName: 'service-1',
      instanceName: 'api-1',
      errorClassName: 'java.lang.NullPointerException',
      txId: '0000000100000032',
    });
    expect(list).toMatchObject({ matching: 3, truncated: false });
  });

  it('keeps the slowest 1,000 of a large selection and says it truncated', () => {
    const points = Array.from({ length: 5000 }, (_, i) => p(i % 15, (i * 7919) % 5000, 0));
    const list = slowestPoints(bufferOf(points), dictionary, null);
    expect(LIST_LIMIT).toBe(1000);
    expect(list.rows).toHaveLength(1000);
    expect(list).toMatchObject({ matching: 5000, truncated: true });
    const elapsed = list.rows.map((row) => row.elapsed);
    expect(elapsed[0]).toBe(4999);
    expect(elapsed.at(-1)).toBe(4000);
    expect([...elapsed].sort((a, b) => b - a)).toEqual(elapsed);
  });

  it('applies a summary-row filter (docs/05 §2.1 "요약 행 클릭 → 목록 필터")', () => {
    const buffer = bufferOf([
      p(0, 10, 0, { instance: 1 }),
      p(1, 20, 2, { instance: 1 }),
      p(1, 30, 0, { instance: 2 }),
    ]);
    expect(slowestPoints(buffer, dictionary, { field: 'service', value: 1 }).matching).toBe(2);
    expect(slowestPoints(buffer, dictionary, { field: 'instance', value: 1 }).matching).toBe(2);
    expect(slowestPoints(buffer, dictionary, { field: 'status', value: 2 }).matching).toBe(1);
    expect(slowestPoints(buffer, dictionary, { field: 'httpStatus', value: 500 }).matching).toBe(1);
    expect(slowestPoints(buffer, dictionary, { field: 'errorClass', value: 1 }).matching).toBe(0);
    expect(matchesFilter(buffer, 0, null)).toBe(true);
  });

  it('returns an empty list for limit 0 but still counts the matches', () => {
    const list = slowestPoints(bufferOf([p(0, 1, 0), p(0, 2, 0)]), dictionary, null, 0);
    expect(list).toMatchObject({ rows: [], matching: 2, truncated: true });
  });
});
