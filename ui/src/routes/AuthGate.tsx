// SPDX-License-Identifier: Apache-2.0
//
// Guards every shell route (docs/15 §3.1 "조회자+", §11): bootstraps the session once (CSRF
// cookie, /auth/me), sends visitors without a session to /login?next=<path+query> (deep link 1),
// shows the forced password change while must_change_password is set (16 §7.9) and, in kiosk mode
// on a kiosk screen, a full-screen "session expired" notice instead of a login form (docs/15
// §3.4). It also drops the cached server data when the session expires (useDropCacheOnExpiry).
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';

import { errorInfo } from '../api/errors';
import { useDocumentTitle } from '../app/useDocumentTitle';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PasswordChangeForm } from '../features/auth/PasswordChangeForm';
import { bootstrapSession, loginPath, useDropCacheOnExpiry } from '../features/auth/session';
import { useAuthStore } from '../state/authStore';

import { useCurrentScreen } from './navigation';
import { kioskApplies } from './screens';
import './shell.css';

/** True when the current URL asks for kiosk mode (?kiosk=1, docs/15 §3.4). */
export function isKioskSearch(search: string): boolean {
  return new URLSearchParams(search).get('kiosk') === '1';
}

/** Full-screen notice of an ended session in kiosk mode: no login form on a wall display. */
export function SessionExpiredScreen() {
  const { t } = useTranslation(['common', 'shell']);
  const navigate = useNavigate();
  const location = useLocation();
  useDocumentTitle(t('shell:sessionExpired.title'));
  return (
    <main className="session-expired" data-testid="session-expired" role="alert">
      <h1>{t('shell:sessionExpired.title')}</h1>
      <p>{t('shell:sessionExpired.description')}</p>
      <button
        type="button"
        className="session-expired__action"
        onClick={() => {
          void navigate(loginPath(location), { replace: true });
        }}
      >
        {t('shell:sessionExpired.action')}
      </button>
    </main>
  );
}

function ForcedPasswordChange() {
  const { t } = useTranslation(['common', 'auth']);
  useDocumentTitle(t('auth:forcedChange.title'));
  return (
    <main className="forced-change">
      <section aria-labelledby="forced-change-title" className="forced-change__panel">
        <h1 id="forced-change-title">{t('auth:forcedChange.title')}</h1>
        <p>{t('auth:forcedChange.description')}</p>
        <PasswordChangeForm
          onChanged={() => {
            useAuthStore.getState().setMustChangePassword(false);
          }}
        />
      </section>
    </main>
  );
}

export function AuthGate() {
  const status = useAuthStore((s) => s.status);
  const mustChange = useAuthStore((s) => s.mustChangePassword);
  const location = useLocation();
  const screen = useCurrentScreen();
  const [bootError, setBootError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);
  useDropCacheOnExpiry();

  useEffect(() => {
    if (status !== 'unknown' || bootError !== null) {
      return undefined;
    }
    let cancelled = false;
    bootstrapSession().catch((error: unknown) => {
      if (!cancelled) {
        setBootError(error);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [status, attempt, bootError]);

  if (bootError !== null) {
    return (
      <main className="auth-gate">
        <ErrorState
          error={errorInfo(bootError)}
          onRetry={() => {
            setBootError(null);
            setAttempt((n) => n + 1);
          }}
        />
      </main>
    );
  }
  if (status === 'unknown') {
    return (
      <main className="auth-gate">
        <LoadingState slowHint="wait" data-testid="loading-state" />
      </main>
    );
  }
  if (status === 'signedOut') {
    // The user logged out here: the sign-in page without a return path.
    return <Navigate to="/login" replace />;
  }
  if (status !== 'authenticated') {
    // The same screen condition as the shell's kiosk layout: on other screens ?kiosk=1 is ignored
    // and the visitor gets the sign-in page.
    if (kioskApplies(isKioskSearch(location.search), screen)) {
      return <SessionExpiredScreen />;
    }
    return <Navigate to={loginPath(location)} replace />;
  }
  if (mustChange) {
    return <ForcedPasswordChange />;
  }
  return <Outlet />;
}
