// SPDX-License-Identifier: Apache-2.0
//
// T-UI-001: XLog coordinate transforms and selection rectangle calculation.
import { describe, expect, it } from 'vitest';

import {
  autoYMax,
  createScale,
  dataRectToPixels,
  durationUnitFor,
  elapsedAt,
  inDataRect,
  linearTicks,
  logTicks,
  niceCeil,
  normalizeRect,
  pixelRectToData,
  quickSelect,
  timeTicks,
  tsAt,
  xOf,
  yOf,
} from './scale';

const FROM = 1_700_000_000_000;
const TO = FROM + 300_000;

describe('T-UI-001 coordinate transforms', () => {
  const linear = createScale(FROM, TO, 1000, 400, 2000, 'linear');
  const log = createScale(FROM, TO, 1000, 400, 9999, 'log');

  it('maps time linearly onto x: x = (ts - from) / (to - from) × W', () => {
    expect(xOf(linear, FROM)).toBe(0);
    expect(xOf(linear, TO)).toBe(1000);
    expect(xOf(linear, FROM + 75_000)).toBe(250);
  });

  it('maps elapsed linearly onto y (0 ms at the bottom, yMax at the top)', () => {
    expect(yOf(linear, 0)).toBe(400);
    expect(yOf(linear, 2000)).toBe(0);
    expect(yOf(linear, 500)).toBe(300);
  });

  it('maps elapsed onto y with log10(elapsed + 1)', () => {
    expect(yOf(log, 0)).toBe(400);
    expect(yOf(log, 9999)).toBeCloseTo(0, 9);
    // log10(99 + 1) / log10(9999 + 1) = 2 / 4: half way up.
    expect(yOf(log, 99)).toBeCloseTo(200, 9);
    expect(yOf(log, 9)).toBeCloseTo(300, 9);
  });

  it('clamps values above yMax to the top edge and negative values to the bottom', () => {
    expect(yOf(linear, 1_000_000)).toBe(0);
    expect(yOf(log, 1_000_000)).toBe(0);
    expect(yOf(linear, -10)).toBe(400);
  });

  it.each([
    ['linear', linear],
    ['log', log],
  ] as const)('inverts the %s transform (round trip)', (_, scale) => {
    for (const ts of [FROM, FROM + 1234, FROM + 150_000, TO]) {
      expect(tsAt(scale, xOf(scale, ts))).toBeCloseTo(ts, 3);
    }
    for (const e of [0, 1, 37, 500, 1999]) {
      expect(elapsedAt(scale, yOf(scale, e))).toBeCloseTo(e, 6);
    }
  });

  it('guards degenerate ranges and sizes', () => {
    const scale = createScale(FROM, FROM, -5, 0, 0, 'linear');
    expect(scale.to).toBeGreaterThan(scale.from);
    expect(scale.width).toBe(0);
    expect(scale.yMax).toBeGreaterThan(0);
    expect(Number.isFinite(tsAt(scale, 10))).toBe(true);
    expect(Number.isFinite(elapsedAt(scale, 10))).toBe(true);
  });
});

describe('T-UI-001 selection rectangle', () => {
  const scale = createScale(FROM, TO, 1000, 400, 2000, 'linear');

  it('orders the corners of a drag in any direction and clamps it to the plot', () => {
    expect(normalizeRect(800, 300, 200, 100, 1000, 400)).toEqual({
      x1: 200,
      y1: 100,
      x2: 800,
      y2: 300,
    });
    expect(normalizeRect(-50, -20, 1200, 900, 1000, 400)).toEqual({
      x1: 0,
      y1: 0,
      x2: 1000,
      y2: 400,
    });
  });

  it('converts a pixel rectangle to t1 <= ts <= t2 && e1 <= elapsed <= e2', () => {
    const data = pixelRectToData(scale, { x1: 250, y1: 100, x2: 500, y2: 300 });
    expect(data.t1).toBe(FROM + 75_000);
    expect(data.t2).toBe(FROM + 150_000);
    expect(data.e1).toBe(500);
    expect(data.e2).toBe(1500);
    expect(inDataRect(data, FROM + 100_000, 1000)).toBe(true);
    expect(inDataRect(data, FROM + 75_000, 500)).toBe(true);
    expect(inDataRect(data, FROM + 74_999, 1000)).toBe(false);
    expect(inDataRect(data, FROM + 100_000, 1501)).toBe(false);
  });

  it('includes everything above the axis when the rectangle touches the top edge', () => {
    const data = pixelRectToData(scale, { x1: 0, y1: 0, x2: 1000, y2: 400 });
    expect(data.e1).toBe(0);
    expect(data.e2).toBe(Number.POSITIVE_INFINITY);
    expect(inDataRect(data, FROM + 1, 3_600_000)).toBe(true);
  });

  it('projects a stored selection back to pixels, following the time range', () => {
    const data = pixelRectToData(scale, { x1: 250, y1: 0, x2: 500, y2: 300 });
    expect(dataRectToPixels(scale, data)).toEqual({ x1: 250, y1: 0, x2: 500, y2: 300 });
    const later = createScale(FROM + 60_000, TO + 60_000, 1000, 400, 2000, 'linear');
    const moved = dataRectToPixels(later, data);
    expect(moved.x1).toBe(50);
    expect(moved.x2).toBe(300);
  });

  it('selects the same points on a log axis', () => {
    const logScale = createScale(FROM, TO, 1000, 400, 9999, 'log');
    const data = pixelRectToData(logScale, { x1: 0, y1: 200, x2: 1000, y2: 300 });
    expect(data.e1).toBeCloseTo(9, 6);
    expect(data.e2).toBeCloseTo(99, 6);
  });
});

describe('axis helpers', () => {
  it('rounds up to 1, 2, 2.5, 5 × 10^k', () => {
    expect(niceCeil(0.7)).toBe(1);
    expect(niceCeil(1)).toBe(1);
    expect(niceCeil(1.1)).toBe(2);
    expect(niceCeil(2.2)).toBe(2.5);
    expect(niceCeil(3)).toBe(5);
    expect(niceCeil(7)).toBe(10);
    expect(niceCeil(1234)).toBe(2000);
    expect(niceCeil(0)).toBe(1);
  });

  it('sets the automatic y maximum to p99.9 × 1.2 rounded up (docs/15 §7.2)', () => {
    const values = new Float64Array(1000);
    for (let i = 0; i < 1000; i += 1) {
      values[i] = i + 1;
    }
    // p99.9 of 1..1000 is 999 (nearest rank below); × 1.2 = 1198.8 -> 2000.
    expect(autoYMax(values, 1000)).toBe(2000);
    expect(autoYMax(new Float64Array(0), 0)).toBe(1000);
    expect(autoYMax(Float64Array.from([1, 1, 1]), 3)).toBe(10);
  });

  it('quickSelect finds the k-th smallest value', () => {
    const values = Float64Array.from([9, 1, 8, 2, 7, 3, 6, 4, 5, 5]);
    expect(quickSelect(values.slice(), 10, 0)).toBe(1);
    expect(quickSelect(values.slice(), 10, 4)).toBe(5);
    expect(quickSelect(values.slice(), 10, 5)).toBe(5);
    expect(quickSelect(values.slice(), 10, 9)).toBe(9);
  });

  it('builds linear and log ticks', () => {
    expect(linearTicks(2000, 4)).toEqual([0, 500, 1000, 1500, 2000]);
    expect(logTicks(5000)).toEqual([0, 1, 10, 100, 1000]);
  });

  it('builds time ticks aligned to the step and inside the range', () => {
    const ticks = timeTicks(FROM + 1, FROM + 300_000, 6);
    expect(ticks.length).toBeGreaterThan(1);
    expect(ticks.length).toBeLessThanOrEqual(7);
    const step = (ticks[1] ?? 0) - (ticks[0] ?? 0);
    expect(step).toBe(60_000);
    for (const t of ticks) {
      expect(t % step).toBe(0);
      expect(t).toBeGreaterThanOrEqual(FROM + 1);
      expect(t).toBeLessThanOrEqual(FROM + 300_000);
    }
  });

  it('switches the duration unit for long transactions (batch: ms -> s -> min)', () => {
    expect(durationUnitFor(2000)).toBe('ms');
    expect(durationUnitFor(50_000)).toBe('s');
    expect(durationUnitFor(3_600_000)).toBe('min');
  });
});
