// SPDX-License-Identifier: Apache-2.0
//
// The external call tab (05 §2.2-6, docs/15 §8.6): the server's totals (`ext.count`, `ext.time_ms`)
// and one row per outgoing call (HTTP, MESSAGE) with host, path, elapsed time, HTTP status and the
// failure class as a badge (TIMEOUT / CONNECT_FAIL / 5xx / other, never colour alone), a link to the
// host's external-call statistics in the detail's time range, and the trace view of the
// transaction (distributed trace). A row opens the side panel.
import { useTranslation } from 'react-i18next';

import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import type { TransactionDetail } from '../data/transactionApi';
import { hasSteps } from '../data/transactionModel';
import { extRows, type ExtRow } from '../model/calls';
import type { DetailLinks } from '../model/links';
import { FailBadge } from '../ParamsView';
import { Field, StepTypeBadge } from '../parts';

export interface ExtTabProps {
  readonly detail: TransactionDetail;
  readonly links: DetailLinks;
  readonly onNavigate: (path: string) => void;
  readonly onSelect: (seq: number) => void;
}

export function ExtTab({ detail, links, onNavigate, onSelect }: ExtTabProps) {
  const { t } = useTranslation(['common', 'tx']);
  const rows = extRows(detail.profile.steps);
  const columns: DataTableColumn<ExtRow>[] = [
    {
      id: 'type',
      header: t('tx:sql.colType'),
      value: (r) => r.type,
      width: 96,
      render: (r) => <StepTypeBadge type={r.type} />,
    },
    { id: 'host', header: t('tx:ext.host'), value: (r) => r.host, mono: true, width: 200 },
    { id: 'path', header: t('tx:ext.path'), value: (r) => r.path, mono: true, width: 260 },
    {
      id: 'elapsed',
      header: t('tx:sql.elapsed'),
      value: (r) => r.elapsedMs,
      kind: 'ms',
      width: 96,
      sortable: true,
    },
    {
      id: 'status',
      header: t('tx:ext.status'),
      value: (r) => r.httpStatus,
      width: 140,
      render: (r) => (
        <span
          className="tx-pair"
          data-testid="transaction-ext-status"
          data-status={r.httpStatus ?? ''}
        >
          {r.httpStatus === null ? <SafeText value={null} /> : <span>{String(r.httpStatus)}</span>}
          {r.failKind === null ? null : <FailBadge kind={r.failKind} />}
        </span>
      ),
    },
    {
      id: 'executions',
      header: t('tx:sql.executions'),
      value: (r) => r.executions,
      kind: 'count',
      width: 80,
    },
    {
      id: 'stats',
      header: t('tx:ext.colStats'),
      value: (r) => r.hostId,
      width: 120,
      render: (r) =>
        r.hostId === null ? (
          <SafeText value={null} />
        ) : (
          <SafeLink
            href={links.hostStats(r.hostId)}
            onNavigate={onNavigate}
            data-testid="transaction-ext-host-link"
          >
            {t('tx:ext.hostStatsShort')}
          </SafeLink>
        ),
    },
  ];
  return (
    <div className="tx-tab tx-ext" data-testid="transaction-ext">
      <dl className="tx-totals" data-testid="transaction-ext-totals">
        <Field label={t('tx:ext.count')}>
          <ValueText
            value={detail.ext.count}
            unit="count"
            data-testid="transaction-ext-total-count"
          />
        </Field>
        <Field label={t('tx:ext.timeTotal')}>
          <ValueText
            value={detail.ext.time_ms}
            unit="ms"
            data-testid="transaction-ext-total-time"
          />
        </Field>
        <Field label={t('tx:ext.trace')}>
          {links.trace === null ? (
            <span className="tx-muted" data-testid="transaction-ext-no-trace">
              {t('tx:ext.noTrace')}
            </span>
          ) : (
            <SafeLink
              href={links.trace}
              onNavigate={onNavigate}
              data-testid="transaction-ext-trace-link"
            >
              {t('tx:ext.traceLink')}
            </SafeLink>
          )}
        </Field>
      </dl>
      {rows.length === 0 ? (
        <EmptyState
          compact
          data-testid="transaction-ext-empty"
          title={t('tx:ext.emptyTitle')}
          description={hasSteps(detail.profile) ? t('tx:ext.empty') : t('tx:tabs.noProfile')}
        />
      ) : (
        <DataTable
          tableId="tx-ext"
          caption={t('tx:ext.listTitle')}
          columns={columns}
          rows={rows}
          rowKey={(r) => String(r.seq)}
          dataComplete
          onRowOpen={(r) => {
            onSelect(r.seq);
          }}
          openOnClick
          density="dense"
        />
      )}
    </div>
  );
}
