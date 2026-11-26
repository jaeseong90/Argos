// SPDX-License-Identifier: Apache-2.0
//
// Playwright E2E suite of the UI (docs/18 §13, docs/15 §15): `corepack pnpm e2e`. Chromium only
// (browser install: `corepack pnpm playwright:install`). Projects:
//   preview         the WO-04 XLog PoC and the global-hook positive controls against `vite preview`
//                   serving the perf build in build/perf-dist/ (the PoC route exists only there,
//                   ADR-0010 C-203; a production build into dist/ cannot replace it mid-run) with
//                   the preview CSP of vite.config.ts (unchanged since WO-04), plus the positive
//                   controls of the stack-mode hooks (stack-hooks.spec.ts)
//   stack-setup     starts and seeds the E2E stack once (e2e/stack/: PostgreSQL, ClickHouse and the
//                   server boot jar of this tree in Docker; generator data, users, agent traffic,
//                   seed manifest), or reuses ARGOS_E2E_STACK_URL; its teardown is stack-teardown
//   stack           every WO-30 spec against the real server (e2e/specs/*.spec.ts except the
//                   preview specs), after stack-setup
//   stack-teardown  server CSP counter (one-sided: a growth beyond the positive controls' reports
//                   fails, ADR-0010 C-188, e2e/README.md), then removes the stack (ARGOS_E2E_KEEP=1
//                   keeps it)
//   initial-load    perf/initial-load.spec.ts (T-NFR-14 initial load, reference values); defined only
//                   when selected with `--project initial-load` (corepack pnpm perf:initial-load)
//   fresh-install   e2e/specs/fresh/*.spec.ts (WO-46: the real setup wizard) against the
//                   fresh-install stack of fresh-setup (e2e/stack/fresh.ts: no administrator, a setup
//                   token, no seed), torn down by fresh-teardown; defined only when selected with
//                   `--project fresh-install` (corepack pnpm e2e:fresh), never with the stack project
// docs/18 §13 and §15: retries 0, one worker, traces of failures only, 1366x768, UTC, en-US (the i18n
// spec switches the language in the UI). CI shards with --shard=<i>/<n> (blob reports, merged).
// The XLog frame-time spec has its own configuration (playwright.perf.config.ts) and never runs here.
import { defineConfig, type ReporterDescription } from '@playwright/test';

import { projectSelection } from './e2e/fixtures/projects';
import { uiTarget } from './e2e/fixtures/uiServer';
import { PERF_DIST } from './perf/lib/pocBuild';

// Read by perf/initial-load.spec.ts: it declares its test only under this configuration (the
// frame-time configuration also collects perf/*.spec.ts).
process.env.ARGOS_E2E_CONFIG = '1';

const target = uiTarget(process.env);
// The preview project serves the perf build (`vite build --mode perf --outDir build/perf-dist`).
const webServer =
  target.webServer === undefined || Array.isArray(target.webServer)
    ? target.webServer
    : { ...target.webServer, command: `${target.webServer.command} --outDir ${PERF_DIST}` };
const selection = projectSelection(process.argv, process.env);
const preview = selection === null || selection.includes('preview');
const initialLoad = selection?.includes('initial-load') === true;
// The fresh-install projects (WO-46) start a stack of their own: defined only when selected
// (`--project fresh-install`, corepack pnpm e2e:fresh), never together with the stack project.
const freshInstall = selection?.includes('fresh-install') === true;
const ci = process.env.CI !== undefined;

/**
 * The specs of the preview project (they run against vite preview, not the stack): the two WO-04
 * specs and the positive controls of the stack-mode page hooks, which need no server.
 */
const PREVIEW_SPECS = ['xlog-poc.spec.ts', 'global-hooks.spec.ts', 'stack-hooks.spec.ts'];

/**
 * The JSON report of this run, read by the traceability report (WO-53, test/README.md section 6):
 * one file per project selection and shard, so the preview run and the stack shards do not
 * overwrite each other. It sits outside `outputDir`, which Playwright empties at the start of every
 * run. Only the runner process writes reports; workers never name a project on their command line.
 */
function jsonReportFile(argv: readonly string[]): string {
  const name =
    selection === null
      ? 'default'
      : selection.map((p) => p.replace(/[^A-Za-z0-9_-]/g, '_')).join('-');
  let shard = '';
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i] ?? '';
    const value = arg.startsWith('--shard=')
      ? arg.slice('--shard='.length)
      : arg === '--shard'
        ? (argv[i + 1] ?? '')
        : '';
    const m = /^(\d+)\/(\d+)$/.exec(value);
    if (m !== null) {
      shard = `-shard-${m[1] ?? ''}-of-${m[2] ?? ''}`;
    }
  }
  return `./test-results/e2e-json/results-${name}${shard}.json`;
}

const reporter: ReporterDescription[] = [
  ['list'],
  ['json', { outputFile: jsonReportFile(process.argv) }],
  ci
    ? ['blob', { outputDir: './blob-report' }]
    : ['html', { open: 'never', outputFolder: './playwright-report/e2e' }],
];

export default defineConfig({
  outputDir: './test-results/e2e',
  fullyParallel: false,
  workers: 1,
  // docs/18 §13, §15: no retries; traces only for failures.
  retries: 0,
  forbidOnly: ci,
  // Refuses a missing, stale or production build in build/perf-dist/ when the preview project
  // serves it (`playwright test` run directly does not build). The stack refuses a stale boot jar itself (e2e/stack/jars.ts).
  ...(preview ? { globalSetup: './e2e/fixtures/globalSetup.ts' } : {}),
  timeout: 90_000,
  expect: {
    timeout: 15_000,
    // Visual regression (docs/18 §13): 0.1 % of the pixels, no animations. A per-pixel colour
    // threshold of 0.1 (Playwright's default 0.2 let a changed placeholder text and a shorter menu
    // background pass against an outdated baseline); baselines come from one pinned image.
    toHaveScreenshot: {
      maxDiffPixelRatio: 0.001,
      threshold: 0.1,
      animations: 'disabled',
      caret: 'hide',
      scale: 'css',
    },
  },
  reporter,
  use: {
    baseURL: target.baseURL,
    browserName: 'chromium',
    // The full Chromium build in new headless mode (not the headless shell).
    channel: 'chromium',
    viewport: { width: 1366, height: 768 },
    deviceScaleFactor: 1,
    locale: 'en-US',
    timezoneId: 'UTC',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    { name: 'preview', testDir: './e2e/specs', testMatch: PREVIEW_SPECS },
    {
      name: 'stack-setup',
      testDir: './e2e/setup',
      testMatch: 'stack.setup.ts',
      teardown: 'stack-teardown',
    },
    { name: 'stack-teardown', testDir: './e2e/setup', testMatch: 'stack.teardown.ts' },
    {
      name: 'stack',
      testDir: './e2e/specs',
      testMatch: '**/*.spec.ts',
      testIgnore: [...PREVIEW_SPECS, 'fresh/**'],
      dependencies: ['stack-setup'],
      // Baselines are created and compared only in the pinned Linux Playwright image (e2e:docker).
      snapshotPathTemplate: '{testDir}/__screenshots__/{testFileName}/{arg}{ext}',
    },
    // Defined only when selected, so a run without --project never measures.
    ...(initialLoad
      ? [
          {
            name: 'initial-load',
            testDir: './perf',
            testMatch: 'initial-load.spec.ts',
            dependencies: ['stack-setup'],
            // No tracing or screenshots during the measurement.
            use: { trace: 'off' as const, screenshot: 'off' as const },
          },
        ]
      : []),
    ...(freshInstall
      ? [
          {
            name: 'fresh-setup',
            testDir: './e2e/setup',
            testMatch: 'fresh.setup.ts',
            teardown: 'fresh-teardown',
          },
          { name: 'fresh-teardown', testDir: './e2e/setup', testMatch: 'fresh.teardown.ts' },
          {
            name: 'fresh-install',
            testDir: './e2e/specs/fresh',
            testMatch: '**/*.spec.ts',
            dependencies: ['fresh-setup'],
          },
        ]
      : []),
  ],
  ...(preview ? { webServer } : {}),
});
