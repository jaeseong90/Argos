// SPDX-License-Identifier: Apache-2.0
//
// Instance comparison table (05 §2.3-4, F-U20, docs/15 §9.2, S-14): instance, state, TPS, p95,
// error rate, active, heap, CPU, pool usage and uptime of every instance of the range, sorted on
// the client (the response is complete, docs/15 §6.3) with the sort in the URL. The outlier cells
// are exactly the server's flags `outlier.p95/error_rate/cpu` (ADR-0010 C-177, never recomputed
// here); a flagged cell is marked by text as well as colour. The instance value filter (05 §2.0-4)
// writes f.instance (the shell's FilterChipBar shows the chip and the dashboard asks again), and
// CSV is the server export only (`format=csv`, DSN-15-06). Instance names are collected strings
// and reach the DOM through the table's SafeText cells (SEC-60).
import type { TFunction } from 'i18next';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import type { InstanceComparison } from '../../../api/dashboard';
import { DataTable, type DataTableColumn, type SortSpec } from '../../../components/DataTable';
import type { ApiErrorInfo } from '../../../components/ErrorState';
import type { CellFilter } from '../../../components/filters';
import { ValueText } from '../../../components/ValueText';
import type { ValueUnit } from '../../../utils/format';

import { isOutlier, OUTLIER_COLUMNS, type OutlierFlag } from './sectionsModel';

export interface InstanceCompareTableProps {
  readonly rows: readonly InstanceComparison[];
  readonly sort: SortSpec | null;
  readonly onSortChange: (sort: SortSpec | null) => void;
  /** Include (eq) or exclude (ne) of an instance id from the instance column (f.instance). */
  readonly onInstanceFilter: (instanceId: number, op: 'eq' | 'ne') => void;
  readonly onExportCsv?: () => void;
  readonly csvBusy?: boolean;
  readonly loading?: boolean;
  readonly error?: ApiErrorInfo | null;
  readonly onRetry?: () => void;
}

/** A metric cell that carries the server's outlier flag: highlighted and labelled. */
function outlierCell(
  row: InstanceComparison,
  flag: OutlierFlag,
  value: number | null,
  unit: ValueUnit,
  label: string,
) {
  if (!isOutlier(row, flag)) {
    return <ValueText value={value} unit={unit} />;
  }
  return (
    <span className="dashboard-compare__outlier" data-outlier={flag} title={label}>
      <ValueText value={value} unit={unit} />
      <span className="visually-hidden">{label}</span>
    </span>
  );
}

/** Columns of the comparison (F-U20 order; widths fit the 1366 px layout, docs/15 §13). */
export function compareColumns(
  t: TFunction<['common', 'dashboard']>,
): DataTableColumn<InstanceComparison>[] {
  const outlier = t('dashboard:compare.outlier');
  return [
    {
      id: 'instance',
      header: t('dashboard:compare.columns.instance'),
      value: (row) => row.name,
      kind: 'text',
      filterable: true,
      filterField: 'instance',
      filterValue: (row) => String(row.instance_id),
      hideable: false,
      width: 200,
    },
    {
      id: 'state',
      header: t('dashboard:compare.columns.state'),
      value: (row) => row.state,
      kind: 'status',
      width: 104,
    },
    {
      id: 'tps',
      header: t('dashboard:compare.columns.tps'),
      value: (row) => row.tps,
      kind: 'rate',
      width: 88,
    },
    {
      id: OUTLIER_COLUMNS.p95,
      header: t('dashboard:compare.columns.p95'),
      value: (row) => row.p95_ms,
      kind: 'ms',
      width: 96,
      render: (row) => outlierCell(row, 'p95', row.p95_ms, 'ms', outlier),
    },
    {
      id: OUTLIER_COLUMNS.error_rate,
      header: t('dashboard:compare.columns.errorRate'),
      value: (row) => row.error_rate,
      kind: 'ratio',
      width: 96,
      render: (row) => outlierCell(row, 'error_rate', row.error_rate, 'ratio', outlier),
    },
    {
      id: 'active',
      header: t('dashboard:compare.columns.active'),
      value: (row) => row.active,
      kind: 'count',
      width: 80,
    },
    {
      id: 'heap_ratio',
      header: t('dashboard:compare.columns.heap'),
      value: (row) => row.heap_ratio,
      kind: 'ratio',
      width: 80,
    },
    {
      id: OUTLIER_COLUMNS.cpu,
      header: t('dashboard:compare.columns.cpu'),
      value: (row) => row.cpu,
      kind: 'ratio',
      width: 88,
      render: (row) => outlierCell(row, 'cpu', row.cpu, 'ratio', outlier),
    },
    {
      id: 'pool_usage_ratio',
      header: t('dashboard:compare.columns.pool'),
      value: (row) => row.pool_usage_ratio,
      kind: 'ratio',
      width: 88,
    },
    {
      id: 'uptime_s',
      header: t('dashboard:compare.columns.uptime'),
      value: (row) => row.uptime_s,
      kind: 'seconds',
      width: 96,
    },
  ];
}

export function InstanceCompareTable({
  rows,
  sort,
  onSortChange,
  onInstanceFilter,
  onExportCsv,
  csvBusy = false,
  loading = false,
  error,
  onRetry,
}: InstanceCompareTableProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  const columns = useMemo(() => compareColumns(t), [t]);
  const outliers = rows.reduce(
    (n, row) =>
      n + (['p95', 'error_rate', 'cpu'] as const).filter((flag) => isOutlier(row, flag)).length,
    0,
  );
  return (
    <section
      className="dashboard-compare"
      aria-labelledby="dashboard-compare-title"
      data-testid="dashboard-compare-table"
      data-outlier-count={outliers}
    >
      <h2 id="dashboard-compare-title" className="dashboard-sections__title">
        {t('dashboard:compare.title')}
      </h2>
      <p className="dashboard-sections__note">{t('dashboard:compare.outlierRule')}</p>
      <DataTable<InstanceComparison>
        tableId="dashboard-instance-compare"
        caption={t('dashboard:compare.title')}
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.instance_id)}
        dataComplete
        sort={sort}
        onSortChange={onSortChange}
        onCellFilter={(filter: CellFilter) => {
          const id = Number(filter.value);
          if (filter.field === 'instance' && Number.isSafeInteger(id) && id > 0) {
            onInstanceFilter(id, filter.op);
          }
        }}
        openOnClick={false}
        onExportCsv={rows.length > 0 ? onExportCsv : undefined}
        csvBusy={csvBusy}
        loading={loading}
        error={error ?? null}
        onRetry={onRetry}
        empty={{ compact: true, title: t('dashboard:compare.empty') }}
        density="dense"
      />
    </section>
  );
}
