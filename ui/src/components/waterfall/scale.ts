// SPDX-License-Identifier: Apache-2.0
//
// Time-axis maths of the transaction timeline and the trace view (docs/15 §8.4, DSN-15-11): the
// ticks of the 0..total ms scale with a unit that fits the length (ms below one second, s below two
// minutes, min above), and the geometry of a bar (left = start / total %, width = elapsed / total %,
// drawn at least MIN_BAR_WIDTH_PX wide so that a 0 ms step stays visible). Pure functions; the
// components (TimeScale, WaterfallRow) only place the results with CSS percentages.
import { formatNumber } from '../../utils/format';

/** Row height of the waterfall lists in px (docs/15 §8.4: DOM virtual list, 24 px rows). */
export const WATERFALL_ROW_HEIGHT = 24;
/** Narrowest bar in px (docs/15 §8.4 "최소 2px"). */
export const MIN_BAR_WIDTH_PX = 2;
/** Default number of ticks the scale aims at (it never shows more than this plus one). */
export const DEFAULT_MAX_TICKS = 6;

export type TimeUnit = 'ms' | 's' | 'min';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;

/** Tick steps per unit: 1-2-5 in the unit, minutes in steps a clock reader expects. */
const STEPS: Readonly<Record<TimeUnit, readonly number[]>> = {
  ms: [1, 2, 5, 10, 20, 50, 100, 200, 500],
  s: [
    100,
    200,
    500,
    SECOND,
    2 * SECOND,
    5 * SECOND,
    10 * SECOND,
    15 * SECOND,
    20 * SECOND,
    30 * SECOND,
  ],
  min: [
    30 * SECOND,
    MINUTE,
    2 * MINUTE,
    5 * MINUTE,
    10 * MINUTE,
    15 * MINUTE,
    30 * MINUTE,
    HOUR,
    2 * HOUR,
    3 * HOUR,
    6 * HOUR,
    12 * HOUR,
    24 * HOUR,
  ],
};

/** Divisor of a unit (ms per unit). */
export const UNIT_MS: Readonly<Record<TimeUnit, number>> = { ms: 1, s: SECOND, min: MINUTE };

/** The unit of a scale of this length: ms below 1 s, s below 2 min, min from 2 min. */
export function unitFor(totalMs: number): TimeUnit {
  if (totalMs < SECOND) {
    return 'ms';
  }
  return totalMs < 2 * MINUTE ? 's' : 'min';
}

/** The smallest 1-2-5 × 10^n value that is at least `raw` (raw > 0). */
export function niceStep(raw: number): number {
  if (!Number.isFinite(raw) || raw <= 0) {
    return 1;
  }
  const base = 10 ** Math.floor(Math.log10(raw));
  for (const multiple of [1, 2, 5, 10]) {
    if (multiple * base >= raw * (1 - 1e-9)) {
      return multiple * base;
    }
  }
  return 10 * base;
}

export interface ScaleTick {
  /** Offset from the start in ms. */
  readonly ms: number;
  /** Position on the track in percent (0..100). */
  readonly leftPct: number;
}

export interface TimeScaleModel {
  /** The scale's length in ms (at least 1). */
  readonly totalMs: number;
  readonly stepMs: number;
  readonly unit: TimeUnit;
  /** Ticks at 0, step, 2·step, … up to the total (inclusive). */
  readonly ticks: readonly ScaleTick[];
}

/**
 * The ticks of a 0..totalMs scale: a step from the unit's list (or a 1-2-5 multiple of hours
 * beyond it) so that there are at most `maxTicks` intervals. A total of 0 ms (or less, or not a
 * number) is a 1 ms scale, so that every bar still has a position.
 */
export function timeScale(totalMs: number, maxTicks: number = DEFAULT_MAX_TICKS): TimeScaleModel {
  const total = Number.isFinite(totalMs) && totalMs > 0 ? totalMs : 1;
  const intervals = Math.max(1, Math.floor(maxTicks));
  const unit = unitFor(total);
  const raw = total / intervals;
  const candidates = STEPS[unit];
  const stepMs =
    candidates.find((step) => step >= raw) ??
    (unit === 'min' ? niceStep(raw / HOUR) * HOUR : niceStep(raw));
  const ticks: ScaleTick[] = [];
  // Integer multiples of the step, so that floating point never adds or loses a tick.
  const count = Math.floor(total / stepMs + 1e-9);
  for (let i = 0; i <= count; i += 1) {
    const ms = i * stepMs;
    ticks.push({ ms, leftPct: (ms / total) * 100 });
  }
  return { totalMs: total, stepMs, unit, ticks };
}

/** A tick label in the scale's unit: `250 ms`, `1.5 s`, `2 min` (unit symbols are not translated). */
export function formatTick(ms: number, unit: TimeUnit, locale: string): string {
  return `${formatNumber(ms / UNIT_MS[unit], locale, 2)} ${unit}`;
}

export interface BarGeometry {
  /** Left edge in percent of the track (0..100). */
  readonly leftPct: number;
  /** Width in percent of the track (0..100 − left); the row draws at least MIN_BAR_WIDTH_PX. */
  readonly widthPct: number;
  /**
   * True when the interval did not fit the scale (starts before 0 or ends after the total) and was
   * cut to it: the row marks the cut edge instead of hiding it (docs/15 §1 "hide nothing").
   */
  readonly clipped: boolean;
}

/**
 * The position of an interval on a 0..totalMs track: left = start / total %, width = elapsed /
 * total %, both clamped to the track. The caller chooses totalMs to cover every bar
 * (timelineTotal), so clipping only happens for inconsistent data.
 */
export function barGeometry(startMs: number, elapsedMs: number, totalMs: number): BarGeometry {
  const total = Number.isFinite(totalMs) && totalMs > 0 ? totalMs : 1;
  const rawStart = Number.isFinite(startMs) ? startMs : 0;
  const rawElapsed = Number.isFinite(elapsedMs) && elapsedMs > 0 ? elapsedMs : 0;
  const start = Math.min(Math.max(rawStart, 0), total);
  const end = Math.min(Math.max(rawStart + rawElapsed, start), total);
  const clipped = rawStart < 0 || rawStart + rawElapsed > total;
  return {
    leftPct: (start / total) * 100,
    widthPct: ((end - start) / total) * 100,
    clipped,
  };
}

/** An interval relative to the timeline start. */
export interface TimeInterval {
  readonly startMs: number;
  readonly elapsedMs: number;
}

/**
 * The length a timeline must show: the root's elapsed time or the latest end of any interval,
 * whichever is longer (an asynchronous step may end after its transaction), at least 1 ms.
 */
export function timelineTotal(rootElapsedMs: number, intervals: Iterable<TimeInterval>): number {
  let total = Number.isFinite(rootElapsedMs) && rootElapsedMs > 0 ? rootElapsedMs : 0;
  for (const interval of intervals) {
    const end = interval.startMs + Math.max(0, interval.elapsedMs);
    if (Number.isFinite(end) && end > total) {
      total = end;
    }
  }
  return Math.max(1, total);
}

/** The share of the scale an interval takes, 0..1 (for tooltips: "12% of the transaction"). */
export function shareOf(elapsedMs: number, totalMs: number): number {
  if (!Number.isFinite(elapsedMs) || !Number.isFinite(totalMs) || totalMs <= 0) {
    return 0;
  }
  return Math.min(1, Math.max(0, elapsedMs / totalMs));
}
