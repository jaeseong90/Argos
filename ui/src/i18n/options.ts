// SPDX-License-Identifier: Apache-2.0
import type { InitOptions } from 'i18next';

import { defaultNS, supportedLanguages, type SupportedLanguage } from './resources';

/** i18next options shared by the application instance and the eager test instance. */
export function i18nOptions(language: SupportedLanguage): InitOptions {
  return {
    lng: language,
    fallbackLng: 'en',
    supportedLngs: supportedLanguages,
    defaultNS,
    // React renders interpolated values as text nodes, so i18next must not HTML-escape them
    // (that would double-escape). HTML is never assembled from strings (SEC-60, SEC-61).
    interpolation: { escapeValue: false },
    // The bundled catalogs are in place when init returns, so the shell renders on the first pass.
    initAsync: false,
  };
}
