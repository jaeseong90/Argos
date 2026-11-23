// SPDX-License-Identifier: Apache-2.0
//
// A breakdown of a statistics detail (docs/15 §9.7, 05 §2.5): versions, instances, calling
// services, the cross-view Top N (SQL, external hosts, error groups) as StatsBreakdown rows
// (docs/14 §4.8: id, name, count, share, avg, p95, errors). The rows are complete, so the table
// sorts them itself. Names are collected strings (SafeText/SafeLink); numbers keep their raw value
// (`stats-value`); a missing name shows the dash. Row key = the API id (decimal string, C-175).
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import type { StatsBreakdown } from '../../../api/stats';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';

import { linkColumn, numberColumn } from './columns';

export type BreakdownMetric = 'count' | 'share' | 'avg_ms' | 'p95_ms' | 'errors';

export interface BreakdownTableProps {
  /** DataTable id and test id (`stats-by-version`, `stats-top-sql`, ...). */
  readonly tableId: string;
  readonly title: string;
  /** Header of the name column ("Version", "Instance", "SQL", ...). */
  readonly nameHeader: string;
  readonly rows: readonly StatsBreakdown[];
  /** Columns after the name, in order. Default: count, share, avg, p95, errors. */
  readonly metrics?: readonly BreakdownMetric[];
  /** Detail link of a row (SafeLink), or null for plain text. */
  readonly href?: (row: StatsBreakdown) => string | null;
  /** Monospace name, one line at the fixed row height (SQL text, hosts; full text in the title). */
  readonly mono?: boolean;
  /** The bucket percentiles are estimates (C-14): estimated badge on the p95 header. */
  readonly estimated?: boolean;
  /** Text of an empty breakdown (the next action). */
  readonly emptyText: string;
  /** A note under the title (e.g. the raw window of instance breakdowns). */
  readonly note?: string;
}

const DEFAULT_METRICS: readonly BreakdownMetric[] = [
  'count',
  'share',
  'avg_ms',
  'p95_ms',
  'errors',
];

export function BreakdownTable({
  tableId,
  title,
  nameHeader,
  rows,
  metrics = DEFAULT_METRICS,
  href,
  mono = false,
  estimated = false,
  emptyText,
  note,
}: BreakdownTableProps) {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const columns = useMemo<DataTableColumn<StatsBreakdown>[]>(() => {
    const name: DataTableColumn<StatsBreakdown> =
      href === undefined
        ? {
            id: 'name',
            header: nameHeader,
            value: (row) => row.name,
            mono,
            maxLines: 1,
            hideable: false,
          }
        : linkColumn({
            id: 'name',
            header: nameHeader,
            value: (row) => row.name ?? row.id,
            href,
            onNavigate: (path) => {
              void navigate(path);
            },
            mono,
            maxLines: 1,
            hideable: false,
          });
    const metric = (id: BreakdownMetric): DataTableColumn<StatsBreakdown> => {
      switch (id) {
        case 'count':
          return numberColumn({
            id,
            header: t('stats:columns.count'),
            unit: 'count',
            value: (row) => row.count,
            sortable: true,
            rangeFilter: false,
          });
        case 'share':
          return numberColumn({
            id,
            header: t('stats:columns.share'),
            unit: 'ratio',
            value: (row) => row.share,
            sortable: true,
            rangeFilter: false,
          });
        case 'avg_ms':
          return numberColumn({
            id,
            header: t('stats:columns.avg_ms'),
            unit: 'ms',
            value: (row) => row.avg_ms,
            sortable: true,
            rangeFilter: false,
          });
        case 'p95_ms':
          return numberColumn({
            id,
            header: t('stats:columns.p95_ms'),
            unit: 'ms',
            value: (row) => row.p95_ms,
            sortable: true,
            rangeFilter: false,
            estimated,
          });
        case 'errors':
          return numberColumn({
            id,
            header: t('stats:columns.errors'),
            unit: 'count',
            value: (row) => row.errors,
            sortable: true,
            rangeFilter: false,
          });
      }
    };
    return [name, ...metrics.map(metric)];
  }, [href, nameHeader, mono, metrics, estimated, t, navigate]);
  const titleId = `${tableId}-title`;
  return (
    <section className="stats-section" aria-labelledby={titleId} data-testid={`${tableId}-section`}>
      <h2 id={titleId} className="stats-section__title">
        {title}
      </h2>
      {note === undefined ? null : <p className="stats-note">{note}</p>}
      <DataTable
        tableId={tableId}
        caption={title}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        dataComplete
        density="dense"
        empty={{ title: emptyText }}
      />
    </section>
  );
}
