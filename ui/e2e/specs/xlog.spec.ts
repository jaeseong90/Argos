// SPDX-License-Identifier: Apache-2.0
//
// XLog screen on the seeded stack (WO-33, docs/05 §2.1, docs/15 §9.3, docs/18 §13): the drag ->
// selection summary -> server transaction list -> detail flow with every number checked against the
// API asked the same question, the summary row filter that narrows the scatter and the list
// (ADR-0010 C-202), collected strings as text, the 1366x768 layout, accessibility and the visual
// baselines of the loading, empty, error and normal states. The checklist items of docs/05 §2.1 are
// data-driven in e2e/required/xlog.yaml (T-E2E-xlog-<n>); these tests carry WO-33's T-E2E ids.
import type { Page } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import { absolute, expectUrl, horizontalOverflow, state } from '../fixtures/ui';
import {
  apiPoints,
  apiSearch,
  apiSummary,
  dragSelect,
  EXPECTATION_ROLE,
  expectReady,
  expectSelectionShown,
  intAttr,
  listKeys,
  openXLog,
  plotBox,
  searchBody,
  selOf,
  selText,
  unsampledWindow,
  xlogPath,
} from '../fixtures/xlogPage';
import { XSS_PAYLOADS } from '../fixtures/xssPayloads';
import type { ApiSession } from '../stack/api';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { manifestApp, type SeedManifest } from '../stack/manifest';

const XLOG = { type: 'req', description: 'F-U02' };

/** True inside the pinned Linux image of e2e:docker (screenshots.spec.ts, the only baselines). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}
const MINUTE = 60_000;

function req(...ids: string[]): { type: string; description: string }[] {
  return [XLOG, ...ids.map((description) => ({ type: 'req', description }))];
}

/**
 * Hovers the point drawn at time `ts` of the plot of range [from, to): its column is exact (the
 * time axis is linear over the plot width), the response time is found by moving up that column
 * in 3 px steps (the hover radius is 6 px) until the tooltip shows.
 */
async function hoverPointAt(page: Page, ts: number, from: number, to: number): Promise<void> {
  const box = await plotBox(page);
  const x = box.x + ((ts - from) / (to - from)) * box.width;
  const tooltip = page.getByTestId('xlog-tooltip');
  for (let y = box.y + box.height - 1; y >= box.y; y -= 3) {
    await page.mouse.move(x, y);
    if (await tooltip.isVisible()) {
      return;
    }
  }
  throw new Error(`no point under the pointer in the column of ${new Date(ts).toISOString()}`);
}

/** An unsampled window of the primary app before the end of the generator range. */
async function window(
  api: ApiSession,
  seed: SeedManifest,
): Promise<{ appId: number; from: string; to: string; total: number }> {
  const end = Math.floor(Date.parse(seed.generator.range.end) / MINUTE) * MINUTE - 2 * MINUTE;
  return { appId: seed.primary_app_id, ...(await unsampledWindow(api, seed.primary_app_id, end)) };
}

test(
  'T-E2E-070 drag -> sel -> summary equals the API -> list by elapsed descending, top 1,000 -> Enter -> detail with ts',
  { annotation: req('05-2.1-2', '05-2.1-8', '05-2.1-9') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(EXPECTATION_ROLE);
    const w = await window(api, seed);
    await openXLog(page, xlogPath(w.appId, w.from, w.to));
    await expect(page.getByTestId('xlog-page')).toHaveAttribute(
      'data-point-count',
      String(w.total),
    );
    await expect(page.getByTestId('xlog-summary-empty')).toBeVisible();
    await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-state', 'idle');

    // Drag over the whole plot: the selection is URL state (docs/14 §6 sel).
    const sel = await dragSelect(page);
    const selectedAt = Date.now();
    await expect(page.getByTestId('xlog-summary')).toHaveAttribute('data-sel', selText(sel));
    const summaryAt = Date.now();
    await expectSelectionShown(page, sel);
    // Reference only (shared laptop, INVALID; ADR-0010 C-204 judges on a quiet machine): from the
    // selection in the URL to the summary numbers and to the list rows on screen.
    testInfo.annotations.push({
      type: 'c36-reference',
      description: `summary ${String(summaryAt - selectedAt)} ms, list ${String(Date.now() - selectedAt)} ms after sel= (end-to-end through the real server)`,
    });

    // The summary counts what the server counts for the same bounds (the window is not sampled).
    const summary = await apiSummary(api, w.appId, sel, 'HTTP');
    const search = await apiSearch(api, w.appId, searchBody(sel, 'HTTP'));
    expect(search.meta.total).toBe(summary.count);
    await expect(page.getByTestId('xlog-summary-count')).toHaveAttribute(
      'data-count',
      String(summary.count),
    );
    await expect(page.getByTestId('xlog-summary-server-count')).toHaveAttribute(
      'data-count',
      String(summary.count),
    );

    // The list: the search's first page (elapsed descending, at most 1,000) and "top 1,000".
    const list = page.getByTestId('xlog-list');
    await expect(list).toHaveAttribute('data-rows', String(search.data.length));
    expect(search.data.length).toBeLessThanOrEqual(1000);
    const elapsed = search.data.map((r) => r.elapsed_ms);
    expect(elapsed).toEqual([...elapsed].sort((a, b) => b - a));
    const keys = await listKeys(page);
    expect(keys).toEqual(search.data.slice(0, keys.length).map((r) => r.tx_id));
    expect(summary.count, 'the drag selects more than one page').toBeGreaterThan(1000);
    await expect(page.getByTestId('xlog-list-top')).toHaveAttribute(
      'data-total',
      String(search.meta.total),
    );
    await expect(page.getByTestId('xlog-list-top')).toHaveAttribute('data-shown', '1000');

    // Enter on the first row: the canonical detail route with the ts hint and the back link.
    const back = new URL(page.url()).pathname + new URL(page.url()).search;
    const first = search.data[0];
    expect(first).toBeDefined();
    await list.getByTestId('data-table-row').first().focus();
    await page.keyboard.press('Enter');
    const detail = await expectUrl(
      page,
      (s) => s.path === `/apps/${String(w.appId)}/transactions/${first?.tx_id ?? ''}`,
      'Enter opens the detail',
    );
    expect(Date.parse(detail.params.get('ts') ?? '')).toBe(Date.parse(first?.ts ?? ''));
    expect(detail.params.get('back')).toBe(back);
  },
);

test(
  'T-E2E-072 keyboard selection: focus the plot, Home, Enter -> sel; summary and list equal the API, and a reload shows the same',
  { annotation: req('05-2.1-2', 'F-U16') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(EXPECTATION_ROLE);
    const w = await window(api, seed);
    await openXLog(page, xlogPath(w.appId, w.from, w.to));
    await expect(page.getByTestId('xlog-page')).toHaveAttribute(
      'data-point-count',
      String(w.total),
    );
    // docs/15 §13, WO-04 C-12: the plot takes the focus, Home puts the time band at the start of
    // the range and Enter selects it: the same sel= as a drag, over every response time.
    await page.getByTestId('xlog-plot').focus();
    await page.keyboard.press('Home');
    await page.keyboard.press('Enter');
    await expect.poll(() => selOf(page), { message: 'sel= written by Enter' }).not.toBeNull();
    const sel = selOf(page);
    if (sel === null) {
      throw new Error(`no valid sel in ${page.url()}`);
    }
    expect(sel.msMin).toBe(0);
    expect(sel.msMax).toBe(4_294_967_295);
    expect(sel.tsFrom).toBeGreaterThanOrEqual(Date.parse(w.from));
    expect(sel.tsTo).toBeLessThan(Date.parse(w.to));
    await expectSelectionShown(page, sel);

    // The summary and the list are those of the server for the same bounds (unsampled window).
    const summary = await apiSummary(api, w.appId, sel, 'HTTP');
    expect(summary.count, 'the band at the start of the window has transactions').toBeGreaterThan(
      0,
    );
    const search = await apiSearch(api, w.appId, searchBody(sel, 'HTTP'));
    expect(search.meta.total).toBe(summary.count);
    const count = page.getByTestId('xlog-summary-count');
    await expect(count).toHaveAttribute('data-count', String(summary.count));
    await expect(page.getByTestId('xlog-list')).toHaveAttribute(
      'data-rows',
      String(search.data.length),
    );
    const keys = await listKeys(page);
    expect(keys.length).toBeGreaterThan(0);
    expect(keys).toEqual(search.data.slice(0, keys.length).map((r) => r.tx_id));

    // A reload keeps the selection (URL state): the same count and the same rows.
    await page.reload();
    await expectReady(page);
    expect(selOf(page)).toEqual(sel);
    await expectSelectionShown(page, sel);
    await expect(count).toHaveAttribute('data-count', String(summary.count));
    expect(await listKeys(page)).toEqual(keys);
  },
);

test.describe('as operator', () => {
  // The transaction search is HEAVY (10 per user per minute): spread over the seeded users.
  test.use({ role: 'operator' });

  test(
    'T-E2E-071 a summary row filters the scatter and the list (f= chip), the selection stays',
    { annotation: req('05-2.1-2', '05-2.1-6') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      await openXLog(page, xlogPath(w.appId, w.from, w.to));
      const sel = await dragSelect(page, 0.25, 0.75);
      await expectSelectionShown(page, sel);
      const instanceRow = page.getByTestId('xlog-summary-instance-row').first();
      const instanceId = (await instanceRow.getAttribute('data-instance-id')) ?? '';
      expect(instanceId).toMatch(/^\d+$/);
      const points = page.waitForRequest(
        (r) =>
          r.method() === 'GET' &&
          r.url().includes(`/api/v1/apps/${String(w.appId)}/xlog?`) &&
          new URL(r.url()).searchParams.getAll('instance').includes(instanceId),
      );
      await instanceRow.getByRole('button').click();
      const after = await expectUrl(
        page,
        (s) =>
          s.filters.some((f) => f.field === 'instance' && f.op === 'eq' && f.value === instanceId),
        'f.instance from the summary row',
      );
      expect(after.params.get('sel')).toBe(selText(sel));
      // The chip names the instance (the points' dictionary, routes/filterLabels.tsx); the key
      // stays in the URL and in data-value.
      const instanceName = manifestApp(seed, w.appId).instances.find(
        (i) => String(i.instance_id) === instanceId,
      )?.name;
      expect(instanceName, `instance ${instanceId} in the seed manifest`).toBeDefined();
      const chip = page.locator('[data-testid="filter-chip"][data-field="instance"]').first();
      await expect(chip).toHaveAttribute('data-value', instanceId);
      await expect(chip.locator('.filter-chip__value')).toHaveText(instanceName ?? '');
      // The scatter is asked again with the instance and draws what the API returns.
      await points;
      const expected = await apiPoints(api, w.appId, {
        from: w.from,
        to: w.to,
        kind: 'HTTP',
        limit: 100_000,
        instance: [instanceId],
      });
      await expectReady(page);
      await expect(page.getByTestId('xlog-page')).toHaveAttribute(
        'data-point-count',
        String(expected.meta.shown),
      );
      // Summary and list follow the filter.
      const summary = await apiSummary(api, w.appId, sel, 'HTTP', {
        instance: [Number(instanceId)],
      });
      await expect(page.getByTestId('xlog-summary-count')).toHaveAttribute(
        'data-count',
        String(summary.count),
      );
      // The search total equals the summary count of the same bounds and filters (T-E2E-070): the
      // list holds its first page, at most 1,000 rows.
      await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-state', 'ready');
      await expect(page.getByTestId('xlog-list')).toHaveAttribute(
        'data-rows',
        String(Math.min(1000, summary.count)),
      );
      // Clicking the pressed row again removes the filter.
      await page
        .locator(
          `[data-testid="xlog-summary-instance-row"][data-instance-id="${instanceId}"] button`,
        )
        .click();
      await expectUrl(
        page,
        (s) => s.filters.length === 0,
        'the filter removed by the second click',
      );
    },
  );
});

test(
  'T-E2E-073 collected strings render as text: the XSS seed service name in the legend, chip, tooltip, summary and every list row; XSS link values in chips and notices',
  { annotation: req('SEC-60', 'SEC-61', 'SEC-62', 'SEC-64') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(EXPECTATION_ROLE);
    const appId = seed.traffic.app_id;
    const { start, end } = seed.traffic.range;
    const points = await apiPoints(api, appId, {
      from: start,
      to: end,
      kind: 'ALL',
      limit: 100_000,
    });
    const xssService = seed.traffic.xss_service;
    expect(xssService, 'the seed traffic has its XSS seed service').not.toBeNull();
    const name = xssService ?? '';
    const serviceNames = new Set(Object.values(points.data.dictionary.service));
    // The XSS seed service's key in the API's dictionary (opaque decimal string, C-175).
    const serviceKey =
      Object.entries(points.data.dictionary.service).find(([, n]) => n === name)?.[0] ?? '';
    expect(serviceKey, `the key of service ${name} in the API dictionary`).toMatch(/^\d{1,20}$/);

    // Colour by service, unfiltered: every legend label is a collected name or "Other".
    await openXLog(page, xlogPath(appId, start, end, { kind: 'ALL', color: 'service' }));
    const other = t('xlog:legend.other');
    const legend = await page
      .getByTestId('xlog-legend')
      .locator('.xlog-legend__label')
      .allTextContents();
    for (const label of legend) {
      expect(label === other || serviceNames.has(label), `legend ${label}`).toBe(true);
    }

    // Filtered to the XSS seed service: its name verbatim everywhere it is shown.
    await openXLog(
      page,
      xlogPath(appId, start, end, { kind: 'ALL', color: 'service', 'f.service': serviceKey }),
    );
    const filtered = await apiPoints(api, appId, {
      from: start,
      to: end,
      kind: 'ALL',
      limit: 100_000,
      service: [serviceKey],
    });
    await expect(page.getByTestId('xlog-page')).toHaveAttribute(
      'data-point-count',
      String(filtered.meta.shown ?? -1),
    );
    await expect(page.getByTestId('xlog-legend').locator('.xlog-legend__label')).toHaveText([name]);
    const chip = page.locator('[data-testid="filter-chip"][data-field="service"]');
    await expect(chip).toHaveCount(1);
    await expect(chip).toHaveAttribute('data-value', serviceKey);
    await expect(chip.locator('.filter-chip__value')).toHaveText(name);
    // The hover tooltip of one of its points.
    const ts = filtered.data.ts[0];
    expect(ts, 'a point of the XSS seed service').toBeDefined();
    await hoverPointAt(page, ts ?? 0, Date.parse(start), Date.parse(end));
    await expect(page.getByTestId('xlog-tooltip').getByTestId('xlog-tooltip-service')).toHaveText(
      name,
    );
    // Summary and list of the whole window: the one service, every list row with its name.
    const sel = await dragSelect(page);
    await expectSelectionShown(page, sel);
    const summary = await apiSummary(api, appId, sel, 'ALL', { service: [serviceKey] });
    const serviceRows = page.getByTestId('xlog-summary-service-row');
    await expect(serviceRows).toHaveCount(1);
    await expect(serviceRows).toHaveAttribute('data-service-id', serviceKey);
    await expect(serviceRows.locator('.safe-text')).toHaveText(name);
    await expect(page.getByTestId('xlog-summary-count')).toHaveAttribute(
      'data-count',
      String(summary.count),
    );
    const search = await apiSearch(
      api,
      appId,
      searchBody(sel, 'ALL', { service_id: [serviceKey] }),
    );
    expect(search.meta.total).toBe(summary.count);
    const list = page.getByTestId('xlog-list');
    await expect(list).toHaveAttribute('data-rows', String(search.data.length));
    const keys = await listKeys(page);
    expect(keys).toEqual(search.data.slice(0, keys.length).map((r) => r.tx_id));
    const services = await list.locator('[data-column="service"] .safe-text').allTextContents();
    expect(services).toEqual(keys.map(() => name));
    expect(
      await page
        .locator(
          '[data-testid="xlog-page"] img, [data-testid="xlog-page"] iframe, [data-testid="xlog-page"] script',
        )
        .count(),
    ).toBe(0);
    expect(await page.evaluate(() => (globalThis as { __xss?: unknown }).__xss)).toBeUndefined();

    // XSS payloads from a link: chips and the "not applied" notices show them as text only.
    const params = new URLSearchParams({ from: start, to: end, kind: 'ALL', sel: selText(sel) });
    XSS_PAYLOADS.forEach((payload, i) => {
      params.append(i % 2 === 0 ? 'f.user' : 'f.request', payload);
    });
    await openXLog(page, `/apps/${String(appId)}/xlog?${params.toString()}`);
    const chips = await page
      .locator('[data-testid="filter-chip"] .filter-chip__value')
      .allTextContents();
    for (const payload of XSS_PAYLOADS) {
      expect(chips, 'chip of a payload').toContain(payload);
    }
    const notice = page.getByTestId('xlog-list-unapplied');
    await expect(notice.locator('li')).toHaveCount(XSS_PAYLOADS.length);
    const values = await notice.locator('li .safe-text').allTextContents();
    expect(new Set(values)).toEqual(new Set(XSS_PAYLOADS));
    expect(
      await page
        .locator(
          '[data-testid="xlog-page"] img, [data-testid="xlog-page"] iframe, [data-testid="xlog-page"] script',
        )
        .count(),
    ).toBe(0);
    // Payloads inside service, instance or error class names are not part of the seed yet.
    testInfo.annotations.push({
      type: 'fixme',
      description:
        'XSS payloads as service/instance/error class names: the seed traffic carries its payloads in URL, header, SQL, thread and outgoing call fields only (WO-46 extends the seed); component tests T-UI-005 cover these columns',
    });
  },
);

test.describe('layout and accessibility as operator', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-075 1366x768: the XLog screen with a selection, summary and list scrolls only vertically',
    { annotation: req('05-2.1-2', 'F-U16') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      await openXLog(page, xlogPath(w.appId, w.from, w.to, { color: 'service' }));
      const sel = await dragSelect(page);
      await expectSelectionShown(page, sel);
      // Right after the drag, without scrolling, the list's header and its first row are on the
      // first screen next to the summary (docs/15 §9.3 "선택 요약 ┤ 트랜잭션 목록", qa-ux-sec-6).
      expect(await page.evaluate(() => globalThis.scrollY), 'the page is not scrolled').toBe(0);
      const list = page.getByTestId('xlog-list');
      const listHeader = await list.locator('thead').boundingBox();
      expect(listHeader?.y ?? 9999, 'list header top').toBeLessThan(768);
      const firstRow = await list.getByTestId('data-table-row').first().boundingBox();
      expect(firstRow, 'first list row').not.toBeNull();
      expect(
        (firstRow?.y ?? 9999) + (firstRow?.height ?? 0),
        'first list row bottom <= 768',
      ).toBeLessThanOrEqual(768);
      const { scrollWidth, clientWidth } = await horizontalOverflow(page);
      expect(scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(clientWidth);
      // The chart and the summary title are on the first screen.
      const viewport = page.viewportSize();
      expect(viewport).toEqual({ width: 1366, height: 768 });
      const summaryTop = (await page.getByTestId('xlog-summary').boundingBox())?.y ?? 9999;
      expect(summaryTop).toBeLessThan(768);
    },
  );

  // Pending a PM decision (WO-33 report): docs/15 §13 forbids a sideways scroll at 1366 px, and
  // the review asked that it holds inside the list's own box too (qa-ux-sec-7). With the summary
  // beside the list (docs/15 §9.3 "선택 요약 ┤ 트랜잭션 목록", qa-ux-sec-6, T-E2E-075 above) the
  // list box is about 671 px wide, and its 13 default columns of 05 §2.1-8 need 1,280 px (1,392 px
  // with the user id); even spanning the content (1,132 px) they do not fit. Whether the table may
  // scroll sideways inside its box, the default cols= shrinks or the layout changes is the PM's
  // call; the check below is the reviewer's, unchanged, and runs once the decision allows it.
  test.fixme(
    'T-E2E-075b 1366x768: the transaction list box shows every default column without a sideways scroll inside it (fixme: PM decision on docs/15 §13 inside a table box, WO-33)',
    { annotation: req('05-2.1-8', 'F-U16') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      await openXLog(page, xlogPath(w.appId, w.from, w.to, { color: 'service' }));
      const sel = await dragSelect(page);
      await expectSelectionShown(page, sel);
      const listBox = await page
        .getByTestId('xlog-list')
        .getByRole('table')
        .evaluate((table) => {
          const box = table.parentElement;
          return { scrollWidth: box?.scrollWidth ?? -1, clientWidth: box?.clientWidth ?? -1 };
        });
      expect(listBox.clientWidth, 'list scroll box width').toBeGreaterThan(0);
      expect(
        listBox.scrollWidth,
        'list scroll box: scrollWidth <= clientWidth',
      ).toBeLessThanOrEqual(listBox.clientWidth);
    },
  );

  test(
    'T-E2E-077 accessibility: no critical axe-core violation on the XLog screen with a selection',
    { annotation: req('F-U16') },
    async ({ page, seed, apiAs }, testInfo) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      await openXLog(page, xlogPath(w.appId, w.from, w.to));
      await expectNoCriticalA11y(page, testInfo, 'xlog');
      const sel = await dragSelect(page);
      await expectSelectionShown(page, sel);
      await expectNoCriticalA11y(page, testInfo, 'xlog-selection');
    },
  );
});

test.describe('visual', () => {
  test.use({ role: 'operator' });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: 'baselines are made in the pinned Linux image only (corepack pnpm e2e:docker)',
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /** Per-run values: the range label, clock times on the axis and in the list, request ids. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('xlog-canvas'),
      page.locator('[data-column="ts"]'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-076a visual: the XLog screen while its points load',
    { annotation: req('F-U16') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      // The points request is held until the screenshot is taken, then continues unchanged.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/apps/*/xlog?**', async (route) => {
        await held;
        await route.continue();
      });
      await page.goto(xlogPath(w.appId, w.from, w.to));
      // The slow-query hint appears after 2 s (LoadingState): wait for it, so the image is stable.
      await expect(page.getByTestId('xlog-loading')).toHaveAttribute('data-slow', 'true');
      await expect(page).toHaveScreenshot('xlog-loading.png', { mask: masks(page) });
      release();
      await expectReady(page);
      await page.unroute('**/api/v1/apps/*/xlog?**');
    },
  );

  test(
    'T-E2E-076b visual: the XLog screen of a window without points',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const start = Date.parse(seed.generator.range.start);
      await page.goto(
        xlogPath(
          seed.primary_app_id,
          new Date(start - 30 * MINUTE).toISOString(),
          new Date(start - 15 * MINUTE).toISOString(),
        ),
      );
      await expect(page.getByTestId('xlog-empty')).toBeVisible();
      await expect(page).toHaveScreenshot('xlog-empty.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-076c visual: the XLog screen for a range over the points API limit (48 h): shorter ranges offered, nothing asked',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed }) => {
      const end = Date.parse(seed.generator.range.end);
      const points: string[] = [];
      page.on('request', (request) => {
        if (/\/api\/v1\/apps\/\d+\/xlog\?/.test(request.url())) {
          points.push(request.url());
        }
      });
      await page.goto(
        xlogPath(
          seed.primary_app_id,
          new Date(end - 48 * 60 * MINUTE).toISOString(),
          new Date(end).toISOString(),
        ),
      );
      // The error state of the screen: the next actions instead of a refused request.
      const notice = page.getByTestId('xlog-range-limit');
      await expect(notice).toBeVisible();
      await expect(notice.getByTestId('xlog-range-last24h')).toBeVisible();
      await expect(notice.getByTestId('xlog-range-last1h')).toBeVisible();
      expect(points, 'no points request for 48 hours').toEqual([]);
      await expect(page).toHaveScreenshot('xlog-error.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-076d visual: the XLog screen with a selection, its summary and list',
    { annotation: req('F-U16') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(EXPECTATION_ROLE);
      const w = await window(api, seed);
      await openXLog(page, xlogPath(w.appId, w.from, w.to));
      const sel = await dragSelect(page);
      await expectSelectionShown(page, sel);
      await expect(page.getByTestId('xlog-summary')).toHaveAttribute('data-server-state', 'ready');
      expect(await intAttr(page.getByTestId('xlog-summary-count'), 'data-count')).toBeGreaterThan(
        0,
      );
      await expect(page).toHaveScreenshot('xlog-normal.png', { mask: masks(page), fullPage: true });
    },
  );
});

/** Requests the page sends for the scatter's points (GET .../xlog?..., not .../xlog/summary). */
function recordPointRequests(page: Page): URL[] {
  const sent: URL[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (request.method() === 'GET' && /^\/api\/v1\/apps\/\d+\/xlog$/.test(url.pathname)) {
      sent.push(url);
    }
  });
  return sent;
}

test(
  'T-E2E-079 a range over 24 hours (48 h link): nothing asked, "Last 24 hours" asks exactly [end - 24 h, end] once, "Last 1 hour" sets the 1h preset',
  { annotation: req('F-U16', '05-2.0-8') },
  async ({ page, seed }) => {
    const appId = seed.primary_app_id;
    const end = Date.parse(seed.generator.range.end);
    const day = 24 * 60 * MINUTE;
    const link = xlogPath(
      appId,
      new Date(end - 2 * day).toISOString(),
      new Date(end).toISOString(),
    );

    // The 48-hour link: the screen offers the shorter ranges and asks the points API nothing.
    let points = recordPointRequests(page);
    await page.goto(link);
    let notice = page.getByTestId('xlog-range-limit');
    await expect(notice).toBeVisible();
    await expect(notice.getByTestId('xlog-range-last24h')).toBeVisible();
    await expect(notice.getByTestId('xlog-range-last1h')).toBeVisible();
    await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-state', 'rangeLimit');
    expect(points.map(String), 'points requests for 48 hours').toEqual([]);

    // "Last 24 hours": the last 24 hours of the link's range, in the URL and in the one request.
    await notice.getByTestId('xlog-range-last24h').click();
    const narrowed = await expectUrl(
      page,
      (s) => s.time.kind === 'absolute',
      '"Last 24 hours" writes an absolute range',
    );
    expect(absolute(narrowed)).toEqual({ fromMs: end - day, toMs: end });
    await expectReady(page);
    expect(points, 'points requests after "Last 24 hours"').toHaveLength(1);
    const [request] = points;
    expect(Date.parse(request?.searchParams.get('from') ?? '')).toBe(end - day);
    expect(Date.parse(request?.searchParams.get('to') ?? '')).toBe(end);

    // The 48-hour link again, "Last 1 hour": the 1h preset.
    points = recordPointRequests(page);
    await page.goto(link);
    notice = page.getByTestId('xlog-range-limit');
    await expect(notice).toBeVisible();
    expect(points.map(String), 'points requests for 48 hours, second visit').toEqual([]);
    await notice.getByTestId('xlog-range-last1h').click();
    const preset = await expectUrl(
      page,
      (s) => s.time.kind === 'relative' && s.time.range === '1h',
      '"Last 1 hour" writes the 1h preset',
    );
    expect(preset.time).toEqual({ kind: 'relative', range: '1h' });
  },
);

test(
  'T-E2E-078 a drag while live freezes the 5-minute window the chart showed, not the 15-minute URL range, and turns live off',
  { annotation: req('05-2.0-2', '05-2.1-4') },
  async ({ page, seed, ws }) => {
    const appId = seed.primary_app_id;
    await page.goto(`/apps/${String(appId)}/xlog?from=now-15m&live=1`);
    const root = page.getByTestId('xlog-page');
    await expect(root).toHaveAttribute('data-live', 'true');
    await ws.waitForSubscription('xlog');
    await expect(root).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
    // While live the chart shows [now - 5 min, now] (docs/15 §7.5), whatever the URL's range.
    const liveFrom = await intAttr(root, 'data-range-from');
    const liveTo = await intAttr(root, 'data-range-to');
    expect(Math.abs(liveTo - liveFrom - 300_000), 'live window of 5 minutes').toBeLessThanOrEqual(
      1000,
    );
    await dragSelect(page, 0.2, 0.6);
    const frozen = await expectUrl(
      page,
      (s) => !s.live && s.time.kind === 'absolute',
      'live off, the window frozen as an absolute range',
    );
    await expect(root).toHaveAttribute('data-live', 'false');
    const from = await intAttr(root, 'data-range-from');
    const to = await intAttr(root, 'data-range-to');
    expect(Math.abs(to - from - 300_000), 'frozen window of 5 minutes (+-1 s)').toBeLessThanOrEqual(
      1000,
    );
    // The URL holds that window, and the chart did not jump to the URL's 15 minutes.
    if (frozen.time.kind === 'absolute') {
      expect(frozen.time.fromMs).toBe(from);
      expect(frozen.time.toMs).toBe(to);
    }
    expect(to).toBeGreaterThanOrEqual(liveTo);
  },
);

/** Filter values a request sends (search and summary bodies, point query), min/max excluded. */
function sentFilterCount(url: URL, body: Record<string, unknown> | null): number {
  if (body === null) {
    return ['service', 'instance', 'error'].reduce(
      (n, k) => n + url.searchParams.getAll(k).length,
      0,
    );
  }
  const filters = (body.filters ?? body) as Record<string, unknown>;
  let count = 0;
  for (const [key, value] of Object.entries(filters)) {
    if (
      ['min_ms', 'max_ms', 'from', 'to', 'kind', 'sort', 'limit', 'cursor', 'filters'].includes(key)
    ) {
      continue;
    }
    count += Array.isArray(value) ? value.length : value === undefined || value === null ? 0 : 1;
  }
  return count;
}

test(
  'T-SEC-64 XLog: a tampered link (reversed sel, color=red, y=cubic, 21 filters, a 1,100-byte value) restores the defaults with one notice and sends none of it',
  { annotation: req('SEC-64') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(EXPECTATION_ROLE);
    const w = await window(api, seed);
    const sent: { url: URL; body: Record<string, unknown> | null; text: string }[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (/^\/api\/v1\/apps\/\d+\/(xlog|xlog\/summary|transactions\/search)$/.test(url.pathname)) {
        const text = request.postData() ?? '';
        sent.push({
          url,
          body: text === '' ? null : (JSON.parse(text) as Record<string, unknown>),
          text: `${url.search}\n${text}`,
        });
      }
    });
    const fromMs = Date.parse(w.from);
    const toMs = Date.parse(w.to);
    const long = 'u'.repeat(1100);
    const params = new URLSearchParams({ from: w.from, to: w.to });
    // A selection with both bounds reversed, an unknown colour basis and y axis.
    params.set('sel', `ms:500-10,ts:${String(toMs - 1000)}-${String(fromMs + 1000)}`);
    params.set('color', 'red');
    params.set('y', 'cubic');
    // A value over 1,024 UTF-8 bytes (refused, not cut) and 21 more filters (20 at most).
    params.append('f.user', long);
    const codes = Array.from({ length: 21 }, (_, i) => String(200 + i));
    for (const code of codes) {
      params.append('f.status', code);
    }
    await openXLog(page, `/apps/${String(w.appId)}/xlog?${params.toString()}`);

    // The screen falls back to its defaults and says so once (docs/15 §4.2).
    await expect(page.getByTestId('url-restore-notice')).toHaveCount(1);
    await expect(page.getByTestId('xlog-colorby-status')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('xlog-yscale-linear')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByTestId('xlog-summary-empty')).toBeVisible();
    await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-state', 'idle');
    const restored = state(page);
    expect(restored.params.has('sel')).toBe(false);
    expect(restored.params.has('color')).toBe(false);
    expect(restored.params.has('y')).toBe(false);
    // The over-long value counts towards the limit and is dropped; of the 21 codes the first 19
    // fill the 20 places, the last two are dropped.
    const kept = codes.slice(0, 19);
    expect(restored.filters).toEqual(kept.map((value) => ({ field: 'status', op: 'eq', value })));

    // A valid selection now: summary and list ask with the kept filters only.
    const sel = await dragSelect(page);
    await expectSelectionShown(page, sel);
    const kinds = (pattern: RegExp) => sent.filter((r) => pattern.test(r.url.pathname));
    expect(kinds(/\/xlog$/).length, 'point requests').toBeGreaterThan(0);
    expect(kinds(/\/xlog\/summary$/).length, 'summary requests').toBeGreaterThan(0);
    expect(kinds(/\/transactions\/search$/).length, 'search requests').toBeGreaterThan(0);
    // The POST bodies are not visible to the browser's network events (the shared transport sends
    // a cloned Request, postData is null; e2e/required/xlogChecks.ts expectCsvDownload): what the
    // search asked is checked through its answer, which equals the API's answer for exactly the
    // kept 19 status codes (a dropped value or filter would change or refuse it).
    const search = await apiSearch(
      api,
      w.appId,
      searchBody(sel, 'HTTP', { http_status: kept.map(Number) }),
    );
    const list = page.getByTestId('xlog-list');
    await expect(list).toHaveAttribute('data-rows', String(search.data.length));
    const keys = await listKeys(page);
    expect(keys).toEqual(search.data.slice(0, keys.length).map((r) => r.tx_id));
    for (const request of sent) {
      expect(request.text, request.url.pathname).not.toContain('cubic');
      expect(request.text, request.url.pathname).not.toContain('u'.repeat(1025));
      expect(request.url.searchParams.get('color_by') ?? 'status').not.toBe('red');
      expect(request.body === null ? '' : JSON.stringify(request.body)).not.toContain('"red"');
      expect(
        `${request.url.searchParams.get('min_ms') ?? ''}-${request.url.searchParams.get('max_ms') ?? ''}`,
      ).not.toBe('500-10');
      const bounds = (request.body?.filters ?? request.body ?? {}) as Record<string, unknown>;
      expect([bounds.min_ms, bounds.max_ms]).not.toEqual([500, 10]);
      expect(sentFilterCount(request.url, request.body), request.url.pathname).toBeLessThanOrEqual(
        20,
      );
    }
  },
);

/**
 * docs/03 §3.3 (realtime screens: drop, reconnect and logout end) on the XLog screen. The REST API
 * is real; the WebSocket is the docs/14 §5 stub (WO-22). After /login the XLog screen is unmounted:
 * its live controller (1 s window timer, REST fills and polls) is stopped and holds no subscription,
 * so neither a socket nor a points request may follow the close.
 */
test(
  'T-SEC-44b XLog live: close 4401 -> /login?next with the XLog URL; no new connection and no points request after the close',
  { tag: '@security', annotation: req('SEC-44', '05-2.1-4') },
  async ({ page, seed, ws, apiAs }) => {
    const api = await apiAs(EXPECTATION_ROLE);
    const appId = seed.primary_app_id;
    // A service key without transactions (checked): the REST load is empty, the point is a push.
    const liveService = '1';
    const none = await apiPoints(api, appId, {
      from: new Date(Date.now() - 10 * MINUTE).toISOString(),
      to: new Date().toISOString(),
      kind: 'HTTP',
      limit: 1,
      service: [liveService],
    });
    expect(none.meta.total, `no transaction of service ${liveService}`).toBe(0);
    await page.goto(`/apps/${String(appId)}/xlog?from=now-5m&live=1&f.service=${liveService}`);
    await ws.waitForSubscription('xlog');
    const root = page.getByTestId('xlog-page');
    await expect(root).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
    // The first data frame is drawn.
    const instanceId = manifestApp(seed, appId).instances[0]?.instance_id ?? 1;
    expect(
      ws.pushXlog([
        {
          tx_id: '18446744073709400001',
          ts: Date.now() - 500,
          elapsed_ms: 120,
          status: 0,
          instance_id: instanceId,
          service_id: liveService,
          kind: 1,
        },
      ]),
    ).toBe(1);
    await expect(root).toHaveAttribute('data-point-count', '1');
    const xlogPathname = new URL(page.url()).pathname;
    const connections = ws.connectionCount();
    expect(connections, 'connections of the live XLog before the close').toBe(1);
    // Every points request (load, fill, poll, summary) from the close on.
    let pointRequests = 0;
    page.on('request', (request) => {
      if (request.url().includes(`/api/v1/apps/${String(appId)}/xlog`)) {
        pointRequests += 1;
      }
    });
    ws.close(4401, 'unauthenticated');
    await expect(page).toHaveURL(/\/login\?next=/, { timeout: 10_000 });
    const next = new URL(page.url()).searchParams.get('next') ?? '';
    expect(next.startsWith(`${xlogPathname}?`), `next=${next}`).toBe(true);
    const nextParams = new URLSearchParams(next.slice(next.indexOf('?') + 1));
    expect(nextParams.get('live')).toBe('1');
    expect(nextParams.getAll('f.service')).toEqual([liveService]);
    // Back-off would retry after 1 s +-20 % and the live window ticks every second (docs/15 §10):
    // 5 s after the navigation cover both, counted by events (no fixed expectation wait).
    const end = Date.now() + 5_000;
    for (;;) {
      expect(ws.connectionCount(), 'connections opened after close 4401').toBe(connections);
      expect(pointRequests, 'XLog points requests after close 4401').toBe(0);
      if (Date.now() >= end) {
        break;
      }
      await page.waitForTimeout(100);
    }
  },
);
