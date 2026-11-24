// SPDX-License-Identifier: Apache-2.0
import type enActive from '../locales/en/active.json';
import type enAdmin from '../locales/en/admin.json';
import type enAlerts from '../locales/en/alerts.json';
import enAuth from '../locales/en/auth.json';
import type enCommands from '../locales/en/commands.json';
import enCommon from '../locales/en/common.json';
import type enDashboard from '../locales/en/dashboard.json';
import type enInstance from '../locales/en/instance.json';
import type enOverview from '../locales/en/overview.json';
import type enSearch from '../locales/en/search.json';
import enShell from '../locales/en/shell.json';
import type enStats from '../locales/en/stats.json';
import type enTrace from '../locales/en/trace.json';
import type enTx from '../locales/en/tx.json';
import type enXlog from '../locales/en/xlog.json';
import koAuth from '../locales/ko/auth.json';
import koCommon from '../locales/ko/common.json';
import koShell from '../locales/ko/shell.json';

/** Namespace used when a key has no `<ns>:` prefix (docs/15 §12). */
export const defaultNS = 'common';

/** UI languages. English is the source language; Korean is a translation (O-A05). */
export const supportedLanguages = ['en', 'ko'] as const;

export type SupportedLanguage = (typeof supportedLanguages)[number];

/**
 * Catalogs bundled with the entry chunk: the namespaces of the shell, the login and the setup
 * pages, which every route renders (ADR-0010 C-209). The UI never loads translations from another
 * origin (O-P03).
 */
export const bundledResources = {
  en: {
    common: enCommon,
    shell: enShell,
    auth: enAuth,
  },
  ko: {
    common: koCommon,
    shell: koShell,
    auth: koAuth,
  },
} as const;

/** Namespaces of the bundled catalogs. */
export type BundledNamespace = keyof (typeof bundledResources)['en'];

export const bundledNamespaces = Object.keys(bundledResources.en) as BundledNamespace[];

/**
 * English source catalogs of the screen namespaces (types only: the catalogs themselves are
 * same-origin chunks that lazyBackend.ts loads with the screen, ADR-0010 C-209).
 */
interface LazyCatalogs {
  xlog: typeof enXlog;
  tx: typeof enTx;
  trace: typeof enTrace;
  dashboard: typeof enDashboard;
  overview: typeof enOverview;
  stats: typeof enStats;
  active: typeof enActive;
  alerts: typeof enAlerts;
  instance: typeof enInstance;
  commands: typeof enCommands;
  admin: typeof enAdmin;
  search: typeof enSearch;
}

/** Screen namespaces, loaded with the route that renders them (routeNamespaces.ts). */
export type LazyNamespace = keyof LazyCatalogs;

export const lazyNamespaces = [
  'xlog',
  'tx',
  'trace',
  'dashboard',
  'overview',
  'stats',
  'active',
  'alerts',
  'instance',
  'commands',
  'admin',
  'search',
] as const satisfies readonly LazyNamespace[];

/** English source catalogs of every namespace: the typed keys of i18next.d.ts. */
export type Catalogs = (typeof bundledResources)['en'] & LazyCatalogs;

/** Every namespace (docs/15 §12). */
export const namespaces: readonly (keyof Catalogs)[] = [...bundledNamespaces, ...lazyNamespaces];
