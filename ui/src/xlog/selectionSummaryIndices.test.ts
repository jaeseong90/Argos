// SPDX-License-Identifier: Apache-2.0
//
// summarizeIndices (ADR-0003 C-36, WO-33 review fe-perf-1): the summary of a selection's physical
// indices read in place equals the summary of the snapshot copy (XLogBuffer.extract), also on a
// ring buffer that has wrapped and evicted, with indices outside the dictionaries.
import { describe, expect, it } from 'vitest';

import { summarizeIndices, summarizeSelection } from './selectionSummary';
import {
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  XLogBuffer,
  type XLogDictionary,
  type XLogPoint,
} from './XLogBuffer';

/** Deterministic pseudo-random sequence (no Math.random in tests). */
function lcg(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state / 0x1_0000_0000;
  };
}

const dictionary: XLogDictionary = {
  services: Array.from({ length: 14 }, (_, i) => `service-${String(i)}`),
  instances: ['api-1', 'api-2', 'api-3', 'api-4'],
  errorClasses: ['java.sql.SQLTimeoutException', 'java.lang.NullPointerException'],
};

function randomPoints(n: number, seed: number, t0: number): XLogPoint[] {
  const next = lcg(seed);
  return Array.from({ length: n }, (_, i) => {
    const r = next();
    const status = r < 0.1 ? STATUS_ERROR : r < 0.25 ? STATUS_SLOW : STATUS_NORMAL;
    return {
      ts: t0 + i * 10,
      elapsed: Math.floor(next() * 30_000),
      status,
      // Some indices fall outside the dictionaries (pooled as "unknown").
      instance: Math.floor(next() * 5),
      service: Math.floor(next() * 16),
      txIdLo: i,
      txIdHi: seed,
      kind: 1,
      httpStatus: [0, 200, 404, 500, 503][Math.floor(next() * 5)] ?? 0,
      errorClass: status === STATUS_ERROR ? 1 + Math.floor(next() * 3) : 0,
    };
  });
}

/** Physical indices of every `step`-th logical point. */
function everyNth(buffer: XLogBuffer, step: number): Uint32Array {
  const out: number[] = [];
  for (let logical = 0; logical < buffer.size; logical += step) {
    out.push(buffer.physical(logical));
  }
  return Uint32Array.from(out);
}

describe('summarizeIndices (ADR-0003 C-36: the summary without the snapshot copy)', () => {
  it('equals summarizeSelection of the extracted points, on a wrapped and evicted ring buffer', () => {
    const buffer = new XLogBuffer(5_000);
    buffer.append(randomPoints(4_000, 7, 1_000_000));
    // Wrap the ring and evict the oldest points: physical order differs from logical order.
    buffer.append(randomPoints(3_000, 11, 1_100_000));
    buffer.evictBefore(1_000_000 + 4_000 * 10);
    expect(buffer.size).toBeGreaterThan(0);
    expect(buffer.physical(0)).not.toBe(0);
    for (const step of [1, 3, 17]) {
      const indices = everyNth(buffer, step);
      for (const options of [{}, { sampled: true }, { topServices: 4 }]) {
        expect(summarizeIndices(buffer, indices, dictionary, options)).toEqual(
          summarizeSelection(buffer.extract(indices), dictionary, options),
        );
      }
    }
  });

  it('summarises an empty selection and a dictionary without error classes like the copy', () => {
    const buffer = new XLogBuffer(100);
    buffer.append(randomPoints(50, 3, 0));
    const none = new Uint32Array(0);
    expect(summarizeIndices(buffer, none, dictionary)).toEqual(
      summarizeSelection(buffer.extract(none), dictionary),
    );
    const noErrors: XLogDictionary = { ...dictionary, errorClasses: null };
    const all = everyNth(buffer, 1);
    const summary = summarizeIndices(buffer, all, noErrors);
    expect(summary).toEqual(summarizeSelection(buffer.extract(all), noErrors));
    expect(summary.count).toBe(50);
    expect(summary.errorClasses).toBeNull();
  });
});
