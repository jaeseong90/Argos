// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import type { TraceEvent } from './frames';
import {
  judgedReflect,
  judgedSelectionReflect,
  REFLECT_BUDGET_MS,
  REFLECT_COMMIT,
  REFLECT_SERVER_COMMIT,
  REFLECT_START,
  reflectFromTrace,
  reflectGpuFromTrace,
  REFLECT_TRACE_CATEGORIES,
  type ReflectProbe,
} from './reflect';

const PID = 10;
const MAIN = 1;
const OTHER = 2;

function meta(tid: number, name: string): TraceEvent {
  return { name: 'thread_name', ph: 'M', ts: 0, pid: PID, tid, args: { name } };
}

function mark(name: string, ts: number): TraceEvent {
  return { name, ph: 'R', ts, pid: PID, tid: MAIN, cat: 'blink.user_timing' };
}

function task(name: string, ts: number, dur: number, tid = MAIN): TraceEvent {
  return { name, ph: 'X', ts, dur, pid: PID, tid };
}

describe('screen reflect from a trace (ADR-0003 C-36, C-204)', () => {
  it('ends at the main thread Commit of the frame after the commit mark', () => {
    const events: TraceEvent[] = [
      meta(MAIN, 'CrRendererMain'),
      meta(OTHER, 'Compositor'),
      mark(REFLECT_START, 1_000_000),
      // A frame before the DOM commit does not count.
      task('Commit', 1_010_000, 500),
      mark(REFLECT_COMMIT, 1_040_000),
      task('Commit', 1_050_000, 1_000, OTHER),
      task('Paint', 1_052_000, 2_000),
      task('Commit', 1_055_000, 1_000),
    ];
    // (1,055,000 + 1,000 - 1,000,000) µs = 56 ms.
    expect(reflectFromTrace(events)).toBe(56);
  });

  it('falls back to Paint and gives null without the marks or the main thread', () => {
    const base = [
      meta(MAIN, 'CrRendererMain'),
      mark(REFLECT_START, 0),
      mark(REFLECT_COMMIT, 50_000),
    ];
    expect(reflectFromTrace([...base, task('Paint', 60_000, 4_000)])).toBe(64);
    expect(reflectFromTrace(base)).toBeNull();
    expect(reflectFromTrace([meta(MAIN, 'CrRendererMain'), task('Commit', 1, 1)])).toBeNull();
    expect(reflectFromTrace([mark(REFLECT_START, 0), mark(REFLECT_COMMIT, 1)])).toBeNull();
  });

  it('judges the larger of the page probe and the trace', () => {
    expect(judgedReflect(80, 95)).toBe(95);
    expect(judgedReflect(80, 60)).toBe(80);
    expect(judgedReflect(80, null)).toBe(80);
    expect(REFLECT_BUDGET_MS).toBe(100);
  });

  it('measures the server rows from their own commit mark', () => {
    const events: TraceEvent[] = [
      meta(MAIN, 'CrRendererMain'),
      mark(REFLECT_START, 0),
      mark(REFLECT_COMMIT, 40_000),
      task('Commit', 45_000, 1_000),
      mark(REFLECT_SERVER_COMMIT, 300_000),
      task('Commit', 310_000, 2_000),
    ];
    expect(reflectFromTrace(events)).toBe(46);
    expect(reflectFromTrace(events, REFLECT_SERVER_COMMIT)).toBe(312);
  });
});

/** The GPU process's display compositor (another process than the renderer). */
const GPU_PID = 20;
const VIZ = 7;
const COMPOSITOR = 3;

function vizTask(name: string, ts: number, dur: number): TraceEvent {
  return { name, ph: 'X', ts, dur, pid: GPU_PID, tid: VIZ, cat: 'viz' };
}

function reporter(ph: 'b' | 'e', id: string, ts: number): TraceEvent {
  return { name: 'PipelineReporter', ph, ts, pid: PID, tid: COMPOSITOR, id, cat: 'cc,benchmark' };
}

describe('screen reflect including raster, GPU and viz drawing (review fe-perf-6)', () => {
  const base: TraceEvent[] = [
    meta(MAIN, 'CrRendererMain'),
    meta(COMPOSITOR, 'Compositor'),
    {
      name: 'thread_name',
      ph: 'M',
      ts: 0,
      pid: GPU_PID,
      tid: VIZ,
      args: { name: 'VizCompositorThread' },
    },
    mark(REFLECT_START, 1_000_000),
    mark(REFLECT_COMMIT, 1_040_000),
    // The main-thread frame of the DOM change ends at 1,056,000 µs (56 ms).
    task('Commit', 1_055_000, 1_000),
  ];

  it('ends at the first draw and swap of the display compositor after the main-thread frame', () => {
    const events: TraceEvent[] = [
      ...base,
      // A draw of the previous frame (before the main-thread Commit ended) does not count.
      vizTask('Display::DrawAndSwap', 1_050_000, 2_000),
      vizTask('Display::DrawAndSwap', 1_064_000, 3_000),
      vizTask('Display::DrawAndSwap', 1_080_000, 3_000),
    ];
    // The GPU end is later than the main thread's Commit: (1,067,000 - 1,000,000) µs = 67 ms.
    expect(reflectFromTrace(events)).toBe(56);
    expect(reflectGpuFromTrace(events)).toBe(67);
    // The judged value is the largest of page, main thread and GPU.
    expect(judgedReflect(60, 56, 67)).toBe(67);
    expect(judgedReflect(90, 56, 67)).toBe(90);
    expect(judgedReflect(60, null, null)).toBe(60);
    expect(judgedReflect(60, null, 75)).toBe(75);
    expect(REFLECT_TRACE_CATEGORIES).toEqual(['viz']);
  });

  it('takes DirectRenderer::DrawFrame without Display::DrawAndSwap, and the PipelineReporter without viz events', () => {
    expect(
      reflectGpuFromTrace([...base, vizTask('DirectRenderer::DrawFrame', 1_060_000, 4_000)]),
    ).toBe(64);
    // No viz events (category not recorded): the end of the frame's reporter, the latest one that
    // began by the end of the main-thread frame and ended after it.
    expect(
      reflectGpuFromTrace([
        ...base,
        reporter('b', 'a', 1_020_000),
        reporter('e', 'a', 1_040_000),
        reporter('b', 'b', 1_036_000),
        reporter('e', 'b', 1_072_000),
        reporter('b', 'c', 1_050_000),
        reporter('e', 'c', 1_090_000),
        reporter('b', 'd', 1_060_000),
        reporter('e', 'd', 1_100_000),
      ]),
    ).toBe(90);
    // Neither: null (the page probe and the main-thread value decide).
    expect(reflectGpuFromTrace(base)).toBeNull();
    expect(reflectGpuFromTrace([mark(REFLECT_START, 0)])).toBeNull();
  });

  it('measures from the commit mark when the trace has no main-thread frame after it', () => {
    const events: TraceEvent[] = [
      meta(MAIN, 'CrRendererMain'),
      {
        name: 'thread_name',
        ph: 'M',
        ts: 0,
        pid: GPU_PID,
        tid: VIZ,
        args: { name: 'VizCompositorThread' },
      },
      mark(REFLECT_START, 0),
      mark(REFLECT_COMMIT, 40_000),
      vizTask('Display::DrawAndSwap', 30_000, 1_000),
      vizTask('Display::DrawAndSwap', 48_000, 2_000),
    ];
    expect(reflectFromTrace(events)).toBeNull();
    expect(reflectGpuFromTrace(events)).toBe(50);
  });

  it('judges the server rows with their GPU value too', () => {
    const probe: ReflectProbe = {
      startAt: 1000,
      summaryPaintedAt: 1040,
      commitAt: 1050,
      paintedAt: 1060,
      serverListAt: 1290,
      serverListPaintedAt: 1300,
      previewKey: null,
      serverKey: null,
    };
    expect(judgedSelectionReflect(probe, 62, 305, 71, 330)).toEqual({
      reflectMs: 71,
      serverReflectMs: 330,
      previewMatchesServer: null,
    });
  });
});

describe('preview rows count only when their first row is the server first row (C-204, fe-perf-3)', () => {
  const probe = (partial: Partial<ReflectProbe>): ReflectProbe => ({
    startAt: 1000,
    summaryPaintedAt: 1040,
    commitAt: 1050,
    paintedAt: 1060,
    serverListAt: 1290,
    serverListPaintedAt: 1300,
    previewKey: '18446744073709551615',
    serverKey: '18446744073709551615',
    ...partial,
  });

  it('keeps the preview reflect when the first preview row is the server first row', () => {
    expect(judgedSelectionReflect(probe({}), 70, 310)).toEqual({
      reflectMs: 70,
      serverReflectMs: 310,
      previewMatchesServer: true,
    });
  });

  it('counts the server rows when the first rows differ or the server list is empty', () => {
    expect(judgedSelectionReflect(probe({ serverKey: '18446744073709551614' }), 70, 310)).toEqual({
      reflectMs: 310,
      serverReflectMs: 310,
      previewMatchesServer: false,
    });
    // Without the trace values the page probe's values decide (300 ms after the pointer up).
    expect(judgedSelectionReflect(probe({ serverKey: null }), null, null)).toEqual({
      reflectMs: 300,
      serverReflectMs: 300,
      previewMatchesServer: false,
    });
  });

  it('judges the server rows directly when no preview row was counted', () => {
    expect(
      judgedSelectionReflect(
        probe({ previewKey: null, paintedAt: 1300, serverListPaintedAt: 1300 }),
        null,
        null,
      ),
    ).toEqual({ reflectMs: 300, serverReflectMs: 300, previewMatchesServer: null });
    // The PoC page has no server rows: the reflect alone.
    expect(
      judgedSelectionReflect(
        probe({ serverListAt: null, serverListPaintedAt: null, serverKey: null }),
        null,
        null,
      ),
    ).toEqual({ reflectMs: 60, serverReflectMs: null, previewMatchesServer: null });
  });
});
