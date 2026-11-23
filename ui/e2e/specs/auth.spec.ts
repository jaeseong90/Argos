// SPDX-License-Identifier: Apache-2.0
//
// Sign-in, deep-link return, session refresh and sign-out of the UI against the real server
// (WO-24 session cookies and CSRF; docs/15 §9.11, §11; docs/16 §7.1-§7.6; SEC-40..44 browser part).
import { readFileSync } from 'node:fs';

import type { Page, Route } from '@playwright/test';

import { t } from '../fixtures/i18n';
import { expect, rolePassword, test } from '../fixtures/stack';
import { expectShell, expectUrl, horizontalOverflow } from '../fixtures/ui';
import { manifestVariables } from '../required/variables';
import { ApiSession } from '../stack/api';
import { credentialFile } from '../stack/paths';

const AUTH = { type: 'req', description: 'SEC-42' };

async function signIn(page: Page, username: string, password: string): Promise<void> {
  await expect(page.getByTestId('login-form')).toBeVisible();
  await page.getByTestId('login-username').fill(username);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
}

test.describe('sign-in', () => {
  test.use({ role: null });

  test(
    'T-E2E-043 the viewer signs in on /login and lands on the overview shell',
    { annotation: AUTH },
    async ({ page, seed }) => {
      await page.goto('/login');
      await signIn(page, seed.users.viewer, rolePassword(seed, 'viewer'));
      await expectShell(page);
      await expectUrl(
        page,
        (s) => s.path === '/overview',
        'the default screen after sign-in is /overview',
      );
      // The overview screen itself (WO-31), not a placeholder.
      await expect(page.getByTestId('overview-page')).toBeVisible();
      await expect(page.getByTestId('route-pending')).toHaveCount(0);
      await page.getByTestId('account-menu').click();
      await expect(page.getByText(t('shell:role.viewer'), { exact: false })).toBeVisible();
    },
  );

  test(
    'T-E2E-044 a wrong password and an unknown user get the same generic message',
    { annotation: [AUTH, { type: 'req', description: 'SEC-48' }] },
    async ({ page, seed }) => {
      await page.goto('/login');
      await signIn(page, seed.users.viewer, 'not-the-password-0000');
      await expect(page.getByTestId('login-error')).toHaveText(t('auth:login.error.invalid'));
      await signIn(page, 'e2e-no-such-user', 'not-the-password-0000');
      await expect(page.getByTestId('login-error')).toHaveText(t('auth:login.error.invalid'));
      await expect(page).toHaveURL(/\/login/);
    },
  );

  test(
    'T-E2E-040 a deep link opened without a session returns to the same URL after sign-in',
    { annotation: [AUTH, { type: 'req', description: 'F-U14' }] },
    async ({ page, seed }) => {
      const vars = manifestVariables(seed);
      const path = `/apps/${String(seed.primary_app_id)}/xlog`;
      await page.goto(`${path}?from=${vars.mid15_from ?? ''}&to=${vars.mid15_to ?? ''}&kind=BATCH`);
      await expect(page).toHaveURL(/\/login\?next=/);
      await signIn(page, seed.users.viewer, rolePassword(seed, 'viewer'));
      await expectShell(page);
      const s = await expectUrl(page, (x) => x.path === path, 'back on the deep-linked path');
      expect(s.time).toEqual({
        kind: 'absolute',
        fromMs: Date.parse(vars.mid15_from ?? ''),
        toMs: Date.parse(vars.mid15_to ?? ''),
      });
      expect(s.kind).toBe('BATCH');
    },
  );

  test(
    'T-E2E-040a next accepts same-origin paths only (no open redirect)',
    { annotation: [AUTH, { type: 'req', description: 'F-U14' }] },
    async ({ page, seed }) => {
      await page.goto(`/login?next=${encodeURIComponent('//evil.example/x')}`);
      await signIn(page, seed.users.viewer, rolePassword(seed, 'viewer'));
      await expectShell(page);
      await expectUrl(
        page,
        (s) => s.path === '/overview',
        'a foreign next falls back to /overview',
      );
      expect(new URL(page.url()).origin).toBe(new URL(seed.stack_url).origin);
    },
  );
});

test.describe('session', () => {
  test(
    'T-SEC-41 an expired access cookie is refreshed once and the request is retried',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-41' } },
    async ({ page, context, seed }) => {
      await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard`);
      await expectShell(page);
      const refreshes: string[] = [];
      page.on('request', (r) => {
        if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/auth/refresh') {
          refreshes.push(r.url());
        }
      });
      // The access cookie lives 15 minutes; dropping it is what its expiry looks like to the server.
      const others = (await context.cookies()).filter((c) => c.name !== 'argos_at');
      await context.clearCookies();
      await context.addCookies(others);
      const retried = page.waitForResponse(
        (r) => new URL(r.url()).pathname === '/api/v1/auth/me' && r.status() === 200,
      );
      await page.reload();
      await retried;
      await expectShell(page);
      expect(refreshes).toHaveLength(1);
      expect((await context.cookies()).some((c) => c.name === 'argos_at')).toBe(true);
    },
  );

  test(
    'T-SEC-41a two tabs with an expired access cookie share one refresh (no reuse revocation)',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-41' } },
    async ({ page, context, seed }) => {
      const path = `/apps/${String(seed.primary_app_id)}/dashboard`;
      await page.goto(path);
      await expectShell(page);
      const second = await context.newPage();
      await second.goto(path);
      await expectShell(second);
      const tabs = [page, second] as const;
      let refreshes = 0;
      context.on('request', (r) => {
        if (r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/auth/refresh') {
          refreshes += 1;
        }
      });
      // /auth/me answers per tab, recorded from now on (401 without the access cookie, 200 after).
      const meAnswers = tabs.map((tab) => {
        const statuses: number[] = [];
        tab.on('response', (r) => {
          if (new URL(r.url()).pathname === '/api/v1/auth/me') {
            statuses.push(r.status());
          }
        });
        return statuses;
      });
      const unauthorized = tabs.map((tab) =>
        tab.waitForResponse(
          (r) => new URL(r.url()).pathname === '/api/v1/auth/me' && r.status() === 401,
        ),
      );
      const authorized = tabs.map((tab) =>
        tab.waitForResponse(
          (r) => new URL(r.url()).pathname === '/api/v1/auth/me' && r.status() === 200,
        ),
      );
      // Hold the first refresh until both tabs got their 401: the second tab's /auth/me then left
      // before the refresh finished, so it can only recover through the single-flight path (Web
      // Lock + "refreshed at" marker) and not with cookies a finished refresh already set. The
      // request goes to the real server unchanged (route.continue), nothing is answered here.
      const bothUnauthorized = Promise.all(unauthorized);
      let held = false;
      const holdFirstRefresh = async (route: Route): Promise<void> => {
        if (!held && route.request().method() === 'POST') {
          held = true;
          // Bounded: if a 401 never comes, the refresh goes on and the assertions below fail.
          await Promise.race([
            bothUnauthorized,
            new Promise((resolve) => {
              const timer = setTimeout(resolve, 20_000);
              const stop = (): void => {
                clearTimeout(timer);
              };
              bothUnauthorized.then(stop, stop);
            }),
          ]);
        }
        await route.continue();
      };
      await context.route('**/api/v1/auth/refresh', holdFirstRefresh);
      const others = (await context.cookies()).filter((c) => c.name !== 'argos_at');
      await context.clearCookies();
      await context.addCookies(others);
      await Promise.all([page.reload(), second.reload()]);
      await bothUnauthorized;
      await Promise.all(authorized);
      await expectShell(page);
      await expectShell(second);
      await context.unroute('**/api/v1/auth/refresh', holdFirstRefresh);
      // Both tabs keep working; the server revokes the family on a reused refresh token (D-9).
      await expect(page).not.toHaveURL(/\/login/);
      await expect(second).not.toHaveURL(/\/login/);
      expect(held, 'the first refresh was held until both tabs got 401').toBe(true);
      expect(
        meAnswers.map((statuses) => statuses.filter((s) => s === 401).length),
        '/auth/me 401 answers per tab',
      ).toEqual([1, 1]);
      expect(
        meAnswers.map((statuses) => statuses.includes(200)),
        '/auth/me 200 after the refresh, per tab',
      ).toEqual([true, true]);
      expect(refreshes, 'POST /auth/refresh over both tabs').toBe(1);
      expect((await context.cookies()).some((c) => c.name === 'argos_at')).toBe(true);
      await second.close();
    },
  );

  test(
    'T-SEC-41b a failed refresh sends the user to /login with the current URL as next',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-41' } },
    async ({ page, context, seed }) => {
      const path = `/apps/${String(seed.primary_app_id)}/dashboard`;
      await page.goto(path);
      await expectShell(page);
      await context.clearCookies();
      await page.reload();
      await expect(page).toHaveURL(/\/login\?next=/);
      const next = new URL(page.url()).searchParams.get('next') ?? '';
      expect(next.startsWith(path)).toBe(true);
    },
  );

  test(
    'T-E2E-045 sign-out ends the session on the server (API 401 afterwards) and closes the WebSocket',
    { tag: '@security', annotation: [AUTH, { type: 'req', description: 'SEC-44' }] },
    async ({ page, context, seed, ws }) => {
      await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard?from=now-15m&live=1`);
      await expectShell(page);
      await ws.waitForSubscription();
      const cookies = await context.cookies();
      const csrfBefore = cookies.find((c) => c.name === 'argos_csrf')?.value;
      const logout = page.waitForRequest(
        (r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/v1/auth/logout',
      );
      await page.getByTestId('account-menu').click();
      await page.getByTestId('logout').click();
      const request = await logout;
      expect(request.headers()['x-argos-csrf']).toBe(csrfBefore);
      await expect(page).toHaveURL(/\/login/);
      // The client closed its WebSocket and opened no new one.
      await expect.poll(() => ws.connections().every((c) => c.closed?.by === 'client')).toBe(true);
      const count = ws.connectionCount();
      await page.waitForTimeout(3000);
      expect(ws.connectionCount()).toBe(count);
      // The old session cookies no longer authenticate (server-side revocation, SEC-42).
      const response = await fetch(`${seed.stack_url}/api/v1/auth/me`, {
        headers: { Cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; ') },
      });
      expect(response.status).toBe(401);
    },
  );
});

test.describe('password change', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-046 /account changes the password with X-Argos-CSRF; the session continues with new cookies',
    {
      tag: '@security',
      annotation: [
        { type: 'req', description: 'SEC-43' },
        { type: 'req', description: 'SEC-48' },
      ],
    },
    async ({ page, context, seed }) => {
      const current = readFileSync(credentialFile(seed.run_id, 'operator'), 'utf8');
      const next = `${current.slice(0, 20)}Zq9`;
      await page.goto('/account');
      await expect(page.getByTestId('account-page')).toBeVisible();
      const csrfBefore = (await context.cookies()).find((c) => c.name === 'argos_csrf')?.value;
      expect(csrfBefore, 'argos_csrf cookie of the session').toBeTruthy();
      await page.getByTestId('password-change-current').fill(current);
      await page.getByTestId('password-change-new').fill(next);
      await page.getByTestId('password-change-confirm').fill(next);
      const put = page.waitForRequest(
        (r) => r.method() === 'PUT' && new URL(r.url()).pathname === '/api/v1/auth/password',
      );
      const done = page.waitForResponse(
        (r) => new URL(r.url()).pathname === '/api/v1/auth/password',
      );
      await page.getByTestId('password-change-submit').click();
      // The request carries the CSRF token of the session (double submit, docs/14 §2.2).
      expect((await put).headers()['x-argos-csrf']).toBe(csrfBefore);
      expect((await done).status()).toBe(204);
      await expect(page.getByTestId('password-change-success')).toHaveText(
        t('auth:password.changed'),
      );
      // 204 with three new cookies (WO-24): a new CSRF value, and the session goes on.
      const after = await context.cookies();
      expect(after.find((c) => c.name === 'argos_csrf')?.value).not.toBe(csrfBefore);
      expect(after.some((c) => c.name === 'argos_at')).toBe(true);
      await page.reload();
      await expect(page.getByTestId('account-page')).toBeVisible();
      // Change it back so that the run's credential file stays valid for later tests.
      const api = new ApiSession(seed.stack_url);
      await api.login(seed.users.operator, next);
      await api.request('PUT', '/auth/password', {
        body: { current: next, new: current },
        expect: [204],
      });
      await api.logout();
    },
  );
});

/** An enveloped answer (docs/14 §2.5) for the routed setup calls. */
function enveloped(data: unknown): { data: unknown; meta: Record<string, string> } {
  return { data, meta: { request_id: 'e2e-setup-route', generated_at: new Date().toISOString() } };
}

test.describe('setup wizard first application', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-057 the setup wizard first-application step registers an app and shows its agent token once',
    {
      tag: '@security',
      annotation: [
        { type: 'req', description: 'SEC-02' },
        { type: 'req', description: 'SEC-47' },
        { type: 'req', description: 'F-U14' },
        {
          type: 'note',
          description:
            'The stack server is set up already, so /setup/status and /setup/admin are routed ' +
            '(needs_setup, a created administrator); the signed-in administrator session, ' +
            'POST /apps and POST /admin/apps/{appId}/tokens are the real server (ADR-0010 C-168 (2)).',
        },
      ],
    },
    async ({ page, apiAs, seed }) => {
      await page.route('**/api/v1/setup/status', (route) =>
        route.fulfill({ json: enveloped({ needs_setup: true, server_version: 'e2e' }) }),
      );
      await page.route('**/api/v1/setup/admin', (route) =>
        route.fulfill({
          status: 201,
          json: enveloped({
            user: { id: 'e2e-setup', username: 'e2e-setup', roles: ['admin'], permissions: [] },
          }),
        }),
      );
      await page.goto('/setup');
      await page.getByTestId('setup-token-input').fill('e2e-routed-setup-token');
      await page.getByTestId('setup-next').click();
      await page.getByTestId('setup-admin-username').fill('e2e-setup');
      await page.getByTestId('setup-admin-password').fill('e2e-setup-password-0');
      await page.getByTestId('setup-admin-confirm').fill('e2e-setup-password-0');
      await page.getByTestId('setup-next').click();
      await page.getByTestId('setup-create').click();
      // The real /auth/me answers for the administrator's cookies: the first-app step opens.
      await expect(page.getByTestId('setup-first-app')).toBeVisible();

      const name = `e2e-first-app-${Date.now().toString(36)}`;
      await page.getByTestId('setup-first-app-name').fill(name);
      await page.getByTestId('setup-first-app-env').fill('e2e');
      const created = page.waitForResponse(
        (r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/v1/apps',
      );
      const issued = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /^\/api\/v1\/admin\/apps\/\d+\/tokens$/.test(new URL(r.url()).pathname),
      );
      await page.getByTestId('setup-first-app-create').click();
      const createdResponse = await created;
      expect(createdResponse.status()).toBe(201);
      const appId = ((await createdResponse.json()) as { data: { app_id: number } }).data.app_id;
      const admin = await apiAs('admin');
      try {
        const issuedResponse = await issued;
        expect(issuedResponse.status()).toBe(201);
        // SEC-02: the one answer that carries the token is not cached.
        expect(issuedResponse.headers()['cache-control'] ?? '').toContain('no-store');

        const value = page.getByTestId('setup-first-app-token-value');
        await expect(value).toBeVisible();
        const token = await value.inputValue();
        expect(token).toMatch(/^argos_[A-Za-z0-9_-]{40,}$/);
        await expect(page.getByTestId('setup-first-app-snippet')).toContainText(
          `-Dargos.app.name=${name}`,
        );
        await expect(page.getByTestId('setup-first-app-snippet')).not.toContainText(token);
        // docs/15 §13: no horizontal page scroll at 1366x768 with the token and options shown.
        await page.setViewportSize({ width: 1366, height: 768 });
        const wide = await horizontalOverflow(page);
        expect(wide.scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(
          wide.clientWidth,
        );

        // The server keeps the prefix only and lists the new token as active.
        const tokens = await admin.data<{ prefix: string; state: string }[]>(
          `/admin/apps/${String(appId)}/tokens`,
        );
        expect(tokens.map((x) => [x.prefix, x.state])).toEqual([[token.slice(0, 14), 'active']]);
        // The issued token authenticates as the application (C-12 Bearer event write).
        const event = await fetch(`${seed.stack_url}/api/v1/events`, {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ app_id: appId, type: 'custom', title: 'T-E2E-057 token check' }),
        });
        expect(event.status).toBe(201);

        // Never kept by the page: not in storage, cookies or the URL.
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
        expect(kept).not.toContain(token);

        await page.getByTestId('setup-first-app-continue').click();
        await expect(page.getByTestId('setup-finish-token-issued')).toHaveText(
          t('auth:setup.finish.agentTokenIssued'),
        );
        // Shown once: gone from the page after the step.
        expect(await page.locator('body').innerText()).not.toContain(token);
        expect(
          await page.evaluate(
            (secret) =>
              Array.from(document.querySelectorAll('input')).some((i) => i.value === secret),
            token,
          ),
        ).toBe(false);
      } finally {
        // Leave no application behind for the other specs (C-153 delete revokes its tokens).
        await admin.request('DELETE', `/apps/${String(appId)}`, {
          query: { confirm: name },
          expect: [202],
        });
        await admin.logout();
      }
    },
  );
});

test.describe('own sessions', () => {
  test(
    'T-E2E-058 /account lists the own sessions with the current one marked and ends another at once',
    {
      tag: '@security',
      annotation: [
        { type: 'req', description: 'SEC-42' },
        { type: 'req', description: 'F-U14' },
      ],
    },
    async ({ page, apiAs }) => {
      // A second session of the same user (the Node client), the one to end from the browser.
      const other = await apiAs('viewer');
      const own = await other.data<{ session_id: string; current: boolean }[]>('/auth/sessions');
      const otherId = own.find((s) => s.current)?.session_id ?? '';
      expect(otherId).toMatch(/^[0-9a-f-]{36}$/);

      await page.setViewportSize({ width: 1366, height: 768 });
      await page.goto('/account');
      const list = page.getByTestId('account-sessions-list');
      await expect(list).toBeVisible();
      // docs/15 §13: the session list fits 1366x768 without horizontal page scroll.
      const wide = await horizontalOverflow(page);
      expect(wide.scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(wide.clientWidth);
      const current = list.locator('[data-testid="account-session"][data-current="true"]');
      await expect(current).toHaveCount(1);
      await expect(current.getByTestId('account-session-current')).toHaveText(
        t('auth:account.sessions.current'),
      );
      await expect(current.getByTestId('account-session-revoke')).toHaveCount(0);
      const target = list.locator(`[data-session-id="${otherId}"]`);
      await expect(target).toBeVisible();
      await expect(target).not.toHaveAttribute('data-current', 'true');

      const ended = page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new URL(r.url()).pathname === `/api/v1/auth/sessions/${otherId}`,
      );
      await target.getByTestId('account-session-revoke').click();
      expect((await ended).status()).toBe(204);
      await expect(page.getByTestId('account-sessions-revoked')).toBeVisible();
      await expect(target).toHaveCount(0);
      // Ended on the server at once: the other session's next request is refused.
      const after = await other.request('GET', '/auth/me', { expect: [401] });
      expect(after.status).toBe(401);
      // The browser's own session goes on.
      await page.reload();
      await expect(page.getByTestId('account-page')).toBeVisible();
    },
  );
});
