// SPDX-License-Identifier: Apache-2.0
//
// Alert state label (docs/15 §6.4, T-UI-008). Korean labels follow docs/01 appendix B:
// OK / PENDING / FIRING / ACKED / RESOLVED / NO_DATA = 정상 / 대기 / 발생 / 확인 / 해제 / 데이터 없음.
// The text is always shown next to the dot.
import { useTranslation } from 'react-i18next';

import './badges.css';

export const ALERT_STATES = ['OK', 'PENDING', 'FIRING', 'ACKED', 'RESOLVED', 'NO_DATA'] as const;
export type AlertState = (typeof ALERT_STATES)[number];

export type AlertSeverity = 'warn' | 'critical';

/** Normalises an API alert state (any case, `no_data` or `nodata`); anything else is null. */
export function normalizeAlertState(value: string | null | undefined): AlertState | null {
  const upper = (value ?? '')
    .trim()
    .toUpperCase()
    .replace(/^NODATA$/, 'NO_DATA');
  return (ALERT_STATES as readonly string[]).includes(upper) ? (upper as AlertState) : null;
}

export interface AlertStateLabelProps {
  readonly state: string | null | undefined;
  /** Severity of a FIRING / PENDING alert: the dot takes the severity colour. */
  readonly severity?: AlertSeverity | null;
  readonly 'data-testid'?: string;
}

/** Dot plus the translated alert state. */
export function AlertStateLabel({ state, severity, 'data-testid': testId }: AlertStateLabelProps) {
  const { t } = useTranslation();
  const normalized = normalizeAlertState(state);
  const label =
    normalized === null
      ? t('component.status.unknown')
      : {
          OK: t('component.alertState.ok'),
          PENDING: t('component.alertState.pending'),
          FIRING: t('component.alertState.firing'),
          ACKED: t('component.alertState.acked'),
          RESOLVED: t('component.alertState.resolved'),
          NO_DATA: t('component.alertState.noData'),
        }[normalized];
  const classes = ['alert-state', `alert-state--${(normalized ?? 'unknown').toLowerCase()}`];
  if (
    severity !== undefined &&
    severity !== null &&
    (normalized === 'FIRING' || normalized === 'PENDING')
  ) {
    classes.push(`alert-state--sev-${severity}`);
  }
  return (
    <span
      className={classes.join(' ')}
      data-state={normalized ?? 'UNKNOWN'}
      data-testid={testId ?? 'alert-state'}
    >
      <span className="alert-state__dot" aria-hidden="true" />
      <span className="alert-state__label">{label}</span>
      {severity !== undefined && severity !== null ? (
        <span className="alert-state__severity">
          {severity === 'critical'
            ? t('component.severity.critical')
            : t('component.severity.warn')}
        </span>
      ) : null}
    </span>
  );
}
