// SPDX-License-Identifier: Apache-2.0
//
// Locale-bound formatting (docs/15 §12 "포맷", 05 §2.0-9). The number, duration, byte and date
// helpers live in utils/format.ts and take a locale; useFormat binds them to the current UI
// language so that a language switch re-renders every value. Relative times ("2 hours ago") use
// Intl.RelativeTimeFormat; plurals of messages use i18next (key_one / key_other).
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import {
  formatBytes,
  formatDateTime,
  formatDateTimeWithOffset,
  formatDuration,
  formatNumber,
  formatTime,
  formatUtcOffset,
} from '../utils/format';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const RELATIVE_FORMATS = new Map<string, Intl.RelativeTimeFormat>();

function relativeFormat(locale: string): Intl.RelativeTimeFormat {
  let format = RELATIVE_FORMATS.get(locale);
  if (format === undefined) {
    format = new Intl.RelativeTimeFormat(locale, { numeric: 'always', style: 'short' });
    RELATIVE_FORMATS.set(locale, format);
  }
  return format;
}

/**
 * A time relative to now: `deltaMs` < 0 is in the past ("3 min. ago" / "3분 전"), > 0 in the
 * future. The unit is the largest one that fits (seconds below a minute, ... days).
 */
export function formatRelativeTime(deltaMs: number, locale: string): string {
  const abs = Math.abs(deltaMs);
  const format = relativeFormat(locale);
  if (abs < MINUTE) {
    return format.format(Math.trunc(deltaMs / SECOND), 'second');
  }
  if (abs < HOUR) {
    return format.format(Math.trunc(deltaMs / MINUTE), 'minute');
  }
  if (abs < DAY) {
    return format.format(Math.trunc(deltaMs / HOUR), 'hour');
  }
  return format.format(Math.trunc(deltaMs / DAY), 'day');
}

export interface Formatter {
  readonly locale: string;
  number(value: number, maxFractionDigits?: number): string;
  duration(ms: number): string;
  bytes(bytes: number): string;
  /** Local date and time with seconds (browser time zone). */
  dateTime(epochMs: number): string;
  time(epochMs: number): string;
  /** Local date-time with the UTC offset, for absolute-time tooltips (05 §2.0-9). */
  dateTimeWithOffset(epochMs: number): string;
  utcOffset(epochMs: number): string;
  relative(epochMs: number, now?: number): string;
}

/** Pure form of useFormat. */
export function createFormatter(locale: string): Formatter {
  return {
    locale,
    number: (value, digits) => formatNumber(value, locale, digits),
    duration: (ms) => formatDuration(ms, locale),
    bytes: (bytes) => formatBytes(bytes, locale),
    dateTime: (ms) => formatDateTime(ms, locale),
    time: (ms) => formatTime(ms, locale),
    dateTimeWithOffset: (ms) => formatDateTimeWithOffset(ms, locale),
    utcOffset: (ms) => formatUtcOffset(ms),
    relative: (ms, now = Date.now()) => formatRelativeTime(ms - now, locale),
  };
}

/** Formatters for the current UI language. */
export function useFormat(): Formatter {
  const { i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  return useMemo(() => createFormatter(locale), [locale]);
}
