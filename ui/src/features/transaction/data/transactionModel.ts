// SPDX-License-Identifier: Apache-2.0
//
// What the detail response says about missing, masked and partial data, as plain values the detail
// screen renders (docs/15 §1 "hide nothing", D-05, D-07, D-23, ADR-0010 C-13, C-154). Nothing here
// fills in or guesses a value the server did not send: a null stays missing (a dash, never 0),
// `measured=false` is "not measurable", masked parameters stay as the server masked them.
import { isApiError } from '../../../api/errors';
import { formatInstant, parseInstant } from '../../../state/urlState';
import { missingReason, type MissingReason } from '../../../utils/missing';

import type { TransactionDetail, TransactionProfile, TransactionStep } from './transactionApi';

// ---------------------------------------------------------------------------------------------
// Time hint (ADR-0007 C-50)
// ---------------------------------------------------------------------------------------------

/** A start-time hint: epoch ms (URL state), ISO-8601 UTC (API values), or none. */
export type TimeHint = number | string | null | undefined;

/**
 * The canonical ISO-8601 UTC spelling of a hint (formatInstant), or undefined for none or an
 * unreadable one. URL state (epoch ms) and API values (`ts`, `sample_normal_ts`) give the same
 * string, so a detail fetched by the /tx alias and the one the detail route asks for share a cache
 * entry.
 */
export function normalizeTimeHint(hint: TimeHint): string | undefined {
  if (hint === null || hint === undefined) {
    return undefined;
  }
  if (typeof hint === 'number') {
    return Number.isFinite(hint) && hint > 0 ? formatInstant(hint) : undefined;
  }
  const ms = parseInstant(hint) ?? Date.parse(hint);
  return Number.isFinite(ms) && ms > 0 ? formatInstant(ms) : undefined;
}

// ---------------------------------------------------------------------------------------------
// Not found (ADR-0007 C-50, ADR-0010 C-150)
// ---------------------------------------------------------------------------------------------

/**
 * Why a transaction was not found: `clock_skew_possible` is the server's C-50 answer (not near the
 * time in its id, the agent clock may have been off: open it from a list or with ?ts); `not_found`
 * any other 404.
 */
export type TransactionNotFoundReason = 'clock_skew_possible' | 'not_found';

/** The not-found reason of a failed detail call, or null when the error is not a 404. */
export function transactionNotFoundReason(error: unknown): TransactionNotFoundReason | null {
  if (!isApiError(error) || error.status !== 404) {
    return null;
  }
  return error.hasDetail('clock_skew_possible', 'tx_id') ? 'clock_skew_possible' : 'not_found';
}

// ---------------------------------------------------------------------------------------------
// Measured values (D-23)
// ---------------------------------------------------------------------------------------------

/** A number with the reason it may not be shown (MissingReason of utils/missing). */
export interface MeasuredValue {
  readonly value: number | null;
  /** `none`: show the value (0 included); `noData`: dash; `notMeasurable`: "Not measurable". */
  readonly reason: MissingReason;
}

/** A value the server may not have measured: `measured=false` wins over any number (D-23). */
export function measuredValue(value: number | null | undefined, measured: boolean): MeasuredValue {
  const reason = missingReason(value, measured);
  return { value: reason === 'none' && typeof value === 'number' ? value : null, reason };
}

/** CPU time of a transaction (docs/14 §4.6 `cpu_time_ms`, `measured.cpu`). */
export function cpuTime(detail: TransactionDetail): MeasuredValue {
  return measuredValue(detail.cpu_time_ms, detail.measured.cpu);
}

/** Allocated bytes (docs/14 §4.6 `alloc_bytes`, `measured.alloc`; virtual threads: not measured). */
export function allocatedBytes(detail: TransactionDetail): MeasuredValue {
  return measuredValue(detail.alloc_bytes, detail.measured.alloc);
}

/** A value that is null when it has no source (no "measured" flag): dash for null, else the value. */
export function optionalValue(value: number | null | undefined): MeasuredValue {
  const reason = missingReason(value);
  return { value: reason === 'none' && typeof value === 'number' ? value : null, reason };
}

// ---------------------------------------------------------------------------------------------
// Profile state (D-07, ADR-0010 C-154)
// ---------------------------------------------------------------------------------------------

export type ProfileState = TransactionProfile['state'];

/**
 * Notices about the profile, in display order:
 *   - `sampled_out`: the agent did not capture steps (sampling policy, D-07),
 *   - `lost`: captured, but no step arrived within the ingest allowance (D-07),
 *   - `retention_exceeded`: the steps are older than the profile retention,
 *   - `steps_pending`: captured, no step yet, ended less than 2 minutes ago (they may still arrive),
 *   - `step_truncated`: the agent cut the profile at its step cap (summary steps follow).
 *
 * The server reports a captured profile whose steps are past the retention as state `lost` with
 * `retention_exceeded` (docs/14 §4.6): the steps were stored and expired, they did not miss the
 * ingest allowance, so only `retention_exceeded` is said (the banner keeps the state `lost`).
 */
export type ProfileNotice =
  'sampled_out' | 'lost' | 'retention_exceeded' | 'steps_pending' | 'step_truncated';

export function profileNotices(profile: TransactionProfile): ProfileNotice[] {
  const notices: ProfileNotice[] = [];
  if (profile.state === 'sampled_out') {
    notices.push('sampled_out');
  } else if (profile.state === 'lost' && !profile.retention_exceeded) {
    notices.push('lost');
  }
  if (profile.retention_exceeded) {
    notices.push('retention_exceeded');
  }
  if (profile.state === 'captured' && profile.steps_pending) {
    notices.push('steps_pending');
  }
  if (profile.step_truncated) {
    notices.push('step_truncated');
  }
  return notices;
}

/** True when the response holds steps to draw (captured and at least one step). */
export function hasSteps(profile: TransactionProfile): boolean {
  return profile.state === 'captured' && profile.steps.length > 0;
}

// ---------------------------------------------------------------------------------------------
// Bind parameters (SEC-3A, docs/14 §4.6 / §7.3, ADR-0010 C-13)
// ---------------------------------------------------------------------------------------------

/** Parameter modes of the contract. */
export const PARAMS_MODES = ['masked', 'partial', 'full', 'masked_by_role'] as const;

/**
 * The mode the server applied to `params`: masked (types only), partial, full, masked_by_role (a
 * full-mode value shown as type and length because the viewer lacks pii.view, C-13), or `unknown`
 * (absent or a value this UI does not know: shown as "unknown mode", the text stays as sent).
 */
export type ParamsMode = (typeof PARAMS_MODES)[number] | 'unknown';

export function paramsMode(raw: string | null | undefined): ParamsMode {
  return PARAMS_MODES.find((mode) => mode === raw) ?? 'unknown';
}

/** The mode of a step's parameters (`params_mode` of SQL steps). */
export function stepParamsMode(step: TransactionStep): ParamsMode {
  return paramsMode(step.params_mode);
}

/** The modes present on the steps, in contract order (for the SQL tab's mode legend). */
export function stepParamsModes(steps: readonly TransactionStep[]): ParamsMode[] {
  const present = new Set<ParamsMode>();
  for (const step of steps) {
    if (step.params !== undefined || step.params_mode !== undefined) {
      present.add(stepParamsMode(step));
    }
  }
  return [...PARAMS_MODES, 'unknown' as const].filter((mode) => present.has(mode));
}

/**
 * True when the server downgraded full-mode parameters for this viewer (C-13): the screen shows the
 * "masked for your role" notice; it never asks for or shows more than the server sent.
 */
export function maskedByRole(detail: TransactionDetail): boolean {
  return detail.profile.steps.some((step) => step.params_mode === 'masked_by_role');
}

// ---------------------------------------------------------------------------------------------
// Collection modes of personal fields (docs/14 §4.6, §7.3)
// ---------------------------------------------------------------------------------------------

/** Remote address mode: full, masked (/24 or /48 shown), off (not collected). */
export type IpMode = 'full' | 'masked' | 'off' | 'unknown';
/** User id mode: plain, hashed, off (not collected). */
export type UserIdMode = 'plain' | 'hashed' | 'off' | 'unknown';

export function ipMode(raw: string | null | undefined): IpMode {
  return raw === 'full' || raw === 'masked' || raw === 'off' ? raw : 'unknown';
}

export function userIdMode(raw: string | null | undefined): UserIdMode {
  return raw === 'plain' || raw === 'hashed' || raw === 'off' ? raw : 'unknown';
}

// ---------------------------------------------------------------------------------------------
// Lookup path (ADR-0010 C-150)
// ---------------------------------------------------------------------------------------------

/** How the server found the transaction: by the ?ts hint, the time in its id, or the 2-day fallback. */
export type LookupPath = 'ts_hint' | 'restored' | 'two_day_fallback' | 'unknown';

export function lookupPath(raw: string | null | undefined): LookupPath {
  return raw === 'ts_hint' || raw === 'restored' || raw === 'two_day_fallback' ? raw : 'unknown';
}
