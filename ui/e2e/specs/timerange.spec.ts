// SPDX-License-Identifier: Apache-2.0
//
// The global time range (docs/05 §2.0-1..3, docs/15 §5, DSN-15-02): presets, absolute input,
// previous / next / zoom / now by buttons and shortcuts, live toggle, and what survives a switch of
// application or screen (time, kind, env, live) and what does not (screen keys such as sel, tab).
// Every expectation is computed from the range the test opened (absolute times of the seed
// manifest), never from a calendar date.
import type { Page } from '@playwright/test';

import { expect, iso, test } from '../fixtures/stack';
import {
  absolute,
  chooseApp,
  choosePreset,
  expectShell,
  expectUrl,
  focusPage,
  liveShown,
  sameTime,
  state,
} from '../fixtures/ui';
import { effectiveKind, PRESETS } from '../fixtures/urlModel';
import { manifestVariables } from '../required/variables';

const TIME = { type: 'req', description: 'F-U16' };
const MINUTE = 60_000;

async function openAbsolute(
  page: Page,
  appId: number,
  from: string,
  to: string,
  extra = '',
): Promise<void> {
  await page.goto(`/apps/${String(appId)}/dashboard?from=${from}&to=${to}${extra}`);
  await expectShell(page);
}

test(
  'T-E2E-047 every preset sets a relative range; live stays on for ranges that end now',
  { annotation: TIME },
  async ({ page, seed }) => {
    await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard?from=now-15m&live=1`);
    await expectShell(page);
    expect(await liveShown(page)).toBe(true);
    for (const range of PRESETS) {
      await choosePreset(page, range);
      const s = await expectUrl(
        page,
        (x) => x.time.kind === 'relative' && x.time.range === range,
        `preset ${range}`,
      );
      await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', range);
      // docs/15 §5: a preset keeps live, except a range that does not end now (yesterday).
      expect(s.live, `live after ${range}`).toBe(range !== 'yesterday');
      if (range === 'yesterday') {
        await page.getByTestId('live-toggle').click();
        await expectUrl(page, (x) => x.live, 'live on again');
      }
    }
  },
);

test(
  'T-E2E-048 previous, next, zoom in, zoom out and now: buttons and shortcuts move the range exactly',
  { annotation: TIME },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    const from = Date.parse(vars.mid15_from ?? '');
    const to = Date.parse(vars.mid15_to ?? '');
    const length = to - from;
    await openAbsolute(page, seed.primary_app_id, iso(from), iso(to));
    const expectRange = async (a: number, b: number, what: string): Promise<void> => {
      await expectUrl(
        page,
        (x) => x.time.kind === 'absolute' && absolute(x).fromMs === a && absolute(x).toMs === b,
        `${what}: ${iso(a)} .. ${iso(b)}`,
      );
    };
    await page.getByTestId('time-range-next').click();
    await expectRange(from + length, to + length, 'next');
    await page.getByTestId('time-range-prev').click();
    await expectRange(from, to, 'prev');
    await page.getByTestId('time-range-zoom-in').click();
    await expectRange(from + length / 4, to - length / 4, 'zoom in (half, same centre)');
    await page.getByTestId('time-range-zoom-out').click();
    await expectRange(from, to, 'zoom out (double, same centre)');
    await focusPage(page);
    await page.keyboard.press(']');
    await expectRange(from + length, to + length, 'shortcut ]');
    await page.keyboard.press('[');
    await expectRange(from, to, 'shortcut [');
    await page.keyboard.press('+');
    await expectRange(from + length / 4, to - length / 4, 'shortcut +');
    await page.keyboard.press('-');
    await expectRange(from, to, 'shortcut -');
    await page.keyboard.press('n');
    let s = await expectUrl(page, (x) => x.time.kind === 'relative', 'shortcut n: back to now');
    expect(s.time).toEqual({ kind: 'relative', range: `${String(length / MINUTE)}m` });
    await page.keyboard.press('l');
    await expectUrl(page, (x) => x.live, 'shortcut l: live on');
    await page.keyboard.press('l');
    await expectUrl(page, (x) => !x.live, 'shortcut l: live off');
    await openAbsolute(page, seed.primary_app_id, iso(from), iso(to));
    await page.getByTestId('time-range-now').click();
    s = await expectUrl(page, (x) => x.time.kind === 'relative', 'now button');
    expect(s.time).toEqual({ kind: 'relative', range: `${String(length / MINUTE)}m` });
  },
);

test(
  'T-E2E-048a an absolute range typed in the picker is applied in the browser time zone and turns live off',
  { annotation: TIME },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    const from = Date.parse(vars.mid15_from ?? '') - 10 * MINUTE;
    const to = from + 20 * MINUTE;
    await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard?from=now-15m&live=1`);
    await expectShell(page);
    await page.getByTestId('time-range-preset').click();
    const local = (ms: number): string => iso(ms).slice(0, 19); // the browser runs in UTC
    await page.getByTestId('time-range-absolute-from').fill(local(from));
    await page.getByTestId('time-range-absolute-to').fill(local(to));
    await expect(page.getByTestId('time-range-offset')).toContainText('UTC');
    await page.getByTestId('time-range-absolute-apply').click();
    const s = await expectUrl(page, (x) => x.time.kind === 'absolute', 'absolute range applied');
    expect(absolute(s)).toEqual({ fromMs: from, toMs: to });
    expect(s.live).toBe(false);
  },
);

test(
  'T-E2E-049 switching the application and the screen keeps time, kind, env and live; screen keys reset',
  { annotation: [TIME, { type: 'req', description: 'F-U14' }] },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    await openAbsolute(
      page,
      seed.primary_app_id,
      vars.mid15_from ?? '',
      vars.mid15_to ?? '',
      '&kind=BATCH',
    );
    const before = state(page);
    // XLog with its own keys (selection, colour) ...
    await page.getByTestId('side-menu-xlog').click();
    let s = await expectUrl(page, (x) => x.path.endsWith('/xlog'), 'XLog screen');
    expect(sameTime(s.time, before.time)).toBe(true);
    expect(effectiveKind(s)).toBe('BATCH');
    const sel = `ms:0-500,ts:${String(Date.parse(vars.mid15_from ?? ''))}-${String(Date.parse(vars.mid15_from ?? '') + MINUTE)}`;
    await page.goto(
      `${s.path}?${s.params.toString()}&sel=${encodeURIComponent(sel)}&color=service`,
    );
    await expectShell(page);
    // ... then another application: same screen, same time, kind and live; sel and color dropped.
    await chooseApp(page, seed.secondary_app_id);
    s = await expectUrl(
      page,
      (x) => x.path === `/apps/${String(seed.secondary_app_id)}/xlog`,
      'same screen of the other app',
    );
    expect(sameTime(s.time, before.time)).toBe(true);
    expect(effectiveKind(s)).toBe('BATCH');
    expect(s.live).toBe(before.live);
    expect(s.params.get('sel')).toBeNull();
    expect(s.params.get('color')).toBeNull();
    // A global screen keeps the shared keys too.
    await page.getByTestId('side-menu-overview').click();
    s = await expectUrl(page, (x) => x.path === '/overview', 'overview');
    expect(sameTime(s.time, before.time)).toBe(true);
    expect(effectiveKind(s)).toBe('BATCH');
  },
);
