// SPDX-License-Identifier: Apache-2.0
//
// "Firing now" panel of the dashboard (05 §2.3-5, docs/15 §9.2): the alerts firing for this
// application and environment, each opening that alert in the history, and a link to the alert
// history of the app and environment over the same range (WO-38). The rows are the FIRING and
// ACKED alerts the alert engine reports for the app and environment (`data.firing[]`, WO-25,
// ADR-0010 C-212 (2)). `firing === null` (a server without the alert engine) says "not available",
// never "no alerts" (T-UI-007 null ≠ empty). Each row is the shared AlertSummaryRow with the value
// and threshold in the rule's unit (ADR-0010 C-227 (4)); rule and target names render as text.
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { AlertSummaryRow } from '../../../components/AlertSummaryRow';
import { AlertValue } from '../../../components/AlertValue';

import { FIRING_ROWS_MAX, firingRows } from './sectionsModel';

export interface FiringPanelProps {
  /** `firing` of the dashboard response: null = a server without the alert engine. */
  readonly firing: readonly unknown[] | null;
  /** The alert history link (app and environment, optionally one alert event). */
  readonly historyHref: (alertId: string | null) => string;
}

export function FiringPanel({ firing, historyHref }: FiringPanelProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  const parsed = firing === null ? null : firingRows(firing);
  const state =
    parsed === null
      ? 'unavailable'
      : parsed.rows.length === 0 && parsed.invalid === 0
        ? 'empty'
        : 'list';
  const shown = parsed?.rows.slice(0, FIRING_ROWS_MAX) ?? [];
  const more = parsed === null ? 0 : parsed.rows.length - shown.length + parsed.invalid;

  return (
    <section
      className="dashboard-panel dashboard-firing"
      aria-labelledby="dashboard-firing-title"
      data-testid="dashboard-firing-panel"
      data-state={state}
      data-count={parsed === null ? 'null' : parsed.rows.length + parsed.invalid}
    >
      <h2 id="dashboard-firing-title" className="dashboard-sections__title">
        {parsed === null || state === 'empty'
          ? t('dashboard:sections.firing')
          : t('dashboard:firing.titleCount', { count: parsed.rows.length + parsed.invalid })}
      </h2>
      {state === 'unavailable' ? (
        <p className="dashboard-sections__note" data-testid="dashboard-firing-unavailable">
          {t('dashboard:firing.unavailable')}
        </p>
      ) : state === 'empty' ? (
        <p className="dashboard-sections__note" data-testid="dashboard-firing-empty">
          {t('dashboard:firing.none')}
        </p>
      ) : (
        <ul className="dashboard-firing__list" data-testid="dashboard-firing-list">
          {shown.map((row, index) => (
            <li
              key={row.id ?? `row-${String(index)}`}
              className="dashboard-firing__row"
              data-testid="dashboard-firing-row"
              data-alert-id={row.id ?? undefined}
              data-severity={row.severity ?? undefined}
            >
              <AlertSummaryRow
                href={historyHref(row.id)}
                alert={row}
                sinceFormat="dateTime"
                // Value and threshold with the rule's unit (ADR-0010 C-227 (4)); a row without
                // either shows no value part.
                value={
                  row.value === null && row.threshold === null ? null : (
                    <AlertValue value={row.value} threshold={row.threshold} unit={row.unit} />
                  )
                }
                ruleTestId="dashboard-firing-rule"
                targetTestId="dashboard-firing-target"
              />
            </li>
          ))}
          {more > 0 ? (
            <li className="dashboard-sections__note" data-testid="dashboard-firing-more">
              {t('dashboard:firing.more', { count: more })}
            </li>
          ) : null}
        </ul>
      )}
      <Link
        to={historyHref(null)}
        className="dashboard-panel__link"
        data-testid="dashboard-firing-history"
      >
        {t('dashboard:firing.history')}
      </Link>
    </section>
  );
}
