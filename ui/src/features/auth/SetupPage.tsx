// SPDX-License-Identifier: Apache-2.0
//
// /setup, the installation wizard (docs/15 §9.11, docs/16 §6.10, §7.8, SEC-47, ADR-0010 C-168
// (2)): only while /setup/status says setup is needed, otherwise a "setup is complete" view.
// Steps: 1 setup token, 2 administrator (password policy hint), 3 IP collection mode (a choice is
// required, `masked` preselected, every mode explained), 4 the optional first application with its
// agent token shown once (SetupFirstApp; only when the browser kept the administrator's session,
// skippable), 5 finish with the agent installation hint.
import { useQuery } from '@tanstack/react-query';
import { useId, useState, type SubmitEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import { createAdmin, fetchSetupStatus, IP_MODES, type IpMode } from '../../api/auth';
import { queryKeys } from '../../api/queryKeys';
import { useDocumentTitle } from '../../app/useDocumentTitle';
import { getWsManager } from '../../realtime/hooks';
import { useAuthStore } from '../../state/authStore';

import './auth.css';
import {
  checkNewPassword,
  SessionNotKeptError,
  setupErrorMessage,
  USERNAME_PATTERN,
  type AuthMessage,
} from './authErrors';
import { AuthPageFrame } from './LoginPage';
import { confirmNewSession } from './session';
import { SetupFirstApp } from './SetupFirstApp';

type Step = 'token' | 'admin' | 'ipMode' | 'firstApp' | 'finish';
const STEPS: readonly Step[] = ['token', 'admin', 'ipMode', 'firstApp', 'finish'];
const STEP_LABELS = {
  token: 'setup.stepToken',
  admin: 'setup.stepAdmin',
  ipMode: 'setup.stepIpMode',
  firstApp: 'setup.stepFirstApp',
  finish: 'setup.stepFinish',
} as const;
const MODE_LABELS = {
  full: { label: 'setup.ipMode.full', help: 'setup.ipMode.fullHelp' },
  masked: { label: 'setup.ipMode.masked', help: 'setup.ipMode.maskedHelp' },
  off: { label: 'setup.ipMode.off', help: 'setup.ipMode.offHelp' },
} as const;

export interface SetupPageProps {
  readonly version: string;
}

export function SetupPage({ version }: SetupPageProps) {
  const { t } = useTranslation(['common', 'auth']);
  const status = useQuery({
    queryKey: queryKeys.setup.status(),
    queryFn: fetchSetupStatus,
    retry: false,
    staleTime: Infinity,
  });
  // null until the administrator exists; then whether the browser kept the session cookies and
  // the outcome of the first-application step (null while it is open).
  const [finished, setFinished] = useState<{
    readonly sessionKept: boolean;
    readonly firstApp: { readonly tokenIssued: boolean } | null;
  } | null>(null);
  useDocumentTitle(t('auth:setup.documentTitle'));

  let body: ReactNode;
  if (finished !== null && finished.sessionKept && finished.firstApp === null) {
    body = (
      <section className="auth-section" data-testid="setup-wizard-first-app">
        <h2>{t('auth:setup.title')}</h2>
        <SetupSteps current="firstApp" />
        <SetupFirstApp
          onDone={(outcome) => {
            setFinished({ sessionKept: true, firstApp: outcome });
          }}
        />
      </section>
    );
  } else if (finished !== null) {
    body = (
      <SetupFinish
        sessionKept={finished.sessionKept}
        tokenIssued={finished.firstApp?.tokenIssued ?? false}
      />
    );
  } else if (status.isPending) {
    body = (
      <p role="status" className="auth-form__info">
        {t('route.loading')}
      </p>
    );
  } else if (status.isError) {
    body = (
      <p role="alert" className="auth-form__error" data-testid="setup-status-error">
        {t('auth:setup.statusFailed')}
      </p>
    );
  } else if (!status.data.needs_setup) {
    body = <SetupClosed />;
  } else {
    body = (
      <SetupWizard
        onFinished={(sessionKept) => {
          setFinished({ sessionKept, firstApp: null });
        }}
      />
    );
  }
  return <AuthPageFrame version={version}>{body}</AuthPageFrame>;
}

/** The step list; `current` is marked as the current step. */
function SetupSteps({ current }: { readonly current: Step }) {
  const { t } = useTranslation(['common', 'auth']);
  return (
    <ol className="setup-steps" aria-label={t('auth:setup.steps')}>
      {STEPS.map((s) => (
        <li
          key={s}
          aria-current={s === current ? 'step' : undefined}
          className={s === current ? 'setup-steps__current' : undefined}
        >
          {t(`auth:${STEP_LABELS[s]}`)}
        </li>
      ))}
    </ol>
  );
}

/** Setup is not needed: the wizard answers like a missing page (docs/15 §3.1 "관리자 존재 시 404"). */
function SetupClosed() {
  const { t } = useTranslation(['common', 'auth']);
  return (
    <section className="auth-section" data-testid="setup-not-found">
      <h2>{t('auth:setup.notFoundTitle')}</h2>
      <p>{t('auth:setup.notFoundDescription')}</p>
      <Link to="/login">{t('auth:setup.signIn')}</Link>
    </section>
  );
}

function SetupWizard({ onFinished }: { readonly onFinished: (sessionKept: boolean) => void }) {
  const { t } = useTranslation(['common', 'auth']);
  const id = useId();
  const [step, setStep] = useState<Step>('token');
  const [token, setToken] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [ipMode, setIpMode] = useState<IpMode>('masked');
  const [error, setError] = useState<AuthMessage | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [closed, setClosed] = useState(false);

  const go = (next: Step): void => {
    setError(null);
    setStep(next);
  };

  const submitToken = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (token.trim() === '') {
      setError({ key: 'setup.token.required' });
      return;
    }
    go('admin');
  };

  const submitAdmin = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!USERNAME_PATTERN.test(username)) {
      setError({ key: 'setup.admin.usernameInvalid' });
      return;
    }
    const invalid = checkNewPassword(password, confirm);
    if (invalid !== null) {
      setError(invalid);
      return;
    }
    go('ipMode');
  };

  const submitIpMode = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    createAdmin({ setupToken: token.trim(), username, password, ipMode })
      .then(async () => {
        setPassword('');
        setConfirm('');
        setToken('');
        // The administrator exists either way. Whether the browser kept the session cookies the
        // server set decides what the last step says (ADR-0010 C-201 (2)).
        let sessionKept = true;
        try {
          const me = await confirmNewSession();
          if (me !== null) {
            useAuthStore.getState().setSession(me);
          }
          getWsManager().resetSession();
        } catch (failure) {
          if (!(failure instanceof SessionNotKeptError)) {
            throw failure;
          }
          sessionKept = false;
        }
        onFinished(sessionKept);
      })
      .catch((failure: unknown) => {
        const result = setupErrorMessage(failure);
        if (result.step === 'closed') {
          setClosed(true);
          return;
        }
        setError(result.message);
        if (result.step !== 'ipMode') {
          setStep(result.step);
        }
      })
      .finally(() => {
        setSubmitting(false);
      });
  };

  if (closed) {
    return <SetupClosed />;
  }

  const errorLine =
    error === null ? null : (
      <p className="auth-form__error" role="alert" data-testid="setup-error">
        {t(`auth:${error.key}`, error.values ?? {})}
      </p>
    );

  return (
    <section className="auth-section" aria-labelledby={`${id}-title`} data-testid="setup-wizard">
      <h2 id={`${id}-title`}>{t('auth:setup.title')}</h2>
      <SetupSteps current={step} />

      {step === 'token' && (
        <form className="auth-form" onSubmit={submitToken} noValidate data-testid="setup-token">
          <p>{t('auth:setup.token.description')}</p>
          <label className="auth-form__field">
            <span>{t('auth:setup.token.label')}</span>
            <input
              type="password"
              autoComplete="off"
              spellCheck={false}
              maxLength={256}
              value={token}
              onChange={(e) => {
                setToken(e.target.value);
              }}
              data-testid="setup-token-input"
            />
          </label>
          {errorLine}
          <div className="auth-form__actions">
            <button type="submit" className="auth-form__submit" data-testid="setup-next">
              {t('auth:setup.next')}
            </button>
          </div>
        </form>
      )}

      {step === 'admin' && (
        <form className="auth-form" onSubmit={submitAdmin} noValidate data-testid="setup-admin">
          <p>{t('auth:setup.admin.description')}</p>
          <label className="auth-form__field">
            <span>{t('auth:setup.admin.username')}</span>
            <input
              type="text"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={64}
              aria-describedby={`${id}-username-hint`}
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
              }}
              data-testid="setup-admin-username"
            />
          </label>
          <p className="auth-form__hint" id={`${id}-username-hint`}>
            {t('auth:setup.admin.usernameHint')}
          </p>
          <p className="auth-form__hint" id={`${id}-policy`}>
            {t('auth:password.policy')}
          </p>
          <label className="auth-form__field">
            <span>{t('auth:setup.admin.password')}</span>
            <input
              type="password"
              autoComplete="new-password"
              maxLength={512}
              aria-describedby={`${id}-policy`}
              value={password}
              onChange={(e) => {
                setPassword(e.target.value);
              }}
              data-testid="setup-admin-password"
            />
          </label>
          <label className="auth-form__field">
            <span>{t('auth:setup.admin.confirm')}</span>
            <input
              type="password"
              autoComplete="new-password"
              maxLength={512}
              value={confirm}
              onChange={(e) => {
                setConfirm(e.target.value);
              }}
              data-testid="setup-admin-confirm"
            />
          </label>
          {errorLine}
          <div className="auth-form__actions">
            <button
              type="button"
              className="auth-form__secondary"
              onClick={() => {
                go('token');
              }}
            >
              {t('auth:setup.back')}
            </button>
            <button type="submit" className="auth-form__submit" data-testid="setup-next">
              {t('auth:setup.next')}
            </button>
          </div>
        </form>
      )}

      {step === 'ipMode' && (
        <form className="auth-form" onSubmit={submitIpMode} noValidate data-testid="setup-ip-mode">
          <p>{t('auth:setup.ipMode.description')}</p>
          <fieldset className="auth-form__choices">
            <legend>{t('auth:setup.ipMode.legend')}</legend>
            {IP_MODES.map((mode) => (
              <label key={mode} className="auth-form__choice">
                <input
                  type="radio"
                  name="ip-mode"
                  value={mode}
                  required
                  checked={ipMode === mode}
                  aria-describedby={`${id}-${mode}-help`}
                  onChange={() => {
                    setIpMode(mode);
                  }}
                  data-testid={`setup-ip-mode-${mode}`}
                />
                <span className="auth-form__choice-label">
                  {t(`auth:${MODE_LABELS[mode].label}`)}
                </span>
                <span className="auth-form__hint" id={`${id}-${mode}-help`}>
                  {t(`auth:${MODE_LABELS[mode].help}`)}
                </span>
              </label>
            ))}
          </fieldset>
          {errorLine}
          <div className="auth-form__actions">
            <button
              type="button"
              className="auth-form__secondary"
              onClick={() => {
                go('admin');
              }}
            >
              {t('auth:setup.back')}
            </button>
            <button
              type="submit"
              className="auth-form__submit"
              disabled={submitting}
              data-testid="setup-create"
            >
              {submitting ? t('auth:setup.creating') : t('auth:setup.create')}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

/**
 * Last step: what to do next (agent token and -javaagent; C-168 (2), C-173). After a token issued
 * in the first-application step it says to store that token; otherwise it points to the token
 * create command. When the browser did not keep the session cookies, it says so instead of "you
 * are signed in" and leads to the sign-in page (ADR-0010 C-201 (2)).
 */
function SetupFinish({
  sessionKept,
  tokenIssued,
}: {
  readonly sessionKept: boolean;
  readonly tokenIssued: boolean;
}) {
  const { t } = useTranslation(['common', 'auth']);
  const navigate = useNavigate();
  return (
    <section className="auth-section" data-testid="setup-finish">
      <h2>{t('auth:setup.finish.title')}</h2>
      {sessionKept ? (
        <p>{t('auth:setup.finish.description')}</p>
      ) : (
        <p className="auth-form__error" role="alert" data-testid="setup-session-not-kept">
          {t('auth:setup.finish.sessionNotKept')}
        </p>
      )}
      <h3>{t('auth:setup.finish.agentTitle')}</h3>
      <ol className="setup-next-steps">
        {tokenIssued ? (
          <li data-testid="setup-finish-token-issued">{t('auth:setup.finish.agentTokenIssued')}</li>
        ) : (
          <li data-testid="setup-finish-token-hint">{t('auth:setup.finish.agentToken')}</li>
        )}
        <li>{t('auth:setup.finish.agentAttach')}</li>
      </ol>
      {sessionKept ? (
        <button
          type="button"
          className="auth-form__submit"
          onClick={() => {
            void navigate('/overview', { replace: true });
          }}
          data-testid="setup-open-overview"
        >
          {t('auth:setup.finish.open')}
        </button>
      ) : (
        <button
          type="button"
          className="auth-form__submit"
          onClick={() => {
            void navigate('/login', { replace: true });
          }}
          data-testid="setup-go-sign-in"
        >
          {t('auth:setup.signIn')}
        </button>
      )}
    </section>
  );
}
