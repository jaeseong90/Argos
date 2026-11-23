// SPDX-License-Identifier: Apache-2.0
//
// Related transactions of a statistics detail (docs/15 §9.7, 05 §2.5-3/-4/-5): the server's sample
// of transactions in the raw window (`related_tx`, `recent_tx`), newest first. Each row opens the
// transaction detail with its start as the `ts` hint (C-50, ADR-0010 C-206 (1)). SQL and external
// details say "profiled transactions only" (`stats-related-note`): the server finds them through
// profile data, not through every transaction.
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import type { RelatedTransaction } from '../../../api/stats';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { formatDateTime } from '../../../utils/format';

import { linkColumn, numberColumn } from './columns';
import { transactionHref } from './links';

export interface RelatedTxListProps {
  readonly appId: number;
  /** DataTable id (`stats-related-tx`, `stats-recent-tx`). */
  readonly tableId: string;
  readonly title: string;
  readonly rows: readonly RelatedTransaction[];
  /** Shows the "profiled transactions only" note. */
  readonly profiledOnly: boolean;
  readonly emptyText: string;
}

export function RelatedTxList({
  appId,
  tableId,
  title,
  rows,
  profiledOnly,
  emptyText,
}: RelatedTxListProps) {
  const { t, i18n } = useTranslation(['common', 'stats']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const navigate = useNavigate();
  const columns = useMemo<DataTableColumn<RelatedTransaction>[]>(
    () => [
      linkColumn({
        id: 'ts',
        header: t('stats:related.ts'),
        value: (row) => {
          const ms = Date.parse(row.ts);
          return Number.isFinite(ms) ? formatDateTime(ms, locale) : row.ts;
        },
        href: (row) => transactionHref(appId, row.tx_id, row.ts),
        onNavigate: (path) => {
          void navigate(path);
        },
        hideable: false,
        width: 200,
      }),
      {
        id: 'service',
        header: t('stats:columns.service'),
        value: (row) => row.service,
        mono: true,
      },
      numberColumn({
        id: 'elapsed_ms',
        header: t('stats:related.elapsed'),
        unit: 'ms',
        value: (row) => row.elapsed_ms,
        sortable: true,
        rangeFilter: false,
      }),
      {
        id: 'instance_id',
        header: t('stats:related.instance'),
        value: (row) => String(row.instance_id),
        width: 110,
      },
    ],
    [appId, locale, t, navigate],
  );
  const titleId = `${tableId}-title`;
  return (
    <section className="stats-section" aria-labelledby={titleId} data-testid={`${tableId}-section`}>
      <h2 id={titleId} className="stats-section__title">
        {title}
      </h2>
      {profiledOnly ? (
        <p className="stats-banner" role="note" data-testid="stats-related-note">
          {t('stats:related.profiledOnly')}
        </p>
      ) : null}
      <DataTable
        tableId={tableId}
        caption={title}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.tx_id}
        dataComplete
        density="dense"
        onRowOpen={(row) => {
          void navigate(transactionHref(appId, row.tx_id, row.ts));
        }}
        empty={{ title: emptyText }}
      />
    </section>
  );
}
