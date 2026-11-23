// SPDX-License-Identifier: Apache-2.0
//
// Collection status panel of the dashboard (05 §2.3-6, D-06, D-18, D-19, F-S10, docs/15 §9.2
// bottom line): agent drops, server drops (with the quota share), ingest lag, instances with a
// clock skew warning, excluded requests (the reasons from /collection-health as a tooltip, 05
// §2.0-8; the ranking screen with its link comes with WO-39), late arrivals and running batches.
// A value without a source is null and shows the missing dash ("No data"), never 0 (T-UI-007);
// each item keeps its raw value in `data-value` ("null" when missing) for the tests.
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { DashboardCollection, ExcludedReasons } from '../../../api/dashboard';
import { Hint } from '../../../components/Hint';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import { useFormat } from '../../../i18n/format';

import { collectionItems, type CollectionItemId } from './sectionsModel';

export interface IngestStatusPanelProps {
  readonly collection: DashboardCollection;
  /** Excluded requests by reason (tooltip), null while not loaded or unavailable. */
  readonly excludedReasons: ExcludedReasons | null;
  readonly excludedReasonsPending?: boolean;
}

const LABEL_KEYS = {
  'agent-drops': 'dashboard:collection.agentDrops',
  'server-drops': 'dashboard:collection.serverDrops',
  'ingest-lag': 'dashboard:collection.ingestLag',
  'clock-skew': 'dashboard:collection.clockSkew',
  excluded: 'dashboard:collection.excluded',
  late: 'dashboard:collection.late',
  'batch-running': 'dashboard:collection.batchRunning',
} as const satisfies Readonly<Record<CollectionItemId, string>>;

export function IngestStatusPanel({
  collection,
  excludedReasons,
  excludedReasonsPending = false,
}: IngestStatusPanelProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  const format = useFormat();
  const items = collectionItems(collection);

  const reasonsTooltip = (): ReactNode => {
    if (excludedReasonsPending) {
      return t('dashboard:collection.reasonsLoading');
    }
    if (excludedReasons === null || excludedReasons.byReason.length === 0) {
      return t('dashboard:collection.reasonsUnavailable');
    }
    return (
      <span className="dashboard-collection__reasons" data-testid="dashboard-collection-reasons">
        <span>{t('dashboard:collection.reasonsTitle')}</span>
        {excludedReasons.byReason.map(([reason, count]) => (
          <span key={reason} className="dashboard-collection__reason">
            <SafeText value={reason} mono showTitle={false} />
            <span>{format.number(count)}</span>
          </span>
        ))}
      </span>
    );
  };

  return (
    <section
      className="dashboard-panel dashboard-collection"
      aria-labelledby="dashboard-collection-title"
      data-testid="dashboard-collection-panel"
    >
      <h2 id="dashboard-collection-title" className="dashboard-sections__title">
        {t('dashboard:sections.collection')}
      </h2>
      <dl className="dashboard-collection__items">
        {items.map((item) => {
          const value = <ValueText value={item.value} unit={item.unit} />;
          let content: ReactNode = value;
          if (item.id === 'server-drops' && item.value !== null) {
            // The quota share is shown only when the server measures it (no dash next to a value).
            const quota = collection.server_drops_quota;
            content =
              typeof quota === 'number' && Number.isFinite(quota) ? (
                <>
                  {value}{' '}
                  <span
                    className="dashboard-collection__sub"
                    data-testid="dashboard-collection-server-drops-quota"
                    data-value={quota}
                  >
                    {t('dashboard:collection.quota', { count: quota })}
                  </span>
                </>
              ) : (
                value
              );
          } else if (item.id === 'excluded' && item.value !== null && item.value > 0) {
            content = (
              <Hint title={reasonsTooltip()}>
                <button
                  type="button"
                  className="dashboard-collection__reasons-button"
                  aria-label={t('dashboard:collection.reasonsLabel', { count: item.value })}
                  data-testid="dashboard-collection-excluded-reasons"
                >
                  {value}
                </button>
              </Hint>
            );
          }
          return (
            <div
              key={item.id}
              className={`dashboard-collection__item${item.warn ? ' dashboard-collection__item--warn' : ''}`}
              data-testid={`dashboard-collection-${item.id}`}
              data-value={item.value ?? 'null'}
              data-warn={item.warn ? 'true' : undefined}
            >
              <dt className="dashboard-collection__label">{t(LABEL_KEYS[item.id])}</dt>
              <dd className="dashboard-collection__value">
                {content}
                {item.warn ? (
                  // Not by colour alone (docs/15 §13): the warning is said in text too.
                  <span className="visually-hidden">{t('dashboard:collection.warning')}</span>
                ) : null}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}
