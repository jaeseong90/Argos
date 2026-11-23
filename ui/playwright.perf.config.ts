// SPDX-License-Identifier: Apache-2.0
//
// Playwright configuration of the XLog performance spec (T-NFR-14, perf/xlog-frame.spec.ts):
// `corepack pnpm perf`. One worker, no retries, no Playwright tracing or video (they would load the
// machine during the measurement). Settings: perf/lib/settings.ts. The poc target (default) serves
// the perf build (`vite build --mode perf`, ADR-0010 C-203) from its own directory,
// build/perf-dist (perf/lib/pocBuild.ts), with vite preview, so a production build into dist/ during
// a session cannot replace what it serves; the route target (XLOG_PERF_TARGET=route) talks to the
// server of the E2E stack that the global setup starts or reuses (absolute URLs,
// perf/lib/routePage.ts), so it has no web server and no base URL.
import { defineConfig } from '@playwright/test';

import { uiTarget } from './e2e/fixtures/uiServer';
import { PERF_DIST } from './perf/lib/pocBuild';
import { perfSettings } from './perf/lib/settings';

const settings = perfSettings(process.env);
const route = settings.target === 'route';
const target = uiTarget(process.env);
const webServer =
  target.webServer === undefined || Array.isArray(target.webServer)
    ? target.webServer
    : { ...target.webServer, command: `${target.webServer.command} --outDir ${PERF_DIST}` };

export default defineConfig({
  testDir: './perf',
  testMatch: '**/*.spec.ts',
  // perf/initial-load.spec.ts runs against the E2E stack (playwright.config.ts, project
  // initial-load: `corepack pnpm perf:initial-load`), not against the preview server.
  testIgnore: '**/initial-load.spec.ts',
  outputDir: './test-results/perf',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: process.env.CI !== undefined,
  globalSetup: './perf/lib/globalSetup.ts',
  globalTeardown: './perf/lib/globalTeardown.ts',
  reporter: [['list']],
  use: {
    ...(route ? {} : { baseURL: target.baseURL }),
    browserName: 'chromium',
    channel: 'chromium',
    headless: !settings.headed,
    viewport: { width: 1366, height: 768 },
    // Default only: the spec sets the ratio of every scenario group from XLOG_PERF_DPR
    // (test.use({ deviceScaleFactor }), default 1 and 2, ADR-0003 C-34).
    deviceScaleFactor: 1,
    locale: 'en-US',
    timezoneId: 'UTC',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium' }],
  ...(route ? {} : { webServer }),
});
