// SPDX-License-Identifier: Apache-2.0
//
// User administration (docs/14 §4.14 `/admin/users*`, docs/15 §9.10, ADR-0010 C-109·C-136 (3),
// WO-39): the list, create (a generated initial password comes back once), role, enabled state and
// `pii.view` changes, ending every session, password reset, lockout release and delete. The initial
// password is kept in component state only, never in a cache, browser storage, logs or the URL.
import { useQuery } from '@tanstack/react-query';

import { api, expectNoContent, unwrap, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type AdminUser = components['schemas']['AdminUserView'];
export type CreatedUser = components['schemas']['CreateUserResponse'];
export type UserRole = AdminUser['roles'][number];
export type UserPatch = components['schemas']['PatchUserRequest'];

export const USER_ROLES: readonly UserRole[] = ['viewer', 'operator', 'admin'];
/** Longest user name the server takes. */
export const USERNAME_MAX = 64;

export const adminUserKeys = {
  list: () => ['admin', 'users'] as const,
};

export async function listUsers() {
  return unwrap<EnvelopeOf<'/api/v1/admin/users', 'get'>>(api().client.GET('/api/v1/admin/users'));
}

export function useUsers() {
  return useQuery({ queryKey: adminUserKeys.list(), queryFn: listUsers, staleTime: 5_000 });
}

/** Creates a user with one role; without `password` the server generates one (answered once). */
export async function createUser(input: {
  readonly username: string;
  readonly role: UserRole;
  readonly mustChangePassword: boolean;
}): Promise<CreatedUser> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/users', 'post'>>(
      api().client.POST('/api/v1/admin/users', {
        body: {
          username: input.username,
          roles: [input.role],
          must_change_password: input.mustChangePassword,
        },
      }),
    )
  ).data;
}

/** Changes role, enabled state or granted permissions (a role change ends the user's sessions). */
export async function patchUser(id: string, body: UserPatch): Promise<AdminUser> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/users/{id}', 'patch'>>(
      api().client.PATCH('/api/v1/admin/users/{id}', { params: { path: { id } }, body }),
    )
  ).data;
}

export async function revokeUserSessions(id: string): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/admin/users/{id}/sessions/revoke', { params: { path: { id } } }),
  );
}

/** Sets a new password the user must change at the next login (sessions end). */
export async function resetUserPassword(id: string, password: string): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/admin/users/{id}/password/reset', {
      params: { path: { id } },
      body: { password },
    }),
  );
}

export async function unlockUser(id: string): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/admin/users/{id}/unlock', { params: { path: { id } } }),
  );
}

/** Deletes a user (409 invalid_state for one's own account or the last administrator). */
export async function deleteUser(id: string): Promise<void> {
  await expectNoContent(
    api().client.DELETE('/api/v1/admin/users/{id}', { params: { path: { id } } }),
  );
}

/** The single role of a user (the server keeps exactly one, docs/14 §4.14). */
export function roleOf(user: AdminUser): UserRole {
  return user.roles[0] ?? 'viewer';
}

/** True while a lockout lasts. */
export function isLocked(user: AdminUser, now: number): boolean {
  if (user.locked_until === null) {
    return false;
  }
  const until = Date.parse(user.locked_until);
  return Number.isFinite(until) && until > now;
}
