// SPDX-License-Identifier: Apache-2.0
//
// The tooltip of a hovered or focused trace row (docs/15 §8.4: time, share, details): the full
// node label, the time with its share of the trace and the offset from the trace start, the D-07
// reason of a profile that was not captured, and for a caller step both durations with the
// network / wait part or the clock-offset note (D-05). Collected strings through SafeText.
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../../components/SafeText';
import { shareOf } from '../../../components/waterfall';
import { formatDuration, formatPercent } from '../../../utils/format';
import { rowInterval, type TraceRow, type TraceTree } from '../model/traceTree';

import { MissingLabel, useProfileText } from './labels';

export function TraceRowTooltip({
  row,
  tree,
}: {
  readonly row: TraceRow;
  readonly tree: TraceTree;
}) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const profileText = useProfileText();
  const interval = rowInterval(row);
  const timing =
    interval === null ? null : (
      <>
        <div className="trace-tip__line">
          {t('trace:tip.timing', {
            elapsed: formatDuration(interval.elapsedMs, locale),
            share: formatPercent(shareOf(interval.elapsedMs, tree.totalMs), locale),
            offset: formatDuration(interval.startMs - tree.originMs, locale),
          })}
        </div>
        {interval.estimated ? (
          <div className="trace-tip__line">{t('trace:node.estimated')}</div>
        ) : null}
      </>
    );
  switch (row.kind) {
    case 'tx': {
      const node = row.item.node;
      return (
        <div className="trace-tip" data-testid="trace-tooltip">
          <div className="trace-tip__title">
            <SafeText value={node.app} showTitle={false} />{' '}
            <span className="trace-env">
              <SafeText value={node.env} showTitle={false} />
            </span>
            <span className="trace-sep" aria-hidden="true" />
            <SafeText value={node.service} showTitle={false} maxLines={2} />
          </div>
          <div className="trace-tip__line">
            <SafeText value={node.instance} showTitle={false} />
            <span className="trace-sep" aria-hidden="true" />
            <SafeText value={node.version} showTitle={false} />
            <span className="trace-sep" aria-hidden="true" />
            {t('trace:tip.status', { status: String(node.status) })}
            {node.error ? (
              <>
                <span className="trace-sep" aria-hidden="true" />
                {t('trace:row.error')}
              </>
            ) : null}
          </div>
          {timing}
          <div className="trace-tip__line">
            {t('trace:tip.profile', { value: profileText(row.item.profileState) })}
          </div>
        </div>
      );
    }
    case 'call': {
      const { gap } = row;
      return (
        <div className="trace-tip" data-testid="trace-tooltip">
          <div className="trace-tip__line">
            {t('trace:tip.call', {
              caller: formatDuration(gap.callerStepMs, locale),
              callee: formatDuration(gap.calleeMs, locale),
            })}
          </div>
          <div className="trace-tip__line">
            {gap.waitMs === null
              ? t('trace:node.clockOffset')
              : t('trace:tip.wait', {
                  value: formatDuration(gap.waitMs, locale),
                  source:
                    gap.waitSource === 'server'
                      ? t('trace:node.waitServer')
                      : t('trace:node.waitComputed'),
                })}
          </div>
          {timing}
        </div>
      );
    }
    case 'external': {
      const external = row.item.external;
      return (
        <div className="trace-tip" data-testid="trace-tooltip">
          <div className="trace-tip__title">
            <SafeText value={external.host} mono showTitle={false} />{' '}
            <SafeText value={external.path_pattern} mono showTitle={false} maxLines={2} />
          </div>
          <div className="trace-tip__line">
            {t('trace:tip.status', {
              status:
                external.status === null ? t('trace:row.noResponse') : String(external.status),
            })}
            {external.error ? (
              <>
                <span className="trace-sep" aria-hidden="true" />
                {t('trace:row.error')}
              </>
            ) : null}
          </div>
          {timing}
        </div>
      );
    }
    case 'missing':
      return (
        <div className="trace-tip" data-testid="trace-tooltip">
          <div className="trace-tip__line">
            <MissingLabel row={row} />
          </div>
        </div>
      );
  }
}
