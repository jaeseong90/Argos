// SPDX-License-Identifier: Apache-2.0
//
// Pure chart logic of TimeSeriesChart (docs/15 §6.1, DSN-15-04), kept apart from uPlot so it can be
// tested without a canvas: data alignment and null handling (D-09, D-16), the click menu range
// (05 §2.0-5), marker grouping and emphasis (deep link 4), outage rectangles (C-5, D-18), baseline
// values and the down-sampled rows of the accessible table.

import type { ValueUnit } from '../utils/format';

export type SeriesKind = 'line' | 'area' | 'bar';

/** One series. Values align with the chart's shared `ts` array; null breaks the line. */
export interface ChartSeries {
  readonly key: string;
  /** Translated name, or a collected name (pool, instance): the legend renders it as text. */
  readonly label: string;
  readonly unit: ValueUnit;
  readonly values: readonly (number | null | undefined)[];
  readonly kind?: SeriesKind;
  /** CSS colour; defaults to the series palette (styles/tokens.ts) by position. */
  readonly color?: string;
}

/** Comparison baseline (F-U19): the same series shifted by `offset`, keyed by series key. */
export interface ChartBaseline {
  readonly offset: '1d' | '7d';
  readonly values: Readonly<Record<string, readonly (number | null | undefined)[]>>;
}

/** Event types of docs/14 §4.12 plus alert transitions (docs/15 §6.1). */
export type MarkerType =
  'deploy' | 'start' | 'stop' | 'config' | 'alert' | 'state' | 'apdex' | 'custom';

export interface ChartMarker {
  readonly id: string;
  readonly ts: number;
  readonly type: MarkerType;
  /** Event title (collected string, rendered with SafeText). */
  readonly title?: string | null;
  /** Version of a deploy (collected string). */
  readonly version?: string | null;
  /** Severity of an alert transition: drawn in the severity colour. */
  readonly severity?: 'warn' | 'critical' | null;
}

/** A collection outage (docs/14 `outages[]`, C-5): `to` null means still ongoing. */
export interface ChartOutage {
  readonly from: number;
  readonly to: number | null;
  readonly reason?: string | null;
}

export interface TimeRange {
  readonly from: number;
  readonly to: number;
}

/** Shapes of docs/15 §6.1: deploy ▲, start ●, stop ○, config ◆, alert transition ✱. */
export type MarkerShape = 'triangle' | 'dot' | 'ring' | 'diamond' | 'asterisk' | 'square';

export function markerShape(type: MarkerType): MarkerShape {
  switch (type) {
    case 'deploy':
      return 'triangle';
    case 'start':
      return 'dot';
    case 'stop':
      return 'ring';
    case 'config':
      return 'diamond';
    case 'alert':
    case 'apdex':
      return 'asterisk';
    case 'state':
    case 'custom':
      return 'square';
  }
}

/** A translucent fill of a series colour: `#rrggbb` gets an alpha byte, other notations stay. */
export function withAlpha(color: string, alpha: number): string {
  if (!/^#[0-9a-f]{6}$/i.test(color)) {
    return color;
  }
  const byte = Math.round(Math.min(1, Math.max(0, alpha)) * 255);
  return `${color}${byte.toString(16).padStart(2, '0')}`;
}

/** Normalises chart values for uPlot: undefined, NaN and ±Infinity become null (a gap, never 0). */
export function toChartValues(
  values: readonly (number | null | undefined)[],
  length: number,
): (number | null)[] {
  const result: (number | null)[] = new Array<number | null>(length);
  for (let i = 0; i < length; i += 1) {
    const value = values[i];
    result[i] = typeof value === 'number' && Number.isFinite(value) ? value : null;
  }
  return result;
}

/**
 * The window of the click menu "Open XLog at this time" (05 §2.0-5): the point time ± range/60,
 * at least ± 1 minute.
 */
export function clickMenuRange(ts: number, range: TimeRange): TimeRange {
  const half = Math.max((range.to - range.from) / 60, 60_000);
  return { from: Math.round(ts - half), to: Math.round(ts + half) };
}

/** Index of the timestamp nearest to `x` in an ascending array, or -1 when empty. */
export function nearestIndex(ts: readonly number[], x: number): number {
  if (ts.length === 0) {
    return -1;
  }
  let lo = 0;
  let hi = ts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if ((ts[mid] ?? 0) < x) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  const after = ts[lo] ?? 0;
  const before = ts[lo - 1];
  return before !== undefined && x - before <= after - x ? lo - 1 : lo;
}

const SEVERITY_RANK = { critical: 2, warn: 1 } as const;

export interface MarkerGroup {
  /** Horizontal position in CSS px inside the plot area. */
  readonly left: number;
  /** The marker drawn for the group: the emphasised one, else the most severe, else the first. */
  readonly lead: ChartMarker;
  readonly markers: readonly ChartMarker[];
  /** True when the group holds the marker of the `alert=` deep-link key. */
  readonly emphasized: boolean;
}

/**
 * Groups markers that would overlap (closer than `minGapPx`) into one marker with a "+N" badge
 * (docs/15 §6.1). Markers outside [0, width] are dropped. The marker whose id equals
 * `emphasizedId` (deep link `alert=`, docs/15 §4.3 item 4) always leads its group.
 */
export function groupMarkers(
  markers: readonly ChartMarker[],
  toPx: (ts: number) => number,
  width: number,
  emphasizedId?: string | null,
  minGapPx = 12,
): MarkerGroup[] {
  const placed = markers
    .map((marker) => ({ marker, left: toPx(marker.ts) }))
    .filter(({ left }) => Number.isFinite(left) && left >= 0 && left <= width)
    .sort((a, b) => a.left - b.left || a.marker.ts - b.marker.ts);
  const groups: { left: number; markers: ChartMarker[] }[] = [];
  for (const { marker, left } of placed) {
    const last = groups[groups.length - 1];
    if (last !== undefined && left - last.left < minGapPx) {
      last.markers.push(marker);
    } else {
      groups.push({ left, markers: [marker] });
    }
  }
  return groups.map(({ left, markers: members }) => {
    const emphasized =
      emphasizedId !== undefined && emphasizedId !== null
        ? members.find((marker) => marker.id === emphasizedId)
        : undefined;
    const severe = [...members].sort(
      (a, b) =>
        (b.severity === undefined || b.severity === null ? 0 : SEVERITY_RANK[b.severity]) -
        (a.severity === undefined || a.severity === null ? 0 : SEVERITY_RANK[a.severity]),
    )[0];
    const lead = emphasized ?? severe ?? members[0];
    if (lead === undefined) {
      throw new Error('empty marker group');
    }
    return { left, lead, markers: members, emphasized: emphasized !== undefined };
  });
}

export interface OutageRect {
  readonly left: number;
  readonly width: number;
  readonly outage: ChartOutage;
}

/**
 * Rectangles of collection outages inside the plot (C-5, D-18): clipped to the visible range, an
 * ongoing outage (`to` null) extends to the end of the range, outages outside the range and empty
 * ones are dropped. A rectangle is at least 2 px wide so a short outage stays visible.
 */
export function outageRects(
  outages: readonly ChartOutage[],
  range: TimeRange,
  toPx: (ts: number) => number,
): OutageRect[] {
  const rects: OutageRect[] = [];
  for (const outage of outages) {
    const end = outage.to ?? range.to;
    const from = Math.max(outage.from, range.from);
    const to = Math.min(end, range.to);
    if (!(to > from)) {
      continue;
    }
    const left = toPx(from);
    const right = toPx(to);
    if (!Number.isFinite(left) || !Number.isFinite(right)) {
      continue;
    }
    rects.push({ left, width: Math.max(2, right - left), outage });
  }
  return rects;
}

/** Relative difference of a value to its baseline (0.1 = 10 % above), null when not comparable. */
export function baselineDifference(
  value: number | null | undefined,
  baseline: number | null | undefined,
): number | null {
  if (
    value === null ||
    value === undefined ||
    baseline === null ||
    baseline === undefined ||
    !Number.isFinite(value) ||
    !Number.isFinite(baseline) ||
    baseline === 0
  ) {
    return null;
  }
  return (value - baseline) / Math.abs(baseline);
}

/** At most this many rows in the accessible data table (docs/15 §6.1). */
export const A11Y_TABLE_MAX_ROWS = 100;

/**
 * Indices of the rows shown in the accessible table: all rows when there are at most `max`,
 * otherwise `max` evenly spaced rows including the first and the last.
 */
export function downsampleIndices(length: number, max = A11Y_TABLE_MAX_ROWS): number[] {
  if (length <= 0) {
    return [];
  }
  if (length <= max) {
    return Array.from({ length }, (_, index) => index);
  }
  const step = (length - 1) / (max - 1);
  const indices: number[] = [];
  for (let i = 0; i < max; i += 1) {
    const index = Math.round(i * step);
    if (indices[indices.length - 1] !== index) {
      indices.push(index);
    }
  }
  return indices;
}
