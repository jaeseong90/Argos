// SPDX-License-Identifier: Apache-2.0
//
// Columns of the XLog transaction list (05 §2.1-8, docs/15 §9.3, docs/14 §4.6 TransactionSearchRow):
// time, service, response time, HTTP status, error class, instance, SQL count and time, external
// call count and time, user id (only when the application collects it), profile, kind, version,
// and the transaction id (hidden by default). Missing values stay null and render as the dash of
// T-UI-007, never as 0. Include/exclude (05 §2.0-4) uses keys, not names: service, error class and
// version filter by their 64-bit key (decimal strings, C-175), instance by its id, HTTP status by
// the code and kind by the kind (the list turns an included kind into the global `kind`).
// Collected strings (service, instance, error class, user id, version) are Untrusted and reach the
// DOM only through the table's SafeText cells (SEC-60).
//
// Widths (docs/15 §13 "1366에서 가로 스크롤 금지"): at 1366 px the content is 1,134 px wide (1366 -
// 200 menu - 2 × 16 padding), 1,132 px inside the table border when the list spans it. The
// defaults are the narrowest that keep the headers and typical values readable (a filterable cell
// also holds its two include/exclude buttons, about 36 px); the time is month, day and time of day
// (a range is at most 24 h, docs/14 §4.5; the range picker shows the year).
// The 13 default columns still need 1,280 px (1,392 px with the user id column): whether the table
// may scroll sideways inside its own box or the default `cols=` shrinks is a PM decision (review
// qa-ux-sec-7); T-E2E-075b measures it (a fixme until that decision).
import type { TFunction } from 'i18next';

import type { TransactionSearchRow } from '../../../api/transactionSearch';
import type { DataTableColumn } from '../../../components/DataTable';

export type TxRow = TransactionSearchRow;

/** Bounded cache of the list's time formats per locale. */
const LIST_TIME_FORMATS = new Map<string, Intl.DateTimeFormat>();
const LIST_TIME_FORMAT_LIMIT = 8;

/**
 * The list's start time: month, day and time of day with seconds in the browser time zone
 * (docs/15 §12 Intl.DateTimeFormat, seconds), e.g. "11/15, 18:01:47" (en), "11. 15. 18:01:47" (ko).
 */
export function formatListTime(epochMs: number, locale: string): string {
  let format = LIST_TIME_FORMATS.get(locale);
  if (format === undefined) {
    if (LIST_TIME_FORMATS.size >= LIST_TIME_FORMAT_LIMIT) {
      LIST_TIME_FORMATS.clear();
    }
    format = new Intl.DateTimeFormat(locale, {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    });
    LIST_TIME_FORMATS.set(locale, format);
  }
  return format.format(epochMs);
}

/** Column ids (URL `cols=`, sort keys of the two server sorts). */
export const LIST_COLUMN_IDS = [
  'ts',
  'service',
  'elapsed_ms',
  'http_status',
  'error_class',
  'instance',
  'sql_count',
  'sql_time_ms',
  'ext_count',
  'ext_time_ms',
  'user_id',
  'has_profile',
  'kind',
  'version',
  'tx_id',
] as const;
export type ListColumnId = (typeof LIST_COLUMN_IDS)[number];

/** Columns the server can sort by (docs/14 §4.6: elapsed_ms:desc, ts:desc). */
export const SORTABLE_COLUMNS: ReadonlySet<string> = new Set(['ts', 'elapsed_ms']);

/** Filter fields of the include/exclude cells (the f= vocabulary of api/transactionSearch.ts). */
export const CELL_FILTER_FIELDS = {
  service: 'service',
  instance: 'instance',
  http_status: 'status',
  error_class: 'error_class',
  kind: 'kind',
  version: 'version',
} as const;

/** True when the application collects user ids (a row carries a plain or hashed user id mode). */
export function userIdCollected(rows: readonly TxRow[]): boolean {
  return rows.some((row) => row.user_id_mode === 'plain' || row.user_id_mode === 'hashed');
}

/** The HTTP status of a row, or null when the transaction is not an HTTP request. */
export function httpStatusOf(row: TxRow): number | null {
  return row.kind.toUpperCase() === 'HTTP' && row.http_status > 0 ? row.http_status : null;
}

/** Start time of a row in epoch ms (null when the server sent an unreadable instant). */
export function rowTime(row: TxRow): number | null {
  const ms = Date.parse(row.ts);
  return Number.isFinite(ms) ? ms : null;
}

function kindLabel(t: TFunction<['common', 'xlog']>, kind: string): string {
  switch (kind.toUpperCase()) {
    case 'HTTP':
      return t('common:term.kind.http');
    case 'BATCH':
      return t('common:term.kind.batch');
    case 'MESSAGE':
      return t('common:term.kind.message');
    default:
      return kind;
  }
}

/**
 * The column definitions. `withUserId` adds the user id column (only for applications that collect
 * it, 05 §2.1-8 "사용자 ID(수집 시)").
 */
export function listColumns(
  t: TFunction<['common', 'xlog']>,
  locale: string,
  withUserId: boolean,
): DataTableColumn<TxRow>[] {
  const columns: DataTableColumn<TxRow>[] = [
    {
      id: 'ts',
      header: t('xlog:list.columns.time'),
      value: (row) => {
        const ms = rowTime(row);
        return ms === null ? null : formatListTime(ms, locale);
      },
      sortable: true,
      hideable: false,
      width: 120,
    },
    {
      id: 'service',
      header: t('xlog:list.columns.service'),
      value: (row) => row.service,
      filterable: true,
      filterField: CELL_FILTER_FIELDS.service,
      filterValue: (row) => row.service_id,
      sortable: false,
      mono: true,
      width: 184,
    },
    {
      id: 'elapsed_ms',
      header: t('xlog:list.columns.elapsed'),
      value: (row) => row.elapsed_ms,
      kind: 'ms',
      sortable: true,
      hideable: false,
      width: 112,
    },
    {
      id: 'http_status',
      header: t('xlog:list.columns.http'),
      value: (row) => httpStatusOf(row),
      kind: 'none',
      filterable: true,
      filterField: CELL_FILTER_FIELDS.http_status,
      filterValue: (row) => {
        const code = httpStatusOf(row);
        return code === null ? null : String(code);
      },
      sortable: false,
      width: 80,
    },
    {
      id: 'error_class',
      header: t('xlog:list.columns.errorClass'),
      value: (row) => row.error_class,
      filterable: true,
      filterField: CELL_FILTER_FIELDS.error_class,
      filterValue: (row) => row.error_class_id,
      sortable: false,
      width: 128,
      mono: true,
    },
    {
      id: 'instance',
      header: t('xlog:list.columns.instance'),
      value: (row) => row.instance,
      filterable: true,
      filterField: CELL_FILTER_FIELDS.instance,
      filterValue: (row) => String(row.instance_id),
      sortable: false,
      width: 136,
    },
    {
      id: 'sql_count',
      header: t('xlog:list.columns.sqlCount'),
      value: (row) => row.sql_count,
      kind: 'count',
      sortable: false,
      width: 56,
    },
    {
      id: 'sql_time_ms',
      header: t('xlog:list.columns.sqlTime'),
      value: (row) => row.sql_time_ms,
      kind: 'ms',
      sortable: false,
      width: 72,
    },
    {
      id: 'ext_count',
      header: t('xlog:list.columns.extCount'),
      value: (row) => row.ext_count,
      kind: 'count',
      sortable: false,
      width: 72,
    },
    {
      id: 'ext_time_ms',
      header: t('xlog:list.columns.extTime'),
      value: (row) => row.ext_time_ms,
      kind: 'ms',
      sortable: false,
      width: 72,
    },
  ];
  if (withUserId) {
    columns.push({
      id: 'user_id',
      header: t('xlog:list.columns.userId'),
      value: (row) => row.user_id,
      sortable: false,
      mono: true,
      width: 112,
    });
  }
  columns.push(
    {
      id: 'has_profile',
      header: t('xlog:list.columns.profile'),
      value: (row) => (row.has_profile ? t('xlog:list.profile.yes') : t('xlog:list.profile.no')),
      sortable: false,
      width: 64,
    },
    {
      id: 'kind',
      header: t('xlog:list.columns.kind'),
      value: (row) => kindLabel(t, row.kind),
      filterable: true,
      filterField: CELL_FILTER_FIELDS.kind,
      filterValue: (row) => row.kind.toUpperCase(),
      sortable: false,
      width: 88,
    },
    {
      id: 'version',
      header: t('xlog:list.columns.version'),
      value: (row) => row.version,
      filterable: true,
      filterField: CELL_FILTER_FIELDS.version,
      filterValue: (row) => row.version_id,
      sortable: false,
      mono: true,
      width: 96,
    },
    {
      id: 'tx_id',
      header: t('xlog:list.columns.txId'),
      value: (row) => row.tx_id,
      sortable: false,
      defaultHidden: true,
      mono: true,
      width: 160,
    },
  );
  return columns;
}

/** Visible column ids of a URL `cols=` value (unknown ids dropped), or undefined for the default. */
export function columnsFromUrl(cols: string | undefined): string[] | undefined {
  if (cols === undefined) {
    return undefined;
  }
  const known = new Set<string>(LIST_COLUMN_IDS);
  const ids = cols.split(',').filter((id) => known.has(id));
  return ids.length === 0 ? undefined : ids;
}

/** The URL `cols=` value of a visible column list; undefined when it is the default set. */
export function columnsToUrl(
  ids: readonly string[],
  defaults: readonly string[],
): string | undefined {
  const same = ids.length === defaults.length && defaults.every((id) => ids.includes(id));
  if (same || ids.length === 0) {
    return undefined;
  }
  return ids.join(',');
}
