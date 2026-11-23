// SPDX-License-Identifier: Apache-2.0
//
// en / ko switch of the UI (F-U13, docs/15 §12): the language is chosen in the account menu, applies
// at once without a reload, survives a reload (per-browser preference) and the ko labels follow the
// glossary of docs/01 appendix B exactly. The browser of the suite is en-US (playwright.config.ts),
// so English is the default.
import type { Page } from '@playwright/test';

import { t } from '../fixtures/i18n';
import { expect, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';

const I18N = { type: 'req', description: 'F-U13' };

/** UI labels (ko) of docs/01 appendix B that the WO-30 shell shows. */
const APPENDIX_B = {
  kindHttp: '요청',
  kindBatch: '배치',
  kindMessage: '메시지',
  overview: '전체 현황',
  active: '액티브 트랜잭션',
  xlog: 'XLog',
  map: '애플리케이션 맵',
} as const;

async function chooseLanguage(page: Page, language: 'en' | 'ko'): Promise<void> {
  await page.getByTestId('account-menu').click();
  await page.getByTestId('language-select').selectOption(language);
  await page.keyboard.press('Escape');
}

test(
  'T-E2E-053 switching to ko shows the appendix B labels at once; the choice survives a reload; en again',
  { annotation: I18N },
  async ({ page, seed }) => {
    // A placeholder screen (the application map, 1.x; the instance screens are WO-37's since the
    // instance list and detail were built).
    await page.goto(`/apps/${String(seed.primary_app_id)}/map`);
    await expectShell(page);
    // English by default for an en-US browser (O-A05).
    await expect(page.getByTestId('kind-filter-http')).toHaveText(t('common:term.kind.http'));
    await expect(page.getByTestId('side-menu-overview')).toContainText(t('common:term.overview'));
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');

    await chooseLanguage(page, 'ko');
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
    await expect(page.getByTestId('kind-filter-http')).toHaveText(APPENDIX_B.kindHttp);
    await expect(page.getByTestId('kind-filter-batch')).toHaveText(APPENDIX_B.kindBatch);
    await expect(page.getByTestId('kind-filter-message')).toHaveText(APPENDIX_B.kindMessage);
    await expect(page.getByTestId('side-menu-overview')).toContainText(APPENDIX_B.overview);
    await expect(page.getByTestId('side-menu-active')).toContainText(APPENDIX_B.active);
    await expect(page.getByTestId('side-menu-xlog')).toContainText(APPENDIX_B.xlog);
    await expect(page.getByTestId('side-menu-map')).toContainText(APPENDIX_B.map);
    // The placeholder of a later screen is translated too (no English left on the page body). Its
    // label shell:screen.map refers to common:term.applicationMap (resolved here; t() of the
    // fixtures does not follow $t references).
    expect(t('shell:screen.map', 'ko')).toBe('$t(common:term.applicationMap)');
    await expect(page.getByTestId('route-pending')).toContainText(
      t('common:term.applicationMap', 'ko'),
    );

    await page.reload();
    await expectShell(page);
    await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
    await expect(page.getByTestId('kind-filter-http')).toHaveText(APPENDIX_B.kindHttp);

    await chooseLanguage(page, 'en');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
    await expect(page.getByTestId('kind-filter-http')).toHaveText(t('common:term.kind.http'));
  },
);

test.describe('sign-in page', () => {
  test.use({ role: null, locale: 'ko-KR' });

  test(
    'T-E2E-053a a ko browser gets the ko sign-in page (browser language picks the default, O-A05)',
    { annotation: I18N },
    async ({ page }) => {
      await page.goto('/login');
      await expect(page.getByTestId('login-form')).toBeVisible();
      await expect(page.getByTestId('login-submit')).toHaveText(t('auth:login.submit', 'ko'));
      await expect(page.locator('html')).toHaveAttribute('lang', 'ko');
    },
  );
});
