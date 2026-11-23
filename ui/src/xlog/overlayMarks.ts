// SPDX-License-Identifier: Apache-2.0
//
// Geometry of the XLog overlay marks (docs/15 §7.2 "마커·음영" row): collection outage shading
// (docs/14 `meta.outages`, C-5, D-18) as hatched grey bands, event markers (docs/15 §6.1 shapes,
// grouped when they would overlap, the `alert=` event emphasised, docs/15 §4.3 item 4) and the
// keyboard selection band (WO-04 C-12). Everything here is pure: the engine draws the result on
// the overlay canvas only, so the point layer is never redrawn for marks (docs/15 §7.2 오프스크린).
// Marks carry numbers only; event titles and versions (collected strings) are rendered by React as
// text nodes (SEC-60).
import {
  groupMarkers,
  markerShape,
  outageRects,
  type ChartMarker,
  type ChartOutage,
  type MarkerShape,
} from '../charts/chartModel';
import { colorTokens } from '../styles/tokens';

import { xOf, type PlotScale } from './scale';

export type { ChartMarker as XLogMarker, ChartOutage as XLogOutage, MarkerShape };

/** Markers closer than this (CSS px) are drawn as one group with a count (docs/15 §6.1 "+3"). */
export const MARKER_GROUP_PX = 12;
/** Narrowest outage band, CSS px, so a short outage stays visible. */
export const MIN_BAND_PX = 2;
/** Distance between hatch lines, CSS px. */
export const HATCH_SPACING_PX = 6;

/** A vertical band of the plot, plot px (0 = left edge of the plot area), x1 < x2. */
export interface PlotBand {
  readonly x1: number;
  readonly x2: number;
}

/** A marker group as the overlay draws it (plot px). */
export interface OverlayMarker {
  /** Horizontal position inside the plot area, CSS px. */
  readonly x: number;
  readonly shape: MarkerShape;
  readonly color: string;
  /** Markers in the group (1 = a single marker). */
  readonly count: number;
  /** The group holds the event of the `alert=` deep-link key. */
  readonly emphasized: boolean;
}

/** A time band in data space (keyboard selection: full height, docs/15 §13). */
export interface TimeBand {
  readonly t1: number;
  readonly t2: number;
}

/** Colour of a marker (docs/15 §6.1: alert transitions in the severity colour). */
export function markerColor(marker: ChartMarker): string {
  if (marker.severity === 'critical') {
    return colorTokens['sev-critical'];
  }
  if (marker.severity === 'warn' || marker.type === 'alert') {
    return colorTokens['sev-warn'];
  }
  return marker.type === 'deploy' ? colorTokens['color-primary'] : colorTokens['color-text-muted'];
}

/**
 * Outage bands inside the plot (C-5, D-18): clipped to the range and the plot width, an ongoing
 * outage (`to` null) reaches the end of the range, empty ones and those outside are dropped, and a
 * band is at least MIN_BAND_PX wide (it may then reach past the right edge by less than that and
 * is clipped there).
 */
export function outageBands(scale: PlotScale, outages: readonly ChartOutage[]): PlotBand[] {
  if (scale.width <= 0) {
    return [];
  }
  return outageRects(outages, { from: scale.from, to: scale.to }, (ts) => xOf(scale, ts)).flatMap(
    (rect) => {
      const x1 = Math.max(0, Math.min(scale.width, rect.left));
      const x2 = Math.max(0, Math.min(scale.width, rect.left + Math.max(MIN_BAND_PX, rect.width)));
      return x2 > x1 ? [{ x1, x2 }] : [];
    },
  );
}

/**
 * Marker groups of the range (docs/15 §6.1): markers outside the plot are dropped, markers closer
 * than MARKER_GROUP_PX form one group led by the `emphasizedId` event, else the most severe one,
 * else the first (charts/chartModel.groupMarkers, the same rule as TimeSeriesChart).
 */
export function markerGroups(
  scale: PlotScale,
  markers: readonly ChartMarker[],
  emphasizedId: string | null | undefined,
): ReturnType<typeof groupMarkers> {
  if (scale.width <= 0 || markers.length === 0) {
    return [];
  }
  return groupMarkers(
    markers,
    (ts) => xOf(scale, ts),
    scale.width,
    emphasizedId ?? null,
    MARKER_GROUP_PX,
  );
}

/** The groups as overlay marks: position, shape and colour of the lead, count, emphasis. */
export function overlayMarkers(
  scale: PlotScale,
  markers: readonly ChartMarker[],
  emphasizedId: string | null | undefined,
): OverlayMarker[] {
  return markerGroups(scale, markers, emphasizedId).map((group) => ({
    x: xOf(scale, group.lead.ts),
    shape: markerShape(group.lead.type),
    color: markerColor(group.lead),
    count: group.markers.length,
    emphasized: group.emphasized,
  }));
}

/** A keyboard band in plot px, clipped to the plot; null when it is outside or empty. */
export function bandPixels(scale: PlotScale, band: TimeBand | null | undefined): PlotBand | null {
  if (band === null || band === undefined || scale.width <= 0) {
    return null;
  }
  const x1 = Math.max(0, Math.min(scale.width, xOf(scale, Math.min(band.t1, band.t2))));
  const x2 = Math.max(0, Math.min(scale.width, xOf(scale, Math.max(band.t1, band.t2))));
  return x2 > x1 ? { x1, x2 } : null;
}

/**
 * Hatch lines of a band (45°, bottom-left to top-right) clipped to the rectangle [x1, x2] × [top,
 * bottom], each as [xa, ya, xb, yb]. The lines lie on x + y = k for k on a fixed grid of `spacing`
 * px, so neighbouring bands share one pattern. Computed instead of clipping the context, so the
 * overlay needs no save/clip/restore.
 */
export function hatchSegments(
  x1: number,
  x2: number,
  top: number,
  bottom: number,
  spacing: number = HATCH_SPACING_PX,
): [number, number, number, number][] {
  const segments: [number, number, number, number][] = [];
  if (!(x2 > x1) || !(bottom > top) || !(spacing > 0)) {
    return segments;
  }
  const first = Math.ceil((x1 + top) / spacing) * spacing;
  const last = x2 + bottom;
  for (let k = first; k <= last && segments.length < 10_000; k += spacing) {
    // Lower-left end: on the bottom edge, or on the left edge when the line enters there.
    const xa = Math.max(x1, k - bottom);
    const xb = Math.min(x2, k - top);
    if (xb > xa) {
      segments.push([xa, k - xa, xb, k - xb]);
    }
  }
  return segments;
}
