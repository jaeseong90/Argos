// SPDX-License-Identifier: Apache-2.0
//
// Playwright configuration of the XLog performance spec (T-NFR-14, perf/xlog-frame.spec.ts):
// `corepack pnpm perf`. One worker, no retries, no Playwright tracing or video (they would load the
// machine during the measurement). Settings: perf/lib/settings.ts.
import { defineConfig } from '@playwright/test';

import { uiTarget } from './e2e/fixtures/uiServer';
import { perfSettings } from './perf/lib/settings';

const target = uiTarget(process.env);
const settings = perfSettings(process.env);

export default defineConfig({
  testDir: './perf',
  testMatch: '**/*.spec.ts',
  outputDir: './test-results/perf',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: process.env.CI !== undefined,
  globalSetup: './perf/lib/globalSetup.ts',
  globalTeardown: './perf/lib/globalTeardown.ts',
  reporter: [['list']],
  use: {
    baseURL: target.baseURL,
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
  webServer: target.webServer,
});
