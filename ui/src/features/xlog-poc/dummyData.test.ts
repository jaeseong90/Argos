// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { KIND_BATCH, STATUS_ERROR, STATUS_NORMAL, STATUS_SLOW } from '../../xlog/XLogBuffer';

import {
  DUMMY_SLOW_THRESHOLD_MS,
  MAX_ELAPSED_MS,
  XSS_ERROR_CLASS,
  XSS_INSTANCE_NAME,
  XSS_SERVICE_NAMES,
  createDummyDataset,
  createDummyModel,
  txHash,
} from './dummyData';

const FROM = 1_700_000_000_000;
const TO = FROM + 30 * 60_000;

function sortedElapsed(count: number, seed: number): Float64Array {
  const { buffer } = createDummyDataset({ count, seed, from: FROM, to: TO });
  return Float64Array.from(buffer.elapsed.subarray(0, buffer.size)).sort();
}

function quantile(sorted: Float64Array, q: number): number {
  return sorted[Math.min(sorted.length - 1, Math.floor(q * (sorted.length - 1)))] ?? 0;
}

describe('dummy XLog data (WO-04)', () => {
  // ~1.4 s idle (100,000 points, one expect each); 5 s default is too tight on a busy host.
  it('generates 100,000 points in time order inside the range', () => {
    const { buffer, sampling, from, to } = createDummyDataset({
      count: 100_000,
      seed: 7,
      from: FROM,
      to: TO,
    });
    expect(buffer.size).toBe(100_000);
    expect(sampling).toBeNull();
    expect(from).toBe(FROM);
    expect(to).toBe(TO);
    for (let i = 1; i < buffer.size; i += 1) {
      expect(buffer.ts[i]).toBeGreaterThanOrEqual(buffer.ts[i - 1] ?? 0);
    }
    expect(buffer.ts[0]).toBeGreaterThanOrEqual(FROM);
    expect(buffer.ts[buffer.size - 1]).toBeLessThanOrEqual(TO);
  }, 30_000);

  it('has log-normal response times with a long tail', () => {
    const sorted = sortedElapsed(100_000, 11);
    const p50 = quantile(sorted, 0.5);
    const p99 = quantile(sorted, 0.99);
    const max = sorted[sorted.length - 1] ?? 0;
    expect(p50).toBeGreaterThan(10);
    expect(p50).toBeLessThan(300);
    // Long tail: p99 is an order of magnitude above the median, the maximum far above p99.
    expect(p99 / p50).toBeGreaterThan(8);
    expect(max).toBeGreaterThan(p99 * 3);
    expect(max).toBeLessThanOrEqual(MAX_ELAPSED_MS);
    // Mean above median is the log-normal signature (right skew).
    const mean = sorted.reduce((acc, v) => acc + v, 0) / sorted.length;
    expect(mean).toBeGreaterThan(p50);
  });

  it('distributes services by a Zipf law and instances unevenly', () => {
    const { buffer, dictionary } = createDummyDataset({
      count: 100_000,
      seed: 3,
      from: FROM,
      to: TO,
    });
    const services = new Map<number, number>();
    const instances = new Map<number, number>();
    for (let i = 0; i < buffer.size; i += 1) {
      services.set(buffer.service[i] ?? 0, (services.get(buffer.service[i] ?? 0) ?? 0) + 1);
      instances.set(buffer.instance[i] ?? 0, (instances.get(buffer.instance[i] ?? 0) ?? 0) + 1);
    }
    const counts = [...services.values()].sort((a, b) => b - a);
    expect(services.size).toBeGreaterThan(100);
    expect(dictionary.services.length).toBe(200);
    // The top service is far more frequent than the 50th.
    expect((counts[0] ?? 0) / (counts[49] ?? 1)).toBeGreaterThan(20);
    expect(instances.size).toBe(dictionary.instances.length);
    const instanceCounts = [...instances.values()].sort((a, b) => b - a);
    expect((instanceCounts[0] ?? 0) / (instanceCounts[5] ?? 1)).toBeGreaterThan(1.5);
  });

  // ~1.3 s idle (50,000 points, expects per point); 5 s default is too tight on a busy host.
  it('derives the status: errors per service rate, slow above the threshold', () => {
    const { buffer } = createDummyDataset({ count: 50_000, seed: 5, from: FROM, to: TO });
    let errors = 0;
    let slow = 0;
    for (let i = 0; i < buffer.size; i += 1) {
      const status = buffer.status[i];
      if (status === STATUS_ERROR) {
        errors += 1;
        expect(buffer.errorClass[i]).toBeGreaterThan(0);
      } else {
        expect(buffer.errorClass[i]).toBe(0);
      }
      if (status === STATUS_SLOW) {
        slow += 1;
        expect(buffer.elapsed[i]).toBeGreaterThanOrEqual(DUMMY_SLOW_THRESHOLD_MS);
      }
      if (status === STATUS_NORMAL && buffer.kind[i] === 1) {
        expect(buffer.elapsed[i]).toBeLessThan(DUMMY_SLOW_THRESHOLD_MS);
      }
    }
    expect(errors / buffer.size).toBeGreaterThan(0.002);
    expect(errors / buffer.size).toBeLessThan(0.05);
    expect(slow).toBeGreaterThan(0);
  }, 30_000);

  it('keeps batch transactions rare so request latency sets the y axis', () => {
    const { buffer } = createDummyDataset({ count: 100_000, seed: 9, from: FROM, to: TO });
    let batch = 0;
    for (let i = 0; i < buffer.size; i += 1) {
      if (buffer.kind[i] === KIND_BATCH) {
        batch += 1;
        expect(buffer.httpStatus[i]).toBe(0);
      }
    }
    expect(batch).toBeGreaterThan(0);
    expect(batch).toBeLessThan(100);
  });

  it('puts an incident in the range: one service slows down on one instance', () => {
    const { buffer } = createDummyDataset({ count: 100_000, seed: 13, from: FROM, to: TO });
    const span = TO - FROM;
    const inside: number[] = [];
    const outside: number[] = [];
    for (let i = 0; i < buffer.size; i += 1) {
      if (buffer.service[i] !== 0 || buffer.instance[i] !== 2) {
        continue;
      }
      const ts = buffer.ts[i] ?? 0;
      const target = ts >= FROM + 0.55 * span && ts < FROM + 0.62 * span ? inside : outside;
      target.push(buffer.elapsed[i] ?? 0);
    }
    const median = (values: number[]) => [...values].sort((a, b) => a - b)[values.length >> 1] ?? 0;
    expect(inside.length).toBeGreaterThan(50);
    expect(median(inside)).toBeGreaterThan(median(outside) * 3);
  });

  it('is deterministic for a seed', () => {
    const a = createDummyDataset({ count: 2000, seed: 21, from: FROM, to: TO });
    const b = createDummyDataset({ count: 2000, seed: 21, from: FROM, to: TO });
    const c = createDummyDataset({ count: 2000, seed: 22, from: FROM, to: TO });
    expect(Array.from(a.buffer.elapsed)).toEqual(Array.from(b.buffer.elapsed));
    expect(Array.from(a.buffer.txIdLo)).toEqual(Array.from(b.buffer.txIdLo));
    expect(Array.from(a.buffer.elapsed)).not.toEqual(Array.from(c.buffer.elapsed));
  });

  it('T-UI-001 samples deterministically: all slow/error points plus a txId hash share (A-5)', () => {
    const options = { count: 10_000, total: 40_000, seed: 31, from: FROM, to: TO };
    const full = createDummyDataset({ ...options, count: 40_000, total: 40_000 });
    const sampled = createDummyDataset(options);
    const again = createDummyDataset(options);
    expect(sampled.sampling).toEqual({ shown: sampled.buffer.size, total: 40_000 });
    expect(sampled.buffer.size).toBeGreaterThan(8000);
    expect(sampled.buffer.size).toBeLessThan(12_000);
    // Same points after a "reload".
    expect(Array.from(again.buffer.txIdLo)).toEqual(Array.from(sampled.buffer.txIdLo));
    // Every slow/error point of the full data survives the sample.
    const kept = new Set(Array.from(sampled.buffer.txIdLo.subarray(0, sampled.buffer.size)));
    let special = 0;
    for (let i = 0; i < full.buffer.size; i += 1) {
      if (full.buffer.status[i] !== STATUS_NORMAL) {
        special += 1;
        expect(kept.has(full.buffer.txIdLo[i] ?? -1)).toBe(true);
      }
    }
    expect(special).toBeGreaterThan(0);
    expect(txHash(1, 2)).toBe(txHash(1, 2));
    expect(txHash(1, 2)).not.toBe(txHash(2, 1));
  });

  it('mixes XSS payloads into service, instance and error class names (SEC-61)', () => {
    const model = createDummyModel(1);
    for (const name of XSS_SERVICE_NAMES) {
      const rank = model.dictionary.services.indexOf(name);
      expect(rank).toBeGreaterThanOrEqual(0);
      expect(rank).toBeLessThan(13);
    }
    expect(model.dictionary.instances).toContain(XSS_INSTANCE_NAME);
    expect(model.dictionary.errorClasses).toContain(XSS_ERROR_CLASS);
    expect(new Set(model.dictionary.services).size).toBe(model.dictionary.services.length);
  });
});
