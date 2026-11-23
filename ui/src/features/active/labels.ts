// SPDX-License-Identifier: Apache-2.0
//
// Translation keys of the active transactions screen that depend on data values (states, kinds,
// bands, threshold problems), as typed constant maps.
import { ACTIVE_STATES, type ActiveState } from '../../api/active';

import type { ThresholdProblem } from './model';

export const STATE_LABEL_KEYS = {
  SQL: 'active:state.SQL',
  HTTP: 'active:state.HTTP',
  DBC: 'active:state.DBC',
  METHOD: 'active:state.METHOD',
  ASYNC: 'active:state.ASYNC',
} as const satisfies Record<ActiveState, string>;

/** A server state as one of the five, or null for a value this UI does not know. */
export function activeState(value: string): ActiveState | null {
  const upper = value.trim().toUpperCase();
  return (ACTIVE_STATES as readonly string[]).includes(upper) ? (upper as ActiveState) : null;
}

export const KIND_LABEL_KEYS = {
  HTTP: 'common:term.kind.http',
  BATCH: 'common:term.kind.batch',
  MESSAGE: 'common:term.kind.message',
} as const;

/** The label key of a transaction kind, or null for an unknown one. */
export function kindLabelKey(
  kind: string,
): (typeof KIND_LABEL_KEYS)[keyof typeof KIND_LABEL_KEYS] | null {
  return Object.prototype.hasOwnProperty.call(KIND_LABEL_KEYS, kind)
    ? KIND_LABEL_KEYS[kind as keyof typeof KIND_LABEL_KEYS]
    : null;
}

export const THRESHOLD_PROBLEM_KEYS = {
  notNumber: 'active:thresholds.error.notNumber',
  notPositive: 'active:thresholds.error.notPositive',
  notAscending: 'active:thresholds.error.notAscending',
  tooLarge: 'active:thresholds.error.tooLarge',
} as const satisfies Record<ThresholdProblem, string>;
