// SPDX-License-Identifier: Apache-2.0
//
// XLog renderer (docs/15 §7.2). Two stacked canvases:
//   - the point layer, redrawn only when data, range, size or style change. Points are drawn in
//     one pass per colour (3 passes for status, at most 13 for the palette modes). The default
//     mode is "pixels" (ADR-0003 C-34, the docs/15 §18 #2 alternative): squares are written into
//     an ImageData through a Uint32Array view and put once. "path" (the docs/15 §7.2 original:
//     rect() accumulated into one path, one fill per colour) and "rect" (fillRect per point) are
//     kept for comparison and diagnosis; in Chrome their GPU rasterisation exceeds 16 ms at
//     50,000 points and more (docs/poc/xlog-ui.md).
//     The point layer's backing store scale is capped at POINT_LAYER_MAX_DPR on high-DPR screens
//     (C-34); the canvas keeps its CSS size and the browser scales it up.
//     In realtime mode the layer is shifted with drawImage and only new points are drawn
//     (docs/15 §7.5); any other change redraws it completely.
//   - the overlay (axes, threshold line, selection, drag rectangle, hover ring), cheap to redraw,
//     always at the full devicePixelRatio.
// Only numbers are drawn on the canvases; collected strings (service names, …) are rendered by
// React as text nodes (SEC-60).
import {
  dataRectToPixels,
  linearTicks,
  logTicks,
  type DataRect,
  type PixelRect,
  type PlotScale,
  yOf,
  xOf,
} from './scale';
import {
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  type XLogBuffer,
  type XLogDictionary,
} from './XLogBuffer';

/** Point edge length in CSS px (one size, docs/05 §2.1, docs/15 §7.2). */
export const POINT_SIZE = 2;

/**
 * Upper bound of the point layer's backing store scale (ADR-0003 C-34). On a screen with a higher
 * devicePixelRatio the point layer is drawn at this scale and the browser scales the canvas up to
 * its CSS size; the overlay (axes, text, selection, hover) keeps the full devicePixelRatio. The
 * value is the largest one with which 100,000 points stayed within 16 ms at DPR 2 in the WO-04
 * measurement (docs/poc/xlog-ui.md §4.7): 2, so a DPR 2 screen gets the full resolution and only
 * higher ratios are capped. 1.5 and 1 were measured as fallbacks with more headroom.
 */
export const POINT_LAYER_MAX_DPR = 2;

/** Backing store scale of the point layer for a devicePixelRatio and a cap (C-34). */
export function pointLayerScale(dpr: number, maxDpr: number = POINT_LAYER_MAX_DPR): number {
  const ratio = Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
  const cap = Number.isFinite(maxDpr) && maxDpr > 0 ? maxDpr : POINT_LAYER_MAX_DPR;
  return Math.min(ratio, cap);
}

/** Point colours by status (docs/15 §13 --point-normal, --point-slow, --point-error). */
export const STATUS_COLORS: readonly string[] = ['#3b82f6', '#f59e0b', '#dc2626'];

/**
 * Categorical palette for colouring by instance or service (docs/15 §7.2: 12 colours + grey).
 * Adjacent pairs pass the CVD and normal-vision separation checks on a white surface; colour is
 * never the only cue (the legend and the summary name every entity).
 */
export const CATEGORY_PALETTE: readonly string[] = [
  '#2a78d6',
  '#eb6834',
  '#1baf7a',
  '#eda100',
  '#e87ba4',
  '#008300',
  '#6250d6',
  '#e34948',
  '#2fa4c8',
  '#a8551c',
  '#7c9c00',
  '#b54bc4',
];
export const OTHER_COLOR = '#9ca3af';

export type ColorBy = 'status' | 'instance' | 'service';
export type DrawMode = 'path' | 'rect' | 'pixels';

export interface LegendEntry {
  readonly color: string;
  /** What the colour stands for: a status, a dictionary key, or "all others". */
  readonly kind: 'status' | 'key' | 'other';
  /** Status value (kind = status) or dictionary index (kind = key); -1 for other. */
  readonly value: number;
}

/**
 * Colour assignment. Slot s is drawn in pass s, so later slots are on top: status passes are
 * normal, slow, error (errors stay visible); palette modes draw "other" first.
 */
export interface ColorMapping {
  readonly colorBy: ColorBy;
  readonly colors: readonly string[];
  /** Dictionary index -> slot for instance/service; null for status (slot = status). */
  readonly slotOfKey: Uint8Array | null;
  readonly legend: readonly LegendEntry[];
}

export function statusColorMapping(): ColorMapping {
  return {
    colorBy: 'status',
    colors: STATUS_COLORS,
    slotOfKey: null,
    legend: [STATUS_NORMAL, STATUS_SLOW, STATUS_ERROR].map((status) => ({
      color: STATUS_COLORS[status] ?? OTHER_COLOR,
      kind: 'status' as const,
      value: status,
    })),
  };
}

/**
 * Colours the `maxColors` most frequent instances or services of the buffer (docs/05 §2.1 "서비스
 * 상위 N"); everything else is grey. The ranking is taken when the mapping is built and kept while
 * realtime data arrives, so an entity keeps its colour.
 */
export function categoryColorMapping(
  colorBy: 'instance' | 'service',
  buffer: XLogBuffer,
  dictionary: XLogDictionary,
  maxColors: number = CATEGORY_PALETTE.length,
): ColorMapping {
  const keyCount =
    colorBy === 'instance' ? dictionary.instances.length : dictionary.services.length;
  const column = colorBy === 'instance' ? buffer.instance : buffer.service;
  const counts = new Uint32Array(keyCount);
  for (let i = 0; i < buffer.size; i += 1) {
    const key = column[buffer.physical(i)] ?? 0;
    if (key < keyCount) {
      counts[key] = (counts[key] ?? 0) + 1;
    }
  }
  const ranked: number[] = [];
  for (let key = 0; key < keyCount; key += 1) {
    if ((counts[key] ?? 0) > 0) {
      ranked.push(key);
    }
  }
  ranked.sort((a, b) => (counts[b] ?? 0) - (counts[a] ?? 0) || a - b);
  const top = ranked.slice(0, Math.min(maxColors, CATEGORY_PALETTE.length));
  // Slot 0 = other (drawn first), slot r + 1 = rank r.
  const slotOfKey = new Uint8Array(keyCount);
  const colors = [OTHER_COLOR];
  const legend: LegendEntry[] = [];
  top.forEach((key, rank) => {
    slotOfKey[key] = rank + 1;
    const color = CATEGORY_PALETTE[rank] ?? OTHER_COLOR;
    colors.push(color);
    legend.push({ color, kind: 'key', value: key });
  });
  if (ranked.length > top.length) {
    legend.push({ color: OTHER_COLOR, kind: 'other', value: -1 });
  }
  return { colorBy, colors, slotOfKey, legend };
}

/** Parses '#rrggbb' into a 32-bit pixel for a Uint32Array view of ImageData (byte order RGBA). */
export function packColor(hex: string, littleEndian: boolean): number {
  const v = Number.parseInt(hex.slice(1, 7), 16);
  const r = (v >>> 16) & 0xff;
  const g = (v >>> 8) & 0xff;
  const b = v & 0xff;
  return littleEndian
    ? ((0xff << 24) | (b << 16) | (g << 8) | r) >>> 0
    : ((r << 24) | (g << 16) | (b << 8) | 0xff) >>> 0;
}

const LITTLE_ENDIAN = new Uint8Array(new Uint32Array([1]).buffer)[0] === 1;

export interface PointLayerInput {
  readonly buffer: XLogBuffer;
  readonly scale: PlotScale;
  readonly mapping: ColorMapping;
  readonly mode: DrawMode;
  /** Allow shift + new-points-only drawing when only time moved forward and data was appended. */
  readonly incremental: boolean;
  /** Force a full redraw (benchmarks). */
  readonly forceFull?: boolean;
}

export interface PointLayerStats {
  readonly full: boolean;
  /** Points drawn in this frame. */
  readonly drawn: number;
  /** Colour passes executed. */
  readonly passes: number;
  /** Device pixels the layer was shifted by (incremental frames). */
  readonly shifted: number;
}

/** The part of a 2D context the point layer uses (lets tests record calls). */
export type PointContext = Pick<
  CanvasRenderingContext2D,
  | 'setTransform'
  | 'clearRect'
  | 'beginPath'
  | 'rect'
  | 'fill'
  | 'fillRect'
  | 'drawImage'
  | 'createImageData'
  | 'putImageData'
  | 'getImageData'
> & { fillStyle: CanvasRenderingContext2D['fillStyle']; globalCompositeOperation: string };

interface ImageState {
  key: string;
  /** Time at the left edge of the retained image (trails the scale by < 1 device px). */
  from: number;
  /** End of the time window the last frame drew (`from` + span). */
  drawnTo: number;
  /** `buffer.appended` at the last frame: every point up to it was looked at. */
  appended: number;
  /**
   * Pending points: the last frame skipped them because they were after `drawnTo` (realtime data
   * ahead of the window). Counted from the tail of the buffer as of that frame: they are its
   * newest `pending` points, except the ones appended since. Counting from the tail keeps the
   * mark valid when old points are evicted from the head.
   */
  pending: number;
  generation: number;
  overwritten: number;
  buffer: XLogBuffer | null;
}

/** What the cached point rows (`PointLayer.rows`) were computed for. */
interface RowState {
  key: string;
  buffer: XLogBuffer | null;
  generation: number;
  appended: number;
}

/**
 * Draws the point layer. Owns per-frame scratch arrays (no allocation per frame once warm).
 * Device size is set by `resize`; coordinates are computed in device pixels and rounded, so every
 * point is a crisp square for any devicePixelRatio.
 */
export class PointLayer {
  private ctx: PointContext | null;
  private readonly canvas: HTMLCanvasElement | null;
  private width = 0;
  private height = 0;
  private dpr = 1;
  private visible = new Uint32Array(0);
  private slots = new Uint8Array(0);
  private order = new Uint32Array(0);
  private readonly counts = new Uint32Array(16);
  private readonly starts = new Uint32Array(17);
  private image: ImageData | null = null;
  private image32: Uint32Array | null = null;
  private state: ImageState = {
    key: '',
    from: 0,
    drawnTo: 0,
    appended: 0,
    pending: 0,
    generation: -1,
    overwritten: 0,
    buffer: null,
  };
  /** Top device row of each point's square, by physical buffer index (see `ensureRows`). */
  private rows = new Int32Array(0);
  private readonly rowState: RowState = { key: '', buffer: null, generation: -1, appended: 0 };

  constructor(canvas: HTMLCanvasElement | null, ctx: PointContext | null) {
    this.canvas = canvas;
    this.ctx = ctx;
  }

  /**
   * Sets the backing store size (CSS size × min(dpr, maxDpr), ADR-0003 C-34) and invalidates the
   * retained image. Point size and coordinates use the same capped scale.
   */
  resize(
    cssWidth: number,
    cssHeight: number,
    dpr: number,
    maxDpr: number = POINT_LAYER_MAX_DPR,
  ): void {
    this.dpr = pointLayerScale(dpr, maxDpr);
    this.width = Math.max(0, Math.round(cssWidth * this.dpr));
    this.height = Math.max(0, Math.round(cssHeight * this.dpr));
    if (this.canvas !== null) {
      this.canvas.width = this.width;
      this.canvas.height = this.height;
    }
    this.image = null;
    this.image32 = null;
    this.state.key = '';
  }

  /** Backing store size of the layer, px. */
  get deviceSize(): { width: number; height: number } {
    return { width: this.width, height: this.height };
  }

  /** Backing store scale in use: min(devicePixelRatio, cap). */
  get scaleFactor(): number {
    return this.dpr;
  }

  draw(input: PointLayerInput): PointLayerStats {
    const { buffer, scale, mapping, mode } = input;
    const key = [
      this.width,
      this.height,
      this.dpr,
      scale.to - scale.from,
      scale.yMax,
      scale.yType,
      mode,
      mapping.colorBy,
      mapping.colors.length,
    ].join('|');
    const state = this.state;
    // Points appended since the last frame, and those plus the pending ones: the newest `scan`
    // points, logical indices [size - scan, size).
    const fresh = buffer.appended - state.appended;
    const scan = fresh + state.pending;
    const canIncrement =
      input.incremental &&
      input.forceFull !== true &&
      state.key === key &&
      state.buffer === buffer &&
      state.generation === buffer.generation &&
      state.overwritten === buffer.overwritten &&
      scale.from >= state.from &&
      fresh >= 0 &&
      scan <= buffer.size;
    if (canIncrement) {
      const pxPerMs = this.width / (scale.to - scale.from);
      const shift = Math.floor((scale.from - state.from) * pxPerMs);
      if (shift < this.width) {
        if (shift > 0) {
          this.shift(shift, mode);
          state.from += shift / pxPerMs;
        }
        // The image is aligned to whole device pixels, so its window [state.from, state.from +
        // span] may end up to 1 px before scale.to. Points after its end are skipped and stay
        // pending, so a later frame, after the window moved on, draws them. The same holds while
        // the range stands still (paused realtime) and data keeps arriving: the pending points
        // are re-checked on every frame (a pass-1 scan, no drawing) and drawn once the window
        // reaches them, e.g. on resume, however far it jumps, as long as the shift is narrower
        // than the layer (a wider jump redraws everything). Points already on the layer are not
        // drawn again. Like new points, they are drawn on top of the retained image, whatever
        // their colour slot (only a full redraw orders the colours).
        const span = scale.to - scale.from;
        const aligned: PlotScale = { ...scale, from: state.from, to: state.from + span };
        const result = this.drawPoints(buffer, aligned, mapping, mode, false, {
          first: buffer.size - scan,
          fresh: buffer.size - fresh,
          drawnTo: state.drawnTo,
        });
        state.drawnTo = aligned.to;
        state.appended = buffer.appended;
        state.pending = buffer.size - result.pendingFrom;
        return { full: false, drawn: result.drawn, passes: result.passes, shifted: shift };
      }
    }
    const result = this.drawPoints(buffer, scale, mapping, mode, true, null);
    this.state = {
      key,
      from: scale.from,
      drawnTo: scale.to,
      appended: buffer.appended,
      // Points after scale.to (realtime data ahead of a paused or not yet moved window) are
      // pending for the next incremental frame.
      pending: buffer.size - result.pendingFrom,
      generation: buffer.generation,
      overwritten: buffer.overwritten,
      buffer,
    };
    return { full: true, drawn: result.drawn, passes: result.passes, shifted: 0 };
  }

  /** Forces the next `draw` to be a full redraw. */
  invalidate(): void {
    this.state.key = '';
  }

  /** Reads one pixel back, which waits for the GPU to finish the frame (benchmarks only). */
  flush(): void {
    if (this.ctx !== null && this.width > 0 && this.height > 0) {
      this.ctx.getImageData(0, 0, 1, 1);
    }
  }

  private shift(dx: number, mode: DrawMode): void {
    if (mode === 'pixels') {
      if (this.image32 === null) {
        return;
      }
      const w = this.width;
      const data = this.image32;
      for (let row = 0; row < this.height; row += 1) {
        const start = row * w;
        data.copyWithin(start, start + dx, start + w);
        data.fill(0, start + w - dx, start + w);
      }
      return;
    }
    const ctx = this.ctx;
    if (ctx === null || this.canvas === null) {
      return;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalCompositeOperation = 'copy';
    ctx.drawImage(this.canvas, -dx, 0);
    ctx.globalCompositeOperation = 'source-over';
    ctx.clearRect(this.width - dx, 0, dx, this.height);
  }

  private ensureScratch(n: number): void {
    if (this.visible.length < n) {
      const size = Math.max(n, 1024);
      this.visible = new Uint32Array(size);
      this.slots = new Uint8Array(size);
      this.order = new Uint32Array(size);
    }
  }

  /**
   * Top device row of every point's square, by physical buffer index. The rows depend only on the
   * y scale (maximum, linear or log), the layer height, the point size and the point's response
   * time, so they are cached: recomputed completely when one of those or the buffer (identity,
   * generation) changes, otherwise only for the points appended since the last call. Range
   * changes, repaints and realtime frames reuse them, so a log axis does not evaluate log10 per
   * point and frame.
   */
  private ensureRows(buffer: XLogBuffer, scale: PlotScale, size: number): Int32Array {
    const height = this.height;
    const key = `${String(height)}|${String(scale.yMax)}|${scale.yType}|${String(size)}`;
    const state = this.rowState;
    if (this.rows.length < buffer.capacity) {
      this.rows = new Int32Array(buffer.capacity);
      state.key = '';
    }
    let first = 0;
    if (state.key === key && state.buffer === buffer && state.generation === buffer.generation) {
      // New points occupy the last logical indices (overwritten slots included).
      const fresh = buffer.appended - state.appended;
      first = fresh >= 0 && fresh <= buffer.size ? buffer.size - fresh : 0;
    }
    const rows = this.rows;
    const elapsed = buffer.elapsed;
    const half = size >> 1;
    const log = scale.yType === 'log';
    const yK = log ? height / Math.log10(scale.yMax + 1) : height / scale.yMax;
    for (let logical = first; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const e = elapsed[p] ?? 0;
      let yh = log ? Math.log10(e + 1) * yK : e * yK;
      if (yh > height) {
        yh = height;
      }
      rows[p] = ((height - yh + 0.5) | 0) - half;
    }
    state.key = key;
    state.buffer = buffer;
    state.generation = buffer.generation;
    state.appended = buffer.appended;
    return rows;
  }

  /**
   * Draws the points that fall into the time range, one pass per colour slot (counting sort by
   * slot, then one fill per slot): all of them (`increment` null) or, for an incremental frame,
   * the ones from logical index `first` on, where the points before `fresh` (pending from earlier
   * frames) are drawn only if they are after `drawnTo`, the end of the window drawn before (the
   * others are already on the layer). `pendingFrom` is the first logical index skipped because
   * it is after the range end (`buffer.size` if none).
   */
  private drawPoints(
    buffer: XLogBuffer,
    scale: PlotScale,
    mapping: ColorMapping,
    mode: DrawMode,
    clear: boolean,
    increment: { first: number; fresh: number; drawnTo: number } | null,
  ): { drawn: number; passes: number; pendingFrom: number } {
    const ctx = this.ctx;
    const width = this.width;
    const height = this.height;
    if (ctx === null || width === 0 || height === 0) {
      return { drawn: 0, passes: 0, pendingFrom: buffer.size };
    }
    const firstLogical = increment?.first ?? 0;
    const freshFrom = increment?.fresh ?? 0;
    const drawnTo = increment?.drawnTo ?? Number.NEGATIVE_INFINITY;
    const slotCount = mapping.colors.length;
    const n = buffer.size - firstLogical;
    this.ensureScratch(Math.max(0, n));
    const visible = this.visible;
    const slots = this.slots;
    const counts = this.counts;
    counts.fill(0);

    // Pass 1: visible points and their colour slot.
    const from = scale.from;
    const to = scale.to;
    const ts = buffer.ts;
    const status = buffer.status;
    const keys =
      mapping.colorBy === 'instance'
        ? buffer.instance
        : mapping.colorBy === 'service'
          ? buffer.service
          : null;
    const slotOfKey = mapping.slotOfKey;
    const keyLimit = slotOfKey === null ? 0 : slotOfKey.length;
    let count = 0;
    let pendingFrom = buffer.size;
    for (let logical = firstLogical; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const t = ts[p] ?? 0;
      if (t > to) {
        // After the range end: may come into view when the window moves on (realtime).
        if (pendingFrom === buffer.size) {
          pendingFrom = logical;
        }
        continue;
      }
      // Left of the range, or (before `fresh`) a point the last frame drew: of those, only the
      // ones after its window end were pending.
      if (t < from || (logical < freshFrom && t <= drawnTo)) {
        continue;
      }
      let slot: number;
      if (keys === null || slotOfKey === null) {
        slot = status[p] ?? 0;
      } else {
        const key = keys[p] ?? 0;
        slot = key < keyLimit ? (slotOfKey[key] ?? 0) : 0;
      }
      if (slot >= slotCount) {
        slot = 0;
      }
      visible[count] = p;
      slots[count] = slot;
      counts[slot] = (counts[slot] ?? 0) + 1;
      count += 1;
    }

    // Pass 2: counting sort of the visible points by slot.
    const starts = this.starts;
    starts[0] = 0;
    for (let s = 0; s < slotCount; s += 1) {
      starts[s + 1] = (starts[s] ?? 0) + (counts[s] ?? 0);
    }
    const order = this.order;
    const cursor = counts;
    for (let s = 0; s < slotCount; s += 1) {
      cursor[s] = starts[s] ?? 0;
    }
    for (let i = 0; i < count; i += 1) {
      const s = slots[i] ?? 0;
      const at = cursor[s] ?? 0;
      order[at] = visible[i] ?? 0;
      cursor[s] = at + 1;
    }

    // Pass 3: one draw pass per colour. y comes from the row cache, x is computed per frame.
    const size = Math.max(1, Math.round(POINT_SIZE * this.dpr));
    const half = size >> 1;
    const pxPerMs = width / (to - from);
    const rows = this.ensureRows(buffer, scale, size);
    let passes = 0;

    if (mode === 'pixels') {
      if (this.image === null || this.image32 === null) {
        this.image = ctx.createImageData(width, height);
        this.image32 = new Uint32Array(this.image.data.buffer);
      }
      const data = this.image32;
      if (clear) {
        data.fill(0);
      }
      const xLast = width - size;
      const yLast = height - size;
      for (let s = 0; s < slotCount; s += 1) {
        const begin = starts[s] ?? 0;
        const end = starts[s + 1] ?? 0;
        if (begin === end) {
          continue;
        }
        passes += 1;
        const color = packColor(mapping.colors[s] ?? OTHER_COLOR, LITTLE_ENDIAN);
        for (let i = begin; i < end; i += 1) {
          const p = order[i] ?? 0;
          const x0 = ((((ts[p] ?? 0) - from) * pxPerMs + 0.5) | 0) - half;
          const y0 = rows[p] ?? 0;
          if (size === 2 && x0 >= 0 && y0 >= 0 && x0 <= xLast && y0 <= yLast) {
            // The common case (DPR 1, or a capped point layer): four direct writes.
            const at = y0 * width + x0;
            data[at] = color;
            data[at + 1] = color;
            data[at + width] = color;
            data[at + width + 1] = color;
            continue;
          }
          // Other sizes, and squares clipped at the layer edge.
          const xa = x0 < 0 ? 0 : x0;
          const ya = y0 < 0 ? 0 : y0;
          const xb = x0 + size > width ? width : x0 + size;
          const yb = y0 + size > height ? height : y0 + size;
          for (let y = ya; y < yb; y += 1) {
            const row = y * width;
            for (let x = xa; x < xb; x += 1) {
              data[row + x] = color;
            }
          }
        }
      }
      ctx.putImageData(this.image, 0, 0);
      return { drawn: count, passes, pendingFrom };
    }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    if (clear) {
      ctx.clearRect(0, 0, width, height);
      this.image = null;
      this.image32 = null;
    }
    for (let s = 0; s < slotCount; s += 1) {
      const begin = starts[s] ?? 0;
      const end = starts[s + 1] ?? 0;
      if (begin === end) {
        continue;
      }
      passes += 1;
      ctx.fillStyle = mapping.colors[s] ?? OTHER_COLOR;
      if (mode === 'path') {
        ctx.beginPath();
      }
      for (let i = begin; i < end; i += 1) {
        const p = order[i] ?? 0;
        const x0 = ((((ts[p] ?? 0) - from) * pxPerMs + 0.5) | 0) - half;
        const y0 = rows[p] ?? 0;
        if (mode === 'path') {
          ctx.rect(x0, y0, size, size);
        } else {
          ctx.fillRect(x0, y0, size, size);
        }
      }
      if (mode === 'path') {
        ctx.fill();
      }
    }
    return { drawn: count, passes, pendingFrom };
  }
}

/** Plot margins inside the canvas container, CSS px (room for the axis labels). */
export const PLOT_MARGIN = { top: 12, right: 16, bottom: 26, left: 64 } as const;

export interface OverlayLayout {
  /** Container size, CSS px. */
  readonly width: number;
  readonly height: number;
  readonly dpr: number;
}

export interface OverlayInput {
  readonly layout: OverlayLayout;
  readonly scale: PlotScale;
  readonly xTicks: readonly number[];
  readonly formatTime: (ts: number) => string;
  readonly formatDuration: (ms: number) => string;
  /** Latency threshold in ms, drawn as a dashed line; null hides it. */
  readonly threshold: number | null;
  readonly thresholdLabel: string;
  readonly selection: DataRect | null;
  readonly drag: PixelRect | null;
  /** Hovered point, plot pixels. */
  readonly hover: { readonly x: number; readonly y: number } | null;
}

const AXIS_TEXT = '#6b7280';
const GRID = '#eceef1';
const AXIS = '#c3c7cf';
const THRESHOLD = '#d97706';
const LABEL_PLATE = 'rgba(255, 255, 255, 0.85)';
const SELECTION_FILL = 'rgba(47, 111, 235, 0.12)';
const SELECTION_STROKE = '#2f6feb';
const HOVER = '#111827';
const FONT = '11px system-ui, sans-serif';

/** The part of a 2D context the overlay uses. */
export type OverlayContext = Pick<
  CanvasRenderingContext2D,
  | 'setTransform'
  | 'clearRect'
  | 'beginPath'
  | 'moveTo'
  | 'lineTo'
  | 'stroke'
  | 'fillRect'
  | 'strokeRect'
  | 'fillText'
  | 'arc'
  | 'setLineDash'
  | 'measureText'
> & {
  fillStyle: CanvasRenderingContext2D['fillStyle'];
  strokeStyle: CanvasRenderingContext2D['strokeStyle'];
  lineWidth: number;
  font: string;
  textAlign: CanvasTextAlign;
  textBaseline: CanvasTextBaseline;
};

/** Y axis tick values for the scale. */
export function yTicks(scale: PlotScale): number[] {
  return scale.yType === 'log' ? logTicks(scale.yMax) : linearTicks(scale.yMax, 5);
}

/** Draws axes, grid, threshold line, selection, drag rectangle and hover ring. */
export function drawOverlay(ctx: OverlayContext, input: OverlayInput): void {
  const { layout, scale } = input;
  const left = PLOT_MARGIN.left;
  const top = PLOT_MARGIN.top;
  ctx.setTransform(layout.dpr, 0, 0, layout.dpr, 0, 0);
  ctx.clearRect(0, 0, layout.width, layout.height);
  if (scale.width <= 0 || scale.height <= 0) {
    return;
  }
  const bottom = top + scale.height;
  const right = left + scale.width;
  ctx.font = FONT;
  ctx.lineWidth = 1;
  ctx.setLineDash([]);

  // Y grid and labels.
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (const value of yTicks(scale)) {
    const y = Math.round(top + yOf(scale, value)) + 0.5;
    ctx.strokeStyle = GRID;
    ctx.beginPath();
    ctx.moveTo(left, y);
    ctx.lineTo(right, y);
    ctx.stroke();
    ctx.fillStyle = AXIS_TEXT;
    ctx.fillText(input.formatDuration(value), left - 6, y);
  }

  // X labels.
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (const t of input.xTicks) {
    const x = Math.round(left + xOf(scale, t)) + 0.5;
    ctx.strokeStyle = AXIS;
    ctx.beginPath();
    ctx.moveTo(x, bottom);
    ctx.lineTo(x, bottom + 4);
    ctx.stroke();
    ctx.fillStyle = AXIS_TEXT;
    ctx.fillText(input.formatTime(t), x, bottom + 6);
  }

  // Axes.
  ctx.strokeStyle = AXIS;
  ctx.beginPath();
  ctx.moveTo(left + 0.5, top);
  ctx.lineTo(left + 0.5, bottom + 0.5);
  ctx.lineTo(right, bottom + 0.5);
  ctx.stroke();

  // Latency threshold.
  if (input.threshold !== null && input.threshold <= scale.yMax) {
    const y = Math.round(top + yOf(scale, input.threshold)) + 0.5;
    ctx.strokeStyle = THRESHOLD;
    ctx.setLineDash([6, 4]);
    ctx.beginPath();
    ctx.moveTo(left, y);
    ctx.lineTo(right, y);
    ctx.stroke();
    ctx.setLineDash([]);
    // Label on a light plate so it stays readable over dense points.
    const labelWidth = ctx.measureText(input.thresholdLabel).width;
    ctx.fillStyle = LABEL_PLATE;
    ctx.fillRect(right - labelWidth - 8, y - 16, labelWidth + 6, 14);
    ctx.fillStyle = THRESHOLD;
    ctx.textAlign = 'right';
    ctx.textBaseline = 'bottom';
    ctx.fillText(input.thresholdLabel, right - 5, y - 3);
  }

  // Stored selection (data space, moves with the range) and the rectangle being dragged.
  const rects: { rect: PixelRect; dashed: boolean }[] = [];
  if (input.selection !== null) {
    rects.push({ rect: dataRectToPixels(scale, input.selection), dashed: false });
  }
  if (input.drag !== null) {
    rects.push({ rect: input.drag, dashed: true });
  }
  for (const { rect, dashed } of rects) {
    const x1 = Math.max(0, Math.min(scale.width, rect.x1));
    const x2 = Math.max(0, Math.min(scale.width, rect.x2));
    if (x2 <= x1 && !dashed) {
      continue;
    }
    const x = left + x1;
    const y = top + rect.y1;
    const w = x2 - x1;
    const h = rect.y2 - rect.y1;
    ctx.fillStyle = SELECTION_FILL;
    ctx.fillRect(x, y, w, h);
    ctx.strokeStyle = SELECTION_STROKE;
    ctx.setLineDash(dashed ? [4, 3] : []);
    ctx.strokeRect(x + 0.5, y + 0.5, Math.max(0, w - 1), Math.max(0, h - 1));
  }
  ctx.setLineDash([]);

  if (input.hover !== null) {
    ctx.strokeStyle = HOVER;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(left + input.hover.x, top + input.hover.y, 5, 0, Math.PI * 2);
    ctx.stroke();
  }
}
