// SPDX-License-Identifier: Apache-2.0
//
// Missing values (T-UI-007, D-23, docs/14 §2.5 nullability, docs/15 §1 "hide nothing"). The API
// marks a value null when it was not measured or has no source yet; the UI shows that as a dash
// (or "not measurable" when the server says measured=false), never as 0. A real 0 is a value.

/** The dash shown for a value that is null or undefined (U+2014 EM DASH). */
export const MISSING_DASH = '—';

/** Why a value is not shown: `none` = it is a value (0 included). */
export type MissingReason = 'none' | 'noData' | 'notMeasurable';

/**
 * Classifies a value: `notMeasurable` when the caller passes measured=false (the server could not
 * measure it, e.g. a JVM without the MXBean), `noData` for null, undefined and NaN, `none`
 * otherwise. 0, false and the empty string are values.
 */
export function missingReason(value: unknown, measured?: boolean | null): MissingReason {
  if (measured === false) {
    return 'notMeasurable';
  }
  if (value === null || value === undefined) {
    return 'noData';
  }
  if (typeof value === 'number' && Number.isNaN(value)) {
    return 'noData';
  }
  return 'none';
}

/** True when the value must be shown as missing (see missingReason). */
export function isMissing(value: unknown, measured?: boolean | null): value is null | undefined {
  return missingReason(value, measured) !== 'none';
}

/** Result of formatOrMissing: the text to show and why it is (not) a value. */
export interface FormattedValue {
  readonly text: string;
  readonly reason: MissingReason;
}

/**
 * Formats a number with `format`, or returns the dash for a missing value. `notMeasurableText` is
 * the translated "Not measurable" label (components pass t('component.missing.notMeasurable')).
 */
export function formatOrMissing(
  value: number | null | undefined,
  format: (value: number) => string,
  options: { readonly measured?: boolean | null; readonly notMeasurableText: string },
): FormattedValue {
  const reason = missingReason(value, options.measured);
  if (reason === 'notMeasurable') {
    return { text: options.notMeasurableText, reason };
  }
  if (reason === 'noData' || value === null || value === undefined) {
    return { text: MISSING_DASH, reason: 'noData' };
  }
  return { text: format(value), reason };
}
