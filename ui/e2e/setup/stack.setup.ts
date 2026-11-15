// SPDX-License-Identifier: Apache-2.0
//
// Project stack-setup (playwright.config.ts): starts and seeds the E2E stack once before the stack
// specs (e2e/stack/setup.ts), or reuses the one named by ARGOS_E2E_STACK_URL. Its duration is the
// "stack start + seed" line of the WO-30 report (18 §2.1 E2E budget).
import { test } from '@playwright/test';

import { setUpStack } from '../stack/setup';

test.describe.configure({ mode: 'serial' });

// eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; this test uses none
test('start and seed the E2E stack (PostgreSQL, ClickHouse, server; generator, traffic, users)', async ({}, testInfo) => {
  // Docker image pulls (first run), the Gradle seed loader and the agent traffic take minutes.
  test.setTimeout(30 * 60_000);
  const lines: string[] = [];
  const session = await setUpStack(
    (line) => {
      lines.push(line);
      process.stdout.write(`[e2e-stack] ${line}\n`);
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
  await testInfo.attach('stack-setup.log', { body: lines.join('\n'), contentType: 'text/plain' });
});
