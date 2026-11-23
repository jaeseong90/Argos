// SPDX-License-Identifier: Apache-2.0
//
// Application dashboard on the seeded stack (WO-32, docs/05 §2.3, docs/15 §9.2, docs/18 §13): the
// ready dashboard with its values checked against the API asked the same question, deep links 4
// (event markers, alert= emphasis, XSS title as text) and 5 (the same absolute link shows the same
// data) on the dashboard (05 §2.0-7, ADR-0010 C-168 (1)), collected strings as text, the 1366x768
// layout, accessibility and the visual baselines of the loading, empty, error and normal states.
// The checklist items of docs/05 §2.3 are data-driven in e2e/required/dashboard.yaml
// (T-E2E-dashboard-<n>); these tests carry WO-32's T-E2E ids. The normal-state test attaches a
// full-page screenshot (dashboard.png) as the evidence of what the dashboard shows.
import type { Page } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import {
  apiCompare,
  apiDashboard,
  apiEvents,
  CHARTS,
  chart,
  dashboardPath,
  expectDashboardState,
  expectDeltas,
  expectedSeriesCounts,
  expectTiles,
  legendKeys,
  markerTotal,
  openDashboard,
  ORACLE_ROLE,
  shownSummary,
  snapshot,
  summaryOf,
} from '../fixtures/dashboardOracle';
import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import { horizontalOverflow, state } from '../fixtures/ui';
import { apiAlertTransitions } from '../required/alertsChecks';
import { DASHBOARD_CHECKS } from '../required/dashboardChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { manifestAlert } from '../stack/manifest';

const DASHBOARD = { type: 'req', description: 'F-U01' };
const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

function req(...ids: string[]): { type: string; description: string }[] {
  return [DASHBOARD, ...ids.map((description) => ({ type: 'req', description }))];
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

test(
  'T-E2E-084 the dashboard of the seeded range: tiles with their changes, nine charts (process and system CPU), instances, comparison, alerts and collection status equal the API (evidence screenshot)',
  { annotation: req('05-2.3-1', '05-2.3-2', '05-2.3-3', '05-2.3-4', '05-2.3-5', '05-2.3-6') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { start: from, end: to } = seed.generator.range;
    const root = await openDashboard(page, dashboardPath(appId, from, to));
    const data = await apiDashboard(api, appId, { from, to });
    const compare = await apiCompare(api, appId, { from, to });
    await expectTiles(page, data.tiles);
    // ADR-0010 C-212 (1): every tile with a change (tps, avg, p95, p99, error rate, active) shows
    // the API delta of the same question.
    await expectDeltas(page, data.tiles.delta);
    const counts = expectedSeriesCounts(data.series);
    for (const id of CHARTS) {
      await expect(chart(page, id)).toBeVisible();
      await expect
        .poll(async () => (await legendKeys(page, id)).length, { message: `chart ${id}` })
        .toBe(counts[id]);
    }
    // C-212 (1): the CPU chart draws process and system CPU, both equal to the API series.
    expect(summaryOf(data.series.system_cpu).n, 'API series.system_cpu').toBeGreaterThan(0);
    await expect
      .poll(() => shownSummary(page, 'cpu'), {
        message: 'cpu chart = series.cpu, series.system_cpu',
      })
      .toEqual({ cpu: summaryOf(data.series.cpu), system_cpu: summaryOf(data.series.system_cpu) });
    expect(await legendKeys(page, 'cpu')).toEqual(['cpu', 'system_cpu']);
    await expect(page.getByTestId('dashboard-instance-tile')).toHaveCount(data.instances.length);
    await expect(
      page.getByTestId('dashboard-compare-table').getByTestId('data-table-row'),
    ).toHaveCount(compare.length);
    await expect(page.getByTestId('dashboard-firing-panel')).toBeVisible();
    await expect(page.getByTestId('dashboard-collection-panel')).toBeVisible();
    await expect(root).toHaveAttribute('data-live', 'false');
    // Evidence of what the dashboard shows (WO-32 report): the whole page after every panel is in.
    const file = testInfo.outputPath('dashboard.png');
    await page.screenshot({ path: file, fullPage: true });
    await testInfo.attach('dashboard.png', { path: file, contentType: 'image/png' });
    testInfo.annotations.push({
      type: 'dashboard',
      description: `tiles ${JSON.stringify(data.tiles)}; ${String(data.instances.length)} instances; ${String(data.events.length)} events; series ${JSON.stringify(counts)}`,
    });
  },
);

test(
  'T-E2E-085 deep link 4 on the dashboard: markers = GET /events of the range on every chart, alert=<event id> emphasises that event with live off, the XSS title as text',
  { annotation: req('05-2.0-7', 'F-U14', 'SEC-60') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const range = seed.generator.range;
    const seeded = seed.events.filter((e) => e.app_id === appId);
    expect(seeded.length, 'seed events of the primary app in the manifest').toBe(2);
    const plain = seeded.find((e) => !e.xss);
    const xss = seeded.find((e) => e.xss);
    if (plain === undefined || xss === undefined) {
      throw new Error('the manifest lacks the plain or the XSS seed event');
    }
    const events = await apiEvents(api, appId, range.start, range.end);
    const data = await apiDashboard(api, appId, { from: range.start, to: range.end });
    expect(data.events.map((e) => e.event_id).sort()).toEqual(events.map((e) => e.event_id).sort());
    for (const event of seeded) {
      expect(events.map((e) => e.event_id)).toContain(event.event_id);
    }
    // The alert transitions of the range are markers too (ADR-0010 C-226 (8), WO-38).
    const transitions = await apiAlertTransitions(api, appId, range.start, range.end);
    const root = await openDashboard(
      page,
      dashboardPath(appId, range.start, range.end, { alert: plain.event_id }),
    );
    for (const id of CHARTS) {
      await expect
        .poll(() => markerTotal(page, id), {
          message: `chart ${id}: markers = events + alert transitions`,
        })
        .toBe(events.length + transitions.length);
      const emphasized = chart(page, id).locator(
        '[data-testid="chart-marker"][data-emphasized="true"]',
      );
      await expect(emphasized, `chart ${id}: one emphasised marker`).toHaveCount(1);
      await expect(emphasized).toHaveAttribute('data-marker-id', plain.event_id);
    }
    expect(state(page).live).toBe(false);
    expect(state(page).params.get('alert')).toBe(plain.event_id);
    await expect(root).toHaveAttribute('data-live', 'false');
    // The XSS title: verbatim text in the marker's name and tooltip, no markup made of it.
    const marker = chart(page, 'tps').locator(
      `[data-testid="chart-marker"][data-marker-id="${xss.event_id}"]`,
    );
    await expect(marker).toHaveCount(1);
    expect(await marker.getAttribute('aria-label')).toContain(xss.title);
    await marker.hover();
    const tip = page.locator('.chart-marker-tip').filter({ visible: true });
    await expect(tip).toHaveCount(1);
    await expect(tip.locator('li .safe-text').first()).toHaveText(xss.title);
    expect(
      await page
        .locator(
          '[data-testid="dashboard-page"] img, [data-testid="dashboard-page"] script, [data-testid="dashboard-page"] iframe, .chart-marker-tip img, .chart-marker-tip script',
        )
        .count(),
    ).toBe(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
  },
);

test(
  'T-E2E-086 deep link 5 on the dashboard: the same absolute link opened twice shows the same tiles, series and instances',
  { annotation: req('05-2.0-7', 'F-U14') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { start: from, end: to } = seed.generator.range;
    const link = dashboardPath(appId, from, to);
    await openDashboard(page, link);
    const first = await snapshot(page);
    await page.goto('about:blank');
    await openDashboard(page, link);
    const second = await snapshot(page);
    expect(second).toEqual(first);
    for (const id of CHARTS) {
      expect(first.charts[id]?.table.length ?? 0, `chart ${id}: data table`).toBeGreaterThan(0);
    }
    // And the data is the API's for that range.
    await expectTiles(page, (await apiDashboard(api, appId, { from, to })).tiles);
  },
);

test(
  "T-E2E-alerts-1 the dashboard's firing panel equals the firing answer of the alert engine, the seed's real FIRING alert among its rows; a row opens /alerts?app=<id>&alert=<event id>",
  { annotation: req('05-2.3-5', 'F-S06') },
  async ({ page, seed, apiAs }, testInfo) => {
    const { start: from, end: to } = seed.generator.range;
    await openDashboard(page, dashboardPath(seed.primary_app_id, from, to));
    const check = DASHBOARD_CHECKS.dashboardFiringAlerts;
    if (check === undefined) {
      throw new Error('dashboardFiringAlerts is not a dashboard check');
    }
    await check({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-alerts-3 the recommended rules of the default templates are applied to the primary app on its own (F-S24): GET /alerts/rules lists the template rules the seed recorded, enabled, next to the seed rule',
  { annotation: req('F-S24') },
  async ({ page, seed, apiAs }, testInfo) => {
    const alert = manifestAlert(seed);
    // Every E2E test opens the UI (the page hooks check a loaded document): the dashboard whose
    // firing panel these rules feed. The rule list itself is the API's (the rules screen is WO-38).
    const { start: from, end: to } = seed.generator.range;
    await openDashboard(page, dashboardPath(alert.app_id, from, to));
    await expect(page.getByTestId('dashboard-firing-panel')).toBeVisible();
    expect(
      alert.template_rules.length,
      'template rules of the primary app at seed time (missing_optional alert_templates names why when none)',
    ).toBeGreaterThan(0);
    const api = await apiAs(ORACLE_ROLE);
    const rules = await api.data<
      {
        rule_id?: string;
        id?: string;
        template_key?: string | null;
        metric?: string;
        enabled?: boolean;
      }[]
    >('/alerts/rules', { app: alert.app_id });
    const byId = new Map(rules.map((r) => [String(r.rule_id ?? r.id), r]));
    for (const recorded of alert.template_rules) {
      const rule = byId.get(recorded.rule_id);
      expect(rule, `template rule ${recorded.template_key} (${recorded.rule_id})`).toBeDefined();
      expect(rule?.template_key).toBe(recorded.template_key);
      expect(rule?.enabled, `template rule ${recorded.template_key} is enabled`).toBe(true);
    }
    // Each template is applied once per application: no duplicate rule of one template part. A
    // template whose product plan row has two metrics (connection pool waiting and usage) has one
    // rule per metric, so a part is (template_key, metric), the key the template diff matches on.
    const keys = rules.flatMap((r) =>
      typeof r.template_key === 'string' && r.template_key !== ''
        ? [`${r.template_key}/${String(r.metric)}`]
        : [],
    );
    expect(new Set(keys).size, `template parts ${keys.join(', ')}`).toBe(keys.length);
    // The operator's seed rule is not a template rule.
    expect(byId.get(alert.rule_id)?.template_key ?? null).toBeNull();
    testInfo.annotations.push({
      type: 'alert-templates',
      description: `app ${String(alert.app_id)}: ${keys.join(', ')}`,
    });
  },
);

test.describe('layout and accessibility as operator', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-087 1366x768: the ready dashboard scrolls only vertically, tiles and the first chart row on the first screen',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
      const { start: from, end: to } = seed.generator.range;
      await openDashboard(page, dashboardPath(seed.primary_app_id, from, to));
      const { scrollWidth, clientWidth } = await horizontalOverflow(page);
      expect(scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(clientWidth);
      expect(await page.evaluate(() => globalThis.scrollY)).toBe(0);
      for (const id of ['tps', 'avg', 'p95', 'p99', 'error-rate', 'active']) {
        const box = await page.getByTestId(`dashboard-tile-${id}`).boundingBox();
        expect(box, `tile ${id}`).not.toBeNull();
        expect((box?.y ?? 9999) + (box?.height ?? 0), `tile ${id} bottom`).toBeLessThanOrEqual(768);
      }
      for (const id of ['tps', 'response', 'error-rate'] as const) {
        const top = (await chart(page, id).boundingBox())?.y ?? 9999;
        expect(top, `chart ${id} starts on the first screen`).toBeLessThan(768);
      }
    },
  );

  test(
    'T-E2E-088 accessibility: no critical axe-core violation on the ready dashboard, with the baseline on',
    { annotation: req('F-U16') },
    async ({ page, seed }, testInfo) => {
      const { start: from, end: to } = seed.generator.range;
      await openDashboard(page, dashboardPath(seed.primary_app_id, from, to));
      await expectNoCriticalA11y(page, testInfo, 'dashboard');
      await openDashboard(page, dashboardPath(seed.primary_app_id, from, to, { baseline: '7d' }));
      await expectNoCriticalA11y(page, testInfo, 'dashboard-baseline');
    },
  );
});

test(
  'T-E2E-089 collected strings on the dashboard are text: instance names in the tiles and the comparison, pool names in the legend',
  { annotation: req('SEC-60', 'SEC-62') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { start: from, end: to } = seed.generator.range;
    await openDashboard(page, dashboardPath(appId, from, to));
    const data = await apiDashboard(api, appId, { from, to });
    expect(data.instances.length).toBeGreaterThan(0);
    for (const instance of data.instances) {
      const id = String(instance.instance_id);
      await expect(
        page.locator(`[data-testid="dashboard-instance-tile"][data-instance-id="${id}"]`),
      ).toContainText(instance.name);
      await expect(
        page
          .getByTestId('dashboard-compare-table')
          .locator(`[data-row-key="${id}"] [data-column="instance"]`),
      ).toContainText(instance.name);
    }
    const pools = Object.keys(data.series.pool_usage_ratio);
    const legend = chart(page, 'pool').getByTestId('chart-legend-item');
    await expect(legend).toHaveCount(pools.length);
    for (const [index, name] of pools.entries()) {
      await expect(legend.nth(index)).toContainText(name);
    }
    expect(
      await page
        .locator(
          '[data-testid="dashboard-page"] img, [data-testid="dashboard-page"] iframe, [data-testid="dashboard-page"] script, [data-testid="dashboard-page"] a[href^="javascript"]',
        )
        .count(),
    ).toBe(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
    test.info().annotations.push({
      type: 'collected-strings',
      description: `${String(data.instances.length)} instance names, pools ${JSON.stringify(pools)}`,
    });
  },
);

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

  /**
   * Per-run values: the range label, the chart plots (clock times on the axes, the seed is anchored
   * on the run day), uptimes and request ids.
   */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.locator('.ts-chart__plot'),
      page.locator('[data-column="uptime_s"]'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-080 visual: the dashboard while its data loads',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const { start: from, end: to } = seed.generator.range;
      // The dashboard request is held until the screenshot is taken, then continues unchanged.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/apps/*/dashboard?**', async (route) => {
        await held;
        await route.continue();
      });
      await page.goto(dashboardPath(seed.primary_app_id, from, to));
      await expectDashboardState(page, 'loading');
      const loading = page.getByTestId('dashboard-loading');
      await expect(loading).toBeVisible();
      // The slow-query hint appears after 2 s (LoadingState): wait for it, so the image is stable.
      await expect(
        loading.locator('[data-testid="loading-state"][data-slow="true"]').first(),
      ).toBeVisible();
      await expect(
        loading.locator('[data-testid="loading-state"]:not([data-slow="true"])'),
      ).toHaveCount(0);
      await expect(page).toHaveScreenshot('dashboard-loading.png', { mask: masks(page) });
      release();
      await expectDashboardState(page);
      await page.unroute('**/api/v1/apps/*/dashboard?**');
    },
  );

  test(
    'T-E2E-081 visual: the dashboard of a range without transactions',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(ORACLE_ROLE);
      const start = Date.parse(seed.generator.range.start);
      const from = new Date(start - 30 * MINUTE).toISOString();
      const to = new Date(start - 15 * MINUTE).toISOString();
      const data = await apiDashboard(api, seed.primary_app_id, { from, to });
      expect(data.tiles.count, 'no transaction before the generator range').toBe(0);
      await page.goto(dashboardPath(seed.primary_app_id, from, to));
      await expectDashboardState(page, 'empty');
      await expect(page.getByTestId('dashboard-empty')).toBeVisible();
      await expect(page).toHaveScreenshot('dashboard-empty.png', {
        mask: masks(page),
        fullPage: true,
      });
    },
  );

  test(
    'T-E2E-082 visual: the dashboard for a range over the API limit (more than 1 year): the server error with the request id and the next actions',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(ORACLE_ROLE);
      const end = Date.parse(seed.generator.range.end);
      const from = new Date(end - 400 * DAY).toISOString();
      const to = new Date(end).toISOString();
      // The same request through the API is refused (range_max 1y, docs/14 dashboard_get).
      const answer = await api.request('GET', `/apps/${String(seed.primary_app_id)}/dashboard`, {
        query: { from, to },
        expect: [400],
      });
      expect(answer.status).toBe(400);
      await page.goto(dashboardPath(seed.primary_app_id, from, to));
      await expectDashboardState(page, 'error');
      const error = page.getByTestId('dashboard-error');
      await expect(error).toBeVisible();
      await expect(error.getByTestId('error-state-request-id')).toBeVisible();
      await expect(error.getByTestId('error-state-retry')).toBeVisible();
      await expect(page).toHaveScreenshot('dashboard-error.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-083 visual: the ready dashboard of the seeded range',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const { start: from, end: to } = seed.generator.range;
      await openDashboard(page, dashboardPath(seed.primary_app_id, from, to));
      for (const id of CHARTS) {
        await expect(chart(page, id).locator('canvas').first()).toBeVisible();
      }
      // The collection status panel shows live values of the run (ingest lag, late arrivals: the
      // seed agent's last batches, ADR-0010 C-220); T-E2E-dashboard-9 checks them exactly. The
      // firing panel lists the engine's live alert (its duration and value change with the run,
      // WO-25); T-E2E-alerts-1 and required 2.3-5 check its rows exactly.
      await expect(page).toHaveScreenshot('dashboard-normal.png', {
        mask: [
          ...masks(page),
          page.getByTestId('dashboard-collection-panel'),
          page.getByTestId('dashboard-firing-panel'),
        ],
        fullPage: true,
      });
    },
  );
});
