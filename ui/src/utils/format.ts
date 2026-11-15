// SPDX-License-Identifier: Apache-2.0
//
// Number, duration, byte, percent and time formatting shared by components, charts and screens
// (docs/15 §12 "포맷"). Every function takes the UI language (i18n.resolvedLanguage) so that
// grouping and decimal separators follow it; times are shown in the browser time zone with the UTC
// offset available for tooltips (05 §2.0-9). Missing values are not handled here: use
// utils/missing.ts (T-UI-007) before formatting.

/** Units the shared components know how to format. */
export type ValueUnit =
  'count' | 'ms' | 'bytes' | 'percent' | 'ratio' | 'rate' | 'seconds' | 'cores' | 'none';

const NUMBER_FORMATS = new Map<string, Intl.NumberFormat>();
/** Bound for the formatter cache: a handful of languages times a few option sets. */
const NUMBER_FORMAT_CACHE_LIMIT = 64;

function numberFormat(locale: string, options: Intl.NumberFormatOptions): Intl.NumberFormat {
  const key = `${locale}|${JSON.stringify(options)}`;
  let format = NUMBER_FORMATS.get(key);
  if (format === undefined) {
    if (NUMBER_FORMATS.size >= NUMBER_FORMAT_CACHE_LIMIT) {
      NUMBER_FORMATS.clear();
    }
    format = new Intl.NumberFormat(locale, options);
    NUMBER_FORMATS.set(key, format);
  }
  return format;
}

/**
 * Formats a plain number with grouping: integers without decimals, other values with up to
 * `maxFractionDigits` (default: 2 below 10, 1 below 1,000, none above).
 */
export function formatNumber(value: number, locale: string, maxFractionDigits?: number): string {
  const abs = Math.abs(value);
  const digits =
    maxFractionDigits ?? (Number.isInteger(value) ? 0 : abs < 10 ? 2 : abs < 1000 ? 1 : 0);
  return numberFormat(locale, { maximumFractionDigits: digits }).format(value);
}

/** Formats a count compactly above 10,000 (12.3K / 1.2만 per locale), exactly below. */
export function formatCount(value: number, locale: string): string {
  if (Math.abs(value) < 10_000) {
    return formatNumber(value, locale);
  }
  return numberFormat(locale, { notation: 'compact', maximumFractionDigits: 1 }).format(value);
}

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * Formats a duration in milliseconds the way docs/15 §12 shows it: "0.42ms", "850ms", "1.2s",
 * "3m 12s", "2h 5m", "3d 4h". Negative durations keep their sign.
 */
export function formatDuration(ms: number, locale: string): string {
  if (ms < 0) {
    return `-${formatDuration(-ms, locale)}`;
  }
  if (ms < 1) {
    return `${numberFormat(locale, { maximumFractionDigits: 2 }).format(ms)}ms`;
  }
  if (ms < SECOND) {
    return `${numberFormat(locale, { maximumFractionDigits: ms < 10 ? 1 : 0 }).format(ms)}ms`;
  }
  if (ms < MINUTE) {
    const seconds = ms / SECOND;
    return `${numberFormat(locale, { maximumFractionDigits: seconds < 10 ? 2 : 1 }).format(seconds)}s`;
  }
  if (ms < HOUR) {
    const minutes = Math.floor(ms / MINUTE);
    const seconds = Math.floor((ms % MINUTE) / SECOND);
    return seconds === 0 ? `${String(minutes)}m` : `${String(minutes)}m ${String(seconds)}s`;
  }
  if (ms < DAY) {
    const hours = Math.floor(ms / HOUR);
    const minutes = Math.floor((ms % HOUR) / MINUTE);
    return minutes === 0 ? `${String(hours)}h` : `${String(hours)}h ${String(minutes)}m`;
  }
  const days = Math.floor(ms / DAY);
  const hours = Math.floor((ms % DAY) / HOUR);
  return hours === 0 ? `${String(days)}d` : `${String(days)}d ${String(hours)}h`;
}

const BYTE_UNITS = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'] as const;

/** Formats a byte count with binary (IEC) units: "512 B", "1.5 KiB", "3.2 GiB". */
export function formatBytes(bytes: number, locale: string): string {
  const sign = bytes < 0 ? '-' : '';
  let value = Math.abs(bytes);
  let unit = 0;
  while (value >= 1024 && unit < BYTE_UNITS.length - 1) {
    value /= 1024;
    unit += 1;
  }
  const digits = unit === 0 ? 0 : value < 10 ? 2 : value < 100 ? 1 : 0;
  const text = numberFormat(locale, { maximumFractionDigits: digits }).format(value);
  return `${sign}${text} ${BYTE_UNITS[unit] ?? 'B'}`;
}

/**
 * Formats a ratio (0.0123) as a percentage ("1.23%"). `fractionDigits` defaults to 2 below 10 %,
 * 1 above.
 */
export function formatPercent(ratio: number, locale: string, fractionDigits?: number): string {
  const percent = Math.abs(ratio * 100);
  const digits = fractionDigits ?? (percent === 0 ? 0 : percent < 10 ? 2 : 1);
  return numberFormat(locale, {
    style: 'percent',
    maximumFractionDigits: digits,
    minimumFractionDigits: 0,
  }).format(ratio);
}

/** Formats a per-second rate ("123.4/s"). */
export function formatRate(perSecond: number, locale: string): string {
  return `${formatNumber(perSecond, locale, Math.abs(perSecond) < 10 ? 2 : 1)}/s`;
}

/**
 * Formats a relative change (0.12 → "+12%", -0.05 → "−5%", 0 → "0%"). Uses U+2212 MINUS SIGN so
 * the sign is as wide as the plus sign in tabular numbers.
 */
export function formatSignedPercent(ratio: number, locale: string): string {
  const percent = ratio * 100;
  const abs = Math.abs(percent);
  const digits = abs < 10 ? 1 : 0;
  const rounded = Number(abs.toFixed(digits));
  const text = numberFormat(locale, { maximumFractionDigits: digits }).format(rounded);
  if (rounded === 0) {
    return `${text}%`;
  }
  return `${percent > 0 ? '+' : '−'}${text}%`;
}

/** Formats a value in `unit`. Missing values must be handled by the caller (utils/missing.ts). */
export function formatValue(value: number, unit: ValueUnit, locale: string): string {
  switch (unit) {
    case 'ms':
      return formatDuration(value, locale);
    case 'seconds':
      return formatDuration(value * SECOND, locale);
    case 'bytes':
      return formatBytes(value, locale);
    case 'percent':
      return formatPercent(value / 100, locale);
    case 'ratio':
      return formatPercent(value, locale);
    case 'rate':
      return formatRate(value, locale);
    case 'count':
      return formatCount(value, locale);
    case 'cores':
    case 'none':
      return formatNumber(value, locale);
  }
}

/**
 * The UTC offset of the browser time zone at `epochMs` ("UTC+09:00", "UTC−03:30", "UTC").
 * Offsets change with daylight saving time, so pass the instant being shown.
 */
export function formatUtcOffset(epochMs: number): string {
  const offsetMinutes = -new Date(epochMs).getTimezoneOffset();
  if (offsetMinutes === 0) {
    return 'UTC';
  }
  const sign = offsetMinutes > 0 ? '+' : '−';
  const abs = Math.abs(offsetMinutes);
  const hours = String(Math.floor(abs / 60)).padStart(2, '0');
  const minutes = String(abs % 60).padStart(2, '0');
  return `UTC${sign}${hours}:${minutes}`;
}

const DATE_TIME_FORMATS = new Map<string, Intl.DateTimeFormat>();

function dateTimeFormat(locale: string, withDate: boolean): Intl.DateTimeFormat {
  const key = `${locale}|${String(withDate)}`;
  let format = DATE_TIME_FORMATS.get(key);
  if (format === undefined) {
    if (DATE_TIME_FORMATS.size >= NUMBER_FORMAT_CACHE_LIMIT) {
      DATE_TIME_FORMATS.clear();
    }
    format = new Intl.DateTimeFormat(locale, {
      ...(withDate ? { year: 'numeric', month: '2-digit', day: '2-digit' } : {}),
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    });
    DATE_TIME_FORMATS.set(key, format);
  }
  return format;
}

/** Local date and time with seconds in the browser time zone (docs/15 §12). */
export function formatDateTime(epochMs: number, locale: string): string {
  return dateTimeFormat(locale, true).format(epochMs);
}

/** Local time with seconds, for axes and tooltips inside one day. */
export function formatTime(epochMs: number, locale: string): string {
  return dateTimeFormat(locale, false).format(epochMs);
}

/** Local date-time followed by the UTC offset, for absolute-time tooltips (05 §2.0-9). */
export function formatDateTimeWithOffset(epochMs: number, locale: string): string {
  return `${formatDateTime(epochMs, locale)} (${formatUtcOffset(epochMs)})`;
}

/**
 * The value of an `<input type="datetime-local" step="1">` for an instant, in the browser time
 * zone ("2026-11-14T09:05:07").
 */
export function toDateTimeLocalValue(epochMs: number): string {
  const date = new Date(epochMs);
  const pad = (n: number): string => String(n).padStart(2, '0');
  return (
    `${String(date.getFullYear()).padStart(4, '0')}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

/**
 * Parses the value of an `<input type="datetime-local">` (browser time zone) into epoch ms, or
 * null when it is empty or invalid.
 */
export function parseDateTimeLocalValue(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(value);
  if (match === null) {
    return null;
  }
  const [, y, mo, d, h, mi, s] = match;
  const date = new Date(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h),
    Number(mi),
    s === undefined ? 0 : Number(s),
  );
  const epoch = date.getTime();
  if (Number.isNaN(epoch) || date.getMonth() !== Number(mo) - 1 || date.getDate() !== Number(d)) {
    return null;
  }
  return epoch;
}
