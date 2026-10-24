// SPDX-License-Identifier: Apache-2.0
//
// Per-frame cost of the XLog canvas from a Chrome trace (T-NFR-14, docs/15 §14).
//
// Frames are delimited by the `xlog:frame` user timing measures the /xlog-poc page emits with
// `perf=1` (one per drawn frame, starting when the frame's JavaScript starts). For frame i the
// window is [start_i, start_i+1). In that window the busy time of every thread of the rendering
// pipeline is summed from its top-level task events (`RunTask`):
//
//   main        renderer main thread (CrRendererMain): JavaScript, canvas command recording, React
//   compositor  renderer compositor thread (Compositor)
//   gpu         GPU process main thread (CrGpuMain): canvas rasterisation, texture uploads, swap;
//               with an in-process GPU (no GPU process) the browser's Chrome_InProcGpuThread
//   viz         display compositor (VizCompositorThread)
//   drdc        GPU display-compositor thread (DrDcThread), when Chrome uses one
//
// The frame time is the busiest of these threads in the window. The stages run in parallel on
// different frames (the main thread records frame i+1 while the GPU rasterises frame i), so the
// slowest stage bounds the frame rate: a frame time of at most 16 ms means every stage keeps up
// with 60 frames per second. Unlike the rAF interval, the value is not rounded up to the display's
// refresh period, and unlike the JavaScript time it includes the rasterisation Chrome defers to
// the GPU process. Threads that only wait (GpuVSyncThread) and thread pools are not counted.

export interface TraceEvent {
  readonly name: string;
  readonly cat?: string;
  readonly ph: string;
  /** Microseconds. */
  readonly ts: number;
  /** Microseconds (complete events, ph "X"). */
  readonly dur?: number;
  readonly pid: number;
  readonly tid: number;
  readonly id?: string | number;
  readonly id2?: { readonly local?: string; readonly global?: string };
  readonly args?: Readonly<Record<string, unknown>>;
}

export const FRAME_MEASURE_NAME = 'xlog:frame';

export interface PipelineThread {
  readonly label: string;
  /** "renderer": the page's renderer process; "gpu": any other process (GPU or browser). */
  readonly process: 'renderer' | 'gpu';
  readonly threadName: string;
}

export const PIPELINE_THREADS: readonly PipelineThread[] = [
  { label: 'main', process: 'renderer', threadName: 'CrRendererMain' },
  { label: 'compositor', process: 'renderer', threadName: 'Compositor' },
  { label: 'gpu', process: 'gpu', threadName: 'CrGpuMain' },
  // In-process GPU (--in-process-gpu, some headless or software set-ups): the GPU thread of the
  // browser process does the work of CrGpuMain.
  { label: 'gpu', process: 'gpu', threadName: 'Chrome_InProcGpuThread' },
  { label: 'viz', process: 'gpu', threadName: 'VizCompositorThread' },
  { label: 'drdc', process: 'gpu', threadName: 'DrDcThread' },
];

/** Top-level task events (devtools.timeline and toplevel categories; their union is used). */
const TASK_EVENT_NAMES: ReadonlySet<string> = new Set(['RunTask', 'ThreadControllerImpl::RunTask']);

export interface FrameMetrics {
  /** Frame start, ms since the first frame of the trace. */
  readonly startMs: number;
  /** Time to the next frame start, ms. */
  readonly intervalMs: number;
  /** Duration of the frame's `xlog:frame` measure (main-thread JavaScript), ms; null if unpaired. */
  readonly jsMs: number | null;
  /** Busy time per pipeline thread label in the frame window, ms. */
  readonly busyMs: Readonly<Record<string, number>>;
  /** Busy time of the busiest pipeline thread, ms (the per-frame time). */
  readonly frameMs: number;
  /** Label of the busiest thread. */
  readonly critical: string;
}

export interface FrameAnalysis {
  readonly frames: readonly FrameMetrics[];
  /** Labels of the pipeline threads found in the trace. */
  readonly threads: readonly string[];
  readonly rendererPid: number | null;
  readonly warnings: readonly string[];
}

export const MAIN_THREAD_MISSING = 'renderer main thread (CrRendererMain) not found in the trace';
export const GPU_THREAD_MISSING =
  'GPU main thread (CrGpuMain or Chrome_InProcGpuThread) not found: GPU work is not included';
export const TRACE_DATA_LOSS = 'Chrome reported trace data loss (buffer overflow)';

/** Type guard for one element of a `Tracing.dataCollected` payload. */
export function isTraceEvent(value: unknown): value is TraceEvent {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return (
    typeof v.name === 'string' &&
    typeof v.ph === 'string' &&
    typeof v.ts === 'number' &&
    typeof v.pid === 'number' &&
    typeof v.tid === 'number'
  );
}

interface Marker {
  readonly start: number;
  readonly end: number | null;
}

function asyncKey(event: TraceEvent): string {
  const id = event.id2?.local ?? event.id2?.global ?? (event.id === undefined ? '' : event.id);
  return `${String(event.pid)}|${String(id)}`;
}

/**
 * Frame markers: begin/end pairs of the user timing measure `name`, in the process that emitted
 * most of them (the page's renderer). Pairs are matched per async id in time order. Chrome writes
 * a measure of zero duration (a frame faster than the 0.1 ms timer resolution) as one async
 * instant event (ph "n"); it is a marker whose end equals its start.
 */
export function frameMarkers(
  events: readonly TraceEvent[],
  name: string = FRAME_MEASURE_NAME,
): { readonly pid: number | null; readonly markers: readonly Marker[] } {
  // At equal timestamps an end sorts before a begin, so back-to-back measures pair correctly.
  const phaseOrder = (e: TraceEvent): number => (e.ph === 'e' ? 0 : 1);
  const relevant = events
    .filter((e) => e.name === name && (e.ph === 'b' || e.ph === 'e' || e.ph === 'n'))
    .sort((a, b) => a.ts - b.ts || phaseOrder(a) - phaseOrder(b));
  const perPid = new Map<number, number>();
  for (const e of relevant) {
    if (e.ph !== 'e') {
      perPid.set(e.pid, (perPid.get(e.pid) ?? 0) + 1);
    }
  }
  let pid: number | null = null;
  let best = 0;
  for (const [candidate, count] of perPid) {
    if (count > best) {
      best = count;
      pid = candidate;
    }
  }
  if (pid === null) {
    return { pid: null, markers: [] };
  }
  const open = new Map<string, number[]>();
  const markers: { start: number; end: number | null }[] = [];
  for (const e of relevant) {
    if (e.pid !== pid) {
      continue;
    }
    if (e.ph === 'n') {
      markers.push({ start: e.ts, end: e.ts });
      continue;
    }
    const key = asyncKey(e);
    if (e.ph === 'b') {
      const queue = open.get(key) ?? [];
      queue.push(markers.length);
      open.set(key, queue);
      markers.push({ start: e.ts, end: null });
    } else {
      const queue = open.get(key);
      const index = queue?.shift();
      const marker = index === undefined ? undefined : markers[index];
      if (marker !== undefined && e.ts >= marker.start) {
        marker.end = e.ts;
      }
    }
  }
  return { pid, markers };
}

/** Sorted, non-overlapping union of [start, end) intervals. */
export function mergeIntervals(
  intervals: readonly (readonly [number, number])[],
): [number, number][] {
  const sorted = intervals
    .filter(([start, end]) => end > start)
    .map(([start, end]): [number, number] => [start, end])
    .sort((a, b) => a[0] - b[0]);
  const merged: [number, number][] = [];
  for (const interval of sorted) {
    const last = merged[merged.length - 1];
    if (last !== undefined && interval[0] <= last[1]) {
      last[1] = Math.max(last[1], interval[1]);
    } else {
      merged.push(interval);
    }
  }
  return merged;
}

/** Total overlap of merged intervals with [start, end). */
export function overlap(
  merged: readonly (readonly [number, number])[],
  start: number,
  end: number,
): number {
  if (end <= start) {
    return 0;
  }
  // First interval whose end is after `start` (binary search).
  let lo = 0;
  let hi = merged.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if ((merged[mid]?.[1] ?? 0) <= start) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  let total = 0;
  for (let i = lo; i < merged.length; i += 1) {
    const interval = merged[i];
    if (interval === undefined || interval[0] >= end) {
      break;
    }
    total += Math.min(end, interval[1]) - Math.max(start, interval[0]);
  }
  return total;
}

function threadNames(events: readonly TraceEvent[]): Map<string, string> {
  const names = new Map<string, string>();
  for (const e of events) {
    if (e.ph === 'M' && e.name === 'thread_name') {
      const name = e.args?.name;
      if (typeof name === 'string') {
        names.set(`${String(e.pid)}:${String(e.tid)}`, name);
      }
    }
  }
  return names;
}

/** Busy intervals (merged, µs) per pipeline thread label. */
export function pipelineBusy(
  events: readonly TraceEvent[],
  rendererPid: number,
): Map<string, [number, number][]> {
  const names = threadNames(events);
  const labelOf = new Map<string, string>();
  for (const [key, name] of names) {
    const pid = Number(key.slice(0, key.indexOf(':')));
    for (const thread of PIPELINE_THREADS) {
      if (thread.threadName !== name) {
        continue;
      }
      const inRenderer = pid === rendererPid;
      if ((thread.process === 'renderer') === inRenderer) {
        labelOf.set(key, thread.label);
      }
    }
  }
  const intervals = new Map<string, [number, number][]>();
  for (const label of new Set(labelOf.values())) {
    intervals.set(label, []);
  }
  for (const e of events) {
    if (e.ph !== 'X' || e.dur === undefined || !TASK_EVENT_NAMES.has(e.name)) {
      continue;
    }
    const label = labelOf.get(`${String(e.pid)}:${String(e.tid)}`);
    if (label !== undefined) {
      intervals.get(label)?.push([e.ts, e.ts + e.dur]);
    }
  }
  const merged = new Map<string, [number, number][]>();
  for (const [label, list] of intervals) {
    merged.set(label, mergeIntervals(list));
  }
  return merged;
}

/** Per-frame metrics of every frame that has a successor in the trace (the last one has no window end). */
export function analyzeFrames(
  events: readonly TraceEvent[],
  measureName: string = FRAME_MEASURE_NAME,
): FrameAnalysis {
  const warnings: string[] = [];
  const { pid, markers } = frameMarkers(events, measureName);
  if (pid === null || markers.length < 2) {
    warnings.push(`fewer than two "${measureName}" frames in the trace`);
    return { frames: [], threads: [], rendererPid: pid, warnings };
  }
  const busy = pipelineBusy(events, pid);
  const threads = [...new Set(PIPELINE_THREADS.map((t) => t.label))].filter((label) =>
    busy.has(label),
  );
  if (!busy.has('main')) {
    warnings.push(MAIN_THREAD_MISSING);
  }
  if (!busy.has('gpu')) {
    warnings.push(GPU_THREAD_MISSING);
  }
  const origin = markers[0]?.start ?? 0;
  const frames: FrameMetrics[] = [];
  for (let i = 0; i + 1 < markers.length; i += 1) {
    const marker = markers[i];
    const next = markers[i + 1];
    if (marker === undefined || next === undefined) {
      continue;
    }
    const busyMs: Record<string, number> = {};
    let frameMs = 0;
    let critical = threads[0] ?? 'main';
    for (const label of threads) {
      const value = overlap(busy.get(label) ?? [], marker.start, next.start) / 1000;
      busyMs[label] = value;
      if (value > frameMs) {
        frameMs = value;
        critical = label;
      }
    }
    frames.push({
      startMs: (marker.start - origin) / 1000,
      intervalMs: (next.start - marker.start) / 1000,
      jsMs: marker.end === null ? null : (marker.end - marker.start) / 1000,
      busyMs,
      frameMs,
      critical,
    });
  }
  return { frames, threads, rendererPid: pid, warnings };
}

/**
 * Broken preconditions of a frame-time measurement, which make its numbers meaningless rather
 * than merely noisy: without the renderer main thread or the GPU thread the busiest-thread time
 * leaves out a pipeline stage (without the GPU thread `path` at 100k points would look like its
 * main-thread JavaScript, about 12 ms, instead of its rasterisation, 60 ms and more), and with
 * trace data loss tasks are missing. The spec turns these into failures of judged scenarios.
 * A trace without frames is reported by the frame count checks of the spec instead.
 */
export function measurementProblems(analysis: FrameAnalysis, dataLoss: boolean): string[] {
  const problems: string[] = [];
  if (analysis.rendererPid !== null && analysis.frames.length > 0) {
    if (!analysis.threads.includes('main')) {
      problems.push(MAIN_THREAD_MISSING);
    }
    if (!analysis.threads.includes('gpu')) {
      problems.push(GPU_THREAD_MISSING);
    }
  }
  if (dataLoss) {
    problems.push(TRACE_DATA_LOSS);
  }
  return problems;
}

/** How often each thread was the busiest one, most frequent first. */
export function criticalShares(
  frames: readonly FrameMetrics[],
): { label: string; share: number }[] {
  const counts = new Map<string, number>();
  for (const frame of frames) {
    counts.set(frame.critical, (counts.get(frame.critical) ?? 0) + 1);
  }
  return [...counts]
    .map(([label, count]) => ({ label, share: frames.length === 0 ? 0 : count / frames.length }))
    .sort((a, b) => b.share - a.share);
}
