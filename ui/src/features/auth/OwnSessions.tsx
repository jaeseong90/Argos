// SPDX-License-Identifier: Apache-2.0
//
// "My sessions" of /account (docs/15 §9.11, docs/16 §7.10, ADR-0010 C-136 (3), C-168 (2)): the
// caller's live sessions from GET /auth/sessions (browser, IP address, sign-in, last use, expiry,
// the current one marked) and "End session" for every other one (DELETE /auth/sessions/{id}; the
// session is signed out at once). The current session ends with the sign-out of the user menu.
// The user agent is client-supplied text (Untrusted): rendered through SafeText only (SEC-60).
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { listOwnSessions, revokeOwnSession, type OwnSession } from '../../api/auth';
import { errorInfo, isApiError } from '../../api/errors';
import { queryKeys } from '../../api/queryKeys';
import { ErrorState } from '../../components/ErrorState';
import { SafeText } from '../../components/SafeText';
import { useFormat } from '../../i18n/format';
import { MISSING_DASH } from '../../utils/missing';

type RevokeState =
  | { readonly status: 'idle' }
  | { readonly status: 'pending'; readonly sessionId: string }
  | { readonly status: 'done' }
  | { readonly status: 'failed'; readonly requestId: string | null };

function Fact({
  label,
  children,
  'data-testid': testId,
}: {
  readonly label: string;
  readonly children: ReactNode;
  readonly 'data-testid'?: string;
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd data-testid={testId}>{children}</dd>
    </div>
  );
}

function TimeValue({ iso }: { readonly iso: string }) {
  const format = useFormat();
  const ms = Date.parse(iso);
  return (
    <time dateTime={iso} title={Number.isFinite(ms) ? format.dateTimeWithOffset(ms) : undefined}>
      {Number.isFinite(ms) ? format.dateTime(ms) : iso}
    </time>
  );
}

export function OwnSessions() {
  const { t } = useTranslation(['common', 'auth']);
  const queryClient = useQueryClient();
  const sessions = useQuery({
    queryKey: queryKeys.auth.sessions(),
    queryFn: listOwnSessions,
    retry: false,
  });
  const [revoke, setRevoke] = useState<RevokeState>({ status: 'idle' });

  const end = (session: OwnSession): void => {
    if (revoke.status === 'pending') {
      return;
    }
    setRevoke({ status: 'pending', sessionId: session.session_id });
    revokeOwnSession(session.session_id)
      .then(() => {
        setRevoke({ status: 'done' });
      })
      .catch((failure: unknown) => {
        // 404: the session ended meanwhile (expired, signed out); the refreshed list shows that.
        if (isApiError(failure) && failure.status === 404) {
          setRevoke({ status: 'done' });
          return;
        }
        setRevoke({
          status: 'failed',
          requestId: isApiError(failure) ? failure.requestId : null,
        });
      })
      .finally(() => {
        void queryClient.invalidateQueries({ queryKey: queryKeys.auth.sessions() });
      });
  };

  let body;
  if (sessions.isPending) {
    body = (
      <p role="status" className="auth-form__hint" data-testid="account-sessions-loading">
        {t('route.loading')}
      </p>
    );
  } else if (sessions.isError) {
    body = (
      <ErrorState
        compact
        error={errorInfo(sessions.error)}
        onRetry={() => {
          void sessions.refetch();
        }}
        data-testid="account-sessions-error"
      />
    );
  } else if (sessions.data.length === 0) {
    body = (
      <p className="auth-form__hint" data-testid="account-sessions-empty">
        {t('auth:account.sessions.empty')}
      </p>
    );
  } else {
    body = (
      <ul
        className="account-sessions"
        aria-label={t('auth:account.sessions.list')}
        data-testid="account-sessions-list"
      >
        {sessions.data.map((session) => (
          <li
            key={session.session_id}
            className="account-sessions__item"
            data-testid="account-session"
            data-session-id={session.session_id}
            data-current={session.current ? 'true' : undefined}
          >
            <div className="account-sessions__head">
              {session.user_agent === null ? (
                <span className="account-sessions__agent" data-testid="account-session-agent">
                  {t('auth:account.sessions.unknownAgent')}
                </span>
              ) : (
                <SafeText
                  className="account-sessions__agent"
                  value={session.user_agent}
                  maxLines={2}
                  data-testid="account-session-agent"
                />
              )}
              {session.current ? (
                <span className="account-sessions__badge" data-testid="account-session-current">
                  {t('auth:account.sessions.current')}
                </span>
              ) : null}
              {session.kiosk ? (
                <span className="account-sessions__badge">{t('auth:account.sessions.kiosk')}</span>
              ) : null}
            </div>
            <dl className="account-sessions__facts">
              <Fact label={t('auth:account.sessions.ip')} data-testid="account-session-ip">
                {session.ip ?? MISSING_DASH}
              </Fact>
              <Fact label={t('auth:account.sessions.createdAt')}>
                <TimeValue iso={session.created_at} />
              </Fact>
              <Fact label={t('auth:account.sessions.lastSeenAt')}>
                <TimeValue iso={session.last_seen_at} />
              </Fact>
              <Fact label={t('auth:account.sessions.expiresAt')}>
                <TimeValue iso={session.expires_at} />
              </Fact>
            </dl>
            <div className="account-sessions__actions">
              {session.current ? (
                <span className="auth-form__hint">{t('auth:account.sessions.currentHint')}</span>
              ) : (
                <button
                  type="button"
                  className="auth-form__secondary"
                  disabled={revoke.status === 'pending'}
                  onClick={() => {
                    end(session);
                  }}
                  data-testid="account-session-revoke"
                >
                  {revoke.status === 'pending' && revoke.sessionId === session.session_id
                    ? t('auth:account.sessions.revoking')
                    : t('auth:account.sessions.revoke')}
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section
      className="account-page__section"
      aria-labelledby="account-sessions"
      data-testid="account-sessions"
    >
      <h2 id="account-sessions">{t('auth:account.sessions.title')}</h2>
      <p>{t('auth:account.sessions.description')}</p>
      {revoke.status === 'done' ? (
        <p className="auth-form__success" role="status" data-testid="account-sessions-revoked">
          {t('auth:account.sessions.revoked')}
        </p>
      ) : null}
      {revoke.status === 'failed' ? (
        <p className="auth-form__error" role="alert" data-testid="account-sessions-revoke-error">
          {revoke.requestId === null
            ? t('auth:account.sessions.revokeFailedNoId')
            : t('auth:account.sessions.revokeFailed', { requestId: revoke.requestId })}
        </p>
      ) : null}
      {body}
    </section>
  );
}
