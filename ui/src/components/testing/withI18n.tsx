// SPDX-License-Identifier: Apache-2.0
//
// Test wrapper: an isolated i18next instance in the given language, for
// `render(ui, { wrapper: withI18n('ko') })`. Test-only module: nothing in the application imports it.
import type { ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';

import { createI18n, type SupportedLanguage } from '../../i18n';

/** A render wrapper that provides translations in `language` (default English). */
export function withI18n(language: SupportedLanguage = 'en') {
  const i18n = createI18n(language);
  return function I18nWrapper({ children }: { readonly children: ReactNode }) {
    return <I18nextProvider i18n={i18n}>{children}</I18nextProvider>;
  };
}
