// SPDX-License-Identifier: Apache-2.0
//
// Accessibility of the WO-30 screens on the real server (docs/15 §13, §15; WO-04 hand-over C-12):
// axe-core finds no violation of impact "critical" and no color-contrast violation (docs/15 §13,
// at any impact) on the sign-in page, the setup page, the account page, the overview and the shell
// (en and ko); other serious and lower findings are attached to the report. The placeholder of the
// empty global search (not evaluated by axe-core) is measured on every shell page at 4.5:1.
// The setup page of this stack is the closed wizard (the unattended administrator exists, WO-24);
// the wizard steps need a server without an administrator (see the report).
import { expectNoCriticalA11y, expectPlaceholderContrast } from '../fixtures/a11y';
import { expect, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';
import { manifestVariables } from '../required/variables';

const A11Y = { type: 'req', description: 'F-U16' };

test.describe('signed out', () => {
  test.use({ role: null });

  test(
    'T-E2E-056 axe: the sign-in page has no critical violation',
    { annotation: A11Y },
    async ({ page }, testInfo) => {
      await page.goto('/login');
      await expect(page.getByTestId('login-form')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'login');
      // The error message after a failed attempt is part of the form (role alert).
      await page.getByTestId('login-username').fill('e2e-no-such-user');
      await page.getByTestId('login-password').fill('not-the-password-0000');
      await page.getByTestId('login-submit').click();
      await expect(page.getByTestId('login-error')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'login-error');
    },
  );

  test(
    'T-E2E-056a axe: the setup page has no critical violation',
    { annotation: A11Y },
    async ({ page }, testInfo) => {
      await page.goto('/setup');
      await expect(page.getByTestId('setup-not-found')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'setup');
    },
  );
});

test.describe('signed in', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-056b axe: the account page has no critical violation',
    { annotation: A11Y },
    async ({ page }, testInfo) => {
      await page.goto('/account');
      await expect(page.getByTestId('account-page')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'account');
      await expectPlaceholderContrast(page.getByTestId('global-search'), 'global-search (account)');
    },
  );

  test(
    'T-E2E-056d axe: the overview (application selector without a value, empty search) has no critical or contrast violation',
    { annotation: A11Y },
    async ({ page }, testInfo) => {
      // On a global route the application selector has no value and shows its placeholder
      // (Ant Design colorTextPlaceholder); the global search is empty and shows its own.
      const apps = page.waitForResponse(
        (response) => new URL(response.url()).pathname === '/api/v1/apps' && response.ok(),
      );
      await page.goto('/overview');
      await expectShell(page);
      await apps;
      await expect(page.getByTestId('app-select')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'overview');
      await expectPlaceholderContrast(
        page.getByTestId('global-search'),
        'global-search (overview)',
      );
    },
  );

  test(
    'T-E2E-056c axe: the shell (en, ko, menus open) has no critical violation',
    { annotation: A11Y },
    async ({ page, seed }, testInfo) => {
      const vars = manifestVariables(seed);
      await page.goto(
        `/apps/${String(seed.primary_app_id)}/dashboard?from=${vars.mid15_from ?? ''}&to=${vars.mid15_to ?? ''}`,
      );
      await expectShell(page);
      await expectNoCriticalA11y(page, testInfo, 'shell-en');
      await expectPlaceholderContrast(page.getByTestId('global-search'), 'global-search (en)');
      await page.getByTestId('time-range-preset').click();
      await expect(page.getByTestId('time-range-absolute')).toBeVisible();
      await expectNoCriticalA11y(page, testInfo, 'shell-time-picker');
      await page.keyboard.press('Escape');
      await page.getByTestId('account-menu').click();
      await page.getByTestId('language-select').selectOption('ko');
      await page.keyboard.press('Escape');
      await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
      await expectNoCriticalA11y(page, testInfo, 'shell-ko');
      await expectPlaceholderContrast(page.getByTestId('global-search'), 'global-search (ko)');
    },
  );
});
