// SPDX-License-Identifier: Apache-2.0
//
// T-UI-006 (ADR-0010 C-209): the route -> lazy namespace table (routeNamespaces.ts) matches the
// code. For every screen with a component (screens.ts `load`) and the other lazy routes, the test
// walks the route's module graph (static and dynamic imports, type-only imports left out) and
// collects the lazy namespaces its modules use (`'<ns>:key'` literals, useTranslation([... '<ns>'])):
// the table must list exactly those, so a screen never renders before its catalog and loads no
// catalog it does not use. The always-loaded shell (entry, Ant Design root, session gate, shell,
// screen route, login, setup) must use bundled namespaces only. The route tree loads the table's
// namespaces together with the route component.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U13' }, { type: 'req', description: 'N-14' }
import type { RouteObject } from 'react-router';
import { describe, expect, it } from 'vitest';

import { createRouteTree } from '../routes/routeTree';
import { SCREENS } from '../routes/screens';

import { createAppI18n } from './appI18n';
import { lazyNamespaces, type LazyNamespace } from './resources';
import { ROUTE_NAMESPACES, routeNamespaces } from './routeNamespaces';

/** Source text of every module under src/ (tests and declarations left out), keyed `/src/...`. */
const SOURCES = import.meta.glob<string>(
  ['/src/**/*.{ts,tsx}', '!/src/**/*.test.{ts,tsx}', '!/src/**/*.d.ts'],
  { query: '?raw', import: 'default', eager: true },
);

/** Lazy routes outside the screen table (routeTree.tsx) and their component modules. */
const OTHER_ROUTES: Readonly<Record<string, string>> = {
  'tx-alias': '/src/routes/TransactionAlias.tsx',
  'xlog-poc': '/src/features/xlog-poc/XLogPocPage.tsx',
};

/** Modules every signed-in route renders (bundled namespaces only). */
const SHELL_MODULES = [
  '/src/main.tsx',
  '/src/app/AntdRoot.tsx',
  '/src/routes/AuthGate.tsx',
  '/src/routes/Shell.tsx',
  '/src/routes/ScreenRoute.tsx',
  '/src/routes/RoutePending.tsx',
  '/src/app/RouteStatus.tsx',
  '/src/features/auth/LoginPage.tsx',
  '/src/features/auth/SetupPage.tsx',
];

/**
 * Modules whose dynamic imports are other routes, not parts of the importing route (the screen
 * table and the route tree).
 */
const ROUTE_TABLES = new Set(['/src/routes/screens.ts', '/src/routes/routeTree.tsx']);

/** Joins a relative specifier to the directory of a `/src/...` module path. */
function joinPath(from: string, specifier: string): string {
  const parts = from.split('/').slice(0, -1);
  for (const part of specifier.split('/')) {
    if (part === '..') {
      parts.pop();
    } else if (part !== '.') {
      parts.push(part);
    }
  }
  return parts.join('/');
}

function resolveModule(from: string, specifier: string): string | null {
  const base = joinPath(from, specifier);
  for (const candidate of [
    base,
    `${base}.ts`,
    `${base}.tsx`,
    `${base}/index.ts`,
    `${base}/index.tsx`,
  ]) {
    if (candidate in SOURCES) {
      return candidate;
    }
  }
  return null;
}

/** Source without type-only imports and exports (they load nothing at run time). */
function runtimeSource(file: string): string {
  return (SOURCES[file] ?? '').replace(/\b(?:import|export)\s+type\s[\s\S]*?from\s*'[^']*'/g, '');
}

const STATIC_IMPORT = /(?:\bfrom\s*|\bimport\s+)'(\.{1,2}\/[^']+)'/g;
const ANY_IMPORT = /(?:\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)'(\.{1,2}\/[^']+)'/g;

function usedNamespaces(source: string): LazyNamespace[] {
  const lists = [...source.matchAll(/useTranslation\(\s*\[([^\]]*)\]/g)].map((m) => m[1] ?? '');
  return lazyNamespaces.filter(
    (ns) =>
      new RegExp(`['"\`]${ns}:[\\w$]`).test(source) ||
      lists.some((list) => new RegExp(`['"]${ns}['"]`).test(list)) ||
      new RegExp(`useTranslation\\(\\s*['"]${ns}['"]`).test(source),
  );
}

/** Lazy namespaces used by the module graph of the entries, with the modules using each. */
function graphNamespaces(
  entries: readonly string[],
  followDynamic: boolean,
): Map<LazyNamespace, string[]> {
  const used = new Map<LazyNamespace, string[]>();
  const seen = new Set<string>();
  const stack = [...entries];
  while (stack.length > 0) {
    const file = stack.pop();
    if (file === undefined || seen.has(file)) {
      continue;
    }
    seen.add(file);
    const source = runtimeSource(file);
    for (const ns of usedNamespaces(source)) {
      used.set(ns, [...(used.get(ns) ?? []), file]);
    }
    const pattern = followDynamic && !ROUTE_TABLES.has(file) ? ANY_IMPORT : STATIC_IMPORT;
    for (const match of source.matchAll(pattern)) {
      const target = resolveModule(file, match[1] ?? '');
      if (target !== null) {
        stack.push(target);
      }
    }
  }
  return used;
}

/** Screen id -> component module, from the `load` lines of screens.ts. */
function screenModules(): Map<string, string> {
  const file = '/src/routes/screens.ts';
  const source = SOURCES[file] ?? '';
  const modules = new Map<string, string>();
  const entries = source.split(/\n\s*\{\s*\n/);
  for (const entry of entries) {
    const id = /\bid:\s*'([^']+)'/.exec(entry)?.[1];
    const load = /\bload:\s*\(\)\s*=>\s*import\(\s*'([^']+)'\s*\)/.exec(entry)?.[1];
    if (id !== undefined && load !== undefined) {
      const target = resolveModule(file, load);
      expect(target, `screen ${id}: cannot resolve ${load}`).not.toBeNull();
      modules.set(id, target ?? '');
    }
  }
  return modules;
}

function sorted(values: Iterable<string>): string[] {
  return [...values].sort();
}

describe('T-UI-006 route namespace table matches the module graphs (ADR-0010 C-209)', () => {
  const screens = screenModules();

  it('T-UI-006 finds the screens with a component', () => {
    // A guard for the parser: these screens have a component since WO-30/WO-33/WO-34.
    expect([...screens.keys()]).toEqual(
      expect.arrayContaining(['xlog', 'transaction', 'trace', 'account']),
    );
  });

  const routes: [string, string][] = [...screens.entries(), ...Object.entries(OTHER_ROUTES)];

  it.each(routes)('T-UI-006 route %s (%s) loads exactly the namespaces it uses', (id, module) => {
    const used = graphNamespaces([module], true);
    expect(
      sorted(routeNamespaces(id)),
      `lazy namespaces used by ${id}: ${JSON.stringify(Object.fromEntries(used))}`,
    ).toEqual(sorted(used.keys()));
  });

  it('T-UI-006 the table names no unknown route', () => {
    // Screens without a component yet may be listed ahead of their `load` line (checked above once
    // it is there).
    const known = new Set([...SCREENS.map((s) => s.id), ...Object.keys(OTHER_ROUTES)]);
    expect(Object.keys(ROUTE_NAMESPACES).filter((id) => !known.has(id))).toEqual([]);
  });

  it('T-UI-006 the always-loaded shell uses bundled namespaces only', () => {
    expect(Object.fromEntries(graphNamespaces(SHELL_MODULES, false))).toEqual({});
  });
});

function findRoute(routes: readonly RouteObject[], id: string): RouteObject | undefined {
  for (const route of routes) {
    if (route.id === id) {
      return route;
    }
    const child = findRoute(route.children ?? [], id);
    if (child !== undefined) {
      return child;
    }
  }
  return undefined;
}

async function runLazy(route: RouteObject | undefined): Promise<void> {
  const lazy = route?.lazy;
  expect(typeof lazy).toBe('function');
  if (typeof lazy === 'function') {
    await lazy();
  }
}

describe('T-UI-006 the route tree loads a screen catalog with the screen component', () => {
  it.each(['xlog', 'tx-alias'])(
    'T-UI-006 route %s: its namespaces are loaded when its lazy part resolves',
    async (id) => {
      // createAppI18n registers the instance with react-i18next (getI18n), as main.tsx does.
      const i18n = createAppI18n('ko');
      for (const ns of routeNamespaces(id)) {
        expect(i18n.hasResourceBundle('ko', ns)).toBe(false);
      }
      await runLazy(findRoute(createRouteTree({ version: '0.0.0-test' }), id));
      expect(routeNamespaces(id).length).toBeGreaterThan(0);
      for (const ns of routeNamespaces(id)) {
        expect(i18n.hasResourceBundle('ko', ns)).toBe(true);
        expect(i18n.hasResourceBundle('en', ns)).toBe(true);
      }
    },
    // The lazy part imports the whole screen module graph: cold, under a parallel full run, that
    // takes several seconds in jsdom.
    30_000,
  );
});
