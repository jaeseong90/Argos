// SPDX-License-Identifier: Apache-2.0
//
// The application's i18next instance (ADR-0010 C-209): the shell catalogs (common, shell, auth) are
// bundled with the entry chunk, the screen catalogs (xlog, tx, trace, dashboard, ...) are
// same-origin chunks that a route loads together with its component (routeNamespaces.ts), so the
// first screen does not carry the catalogs of every other screen.
import i18next, {
  type BackendModule,
  type i18n,
  type Resource,
  type ResourceLanguage,
} from 'i18next';
import { initReactI18next } from 'react-i18next';

import { createLazyBackend } from './lazyBackend';
import { i18nOptions } from './options';
import { bundledNamespaces, bundledResources, type SupportedLanguage } from './resources';

/** A per-language copy of the resources (the catalogs themselves are shared, i18next never edits them). */
function copyResources(resources: Readonly<Record<string, ResourceLanguage>>): Resource {
  return Object.fromEntries(
    Object.entries(resources).map(([language, catalogs]) => [language, { ...catalogs }]),
  );
}

/**
 * Creates the application's i18next instance: bundled shell catalogs, initialised synchronously,
 * and a backend that loads the other namespaces on demand (i18n.loadNamespaces). A language switch
 * loads the new language of every namespace loaded so far before it applies (i18next keeps them in
 * options.ns); a catalog that fails to load leaves the English one in use.
 */
export function createAppI18n(
  language: SupportedLanguage,
  backend: BackendModule = createLazyBackend(),
): i18n {
  const instance = i18next.createInstance();
  void instance
    .use(backend)
    .use(initReactI18next)
    .init({
      ...i18nOptions(language),
      // i18next stores loaded catalogs into the resources object it is given: a copy per instance
      // keeps the module's bundledResources unchanged.
      resources: copyResources(bundledResources),
      // Bundled catalogs plus the backend for the namespaces that are not bundled.
      partialBundledLanguages: true,
      ns: [...bundledNamespaces],
    });
  return instance;
}
