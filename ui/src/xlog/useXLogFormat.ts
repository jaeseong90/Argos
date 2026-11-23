// SPDX-License-Identifier: Apache-2.0
//
// Locale-aware formatting of XLog values (docs/15 §12: Intl for numbers and times, i18n keys for
// units and labels). Names from the dictionaries are returned unchanged: callers render them as
// text nodes only (SEC-60).
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { durationUnitFor, type DurationUnit } from './scale';
import { STATUS_ERROR, STATUS_SLOW } from './XLogBuffer';
import { formatDuration } from './xlogFormat';

export interface XLogFormat {
  /** Duration in the given unit, e.g. "1,200 ms", "1.2 s". */
  readonly duration: (ms: number, unit?: DurationUnit) => string;
  /** Axis time label (HH:mm:ss, browser time zone). */
  readonly axisTime: (ts: number) => string;
  /** Time with milliseconds for lists and tooltips. */
  readonly time: (ts: number) => string;
  readonly count: (value: number) => string;
  readonly status: (status: number) => string;
  readonly httpStatus: (code: number) => string;
  /** A dictionary name, or "Unknown (#id)" when the index is not in the dictionary. */
  readonly name: (name: string | null, id: number) => string;
}

export function useXLogFormat(): XLogFormat {
  const { t, i18n } = useTranslation(['common', 'xlog']);
  const language = i18n.resolvedLanguage ?? 'en';
  return useMemo(() => {
    const axis = new Intl.DateTimeFormat(language, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    });
    const full = new Intl.DateTimeFormat(language, {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      fractionalSecondDigits: 3,
      hourCycle: 'h23',
    });
    const numbers = new Intl.NumberFormat(language);
    const duration = (ms: number, unit: DurationUnit = durationUnitFor(ms)): string =>
      formatDuration((key, values) => t(key, values), ms, unit);
    return {
      duration,
      axisTime: (ts) => axis.format(ts),
      time: (ts) => full.format(ts),
      count: (value) => numbers.format(value),
      status: (status) => {
        if (status === STATUS_ERROR) {
          return t('xlog:status.error');
        }
        return status === STATUS_SLOW ? t('xlog:status.slow') : t('xlog:status.normal');
      },
      httpStatus: (code) => (code === 0 ? t('xlog:summary.http.none') : String(code)),
      name: (name, id) => name ?? t('xlog:value.unknown', { id }),
    };
  }, [t, language]);
}
