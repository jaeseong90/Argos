// SPDX-License-Identifier: Apache-2.0
//
// Screen registry (docs/15 §3.1, §3.3): every shell route with its path, URL schema, label, side
// menu group, required permission and the work order that builds it. The route tree
// (routeTree.tsx), the side menu (SideMenu.tsx) and the g-shortcuts read this one table, so a
// screen work order (WO-31..39) adds `load` to its entry (its features/<screen> component) and
// nothing else in the shell.
import type { ComponentType } from 'react';

import type { Permission } from '../state/permissions';
import {
  AlertsQ,
  CommonQ,
  DashboardQ,
  InstanceQ,
  OverviewQ,
  SearchQ,
  TraceQ,
  TxDetailQ,
  XLogQ,
  type UrlSchema,
} from '../state/urlState';

/** Keys of shell:screen.* (the screen's title). */
export type ScreenLabel =
  | 'overview'
  | 'dashboard'
  | 'xlog'
  | 'transaction'
  | 'trace'
  | 'active'
  | 'services'
  | 'serviceDetail'
  | 'sql'
  | 'sqlDetail'
  | 'external'
  | 'externalDetail'
  | 'errors'
  | 'errorDetail'
  | 'instances'
  | 'instanceDetail'
  | 'map'
  | 'alerts'
  | 'alertRules'
  | 'alertRule'
  | 'alertChannels'
  | 'adminApps'
  | 'adminPending'
  | 'adminInstances'
  | 'adminTokens'
  | 'adminConfig'
  | 'adminUsers'
  | 'adminAudit'
  | 'adminPrivacy'
  | 'licenses'
  | 'about'
  | 'search'
  | 'account';

/** Side menu groups of docs/15 §3.3 (null: not in the menu). */
export type MenuGroup = 'app' | 'stats' | 'global' | 'alerts' | 'admin' | 'about' | null;

export interface ScreenDef {
  /** Stable id (route id, menu key). */
  readonly id: string;
  /** Path relative to its layout (`/apps/:appId/` for app screens, `/` for global ones). */
  readonly path: string;
  readonly scope: 'app' | 'global';
  readonly label: ScreenLabel;
  readonly schema: UrlSchema;
  readonly menu: MenuGroup;
  /** Hidden from the menu and refused by the route without it (the server enforces anyway). */
  readonly permission: Permission;
  /** Work order that builds the screen; until then the route shows the placeholder. */
  readonly owner: string;
  /** The screen supports live mode (docs/15 §3.2 live toggle). */
  readonly live?: boolean;
  /**
   * Loads the screen's component (a separate chunk, docs/15 §14). The screen's work order adds
   * this line; without it the route shows the placeholder (route-pending).
   */
  readonly load?: () => Promise<ComponentType>;
}

export const SCREENS: readonly ScreenDef[] = [
  // Global screens (GlobalLayout).
  {
    id: 'overview',
    path: 'overview',
    scope: 'global',
    label: 'overview',
    schema: OverviewQ,
    menu: 'global',
    permission: 'view',
    owner: 'WO-31',
    live: true,
  },
  {
    id: 'alerts',
    path: 'alerts',
    scope: 'global',
    label: 'alerts',
    schema: AlertsQ,
    menu: 'alerts',
    permission: 'view',
    owner: 'WO-38',
  },
  {
    id: 'alert-rules',
    path: 'alerts/rules',
    scope: 'global',
    label: 'alertRules',
    schema: CommonQ,
    menu: 'alerts',
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
  },
  {
    id: 'alert-rule-new',
    path: 'alerts/rules/new',
    scope: 'global',
    label: 'alertRule',
    schema: CommonQ,
    menu: null,
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
  },
  {
    id: 'alert-rule',
    path: 'alerts/rules/:ruleId',
    scope: 'global',
    label: 'alertRule',
    schema: CommonQ,
    menu: null,
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
  },
  {
    id: 'alert-channels',
    path: 'alerts/channels',
    scope: 'global',
    label: 'alertChannels',
    schema: CommonQ,
    menu: 'alerts',
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
  },
  {
    id: 'admin-apps',
    path: 'admin/apps',
    scope: 'global',
    label: 'adminApps',
    schema: CommonQ,
    menu: 'admin',
    permission: 'admin.view',
    owner: 'WO-39',
  },
  {
    id: 'admin-pending',
    path: 'admin/pending',
    scope: 'global',
    label: 'adminPending',
    schema: CommonQ,
    menu: 'admin',
    permission: 'admin.view',
    owner: 'WO-39',
  },
  {
    id: 'admin-instances',
    path: 'admin/instances',
    scope: 'global',
    label: 'adminInstances',
    schema: CommonQ,
    menu: 'admin',
    permission: 'admin.view',
    owner: 'WO-39',
  },
  {
    id: 'admin-tokens',
    path: 'admin/tokens',
    scope: 'global',
    label: 'adminTokens',
    schema: CommonQ,
    menu: 'admin',
    permission: 'tokens.manage',
    owner: 'WO-39',
  },
  {
    id: 'admin-config',
    path: 'admin/config',
    scope: 'global',
    label: 'adminConfig',
    schema: CommonQ,
    menu: 'admin',
    permission: 'config.push',
    owner: 'WO-39',
  },
  {
    id: 'admin-users',
    path: 'admin/users',
    scope: 'global',
    label: 'adminUsers',
    schema: CommonQ,
    menu: 'admin',
    permission: 'users.manage',
    owner: 'WO-39',
  },
  {
    id: 'admin-audit',
    path: 'admin/audit',
    scope: 'global',
    label: 'adminAudit',
    schema: CommonQ,
    menu: 'admin',
    permission: 'audit.read',
    owner: 'WO-39',
  },
  {
    id: 'admin-privacy',
    path: 'admin/privacy',
    scope: 'global',
    label: 'adminPrivacy',
    schema: CommonQ,
    menu: 'admin',
    permission: 'privacy.delete',
    owner: 'WO-39',
  },
  {
    id: 'licenses',
    path: 'admin/licenses',
    scope: 'global',
    label: 'licenses',
    schema: CommonQ,
    menu: 'about',
    permission: 'view',
    owner: 'WO-39',
  },
  {
    id: 'about',
    path: 'admin/about',
    scope: 'global',
    label: 'about',
    schema: CommonQ,
    menu: 'about',
    permission: 'view',
    owner: 'WO-39',
  },
  {
    id: 'search',
    path: 'search',
    scope: 'global',
    label: 'search',
    schema: SearchQ,
    menu: null,
    permission: 'view',
    owner: 'WO-39',
  },
  {
    id: 'trace',
    path: 'traces/:traceId',
    scope: 'global',
    label: 'trace',
    schema: TraceQ,
    menu: null,
    permission: 'view',
    owner: 'WO-34',
  },
  {
    id: 'account',
    path: 'account',
    scope: 'global',
    label: 'account',
    schema: CommonQ,
    menu: null,
    permission: 'view',
    owner: 'WO-30',
    load: () => import('../features/auth/AccountPage').then((m) => m.AccountPage),
  },
  // Application screens (AppLayout, /apps/:appId/...).
  {
    id: 'dashboard',
    path: 'dashboard',
    scope: 'app',
    label: 'dashboard',
    schema: DashboardQ,
    menu: 'app',
    permission: 'view',
    owner: 'WO-32',
    live: true,
  },
  {
    id: 'xlog',
    path: 'xlog',
    scope: 'app',
    label: 'xlog',
    schema: XLogQ,
    menu: 'app',
    permission: 'view',
    owner: 'WO-33',
    live: true,
  },
  {
    id: 'transaction',
    path: 'transactions/:txId',
    scope: 'app',
    label: 'transaction',
    schema: TxDetailQ,
    menu: null,
    permission: 'view',
    owner: 'WO-34',
  },
  {
    id: 'active',
    path: 'active',
    scope: 'app',
    label: 'active',
    schema: CommonQ,
    menu: 'app',
    permission: 'view',
    owner: 'WO-35',
    live: true,
  },
  {
    id: 'services',
    path: 'stats/services',
    scope: 'app',
    label: 'services',
    schema: CommonQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'service',
    path: 'stats/services/:serviceId',
    scope: 'app',
    label: 'serviceDetail',
    schema: CommonQ,
    menu: null,
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'sql',
    path: 'stats/sql',
    scope: 'app',
    label: 'sql',
    schema: CommonQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'sql-detail',
    path: 'stats/sql/:sqlId',
    scope: 'app',
    label: 'sqlDetail',
    schema: CommonQ,
    menu: null,
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'external',
    path: 'stats/external',
    scope: 'app',
    label: 'external',
    schema: CommonQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'external-detail',
    path: 'stats/external/:hostId',
    scope: 'app',
    label: 'externalDetail',
    schema: CommonQ,
    menu: null,
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'errors',
    path: 'stats/errors',
    scope: 'app',
    label: 'errors',
    schema: CommonQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'error-detail',
    path: 'stats/errors/:groupId',
    scope: 'app',
    label: 'errorDetail',
    schema: CommonQ,
    menu: null,
    permission: 'view',
    owner: 'WO-36',
  },
  {
    id: 'instances',
    path: 'instances',
    scope: 'app',
    label: 'instances',
    schema: CommonQ,
    menu: 'app',
    permission: 'view',
    owner: 'WO-37',
  },
  {
    id: 'instance',
    path: 'instances/:instanceId',
    scope: 'app',
    label: 'instanceDetail',
    schema: InstanceQ,
    menu: null,
    permission: 'view',
    owner: 'WO-37',
  },
  {
    id: 'map',
    path: 'map',
    scope: 'app',
    label: 'map',
    schema: CommonQ,
    menu: 'app',
    permission: 'view',
    owner: 'F-U11 (S)',
  },
];

/** The screen of an id (throws on a typo: the registry is static). */
export function screenById(id: string): ScreenDef {
  const screen = SCREENS.find((s) => s.id === id);
  if (screen === undefined) {
    throw new Error(`Unknown screen ${id}`);
  }
  return screen;
}

/** Screens with the kiosk layout (docs/15 §3.4: "전체 현황·대시보드·액티브에서 지원"). */
const KIOSK_SCREEN_IDS: ReadonlySet<string> = new Set(['overview', 'dashboard', 'active']);

/**
 * True when `?kiosk=1` applies to the screen: the kiosk layout and the kiosk "session expired"
 * notice exist on the overview, the dashboard and active transactions only. Elsewhere the key is
 * ignored (it stays in the URL and is carried back to those screens) and the normal shell shows.
 */
export function kioskApplies(kiosk: boolean, screen: ScreenDef | null): boolean {
  return kiosk && screen !== null && KIOSK_SCREEN_IDS.has(screen.id);
}

/** The URL path of an app screen (`/apps/3/xlog`) or a global one (`/overview`). */
export function screenPath(screen: ScreenDef, appId?: number | null): string {
  if (screen.scope === 'app') {
    if (appId === undefined || appId === null) {
      throw new Error(`Screen ${screen.id} needs an application`);
    }
    return `/apps/${String(appId)}/${screen.path}`;
  }
  return `/${screen.path}`;
}
