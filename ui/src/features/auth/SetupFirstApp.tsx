// SPDX-License-Identifier: Apache-2.0
//
// The optional "first application" step of the setup wizard (docs/15 §9.11, ADR-0010 C-168 (2)):
// once the administrator is signed in, a name and an environment register the application
// (POST /apps, C-153) and issue its agent token (POST /admin/apps/{appId}/tokens, docs/14 §4.14).
// The token is shown once with a copy button, next to how to store it and the -javaagent options
// (docs/agent-config.md). It lives only in this component's state: not in the query or mutation
// cache, not in browser storage, not in the URL (docs/16 §2.1, SEC-02); leaving the step drops it.
// The step can be skipped; the finish step then keeps the token create command hint (C-173).
import { useQueryClient } from '@tanstack/react-query';
import { useId, useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { createApp, issueAgentToken } from '../../api/apps';
import { isApiError } from '../../api/errors';
import { queryKeys } from '../../api/queryKeys';
import { CodeBlock } from '../../components/CodeBlock';
import { useFormat } from '../../i18n/format';
// The -javaagent options are shared with the install guide of the application list (WO-39).
import { javaagentOptions } from '../admin/install/javaagent';

/** Limits of the server (AppInputs: name 1..128, env 1..32, no control characters). */
export const APP_NAME_MAX = 128;
export const APP_ENV_MAX = 32;
/** C0 and C1 control characters and DEL, which the server refuses in names. */
function hasControl(value: string): boolean {
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    if (code < 0x20 || (code >= 0x7f && code <= 0x9f)) {
      return true;
    }
  }
  return false;
}

type FirstAppMessage =
  | { readonly key: 'nameRequired' | 'envRequired' | 'exists' | 'invalid' }
  | {
      readonly key: 'tokenFailed' | 'tokenFailedNoId' | 'failed' | 'failedNoId';
      readonly requestId?: string;
    };

/** A field value as the server stores it, or null when it breaks the server's rules. */
function fieldValue(raw: string, max: number): string | null {
  const value = raw.trim();
  if (value === '' || Array.from(value).length > max || hasControl(value)) {
    return null;
  }
  return value;
}

function createFailure(failure: unknown): FirstAppMessage {
  if (isApiError(failure)) {
    if (failure.status === 409) {
      return { key: 'exists' };
    }
    if (failure.status === 400) {
      return { key: 'invalid' };
    }
    if (failure.requestId !== null) {
      return { key: 'failed', requestId: failure.requestId };
    }
  }
  return { key: 'failedNoId' };
}

function tokenFailure(failure: unknown): FirstAppMessage {
  if (isApiError(failure) && failure.requestId !== null) {
    return { key: 'tokenFailed', requestId: failure.requestId };
  }
  return { key: 'tokenFailedNoId' };
}

/** What the finish step needs to know: whether a token was issued here. */
export interface FirstAppOutcome {
  readonly tokenIssued: boolean;
}

/** The registered application as the administrator entered it (trimmed). */
interface RegisteredApp {
  readonly appId: number;
  readonly name: string;
  readonly env: string;
}

interface ShownToken {
  readonly token: string;
  readonly expiresAt: string | null;
  /** argos.collector.public-address of the server, or null (C-231 (9)). */
  readonly collectorAddress: string | null;
}

export interface SetupFirstAppProps {
  readonly onDone: (outcome: FirstAppOutcome) => void;
}

export function SetupFirstApp({ onDone }: SetupFirstAppProps) {
  const { t } = useTranslation(['common', 'auth']);
  const format = useFormat();
  const id = useId();
  const queryClient = useQueryClient();
  const [name, setName] = useState('');
  const [env, setEnv] = useState('prod');
  const [error, setError] = useState<FirstAppMessage | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The application stays known when only the token failed, so "again" does not register twice.
  const [app, setApp] = useState<RegisteredApp | null>(null);
  const [shown, setShown] = useState<ShownToken | null>(null);
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');

  const issue = async (target: RegisteredApp): Promise<void> => {
    try {
      const issued = await issueAgentToken(target.appId);
      setShown({
        token: issued.token,
        expiresAt: issued.expires_at,
        collectorAddress: issued.collector_address ?? null,
      });
    } catch (failure) {
      setError(tokenFailure(failure));
    }
  };

  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setError(null);
    if (app !== null) {
      setSubmitting(true);
      void issue(app).finally(() => {
        setSubmitting(false);
      });
      return;
    }
    const appName = fieldValue(name, APP_NAME_MAX);
    if (appName === null) {
      setError({ key: 'nameRequired' });
      return;
    }
    const appEnv = fieldValue(env, APP_ENV_MAX);
    if (appEnv === null) {
      setError({ key: 'envRequired' });
      return;
    }
    setSubmitting(true);
    createApp({ name: appName, env: appEnv })
      .then(async (created) => {
        const registered = { appId: created.app_id, name: appName, env: appEnv };
        setApp(registered);
        // The application selector of the shell lists it from now on.
        void queryClient.invalidateQueries({ queryKey: queryKeys.apps.all() });
        await issue(registered);
      })
      .catch((failure: unknown) => {
        setError(createFailure(failure));
      })
      .finally(() => {
        setSubmitting(false);
      });
  };

  const copy = (token: string): void => {
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (clipboard === undefined) {
      setCopied('failed');
      return;
    }
    clipboard.writeText(token).then(
      () => {
        setCopied('done');
      },
      () => {
        setCopied('failed');
      },
    );
  };

  const errorLine =
    error === null ? null : (
      <p className="auth-form__error" role="alert" data-testid="setup-first-app-error">
        {'requestId' in error && error.requestId !== undefined
          ? t(`auth:setup.firstApp.error.${error.key}`, { requestId: error.requestId })
          : t(`auth:setup.firstApp.error.${error.key}`)}
      </p>
    );

  if (shown !== null && app !== null) {
    const expires = shown.expiresAt === null ? Number.NaN : Date.parse(shown.expiresAt);
    return (
      <div className="auth-form setup-first-app" data-testid="setup-first-app-token">
        <h3>{t('auth:setup.firstApp.tokenTitle')}</h3>
        <p className="auth-form__info" data-testid="setup-first-app-token-once">
          {t('auth:setup.firstApp.tokenOnce')}
        </p>
        <label className="auth-form__field">
          <span>{t('auth:setup.firstApp.tokenLabel')}</span>
          <input
            className="setup-first-app__token"
            type="text"
            readOnly
            autoComplete="off"
            spellCheck={false}
            value={shown.token}
            onFocus={(e) => {
              e.currentTarget.select();
            }}
            data-testid="setup-first-app-token-value"
          />
        </label>
        <div className="setup-first-app__copy">
          <button
            type="button"
            className="auth-form__secondary"
            onClick={() => {
              copy(shown.token);
            }}
            data-testid="setup-first-app-copy"
          >
            {t('auth:setup.firstApp.copy')}
          </button>
          <span className="auth-form__hint" role="status" data-testid="setup-first-app-copy-status">
            {copied === 'done'
              ? t('auth:setup.firstApp.copied')
              : copied === 'failed'
                ? t('auth:setup.firstApp.copyFailed')
                : null}
          </span>
        </div>
        <p className="auth-form__hint" data-testid="setup-first-app-token-expiry">
          {Number.isNaN(expires)
            ? t('auth:setup.firstApp.tokenNoExpiry')
            : t('auth:setup.firstApp.tokenExpires', { time: format.dateTime(expires) })}
        </p>
        <h3>{t('auth:setup.firstApp.storeTitle')}</h3>
        <p>{t('auth:setup.firstApp.store')}</p>
        <h3>{t('auth:setup.firstApp.attachTitle')}</h3>
        <p>{t('auth:setup.firstApp.attach')}</p>
        <CodeBlock
          value={javaagentOptions(app.name, app.env, undefined, shown.collectorAddress)}
          label={t('auth:setup.firstApp.snippetLabel')}
          foldLines={0}
          data-testid="setup-first-app-snippet"
        />
        <div className="auth-form__actions">
          <button
            type="button"
            className="auth-form__submit"
            onClick={() => {
              onDone({ tokenIssued: true });
            }}
            data-testid="setup-first-app-continue"
          >
            {t('auth:setup.firstApp.continue')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <form className="auth-form" onSubmit={submit} noValidate data-testid="setup-first-app">
      <p>{t('auth:setup.firstApp.description')}</p>
      <label className="auth-form__field">
        <span>{t('auth:setup.firstApp.name')}</span>
        <input
          type="text"
          autoComplete="off"
          spellCheck={false}
          maxLength={APP_NAME_MAX}
          disabled={app !== null}
          aria-describedby={`${id}-name-hint`}
          value={name}
          onChange={(e) => {
            setName(e.target.value);
          }}
          data-testid="setup-first-app-name"
        />
      </label>
      <p className="auth-form__hint" id={`${id}-name-hint`}>
        {t('auth:setup.firstApp.nameHint')}
      </p>
      <label className="auth-form__field">
        <span>{t('auth:setup.firstApp.env')}</span>
        <input
          type="text"
          autoComplete="off"
          spellCheck={false}
          maxLength={APP_ENV_MAX}
          disabled={app !== null}
          aria-describedby={`${id}-env-hint`}
          value={env}
          onChange={(e) => {
            setEnv(e.target.value);
          }}
          data-testid="setup-first-app-env"
        />
      </label>
      <p className="auth-form__hint" id={`${id}-env-hint`}>
        {t('auth:setup.firstApp.envHint')}
      </p>
      {errorLine}
      <div className="auth-form__actions">
        <button
          type="button"
          className="auth-form__secondary"
          disabled={submitting}
          onClick={() => {
            onDone({ tokenIssued: false });
          }}
          data-testid="setup-first-app-skip"
        >
          {t('auth:setup.firstApp.skip')}
        </button>
        <button
          type="submit"
          className="auth-form__submit"
          disabled={submitting}
          data-testid="setup-first-app-create"
        >
          {submitting
            ? t('auth:setup.firstApp.creating')
            : app !== null
              ? t('auth:setup.firstApp.retryToken')
              : t('auth:setup.firstApp.create')}
        </button>
      </div>
    </form>
  );
}
