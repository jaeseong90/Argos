// SPDX-License-Identifier: Apache-2.0
//
// /account (docs/15 §9.11, ADR-0010 C-168 (2)): who is logged in, the role, the password change
// with the policy hint, "My sessions" (own live sessions, ending the others; docs/16 §7.10,
// C-136 (3)) and the UI language.
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { queryKeys } from '../../api/queryKeys';
import { SafeText } from '../../components/SafeText';
import { supportedLanguages } from '../../i18n';
import { useLanguage } from '../../i18n/useLanguage';
import { useAuthStore } from '../../state/authStore';

import './auth.css';
import { OwnSessions } from './OwnSessions';
import { PasswordChangeForm } from './PasswordChangeForm';

const LANGUAGE_LABEL_KEYS = { en: 'language.en', ko: 'language.ko' } as const;
const ROLE_KEYS = {
  viewer: 'shell:role.viewer',
  operator: 'shell:role.operator',
  admin: 'shell:role.admin',
} as const;

export function AccountPage() {
  const { t } = useTranslation(['common', 'auth', 'shell']);
  const user = useAuthStore((s) => s.user);
  const role = useAuthStore((s) => s.role);
  const { language, setLanguage } = useLanguage();
  const queryClient = useQueryClient();

  return (
    <div className="account-page" data-testid="account-page">
      <h1>{t('auth:account.title')}</h1>
      <dl className="account-page__facts">
        <dt>{t('auth:account.user')}</dt>
        <dd>
          {/* User names are untrusted input (docs/16 §9.1) although the contract does not flag them. */}
          <SafeText value={user?.username} data-testid="account-username" />
        </dd>
        <dt>{t('auth:account.role')}</dt>
        <dd data-testid="account-role">
          {role === null ? t('shell:role.none') : t(ROLE_KEYS[role])}
        </dd>
      </dl>
      <section className="account-page__section" aria-labelledby="account-password">
        <h2 id="account-password">{t('auth:account.passwordTitle')}</h2>
        <PasswordChangeForm
          showSuccess
          onChanged={() => {
            // The change signed the other sessions out (16 §7.4): list what is left.
            void queryClient.invalidateQueries({ queryKey: queryKeys.auth.sessions() });
          }}
        />
      </section>
      <OwnSessions />
      <section className="account-page__section" aria-labelledby="account-language">
        <h2 id="account-language">{t('auth:account.languageTitle')}</h2>
        <p>{t('auth:account.languageDescription')}</p>
        <div
          role="radiogroup"
          aria-labelledby="account-language"
          className="account-page__languages"
        >
          {supportedLanguages.map((code) => (
            <label key={code} className="auth-form__choice">
              <input
                type="radio"
                name="account-language"
                checked={language === code}
                onChange={() => {
                  setLanguage(code);
                }}
                data-testid={`account-language-${code}`}
              />
              <span lang={code}>{t(LANGUAGE_LABEL_KEYS[code])}</span>
            </label>
          ))}
        </div>
      </section>
    </div>
  );
}
