// SPDX-License-Identifier: Apache-2.0
//
// Which screen catalogs a route renders (ADR-0010 C-209). The route tree loads them together with
// the route's component (routeTree.tsx), so no screen renders before its texts are there: no
// suspended render, no key or placeholder flash. routeNamespaces.test.ts walks each route's module
// graph and fails when a module uses a lazy namespace this table does not list for the route.
import type { i18n } from 'i18next';
import { getI18n } from 'react-i18next';

import type { LazyNamespace } from './resources';

/**
 * Route id (screens.ts id, or the id of a route outside the screen table) to the lazy namespaces
 * its module graph uses. Routes not listed use bundled namespaces only.
 */
export const ROUTE_NAMESPACES: Readonly<Record<string, readonly LazyNamespace[]>> = {
  overview: ['overview'],
  dashboard: ['dashboard'],
  xlog: ['xlog'],
  transaction: ['tx'],
  // The trace model labels spans with the transaction detail's step names.
  trace: ['trace', 'tx'],
  // Statistics screens (WO-36).
  services: ['stats'],
  service: ['stats'],
  sql: ['stats'],
  'sql-detail': ['stats'],
  external: ['stats'],
  'external-detail': ['stats'],
  errors: ['stats'],
  // The representative stack reuses the transaction detail's FrameList (its texts are in tx).
  'error-detail': ['stats', 'tx'],
  // Active transactions (WO-35); the stack and dump actions use the command texts (WO-37).
  active: ['active', 'commands'],
  // Instance list and detail (WO-37).
  instances: ['instance'],
  // The commands, environment dump and thread dump parts use the command texts (lane C).
  instance: ['instance', 'commands'],
  // Alert screens (WO-38).
  alerts: ['alerts'],
  'alert-rules': ['alerts'],
  'alert-rule-new': ['alerts'],
  'alert-rule': ['alerts'],
  'alert-channels': ['alerts'],
  // /tx/:txId deep-link alias (routeTree.tsx), before it redirects to the transaction screen.
  'tx-alias': ['tx'],
  // WO-04 PoC page, outside production builds (ADR-0010 C-203).
  'xlog-poc': ['xlog'],
};

/** The lazy namespaces of a route (empty when it uses bundled ones only). */
export function routeNamespaces(routeId: string): readonly LazyNamespace[] {
  return Object.prototype.hasOwnProperty.call(ROUTE_NAMESPACES, routeId)
    ? (ROUTE_NAMESPACES[routeId] ?? [])
    : [];
}

/**
 * Loads the lazy namespaces of a route in the current language and the English fallback. Never
 * rejects: a catalog that cannot be loaded leaves the English one (or, failing that, the key) in
 * use rather than failing the route.
 */
export async function loadRouteNamespaces(routeId: string, instance?: i18n): Promise<void> {
  const namespaces = routeNamespaces(routeId);
  if (namespaces.length === 0) {
    return;
  }
  // Default: the instance registered with react-i18next (main.tsx creates it before the router).
  await (instance ?? getI18n()).loadNamespaces([...namespaces]);
}
