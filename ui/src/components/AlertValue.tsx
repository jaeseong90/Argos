// SPDX-License-Identifier: Apache-2.0
//
// Current value and threshold of an alert with the unit of its rule's metric (ADR-0010 C-227 (4),
// 05 §2.9-2 "현재값/임계치(단위 포함)"). The unit is the server's `rule.unit` (AlertMetric.Unit in
// wire form): ratio as a percentage, ms and seconds/days as durations, per_second as a rate,
// score and count as numbers, state as UP/DOWN (1 = DOWN). A missing value or threshold shows the
// dash with the "No data" tooltip, never 0 (T-UI-007). Unknown units fall back to a plain number.
import { useTranslation } from 'react-i18next';

import {
  formatCount,
  formatDuration,
  formatNumber,
  formatPercent,
  formatRate,
  type ValueUnit,
} from '../utils/format';
import { MISSING_DASH } from '../utils/missing';

import './alerts.css';

/** Units of alert metrics (server AlertMetric.Unit, lower case). */
export const ALERT_UNITS = [
  'ms',
  'seconds',
  'days',
  'ratio',
  'score',
  'count',
  'per_second',
  'state',
] as const;
export type AlertUnit = (typeof ALERT_UNITS)[number];

const DAY_MS = 86_400_000;

/** The alert unit of an API value, or null when it is missing or unknown. */
export function normalizeAlertUnit(value: unknown): AlertUnit | null {
  return typeof value === 'string' && (ALERT_UNITS as readonly string[]).includes(value)
    ? (value as AlertUnit)
    : null;
}

/** The shared chart unit closest to an alert unit (charts of the rule preview). */
export function chartUnitOf(unit: AlertUnit | null): ValueUnit {
  switch (unit) {
    case 'ms':
      return 'ms';
    case 'seconds':
      return 'seconds';
    case 'ratio':
      return 'ratio';
    case 'per_second':
      return 'rate';
    case 'count':
      return 'count';
    case 'days':
    case 'score':
    case 'state':
    case null:
      return 'none';
  }
}

/**
 * Formats a number of an alert unit. `state` values are left to the caller (UP/DOWN are
 * translated texts); here they format as plain numbers.
 */
export function formatAlertNumber(value: number, unit: AlertUnit | null, locale: string): string {
  switch (unit) {
    case 'ms':
      return formatDuration(value, locale);
    case 'seconds':
      return formatDuration(value * 1000, locale);
    case 'days':
      return formatDuration(value * DAY_MS, locale);
    case 'ratio':
      return formatPercent(value, locale);
    case 'score':
      return formatNumber(value, locale, 2);
    case 'count':
      return formatCount(value, locale);
    case 'per_second':
      return formatRate(value, locale);
    case 'state':
    case null:
      return formatNumber(value, locale);
  }
}

/** Formatter of alert values in the UI language (state values as UP/DOWN). */
export function useAlertValueFormat(): (
  value: number | null | undefined,
  unit: AlertUnit | null,
) => string {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  return (value, unit) => {
    if (value === null || value === undefined || !Number.isFinite(value)) {
      return MISSING_DASH;
    }
    if (unit === 'state') {
      return value >= 1 ? t('component.alertValue.down') : t('component.alertValue.up');
    }
    return formatAlertNumber(value, unit, locale);
  };
}

export interface AlertValueProps {
  readonly value: number | null | undefined;
  readonly threshold: number | null | undefined;
  /** `rule.unit` of the alert (any string; unknown units format as plain numbers). */
  readonly unit: string | null | undefined;
  readonly className?: string;
  readonly 'data-testid'?: string;
}

/** "value / threshold" with the rule's unit; `data-value`, `data-threshold`, `data-unit` raw. */
export function AlertValue({
  value,
  threshold,
  unit,
  className,
  'data-testid': testId = 'alert-value',
}: AlertValueProps) {
  const { t } = useTranslation();
  const format = useAlertValueFormat();
  const normalized = normalizeAlertUnit(unit);
  const missing = (v: number | null | undefined): boolean =>
    v === null || v === undefined || !Number.isFinite(v);
  const part = (v: number | null | undefined, role: 'value' | 'threshold') => (
    <span
      className={`alert-value__${role}${missing(v) ? ' alert-value--missing' : ''}`}
      title={missing(v) ? t('component.missing.noData') : undefined}
    >
      {format(v, normalized)}
    </span>
  );
  const classes = ['alert-value'];
  if (className !== undefined) {
    classes.push(className);
  }
  return (
    <span
      className={classes.join(' ')}
      data-testid={testId}
      data-value={missing(value) ? 'null' : String(value)}
      data-threshold={missing(threshold) ? 'null' : String(threshold)}
      data-unit={normalized ?? 'none'}
    >
      {part(value, 'value')}
      {normalized === 'state' ? null : (
        <>
          <span className="alert-value__sep" aria-hidden="true">
            {' / '}
          </span>
          {part(threshold, 'threshold')}
        </>
      )}
    </span>
  );
}
