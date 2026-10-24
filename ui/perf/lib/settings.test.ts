// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import {
  configLabel,
  gpuMismatch,
  MIN_RUNS,
  perfSettings,
  pocQuery,
  pointsLabel,
  ratio,
  WORST_CONFIG,
} from './settings';

describe('perfSettings', () => {
  it('has the documented defaults', () => {
    expect(perfSettings({})).toEqual({
      points: [10_000, 50_000, 100_000],
      renders: ['default'],
      scenarios: ['static', 'static-worst', 'realtime', 'interaction', 'selection'],
      dprs: [1, 2],
      color: 'status',
      y: 'linear',
      pointCap: null,
      runs: 3,
      frames: 120,
      warmupFrames: 30,
      liveSeconds: 10,
      liveRate: 500,
      maxLoad: 20,
      maxWaitMs: 60_000,
      compare: false,
      headed: false,
      outDir: 'perf/results',
      expectGpu: null,
    });
  });

  it('reads valid values', () => {
    const s = perfSettings({
      XLOG_PERF_POINTS: '100000, 10000,100000',
      XLOG_PERF_RENDER: 'path,pixels',
      XLOG_PERF_SCENARIOS: 'static',
      XLOG_PERF_RUNS: '5',
      XLOG_PERF_FRAMES: '300',
      XLOG_PERF_LIVE_SECONDS: '30',
      XLOG_PERF_MAX_LOAD: '10',
      XLOG_PERF_MAX_WAIT: '0',
      XLOG_PERF_COMPARE: '1',
      XLOG_PERF_HEADED: 'true',
      XLOG_PERF_OUT: ' out/perf ',
      XLOG_PERF_EXPECT_GPU: ' Radeon ',
      XLOG_PERF_DPR: '2, 1.25,2',
      XLOG_PERF_COLOR: 'service',
      XLOG_PERF_Y: 'log',
      XLOG_PERF_POINT_CAP: '1.5',
    });
    expect(s.points).toEqual([100_000, 10_000]);
    expect(s.renders).toEqual(['path', 'pixels']);
    expect(s.scenarios).toEqual(['static']);
    expect(s.dprs).toEqual([2, 1.25]);
    expect(s.color).toBe('service');
    expect(s.y).toBe('log');
    expect(s.pointCap).toBe(1.5);
    expect(s.runs).toBe(5);
    expect(s.frames).toBe(300);
    expect(s.liveSeconds).toBe(30);
    expect(s.maxLoad).toBe(10);
    expect(s.maxWaitMs).toBe(0);
    expect(s.compare).toBe(true);
    expect(s.headed).toBe(true);
    expect(s.outDir).toBe('out/perf');
    expect(s.expectGpu).toBe('Radeon');
  });

  it('falls back to the defaults on invalid values and never runs fewer than 3 times', () => {
    const s = perfSettings({
      XLOG_PERF_POINTS: '10000,abc',
      XLOG_PERF_RENDER: 'path,webgl',
      XLOG_PERF_SCENARIOS: 'static,soak',
      XLOG_PERF_RUNS: '1',
      XLOG_PERF_FRAMES: '10',
      XLOG_PERF_LIVE_RATE: '-5',
    });
    expect(s.points).toEqual([10_000, 50_000, 100_000]);
    expect(s.renders).toEqual(['default']);
    expect(s.scenarios).toEqual(['static', 'static-worst', 'realtime', 'interaction', 'selection']);
    expect(s.runs).toBe(MIN_RUNS);
    expect(s.frames).toBe(120);
    expect(s.liveRate).toBe(500);
    expect(perfSettings({ XLOG_PERF_POINTS: '200000' }).points).toEqual([10_000, 50_000, 100_000]);
  });

  it.each(['0.5', '3.5', '4', '1.125', '2e0', 'x', '1,abc'])(
    'falls back to DPR 1 and 2 for XLOG_PERF_DPR=%s (allowed 1-3, up to 2 decimals)',
    (value) => {
      expect(perfSettings({ XLOG_PERF_DPR: value }).dprs).toEqual([1, 2]);
    },
  );

  it('reads DPR 3 and ignores an invalid colour, y axis or point cap', () => {
    const s = perfSettings({
      XLOG_PERF_DPR: '3',
      XLOG_PERF_COLOR: '<script>',
      XLOG_PERF_Y: 'sqrt',
      XLOG_PERF_POINT_CAP: '0.9',
    });
    expect(s.dprs).toEqual([3]);
    expect(s.color).toBe('status');
    expect(s.y).toBe('linear');
    expect(s.pointCap).toBeNull();
    expect(ratio(' 1.25 ')).toBe(1.25);
    expect(ratio(undefined)).toBeNull();
  });
});

describe('gpuMismatch (XLOG_PERF_EXPECT_GPU)', () => {
  const RADEON =
    'ANGLE (AMD, AMD Radeon(TM) Graphics (0x00001638) Direct3D11 vs_5_0 ps_5_0, D3D11)';
  const NVIDIA =
    'ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 Ti Laptop GPU (0x000025A0) Direct3D11 vs_5_0 ps_5_0, D3D11)';

  it('accepts any GPU when nothing is expected, and an empty setting expects nothing', () => {
    expect(gpuMismatch(null, NVIDIA)).toBe(null);
    expect(gpuMismatch(null, null)).toBe(null);
    expect(perfSettings({ XLOG_PERF_EXPECT_GPU: '  ' }).expectGpu).toBe(null);
  });

  it('matches a case-insensitive part of the renderer string', () => {
    expect(gpuMismatch('Radeon', RADEON)).toBe(null);
    expect(gpuMismatch('amd radeon(tm) graphics', RADEON)).toBe(null);
  });

  it('reports another GPU or an unknown one', () => {
    expect(gpuMismatch('Radeon', NVIDIA)).toBe(
      `GPU "${NVIDIA}" does not contain XLOG_PERF_EXPECT_GPU "Radeon": the browser renders on another GPU than the one to measure`,
    );
    expect(gpuMismatch('Radeon', null)).toBe(
      'GPU unknown (no WebGL renderer string), XLOG_PERF_EXPECT_GPU "Radeon" cannot be confirmed',
    );
  });
});

describe('pointsLabel and pocQuery', () => {
  it('labels point counts', () => {
    expect(pointsLabel(10_000)).toBe('10k');
    expect(pointsLabel(100_000)).toBe('100k');
    expect(pointsLabel(1500)).toBe('1.5k');
    expect(pointsLabel(999)).toBe('999');
  });

  it('builds the page query of a scenario', () => {
    expect(pocQuery({ n: 10_000, render: 'default' })).toBe('n=10000&perf=1');
    expect(pocQuery({ n: 100_000, render: 'pixels', live: true, rate: 500 })).toBe(
      'n=100000&perf=1&render=pixels&live=1&rate=500',
    );
    expect(pocQuery({ n: 5, render: 'path', live: true, incremental: false })).toBe(
      'n=5&perf=1&render=path&live=1&rate=500&incremental=0',
    );
  });

  it('passes colour, y axis and point cap only when they differ from the page defaults', () => {
    expect(
      pocQuery({ n: 10, render: 'default', color: 'status', y: 'linear', pointCap: null }),
    ).toBe('n=10&perf=1');
    expect(pocQuery({ n: 100_000, render: 'default', ...WORST_CONFIG, pointCap: 1.5 })).toBe(
      'n=100000&perf=1&color=service&y=log&pointcap=1.5',
    );
  });

  it('labels the drawing configuration and the DPR of a scenario', () => {
    expect(configLabel({ color: 'status', y: 'linear', pointCap: null, dpr: 1 })).toBe(' dpr=1');
    expect(configLabel({ ...WORST_CONFIG, pointCap: 1.25, dpr: 2 })).toBe(
      ' color=service y=log pointcap=1.25 dpr=2',
    );
  });
});
