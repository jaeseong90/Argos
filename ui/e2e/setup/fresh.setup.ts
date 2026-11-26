// SPDX-License-Identifier: Apache-2.0
//
// Project fresh-setup (playwright.config.ts, WO-46, ADR-0010 C-192 (1)): starts the fresh-install
// stack (e2e/stack/fresh.ts: PostgreSQL, ClickHouse and the server, no administrator, no seed)
// before the specs of e2e/specs/fresh/, or reuses the one named by ARGOS_E2E_FRESH_URL. Its
// teardown is fresh-teardown. Never a dependency of the stack project.
import { test } from '@playwright/test';

import { setUpFreshStack } from '../stack/fresh';

// eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; this test uses none
test('start the fresh-install stack (PostgreSQL, ClickHouse, server; no administrator, setup token copied)', async ({}, testInfo) => {
  // Image pulls on a first run; the stack itself starts in about a minute.
  test.setTimeout(15 * 60_000);
  const lines: string[] = [];
  const session = await setUpFreshStack(
    (line) => {
      lines.push(line);
      process.stdout.write(`[e2e-fresh] ${line}\n`);
    },
    // The Playwright runner owns the stack (the worker that runs this setup may exit earlier).
    process.ppid,
  );
  testInfo.annotations.push({ type: 'stack-url', description: session.stack_url });
  testInfo.annotations.push({
    type: 'stack-setup-seconds',
    description: `${session.setup_seconds.toFixed(1)} ${JSON.stringify(session.timings)}`,
  });
  if (session.csp_counter_problem !== null) {
    testInfo.annotations.push({
      type: 'csp-counter-not-available',
      description: session.csp_counter_problem,
    });
  }
  await testInfo.attach('fresh-setup.log', { body: lines.join('\n'), contentType: 'text/plain' });
});
