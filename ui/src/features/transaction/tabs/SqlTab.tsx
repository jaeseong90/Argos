// SPDX-License-Identifier: Apache-2.0
//
// The SQL tab (05 §2.2-5, docs/15 §8.6): the server's totals with the connection wait apart from the
// execution (`sql.wait_ms` vs `sql.time_ms`) and the waits by pool (DBC steps with the pool name),
// then one row per SQL step with its normalized text, execute / fetch time, rows, executions and the
// parameter mode the server applied; a row opens the side panel with the full text and parameters.
// When the server downgraded full-mode parameters for this viewer (`masked_by_role`, C-13) the tab
// says why; it never reveals more than was sent. Each statement links to its SQL statistics with
// the detail's time range.
import { useTranslation } from 'react-i18next';

import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import { ValueText } from '../../../components/ValueText';
import type { TransactionDetail } from '../data/transactionApi';
import { hasSteps, maskedByRole, stepParamsModes } from '../data/transactionModel';
import { poolWaits, sqlRows, type PoolWait, type SqlRow } from '../model/calls';
import type { DetailLinks } from '../model/links';
import { ParamsModeLabel } from '../ParamsView';
import { Field, StepTypeBadge } from '../parts';

export interface SqlTabProps {
  readonly detail: TransactionDetail;
  readonly links: DetailLinks;
  readonly onNavigate: (path: string) => void;
  readonly onSelect: (seq: number) => void;
}

export function SqlTab({ detail, links, onNavigate, onSelect }: SqlTabProps) {
  const { t } = useTranslation(['common', 'tx']);
  const steps = detail.profile.steps;
  const rows = sqlRows(steps);
  const pools = poolWaits(steps);
  const modes = stepParamsModes(steps);
  const columns: DataTableColumn<SqlRow>[] = [
    {
      id: 'type',
      header: t('tx:sql.colType'),
      value: (r) => r.type,
      width: 96,
      render: (r) => <StepTypeBadge type={r.type} />,
    },
    {
      id: 'sql',
      header: t('tx:sql.colSql'),
      value: (r) => r.sql ?? r.target,
      mono: true,
      width: 360,
    },
    {
      id: 'exec',
      header: t('tx:sql.exec'),
      value: (r) => r.execMs,
      kind: 'ms',
      width: 92,
      sortable: true,
    },
    {
      id: 'fetch',
      header: t('tx:sql.fetch'),
      value: (r) => r.fetchMs,
      kind: 'ms',
      width: 92,
      sortable: true,
    },
    {
      id: 'rows',
      header: t('tx:sql.rows'),
      value: (r) => r.rows,
      kind: 'count',
      width: 80,
      sortable: true,
    },
    {
      id: 'elapsed',
      header: t('tx:sql.elapsed'),
      value: (r) => r.elapsedMs,
      kind: 'ms',
      width: 92,
      sortable: true,
    },
    {
      id: 'executions',
      header: t('tx:sql.executions'),
      value: (r) => r.executions,
      kind: 'count',
      width: 80,
      sortable: true,
    },
    {
      id: 'params',
      header: t('tx:sql.colParams'),
      value: (r) => r.paramsMode,
      width: 150,
      render: (r) =>
        r.paramsMode === null ? <SafeText value={null} /> : <ParamsModeLabel mode={r.paramsMode} />,
    },
    {
      id: 'stats',
      header: t('tx:sql.colStats'),
      value: (r) => r.sqlId,
      width: 110,
      render: (r) =>
        r.sqlId === null ? (
          <SafeText value={null} />
        ) : (
          <SafeLink
            href={links.sqlStats(r.sqlId)}
            onNavigate={onNavigate}
            data-testid="transaction-sql-stats-link"
          >
            {t('tx:sql.statsShort')}
          </SafeLink>
        ),
    },
  ];
  const poolColumns: DataTableColumn<PoolWait>[] = [
    { id: 'pool', header: t('tx:sql.pool'), value: (p) => p.pool, width: 200 },
    { id: 'target', header: t('tx:panel.target'), value: (p) => p.target, mono: true, width: 220 },
    { id: 'count', header: t('tx:sql.waits'), value: (p) => p.count, kind: 'count', width: 90 },
    { id: 'total', header: t('tx:sql.waitTotal'), value: (p) => p.totalMs, kind: 'ms', width: 110 },
    { id: 'max', header: t('tx:tree.max'), value: (p) => p.maxMs, kind: 'ms', width: 100 },
  ];

  return (
    <div className="tx-tab tx-sql" data-testid="transaction-sql">
      <dl className="tx-totals" data-testid="transaction-sql-totals">
        <Field
          label={t('tx:sql.count')}
          value={detail.sql.count}
          data-testid="transaction-sql-field-count"
        >
          <ValueText
            value={detail.sql.count}
            unit="count"
            data-testid="transaction-sql-total-count"
          />
        </Field>
        <Field
          label={t('tx:sql.execTotal')}
          note={t('tx:sql.execTotalNote')}
          value={detail.sql.time_ms}
          data-testid="transaction-sql-field-exec"
        >
          <ValueText
            value={detail.sql.time_ms}
            unit="ms"
            data-testid="transaction-sql-exec-total"
          />
        </Field>
        <Field
          label={t('tx:sql.waitTotal')}
          note={t('tx:sql.waitTotalNote')}
          value={detail.sql.wait_ms}
          data-testid="transaction-sql-field-wait"
        >
          <ValueText
            value={detail.sql.wait_ms}
            unit="ms"
            data-testid="transaction-sql-wait-total"
          />
        </Field>
        <Field label={t('tx:sql.profiled')}>
          <ValueText
            value={rows.filter((r) => r.type !== 'SUMMARY').length}
            unit="count"
            data-testid="transaction-sql-profiled"
          />
        </Field>
      </dl>
      {maskedByRole(detail) ? (
        <p className="tx-notice" role="note" data-testid="transaction-sql-masked-by-role">
          {t('tx:params.maskedByRoleNotice')}
        </p>
      ) : null}
      {modes.length > 0 ? (
        <p className="tx-modes" data-testid="transaction-sql-modes">
          <span>{t('tx:params.modesInUse')}</span>
          {modes.map((mode) => (
            <ParamsModeLabel key={mode} mode={mode} />
          ))}
        </p>
      ) : null}
      <section aria-labelledby="tx-sql-pools-title" data-testid="transaction-sql-pools">
        <h3 id="tx-sql-pools-title" className="tx-section-title">
          {t('tx:sql.poolsTitle')}
        </h3>
        {pools.length === 0 ? (
          <p className="tx-muted">{t('tx:sql.noPools')}</p>
        ) : (
          <DataTable
            tableId="tx-sql-pools"
            caption={t('tx:sql.poolsTitle')}
            columns={poolColumns}
            rows={pools}
            rowKey={(p) => `${p.pool ?? ''}\u0000${p.target ?? ''}`}
            dataComplete
            density="dense"
          />
        )}
      </section>
      <section aria-labelledby="tx-sql-list-title">
        <h3 id="tx-sql-list-title" className="tx-section-title">
          {t('tx:sql.listTitle')}
        </h3>
        {rows.length === 0 ? (
          <EmptyState
            compact
            data-testid="transaction-sql-empty"
            title={t('tx:sql.emptyTitle')}
            description={hasSteps(detail.profile) ? t('tx:sql.empty') : t('tx:tabs.noProfile')}
          />
        ) : (
          <DataTable
            tableId="tx-sql"
            caption={t('tx:sql.listTitle')}
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
      </section>
    </div>
  );
}
