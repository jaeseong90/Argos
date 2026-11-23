// SPDX-License-Identifier: Apache-2.0
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  appendRecord,
  poolSelections,
  readRecords,
  REFLECT_BUDGET_MS,
  renderMarkdown,
  runMaxima,
  SELECTION_BUDGET_MS,
  selectionReasons,
  selectionRunMaxima,
  selectionRunMaxSelect,
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

describe('selection verdict (ADR-0003 C-36: per run, query p95 <= 5 ms, screen reflect p95 <= 100 ms)', () => {
  const judgedRun = (
    n: number,
    queries: readonly number[],
    reflects: readonly (number | null)[],
  ): SelectionRun => ({
    run: n,
    loadBefore: 5,
    quiet: true,
    waitedMs: 0,
    loadDuring: 30,
    samples: queries.map((queryMs, i) => ({
      points: 99_900,
      queryMs,
      computeMs: 3,
      commitMs: 40,
      reflectMs: reflects[i] ?? null,
      reflectPageMs: reflects[i] ?? null,
      reflectTraceMs: null,
      summaryMs: 30,
    })),
  });

  it('passes only when every run keeps both budgets', () => {
    const ok = [judgedRun(1, [1, 2, 4.9], [60, 70, 99]), judgedRun(2, [1, 1, 1], [50, 50, 50])];
    expect(selectionReasons(ok)).toEqual([]);
    expect(selectionRunMaxima(ok)).toEqual({ query: 4.9, reflect: 99 });
    const slow = [judgedRun(1, [1, 2, 6.4], [60, 70, 80]), judgedRun(2, [1, 1, 1], [90, 95, 140])];
    expect(selectionReasons(slow)).toEqual([
      'run 1: selection query p95 6.40 ms > 5 ms',
      'run 2: screen reflect p95 140.0 ms > 100 ms',
    ]);
    expect(SELECTION_BUDGET_MS).toBe(5);
    expect(REFLECT_BUDGET_MS).toBe(100);
  });

  it('judges the screen selection (selectMs) per run when a select budget is given (XLog screen)', () => {
    const withSelect = (run: SelectionRun, selects: readonly (number | null)[]): SelectionRun => ({
      ...run,
      samples: run.samples.map((s, i) => ({ ...s, selectMs: selects[i] ?? null })),
    });
    const ok = [
      withSelect(judgedRun(1, [1, 2, 3], [60, 70, 80]), [1.5, 4.9, 2]),
      withSelect(judgedRun(2, [1, 1, 1], [50, 50, 50]), [1, 1, 1]),
    ];
    expect(
      selectionReasons(ok, SELECTION_BUDGET_MS, REFLECT_BUDGET_MS, SELECTION_BUDGET_MS),
    ).toEqual([]);
    expect(selectionRunMaxSelect(ok)).toBe(4.9);
    // The engine query is within budget, the screen's selection is not: the run fails.
    const slow = [
      withSelect(judgedRun(1, [1, 2, 3], [60, 70, 80]), [6, 12.9, 3]),
      withSelect(judgedRun(2, [1, 1, 1], [50, 50, 50]), [1, 1, 1]),
    ];
    expect(
      selectionReasons(slow, SELECTION_BUDGET_MS, REFLECT_BUDGET_MS, SELECTION_BUDGET_MS),
    ).toEqual(['run 1: screen selection p95 12.90 ms > 5 ms']);
    // A judged run without selectMs fails; without a select budget (PoC page) it is not judged.
    const missing = [judgedRun(1, [1, 2, 3], [60, 70, 80])];
    expect(
      selectionReasons(missing, SELECTION_BUDGET_MS, REFLECT_BUDGET_MS, SELECTION_BUDGET_MS),
    ).toEqual(['run 1: no screen selection time (selectMs)']);
    expect(selectionReasons(missing)).toEqual([]);
    expect(selectionRunMaxSelect(missing)).toBeNull();
    // The report shows query, select and compute next to each other (select appended).
    const result: SelectionResult = {
      title: 'selection route 100k drag render=default dpr=1',
      points: 99_900,
      dpr: 1,
      render: 'default',
      runs: slow,
      pooled: poolSelections(slow),
      budgetMs: SELECTION_BUDGET_MS,
      reflectBudgetMs: REFLECT_BUDGET_MS,
      selectJudged: true,
      judged: true,
      pass: false,
      reasons: ['run 1: screen selection p95 12.90 ms > 5 ms'],
      notes: [],
    };
    const md = renderMarkdown([{ type: 'selection', result }]);
    expect(md).toContain('| FAIL | 1.0 / 12.9 / 12.9 | 12.9 |');
    expect(md).toContain('| 30.0 / 30.0 / 30.0 | 6.0 / 12.9 / 12.9 |');
    expect(md).toContain('- run 1: screen selection p95 12.90 ms > 5 ms');
  });

  it('fails a run without reflect or query values and a scenario without runs', () => {
    expect(selectionReasons([judgedRun(1, [1], [null])])).toEqual([
      'run 1: no screen reflect measured',
    ]);
    expect(selectionReasons([])).toEqual(['no run measured']);
    expect(selectionRunMaxima([])).toEqual({ query: null, reflect: null });
  });

  it('renders the per-run maxima, the reflect distributions and the verdict', () => {
    const runs = [judgedRun(1, [1, 2, 3], [60, 70, 80]), judgedRun(2, [2, 2, 2], [90, 95, 99])];
    const result: SelectionResult = {
      title: 'selection route 100k drag render=default dpr=1',
      points: 99_900,
      dpr: 1,
      render: 'default',
      runs,
      pooled: poolSelections(runs),
      budgetMs: SELECTION_BUDGET_MS,
      reflectBudgetMs: REFLECT_BUDGET_MS,
      judged: true,
      pass: true,
      reasons: [],
      notes: [],
    };
    const md = renderMarkdown([{ type: 'selection', result }]);
    expect(md).toContain('| 5 | 3.0 | 80.0 / 99.0 / 99.0 | 99.0 | 30.0 / 30.0 / 30.0 | PASS |');
    // Reflect, its trace value and its GPU value (review fe-perf-6; none in this run's samples).
    expect(md).toContain(
      '| 2 | 5 / 30 | yes | 3 | 2.0 / 2.0 / 2.0 | 3.0 / 3.0 / 3.0 | 40.0 / 40.0 / 40.0 | 95.0 / 99.0 / 99.0 | - | - | 30.0 / 30.0 / 30.0 |',
    );
    const failed = renderMarkdown([
      {
        type: 'selection',
        result: {
          ...result,
          pass: false,
          reasons: ['run 2: screen reflect p95 140.0 ms > 100 ms'],
        },
      },
    ]);
    expect(failed).toContain('| FAIL |');
    expect(failed).toContain('- run 2: screen reflect p95 140.0 ms > 100 ms');
  });
});
