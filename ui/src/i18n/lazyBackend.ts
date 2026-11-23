// SPDX-License-Identifier: Apache-2.0
//
// i18next backend for the screen catalogs (ADR-0010 C-209): every catalog of src/locales except the
// bundled ones (resources.ts bundledResources) is its own same-origin Vite chunk, loaded by a
// dynamic import the first time a route needs its namespace (routeNamespaces.ts). No fetch to
// another origin, no runtime URL built from input (O-P03, SEC-46 script-src 'self'): the loaders
// are the fixed list Vite generates at build time.
import type { BackendModule, ReadCallback } from 'i18next';

/** A catalog loader: resolves to the catalog object of one language and namespace. */
export type CatalogLoader = () => Promise<unknown>;

/** Catalog path (as Vite's glob keys it) to its loader. */
export type CatalogLoaders = Readonly<Record<string, CatalogLoader>>;

/**
 * The lazily loaded catalogs: all of src/locales without the bundled namespaces (common, shell,
 * auth; keep this exclusion list equal to bundledNamespaces, lazyBackend.test.ts checks it).
 */
export const LAZY_CATALOGS: CatalogLoaders = import.meta.glob(
  [
    '../locales/*/*.json',
    '!../locales/*/common.json',
    '!../locales/*/shell.json',
    '!../locales/*/auth.json',
  ],
  { import: 'default' },
);

/** Glob key of a catalog. */
export function catalogPath(language: string, namespace: string): string {
  return `../locales/${language}/${namespace}.json`;
}

function isCatalog(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * The backend. A missing or failed catalog is reported to i18next without a retry: the route then
 * renders with the English catalog (i18next loads the fallback language `en` with every namespace)
 * instead of waiting through retry delays.
 */
export function createLazyBackend(loaders: CatalogLoaders = LAZY_CATALOGS): BackendModule {
  return {
    type: 'backend',
    init() {
      // No options: the loaders are fixed at build time.
    },
    read(language: string, namespace: string, callback: ReadCallback) {
      const path = catalogPath(language, namespace);
      const load = Object.prototype.hasOwnProperty.call(loaders, path) ? loaders[path] : undefined;
      if (load === undefined) {
        callback(new Error(`No catalog for ${language}/${namespace}`), false);
        return;
      }
      load().then(
        (catalog) => {
          if (isCatalog(catalog)) {
            callback(null, catalog);
          } else {
            callback(new Error(`Catalog ${language}/${namespace} is not an object`), false);
          }
        },
        (error: unknown) => {
          callback(error instanceof Error ? error : new Error(String(error)), false);
        },
      );
    },
  };
}
