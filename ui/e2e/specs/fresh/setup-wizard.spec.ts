// SPDX-License-Identifier: Apache-2.0
//
// The real setup wizard on a new installation (WO-46, ADR-0010 C-192 (1); docs/16 §7.8, docs/15
// §9.11, SEC-47, SEC-48, SEC-31): project fresh-install against the fresh stack of fresh-setup
// (e2e/stack/fresh.ts: PostgreSQL, ClickHouse and the server of this tree, no administrator, the
// server's setup token copied from /data/setup-token into the run's secrets directory). Nothing is
// routed: every answer is the real server's.
//
// - T-SEC-47 (fresh) refusals: the wizard refuses an empty token before any request and a wrong
//   token with the token message (the server's 403); the API refuses a missing token (403); a
//   too short password (11 characters, T-SEC-48) is refused by the step and by the server (422)
//   without using the token up; nothing is created meanwhile.
// - T-E2E-059 / T-SEC-47 (fresh) the wizard end to end: the token, an administrator with a
//   12-character password (T-SEC-48 lower bound), the IP collection step that must be passed
//   (ST-31-3: masked preselected, the choice is stored), the first application with its agent
//   token shown once (no-store, not kept by the page), the finish step. Afterwards the used token
//   is refused (403), any other setup request answers 404, /setup shows the closed view,
//   /setup/status says no setup is needed, and the new administrator signs in through /login.
// The tests run in order on the same single-use installation.
import { randomBytes } from 'node:crypto';

import { freshSetupToken, expect, test } from '../../fixtures/fresh';
import { t } from '../../fixtures/i18n';
import { ApiSession } from '../../stack/api';

test.describe.configure({ mode: 'serial' });

const ANNOTATIONS = [
  { type: 'req', description: 'SEC-47' },
  { type: 'req', description: 'SEC-48' },
  { type: 'req', description: 'SEC-31' },
];

/** The administrator the wizard creates (lower-case letters, digits, '.', '_', '-'). */
const ADMIN = 'fresh-admin';

/** A password of exactly `length` characters (base64url of random bytes; never printed). */
function password(length: number): string {
  return randomBytes(length).toString('base64url').slice(0, length);
}

/** A string shaped like a setup token that is not the server's (43 base64url characters). */
function wrongToken(): string {
  return randomBytes(32).toString('base64url');
}

async function needsSetup(api: ApiSession): Promise<boolean> {
  const status = await api.data<{ needs_setup: boolean }>('/setup/status');
  return status.needs_setup;
}

/** POST /setup/admin with an optional setup token header; resolves with the status. */
async function postAdmin(
  baseUrl: string,
  token: string | null,
  body: Readonly<Record<string, string>>,
): Promise<number> {
  const api = new ApiSession(baseUrl);
  await api.csrf();
  const response = await api.request('POST', '/setup/admin', {
    body,
    headers: token === null ? {} : { 'X-Argos-Setup-Token': token },
    expect: [201, 400, 401, 403, 404, 409, 422, 429],
  });
  return response.status;
}

let adminPassword: string | null = null;

test(
  'T-SEC-47 (fresh) without a token, with a wrong one or a too short password the setup is refused (token message, 403/422) and nothing is created',
  { tag: '@security', annotation: ANNOTATIONS },
  async ({ page, freshSession }) => {
    const api = new ApiSession(freshSession.stack_url);
    expect(await needsSetup(api), 'a fresh installation needs its setup').toBe(true);
    const token = freshSetupToken(freshSession);
    expect(token, 'the setup token the server wrote').toMatch(/^[A-Za-z0-9_-]{43}$/);

    // The API: no token 403, a wrong token 403, a valid token with an 11-character password 422.
    const valid = password(12);
    const body = { username: ADMIN, password: valid, ip_mode: 'masked' };
    expect(await postAdmin(freshSession.stack_url, null, body), 'no setup token').toBe(403);
    expect(await postAdmin(freshSession.stack_url, wrongToken(), body), 'a wrong token').toBe(403);
    expect(
      await postAdmin(freshSession.stack_url, token, { ...body, password: password(11) }),
      'an 11-character password (T-SEC-48)',
    ).toBe(422);
    expect(await needsSetup(api), 'nothing created by the refused requests').toBe(true);

    // The wizard: an empty token is refused before any request.
    const setupCalls: number[] = [];
    page.on('response', (response) => {
      if (new URL(response.url()).pathname === '/api/v1/setup/admin') {
        setupCalls.push(response.status());
      }
    });
    await page.goto('/setup');
    await expect(page.getByTestId('setup-wizard')).toBeVisible();
    await page.getByTestId('setup-next').click();
    await expect(page.getByTestId('setup-error')).toHaveText(t('auth:setup.token.required'));
    expect(setupCalls, 'nothing sent without a token').toEqual([]);

    // A too short password stays on the administrator step.
    await page.getByTestId('setup-token-input').fill(wrongToken());
    await page.getByTestId('setup-next').click();
    await page.getByTestId('setup-admin-username').fill(ADMIN);
    const short = password(11);
    await page.getByTestId('setup-admin-password').fill(short);
    await page.getByTestId('setup-admin-confirm').fill(short);
    await page.getByTestId('setup-next').click();
    await expect(page.getByTestId('setup-error')).toBeVisible();
    await expect(page.getByTestId('setup-admin')).toBeVisible();
    await expect(page.getByTestId('setup-ip-mode')).toHaveCount(0);

    // A wrong token: the server's 403 brings the token step back with the token message.
    await page.getByTestId('setup-admin-password').fill(valid);
    await page.getByTestId('setup-admin-confirm').fill(valid);
    await page.getByTestId('setup-next').click();
    await expect(page.getByTestId('setup-ip-mode')).toBeVisible();
    expect(setupCalls, 'nothing sent before the IP address step').toEqual([]);
    await page.getByTestId('setup-create').click();
    await expect(page.getByTestId('setup-error')).toHaveText(t('auth:setup.error.token'));
    await expect(page.getByTestId('setup-token-input')).toBeVisible();
    expect(setupCalls, 'the one setup request of the wrong token').toEqual([403]);
    expect(await needsSetup(api), 'still no administrator').toBe(true);
  },
);

test(
  'T-E2E-059 T-SEC-47 (fresh) the real wizard: token, administrator (12 characters), IP mode chosen, first application with its agent token once (no-store), finish; then the used token 403, the wizard closed (404), the administrator signs in',
  { tag: '@security', annotation: [...ANNOTATIONS, { type: 'req', description: 'SEC-02' }] },
  async ({ page, freshSession }) => {
    const base = freshSession.stack_url;
    const api = new ApiSession(base);
    expect(await needsSetup(api)).toBe(true);
    const token = freshSetupToken(freshSession);
    adminPassword = password(12);

    await page.goto('/setup');
    await expect(page.getByTestId('setup-wizard')).toBeVisible();
    await page.getByTestId('setup-token-input').fill(token);
    await page.getByTestId('setup-next').click();
    await page.getByTestId('setup-admin-username').fill(ADMIN);
    await page.getByTestId('setup-admin-password').fill(adminPassword);
    await page.getByTestId('setup-admin-confirm').fill(adminPassword);
    await page.getByTestId('setup-next').click();

    // ST-31-3: the IP collection step before anything is created, masked preselected, every mode
    // explained; the operator's choice (off) is what the server stores.
    await expect(page.getByTestId('setup-ip-mode')).toBeVisible();
    for (const mode of ['full', 'masked', 'off'] as const) {
      await expect(page.getByTestId(`setup-ip-mode-${mode}`)).toBeVisible();
      await expect(page.getByTestId('setup-ip-mode')).toContainText(
        t(`auth:setup.ipMode.${mode}Help`),
      );
    }
    await expect(page.getByTestId('setup-ip-mode-masked')).toBeChecked();
    expect(await needsSetup(api), 'nothing created before the IP address step').toBe(true);
    await page.getByTestId('setup-ip-mode-off').check();
    const created = page.waitForResponse(
      (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/setup/admin',
    );
    await page.getByTestId('setup-create').click();
    const createdResponse = await created;
    expect(createdResponse.status(), 'POST /setup/admin').toBe(201);
    const createdText = await createdResponse.text();
    expect(createdText.includes(token), 'the answer carries the setup token').toBe(false);
    expect(createdText.includes(adminPassword), 'the answer carries the password').toBe(false);

    // The first application: its agent token once, not cached, not kept by the page.
    await expect(page.getByTestId('setup-first-app')).toBeVisible();
    const name = 'fresh-first-app';
    await page.getByTestId('setup-first-app-name').fill(name);
    await page.getByTestId('setup-first-app-env').fill('e2e');
    const issued = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /^\/api\/v1\/admin\/apps\/\d+\/tokens$/.test(new URL(r.url()).pathname),
    );
    await page.getByTestId('setup-first-app-create').click();
    const issuedResponse = await issued;
    expect(issuedResponse.status()).toBe(201);
    expect(issuedResponse.headers()['cache-control'] ?? '', 'SEC-02 no-store').toContain(
      'no-store',
    );
    const value = page.getByTestId('setup-first-app-token-value');
    await expect(value).toBeVisible();
    const agentToken = await value.inputValue();
    expect(agentToken).toMatch(/^argos_[A-Za-z0-9_-]{40,}$/);
    await expect(page.getByTestId('setup-first-app-snippet')).toContainText(
      `-Dargos.app.name=${name}`,
    );
    await expect(page.getByTestId('setup-first-app-snippet')).not.toContainText(agentToken);
    const kept = await page.evaluate(() => {
      const values = [location.href, document.cookie];
      for (const storage of [localStorage, sessionStorage]) {
        for (let i = 0; i < storage.length; i += 1) {
          const key = storage.key(i) ?? '';
          values.push(key, storage.getItem(key) ?? '');
        }
      }
      return values.join('\n');
    });
    expect(kept, 'the agent token in the URL, cookies or storage').not.toContain(agentToken);
    await page.getByTestId('setup-first-app-continue').click();
    await expect(page.getByTestId('setup-finish-token-issued')).toHaveText(
      t('auth:setup.finish.agentTokenIssued'),
    );
    expect(await page.locator('body').innerText(), 'shown once').not.toContain(agentToken);

    // The installation is set up: the used token is refused, everything else is 404.
    expect(await needsSetup(api), 'GET /setup/status after the wizard').toBe(false);
    const again = { username: 'fresh-second', password: password(16), ip_mode: 'full' };
    expect(await postAdmin(base, token, again), 'the used setup token').toBe(403);
    expect(await postAdmin(base, null, again), 'an administrator exists').toBe(404);

    // The stored IP mode is the wizard's choice; the first application's token is the app's.
    const admin = new ApiSession(base);
    const login = await admin.login(ADMIN, adminPassword);
    expect(login.must_change_password, 'the wizard administrator chose the password').toBe(false);
    expect(login.roles).toContain('admin');
    const privacy = await admin.data<{ default_ip_mode: string; default_ip_mode_source: string }>(
      '/admin/settings/privacy',
    );
    expect(privacy).toEqual({ default_ip_mode: 'off', default_ip_mode_source: 'settings' });
    const apps = await admin.data<{ app_id: number; name: string }[]>('/apps');
    const app = apps.find((a) => a.name === name);
    expect(app, `GET /apps lists ${name}`).toBeDefined();
    const tokens = await admin.data<{ prefix: string; state: string }[]>(
      `/admin/apps/${String(app?.app_id ?? 0)}/tokens`,
    );
    expect(tokens.map((x) => [x.prefix, x.state])).toEqual([[agentToken.slice(0, 14), 'active']]);
    await admin.logout();
  },
);

test(
  'T-SEC-47 (fresh) after the setup /setup shows the closed view, and the new administrator signs in through /login',
  { tag: '@security', annotation: ANNOTATIONS },
  async ({ page }) => {
    const secret = adminPassword;
    test.skip(secret === null, 'the wizard test did not create the administrator');
    if (secret === null) return;
    await page.goto('/setup');
    await expect(page.getByTestId('setup-not-found')).toBeVisible();
    await expect(page.getByTestId('setup-wizard')).toHaveCount(0);
    await page.goto('/login');
    await page.getByTestId('login-username').fill(ADMIN);
    await page.getByTestId('login-password').fill(secret);
    await page.getByTestId('login-submit').click();
    await expect(page.getByTestId('app-shell')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('login-form')).toHaveCount(0);
  },
);
