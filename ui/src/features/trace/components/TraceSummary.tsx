// SPDX-License-Identifier: Apache-2.0
//
// The strip above the trace list (docs/15 §9.5, §1 "hide nothing"): the root transaction, start,
// duration and counts, where the critical path came from; the notices that make the trace partial
// (more than 500 transactions, raw retention exceeded, transactions not collected yet with "query
// again", caller steps shorter than their callees, D-05); and the legend (colour is never the only
// carrier: every swatch has its label, docs/15 §13).
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../../components/SafeText';
import { formatDateTimeWithOffset, formatDuration, formatNumber } from '../../../utils/format';
import type { ResponseMeta } from '../data/traceApi';
import { TRACE_NODE_LIMIT, traceTruncated } from '../data/traceModel';
import type { TraceTree } from '../model/traceTree';

export function TraceSummary({ tree }: { readonly tree: TraceTree }) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const root = tree.root;
  const missing = tree.counts.notYetCollected + tree.counts.notInstrumented;
  return (
    <dl className="trace-summary" data-testid="trace-summary">
      <div className="trace-summary__item trace-summary__item--wide">
        <dt>{t('trace:summary.root')}</dt>
        <dd data-testid="trace-summary-root" data-tx-id={root?.node.tx_id}>
          {root === null ? (
            t('trace:summary.noRoot')
          ) : (
            <>
              <SafeText value={root.node.app} />{' '}
              <span className="trace-env">
                <SafeText value={root.node.env} />
              </span>
              <span className="trace-sep" aria-hidden="true" />
              <SafeText value={root.node.service} />
            </>
          )}
        </dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.start')}</dt>
        <dd data-testid="trace-summary-start">{formatDateTimeWithOffset(tree.originMs, locale)}</dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.duration')}</dt>
        <dd data-testid="trace-summary-duration" data-ms={tree.totalMs}>
          {formatDuration(tree.totalMs, locale)}
        </dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.transactions')}</dt>
        <dd data-testid="trace-summary-transactions">
          {formatNumber(tree.counts.transactions, locale)}
        </dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.apps')}</dt>
        <dd data-testid="trace-summary-apps">{formatNumber(tree.counts.apps, locale)}</dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.external')}</dt>
        <dd data-testid="trace-summary-external">{formatNumber(tree.counts.external, locale)}</dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.missing')}</dt>
        <dd data-testid="trace-summary-missing">{formatNumber(missing, locale)}</dd>
      </div>
      <div className="trace-summary__item">
        <dt>{t('trace:summary.criticalPath')}</dt>
        <dd data-testid="trace-critical-source" data-source={tree.criticalSource}>
          {tree.criticalSource === 'server'
            ? t('trace:summary.criticalServer')
            : tree.criticalSource === 'client'
              ? t('trace:summary.criticalClient')
              : t('trace:summary.criticalNone')}
        </dd>
      </div>
    </dl>
  );
}

export interface TraceNoticesProps {
  readonly tree: TraceTree;
  readonly meta: ResponseMeta;
  readonly onQueryAgain: () => void;
  readonly fetching: boolean;
}

/** Partial-result notices of the trace; renders nothing for a complete trace. */
export function TraceNotices({ tree, meta, onQueryAgain, fetching }: TraceNoticesProps) {
  const { t } = useTranslation(['common', 'trace']);
  const truncated = traceTruncated(meta);
  const retention = meta.retention_exceeded === true;
  const notYet = tree.counts.notYetCollected;
  const offsets = tree.counts.clockOffsets;
  if (!truncated && !retention && notYet === 0 && offsets === 0) {
    return null;
  }
  return (
    <div className="partial-banner trace-notices" role="status" data-testid="trace-partial-banner">
      <ul className="partial-banner__list">
        {truncated ? (
          <li data-testid="trace-partial-truncated">
            {t('trace:partial.truncated', { limit: TRACE_NODE_LIMIT })}
          </li>
        ) : null}
        {retention ? (
          <li data-testid="trace-partial-retention">
            {t('common:component.partial.retentionExceeded')}
          </li>
        ) : null}
        {notYet > 0 ? (
          <li data-testid="trace-partial-not-collected">
            {t('trace:partial.notYetCollected', { count: notYet })}{' '}
            <button
              type="button"
              className="trace-inline-button"
              disabled={fetching}
              onClick={onQueryAgain}
              data-testid="trace-partial-query-again"
            >
              {t('trace:row.queryAgain')}
            </button>
          </li>
        ) : null}
        {offsets > 0 ? (
          <li data-testid="trace-partial-clock-offset">
            {t('trace:partial.clockOffset', { count: offsets })}
          </li>
        ) : null}
      </ul>
    </div>
  );
}

/** The legend of the trace timeline. */
export function TraceLegend() {
  const { t } = useTranslation(['common', 'trace']);
  return (
    <div className="trace-legend" data-testid="trace-legend">
      <span className="trace-legend__title">{t('trace:legend.label')}</span>
      <ul className="trace-legend__list">
        <li>
          <span className="trace-swatch trace-swatch--tx" aria-hidden="true" />
          {t('trace:legend.transaction')}
        </li>
        <li>
          <span className="trace-swatch trace-swatch--call" aria-hidden="true" />
          {t('trace:legend.call')}
        </li>
        <li>
          <span className="trace-swatch trace-swatch--wait" aria-hidden="true" />
          {t('trace:legend.wait')}
        </li>
        <li>
          <span className="trace-swatch trace-swatch--external" aria-hidden="true" />
          {t('trace:legend.external')}
        </li>
        <li>
          <span className="trace-swatch trace-swatch--critical" aria-hidden="true" />
          {t('trace:legend.critical')}
        </li>
        <li>
          <span className="trace-swatch trace-swatch--failed" aria-hidden="true" />
          {t('trace:legend.failed')}
        </li>
      </ul>
      <p className="trace-legend__note">{t('trace:legend.estimated')}</p>
    </div>
  );
}
