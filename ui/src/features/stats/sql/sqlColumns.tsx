// SPDX-License-Identifier: Apache-2.0
//
// Columns and filter fields of the SQL list (docs/15 §9.7, 05 §2.5-1/-3, docs/14 §4.8 SqlStats):
// the normalized statement (collected text, monospace, one line), the JDBC target, counts and
// times (total, execution, fetch, average, max), the bucket-estimated percentiles with the
// estimated badge (C-14) and fetched rows, with the change of count and p95 (relative) and of the
// error rate (percentage points, on the errors column). Value include/exclude: `f.sql=<SQL key>`
// (the XLog vocabulary) and `f.db=<target key>` (the server's `db_id` for one target).
import type { TFunction } from 'i18next';

import { isSortField, type SqlStats } from '../../../api/stats';
import type { DataTableColumn } from '../../../components/DataTable';
import { linkColumn, numberColumn } from '../common/columns';
import type { FilterAccessors } from '../common/model';

type NumberField =
  | 'count'
  | 'errors'
  | 'sum_ms'
  | 'exec_ms'
  | 'fetch_ms'
  | 'avg_ms'
  | 'max_ms'
  | 'p50_ms'
  | 'p95_ms'
  | 'p99_ms'
  | 'fetch_rows';

const NUMBER_FIELDS: readonly NumberField[] = [
  'count',
  'errors',
  'sum_ms',
  'exec_ms',
  'fetch_ms',
  'avg_ms',
  'max_ms',
  'p50_ms',
  'p95_ms',
  'p99_ms',
  'fetch_rows',
];

/**
 * Row keys of the SQL list: the SQL key (the WO-36 data-testid contract), or SQL key and target
 * key (`<sql_id>:<db_id>`) for a statement that runs on several JDBC targets (one row per
 * statement and target, docs/14 §4.8), so that keys stay unique.
 */
export function sqlRowKeys(rows: readonly SqlStats[]): (row: SqlStats) => string {
  const seen = new Map<string, number>();
  for (const row of rows) {
    seen.set(row.sql_id, (seen.get(row.sql_id) ?? 0) + 1);
  }
  return (row) => ((seen.get(row.sql_id) ?? 0) > 1 ? `${row.sql_id}:${row.db.db_id}` : row.sql_id);
}

/** Filter fields of the SQL list: `db` also goes to the server (`db_id`) for a single target. */
export const SQL_ACCESSORS: FilterAccessors<SqlStats> = {
  sql: { key: (row) => row.sql_id, text: (row) => row.sql_normalized },
  db: { key: (row) => row.db.db_id, text: (row) => row.db.target },
  ...Object.fromEntries(
    NUMBER_FIELDS.map((field) => [field, { number: (row: SqlStats) => row[field] }]),
  ),
};

export interface SqlColumnsOptions {
  /** Detail link of a row. */
  readonly href: (row: SqlStats) => string;
  readonly onNavigate: (path: string) => void;
  /** Some row's percentiles are bucket estimates (C-14). */
  readonly estimated: boolean;
}

/** The columns of the SQL list. */
export function sqlColumns(
  t: TFunction<['common', 'stats']>,
  { href, onNavigate, estimated }: SqlColumnsOptions,
): DataTableColumn<SqlStats>[] {
  const sortable = (field: string): boolean => isSortField('sql', field);
  return [
    linkColumn({
      id: 'sql',
      header: t('stats:columns.sql'),
      value: (row) => row.sql_normalized,
      href,
      onNavigate,
      mono: true,
      // One line: DataTable rows have a fixed height; the full text is in the title.
      maxLines: 1,
      filterable: true,
      filterField: 'sql',
      filterValue: (row) => row.sql_id,
      filter: { type: 'text' },
      sortable: false,
      hideable: false,
      width: 360,
    }),
    {
      id: 'db',
      header: t('stats:columns.db'),
      value: (row) => row.db.target,
      mono: true,
      filterable: true,
      filterField: 'db',
      filterValue: (row) => row.db.db_id,
      sortable: false,
      width: 200,
    },
    numberColumn({
      id: 'count',
      header: t('stats:columns.count'),
      unit: 'count',
      value: (row) => row.count,
      sortable: sortable('count'),
      delta: { value: (row) => row.delta?.count, mode: 'relative', goodWhen: 'neutral' },
    }),
    numberColumn({
      id: 'errors',
      header: t('stats:columns.errors'),
      unit: 'count',
      value: (row) => row.errors,
      sortable: sortable('errors'),
      delta: {
        field: 'error_rate',
        value: (row) => row.delta?.error_rate,
        mode: 'points',
        goodWhen: 'down',
      },
    }),
    numberColumn({
      id: 'sum_ms',
      header: t('stats:columns.sum_ms'),
      unit: 'ms',
      value: (row) => row.sum_ms,
      sortable: sortable('sum_ms'),
    }),
    numberColumn({
      id: 'exec_ms',
      header: t('stats:columns.exec_ms'),
      unit: 'ms',
      value: (row) => row.exec_ms,
      sortable: sortable('exec_ms'),
      defaultHidden: true,
    }),
    numberColumn({
      id: 'fetch_ms',
      header: t('stats:columns.fetch_ms'),
      unit: 'ms',
      value: (row) => row.fetch_ms,
      sortable: sortable('fetch_ms'),
      defaultHidden: true,
    }),
    numberColumn({
      id: 'avg_ms',
      header: t('stats:columns.avg_ms'),
      unit: 'ms',
      value: (row) => row.avg_ms,
      sortable: sortable('avg_ms'),
    }),
    numberColumn({
      id: 'max_ms',
      header: t('stats:columns.max_ms'),
      unit: 'ms',
      value: (row) => row.max_ms,
      sortable: sortable('max_ms'),
    }),
    numberColumn({
      id: 'p50_ms',
      header: t('stats:columns.p50_ms'),
      unit: 'ms',
      value: (row) => row.p50_ms,
      sortable: sortable('p50_ms'),
      estimated,
      defaultHidden: true,
    }),
    numberColumn({
      id: 'p95_ms',
      header: t('stats:columns.p95_ms'),
      unit: 'ms',
      value: (row) => row.p95_ms,
      sortable: sortable('p95_ms'),
      estimated,
      delta: { value: (row) => row.delta?.p95_ms, mode: 'relative', goodWhen: 'down' },
    }),
    numberColumn({
      id: 'p99_ms',
      header: t('stats:columns.p99_ms'),
      unit: 'ms',
      value: (row) => row.p99_ms,
      sortable: sortable('p99_ms'),
      estimated,
    }),
    numberColumn({
      id: 'fetch_rows',
      header: t('stats:columns.fetch_rows'),
      unit: 'count',
      value: (row) => row.fetch_rows,
      sortable: sortable('fetch_rows'),
    }),
  ];
}
