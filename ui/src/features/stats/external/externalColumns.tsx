// SPDX-License-Identifier: Apache-2.0
//
// Columns and filter fields of the external calls list (docs/15 §9.7, 05 §2.5-1/-4, docs/14 §4.8
// ExternalStats): the host (collected text, `scheme://host:port`), the path pattern (not stored:
// the statistics key is the host only, always null and shown as the dash, ADR-0010 C-178 (3),
// C-223 (5)), counts
// (calls, errors, timeouts, connection failures), the bucket-estimated percentiles with the
// estimated badge (C-14), total, average and max time, with the change of count and p95 (relative)
// and of the error rate (percentage points, on the errors column). Value include/exclude:
// `f.host=<host key>` (client side: the API has no host parameter, WO-36 plan PM question 2).
import type { TFunction } from 'i18next';

import { isSortField, type ExternalStats } from '../../../api/stats';
import type { DataTableColumn } from '../../../components/DataTable';
import { linkColumn, numberColumn } from '../common/columns';
import type { FilterAccessors } from '../common/model';

type NumberField =
  | 'count'
  | 'errors'
  | 'timeouts'
  | 'connect_failures'
  | 'sum_ms'
  | 'avg_ms'
  | 'max_ms'
  | 'p50_ms'
  | 'p95_ms'
  | 'p99_ms';

const NUMBER_FIELDS: readonly NumberField[] = [
  'count',
  'errors',
  'timeouts',
  'connect_failures',
  'sum_ms',
  'avg_ms',
  'max_ms',
  'p50_ms',
  'p95_ms',
  'p99_ms',
];

/** Row key of the external list: the host key (the WO-36 data-testid contract). */
export function externalRowKey(row: ExternalStats): string {
  return row.host_id;
}

/** Filter fields of the external list (all applied to the received rows). */
export const EXTERNAL_ACCESSORS: FilterAccessors<ExternalStats> = {
  host: { key: (row) => row.host_id, text: (row) => row.host },
  path_pattern: { text: (row) => row.path_pattern },
  ...Object.fromEntries(
    NUMBER_FIELDS.map((field) => [field, { number: (row: ExternalStats) => row[field] }]),
  ),
};

export interface ExternalColumnsOptions {
  /** Detail link of a row. */
  readonly href: (row: ExternalStats) => string;
  readonly onNavigate: (path: string) => void;
  /** Some row's percentiles are bucket estimates (C-14). */
  readonly estimated: boolean;
}

/** The columns of the external calls list. */
export function externalColumns(
  t: TFunction<['common', 'stats']>,
  { href, onNavigate, estimated }: ExternalColumnsOptions,
): DataTableColumn<ExternalStats>[] {
  const sortable = (field: string): boolean => isSortField('external', field);
  return [
    linkColumn({
      id: 'host',
      header: t('stats:columns.host'),
      value: (row) => row.host,
      href,
      onNavigate,
      mono: true,
      filterable: true,
      filterField: 'host',
      filterValue: (row) => row.host_id,
      filter: { type: 'text' },
      sortable: false,
      hideable: false,
      width: 280,
    }),
    {
      id: 'path_pattern',
      header: t('stats:columns.path_pattern'),
      // Always null, not stored (host key only, C-178 (3), C-223 (5)): the missing dash.
      value: (row) => row.path_pattern,
      mono: true,
      sortable: false,
      width: 160,
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
      id: 'timeouts',
      header: t('stats:columns.timeouts'),
      unit: 'count',
      value: (row) => row.timeouts,
      sortable: sortable('timeouts'),
    }),
    numberColumn({
      id: 'connect_failures',
      header: t('stats:columns.connect_failures'),
      unit: 'count',
      value: (row) => row.connect_failures,
      sortable: sortable('connect_failures'),
    }),
    numberColumn({
      id: 'p50_ms',
      header: t('stats:columns.p50_ms'),
      unit: 'ms',
      value: (row) => row.p50_ms,
      sortable: sortable('p50_ms'),
      estimated,
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
      defaultHidden: true,
    }),
    numberColumn({
      id: 'sum_ms',
      header: t('stats:columns.sum_ms'),
      unit: 'ms',
      value: (row) => row.sum_ms,
      sortable: sortable('sum_ms'),
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
  ];
}
