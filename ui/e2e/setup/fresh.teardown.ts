// SPDX-License-Identifier: Apache-2.0
//
// Project fresh-teardown (playwright.config.ts, WO-46): runs after the specs of e2e/specs/fresh/,
// also when they failed. The fresh-install run has no CSP positive control, so any growth of the
// server's argos_csp_violations_total over the run fails it (one-sided as in stack.teardown.ts,
// ADR-0010 C-188: no growth makes no claim). Then it removes the stack this run started
// (ARGOS_E2E_KEEP=1 keeps it; a reused stack is never removed) and releases the server-it lock.
import { expect, test } from '@playwright/test';

import { tearDownFreshStack } from '../stack/fresh';

test(
  'T-SEC-46 (fresh) server CSP counter: no growth of argos_csp_violations_total over the fresh-install run; the stack is removed',
  { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; this test uses none
  async ({}, testInfo) => {
    test.setTimeout(5 * 60_000);
    const result = await tearDownFreshStack((line) => {
      process.stdout.write(`[e2e-fresh] ${line}\n`);
    });
    testInfo.annotations.push({
      type: 'csp-counter',
      description:
        result.cspIncrease === null
          ? `not read: ${result.cspProblem ?? 'unknown reason'}`
          : `increase ${String(result.cspIncrease)} (informational, C-188)`,
    });
    expect(
      result.cspIncrease ?? 0,
      'argos_csp_violations_total increase over the fresh-install run',
    ).toBe(0);
  },
);
