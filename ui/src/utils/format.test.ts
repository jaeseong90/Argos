// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  formatBytes,
  formatCount,
  formatDateTime,
  formatDateTimeWithOffset,
  formatDuration,
  formatNumber,
  formatPercent,
  formatRate,
  formatSignedPercent,
  formatUtcOffset,
  formatValue,
  parseDateTimeLocalValue,
  toDateTimeLocalValue,
} from './format';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U13' };

describe('format helpers (docs/15 §12)', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('formats numbers with grouping and adaptive fraction digits', () => {
    expect(formatNumber(1234567, 'en')).toBe('1,234,567');
    expect(formatNumber(3.14159, 'en')).toBe('3.14');
    expect(formatNumber(123.456, 'en')).toBe('123.5');
    expect(formatNumber(12345.6, 'en')).toBe('12,346');
    expect(formatNumber(0, 'en')).toBe('0');
    expect(formatNumber(1234567, 'ko')).toBe('1,234,567');
  });

  it('formats counts exactly below 10,000 and compactly above', () => {
    expect(formatCount(9999, 'en')).toBe('9,999');
    expect(formatCount(12345, 'en')).toBe('12.3K');
    expect(formatCount(12345, 'ko')).toBe('1.2만');
  });

  it('formats durations as 850ms, 1.2s, 3m 12s, 2h 5m, 3d 4h', () => {
    expect(formatDuration(0.42, 'en')).toBe('0.42ms');
    expect(formatDuration(5.25, 'en')).toBe('5.3ms');
    expect(formatDuration(850, 'en')).toBe('850ms');
    expect(formatDuration(1200, 'en')).toBe('1.2s');
    expect(formatDuration(12_340, 'en')).toBe('12.3s');
    expect(formatDuration(192_000, 'en')).toBe('3m 12s');
    expect(formatDuration(180_000, 'en')).toBe('3m');
    expect(formatDuration(7_500_000, 'en')).toBe('2h 5m');
    expect(formatDuration(273_600_000, 'en')).toBe('3d 4h');
    expect(formatDuration(-850, 'en')).toBe('-850ms');
    expect(formatDuration(0, 'en')).toBe('0ms');
  });

  it('formats bytes with binary units', () => {
    expect(formatBytes(0, 'en')).toBe('0 B');
    expect(formatBytes(512, 'en')).toBe('512 B');
    expect(formatBytes(1536, 'en')).toBe('1.5 KiB');
    expect(formatBytes(3.25 * 1024 ** 3, 'en')).toBe('3.25 GiB');
    expect(formatBytes(500 * 1024 ** 2, 'en')).toBe('500 MiB');
    expect(formatBytes(-2048, 'en')).toBe('-2 KiB');
  });

  it('formats ratios as percentages and rates per second', () => {
    expect(formatPercent(0.0123, 'en')).toBe('1.23%');
    expect(formatPercent(0.5, 'en')).toBe('50%');
    expect(formatPercent(0, 'en')).toBe('0%');
    expect(formatPercent(0.1234, 'en')).toBe('12.3%');
    expect(formatRate(123.45, 'en')).toBe('123.5/s');
    expect(formatRate(1.234, 'en')).toBe('1.23/s');
  });

  it('formats signed relative changes with a real minus sign', () => {
    expect(formatSignedPercent(0.12, 'en')).toBe('+12%');
    expect(formatSignedPercent(-0.05, 'en')).toBe('−5%');
    expect(formatSignedPercent(0.004, 'en')).toBe('+0.4%');
    expect(formatSignedPercent(0.0004, 'en')).toBe('0%');
    expect(formatSignedPercent(0, 'en')).toBe('0%');
  });

  it('formats values by unit', () => {
    expect(formatValue(850, 'ms', 'en')).toBe('850ms');
    expect(formatValue(2, 'seconds', 'en')).toBe('2s');
    expect(formatValue(2048, 'bytes', 'en')).toBe('2 KiB');
    expect(formatValue(0.25, 'ratio', 'en')).toBe('25%');
    expect(formatValue(25, 'percent', 'en')).toBe('25%');
    expect(formatValue(12.5, 'rate', 'en')).toBe('12.5/s');
    expect(formatValue(12345, 'count', 'en')).toBe('12.3K');
    expect(formatValue(1.5, 'cores', 'en')).toBe('1.5');
  });

  it('shows the browser UTC offset of the instant (05 §2.0-9)', () => {
    const at = Date.UTC(2026, 10, 14, 0, 0, 0);
    vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(-540);
    expect(formatUtcOffset(at)).toBe('UTC+09:00');
    vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(210);
    expect(formatUtcOffset(at)).toBe('UTC−03:30');
    vi.spyOn(Date.prototype, 'getTimezoneOffset').mockReturnValue(0);
    expect(formatUtcOffset(at)).toBe('UTC');
    expect(formatDateTimeWithOffset(at, 'en')).toContain('(UTC)');
  });

  it('formats local date-times with seconds in 24-hour time', () => {
    const at = new Date(2026, 10, 14, 13, 5, 7).getTime();
    expect(formatDateTime(at, 'en')).toBe('11/14/2026, 13:05:07');
  });

  it('round-trips datetime-local input values in the browser time zone', () => {
    const at = new Date(2026, 10, 14, 9, 5, 7).getTime();
    expect(toDateTimeLocalValue(at)).toBe('2026-11-14T09:05:07');
    expect(parseDateTimeLocalValue('2026-11-14T09:05:07')).toBe(at);
    expect(parseDateTimeLocalValue('2026-11-14T09:05')).toBe(
      new Date(2026, 10, 14, 9, 5).getTime(),
    );
    expect(parseDateTimeLocalValue('')).toBeNull();
    expect(parseDateTimeLocalValue('2026-02-30T00:00')).toBeNull();
    expect(parseDateTimeLocalValue('not a date')).toBeNull();
  });
});
