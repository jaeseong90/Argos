// SPDX-License-Identifier: Apache-2.0
//
// Results of the XLog performance spec. Every scenario appends one JSON line to
// <outDir>/xlog-frame-<runId>.jsonl (so a failed scenario, which restarts the Playwright worker,
// loses nothing); the global teardown turns the file into a Markdown report and a JSON summary.
import { appendFileSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { distribution, type Distribution } from './stats';

export interface EnvironmentInfo {
  readonly startedAt: string;
  readonly os: string;
  readonly cpuModel: string;
  readonly logicalCores: number;
  readonly memoryGb: number;
  readonly node: string;
  readonly playwright: string;
  readonly browser: string;
  readonly headless: boolean;
  readonly gpuRenderer: string | null;
  /** devicePixelRatios of this run (XLOG_PERF_DPR); each scenario records its own. */
  readonly devicePixelRatios: readonly number[];
  readonly viewport: string;
  readonly plotSize: string;
  /** Median rAF interval of an idle page: the display refresh period seen by the browser. */
  readonly refreshMs: number | null;
}

export interface UntracedControl {
  readonly frames: number;
  readonly interval: Distribution | null;
  readonly js: Distribution;
}

export interface RunResult {
  readonly run: number;
  /** Machine CPU load before the run, %: the background load (other processes). */
  readonly loadBefore: number | null;
  readonly quiet: boolean;
  readonly waitedMs: number;
  /** Machine CPU load during the traced run, %: includes the browser under test. */
  readonly loadDuring: number | null;
  /** Per-frame time from the trace (busiest pipeline thread). */
  readonly frames: Distribution;
  /** Frame start to next frame start, from the trace. */
  readonly interval: Distribution;
  /** Main-thread JavaScript of the frame (xlog:frame measure). */
  readonly js: Distribution;
  /** p95 busy time per pipeline thread. */
  readonly busyP95: Readonly<Record<string, number>>;
  readonly critical: readonly { readonly label: string; readonly share: number }[];
  /** The same workload without tracing (the page's own timing), to show the tracing overhead. */
  readonly untraced: UntracedControl | null;
  /**
   * Interaction only: frame times of a second trace around the end of the drag (pointer up, the
   * selection's React commit of summary and list, the next hover frames). Reported, not judged.
   */
  readonly afterSelection?: Distribution | null;
  readonly warnings: readonly string[];
}

export type ScenarioKind = 'static' | 'realtime' | 'interaction';

/** Backing store sizes of the two canvases, px ("1254x382"). */
export interface CanvasSizes {
  readonly points: string;
  readonly overlay: string;
}

export interface ScenarioResult {
  readonly title: string;
  readonly kind: ScenarioKind;
  readonly points: number;
  /** devicePixelRatio of the page (Playwright deviceScaleFactor). */
  readonly dpr: number;
  /** Canvas backing store sizes: the point layer is capped (ADR-0003 C-34), the overlay is not. */
  readonly canvas: CanvasSizes | null;
  /** Colour basis and y axis of the scenario. */
  readonly color: string;
  readonly y: string;
  /** Requested drawing mode ("default" = the page's default). */
  readonly render: string;
  /** Drawing mode the page reported. */
  readonly mode: string;
  /** Whether the 16 ms budget is judged for this scenario (comparison scenarios are not). */
  readonly judged: boolean;
  readonly runs: readonly RunResult[];
  readonly pooled: {
    readonly frames: Distribution;
    readonly interval: Distribution;
    readonly js: Distribution;
  };
  readonly pass: boolean | null;
  readonly reasons: readonly string[];
  readonly notes: readonly string[];
}

/** One drag selection on the page (window.__xlogPerf.lastSelection() and the reflect probe). */
export interface SelectionSample {
  readonly points: number;
  /** Hit index check + rectangle query: the docs/15 §7.3 "선택 계산". */
  readonly queryMs: number | null;
  /**
   * XLog screen (route) only: the screen's own selection computation, the points of the URL
   * selection found in the buffer (selectedIndices, no summary). Judged like queryMs (C-36).
   */
  readonly selectMs?: number | null;
  /**
   * XLog screen (route) only, diagnostics (WO-33 review r1-new-3): queryMs includes a rebuild of
   * the hit index, and the rebuild's share of queryMs (ms, 0 when the index was current).
   */
  readonly indexRebuilt?: boolean | null;
  readonly indexBuildMs?: number | null;
  /** PoC: snapshot copy + summary; route: selection + summary (part of the reflect path). */
  readonly computeMs: number;
  /** Pointer up to the React commit of summary and list. */
  readonly commitMs: number;
  /**
   * Screen reflect (ADR-0003 C-36, C-204, perf/lib/reflect.ts): pointer up to the frame with the
   * summary count and the first list row on screen, the largest of the page probe, the trace and
   * the trace's GPU end (reflectGpuMs). A preview row counts only when the first preview row is the
   * server's first row; otherwise this is serverReflectMs (judgedSelectionReflect, review
   * fe-perf-3).
   */
  readonly reflectMs?: number | null;
  /**
   * Route: pointer up to the frame after the list's server rows (page probe and trace, the larger),
   * the server-first-row reflect reported next to reflectMs (C-204 PM 결정 대기).
   */
  readonly serverReflectMs?: number | null;
  /**
   * Route: the reflect counted a preview row and its key equals the server's first row (true),
   * differs (false: reflectMs is the server rows'); null when no preview row was counted.
   */
  readonly previewMatchesServer?: boolean | null;
  /** The page probe's value and the trace's value of reflectMs (null without the trace events). */
  readonly reflectPageMs?: number | null;
  readonly reflectTraceMs?: number | null;
  /**
   * The trace's value including raster, GPU and viz drawing (display compositor draw and swap
   * after the main-thread frame, review fe-perf-6); null without those events. reflectMs is the
   * largest of page, trace and GPU.
   */
  readonly reflectGpuMs?: number | null;
  /** Pointer up to the frame with the summary count on screen (list not awaited), page probe. */
  readonly summaryMs?: number | null;
  /**
   * Pointer up to the list's server rows in the DOM when the screen previews the list from the
   * chart's points first (route, C-204; the commit, not a painted frame); reported, not judged.
   */
  readonly serverListMs?: number | null;
}

export interface SelectionRun {
  readonly run: number;
  readonly loadBefore: number | null;
  readonly quiet: boolean;
  readonly waitedMs: number;
  readonly loadDuring: number | null;
  readonly samples: readonly SelectionSample[];
}

/** docs/15 §7.3: "10만 점 선택 계산 ≤ 5ms". */
export const SELECTION_BUDGET_MS = 5;
/** ADR-0003 C-36: screen reflect after the drag p95 <= 100 ms (perf/lib/reflect.ts). */
export const REFLECT_BUDGET_MS = 100;

/**
 * Selection timing scenario. Judged per run (ADR-0003 C-36): the canvas query p95 and, on the XLog
 * screen, the screen's selection computation (selectMs) p95 <= budgetMs (5 ms), and the screen
 * reflect p95 <= reflectBudgetMs (100 ms) in every run; the pooled values are reported. Older
 * records (WO-04) have no verdict, no reflect and no select values.
 */
export interface SelectionResult {
  readonly title: string;
  readonly points: number;
  readonly dpr: number;
  readonly render: string;
  readonly runs: readonly SelectionRun[];
  readonly pooled: {
    readonly query: Distribution;
    readonly compute: Distribution;
    readonly commit: Distribution;
    readonly reflect?: Distribution;
    readonly summary?: Distribution;
    readonly select?: Distribution;
  };
  readonly budgetMs: number;
  readonly reflectBudgetMs?: number;
  /** Whether selectMs is judged (the XLog screen); absent or false on the PoC and older records. */
  readonly selectJudged?: boolean;
  readonly judged?: boolean;
  readonly pass?: boolean | null;
  readonly reasons?: readonly string[];
  readonly notes: readonly string[];
}

export type ResultRecord =
  | { readonly type: 'env'; readonly env: EnvironmentInfo }
  | { readonly type: 'scenario'; readonly result: ScenarioResult }
  | { readonly type: 'selection'; readonly result: SelectionResult };

function present(values: readonly (number | null | undefined)[]): number[] {
  return values.filter((v): v is number => typeof v === 'number');
}

/** Distributions of the selection samples of all runs. */
export function poolSelections(runs: readonly SelectionRun[]): Required<SelectionResult['pooled']> {
  const samples = runs.flatMap((run) => run.samples);
  return {
    query: distribution(samples.flatMap((s) => (s.queryMs === null ? [] : [s.queryMs]))),
    compute: distribution(samples.map((s) => s.computeMs)),
    commit: distribution(samples.map((s) => s.commitMs)),
    reflect: distribution(present(samples.map((s) => s.reflectMs))),
    summary: distribution(present(samples.map((s) => s.summaryMs))),
    select: distribution(present(samples.map((s) => s.selectMs))),
  };
}

/**
 * ADR-0003 C-36 verdict of a selection scenario, per run: query p95 <= queryBudgetMs and screen
 * reflect p95 <= reflectBudgetMs in every run; with selectBudgetMs (the XLog screen) also the
 * screen's selection computation p95 <= selectBudgetMs. A run without measured values fails.
 */
export function selectionReasons(
  runs: readonly SelectionRun[],
  queryBudgetMs: number = SELECTION_BUDGET_MS,
  reflectBudgetMs: number = REFLECT_BUDGET_MS,
  selectBudgetMs: number | null = null,
): string[] {
  const reasons: string[] = [];
  if (runs.length === 0) {
    reasons.push('no run measured');
  }
  for (const run of runs) {
    const pooled = poolSelections([run]);
    const label = `run ${String(run.run)}`;
    if (pooled.query.count === 0) {
      reasons.push(`${label}: no selection query time`);
    } else if (pooled.query.p95 > queryBudgetMs) {
      reasons.push(
        `${label}: selection query p95 ${pooled.query.p95.toFixed(2)} ms > ${String(queryBudgetMs)} ms`,
      );
    }
    if (selectBudgetMs !== null) {
      if (pooled.select.count === 0) {
        reasons.push(`${label}: no screen selection time (selectMs)`);
      } else if (pooled.select.p95 > selectBudgetMs) {
        reasons.push(
          `${label}: screen selection p95 ${pooled.select.p95.toFixed(2)} ms > ${String(selectBudgetMs)} ms`,
        );
      }
    }
    if (pooled.reflect.count === 0) {
      reasons.push(`${label}: no screen reflect measured`);
    } else if (pooled.reflect.p95 > reflectBudgetMs) {
      reasons.push(
        `${label}: screen reflect p95 ${pooled.reflect.p95.toFixed(1)} ms > ${String(reflectBudgetMs)} ms`,
      );
    }
  }
  return reasons;
}

/** Highest single-run p95 of the query and of the screen reflect (null without values). */
export function selectionRunMaxima(runs: readonly SelectionRun[]): {
  query: number | null;
  reflect: number | null;
} {
  let query: number | null = null;
  let reflect: number | null = null;
  for (const run of runs) {
    const pooled = poolSelections([run]);
    if (pooled.query.count > 0) {
      query = Math.max(query ?? 0, pooled.query.p95);
    }
    if (pooled.reflect.count > 0) {
      reflect = Math.max(reflect ?? 0, pooled.reflect.p95);
    }
  }
  return { query, reflect };
}

/** Highest single-run p95 of the screen's selection computation (null without values). */
export function selectionRunMaxSelect(runs: readonly SelectionRun[]): number | null {
  let select: number | null = null;
  for (const run of runs) {
    const pooled = poolSelections([run]);
    if (pooled.select.count > 0) {
      select = Math.max(select ?? 0, pooled.select.p95);
    }
  }
  return select;
}

export function appendRecord(file: string, record: ResultRecord): void {
  mkdirSync(dirname(file), { recursive: true });
  appendFileSync(file, `${JSON.stringify(record)}\n`, 'utf8');
}

export function readRecords(file: string): ResultRecord[] {
  let text: string;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  return text
    .split('\n')
    .filter((line) => line.trim() !== '')
    .map((line) => JSON.parse(line) as ResultRecord);
}

function ms(value: number): string {
  return value >= 100 ? value.toFixed(0) : value.toFixed(1);
}

export function triple(d: Distribution): string {
  return d.count === 0 ? '-' : `${ms(d.p50)} / ${ms(d.p95)} / ${ms(d.max)}`;
}

function percent(value: number | null): string {
  return value === null ? '-' : value.toFixed(0);
}

function loadRange(runs: readonly RunResult[], pick: (run: RunResult) => number | null): string {
  const values = runs.map(pick).filter((v): v is number => v !== null);
  if (values.length === 0) {
    return '-';
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  return min.toFixed(0) === max.toFixed(0) ? percent(min) : `${percent(min)}-${percent(max)}`;
}

function verdict(result: ScenarioResult): string {
  if (!result.judged || result.pass === null) {
    return 'not judged';
  }
  return result.pass ? 'PASS' : 'FAIL';
}

function busiest(result: ScenarioResult): string {
  const totals = new Map<string, number>();
  for (const run of result.runs) {
    for (const { label, share } of run.critical) {
      totals.set(label, (totals.get(label) ?? 0) + share / result.runs.length);
    }
  }
  return [...totals]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 2)
    .map(([label, share]) => `${label} ${(share * 100).toFixed(0)}%`)
    .join(', ');
}

/**
 * Highest p95 and highest mean of a single run. The verdict uses the pooled frames of all runs
 * (checkFrameBudget, C-34); these show when one run alone went over the budget.
 */
export function runMaxima(runs: readonly RunResult[]): { p95: number; mean: number } | null {
  const measured = runs.filter((run) => run.frames.count > 0);
  if (measured.length === 0) {
    return null;
  }
  return {
    p95: Math.max(...measured.map((run) => run.frames.p95)),
    mean: Math.max(...measured.map((run) => run.frames.mean)),
  };
}

/** Markdown report: environment, summary table (pooled runs) and one table per scenario. */
export function renderMarkdown(records: readonly ResultRecord[]): string {
  const env = records.find((r) => r.type === 'env')?.env ?? null;
  const results = records.flatMap((r) => (r.type === 'scenario' ? [r.result] : []));
  const selections = records.flatMap((r) => (r.type === 'selection' ? [r.result] : []));
  const lines: string[] = ['# XLog frame time (T-NFR-14)', ''];
  if (env !== null) {
    lines.push(
      `- Started: ${env.startedAt}`,
      `- Machine: ${env.os}, ${env.cpuModel}, ${String(env.logicalCores)} logical cores, ${env.memoryGb.toFixed(0)} GB`,
      `- Browser: ${env.browser} (${env.headless ? 'headless' : 'headed'}), Playwright ${env.playwright}, Node ${env.node}`,
      `- GPU: ${env.gpuRenderer ?? 'unknown'}`,
      `- Viewport ${env.viewport}, DPR ${env.devicePixelRatios.map(String).join(' and ')} (per scenario), plot ${env.plotSize} CSS px, refresh ${env.refreshMs === null ? '-' : `${env.refreshMs.toFixed(1)} ms`}`,
      '',
    );
  }
  lines.push(
    'Frame time = busiest rendering-pipeline thread per frame (Chrome trace). Budget (ADR-0003 C-34, C-35): p95 and mean <= 16 ms in every run; the verdict is per run, so Max run p95 and Max run mean (the highest p95 and mean of a single run) must both be <= 16 ms. The p50 / p95 / max and mean columns pool the frames of all runs (reported). Canvas px: backing store of the point layer (capped, ADR-0003 C-34) / the overlay.',
    '',
    '| Scenario | DPR | Canvas px (points / overlay) | Mode | Frames | Frame time p50 / p95 / max (ms) | Mean | Max run p95 (ms) | Max run mean (ms) | Interval p50 / p95 / max (ms) | JS p50 / p95 / max (ms) | Busiest thread | CPU load before (%) | Verdict |',
    '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|',
  );
  for (const r of results) {
    const maxima = runMaxima(r.runs);
    lines.push(
      `| ${r.title} | ${String(r.dpr)} | ${r.canvas === null ? '-' : `${r.canvas.points} / ${r.canvas.overlay}`} | ${r.mode} | ${String(r.pooled.frames.count)} | ${triple(r.pooled.frames)} | ${ms(r.pooled.frames.mean)} | ${maxima === null ? '-' : ms(maxima.p95)} | ${maxima === null ? '-' : ms(maxima.mean)} | ${triple(r.pooled.interval)} | ${triple(r.pooled.js)} | ${busiest(r)} | ${loadRange(r.runs, (run) => run.loadBefore)} | ${verdict(r)} |`,
    );
  }
  if (selections.length > 0) {
    lines.push(
      '',
      `Selection timing (ADR-0003 C-36; the Verdict column says whether a scenario is judged, per run): query = hit index check + rectangle query of the canvas, the docs/15 §7.3 "선택 계산" (budget ${String(SELECTION_BUDGET_MS)} ms, p95 per run); select = the XLog screen's own selection computation, the points of the URL selection found in the buffer (selectedIndices, no summary; budget ${String(SELECTION_BUDGET_MS)} ms, p95 per run, judged on the screen only, "-" on the PoC); compute = PoC: snapshot copy + summary, screen: selection + summary (part of the reflect path, not the ${String(SELECTION_BUDGET_MS)} ms budget); commit = pointer up to the React commit of summary and list; reflect = pointer up to the frame with the summary count and the first list row on screen (C-204, budget ${String(REFLECT_BUDGET_MS)} ms, p95 per run; the largest of the page probe, the trace's main-thread frame and its display compositor draw and swap (GPU)); summary = pointer up to the frame with the summary count (reported).`,
      '',
      '| Scenario | DPR | Selections | Points | Query p50 / p95 / max (ms) | Query within budget | Compute p50 / p95 / max (ms) | Commit p50 / p95 / max (ms) | CPU load before (%) | Max run query p95 (ms) | Reflect p50 / p95 / max (ms) | Max run reflect p95 (ms) | Summary p50 / p95 / max (ms) | Verdict | Select p50 / p95 / max (ms) | Max run select p95 (ms) |',
      '|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|',
    );
    for (const s of selections) {
      const points = [...new Set(s.runs.flatMap((run) => run.samples.map((x) => x.points)))];
      const maxima = selectionRunMaxima(s.runs);
      const maxSelect = selectionRunMaxSelect(s.runs);
      const pooled = poolSelections(s.runs);
      const verdict = s.judged === true ? (s.pass === true ? 'PASS' : 'FAIL') : 'not judged';
      lines.push(
        `| ${s.title} | ${String(s.dpr)} | ${String(s.pooled.commit.count)} | ${points.map(String).join(', ')} | ${triple(s.pooled.query)} | ${s.pooled.query.count === 0 ? '-' : `p95 ${s.pooled.query.p95 <= s.budgetMs ? '<=' : '>'} ${String(s.budgetMs)} ms`} | ${triple(s.pooled.compute)} | ${triple(s.pooled.commit)} | ${selectionLoadRange(s.runs)} | ${maxima.query === null ? '-' : ms(maxima.query)} | ${triple(pooled.reflect)} | ${maxima.reflect === null ? '-' : ms(maxima.reflect)} | ${triple(pooled.summary)} | ${verdict} | ${triple(pooled.select)} | ${maxSelect === null ? '-' : ms(maxSelect)} |`,
      );
    }
  }
  for (const r of results) {
    lines.push(
      '',
      `## ${r.title}`,
      '',
      '| Run | CPU before / during (%) | Quiet | Frame time p50 / p95 / max | Interval p50 / p95 / max | JS p50 / p95 / max | p95 busy per thread | Untraced interval p50 / p95 / max | Untraced JS p50 / p95 / max |',
      '|---|---|---|---|---|---|---|---|---|',
    );
    for (const run of r.runs) {
      const busy = Object.entries(run.busyP95)
        .map(([label, value]) => `${label} ${ms(value)}`)
        .join(', ');
      lines.push(
        `| ${String(run.run)} | ${percent(run.loadBefore)} / ${percent(run.loadDuring)} | ${run.quiet ? 'yes' : `no (waited ${String(Math.round(run.waitedMs / 1000))} s)`} | ${triple(run.frames)} | ${triple(run.interval)} | ${triple(run.js)} | ${busy} | ${run.untraced?.interval ? triple(run.untraced.interval) : '-'} | ${run.untraced ? triple(run.untraced.js) : '-'} |`,
      );
    }
    const afterSelection = r.runs.flatMap((run) =>
      run.afterSelection === undefined || run.afterSelection === null
        ? []
        : [
            `run ${String(run.run)}: frames around the end of the drag (pointer up, React commit of summary and list, next hover frames; not judged): p50 / p95 / max ${triple(run.afterSelection)} ms`,
          ],
    );
    const notes = [
      ...r.reasons,
      ...r.notes,
      ...afterSelection,
      ...new Set(r.runs.flatMap((run) => run.warnings)),
    ];
    if (notes.length > 0) {
      lines.push('', ...notes.map((note) => `- ${note}`));
    }
  }
  for (const s of selections) {
    lines.push(
      '',
      `## ${s.title}`,
      '',
      '| Run | CPU before / during (%) | Quiet | Selections | Query p50 / p95 / max | Compute p50 / p95 / max | Commit p50 / p95 / max | Reflect p50 / p95 / max | Reflect (trace) p50 / p95 / max | Reflect (GPU) p50 / p95 / max | Summary p50 / p95 / max | Select p50 / p95 / max |',
      '|---|---|---|---|---|---|---|---|---|---|---|---|',
    );
    for (const run of s.runs) {
      const pooled = poolSelections([run]);
      const traced = distribution(present(run.samples.map((x) => x.reflectTraceMs)));
      const gpu = distribution(present(run.samples.map((x) => x.reflectGpuMs)));
      lines.push(
        `| ${String(run.run)} | ${percent(run.loadBefore)} / ${percent(run.loadDuring)} | ${run.quiet ? 'yes' : `no (waited ${String(Math.round(run.waitedMs / 1000))} s)`} | ${String(run.samples.length)} | ${triple(pooled.query)} | ${triple(pooled.compute)} | ${triple(pooled.commit)} | ${triple(pooled.reflect)} | ${triple(traced)} | ${triple(gpu)} | ${triple(pooled.summary)} | ${triple(pooled.select)} |`,
      );
    }
    const notes = [...(s.reasons ?? []), ...s.notes];
    if (notes.length > 0) {
      lines.push('', ...notes.map((note) => `- ${note}`));
    }
  }
  return `${lines.join('\n')}\n`;
}

function selectionLoadRange(runs: readonly SelectionRun[]): string {
  const values = runs.map((run) => run.loadBefore).filter((v): v is number => v !== null);
  if (values.length === 0) {
    return '-';
  }
  const min = Math.min(...values);
  const max = Math.max(...values);
  return min.toFixed(0) === max.toFixed(0) ? percent(min) : `${percent(min)}-${percent(max)}`;
}
