// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { RecordingContext } from '../../test/canvasMock';

import {
  CATEGORY_PALETTE,
  OTHER_COLOR,
  POINT_LAYER_MAX_DPR,
  POINT_SIZE,
  PointLayer,
  STATUS_COLORS,
  categoryColorMapping,
  drawOverlay,
  packColor,
  pointLayerScale,
  statusColorMapping,
  type ColorMapping,
  type DrawMode,
  type PointContext,
  type PointLayerStats,
} from './renderer';
import { createScale } from './scale';
import { STATUS_ERROR, STATUS_NORMAL, STATUS_SLOW, XLogBuffer, type XLogPoint } from './XLogBuffer';

const FROM = 1_700_000_000_000;
const TO = FROM + 1000;

function point(ts: number, elapsed: number, status: number, extra: Partial<XLogPoint> = {}) {
  return {
    ts,
    elapsed,
    status,
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

function layer(ctx: RecordingContext, width = 100, height = 50, dpr = 1): PointLayer {
  const canvas = document.createElement('canvas');
  const result = new PointLayer(canvas, ctx as unknown as PointContext);
  result.resize(width, height, dpr);
  return result;
}

const dictionary = {
  services: ['a', 'b', 'c'],
  instances: ['i0', 'i1'],
  errorClasses: null,
};

describe('PointLayer (docs/15 §7.2)', () => {
  it('draws one path and one fill per status colour: 3 passes for 3 statuses', () => {
    const buffer = new XLogBuffer(100);
    buffer.append([
      point(FROM + 100, 10, STATUS_NORMAL),
      point(FROM + 200, 20, STATUS_ERROR),
      point(FROM + 300, 30, STATUS_SLOW),
      point(FROM + 400, 40, STATUS_NORMAL),
      point(FROM + 500, 50, STATUS_ERROR),
    ]);
    const ctx = new RecordingContext();
    const stats = layer(ctx).draw({
      buffer,
      scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
      mapping: statusColorMapping(),
      mode: 'path',
      incremental: false,
    });
    expect(stats).toMatchObject({ full: true, drawn: 5, passes: 3 });
    expect(ctx.count('beginPath')).toBe(3);
    expect(ctx.count('fill')).toBe(3);
    expect(ctx.count('rect')).toBe(5);
    // Pass order normal -> slow -> error, so errors are drawn on top.
    expect(ctx.named('fill').map((call) => call.fillStyle)).toEqual([...STATUS_COLORS]);
    // Every rect is one point size (DPR 1) and inside the device canvas.
    for (const call of ctx.named('rect')) {
      const [x, y, w, h] = call.args as number[];
      expect(w).toBe(POINT_SIZE);
      expect(h).toBe(POINT_SIZE);
      expect(x).toBeGreaterThanOrEqual(-POINT_SIZE);
      expect(y).toBeGreaterThanOrEqual(-POINT_SIZE);
    }
  });

  it('skips points outside the time range and empty colour passes', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([
      point(FROM - 1, 10, STATUS_ERROR),
      point(FROM + 10, 10, STATUS_NORMAL),
      point(TO + 1, 10, STATUS_SLOW),
    ]);
    const ctx = new RecordingContext();
    const stats = layer(ctx).draw({
      buffer,
      scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
      mapping: statusColorMapping(),
      mode: 'path',
      incremental: false,
    });
    expect(stats).toMatchObject({ drawn: 1, passes: 1 });
    expect(ctx.count('fill')).toBe(1);
  });

  it('scales the device canvas and the point size with devicePixelRatio up to the cap', () => {
    const canvas = document.createElement('canvas');
    const ctx = new RecordingContext();
    const pointLayer = new PointLayer(canvas, ctx as unknown as PointContext);
    // Cap 3: DPR 2 is used in full.
    pointLayer.resize(300, 120, 2, 3);
    expect(pointLayer.scaleFactor).toBe(2);
    expect(canvas.width).toBe(600);
    expect(canvas.height).toBe(240);
    expect(pointLayer.deviceSize).toEqual({ width: 600, height: 240 });
    const buffer = new XLogBuffer(4);
    buffer.append([point(FROM + 500, 50, STATUS_NORMAL)]);
    pointLayer.draw({
      buffer,
      scale: createScale(FROM, TO, 300, 120, 100, 'linear'),
      mapping: statusColorMapping(),
      mode: 'path',
      incremental: false,
    });
    const [x, y, w] = ctx.named('rect')[0]?.args as number[];
    expect(w).toBe(POINT_SIZE * 2);
    // Centre at device (300, 120): x = 300 - size/2, y = 120 - size/2.
    expect(x).toBe(300 - POINT_SIZE);
    expect(y).toBe(120 - POINT_SIZE);
    pointLayer.resize(300, 120, 1.25, 3);
    expect(canvas.width).toBe(375);
    expect(canvas.height).toBe(150);
  });

  it.each([1, 1.25, 1.5])(
    'caps the backing store scale at DPR 2 with cap %s (ADR-0003 C-34)',
    (cap) => {
      const canvas = document.createElement('canvas');
      const ctx = new RecordingContext();
      const pointLayer = new PointLayer(canvas, ctx as unknown as PointContext);
      pointLayer.resize(301, 121, 2, cap);
      expect(pointLayer.scaleFactor).toBe(cap);
      expect(canvas.width).toBe(Math.round(301 * cap));
      expect(canvas.height).toBe(Math.round(121 * cap));
      const buffer = new XLogBuffer(4);
      buffer.append([point(FROM + 500, 50, STATUS_NORMAL)]);
      pointLayer.draw({
        buffer,
        scale: createScale(FROM, TO, 301, 121, 100, 'linear'),
        mapping: statusColorMapping(),
        mode: 'pixels',
        incremental: false,
      });
      const image = ctx.lastImage as ImageData;
      expect(image.width).toBe(Math.round(301 * cap));
      // The point is POINT_SIZE CSS px at the capped scale: round(2 × cap) backing px wide.
      const size = Math.round(POINT_SIZE * cap);
      const data = new Uint32Array(image.data.buffer);
      expect(Array.from(data).filter((v) => v !== 0)).toHaveLength(size * size);
    },
  );

  it('uses the default cap POINT_LAYER_MAX_DPR and never scales below DPR 1', () => {
    const canvas = document.createElement('canvas');
    const pointLayer = new PointLayer(canvas, new RecordingContext() as unknown as PointContext);
    pointLayer.resize(300, 120, 2);
    expect(pointLayer.scaleFactor).toBe(Math.min(2, POINT_LAYER_MAX_DPR));
    expect(canvas.width).toBe(Math.round(300 * Math.min(2, POINT_LAYER_MAX_DPR)));
    // A DPR 3 screen is capped.
    expect(POINT_LAYER_MAX_DPR).toBeLessThan(3);
    pointLayer.resize(300, 120, 3);
    expect(pointLayer.scaleFactor).toBe(POINT_LAYER_MAX_DPR);
    expect(canvas.width).toBe(Math.round(300 * POINT_LAYER_MAX_DPR));
    pointLayer.resize(300, 120, 1);
    expect(pointLayer.scaleFactor).toBe(1);
    expect(canvas.width).toBe(300);
    expect(pointLayerScale(3, 1.5)).toBe(1.5);
    expect(pointLayerScale(1.25, 1.5)).toBe(1.25);
    expect(pointLayerScale(0, 1.5)).toBe(1);
    expect(pointLayerScale(2, Number.NaN)).toBe(Math.min(2, POINT_LAYER_MAX_DPR));
  });

  it('uses fillRect per point in rect mode (alternative strategy)', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(FROM + 1, 1, 0), point(FROM + 2, 2, 2)]);
    const ctx = new RecordingContext();
    layer(ctx).draw({
      buffer,
      scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
      mapping: statusColorMapping(),
      mode: 'rect',
      incremental: false,
    });
    expect(ctx.count('fillRect')).toBe(2);
    expect(ctx.count('fill')).toBe(0);
  });

  it('writes pixels into ImageData in pixels mode (alternative strategy)', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(FROM + 500, 50, STATUS_ERROR)]);
    const ctx = new RecordingContext();
    layer(ctx, 100, 50).draw({
      buffer,
      scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
      mapping: statusColorMapping(),
      mode: 'pixels',
      incremental: false,
    });
    expect(ctx.count('putImageData')).toBe(1);
    const image = ctx.lastImage;
    expect(image).not.toBeNull();
    const data = new Uint32Array((image as ImageData).data.buffer);
    const red = packColor(STATUS_COLORS[STATUS_ERROR] ?? '', true);
    const painted = Array.from(data).filter((v) => v !== 0);
    expect(painted).toHaveLength(POINT_SIZE * POINT_SIZE);
    expect(painted.every((v) => v === red)).toBe(true);
    // Centre (50, 25) -> square [49, 51) × [24, 26).
    expect(data[24 * 100 + 49]).toBe(red);
    expect(data[25 * 100 + 50]).toBe(red);
  });

  describe('pixels mode with the row cache (perf-3): y = log, colour = service', () => {
    const SERVICES = Array.from({ length: 20 }, (_, i) => `svc-${String(i)}`);

    /** Points with services 0..19, every status, response times from 0 to beyond the axis maximum,
     *  and time stamps at and outside both ends of the range. */
    function serviceBuffer(capacity: number, count: number, seed: number): XLogBuffer {
      const buffer = new XLogBuffer(capacity);
      buffer.append(Array.from({ length: count }, (_, i) => pseudoPoint(i, seed)));
      return buffer;
    }

    function pseudoPoint(i: number, seed: number): XLogPoint {
      const r = (k: number) => {
        const v = Math.sin((i + 1) * 12.9898 + seed * 78.233 + k * 37.719) * 43758.5453;
        return v - Math.floor(v);
      };
      const ts = i % 97 === 0 ? FROM : i % 89 === 0 ? TO : FROM - 50 + Math.floor(r(1) * 1100);
      const elapsed = i % 53 === 0 ? 0 : Math.floor(Math.exp(r(2) * 8));
      return point(ts, elapsed, Math.floor(r(3) * 3), {
        service: Math.floor(r(4) * r(4) * SERVICES.length),
      });
    }

    /**
     * Independent reference: docs/15 §7.2 coordinates, slot order, later points on top. Draws the
     * points from logical index `firstLogical` on over `base` (a copy), or on an empty image.
     */
    function reference(
      buffer: XLogBuffer,
      scale: ReturnType<typeof createScale>,
      mapping: ColorMapping,
      width: number,
      height: number,
      layerScale: number,
      firstLogical = 0,
      base: Uint32Array | null = null,
    ): Uint32Array {
      const out = base === null ? new Uint32Array(width * height) : base.slice();
      const size = Math.max(1, Math.round(POINT_SIZE * layerScale));
      const half = size >> 1;
      const yK = scale.yType === 'log' ? height / Math.log10(scale.yMax + 1) : height / scale.yMax;
      const entries: { slot: number; logical: number; x0: number; y0: number }[] = [];
      for (let logical = firstLogical; logical < buffer.size; logical += 1) {
        const p = buffer.physical(logical);
        const t = buffer.ts[p] ?? 0;
        if (t < scale.from || t > scale.to) {
          continue;
        }
        const key = buffer.service[p] ?? 0;
        let slot = mapping.slotOfKey === null ? 0 : (mapping.slotOfKey[key] ?? 0);
        if (slot >= mapping.colors.length) {
          slot = 0;
        }
        const e = buffer.elapsed[p] ?? 0;
        const yh = Math.min(height, (scale.yType === 'log' ? Math.log10(e + 1) : e) * yK);
        entries.push({
          slot,
          logical,
          x0: Math.round(((t - scale.from) * width) / (scale.to - scale.from)) - half,
          y0: Math.round(height - yh) - half,
        });
      }
      entries.sort((a, b) => a.slot - b.slot || a.logical - b.logical);
      for (const { slot, x0, y0 } of entries) {
        const color = packColor(mapping.colors[slot] ?? OTHER_COLOR, true);
        for (let y = Math.max(0, y0); y < Math.min(height, y0 + size); y += 1) {
          for (let x = Math.max(0, x0); x < Math.min(width, x0 + size); x += 1) {
            out[y * width + x] = color;
          }
        }
      }
      return out;
    }

    function pixels(ctx: RecordingContext): Uint32Array {
      return new Uint32Array((ctx.lastImage as ImageData).data.buffer).slice();
    }

    // ~1.7 s idle (6 frames vs. a per-pixel reference); 5 s default is too tight on a busy host.
    it.each([
      { dpr: 1, cap: 1, label: 'DPR 1 (2 px points, direct writes)' },
      { dpr: 2, cap: 1.5, label: 'DPR 2 capped at 1.5 (3 px points)' },
    ])(
      'draws the same pixels as the reference with a cold and a warm cache: $label',
      ({ dpr, cap }) => {
        const buffer = serviceBuffer(5000, 4000, 7);
        const mapping = categoryColorMapping('service', buffer, {
          ...dictionary,
          services: SERVICES,
        });
        const ctx = new RecordingContext();
        const pointLayer = new PointLayer(
          document.createElement('canvas'),
          ctx as unknown as PointContext,
        );
        pointLayer.resize(200, 100, dpr, cap);
        const { width, height } = pointLayer.deviceSize;
        const scaleOf = (from: number, yType: 'linear' | 'log') =>
          createScale(from, from + 1000, 200, 100, 1000, yType);
        const draw = (scale: ReturnType<typeof createScale>) => {
          pointLayer.draw({ buffer, scale, mapping, mode: 'pixels', incremental: false });
          return pixels(ctx);
        };

        const log = scaleOf(FROM, 'log');
        const cold = draw(log);
        expect(cold.some((v) => v !== 0)).toBe(true);
        expect(cold).toEqual(reference(buffer, log, mapping, width, height, cap));
        // Warm cache: the same frame again.
        expect(draw(log)).toEqual(cold);
        // Range change: x moves, the cached rows are reused.
        const moved = scaleOf(FROM + 137, 'log');
        expect(draw(moved)).toEqual(reference(buffer, moved, mapping, width, height, cap));
        // New points (ring wraps): only their rows are computed.
        buffer.append(Array.from({ length: 1500 }, (_, i) => pseudoPoint(10_000 + i, 7)));
        expect(buffer.overwritten).toBeGreaterThan(0);
        expect(draw(moved)).toEqual(reference(buffer, moved, mapping, width, height, cap));
        // Axis type change invalidates the rows.
        const linear = scaleOf(FROM + 137, 'linear');
        expect(draw(linear)).toEqual(reference(buffer, linear, mapping, width, height, cap));
        // A cleared and refilled buffer (new generation) invalidates them as well.
        buffer.clear();
        buffer.append(Array.from({ length: 300 }, (_, i) => pseudoPoint(20_000 + i, 7)));
        expect(draw(linear)).toEqual(reference(buffer, linear, mapping, width, height, cap));
      },
      30_000,
    );

    it('draws a realtime increment as the retained image plus the new points on top', () => {
      // Realtime-like data: every point inside the range, time stamps in arrival order.
      const buffer = new XLogBuffer(6000);
      buffer.append(
        Array.from({ length: 3000 }, (_, i) => ({ ...pseudoPoint(i, 11), ts: FROM + i / 4 })),
      );
      const mapping = categoryColorMapping('service', buffer, {
        ...dictionary,
        services: SERVICES,
      });
      const ctx = new RecordingContext();
      const pointLayer = layer(ctx, 200, 100);
      const scale = createScale(FROM, TO, 200, 100, 1000, 'log');
      pointLayer.draw({ buffer, scale, mapping, mode: 'pixels', incremental: true });
      const before = pixels(ctx);
      expect(before).toEqual(reference(buffer, scale, mapping, 200, 100, 1));
      // Realtime-like new points at the right end of the range, no time shift: their rows are
      // computed, the others reused.
      buffer.append(
        Array.from({ length: 50 }, (_, i) => ({
          ...pseudoPoint(30_000 + i, 11),
          ts: FROM + 950 + i,
        })),
      );
      const stats = pointLayer.draw({ buffer, scale, mapping, mode: 'pixels', incremental: true });
      expect(stats.full).toBe(false);
      expect(pixels(ctx)).toEqual(
        reference(buffer, scale, mapping, 200, 100, 1, buffer.size - 50, before),
      );
    });
  });

  it('shifts the layer and draws only new points when only time moved (realtime, docs/15 §7.5)', () => {
    const buffer = new XLogBuffer(100);
    buffer.append([point(FROM + 100, 10, 0), point(FROM + 200, 10, 0)]);
    const ctx = new RecordingContext();
    const pointLayer = layer(ctx, 100, 50);
    const mapping = statusColorMapping();
    const first = pointLayer.draw({
      buffer,
      scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
      mapping,
      mode: 'path',
      incremental: true,
    });
    expect(first.full).toBe(true);
    ctx.reset();
    buffer.append([point(FROM + 1050, 10, 2)]);
    const second = pointLayer.draw({
      buffer,
      scale: createScale(FROM + 100, TO + 100, 100, 50, 100, 'linear'),
      mapping,
      mode: 'path',
      incremental: true,
    });
    // 100 ms of a 1,000 ms range on 100 px = 10 px shift; one new point drawn.
    expect(second).toMatchObject({ full: false, drawn: 1, shifted: 10 });
    const draw = ctx.named('drawImage')[0];
    expect(draw?.args.slice(1)).toEqual([-10, 0, 'copy']);
    expect(ctx.count('rect')).toBe(1);
    expect(ctx.count('clearRect')).toBe(1);
  });

  it('redraws completely when the style, size or buffer generation changes', () => {
    const buffer = new XLogBuffer(100);
    buffer.append([point(FROM + 100, 10, 0)]);
    const ctx = new RecordingContext();
    const pointLayer = layer(ctx, 100, 50);
    const scale = createScale(FROM, TO, 100, 50, 100, 'linear');
    const input = { buffer, scale, mapping: statusColorMapping(), mode: 'path' as const };
    expect(pointLayer.draw({ ...input, incremental: true }).full).toBe(true);
    expect(pointLayer.draw({ ...input, incremental: true }).full).toBe(false);
    expect(
      pointLayer.draw({ ...input, scale: { ...scale, yType: 'log' }, incremental: true }).full,
    ).toBe(true);
    buffer.clear();
    expect(pointLayer.draw({ ...input, incremental: true }).full).toBe(true);
    expect(pointLayer.draw({ ...input, incremental: false }).full).toBe(true);
    expect(pointLayer.draw({ ...input, incremental: true, forceFull: true }).full).toBe(true);
  });

  it('does nothing without a context (jsdom) or with a zero-size plot', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(FROM + 1, 1, 0)]);
    const noContext = new PointLayer(null, null);
    noContext.resize(100, 50, 1);
    expect(
      noContext.draw({
        buffer,
        scale: createScale(FROM, TO, 100, 50, 100, 'linear'),
        mapping: statusColorMapping(),
        mode: 'path',
        incremental: false,
      }),
    ).toMatchObject({ drawn: 0, passes: 0 });
  });
});

/**
 * A context that rasterises what the point layer draws (rect + fill, fillRect, putImageData, the
 * drawImage 'copy' shift, clearRect) into one value per pixel, so a test can check which points
 * are on the layer, whatever the drawing mode.
 */
class RasterContext {
  fillStyle: string | CanvasGradient | CanvasPattern = '#000000';
  globalCompositeOperation = 'source-over';
  private readonly canvas: HTMLCanvasElement;
  private pixels = new Uint32Array(0);
  private width = 0;
  private height = 0;
  private path: [number, number, number, number][] = [];
  private readonly colorIds = new Map<string, number>();

  constructor(canvas: HTMLCanvasElement) {
    this.canvas = canvas;
  }

  isPainted(x: number, y: number): boolean {
    return (this.grid()[y * this.width + x] ?? 0) !== 0;
  }

  setTransform(): void {
    // The point layer only sets the identity transform.
  }
  clearRect(x: number, y: number, w: number, h: number): void {
    this.paint(x, y, w, h, 0);
  }
  beginPath(): void {
    this.path = [];
  }
  rect(x: number, y: number, w: number, h: number): void {
    this.path.push([x, y, w, h]);
  }
  fill(): void {
    const id = this.colorId();
    for (const [x, y, w, h] of this.path) {
      this.paint(x, y, w, h, id);
    }
  }
  fillRect(x: number, y: number, w: number, h: number): void {
    this.paint(x, y, w, h, this.colorId());
  }
  drawImage(_source: unknown, dx: number, dy: number): void {
    // The realtime shift: the layer copied onto itself, moved left.
    expect(this.globalCompositeOperation).toBe('copy');
    expect(dx).toBeLessThanOrEqual(0);
    expect(dy).toBe(0);
    const grid = this.grid();
    for (let row = 0; row < this.height; row += 1) {
      const start = row * this.width;
      grid.copyWithin(start, start - dx, start + this.width);
      grid.fill(0, start + this.width + dx, start + this.width);
    }
  }
  createImageData(width: number, height: number): ImageData {
    return { width, height, data: new Uint8ClampedArray(width * height * 4), colorSpace: 'srgb' };
  }
  putImageData(image: ImageData, dx: number, dy: number): void {
    expect([dx, dy]).toEqual([0, 0]);
    const grid = this.grid();
    grid.set(new Uint32Array(image.data.buffer, 0, grid.length));
  }
  getImageData(): ImageData {
    return this.createImageData(1, 1);
  }

  private grid(): Uint32Array {
    if (this.canvas.width !== this.width || this.canvas.height !== this.height) {
      this.width = this.canvas.width;
      this.height = this.canvas.height;
      this.pixels = new Uint32Array(this.width * this.height);
    }
    return this.pixels;
  }

  private colorId(): number {
    const key = typeof this.fillStyle === 'string' ? this.fillStyle : 'pattern';
    const id = this.colorIds.get(key) ?? this.colorIds.size + 1;
    this.colorIds.set(key, id);
    return id;
  }

  private paint(x: number, y: number, w: number, h: number, value: number): void {
    const grid = this.grid();
    const x0 = Math.max(0, x);
    const x1 = Math.min(this.width, x + w);
    for (let row = Math.max(0, y); row < Math.min(this.height, y + h); row += 1) {
      if (x1 > x0) {
        grid.fill(value, row * this.width + x0, row * this.width + x1);
      }
    }
  }
}

// Realtime rig (docs/15 §7.5): 1254 px plot, 300 s window that moves 1 s at a time (4.18 px, so
// the shift is fractional), 500 points/s in 100 ms batches, like realtimeSim.ts.
const RT_WIDTH = 1254;
const RT_HEIGHT = 520;
const RT_SPAN = 300_000;
const RT_STEP = 1000;
const RT_BATCH_MS = 100;
const RT_BATCH_POINTS = 50;
/**
 * A small epoch keeps the oracle's float arithmetic (left edge of the layer + x of every point)
 * exact; the fractional shift does not depend on the magnitude of the time stamps.
 */
const RT_T0 = 10_000_000;
/**
 * Point i has elapsed 8 + 2 × (i mod 250): with y max = height, consecutive points sit 2 rows
 * apart, and two points on the same row are 500 ms (2.09 px) apart. So no other 2×2 square covers
 * the top-left pixel of a point, and "that pixel is painted" means "this point was drawn".
 */
const RT_ROWS = 250;

class RealtimeRig {
  readonly buffer = new XLogBuffer(200_000);
  readonly frames: PointLayerStats[] = [];
  now = RT_T0;
  from = 0;
  to = 0;
  private readonly mode: DrawMode;
  private readonly ctx: RasterContext;
  private readonly layer: PointLayer;
  private readonly mapping = statusColorMapping();
  private seq = 0;
  /** Time at the left edge of the layer, followed from the frame stats as the renderer does. */
  private origin = 0;
  private drawnTo = 0;

  constructor(mode: DrawMode, start: number = RT_T0) {
    this.mode = mode;
    const canvas = document.createElement('canvas');
    this.ctx = new RasterContext(canvas);
    this.layer = new PointLayer(canvas, this.ctx as unknown as PointContext);
    this.layer.resize(RT_WIDTH, RT_HEIGHT, 1);
    this.now = start;
    this.moveWindow();
  }

  /** One batch: 50 points spread over (now - 100 ms, now]. */
  batch(): void {
    this.now += RT_BATCH_MS;
    const points: XLogPoint[] = [];
    for (let k = 1; k <= RT_BATCH_POINTS; k += 1) {
      const elapsed = 8 + 2 * (this.seq % RT_ROWS);
      this.seq += 1;
      points.push(point(this.now - RT_BATCH_MS + (k * RT_BATCH_MS) / RT_BATCH_POINTS, elapsed, 0));
    }
    this.buffer.append(points);
  }

  /** Window end one step ahead of the clock (realtimeSim.liveWindowEnd); true if it moved. */
  moveWindow(): boolean {
    const end = Math.floor(this.now / RT_STEP) * RT_STEP + RT_STEP;
    if (end === this.to) {
      return false;
    }
    this.to = end;
    this.from = end - RT_SPAN;
    return true;
  }

  frame(forceFull = false): PointLayerStats {
    const scale = createScale(this.from, this.to, RT_WIDTH, RT_HEIGHT, RT_HEIGHT, 'linear');
    const stats = this.layer.draw({
      buffer: this.buffer,
      scale,
      mapping: this.mapping,
      mode: this.mode,
      incremental: true,
      forceFull,
    });
    const span = scale.to - scale.from;
    if (stats.full) {
      this.origin = scale.from;
      this.drawnTo = scale.to;
    } else {
      if (stats.shifted > 0) {
        this.origin += stats.shifted / (RT_WIDTH / span);
      }
      this.drawnTo = this.origin + span;
    }
    this.frames.push(stats);
    return stats;
  }

  /** Points inside the window the layer shows that are not on it; also counts the checked ones. */
  check(): { checked: number; missing: number; firstMissing: number[] } {
    const { buffer } = this;
    const from = this.origin;
    const to = this.drawnTo;
    const pxPerMs = RT_WIDTH / (to - from);
    const missing: number[] = [];
    let checked = 0;
    for (let logical = 0; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const t = buffer.ts[p] ?? 0;
      if (t < from || t > to) {
        continue;
      }
      checked += 1;
      // Same rounding as the renderer; the edge columns of a clipped square still show.
      const x0 = ((((t - from) * pxPerMs + 0.5) | 0) - POINT_SIZE / 2) | 0;
      const x = Math.min(RT_WIDTH - 1, Math.max(0, x0));
      const y = RT_HEIGHT - (buffer.elapsed[p] ?? 0) - POINT_SIZE / 2;
      if (!this.ctx.isPainted(x, y)) {
        missing.push(t - RT_T0);
      }
    }
    return { checked, missing: missing.length, firstMissing: missing.slice(0, 5) };
  }

  /** Asserts that every point inside the shown window is drawn; returns how many were checked. */
  expectComplete(): number {
    const result = this.check();
    expect({ missing: result.missing, firstMissing: result.firstMissing }).toEqual({
      missing: 0,
      firstMissing: [],
    });
    return result.checked;
  }

  /** The newest point lies inside the window the layer shows. */
  showsEverything(): boolean {
    return this.now <= this.drawnTo;
  }
}

const DRAW_MODES: readonly DrawMode[] = ['path', 'rect', 'pixels'];

describe('PointLayer realtime: no new point inside the window is lost (docs/15 §7.5)', () => {
  // ~2.5 s idle (600 frames, each fully checked); 5 s default is too tight on a busy host.
  it.each(DRAW_MODES)(
    '%s: 60 s at 500 points/s with a fractional shift per 1 s window move',
    (mode) => {
      const rig = new RealtimeRig(mode);
      expect(rig.frame().full).toBe(true);
      for (let i = 0; i < 600; i += 1) {
        rig.batch();
        rig.moveWindow();
        rig.frame();
        rig.expectComplete();
      }
      expect(rig.showsEverything()).toBe(true);
      expect(rig.expectComplete()).toBe(30_000);
      // Every frame after the first was incremental, shifted by 4 or 5 px (4.18 px per second).
      const later = rig.frames.slice(1);
      expect(later.every((frame) => !frame.full)).toBe(true);
      expect(new Set(later.filter((f) => f.shifted > 0).map((f) => f.shifted))).toEqual(
        new Set([4, 5]),
      );
    },
    30_000,
  );

  it.each(DRAW_MODES)(
    '%s: a frame between the append and the window move draws the late points later',
    (mode) => {
      // Batches end 50 ms after each step, so every 10th batch reaches past the window end
      // until the window moves.
      const rig = new RealtimeRig(mode, RT_T0 + 50);
      rig.frame();
      let ahead = 0;
      for (let i = 0; i < 300; i += 1) {
        rig.batch();
        if (rig.now > rig.to) {
          ahead += 1;
        }
        rig.frame();
        rig.expectComplete();
        if (rig.moveWindow()) {
          rig.frame();
          rig.expectComplete();
        }
      }
      expect(ahead).toBe(30);
      expect(rig.showsEverything()).toBe(true);
      expect(rig.expectComplete()).toBe(15_000);
      expect(rig.frames.slice(1).every((frame) => !frame.full)).toBe(true);
    },
  );

  it.each(DRAW_MODES)(
    '%s: points that arrive while the range is paused appear on resume',
    (mode) => {
      const rig = new RealtimeRig(mode);
      rig.frame();
      for (let i = 0; i < 100; i += 1) {
        rig.batch();
        rig.moveWindow();
        rig.frame();
      }
      // Pause for 20 s: the range stands still, batches keep arriving (and pass its end).
      const pausedTo = rig.to;
      for (let i = 0; i < 200; i += 1) {
        rig.batch();
        // A full redraw in the middle (resize, colour change) must keep the pending points too.
        rig.frame(i === 100);
        rig.expectComplete();
      }
      expect(rig.now - pausedTo).toBeGreaterThan(18_000);
      expect(rig.showsEverything()).toBe(false);
      // Resume: the window jumps by the paused time; one incremental frame catches up.
      expect(rig.moveWindow()).toBe(true);
      const resume = rig.frame();
      expect(resume.full).toBe(false);
      expect(resume.shifted).toBeGreaterThan(80);
      expect(rig.showsEverything()).toBe(true);
      expect(rig.expectComplete()).toBe(15_000);
      for (let i = 0; i < 50; i += 1) {
        rig.batch();
        rig.moveWindow();
        rig.frame();
      }
      expect(rig.expectComplete()).toBe(17_500);
    },
  );

  it.each(DRAW_MODES)(
    '%s: a resume farther than the layer width redraws everything, with every point',
    (mode) => {
      const rig = new RealtimeRig(mode);
      rig.frame();
      for (let i = 0; i < 100; i += 1) {
        rig.batch();
        rig.moveWindow();
        rig.frame();
      }
      // Pause for 310 s, longer than the 300 s window: the shift would exceed the layer width.
      for (let i = 0; i < 3100; i += 1) {
        rig.batch();
        if (i % 100 === 99) {
          expect(rig.frame().full).toBe(false);
        }
      }
      expect(rig.moveWindow()).toBe(true);
      expect(rig.frame()).toMatchObject({ full: true, shifted: 0 });
      expect(rig.showsEverything()).toBe(true);
      // Every point of the last 300 s (the new window) is on the layer.
      expect(rig.expectComplete()).toBeGreaterThan(149_000);
    },
  );
});

describe('colour mappings (docs/05 §2.1: status / instance / top N services)', () => {
  it('maps status to 3 colours', () => {
    const mapping = statusColorMapping();
    expect(mapping.colors).toEqual(STATUS_COLORS);
    expect(mapping.legend.map((entry) => entry.value)).toEqual([0, 1, 2]);
  });

  it('colours the most frequent services and greys the rest (at most 13 passes)', () => {
    const services = Array.from({ length: 20 }, (_, i) => `svc-${String(i)}`);
    const buffer = new XLogBuffer(1000);
    const points: XLogPoint[] = [];
    // Service i appears i + 1 times: the most frequent are 19, 18, ...
    services.forEach((_, s) => {
      for (let k = 0; k <= s; k += 1) {
        points.push(point(FROM + points.length, 5, 0, { service: s }));
      }
    });
    buffer.append(points);
    const mapping = categoryColorMapping('service', buffer, { ...dictionary, services });
    expect(mapping.colors).toHaveLength(CATEGORY_PALETTE.length + 1);
    expect(mapping.colors[0]).toBe(OTHER_COLOR);
    expect(mapping.slotOfKey?.[19]).toBe(1);
    expect(mapping.slotOfKey?.[8]).toBe(12);
    expect(mapping.slotOfKey?.[7]).toBe(0);
    expect(mapping.legend[0]).toEqual({ color: CATEGORY_PALETTE[0], kind: 'key', value: 19 });
    expect(mapping.legend.at(-1)).toEqual({ color: OTHER_COLOR, kind: 'other', value: -1 });

    const ctx = new RecordingContext();
    const stats = layer(ctx).draw({
      buffer,
      scale: createScale(FROM, FROM + points.length, 100, 50, 100, 'linear'),
      mapping,
      mode: 'path',
      incremental: false,
    });
    expect(stats.passes).toBe(13);
    expect(ctx.count('fill')).toBe(13);
  });

  it('colours instances without an "other" entry when all fit', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(FROM, 1, 0, { instance: 1 }), point(FROM, 1, 0, { instance: 1 })]);
    const mapping = categoryColorMapping('instance', buffer, dictionary);
    expect(mapping.legend).toEqual([{ color: CATEGORY_PALETTE[0], kind: 'key', value: 1 }]);
  });

  it('packs colours for little- and big-endian pixel views', () => {
    expect(packColor('#102030', true)).toBe(0xff302010);
    expect(packColor('#102030', false)).toBe(0x102030ff);
  });
});

describe('drawOverlay', () => {
  it('draws axis labels, the threshold line, the selection and the hover ring', () => {
    const ctx = new RecordingContext();
    drawOverlay(ctx, {
      layout: { width: 400, height: 200, dpr: 2 },
      scale: createScale(FROM, TO, 320, 160, 2000, 'linear'),
      xTicks: [FROM + 500],
      formatTime: () => 'T',
      formatDuration: (ms) => `${String(ms)}ms`,
      threshold: 1000,
      thresholdLabel: 'limit',
      selection: { t1: FROM, t2: FROM + 500, e1: 0, e2: 1000 },
      drag: null,
      hover: { x: 10, y: 10 },
    });
    expect(ctx.named('setTransform')[0]?.args).toEqual([2, 0, 0, 2, 0, 0]);
    expect(ctx.texts).toEqual(expect.arrayContaining(['0ms', '2000ms', 'T', 'limit']));
    expect(ctx.count('strokeRect')).toBe(1);
    expect(ctx.count('arc')).toBe(1);
  });

  it('hides the threshold line above the axis maximum', () => {
    const ctx = new RecordingContext();
    drawOverlay(ctx, {
      layout: { width: 400, height: 200, dpr: 1 },
      scale: createScale(FROM, TO, 320, 160, 500, 'linear'),
      xTicks: [],
      formatTime: () => 'T',
      formatDuration: () => 'd',
      threshold: 1000,
      thresholdLabel: 'limit',
      selection: null,
      drag: null,
      hover: null,
    });
    expect(ctx.texts).not.toContain('limit');
  });
});
