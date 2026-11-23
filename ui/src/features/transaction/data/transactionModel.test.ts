// SPDX-License-Identifier: Apache-2.0
//
// What the detail response says about missing, masked and partial data (docs/15 §1, D-07, D-23,
// ADR-0010 C-13, C-154): measured=false is "not measurable" and a null is a dash, both distinct from
// 0 (T-UI-007, T-ACC-23 display); profile states and their notices (T-ACC-07 display); parameter
// modes including masked_by_role; personal-field modes; the C-50 not-found mapping; time hints.
// Requirements (test/README.md §5): { type: 'req', description: 'D-23' }, { type: 'req', description: 'D-07' }, { type: 'req', description: 'SEC-3A' }, { type: 'req', description: 'D-11' }
import { describe, expect, it } from 'vitest';

import { ApiError } from '../../../api/errors';
import { untrusted } from '../../../api/untrusted';

import {
  FIXTURE_TS,
  profileFixture,
  sqlStepFixture,
  stepFixture,
  transactionDetailFixture,
} from './testing/fixtures';
import {
  allocatedBytes,
  cpuTime,
  hasSteps,
  ipMode,
  lookupPath,
  maskedByRole,
  measuredValue,
  normalizeTimeHint,
  optionalValue,
  paramsMode,
  profileNotices,
  stepParamsMode,
  stepParamsModes,
  transactionNotFoundReason,
  userIdMode,
} from './transactionModel';

describe('T-UI-007 measured values (D-23): missing is not 0', () => {
  it('keeps 0 as a value and a null as a dash', () => {
    expect(measuredValue(0, true)).toEqual({ value: 0, reason: 'none' });
    expect(measuredValue(12, true)).toEqual({ value: 12, reason: 'none' });
    expect(measuredValue(null, true)).toEqual({ value: null, reason: 'noData' });
    expect(measuredValue(undefined, true)).toEqual({ value: null, reason: 'noData' });
    expect(optionalValue(0)).toEqual({ value: 0, reason: 'none' });
    expect(optionalValue(null)).toEqual({ value: null, reason: 'noData' });
  });

  it('measured=false is "not measurable" and never shows a number, even one the server sent', () => {
    expect(measuredValue(null, false)).toEqual({ value: null, reason: 'notMeasurable' });
    expect(measuredValue(0, false)).toEqual({ value: null, reason: 'notMeasurable' });
    expect(measuredValue(7, false)).toEqual({ value: null, reason: 'notMeasurable' });
  });

  it('T-UI-007/T-ACC-23 a virtual-thread transaction: CPU and allocation not measurable', () => {
    const vt = transactionDetailFixture({
      cpu_time_ms: null,
      alloc_bytes: null,
      measured: { cpu: false, alloc: false },
      flags: { ...transactionDetailFixture().flags, virtual_thread: true },
    });
    expect(cpuTime(vt)).toEqual({ value: null, reason: 'notMeasurable' });
    expect(allocatedBytes(vt)).toEqual({ value: null, reason: 'notMeasurable' });
    const measured = transactionDetailFixture({ cpu_time_ms: 0, alloc_bytes: 0 });
    expect(cpuTime(measured)).toEqual({ value: 0, reason: 'none' });
    expect(allocatedBytes(measured)).toEqual({ value: 0, reason: 'none' });
    const unknown = transactionDetailFixture({ cpu_time_ms: null });
    expect(cpuTime(unknown)).toEqual({ value: null, reason: 'noData' });
  });
});

describe('T-ACC-07 profile state notices (D-07, C-154)', () => {
  it('captured with steps: no notice, steps to draw', () => {
    const profile = profileFixture();
    expect(profileNotices(profile)).toEqual([]);
    expect(hasSteps(profile)).toBe(true);
  });

  it('sampled_out and lost name their reason; no steps to draw', () => {
    const sampled = profileFixture({ state: 'sampled_out', steps: [], self_time_ms: null });
    expect(profileNotices(sampled)).toEqual(['sampled_out']);
    expect(hasSteps(sampled)).toBe(false);
    // Lost before ingest (retention not exceeded): the D-07 lost reason.
    expect(
      profileNotices(profileFixture({ state: 'lost', steps: [], retention_exceeded: false })),
    ).toEqual(['lost']);
  });

  it('past the retention the server says lost + retention_exceeded: only the retention is said', () => {
    // The steps were stored and expired; "did not arrive before the ingest allowance" is not true.
    expect(
      profileNotices(profileFixture({ state: 'lost', steps: [], retention_exceeded: true })),
    ).toEqual(['retention_exceeded']);
  });

  it('captured but steps still on their way, and a profile cut at the step cap', () => {
    expect(profileNotices(profileFixture({ steps: [], steps_pending: true }))).toEqual([
      'steps_pending',
    ]);
    expect(hasSteps(profileFixture({ steps: [], steps_pending: true }))).toBe(false);
    expect(profileNotices(profileFixture({ step_truncated: true, total_steps: 5000 }))).toEqual([
      'step_truncated',
    ]);
  });
});

describe('bind parameter modes (SEC-3A, C-13)', () => {
  it('reads the four modes of the contract and keeps anything else as unknown', () => {
    expect(paramsMode('masked')).toBe('masked');
    expect(paramsMode('partial')).toBe('partial');
    expect(paramsMode('full')).toBe('full');
    expect(paramsMode('masked_by_role')).toBe('masked_by_role');
    expect(paramsMode(null)).toBe('unknown');
    expect(paramsMode(undefined)).toBe('unknown');
    expect(paramsMode('FULL')).toBe('unknown');
    expect(paramsMode('<img src=x onerror=1>')).toBe('unknown');
  });

  it('lists the modes present on the steps in contract order', () => {
    const steps = [
      sqlStepFixture(1, { params_mode: 'masked_by_role', params: untrusted('[String(12)]') }),
      stepFixture({ seq: 2, type: 'METHOD' }),
      sqlStepFixture(3, { params_mode: 'masked' }),
      sqlStepFixture(4, { params_mode: 'something-new' }),
    ];
    expect(stepParamsModes(steps)).toEqual(['masked', 'masked_by_role', 'unknown']);
    expect(stepParamsMode(steps[0] as (typeof steps)[number])).toBe('masked_by_role');
  });

  it('C-13 flags a detail whose full parameters were downgraded for this viewer', () => {
    const downgraded = transactionDetailFixture({
      profile: profileFixture({
        params_mode: 'full',
        steps: [
          sqlStepFixture(1, { params_mode: 'masked_by_role', params: untrusted('[String(12)]') }),
        ],
      }),
    });
    expect(maskedByRole(downgraded)).toBe(true);
    // The parameters stay exactly what the server sent (type and length only).
    expect(downgraded.profile.steps[0]?.params).toBe('[String(12)]');
    expect(maskedByRole(transactionDetailFixture())).toBe(false);
  });
});

describe('collection modes and lookup path (docs/14 §4.6)', () => {
  it('reads ip_mode, user_id_mode and lookup, unknown values as unknown', () => {
    expect(['full', 'masked', 'off', 'x', null].map(ipMode)).toEqual([
      'full',
      'masked',
      'off',
      'unknown',
      'unknown',
    ]);
    expect(['plain', 'hashed', 'off', ''].map(userIdMode)).toEqual([
      'plain',
      'hashed',
      'off',
      'unknown',
    ]);
    expect(['ts_hint', 'restored', 'two_day_fallback', 'bloom'].map(lookupPath)).toEqual([
      'ts_hint',
      'restored',
      'two_day_fallback',
      'unknown',
    ]);
  });
});

describe('C-50 not found and time hints', () => {
  const http = (status: number, details: { field: string | null; reason: string }[] = []) =>
    new ApiError({ kind: 'http', status, code: 'not_found', message: 'm', details });

  it('maps 404 clock_skew_possible to its reason, other 404s to not_found, the rest to null', () => {
    expect(
      transactionNotFoundReason(http(404, [{ field: 'tx_id', reason: 'clock_skew_possible' }])),
    ).toBe('clock_skew_possible');
    expect(transactionNotFoundReason(http(404))).toBe('not_found');
    expect(transactionNotFoundReason(http(400))).toBeNull();
    expect(transactionNotFoundReason(http(500))).toBeNull();
    expect(transactionNotFoundReason(new Error('x'))).toBeNull();
  });

  it('normalizes epoch ms and ISO hints to one ISO spelling, and drops unreadable ones', () => {
    const ms = Date.parse(FIXTURE_TS);
    expect(normalizeTimeHint(ms)).toBe(FIXTURE_TS);
    expect(normalizeTimeHint(FIXTURE_TS)).toBe(FIXTURE_TS);
    expect(normalizeTimeHint('2026-11-14T00:00:01Z')).toBe(FIXTURE_TS);
    expect(normalizeTimeHint('2026-11-14T09:00:01+09:00')).toBe(FIXTURE_TS);
    expect(normalizeTimeHint('yesterday')).toBeUndefined();
    expect(normalizeTimeHint(0)).toBeUndefined();
    expect(normalizeTimeHint(null)).toBeUndefined();
    expect(normalizeTimeHint(undefined)).toBeUndefined();
  });
});
