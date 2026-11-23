// SPDX-License-Identifier: Apache-2.0
//
// Top summary of the active transactions screen (05 §2.6-5, docs/15 §9.6 SummaryBar): every
// running transaction of the application, those in the top elapsed band (default 8 s and more),
// the busiest instance, and the link to the alert rules of the `active_transactions` metric
// (docs/12; the rule screen itself is WO-38).
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { ActiveInstance } from '../../api/active';
import { untrusted } from '../../api/untrusted';
import { SafeText } from '../../components/SafeText';
import { useFormat } from '../../i18n/format';
import { useCan } from '../../state/permissions';
import {
  carriedState,
  CommonQ,
  defaultState,
  writeQuery,
  type CommonState,
} from '../../state/urlState';
import { MISSING_DASH } from '../../utils/missing';

import { bandLabel } from './bands';
import type { ActiveSummary as Summary, Thresholds } from './model';

/** The alert rules of the active transactions metric for this application. */
export function ruleHref(appId: number, current: Partial<CommonState>): string {
  const query = writeQuery(CommonQ, {
    ...defaultState(CommonQ),
    ...carriedState(current),
    filters: [
      { field: 'metric', op: 'eq', value: untrusted('active_transactions') },
      { field: 'app_id', op: 'eq', value: untrusted(String(appId)) },
    ],
  });
  return `/alerts/rules?${query}`;
}

export interface ActiveSummaryProps {
  readonly appId: number;
  readonly summary: Summary;
  readonly instances: ReadonlyMap<number, ActiveInstance>;
  readonly thresholds: Thresholds;
  readonly current: Partial<CommonState>;
}

export function ActiveSummary({
  appId,
  summary,
  instances,
  thresholds,
  current,
}: ActiveSummaryProps) {
  const { t } = useTranslation(['common', 'active']);
  const format = useFormat();
  const canEditRules = useCan('alerts.rules.edit');
  const max = summary.max;
  const maxInstance = max === null ? undefined : instances.get(max.instanceId);
  return (
    <div className="active-summary" data-testid="active-summary">
      <span className="active-summary__item">
        <span className="active-summary__label">{t('active:summary.total')}</span>
        <strong data-value={summary.total} data-testid="active-summary-total">
          {format.number(summary.total)}
        </strong>
      </span>
      <span className="active-summary__item">
        <span className="active-summary__label">
          <span className="active-swatch active-swatch--band-3" aria-hidden="true" />
          {bandLabel(3, thresholds, t, format)}
        </span>
        <strong
          data-value={summary.slow}
          data-approx={String(summary.slowApproximate)}
          data-testid="active-summary-slow"
          title={summary.slowApproximate ? t('active:summary.slowApproximate') : undefined}
        >
          {summary.slowApproximate ? '≈' : ''}
          {format.number(summary.slow)}
        </strong>
      </span>
      <span className="active-summary__item">
        <span className="active-summary__label">{t('active:summary.max')}</span>
        <strong
          data-value={max === null ? undefined : max.count}
          data-instance={max === null ? undefined : max.instanceId}
          data-testid="active-summary-max"
        >
          {max === null ? MISSING_DASH : format.number(max.count)}
        </strong>
        {max === null ? null : maxInstance?.name === null || maxInstance === undefined ? (
          <span className="active-muted">{t('active:bars.unnamed', { id: max.instanceId })}</span>
        ) : (
          <SafeText
            value={maxInstance.name}
            className="active-summary__name"
            data-testid="active-summary-max-name"
          />
        )}
      </span>
      {canEditRules ? (
        <Link
          to={ruleHref(appId, current)}
          className="active-summary__link"
          data-testid="active-rule-link"
        >
          {t('active:summary.ruleLink')}
        </Link>
      ) : (
        <span
          className="active-summary__link active-summary__link--disabled"
          aria-disabled="true"
          title={t('active:summary.ruleLinkNoPermission')}
          data-testid="active-rule-link"
        >
          {t('active:summary.ruleLink')}
          <span className="visually-hidden">{t('active:summary.ruleLinkNoPermission')}</span>
        </span>
      )}
    </div>
  );
}
