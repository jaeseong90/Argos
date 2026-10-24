// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it } from 'vitest';

import {
  FrameRecorder,
  distribution,
  frameStats,
  installPerfHook,
  percentile,
  type FrameSample,
  type XLogPerfHook,
} from './perf';

function sample(totalMs: number, interval: number | null = null): FrameSample {
  return {
    t: 0,
    interval,
    pointsMs: totalMs,
    overlayMs: 0,
    totalMs,
    full: true,
    pointsDrawn: true,
    drawn: 100,
    passes: 3,
    mode: 'path',
    flushed: false,
  };
}

describe('frame statistics', () => {
  it('computes nearest-rank percentiles', () => {
    const values = Array.from({ length: 100 }, (_, i) => i + 1);
    expect(percentile(values, 0.5)).toBe(50);
    expect(percentile(values, 0.95)).toBe(95);
    expect(percentile(values, 1)).toBe(100);
    expect(percentile([], 0.5)).toBe(0);
  });

  it('summarises a distribution', () => {
    expect(distribution([4, 1, 3, 2])).toEqual({ count: 4, mean: 2.5, p50: 2, p95: 4, max: 4 });
    expect(distribution([])).toEqual({ count: 0, mean: 0, p50: 0, p95: 0, max: 0 });
  });

  it('reports rAF intervals only for consecutive frames', () => {
    const stats = frameStats([sample(1), sample(2, 16), sample(3, 17)]);
    expect(stats.frames).toBe(3);
    expect(stats.intervalMs?.count).toBe(2);
    expect(frameStats([sample(1)]).intervalMs).toBeNull();
  });
});

describe('FrameRecorder', () => {
  it('keeps the last N samples (bounded)', () => {
    const recorder = new FrameRecorder(3);
    for (let i = 1; i <= 5; i += 1) {
      recorder.push(sample(i));
    }
    expect(recorder.samples().map((s) => s.totalMs)).toEqual([3, 4, 5]);
    expect(recorder.last()?.totalMs).toBe(5);
    expect(recorder.stats().totalMs.max).toBe(5);
    recorder.reset();
    expect(recorder.samples()).toEqual([]);
    expect(recorder.last()).toBeNull();
  });
});

describe('installPerfHook', () => {
  afterEach(() => {
    delete window.__xlogPerf;
  });

  it('installs window.__xlogPerf and removes only its own hook', () => {
    const hook: XLogPerfHook = {
      version: 1,
      ready: true,
      pointCount: () => 1,
      visibleCount: () => 1,
      samples: () => [],
      stats: () => frameStats([]),
      reset: () => undefined,
      benchmark: () => Promise.resolve({ samples: [], stats: frameStats([]) }),
      lastSelection: () => ({ points: 100_000, queryMs: 3.5, computeMs: 9, commitMs: 60 }),
    };
    const uninstall = installPerfHook(window, hook);
    expect(window.__xlogPerf).toBe(hook);
    // The selection timing carries the docs/15 §7.3 selection computation separately.
    expect(window.__xlogPerf?.lastSelection()).toEqual({
      points: 100_000,
      queryMs: 3.5,
      computeMs: 9,
      commitMs: 60,
    });
    const other: XLogPerfHook = { ...hook };
    const uninstallOther = installPerfHook(window, other);
    uninstall();
    expect(window.__xlogPerf).toBe(other);
    uninstallOther();
    expect(window.__xlogPerf).toBeUndefined();
  });
});
