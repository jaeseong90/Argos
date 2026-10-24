// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  appendRecord,
  poolSelections,
  readRecords,
  renderMarkdown,
  runMaxima,
  SELECTION_BUDGET_MS,
  triple,
  type EnvironmentInfo,
  type ResultRecord,
  type RunResult,
  type ScenarioResult,
  type SelectionResult,
  type SelectionRun,
} from './results';
import { distribution } from './stats';

const env: EnvironmentInfo = {
  startedAt: '2026-10-23T00:00:00.000Z',
  os: 'Windows_NT 10.0.26200 x64',
  cpuModel: 'Test CPU',
  logicalCores: 12,
  memoryGb: 16,
  node: 'v24.14.0',
  playwright: '1.64.1',
  browser: 'chromium 156.0.0.0',
  headless: true,
  gpuRenderer: 'ANGLE (Test GPU)',
  devicePixelRatios: [1, 2],
  viewport: '1366x768',
  plotSize: '1256x384',
  refreshMs: 6.9,
};

function run(n: number, load: number, frames: readonly number[] = [8, 9, 10, 120]): RunResult {
  return {
    run: n,
    loadBefore: load,
    quiet: load <= 20,
    waitedMs: load <= 20 ? 2000 : 60_000,
    loadDuring: 30,
    frames: distribution(frames),
    interval: distribution([13.9, 13.9]),
    js: distribution([5, 6]),
    busyP95: { main: 6, gpu: 10 },
    critical: [{ label: 'gpu', share: 1 }],
    untraced: { frames: 2, interval: distribution([6.9]), js: distribution([5]) },
    warnings: ['w'],
  };
}

const scenario: ScenarioResult = {
  title: 'T-NFR-14 static 100k render=default',
  kind: 'static',
  points: 100_000,
  dpr: 2,
  canvas: { points: '1254x382', overlay: '2668x840' },
  color: 'status',
  y: 'linear',
  render: 'default',
  mode: 'path',
  judged: true,
  runs: [run(1, 5, [8, 9, 10, 12]), run(2, 35)],
  pooled: {
    frames: distribution([8, 9, 10, 120]),
    interval: distribution([13.9]),
    js: distribution([5]),
  },
  pass: false,
  reasons: ['p95 120 ms > 16 ms'],
  notes: ['note'],
};

let dir: string | null = null;

afterEach(() => {
  if (dir !== null) {
    rmSync(dir, { recursive: true, force: true });
    dir = null;
  }
});

describe('results file', () => {
  it('appends JSON lines and reads them back; a missing file has no records', () => {
    dir = mkdtempSync(join(tmpdir(), 'xlog-perf-'));
    const file = join(dir, 'sub', 'r.jsonl');
    expect(readRecords(file)).toEqual([]);
    const records: ResultRecord[] = [
      { type: 'env', env },
      { type: 'scenario', result: scenario },
    ];
    for (const record of records) {
      appendRecord(file, record);
    }
    expect(readRecords(file)).toEqual(records);
  });
});

describe('renderMarkdown', () => {
  it('writes the environment, the summary row and the per-run rows', () => {
    const md = renderMarkdown([
      { type: 'env', env },
      { type: 'scenario', result: scenario },
    ]);
    expect(md).toContain('- GPU: ANGLE (Test GPU)');
    expect(md).toContain('refresh 6.9 ms');
    expect(md).toContain('DPR 1 and 2 (per scenario)');
    expect(md).toContain(
      '| T-NFR-14 static 100k render=default | 2 | 1254x382 / 2668x840 | path | 4 | 9.0 / 120 / 120 | 36.8 | 120 | 36.8 | 13.9 / 13.9 / 13.9 | 5.0 / 5.0 / 5.0 | gpu 100% | 5-35 | FAIL |',
    );
    expect(md).toContain('| 1 | 5 / 30 | yes |');
    expect(md).toContain('| 2 | 35 / 30 | no (waited 60 s) |');
    expect(md).toContain('main 6.0, gpu 10.0');
    expect(md).toContain('- p95 120 ms > 16 ms\n- note\n- w');
  });

  it('shows the highest single-run p95 and mean next to the pooled verdict', () => {
    // A pooled pass whose first run alone is over the budget (runB-pixels static 100k, 1st series).
    const frames = (p95: number, mean: number): RunResult['frames'] => ({
      count: 119,
      mean,
      p50: 10,
      p95,
      max: 18.4,
    });
    const runs: RunResult[] = [
      { ...run(1, 3), frames: frames(16.7, 11.6) },
      { ...run(2, 3), frames: frames(15.5, 11.2) },
      { ...run(3, 4), frames: frames(12.5, 10.2) },
    ];
    expect(runMaxima(runs)).toEqual({ p95: 16.7, mean: 11.6 });
    const md = renderMarkdown([
      {
        type: 'scenario',
        result: {
          ...scenario,
          title: 'T-NFR-14 static 100k render=pixels',
          mode: 'pixels',
          runs,
          pooled: { ...scenario.pooled, frames: frames(15.5, 11.0) },
          pass: true,
          reasons: [],
        },
      },
    ]);
    expect(md).toContain('Max run p95 (ms) | Max run mean (ms) |');
    expect(md).toContain(
      '| T-NFR-14 static 100k render=pixels | 2 | 1254x382 / 2668x840 | pixels | 119 | 10.0 / 15.5 / 18.4 | 11.0 | 16.7 | 11.6 |',
    );
    expect(md).toContain('| PASS |');
  });

  it('has no run maxima without measured runs', () => {
    expect(runMaxima([])).toBe(null);
    expect(runMaxima([{ ...run(1, 5), frames: distribution([]) }])).toBe(null);
    const md = renderMarkdown([{ type: 'scenario', result: { ...scenario, runs: [] } }]);
    expect(md).toContain('| 36.8 | - | - |');
  });

  it('formats empty distributions as a dash', () => {
    expect(triple(distribution([]))).toBe('-');
  });

  it('notes the frames around the end of the drag of an interaction run (not judged)', () => {
    const md = renderMarkdown([
      {
        type: 'scenario',
        result: {
          ...scenario,
          kind: 'interaction',
          title: 'T-NFR-14 interaction 100k hover+drag render=default dpr=1',
          runs: [{ ...run(1, 5), afterSelection: distribution([2, 3, 64]) }],
        },
      },
    ]);
    expect(md).toContain(
      '- run 1: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max 3.0 / 64.0 / 64.0 ms',
    );
  });
});

describe('selection timing (docs/15 §7.3, not judged)', () => {
  const selectionRun = (n: number, load: number, queries: readonly number[]): SelectionRun => ({
    run: n,
    loadBefore: load,
    quiet: load <= 20,
    waitedMs: 1000,
    loadDuring: 40,
    samples: queries.map((queryMs, i) => ({
      points: 100_000,
      queryMs,
      computeMs: 8 + i,
      commitMs: 50 + i,
    })),
  });

  it('pools query, compute and commit times of all runs; clicks have no query time', () => {
    const runs = [selectionRun(1, 5, [2, 3]), selectionRun(2, 9, [4])];
    const pooled = poolSelections(runs);
    expect(pooled.query).toEqual(distribution([2, 3, 4]));
    expect(pooled.compute).toEqual(distribution([8, 9, 8]));
    expect(pooled.commit).toEqual(distribution([50, 51, 50]));
    const withClick: SelectionRun = {
      ...selectionRun(3, 5, []),
      samples: [{ points: 1, queryMs: null, computeMs: 0.1, commitMs: 3 }],
    };
    expect(poolSelections([withClick]).query.count).toBe(0);
  });

  it('renders the summary row against the 5 ms budget and one table per scenario', () => {
    const runs = [selectionRun(1, 5, [2, 3]), selectionRun(2, 9, [6])];
    const result: SelectionResult = {
      title: 'selection 100k full-plot drag render=default dpr=1',
      points: 100_000,
      dpr: 1,
      render: 'default',
      runs,
      pooled: poolSelections(runs),
      budgetMs: SELECTION_BUDGET_MS,
      notes: ['n'],
    };
    const md = renderMarkdown([{ type: 'selection', result }]);
    expect(md).toContain(
      '| selection 100k full-plot drag render=default dpr=1 | 1 | 3 | 100000 | 3.0 / 6.0 / 6.0 | p95 > 5 ms | 8.0 / 9.0 / 9.0 | 50.0 / 51.0 / 51.0 | 5-9 |',
    );
    expect(md).toContain('## selection 100k full-plot drag render=default dpr=1');
    expect(md).toContain(
      '| 1 | 5 / 40 | yes | 2 | 2.0 / 3.0 / 3.0 | 8.0 / 9.0 / 9.0 | 50.0 / 51.0 / 51.0 |',
    );
    expect(md).toContain('- n');
  });
});
