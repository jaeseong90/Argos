// SPDX-License-Identifier: Apache-2.0
//
// The list screen of a statistics endpoint (docs/15 §9.7, 05 §2.5-1): sort switch, Top N, the
// screen's own toggles, the source label, the filter scope note and the shared DataTable (server
// sort, value include/exclude, column filters, column chooser, server CSV, keyboard). The table id
// is the root test id (`stats-services`, ...); rows carry the API key in `data-row-key`.
import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { errorInfo } from '../../../api/errors';
import type { StatsCsvRequest } from '../../../api/stats';
import { DataTable, type DataTableColumn, type RowAttributes } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import type { Filter } from '../../../state/urlState';

import type { StatsQueryResult } from './data';
import {
  FilterScopeNote,
  LimitSelect,
  SortSegment,
  SourceNote,
  type SortOption,
} from './ListControls';
import { narrowsOnClient, type ClientFilterResult } from './model';
import { StatsScreen, type StatsScreenState } from './StatsScreen';
import type { StatsListApi } from './useStatsList';

export interface StatsListShellProps<Row extends object> {
  /** Root test id and DataTable id (`stats-services`, `stats-sql`, ...). */
  readonly testId: string;
  readonly title: string;
  /** Accessible name of the table. */
  readonly caption: string;
  readonly list: StatsListApi;
  readonly sortOptions: readonly SortOption[];
  /** Toggles of the screen (split by version, new only). */
  readonly extraControls?: ReactNode;
  readonly headerActions?: ReactNode;
  readonly query: StatsQueryResult<Row[]>;
  /** The response rows after the URL filters (applyClientFilters). */
  readonly filtered: ClientFilterResult<Row>;
  /** Applied filters the server applied itself (e.g. the single `db` of the SQL list). */
  readonly serverApplied?: readonly Filter[];
  readonly columns: readonly DataTableColumn<Row>[];
  readonly rowKey: (row: Row) => string;
  readonly onRowOpen?: (row: Row) => void;
  readonly rowAttributes?: (row: Row) => RowAttributes;
  /** The CSV export of the same request, or null before the request exists. */
  readonly csv: StatsCsvRequest | null;
  /** Texts of an empty response. */
  readonly empty: { readonly title: string; readonly description: string };
}

export function StatsListShell<Row extends object>({
  testId,
  title,
  caption,
  list,
  sortOptions,
  extraControls,
  headerActions,
  query,
  filtered,
  serverApplied = [],
  columns,
  rowKey,
  onRowOpen,
  rowAttributes,
  csv,
  empty,
}: StatsListShellProps<Row>) {
  const { t } = useTranslation(['common', 'stats']);
  const received = query.data?.length ?? 0;
  const narrowed = narrowsOnClient(filtered.applied, serverApplied);
  let state: StatsScreenState;
  if (query.status === 'loading') {
    state = 'loading';
  } else if (query.status === 'error') {
    state = 'error';
  } else if (filtered.rows.length === 0) {
    state = 'empty';
  } else {
    state = 'ready';
  }
  const filteredEmpty = received > 0 && filtered.rows.length === 0;
  // Controlled columns: the URL's `cols=`, else the defaults of the current columns (a column
  // that appears later, such as the version of the split, is shown).
  const visibleColumns = useMemo(
    () =>
      list.visibleColumns ??
      columns.filter((column) => column.defaultHidden !== true).map((column) => column.id),
    [list.visibleColumns, columns],
  );
  return (
    <StatsScreen
      testId={testId}
      title={title}
      state={state}
      range={list.range}
      error={query.error}
      onRetry={query.retry}
      onNarrowRange={list.narrowRange}
      refreshing={query.refreshing}
      headerActions={headerActions}
      empty={{
        title: filteredEmpty ? t('stats:state.filteredTitle') : empty.title,
        description: filteredEmpty ? t('stats:state.filteredDescription') : empty.description,
        onWidenRange: list.widenRange,
        onClearFilters: list.clearFilters,
      }}
      controls={
        <>
          <SortSegment
            options={sortOptions}
            selected={list.sortField}
            onSelect={list.setSortField}
          />
          <LimitSelect value={list.state.limit} onChange={list.setLimit} />
          {extraControls}
        </>
      }
    >
      <SourceNote meta={query.meta} />
      <FilterScopeNote narrowed={narrowed} received={received} unapplied={filtered.unapplied} />
      {list.csvError === null || list.csvError === undefined ? null : (
        <ErrorState compact error={errorInfo(list.csvError)} data-testid="stats-csv-error" />
      )}
      <DataTable
        tableId={testId}
        caption={caption}
        columns={columns}
        rows={filtered.rows}
        rowKey={rowKey}
        sort={list.tableSort}
        onSortChange={list.onTableSort}
        filters={list.state.filters}
        onFiltersChange={list.onFiltersChange}
        onCellFilter={list.onCellFilter}
        visibleColumns={visibleColumns}
        onVisibleColumnsChange={list.onVisibleColumnsChange}
        onRowOpen={onRowOpen}
        onExportCsv={
          csv === null
            ? undefined
            : () => {
                list.exportCsv(csv);
              }
        }
        csvBusy={list.csvBusy}
        loading={query.refreshing}
        rowAttributes={rowAttributes}
      />
    </StatsScreen>
  );
}
