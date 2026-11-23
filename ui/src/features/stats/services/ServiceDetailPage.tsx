// SPDX-License-Identifier: Apache-2.0
//
// Service detail /apps/:appId/stats/services/:serviceId (docs/15 §9.7, 05 §2.5-2, WO-36): the
// trend of the range (count and error rate; average, p95 and p99) with the event markers (deploy
// markers among them) and outage shading, the per-version table with p95 to compare releases
// (T-ACPT-04), the instance distribution (raw window), the cross view (the service's SQL, external
// calls and error groups Top N, each row opening its detail; `tab=`), and the related transactions
// in the XLog with `f.service`. No baseline series: services/{id} has none and 05 §2.5 does not
// ask for one (WO-36 plan, PM question 3).
import { useCallback, useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router';

import { getServiceStats, type ServiceDetail, type StatsBreakdown } from '../../../api/stats';
import { untrusted } from '../../../api/untrusted';
import type { TimeRange as ChartRange } from '../../../charts/chartModel';
import { SafeText } from '../../../components/SafeText';
import { useAppId } from '../../../routes/navigation';
import {
  carriedState,
  SERVICE_DETAIL_TABS,
  ServiceDetailQ,
  type Filter,
} from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { BreakdownTable } from '../common/BreakdownTable';
import { statsKey, useEventMarkers, useStatsQuery, useStatsRange } from '../common/data';
import { DetailLinks, RawWindowNote, routeKey } from '../common/DetailParts';
import {
  errorGroupHref,
  externalHref,
  servicesHref,
  sqlHref,
  xlogAtRangeHref,
  xlogHref,
} from '../common/links';
import { SourceNote } from '../common/ListControls';
import { xlogFilters } from '../common/model';
import { StatsScreen, type StatsScreenState } from '../common/StatsScreen';
import { TrendChart } from '../common/TrendChart';

type CrossTab = (typeof SERVICE_DETAIL_TABS)[number];

/** Total calls of the series (0: nothing in the range). */
function totalCount(detail: ServiceDetail): number {
  return detail.series.count.reduce<number>((sum, value) => sum + (value ?? 0), 0);
}

export function ServiceDetailPage() {
  const { t } = useTranslation(['common', 'stats']);
  const appId = useAppId();
  const params = useParams();
  const serviceId = routeKey(params.serviceId);
  const { state, set } = useUrlState(ServiceDetailQ);
  const time = useTimeRange(ServiceDetailQ);
  const dispatch = time.dispatch;
  const range = useStatsRange(state.time);
  const kind = state.kind;
  const enabled = appId !== null && serviceId !== null;

  const query = useStatsQuery<ServiceDetail>(
    statsKey('service', { appId, range, kind, extra: { serviceId } }),
    (signal) =>
      getServiceStats({ appId: appId ?? 0, range, kind, serviceId: serviceId ?? '' }, signal),
    enabled,
  );
  const markers = useEventMarkers(appId, range);
  const detail = query.data;
  const carried = useMemo(() => carriedState(state), [state]);
  const ownFilter = useMemo<Filter[]>(
    () => (serviceId === null ? [] : [{ field: 'service', op: 'eq', value: untrusted(serviceId) }]),
    [serviceId],
  );
  const filters = useMemo(() => xlogFilters(ownFilter, state.filters), [ownFilter, state.filters]);
  const env = state.env;
  const xlogAt = useCallback(
    (window: ChartRange) => xlogAtRangeHref(appId ?? 0, window, { kind, env }, filters),
    [appId, kind, env, filters],
  );
  const onZoom = useCallback(
    (from: number, to: number) => {
      dispatch({ type: 'chartDrag', from, to });
    },
    [dispatch],
  );
  const id = appId ?? 0;
  const versionHref = useCallback(
    (row: StatsBreakdown) =>
      xlogHref(id, carried, [...filters, { field: 'version', op: 'eq', value: untrusted(row.id) }]),
    [id, carried, filters],
  );

  let pageState: StatsScreenState;
  if (!enabled) {
    pageState = 'empty';
  } else if (query.status === 'ready' && detail !== null) {
    pageState = totalCount(detail) === 0 ? 'empty' : 'ready';
  } else {
    pageState = query.status === 'error' ? 'error' : 'loading';
  }

  const tab: CrossTab = state.tab;
  let crossView: ReactNode = null;
  if (detail !== null) {
    switch (tab) {
      case 'sql':
        crossView = (
          <BreakdownTable
            tableId="stats-top-sql"
            title={t('stats:services.cross.sql')}
            nameHeader={t('stats:columns.sql')}
            rows={detail.top_sql}
            href={(row) => sqlHref(id, row.id, carried)}
            mono
            emptyText={t('stats:services.cross.sqlEmpty')}
          />
        );
        break;
      case 'external':
        crossView = (
          <BreakdownTable
            tableId="stats-top-external"
            title={t('stats:services.cross.external')}
            nameHeader={t('stats:columns.host')}
            rows={detail.top_external}
            href={(row) => externalHref(id, row.id, carried)}
            mono
            emptyText={t('stats:services.cross.externalEmpty')}
          />
        );
        break;
      case 'errors':
        crossView = (
          <BreakdownTable
            tableId="stats-top-errors"
            title={t('stats:services.cross.errors')}
            nameHeader={t('stats:columns.errorGroup')}
            rows={detail.top_error_groups}
            metrics={['count', 'share']}
            href={(row) => errorGroupHref(id, row.id, carried)}
            mono
            emptyText={t('stats:services.cross.errorsEmpty')}
          />
        );
        break;
    }
  }
  const tabLabel = (value: CrossTab): string =>
    value === 'sql'
      ? t('stats:services.cross.sqlTab')
      : value === 'external'
        ? t('stats:services.cross.externalTab')
        : t('stats:services.cross.errorsTab');

  return (
    <StatsScreen
      testId="stats-service-detail"
      title={
        detail === null ? (
          t('stats:services.detailTitle')
        ) : (
          <SafeText value={detail.service} mono data-testid="stats-service-name" />
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
          backHref={servicesHref(id, carried)}
          xlogHref={enabled ? xlogHref(id, carried, filters) : null}
        />
      }
      empty={
        enabled
          ? {
              title: t('stats:services.detailEmptyTitle'),
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
          <TrendChart
            series={detail.series}
            meta={query.meta}
            range={range}
            markers={markers}
            estimated={false}
            volume={['count', 'error_rate']}
            latency={['avg_ms', 'p95_ms', 'p99_ms']}
            xlogAt={xlogAt}
            onZoom={onZoom}
          />
          <div className="stats-grid">
            <BreakdownTable
              tableId="stats-by-version"
              title={t('stats:breakdown.byVersion')}
              nameHeader={t('stats:columns.version')}
              rows={detail.by_version}
              href={versionHref}
              mono
              emptyText={t('stats:breakdown.empty')}
            />
            <div className="stats-section">
              <RawWindowNote window={detail.raw_window} />
              <BreakdownTable
                tableId="stats-by-instance"
                title={t('stats:breakdown.byInstance')}
                nameHeader={t('stats:columns.instance')}
                rows={detail.by_instance}
                emptyText={t('stats:breakdown.rawEmpty')}
              />
            </div>
          </div>
          <section className="stats-section" aria-label={t('stats:services.cross.label')}>
            <div
              className="segmented stats-tabs"
              role="group"
              aria-label={t('stats:services.cross.label')}
            >
              {SERVICE_DETAIL_TABS.map((value) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={value === tab}
                  className={`segmented__option${value === tab ? ' segmented__option--checked' : ''}`}
                  onClick={() => {
                    if (value !== tab) {
                      set({ tab: value });
                    }
                  }}
                  data-testid={`stats-cross-tab-${value}`}
                >
                  {tabLabel(value)}
                </button>
              ))}
            </div>
            {crossView}
          </section>
        </>
      )}
    </StatsScreen>
  );
}
