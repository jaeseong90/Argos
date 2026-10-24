// SPDX-License-Identifier: Apache-2.0
//
// Frame timing of the XLog canvas (docs/15 §14 N-14, T-NFR-14). The engine reports one sample per
// drawn frame; FrameRecorder keeps the last N in a bounded ring. `window.__xlogPerf` exposes them
// to the performance spec (ui/perf/xlog-frame.spec.ts, next WO-04 step).
//
// The times are main-thread JavaScript times of the draw calls (performance.now). Chrome
// rasterises canvas commands later, on the GPU process; a benchmark run with `flush: true` reads
// one pixel back after each frame so the time includes that work. The rAF interval of consecutive
// benchmark frames is recorded as well. Chrome tracing remains the reference measurement.
import type { DrawMode } from './renderer';

export interface FrameSample {
  /** performance.now() at the start of the frame. */
  readonly t: number;
  /** Time since the previous frame when frames were consecutive (benchmark), otherwise null. */
  readonly interval: number | null;
  readonly pointsMs: number;
  readonly overlayMs: number;
  /** Includes the read-back when the benchmark flushes. */
  readonly totalMs: number;
  /** Point layer redrawn completely (false: shift + new points, or overlay only). */
  readonly full: boolean;
  /** Point layer drawn in this frame at all. */
  readonly pointsDrawn: boolean;
  /** Points drawn. */
  readonly drawn: number;
  readonly passes: number;
  readonly mode: DrawMode;
  readonly flushed: boolean;
}

export interface Distribution {
  readonly count: number;
  readonly mean: number;
  readonly p50: number;
  readonly p95: number;
  readonly max: number;
}

export interface FrameStats {
  readonly frames: number;
  readonly totalMs: Distribution;
  readonly pointsMs: Distribution;
  readonly intervalMs: Distribution | null;
}

/** Nearest-rank percentile of ascending `sorted` values (q in [0, 1]). */
export function percentile(sorted: readonly number[], q: number): number {
  if (sorted.length === 0) {
    return 0;
  }
  const rank = Math.ceil(q * sorted.length);
  const index = Math.min(sorted.length - 1, Math.max(0, rank - 1));
  return sorted[index] ?? 0;
}

export function distribution(values: readonly number[]): Distribution {
  const sorted = [...values].sort((a, b) => a - b);
  const sum = sorted.reduce((acc, v) => acc + v, 0);
  return {
    count: sorted.length,
    mean: sorted.length === 0 ? 0 : sum / sorted.length,
    p50: percentile(sorted, 0.5),
    p95: percentile(sorted, 0.95),
    max: sorted.length === 0 ? 0 : (sorted[sorted.length - 1] ?? 0),
  };
}

export function frameStats(samples: readonly FrameSample[]): FrameStats {
  const intervals = samples.flatMap((s) => (s.interval === null ? [] : [s.interval]));
  return {
    frames: samples.length,
    totalMs: distribution(samples.map((s) => s.totalMs)),
    pointsMs: distribution(samples.filter((s) => s.pointsDrawn).map((s) => s.pointsMs)),
    intervalMs: intervals.length === 0 ? null : distribution(intervals),
  };
}

/** Bounded ring of frame samples. */
export class FrameRecorder {
  readonly capacity: number;
  private readonly ring: (FrameSample | undefined)[];
  private next = 0;
  private filled = 0;

  constructor(capacity = 600) {
    this.capacity = Math.max(1, Math.floor(capacity));
    this.ring = new Array<FrameSample | undefined>(this.capacity);
  }

  push(sample: FrameSample): void {
    this.ring[this.next] = sample;
    this.next = (this.next + 1) % this.capacity;
    this.filled = Math.min(this.capacity, this.filled + 1);
  }

  /** Samples, oldest first. */
  samples(): FrameSample[] {
    const out: FrameSample[] = [];
    const start = (this.next - this.filled + this.capacity) % this.capacity;
    for (let i = 0; i < this.filled; i += 1) {
      const sample = this.ring[(start + i) % this.capacity];
      if (sample !== undefined) {
        out.push(sample);
      }
    }
    return out;
  }

  last(): FrameSample | null {
    if (this.filled === 0) {
      return null;
    }
    return this.ring[(this.next - 1 + this.capacity) % this.capacity] ?? null;
  }

  reset(): void {
    this.ring.fill(undefined);
    this.next = 0;
    this.filled = 0;
  }

  stats(): FrameStats {
    return frameStats(this.samples());
  }
}

export interface BenchmarkOptions {
  /** Consecutive full redraws (default 60, at most 600). */
  readonly frames?: number;
  /** Read one pixel back after each frame so GPU work is included (default false). */
  readonly flush?: boolean;
}

export interface BenchmarkResult {
  readonly samples: readonly FrameSample[];
  readonly stats: FrameStats;
}

/** Cost of the last drag selection on the /xlog-poc page. */
export interface SelectionTiming {
  /** Points selected. */
  readonly points: number;
  /**
   * Selection computation of the canvas (docs/15 §7.3 "10만 점 선택 계산 ≤ 5ms"): hit index check
   * and rectangle query, ms; null for a click (no rectangle query).
   */
  readonly queryMs: number | null;
  /** Snapshot copy + summary (docs/15 §7.4), synchronous in the pointerup handler. */
  readonly computeMs: number;
  /** From pointerup to the React commit of summary and list (list computation included). */
  readonly commitMs: number;
}

/**
 * `window.__xlogPerf` (installed by the /xlog-poc page). Stable contract for the performance spec:
 *   ready           true once the dataset is generated and the first frame is drawn
 *   pointCount()    points held by the buffer; visibleCount() points drawn in the last full frame
 *   samples()       recorded frames (bounded ring), stats() their distribution, reset() clears them
 *   benchmark()     forces consecutive full redraws and resolves with their samples and stats
 *   lastSelection() timing of the last drag selection, or null
 */
export interface XLogPerfHook {
  readonly version: 1;
  readonly ready: boolean;
  pointCount(): number;
  visibleCount(): number;
  samples(): FrameSample[];
  stats(): FrameStats;
  reset(): void;
  benchmark(options?: BenchmarkOptions): Promise<BenchmarkResult>;
  lastSelection(): SelectionTiming | null;
}

declare global {
  interface Window {
    __xlogPerf?: XLogPerfHook;
  }
}

/** Installs the hook on `target`; the returned function removes it (only if still the same). */
export function installPerfHook(target: Window, hook: XLogPerfHook): () => void {
  target.__xlogPerf = hook;
  return () => {
    if (target.__xlogPerf === hook) {
      delete target.__xlogPerf;
    }
  };
}
