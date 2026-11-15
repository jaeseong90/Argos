// SPDX-License-Identifier: Apache-2.0
//
// UI permission keys (docs/15 §11 "권한 모델", docs/14 §7.1 role matrix). Menus and buttons are
// hidden in advance with useCan(permission); the server enforces every operation regardless
// (x-argos-role, 403). /auth/me returns the role and the granted extra permissions (`pii.view`,
// per administrator, C-106 (6)); the role-derived keys below mirror the docs/14 §7.1 table.
import { roleAtLeast, useAuthStore, type Role } from './authStore';

/** Keys whose minimum role decides (docs/14 §7.1). */
const ROLE_PERMISSIONS = {
  // Viewer: every query screen and CSV export.
  view: 'viewer',
  'csv.export': 'viewer',
  // Operator.
  'commands.thread-dump': 'operator',
  'commands.thread-stack': 'operator',
  'commands.self-diagnosis': 'operator',
  'events.create': 'operator',
  'alerts.rules.edit': 'operator',
  'alerts.ack': 'operator',
  'instances.archive': 'operator',
  'config.view-effective': 'operator',
  'storage.view': 'operator',
  'admin.view': 'operator',
  // Administrator.
  'commands.env-dump': 'admin',
  'commands.config-reload': 'admin',
  'channels.edit-url': 'admin',
  'apps.manage': 'admin',
  'tokens.manage': 'admin',
  'config.push': 'admin',
  'kill-switch': 'admin',
  'users.manage': 'admin',
  'audit.read': 'admin',
  'privacy.delete': 'admin',
} as const satisfies Record<string, Role>;

/** Keys granted individually by the server (`permissions` of /auth/me). */
const GRANTED_PERMISSIONS = ['pii.view'] as const;

export type Permission = keyof typeof ROLE_PERMISSIONS | (typeof GRANTED_PERMISSIONS)[number];

/** Pure form of useCan for loaders and tests. */
export function can(
  permission: Permission,
  role: Role | null,
  granted: readonly string[],
): boolean {
  if ((GRANTED_PERMISSIONS as readonly string[]).includes(permission)) {
    // pii.view needs the grant and the administrator role (docs/14 §7.1: admin + pii.view).
    return granted.includes(permission) && roleAtLeast(role, 'admin');
  }
  const minimum = ROLE_PERMISSIONS[permission as keyof typeof ROLE_PERMISSIONS];
  return roleAtLeast(role, minimum);
}

/** True when the logged-in user holds the permission (docs/15 §11). */
export function useCan(permission: Permission): boolean {
  const role = useAuthStore((s) => s.role);
  const granted = useAuthStore((s) => s.permissions);
  return can(permission, role, granted);
}
