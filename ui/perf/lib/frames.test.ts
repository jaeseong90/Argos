// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import {
  analyzeFrames,
  criticalShares,
  frameMarkers,
  GPU_THREAD_MISSING,
  isTraceEvent,
  MAIN_THREAD_MISSING,
  measurementProblems,
  mergeIntervals,
  overlap,
  TRACE_DATA_LOSS,
  type TraceEvent,
} from './frames';

const RENDERER = 10;
const GPU = 20;
const BROWSER = 30;
const OTHER_RENDERER = 40;

function thread(pid: number, tid: number, name: string): TraceEvent {
  return { name: 'thread_name', ph: 'M', ts: 0, pid, tid, args: { name } };
}

/** Thread names of a typical Chrome trace (pid:tid). */
const METADATA: TraceEvent[] = [
  thread(RENDERER, 1, 'CrRendererMain'),
  thread(RENDERER, 2, 'Compositor'),
  thread(RENDERER, 3, 'ThreadPoolForegroundWorker'),
  thread(GPU, 1, 'CrGpuMain'),
  thread(GPU, 2, 'VizCompositorThread'),
  thread(GPU, 3, 'GpuVSyncThread'),
  thread(BROWSER, 1, 'CrBrowserMain'),
  thread(OTHER_RENDERER, 1, 'CrRendererMain'),
];

/** Complete top-level task, times in ms (trace events use µs). */
function task(
  pid: number,
  tid: number,
  startMs: number,
  durMs: number,
  name = 'RunTask',
): TraceEvent {
  return {
    name,
    cat: 'disabled-by-default-devtools.timeline',
    ph: 'X',
    ts: startMs * 1000,
    dur: durMs * 1000,
    pid,
    tid,
  };
}

/** One xlog:frame measure (begin + end), times in ms. */
function measure(startMs: number, durMs: number, pid = RENDERER, id = '0x40'): TraceEvent[] {
  const base = { name: 'xlog:frame', cat: 'blink.user_timing', pid, tid: 1, id2: { local: id } };
  return [
    { ...base, ph: 'b', ts: startMs * 1000 },
    { ...base, ph: 'e', ts: (startMs + durMs) * 1000 },
  ];
}

describe('isTraceEvent', () => {
  it('accepts trace events and rejects anything else', () => {
    expect(isTraceEvent({ name: 'RunTask', ph: 'X', ts: 1, pid: 1, tid: 2, dur: 3 })).toBe(true);
    expect(isTraceEvent({ name: 'RunTask', ph: 'X', ts: '1', pid: 1, tid: 2 })).toBe(false);
    expect(isTraceEvent({ ph: 'X', ts: 1, pid: 1, tid: 2 })).toBe(false);
    expect(isTraceEvent(null)).toBe(false);
    expect(isTraceEvent('RunTask')).toBe(false);
  });
});

describe('mergeIntervals and overlap', () => {
  it('merges overlapping and nested intervals and drops empty ones', () => {
    expect(
      mergeIntervals([
        [10, 20],
        [0, 5],
        [12, 15],
        [18, 25],
        [30, 30],
        [25, 26],
      ]),
    ).toEqual([
      [0, 5],
      [10, 26],
    ]);
  });

  it('sums the overlap with a window, clipping intervals at its edges', () => {
    const merged = mergeIntervals([
      [0, 5],
      [10, 26],
      [40, 50],
    ]);
    expect(overlap(merged, 3, 12)).toBe(4);
    expect(overlap(merged, 0, 100)).toBe(31);
    expect(overlap(merged, 26, 40)).toBe(0);
    expect(overlap(merged, 45, 45)).toBe(0);
    expect(overlap([], 0, 10)).toBe(0);
  });
});

describe('frameMarkers', () => {
  it('pairs begin and end per id in time order, also when every measure reuses one id', () => {
    const events = [...measure(32, 3), ...measure(0, 5), ...measure(16, 4)].reverse();
    const { pid, markers } = frameMarkers(events);
    expect(pid).toBe(RENDERER);
    expect(markers).toEqual([
      { start: 0, end: 5000 },
      { start: 16_000, end: 20_000 },
      { start: 32_000, end: 35_000 },
    ]);
  });

  it('keeps a begin without end and takes the process that emitted most frames', () => {
    const events = [
      ...measure(0, 2),
      { name: 'xlog:frame', ph: 'b', ts: 16_000, pid: RENDERER, tid: 1, id2: { local: '0x40' } },
      ...measure(5, 1, OTHER_RENDERER),
    ];
    const { pid, markers } = frameMarkers(events);
    expect(pid).toBe(RENDERER);
    expect(markers).toEqual([
      { start: 0, end: 2000 },
      { start: 16_000, end: null },
    ]);
  });

  it('takes a zero-duration measure (async instant event) as a frame without JavaScript time', () => {
    const instant: TraceEvent = {
      name: 'xlog:frame',
      cat: 'blink.user_timing',
      ph: 'n',
      ts: 10_000,
      pid: RENDERER,
      tid: 1,
      id2: { local: '0x1d' },
    };
    const { markers } = frameMarkers([...measure(0, 2), instant, ...measure(20, 1)]);
    expect(markers).toEqual([
      { start: 0, end: 2000 },
      { start: 10_000, end: 10_000 },
      { start: 20_000, end: 21_000 },
    ]);
  });

  it('returns no process without measures', () => {
    expect(frameMarkers(METADATA)).toEqual({ pid: null, markers: [] });
  });
});

describe('analyzeFrames', () => {
  it('takes the busiest pipeline thread per frame window, GPU work included', () => {
    const events: TraceEvent[] = [
      ...METADATA,
      // Frames start at 0, 20 and 40 ms; the third has no successor and is not reported.
      ...measure(0, 6),
      ...measure(20, 7),
      ...measure(40, 6),
      // Main thread: 6 ms of frame JavaScript, plus 2 ms of other work in frame 2.
      task(RENDERER, 1, 0, 6),
      task(RENDERER, 1, 20, 7),
      task(RENDERER, 1, 30, 2),
      // GPU main: rasterisation of frame 1 runs into frame 2's window.
      task(GPU, 1, 6, 18),
      // Nested task on the GPU main thread must not be counted twice.
      task(GPU, 1, 8, 4, 'ThreadControllerImpl::RunTask'),
      // Display compositor.
      task(GPU, 2, 25, 1),
      // Not pipeline threads: vsync waits, thread pools, browser and another renderer.
      task(GPU, 3, 0, 40),
      task(RENDERER, 3, 0, 40),
      task(BROWSER, 1, 0, 40),
      task(OTHER_RENDERER, 1, 0, 40),
    ];
    const analysis = analyzeFrames(events);
    expect(analysis.rendererPid).toBe(RENDERER);
    // Compositor exists (thread name) but is idle: reported with 0 ms.
    expect(analysis.threads).toEqual(['main', 'compositor', 'gpu', 'viz']);
    expect(analysis.warnings).toEqual([]);
    expect(analysis.frames).toHaveLength(2);
    const [first, second] = analysis.frames;
    expect(first).toEqual({
      startMs: 0,
      intervalMs: 20,
      jsMs: 6,
      busyMs: { main: 6, compositor: 0, gpu: 14, viz: 0 },
      frameMs: 14,
      critical: 'gpu',
    });
    expect(second).toEqual({
      startMs: 20,
      intervalMs: 20,
      jsMs: 7,
      busyMs: { main: 9, compositor: 0, gpu: 4, viz: 1 },
      frameMs: 9,
      critical: 'main',
    });
    expect(criticalShares(analysis.frames)).toEqual([
      { label: 'gpu', share: 0.5 },
      { label: 'main', share: 0.5 },
    ]);
  });

  it('counts a GPU-side thread in the browser process (no GPU process) as GPU work', () => {
    const events: TraceEvent[] = [
      thread(RENDERER, 1, 'CrRendererMain'),
      thread(BROWSER, 5, 'VizCompositorThread'),
      ...measure(0, 1),
      ...measure(16, 1),
      task(BROWSER, 5, 2, 12),
    ];
    const analysis = analyzeFrames(events);
    expect(analysis.frames[0]?.busyMs).toEqual({ main: 0, viz: 12 });
    expect(analysis.frames[0]?.critical).toBe('viz');
    expect(analysis.warnings).toEqual([GPU_THREAD_MISSING]);
    expect(GPU_THREAD_MISSING).toBe(
      'GPU main thread (CrGpuMain or Chrome_InProcGpuThread) not found: GPU work is not included',
    );
  });

  it('counts the in-process GPU thread of the browser process (Chrome_InProcGpuThread) as gpu', () => {
    const events: TraceEvent[] = [
      thread(RENDERER, 1, 'CrRendererMain'),
      thread(BROWSER, 7, 'Chrome_InProcGpuThread'),
      ...measure(0, 1),
      ...measure(16, 1),
      task(RENDERER, 1, 0, 1),
      task(BROWSER, 7, 1, 13),
    ];
    const analysis = analyzeFrames(events);
    expect(analysis.threads).toEqual(['main', 'gpu']);
    expect(analysis.frames[0]?.busyMs).toEqual({ main: 1, gpu: 13 });
    expect(analysis.frames[0]?.critical).toBe('gpu');
    expect(analysis.warnings).toEqual([]);
    expect(measurementProblems(analysis, false)).toEqual([]);
  });

  it('reports no frames, with a warning, when the trace has fewer than two measures', () => {
    const analysis = analyzeFrames([...METADATA, ...measure(0, 3), task(RENDERER, 1, 0, 3)]);
    expect(analysis.frames).toEqual([]);
    expect(analysis.warnings).toEqual(['fewer than two "xlog:frame" frames in the trace']);
  });
});

describe('measurementProblems', () => {
  it('has no problem for a complete trace without data loss', () => {
    const analysis = analyzeFrames([
      ...METADATA,
      ...measure(0, 6),
      ...measure(20, 7),
      task(RENDERER, 1, 0, 6),
      task(GPU, 1, 6, 10),
    ]);
    expect(analysis.warnings).toEqual([]);
    expect(measurementProblems(analysis, false)).toEqual([]);
  });

  it('turns a missing renderer main thread, a missing GPU thread and trace data loss into problems', () => {
    // Frames exist, but neither CrRendererMain nor a GPU main thread is named in the trace.
    const analysis = analyzeFrames([
      thread(GPU, 2, 'VizCompositorThread'),
      ...measure(0, 6),
      ...measure(20, 7),
      task(GPU, 2, 0, 3),
    ]);
    expect(analysis.frames).toHaveLength(1);
    expect(analysis.warnings).toEqual([MAIN_THREAD_MISSING, GPU_THREAD_MISSING]);
    expect(measurementProblems(analysis, true)).toEqual([
      MAIN_THREAD_MISSING,
      GPU_THREAD_MISSING,
      TRACE_DATA_LOSS,
    ]);
    expect(measurementProblems(analysis, false)).toEqual([MAIN_THREAD_MISSING, GPU_THREAD_MISSING]);
  });

  it('reports data loss alone for a trace without frames (the frame count checks cover the rest)', () => {
    const analysis = analyzeFrames(METADATA);
    expect(analysis.frames).toEqual([]);
    expect(measurementProblems(analysis, false)).toEqual([]);
    expect(measurementProblems(analysis, true)).toEqual([TRACE_DATA_LOSS]);
  });
});
