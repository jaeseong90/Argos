// SPDX-License-Identifier: Apache-2.0
//
// Instance administration `/admin/instances` (docs/15 §9.10, 05 §2.11-2 and §2.11-8, WO-39): for
// the chosen application, the instance table with its nine columns (state, name, agent version
// with the old/unsupported warning, configuration hash with the application-majority mismatch
// highlighted, last heartbeat, drops, clock offset, auto-disabled modules, IP capture mode), the
// archived list, archive (operators, instances not connected) and delete (administrators, archived
// instances only), the 7-day automatic archive note, and the collection health ranking tab. The
// state is the REST state of the instance list (the same basis as the overview, ADR-0010
// C-218 (3)). Instance names, hosts, versions and hashes are agent-sent text (SafeText).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { useAdminApps } from '../../../api/admin/apps';
import {
  adminInstanceKeys,
  archiveInstance,
  deleteInstance,
  hasConfigDrift,
  majorityConfigHash,
  useAdminInstances,
  type Instance,
} from '../../../api/admin/instances';
import { errorInfo } from '../../../api/errors';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { StatusBadge } from '../../../components/StatusBadge';
import { useCan } from '../../../state/permissions';
import { AdminInstancesQ } from '../../../state/urlState';
import { useTimeRange } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { MISSING_DASH } from '../../../utils/missing';
import { compatOf, ipModeOf, warnReasons } from '../../instances/header/model';
import { instanceUrl } from '../../instances/links';
import { CollectionRanking } from '../collection/CollectionRanking';
import { AdminAppPicker } from '../common/AdminAppPicker';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';

import '../../../components/controls.css';
import '../common/admin.css';

/** Days without a connection after which the server archives an instance (05 §2.11-2). */
export const AUTO_ARCHIVE_DAYS = 7;

type ScreenState = 'loading' | 'empty' | 'error' | 'ready' | 'noApp';
type Confirm =
  | { readonly kind: 'archive'; readonly instance: Instance }
  | { readonly kind: 'delete'; readonly instance: Instance };

/** True when an instance may be deleted: only from the archived list (docs/14 §4.3). */
export function deletable(instance: Instance): boolean {
  return instance.archived_at !== null;
}

/** True when an instance may be archived: not archived and not connected (UP/WARN/STALE). */
export function archivable(instance: Instance): boolean {
  return instance.archived_at === null && instance.state.toUpperCase() === 'DOWN';
}

export function AdminInstancesPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const canArchive = useCan('instances.archive');
  const canDelete = useCan('apps.manage');
  const { state, set } = useUrlState(AdminInstancesQ);
  const time = useTimeRange(AdminInstancesQ);
  const apps = useAdminApps();
  const appList = apps.data?.data ?? [];
  const appId = state.app ?? appList[0]?.app_id ?? null;
  const query = useAdminInstances(state.tab === 'list' ? appId : null, state.archived);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [confirmError, setConfirmError] = useState<unknown>(null);
  const [confirmPending, setConfirmPending] = useState(false);

  const instances = query.data?.data ?? [];
  const majority = majorityConfigHash(instances);
  const carried = {
    time: state.time,
    live: false,
    kind: state.kind,
    env: state.env,
    kiosk: false,
  };

  const runConfirm = async (): Promise<void> => {
    if (confirm === null) {
      return;
    }
    setConfirmPending(true);
    setConfirmError(null);
    try {
      if (confirm.kind === 'archive') {
        await archiveInstance(confirm.instance.instance_id);
      } else {
        await deleteInstance(confirm.instance.instance_id);
      }
      setConfirm(null);
      void queryClient.invalidateQueries({ queryKey: adminInstanceKeys.all() });
    } catch (failure) {
      setConfirmError(failure);
    } finally {
      setConfirmPending(false);
    }
  };

  const columns: DataTableColumn<Instance>[] = [
    {
      id: 'state',
      header: t('admin:instances.column.state'),
      value: (row) => row.state,
      width: 110,
      render: (row) => (
        <StatusBadge
          status={row.state}
          warnReasons={warnReasons(row)}
          data-testid="admin-instance-state"
        />
      ),
    },
    {
      id: 'name',
      header: t('admin:instances.column.name'),
      value: (row) => row.name,
      width: 200,
      render: (row) => <SafeText value={row.name} data-testid="admin-instance-name" />,
    },
    {
      id: 'agent_version',
      header: t('admin:instances.column.agent_version'),
      value: (row) => row.agent_version,
      width: 170,
      render: (row) => {
        const compat = compatOf(row);
        return (
          <span data-compat={compat ?? undefined}>
            <SafeText value={row.agent_version} mono />
            {compat === 'old' ? (
              <span className="admin-badge admin-badge--warn" data-testid="admin-instance-compat">
                {' '}
                {t('admin:instances.compatOld')}
              </span>
            ) : compat === 'unsupported' ? (
              <span className="admin-badge admin-badge--error" data-testid="admin-instance-compat">
                {' '}
                {t('admin:instances.compatUnsupported')}
              </span>
            ) : null}
          </span>
        );
      },
    },
    {
      id: 'config_hash',
      header: t('admin:instances.column.config_hash'),
      value: (row) => row.config_hash,
      width: 150,
      render: (row) => {
        const drift = hasConfigDrift(row, majority);
        return (
          <span
            className={drift ? 'admin-drift' : undefined}
            data-drift={drift ? 'true' : 'false'}
            data-testid="admin-instance-config-hash"
          >
            <SafeText value={row.config_hash} mono />
            {drift ? <span> {t('admin:instances.drift')}</span> : null}
          </span>
        );
      },
    },
    {
      id: 'last_heartbeat',
      header: t('admin:instances.column.last_heartbeat'),
      value: (row) => row.last_heartbeat_at,
      width: 170,
      render: (row) => <TimeCell iso={row.last_heartbeat_at} />,
    },
    {
      id: 'drops',
      header: t('admin:instances.column.drops'),
      value: (row) => row.drops_24h,
      kind: 'count',
      width: 100,
    },
    {
      id: 'clock_skew',
      header: t('admin:instances.column.clock_skew'),
      value: (row) => row.clock_skew_ms,
      kind: 'ms',
      width: 110,
    },
    {
      id: 'modules_disabled',
      header: t('admin:instances.column.modules_disabled'),
      value: (row) => (row.modules_disabled === null ? null : row.modules_disabled.length),
      width: 160,
      render: (row) =>
        row.modules_disabled === null ? (
          <span className="admin-muted">{MISSING_DASH}</span>
        ) : row.modules_disabled.length === 0 ? (
          <span>{t('admin:instances.noModulesDisabled')}</span>
        ) : (
          <SafeText value={row.modules_disabled.join(', ')} maxLines={2} />
        ),
    },
    {
      id: 'ip_mode',
      header: t('admin:instances.column.ip_mode'),
      value: (row) => row.ip_mode,
      width: 110,
      render: (row) => {
        const mode = ipModeOf(row);
        return mode === null ? (
          <span className="admin-muted">{MISSING_DASH}</span>
        ) : (
          <span data-mode={mode}>{t(`admin:instances.ipMode.${mode}`)}</span>
        );
      },
    },
  ];
  if (canArchive) {
    columns.push({
      id: 'actions',
      header: t('admin:common.actions'),
      value: () => null,
      width: 120,
      hideable: false,
      sortable: false,
      render: (row) =>
        deletable(row) ? (
          canDelete ? (
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setConfirmError(null);
                setConfirm({ kind: 'delete', instance: row });
              }}
              data-testid="admin-instance-delete"
            >
              {t('admin:instances.delete')}
            </button>
          ) : null
        ) : (
          <button
            type="button"
            className="control-button"
            disabled={!archivable(row)}
            title={archivable(row) ? undefined : t('admin:instances.archiveConnected')}
            onClick={() => {
              setConfirmError(null);
              setConfirm({ kind: 'archive', instance: row });
            }}
            data-testid="admin-instance-archive"
          >
            {t('admin:instances.archive')}
          </button>
        ),
    });
  }

  let screenState: ScreenState;
  if (apps.isPending || (appId !== null && query.isPending && state.tab === 'list')) {
    screenState = 'loading';
  } else if (apps.isError || query.isError) {
    screenState = 'error';
  } else if (appId === null) {
    screenState = 'noApp';
  } else if (state.tab === 'list' && instances.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  const tabButton = (tab: 'list' | 'collection', label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={state.tab === tab}
      className={`admin-tab${state.tab === tab ? ' admin-tab--on' : ''}`}
      onClick={() => {
        set({ tab });
      }}
      data-testid={`admin-instances-tab-${tab}`}
    >
      {label}
    </button>
  );

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-instances-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-instances"
      data-state={screenState === 'noApp' ? 'empty' : screenState}
      data-tab={state.tab}
      data-app-id={appId === null ? undefined : String(appId)}
    >
      <header className="admin-page__header">
        <h1 id="admin-instances-title" className="admin-page__title">
          {t('admin:instances.title')}
        </h1>
        <div className="admin-tabs" role="tablist">
          {tabButton('list', t('admin:instances.tabList'))}
          {tabButton('collection', t('admin:instances.tabCollection'))}
        </div>
      </header>
      <div className="admin-toolbar">
        <AdminAppPicker
          apps={appList}
          value={appId}
          onChange={(next) => {
            set({ app: next });
          }}
        />
        {state.tab === 'list' ? (
          <label className="admin-field admin-field--inline">
            <input
              type="checkbox"
              checked={state.archived}
              onChange={(event) => {
                set({ archived: event.target.checked });
              }}
              data-testid="admin-instances-archived"
            />
            <span>{t('admin:instances.showArchived')}</span>
          </label>
        ) : null}
      </div>
      {state.tab === 'list' ? (
        <p className="admin-note" data-testid="admin-instances-auto-archive">
          {t('admin:instances.autoArchive', { days: AUTO_ARCHIVE_DAYS })}
        </p>
      ) : null}
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-instances-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(apps.isError ? apps.error : query.error)}
          onRetry={() => {
            void apps.refetch();
            void query.refetch();
          }}
          data-testid="admin-instances-error"
        />
      ) : screenState === 'noApp' || appId === null ? (
        <EmptyState
          title={t('admin:instances.noApp.title')}
          description={t('admin:instances.noApp.description')}
          data-testid="admin-instances-no-app"
        />
      ) : state.tab === 'collection' ? (
        <CollectionRanking
          appId={appId}
          from={new Date(time.resolved.from).toISOString()}
          to={new Date(time.resolved.to).toISOString()}
          sort={state.sort}
          onSortChange={(key) => {
            set({ sort: `${key}:desc` });
          }}
          carried={carried}
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={
            state.archived
              ? t('admin:instances.emptyArchived.title')
              : t('admin:instances.empty.title')
          }
          description={
            state.archived
              ? t('admin:instances.emptyArchived.description')
              : t('admin:instances.empty.description')
          }
          data-testid="admin-instances-empty"
        />
      ) : (
        <DataTable
          tableId="admin-instances"
          caption={t('admin:instances.title')}
          columns={columns}
          rows={instances}
          rowKey={(row) => String(row.instance_id)}
          dataComplete
          onRowOpen={(row) => {
            void navigate(instanceUrl(appId, row.instance_id, carried));
          }}
          rowAttributes={(row) => ({
            'data-instance-id': String(row.instance_id),
            'data-state': row.state,
            'data-archived': row.archived_at === null ? 'false' : 'true',
          })}
        />
      )}
      <ConfirmDialog
        open={confirm !== null}
        title={
          confirm?.kind === 'delete'
            ? t('admin:instances.deleteTitle')
            : t('admin:instances.archiveTitle')
        }
        impact={
          confirm?.kind === 'delete'
            ? t('admin:instances.deleteImpact')
            : t('admin:instances.archiveImpact')
        }
        okLabel={
          confirm?.kind === 'delete' ? t('admin:instances.delete') : t('admin:instances.archive')
        }
        pending={confirmPending}
        error={confirmError}
        onCancel={() => {
          setConfirm(null);
        }}
        onOk={() => {
          void runConfirm();
        }}
      >
        {confirm === null ? null : (
          <p className="admin-note">
            <SafeText value={confirm.instance.name} mono />
          </p>
        )}
      </ConfirmDialog>
    </section>
  );
}
