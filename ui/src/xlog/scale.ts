// SPDX-License-Identifier: Apache-2.0
//
// Coordinate transforms of the XLog plot (docs/15 §7.2, T-UI-001). All coordinates are CSS pixels
// relative to the plot area: x grows with time, y grows downwards (0 = top = yMax).

export type YScaleType = 'linear' | 'log';

export interface PlotScale {
  /** Time range, epoch ms (from < to). */
  readonly from: number;
  readonly to: number;
  /** Plot area size in CSS px. */
  readonly width: number;
  readonly height: number;
  /** Upper bound of the y axis in ms. Larger values are drawn on the top edge. */
  readonly yMax: number;
  readonly yType: YScaleType;
}

/** A rectangle in plot pixels, normalised so that x1 <= x2 and y1 (top) <= y2 (bottom). */
export interface PixelRect {
  readonly x1: number;
  readonly y1: number;
  readonly x2: number;
  readonly y2: number;
}

/**
 * A selection in data space: `t1 <= ts <= t2 && e1 <= elapsed <= e2` (docs/15 §7.3). `e2` is
 * Infinity when the rectangle reaches the top edge, so points clamped to the edge are included.
 */
export interface DataRect {
  readonly t1: number;
  readonly t2: number;
  readonly e1: number;
  readonly e2: number;
}

export function createScale(
  from: number,
  to: number,
  width: number,
  height: number,
  yMax: number,
  yType: YScaleType,
): PlotScale {
  return {
    from,
    to: to > from ? to : from + 1,
    width: Math.max(0, width),
    height: Math.max(0, height),
    yMax: yMax > 0 ? yMax : 1,
    yType,
  };
}

/** Normalised height (0 = bottom, 1 = top) of `elapsed`, clamped to [0, 1]. */
export function yFraction(scale: PlotScale, elapsed: number): number {
  const e = elapsed > 0 ? elapsed : 0;
  const v = scale.yType === 'log' ? Math.log10(e + 1) / Math.log10(scale.yMax + 1) : e / scale.yMax;
  return v > 1 ? 1 : v;
}

export function xOf(scale: PlotScale, ts: number): number {
  return ((ts - scale.from) / (scale.to - scale.from)) * scale.width;
}

export function yOf(scale: PlotScale, elapsed: number): number {
  return scale.height - yFraction(scale, elapsed) * scale.height;
}

/** Inverse of `xOf`. */
export function tsAt(scale: PlotScale, x: number): number {
  return scale.from + (scale.width > 0 ? x / scale.width : 0) * (scale.to - scale.from);
}

/** Inverse of `yOf` for 0 <= y <= height. */
export function elapsedAt(scale: PlotScale, y: number): number {
  const v = scale.height > 0 ? (scale.height - y) / scale.height : 0;
  const f = v < 0 ? 0 : v > 1 ? 1 : v;
  return scale.yType === 'log' ? Math.pow(10, f * Math.log10(scale.yMax + 1)) - 1 : f * scale.yMax;
}

/** Orders and clamps the drag rectangle from (ax, ay) to (bx, by) to the plot area. */
export function normalizeRect(
  ax: number,
  ay: number,
  bx: number,
  by: number,
  width: number,
  height: number,
): PixelRect {
  const cx = (v: number): number => (v < 0 ? 0 : v > width ? width : v);
  const cy = (v: number): number => (v < 0 ? 0 : v > height ? height : v);
  return {
    x1: cx(Math.min(ax, bx)),
    x2: cx(Math.max(ax, bx)),
    y1: cy(Math.min(ay, by)),
    y2: cy(Math.max(ay, by)),
  };
}

/**
 * Converts a plot rectangle to its data-space selection. A rectangle touching the top edge
 * selects everything above the axis maximum, one touching the bottom edge starts at 0 ms.
 */
export function pixelRectToData(scale: PlotScale, rect: PixelRect): DataRect {
  return {
    t1: tsAt(scale, rect.x1),
    t2: tsAt(scale, rect.x2),
    e1: rect.y2 >= scale.height ? 0 : elapsedAt(scale, rect.y2),
    e2: rect.y1 <= 0 ? Number.POSITIVE_INFINITY : elapsedAt(scale, rect.y1),
  };
}

/** Projects a stored data-space selection back onto the plot (it moves with the time range). */
export function dataRectToPixels(scale: PlotScale, rect: DataRect): PixelRect {
  return {
    x1: xOf(scale, rect.t1),
    x2: xOf(scale, rect.t2),
    y1: rect.e2 === Number.POSITIVE_INFINITY ? 0 : yOf(scale, rect.e2),
    y2: yOf(scale, rect.e1),
  };
}

/** True when the point lies inside the data-space selection (inclusive bounds). */
export function inDataRect(rect: DataRect, ts: number, elapsed: number): boolean {
  return ts >= rect.t1 && ts <= rect.t2 && elapsed >= rect.e1 && elapsed <= rect.e2;
}

const NICE_STEPS = [1, 2, 2.5, 5, 10];

/** Smallest "nice" number (1, 2, 2.5, 5 × 10^k) that is >= value. */
export function niceCeil(value: number): number {
  if (!(value > 0)) {
    return 1;
  }
  const exponent = Math.floor(Math.log10(value));
  const base = Math.pow(10, exponent);
  for (const step of NICE_STEPS) {
    const candidate = step * base;
    if (candidate >= value * (1 - 1e-12)) {
      return candidate;
    }
  }
  return 10 * base;
}

/** Minimum automatic y maximum, so an idle app does not zoom into sub-millisecond noise. */
export const MIN_AUTO_Y_MAX = 10;

/** y maximum when there is no data. */
export const DEFAULT_Y_MAX = 1000;

/**
 * Automatic y maximum (docs/15 §7.2): p99.9 of the elapsed values × 1.2, rounded up to a nice
 * number. `values[0..count)` holds the elapsed values; the array is reordered.
 */
export function autoYMax(values: Float64Array, count: number, quantileLevel = 0.999): number {
  if (count <= 0) {
    return DEFAULT_Y_MAX;
  }
  const k = Math.min(count - 1, Math.floor(quantileLevel * (count - 1)));
  const q = quickSelect(values, count, k);
  return Math.max(MIN_AUTO_Y_MAX, niceCeil(q * 1.2));
}

/** k-th smallest of a[0..count) (0-based), reordering `a` in place. Average O(n). */
export function quickSelect(a: Float64Array, count: number, k: number): number {
  let lo = 0;
  let hi = count - 1;
  while (hi > lo) {
    const mid = (lo + hi) >>> 1;
    const pivot = median3(a[lo] ?? 0, a[mid] ?? 0, a[hi] ?? 0);
    let i = lo;
    let j = hi;
    while (i <= j) {
      while ((a[i] ?? 0) < pivot) {
        i += 1;
      }
      while ((a[j] ?? 0) > pivot) {
        j -= 1;
      }
      if (i <= j) {
        const tmp = a[i] ?? 0;
        a[i] = a[j] ?? 0;
        a[j] = tmp;
        i += 1;
        j -= 1;
      }
    }
    if (k <= j) {
      hi = j;
    } else if (k >= i) {
      lo = i;
    } else {
      break;
    }
  }
  return a[k] ?? 0;
}

function median3(a: number, b: number, c: number): number {
  if (a < b) {
    return b < c ? b : a < c ? c : a;
  }
  return a < c ? a : b < c ? c : b;
}

/** Linear axis ticks from 0 to max (inclusive when it falls on a step), about `count` of them. */
export function linearTicks(max: number, count = 5): number[] {
  if (!(max > 0)) {
    return [0];
  }
  const step = niceCeil(max / Math.max(1, count));
  const ticks: number[] = [];
  for (let v = 0; v <= max * (1 + 1e-9) && ticks.length < 50; v += step) {
    ticks.push(Math.round(v * 1e6) / 1e6);
  }
  return ticks;
}

/** Log axis ticks: 0 and the powers of ten up to max. */
export function logTicks(max: number): number[] {
  const ticks = [0];
  for (let v = 1; v <= max * (1 + 1e-9) && ticks.length < 20; v *= 10) {
    ticks.push(v);
  }
  return ticks;
}

const TIME_STEPS_MS = [
  1_000, 2_000, 5_000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000,
  1_800_000, 3_600_000, 7_200_000, 10_800_000, 21_600_000, 43_200_000, 86_400_000,
];

/** Time axis step (ms) that gives at most about `count` ticks over the range. */
export function timeTickStep(from: number, to: number, count = 6): number {
  const span = to - from;
  for (const step of TIME_STEPS_MS) {
    if (span / step <= count) {
      return step;
    }
  }
  return TIME_STEPS_MS[TIME_STEPS_MS.length - 1] ?? 86_400_000;
}

/**
 * Time axis ticks: multiples of the step inside [from, to]. Aligned to local-time boundaries for
 * steps up to one hour through `offsetMs` (the browser's UTC offset at `from`, in ms).
 */
export function timeTicks(from: number, to: number, count = 6, offsetMs = 0): number[] {
  const step = timeTickStep(from, to, count);
  const first = Math.ceil((from + offsetMs) / step) * step - offsetMs;
  const ticks: number[] = [];
  for (let t = first; t <= to && ticks.length < 100; t += step) {
    ticks.push(t);
  }
  return ticks;
}

/** Display unit of durations on the y axis (docs/15 §7.2 "배치 단위"). */
export type DurationUnit = 'ms' | 's' | 'min';

/** ms below 10 s, seconds below 10 min, minutes above: batch transactions switch automatically. */
export function durationUnitFor(maxMs: number): DurationUnit {
  if (maxMs >= 600_000) {
    return 'min';
  }
  return maxMs >= 10_000 ? 's' : 'ms';
}

export function toUnit(ms: number, unit: DurationUnit): number {
  if (unit === 'min') {
    return ms / 60_000;
  }
  return unit === 's' ? ms / 1000 : ms;
}
