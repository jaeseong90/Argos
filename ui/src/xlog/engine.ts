// SPDX-License-Identifier: Apache-2.0
//
// Imperative core of XLogCanvas: owns the two canvases, the frame loop, the automatic y maximum,
// the hit index and the pointer interaction (hover, drag selection, click). React passes its props
// in with `update`; buffer changes arrive through `XLogBuffer.subscribe`, so realtime data never
// re-renders React. A frame is requested only when something is dirty (docs/15 §7.2 "프레임").
import { HitIndex } from './hitIndex';
import {
  bandPixels,
  outageBands,
  overlayMarkers,
  type OverlayMarker,
  type PlotBand,
  type TimeBand,
  type XLogMarker,
  type XLogOutage,
} from './overlayMarks';
import type { BenchmarkOptions, FrameSample } from './perf';
import {
  PLOT_MARGIN,
  POINT_LAYER_MAX_DPR,
  PointLayer,
  drawOverlay,
  type ColorMapping,
  type DrawMode,
  type OverlayContext,
} from './renderer';
import {
  DEFAULT_Y_MAX,
  autoYMax,
  createScale,
  durationUnitFor,
  normalizeRect,
  pixelRectToData,
  timeTicks,
  type DataRect,
  type DurationUnit,
  type PixelRect,
  type PlotScale,
  type YScaleType,
} from './scale';
import { scanDataRect } from './scanSelection';
import type { XLogBuffer } from './XLogBuffer';

/** Minimum drag size in CSS px; smaller gestures are clicks. */
export const MIN_DRAG_PX = 3;
/** The automatic y maximum is recomputed at most this often while data streams in. */
export const Y_MAX_INTERVAL_MS = 1000;
export const MAX_BENCHMARK_FRAMES = 600;

export interface EngineSelection {
  readonly rect: DataRect;
  /**
   * Physical buffer indices of the selected points, valid until the buffer changes. Made when
   * first read, outside queryMs: a copy of the query's matches, or, when another query has run
   * since, a scan of the buffer for `rect` (the same points while the buffer is unchanged, oldest
   * first). A consumer that reads only `rect` (the XLog screen, which selects through the URL)
   * makes no copy (WO-33 review r1-new-3).
   */
  readonly indices: Uint32Array;
  /**
   * Time of the selection computation (docs/15 §7.3 "10만 점 선택 계산 ≤ 5ms"): hit index check
   * (rebuilt when stale) and rectangle query, ms.
   */
  readonly queryMs?: number;
  /**
   * Whether queryMs includes a rebuild of the hit index (the buffer or the scale changed since the
   * index was built, and no hover rebuilt it first). Diagnostics of ADR-0003 C-36 (WO-33 review
   * r1-new-3).
   */
  readonly indexRebuilt?: boolean;
  /** The rebuild's share of queryMs, ms (0 when the index was current). */
  readonly indexBuildMs?: number;
}

export interface EngineHover {
  /** Physical buffer index. */
  readonly index: number;
  /** Point position relative to the container, CSS px. */
  readonly x: number;
  readonly y: number;
}

export interface EngineProps {
  readonly buffer: XLogBuffer;
  readonly mapping: ColorMapping;
  readonly from: number;
  readonly to: number;
  readonly yType: YScaleType;
  /** Fixed y maximum in ms; null = automatic. */
  readonly yMax: number | null;
  /** Latency threshold line in ms; null hides it. */
  readonly threshold: number | null;
  readonly selection: DataRect | null;
  readonly drawMode: DrawMode;
  readonly incremental: boolean;
  /**
   * Draw the point layer on a CPU-backed canvas (`willReadFrequently`) instead of the default
   * GPU-accelerated one (PoC measurement option). Read once, when the engine is created.
   */
  readonly softwareCanvas?: boolean;
  /**
   * Cap of the point layer's backing store scale (ADR-0003 C-34); default POINT_LAYER_MAX_DPR.
   * The overlay always uses the full devicePixelRatio.
   */
  readonly pointLayerMaxDpr?: number;
  readonly formatTime: (ts: number) => string;
  readonly formatDuration: (ms: number, unit: DurationUnit) => string;
  readonly formatThreshold: (ms: number) => string;
  /**
   * Collection outages (docs/14 `meta.outages`, C-5, D-18): hatched grey bands on the overlay.
   * Overlay only: a change never redraws the point layer. None by default.
   */
  readonly outages?: readonly XLogOutage[];
  /** Event markers (docs/15 §6.1) on the overlay, grouped when they overlap. None by default. */
  readonly markers?: readonly XLogMarker[];
  /** Event id of the `alert=` deep-link key: its marker leads its group and is emphasised. */
  readonly emphasizedMarkerId?: string | null;
  /** Keyboard selection band (data space, full height, docs/15 §13). None by default. */
  readonly band?: TimeBand | null;
  readonly onSelect?: (selection: EngineSelection) => void;
  readonly onHover?: (hover: EngineHover | null) => void;
  readonly onPointClick?: (index: number) => void;
  readonly onFrame?: (sample: FrameSample) => void;
  /** Plot area size (CSS px) after every measurement, for DOM parts placed over the plot. */
  readonly onLayout?: (layout: { readonly plotWidth: number; readonly plotHeight: number }) => void;
}

/** Browser services the engine uses; injectable for tests. */
export interface EngineEnvironment {
  readonly requestFrame: (callback: (time: number) => void) => number;
  readonly cancelFrame: (handle: number) => void;
  readonly now: () => number;
  readonly devicePixelRatio: () => number;
  /** Calls `callback` when the element's size may have changed; returns the function to stop. */
  readonly observeResize: (element: HTMLElement, callback: () => void) => () => void;
  /**
   * Calls `callback` when the devicePixelRatio stops being `dpr` (browser zoom, another screen);
   * returns the function to stop. The engine registers again for every new ratio.
   */
  readonly watchResolution: (dpr: number, callback: () => void) => () => void;
}

function observeResize(element: HTMLElement, callback: () => void): () => void {
  if (typeof ResizeObserver === 'function') {
    const observer = new ResizeObserver(() => {
      callback();
    });
    observer.observe(element);
    return () => {
      observer.disconnect();
    };
  }
  window.addEventListener('resize', callback);
  return () => {
    window.removeEventListener('resize', callback);
  };
}

function watchResolution(dpr: number, callback: () => void): () => void {
  if (typeof window.matchMedia !== 'function') {
    return () => undefined;
  }
  const query = window.matchMedia(`(resolution: ${String(dpr)}dppx)`);
  query.addEventListener('change', callback);
  return () => {
    query.removeEventListener('change', callback);
  };
}

function defaultEnvironment(): EngineEnvironment {
  const hasRaf = typeof window.requestAnimationFrame === 'function';
  return {
    requestFrame: hasRaf
      ? (callback) => window.requestAnimationFrame(callback)
      : (callback) =>
          window.setTimeout(() => {
            callback(performance.now());
          }, 16),
    cancelFrame: hasRaf
      ? (handle) => {
          window.cancelAnimationFrame(handle);
        }
      : (handle) => {
          window.clearTimeout(handle);
        },
    now: () => performance.now(),
    devicePixelRatio: () => (window.devicePixelRatio > 0 ? window.devicePixelRatio : 1),
    observeResize,
    watchResolution,
  };
}

interface Benchmark {
  remaining: number;
  readonly flush: boolean;
  lastFrameTime: number | null;
  readonly samples: FrameSample[];
  readonly resolve: (samples: FrameSample[]) => void;
  readonly reject: (error: Error) => void;
}

interface Drag {
  readonly pointerId: number;
  readonly x0: number;
  readonly y0: number;
  x1: number;
  y1: number;
}

export class XLogEngine {
  /**
   * The canvas container: carries `data-y-unit` (ms, s or min), the unit of the linear y axis
   * (docs/15 §7.2 batch unit), for tests and the E2E suite.
   */
  private readonly container: HTMLElement;
  private readonly pointsCanvas: HTMLCanvasElement;
  private readonly overlayCanvas: HTMLCanvasElement;
  private props: EngineProps;
  private readonly env: EngineEnvironment;
  private readonly points: PointLayer;
  private readonly overlayCtx: OverlayContext | null;
  private readonly hitIndex = new HitIndex();
  private unsubscribe: () => void = () => undefined;
  private readonly stopResize: () => void;
  private stopResolution: (() => void) | null = null;
  private watchedDpr = 0;
  private frameHandle = 0;
  private destroyed = false;

  private width = 0;
  private height = 0;
  private dpr = 1;
  private pointsDirty = true;
  private overlayDirty = true;
  private overlayScaleKey = '';

  private yMaxValue = DEFAULT_Y_MAX;
  private yMaxState: {
    buffer: XLogBuffer | null;
    generation: number;
    version: number;
    at: number;
    range: string;
  } = { buffer: null, generation: -1, version: -1, at: -Infinity, range: '' };
  private yScratch = new Float64Array(0);

  private hover: { index: number; x: number; y: number } | null = null;
  /** Last pointer position over the plot (plot px); null when the pointer is not over it. */
  private pointer: { x: number; y: number } | null = null;
  /** The scale changed under the pointer: pick the hovered point again in the next frame. */
  private hoverStale = false;
  private drag: Drag | null = null;
  private bench: Benchmark | null = null;
  private lastVisible = 0;
  /** Overlay marks of the last overlay draw, reused while scale and inputs are unchanged. */
  private marks: {
    key: string;
    outages: readonly XLogOutage[] | undefined;
    markers: readonly XLogMarker[] | undefined;
    emphasized: string | null | undefined;
    band: TimeBand | null | undefined;
    bands: readonly PlotBand[];
    groups: readonly OverlayMarker[];
    keyboard: PlotBand | null;
  } | null = null;

  constructor(
    container: HTMLElement,
    pointsCanvas: HTMLCanvasElement,
    overlayCanvas: HTMLCanvasElement,
    props: EngineProps,
    env: EngineEnvironment = defaultEnvironment(),
  ) {
    this.container = container;
    this.pointsCanvas = pointsCanvas;
    this.overlayCanvas = overlayCanvas;
    this.props = props;
    this.env = env;
    this.points = new PointLayer(
      pointsCanvas,
      pointsCanvas.getContext(
        '2d',
        props.softwareCanvas === true ? { willReadFrequently: true } : {},
      ),
    );
    this.overlayCtx = overlayCanvas.getContext('2d');
    this.subscribe(props.buffer);
    this.overlayCanvas.addEventListener('pointerdown', this.onPointerDown);
    this.overlayCanvas.addEventListener('pointermove', this.onPointerMove);
    this.overlayCanvas.addEventListener('pointerup', this.onPointerUp);
    this.overlayCanvas.addEventListener('pointercancel', this.onPointerCancel);
    this.overlayCanvas.addEventListener('pointerleave', this.onPointerLeave);
    this.stopResize = env.observeResize(container, this.onResize);
    this.measure();
  }

  /** Applies new props; marks what they invalidate. */
  update(next: EngineProps): void {
    const prev = this.props;
    this.props = next;
    if (next.buffer !== prev.buffer) {
      this.subscribe(next.buffer);
      this.points.invalidate();
      this.clearHover();
      this.markAll();
      return;
    }
    if (next.pointLayerMaxDpr !== prev.pointLayerMaxDpr) {
      // New backing store size for the point layer; measure() redraws everything.
      this.measure();
      return;
    }
    if (
      next.mapping !== prev.mapping ||
      next.from !== prev.from ||
      next.to !== prev.to ||
      next.yType !== prev.yType ||
      next.yMax !== prev.yMax ||
      next.drawMode !== prev.drawMode ||
      next.incremental !== prev.incremental
    ) {
      if (next.from !== prev.from || next.to !== prev.to || next.yType !== prev.yType) {
        // The points move under a resting pointer (realtime: once per second): the next frame
        // picks the hovered point again instead of dropping the tooltip.
        this.hoverStale = true;
      }
      this.markAll();
      return;
    }
    if (
      next.threshold !== prev.threshold ||
      next.selection !== prev.selection ||
      next.formatTime !== prev.formatTime ||
      next.formatDuration !== prev.formatDuration ||
      next.formatThreshold !== prev.formatThreshold ||
      // Overlay marks (docs/15 §7.2): the overlay only, never the point layer.
      next.outages !== prev.outages ||
      next.markers !== prev.markers ||
      next.emphasizedMarkerId !== prev.emphasizedMarkerId ||
      next.band !== prev.band
    ) {
      this.overlayDirty = true;
      this.requestFrame();
    }
  }

  /** Plot area size (CSS px) and container size. */
  get layout(): {
    width: number;
    height: number;
    plotWidth: number;
    plotHeight: number;
    dpr: number;
  } {
    const { plotWidth, plotHeight } = this.plotSize();
    return { width: this.width, height: this.height, plotWidth, plotHeight, dpr: this.dpr };
  }

  /** Points drawn by the last full redraw. */
  get visibleCount(): number {
    return this.lastVisible;
  }

  /** The scale of the current props and size. */
  scale(): PlotScale {
    const { plotWidth, plotHeight } = this.plotSize();
    return createScale(
      this.props.from,
      this.props.to,
      plotWidth,
      plotHeight,
      this.props.yMax ?? this.currentAutoYMax(),
      this.props.yType,
    );
  }

  /** Re-reads the drawing area size (the container's padding box) and devicePixelRatio. */
  measure(): void {
    if (this.destroyed) {
      return;
    }
    // The canvases are laid out in the container's padding box (inside its border); the overlay
    // fills that box, so its rectangle is the drawing area.
    const rect = this.overlayCanvas.getBoundingClientRect();
    const dpr = this.env.devicePixelRatio();
    this.width = Math.max(0, rect.width);
    this.height = Math.max(0, rect.height);
    this.watchDpr(dpr);
    this.dpr = dpr;
    const { plotWidth, plotHeight } = this.plotSize();
    const style = this.pointsCanvas.style;
    style.left = `${String(PLOT_MARGIN.left)}px`;
    style.top = `${String(PLOT_MARGIN.top)}px`;
    style.width = `${String(plotWidth)}px`;
    style.height = `${String(plotHeight)}px`;
    // The point layer's backing store scale is capped (C-34); the overlay keeps the full DPR.
    // Both canvases keep their CSS size, so hit testing and hover stay in CSS px.
    this.points.resize(
      plotWidth,
      plotHeight,
      dpr,
      this.props.pointLayerMaxDpr ?? POINT_LAYER_MAX_DPR,
    );
    this.overlayCanvas.width = Math.round(this.width * dpr);
    this.overlayCanvas.height = Math.round(this.height * dpr);
    // The layout moved: the last pointer position is no longer valid until the pointer moves.
    this.pointer = null;
    this.clearHover();
    this.markAll();
    this.props.onLayout?.({ plotWidth, plotHeight });
  }

  /**
   * Selects every point of a time band over the full height (the keyboard alternative to a drag
   * and "select visible range", docs/15 §13, WO-04 C-12) through the same hit index query as a
   * drag, and reports it with onSelect like a drag. The band is clipped to the range; returns null
   * (and reports nothing) when nothing of it is inside the plot.
   */
  selectTimeBand(band: TimeBand): EngineSelection | null {
    if (this.destroyed) {
      return null;
    }
    const scale = this.scale();
    const t1 = Math.max(scale.from, Math.min(band.t1, band.t2));
    const t2 = Math.min(scale.to, Math.max(band.t1, band.t2));
    const pixels = bandPixels(scale, { t1, t2 });
    if (pixels === null || scale.height <= 0) {
      return null;
    }
    const started = this.env.now();
    const buildMs = this.ensureIndex(scale);
    const rect: DataRect = { t1, t2, e1: 0, e2: Number.POSITIVE_INFINITY };
    const found = this.hitIndex.queryDataInPlace(
      this.props.buffer,
      { x1: pixels.x1, x2: pixels.x2, y1: 0, y2: scale.height },
      rect,
    );
    const selection = this.selectionOf(rect, found, this.env.now() - started, buildMs);
    this.props.onSelect?.(selection);
    return selection;
  }

  /**
   * Forces `frames` consecutive full redraws and resolves with their samples (performance spec).
   */
  benchmark(options: BenchmarkOptions = {}): Promise<FrameSample[]> {
    if (this.bench !== null) {
      return Promise.reject(new Error('An XLog benchmark is already running'));
    }
    const frames = Math.max(1, Math.min(MAX_BENCHMARK_FRAMES, Math.floor(options.frames ?? 60)));
    return new Promise<FrameSample[]>((resolve, reject) => {
      this.bench = {
        remaining: frames,
        flush: options.flush === true,
        lastFrameTime: null,
        samples: [],
        resolve,
        reject,
      };
      this.requestFrame();
    });
  }

  destroy(): void {
    this.destroyed = true;
    if (this.frameHandle !== 0) {
      this.env.cancelFrame(this.frameHandle);
      this.frameHandle = 0;
    }
    this.unsubscribe();
    this.stopResize();
    this.stopResolution?.();
    this.stopResolution = null;
    this.pointer = null;
    this.overlayCanvas.removeEventListener('pointerdown', this.onPointerDown);
    this.overlayCanvas.removeEventListener('pointermove', this.onPointerMove);
    this.overlayCanvas.removeEventListener('pointerup', this.onPointerUp);
    this.overlayCanvas.removeEventListener('pointercancel', this.onPointerCancel);
    this.overlayCanvas.removeEventListener('pointerleave', this.onPointerLeave);
    const bench = this.bench;
    this.bench = null;
    bench?.reject(new Error('The XLog canvas was removed during a benchmark'));
  }

  private plotSize(): { plotWidth: number; plotHeight: number } {
    return {
      plotWidth: Math.max(0, Math.floor(this.width - PLOT_MARGIN.left - PLOT_MARGIN.right)),
      plotHeight: Math.max(0, Math.floor(this.height - PLOT_MARGIN.top - PLOT_MARGIN.bottom)),
    };
  }

  private subscribe(buffer: XLogBuffer): void {
    this.unsubscribe();
    this.unsubscribe = buffer.subscribe(() => {
      this.pointsDirty = true;
      this.requestFrame();
    });
  }

  private markAll(): void {
    this.pointsDirty = true;
    this.overlayDirty = true;
    this.requestFrame();
  }

  private requestFrame(): void {
    if (this.frameHandle === 0 && !this.destroyed) {
      this.frameHandle = this.env.requestFrame(this.frame);
    }
  }

  /** Watches for the devicePixelRatio to change from `dpr` (one registration per ratio). */
  private watchDpr(dpr: number): void {
    if (this.stopResolution !== null && dpr === this.watchedDpr) {
      return;
    }
    this.stopResolution?.();
    this.watchedDpr = dpr;
    this.stopResolution = this.env.watchResolution(dpr, this.onDprChange);
  }

  private readonly onDprChange = (): void => {
    this.measure();
  };

  private readonly onResize = (): void => {
    this.measure();
  };

  /** p99.9 × 1.2 of the points in range, recomputed at most once per Y_MAX_INTERVAL_MS. */
  private currentAutoYMax(): number {
    const buffer = this.props.buffer;
    const state = this.yMaxState;
    const range = `${String(this.props.from)}|${String(this.props.to)}`;
    const now = this.env.now();
    const stale =
      state.buffer !== buffer ||
      state.generation !== buffer.generation ||
      (state.version !== buffer.version && now - state.at >= Y_MAX_INTERVAL_MS) ||
      (state.range !== range && now - state.at >= Y_MAX_INTERVAL_MS);
    if (!stale) {
      return this.yMaxValue;
    }
    if (this.yScratch.length < buffer.capacity) {
      this.yScratch = new Float64Array(buffer.capacity);
    }
    const values = this.yScratch;
    const { from, to } = this.props;
    let count = 0;
    for (let logical = 0; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const t = buffer.ts[p] ?? 0;
      if (t >= from && t <= to) {
        values[count] = buffer.elapsed[p] ?? 0;
        count += 1;
      }
    }
    this.yMaxValue = autoYMax(values, count);
    this.yMaxState = {
      buffer,
      generation: buffer.generation,
      version: buffer.version,
      at: now,
      range,
    };
    return this.yMaxValue;
  }

  private readonly frame = (time: number): void => {
    this.frameHandle = 0;
    if (this.destroyed) {
      return;
    }
    const bench = this.bench;
    const t0 = this.env.now();
    const scale = this.scale();
    const props = this.props;
    let full = false;
    let drawn = 0;
    let passes = 0;
    const drawPoints = this.pointsDirty || bench !== null;
    if (drawPoints) {
      const stats = this.points.draw({
        buffer: props.buffer,
        scale,
        mapping: props.mapping,
        mode: props.drawMode,
        incremental: props.incremental,
        forceFull: bench !== null,
      });
      if (bench?.flush === true) {
        this.points.flush();
      }
      this.pointsDirty = false;
      full = stats.full;
      drawn = stats.drawn;
      passes = stats.passes;
      if (stats.full) {
        this.lastVisible = stats.drawn;
      }
    }
    const t1 = this.env.now();
    const pointsMs = t1 - t0;

    const scaleKey = [
      scale.from,
      scale.to,
      scale.width,
      scale.height,
      scale.yMax,
      scale.yType,
    ].join('|');
    if (scaleKey !== this.overlayScaleKey) {
      this.overlayScaleKey = scaleKey;
      this.overlayDirty = true;
      // Also an automatic y maximum or a size change moves the points under the pointer.
      this.hoverStale = true;
    }
    if (this.hoverStale) {
      this.hoverStale = false;
      this.reselectHover(scale);
    }
    const drawOverlayNow = this.overlayDirty || bench !== null;
    if (drawOverlayNow) {
      this.renderOverlay(scale);
      this.overlayDirty = false;
    }
    const t2 = this.env.now();

    let interval: number | null = null;
    if (bench !== null) {
      interval = bench.lastFrameTime === null ? null : time - bench.lastFrameTime;
      bench.lastFrameTime = time;
    }
    const sample: FrameSample = {
      t: t0,
      interval,
      pointsMs,
      overlayMs: t2 - t1,
      totalMs: t2 - t0,
      full,
      pointsDrawn: drawPoints,
      drawn,
      passes,
      mode: props.drawMode,
      flushed: bench?.flush === true,
    };
    props.onFrame?.(sample);
    if (bench !== null) {
      bench.samples.push(sample);
      bench.remaining -= 1;
      if (bench.remaining > 0) {
        this.requestFrame();
      } else {
        this.bench = null;
        bench.resolve(bench.samples);
      }
    }
  };

  private renderOverlay(scale: PlotScale): void {
    const ctx = this.overlayCtx;
    if (ctx === null) {
      return;
    }
    const props = this.props;
    const unit = durationUnitFor(scale.yMax);
    if (this.container.dataset['yUnit'] !== unit) {
      this.container.dataset['yUnit'] = unit;
    }
    const offset = -new Date(props.from).getTimezoneOffset() * 60_000;
    const drag = this.drag;
    const { plotWidth, plotHeight } = this.plotSize();
    const marks = this.overlayMarks(scale);
    drawOverlay(ctx, {
      layout: { width: this.width, height: this.height, dpr: this.dpr },
      scale,
      xTicks: timeTicks(props.from, props.to, Math.max(2, Math.floor(plotWidth / 110)), offset),
      formatTime: props.formatTime,
      // A linear axis uses one unit; a log axis spans decades, so each label picks its own.
      formatDuration: (ms) =>
        props.formatDuration(ms, scale.yType === 'log' ? durationUnitFor(ms) : unit),
      threshold: props.threshold,
      thresholdLabel: props.threshold === null ? '' : props.formatThreshold(props.threshold),
      selection: props.selection,
      drag:
        drag === null
          ? null
          : normalizeRect(drag.x0, drag.y0, drag.x1, drag.y1, plotWidth, plotHeight),
      hover: this.hover === null ? null : { x: this.hover.x, y: this.hover.y },
      outages: marks.bands,
      markers: marks.groups,
      band: marks.keyboard,
    });
  }

  /** Outage bands, marker groups and keyboard band for the scale (cached per scale and inputs). */
  private overlayMarks(scale: PlotScale): {
    bands: readonly PlotBand[];
    groups: readonly OverlayMarker[];
    keyboard: PlotBand | null;
  } {
    const props = this.props;
    const key = this.overlayScaleKey;
    const cached = this.marks;
    if (
      cached?.key === key &&
      cached.outages === props.outages &&
      cached.markers === props.markers &&
      cached.emphasized === props.emphasizedMarkerId &&
      cached.band === props.band
    ) {
      return cached;
    }
    const next = {
      key,
      outages: props.outages,
      markers: props.markers,
      emphasized: props.emphasizedMarkerId,
      band: props.band,
      bands: props.outages === undefined ? [] : outageBands(scale, props.outages),
      groups:
        props.markers === undefined
          ? []
          : overlayMarkers(scale, props.markers, props.emphasizedMarkerId),
      keyboard: bandPixels(scale, props.band),
    };
    this.marks = next;
    return next;
  }

  /** Pointer position relative to the plot area. */
  private plotPoint(event: PointerEvent): { x: number; y: number; inside: boolean } {
    // Same origin as the canvases: the padding box (the container's rectangle includes its border).
    const rect = this.overlayCanvas.getBoundingClientRect();
    const x = event.clientX - rect.left - PLOT_MARGIN.left;
    const y = event.clientY - rect.top - PLOT_MARGIN.top;
    const { plotWidth, plotHeight } = this.plotSize();
    return { x, y, inside: x >= 0 && y >= 0 && x <= plotWidth && y <= plotHeight };
  }

  private freshIndex(scale: PlotScale): HitIndex {
    this.hitIndex.ensure(this.props.buffer, scale);
    return this.hitIndex;
  }

  /**
   * Makes the hit index current for `scale` (like freshIndex) and returns the rebuild's time in
   * ms, or null when the index was current: a selection reports whether its queryMs includes a
   * rebuild (WO-33 review r1-new-3).
   */
  private ensureIndex(scale: PlotScale): number | null {
    const started = this.env.now();
    return this.hitIndex.ensure(this.props.buffer, scale) ? this.env.now() - started : null;
  }

  /**
   * The reported selection of a query whose `found` matches are in the hit index's scratch: its
   * `indices` are made when first read (EngineSelection.indices), so the query itself allocates
   * no result array. Before WO-33 review r1-new-3 every drag copied its matches (about 356 KB for
   * an 89,000-point selection) inside queryMs, also for the XLog screen, which never reads them.
   */
  private selectionOf(
    rect: DataRect,
    found: number,
    queryMs: number,
    buildMs: number | null,
  ): EngineSelection {
    const index = this.hitIndex;
    const token = index.queryToken;
    const buffer = this.props.buffer;
    let indices: Uint32Array | null = null;
    return {
      rect,
      get indices(): Uint32Array {
        indices ??= index.copyMatches(token, found) ?? scanDataRect(buffer, rect);
        return indices;
      },
      queryMs,
      indexRebuilt: buildMs !== null,
      indexBuildMs: buildMs ?? 0,
    };
  }

  /**
   * Picks the hovered point again after the scale changed under a resting pointer (realtime window
   * move, axis or size change), during the frame, before the overlay is drawn. Rebuilding the hit
   * index takes about 4-5 ms for 100,000 points; it happens only while the pointer rests over the
   * plot, once per scale change (once per second in realtime). Without a pointer over the plot
   * (as in the realtime performance scenario) nothing is rebuilt.
   */
  private reselectHover(scale: PlotScale): void {
    const pointer = this.pointer;
    if (pointer === null || this.drag !== null) {
      this.applyHover(-1);
      return;
    }
    this.applyHover(this.freshIndex(scale).nearest(pointer.x, pointer.y));
  }

  /**
   * Sets the hovered point (physical index from the current hit index; < 0 = none) and reports
   * it. A point that stayed hovered but moved is reported again with its new position. Returns
   * whether anything changed; the overlay is marked dirty but no frame is requested.
   */
  private applyHover(index: number): boolean {
    const current = this.hover;
    if (index < 0) {
      if (current === null) {
        return false;
      }
      this.hover = null;
      this.overlayDirty = true;
      this.props.onHover?.(null);
      return true;
    }
    const position = this.hitIndex.position(index);
    if (
      current !== null &&
      current.index === index &&
      current.x === position.x &&
      current.y === position.y
    ) {
      return false;
    }
    this.hover = { index, x: position.x, y: position.y };
    this.overlayDirty = true;
    this.props.onHover?.({
      index,
      x: PLOT_MARGIN.left + position.x,
      y: PLOT_MARGIN.top + position.y,
    });
    return true;
  }

  private setHover(index: number): void {
    if (this.applyHover(index)) {
      this.requestFrame();
    }
  }

  private clearHover(): void {
    if (this.applyHover(-1)) {
      this.requestFrame();
    }
  }

  private readonly onPointerDown = (event: PointerEvent): void => {
    if (event.button !== 0) {
      return;
    }
    const point = this.plotPoint(event);
    if (!point.inside) {
      return;
    }
    this.drag = { pointerId: event.pointerId, x0: point.x, y0: point.y, x1: point.x, y1: point.y };
    const target = this.overlayCanvas as Partial<Pick<HTMLCanvasElement, 'setPointerCapture'>>;
    target.setPointerCapture?.call(this.overlayCanvas, event.pointerId);
    this.pointer = null;
    this.clearHover();
    event.preventDefault();
  };

  private readonly onPointerMove = (event: PointerEvent): void => {
    const point = this.plotPoint(event);
    const drag = this.drag;
    if (drag !== null) {
      if (event.pointerId !== drag.pointerId) {
        return;
      }
      drag.x1 = point.x;
      drag.y1 = point.y;
      this.overlayDirty = true;
      this.requestFrame();
      return;
    }
    if (!point.inside) {
      this.pointer = null;
      this.clearHover();
      return;
    }
    this.pointer = { x: point.x, y: point.y };
    this.setHover(this.freshIndex(this.scale()).nearest(point.x, point.y));
  };

  private readonly onPointerUp = (event: PointerEvent): void => {
    const drag = this.drag;
    if (drag === null || event.pointerId !== drag.pointerId) {
      return;
    }
    const point = this.plotPoint(event);
    drag.x1 = point.x;
    drag.y1 = point.y;
    this.drag = null;
    this.overlayDirty = true;
    this.requestFrame();
    const scale = this.scale();
    const started = this.env.now();
    const buildMs = this.ensureIndex(scale);
    if (Math.abs(drag.x1 - drag.x0) >= MIN_DRAG_PX && Math.abs(drag.y1 - drag.y0) >= MIN_DRAG_PX) {
      const rect: PixelRect = normalizeRect(
        drag.x0,
        drag.y0,
        drag.x1,
        drag.y1,
        scale.width,
        scale.height,
      );
      const data = pixelRectToData(scale, rect);
      const found = this.hitIndex.queryDataInPlace(this.props.buffer, rect, data);
      this.props.onSelect?.(this.selectionOf(data, found, this.env.now() - started, buildMs));
      return;
    }
    const hit = this.hitIndex.nearest(point.x, point.y);
    if (hit >= 0) {
      this.props.onPointClick?.(hit);
    }
  };

  private readonly onPointerCancel = (): void => {
    if (this.drag !== null) {
      this.drag = null;
      this.overlayDirty = true;
      this.requestFrame();
    }
  };

  private readonly onPointerLeave = (): void => {
    this.pointer = null;
    if (this.drag === null) {
      this.clearHover();
    }
  };
}
