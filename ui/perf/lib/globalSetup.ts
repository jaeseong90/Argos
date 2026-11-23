// SPDX-License-Identifier: Apache-2.0
//
// Runs before the workers start. It names this performance run (environment variables set here
// reach the test workers, so every scenario appends to the same results file), and prepares the
// target (perf/lib/settings.ts XLOG_PERF_TARGET):
//   poc    refuses a missing, stale or production build in build/perf-dist/ when Playwright serves
//          it itself (perf/lib/pocBuild.ts: `playwright test` run directly does not build)
//   route  starts and seeds the WO-30 E2E stack (e2e/stack/setup.ts: PostgreSQL, ClickHouse and
//          the server boot jar of this tree in Docker, generator data, users, agent traffic, seed
//          manifest), or reuses the one named by ARGOS_E2E_STACK_URL; globalTeardown removes a
//          stack this run started (ARGOS_E2E_KEEP=1 keeps it)
import { UI_ROOT } from '../../e2e/fixtures/globalSetup';
import { uiTarget } from '../../e2e/fixtures/uiServer';
import { setUpStack } from '../../e2e/stack/setup';

import { perfBuildProblem } from './pocBuild';
import { perfSettings } from './settings';

export default async function globalSetup(): Promise<void> {
  if (process.env.XLOG_PERF_RUN_ID === undefined || process.env.XLOG_PERF_RUN_ID === '') {
    process.env.XLOG_PERF_RUN_ID = new Date().toISOString().replace(/[:.]/g, '-');
  }
  if (perfSettings(process.env).target === 'route') {
    await setUpStack((line) => {
      process.stdout.write(`[perf-stack] ${line}\n`);
    }, process.pid);
    return;
  }
  // The suite's own preview serves the perf build (perf/lib/pocBuild.ts PERF_DIST); a server named
  // by ARGOS_UI_BASE_URL serves its own build.
  if (uiTarget(process.env).webServer !== undefined) {
    const problem = perfBuildProblem(UI_ROOT);
    if (problem !== null) {
      throw new Error(problem);
    }
  }
}
