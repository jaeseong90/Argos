// SPDX-License-Identifier: Apache-2.0
//
// Elapsed colour thresholds of the active transactions screen (05 §2.6 "설정 가능"): a per-browser
// preference under argos.ui.* (docs/15 §4.1), like the overview's card/table choice, on top of the
// operator-wide default (ADR-0010 C-225 (3)): the server setting argos.ui.active-thresholds-ms,
// sent as `default_thresholds_ms` with GET /apps/{appId}/active. Order: the viewer's stored value,
// else the operator default, else 1/3/8 s. Storing a value equal to the operator default removes
// the entry, so the viewer follows a later change of the default.
// Reads and writes never throw: storage may be disabled, full or missing (private windows,
// previews). The key `argos.ui.activeThresholds` is written as a literal at each call (lint rule
// argos/no-token-storage).
import { DEFAULT_THRESHOLDS, thresholdProblem, type Thresholds } from './model';

/** The thresholds this browser remembers, or null when nothing (valid) is stored or storage fails. */
export function readStoredThresholds(): Thresholds | null {
  try {
    const raw = localStorage.getItem('argos.ui.activeThresholds');
    if (raw === null) {
      return null;
    }
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed) || !parsed.every((value) => typeof value === 'number')) {
      return null;
    }
    const values: number[] = parsed;
    if (thresholdProblem(values) !== null) {
      return null;
    }
    const [t1, t2, t3] = values as [number, number, number];
    return [t1, t2, t3];
  } catch {
    return null;
  }
}

/** The remembered thresholds, or `fallback` (the operator default, else 1/3/8 s). */
export function readThresholds(fallback: Thresholds = DEFAULT_THRESHOLDS): Thresholds {
  return readStoredThresholds() ?? fallback;
}

/** True when both sets are the same three values. */
export function sameThresholds(a: Thresholds, b: Thresholds): boolean {
  return a.every((value, index) => value === b[index]);
}

/**
 * Remembers the thresholds; a value equal to `defaults` (the operator default, else 1/3/8 s)
 * removes the entry. A storage failure is ignored.
 */
export function writeThresholds(
  thresholds: Thresholds,
  defaults: Thresholds = DEFAULT_THRESHOLDS,
): void {
  try {
    if (sameThresholds(thresholds, defaults)) {
      localStorage.removeItem('argos.ui.activeThresholds');
    } else {
      localStorage.setItem('argos.ui.activeThresholds', JSON.stringify(thresholds));
    }
  } catch {
    // Not remembered: the thresholds still apply to this page.
  }
}

/**
 * The operator default of a snapshot (`default_thresholds_ms`, three ascending positive
 * milliseconds, ADR-0010 C-225 (3)) in seconds, or null when the server sends none or a set the
 * screen would refuse (then 1/3/8 s applies).
 */
export function operatorThresholds(snapshot: unknown): Thresholds | null {
  if (typeof snapshot !== 'object' || snapshot === null) {
    return null;
  }
  const value: unknown = Reflect.get(snapshot, 'default_thresholds_ms');
  if (
    !Array.isArray(value) ||
    value.length !== 3 ||
    !value.every((ms) => typeof ms === 'number' && Number.isFinite(ms))
  ) {
    return null;
  }
  const seconds = (value as number[]).map((ms) => ms / 1000);
  if (thresholdProblem(seconds) !== null) {
    return null;
  }
  const [t1, t2, t3] = seconds as [number, number, number];
  return [t1, t2, t3];
}
