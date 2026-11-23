// SPDX-License-Identifier: Apache-2.0
//
// XLog transaction list (05 §2.1-2, -8, -9, docs/15 §7.4, §9.3, docs/14 §4.6): the transactions of
// the dragged region from the server search (`sel` -> from/to and min_ms/max_ms, the f= filters,
// sort elapsed_ms:desc by default or ts:desc, at most 1,000 rows, "top 1,000" when there are more),
// in the shared DataTable (virtualized above 100 rows, column chooser `cols=`, include/exclude ->
// f= chips, keyboard Up/Down/Enter, missing values as the dash). Enter or a click opens the detail
// with the `ts` hint and the `back` link (ADR-0010 C-206 (1)); the list order is remembered for the
// detail's previous/next (listContext.ts). CSV is the server export of the same search (C-205 (1),
// DSN-15-06); the UI never builds a CSV. Filters the search cannot express are listed, never
// emulated. Collected strings render as text only (SEC-60).
//
// While the first page of a new selection is on its way, the list previews its first rows from
// the chart's points (`preview`, previewRows.ts; ADR-0010 C-204 "목록 점진 렌더", ADR-0003 C-36):
// `data-state="preview"`, the columns the points do not carry pending, then the server rows.
import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { excludedCount } from '../../../api/collectionHealth';
import { errorInfo, isApiError, type ErrorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import {
  exportTransactionsCsv,
  searchSortOf,
  searchTransactions,
  toTransactionSearchRequest,
  type XLogSearchQuery,
} from '../../../api/transactionSearch';
import { DataTable, type SortSpec } from '../../../components/DataTable';
import { addCellFilter, type CellFilter, type FilterSpec } from '../../../components/filters';
import { toUrlFilters } from '../../../state/filters';
import { FILTER_MAX, KINDS, type Filter, type Kind } from '../../../state/urlState';
import type { XLogBuffer } from '../../../xlog/XLogBuffer';
import type { SummaryDictionary } from '../summary/summaryModel';

import {
  columnsFromUrl,
  columnsToUrl,
  listColumns,
  rowTime,
  userIdCollected,
  type TxRow,
} from './columns';
import { transactionDetailUrl } from './detailLink';
import { saveListContext } from './listContext';
import {
  canPreview,
  exactPreview,
  PREVIEW_COLUMNS,
  previewRows,
  type PreviewSampling,
} from './previewRows';
import { UnappliedFilters } from './UnappliedFilters';

import './txList.css';

/**
 * Height of the virtualized list viewport (above 100 rows): 18 dense rows. The list sits beside
 * the summary from 1366 px up (xlogPage.css), so at 1366x768 its header and first rows are on the
 * first screen under the chart; the lower rows of the viewport are one page scroll away (docs/15
 * §9.3, §13; T-E2E-075).
 */
export const LIST_VIEWPORT_PX = 432;

/** The XLog URL state the list reads (urlState XLogQ). */
export interface XLogListQuery extends XLogSearchQuery {
  readonly cols?: string | undefined;
}

/** URL changes the list asks for (the page writes them with useUrlState(XLogQ).set). */
export interface XLogListChange {
  filters?: readonly Filter[];
  sort?: string | undefined;
  cols?: string | undefined;
  kind?: Kind;
}

/**
 * What the list shows, for tests and the C-36 measurement (`data-state` on `xlog-list`): `preview`
 * is the first page's wait with rows from the chart's points (previewRows.ts).
 */
export type ListState = 'idle' | 'loading' | 'preview' | 'ready' | 'empty' | 'error';

/**
 * The chart's points for the list preview of a new selection (previewRows.ts). The sampling facts
 * (`sampled`, `truncated`, `slowThresholdMs`: the points response's meta) decide which preview rows
 * are exactly the server's first rows (exactPreview); a sampled response previews nothing until
 * `truncated` and `slowThresholdMs` are given.
 */
export interface ListPreviewSource extends PreviewSampling {
  readonly buffer: XLogBuffer;
  readonly dictionary: SummaryDictionary;
  /**
   * The buffer holds the points of the current range, kind and filters, and the points API applied
   * every filter (false while new points load, or with filters only the search applies).
   */
  readonly applicable: boolean;
}

export interface TxListPanelProps {
  readonly appId: number;
  readonly query: XLogListQuery;
  /** Writes URL state (filters, sort, cols, kind); `sel` is never touched by the list. */
  readonly onQueryChange: (change: XLogListChange) => void;
  /** The current XLog location (pathname + search): the `back` of the detail links. */
  readonly back: string;
  /** Opens a row. Default: navigate to the detail link (`href`). */
  readonly onOpen?: (row: TxRow, href: string) => void;
  /** The chart's points: previews the first rows of a new selection (C-204). None by default. */
  readonly preview?: ListPreviewSource | null;
}

/** The `sel` of a query in URL form, the key of what the list shows (data-sel). */
export function selectionKey(query: Pick<XLogSearchQuery, 'sel'>): string {
  const sel = query.sel;
  return sel === undefined
    ? ''
    : `ms:${String(sel.msMin)}-${String(sel.msMax)},ts:${String(sel.tsFrom)}-${String(sel.tsTo)}`;
}

/** The URL `sort` of a table sort: only the two server sorts, always descending. */
export function sortToUrl(sort: SortSpec | null): string | undefined {
  // The default (elapsed descending) is not written, so `back` links stay short.
  return sort?.id === 'ts' ? 'ts:desc' : undefined;
}

/** The URL filters after a cell include/exclude, or null when they would break the URL limits. */
export function filtersAfterCellFilter(
  current: readonly Filter[],
  cell: CellFilter,
): Filter[] | null {
  const specs: FilterSpec[] = addCellFilter(
    current.map((f) => ({ field: f.field, op: f.op, value: f.value })),
    cell,
  );
  const next: Filter[] = [];
  for (const spec of specs) {
    const converted = toUrlFilters(spec);
    if (converted === null) {
      return null;
    }
    next.push(...converted);
  }
  return next.length > FILTER_MAX ? null : next;
}

function isKind(value: string): value is Kind {
  return (KINDS as readonly string[]).includes(value);
}

function csvErrorKind(info: ErrorInfo): 'limit' | 'rateLimited' | 'other' {
  if (info.code === 'limit_exceeded') {
    return 'limit';
  }
  return info.code === 'rate_limited' || info.status === 429 ? 'rateLimited' : 'other';
}

/** Cell of a column the preview rows do not carry: pending, not missing (T-UI-007). */
function pendingCell(): ReactNode {
  // The mark itself is drawn by CSS (txList.css): no text for screen readers in every cell; the
  // table is aria-busy and the preview note says what is pending.
  return <span className="xlog-list__pending" aria-hidden="true" data-testid="xlog-list-pending" />;
}

/** The transaction list of the XLog selection. */
export function TxListPanel({
  appId,
  query,
  onQueryChange,
  back,
  onOpen,
  preview = null,
}: TxListPanelProps) {
  const { t, i18n } = useTranslation(['common', 'xlog']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const [csvBusy, setCsvBusy] = useState(false);
  // Messages belong to the search they were made for (searchKey): a new search hides them.
  const [csvFailure, setCsvFailure] = useState<{ key: string; info: ErrorInfo } | null>(null);
  const [limitKey, setLimitKey] = useState<string | null>(null);

  const call = useMemo(
    () =>
      toTransactionSearchRequest(appId, {
        time: query.time,
        kind: query.kind,
        filters: query.filters,
        sort: query.sort,
        sel: query.sel,
      }),
    [appId, query.time, query.kind, query.filters, query.sort, query.sel],
  );
  const selKey = selectionKey(query);
  const hasSelection = query.sel !== undefined;
  const sort = searchSortOf(query.sort);
  const enabled = hasSelection && !call.empty;

  const search = useQuery({
    queryKey: queryKeys.screen('xlog-search', {
      appId,
      range: { from: Date.parse(call.body.from ?? ''), to: Date.parse(call.body.to ?? '') },
      kind: query.kind,
      filters: query.filters,
      extra: { sel: selKey, sort },
    }),
    queryFn: ({ signal }) => searchTransactions(call, signal),
    enabled,
    staleTime: 30_000,
  });

  const rows: readonly TxRow[] = useMemo(
    () => (enabled ? (search.data?.data ?? []) : []),
    [enabled, search.data],
  );
  const meta = enabled ? search.data?.meta : undefined;
  const total = typeof meta?.total === 'number' ? meta.total : null;
  const truncated = meta?.truncated === true || (total !== null && total > rows.length);

  // "N excluded in this range" for an empty result (D-19): only when the list is empty.
  const showExcluded = enabled && search.isSuccess && rows.length === 0;
  const excluded = useQuery({
    queryKey: ['xlog-list-excluded', appId, call.body.from ?? '', call.body.to ?? ''] as const,
    queryFn: ({ signal }) => excludedCount(appId, call.body.from ?? '', call.body.to ?? '', signal),
    enabled: showExcluded,
    staleTime: 60_000,
  });

  // Preview: only while the first page of this search has not arrived (a refetch keeps its rows),
  // and only the rows that are exactly the server's first rows (exactPreview).
  const sel = query.sel;
  const previewList = useMemo(() => {
    if (
      !enabled ||
      !search.isPending ||
      sel === undefined ||
      preview === null ||
      !preview.applicable ||
      call.unapplied.length > 0 ||
      !canPreview(sort, preview)
    ) {
      return null;
    }
    const found = exactPreview(
      previewRows(preview.buffer, preview.dictionary, sel, sort),
      sort,
      preview,
    );
    return found.length === 0 ? null : found;
  }, [enabled, search.isPending, sel, preview, call.unapplied.length, sort]);

  const withUserId = userIdCollected(rows);
  const baseColumns = useMemo(() => listColumns(t, locale, withUserId), [t, locale, withUserId]);
  const columns = useMemo(
    () =>
      previewList === null
        ? baseColumns
        : baseColumns.map((column) =>
            PREVIEW_COLUMNS.has(column.id)
              ? column
              : { ...column, render: pendingCell, filterable: false },
          ),
    [baseColumns, previewList],
  );
  const defaultVisible = useMemo(
    () => columns.filter((c) => c.defaultHidden !== true).map((c) => c.id),
    [columns],
  );
  const visible = columnsFromUrl(query.cols) ?? defaultVisible;

  const searchKey = JSON.stringify(call.body);
  const csvError = csvFailure?.key === searchKey ? csvFailure.info : null;
  const limitHint = limitKey === searchKey;

  const open = useCallback(
    (row: TxRow) => {
      const ms = rowTime(row);
      const href = transactionDetailUrl(appId, row.tx_id, ms ?? row.ts, back);
      if (previewList !== null) {
        // A preview row opens its detail; the list order for previous/next is the server's, so
        // the detail re-queries it from `back` (C-206 (2)).
        if (onOpen !== undefined) {
          onOpen(row, href);
        } else {
          void navigate(href);
        }
        return;
      }
      saveListContext({
        back,
        appId,
        query: {
          time: query.time,
          kind: query.kind,
          filters: query.filters,
          sort: query.sort,
          sel: query.sel,
        },
        rows: rows.flatMap((r) => {
          const ts = rowTime(r);
          return ts === null ? [] : [{ txId: r.tx_id, ts }];
        }),
        total,
        nextCursor: meta?.next_cursor ?? null,
      });
      if (onOpen !== undefined) {
        onOpen(row, href);
      } else {
        void navigate(href);
      }
    },
    [appId, back, meta, navigate, onOpen, previewList, query, rows, total],
  );

  const onCellFilter = (cell: CellFilter): void => {
    // Including a kind switches the global kind (docs/14 §6 `kind`); excluding one is a filter.
    if (cell.field === 'kind' && cell.op === 'eq' && isKind(cell.value)) {
      onQueryChange({ kind: cell.value });
      return;
    }
    const next = filtersAfterCellFilter(query.filters, cell);
    if (next === null) {
      setLimitKey(searchKey);
      return;
    }
    setLimitKey(null);
    onQueryChange({ filters: next });
  };

  const exportCsvFile = (): void => {
    setCsvBusy(true);
    setCsvFailure(null);
    const key = searchKey;
    exportTransactionsCsv(call)
      .catch((error: unknown) => {
        setCsvFailure({ key, info: isApiError(error) ? errorInfo(error) : { status: 0 } });
      })
      .finally(() => {
        setCsvBusy(false);
      });
  };

  let state: ListState;
  if (!hasSelection) {
    state = 'idle';
  } else if (call.empty) {
    state = 'empty';
  } else if (search.isError) {
    state = 'error';
  } else if (previewList !== null) {
    state = 'preview';
  } else if (search.isPending || search.isFetching) {
    state = 'loading';
  } else {
    state = rows.length === 0 ? 'empty' : 'ready';
  }

  const csvKind = csvError === null ? null : csvErrorKind(csvError);
  const excludedTotal = excluded.data ?? null;

  return (
    <section
      className="xlog-list"
      aria-labelledby="xlog-list-title"
      data-testid="xlog-list"
      data-state={state}
      data-sel={selKey}
      data-rows={rows.length}
      data-preview-rows={previewList === null ? undefined : previewList.length}
    >
      <div className="xlog-list__header">
        <h2 id="xlog-list-title" className="xlog-list__title">
          {t('xlog:list.title')}
        </h2>
        {state === 'ready' || state === 'empty' ? (
          <p className="xlog-list__count" data-testid="xlog-list-count" aria-live="polite">
            {truncated && total !== null ? (
              <span data-testid="xlog-list-top" data-total={total} data-shown={rows.length}>
                {sort === 'ts:desc'
                  ? t('xlog:list.topLatest', { shown: rows.length, matching: total })
                  : t('xlog:list.top', { shown: rows.length, matching: total })}
              </span>
            ) : (
              t('xlog:list.all', { count: rows.length })
            )}
          </p>
        ) : null}
        {state === 'preview' ? (
          <p className="xlog-list__count" data-testid="xlog-list-preview">
            {t('xlog:list.preview')}
          </p>
        ) : null}
      </div>
      <UnappliedFilters
        unapplied={hasSelection ? call.unapplied : []}
        title={t('xlog:list.unapplied.title')}
        data-testid="xlog-list-unapplied"
      />
      {hasSelection && !withUserId && rows.length > 0 ? (
        <p className="xlog-list__note" data-testid="xlog-list-user-note">
          {t('xlog:list.userNotCollected')}
        </p>
      ) : null}
      {limitHint ? (
        <p className="xlog-list__note" role="status" data-testid="xlog-list-filter-limit">
          {t('xlog:list.filterLimit', { max: FILTER_MAX })}
        </p>
      ) : null}
      {csvKind === null || csvError === null ? null : (
        <p className="xlog-list__error" role="alert" data-testid="xlog-list-csv-error">
          {csvKind === 'limit'
            ? t('xlog:list.csvError.limit')
            : csvKind === 'rateLimited'
              ? t('xlog:list.csvError.rateLimited', { seconds: csvError.retryAfterSeconds ?? 0 })
              : t('xlog:list.csvError.other', { status: csvError.status ?? 0 })}
        </p>
      )}
      {hasSelection ? (
        <DataTable<TxRow>
          tableId="xlog-transactions"
          caption={t('xlog:list.caption')}
          columns={columns}
          rows={previewList ?? rows}
          rowKey={(row) => row.tx_id}
          sort={{ id: sort === 'ts:desc' ? 'ts' : 'elapsed_ms', desc: true }}
          onSortChange={(next) => {
            const value = sortToUrl(next);
            if (value !== query.sort) {
              onQueryChange({ sort: value });
            }
          }}
          filters={query.filters}
          onCellFilter={onCellFilter}
          visibleColumns={visible}
          onVisibleColumnsChange={(ids) => {
            onQueryChange({ cols: columnsToUrl(ids, defaultVisible) });
          }}
          onRowOpen={open}
          onExportCsv={rows.length > 0 && previewList === null ? exportCsvFile : undefined}
          csvBusy={csvBusy}
          loading={state === 'loading' || state === 'preview'}
          error={search.isError ? errorInfo(search.error) : null}
          onRetry={() => {
            void search.refetch();
          }}
          empty={{
            title: t('xlog:list.noMatch.title'),
            description: call.empty
              ? t('xlog:list.noMatch.impossible')
              : t('xlog:list.noMatch.description'),
            excluded:
              excludedTotal !== null && excludedTotal > 0
                ? {
                    count: excludedTotal,
                    onOpen: () => {
                      void navigate(`/apps/${String(appId)}/dashboard`);
                    },
                  }
                : null,
            'data-testid': 'xlog-list-empty',
          }}
          density="dense"
          maxHeight={LIST_VIEWPORT_PX}
        />
      ) : (
        <p className="xlog-list__idle" data-testid="xlog-list-idle">
          {t('xlog:list.noSelection')}
        </p>
      )}
    </section>
  );
}
