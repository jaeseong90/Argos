// SPDX-License-Identifier: Apache-2.0
//
// Columns and filter fields of the services list (docs/15 §9.7, 05 §2.5-1, docs/14 §4.8
// ServiceStats): every ServiceRow field with the change of count, p95 (relative) and error rate
// (percentage points). Service percentiles merge DD sketches (C-53), so they carry no estimated
// badge (C-14 marks bucket estimates only). Value include/exclude uses keys: `f.service=<service
// key>` and, with the per-version split, `f.version=<version key>` (the XLog vocabulary).
import type { TFunction } from 'i18next';

import { isSortField, type ServiceStats } from '../../../api/stats';
import type { DataTableColumn } from '../../../components/DataTable';
import { linkColumn, numberColumn } from '../common/columns';
import type { FilterAccessors } from '../common/model';

type NumberField =
  | 'count'
  | 'errors'
  | 'error_rate'
  | 'avg_ms'
  | 'p50_ms'
  | 'p95_ms'
  | 'p99_ms'
  | 'max_ms'
  | 'apdex'
  | 'sum_ms'
  | 'sql_time_ratio'
  | 'ext_time_ratio';

const NUMBER_FIELDS: readonly NumberField[] = [
  'count',
  'errors',
  'error_rate',
  'avg_ms',
  'p50_ms',
  'p95_ms',
  'p99_ms',
  'max_ms',
  'apdex',
  'sum_ms',
  'sql_time_ratio',
  'ext_time_ratio',
];

/** Row key: the service key, plus the version key with the per-version split. */
export function serviceRowKey(row: ServiceStats): string {
  return row.version_id === undefined ? row.service_id : `${row.service_id}:${row.version_id}`;
}

/** Filter fields of the services list (client-side, within the received top N). */
export function serviceAccessors(split: boolean): FilterAccessors<ServiceStats> {
  const accessors: Record<string, FilterAccessors<ServiceStats>[string]> = {
    service: { key: (row) => row.service_id, text: (row) => row.service },
  };
  if (split) {
    accessors.version = { key: (row) => row.version_id, text: (row) => row.version };
  }
  for (const field of NUMBER_FIELDS) {
    accessors[field] = { number: (row) => row[field] };
  }
  return accessors;
}

export interface ServiceColumnsOptions {
  readonly split: boolean;
  /** Detail link of a row. */
  readonly href: (row: ServiceStats) => string;
  /** In-app navigation (the router's navigate). */
  readonly onNavigate: (path: string) => void;
}

/** The columns of the services list. */
export function serviceColumns(
  t: TFunction<['common', 'stats']>,
  { split, href, onNavigate }: ServiceColumnsOptions,
): DataTableColumn<ServiceStats>[] {
  const sortable = (field: string): boolean => isSortField('services', field);
  const columns: DataTableColumn<ServiceStats>[] = [
    linkColumn({
      id: 'service',
      header: t('stats:columns.service'),
      value: (row) => row.service,
      href,
      onNavigate,
      mono: true,
      filterable: true,
      filterField: 'service',
      filterValue: (row) => row.service_id,
      filter: { type: 'text' },
      sortable: false,
      hideable: false,
      width: 280,
    }),
  ];
  if (split) {
    columns.push({
      id: 'version',
      header: t('stats:columns.version'),
      value: (row) => row.version,
      mono: true,
      filterable: true,
      filterField: 'version',
      filterValue: (row) => row.version_id,
      filter: { type: 'text' },
      sortable: false,
      width: 140,
    });
  }
  columns.push(
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
    }),
    numberColumn({
      id: 'error_rate',
      header: t('stats:columns.error_rate'),
      unit: 'ratio',
      value: (row) => row.error_rate,
      sortable: sortable('error_rate'),
      delta: { value: (row) => row.delta?.error_rate, mode: 'points', goodWhen: 'down' },
    }),
    numberColumn({
      id: 'avg_ms',
      header: t('stats:columns.avg_ms'),
      unit: 'ms',
      value: (row) => row.avg_ms,
      sortable: sortable('avg_ms'),
    }),
    numberColumn({
      id: 'p50_ms',
      header: t('stats:columns.p50_ms'),
      unit: 'ms',
      value: (row) => row.p50_ms,
      sortable: sortable('p50_ms'),
      defaultHidden: true,
    }),
    numberColumn({
      id: 'p95_ms',
      header: t('stats:columns.p95_ms'),
      unit: 'ms',
      value: (row) => row.p95_ms,
      sortable: sortable('p95_ms'),
      delta: { value: (row) => row.delta?.p95_ms, mode: 'relative', goodWhen: 'down' },
    }),
    numberColumn({
      id: 'p99_ms',
      header: t('stats:columns.p99_ms'),
      unit: 'ms',
      value: (row) => row.p99_ms,
      sortable: sortable('p99_ms'),
    }),
    numberColumn({
      id: 'max_ms',
      header: t('stats:columns.max_ms'),
      unit: 'ms',
      value: (row) => row.max_ms,
      sortable: sortable('max_ms'),
    }),
    numberColumn({
      id: 'apdex',
      header: t('stats:columns.apdex'),
      unit: 'none',
      value: (row) => row.apdex,
      sortable: sortable('apdex'),
    }),
    numberColumn({
      id: 'sum_ms',
      header: t('stats:columns.sum_ms'),
      unit: 'ms',
      value: (row) => row.sum_ms,
      sortable: sortable('sum_ms'),
    }),
    numberColumn({
      id: 'sql_time_ratio',
      header: t('stats:columns.sql_time_ratio'),
      unit: 'ratio',
      value: (row) => row.sql_time_ratio,
      sortable: sortable('sql_time_ratio'),
      defaultHidden: true,
    }),
    numberColumn({
      id: 'ext_time_ratio',
      header: t('stats:columns.ext_time_ratio'),
      unit: 'ratio',
      value: (row) => row.ext_time_ratio,
      sortable: sortable('ext_time_ratio'),
      defaultHidden: true,
    }),
  );
  return columns;
}
