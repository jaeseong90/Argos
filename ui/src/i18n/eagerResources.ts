// SPDX-License-Identifier: Apache-2.0
//
// Every catalog of src/locales, bundled into the module that imports this one. Only the eager
// createI18n (unit tests, synchronous) uses it; the application loads the screen catalogs lazily
// (appI18n.ts, ADR-0010 C-209), so nothing on the production path imports this module.
import type { Resource } from 'i18next';

const CATALOGS = import.meta.glob<Record<string, unknown>>('../locales/*/*.json', {
  eager: true,
  import: 'default',
});

const CATALOG_PATH = /^\.\.\/locales\/([^/]+)\/([^/]+)\.json$/;

/**
 * All catalogs of all languages, as i18next resources ({ en: { common: {...}, ... }, ko: ... }).
 * A function, not a module-level value: the module has no top-level call, so a bundle that does not
 * call createI18n drops it together with the catalogs.
 */
export function eagerResources(): Resource {
  const resources: Record<string, Record<string, Record<string, unknown>>> = {};
  for (const [path, catalog] of Object.entries(CATALOGS)) {
    const match = CATALOG_PATH.exec(path);
    if (match === null) {
      continue;
    }
    const [, language = '', namespace = ''] = match;
    resources[language] = { ...resources[language], [namespace]: catalog };
  }
  return resources;
}
