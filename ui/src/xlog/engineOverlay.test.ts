// SPDX-License-Identifier: Apache-2.0
//
// WO-33 engine extensions (docs/15 §7.2 "마커·음영", §7.3, §13): outage shading, event markers and
// the keyboard band live on the overlay only (the point layer is never redrawn for them), the
// keyboard/"select visible range" band selection goes through the hit index like a drag and equals
// a brute-force scan, the plot size is reported for DOM parts, and batch response times switch the
// y-axis unit automatically (ms -> s -> min, docs/15 §7.2 "배치 단위").
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout, type RecordingContext } from '../../test/canvasMock';

import { XLogEngine, type EngineEnvironment, type EngineProps } from './engine';
import type { FrameSample } from './perf';
import { PLOT_MARGIN, statusColorMapping } from './renderer';
import { durationUnitFor, type DurationUnit } from './scale';
import { scanDataRect } from './scanSelection';
import { KIND_BATCH, STATUS_ERROR, XLogBuffer, type XLogPoint } from './XLogBuffer';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const FROM = 1_700_000_000_000;
const TO = FROM + 100_000;
const PLOT_W = 800;
const PLOT_H = 400;
const WIDTH = PLOT_W + PLOT_MARGIN.left + PLOT_MARGIN.right;
const HEIGHT = PLOT_H + PLOT_MARGIN.top + PLOT_MARGIN.bottom;

function point(ts: number, elapsed: number, extra: Partial<XLogPoint> = {}): XLogPoint {
  return {
    ts,
    elapsed,
    status: 0,
    instance: 0,
    service: 0,
    txIdLo: 0,
    txIdHi: 0,
    kind: 1,
    httpStatus: 0,
    errorClass: 0,
    ...extra,
  };
}

/** 1,000 points: ts every 100 ms, elapsed 0..1998, some above the axis maximum. */
function gridBuffer(): XLogBuffer {
  const buffer = new XLogBuffer(2000);
  buffer.append(
    Array.from({ length: 1000 }, (_, i) =>
      point(FROM + i * 100, i % 97 === 0 ? 50_000 : ((i * 37) % 1000) * 2, {
        status: i % 50 === 0 ? STATUS_ERROR : 0,
      }),
    ),
  );
  return buffer;
}

interface Harness {
  engine: XLogEngine;
  props: EngineProps;
  container: HTMLDivElement;
  overlayCtx: () => RecordingContext;
  runFrames: () => FrameSample[];
}

function setup(props: Partial<EngineProps> = {}): Harness {
  const contexts = installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
  const container = document.createElement('div');
  const points = document.createElement('canvas');
  const overlay = document.createElement('canvas');
  container.append(points, overlay);
  document.body.append(container);
  const callbacks: ((time: number) => void)[] = [];
  let now = 0;
  const env: EngineEnvironment = {
    requestFrame: (callback) => callbacks.push(callback),
    cancelFrame: () => undefined,
    now: () => now,
    devicePixelRatio: () => 1,
    observeResize: () => () => undefined,
    watchResolution: () => () => undefined,
  };
  const samples: FrameSample[] = [];
  const fullProps: EngineProps = {
    buffer: gridBuffer(),
    mapping: statusColorMapping(),
    from: FROM,
    to: TO,
    yType: 'linear',
    yMax: 2000,
    threshold: 1000,
    selection: null,
    drawMode: 'pixels',
    incremental: true,
    formatTime: () => 't',
    formatDuration: (ms) => String(ms),
    formatThreshold: () => 'threshold',
    onFrame: (sample) => samples.push(sample),
    ...props,
  };
  const engine = new XLogEngine(container, points, overlay, fullProps, env);
  return {
    engine,
    props: fullProps,
    container,
    overlayCtx: () => {
      const ctx = contexts.get(overlay);
      if (ctx === undefined) {
        throw new Error('no overlay context');
      }
      return ctx;
    },
    runFrames: () => {
      const start = samples.length;
      let ran = 0;
      while (callbacks.length > 0 && ran < 100) {
        const callback = callbacks.shift();
        now += 16;
        callback?.(now);
        ran += 1;
      }
      return samples.slice(start);
    },
  };
}

afterEach(() => {
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

describe('XLogEngine overlay marks (WO-33)', () => {
  it('T-UI-001 shades outages on the overlay without redrawing the point layer (docs/15 §7.2)', () => {
    const h = setup();
    h.runFrames();
    h.overlayCtx().reset();
    h.engine.update({ ...h.props, outages: [{ from: FROM + 10_000, to: FROM + 20_000 }] });
    const frames = h.runFrames();
    expect(frames).toHaveLength(1);
    expect(frames[0]?.pointsDrawn).toBe(false);
    const ctx = h.overlayCtx();
    // The light fill covers exactly the band (plot px 80..160) over the full plot height.
    const fill = ctx
      .named('fillRect')
      .find((call) => call.fillStyle.startsWith('rgba(139, 146, 156'));
    expect(fill?.args).toEqual([PLOT_MARGIN.left + 80, PLOT_MARGIN.top, 80, PLOT_H]);
    // Hatch lines stay inside the band.
    const moves = ctx.named('moveTo').slice(0, 5);
    expect(moves.length).toBeGreaterThan(0);
    for (const move of moves) {
      const [x, y] = move.args as [number, number];
      expect(x).toBeGreaterThanOrEqual(PLOT_MARGIN.left + 80);
      expect(x).toBeLessThanOrEqual(PLOT_MARGIN.left + 160);
      expect(y).toBeGreaterThanOrEqual(PLOT_MARGIN.top);
      expect(y).toBeLessThanOrEqual(PLOT_MARGIN.top + PLOT_H);
    }
  });

  it('T-UI-001 clips an outage that reaches past the range to the plot area', () => {
    const h = setup({ outages: [{ from: FROM - 60_000, to: null }] });
    h.runFrames();
    const fill = h
      .overlayCtx()
      .named('fillRect')
      .find((call) => call.fillStyle.startsWith('rgba(139, 146, 156'));
    expect(fill?.args).toEqual([PLOT_MARGIN.left, PLOT_MARGIN.top, PLOT_W, PLOT_H]);
  });

  it('T-UI-001 draws event markers at their time, the alert= one solid and the others dashed', () => {
    const h = setup();
    h.runFrames();
    h.overlayCtx().reset();
    h.engine.update({
      ...h.props,
      markers: [
        { id: 'deploy-1', ts: FROM + 25_000, type: 'deploy' },
        { id: 'alert-1', ts: FROM + 75_000, type: 'alert', severity: 'warn' },
      ],
      emphasizedMarkerId: 'alert-1',
    });
    const frames = h.runFrames();
    expect(frames[0]?.pointsDrawn).toBe(false);
    const calls = h.overlayCtx().calls;
    const lineAt = (x: number): readonly unknown[] | undefined => {
      const index = calls.findIndex(
        (call) =>
          call.name === 'moveTo' &&
          call.args[0] === Math.round(PLOT_MARGIN.left + x) + 0.5 &&
          call.args[1] === PLOT_MARGIN.top,
      );
      // The dash pattern set right before the marker's line.
      for (let i = index; i >= 0; i -= 1) {
        if (calls[i]?.name === 'setLineDash') {
          return calls[i]?.args[0] as readonly unknown[];
        }
      }
      return undefined;
    };
    expect(lineAt(200)).toEqual([2, 3]);
    expect(lineAt(600)).toEqual([]);
  });

  it('draws the keyboard band over the full height and redraws only the overlay for it', () => {
    const h = setup();
    h.runFrames();
    h.overlayCtx().reset();
    h.engine.update({ ...h.props, band: { t1: FROM + 50_000, t2: FROM + 60_000 } });
    const frames = h.runFrames();
    expect(frames[0]?.pointsDrawn).toBe(false);
    const stroke = h.overlayCtx().named('strokeRect')[0];
    expect(stroke?.args).toEqual([PLOT_MARGIN.left + 400 + 1, PLOT_MARGIN.top + 1, 78, PLOT_H - 2]);
  });

  it('T-UI-001 selects a time band over the full height like a drag, equal to a brute-force scan', () => {
    const onSelect = vi.fn();
    const h = setup({ onSelect });
    h.runFrames();
    const t1 = FROM + 20_000;
    const t2 = FROM + 45_000;
    const selection = h.engine.selectTimeBand({ t1, t2 });
    expect(selection).not.toBeNull();
    expect(onSelect).toHaveBeenCalledWith(selection);
    expect(selection?.rect).toEqual({ t1, t2, e1: 0, e2: Number.POSITIVE_INFINITY });
    expect(typeof selection?.queryMs).toBe('number');
    const expected = [...scanDataRect(h.props.buffer, { t1, t2, e1: 0, e2: Infinity })].sort(
      (a, b) => a - b,
    );
    const actual = [...(selection?.indices ?? [])].sort((a, b) => a - b);
    // Points above the axis maximum (drawn on the top edge) are included.
    expect(actual).toEqual(expected);
    expect(actual.length).toBe(251);
  });

  it('clips a band to the range and selects nothing for a band outside it', () => {
    const onSelect = vi.fn();
    const h = setup({ onSelect });
    h.runFrames();
    const all = h.engine.selectTimeBand({ t1: FROM - 10_000, t2: TO + 10_000 });
    expect(all?.indices.length).toBe(1000);
    expect(all?.rect.t1).toBe(FROM);
    expect(all?.rect.t2).toBe(TO);
    expect(h.engine.selectTimeBand({ t1: TO + 1, t2: TO + 2 })).toBeNull();
    expect(onSelect).toHaveBeenCalledTimes(1);
  });

  it('reports the plot size after measuring (DOM parts over the plot)', () => {
    const onLayout = vi.fn();
    setup({ onLayout });
    expect(onLayout).toHaveBeenCalledWith({ plotWidth: PLOT_W, plotHeight: PLOT_H });
  });
});

describe('batch units on the y axis (docs/15 §7.2 "배치 단위", 05 §2.1-6)', () => {
  it('T-UI-001 labels batch response times in minutes or seconds automatically', () => {
    const units: DurationUnit[] = [];
    const batch = new XLogBuffer(100);
    // Batch jobs of 1 to 25 minutes (kind = batch).
    batch.append(
      Array.from({ length: 50 }, (_, i) =>
        point(FROM + i * 1000, (1 + (i % 25)) * 60_000, { kind: KIND_BATCH }),
      ),
    );
    const h = setup({
      buffer: batch,
      yMax: null,
      formatDuration: (ms, unit) => {
        units.push(unit);
        return String(ms);
      },
    });
    h.runFrames();
    expect(new Set(units)).toEqual(new Set(['min']));
    // The automatic maximum is p99.9 × 1.2 rounded up to a nice number: 25 min × 1.2 = 30 min
    // -> 2,000,000 ms.
    expect(h.engine.scale().yMax).toBe(2_000_000);
    expect(durationUnitFor(h.engine.scale().yMax)).toBe('min');
    // The container carries the unit for the E2E suite (T-E2E-xlog-6).
    expect(h.container.dataset['yUnit']).toBe('min');

    const seconds = new XLogBuffer(100);
    seconds.append(Array.from({ length: 20 }, (_, i) => point(FROM + i * 1000, 5_000 + i * 1000)));
    units.length = 0;
    h.engine.update({ ...h.props, buffer: seconds });
    h.runFrames();
    expect(new Set(units)).toEqual(new Set(['s']));
    expect(h.container.dataset['yUnit']).toBe('s');
  });
});
