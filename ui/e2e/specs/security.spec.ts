// SPDX-License-Identifier: Apache-2.0
//
// Browser part of the session and page security of WO-24's server (docs/16 §7.1, §7.6, §9.9;
// SEC-40, SEC-43, SEC-46): the UI never holds a session token where scripts can read it, every
// state-changing request carries X-Argos-CSRF equal to the argos_csrf cookie, and the page runs
// under the server's per-request nonce CSP without a single violation (component styles included:
// Ant Design gets the nonce through ConfigProvider csp). The page hook of every stack test repeats
// the storage and CSP checks (e2e/fixtures/fixtures.ts).
import type { Page, Request } from '@playwright/test';

import { expect, rolePassword, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';

const UNSAFE = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
/** Token-exempt on the server (login, first administrator) or sent by the browser itself. */
const NO_TOKEN = new Set(['/api/v1/auth/login', '/api/v1/setup/admin', '/api/v1/csp-report']);

async function signIn(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.getByTestId('login-username').fill(username);
  await page.getByTestId('login-password').fill(password);
  await page.getByTestId('login-submit').click();
  await expectShell(page);
}

test.describe('signed in through the form', () => {
  test.use({ role: null });

  test(
    'T-SEC-40 session tokens stay in httpOnly cookies: none in document.cookie, localStorage or sessionStorage',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-40' } },
    async ({ page, context, seed }) => {
      await signIn(page, seed.users.viewer, rolePassword(seed, 'viewer'));
      await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard`);
      await expectShell(page);
      const cookies = await context.cookies();
      const at = cookies.find((c) => c.name === 'argos_at');
      const rt = cookies.find((c) => c.name === 'argos_rt');
      const csrf = cookies.find((c) => c.name === 'argos_csrf');
      expect(at).toMatchObject({ httpOnly: true, secure: true, sameSite: 'Strict', path: '/' });
      expect(rt).toMatchObject({
        httpOnly: true,
        secure: true,
        sameSite: 'Strict',
        path: '/api/v1/auth/refresh',
      });
      expect(csrf).toMatchObject({ httpOnly: false, secure: true, sameSite: 'Strict' });
      const seen = await page.evaluate(() => ({
        cookie: document.cookie,
        local: Object.entries(window.localStorage),
        session: Object.entries(window.sessionStorage),
      }));
      const names = seen.cookie.split(';').map((c) => c.trim().split('=')[0]);
      expect(names).not.toContain('argos_at');
      expect(names).not.toContain('argos_rt');
      const stored = JSON.stringify([...seen.local, ...seen.session]);
      expect(stored).not.toContain(at?.value ?? '\u0000');
      expect(stored).not.toContain(rt?.value ?? '\u0000');
      expect(stored).not.toMatch(/eyJ[A-Za-z0-9_-]{8,}\.eyJ/);
      // Only per-viewer preferences under the argos.ui. prefix (docs/15 §4.1, argos/no-token-storage).
      for (const [key] of [...seen.local, ...seen.session]) {
        expect(key).toMatch(/^argos\.ui\./);
      }
    },
  );

  test(
    'T-SEC-43 every state-changing request of a session carries X-Argos-CSRF equal to the argos_csrf cookie',
    { tag: '@security', annotation: { type: 'req', description: 'SEC-43' } },
    async ({ page, context, seed }) => {
      const unsafe: Request[] = [];
      page.on('request', (r) => {
        const url = new URL(r.url());
        if (
          UNSAFE.has(r.method()) &&
          url.pathname.startsWith('/api/') &&
          !NO_TOKEN.has(url.pathname)
        ) {
          unsafe.push(r);
        }
      });
      await signIn(page, seed.users.viewer, rolePassword(seed, 'viewer'));
      await page.goto(`/apps/${String(seed.primary_app_id)}/xlog?from=now-1h`);
      await expectShell(page);
      await page.goto('/account');
      await expect(page.getByTestId('account-page')).toBeVisible();
      const csrf = (await context.cookies()).find((c) => c.name === 'argos_csrf')?.value;
      expect(csrf, 'argos_csrf cookie after sign-in').toBeTruthy();
      await page.getByTestId('account-menu').click();
      await page.getByTestId('logout').click();
      await expect(page).toHaveURL(/\/login/);
      const paths = unsafe.map((r) => `${r.method()} ${new URL(r.url()).pathname}`);
      expect(paths).toContain('POST /api/v1/auth/logout');
      for (const r of unsafe) {
        expect(r.headers()['x-argos-csrf'], `${r.method()} ${r.url()}`).toBe(csrf);
      }
    },
  );
});

test(
  'T-SEC-46 the UI runs under the server nonce CSP: fresh nonce per page, component styles carry it, zero violations',
  { tag: '@security', annotation: { type: 'req', description: 'SEC-46' } },
  async ({ page, seed }, testInfo) => {
    const headers: string[] = [];
    page.on('response', (r) => {
      if (r.request().resourceType() === 'document') {
        headers.push(r.headers()['content-security-policy'] ?? '');
      }
    });
    await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard`);
    await expectShell(page);
    // Open popups so that Ant Design injects the styles of more components (Select, popover).
    await page.getByTestId('app-select').click();
    await page.keyboard.press('Escape');
    await page.getByTestId('time-range-preset').click();
    await page.keyboard.press('Escape');
    const first = headers[0] ?? '';
    const nonce = /'nonce-([A-Za-z0-9_-]{16,})'/.exec(first)?.[1];
    expect(nonce, `CSP of the page: ${first}`).toBeDefined();
    expect(first).toContain("'strict-dynamic'");
    expect(first).toMatch(/style-src 'self' 'nonce-/);
    expect(first).not.toContain('unsafe-inline');
    expect(first).toContain("object-src 'none'");
    expect(first).toContain("frame-ancestors 'none'");
    const page1 = await page.evaluate(() => ({
      meta: document.querySelector('meta[name="csp-nonce"]')?.getAttribute('content') ?? null,
      styles: [...document.querySelectorAll('style')].map((s) => s.nonce),
      scripts: [...document.querySelectorAll('script')].map((s) => s.nonce),
    }));
    expect(page1.meta).toBe(nonce);
    expect(page1.styles.length, 'component library style elements').toBeGreaterThan(0);
    expect(
      page1.styles.filter((n) => n !== nonce),
      'style elements without the page nonce',
    ).toEqual([]);
    expect(
      page1.scripts.filter((n) => n !== nonce),
      'script elements without the page nonce',
    ).toEqual([]);
    testInfo.annotations.push({
      type: 'csp-style-src',
      description: /style-src [^;]+/.exec(first)?.[0] ?? '',
    });
    await page.reload();
    await expectShell(page);
    const second = /'nonce-([A-Za-z0-9_-]{16,})'/.exec(headers[1] ?? '')?.[1];
    expect(second).toBeDefined();
    expect(second).not.toBe(nonce);
    // The page hooks and the context recorder assert zero securitypolicyviolation events after the
    // test (ADR-0010 C-188 (1)); csp-report.spec.ts holds their positive controls.
  },
);
