// SPDX-License-Identifier: Apache-2.0
//
// Application administration `/admin/apps` (docs/15 §9.10, 05 §2.11-1 and §2.11-7, WO-39): the
// application table with its eight columns (name, environment, group, instances, tokens,
// registration policy, recommended rules, last received), create and edit (Apdex T, group,
// registration policy with the environment default shown, recommended rules), delete with the name
// typed again, and the agent install guide drawer (`app=` keeps it open in the URL). Operators see
// the list and the install guide; changes are for administrators (`apps.manage`; the server
// enforces it). Names are entered strings: SafeText only (SEC-60).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  adminAppKeys,
  deleteApp,
  tokensActive,
  useAdminApps,
  type App,
  type AppDeleted,
} from '../../../api/admin/apps';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { untrusted } from '../../../api/untrusted';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useCan } from '../../../state/permissions';
import { AdminAppsQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { MISSING_DASH } from '../../../utils/missing';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { InstallGuide } from '../install/InstallGuide';

import { AppEditor } from './AppEditor';

import '../../../components/controls.css';
import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';
type EditorState = { readonly mode: 'new' } | { readonly mode: 'edit'; readonly app: App };

/** Case-insensitive name filter of the list (`q=`). */
export function filterApps(apps: readonly App[], q: string | undefined): App[] {
  const needle = (q ?? '').trim().toLowerCase();
  if (needle === '') {
    return [...apps];
  }
  return apps.filter((app) => String(app.name).toLowerCase().includes(needle));
}

export function AdminAppsPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const canManage = useCan('apps.manage');
  const canTokens = useCan('tokens.manage');
  const { state, set } = useUrlState(AdminAppsQ);
  const query = useAdminApps();
  const [editor, setEditor] = useState<EditorState | null>(null);
  const [deleting, setDeleting] = useState<App | null>(null);
  const [deleteName, setDeleteName] = useState('');
  const [deleteError, setDeleteError] = useState<unknown>(null);
  const [deletePending, setDeletePending] = useState(false);
  const [deleted, setDeleted] = useState<AppDeleted | null>(null);
  const [notice, setNotice] = useState<'rulesFailed' | null>(null);

  const apps = query.data?.data ?? [];
  // The URL's name filter, typed into the field by this user (plain text for the input).
  const searchText: string = state.q ?? '';
  const shown = filterApps(apps, state.q);
  const guideApp =
    state.app === undefined ? null : (apps.find((a) => a.app_id === state.app) ?? null);

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: adminAppKeys.all() });
    // The shell's application selector lists the same applications.
    void queryClient.invalidateQueries({ queryKey: queryKeys.apps.all() });
  };

  const runDelete = async (): Promise<void> => {
    if (deleting === null) {
      return;
    }
    setDeletePending(true);
    setDeleteError(null);
    try {
      const result = await deleteApp(deleting.app_id, String(deleting.name));
      setDeleting(null);
      setDeleteName('');
      setDeleted(result);
      refresh();
    } catch (failure) {
      setDeleteError(failure);
    } finally {
      setDeletePending(false);
    }
  };

  const columns: DataTableColumn<App>[] = [
    {
      id: 'name',
      header: t('admin:apps.column.name'),
      value: (row) => row.name,
      width: 200,
      render: (row) => <SafeText value={row.name} data-testid="admin-app-name" />,
    },
    {
      id: 'env',
      header: t('admin:apps.column.env'),
      value: (row) => row.env,
      width: 110,
      render: (row) => <SafeText value={row.env} />,
    },
    {
      id: 'group',
      header: t('admin:apps.column.group'),
      value: (row) => row.group,
      width: 120,
      render: (row) => <SafeText value={row.group} />,
    },
    {
      id: 'instances',
      header: t('admin:apps.column.instances'),
      value: (row) => row.instances_total,
      kind: 'count',
      width: 110,
      render: (row) => (
        <span data-up={String(row.instances_up)} data-total={String(row.instances_total)}>
          {t('admin:apps.instancesUp', { up: row.instances_up, total: row.instances_total })}
        </span>
      ),
    },
    {
      id: 'tokens',
      header: t('admin:apps.column.tokens'),
      value: (row) => tokensActive(row),
      kind: 'count',
      width: 90,
    },
    {
      id: 'registration_policy',
      header: t('admin:apps.column.registration_policy'),
      value: (row) => row.registration_policy,
      width: 170,
      render: (row) => (
        <span data-policy={row.registration_policy}>
          {row.registration_policy === 'approval'
            ? t('admin:apps.policy.approval')
            : row.registration_policy === 'auto'
              ? t('admin:apps.policy.auto')
              : MISSING_DASH}
          {row.registration_policy_inherited === true ? (
            <span className="admin-muted"> {t('admin:apps.policy.inheritedMark')}</span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'recommended_rules',
      header: t('admin:apps.column.recommended_rules'),
      value: (row) => (row.recommended_rules_applied ? 1 : 0),
      width: 130,
      render: (row) =>
        row.recommended_rules_applied ? (
          <span data-applied="true">{t('admin:apps.rulesApplied')}</span>
        ) : (
          <span className="admin-badge admin-badge--warn" data-applied="false">
            {t('admin:apps.rulesNotApplied')}
          </span>
        ),
    },
    {
      id: 'last_seen',
      header: t('admin:apps.column.last_seen'),
      value: (row) => row.last_seen_at,
      width: 170,
      render: (row) => <TimeCell iso={row.last_seen_at} />,
    },
    {
      id: 'actions',
      header: t('admin:common.actions'),
      value: () => null,
      width: canManage ? 250 : 130,
      hideable: false,
      sortable: false,
      render: (row) => (
        <span className="admin-actions">
          <button
            type="button"
            className="control-button"
            onClick={() => {
              set({ app: row.app_id });
            }}
            data-testid="admin-app-install"
          >
            {t('admin:apps.action.install')}
          </button>
          {canManage ? (
            <>
              <button
                type="button"
                className="control-button"
                onClick={() => {
                  setEditor({ mode: 'edit', app: row });
                }}
                data-testid="admin-app-edit"
              >
                {t('admin:apps.action.edit')}
              </button>
              <button
                type="button"
                className="control-button"
                onClick={() => {
                  setDeleteError(null);
                  setDeleteName('');
                  setDeleting(row);
                }}
                data-testid="admin-app-delete"
              >
                {t('admin:apps.action.delete')}
              </button>
            </>
          ) : null}
        </span>
      ),
    },
  ];

  let screenState: ScreenState;
  if (query.isPending) {
    screenState = 'loading';
  } else if (query.isError) {
    screenState = 'error';
  } else if (apps.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-apps-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-apps"
      data-state={screenState}
      data-editable={canManage ? 'true' : 'false'}
    >
      <header className="admin-page__header">
        <h1 id="admin-apps-title" className="admin-page__title">
          {t('admin:apps.title')}
        </h1>
        {canManage ? (
          <button
            type="button"
            className="control-button control-button--primary"
            onClick={() => {
              setEditor({ mode: 'new' });
            }}
            data-testid="admin-app-new"
          >
            {t('admin:apps.action.new')}
          </button>
        ) : (
          <p className="admin-muted" data-testid="admin-apps-read-only">
            {t('admin:apps.readOnly')}
          </p>
        )}
      </header>
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:apps.search')}</span>
          <input
            className="admin-input"
            type="search"
            maxLength={128}
            defaultValue={searchText}
            onChange={(event) => {
              const value = event.target.value.trim();
              set({ q: value === '' ? undefined : untrusted(value) }, { replace: true });
            }}
            data-testid="admin-apps-search"
          />
        </label>
      </div>
      {deleted === null ? null : (
        <p className="admin-note" role="status" data-testid="admin-app-deleted">
          {t('admin:apps.deleted', {
            tokens: deleted.tokens_revoked,
            sessions: deleted.sessions_closed,
            rules: deleted.rules_deleted,
          })}
        </p>
      )}
      {notice === null ? null : (
        <p className="admin-warning" role="alert" data-testid="admin-app-rules-failed">
          {t('admin:apps.rulesFailed')}
        </p>
      )}
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-apps-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-apps-error"
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={t('admin:apps.empty.title')}
          description={
            canManage ? t('admin:apps.empty.descriptionAdmin') : t('admin:apps.empty.description')
          }
          actions={
            canManage
              ? [
                  {
                    label: t('admin:apps.action.new'),
                    onClick: () => {
                      setEditor({ mode: 'new' });
                    },
                    'data-testid': 'admin-app-new-empty',
                  },
                ]
              : []
          }
          data-testid="admin-apps-empty"
        />
      ) : (
        <DataTable
          tableId="admin-apps"
          caption={t('admin:apps.title')}
          columns={columns}
          rows={shown}
          rowKey={(row) => String(row.app_id)}
          dataComplete
          empty={{
            title: t('admin:apps.noMatch.title'),
            description: t('admin:apps.noMatch.description'),
            onClearFilters: () => {
              set({ q: undefined });
            },
          }}
          rowAttributes={(row) => ({ 'data-app-id': String(row.app_id) })}
        />
      )}
      {editor === null ? null : (
        <AppEditor
          app={editor.mode === 'edit' ? editor.app : null}
          onClose={() => {
            setEditor(null);
          }}
          onSaved={(saved, rulesFailed) => {
            setEditor(null);
            setNotice(rulesFailed ? 'rulesFailed' : null);
            refresh();
            if (editor.mode === 'new') {
              // Next step after registering: how to attach the agent (05 §2.11-7).
              set({ app: saved.app_id });
            }
          }}
        />
      )}
      <ConfirmDialog
        open={deleting !== null}
        title={t('admin:apps.delete.title')}
        impact={t('admin:apps.delete.impact')}
        okLabel={t('admin:apps.delete.ok')}
        okDisabled={deleting === null || deleteName !== String(deleting.name)}
        pending={deletePending}
        error={deleteError}
        onCancel={() => {
          setDeleting(null);
          setDeleteName('');
        }}
        onOk={() => {
          void runDelete();
        }}
      >
        {deleting === null ? null : (
          <label className="admin-field">
            <span className="admin-field__label">
              {t('admin:apps.delete.typeName')} <SafeText value={deleting.name} mono />
            </span>
            <input
              className="admin-input"
              type="text"
              autoComplete="off"
              spellCheck={false}
              value={deleteName}
              onChange={(event) => {
                setDeleteName(event.target.value);
              }}
              data-testid="admin-app-delete-confirm"
            />
          </label>
        )}
      </ConfirmDialog>
      {guideApp === null ? null : (
        <InstallGuide
          app={guideApp}
          canIssueTokens={canTokens}
          onClose={() => {
            set({ app: undefined });
          }}
        />
      )}
    </section>
  );
}
