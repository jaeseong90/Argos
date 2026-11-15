// SPDX-License-Identifier: Apache-2.0
//
// Visual regression of the WO-30 shell (docs/18 §13, WO-04 hand-over C-12): the sign-in page, the
// shell around a placeholder screen, and the empty, error and loading states, each rendered by the
// real server at 1366x768. Threshold 0.1 % of the pixels, animations off (playwright.config.ts).
// Baselines exist only for the pinned Linux Playwright image (e2e/stack/images.ts PLAYWRIGHT_IMAGE):
// `corepack pnpm e2e:docker` creates missing ones (--update-snapshots=missing) and compares the
// rest. Elsewhere (Windows, macOS, a different image) the fonts and anti-aliasing differ, so the
// comparison is skipped with the annotation screenshot-linux-only. The screens use relative time
// ranges (labels of constant width); values that change per run (the range button, request ids)
// are masked. Fonts: the system-ui stack of the image
// (no bundled font, ADR-0010 C-169 (1)).
import type { Page } from '@playwright/test';

import { expect, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';
import { OVERFLOW_TX_ID } from '../required/checks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';

const VISUAL = { type: 'req', description: 'F-U16' };

/** True inside the Linux Playwright image of e2e:docker (the only place baselines are valid). */
export function inPinnedLinuxImage(
  env: Readonly<Record<string, string | undefined>> = process.env,
): boolean {
  return process.platform === 'linux' && env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

// eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
test.beforeEach(({}, testInfo) => {
  if (!inPinnedLinuxImage()) {
    testInfo.annotations.push({
      type: 'screenshot-linux-only',
      description: `baselines are made in ${PLAYWRIGHT_IMAGE} only (corepack pnpm e2e:docker)`,
    });
  }
  test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
});

/** Elements whose content changes per run. */
function masks(page: Page) {
  return [page.getByTestId('time-range-preset'), page.getByTestId('error-state-request-id')];
}

test.describe('signed out', () => {
  test.use({ role: null });

  test('T-E2E-055 visual: the sign-in page', { annotation: VISUAL }, async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByTestId('login-form')).toBeVisible();
    await expect(page).toHaveScreenshot('login.png', { fullPage: true });
  });
});

test(
  'T-E2E-055a visual: the shell with a placeholder screen',
  { annotation: VISUAL },
  async ({ page, seed }) => {
    // A relative range: its label ("Last 1 hour") has the same width every day, unlike an
    // absolute one, whose width would move the controls next to the mask.
    await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard?from=now-1h`);
    await expectShell(page);
    await expect(page.getByTestId('route-pending')).toBeVisible();
    await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', '1h');
    await expect(page).toHaveScreenshot('shell-placeholder.png', { mask: masks(page) });
  },
);

test(
  'T-E2E-055b visual: the empty state (unknown application)',
  { annotation: VISUAL },
  async ({ page }) => {
    await page.goto('/apps/999999/dashboard');
    await expect(page.getByTestId('app-not-found')).toBeVisible();
    await expect(page).toHaveScreenshot('empty-state.png', { mask: masks(page) });
  },
);

test(
  'T-E2E-055c visual: the error state (400 answer of the server)',
  { annotation: VISUAL },
  async ({ page }) => {
    await page.goto(`/tx/${OVERFLOW_TX_ID}`);
    await expect(page.getByTestId('error-state')).toBeVisible();
    await expect(page.getByTestId('error-state-request-id')).toBeVisible();
    await expect(page).toHaveScreenshot('error-state.png', { mask: masks(page) });
  },
);

test(
  'T-E2E-055d visual: the loading state with the slow-query hint',
  { annotation: VISUAL },
  async ({ page, seed }) => {
    // The lookup is held back in the browser until the screenshot is taken, then continues to the
    // real server unchanged (a delay, not a substitute answer).
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route('**/api/v1/transactions/**', async (route) => {
      await held;
      await route.continue();
    });
    const tx = seed.tx.captured_sql_stack;
    await page.goto(`/tx/${tx.tx_id}`);
    const loading = page.getByTestId('loading-state');
    await expect(loading).toHaveAttribute('data-slow', 'true');
    await expect(page).toHaveScreenshot('loading-state.png', { mask: masks(page) });
    release();
    await expect(page).toHaveURL(new RegExp(`/apps/${String(tx.app_id)}/transactions/${tx.tx_id}`));
    await page.unroute('**/api/v1/transactions/**');
  },
);
