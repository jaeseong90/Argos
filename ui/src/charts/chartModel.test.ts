// SPDX-License-Identifier: Apache-2.0
import type uPlot from 'uplot';
import { describe, expect, it, vi } from 'vitest';

import {
  A11Y_TABLE_MAX_ROWS,
  baselineDifference,
  clickMenuRange,
  downsampleIndices,
  groupMarkers,
  markerShape,
  nearestIndex,
  outageRects,
  toChartValues,
  withAlpha,
  type ChartMarker,
} from './chartModel';
import { BASELINE_ALPHA, BASELINE_DASH, baselineSeriesOptions } from './plugins/baseline';
import { clickMenuPlugin } from './plugins/clickMenu';
import { cursorSyncOptions } from './plugins/cursorSync';
import { gapShadingPlugin } from './plugins/gapShading';
import { markersPlugin } from './plugins/markers';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };

const MIN = 60_000;
const HOUR = 60 * MIN;
const FROM = 1_800_000_000_000;
const RANGE = { from: FROM, to: FROM + HOUR };
/** 1 px per minute over the one-hour range. */
const toPx = (ts: number): number => (ts - FROM) / MIN;

function marker(id: string, minute: number, extra: Partial<ChartMarker> = {}): ChartMarker {
  return { id, ts: FROM + minute * MIN, type: 'deploy', ...extra };
}

describe('missing values in charts (D-09, D-16)', () => {
  it('T-UI-007 turns undefined, NaN and infinities into null gaps and keeps 0 as a value', () => {
    expect(toChartValues([1, null, undefined, Number.NaN, 0, Infinity, -Infinity], 8)).toEqual([
      1,
      null,
      null,
      null,
      0,
      null,
      null,
      null,
    ]);
    expect(toChartValues([1, 2, 3], 2)).toEqual([1, 2]);
  });

  it('draws baselines and series with spanGaps off so null breaks the line', () => {
    const options = baselineSeriesOptions('TPS (7 days ago)', '#2f6feb', 'y-rate');
    expect(options.spanGaps).toBe(false);
    expect(options.dash).toEqual([4, 4]);
    expect(BASELINE_DASH).toEqual([4, 4]);
    expect(options.alpha).toBe(0.6);
    expect(BASELINE_ALPHA).toBe(0.6);
    expect(options.stroke).toBe('#2f6feb');
  });

  it('compares a value with its baseline only when both are measured', () => {
    expect(baselineDifference(125, 100)).toBeCloseTo(0.25);
    expect(baselineDifference(50, 100)).toBeCloseTo(-0.5);
    expect(baselineDifference(0, 100)).toBe(-1);
    expect(baselineDifference(null, 100)).toBeNull();
    expect(baselineDifference(100, null)).toBeNull();
    expect(baselineDifference(100, 0)).toBeNull();
    expect(baselineDifference(100, undefined)).toBeNull();
  });

  it('makes translucent fills from hex colours only', () => {
    expect(withAlpha('#2f6feb', 0.2)).toBe('#2f6feb33');
    expect(withAlpha('#2F6FEB', 1)).toBe('#2F6FEBff');
    expect(withAlpha('rgb(1, 2, 3)', 0.2)).toBe('rgb(1, 2, 3)');
  });
});

describe('click menu range (05 §2.0-5)', () => {
  it('opens XLog at the point time plus or minus range/60', () => {
    const point = FROM + 3 * HOUR;
    expect(clickMenuRange(point, { from: FROM, to: FROM + 6 * HOUR })).toEqual({
      from: point - 6 * MIN,
      to: point + 6 * MIN,
    });
    expect(clickMenuRange(point, { from: FROM, to: FROM + 24 * HOUR })).toEqual({
      from: point - 24 * MIN,
      to: point + 24 * MIN,
    });
  });

  it('uses at least one minute on each side', () => {
    const point = FROM + 7 * MIN;
    expect(clickMenuRange(point, { from: FROM, to: FROM + 15 * MIN })).toEqual({
      from: point - MIN,
      to: point + MIN,
    });
    expect(clickMenuRange(point, RANGE)).toEqual({ from: point - MIN, to: point + MIN });
  });

  it('finds the nearest data point', () => {
    const ts = [0, 10, 20, 30];
    expect(nearestIndex(ts, -5)).toBe(0);
    expect(nearestIndex(ts, 4)).toBe(0);
    expect(nearestIndex(ts, 5)).toBe(0);
    expect(nearestIndex(ts, 6)).toBe(1);
    expect(nearestIndex(ts, 29)).toBe(3);
    expect(nearestIndex(ts, 99)).toBe(3);
    expect(nearestIndex([], 1)).toBe(-1);
  });

  it('reports the cursor index and position on a plot click', () => {
    const over = document.createElement('div');
    const onPointClick = vi.fn();
    const plugin = clickMenuPlugin({ onPointClick });
    const plot = {
      over,
      cursor: { left: 42, top: 17, idx: 3 },
      posToIdx: () => 0,
    } as unknown as uPlot;
    const hooks = plugin.hooks as { ready: (u: uPlot) => void; destroy: (u: uPlot) => void };
    hooks.ready(plot);
    over.dispatchEvent(new MouseEvent('click'));
    expect(onPointClick).toHaveBeenCalledWith(3, 42, 17);
    hooks.destroy(plot);
    over.dispatchEvent(new MouseEvent('click'));
    expect(onPointClick).toHaveBeenCalledTimes(1);
  });
});

describe('event markers (docs/15 §6.1, deep link 4)', () => {
  it('maps event types to the shapes of docs/15 §6.1', () => {
    expect(markerShape('deploy')).toBe('triangle');
    expect(markerShape('start')).toBe('dot');
    expect(markerShape('stop')).toBe('ring');
    expect(markerShape('config')).toBe('diamond');
    expect(markerShape('alert')).toBe('asterisk');
    expect(markerShape('custom')).toBe('square');
  });

  it('groups markers closer than 12 px into one "+N" group and drops markers outside the plot', () => {
    const groups = groupMarkers(
      [
        marker('a', 10),
        marker('b', 15),
        marker('c', 20),
        marker('d', 40),
        marker('early', -1),
        marker('late', 61),
      ],
      toPx,
      60,
    );
    expect(groups.map((group) => group.markers.map((m) => m.id))).toEqual([['a', 'b', 'c'], ['d']]);
    expect(groups.map((group) => group.left)).toEqual([10, 40]);
    expect(groups.map((group) => group.lead.id)).toEqual(['a', 'd']);
    expect(groups.every((group) => !group.emphasized)).toBe(true);
  });

  it('leads a group with the alert= marker, else with the most severe one', () => {
    const members = [
      marker('deploy', 10),
      marker('warn', 11, { type: 'alert', severity: 'warn' }),
      marker('critical', 12, { type: 'alert', severity: 'critical' }),
    ];
    const [bySeverity] = groupMarkers(members, toPx, 60);
    expect(bySeverity?.lead.id).toBe('critical');
    expect(bySeverity?.emphasized).toBe(false);
    const [emphasized] = groupMarkers(members, toPx, 60, 'deploy');
    expect(emphasized?.lead.id).toBe('deploy');
    expect(emphasized?.emphasized).toBe(true);
    expect(groupMarkers(members, toPx, 60, 'unknown')[0]?.emphasized).toBe(false);
  });

  it('draws a dashed line per group in the lead colour and reports the layout', () => {
    const calls: { name: string; args: unknown[]; strokeStyle: unknown; alpha: unknown }[] = [];
    const ctx = {
      strokeStyle: '',
      globalAlpha: 1,
      lineWidth: 1,
      save: () => undefined,
      restore: () => undefined,
      setLineDash: (...args: unknown[]) =>
        calls.push({ name: 'setLineDash', args, strokeStyle: '', alpha: 1 }),
      beginPath: () => undefined,
      moveTo: () => undefined,
      lineTo: () => undefined,
      stroke(this: { strokeStyle: unknown; globalAlpha: unknown }) {
        calls.push({
          name: 'stroke',
          args: [],
          strokeStyle: this.strokeStyle,
          alpha: this.globalAlpha,
        });
      },
    };
    const onLayout = vi.fn();
    const plugin = markersPlugin({
      getMarkers: () => [marker('a', 10), marker('b', 30, { type: 'alert', severity: 'critical' })],
      getEmphasizedId: () => 'b',
      colorOf: (m) => (m.severity === 'critical' ? '#b91c1c' : '#2f6feb'),
      onLayout,
    });
    const plot = {
      ctx,
      // jsdom reports devicePixelRatio 1: the canvas box equals the CSS box.
      bbox: { left: 0, top: 0, width: 60, height: 100 },
      valToPos: (ts: number) => toPx(ts),
    } as unknown as uPlot;
    (plugin.hooks as { draw: (u: uPlot) => void }).draw(plot);
    const groups = onLayout.mock.calls[0]?.[0] as { lead: ChartMarker; emphasized: boolean }[];
    expect(groups.map((group) => [group.lead.id, group.emphasized])).toEqual([
      ['a', false],
      ['b', true],
    ]);
    expect(
      calls.filter((call) => call.name === 'stroke').map((call) => [call.strokeStyle, call.alpha]),
    ).toEqual([
      ['#2f6feb', 0.7],
      ['#b91c1c', 1],
    ]);
  });
});

describe('collection outages (C-5, D-18)', () => {
  it('clips outages to the range, extends an ongoing one to the end and drops the rest', () => {
    const rects = outageRects(
      [
        { from: FROM + 10 * MIN, to: FROM + 20 * MIN },
        { from: FROM - 30 * MIN, to: FROM + 5 * MIN },
        { from: FROM + 50 * MIN, to: null },
        { from: FROM - 30 * MIN, to: FROM - 10 * MIN },
        { from: FROM + 70 * MIN, to: null },
        { from: FROM + 30 * MIN, to: FROM + 30 * MIN },
      ],
      RANGE,
      toPx,
    );
    expect(rects.map((rect) => [rect.left, rect.width])).toEqual([
      [10, 10],
      [0, 5],
      [50, 10],
    ]);
  });

  it('keeps a short outage at least 2 px wide', () => {
    const [rect] = outageRects(
      [{ from: FROM + 10 * MIN, to: FROM + 10 * MIN + 1000 }],
      RANGE,
      toPx,
    );
    expect(rect?.width).toBe(2);
  });

  it('lays the rectangles out after every draw (gapShading plugin)', () => {
    const onLayout = vi.fn();
    const plugin = gapShadingPlugin({
      getOutages: () => [{ from: FROM + 10 * MIN, to: FROM + 20 * MIN }],
      getRange: () => RANGE,
      onLayout,
    });
    (plugin.hooks as { draw: (u: uPlot) => void }).draw({
      valToPos: (ts: number) => toPx(ts),
    } as unknown as uPlot);
    expect(onLayout).toHaveBeenCalledWith([
      { left: 10, width: 10, outage: { from: FROM + 10 * MIN, to: FROM + 20 * MIN } },
    ]);
  });
});

describe('cursor sync and the accessible table (docs/15 §6.1)', () => {
  it('joins the sync group of a key without sharing series visibility', () => {
    expect(cursorSyncOptions('dashboard')).toEqual({ key: 'dashboard', setSeries: false });
    expect(cursorSyncOptions('')).toBeUndefined();
    expect(cursorSyncOptions(undefined)).toBeUndefined();
  });

  it('keeps every row up to 100 and 100 evenly spaced rows with both ends above', () => {
    expect(downsampleIndices(0)).toEqual([]);
    expect(downsampleIndices(3)).toEqual([0, 1, 2]);
    expect(downsampleIndices(100)).toHaveLength(100);
    const rows = downsampleIndices(720);
    expect(rows).toHaveLength(A11Y_TABLE_MAX_ROWS);
    expect(rows[0]).toBe(0);
    expect(rows[rows.length - 1]).toBe(719);
    expect([...rows].sort((a, b) => a - b)).toEqual(rows);
    expect(new Set(rows).size).toBe(rows.length);
  });
});
