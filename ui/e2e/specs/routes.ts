// SPDX-License-Identifier: Apache-2.0
//
// Every route of the WO-30 shell (docs/15 §3.1, ADR-0010 C-166 app-scoped paths) with the manifest
// values that fill its parameters: the screens of WO-30 itself (/login, /setup, /account) and the
// lazy placeholders of WO-31..39 (route-pending). The layout spec opens each at 1366x768;
// routes.test.ts keeps this list in step with src/routes/screens.ts.
import type { SeedManifest } from '../stack/manifest';

export interface ShellRoute {
  /** docs/15 §3.1 path pattern (as in src/routes/screens.ts, with the layout prefix). */
  readonly pattern: string;
  /** Who shows it: the WO-30 screen, or the placeholder until its work order. */
  readonly kind: 'screen' | 'placeholder';
  /** Role that may open it (the side menu hides the others). */
  readonly role: 'viewer' | 'operator' | 'admin' | 'anonymous';
}

export const SHELL_ROUTES: readonly ShellRoute[] = [
  { pattern: '/login', kind: 'screen', role: 'anonymous' },
  // With an administrator present the wizard is closed and says so (docs/15 §9.11, SEC-47).
  { pattern: '/setup', kind: 'screen', role: 'anonymous' },
  { pattern: '/account', kind: 'screen', role: 'viewer' },
  { pattern: '/overview', kind: 'placeholder', role: 'viewer' },
  { pattern: '/alerts', kind: 'placeholder', role: 'viewer' },
  { pattern: '/alerts/rules', kind: 'placeholder', role: 'operator' },
  { pattern: '/alerts/rules/new', kind: 'placeholder', role: 'operator' },
  { pattern: '/alerts/rules/:ruleId', kind: 'placeholder', role: 'operator' },
  { pattern: '/alerts/channels', kind: 'placeholder', role: 'operator' },
  { pattern: '/admin/apps', kind: 'placeholder', role: 'operator' },
  { pattern: '/admin/pending', kind: 'placeholder', role: 'operator' },
  { pattern: '/admin/instances', kind: 'placeholder', role: 'operator' },
  { pattern: '/admin/tokens', kind: 'placeholder', role: 'admin' },
  { pattern: '/admin/config', kind: 'placeholder', role: 'admin' },
  { pattern: '/admin/users', kind: 'placeholder', role: 'admin' },
  { pattern: '/admin/audit', kind: 'placeholder', role: 'admin' },
  { pattern: '/admin/privacy', kind: 'placeholder', role: 'admin' },
  { pattern: '/admin/licenses', kind: 'placeholder', role: 'viewer' },
  { pattern: '/admin/about', kind: 'placeholder', role: 'viewer' },
  { pattern: '/search', kind: 'placeholder', role: 'viewer' },
  { pattern: '/traces/:traceId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/dashboard', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/xlog', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/transactions/:txId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/active', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/services', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/services/:serviceId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/sql', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/sql/:sqlId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/external', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/external/:hostId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/errors', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/stats/errors/:groupId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/instances', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/instances/:instanceId', kind: 'placeholder', role: 'viewer' },
  { pattern: '/apps/:appId/map', kind: 'placeholder', role: 'viewer' },
];

/** Fills the route parameters with ids of the seed manifest (64-bit ids stay strings). */
export function routePath(pattern: string, seed: SeedManifest): string {
  const primary = seed.apps.find((a) => a.app_id === seed.primary_app_id);
  const tx = seed.tx.captured_sql_stack;
  const values: Record<string, string> = {
    appId: String(seed.primary_app_id),
    txId: tx.tx_id,
    traceId: seed.tx.trace_root_two_apps.trace_id,
    instanceId: String(primary?.instances[0]?.instance_id ?? 1),
    // Placeholder screens do not look the ids up; any well-formed id opens them.
    serviceId: '1',
    sqlId: '1',
    hostId: '1',
    groupId: '1',
    ruleId: '1',
  };
  return pattern.replace(/:([A-Za-z]+)/g, (_, name: string) => values[name] ?? '1');
}
