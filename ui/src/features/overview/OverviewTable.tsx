// SPDX-License-Identifier: Apache-2.0
//
// Table view of the overview (docs/15 §9.1 `OverviewTable`, 05 §2.10): one dense row (20 px) per
// application with the name and the ten required items, on the shared DataTable. The page sorts
// (sortOverview: abnormal applications pinned on top whatever the sort), so the table only shows
// the controlled sort on the four sortable headers and reports clicks. A row click (or Enter on a
// focused row) opens the application's dashboard with the global keys (time range included); the
// name is also a real link for middle-click and keyboard users.
import { useMemo, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { OverviewApp } from '../../api/overview';
import { DataTable, type DataTableColumn, type SortSpec } from '../../components/DataTable';
import { SafeText } from '../../components/SafeText';
import { normalizeStatus } from '../../components/StatusBadge';

import { DeployCell, FiringCell, InstancesCell, NumberCell, StatusCell, TrendCell } from './cells';
import { isOverviewSortId, type OverviewSort, type SortedRow } from './sortOverview';

export interface OverviewTableProps {
  readonly rows: readonly SortedRow[];
  /** The sort in effect (the URL's, or the default status order). */
  readonly sort: OverviewSort;
  readonly onSort: (sort: OverviewSort) => void;
  /** The dashboard link of an application (global keys carried). */
  readonly hrefOf: (appId: number) => string;
  readonly onOpen: (appId: number) => void;
  /** Controls shown in the table's toolbar row (filters, sort, view). */
  readonly toolbar: ReactNode;
  /** Clock of the relative deploy times. */
  readonly now: number;
}

export function OverviewTable({
  rows,
  sort,
  onSort,
  hrefOf,
  onOpen,
  toolbar,
  now,
}: OverviewTableProps) {
  const { t } = useTranslation(['common', 'overview']);
  const columns = useMemo<DataTableColumn<SortedRow>[]>(
    () => [
      {
        id: 'name',
        header: t('overview:table.name'),
        value: (row) => row.app.name,
        width: 116,
        sortable: false,
        hideable: false,
        render: (row) => <NameCell app={row.app} href={hrefOf(row.app.app_id)} />,
      },
      {
        id: 'status',
        header: t('overview:table.status'),
        value: (row) => row.app.status,
        width: 80,
        hideable: false,
        render: (row) => <StatusCell app={row.app} />,
      },
      {
        id: 'instances',
        header: t('overview:table.instances'),
        value: (row) => row.app.instances.up,
        kind: 'count',
        width: 72,
        sortable: false,
        render: (row) => <InstancesCell app={row.app} />,
      },
      {
        id: 'tps',
        header: t('overview:table.tps'),
        value: (row) => row.app.tps,
        kind: 'rate',
        // Four-digit rates ("1,409.8/s") fit without clipping (T-E2E-090 checks every cell).
        width: 72,
        sortable: false,
        render: (row) => <NumberCell app={row.app} field="tps" />,
      },
      {
        id: 'p95',
        header: t('overview:table.p95'),
        value: (row) => row.app.p95_ms,
        kind: 'ms',
        width: 60,
        render: (row) => <NumberCell app={row.app} field="p95" />,
      },
      {
        id: 'error_rate',
        header: t('overview:table.errorRate'),
        value: (row) => row.app.error_rate,
        kind: 'ratio',
        width: 60,
        render: (row) => <NumberCell app={row.app} field="error-rate" />,
      },
      {
        id: 'active',
        header: t('overview:table.active'),
        value: (row) => row.app.active,
        kind: 'count',
        width: 56,
        sortable: false,
        render: (row) => <NumberCell app={row.app} field="active" />,
      },
      {
        id: 'batches',
        header: t('overview:table.batches'),
        value: (row) => row.app.batch_running,
        kind: 'count',
        width: 60,
        sortable: false,
        render: (row) => <NumberCell app={row.app} field="batches" />,
      },
      {
        id: 'firing',
        header: t('overview:table.firing'),
        value: () => null,
        kind: 'count',
        width: 60,
        render: (row) => <FiringCell app={row.app} />,
      },
      {
        id: 'deploy',
        header: t('overview:table.deploy'),
        value: (row) => row.app.last_deploy?.ts ?? null,
        width: 112,
        sortable: false,
        render: (row) => <DeployCell app={row.app} now={now} />,
      },
      {
        id: 'trend',
        header: t('overview:table.trend'),
        value: () => null,
        // Three 36 px sparklines, two 4 px gaps and the cell padding.
        width: 132,
        sortable: false,
        render: (row) => <TrendCell app={row.app} />,
      },
    ],
    [t, hrefOf, now],
  );

  const tableSort: SortSpec = { id: sort.id, desc: sort.desc };
  return (
    <div className="overview-table" data-testid="overview-table">
      <DataTable<SortedRow>
        tableId="overview"
        caption={t('overview:table.caption')}
        columns={columns}
        rows={rows}
        rowKey={(row) => String(row.app.app_id)}
        sort={tableSort}
        onSortChange={(next) => {
          if (next !== null && isOverviewSortId(next.id)) {
            onSort({ id: next.id, desc: next.desc });
          }
        }}
        onRowOpen={(row) => {
          onOpen(row.app.app_id);
        }}
        density="dense"
        toolbar={toolbar}
        rowAttributes={(row) => ({
          'data-testid': `overview-row-${String(row.app.app_id)}`,
          'data-app-id': String(row.app.app_id),
          'data-pinned': row.pinned ? 'true' : 'false',
          'data-status': normalizeStatus(row.app.status),
        })}
      />
    </div>
  );
}

/** Application name (collected, SafeText) as the dashboard link, with its environment. */
export function NameCell({ app, href }: { readonly app: OverviewApp; readonly href: string }) {
  return (
    <Link to={href} className="overview-name" data-testid={`overview-link-${String(app.app_id)}`}>
      <SafeText value={app.name} className="overview-name__text" />
      <SafeText value={app.env} className="overview-name__env" />
    </Link>
  );
}
