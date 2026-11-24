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
  AdminAppsQ,
  AdminAuditQ,
  AdminConfigQ,
  AdminInstancesQ,
  AdminPrivacyQ,
  AdminTokensQ,
  AdminUsersQ,
  AlertRuleQ,
  AlertRulesQ,
  AlertsQ,
  CommonQ,
  DashboardQ,
  ErrorsQ,
  InstanceQ,
  OverviewQ,
  SearchQ,
  ServiceDetailQ,
  StatsListQ,
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
    load: () => import('../features/overview').then((m) => m.OverviewPage),
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
    // Live: the shell's overview subscription keeps the stale badge honest; the screen adds the
    // `alert` topic (pinned panel, badge, first page of events).
    live: true,
    load: () => import('../features/alerts/history').then((m) => m.AlertHistoryPage),
  },
  {
    id: 'alert-rules',
    path: 'alerts/rules',
    scope: 'global',
    label: 'alertRules',
    schema: AlertRulesQ,
    menu: 'alerts',
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
    load: () => import('../features/alerts/rules').then((m) => m.AlertRulesPage),
  },
  {
    id: 'alert-rule-new',
    path: 'alerts/rules/new',
    scope: 'global',
    label: 'alertRule',
    schema: AlertRuleQ,
    menu: null,
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
    load: () => import('../features/alerts/rules').then((m) => m.AlertRuleEditorPage),
  },
  {
    id: 'alert-rule',
    path: 'alerts/rules/:ruleId',
    scope: 'global',
    label: 'alertRule',
    schema: AlertRuleQ,
    menu: null,
    permission: 'alerts.rules.edit',
    owner: 'WO-38',
    load: () => import('../features/alerts/rules').then((m) => m.AlertRuleEditorPage),
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
    load: () => import('../features/alerts/channels').then((m) => m.AlertChannelsPage),
  },
  {
    id: 'admin-apps',
    path: 'admin/apps',
    scope: 'global',
    label: 'adminApps',
    schema: AdminAppsQ,
    menu: 'admin',
    permission: 'admin.view',
    owner: 'WO-39',
    load: () => import('../features/admin/apps/AdminAppsPage').then((m) => m.AdminAppsPage),
  },
  {
    id: 'admin-pending',
    path: 'admin/pending',
    scope: 'global',
    label: 'adminPending',
    schema: CommonQ,
    menu: 'admin',
    // Administrators only, as the API (docs/14 §4.14, docs/16 §7.10; WO-39 PM question 1).
    permission: 'apps.manage',
    owner: 'WO-39',
    load: () =>
      import('../features/admin/pending/AdminPendingPage').then((m) => m.AdminPendingPage),
  },
  {
    id: 'admin-instances',
    path: 'admin/instances',
    scope: 'global',
    label: 'adminInstances',
    schema: AdminInstancesQ,
    menu: 'admin',
    permission: 'admin.view',
    owner: 'WO-39',
    load: () =>
      import('../features/admin/instances/AdminInstancesPage').then((m) => m.AdminInstancesPage),
  },
  {
    id: 'admin-tokens',
    path: 'admin/tokens',
    scope: 'global',
    label: 'adminTokens',
    schema: AdminTokensQ,
    menu: 'admin',
    permission: 'tokens.manage',
    owner: 'WO-39',
    load: () => import('../features/admin/tokens/AdminTokensPage').then((m) => m.AdminTokensPage),
  },
  {
    id: 'admin-config',
    path: 'admin/config',
    scope: 'global',
    label: 'adminConfig',
    schema: AdminConfigQ,
    menu: 'admin',
    permission: 'config.push',
    owner: 'WO-39',
    load: () => import('../features/admin/config').then((m) => m.AdminConfigPage),
  },
  {
    id: 'admin-users',
    path: 'admin/users',
    scope: 'global',
    label: 'adminUsers',
    schema: AdminUsersQ,
    menu: 'admin',
    permission: 'users.manage',
    owner: 'WO-39',
    load: () => import('../features/admin/users/AdminUsersPage').then((m) => m.AdminUsersPage),
  },
  {
    id: 'admin-audit',
    path: 'admin/audit',
    scope: 'global',
    label: 'adminAudit',
    schema: AdminAuditQ,
    menu: 'admin',
    permission: 'audit.read',
    owner: 'WO-39',
    load: () => import('../features/admin/audit').then((m) => m.AdminAuditPage),
  },
  {
    id: 'admin-privacy',
    path: 'admin/privacy',
    scope: 'global',
    label: 'adminPrivacy',
    schema: AdminPrivacyQ,
    menu: 'admin',
    permission: 'privacy.delete',
    owner: 'WO-39',
    load: () => import('../features/admin/privacy').then((m) => m.AdminPrivacyPage),
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
    load: () => import('../features/admin/about/LicensesPage').then((m) => m.LicensesPage),
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
    load: () => import('../features/admin/about/AboutPage').then((m) => m.AboutPage),
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
    load: () => import('../features/search').then((m) => m.SearchPage),
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
    load: () => import('../features/trace/TracePage').then((m) => m.TracePage),
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
    load: () => import('../features/dashboard').then((m) => m.DashboardPage),
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
    load: () => import('../features/xlog').then((m) => m.XLogPage),
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
    load: () => import('../features/transaction/TransactionPage').then((m) => m.TransactionPage),
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
    load: () => import('../features/active').then((m) => m.ActivePage),
  },
  {
    id: 'services',
    path: 'stats/services',
    scope: 'app',
    label: 'services',
    schema: StatsListQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
    load: () => import('../features/stats/services/ServicesPage').then((m) => m.ServicesPage),
  },
  {
    id: 'service',
    path: 'stats/services/:serviceId',
    scope: 'app',
    label: 'serviceDetail',
    schema: ServiceDetailQ,
    menu: null,
    permission: 'view',
    owner: 'WO-36',
    load: () =>
      import('../features/stats/services/ServiceDetailPage').then((m) => m.ServiceDetailPage),
  },
  {
    id: 'sql',
    path: 'stats/sql',
    scope: 'app',
    label: 'sql',
    schema: StatsListQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
    load: () => import('../features/stats/sql/SqlPage').then((m) => m.SqlPage),
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
    load: () => import('../features/stats/sql/SqlDetailPage').then((m) => m.SqlDetailPage),
  },
  {
    id: 'external',
    path: 'stats/external',
    scope: 'app',
    label: 'external',
    schema: StatsListQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
    load: () => import('../features/stats/external/ExternalPage').then((m) => m.ExternalPage),
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
    load: () =>
      import('../features/stats/external/ExternalDetailPage').then((m) => m.ExternalDetailPage),
  },
  {
    id: 'errors',
    path: 'stats/errors',
    scope: 'app',
    label: 'errors',
    schema: ErrorsQ,
    menu: 'stats',
    permission: 'view',
    owner: 'WO-36',
    load: () => import('../features/stats/errors/ErrorsPage').then((m) => m.ErrorsPage),
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
    load: () => import('../features/stats/errors/ErrorDetailPage').then((m) => m.ErrorDetailPage),
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
    load: () => import('../features/instances/InstancesPage').then((m) => m.InstancesPage),
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
    load: () => import('../features/instances/InstancePage').then((m) => m.InstancePage),
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
