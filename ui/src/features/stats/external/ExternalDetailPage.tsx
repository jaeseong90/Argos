// SPDX-License-Identifier: Apache-2.0
//
// External host detail /apps/:appId/stats/external/:hostId (docs/15 §9.7, 05 §2.5-4, WO-36): the
// trend of the range (calls and errors; average and p95, bucket estimates: C-14) with the event
// markers and outage shading, the calling services (count and share, a sample of profiled
// transactions in the raw window), the related transactions ("profiled transactions only") opening
// their detail with `ts` (C-206 (1)), and the XLog of the range and kind. The XLog has no host
// filter (docs/14 §4.6), so the link carries the range and kind only and the screen says so. No
// application map link: F-U11 (S) has no screen in Phase 1 (WO-70).
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { getExternalStats, type ExternalDetail } from '../../../api/stats';
import type { TimeRange as ChartRange } from '../../../charts/chartModel';
import { SafeText } from '../../../components/SafeText';
import { useAppId } from '../../../routes/navigation';
import { carriedState, CommonQ } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { BreakdownTable } from '../common/BreakdownTable';
import { statsKey, useEventMarkers, useStatsQuery, useStatsRange } from '../common/data';
import { DetailLinks, RawWindowNote, routeKey, SummaryItem } from '../common/DetailParts';
import { externalListHref, serviceHref, xlogAtRangeHref, xlogHref } from '../common/links';
import { SourceNote } from '../common/ListControls';
import { RelatedTxList } from '../common/RelatedTxList';
import { StatsScreen, type StatsScreenState } from '../common/StatsScreen';
import { TrendChart } from '../common/TrendChart';

/** Sum of a series (null buckets count as 0). */
function seriesTotal(values: readonly (number | null)[] | null | undefined): number | null {
  if (values === null || values === undefined) {
    return null;
  }
  return values.reduce<number>((sum, value) => sum + (value ?? 0), 0);
}

export function ExternalDetailPage() {
  const { t } = useTranslation(['common', 'stats']);
  const appId = useAppId();
  const params = useParams();
  const hostId = routeKey(params.hostId);
  const { state } = useUrlState(CommonQ);
  const time = useTimeRange(CommonQ);
  const dispatch = time.dispatch;
  const range = useStatsRange(state.time);
  const kind = state.kind;
  const enabled = appId !== null && hostId !== null;

  const query = useStatsQuery<ExternalDetail>(
    statsKey('external-detail', { appId, range, kind, extra: { hostId } }),
    (signal) => getExternalStats({ appId: appId ?? 0, range, kind, hostId: hostId ?? '' }, signal),
    enabled,
  );
  const markers = useEventMarkers(appId, range);
  const detail = query.data;
  const id = appId ?? 0;
  const carried = useMemo(() => carriedState(state), [state]);
  const env = state.env;
  // The XLog search has no host filter: range, kind and environment only.
  const xlogAt = useCallback(
    (window: ChartRange) => xlogAtRangeHref(appId ?? 0, window, { kind, env }, []),
    [appId, kind, env],
  );
  const onZoom = useCallback(
    (from: number, to: number) => {
      dispatch({ type: 'chartDrag', from, to });
    },
    [dispatch],
  );
  const calls = detail === null ? null : seriesTotal(detail.series.count);
  const errors = detail === null ? null : seriesTotal(detail.series.errors);
  const timeouts = detail === null ? null : seriesTotal(detail.series.timeouts);
  const connectFailures = detail === null ? null : seriesTotal(detail.series.connect_failures);

  let pageState: StatsScreenState;
  if (!enabled) {
    pageState = 'empty';
  } else if (query.status === 'ready' && detail !== null) {
    pageState = (calls ?? 0) === 0 ? 'empty' : 'ready';
  } else {
    pageState = query.status === 'error' ? 'error' : 'loading';
  }

  return (
    <StatsScreen
      testId="stats-external-detail"
      title={
        detail === null ? (
          t('stats:external.detailTitle')
        ) : (
          <SafeText value={detail.host} mono data-testid="stats-external-host" />
        )
      }
      state={pageState}
      range={range}
      error={query.error}
      onRetry={query.retry}
      onNarrowRange={() => {
        dispatch({ type: 'preset', range: '1h' });
      }}
      refreshing={query.refreshing}
      loadingVariant="chart"
      headerActions={
        <DetailLinks
          backHref={externalListHref(id, carried)}
          xlogHref={enabled ? xlogHref(id, carried, []) : null}
        />
      }
      empty={
        enabled
          ? {
              title: t('stats:external.detailEmptyTitle'),
              description: t('stats:state.emptyDescription'),
              onWidenRange: () => {
                dispatch({ type: 'zoomOut' });
              },
            }
          : {
              title: t('stats:state.invalidKeyTitle'),
              description: t('stats:state.invalidKeyDescription'),
            }
      }
    >
      {detail === null ? null : (
        <>
          <SourceNote meta={query.meta} />
          <p className="stats-note" data-testid="stats-external-xlog-note">
            {t('stats:external.xlogNote')}
          </p>
          <dl className="stats-summary" data-testid="stats-external-summary">
            <SummaryItem
              label={t('stats:external.calls')}
              value={calls}
              unit="count"
              data-testid="stats-external-count"
            />
            <SummaryItem
              label={t('stats:external.errors')}
              value={errors}
              unit="count"
              data-testid="stats-external-errors"
            />
            <SummaryItem
              label={t('stats:columns.timeouts')}
              value={timeouts}
              unit="count"
              data-testid="stats-external-timeouts"
            />
            <SummaryItem
              label={t('stats:columns.connect_failures')}
              value={connectFailures}
              unit="count"
              data-testid="stats-external-connect-failures"
            />
          </dl>
          <TrendChart
            series={detail.series}
            meta={query.meta}
            range={range}
            markers={markers}
            estimated={detail.estimated}
            volume={['count', 'errors', 'timeouts', 'connect_failures']}
            latency={['avg_ms', 'p95_ms']}
            xlogAt={xlogAt}
            onZoom={onZoom}
          />
          <div className="stats-section">
            <RawWindowNote window={detail.raw_window} />
            <BreakdownTable
              tableId="stats-by-service"
              title={t('stats:breakdown.byService')}
              nameHeader={t('stats:columns.service')}
              rows={detail.by_service}
              metrics={['count', 'share', 'avg_ms']}
              href={(row) => serviceHref(id, row.id, carried)}
              mono
              emptyText={t('stats:breakdown.rawEmpty')}
            />
          </div>
          <RelatedTxList
            appId={id}
            tableId="stats-related-tx"
            title={t('stats:related.title')}
            rows={detail.related_tx.items}
            profiledOnly
            emptyText={t('stats:related.empty')}
          />
        </>
      )}
    </StatsScreen>
  );
}
