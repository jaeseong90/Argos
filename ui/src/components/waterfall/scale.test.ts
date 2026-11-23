// SPDX-License-Identifier: Apache-2.0
//
// Waterfall time-axis maths (docs/15 §8.4, DSN-15-11): tick steps and the ms / s / min unit switch,
// bar geometry (left = start / total %, width = elapsed / total %), clipping that is reported
// instead of hidden, the timeline length that covers every bar.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }
import { describe, expect, it } from 'vitest';

import {
  barGeometry,
  formatTick,
  niceStep,
  shareOf,
  timelineTotal,
  timeScale,
  unitFor,
} from './scale';

describe('waterfall scale ticks (docs/15 §8.4 "상단 눈금(0 ~ total ms)")', () => {
  it('niceStep rounds up to 1-2-5 times a power of ten', () => {
    expect(niceStep(0.3)).toBeCloseTo(0.5);
    expect(niceStep(1)).toBe(1);
    expect(niceStep(1.01)).toBe(2);
    expect(niceStep(3)).toBe(5);
    expect(niceStep(7)).toBe(10);
    expect(niceStep(166.7)).toBe(200);
    expect(niceStep(0)).toBe(1);
    expect(niceStep(Number.NaN)).toBe(1);
  });

  it('switches the unit with the length: ms below 1 s, s below 2 min, min from 2 min', () => {
    expect(unitFor(0)).toBe('ms');
    expect(unitFor(999)).toBe('ms');
    expect(unitFor(1000)).toBe('s');
    expect(unitFor(119_999)).toBe('s');
    expect(unitFor(120_000)).toBe('min');
  });

  it('places ticks from 0 to the total at their share of the track', () => {
    const model = timeScale(1832);
    expect(model.unit).toBe('s');
    expect(model.stepMs).toBe(500);
    expect(model.ticks.map((t) => t.ms)).toEqual([0, 500, 1000, 1500]);
    expect(model.ticks[0]?.leftPct).toBe(0);
    expect(model.ticks[1]?.leftPct).toBeCloseTo((500 / 1832) * 100, 10);
    for (const tick of model.ticks) {
      expect(tick.leftPct).toBeGreaterThanOrEqual(0);
      expect(tick.leftPct).toBeLessThanOrEqual(100);
    }
  });

  it('includes the total itself when it is a multiple of the step, and never exceeds maxTicks', () => {
    expect(timeScale(1000).ticks.map((t) => t.ms)).toEqual([0, 200, 400, 600, 800, 1000]);
    expect(timeScale(1000).ticks.at(-1)?.leftPct).toBe(100);
    for (const total of [1, 7, 99, 999, 1001, 4321, 59_999, 120_000, 3_600_000, 86_400_000]) {
      const model = timeScale(total);
      expect(model.ticks.length - 1, String(total)).toBeLessThanOrEqual(6);
      expect(model.ticks.length, String(total)).toBeGreaterThanOrEqual(1);
      expect(model.ticks.at(-1)?.ms ?? 0, String(total)).toBeLessThanOrEqual(total);
    }
  });

  it('uses whole milliseconds and clock-like minute steps', () => {
    expect(timeScale(3).stepMs).toBe(1);
    expect(timeScale(3).ticks.map((t) => t.ms)).toEqual([0, 1, 2, 3]);
    const twoHours = timeScale(2 * 3_600_000);
    expect(twoHours.unit).toBe('min');
    expect(twoHours.stepMs).toBe(30 * 60_000);
    const tenDays = timeScale(10 * 86_400_000);
    expect(tenDays.stepMs % 3_600_000).toBe(0);
  });

  it('treats a 0 ms (or invalid) total as a 1 ms scale', () => {
    expect(timeScale(0)).toMatchObject({ totalMs: 1, unit: 'ms' });
    expect(timeScale(-5).totalMs).toBe(1);
    expect(timeScale(Number.NaN).totalMs).toBe(1);
  });

  it('labels ticks in the unit with the locale number format', () => {
    expect(formatTick(250, 'ms', 'en')).toBe('250 ms');
    expect(formatTick(1500, 's', 'en')).toBe('1.5 s');
    expect(formatTick(90_000, 'min', 'en')).toBe('1.5 min');
    expect(formatTick(1_250_000, 'ms', 'ko')).toBe('1,250,000 ms');
  });
});

describe('waterfall bar geometry (docs/15 §8.4 left = start/total%, width = elapsed/total%)', () => {
  it('maps start and elapsed to percentages of the total', () => {
    expect(barGeometry(0, 1832, 1832)).toEqual({ leftPct: 0, widthPct: 100, clipped: false });
    const sql = barGeometry(1203, 410, 1832);
    expect(sql.leftPct).toBeCloseTo((1203 / 1832) * 100, 10);
    expect(sql.widthPct).toBeCloseTo((410 / 1832) * 100, 10);
    expect(sql.clipped).toBe(false);
  });

  it('gives a 0 ms step a 0 % width (the row draws MIN_BAR_WIDTH_PX)', () => {
    expect(barGeometry(500, 0, 1000)).toEqual({ leftPct: 50, widthPct: 0, clipped: false });
    expect(barGeometry(1000, 0, 1000)).toEqual({ leftPct: 100, widthPct: 0, clipped: false });
  });

  it('cuts an interval that leaves the scale and says so instead of hiding it', () => {
    expect(barGeometry(900, 300, 1000)).toEqual({ leftPct: 90, widthPct: 10, clipped: true });
    expect(barGeometry(-50, 100, 1000)).toEqual({ leftPct: 0, widthPct: 5, clipped: true });
    expect(barGeometry(2000, 10, 1000)).toEqual({ leftPct: 100, widthPct: 0, clipped: true });
  });

  it('never divides by zero', () => {
    expect(barGeometry(0, 0, 0)).toEqual({ leftPct: 0, widthPct: 0, clipped: false });
    expect(barGeometry(0, 1, 0)).toEqual({ leftPct: 0, widthPct: 100, clipped: false });
  });

  it('timelineTotal covers the root and every interval (asynchronous steps may end later)', () => {
    expect(timelineTotal(100, [{ startMs: 10, elapsedMs: 20 }])).toBe(100);
    expect(timelineTotal(100, [{ startMs: 90, elapsedMs: 40 }])).toBe(130);
    expect(timelineTotal(0, [])).toBe(1);
    expect(timelineTotal(Number.NaN, [{ startMs: 0, elapsedMs: 5 }])).toBe(5);
  });

  it('shareOf is the clamped ratio for tooltips', () => {
    expect(shareOf(25, 100)).toBe(0.25);
    expect(shareOf(150, 100)).toBe(1);
    expect(shareOf(5, 0)).toBe(0);
  });
});
