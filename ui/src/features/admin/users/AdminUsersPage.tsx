// SPDX-License-Identifier: Apache-2.0
//
// Users and roles `/admin/users` (docs/15 §9.10, docs/14 §4.14, docs/16 §7.10, ADR-0010 C-109 and
// C-136 (3), WO-39): the users with role, enabled, lockout and "must change password" state; create
// (one role; the generated initial password is shown once, the user changes it at first login),
// change the role (the user's sessions end), enable or disable, end every session, release a
// lockout, set a new password the user must change, grant `pii.view` to an administrator, and
// delete (not one's own account; the server also keeps the last administrator). Administrators
// only (`users.manage`). User names are entered strings (SafeText).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  adminUserKeys,
  createUser,
  deleteUser,
  isLocked,
  patchUser,
  resetUserPassword,
  revokeUserSessions,
  roleOf,
  unlockUser,
  USER_ROLES,
  USERNAME_MAX,
  useUsers,
  type AdminUser,
  type UserRole,
} from '../../../api/admin/users';
import { errorInfo } from '../../../api/errors';
import { untrusted } from '../../../api/untrusted';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useAuthStore } from '../../../state/authStore';
import { AdminUsersQ } from '../../../state/urlState';
import { useClock } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { hasControl } from '../apps/AppEditor';
import { TimeCell, YesNo } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { OnceSecretDialog, type OnceSecret } from '../common/OnceSecretDialog';

import '../../../components/controls.css';
import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready';

type Action =
  | { readonly kind: 'create' }
  | { readonly kind: 'role'; readonly user: AdminUser; readonly role: UserRole }
  | { readonly kind: 'enabled'; readonly user: AdminUser; readonly enabled: boolean }
  | { readonly kind: 'pii'; readonly user: AdminUser; readonly grant: boolean }
  | { readonly kind: 'sessions'; readonly user: AdminUser }
  | { readonly kind: 'unlock'; readonly user: AdminUser }
  | { readonly kind: 'reset'; readonly user: AdminUser }
  | { readonly kind: 'delete'; readonly user: AdminUser };

/** Minimum length the password policy asks for (docs/16 §7.4, server PasswordPolicy). */
export const PASSWORD_MIN = 12;

/** A user name the server accepts: 1..64 characters, letters or digits first, no control. */
export function validUsername(raw: string): string | null {
  const value = raw.trim();
  if (
    value === '' ||
    Array.from(value).length > USERNAME_MAX ||
    hasControl(value) ||
    !/^[\p{L}\p{N}][\p{L}\p{N}._@+-]*$/u.test(value)
  ) {
    return null;
  }
  return value;
}

/** Case-insensitive user name filter (`q=`). */
export function filterUsers(users: readonly AdminUser[], q: string | undefined): AdminUser[] {
  const needle = (q ?? '').trim().toLowerCase();
  return needle === ''
    ? [...users]
    : users.filter((user) => String(user.username).toLowerCase().includes(needle));
}

export function AdminUsersPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const { state, set } = useUrlState(AdminUsersQ);
  const query = useUsers();
  const now = useClock();
  const [action, setAction] = useState<Action | null>(null);
  const [username, setUsername] = useState('');
  const [newRole, setNewRole] = useState<UserRole>('viewer');
  const [mustChange, setMustChange] = useState(true);
  const [password, setPassword] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [secret, setSecret] = useState<OnceSecret | null>(null);

  const users = query.data?.data ?? [];
  // The URL's name filter, typed into the field by this user (plain text for the input).
  const searchText: string = state.q ?? '';
  const shown = filterUsers(users, state.q);

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: adminUserKeys.list() });
  };

  const open = (next: Action): void => {
    setError(null);
    setPassword('');
    if (next.kind === 'create') {
      setUsername('');
      setNewRole('viewer');
      setMustChange(true);
    }
    setAction(next);
  };

  const run = async (): Promise<void> => {
    if (action === null) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      switch (action.kind) {
        case 'create': {
          const name = validUsername(username);
          if (name === null) {
            return;
          }
          const created = await createUser({
            username: name,
            role: newRole,
            mustChangePassword: mustChange,
          });
          setAction(null);
          setUsername('');
          if (created.initial_password !== undefined && created.initial_password !== '') {
            setSecret({ kind: 'password', value: created.initial_password });
          }
          break;
        }
        case 'role':
          await patchUser(action.user.id, { roles: [action.role] });
          setAction(null);
          break;
        case 'enabled':
          await patchUser(action.user.id, { enabled: action.enabled });
          setAction(null);
          break;
        case 'pii':
          await patchUser(action.user.id, { permissions: action.grant ? ['pii.view'] : [] });
          setAction(null);
          break;
        case 'sessions':
          await revokeUserSessions(action.user.id);
          setAction(null);
          break;
        case 'unlock':
          await unlockUser(action.user.id);
          setAction(null);
          break;
        case 'reset':
          if (Array.from(password).length < PASSWORD_MIN) {
            return;
          }
          await resetUserPassword(action.user.id, password);
          setPassword('');
          setAction(null);
          break;
        case 'delete':
          await deleteUser(action.user.id);
          setAction(null);
          break;
      }
      refresh();
    } catch (failure) {
      setError(failure);
    } finally {
      setPending(false);
    }
  };

  const columns: DataTableColumn<AdminUser>[] = [
    {
      id: 'username',
      header: t('admin:users.column.username'),
      value: (row) => row.username,
      width: 180,
      render: (row) => <SafeText value={row.username} data-testid="admin-user-name" />,
    },
    {
      id: 'roles',
      header: t('admin:users.column.roles'),
      value: (row) => roleOf(row),
      width: 150,
      render: (row) => (
        <select
          className="admin-select"
          value={roleOf(row)}
          aria-label={t('admin:users.column.roles')}
          disabled={me?.id === row.id}
          onChange={(event) => {
            const role = event.target.value as UserRole;
            if (USER_ROLES.includes(role) && role !== roleOf(row)) {
              open({ kind: 'role', user: row, role });
            }
          }}
          data-testid="admin-user-role"
        >
          {USER_ROLES.map((role) => (
            <option key={role} value={role}>
              {t(`admin:users.role.${role}`)}
            </option>
          ))}
        </select>
      ),
    },
    {
      id: 'enabled',
      header: t('admin:users.column.enabled'),
      value: (row) => (row.enabled ? 1 : 0),
      width: 90,
      render: (row) => <YesNo value={row.enabled} />,
    },
    {
      id: 'locked',
      header: t('admin:users.column.locked'),
      value: (row) => row.locked_until,
      width: 170,
      render: (row) =>
        isLocked(row, now) ? (
          <span className="admin-badge admin-badge--warn" data-locked="true">
            {t('admin:users.lockedUntil')} <TimeCell iso={row.locked_until} />
          </span>
        ) : (
          <span data-locked="false">{t('admin:common.no')}</span>
        ),
    },
    {
      id: 'must_change_password',
      header: t('admin:users.column.must_change_password'),
      value: (row) => (row.must_change_password ? 1 : 0),
      width: 130,
      render: (row) => <YesNo value={row.must_change_password} />,
    },
    {
      id: 'pii',
      header: t('admin:users.column.pii'),
      value: (row) => (row.permissions.includes('pii.view') ? 1 : 0),
      width: 110,
      render: (row) =>
        roleOf(row) === 'admin' ? (
          <label className="admin-field admin-field--inline">
            <input
              type="checkbox"
              checked={row.permissions.includes('pii.view')}
              onChange={(event) => {
                open({ kind: 'pii', user: row, grant: event.target.checked });
              }}
              data-testid="admin-user-pii"
            />
            <span>{t('admin:users.piiView')}</span>
          </label>
        ) : (
          <span className="admin-muted">{t('admin:users.piiAdminOnly')}</span>
        ),
    },
    {
      id: 'created_at',
      header: t('admin:users.column.created_at'),
      value: (row) => row.created_at,
      width: 170,
      render: (row) => <TimeCell iso={row.created_at} />,
    },
    {
      id: 'actions',
      header: t('admin:common.actions'),
      value: () => null,
      width: 420,
      hideable: false,
      sortable: false,
      render: (row) => {
        const self = me?.id === row.id;
        return (
          <span className="admin-actions">
            <button
              type="button"
              className="control-button"
              disabled={self}
              onClick={() => {
                open({ kind: 'enabled', user: row, enabled: !row.enabled });
              }}
              data-testid="admin-user-enabled"
            >
              {row.enabled ? t('admin:users.disable') : t('admin:users.enable')}
            </button>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                open({ kind: 'sessions', user: row });
              }}
              data-testid="admin-user-sessions-revoke"
            >
              {t('admin:users.revokeSessions')}
            </button>
            {isLocked(row, now) ? (
              <button
                type="button"
                className="control-button"
                onClick={() => {
                  open({ kind: 'unlock', user: row });
                }}
                data-testid="admin-user-unlock"
              >
                {t('admin:users.unlock')}
              </button>
            ) : null}
            <button
              type="button"
              className="control-button"
              onClick={() => {
                open({ kind: 'reset', user: row });
              }}
              data-testid="admin-user-reset-password"
            >
              {t('admin:users.resetPassword')}
            </button>
            <button
              type="button"
              className="control-button"
              disabled={self}
              title={self ? t('admin:users.selfDelete') : undefined}
              onClick={() => {
                open({ kind: 'delete', user: row });
              }}
              data-testid="admin-user-delete"
            >
              {t('admin:users.delete')}
            </button>
          </span>
        );
      },
    },
  ];

  let screenState: ScreenState;
  if (query.isPending) {
    screenState = 'loading';
  } else if (query.isError) {
    screenState = 'error';
  } else if (users.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  let title = '';
  let impact = '';
  let okLabel = '';
  let okDisabled = false;
  switch (action?.kind) {
    case 'create':
      title = t('admin:users.createTitle');
      impact = t('admin:users.createImpact');
      okLabel = t('admin:users.create');
      okDisabled = validUsername(username) === null;
      break;
    case 'role':
      title = t('admin:users.roleTitle');
      impact = t('admin:users.roleImpact', { role: t(`admin:users.role.${action.role}`) });
      okLabel = t('admin:users.roleOk');
      break;
    case 'enabled':
      title = action.enabled ? t('admin:users.enableTitle') : t('admin:users.disableTitle');
      impact = action.enabled ? t('admin:users.enableImpact') : t('admin:users.disableImpact');
      okLabel = action.enabled ? t('admin:users.enable') : t('admin:users.disable');
      break;
    case 'pii':
      title = t('admin:users.piiTitle');
      impact = action.grant ? t('admin:users.piiGrantImpact') : t('admin:users.piiRevokeImpact');
      okLabel = t('admin:users.roleOk');
      break;
    case 'sessions':
      title = t('admin:users.revokeSessions');
      impact = t('admin:users.sessionsImpact');
      okLabel = t('admin:users.revokeSessions');
      break;
    case 'unlock':
      title = t('admin:users.unlock');
      impact = t('admin:users.unlockImpact');
      okLabel = t('admin:users.unlock');
      break;
    case 'reset':
      title = t('admin:users.resetPassword');
      impact = t('admin:users.resetImpact');
      okLabel = t('admin:users.resetPassword');
      okDisabled = Array.from(password).length < PASSWORD_MIN;
      break;
    case 'delete':
      title = t('admin:users.deleteTitle');
      impact = t('admin:users.deleteImpact');
      okLabel = t('admin:users.delete');
      break;
    default:
      break;
  }

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-users-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-users"
      data-state={screenState}
    >
      <header className="admin-page__header">
        <h1 id="admin-users-title" className="admin-page__title">
          {t('admin:users.title')}
        </h1>
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={() => {
            open({ kind: 'create' });
          }}
          data-testid="admin-user-new"
        >
          {t('admin:users.create')}
        </button>
      </header>
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:users.search')}</span>
          <input
            className="admin-input"
            type="search"
            maxLength={USERNAME_MAX}
            defaultValue={searchText}
            onChange={(event) => {
              const value = event.target.value.trim();
              set({ q: value === '' ? undefined : untrusted(value) }, { replace: true });
            }}
            data-testid="admin-users-search"
          />
        </label>
      </div>
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-users-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(query.error)}
          onRetry={() => {
            void query.refetch();
          }}
          data-testid="admin-users-error"
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={t('admin:users.empty.title')}
          description={t('admin:users.empty.description')}
          data-testid="admin-users-empty"
        />
      ) : (
        <DataTable
          tableId="admin-users"
          caption={t('admin:users.title')}
          columns={columns}
          rows={shown}
          rowKey={(row) => row.id}
          dataComplete
          empty={{
            title: t('admin:users.noMatch.title'),
            description: t('admin:users.noMatch.description'),
            onClearFilters: () => {
              set({ q: undefined });
            },
          }}
          rowAttributes={(row) => ({
            'data-user-id': row.id,
            'data-role': roleOf(row),
            'data-self': me?.id === row.id ? 'true' : 'false',
          })}
        />
      )}
      <ConfirmDialog
        open={action !== null}
        title={title}
        impact={impact}
        okLabel={okLabel}
        okDisabled={okDisabled}
        pending={pending}
        error={error}
        onCancel={() => {
          setAction(null);
          setPassword('');
        }}
        onOk={() => {
          void run();
        }}
      >
        {action !== null && action.kind !== 'create' ? (
          <p className="admin-note" data-testid="admin-user-subject">
            <SafeText value={action.user.username} />
          </p>
        ) : null}
        {action?.kind === 'create' ? (
          <>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:users.column.username')}</span>
              <input
                className="admin-input"
                type="text"
                autoComplete="off"
                spellCheck={false}
                maxLength={USERNAME_MAX}
                value={username}
                onChange={(event) => {
                  setUsername(event.target.value);
                }}
                data-testid="admin-user-field-username"
              />
            </label>
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:users.column.roles')}</span>
              <select
                className="admin-select"
                value={newRole}
                onChange={(event) => {
                  setNewRole(event.target.value as UserRole);
                }}
                data-testid="admin-user-field-role"
              >
                {USER_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {t(`admin:users.role.${role}`)}
                  </option>
                ))}
              </select>
            </label>
            <label className="admin-field admin-field--inline">
              <input
                type="checkbox"
                checked={mustChange}
                onChange={(event) => {
                  setMustChange(event.target.checked);
                }}
                data-testid="admin-user-field-must-change"
              />
              <span>{t('admin:users.mustChange')}</span>
            </label>
          </>
        ) : null}
        {action?.kind === 'reset' ? (
          <label className="admin-field">
            <span className="admin-field__label">{t('admin:users.newPassword')}</span>
            <input
              className="admin-input"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
              }}
              data-testid="admin-user-field-password"
            />
            <span className="admin-field__help">
              {t('admin:users.passwordHelp', { min: PASSWORD_MIN })}
            </span>
          </label>
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
