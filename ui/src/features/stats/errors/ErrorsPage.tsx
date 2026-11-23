// SPDX-License-Identifier: Apache-2.0
//
// Error groups list /apps/:appId/stats/errors (docs/15 §9.7, 05 §2.5-1/-5, WO-36): the server's
// Top N groups of `GET /apps/{appId}/errors/groups` for the URL range and kind (count, first and
// last occurrence, top 5 services, versions) with the change of the count against the previous
// range, the sort switch (count, first seen, last seen), Top N, the "new" filter (`new=1` →
// new_only=true: groups first seen ever inside the range), value include/exclude of an exception
// class, column filters within the received rows, the server CSV and the XLog. The class, message
// pattern (masked by the agent) and top frame are collected text: SafeText only.
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import {
  apiSort,
  listErrorGroups,
  type ErrorGroup,
  type ErrorGroupsRequest,
} from '../../../api/stats';
import type { Untrusted } from '../../../api/untrusted';
import type { FilterValueLabel } from '../../../components/FilterChipBar';
import { SafeLink } from '../../../components/SafeLink';
import { useRegisterFilterLabels } from '../../../routes/filterLabels';
import { carriedState, ErrorsQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { statsKey, useStatsQuery } from '../common/data';
import { errorGroupHref, xlogHref } from '../common/links';
import { ToggleButton, type SortOption } from '../common/ListControls';
import { applyClientFilters, xlogFilters } from '../common/model';
import { StatsListShell } from '../common/StatsListShell';
import { useStatsList } from '../common/useStatsList';

import { ERROR_ACCESSORS, errorColumns, errorRowKey } from './errorColumns';

const EMPTY_ROWS: readonly ErrorGroup[] = [];

export function ErrorsPage() {
  const { t, i18n } = useTranslation(['common', 'stats']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const list = useStatsList(ErrorsQ, 'errors');
  const { appId, state, range, limit } = list;
  const errorsUrl = useUrlState(ErrorsQ);
  const newOnly = errorsUrl.state.new;
  const setUrl = errorsUrl.set;
  const kind = state.kind;
  const urlSort = state.sort;
  const filters = state.filters;

  const request = useMemo<ErrorGroupsRequest | null>(
    () => (appId === null ? null : { appId, range, kind, sort: urlSort, limit, newOnly }),
    [appId, range, kind, urlSort, limit, newOnly],
  );
  const query = useStatsQuery<ErrorGroup[]>(
    statsKey('errors', {
      appId,
      range,
      kind,
      extra: { sort: apiSort('errors', urlSort) ?? null, limit, newOnly },
    }),
    (signal) => {
      if (request === null) {
        throw new Error('no application');
      }
      return listErrorGroups(request, signal);
    },
    request !== null,
  );

  const rows = query.data ?? EMPTY_ROWS;
  const filtered = useMemo(
    () => applyClientFilters(rows, filters, ERROR_ACCESSORS),
    [rows, filters],
  );
  const carried = useMemo(() => carriedState(state), [state]);
  const detailHref = useCallback(
    (row: ErrorGroup) => errorGroupHref(appId ?? 0, row.group_id, carried),
    [appId, carried],
  );
  // The change column appears when the server sends `delta` (compare_prev, T-API-011b).
  const showDelta = rows.some((row) => row.delta !== undefined);
  const columns = useMemo(
    () =>
      errorColumns(t, {
        href: detailHref,
        onNavigate: (path) => {
          void navigate(path);
        },
        locale,
        showDelta,
      }),
    [t, detailHref, navigate, locale, showDelta],
  );

  // The shell's chips name f.error_group, f.service and f.version keys from the response.
  const chipLabel = useMemo<FilterValueLabel>(() => {
    const groups = new Map<string, Untrusted>();
    const services = new Map<string, Untrusted>();
    const versions = new Map<string, Untrusted>();
    for (const row of rows) {
      if (row.error_class !== null) {
        groups.set(row.group_id, row.error_class);
      }
      for (const service of row.services) {
        if (service.name !== null) {
          services.set(service.id, service.name);
        }
      }
      for (const version of row.versions) {
        if (version.version !== null) {
          versions.set(version.version_id, version.version);
        }
      }
    }
    return (filter) => {
      switch (filter.field) {
        case 'error_group':
          return groups.get(filter.value) ?? null;
        case 'service':
          return services.get(filter.value) ?? null;
        case 'version':
          return versions.get(filter.value) ?? null;
        default:
          return null;
      }
    };
  }, [rows]);
  useRegisterFilterLabels(chipLabel);

  const sortOptions = useMemo<SortOption[]>(
    () => [
      { field: 'count', label: t('stats:sort.count') },
      { field: 'first_seen', label: t('stats:sort.first_seen') },
      { field: 'last_seen', label: t('stats:sort.last_seen') },
    ],
    [t],
  );

  return (
    <StatsListShell
      testId="stats-errors"
      title={t('stats:errors.title')}
      caption={t('stats:errors.caption')}
      list={list}
      sortOptions={sortOptions}
      extraControls={
        <ToggleButton
          label={t('stats:errors.newOnly')}
          pressed={newOnly}
          onToggle={(pressed) => {
            setUrl({ new: pressed });
          }}
          data-testid="stats-errors-new"
        />
      }
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
      rowKey={errorRowKey}
      rowAttributes={(row) => ({ 'data-new': row.is_new ? 'true' : undefined })}
      onRowOpen={(row) => {
        void navigate(detailHref(row));
      }}
      csv={request === null ? null : { endpoint: 'errors', request }}
      empty={{
        title: newOnly ? t('stats:errors.emptyNewTitle') : t('stats:errors.emptyTitle'),
        description: newOnly
          ? t('stats:errors.emptyNewDescription')
          : t('stats:state.emptyDescription'),
      }}
    />
  );
}
