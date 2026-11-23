// SPDX-License-Identifier: Apache-2.0
//
// Sign-in, deep-link return, session refresh and sign-out of the UI against the real server
// (WO-24 session cookies and CSRF; docs/15 §9.11, §11; docs/16 §7.1-§7.6; SEC-40..44 browser part).
import { readFileSync } from 'node:fs';

import type { Page, Route } from '@playwright/test';

import { t } from '../fixtures/i18n';
import { expect, rolePassword, test } from '../fixtures/stack';
import { expectShell, expectUrl } from '../fixtures/ui';
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
