// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';

import { installCanvasMock, mockLayout, type RecordingContext } from '../../test/canvasMock';

import { XLogEngine, type EngineEnvironment, type EngineProps } from './engine';
import { HitIndex } from './hitIndex';
import type { FrameSample } from './perf';
import {
  PLOT_MARGIN,
  POINT_LAYER_MAX_DPR,
  POINT_SIZE,
  pointLayerScale,
  statusColorMapping,
} from './renderer';
import { inDataRect, xOf, yOf } from './scale';
import { STATUS_ERROR, XLogBuffer, type XLogPoint } from './XLogBuffer';

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
    httpStatus: 200,
    errorClass: 0,
    ...extra,
  };
}

/** 1,000 points on a grid: ts every 100 ms, elapsed 0..1998. */
function gridBuffer(): XLogBuffer {
  const buffer = new XLogBuffer(2000);
  buffer.append(
    Array.from({ length: 1000 }, (_, i) =>
      point(FROM + i * 100, ((i * 37) % 1000) * 2, { status: i % 50 === 0 ? STATUS_ERROR : 0 }),
    ),
  );
  return buffer;
}

interface Harness {
  engine: XLogEngine;
  props: EngineProps;
  container: HTMLDivElement;
  overlay: HTMLCanvasElement;
  points: HTMLCanvasElement;
  pointsCtx: () => RecordingContext;
  overlayCtx: () => RecordingContext;
  runFrames: () => number;
  pending: () => number;
  setNow: (now: number) => void;
  /** What the engine registered through EngineEnvironment.observeResize / watchResolution. */
  resizeWatchers: Watcher[];
  resolutionWatchers: (Watcher & { dpr: number })[];
  setDpr: (dpr: number) => void;
}

interface Watcher {
  readonly callback: () => void;
  active: boolean;
}

function rectOf(left: number, top: number, width: number, height: number): DOMRect {
  return {
    x: left,
    y: top,
    left,
    top,
    right: left + width,
    bottom: top + height,
    width,
    height,
    toJSON: () => ({}),
  };
}

/**
 * Lays the container out with a `border` px border, like `.xlog-canvas`: its rectangle (border
 * box) is larger than the padding box at (border, border) that the canvases fill.
 */
function mockBorderedLayout(container: HTMLElement, border: number): void {
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    return this === container
      ? rectOf(0, 0, WIDTH + 2 * border, HEIGHT + 2 * border)
      : rectOf(border, border, WIDTH, HEIGHT);
  });
}

function setup(props: Partial<EngineProps> = {}, dpr = 1, border = 0): Harness {
  const contexts = installCanvasMock();
  mockLayout(WIDTH, HEIGHT);
  const container = document.createElement('div');
  const points = document.createElement('canvas');
  const overlay = document.createElement('canvas');
  container.append(points, overlay);
  document.body.append(container);
  if (border > 0) {
    mockBorderedLayout(container, border);
  }
  const callbacks: ((time: number) => void)[] = [];
  let now = 0;
  let ratio = dpr;
  const resizeWatchers: Watcher[] = [];
  const resolutionWatchers: (Watcher & { dpr: number })[] = [];
  const env: EngineEnvironment = {
    requestFrame: (callback) => callbacks.push(callback),
    cancelFrame: () => undefined,
    now: () => now,
    devicePixelRatio: () => ratio,
    observeResize: (element, callback) => {
      expect(element).toBe(container);
      const watcher = { callback, active: true };
      resizeWatchers.push(watcher);
      return () => {
        watcher.active = false;
      };
    },
    watchResolution: (value, callback) => {
      const watcher = { dpr: value, callback, active: true };
      resolutionWatchers.push(watcher);
      return () => {
        watcher.active = false;
      };
    },
  };
  const fullProps: EngineProps = {
    buffer: gridBuffer(),
    mapping: statusColorMapping(),
    from: FROM,
    to: TO,
    yType: 'linear',
    yMax: 2000,
    threshold: 1000,
    selection: null,
    drawMode: 'path',
    incremental: true,
    formatTime: () => 't',
    formatDuration: (ms) => String(ms),
    formatThreshold: () => 'threshold',
    ...props,
  };
  const engine = new XLogEngine(container, points, overlay, fullProps, env);
  const contextOf = (canvas: HTMLCanvasElement): RecordingContext => {
    const ctx = contexts.get(canvas);
    if (ctx === undefined) {
      throw new Error('no context');
    }
    return ctx;
  };
  return {
    engine,
    props: fullProps,
    container,
    overlay,
    points,
    pointsCtx: () => contextOf(points),
    overlayCtx: () => contextOf(overlay),
    runFrames: () => {
      let ran = 0;
      while (callbacks.length > 0 && ran < 1000) {
        const callback = callbacks.shift();
        now += 16;
        callback?.(now);
        ran += 1;
      }
      return ran;
    },
    pending: () => callbacks.length,
    setNow: (value) => {
      now = value;
    },
    resizeWatchers,
    resolutionWatchers,
    setDpr: (value) => {
      ratio = value;
    },
  };
}

/** Dispatches a pointer event at plot position (x, y); `origin` = client position of the canvases. */
function pointer(
  target: Element,
  type: string,
  x: number,
  y: number,
  button = 0,
  origin = 0,
): void {
  target.dispatchEvent(
    new PointerEvent(type, {
      clientX: origin + PLOT_MARGIN.left + x,
      clientY: origin + PLOT_MARGIN.top + y,
      button,
      pointerId: 1,
      bubbles: true,
    }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

describe('XLogEngine', () => {
  it('sizes both canvases for the container and devicePixelRatio (docs/15 §7.2)', () => {
    // Without a cap below the DPR, both canvases use the full devicePixelRatio.
    const h = setup({ pointLayerMaxDpr: 3 }, 2);
    expect(h.points.width).toBe(PLOT_W * 2);
    expect(h.points.height).toBe(PLOT_H * 2);
    expect(h.points.style.width).toBe(`${String(PLOT_W)}px`);
    expect(h.points.style.left).toBe(`${String(PLOT_MARGIN.left)}px`);
    expect(h.overlay.width).toBe(WIDTH * 2);
    expect(h.overlay.height).toBe(HEIGHT * 2);
    expect(h.engine.layout).toMatchObject({ plotWidth: PLOT_W, plotHeight: PLOT_H, dpr: 2 });
    h.engine.destroy();
  });

  it('caps the point layer backing store at DPR 2, keeps the overlay at full DPR and hover in CSS px (ADR-0003 C-34)', () => {
    const buffer = gridBuffer();
    const hovers: { index: number; x: number; y: number }[] = [];
    const hoverAt = (dpr: number, props: Partial<EngineProps>) => {
      const h = setup(
        {
          buffer,
          onHover: (hover) => {
            if (hover !== null) {
              hovers.push(hover);
            }
          },
          ...props,
        },
        dpr,
      );
      h.runFrames();
      const scale = h.engine.scale();
      pointer(
        h.overlay,
        'pointermove',
        xOf(scale, buffer.ts[7] ?? 0) + 1,
        yOf(scale, buffer.elapsed[7] ?? 0),
      );
      return h;
    };
    // Default cap.
    const capped = hoverAt(2, {});
    const pointScale = Math.min(2, POINT_LAYER_MAX_DPR);
    expect(capped.points.width).toBe(Math.round(PLOT_W * pointScale));
    expect(capped.points.height).toBe(Math.round(PLOT_H * pointScale));
    expect(capped.points.style.width).toBe(`${String(PLOT_W)}px`);
    expect(capped.overlay.width).toBe(WIDTH * 2);
    expect(capped.overlay.height).toBe(HEIGHT * 2);
    capped.engine.destroy();
    vi.restoreAllMocks();
    // An explicit cap of 1.5.
    const h = hoverAt(2, { pointLayerMaxDpr: 1.5 });
    expect(h.points.width).toBe(PLOT_W * 1.5);
    expect(h.points.height).toBe(PLOT_H * 1.5);
    expect(h.overlay.width).toBe(WIDTH * 2);
    // Changing the cap re-measures the point layer only.
    h.engine.update({ ...h.props, pointLayerMaxDpr: 1 });
    expect(h.points.width).toBe(PLOT_W);
    expect(h.overlay.width).toBe(WIDTH * 2);
    h.engine.destroy();
    vi.restoreAllMocks();
    // DPR 1 for comparison: the hovered point and its position are the same in CSS px.
    hoverAt(1, {}).engine.destroy();
    expect(hovers).toHaveLength(3);
    expect(hovers[0]?.index).toBe(7);
    expect(hovers[1]).toEqual(hovers[0]);
    expect(hovers[2]).toEqual(hovers[0]);
  });

  it('asks for a CPU-backed point layer only when softwareCanvas is set', () => {
    const gpu = setup();
    expect(gpu.pointsCtx().attributes).toEqual({});
    gpu.engine.destroy();
    vi.restoreAllMocks();
    const cpu = setup({ softwareCanvas: true });
    expect(cpu.pointsCtx().attributes).toEqual({ willReadFrequently: true });
    // The overlay always uses the default (GPU) canvas.
    expect(cpu.overlayCtx().attributes).toBeUndefined();
    cpu.engine.destroy();
  });

  it('draws once, then only when something changes', () => {
    const h = setup();
    expect(h.runFrames()).toBe(1);
    expect(h.pointsCtx().count('fill')).toBe(2);
    expect(h.pending()).toBe(0);
    // Same props: nothing to draw.
    h.engine.update({ ...h.props });
    expect(h.pending()).toBe(0);
    h.engine.destroy();
  });

  it('draws only the new points when realtime data arrives', () => {
    const buffer = gridBuffer();
    const h = setup({ buffer });
    h.runFrames();
    h.pointsCtx().reset();
    buffer.append([point(FROM + 99_950, 10, { status: STATUS_ERROR })]);
    expect(h.pending()).toBe(1);
    h.runFrames();
    expect(h.pointsCtx().count('rect')).toBe(1);
    h.engine.destroy();
  });

  it('T-UI-001 selects the points inside a drag rectangle (docs/15 §7.3)', () => {
    const onSelect = vi.fn();
    const buffer = gridBuffer();
    const h = setup({ buffer, onSelect });
    h.runFrames();
    pointer(h.overlay, 'pointerdown', 600, 300);
    pointer(h.overlay, 'pointermove', 400, 250);
    pointer(h.overlay, 'pointermove', 200, 100);
    // The drag rectangle is drawn on the overlay only.
    h.pointsCtx().reset();
    h.runFrames();
    expect(h.pointsCtx().count('fill')).toBe(0);
    expect(h.overlayCtx().count('strokeRect')).toBeGreaterThan(0);
    pointer(h.overlay, 'pointerup', 200, 100);

    expect(onSelect).toHaveBeenCalledTimes(1);
    const selection = onSelect.mock.calls[0]?.[0] as {
      rect: { t1: number; t2: number; e1: number; e2: number };
      indices: Uint32Array;
    };
    // x 200..600 of 800 px over 100 s -> 25 s..75 s; y 100..300 of 400 px over 2,000 ms -> 500..1,500 ms.
    expect(selection.rect).toEqual({ t1: FROM + 25_000, t2: FROM + 75_000, e1: 500, e2: 1500 });
    const expected: number[] = [];
    for (let p = 0; p < buffer.size; p += 1) {
      if (inDataRect(selection.rect, buffer.ts[p] ?? 0, buffer.elapsed[p] ?? 0)) {
        expected.push(p);
      }
    }
    expect(expected.length).toBeGreaterThan(100);
    expect(Array.from(selection.indices).sort((a, b) => a - b)).toEqual(expected);
    h.engine.destroy();
  });

  it('measures and maps pointers in the padding box, not the bordered container box', () => {
    // Regression (WO-04 E2E): with a 1px container border, sizing from the container's rectangle
    // made the plot 2px too large and shifted every pointer position by 1px.
    const onSelect = vi.fn();
    const h = setup({ onSelect }, 1, 1);
    expect(h.engine.layout).toMatchObject({
      width: WIDTH,
      height: HEIGHT,
      plotWidth: PLOT_W,
      plotHeight: PLOT_H,
    });
    expect(h.overlay.width).toBe(WIDTH);
    expect(h.overlay.height).toBe(HEIGHT);
    h.runFrames();
    // Press on the plot's top-left pixel and drag to (200, 100).
    pointer(h.overlay, 'pointerdown', 0, 0, 0, 1);
    pointer(h.overlay, 'pointermove', 200, 100, 0, 1);
    pointer(h.overlay, 'pointerup', 200, 100, 0, 1);
    expect(onSelect).toHaveBeenCalledTimes(1);
    const selection = onSelect.mock.calls[0]?.[0] as {
      rect: { t1: number; t2: number; e1: number; e2: number };
    };
    // The first pixel column starts the range; the top edge also takes points above the axis maximum.
    expect(selection.rect).toEqual({
      t1: FROM,
      t2: FROM + 25_000,
      e1: 1500,
      e2: Number.POSITIVE_INFINITY,
    });
    h.engine.destroy();
  });

  it('treats a tiny drag as a click on the nearest point', () => {
    const onSelect = vi.fn();
    const onPointClick = vi.fn();
    const buffer = gridBuffer();
    const h = setup({ buffer, onSelect, onPointClick });
    h.runFrames();
    const scale = h.engine.scale();
    const x = xOf(scale, buffer.ts[10] ?? 0);
    const y = yOf(scale, buffer.elapsed[10] ?? 0);
    pointer(h.overlay, 'pointerdown', x + 1, y + 1);
    pointer(h.overlay, 'pointerup', x + 1, y + 1);
    expect(onSelect).not.toHaveBeenCalled();
    expect(onPointClick).toHaveBeenCalledWith(10);
    h.engine.destroy();
  });

  it('reports the hovered point and clears it when the pointer leaves', () => {
    const onHover = vi.fn();
    const buffer = gridBuffer();
    const h = setup({ buffer, onHover });
    h.runFrames();
    const scale = h.engine.scale();
    const x = xOf(scale, buffer.ts[3] ?? 0);
    const y = yOf(scale, buffer.elapsed[3] ?? 0);
    pointer(h.overlay, 'pointermove', x + 2, y - 2);
    const hover = onHover.mock.lastCall?.[0] as { index: number; x: number; y: number };
    expect(hover.index).toBe(3);
    expect(hover.x).toBeCloseTo(PLOT_MARGIN.left + x, 4);
    expect(hover.y).toBeCloseTo(PLOT_MARGIN.top + y, 4);
    h.runFrames();
    expect(h.overlayCtx().count('arc')).toBe(1);
    h.overlay.dispatchEvent(new PointerEvent('pointerleave'));
    expect(onHover).toHaveBeenLastCalledWith(null);
    h.engine.destroy();
  });

  it('ignores secondary buttons and presses outside the plot', () => {
    const onSelect = vi.fn();
    const h = setup({ onSelect });
    pointer(h.overlay, 'pointerdown', 100, 100, 2);
    pointer(h.overlay, 'pointerup', 300, 300, 2);
    pointer(h.overlay, 'pointerdown', -30, 100);
    pointer(h.overlay, 'pointerup', 300, 300);
    expect(onSelect).not.toHaveBeenCalled();
    h.engine.destroy();
  });

  it('computes the automatic y maximum from p99.9 × 1.2 and throttles it', () => {
    const buffer = gridBuffer();
    const h = setup({ buffer, yMax: null });
    // elapsed values 0..1998: p99.9 = 1996 -> × 1.2 = 2395.2 -> 2500.
    expect(h.engine.scale().yMax).toBe(2500);
    buffer.append([point(FROM + 10, 1_000_000), point(FROM + 11, 1_000_000)]);
    // Within one second the value is kept.
    expect(h.engine.scale().yMax).toBe(2500);
    h.setNow(5000);
    expect(h.engine.scale().yMax).toBe(2500);
    buffer.append(Array.from({ length: 10 }, (_, i) => point(FROM + 20 + i, 1_000_000)));
    h.setNow(10_000);
    expect(h.engine.scale().yMax).toBe(2_000_000);
    h.engine.destroy();
  });

  it('labels a linear axis in one unit and each decade of a log axis in its own unit', () => {
    const formatDuration = (ms: number, unit: string) => `${String(ms)}|${unit}`;
    const linear = setup({ yMax: 50_000, formatDuration });
    linear.runFrames();
    expect(linear.overlayCtx().texts).toEqual(
      expect.arrayContaining(['0|s', '10000|s', '50000|s']),
    );
    linear.engine.destroy();
    vi.restoreAllMocks();
    const log = setup({ yType: 'log', yMax: 50_000, formatDuration });
    log.runFrames();
    expect(log.overlayCtx().texts).toEqual(
      expect.arrayContaining(['1|ms', '100|ms', '1000|ms', '10000|s']),
    );
    log.engine.destroy();
  });

  it('benchmarks consecutive full redraws and reports their timing', async () => {
    const h = setup();
    h.runFrames();
    const result = h.engine.benchmark({ frames: 5, flush: true });
    expect(h.runFrames()).toBe(5);
    const samples = await result;
    expect(samples).toHaveLength(5);
    expect(samples.every((s) => s.full && s.flushed && s.pointsDrawn)).toBe(true);
    expect(samples[0]?.interval).toBeNull();
    expect(samples[1]?.interval).toBe(16);
    expect(h.pointsCtx().count('getImageData')).toBe(5);
    await expect(
      Promise.all([h.engine.benchmark({ frames: 1 }), h.engine.benchmark({ frames: 1 })]),
    ).rejects.toThrow(/already running/);
    h.runFrames();
    h.engine.destroy();
  });

  it('stops drawing and rejects a running benchmark when destroyed', async () => {
    const buffer = gridBuffer();
    const h = setup({ buffer });
    h.runFrames();
    const pending = h.engine.benchmark({ frames: 10 });
    h.engine.destroy();
    await expect(pending).rejects.toThrow(/removed/);
    buffer.append([point(FROM + 1, 1)]);
    h.runFrames();
    expect(h.pending()).toBe(0);
  });
});

describe('XLogEngine hover under a moving range (realtime window, docs/15 §7.5)', () => {
  /**
   * A (index 0) at plot (400, 200); B (index 1) 1 s = 8 px to its right at the same height; C far
   * away. 800 px for 100 s, 400 px for 2,000 ms.
   */
  function hoverBuffer(): XLogBuffer {
    const buffer = new XLogBuffer(10);
    buffer.append([
      point(FROM + 50_000, 1000),
      point(FROM + 51_000, 1000),
      point(FROM + 10_000, 300),
    ]);
    return buffer;
  }

  const at = (index: number, x: number, y: number) => ({
    index,
    x: expect.closeTo(PLOT_MARGIN.left + x, 3) as number,
    y: expect.closeTo(PLOT_MARGIN.top + y, 3) as number,
  });

  it('picks the point under a resting pointer again when the range moves', () => {
    const onHover = vi.fn();
    const h = setup({ buffer: hoverBuffer(), onHover });
    h.runFrames();
    pointer(h.overlay, 'pointermove', 400, 200);
    expect(onHover).toHaveBeenLastCalledWith(at(0, 400, 200));
    h.runFrames();
    onHover.mockClear();

    // The window moves 0.25 s (2 px): the hover is kept, A stays the nearest point, at x 398.
    const moved = { ...h.props, from: FROM + 250, to: TO + 250 };
    h.engine.update(moved);
    expect(onHover).not.toHaveBeenCalled();
    expect(h.runFrames()).toBe(1);
    expect(onHover).toHaveBeenCalledTimes(1);
    expect(onHover).toHaveBeenLastCalledWith(at(0, 398, 200));

    // 1 s in all: A is 8 px away (outside the hover radius), B is now under the pointer.
    h.overlayCtx().reset();
    h.engine.update({ ...moved, from: FROM + 1000, to: TO + 1000 });
    expect(h.runFrames()).toBe(1);
    expect(onHover).toHaveBeenCalledTimes(2);
    expect(onHover).toHaveBeenLastCalledWith(at(1, 400, 200));
    // The hover ring is drawn at the new point in the same frame.
    const arc = h.overlayCtx().named('arc');
    expect(arc).toHaveLength(1);
    expect(arc[0]?.args[0]).toBeCloseTo(PLOT_MARGIN.left + 400, 3);
    expect(arc[0]?.args[1]).toBeCloseTo(PLOT_MARGIN.top + 200, 3);

    // A range with no point under the pointer clears the hover.
    h.engine.update({ ...moved, from: FROM + 20_000, to: TO + 20_000 });
    h.runFrames();
    expect(onHover).toHaveBeenLastCalledWith(null);
    h.engine.destroy();
  });

  it('does not bring the hover back after the pointer left or while dragging', () => {
    const onHover = vi.fn();
    const h = setup({ buffer: hoverBuffer(), onHover });
    h.runFrames();
    pointer(h.overlay, 'pointermove', 400, 200);
    h.runFrames();
    h.overlay.dispatchEvent(new PointerEvent('pointerleave'));
    expect(onHover).toHaveBeenLastCalledWith(null);
    onHover.mockClear();
    h.overlayCtx().reset();
    // Without a pointer over the plot the hit index is not rebuilt for the hover.
    const build = vi.spyOn(HitIndex.prototype, 'build');
    h.engine.update({ ...h.props, from: FROM + 1000, to: TO + 1000 });
    h.runFrames();
    expect(onHover).not.toHaveBeenCalled();
    expect(build).not.toHaveBeenCalled();
    expect(h.overlayCtx().count('fillText')).toBeGreaterThan(0);
    expect(h.overlayCtx().count('arc')).toBe(0);

    // Hover again, then press: the drag clears the hover and a range move does not restore it.
    pointer(h.overlay, 'pointermove', 400, 200);
    expect(onHover).toHaveBeenLastCalledWith(at(1, 400, 200));
    pointer(h.overlay, 'pointerdown', 400, 200);
    expect(onHover).toHaveBeenLastCalledWith(null);
    h.engine.update({ ...h.props, from: FROM, to: TO });
    h.runFrames();
    expect(onHover).toHaveBeenLastCalledWith(null);
    h.engine.destroy();
  });
});

describe('XLogEngine resize and devicePixelRatio changes (docs/15 §7.2, §15 좌표 변환)', () => {
  it('re-measures both canvases when the container is resized and redraws everything', () => {
    const onFrame = vi.fn<(sample: FrameSample) => void>();
    const buffer = gridBuffer();
    const h = setup({ buffer, onFrame });
    expect(h.resizeWatchers).toHaveLength(1);
    h.runFrames();
    // Realtime data alone: an incremental frame.
    buffer.append([point(FROM + 99_900, 10)]);
    h.runFrames();
    expect(onFrame.mock.lastCall?.[0]).toMatchObject({ full: false, pointsDrawn: true });

    // The container shrinks to a 600 × 300 plot.
    const width = 600 + PLOT_MARGIN.left + PLOT_MARGIN.right;
    const height = 300 + PLOT_MARGIN.top + PLOT_MARGIN.bottom;
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(
      rectOf(0, 0, width, height),
    );
    h.resizeWatchers[0]?.callback();
    expect(h.engine.layout).toMatchObject({ width, height, plotWidth: 600, plotHeight: 300 });
    expect([h.points.width, h.points.height]).toEqual([600, 300]);
    expect([h.points.style.width, h.points.style.height]).toEqual(['600px', '300px']);
    expect([h.overlay.width, h.overlay.height]).toEqual([width, height]);
    expect(h.engine.scale()).toMatchObject({ width: 600, height: 300 });
    h.overlayCtx().reset();
    expect(h.runFrames()).toBe(1);
    expect(onFrame.mock.lastCall?.[0]).toMatchObject({ full: true, pointsDrawn: true });
    // The overlay is cleared and drawn for the new size.
    expect(h.overlayCtx().named('clearRect')[0]?.args).toEqual([0, 0, width, height]);
    h.engine.destroy();
    expect(h.resizeWatchers[0]?.active).toBe(false);
  });

  it.each([
    { cap: undefined, label: 'default point layer cap' },
    { cap: 1.5, label: 'point layer cap 1.5' },
  ])('follows devicePixelRatio changes 1 → 2 → 1.25 ($label)', ({ cap }) => {
    const onFrame = vi.fn<(sample: FrameSample) => void>();
    const h = setup(cap === undefined ? { onFrame } : { onFrame, pointLayerMaxDpr: cap }, 1);
    const effective = (dpr: number) => pointLayerScale(dpr, cap ?? POINT_LAYER_MAX_DPR);
    const expectSizes = (dpr: number) => {
      expect(h.engine.layout.dpr).toBe(dpr);
      expect([h.overlay.width, h.overlay.height]).toEqual([
        Math.round(WIDTH * dpr),
        Math.round(HEIGHT * dpr),
      ]);
      expect([h.points.width, h.points.height]).toEqual([
        Math.round(PLOT_W * effective(dpr)),
        Math.round(PLOT_H * effective(dpr)),
      ]);
      // The CSS size does not change, so hit testing stays in CSS px.
      expect([h.points.style.width, h.points.style.height]).toEqual([
        `${String(PLOT_W)}px`,
        `${String(PLOT_H)}px`,
      ]);
    };
    expectSizes(1);
    expect(h.resolutionWatchers.map((w) => [w.dpr, w.active])).toEqual([[1, true]]);
    h.runFrames();
    for (const dpr of [2, 1.25]) {
      h.setDpr(dpr);
      h.resolutionWatchers.at(-1)?.callback();
      expectSizes(dpr);
      h.pointsCtx().reset();
      h.overlayCtx().reset();
      expect(h.runFrames()).toBe(1);
      expect(onFrame.mock.lastCall?.[0]).toMatchObject({ full: true, pointsDrawn: true });
      // Points are squares of the scaled point size; the overlay is drawn at the full ratio.
      const size = Math.max(1, Math.round(POINT_SIZE * effective(dpr)));
      expect(h.pointsCtx().named('rect')[0]?.args.slice(2)).toEqual([size, size]);
      expect(h.overlayCtx().named('setTransform')[0]?.args).toEqual([dpr, 0, 0, dpr, 0, 0]);
    }
    // One registration per ratio, each one for the new resolution query; the old ones stopped.
    expect(h.resolutionWatchers.map((w) => [w.dpr, w.active])).toEqual([
      [1, false],
      [2, false],
      [1.25, true],
    ]);
    // Re-measuring at the same ratio keeps the registration.
    h.resizeWatchers[0]?.callback();
    expect(h.resolutionWatchers).toHaveLength(3);
    h.engine.destroy();
    expect(h.resolutionWatchers.every((w) => !w.active)).toBe(true);
    expect(h.resizeWatchers.every((w) => !w.active)).toBe(true);
  });
});
