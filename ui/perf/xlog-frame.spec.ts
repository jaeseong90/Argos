// SPDX-License-Identifier: Apache-2.0
//
// T-NFR-14 (PoC version, WO-04): XLog canvas frame time on /xlog-poc, measured with Chrome tracing.
//
// Scenarios (docs/15 §14 "XLog 렌더 16ms/프레임, 10만 점", N-14):
//   static        10k / 50k / 100k points, consecutive full redraws of the point layer
//                 (window.__xlogPerf.benchmark: the worst case of resize, axis or range changes)
//   static-worst  100k points with the slowest drawing configuration measured: y = log and colour
//                 = service (12 palette colours + grey, 13 passes)
//   realtime      100k points + 500 points/s, 100 ms batches, 5-minute window (docs/15 §7.5)
//   interaction   100k points, hover and drag: overlay-only frames over an unchanged point layer;
//                 a second trace around the end of the drag (pointer up, the React commit of
//                 summary and list) is reported, not judged
//   selection     100k points, full-plot drag selections: selection computation (docs/15 §7.3,
//                 budget 5 ms), summary and React commit times; reported, not judged
//
// Every scenario runs once per devicePixelRatio of XLOG_PERF_DPR (default 1 and 2, ADR-0003 C-34),
// grouped by test.describe + test.use({ deviceScaleFactor }); titles carry "dpr=<n>".
//
// Per frame, the busiest rendering-pipeline thread (renderer main, compositor, GPU main, display
// compositor) is taken from the trace (perf/lib/frames.ts). Each scenario: warm-up, then at least
// 3 measured runs, each preceded by a machine CPU load check that waits for a quiet machine.
// Judged: p95 and mean of the pooled per-frame times <= 16 ms (perf/lib/stats.ts); p50/p95/max
// and the CPU load of every run go to perf/results/ (perf/lib/results.ts).
//
// Run: `corepack pnpm perf` (builds, serves dist/ with vite preview). Settings: perf/lib/settings.ts.
import { resolve } from 'node:path';

import { expect, test, type Browser, type Page, type TestInfo } from '@playwright/test';

import type { FrameSample } from '../src/xlog/perf';

import { cpuSnapshot, loadBetween, waitForQuiet, type QuietResult } from './lib/cpuLoad';
import {
  analyzeFrames,
  criticalShares,
  measurementProblems,
  type FrameMetrics,
} from './lib/frames';
import {
  appendRecord,
  poolSelections,
  readRecords,
  SELECTION_BUDGET_MS,
  type CanvasSizes,
  type RunResult,
  type ScenarioKind,
  type ScenarioResult,
  type SelectionResult,
  type SelectionRun,
  type SelectionSample,
  type UntracedControl,
} from './lib/results';
import {
  configLabel,
  gpuMismatch,
  perfSettings,
  pocQuery,
  pointsLabel,
  WORST_CONFIG,
  type ColorSetting,
  type RenderSetting,
  type ScenarioSetting,
  type YSetting,
} from './lib/settings';
import { checkFrameBudget, distribution, type Distribution } from './lib/stats';
import { traceDuring, type TraceCapture } from './lib/tracing';
import {
  benchmark,
  canvasInfo,
  environmentInfo,
  gpuRenderer,
  lastSelection,
  modeOf,
  openPoc,
  pointCount,
  pointsCanvas,
  resetSamples,
  samples,
  visibleCount,
} from './lib/xlogPage';

const settings = perfSettings(process.env);
const WORST_POINTS = 100_000;
const REALTIME_POINTS = 100_000;
const INTERACTION_POINTS = 100_000;
const SELECTION_POINTS = 100_000;
const SELECTIONS_PER_RUN = 5;
const REALTIME_WARMUP_MS = 3000;

/** Drawing configuration and devicePixelRatio of one scenario. */
interface Config {
  readonly dpr: number;
  readonly render: RenderSetting;
  readonly color: ColorSetting;
  readonly y: YSetting;
}

function query(
  config: Config,
  options: {
    readonly n: number;
    readonly live?: boolean;
    readonly rate?: number;
    readonly incremental?: boolean;
  },
): string {
  return pocQuery({
    ...options,
    render: config.render,
    color: config.color,
    y: config.y,
    pointCap: settings.pointCap,
  });
}

/** " color=service y=log dpr=2": the title suffix of a scenario. */
function label(config: Config): string {
  return configLabel({
    color: config.color,
    y: config.y,
    pointCap: settings.pointCap,
    dpr: config.dpr,
  });
}

function resultsFile(): string {
  const runId = process.env.XLOG_PERF_RUN_ID ?? 'adhoc';
  return resolve(settings.outDir, `xlog-frame-${runId}.jsonl`);
}

function annotate(testInfo: TestInfo): void {
  testInfo.annotations.push({ type: 'req', description: 'N-14' });
}

async function recordEnvironmentOnce(
  page: Page,
  browser: Browser,
  testInfo: TestInfo,
): Promise<void> {
  const file = resultsFile();
  if (readRecords(file).some((r) => r.type === 'env')) {
    return;
  }
  const env = await environmentInfo(page, browser, {
    headless: !settings.headed,
    playwright: testInfo.config.version,
    devicePixelRatios: settings.dprs,
  });
  appendRecord(file, { type: 'env', env });
}

/**
 * XLOG_PERF_EXPECT_GPU: the GPU the browser must render on (a hybrid-GPU laptop may pick the
 * discrete GPU on one run and the integrated one on the next). A mismatch fails judged scenarios;
 * comparison scenarios get it as a note (finish()).
 */
async function gpuProblems(page: Page): Promise<string[]> {
  if (settings.expectGpu === null) {
    return [];
  }
  const problem = gpuMismatch(settings.expectGpu, await gpuRenderer(page));
  return problem === null ? [] : [problem];
}

/**
 * The page's devicePixelRatio and canvas backing store sizes. A ratio other than the scenario's
 * is a broken measurement precondition (the scenario would measure another DPR).
 */
async function canvasOf(
  page: Page,
  config: Config,
): Promise<{ canvas: CanvasSizes | null; problems: string[] }> {
  const info = await canvasInfo(page);
  return {
    canvas: info.canvas,
    problems:
      info.dpr === config.dpr
        ? []
        : [`page devicePixelRatio ${String(info.dpr)}, expected ${String(config.dpr)}`],
  };
}

async function quietMachine(): Promise<QuietResult> {
  return waitForQuiet({ maxLoad: settings.maxLoad, maxWaitMs: settings.maxWaitMs });
}

function busyP95(
  frames: readonly FrameMetrics[],
  threads: readonly string[],
): Record<string, number> {
  const out: Record<string, number> = {};
  for (const label of threads) {
    out[label] = distribution(frames.map((f) => f.busyMs[label] ?? 0)).p95;
  }
  return out;
}

function untracedControl(frames: readonly FrameSample[]): UntracedControl {
  const intervals = frames.flatMap((s) => (s.interval === null ? [] : [s.interval]));
  return {
    frames: frames.length,
    interval: intervals.length === 0 ? null : distribution(intervals),
    js: distribution(frames.map((s) => s.totalMs)),
  };
}

interface RunInput {
  readonly run: number;
  readonly quiet: QuietResult;
  readonly loadDuring: number | null;
  readonly capture: TraceCapture<unknown>;
  readonly untraced: UntracedControl | null;
}

function runResult(input: RunInput): {
  result: RunResult;
  frames: readonly FrameMetrics[];
  /** Broken measurement preconditions (perf/lib/frames.ts measurementProblems), run-prefixed. */
  problems: readonly string[];
} {
  const analysis = analyzeFrames(input.capture.events);
  const warnings = [...analysis.warnings];
  const frames = analysis.frames;
  return {
    frames,
    problems: measurementProblems(analysis, input.capture.dataLoss).map(
      (problem) => `run ${String(input.run)}: ${problem}`,
    ),
    result: {
      run: input.run,
      loadBefore: input.quiet.load,
      quiet: input.quiet.quiet,
      waitedMs: input.quiet.waitedMs,
      loadDuring: input.loadDuring,
      frames: distribution(frames.map((f) => f.frameMs)),
      interval: distribution(frames.map((f) => f.intervalMs)),
      js: distribution(frames.flatMap((f) => (f.jsMs === null ? [] : [f.jsMs]))),
      busyP95: busyP95(frames, analysis.threads),
      critical: criticalShares(frames),
      untraced: input.untraced,
      warnings,
    },
  };
}

interface Finish {
  readonly title: string;
  readonly kind: ScenarioKind;
  readonly points: number;
  readonly config: Config;
  readonly canvas: CanvasSizes | null;
  readonly mode: string;
  readonly judged: boolean;
  readonly runs: readonly RunResult[];
  readonly frames: readonly FrameMetrics[];
  readonly notes: readonly string[];
  /** Failed sanity checks of the measurement itself (missing frames, stalled stream). */
  readonly problems: readonly string[];
}

async function finish(input: Finish, testInfo: TestInfo): Promise<ScenarioResult> {
  const pooledFrames = distribution(input.frames.map((f) => f.frameMs));
  const verdict = checkFrameBudget(pooledFrames);
  const result: ScenarioResult = {
    title: input.title,
    kind: input.kind,
    points: input.points,
    dpr: input.config.dpr,
    canvas: input.canvas,
    color: input.config.color,
    y: input.config.y,
    render: input.config.render,
    mode: input.mode,
    judged: input.judged,
    runs: input.runs,
    pooled: {
      frames: pooledFrames,
      interval: distribution(input.frames.map((f) => f.intervalMs)),
      js: distribution(input.frames.flatMap((f) => (f.jsMs === null ? [] : [f.jsMs]))),
    },
    pass: input.judged ? verdict.pass && input.problems.length === 0 : null,
    reasons: input.judged ? [...input.problems, ...verdict.reasons] : [],
    notes: input.judged ? input.notes : [...input.problems, ...input.notes],
  };
  appendRecord(resultsFile(), { type: 'scenario', result });
  await testInfo.attach('result.json', {
    body: JSON.stringify(result, null, 2),
    contentType: 'application/json',
  });
  return result;
}

function expectWithinBudget(result: ScenarioResult): void {
  const frames = result.pooled.frames;
  const summary = `${result.title}: ${String(frames.count)} frames, p50 ${frames.p50.toFixed(1)} / p95 ${frames.p95.toFixed(1)} / max ${frames.max.toFixed(1)} / mean ${frames.mean.toFixed(1)} ms`;
  expect(result.reasons, summary).toEqual([]);
}

function staticTimeout(): number {
  // Quiet wait + traced and untraced runs at up to 250 ms per frame, per run.
  return settings.runs * (settings.maxWaitMs + settings.frames * 2 * 250 + 30_000) + 120_000;
}

function liveTimeout(): number {
  return settings.runs * (settings.maxWaitMs + settings.liveSeconds * 2 * 1000 + 30_000) + 120_000;
}

function selectionTimeout(): number {
  return settings.runs * (settings.maxWaitMs + SELECTIONS_PER_RUN * 10_000 + 30_000) + 120_000;
}

const enabled = (scenario: ScenarioSetting): boolean => settings.scenarios.includes(scenario);

function staticScenario(n: number, config: Config, title: string): void {
  test(title, async ({ page, browser }, testInfo) => {
    test.setTimeout(staticTimeout());
    annotate(testInfo);
    await openPoc(page, query(config, { n }));
    await recordEnvironmentOnce(page, browser, testInfo);
    expect(await visibleCount(page), 'every point is drawn').toBe(n);
    const { canvas, problems: dprProblems } = await canvasOf(page, config);
    if (settings.warmupFrames > 0) {
      await benchmark(page, settings.warmupFrames);
    }
    const runs: RunResult[] = [];
    const frames: FrameMetrics[] = [];
    const problems: string[] = [...dprProblems, ...(await gpuProblems(page))];
    let mode = 'unknown';
    for (let run = 1; run <= settings.runs; run += 1) {
      const quiet = await quietMachine();
      const before = cpuSnapshot();
      const capture = await traceDuring(page, () => benchmark(page, settings.frames));
      const loadDuring = loadBetween(before, cpuSnapshot());
      const control = await benchmark(page, settings.frames);
      mode = modeOf(capture.result.samples);
      const measured = runResult({
        run,
        quiet,
        loadDuring,
        capture,
        untraced: untracedControl(control.samples),
      });
      if (measured.frames.length < settings.frames - 2) {
        problems.push(
          `run ${String(run)}: ${String(measured.frames.length)} frames in the trace, expected ${String(settings.frames - 1)}`,
        );
      }
      problems.push(...measured.problems);
      runs.push(measured.result);
      frames.push(...measured.frames);
    }
    const result = await finish(
      {
        title,
        kind: 'static',
        points: n,
        config,
        canvas,
        mode,
        judged: true,
        runs,
        frames,
        notes: [],
        problems,
      },
      testInfo,
    );
    expectWithinBudget(result);
  });
}

function realtimeScenarios(config: Config): void {
  const liveScenarios: { readonly incremental: boolean; readonly judged: boolean }[] = [
    { incremental: true, judged: true },
    ...(settings.compare ? [{ incremental: false, judged: false }] : []),
  ];
  for (const { incremental, judged } of liveScenarios) {
    const title = `${judged ? 'T-NFR-14' : 'comparison:'} realtime ${pointsLabel(REALTIME_POINTS)} + ${String(settings.liveRate)}/s render=${config.render}${incremental ? '' : ' incremental=0'}${label(config)}`;
    test(title, async ({ page, browser }, testInfo) => {
      test.setTimeout(liveTimeout());
      annotate(testInfo);
      await openPoc(
        page,
        query(config, { n: REALTIME_POINTS, live: true, rate: settings.liveRate, incremental }),
      );
      await recordEnvironmentOnce(page, browser, testInfo);
      const { canvas, problems: dprProblems } = await canvasOf(page, config);
      await page.waitForTimeout(REALTIME_WARMUP_MS);
      const runs: RunResult[] = [];
      const frames: FrameMetrics[] = [];
      const problems: string[] = [...dprProblems, ...(await gpuProblems(page))];
      const notes: string[] = [];
      let mode = 'unknown';
      for (let run = 1; run <= settings.runs; run += 1) {
        const quiet = await quietMachine();
        const pointsBefore = await pointCount(page);
        const before = cpuSnapshot();
        await resetSamples(page);
        const capture = await traceDuring(page, () =>
          page.waitForTimeout(settings.liveSeconds * 1000),
        );
        const loadDuring = loadBetween(before, cpuSnapshot());
        const traced = await samples(page);
        mode = modeOf(traced);
        await resetSamples(page);
        await page.waitForTimeout(settings.liveSeconds * 1000);
        const control = await samples(page);
        const pointsAfter = await pointCount(page);
        const measured = runResult({
          run,
          quiet,
          loadDuring,
          capture,
          untraced: untracedControl(control),
        });
        // About 10 frames per second (one per 100 ms batch); fewer means the stream stalled.
        if (measured.frames.length < settings.liveSeconds * 5) {
          problems.push(
            `run ${String(run)}: ${String(measured.frames.length)} frames in ${String(settings.liveSeconds)} s, expected about ${String(settings.liveSeconds * 10)} (one per 100 ms batch)`,
          );
        }
        const fullRedraws = traced.filter((s) => s.full).length;
        notes.push(
          `run ${String(run)}: ${String(traced.length)} frames (${String(fullRedraws)} full redraws), points ${String(pointsBefore)} -> ${String(pointsAfter)}`,
        );
        problems.push(...measured.problems);
        runs.push(measured.result);
        frames.push(...measured.frames);
      }
      const result = await finish(
        {
          title,
          kind: 'realtime',
          points: REALTIME_POINTS,
          config,
          canvas,
          mode,
          judged,
          runs,
          frames,
          notes,
          problems,
        },
        testInfo,
      );
      if (judged) {
        expectWithinBudget(result);
      }
    });
  }
}

function interactionScenario(config: Config): void {
  const title = `T-NFR-14 interaction ${pointsLabel(INTERACTION_POINTS)} hover+drag render=${config.render}${label(config)}`;
  test(title, async ({ page, browser }, testInfo) => {
    test.setTimeout(liveTimeout());
    annotate(testInfo);
    await openPoc(page, query(config, { n: INTERACTION_POINTS }));
    await recordEnvironmentOnce(page, browser, testInfo);
    const { canvas, problems: dprProblems } = await canvasOf(page, config);
    const box = await pointsCanvas(page).boundingBox();
    if (box === null) {
      throw new Error('The point canvas has no bounding box');
    }
    const mid = box.y + box.height / 2;
    // Warm-up: the first hover builds the hit index.
    await page.mouse.move(box.x + 4, mid);
    await page.mouse.move(box.x + box.width - 4, mid, { steps: 30 });
    const runs: RunResult[] = [];
    const frames: FrameMetrics[] = [];
    const problems: string[] = [...dprProblems, ...(await gpuProblems(page))];
    const notes: string[] = [];
    let mode = 'unknown';
    for (let run = 1; run <= settings.runs; run += 1) {
      const quiet = await quietMachine();
      const before = cpuSnapshot();
      await resetSamples(page);
      // Judged: hover and drag frames (the pointer is still down at the end).
      const capture = await traceDuring(page, async () => {
        await page.mouse.move(box.x + 4, box.y + 4);
        await page.mouse.move(box.x + box.width - 4, box.y + box.height - 4, { steps: 120 });
        await page.mouse.move(box.x + box.width * 0.2, box.y + box.height * 0.2, { steps: 20 });
        await page.mouse.down();
        await page.mouse.move(box.x + box.width * 0.7, box.y + box.height * 0.9, { steps: 120 });
      });
      const loadDuring = loadBetween(before, cpuSnapshot());
      const traced = await samples(page);
      mode = modeOf(traced);
      // Not judged: the end of the drag. The pointer up runs the selection, the summary and the
      // React commit of summary and list in one task; the frames after it are hover frames.
      const end = await traceDuring(page, async () => {
        await page.mouse.move(box.x + box.width * 0.72, box.y + box.height * 0.92, { steps: 10 });
        await page.mouse.up();
        await expect(page.getByTestId('xlog-summary-count')).toBeVisible();
        await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.5, { steps: 30 });
      });
      const endFrames = analyzeFrames(end.events).frames;
      const afterSelection: Distribution | null =
        endFrames.length === 0 ? null : distribution(endFrames.map((f) => f.frameMs));
      const selection = await lastSelection(page);
      if (selection !== null) {
        notes.push(
          `run ${String(run)}: selection of ${String(selection.points)} points, query ${selection.queryMs === null ? '-' : selection.queryMs.toFixed(2)} ms, compute ${selection.computeMs.toFixed(1)} ms, React commit ${selection.commitMs.toFixed(1)} ms (not a canvas frame, not judged)`,
        );
      }
      const pointLayerFrames = traced.filter((s) => s.pointsDrawn).length;
      notes.push(
        `run ${String(run)}: ${String(traced.length)} frames, point layer redrawn in ${String(pointLayerFrames)}`,
      );
      await page.keyboard.press('Escape');
      const measured = runResult({ run, quiet, loadDuring, capture, untraced: null });
      if (measured.frames.length < 20) {
        problems.push(
          `run ${String(run)}: ${String(measured.frames.length)} overlay frames in the trace, expected at least 20`,
        );
      }
      problems.push(...measured.problems);
      runs.push({ ...measured.result, afterSelection });
      frames.push(...measured.frames);
    }
    const result = await finish(
      {
        title,
        kind: 'interaction',
        points: INTERACTION_POINTS,
        config,
        canvas,
        mode,
        judged: true,
        runs,
        frames,
        notes,
        problems,
      },
      testInfo,
    );
    expectWithinBudget(result);
  });
}

function selectionScenario(config: Config): void {
  const title = `selection ${pointsLabel(SELECTION_POINTS)} full-plot drag render=${config.render}${label(config)} (not judged)`;
  test(title, async ({ page, browser }, testInfo) => {
    test.setTimeout(selectionTimeout());
    annotate(testInfo);
    await openPoc(page, query(config, { n: SELECTION_POINTS }));
    await recordEnvironmentOnce(page, browser, testInfo);
    const box = await pointsCanvas(page).boundingBox();
    if (box === null) {
      throw new Error('The point canvas has no bounding box');
    }
    const summaryCount = page.getByTestId('xlog-summary-count');
    const summaryEmpty = page.getByTestId('xlog-summary-empty');
    // Press on the plot's top-left pixel, release beyond the bottom-right corner: every point.
    const selectAll = async (): Promise<SelectionSample> => {
      await page.mouse.move(box.x, box.y);
      await page.mouse.down();
      await page.mouse.move(box.x + box.width + 8, box.y + box.height + 8, { steps: 4 });
      await page.mouse.up();
      await expect(summaryCount).toBeVisible();
      const timing = await lastSelection(page);
      await page.keyboard.press('Escape');
      await expect(summaryEmpty).toBeVisible();
      if (timing === null) {
        throw new Error('The page reported no selection timing');
      }
      return timing;
    };
    await selectAll(); // warm-up
    const runs: SelectionRun[] = [];
    const notes: string[] = [...(await gpuProblems(page))];
    for (let run = 1; run <= settings.runs; run += 1) {
      const quiet = await quietMachine();
      const before = cpuSnapshot();
      const taken: SelectionSample[] = [];
      for (let i = 0; i < SELECTIONS_PER_RUN; i += 1) {
        taken.push(await selectAll());
      }
      const wrong = taken.filter((s) => s.points !== SELECTION_POINTS).length;
      if (wrong > 0) {
        notes.push(
          `run ${String(run)}: ${String(wrong)} selections did not hold all ${String(SELECTION_POINTS)} points`,
        );
      }
      runs.push({
        run,
        loadBefore: quiet.load,
        quiet: quiet.quiet,
        waitedMs: quiet.waitedMs,
        loadDuring: loadBetween(before, cpuSnapshot()),
        samples: taken,
      });
    }
    const result: SelectionResult = {
      title,
      points: SELECTION_POINTS,
      dpr: config.dpr,
      render: config.render,
      runs,
      pooled: poolSelections(runs),
      budgetMs: SELECTION_BUDGET_MS,
      notes,
    };
    appendRecord(resultsFile(), { type: 'selection', result });
    await testInfo.attach('result.json', {
      body: JSON.stringify(result, null, 2),
      contentType: 'application/json',
    });
    expect(result.pooled.commit.count, 'selections measured').toBe(
      settings.runs * SELECTIONS_PER_RUN,
    );
  });
}

for (const dpr of settings.dprs) {
  test.describe(`dpr=${String(dpr)}`, () => {
    test.use({ deviceScaleFactor: dpr });
    for (const render of settings.renders) {
      const config: Config = { dpr, render, color: settings.color, y: settings.y };
      if (enabled('static')) {
        for (const n of settings.points) {
          staticScenario(
            n,
            config,
            `T-NFR-14 static ${pointsLabel(n)} render=${render}${label(config)}`,
          );
        }
      }
      if (enabled('static-worst')) {
        const worst: Config = { ...config, ...WORST_CONFIG };
        staticScenario(
          WORST_POINTS,
          worst,
          `T-NFR-14 static ${pointsLabel(WORST_POINTS)} worst render=${render}${label(worst)}`,
        );
      }
      if (enabled('realtime')) {
        realtimeScenarios(config);
      }
      if (enabled('interaction')) {
        interactionScenario(config);
      }
      if (enabled('selection')) {
        selectionScenario(config);
      }
    }
  });
}
