// SPDX-License-Identifier: Apache-2.0
//
// Deep links (docs/05 §2.0 딥링크 수락 기준, docs/15 §4.3, docs/14 §6 / ADR-0010 C-166): 2 a fixed
// absolute range is not live and the picker shows it; 3 target filters become chips and removing one
// updates the URL; a tampered URL falls back to defaults with one notice (T-SEC-64). Deep link 1 is
// in auth.spec.ts; 4 and 5 are completed by WO-32 / WO-33 (ADR-0010 C-168 (1)).
import { expect, iso, test } from '../fixtures/stack';
import { absolute, expectShell, expectUrl, liveShown, state } from '../fixtures/ui';
import { deepLinkQuery, effectiveKind } from '../fixtures/urlModel';
import { manifestVariables } from '../required/variables';

const DEEP = { type: 'req', description: 'F-U14' };

test(
  'T-E2E-041 deep link 2: an absolute range opens with live off and the picker shows that range',
  { annotation: DEEP },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    const from = Date.parse(vars.mid15_from ?? '');
    const to = Date.parse(vars.mid15_to ?? '');
    // An alert deep link carries an absolute range and no live (docs/15 §4.3 2).
    await page.goto(
      `/apps/${String(seed.primary_app_id)}/dashboard?${deepLinkQuery({ from: iso(from), to: iso(to), env: vars.primary_env })}`,
    );
    await expectShell(page);
    expect(await liveShown(page)).toBe(false);
    const trigger = page.getByTestId('time-range-preset');
    await expect(trigger).toHaveAttribute('data-range', 'absolute');
    // The tooltip names both ends with the UTC offset (UTC in this browser).
    const title = (await trigger.getAttribute('title')) ?? '';
    const hhmm = (ms: number): string => iso(ms).slice(11, 16);
    expect(title).toContain(hhmm(from));
    expect(title).toContain(hhmm(to));
    expect(title).toContain('UTC');
    expect(absolute(state(page))).toEqual({ fromMs: from, toMs: to });
    expect(state(page).live).toBe(false);
    // Opening the absolute form shows the same instants.
    await trigger.click();
    await expect(page.getByTestId('time-range-absolute')).toBeVisible();
  },
);

test(
  'T-E2E-042 deep link 3: f. filters show as chips; removing one, then clearing, updates the URL',
  { annotation: [DEEP, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    const query = deepLinkQuery({
      from: vars.mid15_from,
      to: vars.mid15_to,
      env: vars.primary_env,
      filters: [
        { field: 'instance', value: vars.primary_instance_id ?? '1' },
        { field: 'service', value: 'GET /api/orders/{id}' },
        { field: 'status', value: '200', exclude: true },
      ],
    });
    await page.goto(`/apps/${String(seed.primary_app_id)}/xlog?${query}`);
    await expectShell(page);
    const chips = page.getByTestId('filter-chip');
    await expect(chips).toHaveCount(3);
    await expect(chips.nth(1).locator('.filter-chip__value')).toHaveText('GET /api/orders/{id}');
    await expect(chips.nth(2)).toHaveAttribute('data-op', 'ne');
    await chips.nth(1).getByTestId('filter-chip-remove').click();
    const s = await expectUrl(page, (x) => x.filters.length === 2, 'two filters left');
    expect(s.filters.map((f) => `${f.field}:${f.op}:${f.value}`)).toEqual([
      `instance:eq:${vars.primary_instance_id ?? '1'}`,
      'status:ne:200',
    ]);
    // Removing filters keeps the time range (docs/15 §5: only the filters change).
    expect(absolute(s)).toEqual({
      fromMs: Date.parse(vars.mid15_from ?? ''),
      toMs: Date.parse(vars.mid15_to ?? ''),
    });
    await page.getByTestId('filter-chip-clear').click();
    await expectUrl(page, (x) => x.filters.length === 0, 'no filter left');
    await expect(chips).toHaveCount(0);
    // Back restores the previous filters (filter changes are history entries, docs/15 §4.2).
    await page.goBack();
    await expectUrl(page, (x) => x.filters.length === 2, 'back restores the two filters');
  },
);

test(
  'T-SEC-64 a tampered URL opens with defaults for the broken items and one notice',
  { tag: '@security', annotation: [DEEP, { type: 'req', description: 'SEC-64' }] },
  async ({ page, seed }) => {
    const long = 'x'.repeat(1100);
    const params = new URLSearchParams();
    params.set('from', 'yesterday-ish');
    params.set('to', '2000-13-45T99:99:99Z');
    params.set('kind', 'DROP TABLE');
    params.set('live', 'yes');
    params.append('f.service', long);
    params.append('f.Bad-Field', 'x');
    params.append('f.instance', '7');
    await page.goto(`/apps/${String(seed.primary_app_id)}/dashboard?${params.toString()}`);
    await expectShell(page);
    await expect(page.getByTestId('url-restore-notice')).toBeVisible();
    await expect(page.getByTestId('url-restore-notice')).toHaveCount(1);
    // Invalid items fell back to their defaults; the valid filter stayed (each item on its own).
    await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', '15m');
    await expect(page.getByTestId('kind-filter-http')).toHaveAttribute('aria-checked', 'true');
    expect(await liveShown(page)).toBe(false);
    const chips = page.getByTestId('filter-chip');
    await expect(chips).toHaveCount(1);
    await expect(chips.first()).toHaveAttribute('data-field', 'instance');
    await expect(chips.first().locator('.filter-chip__value')).toHaveText('7');
    // Whatever the address bar keeps, the state the UI writes back holds none of the broken items.
    await page.getByTestId('time-range-preset').click();
    await page.getByTestId('time-range-preset-1h').click();
    const s = await expectUrl(
      page,
      (x) => x.time.kind === 'relative' && x.time.range === '1h',
      'preset 1h written',
    );
    expect(effectiveKind(s)).toBe('HTTP');
    expect(s.live).toBe(false);
    expect(s.filters).toEqual([{ field: 'instance', op: 'eq', value: '7' }]);
    // The over-long value never reached the page.
    expect(await page.content()).not.toContain(long);
  },
);

test(
  'T-SEC-64a a query over 8 KB reached inside the app is ignored as a whole: defaults and the notice',
  { tag: '@security', annotation: [DEEP, { type: 'req', description: 'SEC-64' }] },
  async ({ page, seed }) => {
    const vars = manifestVariables(seed);
    const path = `/apps/${String(seed.primary_app_id)}/dashboard`;
    await page.goto(`${path}?${deepLinkQuery({ from: vars.mid15_from, to: vars.mid15_to })}`);
    await expectShell(page);
    await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', 'absolute');
    // A page load of such an address never reaches the UI: the server refuses request lines over
    // 8 KB (HTTP 400). The UI's own limit (docs/15 §4.2) applies to addresses it reaches by
    // history navigation, which is what this test does.
    const params = new URLSearchParams();
    params.set('from', vars.mid15_from ?? '');
    params.set('to', vars.mid15_to ?? '');
    for (let i = 0; i < 9; i += 1) {
      params.append('f.service', 'y'.repeat(1000));
    }
    const long = `${path}?${params.toString()}`;
    expect(long.length).toBeGreaterThan(8192);
    await page.evaluate((url) => {
      window.history.pushState(null, '', url);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, long);
    await expect(page.getByTestId('url-restore-notice')).toBeVisible();
    await expect(page.getByTestId('filter-chip')).toHaveCount(0);
    await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', '15m');
    expect(await page.content()).not.toContain('y'.repeat(1000));
  },
);
