// SPDX-License-Identifier: Apache-2.0
import i18next, { type i18n } from 'i18next';
import { initReactI18next } from 'react-i18next';

import { eagerResources } from './eagerResources';
import { i18nOptions } from './options';
import { namespaces, type SupportedLanguage } from './resources';

export { bindDocumentLanguage } from './documentLanguage';

/**
 * Creates an isolated, synchronously initialised i18next instance for the given language with
 * every catalog bundled (unit tests and other synchronous callers). The application uses
 * createAppI18n, which loads the screen catalogs with their routes (ADR-0010 C-209).
 */
export function createI18n(language: SupportedLanguage): i18n {
  const instance = i18next.createInstance();
  void instance.use(initReactI18next).init({
    ...i18nOptions(language),
    resources: eagerResources(),
    ns: [...namespaces],
  });
  return instance;
}
