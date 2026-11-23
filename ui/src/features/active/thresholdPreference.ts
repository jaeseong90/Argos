// SPDX-License-Identifier: Apache-2.0
//
// Elapsed colour thresholds of the active transactions screen (05 §2.6 "설정 가능"): a per-browser
// preference under argos.ui.* (docs/15 §4.1), like the overview's card/table choice. No server
// storage is designed for it (plan PM question 3); an operator-wide setting would be a follow-up.
// Reads and writes never throw: storage may be disabled, full or missing (private windows,
// previews); the default 1/3/8 s applies then. The key `argos.ui.activeThresholds` is written as a
// literal at each call (lint rule argos/no-token-storage).
import {
  DEFAULT_THRESHOLDS,
  isDefaultThresholds,
  thresholdProblem,
  type Thresholds,
} from './model';

/** The remembered thresholds, or the default when nothing (valid) is stored or storage fails. */
export function readThresholds(): Thresholds {
  try {
    const raw = localStorage.getItem('argos.ui.activeThresholds');
    if (raw === null) {
      return DEFAULT_THRESHOLDS;
    }
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed) || !parsed.every((value) => typeof value === 'number')) {
      return DEFAULT_THRESHOLDS;
    }
    const values: number[] = parsed;
    if (thresholdProblem(values) !== null) {
      return DEFAULT_THRESHOLDS;
    }
    const [t1, t2, t3] = values as [number, number, number];
    return [t1, t2, t3];
  } catch {
    return DEFAULT_THRESHOLDS;
  }
}

/** Remembers the thresholds (the default removes the entry); a storage failure is ignored. */
export function writeThresholds(thresholds: Thresholds): void {
  try {
    if (isDefaultThresholds(thresholds)) {
      localStorage.removeItem('argos.ui.activeThresholds');
    } else {
      localStorage.setItem('argos.ui.activeThresholds', JSON.stringify(thresholds));
    }
  } catch {
    // Not remembered: the thresholds still apply to this page.
  }
}
