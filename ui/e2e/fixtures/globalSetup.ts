// SPDX-License-Identifier: Apache-2.0
//
// Global setup of the E2E suite: when Playwright serves the preview project's build itself, refuse
// a missing, stale or production build in build/perf-dist/ (the WO-04 PoC route exists only in the
// perf build, ADR-0010 C-203; perf/lib/pocBuild.ts, e2e/fixtures/distCheck.ts). A server named by
// ARGOS_UI_BASE_URL serves its own build.
import { fileURLToPath } from 'node:url';

import { perfBuildProblem } from '../../perf/lib/pocBuild';

import { uiTarget } from './uiServer';

/** The ui/ directory. */
export const UI_ROOT = fileURLToPath(new URL('../..', import.meta.url));

/** Throws when the suite would serve a missing, stale or production preview build. */
export function requireFreshDist(env: Readonly<Record<string, string | undefined>>): void {
  if (uiTarget(env).webServer === undefined) {
    return;
  }
  const problem = perfBuildProblem(UI_ROOT);
  if (problem !== null) {
    throw new Error(problem);
  }
}

export default function globalSetup(): void {
  requireFreshDist(process.env);
}
