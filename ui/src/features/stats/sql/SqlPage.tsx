// SPDX-License-Identifier: Apache-2.0
//
// SQL list /apps/:appId/stats/sql (docs/15 §9.7, 05 §2.5-1, WO-36): the server's Top N of
// `GET /apps/{appId}/sql/stats` (complete counts, D-12; percentiles estimated from latency
// buckets, C-14) for the URL range and kind with the change against the previous range, the sort
// switch (total time, count, average, p95, errors), Top N, value include/exclude (SQL key, JDBC
// target key: one target goes to the server as `db_id`), column filters within the received rows,
// the server CSV and the XLog with `f.sql`. The statement is collected text: SafeText only.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { apiSort, listSqlStats, type SqlStats, type SqlStatsRequest } from '../../../api/stats';
import type { Untrusted } from '../../../api/untrusted';
import type { FilterValueLabel } from '../../../components/FilterChipBar';
import { SafeLink } from '../../../components/SafeLink';
import { useRegisterFilterLabels } from '../../../routes/filterLabels';
import { carriedState, StatsListQ } from '../../../state/urlState';
import { statsKey, useStatsQuery } from '../common/data';
import { sqlHref, xlogHref } from '../common/links';
import type { SortOption } from '../common/ListControls';
import { applyClientFilters, singleEqValue, xlogFilters } from '../common/model';
import { StatsListShell } from '../common/StatsListShell';
import { useStatsList } from '../common/useStatsList';

import { SQL_ACCESSORS, sqlColumns, sqlRowKeys } from './sqlColumns';

const EMPTY_ROWS: readonly SqlStats[] = [];

export function SqlPage() {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const list = useStatsList(StatsListQ, 'sql');
  const { appId, state, range, limit } = list;
  const kind = state.kind;
  const urlSort = state.sort;
  const filters = state.filters;
  // One `f.db=<key>` (and no exclusion of a target) is the server's `db_id` (docs/14 §4.8).
  const dbId = singleEqValue(filters, 'db');

  const request = useMemo<SqlStatsRequest | null>(
    () => (appId === null ? null : { appId, range, kind, sort: urlSort, limit, dbId }),
    [appId, range, kind, urlSort, limit, dbId],
  );
  const query = useStatsQuery<SqlStats[]>(
    statsKey('sql', {
      appId,
      range,
      kind,
      extra: { sort: apiSort('sql', urlSort) ?? null, limit, db: dbId },
    }),
    (signal) => {
      if (request === null) {
        throw new Error('no application');
      }
      return listSqlStats(request, signal);
    },
    request !== null,
  );

  const rows = query.data ?? EMPTY_ROWS;
  const filtered = useMemo(() => applyClientFilters(rows, filters, SQL_ACCESSORS), [rows, filters]);
  const serverApplied = useMemo(
    () => (dbId === null ? [] : filters.filter((filter) => filter.field === 'db')),
    [dbId, filters],
  );
  const carried = useMemo(() => carriedState(state), [state]);
  const detailHref = useCallback(
    (row: SqlStats) => sqlHref(appId ?? 0, row.sql_id, carried),
    [appId, carried],
  );
  const estimated = rows.some((row) => row.estimated);
  const columns = useMemo(
    () =>
      sqlColumns(t, {
        href: detailHref,
        onNavigate: (path) => {
          void navigate(path);
        },
        estimated,
      }),
    [t, detailHref, navigate, estimated],
  );
  const rowKey = useMemo(() => sqlRowKeys(rows), [rows]);

  // The shell's chips name f.db and f.sql keys with the target and the statement.
  const chipLabel = useMemo<FilterValueLabel>(() => {
    const targets = new Map<string, Untrusted>();
    const statements = new Map<string, Untrusted>();
    for (const row of rows) {
      targets.set(row.db.db_id, row.db.target);
      statements.set(row.sql_id, row.sql_normalized);
    }
    return (filter) =>
      filter.field === 'db'
        ? (targets.get(filter.value) ?? null)
        : filter.field === 'sql'
          ? (statements.get(filter.value) ?? null)
          : null;
  }, [rows]);
  useRegisterFilterLabels(chipLabel);

  const sortOptions = useMemo<SortOption[]>(
    () => [
      { field: 'sum_ms', label: t('stats:sort.sum_ms') },
      { field: 'count', label: t('stats:sort.count') },
      { field: 'avg_ms', label: t('stats:sort.avg_ms') },
      { field: 'p95_ms', label: t('stats:sort.p95_ms') },
      { field: 'errors', label: t('stats:sort.errors') },
    ],
    [t],
  );

  return (
    <StatsListShell
      testId="stats-sql"
      title={t('stats:sql.title')}
      caption={t('stats:sql.caption')}
      list={list}
      sortOptions={sortOptions}
      headerActions={
        appId === null ? undefined : (
          <SafeLink
            href={xlogHref(appId, carried, xlogFilters([], filters))}
            onNavigate={(path) => {
              void navigate(path);
            }}
            data-testid="stats-open-xlog"
          >
            {t('stats:openXlog')}
          </SafeLink>
        )
      }
      query={query}
      filtered={filtered}
      serverApplied={serverApplied}
      columns={columns}
      rowKey={rowKey}
      rowAttributes={(row) => ({ 'data-sql-id': row.sql_id, 'data-db-id': row.db.db_id })}
      onRowOpen={(row) => {
        void navigate(detailHref(row));
      }}
      csv={request === null ? null : { endpoint: 'sql', request }}
      empty={{
        title: t('stats:sql.emptyTitle'),
        description: t('stats:state.emptyDescription'),
      }}
    />
  );
}
