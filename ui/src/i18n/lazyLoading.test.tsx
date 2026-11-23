// SPDX-License-Identifier: Apache-2.0
//
// T-UI-006 (ADR-0010 C-209): the shell catalogs are bundled, the screen catalogs are same-origin
// chunks loaded with their route. A loaded screen renders its texts on the first pass, a language
// switch brings the new language of every loaded namespace, and a catalog that fails to load leaves
// the English one in use.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U13' }, { type: 'req', description: 'N-14' }, { type: 'req', description: 'O-P03' }
import { render, screen } from '@testing-library/react';
import { Suspense } from 'react';
import { I18nextProvider, useTranslation } from 'react-i18next';
import { describe, expect, it, vi } from 'vitest';

import enCommon from '../locales/en/common.json';
import enDashboard from '../locales/en/dashboard.json';
import enTx from '../locales/en/tx.json';
import enXlog from '../locales/en/xlog.json';
import koDashboard from '../locales/ko/dashboard.json';
import koTrace from '../locales/ko/trace.json';
import koTx from '../locales/ko/tx.json';
import koXlog from '../locales/ko/xlog.json';

import { createAppI18n } from './appI18n';
import { createI18n } from './createI18n';
import {
  catalogPath,
  createLazyBackend,
  LAZY_CATALOGS,
  type CatalogLoader,
  type CatalogLoaders,
} from './lazyBackend';
import {
  bundledNamespaces,
  lazyNamespaces,
  namespaces,
  supportedLanguages,
  type SupportedLanguage,
} from './resources';
import { loadRouteNamespaces } from './routeNamespaces';

/** Every catalog file of src/locales (keys only: nothing is loaded). */
const LOCALE_FILES = Object.keys(import.meta.glob('../locales/*/*.json'));

/** Loaders over the real catalogs that count their calls and can be made to fail. */
function countingLoaders(fail: ReadonlySet<string> = new Set()): {
  loaders: CatalogLoaders;
  calls: string[];
} {
  const calls: string[] = [];
  const loaders: Record<string, CatalogLoader> = {};
  for (const [path, load] of Object.entries(LAZY_CATALOGS)) {
    loaders[path] = async () => {
      calls.push(path);
      if (fail.has(path)) {
        throw new TypeError('Failed to fetch dynamically imported module');
      }
      return load();
    };
  }
  return { loaders, calls };
}

function appI18n(language: SupportedLanguage, loaders: CatalogLoaders = LAZY_CATALOGS) {
  return createAppI18n(language, createLazyBackend(loaders));
}

describe('T-UI-006 catalog split (ADR-0010 C-209)', () => {
  it('T-UI-006 every catalog of src/locales is either bundled or lazy, in every language', () => {
    for (const language of supportedLanguages) {
      const prefix = `../locales/${language}/`;
      const files = LOCALE_FILES.filter((file) => file.startsWith(prefix))
        .map((file) => file.slice(prefix.length, -'.json'.length))
        .sort();
      expect(files).toEqual([...namespaces].sort());
    }
    expect(
      bundledNamespaces.filter((ns) => (lazyNamespaces as readonly string[]).includes(ns)),
    ).toEqual([]);
  });

  it('T-UI-006 the lazy loaders are exactly the lazy namespaces (no bundled catalog twice)', () => {
    const expected = supportedLanguages
      .flatMap((language) => lazyNamespaces.map((ns) => catalogPath(language, ns)))
      .sort();
    expect(Object.keys(LAZY_CATALOGS).sort()).toEqual(expected);
  });

  it('T-UI-006 the eager test instance still has every namespace synchronously', () => {
    const i18n = createI18n('ko');
    for (const ns of namespaces) {
      expect(i18n.hasResourceBundle('ko', ns)).toBe(true);
      expect(i18n.hasResourceBundle('en', ns)).toBe(true);
    }
    expect(i18n.t('xlog:page.refreshing')).toBe(koXlog.page.refreshing);
  });
});

describe('T-UI-006 application instance (createAppI18n)', () => {
  it('T-UI-006 starts synchronously with the shell catalogs only', () => {
    const { loaders, calls } = countingLoaders();
    const i18n = appI18n('en', loaders);
    expect(i18n.isInitialized).toBe(true);
    expect(i18n.t('common:app.tagline')).toBe(enCommon.app.tagline);
    for (const ns of bundledNamespaces) {
      expect(i18n.hasResourceBundle('en', ns)).toBe(true);
    }
    for (const ns of lazyNamespaces) {
      expect(i18n.hasResourceBundle('en', ns)).toBe(false);
    }
    expect(calls).toEqual([]);
  });

  it('T-UI-006 a route loads its namespaces in the UI language and the English fallback', async () => {
    const { loaders, calls } = countingLoaders();
    const i18n = appI18n('ko', loaders);

    await loadRouteNamespaces('trace', i18n);

    expect([...calls].sort()).toEqual(
      [
        catalogPath('en', 'trace'),
        catalogPath('en', 'tx'),
        catalogPath('ko', 'trace'),
        catalogPath('ko', 'tx'),
      ].sort(),
    );
    expect(i18n.t('trace:page.refreshing')).toBe(koTrace.page.refreshing);
    expect(i18n.t('tx:notFound.title')).toBe(koTx.notFound.title);
    expect(i18n.hasResourceBundle('ko', 'xlog')).toBe(false);

    // Loaded once: entering the route again loads nothing.
    await loadRouteNamespaces('trace', i18n);
    expect(calls).toHaveLength(4);
  });

  it('T-UI-006 the dashboard route loads the dashboard catalog', async () => {
    const i18n = appI18n('ko');
    expect(i18n.hasResourceBundle('ko', 'dashboard')).toBe(false);
    await loadRouteNamespaces('dashboard', i18n);
    expect(i18n.t('dashboard:page.refreshing')).toBe(koDashboard.page.refreshing);
    await i18n.changeLanguage('en');
    expect(i18n.t('dashboard:page.refreshing')).toBe(enDashboard.page.refreshing);
  });

  it('T-UI-006 catalogs loaded by one instance do not leak into another', async () => {
    const first = appI18n('ko');
    await loadRouteNamespaces('transaction', first);
    expect(first.hasResourceBundle('ko', 'tx')).toBe(true);
    const second = appI18n('ko');
    expect(second.hasResourceBundle('ko', 'tx')).toBe(false);
    expect(second.hasResourceBundle('en', 'tx')).toBe(false);
  });

  it('T-UI-006 a route without screen catalogs loads nothing', async () => {
    const { loaders, calls } = countingLoaders();
    const i18n = appI18n('ko', loaders);
    await loadRouteNamespaces('account', i18n);
    // /alerts has its catalog since WO-38; the administration screens still have none (WO-39).
    await loadRouteNamespaces('admin-apps', i18n);
    expect(calls).toEqual([]);
  });

  it('T-UI-006 a language switch loads the new language of the loaded namespaces before it applies', async () => {
    const { loaders, calls } = countingLoaders();
    const i18n = appI18n('en', loaders);
    await loadRouteNamespaces('xlog', i18n);
    expect(calls).toEqual([catalogPath('en', 'xlog')]);
    expect(i18n.t('xlog:page.refreshing')).toBe(enXlog.page.refreshing);

    const languages: string[] = [];
    i18n.on('languageChanged', (language: string) => {
      // When the switch is announced, the new catalog is already there (no key flash).
      languages.push(`${language}:${i18n.t('xlog:page.refreshing')}`);
    });
    await i18n.changeLanguage('ko');

    expect(calls).toContain(catalogPath('ko', 'xlog'));
    expect(calls).not.toContain(catalogPath('ko', 'tx'));
    expect(languages).toEqual([`ko:${koXlog.page.refreshing}`]);
    expect(i18n.t('common:app.tagline')).not.toBe(enCommon.app.tagline);
  });

  it('T-UI-006 a catalog that fails to load leaves the English one in use (no retry, no rejection)', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout'] });
    try {
      const failing = new Set([catalogPath('ko', 'tx')]);
      const { loaders, calls } = countingLoaders(failing);
      const i18n = appI18n('ko', loaders);

      await expect(loadRouteNamespaces('transaction', i18n)).resolves.toBeUndefined();

      expect(i18n.t('tx:notFound.title')).toBe(enTx.notFound.title);
      // No retry timer: the route does not wait through back-off delays.
      expect(vi.getTimerCount()).toBe(0);
      expect(calls.filter((path) => path === catalogPath('ko', 'tx'))).toHaveLength(1);
      expect(i18n.hasLoadedNamespace('tx')).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('T-UI-006 an unknown catalog is an error for i18next without a retry', () => {
    const backend = createLazyBackend({});
    const callback = vi.fn();
    backend.read('ko', 'nope', callback);
    expect(callback).toHaveBeenCalledTimes(1);
    const [error, retry] = callback.mock.calls[0] as [unknown, unknown];
    expect(error).toBeInstanceOf(Error);
    expect(retry).toBe(false);
  });

  it('T-UI-006 a screen rendered after its route loaded shows its texts on the first render', async () => {
    const i18n = appI18n('ko');
    await loadRouteNamespaces('xlog', i18n);

    function Screen() {
      const { t } = useTranslation(['common', 'xlog']);
      return <p>{t('xlog:page.refreshing')}</p>;
    }
    render(
      <I18nextProvider i18n={i18n}>
        <Suspense fallback={<p>suspended</p>}>
          <Screen />
        </Suspense>
      </I18nextProvider>,
    );

    // Synchronous query: the first commit already has the text, the fallback never showed.
    expect(screen.getByText(koXlog.page.refreshing)).toBeInTheDocument();
    expect(screen.queryByText('suspended')).toBeNull();
  });
});
