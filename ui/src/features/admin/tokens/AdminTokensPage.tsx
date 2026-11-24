// SPDX-License-Identifier: Apache-2.0
//
// Agent tokens `/admin/tokens` (docs/15 §9.10, 05 §2.11-4, docs/16 §2.1, WO-39): per application,
// the tokens by prefix (argos_ and 8 characters) with issuer, creation, expiry ("expires soon"
// badge), last use, live sessions and state. Issue (expiry in days, default 365) and rotate (the old
// token works 7 more days) show the new token once (SEC-02: component state only, dropped on
// close); revoke asks for a reason and warns that the live sessions end at once. Administrators
// only (`tokens.manage`).
import { useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { adminAppKeys, useAdminApps } from '../../../api/admin/apps';
import {
  adminTokenKeys,
  expiresSoon,
  issueToken,
  revokeToken,
  rotateToken,
  TOKEN_EXPIRY_DEFAULT_DAYS,
  TOKEN_EXPIRY_MAX_DAYS,
  TOKEN_EXPIRY_WARN_DAYS,
  TOKEN_REVOKE_REASON_MAX,
  TOKEN_ROTATE_GRACE_DAYS,
  useTokens,
  type AgentToken,
} from '../../../api/admin/tokens';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { AdminTokensQ } from '../../../state/urlState';
import { useClock } from '../../../state/useTimeRange';
import { useUrlState } from '../../../state/useUrlState';
import { AdminAppPicker } from '../common/AdminAppPicker';
import { TimeCell } from '../common/cells';
import { ConfirmDialog } from '../common/ConfirmDialog';
import { OnceSecretDialog, type OnceSecret } from '../common/OnceSecretDialog';
import { InstallGuide } from '../install/InstallGuide';

import '../../../components/controls.css';
import '../common/admin.css';

type ScreenState = 'loading' | 'empty' | 'error' | 'ready' | 'noApp';
type Action =
  | { readonly kind: 'issue' }
  | { readonly kind: 'rotate'; readonly token: AgentToken }
  | { readonly kind: 'revoke'; readonly token: AgentToken };

/** A whole number of days within the server's range, or null. */
export function expiryDays(raw: string): number | null {
  const value = Number(raw);
  return Number.isInteger(value) && value >= 1 && value <= TOKEN_EXPIRY_MAX_DAYS ? value : null;
}

export function AdminTokensPage() {
  const { t } = useTranslation(['common', 'admin']);
  const queryClient = useQueryClient();
  const { state, set } = useUrlState(AdminTokensQ);
  const apps = useAdminApps();
  const appList = apps.data?.data ?? [];
  const appId = state.app ?? appList[0]?.app_id ?? null;
  const app = appList.find((a) => a.app_id === appId) ?? null;
  const query = useTokens(appId);
  const [action, setAction] = useState<Action | null>(null);
  const [days, setDays] = useState(String(TOKEN_EXPIRY_DEFAULT_DAYS));
  const [reason, setReason] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [secret, setSecret] = useState<OnceSecret | null>(null);
  // The collector address of the last issue answer, for the install guide (not a secret).
  const [publicAddress, setPublicAddress] = useState<string | null>(null);
  const [guideOpen, setGuideOpen] = useState(false);
  const now = useClock();

  const tokens = query.data?.data ?? [];

  const refresh = (): void => {
    void queryClient.invalidateQueries({ queryKey: adminTokenKeys.list(appId) });
    void queryClient.invalidateQueries({ queryKey: adminAppKeys.all() });
    void queryClient.invalidateQueries({ queryKey: queryKeys.apps.all() });
  };

  const run = async (): Promise<void> => {
    if (action === null || appId === null) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      if (action.kind === 'issue') {
        const expires = expiryDays(days);
        if (expires === null) {
          return;
        }
        const issued = await issueToken(appId, expires);
        setAction(null);
        setPublicAddress(issued.collector_address);
        setSecret({ kind: 'token', value: issued.token, expiresAt: issued.expires_at });
      } else if (action.kind === 'rotate') {
        const issued = await rotateToken(action.token.token_id, TOKEN_ROTATE_GRACE_DAYS);
        setAction(null);
        setPublicAddress(issued.collector_address);
        setSecret({
          kind: 'token',
          value: issued.token,
          expiresAt: issued.expires_at,
          previousValidUntil: issued.grace_until,
        });
      } else {
        const text = reason.trim();
        if (text === '') {
          return;
        }
        await revokeToken(action.token.token_id, text);
        setAction(null);
        setReason('');
      }
      refresh();
    } catch (failure) {
      setError(failure);
    } finally {
      setPending(false);
    }
  };

  const columns: DataTableColumn<AgentToken>[] = [
    {
      id: 'prefix',
      header: t('admin:tokens.column.prefix'),
      value: (row) => row.prefix,
      width: 170,
      mono: true,
      render: (row) => <SafeText value={row.prefix} mono data-testid="admin-token-prefix" />,
    },
    {
      id: 'created_by',
      header: t('admin:tokens.column.created_by'),
      value: (row) => row.created_by,
      width: 140,
      render: (row) =>
        row.created_by === null ? (
          <span className="admin-muted">{t('admin:tokens.createdBySystem')}</span>
        ) : (
          <SafeText value={row.created_by} />
        ),
    },
    {
      id: 'created_at',
      header: t('admin:tokens.column.created_at'),
      value: (row) => row.created_at,
      width: 170,
      render: (row) => <TimeCell iso={row.created_at} />,
    },
    {
      id: 'expires_at',
      header: t('admin:tokens.column.expires_at'),
      value: (row) => row.expires_at,
      width: 220,
      render: (row) => (
        <span data-expiring={expiresSoon(row, now) ? 'true' : 'false'}>
          {row.expires_at === null ? (
            <span>{t('admin:tokens.noExpiry')}</span>
          ) : (
            <TimeCell iso={row.expires_at} />
          )}
          {expiresSoon(row, now) ? (
            <span className="admin-badge admin-badge--warn" data-testid="admin-token-expiring">
              {' '}
              {t('admin:tokens.expiring')}
            </span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'last_used_at',
      header: t('admin:tokens.column.last_used_at'),
      value: (row) => row.last_used_at,
      width: 170,
      render: (row) => <TimeCell iso={row.last_used_at} />,
    },
    {
      id: 'instances_using',
      header: t('admin:tokens.column.instances_using'),
      value: (row) => row.instances_using,
      kind: 'count',
      width: 110,
    },
    {
      id: 'state',
      header: t('admin:tokens.column.state'),
      value: (row) => row.state,
      width: 160,
      render: (row) => (
        <span data-token-state={row.state}>
          {t(`admin:tokens.state.${row.state}`)}
          {row.state === 'rotating' && row.grace_until !== null ? (
            <span className="admin-muted">
              {' '}
              {t('admin:tokens.graceUntil')} <TimeCell iso={row.grace_until} />
            </span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'actions',
      header: t('admin:common.actions'),
      value: () => null,
      width: 180,
      hideable: false,
      sortable: false,
      render: (row) =>
        row.state === 'active' || row.state === 'rotating' ? (
          <span className="admin-actions">
            {row.state === 'active' ? (
              <button
                type="button"
                className="control-button"
                onClick={() => {
                  setError(null);
                  setAction({ kind: 'rotate', token: row });
                }}
                data-testid="admin-token-rotate"
              >
                {t('admin:tokens.rotate')}
              </button>
            ) : null}
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setError(null);
                setReason('');
                setAction({ kind: 'revoke', token: row });
              }}
              data-testid="admin-token-revoke"
            >
              {t('admin:tokens.revoke')}
            </button>
          </span>
        ) : null,
    },
  ];

  let screenState: ScreenState;
  if (apps.isPending || (appId !== null && query.isPending)) {
    screenState = 'loading';
  } else if (apps.isError || query.isError) {
    screenState = 'error';
  } else if (appId === null) {
    screenState = 'noApp';
  } else if (tokens.length === 0) {
    screenState = 'empty';
  } else {
    screenState = 'ready';
  }

  const issueButton = (
    <button
      type="button"
      className="control-button control-button--primary"
      disabled={appId === null}
      onClick={() => {
        setError(null);
        setDays(String(TOKEN_EXPIRY_DEFAULT_DAYS));
        setAction({ kind: 'issue' });
      }}
      data-testid="admin-token-issue"
    >
      {t('admin:tokens.issue')}
    </button>
  );

  let impact = '';
  let title = '';
  let okLabel = '';
  let okDisabled = false;
  if (action?.kind === 'issue') {
    title = t('admin:tokens.issueTitle');
    impact = t('admin:tokens.issueImpact');
    okLabel = t('admin:tokens.issue');
    okDisabled = expiryDays(days) === null;
  } else if (action?.kind === 'rotate') {
    title = t('admin:tokens.rotateTitle');
    impact = t('admin:tokens.rotateImpact', { days: TOKEN_ROTATE_GRACE_DAYS });
    okLabel = t('admin:tokens.rotate');
  } else if (action?.kind === 'revoke') {
    title = t('admin:tokens.revokeTitle');
    impact = t('admin:tokens.revokeImpact', { count: action.token.instances_using });
    okLabel = t('admin:tokens.revoke');
    okDisabled = reason.trim() === '';
  }

  return (
    <section
      className="admin-page"
      aria-labelledby="admin-tokens-title"
      aria-busy={screenState === 'loading' || undefined}
      data-testid="admin-tokens"
      data-state={screenState === 'noApp' ? 'empty' : screenState}
      data-app-id={appId === null ? undefined : String(appId)}
    >
      <header className="admin-page__header">
        <h1 id="admin-tokens-title" className="admin-page__title">
          {t('admin:tokens.title')}
        </h1>
        {issueButton}
      </header>
      <div className="admin-toolbar">
        <AdminAppPicker
          apps={appList}
          value={appId}
          onChange={(next) => {
            set({ app: next });
          }}
        />
        {app === null ? null : (
          <button
            type="button"
            className="control-button"
            onClick={() => {
              setGuideOpen(true);
            }}
            data-testid="admin-tokens-install"
          >
            {t('admin:apps.action.install')}
          </button>
        )}
      </div>
      <p className="admin-note">{t('admin:tokens.help', { days: TOKEN_EXPIRY_WARN_DAYS })}</p>
      {screenState === 'loading' ? (
        <LoadingState variant="table" data-testid="admin-tokens-loading" />
      ) : screenState === 'error' ? (
        <ErrorState
          error={errorInfo(apps.isError ? apps.error : query.error)}
          onRetry={() => {
            void apps.refetch();
            void query.refetch();
          }}
          data-testid="admin-tokens-error"
        />
      ) : screenState === 'noApp' ? (
        <EmptyState
          title={t('admin:instances.noApp.title')}
          description={t('admin:instances.noApp.description')}
          data-testid="admin-tokens-no-app"
        />
      ) : screenState === 'empty' ? (
        <EmptyState
          title={t('admin:tokens.empty.title')}
          description={t('admin:tokens.empty.description')}
          data-testid="admin-tokens-empty"
        />
      ) : (
        <DataTable
          tableId="admin-tokens"
          caption={t('admin:tokens.title')}
          columns={columns}
          rows={tokens}
          rowKey={(row) => row.token_id}
          dataComplete
          rowAttributes={(row) => ({
            'data-token-id': row.token_id,
            'data-state': row.state,
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
        }}
        onOk={() => {
          void run();
        }}
      >
        {action?.kind === 'issue' ? (
          <label className="admin-field">
            <span className="admin-field__label">{t('admin:tokens.expiresDays')}</span>
            <input
              className="admin-input"
              type="number"
              min={1}
              max={TOKEN_EXPIRY_MAX_DAYS}
              step={1}
              value={days}
              onChange={(event) => {
                setDays(event.target.value);
              }}
              data-testid="admin-token-expires-days"
            />
          </label>
        ) : null}
        {action?.kind === 'rotate' || action?.kind === 'revoke' ? (
          <p className="admin-note">
            <SafeText value={action.token.prefix} mono />
          </p>
        ) : null}
        {action?.kind === 'revoke' ? (
          <>
            {action.token.instances_using > 0 ? (
              <p className="admin-warning" data-testid="admin-token-revoke-sessions">
                {t('admin:tokens.revokeSessions', { count: action.token.instances_using })}
              </p>
            ) : null}
            <label className="admin-field">
              <span className="admin-field__label">{t('admin:tokens.reason')}</span>
              <input
                className="admin-input"
                type="text"
                autoComplete="off"
                maxLength={TOKEN_REVOKE_REASON_MAX}
                value={reason}
                onChange={(event) => {
                  setReason(event.target.value);
                }}
                data-testid="admin-token-revoke-reason"
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
      {guideOpen && app !== null ? (
        <InstallGuide
          app={app}
          publicAddress={publicAddress}
          canIssueTokens={false}
          onClose={() => {
            setGuideOpen(false);
          }}
        />
      ) : null}
    </section>
  );
}
