// SPDX-License-Identifier: Apache-2.0
//
// URL state and actions shared by the four statistics lists (docs/15 §4.2, §6.3, §9.7, 05 §2.5-1):
// range, kind, sort (sent only when the list's API accepts it), Top N, filters (value include and
// exclude, column filters; FILTER_MAX at most), visible columns (`cols=`) and the server CSV export
// with the request's own parameters (T-UI-004, DSN-15-06). Everything lives in the URL; this hook
// only converts component events into URL writes.
import { useCallback, useMemo, useState } from 'react';

import {
  effectiveSort,
  exportStatsCsv,
  isSortField,
  type StatsCsvRequest,
  type StatsEndpoint,
  type StatsRange,
} from '../../../api/stats';
import type { SortSpec } from '../../../components/DataTable';
import { addCellFilter, type CellFilter, type FilterSpec } from '../../../components/filters';
import { useAppId } from '../../../routes/navigation';
import { toUrlFilters } from '../../../state/filters';
import {
  FILTER_MAX,
  type Filter,
  type StatsListQ,
  type StatsListState,
} from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';

import { useStatsRange } from './data';

/** URL filters of component filters (invalid ones dropped, at most FILTER_MAX). */
export function toUrlFilterList(specs: readonly FilterSpec[]): Filter[] {
  const out: Filter[] = [];
  for (const spec of specs) {
    out.push(...(toUrlFilters(spec) ?? []));
  }
  return out.slice(0, FILTER_MAX);
}

export interface StatsListApi {
  readonly appId: number | null;
  readonly state: StatsListState;
  readonly range: StatsRange;
  /** The effective sort (`field:asc|desc`): the accepted URL sort or the server default. */
  readonly sort: string;
  /** Its field (the pressed sort button). */
  readonly sortField: string;
  /** The table's sort (header arrows). */
  readonly tableSort: SortSpec;
  readonly limit: number;
  readonly setSortField: (field: string) => void;
  readonly onTableSort: (sort: SortSpec | null) => void;
  readonly setLimit: (limit: StatsListState['limit']) => void;
  readonly setSplit: (split: boolean) => void;
  readonly onCellFilter: (filter: CellFilter) => void;
  readonly onFiltersChange: (filters: FilterSpec[]) => void;
  readonly clearFilters: (() => void) | undefined;
  readonly visibleColumns: readonly string[] | undefined;
  readonly onVisibleColumnsChange: (ids: string[]) => void;
  readonly widenRange: () => void;
  readonly narrowRange: () => void;
  /** Runs the server CSV export of the request. */
  readonly exportCsv: (request: StatsCsvRequest) => void;
  readonly csvBusy: boolean;
  readonly csvError: unknown;
}

/**
 * The list state of `schema` (StatsListQ, or ErrorsQ which extends it: its own keys stay in the
 * URL and are read by the errors screen).
 */
export function useStatsList(schema: typeof StatsListQ, endpoint: StatsEndpoint): StatsListApi {
  const appId = useAppId();
  const { state, set } = useUrlState(schema);
  const time = useTimeRange(schema);
  const dispatch = time.dispatch;
  const range = useStatsRange(state.time);
  const sort = effectiveSort(endpoint, state.sort);
  const colon = sort.lastIndexOf(':');
  const sortField = sort.slice(0, colon);
  const tableSort = useMemo<SortSpec>(
    () => ({ id: sortField, desc: sort.slice(colon + 1) === 'desc' }),
    [sort, sortField, colon],
  );
  const filters = state.filters;

  const setSortField = useCallback(
    (field: string) => {
      if (isSortField(endpoint, field)) {
        set({ sort: `${field}:desc` });
      }
    },
    [endpoint, set],
  );
  const onTableSort = useCallback(
    (next: SortSpec | null) => {
      if (next !== null && isSortField(endpoint, next.id)) {
        // The field is one of the list's API sort fields, so the API form needs no further
        // check. state/sort.ts is not imported here: a second importer would split that module
        // out of the overview chunk into an initial-path chunk of its own (C-218 (1)).
        set({ sort: `${next.id}:${next.desc ? 'desc' : 'asc'}` });
      }
    },
    [endpoint, set],
  );
  const setLimit = useCallback(
    (limit: StatsListState['limit']) => {
      set({ limit });
    },
    [set],
  );
  const setSplit = useCallback(
    (split: boolean) => {
      set({ split });
    },
    [set],
  );
  const onCellFilter = useCallback(
    (filter: CellFilter) => {
      set({ filters: toUrlFilterList(addCellFilter(filters, filter)) });
    },
    [filters, set],
  );
  const onFiltersChange = useCallback(
    (next: FilterSpec[]) => {
      set({ filters: toUrlFilterList(next) });
    },
    [set],
  );
  const clearFilters = useMemo(
    () =>
      filters.length === 0
        ? undefined
        : () => {
            set({ filters: [] });
          },
    [filters.length, set],
  );
  const visibleColumns = useMemo(
    () => (state.cols === undefined ? undefined : state.cols.split(',').filter((id) => id !== '')),
    [state.cols],
  );
  const onVisibleColumnsChange = useCallback(
    (ids: string[]) => {
      set({ cols: ids.length === 0 ? undefined : ids.join(',') });
    },
    [set],
  );
  const widenRange = useCallback(() => {
    dispatch({ type: 'zoomOut' });
  }, [dispatch]);
  const narrowRange = useCallback(() => {
    dispatch({ type: 'preset', range: '1h' });
  }, [dispatch]);

  const [csvBusy, setCsvBusy] = useState(false);
  const [csvError, setCsvError] = useState<unknown>(null);
  const exportCsv = useCallback((request: StatsCsvRequest) => {
    setCsvBusy(true);
    setCsvError(null);
    exportStatsCsv(request).then(
      () => {
        setCsvBusy(false);
      },
      (error: unknown) => {
        setCsvBusy(false);
        setCsvError(error);
      },
    );
  }, []);

  return {
    appId,
    state,
    range,
    sort,
    sortField,
    tableSort,
    limit: Number(state.limit),
    setSortField,
    onTableSort,
    setLimit,
    setSplit,
    onCellFilter,
    onFiltersChange,
    clearFilters,
    visibleColumns,
    onVisibleColumnsChange,
    widenRange,
    narrowRange,
    exportCsv,
    csvBusy,
    csvError,
  };
}
