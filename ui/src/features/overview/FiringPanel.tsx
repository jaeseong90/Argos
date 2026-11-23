// SPDX-License-Identifier: Apache-2.0
//
// All-application firing alerts panel (05 §2.10 "전 앱 발생 중 알림 요약 패널", docs/15 §9.1).
// It lists the active alerts of the alert engine (WO-25, ADR-0010 C-212 (2)): `GET /overview/alerts`
// without a state filter, FIRING and ACKED (critical first), the same list `alerts_firing_total`
// counts, with the counts by severity. `alerts_firing_total === null` (a server without the alert
// engine) says "not available", never "no alerts", and requests nothing (T-UI-007 null ≠ empty). The panel links to the alert history filtered to firing alerts (`/alerts?state=
// firing`, global keys carried; an ACKED row opens `state=acked`); each row is the shared
// AlertSummaryRow (ADR-0010 C-227 (4): the rule name keeps its width), names through SafeText.
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { errorInfo } from '../../api/errors';
import { useFiringAlertsQuery } from '../../api/overview';
import { AlertSummaryRow } from '../../components/AlertSummaryRow';
import { ErrorState } from '../../components/ErrorState';

import { FIRING_PANEL_ROWS_MAX, firingAlerts } from './firingAlerts';

export interface FiringPanelProps {
  /** `alerts_firing_total` of the overview: null = a server without the alert engine. */
  readonly total: number | null;
  readonly env: string | undefined;
  /**
   * The alert history link, optionally to one alert event and filtered to its state (an ACKED
   * row opens the acknowledged alerts, ADR-0010 C-226 (8)).
   */
  readonly historyHref: (alertId: string | null, state?: string | null) => string;
}

export function FiringPanel({ total, env, historyHref }: FiringPanelProps) {
  const { t } = useTranslation(['common', 'overview']);
  const available = total !== null;
  const query = useFiringAlertsQuery(env, available);
  const parsed = available && query.data !== undefined ? firingAlerts(query.data) : null;
  const state = !available
    ? 'unavailable'
    : parsed === null
      ? query.isError
        ? 'error'
        : 'loading'
      : parsed.rows.length + parsed.invalid === 0
        ? 'empty'
        : 'list';
  const shown = parsed?.rows.slice(0, FIRING_PANEL_ROWS_MAX) ?? [];
  const more = parsed === null ? 0 : parsed.rows.length - shown.length + parsed.invalid;

  return (
    <section
      className="overview-panel overview-firing-panel"
      aria-labelledby="overview-firing-title"
      data-testid="overview-firing-panel"
      data-state={state}
      data-total={total === null ? 'null' : String(total)}
    >
      <h2 id="overview-firing-title" className="overview-panel__title">
        {t('overview:panel.firing.title')}
      </h2>
      {state === 'unavailable' ? (
        <p className="overview-panel__note" data-testid="overview-firing-unavailable">
          {t('overview:panel.firing.unavailable')}
        </p>
      ) : state === 'loading' ? (
        <p className="overview-panel__note">{t('overview:panel.firing.loading')}</p>
      ) : state === 'error' ? (
        <ErrorState
          compact
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="overview-firing-error"
        />
      ) : state === 'empty' ? (
        <p className="overview-panel__note" data-testid="overview-firing-empty">
          {t('overview:panel.firing.none')}
        </p>
      ) : (
        <>
          <p className="overview-panel__summary" data-testid="overview-firing-summary">
            {t('overview:panel.firing.summary', {
              critical: parsed?.critical ?? 0,
              warn: parsed?.warn ?? 0,
            })}
          </p>
          <ul className="overview-firing-panel__list" data-testid="overview-firing-list">
            {shown.map((row, index) => (
              <li
                key={row.id ?? `row-${String(index)}`}
                className="overview-firing-panel__row"
                data-testid="overview-firing-row"
                data-alert-id={row.id ?? undefined}
                data-severity={row.severity ?? undefined}
              >
                <AlertSummaryRow href={historyHref(row.id, row.state)} alert={row} />
              </li>
            ))}
            {more > 0 ? (
              <li className="overview-panel__note" data-testid="overview-firing-more">
                {t('overview:panel.firing.more', { count: more })}
              </li>
            ) : null}
          </ul>
        </>
      )}
      <Link
        to={historyHref(null)}
        className="overview-panel__link"
        data-testid="overview-firing-history"
      >
        {t('overview:panel.firing.all')}
      </Link>
    </section>
  );
}
