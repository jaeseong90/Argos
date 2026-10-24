// SPDX-License-Identifier: Apache-2.0
import i18next, { type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';

import {
  defaultNS,
  namespaces,
  resources,
  supportedLanguages,
  type SupportedLanguage,
} from './resources';

/** Creates an isolated, synchronously initialised i18next instance for the given language. */
export function createI18n(language: SupportedLanguage): i18n {
  const instance = i18next.createInstance();
  void instance.use(initReactI18next).init({
    resources,
    lng: language,
    fallbackLng: 'en',
    supportedLngs: supportedLanguages,
    defaultNS,
    ns: namespaces,
    // React renders interpolated values as text nodes, so i18next must not HTML-escape them
    // (that would double-escape). HTML is never assembled from strings (SEC-60, SEC-61).
    interpolation: { escapeValue: false },
    // Resources are bundled, so initialisation completes before the first render.
    initAsync: false,
  });
  return instance;
}

/** Keeps `<html lang>` in sync with the UI language (screen readers, hyphenation). */
export function bindDocumentLanguage(instance: i18n, doc: Document): () => void {
  const apply = (language: string): void => {
    doc.documentElement.lang = language;
  };
  apply(instance.resolvedLanguage ?? 'en');
  instance.on('languageChanged', apply);
  return () => {
    instance.off('languageChanged', apply);
  };
}
