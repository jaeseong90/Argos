// SPDX-License-Identifier: Apache-2.0
//
// 1366x768 layout (docs/15 §13, 05 §2.0, WO-30 acceptance): every route of the WO-30 shell - its own
// screens and the placeholders of WO-31..39 - opens without horizontal page scroll
// (document scrollWidth <= clientWidth). Placeholders show the route-pending empty state and no data.
import { t } from '../fixtures/i18n';
import { expect, test } from '../fixtures/stack';
import { expectShell, horizontalOverflow } from '../fixtures/ui';

import { routePath, SHELL_ROUTES } from './routes';

const LAYOUT = { type: 'req', description: 'F-U16' };

for (const role of ['viewer', 'operator', 'admin', 'anonymous'] as const) {
  const routes = SHELL_ROUTES.filter((r) => r.role === role);
  test.describe(`routes for ${role}`, () => {
    test.use({ role: role === 'anonymous' ? null : role });
    test(
      `T-E2E-054 ${role}: ${String(routes.length)} routes at 1366x768 without horizontal scroll`,
      { annotation: LAYOUT },
      async ({ page, seed }, testInfo) => {
        test.setTimeout(30_000 + routes.length * 8_000);
        const viewport = page.viewportSize();
        expect(viewport).toEqual({ width: 1366, height: 768 });
        const results: string[] = [];
        for (const route of routes) {
          const path = routePath(route.pattern, seed);
          await page.goto(path);
          if (route.pattern === '/setup') {
            await expect(page.getByText(t('auth:setup.notFoundTitle'))).toBeVisible();
          } else if (route.role === 'anonymous') {
            await expect(page.getByTestId('login-form')).toBeVisible();
          } else {
            await expectShell(page);
            if (route.kind === 'placeholder') {
              await expect(page.getByTestId('route-pending'), path).toBeVisible();
            }
          }
          const { scrollWidth, clientWidth } = await horizontalOverflow(page);
          results.push(`${path} ${String(scrollWidth)}/${String(clientWidth)}`);
          expect(scrollWidth, `${path}: scrollWidth <= clientWidth`).toBeLessThanOrEqual(
            clientWidth,
          );
        }
        await testInfo.attach('scroll-widths.txt', {
          body: results.join('\n'),
          contentType: 'text/plain',
        });
      },
    );
  });
}
