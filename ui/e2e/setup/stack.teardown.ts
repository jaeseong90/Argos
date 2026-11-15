// SPDX-License-Identifier: Apache-2.0
//
// Project stack-teardown (playwright.config.ts): runs after every stack spec, also when they
// failed. It compares the server's CSP report counter argos_csp_violations_total with its value at
// the end of the setup, then removes the stack this run started (ARGOS_E2E_KEEP=1 keeps it; a
// reused stack is never removed).
//
// The comparison is one-sided (ADR-0010 C-188, which replaces docs/16 §9.5 ST-46-1): a growth
// beyond the reports the positive controls of e2e/specs/csp-report.spec.ts may cause fails the run,
// because the server counted a violation. No growth makes no claim (Chromium's Reporting API sends
// reports late or drops them), so the run's SEC-46 evidence is the browser side: the
// securitypolicyviolation checks after every stack test (e2e/fixtures/) with their positive
// controls, and the server tests of the report endpoint.
import { expect, test } from '@playwright/test';

import { tearDownStack } from '../stack/setup';

test(
  "T-SEC-46 server CSP counter: a growth of argos_csp_violations_total beyond the positive controls' reports fails the run; the stack is removed",
  { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; this test uses none
  async ({}, testInfo) => {
    test.setTimeout(5 * 60_000);
    const result = await tearDownStack((line) => {
      process.stdout.write(`[e2e-stack] ${line}\n`);
    });
    const verdict = result.cspVerdict;
    testInfo.annotations.push({
      type: 'csp-counter',
      description:
        verdict.kind === 'unread'
          ? verdict.reason
          : `increase ${String(result.cspIncrease)}, positive controls' reports at most ${String(result.cspControl?.expected_increase ?? 0)} (informational, C-188)`,
    });
    expect(
      verdict.kind === 'violations' ? verdict.increase : 0,
      "argos_csp_violations_total increase over the E2E run (beyond the positive controls' reports)",
    ).toBe(0);
  },
);
