// SPDX-License-Identifier: Apache-2.0
//
// Menu exposure by role (WO-39, docs/16 §7.10 permission matrix, docs/15 §3.1 and §11, SEC-49): for
// each seeded role the side menu holds exactly the screens of the expectation table below, written
// here from docs/16 §7.10 and docs/15 §3.3 and not read from src/routes/screens.ts (an independent
// oracle: a wrong permission in the registry fails here). A hidden screen opened by its URL shows
// route-forbidden, and the server refuses a representative write of a higher role with 403 (the
// server enforces, the UI only hides). The write buttons of the administration screens follow the
// role too.
import { readFileSync } from 'node:fs';

import type { Page } from '@playwright/test';

import { expect, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';
import { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { credentialFile } from '../stack/paths';

/** Menu items every signed-in user sees (an application selected). */
const VIEWER_MENU = [
  'overview',
  'alerts',
  'dashboard',
  'xlog',
  'active',
  'instances',
  'map',
  'services',
  'sql',
  'external',
  'errors',
  'licenses',
  'about',
] as const;

/** docs/16 §7.10: alert rules and channel choice, instance archive, read-only administration. */
const OPERATOR_MENU = [
  ...VIEWER_MENU,
  'alert-rules',
  'alert-channels',
  'admin-apps',
  'admin-instances',
] as const;

/** docs/16 §7.10: approval, tokens, configuration and kill switch, users, audit, privacy. */
const ADMIN_MENU = [
  ...OPERATOR_MENU,
  'admin-pending',
  'admin-tokens',
  'admin-config',
  'admin-users',
  'admin-audit',
  'admin-privacy',
] as const;

/** The URL of each screen a role may lack (global screens). */
const SCREEN_PATHS: Readonly<Record<string, string>> = {
  'alert-rules': '/alerts/rules',
  'alert-channels': '/alerts/channels',
  'admin-apps': '/admin/apps',
  'admin-pending': '/admin/pending',
  'admin-instances': '/admin/instances',
  'admin-tokens': '/admin/tokens',
  'admin-config': '/admin/config',
  'admin-users': '/admin/users',
  'admin-audit': '/admin/audit',
  'admin-privacy': '/admin/privacy',
};

const EXPECTED: Readonly<Record<Role, readonly string[]>> = {
  viewer: VIEWER_MENU,
  operator: OPERATOR_MENU,
  admin: ADMIN_MENU,
};

/** A representative write per minimum role (docs/16 §7.10 rows) and its request. */
interface Write {
  readonly minimum: Role;
  readonly what: string;
  readonly method: string;
  readonly path: (seed: SeedManifest) => string;
  readonly body?: unknown;
}

const WRITES: readonly Write[] = [
  {
    minimum: 'operator',
    what: 'instance archive',
    method: 'POST',
    path: (seed) =>
      `/instances/${String(seed.apps.find((a) => a.app_id === seed.primary_app_id)?.instances[0]?.instance_id ?? 1)}/archive`,
  },
  {
    minimum: 'admin',
    what: 'application create',
    method: 'POST',
    path: () => '/apps',
    body: { name: 'wo39-roles-forbidden', env: 'e2e' },
  },
  {
    minimum: 'admin',
    what: 'agent token issue',
    method: 'POST',
    path: (seed) => `/admin/apps/${String(seed.traffic.app_id)}/tokens`,
    body: { expires_in_days: 1 },
  },
  {
    minimum: 'admin',
    what: 'kill switch',
    method: 'POST',
    path: (seed) => `/admin/apps/${String(seed.traffic.app_id)}/kill-switch`,
    body: { enabled: false, instance_ids: [], reason: 'WO-39 roles: must be refused' },
  },
  {
    minimum: 'admin',
    what: 'user create',
    method: 'POST',
    path: () => '/admin/users',
    body: { username: 'wo39-roles-forbidden', roles: ['viewer'], must_change_password: true },
  },
];

const RANK: Readonly<Record<Role, number>> = { viewer: 0, operator: 1, admin: 2 };

async function menuIds(page: Page): Promise<string[]> {
  const ids = await page
    .getByTestId('side-menu')
    .locator('a[data-testid^="side-menu-"]')
    .evaluateAll((links) =>
      links.map((l) => (l.getAttribute('data-testid') ?? '').slice('side-menu-'.length)),
    );
  return ids.sort();
}

const CASES: readonly [number, Role][] = [
  [1, 'viewer'],
  [2, 'operator'],
  [3, 'admin'],
];

for (const [n, role] of CASES) {
  test.describe(`role ${role}`, () => {
    test.use({ role });

    test(
      `T-E2E-roles-${String(n)} ${role}: the side menu = the docs/16 §7.10 table, hidden screens refuse their URL, higher writes answer 403, write buttons follow the role`,
      {
        tag: '@security',
        annotation: [
          { type: 'req', description: 'SEC-49' },
          { type: 'req', description: 'F-S05' },
          { type: 'req', description: 'F-U10' },
        ],
      },
      async ({ page, seed }) => {
        test.setTimeout(120_000);
        await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard`);
        await expectShell(page);
        expect(await menuIds(page), `menu of ${role}`).toEqual([...EXPECTED[role]].sort());
        // Every screen of a higher role refuses its URL (the menu hid it).
        for (const [id, path] of Object.entries(SCREEN_PATHS)) {
          if (EXPECTED[role].includes(id)) {
            continue;
          }
          await page.goto(path);
          await expect(page.getByTestId('route-forbidden'), path).toBeVisible();
        }
        // The server refuses the writes of a higher role (403), whatever the UI shows.
        const api = new ApiSession(seed.stack_url);
        await api.login(seed.users[role], readFileSync(credentialFile(seed.run_id, role), 'utf8'));
        for (const write of WRITES) {
          if (RANK[role] >= RANK[write.minimum]) {
            continue;
          }
          const response = await api.request(write.method, write.path(seed), {
            body: write.body,
            expect: [403],
          });
          expect(response.status, write.what).toBe(403);
        }
        await api.logout();
        // Write buttons of the administration screens.
        if (role === 'viewer') {
          return;
        }
        await page.goto('/admin/apps');
        await expect(page.getByTestId('admin-apps')).toHaveAttribute(
          'data-state',
          /^(ready|empty)$/,
          {
            timeout: 30_000,
          },
        );
        await expect(page.getByTestId('admin-app-new')).toHaveCount(role === 'admin' ? 1 : 0);
        await expect(page.getByTestId('admin-apps-read-only')).toHaveCount(
          role === 'admin' ? 0 : 1,
        );
        await expect(page.getByTestId('admin-app-edit').first()).toHaveCount(
          role === 'admin' ? 1 : 0,
        );
        await page.goto(`/admin/instances?app=${String(seed.primary_app_id)}`);
        await expect(page.getByTestId('admin-instances')).toHaveAttribute(
          'data-state',
          /^(ready|empty)$/,
          {
            timeout: 30_000,
          },
        );
        // Seed instances have no session: archivable by operators and administrators.
        await expect(page.getByTestId('admin-instance-archive').first()).toBeVisible();
        if (role === 'admin') {
          await page.goto(`/admin/tokens?app=${String(seed.traffic.app_id)}`);
          await expect(page.getByTestId('admin-token-issue')).toBeVisible();
          await page.goto(`/admin/config?app=${String(seed.traffic.app_id)}`);
          await expect(page.getByTestId('admin-config')).toHaveAttribute(
            'data-state',
            /^(ready|empty)$/,
            {
              timeout: 30_000,
            },
          );
        }
      },
    );
  });
}
