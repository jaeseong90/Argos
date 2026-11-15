// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { formatBytes, formatDuration, formatNumber } from './format';
import { formatOrMissing, isMissing, MISSING_DASH, missingReason } from './missing';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'D-23' };

const NOT_MEASURABLE = 'Not measurable';
const options = { notMeasurableText: NOT_MEASURABLE };

describe('missing values (T-UI-007)', () => {
  it('T-UI-007 classifies null, undefined and NaN as no data and measured=false as not measurable', () => {
    expect(missingReason(null)).toBe('noData');
    expect(missingReason(undefined)).toBe('noData');
    expect(missingReason(Number.NaN)).toBe('noData');
    expect(missingReason(12, false)).toBe('notMeasurable');
    expect(missingReason(null, false)).toBe('notMeasurable');
    expect(missingReason(0)).toBe('none');
    expect(missingReason(0, true)).toBe('none');
    expect(missingReason('')).toBe('none');
    expect(isMissing(0)).toBe(false);
    expect(isMissing(null)).toBe(true);
  });

  it('T-UI-007 renders a missing number as the dash and 0 as 0', () => {
    const number = (v: number): string => formatNumber(v, 'en');
    expect(formatOrMissing(null, number, options)).toEqual({
      text: MISSING_DASH,
      reason: 'noData',
    });
    expect(formatOrMissing(undefined, number, options)).toEqual({
      text: MISSING_DASH,
      reason: 'noData',
    });
    expect(formatOrMissing(0, number, options)).toEqual({ text: '0', reason: 'none' });
    expect(formatOrMissing(0, number, options).text).not.toBe(MISSING_DASH);
  });

  it('T-UI-007 renders a missing duration as the dash and 0 ms as 0ms', () => {
    const duration = (v: number): string => formatDuration(v, 'en');
    expect(formatOrMissing(null, duration, options).text).toBe(MISSING_DASH);
    expect(formatOrMissing(0, duration, options).text).toBe('0ms');
    expect(formatOrMissing(250, duration, { ...options, measured: false }).text).toBe(
      NOT_MEASURABLE,
    );
  });

  it('T-UI-007 renders missing bytes as the dash or not measurable and 0 bytes as 0 B', () => {
    const bytes = (v: number): string => formatBytes(v, 'en');
    expect(formatOrMissing(undefined, bytes, options).text).toBe(MISSING_DASH);
    expect(formatOrMissing(0, bytes, options).text).toBe('0 B');
    expect(formatOrMissing(null, bytes, { ...options, measured: false })).toEqual({
      text: NOT_MEASURABLE,
      reason: 'notMeasurable',
    });
  });
});
