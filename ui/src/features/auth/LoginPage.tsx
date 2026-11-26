// SPDX-License-Identifier: Apache-2.0
//
// /login (docs/15 §9.11, §4.3 deep link 1): user name and password; failures are generic, a lock
// and the rate limit say so; must_change_password leads to the forced password change before the
// shell opens; success returns to `next` (same-origin path only, docs/15 §4.3).
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type SubmitEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useSearchParams } from 'react-router';

import { fetchSetupStatus } from '../../api/auth';
import { queryKeys } from '../../api/queryKeys';
import { useDocumentTitle } from '../../app/useDocumentTitle';
import { DemoBanner } from '../../components/DemoBanner';
import { supportedLanguages } from '../../i18n';
import { useLanguage } from '../../i18n/useLanguage';
import { useAuthStore } from '../../state/authStore';
import { safeNextPath } from '../../state/urlState';

import './auth.css';
import { loginErrorMessage, type AuthMessage } from './authErrors';
import { PasswordChangeForm } from './PasswordChangeForm';
import { signIn } from './session';

const LANGUAGE_LABEL_KEYS = { en: 'language.en', ko: 'language.ko' } as const;

export interface AuthPageFrameProps {
  /** Build version, rendered as text (DSN-19-10). */
  readonly version: string;
  readonly children: ReactNode;
}

/** Shared frame of /login and /setup: product name, pre-release note, language, version. */
export function AuthPageFrame({ version, children }: AuthPageFrameProps) {
  const { t } = useTranslation();
  const { language, setLanguage } = useLanguage();
  return (
    <main className="auth-page">
      <div className="auth-page__panel">
        {/* Demo profile only (setup/status demo_mode, WO-41): also before sign-in. */}
        <DemoBanner placement="page" />
        <header className="auth-page__header">
          <h1>{t('app.name')}</h1>
          <p>{t('app.tagline')}</p>
        </header>
        <p className="auth-page__notice">{t('app.status.preRelease')}</p>
        {children}
        <footer className="auth-page__footer">
          <nav
            aria-label={t('language.label')}
            className="auth-page__languages"
            data-testid="login-language"
          >
            {supportedLanguages.map((code) => (
              <button
                key={code}
                type="button"
                lang={code}
                aria-pressed={language === code}
                onClick={() => {
                  setLanguage(code);
                }}
              >
                {t(LANGUAGE_LABEL_KEYS[code])}
              </button>
            ))}
          </nav>
          <p className="auth-page__version">{t('app.version', { version })}</p>
        </footer>
      </div>
    </main>
  );
}

export interface LoginPageProps {
  readonly version: string;
}

export function LoginPage({ version }: LoginPageProps) {
  const { t } = useTranslation(['common', 'auth']);
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNextPath(params.get('next')) ?? '/overview';
  const sessionEnded = useAuthStore((s) => s.status === 'expired');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<AuthMessage | null>(null);
  const [mustChange, setMustChange] = useState(false);
  const queryClient = useQueryClient();
  const setup = useQuery({
    queryKey: queryKeys.setup.status(),
    queryFn: fetchSetupStatus,
    staleTime: 60_000,
    retry: false,
  });
  useDocumentTitle(mustChange ? t('auth:forcedChange.title') : t('auth:login.documentTitle'));

  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    if (username === '' || password === '') {
      setError({ key: 'login.error.required' });
      return;
    }
    setSubmitting(true);
    setError(null);
    signIn(username, password, queryClient)
      .then((result) => {
        setPassword('');
        if (result.must_change_password) {
          setMustChange(true);
          return;
        }
        void navigate(next, { replace: true });
      })
      .catch((failure: unknown) => {
        setPassword('');
        setError(loginErrorMessage(failure));
      })
      .finally(() => {
        setSubmitting(false);
      });
  };

  if (mustChange) {
    return (
      <AuthPageFrame version={version}>
        <section aria-labelledby="forced-change-title" className="auth-section">
          <h2 id="forced-change-title">{t('auth:forcedChange.title')}</h2>
          <p>{t('auth:forcedChange.description')}</p>
          <PasswordChangeForm
            onChanged={() => {
              useAuthStore.getState().setMustChangePassword(false);
              void navigate(next, { replace: true });
            }}
          />
        </section>
      </AuthPageFrame>
    );
  }

  return (
    <AuthPageFrame version={version}>
      <section aria-labelledby="login-title" className="auth-section">
        <h2 id="login-title">{t('auth:login.title')}</h2>
        {sessionEnded && (
          <p className="auth-form__info" role="status" data-testid="login-session-ended">
            {t('auth:login.sessionEnded')}
          </p>
        )}
        <form className="auth-form" onSubmit={submit} noValidate data-testid="login-form">
          <label className="auth-form__field">
            <span>{t('auth:login.username')}</span>
            <input
              type="text"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={256}
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
              }}
              data-testid="login-username"
            />
          </label>
          <label className="auth-form__field">
            <span>{t('auth:login.password')}</span>
            <input
              type="password"
              name="password"
              autoComplete="current-password"
              maxLength={512}
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
              }}
              data-testid="login-password"
            />
          </label>
          {error !== null && (
            <p className="auth-form__error" role="alert" data-testid="login-error">
              {t(`auth:${error.key}`, error.values ?? {})}
            </p>
          )}
          <button
            type="submit"
            className="auth-form__submit"
            disabled={submitting}
            data-testid="login-submit"
          >
            {submitting ? t('auth:login.submitting') : t('auth:login.submit')}
          </button>
        </form>
        {setup.data?.needs_setup === true && (
          <p className="auth-form__info" data-testid="login-setup-hint">
            {t('auth:login.setupHint')} <Link to="/setup">{t('auth:login.setupLink')}</Link>
          </p>
        )}
      </section>
    </AuthPageFrame>
  );
}
