// SPDX-License-Identifier: Apache-2.0
//
// Alert statistics (05 §2.9-5, docs/14 §4.13 GET /alerts/stats): per rule over the last 7 or 30
// days the firings, the average firing duration, the average time to the acknowledgement and to
// the resolution, most firings first (finding noisy rules). The server sends at most 1,000 rules,
// so the table sorts the complete result itself. A missing average (no resolved or acknowledged
// alert) is the dash, never 0 (T-UI-007).
import type { UseQueryResult } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import type { AlertRuleStats } from '../../../api/alerts';
import type { Enveloped } from '../../../api/client';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';

export interface StatsTabProps {
  readonly days: '7' | '30';
  readonly onDaysChange: (days: '7' | '30') => void;
  readonly query: UseQueryResult<Enveloped<AlertRuleStats[]>>;
  readonly allApps: boolean;
}

export function StatsTab({ days, onDaysChange, query, allApps }: StatsTabProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const columns: DataTableColumn<AlertRuleStats>[] = [
    {
      id: 'rule',
      header: t('alerts:stats.column.rule'),
      value: (row) => row.name,
      width: 280,
      sortable: true,
      render: (row) => <SafeText value={row.name} />,
    },
    {
      id: 'firings',
      header: t('alerts:stats.column.firings'),
      value: (row) => row.firings,
      kind: 'count',
      sortable: true,
    },
    {
      id: 'avg_duration_s',
      header: t('alerts:stats.column.avgDuration'),
      value: (row) => row.avg_duration_s,
      kind: 'seconds',
      width: 150,
      sortable: true,
    },
    {
      id: 'avg_time_to_ack_s',
      header: t('alerts:stats.column.timeToAck'),
      value: (row) => row.avg_time_to_ack_s,
      kind: 'seconds',
      width: 150,
      sortable: true,
    },
    {
      id: 'avg_time_to_resolve_s',
      header: t('alerts:stats.column.timeToResolve'),
      value: (row) => row.avg_time_to_resolve_s,
      kind: 'seconds',
      width: 150,
      sortable: true,
    },
  ];
  const rows = query.data?.data ?? [];
  return (
    <section
      className="alerts-stats"
      aria-labelledby="alerts-stats-title"
      data-testid="alerts-stats"
    >
      <div className="alerts-toolbar">
        <h2 id="alerts-stats-title" className="alerts-panel__title">
          {allApps ? t('alerts:stats.titleAll') : t('alerts:stats.titleApp')}
        </h2>
        <div className="alerts-segment" role="group" aria-label={t('alerts:stats.period')}>
          {(['7', '30'] as const).map((value) => (
            <button
              key={value}
              type="button"
              className={`control-button${days === value ? ' alerts-segment--on' : ''}`}
              aria-pressed={days === value}
              onClick={() => {
                onDaysChange(value);
              }}
              data-testid={`alerts-stats-days-${value}`}
            >
              {t('alerts:stats.days', { count: Number(value) })}
            </button>
          ))}
        </div>
      </div>
      {query.isError ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="alerts-stats-error"
        />
      ) : query.isPending ? (
        <LoadingState variant="table" slowHint="wait" data-testid="alerts-stats-loading" />
      ) : rows.length === 0 ? (
        <EmptyState
          title={t('alerts:stats.emptyTitle', { n: Number(days) })}
          description={t('alerts:stats.emptyDescription')}
          data-testid="alerts-stats-empty"
        />
      ) : (
        <DataTable
          tableId="alerts-stats"
          caption={t('alerts:stats.caption', { n: Number(days) })}
          columns={columns}
          rows={rows}
          rowKey={(row) => row.rule_id}
          dataComplete
        />
      )}
    </section>
  );
}
