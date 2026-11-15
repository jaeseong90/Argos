// SPDX-License-Identifier: Apache-2.0
//
// Seed (c): the three accounts of the auth fixture, created through the real API (WO-24):
//   admin     the unattended administrator (ARGOS_ADMIN_USERNAME + ARGOS_ADMIN_PASSWORD_FILE). The
//             server sets must_change_password, so the setup first changes the password through
//             PUT /auth/password, as the first login in the UI would.
//   operator  POST /admin/users {roles: [operator], password, must_change_password: false}
//   viewer    POST /admin/users {roles: [viewer], ...}
// Passwords are generated per run and written to the run's secrets directory (credentialFile);
// nothing logs them.
import { ApiSession } from '../api.ts';
import type { Role } from '../manifest.ts';
import { credentialFileName, secretsDir } from '../paths.ts';
import { newSecret, readSecret, writeSecret } from '../secrets.ts';
import { ADMIN_USERNAME, type Log } from '../stack.ts';

/** User names of the seeded accounts. */
export const USERNAMES: Readonly<Record<Role, string>> = {
  admin: ADMIN_USERNAME,
  operator: 'e2e-operator',
  viewer: 'e2e-viewer',
};

const passwordFileName = credentialFileName;

/** The password of a seeded role (read from the run's secrets directory). */
export function rolePassword(runId: string, role: Role): string {
  return readSecret(secretsDir(runId), passwordFileName(role));
}

/** Creates the accounts; resolves with their user names. */
export async function seedUsers(
  baseUrl: string,
  runId: string,
  initialAdminPassword: string,
  log: Log,
): Promise<Readonly<Record<Role, string>>> {
  const dir = secretsDir(runId);
  const admin = new ApiSession(baseUrl);
  const login = await admin.login(ADMIN_USERNAME, initialAdminPassword);
  const adminPassword = newSecret(24);
  if (!login.must_change_password) {
    throw new Error(
      'The unattended administrator does not have to change its password: the server did not apply ARGOS_ADMIN_PASSWORD_FILE as WO-24 describes',
    );
  }
  // 204 with three new cookies (WO-24): the session continues with them.
  await admin.request('PUT', '/auth/password', {
    body: { current: initialAdminPassword, new: adminPassword },
    expect: [204],
  });
  writeSecret(dir, passwordFileName('admin'), adminPassword);
  for (const role of ['operator', 'viewer'] as const) {
    const password = newSecret(24);
    await admin.request('POST', '/admin/users', {
      body: {
        username: USERNAMES[role],
        roles: [role],
        password,
        must_change_password: false,
      },
      expect: [201, 200],
    });
    writeSecret(dir, passwordFileName(role), password);
  }
  // Every account logs in with its own password (and nothing else needs to be changed first).
  for (const role of ['admin', 'operator', 'viewer'] as const) {
    const session = new ApiSession(baseUrl);
    const result = await session.login(USERNAMES[role], rolePassword(runId, role));
    if (result.must_change_password || !result.roles.map((r) => r.toLowerCase()).includes(role)) {
      throw new Error(
        `${USERNAMES[role]}: roles ${JSON.stringify(result.roles)}, must_change_password ${String(result.must_change_password)}`,
      );
    }
    await session.logout();
  }
  await admin.logout();
  log(`users seeded: ${Object.values(USERNAMES).join(', ')}`);
  return USERNAMES;
}
