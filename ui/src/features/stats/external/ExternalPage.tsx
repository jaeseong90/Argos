// SPDX-License-Identifier: Apache-2.0
//
// External calls list /apps/:appId/stats/external (docs/15 §9.7, 05 §2.5-1/-4, WO-36): the
// server's Top N hosts of `GET /apps/{appId}/external/stats` for the URL range and kind with the
// change against the previous range, the sort switch (total time, count, average, p95, errors,
// timeouts), Top N, value include/exclude of a host (narrowing the received rows: the API has no
// host parameter), column filters, the server CSV and the XLog of the range. Timeouts and
// connection failures come from the failure class the agent stores per call (WO-14, ADR-0010
// C-223 (3): any timeout is a timeout, refused or unreachable a connection failure). The path
// pattern is not stored: the statistics key is the host only (C-178 (3), C-223 (5)). No
// application map link: F-U11 (S) has no screen in Phase 1.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import {
  apiSort,
  listExternalStats,
  type ExternalStats,
  type StatsListRequest,
} from '../../../api/stats';
import type { Untrusted } from '../../../api/untrusted';
import type { FilterValueLabel } from '../../../components/FilterChipBar';
import { SafeLink } from '../../../components/SafeLink';
import { useRegisterFilterLabels } from '../../../routes/filterLabels';
import { carriedState, StatsListQ } from '../../../state/urlState';
import { statsKey, useStatsQuery } from '../common/data';
import { externalHref, xlogHref } from '../common/links';
import type { SortOption } from '../common/ListControls';
import { applyClientFilters, xlogFilters } from '../common/model';
import { StatsListShell } from '../common/StatsListShell';
import { useStatsList } from '../common/useStatsList';

import { EXTERNAL_ACCESSORS, externalColumns, externalRowKey } from './externalColumns';

const EMPTY_ROWS: readonly ExternalStats[] = [];

export function ExternalPage() {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const list = useStatsList(StatsListQ, 'external');
  const { appId, state, range, limit } = list;
  const kind = state.kind;
  const urlSort = state.sort;
  const filters = state.filters;

  const request = useMemo<StatsListRequest | null>(
    () => (appId === null ? null : { appId, range, kind, sort: urlSort, limit }),
    [appId, range, kind, urlSort, limit],
  );
  const query = useStatsQuery<ExternalStats[]>(
    statsKey('external', {
      appId,
      range,
      kind,
      extra: { sort: apiSort('external', urlSort) ?? null, limit },
    }),
    (signal) => {
      if (request === null) {
        throw new Error('no application');
      }
      return listExternalStats(request, signal);
    },
    request !== null,
  );

  const rows = query.data ?? EMPTY_ROWS;
  const filtered = useMemo(
    () => applyClientFilters(rows, filters, EXTERNAL_ACCESSORS),
    [rows, filters],
  );
  const carried = useMemo(() => carriedState(state), [state]);
  const detailHref = useCallback(
    (row: ExternalStats) => externalHref(appId ?? 0, row.host_id, carried),
    [appId, carried],
  );
  const estimated = rows.some((row) => row.estimated);
  const columns = useMemo(
    () =>
      externalColumns(t, {
        href: detailHref,
        onNavigate: (path) => {
          void navigate(path);
        },
        estimated,
      }),
    [t, detailHref, navigate, estimated],
  );

  // The shell's chips name f.host keys with the host of the response.
  const chipLabel = useMemo<FilterValueLabel>(() => {
    const hosts = new Map<string, Untrusted>();
    for (const row of rows) {
      hosts.set(row.host_id, row.host);
    }
    return (filter) => (filter.field === 'host' ? (hosts.get(filter.value) ?? null) : null);
  }, [rows]);
  useRegisterFilterLabels(chipLabel);

  const sortOptions = useMemo<SortOption[]>(
    () => [
      { field: 'sum_ms', label: t('stats:sort.sum_ms') },
      { field: 'count', label: t('stats:sort.count') },
      { field: 'avg_ms', label: t('stats:sort.avg_ms') },
      { field: 'p95_ms', label: t('stats:sort.p95_ms') },
      { field: 'errors', label: t('stats:sort.errors') },
      { field: 'timeouts', label: t('stats:sort.timeouts') },
    ],
    [t],
  );

  return (
    <StatsListShell
      testId="stats-external"
      title={t('stats:external.title')}
      caption={t('stats:external.caption')}
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
      columns={columns}
      rowKey={externalRowKey}
      onRowOpen={(row) => {
        void navigate(detailHref(row));
      }}
      csv={request === null ? null : { endpoint: 'external', request }}
      empty={{
        title: t('stats:external.emptyTitle'),
        description: t('stats:state.emptyDescription'),
      }}
    />
  );
}
