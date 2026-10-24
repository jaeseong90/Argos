// SPDX-License-Identifier: Apache-2.0
//
// Runs before the workers start. It refuses a missing or stale dist/ when Playwright serves it
// itself (e2e/fixtures/distCheck.ts: `playwright test` run directly does not build), and names this
// performance run: environment variables set here reach the test workers, so every scenario appends
// to the same results file.
import { requireFreshDist } from '../../e2e/fixtures/globalSetup';

export default function globalSetup(): void {
  requireFreshDist(process.env);
  if (process.env.XLOG_PERF_RUN_ID === undefined || process.env.XLOG_PERF_RUN_ID === '') {
    process.env.XLOG_PERF_RUN_ID = new Date().toISOString().replace(/[:.]/g, '-');
  }
}
