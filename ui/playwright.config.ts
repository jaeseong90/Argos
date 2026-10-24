// SPDX-License-Identifier: Apache-2.0
//
// Playwright E2E suite of the UI (docs/18 §13, docs/15 §15): `corepack pnpm e2e`. Chromium only
// for now (browser install: `corepack pnpm exec playwright install chromium`). The performance
// spec has its own configuration (playwright.perf.config.ts) and never runs here.
import { defineConfig } from '@playwright/test';

import { uiTarget } from './e2e/fixtures/uiServer';

const target = uiTarget(process.env);

export default defineConfig({
  testDir: './e2e/specs',
  outputDir: './test-results/e2e',
  fullyParallel: false,
  workers: 1,
  // docs/18 §13: no retries; traces only for failures.
  retries: 0,
  forbidOnly: process.env.CI !== undefined,
  // Refuses a missing or stale dist/ (`playwright test` run directly does not build).
  globalSetup: './e2e/fixtures/globalSetup.ts',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never', outputFolder: './playwright-report/e2e' }]],
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
  projects: [{ name: 'chromium' }],
  webServer: target.webServer,
});
