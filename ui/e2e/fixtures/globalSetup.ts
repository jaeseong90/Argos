// SPDX-License-Identifier: Apache-2.0
//
// Global setup of the E2E suite: when Playwright serves dist/ itself, refuse a missing or stale
// build (e2e/fixtures/distCheck.ts). A server named by ARGOS_UI_BASE_URL serves its own build.
import { fileURLToPath } from 'node:url';

import { checkDist } from './distCheck';
import { uiTarget } from './uiServer';

/** The ui/ directory. */
export const UI_ROOT = fileURLToPath(new URL('../..', import.meta.url));

/** Throws when the suite would serve a missing or stale dist/. */
export function requireFreshDist(env: Readonly<Record<string, string | undefined>>): void {
  if (uiTarget(env).webServer === undefined) {
    return;
  }
  const problem = checkDist(UI_ROOT);
  if (problem !== null) {
    throw new Error(problem);
  }
}

export default function globalSetup(): void {
  requireFreshDist(process.env);
}
