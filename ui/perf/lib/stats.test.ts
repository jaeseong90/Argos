// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { checkFrameBudget, distribution, FRAME_BUDGET_MS, percentile } from './stats';

describe('percentile', () => {
  it('uses the nearest rank', () => {
    const values = Array.from({ length: 100 }, (_, i) => i + 1);
    expect(percentile(values, 0.5)).toBe(50);
    expect(percentile(values, 0.95)).toBe(95);
    expect(percentile(values, 1)).toBe(100);
    expect(percentile(values, 0)).toBe(1);
    expect(percentile([7], 0.95)).toBe(7);
    expect(percentile([], 0.95)).toBe(0);
  });
});

describe('distribution', () => {
  it('reports count, mean, p50, p95 and max of unsorted values, ignoring non-finite ones', () => {
    expect(distribution([5, 1, 3, Number.NaN, 2, 4, Number.POSITIVE_INFINITY])).toEqual({
      count: 5,
      mean: 3,
      p50: 3,
      p95: 5,
      max: 5,
    });
    expect(distribution([])).toEqual({ count: 0, mean: 0, p50: 0, p95: 0, max: 0 });
  });
});

describe('checkFrameBudget', () => {
  const frames = (values: number[]) => distribution(values);

  it('passes when p95 and mean are within 16 ms, whatever the maximum', () => {
    const values = [...Array.from({ length: 95 }, () => 10), 16, 16, 16, 16, 40];
    const verdict = checkFrameBudget(frames(values));
    expect(FRAME_BUDGET_MS).toBe(16);
    expect(verdict).toEqual({ pass: true, reasons: [] });
  });

  it('fails when p95 exceeds the budget even with a low median', () => {
    const values = [
      ...Array.from({ length: 90 }, () => 5),
      ...Array.from({ length: 10 }, () => 17),
    ];
    const verdict = checkFrameBudget(frames(values));
    expect(verdict.pass).toBe(false);
    expect(verdict.reasons).toEqual(['p95 17.0 ms > 16 ms']);
  });

  it('fails when the mean exceeds the budget', () => {
    const values = [
      ...Array.from({ length: 96 }, () => 15),
      ...Array.from({ length: 4 }, () => 50),
    ];
    const verdict = checkFrameBudget(frames(values));
    expect(verdict.pass).toBe(false);
    expect(verdict.reasons).toEqual(['mean 16.4 ms > 16 ms']);
  });

  it('fails with too few frames, so an empty measurement never passes', () => {
    expect(checkFrameBudget(frames([]))).toEqual({
      pass: false,
      reasons: ['only 0 frames measured (at least 60)'],
    });
    expect(checkFrameBudget(frames(Array.from({ length: 59 }, () => 1))).pass).toBe(false);
    expect(checkFrameBudget(frames(Array.from({ length: 60 }, () => 1))).pass).toBe(true);
  });
});
