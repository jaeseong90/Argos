// SPDX-License-Identifier: Apache-2.0
//
// Password change form (docs/15 §9.11, docs/16 §7.9): current, new and repeated password, the
// policy hint (12..128 characters, no common passwords, no user name, no "argos"), client checks
// before the call and the server's policy reasons after it. Used by the forced change after a
// login with must_change_password and by the account page.
import { useId, useState, type SubmitEvent } from 'react';
import { useTranslation } from 'react-i18next';

import { changePassword } from '../../api/auth';

import { checkNewPassword, passwordErrorMessage, type AuthMessage } from './authErrors';

export interface PasswordChangeFormProps {
  /** Called after the server accepted the new password (204, new cookies). */
  readonly onChanged: () => void;
  /** Shows the success text in the form (account page) instead of leaving it to the caller. */
  readonly showSuccess?: boolean;
}

export function PasswordChangeForm({ onChanged, showSuccess = false }: PasswordChangeFormProps) {
  const { t } = useTranslation(['common', 'auth']);
  const id = useId();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<AuthMessage | null>(null);
  const [done, setDone] = useState(false);

  const submit = (event: SubmitEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const invalid =
      current === ''
        ? { key: 'password.error.required' as const }
        : checkNewPassword(next, confirm);
    if (invalid !== null) {
      setError(invalid);
      return;
    }
    setSubmitting(true);
    setError(null);
    changePassword(current, next)
      .then(() => {
        setCurrent('');
        setNext('');
        setConfirm('');
        setDone(true);
        onChanged();
      })
      .catch((failure: unknown) => {
        setError(passwordErrorMessage(failure));
      })
      .finally(() => {
        setSubmitting(false);
      });
  };

  const policyId = `${id}-policy`;
  const errorId = `${id}-error`;
  return (
    <form className="auth-form" onSubmit={submit} noValidate data-testid="password-change">
      <p className="auth-form__hint" id={policyId}>
        {t('auth:password.policy')}
      </p>
      <label className="auth-form__field">
        <span>{t('auth:password.current')}</span>
        <input
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => {
            setCurrent(e.target.value);
          }}
          data-testid="password-change-current"
        />
      </label>
      <label className="auth-form__field">
        <span>{t('auth:password.new')}</span>
        <input
          type="password"
          autoComplete="new-password"
          aria-describedby={policyId}
          maxLength={512}
          value={next}
          onChange={(e) => {
            setNext(e.target.value);
          }}
          data-testid="password-change-new"
        />
      </label>
      <label className="auth-form__field">
        <span>{t('auth:password.confirm')}</span>
        <input
          type="password"
          autoComplete="new-password"
          maxLength={512}
          value={confirm}
          onChange={(e) => {
            setConfirm(e.target.value);
          }}
          data-testid="password-change-confirm"
        />
      </label>
      {error !== null && (
        <p
          className="auth-form__error"
          role="alert"
          id={errorId}
          data-testid="password-change-error"
        >
          {t(`auth:${error.key}`, error.values ?? {})}
        </p>
      )}
      {showSuccess && done && error === null && (
        <p className="auth-form__success" role="status" data-testid="password-change-success">
          {t('auth:password.changed')}
        </p>
      )}
      <button
        type="submit"
        className="auth-form__submit"
        disabled={submitting}
        data-testid="password-change-submit"
      >
        {submitting ? t('auth:password.submitting') : t('auth:password.submit')}
      </button>
    </form>
  );
}
