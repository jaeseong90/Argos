// SPDX-License-Identifier: Apache-2.0
//
// Collection health ranking (05 §2.11-8, WO-39 `admin-collection`, ADR-0010 C-211 (2)): the
// instances of the chosen application over the global range, ranked by agent drops, ingest lag,
// clock offset or excluded requests (`per_instance` of GET /apps/{appId}/collection-health). A value
// without a source is the D-23 dash, never 0, and sorts last. A row opens the instance detail.
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import {
  COLLECTION_SORT_KEYS,
  rankCollection,
  useInstanceCollection,
  type CollectionSortKey,
  type InstanceCollection,
} from '../../../api/admin/instances';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { instanceUrl, type CarriedState } from '../../instances/links';

import '../common/admin.css';

/** The sort key of `sort=` (`-drops`, `drops:desc`, ...); unknown keys fall back to drops. */
export function collectionSortKey(sort: string | undefined): CollectionSortKey {
  const key = (sort ?? '').replace(/^-/, '').replace(/:(asc|desc)$/, '');
  return (COLLECTION_SORT_KEYS as readonly string[]).includes(key)
    ? (key as CollectionSortKey)
    : 'drops';
}

export interface CollectionRankingProps {
  readonly appId: number;
  readonly from: string;
  readonly to: string;
  readonly sort: string | undefined;
  readonly onSortChange: (key: CollectionSortKey) => void;
  readonly carried: CarriedState;
}

export function CollectionRanking({
  appId,
  from,
  to,
  sort,
  onSortChange,
  carried,
}: CollectionRankingProps) {
  const { t } = useTranslation(['common', 'admin']);
  const navigate = useNavigate();
  const query = useInstanceCollection(appId, from, to);
  const key = collectionSortKey(sort);
  const rows = rankCollection(query.data?.data ?? [], key);

  const columns: DataTableColumn<InstanceCollection>[] = [
    {
      id: 'name',
      header: t('admin:collection.column.name'),
      value: (row) => row.name,
      width: 240,
      sortable: false,
      render: (row) => <SafeText value={row.name} data-testid="admin-collection-name" />,
    },
    {
      id: 'drops',
      header: t('admin:collection.column.drops'),
      value: (row) => row.drops,
      kind: 'count',
      sortable: false,
      width: 120,
    },
    {
      id: 'lag_s',
      header: t('admin:collection.column.lag_s'),
      value: (row) => row.lag_s,
      kind: 'seconds',
      sortable: false,
      width: 130,
    },
    {
      id: 'skew_ms',
      header: t('admin:collection.column.skew_ms'),
      value: (row) => row.skew_ms,
      kind: 'ms',
      sortable: false,
      width: 130,
    },
    {
      id: 'excluded',
      header: t('admin:collection.column.excluded'),
      value: (row) => row.excluded,
      kind: 'count',
      sortable: false,
      width: 130,
    },
  ];

  let state: 'loading' | 'error' | 'empty' | 'ready';
  if (query.isPending) {
    state = 'loading';
  } else if (query.isError) {
    state = 'error';
  } else if (rows.length === 0) {
    state = 'empty';
  } else {
    state = 'ready';
  }

  return (
    <section
      className="admin-page"
      aria-label={t('admin:collection.title')}
      data-testid="admin-collection"
      data-state={state}
      data-sort={key}
    >
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:collection.sortBy')}</span>
          <select
            className="admin-select"
            value={key}
            onChange={(event) => {
              onSortChange(collectionSortKey(event.target.value));
            }}
            data-testid="admin-collection-sort"
          >
            {COLLECTION_SORT_KEYS.map((option) => (
              <option key={option} value={option}>
                {t(`admin:collection.column.${option}`)}
              </option>
            ))}
          </select>
        </label>
        <p className="admin-note">{t('admin:collection.help')}</p>
      </div>
      {state === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-collection-loading" />
      ) : state === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-collection-error"
        />
      ) : state === 'empty' ? (
        <EmptyState
          title={t('admin:collection.empty.title')}
          description={t('admin:collection.empty.description')}
          data-testid="admin-collection-empty"
        />
      ) : (
        <DataTable
          tableId="admin-collection"
          caption={t('admin:collection.title')}
          columns={columns}
          rows={rows}
          rowKey={(row) => String(row.instance_id)}
          onRowOpen={(row) => {
            void navigate(instanceUrl(appId, row.instance_id, carried));
          }}
          openOnClick
          rowAttributes={(row) => ({ 'data-instance-id': String(row.instance_id) })}
        />
      )}
    </section>
  );
}
