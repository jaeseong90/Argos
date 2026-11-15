// SPDX-License-Identifier: Apache-2.0
//
// UI helpers of the stack specs, written against the data-testid contract of WO-30 (ui/README.md):
// they drive the shell like a user (clicks, keys) and read the URL state with e2e/fixtures/urlModel.
import { expect, type Locator, type Page } from '@playwright/test';

import { urlState, type TimeState, type UrlState } from './urlModel';

/** The URL state of the page now. */
export function state(page: Page): UrlState {
  return urlState(page.url());
}

/** Waits until the application shell is rendered (top bar and side menu). */
export async function expectShell(page: Page): Promise<void> {
  await expect(page.getByTestId('app-shell')).toBeVisible();
  await expect(page.getByTestId('topbar')).toBeVisible();
}

/** Opens `path` and waits for the shell. */
export async function openShell(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expectShell(page);
}

/**
 * Resolves after the page has rendered two more frames. The router writes the address before React
 * commits the matching render and runs its effects; a user acts on what the screen shows, so the
 * helpers below wait for that before the next click or key.
 */
export async function rendered(page: Page): Promise<void> {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            setTimeout(resolve, 0);
          });
        });
      }),
  );
}

/** Polls until the URL state satisfies `predicate`, then waits until the page rendered it. */
export async function expectUrl(
  page: Page,
  predicate: (s: UrlState) => boolean,
  message: string,
): Promise<UrlState> {
  await expect.poll(() => predicate(state(page)), { message, timeout: 10_000 }).toBe(true);
  await rendered(page);
  return state(page);
}

/** Absolute time state of the page, or a failure naming what it was. */
export function absolute(s: UrlState): { fromMs: number; toMs: number } {
  if (s.time.kind !== 'absolute') {
    throw new Error(
      `expected an absolute range, the URL has ${JSON.stringify(s.time)} (${s.params.toString()})`,
    );
  }
  return { fromMs: s.time.fromMs, toMs: s.time.toMs };
}

/** Whether two time states mean the same range. */
export function sameTime(a: TimeState, b: TimeState): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** Opens the time range picker's popup. */
export async function openTimePicker(page: Page): Promise<Locator> {
  const trigger = page.getByTestId('time-range-preset');
  await trigger.click();
  await expect(trigger).toHaveAttribute('aria-expanded', 'true');
  return trigger;
}

/** Selects a preset (5m ... yesterday) through the picker. */
export async function choosePreset(page: Page, range: string): Promise<void> {
  await openTimePicker(page);
  await page.getByTestId(`time-range-preset-${range}`).click();
}

/** Moves focus away from inputs so that global shortcuts apply (docs/15 §13). */
export async function focusPage(page: Page): Promise<void> {
  await page.evaluate(() => {
    (document.activeElement as HTMLElement | null)?.blur();
  });
}

/** Selects an application in the app selector (AntD Select with search). */
export async function chooseApp(page: Page, appId: number): Promise<void> {
  await page.getByTestId('app-select').click();
  await page.getByTestId(`app-select-option-${String(appId)}`).click();
}

/** The value of `live` the toggle shows. */
export async function liveShown(page: Page): Promise<boolean> {
  return (await page.getByTestId('live-toggle').getAttribute('aria-pressed')) === 'true';
}

/** document.scrollWidth <= clientWidth (no horizontal page scroll, docs/15 §13). */
export async function horizontalOverflow(
  page: Page,
): Promise<{ scrollWidth: number; clientWidth: number }> {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
}
