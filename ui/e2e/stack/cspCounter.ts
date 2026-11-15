// SPDX-License-Identifier: Apache-2.0
//
// The server's CSP report counter argos_csp_violations_total over one E2E run, read on the
// management port with the run's metrics token at the end of the setup and again at the teardown.
//
// It is not the SEC-46 evidence of the suite (ADR-0010 C-188, replacing docs/16 §9.5 ST-46-1): the
// server policy names `report-uri` and `report-to csp` (CspPolicy, WO-24); Chromium then uses the
// Reporting API only, which batches reports, sends them late and may drop them when a context
// closes, so "the counter did not grow" proves nothing. SEC-46 is judged by
//   (1) the browser's securitypolicyviolation events, after every stack test (e2e/fixtures/),
//   (2) the positive controls of e2e/specs/csp-report.spec.ts (a real violation must be seen),
//   (3) the server tests of the report endpoint (both formats raise the counter, CspSecurityTest).
// What remains here is a one-sided check: a growth beyond the reports the positive controls may
// cause fails the run (a violation the server did count). The controls add their possible reports
// to ui/e2e/.stack/<run>/csp-control.json before they cause them, so the teardown subtracts them
// whether or not Chromium delivered them. No growth, or a smaller one, passes without a claim.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { z } from 'zod';

import { cspControlFile, secretsDir } from './paths.ts';
import { loadStackSecrets } from './secrets.ts';
import { readCspViolations } from './stack.ts';

export interface CspCounterReading {
  /** argos_csp_violations_total summed over its label sets, or null when it could not be read. */
  readonly value: number | null;
  /** Why the counter could not be read, or null. */
  readonly problem: string | null;
}

/** Reads the counter of a stack (management URL, metrics token of the run's secrets). */
export async function readStackCspCounter(
  managementUrl: string | null,
  runId: string,
): Promise<CspCounterReading> {
  if (managementUrl === null) {
    return { value: null, problem: 'the management URL of the stack is unknown' };
  }
  let token: string;
  try {
    token = loadStackSecrets(secretsDir(runId)).metricsToken;
  } catch {
    return { value: null, problem: `the metrics token of run ${runId} is not in ui/e2e/.stack` };
  }
  const value = await readCspViolations(managementUrl, token);
  return value === null
    ? {
        value: null,
        problem: `${managementUrl}/actuator/prometheus has no argos_csp_violations_total`,
      }
    : { value, problem: null };
}

export const CspControl = z.object({
  run_id: z.string(),
  /**
   * The most reports the positive controls of this run can cause (one per violation they cause);
   * subtracted by the teardown. Chromium may deliver fewer, or none.
   */
  expected_increase: z.number().int().nonnegative(),
});
export type CspControl = z.infer<typeof CspControl>;

/** Writes the control record of `runId`. */
export function recordCspControl(
  control: CspControl,
  file: string = cspControlFile(control.run_id),
): void {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(CspControl.parse(control), null, 2)}\n`, 'utf8');
}

/** The control record of `runId`, or null when no control ran in this session. */
export function readCspControl(
  runId: string,
  file: string = cspControlFile(runId),
): CspControl | null {
  if (!existsSync(file)) {
    return null;
  }
  const control = CspControl.parse(JSON.parse(readFileSync(file, 'utf8')));
  return control.run_id === runId ? control : null;
}

/**
 * Adds `reports` possible reports of a positive control to the record of `runId`; called before
 * the control causes its violations, so a report that arrives later is still the control's.
 */
export function addCspControlReports(
  runId: string,
  reports: number,
  file: string = cspControlFile(runId),
): CspControl {
  const before = readCspControl(runId, file)?.expected_increase ?? 0;
  const control: CspControl = { run_id: runId, expected_increase: before + reports };
  recordCspControl(control, file);
  return control;
}

/** Forgets the record of an earlier Playwright run on the same stack (a reused stack). */
export function clearCspControl(runId: string, file: string = cspControlFile(runId)): void {
  rmSync(file, { force: true });
}

export type CspVerdict =
  | { readonly kind: 'violations'; readonly increase: number }
  | { readonly kind: 'no-growth' }
  | { readonly kind: 'unread'; readonly reason: string };

/**
 * Judges the counter over a run: `increase` is end minus start (null when either could not be
 * read), `control` the record of the positive controls (null when none ran). Only a growth beyond
 * the controls' possible reports is a finding; anything else makes no claim (C-188).
 */
export function judgeCspCounter(
  increase: number | null,
  problem: string | null,
  control: CspControl | null,
): CspVerdict {
  if (increase === null) {
    return {
      kind: 'unread',
      reason: `argos_csp_violations_total not read: ${problem ?? 'unknown reason'}`,
    };
  }
  const net = increase - (control?.expected_increase ?? 0);
  return net > 0 ? { kind: 'violations', increase: net } : { kind: 'no-growth' };
}
