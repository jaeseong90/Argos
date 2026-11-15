// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the required-elements files (`- check: <name>` in ui/e2e/required/*.yaml): the
// assertions that need more than "visible / URL parameter / text". Each runs after the item's route
// and steps, on the real stack, and asserts exact values (from the URL, the seed manifest or an API
// answer captured in the test).
import { readFileSync } from 'node:fs';

import { expect, type Page, type TestInfo } from '@playwright/test';

import { t } from '../fixtures/i18n';
import {
  absolute,
  chooseApp,
  choosePreset,
  expectShell,
  expectUrl,
  focusPage,
  liveShown,
  openTimePicker,
  sameTime,
  state,
} from '../fixtures/ui';
import { effectiveKind, PRESETS } from '../fixtures/urlModel';
import { XSS_PAYLOADS } from '../fixtures/xssPayloads';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { credentialFile } from '../stack/paths';

import { manifestVariables } from './variables';

export interface CheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly role: Role | null;
}

export type Check = (ctx: CheckContext) => Promise<void>;

const MINUTE = 60_000;

/** Clicks the side-menu link of screen `id` (side-menu-<id>) and waits for its path. */
export async function menuTo(page: Page, id: string, pathSuffix: string): Promise<void> {
  await page.getByTestId(`side-menu-${id}`).click();
  await expectUrl(page, (s) => s.path.endsWith(pathSuffix), `navigated to …${pathSuffix}`);
}

/** 2.0-1: time range, kind, env and live survive screen and app switches. */
const timeRangeKeptAcrossNavigation: Check = async ({ page, seed }) => {
  const before = state(page);
  expect(before.time.kind, 'the item opens an absolute range').toBe('absolute');
  await menuTo(page, 'xlog', '/xlog');
  let after = state(page);
  expect(after.path).toBe(`/apps/${String(seed.primary_app_id)}/xlog`);
  expect(sameTime(after.time, before.time), 'time range after switching the screen').toBe(true);
  expect(effectiveKind(after)).toBe(effectiveKind(before));
  expect(after.live).toBe(before.live);
  await chooseApp(page, seed.secondary_app_id);
  after = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(seed.secondary_app_id)}/xlog`,
    'the app selector keeps the screen and replaces the app id',
  );
  expect(sameTime(after.time, before.time), 'time range after switching the app').toBe(true);
  expect(effectiveKind(after)).toBe(effectiveKind(before));
  expect(after.live).toBe(before.live);
};

/** 2.0-2 (shell part): leaving "now" turns live off; the live toggle brings it back, ending now. */
const liveOffWhenLeavingNow: Check = async ({ page }) => {
  await expect.poll(() => liveShown(page)).toBe(true);
  await page.getByTestId('time-range-prev').click();
  const moved = await expectUrl(
    page,
    (s) => !s.live && s.time.kind === 'absolute',
    'prev turns live off',
  );
  expect(await liveShown(page)).toBe(false);
  expect(moved.params.get('live')).toBeNull();
  await page.getByTestId('live-toggle').click();
  const resumed = await expectUrl(
    page,
    (s) => s.live && s.time.kind === 'relative',
    'live is on again',
  );
  expect(resumed.time).toEqual({ kind: 'relative', range: '15m' });
};

/** 2.0-3: the nine presets, and the buttons and shortcuts of previous / next / zoom / now. */
const presetsAndShortcuts: Check = async ({ page }) => {
  await openTimePicker(page);
  for (const range of PRESETS) {
    await expect(page.getByTestId(`time-range-preset-${range}`), `preset ${range}`).toBeVisible();
  }
  await page.keyboard.press('Escape');
  const start = absolute(state(page));
  const length = start.toMs - start.fromMs;
  const centre = (start.fromMs + start.toMs) / 2;
  await focusPage(page);
  await page.keyboard.press(']');
  let s = await expectUrl(
    page,
    (x) => x.time.kind === 'absolute' && absolute(x).fromMs === start.fromMs + length,
    '] moves one range later',
  );
  expect(absolute(s).toMs).toBe(start.toMs + length);
  await page.keyboard.press('[');
  s = await expectUrl(
    page,
    (x) => x.time.kind === 'absolute' && absolute(x).fromMs === start.fromMs,
    '[ moves back',
  );
  expect(absolute(s).toMs).toBe(start.toMs);
  await page.keyboard.press('+');
  s = await expectUrl(
    page,
    (x) =>
      x.time.kind === 'absolute' &&
      absolute(x).toMs - absolute(x).fromMs === Math.round(length / 2),
    '+ halves the range',
  );
  expect(Math.abs((absolute(s).fromMs + absolute(s).toMs) / 2 - centre)).toBeLessThanOrEqual(1);
  await page.keyboard.press('-');
  s = await expectUrl(
    page,
    (x) => x.time.kind === 'absolute' && absolute(x).toMs - absolute(x).fromMs === length,
    '- doubles the range',
  );
  expect(Math.abs((absolute(s).fromMs + absolute(s).toMs) / 2 - centre)).toBeLessThanOrEqual(1);
  await page.keyboard.press('n');
  s = await expectUrl(
    page,
    (x) => x.time.kind === 'relative',
    'n returns to now with the same length',
  );
  expect(s.time).toEqual({ kind: 'relative', range: `${String(length / MINUTE)}m` });
  // The same moves with the buttons.
  await page.getByTestId('time-range-next').click();
  await expectUrl(page, (x) => x.time.kind === 'absolute', 'next button: absolute range');
  await page.getByTestId('time-range-zoom-in').click();
  await page.getByTestId('time-range-zoom-out').click();
  await page.getByTestId('time-range-now').click();
  await expectUrl(page, (x) => x.time.kind === 'relative', 'now button: relative range');
  await choosePreset(page, '1h');
  await expectUrl(page, (x) => x.time.kind === 'relative' && x.time.range === '1h', 'preset 1h');
};

/** 2.0-6: the kind filter is global (default: requests), the environment filter is global. */
const kindAndEnvGlobal: Check = async ({ page, seed }) => {
  expect(effectiveKind(state(page))).toBe('HTTP');
  await expect(page.getByTestId('kind-filter-http')).toHaveAttribute('aria-checked', 'true');
  await page.getByTestId('kind-filter-batch').click();
  await expectUrl(page, (s) => effectiveKind(s) === 'BATCH', 'kind BATCH in the URL');
  await menuTo(page, 'xlog', '/xlog');
  expect(effectiveKind(state(page)), 'kind after switching the screen').toBe('BATCH');
  await expect(page.getByTestId('kind-filter-batch')).toHaveAttribute('aria-checked', 'true');
  const env = seed.apps.find((a) => a.app_id === seed.primary_app_id)?.env ?? '';
  await expect(page.getByTestId('env-select')).toContainText(env);
};

/** 2.0-7 deep link 1: an anonymous visit returns to the same URL after the login. */
const loginReturnsToDeepLink: Check = async ({ page, seed }) => {
  const vars = manifestVariables(seed);
  const target = `/apps/${vars.primary_app_id ?? ''}/xlog?from=${vars.mid15_from ?? ''}&to=${vars.mid15_to ?? ''}`;
  await expect(page).toHaveURL(/\/login\?/);
  const next = new URL(page.url()).searchParams.get('next');
  expect(next, 'login keeps the original path and query in next').not.toBeNull();
  await page.getByTestId('login-username').fill(seed.users.viewer);
  await page
    .getByTestId('login-password')
    .fill(readFileSync(credentialFile(seed.run_id, 'viewer'), 'utf8'));
  await page.getByTestId('login-submit').click();
  await expectShell(page);
  const s = await expectUrl(
    page,
    (x) => x.path === target.split('?')[0],
    'back on the deep-linked screen',
  );
  expect(s.time).toEqual({
    kind: 'absolute',
    fromMs: Date.parse(vars.mid15_from ?? ''),
    toMs: Date.parse(vars.mid15_to ?? ''),
  });
};

/** 2.0-7 deep link 2: an absolute range is shown as such and live is off. */
const fixedRangeNotLive: Check = async ({ page, seed }) => {
  const vars = manifestVariables(seed);
  const s = state(page);
  expect(s.live).toBe(false);
  expect(absolute(s)).toEqual({
    fromMs: Date.parse(vars.mid15_from ?? ''),
    toMs: Date.parse(vars.mid15_to ?? ''),
  });
  await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', 'absolute');
  expect(await liveShown(page)).toBe(false);
};

/** 2.0-7 deep link 3: the target filters are chips; removing one updates the URL. */
const filtersAreChips: Check = async ({ page }) => {
  const before = state(page).filters;
  expect(before.length, 'the item opens a link with two filters').toBe(2);
  const chips = page.getByTestId('filter-chip');
  await expect(chips).toHaveCount(2);
  for (const [i, f] of before.entries()) {
    await expect(chips.nth(i)).toHaveAttribute('data-field', f.field);
    await expect(chips.nth(i)).toHaveAttribute('data-op', f.op);
    await expect(chips.nth(i).locator('.filter-chip__value')).toHaveText(f.value);
  }
  await chips.nth(0).getByTestId('filter-chip-remove').click();
  const after = await expectUrl(page, (s) => s.filters.length === 1, 'one filter left in the URL');
  expect(after.filters).toEqual([before[1]]);
  await expect(chips).toHaveCount(1);
};

/** The transaction id of the 2.0-8 error item: above 2^64 - 1, so the server answers 400. */
export const OVERFLOW_TX_ID = '99999999999999999999';

/**
 * 2.0-8: an error answer of the real server is an error state with the status, the request id and
 * the next action (retry); the same request through the API gives the same answer.
 */
const statesNextAction: Check = async ({ page, apiAs }) => {
  const error = page.getByTestId('error-state');
  await expect(error).toBeVisible();
  await expect(error).toHaveAttribute('data-error-kind', 'badRequest');
  await expect(error.locator('.state__title')).toHaveText(t('common:component.error.badRequest'));
  await expect(error.getByTestId('error-state-status')).toHaveText(
    t('common:component.error.status', 'en', { status: '400' }),
  );
  const requestId = error.getByTestId('error-state-request-id');
  await expect(requestId).toContainText(
    t('common:component.error.requestId', 'en', { id: '' }).trim(),
  );
  await expect(requestId).toHaveText(/\S{6,}$/);
  await expect(error.getByTestId('error-state-retry')).toBeVisible();
  const api = await apiAs('viewer');
  const answer = await api.request<{ error?: { code?: string; request_id?: string } }>(
    'GET',
    `/transactions/${OVERFLOW_TX_ID}`,
    { expect: [400] },
  );
  expect(answer.body?.error?.code).toBe('invalid_request');
  await api.logout();
};

/** 2.0-8: an unknown application is an empty state whose action leads back to the overview. */
const emptyStateNextAction: Check = async ({ page }) => {
  const empty = page.getByTestId('app-not-found');
  await expect(empty).toContainText(t('common:route.notFound.title'));
  await empty.getByRole('button', { name: t('shell:pending.overview') }).click();
  await expectUrl(page, (s) => s.path === '/overview', 'the action opens the overview');
};

/** 2.0-9: absolute times in the browser time zone, the tooltip names the UTC offset. */
const browserTimeZone: Check = async ({ page }) => {
  const s = absolute(state(page));
  const trigger = page.getByTestId('time-range-preset');
  const offset = await page.evaluate((ms) => -new Date(ms).getTimezoneOffset(), s.fromMs);
  expect(offset, 'this item runs in a non-UTC browser time zone').not.toBe(0);
  const local = await page.evaluate(
    (ms) =>
      new Intl.DateTimeFormat(navigator.language, {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23',
      }).format(ms),
    s.fromMs,
  );
  await expect(trigger).toContainText(local);
  const sign = offset > 0 ? '+' : '−';
  const hh = String(Math.floor(Math.abs(offset) / 60)).padStart(2, '0');
  const mm = String(Math.abs(offset) % 60).padStart(2, '0');
  // Both ends of the tooltip carry the offset, e.g. "(UTC+09:00)".
  const title = (await trigger.getAttribute('title')) ?? '';
  expect(
    title.split(`UTC${sign}${hh}:${mm}`).length - 1,
    `UTC offset in the tooltip: ${title}`,
  ).toBe(2);
};

/** 2.0-10 / T-SEC-64: XSS payloads in deep-link filters render as exact text, nothing runs. */
const collectedTextOnly: Check = async ({ page, testInfo }) => {
  const chips = page.getByTestId('filter-chip');
  // Every payload of the C-45 union is a valid filter value (at most 1 KiB, docs/16 §9.7): each
  // becomes a chip whose text is exactly the payload, in link order.
  await expect(chips).toHaveCount(XSS_PAYLOADS.length);
  const texts = await chips.locator('.filter-chip__value').allTextContents();
  expect(texts).toEqual([...XSS_PAYLOADS]);
  // Only text inside the chips: no element the payloads describe exists there.
  expect(
    await page
      .locator(
        '[data-testid="filter-chip-bar"] img, [data-testid="filter-chip-bar"] svg:not([aria-hidden]), [data-testid="filter-chip-bar"] iframe, [data-testid="filter-chip-bar"] script, [data-testid="filter-chip-bar"] a[href^="javascript"]',
      )
      .count(),
  ).toBe(0);
  // Nothing in the link was invalid, so nothing was restored.
  await expect(page.getByTestId('url-restore-notice')).toHaveCount(0);
  testInfo.annotations.push({
    type: 'xss-payloads',
    description: `${String(XSS_PAYLOADS.length)} payloads rendered as exact text`,
  });
};

/** Messages of i18n keys this file relies on exist (fails early with the missing key). */
export function requireKeys(keys: readonly string[]): void {
  for (const key of keys) {
    t(key);
  }
}

export const CHECKS: Readonly<Record<string, Check>> = {
  timeRangeKeptAcrossNavigation,
  liveOffWhenLeavingNow,
  presetsAndShortcuts,
  kindAndEnvGlobal,
  loginReturnsToDeepLink,
  fixedRangeNotLive,
  filtersAreChips,
  statesNextAction,
  emptyStateNextAction,
  browserTimeZone,
  collectedTextOnly,
};
