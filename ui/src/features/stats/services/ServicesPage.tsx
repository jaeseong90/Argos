// SPDX-License-Identifier: Apache-2.0
//
// Services list /apps/:appId/stats/services (docs/15 §9.7, 05 §2.5-1, WO-36): the server's Top N
// of `GET /apps/{appId}/services/stats` for the URL range and kind with the change against the
// previous range (compare_prev), the sort switch (total time, count, average, p95, error rate),
// Top N, the per-version split (`split=1` → split_version=true, a version column), value include
// and exclude (service and version keys), column filters within the received rows, the server CSV
// and "related transactions" in the XLog with the same key filters. A row opens the service detail.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import {
  apiSort,
  listServiceStats,
  type ServiceStats,
  type ServiceStatsRequest,
} from '../../../api/stats';
import type { Untrusted } from '../../../api/untrusted';
import type { FilterValueLabel } from '../../../components/FilterChipBar';
import { SafeLink } from '../../../components/SafeLink';
import { useRegisterFilterLabels } from '../../../routes/filterLabels';
import { carriedState, StatsListQ } from '../../../state/urlState';
import { statsKey, useStatsQuery } from '../common/data';
import { serviceHref, xlogHref } from '../common/links';
import { ToggleButton, type SortOption } from '../common/ListControls';
import { applyClientFilters, xlogFilters } from '../common/model';
import { StatsListShell } from '../common/StatsListShell';
import { useStatsList } from '../common/useStatsList';

import { serviceAccessors, serviceColumns, serviceRowKey } from './serviceColumns';

const EMPTY_ROWS: readonly ServiceStats[] = [];

export function ServicesPage() {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const list = useStatsList(StatsListQ, 'services');
  const { appId, state, range, limit } = list;
  const split = state.split;
  const kind = state.kind;
  const urlSort = state.sort;

  const request = useMemo<ServiceStatsRequest | null>(
    () =>
      appId === null ? null : { appId, range, kind, sort: urlSort, limit, splitVersion: split },
    [appId, range, kind, urlSort, limit, split],
  );
  const query = useStatsQuery<ServiceStats[]>(
    statsKey('services', {
      appId,
      range,
      kind,
      extra: { sort: apiSort('services', urlSort) ?? null, limit, split },
    }),
    (signal) => {
      if (request === null) {
        throw new Error('no application');
      }
      return listServiceStats(request, signal);
    },
    request !== null,
  );

  const rows = query.data ?? EMPTY_ROWS;
  const filtered = useMemo(
    () => applyClientFilters(rows, state.filters, serviceAccessors(split)),
    [rows, state.filters, split],
  );
  const carried = useMemo(() => carriedState(state), [state]);
  const detailHref = useCallback(
    (row: ServiceStats) => serviceHref(appId ?? 0, row.service_id, carried),
    [appId, carried],
  );
  const columns = useMemo(
    () =>
      serviceColumns(t, {
        split,
        href: detailHref,
        onNavigate: (path) => {
          void navigate(path);
        },
      }),
    [t, split, detailHref, navigate],
  );

  // The shell's chips name f.service and f.version keys with the names of the response.
  const chipLabel = useMemo<FilterValueLabel>(() => {
    const services = new Map<string, Untrusted>();
    const versions = new Map<string, Untrusted>();
    for (const row of rows) {
      services.set(row.service_id, row.service);
      if (row.version_id !== undefined && row.version !== undefined) {
        versions.set(row.version_id, row.version);
      }
    }
    return (filter) =>
      filter.field === 'service'
        ? (services.get(filter.value) ?? null)
        : filter.field === 'version'
          ? (versions.get(filter.value) ?? null)
          : null;
  }, [rows]);
  useRegisterFilterLabels(chipLabel);

  const sortOptions = useMemo<SortOption[]>(
    () => [
      { field: 'sum_ms', label: t('stats:sort.sum_ms') },
      { field: 'count', label: t('stats:sort.count') },
      { field: 'avg_ms', label: t('stats:sort.avg_ms') },
      { field: 'p95_ms', label: t('stats:sort.p95_ms') },
      { field: 'error_rate', label: t('stats:sort.error_rate') },
    ],
    [t],
  );

  return (
    <StatsListShell
      testId="stats-services"
      title={t('stats:services.title')}
      caption={t('stats:services.caption')}
      list={list}
      sortOptions={sortOptions}
      extraControls={
        <ToggleButton
          label={t('stats:split.label')}
          pressed={split}
          onToggle={list.setSplit}
          data-testid="stats-split-version"
        />
      }
      headerActions={
        appId === null ? undefined : (
          <SafeLink
            href={xlogHref(appId, carried, xlogFilters([], state.filters))}
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
      rowKey={serviceRowKey}
      onRowOpen={(row) => {
        void navigate(detailHref(row));
      }}
      csv={request === null ? null : { endpoint: 'services', request }}
      empty={{
        title: t('stats:services.emptyTitle'),
        description: t('stats:state.emptyDescription'),
      }}
    />
  );
}
