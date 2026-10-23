// SPDX-License-Identifier: Apache-2.0
import { useTranslation } from 'react-i18next';

import { supportedLanguages, type SupportedLanguage } from '../i18n';

const languageLabelKeys = {
  en: 'language.en',
  ko: 'language.ko',
} as const satisfies Record<SupportedLanguage, string>;

export interface AppProps {
  /** Build version (DSN-19-10). Rendered as text only. */
  readonly version: string;
}

export function App({ version }: AppProps) {
  const { t, i18n } = useTranslation();
  const current = i18n.resolvedLanguage;

  return (
    <main className="app">
      <header>
        <h1>{t('app.name')}</h1>
        <p>{t('app.tagline')}</p>
      </header>
      <p className="app__notice">{t('app.status.preRelease')}</p>
      <nav aria-label={t('language.label')} className="app__languages">
        {supportedLanguages.map((language) => (
          <button
            key={language}
            type="button"
            lang={language}
            aria-pressed={current === language}
            onClick={() => {
              void i18n.changeLanguage(language);
            }}
          >
            {t(languageLabelKeys[language])}
          </button>
        ))}
      </nav>
      <footer>
        <p className="app__version">{t('app.version', { version })}</p>
      </footer>
    </main>
  );
}
