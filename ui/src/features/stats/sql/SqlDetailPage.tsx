// SPDX-License-Identifier: Apache-2.0
//
// SQL statement detail /apps/:appId/stats/sql/:sqlId (docs/15 §9.7, 05 §2.5-3, WO-36): totals
// (executions, execution and fetch time, connection wait), the trend with bucket-estimated
// percentiles (C-14) and event markers, the calling services with their count and share, the
// instance distribution (raw window), the split by JDBC target, the normalized SQL and the SQL
// text (CodeBlock: text nodes only, SEC-61; truncation noted), the related transactions ("profiled
// transactions only") and the XLog with `f.sql`. The connection wait is not attributed per
// statement yet (ADR-0010 C-178 (2)): the server sends null and the screen says "Not measurable",
// never 0.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';

import { getSqlStats, type SqlDetail, type SqlStats } from '../../../api/stats';
import { untrusted } from '../../../api/untrusted';
import type { TimeRange as ChartRange } from '../../../charts/chartModel';
import { CodeBlock } from '../../../components/CodeBlock';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { useAppId } from '../../../routes/navigation';
import { carriedState, CommonQ, type Filter } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { BreakdownTable } from '../common/BreakdownTable';
import { numberColumn } from '../common/columns';
import { statsKey, useEventMarkers, useStatsQuery, useStatsRange } from '../common/data';
import { DetailLinks, RawWindowNote, routeKey, SummaryItem } from '../common/DetailParts';
import { serviceHref, sqlListHref, xlogAtRangeHref, xlogHref } from '../common/links';
import { SourceNote } from '../common/ListControls';
import { xlogFilters } from '../common/model';
import { RelatedTxList } from '../common/RelatedTxList';
import { StatsScreen, type StatsScreenState } from '../common/StatsScreen';
import { TrendChart } from '../common/TrendChart';

export function SqlDetailPage() {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const appId = useAppId();
  const params = useParams();
  const sqlId = routeKey(params.sqlId);
  const { state } = useUrlState(CommonQ);
  const time = useTimeRange(CommonQ);
  const dispatch = time.dispatch;
  const range = useStatsRange(state.time);
  const kind = state.kind;
  const enabled = appId !== null && sqlId !== null;

  const query = useStatsQuery<SqlDetail>(
    statsKey('sql-detail', { appId, range, kind, extra: { sqlId } }),
    (signal) => getSqlStats({ appId: appId ?? 0, range, kind, sqlId: sqlId ?? '' }, signal),
    enabled,
  );
  const markers = useEventMarkers(appId, range);
  const detail = query.data;
  const id = appId ?? 0;
  const carried = useMemo(() => carriedState(state), [state]);
  const ownFilter = useMemo<Filter[]>(
    () => (sqlId === null ? [] : [{ field: 'sql', op: 'eq', value: untrusted(sqlId) }]),
    [sqlId],
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
  const estimated = detail?.estimated === true;
  const dbColumns = useMemo<DataTableColumn<SqlStats>[]>(
    () => [
      {
        id: 'db',
        header: t('stats:columns.db'),
        value: (row) => row.db.target,
        mono: true,
        hideable: false,
        width: 220,
      },
      numberColumn({
        id: 'count',
        header: t('stats:columns.count'),
        unit: 'count',
        value: (row) => row.count,
        sortable: true,
        rangeFilter: false,
      }),
      numberColumn({
        id: 'errors',
        header: t('stats:columns.errors'),
        unit: 'count',
        value: (row) => row.errors,
        sortable: true,
        rangeFilter: false,
      }),
      numberColumn({
        id: 'sum_ms',
        header: t('stats:columns.sum_ms'),
        unit: 'ms',
        value: (row) => row.sum_ms,
        sortable: true,
        rangeFilter: false,
      }),
      numberColumn({
        id: 'avg_ms',
        header: t('stats:columns.avg_ms'),
        unit: 'ms',
        value: (row) => row.avg_ms,
        sortable: true,
        rangeFilter: false,
      }),
      numberColumn({
        id: 'p95_ms',
        header: t('stats:columns.p95_ms'),
        unit: 'ms',
        value: (row) => row.p95_ms,
        sortable: true,
        rangeFilter: false,
        estimated,
      }),
    ],
    [t, estimated],
  );

  let pageState: StatsScreenState;
  if (!enabled) {
    pageState = 'empty';
  } else if (query.status === 'ready' && detail !== null) {
    pageState = detail.count === 0 ? 'empty' : 'ready';
  } else {
    pageState = query.status === 'error' ? 'error' : 'loading';
  }

  return (
    <StatsScreen
      testId="stats-sql-detail"
      title={t('stats:sql.detailTitle')}
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
          backHref={sqlListHref(id, carried)}
          xlogHref={enabled ? xlogHref(id, carried, filters) : null}
        />
      }
      empty={
        enabled
          ? {
              title: t('stats:sql.detailEmptyTitle'),
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
          <section className="stats-section" aria-labelledby="stats-sql-normalized-title">
            <h2 id="stats-sql-normalized-title" className="stats-section__title">
              {t('stats:sql.normalized')}
            </h2>
            <CodeBlock
              value={detail.sql_normalized}
              language="sql"
              foldLines={6}
              label={t('stats:sql.normalized')}
              data-testid="stats-sql-normalized"
            />
          </section>
          <dl className="stats-summary" data-testid="stats-sql-summary">
            <SummaryItem
              label={t('stats:sql.count')}
              value={detail.count}
              unit="count"
              data-testid="stats-sql-count"
            />
            <SummaryItem
              label={t('stats:sql.exec')}
              value={detail.exec_ms_total}
              unit="ms"
              data-testid="stats-sql-exec"
            />
            <SummaryItem
              label={t('stats:sql.fetch')}
              value={detail.fetch_ms_total}
              unit="ms"
              data-testid="stats-sql-fetch"
            />
            <SummaryItem
              label={t('stats:sql.wait')}
              value={detail.wait_ms_total}
              unit="ms"
              measured={detail.wait_ms_total !== null}
              data-testid="stats-sql-wait"
              note={detail.wait_ms_total === null ? t('stats:sql.waitNotMeasured') : undefined}
            />
          </dl>
          <TrendChart
            series={detail.series}
            meta={query.meta}
            range={range}
            markers={markers}
            estimated={estimated}
            volume={['count', 'errors']}
            latency={['avg_ms', 'p95_ms']}
            xlogAt={xlogAt}
            onZoom={onZoom}
          />
          <div className="stats-grid">
            <BreakdownTable
              tableId="stats-by-service"
              title={t('stats:breakdown.byService')}
              nameHeader={t('stats:columns.service')}
              rows={detail.by_service}
              metrics={['count', 'share']}
              href={(row) => serviceHref(id, row.id, carried)}
              mono
              emptyText={t('stats:breakdown.rawEmpty')}
            />
            <div className="stats-section">
              <RawWindowNote window={detail.raw_window} />
              <BreakdownTable
                tableId="stats-by-instance"
                title={t('stats:breakdown.byInstance')}
                nameHeader={t('stats:columns.instance')}
                rows={detail.by_instance}
                metrics={['count', 'share', 'avg_ms']}
                emptyText={t('stats:breakdown.rawEmpty')}
              />
            </div>
          </div>
          <section className="stats-section" aria-labelledby="stats-by-db-title">
            <h2 id="stats-by-db-title" className="stats-section__title">
              {t('stats:breakdown.byDb')}
            </h2>
            <DataTable
              tableId="stats-by-db"
              caption={t('stats:breakdown.byDb')}
              columns={dbColumns}
              rows={detail.by_db}
              rowKey={(row) => row.db.db_id}
              dataComplete
              density="dense"
              empty={{ title: t('stats:breakdown.empty') }}
            />
          </section>
          <section className="stats-section" aria-labelledby="stats-sql-text-title">
            <h2 id="stats-sql-text-title" className="stats-section__title">
              {t('stats:sql.text')}
            </h2>
            {detail.sql_truncated ? (
              <p className="stats-note" data-testid="stats-sql-truncated">
                {t('stats:sql.truncated')}
              </p>
            ) : null}
            <CodeBlock
              value={detail.sql_text}
              language="sql"
              label={t('stats:sql.text')}
              data-testid="stats-sql-text"
            />
          </section>
          <RelatedTxList
            appId={id}
            tableId="stats-related-tx"
            title={t('stats:related.title')}
            rows={detail.related_tx.items}
            profiledOnly
            emptyText={t('stats:related.empty')}
          />
          <p className="stats-note">
            <button
              type="button"
              className="control-button"
              onClick={() => {
                void navigate(xlogHref(id, carried, filters));
              }}
              data-testid="stats-related-xlog"
            >
              {t('stats:related.allInXlog')}
            </button>
          </p>
        </>
      )}
    </StatsScreen>
  );
}
