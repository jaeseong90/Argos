// SPDX-License-Identifier: Apache-2.0
//
// T-NFR-14: XLog canvas frame time, measured with Chrome tracing, on one of two targets
// (XLOG_PERF_TARGET, perf/lib/settings.ts):
//   poc    /xlog-poc with synthetic points (WO-04), served from the perf build (`vite build
//          --mode perf`; the production bundle has no PoC, ADR-0010 C-203)
//   route  the XLog screen /apps/<app>/xlog on the WO-30 E2E stack (real server, ClickHouse, seed
//          manifest window of 100,000 points or more, perf/lib/routePage.ts; WO-33)
//
// Scenarios (docs/15 §14 "XLog 렌더 16ms/프레임, 10만 점", N-14):
//   static        poc: 10k / 50k / 100k points; route: the window's points (≥ 100k, sampled to
//                 100k by the server). Consecutive full redraws of the point layer
//                 (window.__xlogPerf.benchmark: the worst case of resize, axis or range changes).
//                 The route also records the cost of three loads of the window (response size,
//                 JSON, conversion, first frame; docs/14 §4.5 N-07 reference 2 s)
//   static-worst  the same with the slowest drawing configuration measured: y = log and colour =
//                 service (12 palette colours + grey, 13 passes)
//   realtime      100k points + 500 points/s, 100 ms batches, 5-minute window (docs/15 §7.5); on
//                 the route the live screen's `xlog` topic is played by the docs/14 §5 stub
//                 (perf/lib/routeLive.ts, titles "ws=stub"); the real WebSocket server is WO-22
//   interaction   hover and drag: overlay-only frames over an unchanged point layer; a second trace
//                 around the end of the drag (pointer up, summary and list) is reported, not judged
//   selection     drag selections (poc: the full plot; route: three judged variants, a different
//                 rectangle each time so that no server answer comes from a cache: "full", almost
//                 the full plot released below it, "partial", pressed and released inside the
//                 plot around a few hundred to a few thousand points, docs/poc/xlog-ui.md 8 item
//                 5, both dragged in 4 steps, and "partial-path", the same rectangle reached along
//                 the interaction scenario's pointer path: hover across the plot, 20 steps to the
//                 press, 120 + 10 steps of drag; titles "real-path", review fe-perf-2 / PMO
//                 pmo-1). On the route each is judged per run (ADR-0003 C-36): the selection query
//                 (hit index, docs/15 §7.3) and the screen's own selection computation (selectMs)
//                 p95 <= 5 ms, and the screen reflect (pointer up to the frame with the summary
//                 count and the first list row on screen, ADR-0010 C-204, perf/lib/reflect.ts)
//                 p95 <= 100 ms; on the poc the same values are reported
//
// Every scenario runs once per devicePixelRatio of XLOG_PERF_DPR (default 1 and 2, ADR-0003 C-34),
// grouped by test.describe + test.use({ deviceScaleFactor }); titles carry "dpr=<n>".
//
// Per frame, the busiest rendering-pipeline thread (renderer main, compositor, GPU main, display
// compositor) is taken from the trace (perf/lib/frames.ts). Each scenario: warm-up, then at least
// 3 measured runs, each preceded by a machine CPU load check that waits for a quiet machine.
// Judged (ADR-0003 C-35): p95 and mean of the per-frame times <= 16 ms in every run
// (perf/lib/stats.ts checkRunBudgets); p50/p95/max of every run and of all runs together, and the
// CPU load of every run, go to perf/results/ (perf/lib/results.ts).
//
// Run: `corepack pnpm perf` (perf build, vite preview), `XLOG_PERF_TARGET=route corepack pnpm perf`
// (E2E stack; build the jars first, see e2e/README.md). Settings: perf/lib/settings.ts.
// Diagnostics: XLOG_PERF_TRACE_DIR=<dir> also writes each measured selection's renderer trace
// events to <dir> (perf/lib/traceDump.ts), to see where the reflect time goes; off by default.
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
  armReflectProbe,
  judgedSelectionReflect,
  POC_SELECTORS,
  REFLECT_COMMIT,
  REFLECT_PAINTED,
  REFLECT_SERVER_COMMIT,
  REFLECT_START,
  reflectFromTrace,
  reflectGpuFromTrace,
  REFLECT_TRACE_CATEGORIES,
  ROUTE_SELECTORS,
  type ReflectProbe,
  type ReflectSelectors,
} from './lib/reflect';
import {
  appendRecord,
  poolSelections,
  readRecords,
  REFLECT_BUDGET_MS,
  SELECTION_BUDGET_MS,
  selectionReasons,
  type CanvasSizes,
  type RunResult,
  type ScenarioKind,
  type ScenarioResult,
  type SelectionResult,
  type SelectionRun,
  type SelectionSample,
  type UntracedControl,
} from './lib/results';
import { backfill, LIVE_SERVICE, openRouteLive, stream } from './lib/routeLive';
import {
  openRoute,
  ROUTE_MIN_POINTS,
  ROUTE_UNSAMPLED_MIN_POINTS,
  routeTarget,
} from './lib/routePage';
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
import { checkRunBudgets, distribution, type Distribution } from './lib/stats';
import { diagnosticTraceCategories, dumpSelectionTrace } from './lib/traceDump';
import { traceDuring, type TraceCapture } from './lib/tracing';
import {
  benchmark,
  canvasInfo,
  environmentInfo,
  gpuRenderer,
  lastLoad,
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
const ROUTE = settings.target === 'route';
const WORST_POINTS = 100_000;
const REALTIME_POINTS = 100_000;
const INTERACTION_POINTS = 100_000;
const SELECTION_POINTS = 100_000;
const SELECTIONS_PER_RUN = 5;
const REALTIME_WARMUP_MS = 3000;
/** Route: page loads measured before the static frames (N-07 reference). */
const ROUTE_LOADS = 3;
/** Longest wait for one screen reflect (the list comes from the server search on the route). */
const REFLECT_TIMEOUT_MS = 30_000;
/**
 * Route: the list's search is a heavy request, 10 per user and minute (SEC-56, docs/14 §2.7):
 * selections start at least this far apart, so the server never answers 429. The wait is outside
 * the measured interval.
 */
const ROUTE_SEARCH_SPACING_MS = 6_500;
let lastRouteSearchAt = 0;

/** Drawing configuration and devicePixelRatio of one scenario. */
interface Config {
  readonly dpr: number;
  readonly render: RenderSetting;
  readonly color: ColorSetting;
  readonly y: YSetting;
}

/** The page under measurement and the number of points it holds. */
interface Target {
  readonly page: Page;
  readonly points: number;
  /** What a selection must show to be reflected (perf/lib/reflect.ts). */
  readonly selectors: ReflectSelectors;
  readonly close: () => Promise<void>;
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

/**
 * Opens the target: the poc page in the fixture page with `n` synthetic points, or the XLog screen
 * of the route in its own context (the drawing configuration in its URL). The route draws in its
 * default mode (`pixels`, C-34): the render setting applies to the poc page only.
 */
async function openTarget(
  page: Page,
  browser: Browser,
  config: Config,
  poc: {
    readonly n: number;
    readonly live?: boolean;
    readonly rate?: number;
    readonly incremental?: boolean;
  },
): Promise<Target> {
  if (!ROUTE) {
    await openPoc(page, query(config, poc));
    return {
      page,
      points: poc.n,
      selectors: POC_SELECTORS,
      close: () => Promise.resolve(),
    };
  }
  const target = await routeTarget(settings.routeWindow, settings.routeUnsampled);
  const opened = await openRoute(browser, target, {
    dpr: config.dpr,
    color: config.color,
    y: config.y,
  });
  return {
    page: opened.page,
    points: await pointCount(opened.page),
    selectors: ROUTE_SELECTORS,
    close: () => opened.context.close(),
  };
}

/**
 * Route: how many points the screen draws for the window and what they are (the server's sample of
 * a larger window, or every point), for the titles that say "route" (review fe-perf-5).
 */
async function routeDrawnNotes(target: Target): Promise<string[]> {
  if (!ROUTE) {
    return [];
  }
  const load = await lastLoad(target.page);
  const total = load?.total ?? null;
  const what =
    load?.sampled === true ? "the server's deterministic sample (A-5)" : 'every point drawn';
  return [`drawn ${String(target.points)} points of the window's ${String(total ?? '-')}: ${what}`];
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
  // ADR-0003 C-35: the p95 and mean budget holds in every run; the pooled values are reported.
  const verdict = checkRunBudgets(input.runs, pooledFrames.count);
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
  const summary = `${result.title}: ${String(frames.count)} frames, p50 ${frames.p50.toFixed(1)} / p95 ${frames.p95.toFixed(1)} / max ${frames.max.toFixed(1)} / mean ${frames.mean.toFixed(1)} ms (pooled; verdict per run)`;
  expect(result.reasons, summary).toEqual([]);
}

function staticTimeout(): number {
  // Quiet wait + traced and untraced runs at up to 250 ms per frame, per run (+ route loads).
  return settings.runs * (settings.maxWaitMs + settings.frames * 2 * 250 + 30_000) + 240_000;
}

function liveTimeout(): number {
  return settings.runs * (settings.maxWaitMs + settings.liveSeconds * 2 * 1000 + 30_000) + 120_000;
}

function selectionTimeout(): number {
  return (
    settings.runs *
      (settings.maxWaitMs +
        SELECTIONS_PER_RUN * (REFLECT_TIMEOUT_MS + ROUTE_SEARCH_SPACING_MS + 10_000)) +
    180_000
  );
}

const enabled = (scenario: ScenarioSetting): boolean => settings.scenarios.includes(scenario);

/** "load 100000 of 143210 points, 1,234 KB gzip (5,678 KB JSON), request → body 380 ms, ..." */
async function routeLoadNotes(page: Page): Promise<string[]> {
  const notes: string[] = [];
  for (let i = 1; i <= ROUTE_LOADS; i += 1) {
    await page.reload();
    await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-ready', 'true', {
      timeout: 120_000,
    });
    const load = await lastLoad(page);
    if (load === null || load.firstFrameAt === null) {
      notes.push(`load ${String(i)}: no load timing (window.__xlogPerf.lastLoad())`);
      continue;
    }
    const kb = (bytes: number | null): string =>
      bytes === null ? '-' : `${(bytes / 1024).toFixed(0)} KB`;
    const body = load.responseEndAt === null ? null : load.responseEndAt - load.requestedAt;
    const json = load.responseEndAt === null ? null : load.receivedAt - load.responseEndAt;
    notes.push(
      `load ${String(i)} (N-07 reference 2 s, docs/13 §15 Q3): ${String(load.points)} points (shown ${String(load.shown ?? '-')} of ${String(load.total ?? '-')}${load.sampled ? ', sampled' : ''}), body ${kb(load.encodedBytes)} on the wire (gzip), ${kb(load.decodedBytes)} JSON; request to end of body ${body === null ? '-' : body.toFixed(0)} ms, JSON parse ${json === null ? '-' : json.toFixed(0)} ms, columns into the buffer ${load.convertMs.toFixed(1)} ms (tx_id parse ${load.txIdParseMs.toFixed(1)} ms), request to first frame ${(load.firstFrameAt - load.requestedAt).toFixed(0)} ms`,
    );
  }
  return notes;
}

function staticScenario(n: number, config: Config, title: string): void {
  test(title, async ({ page: fixturePage, browser }, testInfo) => {
    test.setTimeout(staticTimeout());
    annotate(testInfo);
    const target = await openTarget(fixturePage, browser, config, { n });
    const page = target.page;
    try {
      await recordEnvironmentOnce(page, browser, testInfo);
      const points = target.points;
      expect(await visibleCount(page), 'every point is drawn').toBe(points);
      const notes: string[] = [];
      const problems: string[] = [];
      if (ROUTE && settings.routeUnsampled) {
        // The longest start of the window with at most 100,000 points (review fe-perf-5): the
        // server sends every point (no sampling), about 100,000 of them.
        const load = await lastLoad(page);
        const total = load?.total ?? null;
        if (
          total === null ||
          total > ROUTE_MIN_POINTS ||
          total < ROUTE_UNSAMPLED_MIN_POINTS ||
          load?.sampled !== false
        ) {
          problems.push(
            `the unsampled start of ${settings.routeWindow} holds ${String(total ?? 'an unknown number of')} points${load?.sampled === true ? ', sampled' : ''}: expected ${String(ROUTE_UNSAMPLED_MIN_POINTS)} to ${String(ROUTE_MIN_POINTS)} points, unsampled`,
          );
        }
        notes.push(
          `drawn ${String(points)} points: every point of the window's first ${String(total ?? '-')} (unsampled)`,
          ...(await routeLoadNotes(page)),
        );
      } else if (ROUTE) {
        // The window must hold 100,000 points or more (docs/15 §14 N-14); the screen draws what
        // the server sends for it, a deterministic sample of at most 100,000 (A-5, docs/14 §4.5).
        const load = await lastLoad(page);
        const total = load?.total ?? null;
        if (total === null || total < ROUTE_MIN_POINTS) {
          problems.push(
            `the window ${settings.routeWindow} holds ${String(total ?? 'an unknown number of')} points, fewer than ${String(ROUTE_MIN_POINTS)} (docs/15 §14 N-14)`,
          );
        }
        notes.push(
          `drawn ${String(points)} points: the server's deterministic sample (A-5) of the window's ${String(total ?? '-')}`,
          ...(await routeLoadNotes(page)),
        );
      }
      const { canvas, problems: dprProblems } = await canvasOf(page, config);
      problems.push(...dprProblems, ...(await gpuProblems(page)));
      if (settings.warmupFrames > 0) {
        await benchmark(page, settings.warmupFrames);
      }
      const runs: RunResult[] = [];
      const frames: FrameMetrics[] = [];
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
          points,
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
    } finally {
      await target.close();
    }
  });
}

/**
 * The measured runs of a live page (poc or route): per run, a quiet-machine check, `liveSeconds`
 * traced (the judged frames), then the same untraced (control).
 */
async function realtimeRuns(page: Page): Promise<{
  runs: RunResult[];
  frames: FrameMetrics[];
  notes: string[];
  problems: string[];
  mode: string;
}> {
  const runs: RunResult[] = [];
  const frames: FrameMetrics[] = [];
  const notes: string[] = [];
  const problems: string[] = [];
  let mode = 'unknown';
  for (let run = 1; run <= settings.runs; run += 1) {
    const quiet = await quietMachine();
    const pointsBefore = await pointCount(page);
    const before = cpuSnapshot();
    await resetSamples(page);
    const capture = await traceDuring(page, () => page.waitForTimeout(settings.liveSeconds * 1000));
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
  return { runs, frames, notes, problems, mode };
}

/** The tab's used JS heap in MB (CDP Performance.getMetrics JSHeapUsedSize), or null. */
async function jsHeapUsedMb(page: Page): Promise<number | null> {
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('Performance.enable');
    const { metrics } = await cdp.send('Performance.getMetrics');
    const used = metrics.find((m) => m.name === 'JSHeapUsedSize')?.value;
    return used === undefined ? null : used / (1024 * 1024);
  } finally {
    await cdp.detach().catch(() => undefined);
  }
}

/**
 * Route realtime (review fe-perf-4, ADR-0010 C-205 (3)): the production XLog screen in live mode,
 * its `xlog` topic played by the docs/14 §5 stub (perf/lib/routeLive.ts): the 5-minute window is
 * backfilled to 100,000 points, then `liveRate` points per second arrive in 100 ms batches; the
 * frames are judged with the same definition (perf/lib/frames.ts) once the buffer holds 100,000
 * points. Only the real WebSocket server (WO-22) is missing from the path.
 */
function routeRealtimeScenario(config: Config, title: string, judged: boolean): void {
  test(title, async ({ browser }, testInfo) => {
    test.setTimeout(liveTimeout() + 180_000);
    annotate(testInfo);
    const target = await routeTarget(settings.routeWindow);
    const opened = await openRouteLive(browser, target, { dpr: config.dpr });
    const page = opened.page;
    let live: { stop: () => void; pushed: () => number } | null = null;
    try {
      await recordEnvironmentOnce(page, browser, testInfo);
      const { canvas, problems: dprProblems } = await canvasOf(page, config);
      const problems: string[] = [...dprProblems, ...(await gpuProblems(page))];
      const loaded = await pointCount(page);
      const nextTx = backfill(opened.feed, REALTIME_POINTS, target.instanceId, 1);
      live = stream(opened.feed, settings.liveRate, target.instanceId, nextTx);
      await expect
        .poll(() => pointCount(page), {
          timeout: 60_000,
          message: `the live buffer reaches ${String(REALTIME_POINTS)} points`,
        })
        .toBeGreaterThanOrEqual(REALTIME_POINTS);
      await page.waitForTimeout(REALTIME_WARMUP_MS);
      // JS heap of the tab while live (reference only, docs/15 §14 tab heap <= 300 MB, review
      // fe-perf-10): after the backfill and after the runs (CDP Performance.getMetrics).
      const heapBefore = await jsHeapUsedMb(page);
      const measured = await realtimeRuns(page);
      const heapAfter = await jsHeapUsedMb(page);
      problems.push(
        ...measured.problems,
        ...opened.feed.violations().map((violation) => `ws stub: ${violation}`),
      );
      const notes = [
        `${opened.path}: REST load of the live window ${String(loaded)} points (service ${LIVE_SERVICE} has none), then ${String(REALTIME_POINTS)} points backfilled over the 5-minute window and ${String(settings.liveRate)} points/s in 100 ms batches through the docs/14 §5 stub (${String(live.pushed())} streamed); the real WebSocket server is WO-22`,
        `JS heap used (reference, not judged): ${heapBefore === null ? '-' : heapBefore.toFixed(1)} MB after the backfill, ${heapAfter === null ? '-' : heapAfter.toFixed(1)} MB after the runs`,
        ...measured.notes,
      ];
      const result = await finish(
        {
          title,
          kind: 'realtime',
          points: REALTIME_POINTS,
          config,
          canvas,
          mode: measured.mode,
          judged,
          runs: measured.runs,
          frames: measured.frames,
          notes,
          problems,
        },
        testInfo,
      );
      if (judged) {
        expectWithinBudget(result);
      }
    } finally {
      live?.stop();
      await opened.context.close();
    }
  });
}

function realtimeScenarios(config: Config): void {
  const liveScenarios: { readonly incremental: boolean; readonly judged: boolean }[] = [
    { incremental: true, judged: true },
    ...(settings.compare ? [{ incremental: false, judged: false }] : []),
  ];
  for (const { incremental, judged } of liveScenarios) {
    const title = `${judged ? 'T-NFR-14' : 'comparison:'} realtime ${pointsLabel(REALTIME_POINTS)} + ${String(settings.liveRate)}/s render=${config.render}${incremental ? '' : ' incremental=0'}${label(config)}`;
    if (ROUTE) {
      // The screen always draws incrementally while live; the comparison is the poc's.
      if (incremental) {
        routeRealtimeScenario(config, `${title}${TARGET_LABEL} ws=stub`, judged);
      }
      continue;
    }
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
      const problems: string[] = [...dprProblems, ...(await gpuProblems(page))];
      const measured = await realtimeRuns(page);
      problems.push(...measured.problems);
      const result = await finish(
        {
          title,
          kind: 'realtime',
          points: REALTIME_POINTS,
          config,
          canvas,
          mode: measured.mode,
          judged,
          runs: measured.runs,
          frames: measured.frames,
          notes: measured.notes,
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

/** Clears the selection (Escape, the global shortcut) and waits for the summary to be empty. */
async function clearSelection(page: Page): Promise<void> {
  await page.keyboard.press('Escape');
  await expect(page.getByTestId('xlog-summary-empty')).toBeVisible();
}

function interactionScenario(config: Config, title: string): void {
  test(title, async ({ page: fixturePage, browser }, testInfo) => {
    test.setTimeout(liveTimeout());
    annotate(testInfo);
    const target = await openTarget(fixturePage, browser, config, { n: INTERACTION_POINTS });
    const page = target.page;
    try {
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
      const notes: string[] = [...(await routeDrawnNotes(target))];
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
        // Not judged here: the end of the drag (pointer up, selection, summary and list, the next
        // hover frames); the selection scenario judges the screen reflect.
        const end = await traceDuring(page, async () => {
          await page.mouse.move(box.x + box.width * 0.72, box.y + box.height * 0.92, { steps: 10 });
          await page.mouse.up();
          await expect(page.getByTestId('xlog-summary-count')).toBeVisible();
          await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.5, { steps: 30 });
        });
        dumpSelectionTrace(
          process.env.XLOG_PERF_TRACE_DIR,
          `${title} end run${String(run)}`,
          end.events,
        );
        const endFrames = analyzeFrames(end.events).frames;
        const afterSelection: Distribution | null =
          endFrames.length === 0 ? null : distribution(endFrames.map((f) => f.frameMs));
        const selection = await lastSelection(page);
        if (selection !== null) {
          notes.push(
            `run ${String(run)}: selection of ${String(selection.points)} points, query ${selection.queryMs === null ? '-' : selection.queryMs.toFixed(2)} ms, select ${typeof selection.selectMs === 'number' ? selection.selectMs.toFixed(2) : '-'} ms, compute ${selection.computeMs.toFixed(1)} ms, React commit ${selection.commitMs.toFixed(1)} ms (not a canvas frame, not judged here; the selection scenario's "partial-path" variant judges this drag along the same pointer path)`,
          );
        }
        const pointLayerFrames = traced.filter((s) => s.pointsDrawn).length;
        notes.push(
          `run ${String(run)}: ${String(traced.length)} frames, point layer redrawn in ${String(pointLayerFrames)}`,
        );
        await clearSelection(page);
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
          points: target.points,
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
    } finally {
      await target.close();
    }
  });
}

/**
 * One drag selection with the screen reflect probe and a trace around it (perf/lib/reflect.ts):
 * press at (x0, y0), release at (x1, y1) (page px), wait for the probe, clear the selection.
 * With `path`, the pointer gets to the release point along that path instead (it ends pressed at
 * `to`), and the probe is armed after it, just before the release.
 */
async function measuredSelection(
  target: Target,
  from: { x: number; y: number },
  to: { x: number; y: number },
  traceName = 'selection',
  path?: () => Promise<void>,
): Promise<SelectionSample> {
  const page = target.page;
  if (ROUTE) {
    const wait = lastRouteSearchAt + ROUTE_SEARCH_SPACING_MS - Date.now();
    if (wait > 0) {
      await page.waitForTimeout(wait);
    }
    lastRouteSearchAt = Date.now();
  }
  const arm = (): Promise<void> =>
    page.evaluate(armReflectProbe, {
      selectors: target.selectors,
      timeoutMs: REFLECT_TIMEOUT_MS,
      marks: [REFLECT_START, REFLECT_COMMIT, REFLECT_PAINTED] as const,
      serverCommitMark: REFLECT_SERVER_COMMIT,
    });
  if (path === undefined) {
    await arm();
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    await page.mouse.move(to.x, to.y, { steps: 4 });
  } else {
    await path();
    await arm();
  }
  // With the `viz` category: the display compositor's draw and swap end the GPU reflect.
  const capture = await traceDuring(page, async () => {
    await page.mouse.up();
    return page.evaluate(async () => {
      const probe = window.__xlogReflect;
      if (probe === undefined) {
        throw new Error('the reflect probe is not armed');
      }
      return probe;
    });
  }, [...REFLECT_TRACE_CATEGORIES, ...diagnosticTraceCategories(process.env.XLOG_PERF_TRACE_DIR)]);
  const probe: ReflectProbe = capture.result;
  // Diagnostics only (XLOG_PERF_TRACE_DIR, perf/lib/traceDump.ts): the renderer's trace events.
  dumpSelectionTrace(process.env.XLOG_PERF_TRACE_DIR, traceName, capture.events);
  const timing = await lastSelection(page);
  if (timing === null) {
    throw new Error('The page reported no selection timing');
  }
  await clearSelection(page);
  const reflectPageMs = probe.paintedAt - probe.startAt;
  const reflectTraceMs = reflectFromTrace(capture.events);
  // Including raster, GPU and viz drawing (review fe-perf-6): the judged value is the largest.
  const reflectGpuMs = reflectGpuFromTrace(capture.events);
  // A preview row counts only when its first row is the server's first row (C-204, fe-perf-3).
  const judged = judgedSelectionReflect(
    probe,
    reflectTraceMs,
    reflectFromTrace(capture.events, REFLECT_SERVER_COMMIT),
    reflectGpuMs,
    reflectGpuFromTrace(capture.events, REFLECT_SERVER_COMMIT),
  );
  return {
    ...timing,
    reflectPageMs,
    reflectTraceMs,
    reflectGpuMs,
    reflectMs: judged.reflectMs,
    serverReflectMs: judged.serverReflectMs,
    previewMatchesServer: judged.previewMatchesServer,
    summaryMs: probe.summaryPaintedAt - probe.startAt,
    serverListMs: probe.serverListAt === null ? null : probe.serverListAt - probe.startAt,
  };
}

/**
 * Selection variants: "full" (poc: every point; route: almost every point, released below the
 * plot), "partial" (route only: pressed and released inside the plot, the interaction
 * scenario's (0.2, 0.2) -> (0.72, 0.92) rectangle, a few hundred to a few thousand points) and
 * "partial-path" (route only: the same rectangle reached along the interaction scenario's pointer
 * path, realDragPath; review fe-perf-2, PMO pmo-1). "full" and "partial" drag in 4 steps.
 */
type SelectionVariant = 'full' | 'partial' | 'partial-path';

/** Press and release points of the index-th selection (each one a new rectangle, page px). */
function selectionDrag(
  variant: SelectionVariant,
  box: { x: number; y: number; width: number; height: number },
  index: number,
): [{ x: number; y: number }, { x: number; y: number }] {
  if (variant === 'partial' || variant === 'partial-path') {
    // 1 px narrower each time, so that no search or summary answer comes from a cache.
    return [
      { x: box.x + box.width * 0.2, y: box.y + box.height * 0.2 },
      { x: box.x + box.width * 0.72 - index, y: box.y + box.height * 0.92 },
    ];
  }
  // poc: press on the plot's top-left pixel, release beyond the bottom-right corner (every
  // point). route: release i px inside the right edge, so that every selection is a new server
  // question (no cached search or summary answer) holding almost every point.
  return [
    { x: box.x, y: box.y },
    ROUTE
      ? { x: box.x + box.width - 1 - index, y: box.y + box.height + 8 }
      : { x: box.x + box.width + 8, y: box.y + box.height + 8 },
  ];
}

/**
 * The interaction scenario's pointer path to the index-th "partial-path" selection (page px):
 * hover across the plot (top left to bottom right, 120 steps), 20 steps to the press at
 * (0.2, 0.2), press, 120 steps to (0.7, 0.9) and 10 steps to the release point of
 * selectionDrag('partial', ...), where the pointer stays pressed.
 */
function realDragPath(
  page: Page,
  box: { x: number; y: number; width: number; height: number },
  index: number,
): () => Promise<void> {
  return async () => {
    await page.mouse.move(box.x + 4, box.y + 4);
    await page.mouse.move(box.x + box.width - 4, box.y + box.height - 4, { steps: 120 });
    await page.mouse.move(box.x + box.width * 0.2, box.y + box.height * 0.2, { steps: 20 });
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.7, box.y + box.height * 0.9, { steps: 120 });
    await page.mouse.move(box.x + box.width * 0.72 - index, box.y + box.height * 0.92, {
      steps: 10,
    });
  };
}

function selectionScenario(config: Config, title: string, variant: SelectionVariant): void {
  test(title, async ({ page: fixturePage, browser }, testInfo) => {
    test.setTimeout(selectionTimeout());
    annotate(testInfo);
    const target = await openTarget(fixturePage, browser, config, { n: SELECTION_POINTS });
    const page = target.page;
    try {
      await recordEnvironmentOnce(page, browser, testInfo);
      const box = await pointsCanvas(page).boundingBox();
      if (box === null) {
        throw new Error('The point canvas has no bounding box');
      }
      const drag = (index: number): [{ x: number; y: number }, { x: number; y: number }] =>
        selectionDrag(variant, box, index);
      const path = (index: number): (() => Promise<void>) | undefined =>
        variant === 'partial-path' ? realDragPath(page, box, index) : undefined;
      const warmIndex = SELECTIONS_PER_RUN * settings.runs + 1;
      const [warmFrom, warmTo] = drag(warmIndex);
      await measuredSelection(target, warmFrom, warmTo, 'selection', path(warmIndex)); // warm-up
      const runs: SelectionRun[] = [];
      const notes: string[] = [...(await gpuProblems(page)), ...(await routeDrawnNotes(target))];
      let index = 0;
      for (let run = 1; run <= settings.runs; run += 1) {
        const quiet = await quietMachine();
        const before = cpuSnapshot();
        const taken: SelectionSample[] = [];
        for (let i = 0; i < SELECTIONS_PER_RUN; i += 1) {
          const [from, to] = drag(index);
          const route = path(index);
          index += 1;
          taken.push(
            await measuredSelection(
              target,
              from,
              to,
              `${title} run${String(run)}-${String(i)}`,
              route,
            ),
          );
        }
        if (!ROUTE) {
          const wrong = taken.filter((s) => s.points !== SELECTION_POINTS).length;
          if (wrong > 0) {
            notes.push(
              `run ${String(run)}: ${String(wrong)} selections did not hold all ${String(SELECTION_POINTS)} points`,
            );
          }
        }
        const server = taken.flatMap((s) =>
          typeof s.serverListMs === 'number' ? [s.serverListMs] : [],
        );
        if (server.length > 0) {
          const d = distribution(server);
          notes.push(
            `run ${String(run)}: server rows of the list in the DOM after the drag (reported; the reflect counts a preview row from the chart's points only when its first row is the server's, C-204): p50 / p95 / max ${d.p50.toFixed(1)} / ${d.p95.toFixed(1)} / ${d.max.toFixed(1)} ms`,
          );
        }
        const serverPainted = taken.flatMap((s) =>
          typeof s.serverReflectMs === 'number' ? [s.serverReflectMs] : [],
        );
        if (serverPainted.length > 0) {
          const d = distribution(serverPainted);
          notes.push(
            `run ${String(run)}: server-first-row reflect (pointer up to the frame after the server rows, page probe and trace; reported next to the reflect, C-204 PM 결정 대기): p50 / p95 / max ${d.p50.toFixed(1)} / ${d.p95.toFixed(1)} / ${d.max.toFixed(1)} ms`,
          );
        }
        const previewed = taken.filter((s) => typeof s.previewMatchesServer === 'boolean');
        if (previewed.length > 0) {
          const differ = previewed.filter((s) => s.previewMatchesServer === false).length;
          notes.push(
            `run ${String(run)}: ${String(previewed.length)} selections reflected through preview rows; the first preview row differed from the server first row in ${String(differ)} (counted at the server rows)`,
          );
        }
        const rebuilt = taken.filter((s) => s.indexRebuilt === true);
        if (taken.some((s) => typeof s.indexRebuilt === 'boolean')) {
          // Diagnostics (WO-33 review r1-new-3): queryMs includes a hit index rebuild or not.
          notes.push(
            `run ${String(run)}: hit index rebuilt inside the query in ${String(rebuilt.length)} of ${String(taken.length)} selections${rebuilt.length === 0 ? '' : ` (rebuild ${rebuilt.map((s) => (s.indexBuildMs ?? 0).toFixed(1)).join(' / ')} ms)`}`,
          );
        }
        const traced = taken.filter((s) => s.reflectTraceMs === null).length;
        if (traced > 0) {
          notes.push(
            `run ${String(run)}: ${String(traced)} selections without the trace's Commit/Paint after the commit mark (the page probe's value is used)`,
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
      if (variant === 'partial' || variant === 'partial-path') {
        const selected = runs.flatMap((run) => run.samples.map((s) => s.points));
        notes.push(
          `partial drags inside the plot${variant === 'partial-path' ? ' along the interaction pointer path' : ''}: ${String(Math.min(...selected))} to ${String(Math.max(...selected))} points selected`,
        );
        const commits = distribution(runs.flatMap((run) => run.samples.map((s) => s.commitMs)));
        notes.push(
          `React commit after the release (reported, the page's selection timing): p50 / p95 / max ${commits.p50.toFixed(1)} / ${commits.p95.toFixed(1)} / ${commits.max.toFixed(1)} ms`,
        );
      }
      // C-36 is judged on the XLog screen (route), the canvas query and the screen's selection
      // (selectMs) against 5 ms and the reflect against 100 ms; the WO-04 PoC page (client list
      // without virtualisation, no selectMs) is measured the same way for comparison, not judged.
      const judged = ROUTE;
      const reasons = selectionReasons(
        runs,
        SELECTION_BUDGET_MS,
        REFLECT_BUDGET_MS,
        ROUTE ? SELECTION_BUDGET_MS : null,
      );
      const result: SelectionResult = {
        title,
        points: target.points,
        dpr: config.dpr,
        render: config.render,
        runs,
        pooled: poolSelections(runs),
        budgetMs: SELECTION_BUDGET_MS,
        reflectBudgetMs: REFLECT_BUDGET_MS,
        selectJudged: ROUTE,
        judged,
        pass: judged ? reasons.length === 0 : null,
        reasons: judged ? reasons : [],
        notes: judged ? notes : [...notes, ...reasons],
      };
      appendRecord(resultsFile(), { type: 'selection', result });
      await testInfo.attach('result.json', {
        body: JSON.stringify(result, null, 2),
        contentType: 'application/json',
      });
      expect(result.pooled.commit.count, 'selections measured').toBe(
        settings.runs * SELECTIONS_PER_RUN,
      );
      const pooled = result.pooled;
      expect(
        judged ? reasons : [],
        `${title}: query p95 ${pooled.query.p95.toFixed(2)} ms, select p95 ${(pooled.select?.p95 ?? 0).toFixed(2)} ms, screen reflect p95 ${(pooled.reflect?.p95 ?? 0).toFixed(1)} ms (pooled; verdict per run)`,
      ).toEqual([]);
    } finally {
      await target.close();
    }
  });
}

/**
 * " target=route window=<name>" (" unsampled" for its unsampled start) for the route, nothing for the
 * poc (the WO-04 titles stay as they were).
 */
const TARGET_LABEL = ROUTE
  ? ` target=route window=${settings.routeWindow}${settings.routeUnsampled ? ' unsampled' : ''}`
  : '';
/**
 * Points in a title: the poc's synthetic count, or "route" (the route draws what the server sends for
 * the window, e.g. its 100,000-point sample of a larger window; the result's Points column and notes
 * give the drawn count, review fe-perf-5).
 */
const POINTS_LABEL = (n: number): string => (ROUTE ? 'route' : pointsLabel(n));

for (const dpr of settings.dprs) {
  test.describe(`dpr=${String(dpr)}`, () => {
    test.use({ deviceScaleFactor: dpr });
    for (const render of settings.renders) {
      const config: Config = { dpr, render, color: settings.color, y: settings.y };
      if (enabled('static')) {
        if (ROUTE) {
          staticScenario(
            ROUTE_MIN_POINTS,
            config,
            `T-NFR-14 static route render=${render}${label(config)}${TARGET_LABEL}`,
          );
        } else {
          for (const n of settings.points) {
            staticScenario(
              n,
              config,
              `T-NFR-14 static ${pointsLabel(n)} render=${render}${label(config)}`,
            );
          }
        }
      }
      if (enabled('static-worst')) {
        const worst: Config = { ...config, ...WORST_CONFIG };
        staticScenario(
          WORST_POINTS,
          worst,
          `T-NFR-14 static ${ROUTE ? 'route' : pointsLabel(WORST_POINTS)} worst render=${render}${label(worst)}${TARGET_LABEL}`,
        );
      }
      if (enabled('realtime')) {
        realtimeScenarios(config);
      }
      if (enabled('interaction')) {
        interactionScenario(
          config,
          `T-NFR-14 interaction ${POINTS_LABEL(INTERACTION_POINTS)} hover+drag render=${render}${label(config)}${TARGET_LABEL}`,
        );
      }
      if (enabled('selection')) {
        selectionScenario(
          config,
          ROUTE
            ? `T-NFR-14 selection ${POINTS_LABEL(SELECTION_POINTS)} drag render=${render}${label(config)}${TARGET_LABEL}`
            : `selection ${pointsLabel(SELECTION_POINTS)} full-plot drag render=${render}${label(config)} (not judged)`,
          'full',
        );
        if (ROUTE) {
          selectionScenario(
            config,
            `T-NFR-14 selection ${POINTS_LABEL(SELECTION_POINTS)} partial in-plot drag render=${render}${label(config)}${TARGET_LABEL}`,
            'partial',
          );
          selectionScenario(
            config,
            `T-NFR-14 selection ${POINTS_LABEL(SELECTION_POINTS)} partial in-plot drag real-path render=${render}${label(config)}${TARGET_LABEL}`,
            'partial-path',
          );
        }
      }
    }
  });
}
