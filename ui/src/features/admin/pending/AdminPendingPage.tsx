// SPDX-License-Identifier: Apache-2.0
//
// Registration requests waiting for approval `/admin/pending` (docs/15 §9.10, 05 §2.11-3, F-S30,
// WO-39): agents of an unregistered (name, env) whose environment does not register automatically.
// The table shows (name, env), host, first and last attempt and attempts. Approve asks for the
// group and Apdex T, creates the application and shows its new token once; reject asks first.
// Administrators only (docs/14 §4.14, docs/16 §7.10). Names and hosts are agent-sent text.
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { adminAppKeys, APP_LIMITS } from '../../../api/admin/apps';
import {
  adminPendingKeys,
  approveRegistration,
  rejectRegistration,
  usePendingRegistrations,
  type PendingRegistration,
} from '../../../api/admin/pending';
import { errorInfo, isApiError } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { textValue } from '../apps/AppEditor';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { OnceSecretDialog, type OnceSecret } from '../common/OnceSecretDialog';

import '../../../components/controls.css';
import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';
type Action =
  | { readonly kind: 'approve'; readonly request: PendingRegistration }
  | { readonly kind: 'reject'; readonly request: PendingRegistration };

export function AdminPendingPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const query = usePendingRegistrations();
  const [action, setAction] = useState<Action | null>(null);
  const [group, setGroup] = useState('');
  const [apdex, setApdex] = useState(String(APP_LIMITS.apdexDefault));
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [secret, setSecret] = useState<OnceSecret | null>(null);
  const [noTokenNotice, setNoTokenNotice] = useState(false);

  const rows = query.data?.data ?? [];

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: adminPendingKeys.list() });
    void queryClient.invalidateQueries({ queryKey: adminAppKeys.all() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.apps.all() });
  };

  const run = async (): Promise<void> => {
    if (action === null) {
      return;
    }
    setError(null);
    if (action.kind === 'reject') {
      setPending(true);
      try {
        await rejectRegistration(action.request.id);
        setAction(null);
        refresh();
      } catch (failure) {
        setError(failure);
      } finally {
        setPending(false);
      }
      return;
    }
    const groupValue = textValue(group, APP_LIMITS.groupMax, true);
    const apdexValue = Number(apdex);
    if (
      groupValue === null ||
      !Number.isInteger(apdexValue) ||
      apdexValue < APP_LIMITS.apdexMin ||
      apdexValue > APP_LIMITS.apdexMax
    ) {
      return;
    }
    setPending(true);
    try {
      const approved = await approveRegistration(action.request.id, {
        apdex_t_ms: apdexValue,
        ...(groupValue === '' ? {} : { group: groupValue }),
      });
      setAction(null);
      setSecret({
        kind: 'token',
        value: approved.token.token,
        expiresAt: approved.token.expires_at,
      });
      refresh();
    } catch (failure) {
      // 409 invalid_state "token: not_issued": approved, but the application has 5 tokens already.
      if (
        isApiError(failure) &&
        failure.status === 409 &&
        failure.hasDetail('not_issued', 'token')
      ) {
        setAction(null);
        setNoTokenNotice(true);
        refresh();
      } else {
        setError(failure);
      }
    } finally {
      setPending(false);
    }
  };

  const columns: DataTableColumn<PendingRegistration>[] = [
    {
      id: 'name_env',
      header: t('admin:pending.column.name_env'),
      value: (row) => `${String(row.name)} ${String(row.env)}`,
      width: 240,
      render: (row) => (
        <span>
          <SafeText value={row.name} data-testid="admin-pending-name" /> ·{' '}
          <SafeText value={row.env} data-testid="admin-pending-env" />
        </span>
      ),
    },
    {
      id: 'host',
      header: t('admin:pending.column.host'),
      value: (row) => row.host,
      width: 200,
      render: (row) => <SafeText value={row.host} data-testid="admin-pending-host" />,
    },
    {
      id: 'first_attempt',
      header: t('admin:pending.column.first_attempt'),
      value: (row) => row.first_attempt_at,
      width: 170,
      render: (row) => <TimeCell iso={row.first_attempt_at} />,
    },
    {
      id: 'last_attempt',
      header: t('admin:pending.column.last_attempt'),
      value: (row) => row.last_attempt_at,
      width: 170,
      render: (row) => <TimeCell iso={row.last_attempt_at} />,
    },
    {
      id: 'attempts',
      header: t('admin:pending.column.attempts'),
      value: (row) => row.attempts,
      kind: 'count',
      width: 100,
    },
    {
      id: 'actions',
      header: t('admin:common.actions'),
      value: () => null,
      width: 190,
      hideable: false,
      sortable: false,
      render: (row) => (
        <span className="admin-actions">
          <button
            type="button"
            className="control-button control-button--primary"
            onClick={() => {
              setError(null);
              setGroup('');
              setApdex(String(APP_LIMITS.apdexDefault));
              setAction({ kind: 'approve', request: row });
            }}
            data-testid="admin-pending-approve"
          >
            {t('admin:pending.approve')}
          </button>
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setError(null);
              setAction({ kind: 'reject', request: row });
            }}
            data-testid="admin-pending-reject"
          >
            {t('admin:pending.reject')}
          </button>
        </span>
      ),
    },
  ];

  let screenState: ScreenState;
  if (query.isPending) {
    screenState = 'loading';
  } else if (query.isError) {
    screenState = 'error';
  } else if (rows.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  const apdexValid = (() => {
    const n = Number(apdex);
    return Number.isInteger(n) && n >= APP_LIMITS.apdexMin && n <= APP_LIMITS.apdexMax;
  })();
  const groupValid = textValue(group, APP_LIMITS.groupMax, true) !== null;

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-pending-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-pending"
      data-state={screenState}
    >
      <header className="admin-page__header">
        <h1 id="admin-pending-title" className="admin-page__title">
          {t('admin:pending.title')}
        </h1>
      </header>
      <p className="admin-note">{t('admin:pending.description')}</p>
      {noTokenNotice ? (
        <p className="admin-warning" role="alert" data-testid="admin-pending-no-token">
          {t('admin:pending.noToken')}
        </p>
      ) : null}
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-pending-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-pending-error"
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={t('admin:pending.empty.title')}
          description={t('admin:pending.empty.description')}
          data-testid="admin-pending-empty"
        />
      ) : (
        <DataTable
          tableId="admin-pending"
          caption={t('admin:pending.title')}
          columns={columns}
          rows={rows}
          rowKey={(row) => String(row.id)}
          dataComplete
          rowAttributes={(row) => ({ 'data-request-id': String(row.id) })}
        />
      )}
      <ConfirmDialog
        open={action !== null}
        title={
          action?.kind === 'reject'
            ? t('admin:pending.rejectTitle')
            : t('admin:pending.approveTitle')
        }
        impact={
          action?.kind === 'reject'
            ? t('admin:pending.rejectImpact')
            : t('admin:pending.approveImpact')
        }
        okLabel={action?.kind === 'reject' ? t('admin:pending.reject') : t('admin:pending.approve')}
        okDisabled={action?.kind === 'approve' && (!apdexValid || !groupValid)}
        pending={pending}
        error={error}
        onCancel={() => {
          setAction(null);
        }}
        onOk={() => {
          void run();
        }}
      >
        {action === null ? null : (
          <p className="admin-note" data-testid="admin-pending-subject">
            <SafeText value={action.request.name} /> · <SafeText value={action.request.env} /> (
            <SafeText value={action.request.host} />)
          </p>
        )}
        {action?.kind === 'approve' ? (
          <>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:apps.column.group')}</span>
              <input
                className="admin-input"
                type="text"
                autoComplete="off"
                maxLength={APP_LIMITS.groupMax}
                value={group}
                onChange={(event) => {
                  setGroup(event.target.value);
                }}
                data-testid="admin-pending-group"
              />
            </label>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:apps.editor.apdex')}</span>
              <input
                className="admin-input"
                type="number"
                min={APP_LIMITS.apdexMin}
                max={APP_LIMITS.apdexMax}
                value={apdex}
                onChange={(event) => {
                  setApdex(event.target.value);
                }}
                data-testid="admin-pending-apdex"
              />
            </label>
          </>
        ) : null}
      </ConfirmDialog>
      <OnceSecretDialog
        secret={secret}
        onClose={() => {
          setSecret(null);
        }}
      />
    </section>
  );
}
