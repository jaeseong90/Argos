// SPDX-License-Identifier: Apache-2.0
//
// Change history of an application (docs/15 §9.10 "변경 이력", 05 §2.11-5): newest first in cursor
// pages (GET /admin/apps/{appId}/config/changes), with the time, kind, state, reason and requester;
// a row opens the change with its per-instance results (URL `change=`).
import { useInfiniteQuery, type InfiniteData } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { configKeys, listConfigChanges, type ConfigChange } from '../../../api/admin/config';
import { useUsers } from '../../../api/admin/users';
import type { Enveloped } from '../../../api/client';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { SafeText } from '../../../components/SafeText';
import { TimeCell } from '../common/cells';

import { changeKindLabel, changeStatusLabel } from './labels';

export interface ChangeHistoryProps {
  readonly appId: number;
  readonly selected: string | undefined;
  readonly onOpen: (changeId: string) => void;
}

export function useChangeHistory(appId: number) {
  return useInfiniteQuery<
    Enveloped<ConfigChange[]>,
    Error,
    InfiniteData<Enveloped<ConfigChange[]>, string | null>,
    ReturnType<typeof configKeys.history>,
    string | null
  >({
    queryKey: configKeys.history(appId),
    queryFn: ({ pageParam, signal }) => listConfigChanges(appId, pageParam, signal),
    initialPageParam: null,
    getNextPageParam: (last) =>
      last.meta.next_cursor === null || last.meta.next_cursor === undefined
        ? undefined
        : last.meta.next_cursor,
  });
}

export function ChangeHistory({ appId, selected, onOpen }: ChangeHistoryProps) {
  const { t } = useTranslation(['common', 'admin']);
  const query = useChangeHistory(appId);
  const users = useUsers();
  const userNames = useMemo(
    () => new Map((users.data?.data ?? []).map((user) => [user.id, user.username] as const)),
    [users.data],
  );
  const rows = useMemo(() => query.data?.pages.flatMap((page) => page.data) ?? [], [query.data]);

  const columns: DataTableColumn<ConfigChange>[] = [
    {
      id: 'requested_at',
      header: t('admin:config.history.column.requestedAt'),
      value: (row) => row.requested_at,
      width: 170,
      render: (row) => <TimeCell iso={row.requested_at} />,
    },
    {
      id: 'kind',
      header: t('admin:config.history.column.kind'),
      value: (row) => row.kind,
      width: 120,
      render: (row) => changeKindLabel(t, row),
    },
    {
      id: 'status',
      header: t('admin:config.history.column.status'),
      value: (row) => row.status,
      width: 120,
      render: (row) => changeStatusLabel(t, row.status),
    },
    {
      id: 'keys',
      header: t('admin:config.history.column.keys'),
      value: (row) => row.preview.diff.map((entry) => entry.key).join(', '),
      width: 220,
      mono: true,
    },
    {
      id: 'reason',
      header: t('admin:config.history.column.reason'),
      value: (row) => row.reason,
      width: 240,
      render: (row) => <SafeText value={row.reason} maxLines={2} />,
    },
    {
      id: 'requested_by',
      header: t('admin:config.history.column.requestedBy'),
      value: (row) => userNames.get(row.requested_by) ?? null,
      width: 140,
      render: (row) => {
        const name = userNames.get(row.requested_by);
        return name === undefined ? (
          <span className="admin-muted">{row.requested_by.slice(0, 8)}</span>
        ) : (
          <SafeText value={name} />
        );
      },
    },
    {
      id: 'open',
      header: t('admin:config.history.column.open'),
      value: () => null,
      width: 110,
      hideable: false,
      render: (row) => (
        <button
          type="button"
          className="control-button"
          aria-current={row.change_id === selected ? 'true' : undefined}
          onClick={() => {
            onOpen(row.change_id);
          }}
          data-testid="admin-config-history-open"
        >
          {t('admin:config.history.open')}
        </button>
      ),
    },
  ];

  return (
    <section className="admin-panel" aria-labelledby="admin-config-history-title">
      <h2 id="admin-config-history-title" className="admin-panel__title">
        {t('admin:config.history.title')}
      </h2>
      <DataTable
        tableId="admin-config-history"
        caption={t('admin:config.history.caption')}
        columns={columns}
        rows={rows}
        rowKey={(row) => row.change_id}
        dataComplete={!query.hasNextPage}
        density="dense"
        loading={query.isPending}
        error={query.isError ? errorInfo(query.error) : null}
        onRetry={() => {
          void query.refetch();
        }}
        empty={{
          title: t('admin:config.history.emptyTitle'),
          description: t('admin:config.history.emptyDescription'),
        }}
        rowAttributes={(row) => ({
          'data-change-id': row.change_id,
          'data-status': row.status,
          'data-kind': row.kind,
          'data-selected': row.change_id === selected ? 'true' : undefined,
        })}
      />
      {query.hasNextPage ? (
        <div className="admin-actions">
          <button
            type="button"
            className="control-button"
            disabled={query.isFetchingNextPage}
            onClick={() => {
              void query.fetchNextPage();
            }}
            data-testid="admin-config-history-more"
          >
            {t('admin:config.history.more')}
          </button>
        </div>
      ) : null}
    </section>
  );
}
