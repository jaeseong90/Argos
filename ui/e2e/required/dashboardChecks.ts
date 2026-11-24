// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the application dashboard (WO-32): the docs/05 §2.3 checklist of
// ui/e2e/required/dashboard.yaml and the WO-32 items of ui/e2e/required/common.yaml (05 §2.0-1 chart
// drag, 2.0-5 point click menu and event markers). Each runs on the real stack (server, ClickHouse,
// PostgreSQL, seed) after the item's route and asserts exact values: from the URL, the seed
// manifest or the API asked the same question inside the check (GET /dashboard, /instances/compare,
// /events of the same bounds, e2e/fixtures/dashboardOracle.ts). The WebSocket is the docs/14 §5
// stub (C-205 (3)) except in dashboardLiveServer (wsMode 'server': the real /ws of WO-22); the
// outage shading checks use the collection outage of the seed (manifest `outage`, WO-22) and the
// firing alert checks the real FIRING alert of the seed (manifest `alert`, WO-25). The real
// STALE/WARN tiles (2.3-3, WO-39, ADR-0010 C-217 (2)) come from an agent of the check's own: the
// kill switch makes it WARN kill_switch, stopping it makes it STALE (dashboardWarnStaleLive).
import { expect, type Page, type TestInfo } from '@playwright/test';

import {
  apiCompare,
  apiDashboard,
  apiEvents,
  CHARTS,
  chart,
  COLLECTION_ITEMS,
  dashboardPath,
  dragChart,
  expectDashboardState,
  expectDeltas,
  expectedSeriesCounts,
  expectTiles,
  legendKeys,
  liveTsMs,
  markerTotal,
  numAttr,
  openDashboard,
  ORACLE_ROLE,
  plotBoxOf,
  recordDashboardRequests,
  shownSummary,
  summaryOf,
  tile,
  TILES,
  type ChartId,
  type DashboardData,
  type InstanceComparison,
} from '../fixtures/dashboardOracle';
import { t } from '../fixtures/i18n';
import { absolute, expectUrl, sameTime, state } from '../fixtures/ui';
import { effectiveKind } from '../fixtures/urlModel';
import type { WsStub } from '../fixtures/ws';
import type { ApiSession } from '../stack/api';
import { manifestAlert, type Role, type SeedManifest } from '../stack/manifest';

import {
  apiKillSwitch,
  changeResult,
  createApp,
  deleteAppQuietly,
  issueToken,
  ownShopProblem,
  runSuffix,
  startOwnShop,
  writeTokenFile,
  type OwnShop,
} from './adminChecks';
import { apiAlertTransitions } from './alertsChecks';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface DashboardCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  /** The docs/14 §5 WebSocket stub (realtime checks). */
  readonly ws?: WsStub;
}

type DashboardCheck = (ctx: DashboardCheckContext) => Promise<void>;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

/** Text of a missing value: the dash (no data) or "Not measurable" (T-UI-007). */
const MISSING = new RegExp(
  `—|${t('common:component.missing.notMeasurable').replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`,
);

/** The outlier flags of the comparison API and the data-outlier names of the cells (C-177). */
const OUTLIER_FLAGS = ['p95', 'error_rate', 'cpu'] as const;

/** Dashed baseline lines per chart: the series of the API's baseline (tps, avg/p95/p99, error rate). */
const BASELINE_LINES: Readonly<Record<ChartId, number>> = {
  tps: 1,
  response: 3,
  'error-rate': 1,
  active: 0,
  'heap-gc': 0,
  cpu: 0,
  tomcat: 0,
  pool: 0,
  ext: 0,
};

function isBaselineKey(key: string): boolean {
  return key.endsWith(':baseline');
}

/** The absolute range of the page (the item's route) as ISO strings for the API. */
function pageRange(page: Page): { from: string; to: string; fromMs: number; toMs: number } {
  const { fromMs, toMs } = absolute(state(page));
  return {
    from: new Date(fromMs).toISOString(),
    to: new Date(toMs).toISOString(),
    fromMs,
    toMs,
  };
}

/**
 * A relative live range that holds the whole generator range (the seed's data ends at the load
 * time; a reused stack may be older than one hour), in whole hours.
 */
function liveHours(seed: SeedManifest): number {
  return Math.ceil((Date.now() - Date.parse(seed.generator.range.start)) / HOUR) + 1;
}

/** Whether `values` are in order (non-null ones monotonic, nulls together at one end). */
export function sortedWithNulls(values: readonly (number | null)[], desc: boolean): boolean {
  const nulls = values.filter((v) => v === null).length;
  const together =
    nulls === 0 ||
    values.slice(0, nulls).every((v) => v === null) ||
    values.slice(values.length - nulls).every((v) => v === null);
  if (!together) {
    return false;
  }
  const numbers = values.filter((v): v is number => v !== null);
  return numbers.every((v, i) => {
    const previous = numbers[i - 1];
    return previous === undefined || (desc ? previous >= v : previous <= v);
  });
}

/**
 * 2.3-1: the six summary tiles show the API tiles of the same range (null stays null, never 0); the
 * changes of TPS, average, p95, p99, error rate and active (ADR-0010 C-212 (1)) are the API deltas
 * against the previous range, and with tileRef=7d against 7 days ago (the API's baseline=7d basis);
 * at most two dashboard requests.
 */
const dashboardTiles: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const { from, to } = pageRange(page);
  await expectDashboardState(page);
  const prev = await apiDashboard(api, appId, { from, to });
  expect(prev.tiles.count, 'transactions of the primary app in the range').toBeGreaterThan(0);
  expect(prev.tiles.delta.basis).toBe('prev');
  await expectTiles(page, prev.tiles);
  await expectDeltas(page, prev.tiles.delta);
  await expect(page.getByTestId('dashboard-tile-ref')).toHaveAttribute('data-value', 'prev');

  // The basis toggle: tileRef=7d in the URL, the tiles ask the 7-day basis.
  const sent = recordDashboardRequests(page);
  await page.getByTestId('dashboard-tile-ref-7d').click();
  await expectUrl(page, (x) => x.params.get('tileRef') === '7d', 'tileRef=7d in the URL');
  await expect(page.getByTestId('dashboard-tile-ref-7d')).toHaveAttribute('aria-checked', 'true');
  await expectDashboardState(page);
  const week = await apiDashboard(api, appId, { from, to, baseline: '7d' });
  expect(week.tiles.delta.basis).toBe('7d');
  // The values stay those of the range; only the change basis moves to 7 days ago.
  await expectTiles(page, prev.tiles);
  await expectDeltas(page, week.tiles.delta);
  const s = state(page);
  expect(s.params.get('tileRef')).toBe('7d');
  expect(s.params.get('baseline'), 'tileRef does not switch the chart baseline on').toBeNull();
  expect(sent.length, 'dashboard requests after the toggle').toBeGreaterThanOrEqual(1);
  expect(sent.length, 'dashboard requests after the toggle (at most 2)').toBeLessThanOrEqual(2);
  for (const url of sent) {
    expect(url.searchParams.get('from')).toBe(from);
    expect(url.searchParams.get('to')).toBe(to);
  }
  // A reload of the link keeps the basis (URL state, deep link).
  await openDashboard(page, dashboardPath(appId, from, to, { tileRef: '7d' }));
  await expectDeltas(page, week.tiles.delta);
  testInfo.annotations.push({
    type: 'tiles',
    description: `prev ${JSON.stringify(prev.tiles)}; 7d delta ${JSON.stringify(week.tiles.delta)}; ${String(sent.length)} request(s) for tileRef=7d`,
  });
};

/**
 * 2.3-2: the nine charts of docs/15 §9.2 with the series of the API answer (the CPU chart: process
 * and system CPU equal to series.cpu and series.system_cpu, ADR-0010 C-212 (1)), the events as
 * markers on every chart, the outage legend exactly when the API has outages, one cursor for all
 * charts, baseline=1d adds the dashed lines of the response chart, and a drag on a live dashboard
 * writes an absolute range and turns live off.
 */
const dashboardCharts: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const { from, to } = pageRange(page);
  await expectDashboardState(page);
  const data = await apiDashboard(api, appId, { from, to });
  // The alert transitions of the range are markers too (ADR-0010 C-226 (8), WO-38).
  const transitions = await apiAlertTransitions(api, appId, from, to);
  const expected = expectedSeriesCounts(data.series);
  for (const id of CHARTS) {
    await expect(chart(page, id), `chart ${id}`).toBeVisible();
    await expect
      .poll(async () => (await legendKeys(page, id)).filter((k) => !isBaselineKey(k)).length, {
        message: `chart ${id}: series of the API answer`,
      })
      .toBe(expected[id]);
    expect((await legendKeys(page, id)).filter(isBaselineKey), `chart ${id}: no baseline`).toEqual(
      [],
    );
    await expect
      .poll(() => markerTotal(page, id), {
        message: `chart ${id}: markers = API events + alert transitions`,
      })
      .toBe(data.events.length + transitions.length);
  }
  // C-212 (1): process and system CPU equal the API series (buckets with a value and their sum);
  // the seed generator records system_cpu_usage, so the system series is not empty.
  const cpu = { cpu: summaryOf(data.series.cpu), system_cpu: summaryOf(data.series.system_cpu) };
  expect(cpu.system_cpu.n, 'API series.system_cpu of the seeded range').toBeGreaterThan(0);
  await expect
    .poll(() => shownSummary(page, 'cpu'), { message: 'cpu chart: process and system CPU = API' })
    .toEqual(cpu);
  await expect(
    page.locator('[data-testid^="dashboard-chart-"] [data-testid="chart-outage-legend"]'),
    'outage legends: on every chart exactly when the API reports outages',
  ).toHaveCount(data.outages.length > 0 ? CHARTS.length : 0);
  testInfo.annotations.push({
    type: 'charts',
    description: `series ${JSON.stringify(expected)}, events ${String(data.events.length)}, outages ${String(data.outages.length)}`,
  });

  // One cursor: hovering the TPS chart shows the same instant in the tooltips of the others.
  const box = await plotBoxOf(page, 'tps');
  await page.mouse.move(box.x + box.width * 0.4, box.y + box.height * 0.6);
  const own = chart(page, 'tps').getByTestId('chart-tooltip');
  await expect(own).toBeVisible();
  const instant = (await own.locator('.chart-tooltip__time').textContent()) ?? '';
  expect(instant).not.toBe('');
  for (const id of ['response', 'error-rate', 'heap-gc', 'ext'] as const) {
    const other = chart(page, id).getByTestId('chart-tooltip');
    await expect(other, `chart ${id} follows the cursor`).toBeVisible();
    await expect(other.locator('.chart-tooltip__time')).toHaveText(instant);
  }
  await page.mouse.move(0, 0);

  // The baseline toggle: baseline=1d in the URL, dashed lines of avg, p95 and p99 one day earlier
  // (the API's baseline answer); a reload of that link shows them again.
  await page.getByTestId('dashboard-baseline-1d').click();
  await expectUrl(page, (x) => x.params.get('baseline') === '1d', 'baseline=1d in the URL');
  await expect
    .poll(async () => (await legendKeys(page, 'response')).filter(isBaselineKey).length, {
      message: 'response chart: three baseline lines after the toggle',
    })
    .toBe(3);
  await openDashboard(page, dashboardPath(appId, from, to, { baseline: '1d' }));
  const withBaseline = await apiDashboard(api, appId, { from, to, baseline: '1d' });
  expect(withBaseline.baseline?.offset).toBe('1d');
  // The API's baseline has tps, avg, p95, p99 and error rate: those series get a dashed line, the
  // charts of the other series none.
  for (const id of CHARTS) {
    await expect
      .poll(async () => (await legendKeys(page, id)).filter(isBaselineKey).length, {
        message: `chart ${id}: baseline lines`,
      })
      .toBe(BASELINE_LINES[id]);
  }
  await expect(chart(page, 'response').locator('.chart-legend__suffix').first()).toHaveText(
    t('common:component.chart.baseline.oneDay'),
  );

  // A drag on the live dashboard: an absolute range of the dragged span, live off.
  const hours = liveHours(seed);
  await openDashboard(page, `/apps/${String(appId)}/dashboard?from=now-${String(hours)}h&live=1`);
  const before = Date.now();
  const drag = await dragChart(page, 'tps', 0.3, 0.6);
  const after = await expectUrl(
    page,
    (s) => s.time.kind === 'absolute',
    'the chart drag writes an absolute range',
  );
  expect(after.live).toBe(false);
  expect(after.params.get('live')).toBeNull();
  const { fromMs, toMs } = absolute(after);
  const perPx = (hours * HOUR) / drag.box.width;
  expect(Math.abs(toMs - fromMs - (drag.endPx - drag.startPx) * perPx)).toBeLessThanOrEqual(
    3 * perPx,
  );
  expect(fromMs).toBeGreaterThanOrEqual(before - hours * HOUR - MINUTE);
  expect(toMs).toBeLessThanOrEqual(Date.now());
  await expect(page.getByTestId('live-toggle')).toHaveAttribute('aria-pressed', 'false');
};

/**
 * 2.3-3: one instance tile per API instance with its state (F-S28 colours; UP and DOWN until WO-22
 * adds STALE and WARN, ADR-0010 C-160); a click opens the instance detail in the same range.
 */
const dashboardInstanceTiles: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const { from, to } = pageRange(page);
  await expectDashboardState(page);
  const data = await apiDashboard(api, appId, { from, to });
  expect(data.instances.length, 'instances of the primary app').toBeGreaterThan(0);
  const tiles = page.getByTestId('dashboard-instance-tiles').getByTestId('dashboard-instance-tile');
  await expect(tiles).toHaveCount(data.instances.length);
  // State and WARN reasons per tile are the API's (ADR-0010 C-216 (2): reasons only while WARN).
  const shown = await tiles.evaluateAll((nodes) =>
    nodes.map(
      (n) =>
        `${n.getAttribute('data-instance-id') ?? ''}:${n.getAttribute('data-state') ?? ''}:${n.getAttribute('data-warn-reasons') ?? ''}`,
    ),
  );
  expect(shown.sort()).toEqual(
    data.instances
      .map(
        (i) =>
          `${String(i.instance_id)}:${i.state}:${i.state === 'WARN' ? [...new Set(i.warn_reasons)].join(',') : ''}`,
      )
      .sort(),
  );
  testInfo.annotations.push({ type: 'instance-states', description: shown.join(', ') });
  const first = data.instances[0];
  if (first === undefined) {
    return;
  }
  const before = state(page);
  await page
    .locator(
      `[data-testid="dashboard-instance-tile"][data-instance-id="${String(first.instance_id)}"]`,
    )
    .click();
  const detail = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/instances/${String(first.instance_id)}`,
    'the tile opens the instance detail',
  );
  expect(sameTime(detail.time, before.time), 'the time range is kept (05 §2.0-1)').toBe(true);
};

/** Row keys of the comparison table in display order. */
async function compareRowKeys(page: Page): Promise<string[]> {
  return page
    .getByTestId('dashboard-compare-table')
    .getByTestId('data-table-row')
    .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-row-key') ?? ''));
}

/**
 * 2.3-4 (F-U20): one row per instance of the comparison API, sorting by a column orders the rows by
 * the API values in both directions, the highlighted cells are exactly the server's outlier flags
 * (C-177, never recomputed in the browser), and the instance value filter writes f.instance, shows
 * the chip and queries the dashboard again for that instance.
 */
const dashboardCompareTable: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const { from, to } = pageRange(page);
  await expectDashboardState(page);
  const rows = await apiCompare(api, appId, { from, to });
  expect(rows.length, 'instances in the comparison').toBeGreaterThan(0);
  const table = page.getByTestId('dashboard-compare-table');
  await expect(table.getByTestId('data-table-row')).toHaveCount(rows.length);
  expect((await compareRowKeys(page)).sort()).toEqual(
    rows.map((r) => String(r.instance_id)).sort(),
  );

  const flagged = await table
    .locator('[data-outlier]')
    .evaluateAll((nodes) =>
      nodes.map(
        (n) =>
          `${n.closest('[data-row-key]')?.getAttribute('data-row-key') ?? ''}:${n.getAttribute('data-outlier') ?? ''}`,
      ),
    );
  const expectedFlags = rows.flatMap((r) =>
    OUTLIER_FLAGS.filter((f) => r.outlier[f] === true).map((f) => `${String(r.instance_id)}:${f}`),
  );
  expect(flagged.sort(), 'highlighted cells = server outlier flags').toEqual(expectedFlags.sort());
  testInfo.annotations.push({
    type: 'outliers',
    description: `${String(rows.length)} instances, flags ${expectedFlags.join(', ') || 'none'}`,
  });

  // Sorting by TPS: both directions follow the API values.
  const byId = new Map<string, InstanceComparison>(rows.map((r) => [String(r.instance_id), r]));
  const header = table.locator('th[data-column="tps"]');
  for (let click = 0; click < 2; click += 1) {
    const previous = await header.getAttribute('aria-sort');
    await table.getByTestId('data-table-sort-tps').click();
    await expect
      .poll(() => header.getAttribute('aria-sort'), { message: 'the sort direction changes' })
      .not.toBe(previous);
    const direction = await header.getAttribute('aria-sort');
    expect(direction).toMatch(/^(ascending|descending)$/);
    const values = (await compareRowKeys(page)).map((k) => byId.get(k)?.tps ?? null);
    expect(
      sortedWithNulls(values, direction === 'descending'),
      `rows ${direction ?? ''} by TPS: ${JSON.stringify(values)}`,
    ).toBe(true);
  }

  // The instance value -> f.instance chip -> the dashboard of that instance.
  const target = rows[0];
  if (target === undefined) {
    return;
  }
  const id = String(target.instance_id);
  const cell = table.locator(`[data-row-key="${id}"] [data-column="instance"]`);
  const sent = recordDashboardRequests(page);
  await cell.hover();
  await cell.getByTestId('cell-filter-include').click();
  await expectUrl(
    page,
    (s) => s.filters.some((f) => f.field === 'instance' && f.op === 'eq' && f.value === id),
    'f.instance from the comparison table',
  );
  const chip = page.locator('[data-testid="filter-chip"][data-field="instance"][data-op="eq"]');
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveAttribute('data-value', id);
  await expect
    .poll(() => sent.some((u) => u.searchParams.getAll('instance').includes(id)), {
      message: 'the dashboard is asked again with instance=<id>',
    })
    .toBe(true);
  await expectDashboardState(page);
  const filtered = await apiDashboard(api, appId, { from, to, instance: [target.instance_id] });
  await expectTiles(page, filtered.tiles);
};

/**
 * 2.3-5: the firing alerts panel of the app and environment says what the API says: a list, "no
 * alerts" for [] and "not available" only for a null firing (a server without the alert engine;
 * since WO-25 the engine always answers a list); its link opens the alert history.
 */
const dashboardFiring: DashboardCheck = async (ctx) => {
  const { page, seed } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const { from, to } = pageRange(page);
  await expectDashboardState(page);
  const data = await apiDashboard(api, seed.primary_app_id, { from, to });
  const expected =
    data.firing === null ? 'unavailable' : data.firing.length === 0 ? 'empty' : 'list';
  await expect(page.getByTestId('dashboard-firing-panel')).toHaveAttribute('data-state', expected);
  const history = page.getByTestId('dashboard-firing-history');
  await expect(history).toBeVisible();
  await history.click();
  await expectUrl(page, (s) => s.path === '/alerts', 'the panel opens the alert history');
};

/** Most firing rows the dashboard panel draws (src/features/dashboard/sections FIRING_ROWS_MAX). */
export const DASHBOARD_FIRING_ROWS = 20;

/** A firing row of the dashboard panel as the API item says it should be drawn. */
export interface FiringRowOracle {
  readonly id: string;
  readonly severity: string | null;
  readonly rule: string | null;
}

/**
 * The panel the API's `firing` answer asks for (WO-25 item schema, ADR-0010 C-212 (2)): its state,
 * its count and the rows in the API order, at most 20; an item without an event id is not a row.
 */
export function expectedFiringPanel(firing: readonly unknown[] | null): {
  state: 'unavailable' | 'empty' | 'list';
  count: string;
  rows: FiringRowOracle[];
} {
  if (firing === null) {
    return { state: 'unavailable', count: 'null', rows: [] };
  }
  const rows: FiringRowOracle[] = [];
  for (const item of firing) {
    const o = (typeof item === 'object' && item !== null ? item : {}) as {
      event_id?: unknown;
      severity?: unknown;
      rule?: { name?: unknown } | null;
    };
    if (typeof o.event_id !== 'string' && typeof o.event_id !== 'number') {
      continue;
    }
    const severity = typeof o.severity === 'string' ? o.severity.toLowerCase() : null;
    rows.push({
      id: String(o.event_id),
      severity: severity === 'warn' || severity === 'critical' ? severity : null,
      rule: typeof o.rule?.name === 'string' ? o.rule.name : null,
    });
  }
  return {
    state: firing.length === 0 ? 'empty' : 'list',
    count: String(firing.length),
    rows: rows.slice(0, DASHBOARD_FIRING_ROWS),
  };
}

/** The firing panel as drawn (one pass over the DOM). */
async function shownFiringPanel(page: Page): Promise<{
  state: string | null;
  count: string | null;
  rows: FiringRowOracle[];
}> {
  return page.evaluate(() => {
    const panel = document.querySelector('[data-testid="dashboard-firing-panel"]');
    const rows = [...(panel?.querySelectorAll('[data-testid="dashboard-firing-row"]') ?? [])].map(
      (row) => ({
        id: row.getAttribute('data-alert-id') ?? '',
        severity: row.getAttribute('data-severity'),
        rule: row.querySelector('[data-testid="dashboard-firing-rule"]')?.textContent ?? null,
      }),
    );
    return {
      state: panel?.getAttribute('data-state') ?? null,
      count: panel?.getAttribute('data-count') ?? null,
      rows,
    };
  });
}

/**
 * 2.3-5 (WO-25): the firing alerts of the app and environment are the engine's: the panel draws
 * the rows of the `firing` answer the page received (same order, event ids, severities and rule
 * names, at most 20, the count of all), that answer holds the seed's real FIRING alert (manifest
 * `alert`: a rule the operator created on the primary app, ADR-0010 C-212 (2)), and a click on its
 * row opens that alert in the history: /alerts with app=<primary> and alert=<event id>.
 */
const dashboardFiringAlerts: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const alert = manifestAlert(seed);
  expect(alert.app_id, 'the seed alert is on the primary app').toBe(seed.primary_app_id);
  const received: Promise<{ data?: { firing?: unknown[] | null } } | null>[] = [];
  const path = `/api/v1/apps/${String(seed.primary_app_id)}/dashboard`;
  page.on('response', (response) => {
    if (
      response.request().method() === 'GET' &&
      response.ok() &&
      new URL(response.url()).pathname === path
    ) {
      received.push(
        response.json().then(
          (json: unknown) => json as { data?: { firing?: unknown[] | null } },
          () => null,
        ),
      );
    }
  });
  await page.reload();
  await expectDashboardState(page);
  const lastFiring = async (): Promise<unknown[] | null | undefined> =>
    (await received.at(-1))?.data?.firing;
  await expect
    .poll(async () => (await lastFiring()) !== undefined, {
      message: 'the page received a GET /dashboard answer with firing',
    })
    .toBe(true);
  // The DOM equals the last answer the page received (no second request: alerts change over time).
  await expect
    .poll(
      async () => {
        const expected = expectedFiringPanel((await lastFiring()) ?? null);
        const shown = await shownFiringPanel(page);
        return JSON.stringify(shown) === JSON.stringify(expected)
          ? 'same'
          : JSON.stringify({ shown, expected });
      },
      { message: 'firing panel = firing of the GET /dashboard answer', timeout: 15_000 },
    )
    .toBe('same');
  const firing = (await lastFiring()) ?? null;
  expect(firing, 'the alert engine answers a list (WO-25), never null').not.toBeNull();
  const ids = expectedFiringPanel(firing).rows.map((row) => row.id);
  expect(
    ids,
    `the seed alert ${alert.event_id} fires on the dashboard of app ${String(alert.app_id)} (a reused stack older than the rule's 1 h window has no generator data left to fire on)`,
  ).toContain(alert.event_id);
  const row = page.locator(
    `[data-testid="dashboard-firing-row"][data-alert-id="${alert.event_id}"]`,
  );
  await expect(row).toHaveAttribute('data-severity', alert.severity);
  await expect(row.getByTestId('dashboard-firing-rule')).toHaveText(alert.rule_name);
  testInfo.annotations.push({
    type: 'dashboard-firing',
    description: `${String(firing?.length ?? 0)} firing alert(s) of app ${String(alert.app_id)}; seed event ${alert.event_id}`,
  });
  // Evidence (WO-25): the panel with the engine's real rows, before the row opens the history.
  const evidence = testInfo.outputPath('dashboard-firing.png');
  await page.getByTestId('dashboard-firing-panel').screenshot({ path: evidence });
  await testInfo.attach('dashboard-firing.png', { path: evidence, contentType: 'image/png' });
  await row.getByRole('link').click();
  await expectUrl(
    page,
    (s) =>
      s.path === '/alerts' &&
      s.params.get('alert') === alert.event_id &&
      s.params.get('app') === String(alert.app_id),
    'the row opens that alert in the history (/alerts?app=<id>&alert=<event id>)',
  );
};

/**
 * 2.3-6: the seven collection status items show the API collection of the same range: agent drops,
 * server drops, ingest lag, clock skew instances, excluded requests, late arrivals, running batches;
 * null (no source yet, or a past range for the live values) is the dash or "not measurable", never 0.
 */
const dashboardCollection: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const { from, to, fromMs, toMs } = pageRange(page);
  // The page's own answers (ADR-0010 C-220 follow-up): the live items change between two requests,
  // so they are compared with the last GET /dashboard response the page received, never with a
  // second request of the check. Recorded from a reload, so the first answer is captured too.
  const received = recordDashboardResponses(page);
  await page.reload();
  await expectDashboardState(page);
  const data = await apiDashboard(api, seed.primary_app_id, { from, to });
  const panel = page.getByTestId('dashboard-collection-panel');
  for (const { id, key } of COLLECTION_ITEMS) {
    const item = panel.getByTestId(`dashboard-collection-${id}`);
    const live = LIVE_COLLECTION_KEYS.has(key);
    const expected = async (): Promise<number | null | undefined> => {
      if (!live) {
        return data.collection[key];
      }
      const last = await lastAnswer(received);
      return last?.collection[key];
    };
    // Exact equality: null (not measured) and 0 are different values (T-UI-007).
    await expect
      .poll(
        async () => {
          const want = await expected();
          if (want === undefined) {
            return 'no dashboard response received yet';
          }
          const shown = await numAttr(item);
          return shown === want ? 'equal' : `shown ${String(shown)}, expected ${String(want)}`;
        },
        {
          message: `collection ${id} = collection.${key} of ${live ? "the page's last dashboard response" : 'the API asked the same question'}`,
        },
      )
      .toBe('equal');
    const value = await expected();
    if (value === null) {
      await expect(item, `${id}: null is shown as missing`).toContainText(MISSING);
    } else {
      await expect(item, `${id}: ${String(value)} is a value`).not.toContainText(MISSING);
    }
  }
  const last = await lastAnswer(received);
  expect(last, 'the page received a dashboard response').toBeDefined();
  // The page asked the question of its URL (same bounds, kind HTTP).
  expect(Date.parse(last?.url.searchParams.get('from') ?? ''), 'page request from').toBe(fromMs);
  expect(Date.parse(last?.url.searchParams.get('to') ?? ''), 'page request to').toBe(toMs);
  expect(last?.url.searchParams.get('kind') ?? 'HTTP', 'page request kind').toBe('HTTP');
  testInfo.annotations.push({
    type: 'collection',
    description: `api ${JSON.stringify(data.collection)}; page ${JSON.stringify(last?.collection ?? null)} (${String(received.length)} responses)`,
  });
};

/**
 * Collection items whose value changes from one request to the next while the stack runs: the
 * ingest lag (now − last ingested point) and the late arrivals of the seed agent's last batches.
 */
const LIVE_COLLECTION_KEYS: ReadonlySet<string> = new Set(['ingest_lag_s', 'late']);

/** A GET /apps/{id}/dashboard answer the page received. */
interface ReceivedDashboard {
  readonly url: URL;
  readonly body: Promise<{ data?: { collection?: DashboardData['collection'] } } | null>;
}

/** Records the successful GET /apps/{id}/dashboard responses of the page from now on, in order. */
function recordDashboardResponses(page: Page): ReceivedDashboard[] {
  const received: ReceivedDashboard[] = [];
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (
      response.request().method() === 'GET' &&
      response.ok() &&
      /^\/api\/v1\/apps\/\d+\/dashboard$/.test(url.pathname)
    ) {
      received.push({
        url,
        body: response.json().then(
          (json: unknown) => json as { data?: { collection?: DashboardData['collection'] } },
          () => null,
        ),
      });
    }
  });
  return received;
}

/** The collection of the last recorded answer (undefined before the first one arrived). */
async function lastAnswer(
  received: readonly ReceivedDashboard[],
): Promise<{ url: URL; collection: DashboardData['collection'] } | undefined> {
  const last = received.at(-1);
  if (last === undefined) {
    return undefined;
  }
  const collection = (await last.body)?.data?.collection;
  return collection === undefined ? undefined : { url: last.url, collection };
}

/**
 * 2.3-7: live (5-second refresh): the counter push of the docs/14 §5 stub updates the tiles and the
 * instance state and stamps data-live-ts; leaving live ends the subscription, and a past range shows
 * the REST answer only (a push changes nothing).
 */
const dashboardLive: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const ws = ctx.ws;
  if (ws === undefined) {
    throw new Error('dashboardLive needs the ws stub fixture');
  }
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const hours = liveHours(seed);
  await page.goto(`/apps/${String(appId)}/dashboard?from=now-${String(hours)}h&live=1`);
  const subscription = await ws.waitForSubscription('counter');
  expect(subscription.params).toMatchObject({ app: appId });
  const root = await expectDashboardState(page);
  const now = Date.now();
  const rest = await apiDashboard(api, appId, {
    from: new Date(now - hours * HOUR).toISOString(),
    to: new Date(now).toISOString(),
  });
  const instance = rest.instances[0];
  const nextState = instance?.state === 'UP' ? 'DOWN' : 'UP';
  const frame = {
    tps: 4242.5,
    avg_ms: 83.25,
    p95_ms: 410.5,
    p99_ms: 1200.75,
    error_rate: 0.0123,
    active: 37,
    heap_used: 268_435_456,
    gc_pause_ms: 12.5,
    cpu: 0.42,
    per_instance:
      instance === undefined
        ? []
        : [{ instance_id: instance.instance_id, active: 9, state: nextState }],
  };
  const pushedAt = Date.now();
  expect(ws.push('counter', frame), 'counter subscriptions of the page').toBeGreaterThan(0);
  const pushedTiles = {
    tps: frame.tps,
    avg_ms: frame.avg_ms,
    p95_ms: frame.p95_ms,
    p99_ms: frame.p99_ms,
    error_rate: frame.error_rate,
    active: frame.active,
  };
  for (const { id, key } of TILES) {
    await expect
      .poll(() => numAttr(tile(page, id)), { message: `tile ${id} after the push` })
      .toBe(pushedTiles[key]);
  }
  const stamped = liveTsMs(await root.getAttribute('data-live-ts'));
  expect(stamped, 'data-live-ts of the applied push').not.toBeNull();
  expect(stamped ?? 0).toBeGreaterThanOrEqual(pushedAt - 1000);
  expect(stamped ?? 0).toBeLessThanOrEqual(Date.now() + 1000);
  if (instance !== undefined) {
    await expect(
      page.locator(
        `[data-testid="dashboard-instance-tile"][data-instance-id="${String(instance.instance_id)}"]`,
      ),
    ).toHaveAttribute('data-state', nextState);
  }
  // Every frame is applied (the server sends one every 5 s).
  ws.push('counter', { ...frame, tps: 4343.5 });
  await expect.poll(() => numAttr(tile(page, 'tps'))).toBe(4343.5);
  await expect
    .poll(async () => liveTsMs(await root.getAttribute('data-live-ts')) ?? 0)
    .toBeGreaterThanOrEqual(stamped ?? 0);

  // Leaving now ends live and the subscription.
  await page.getByTestId('time-range-prev').click();
  await expectUrl(page, (s) => !s.live && s.time.kind === 'absolute', 'prev turns live off');
  await expect
    .poll(() => ws.subscriptions('counter').length, { message: 'no counter subscription' })
    .toBe(0);

  // A past range: the REST answer, no subscription, a push changes nothing.
  const range = seed.generator.range;
  await openDashboard(page, dashboardPath(appId, range.start, range.end));
  const past = await apiDashboard(api, appId, { from: range.start, to: range.end });
  expect(ws.subscriptions('counter')).toEqual([]);
  expect(ws.push('counter', { ...frame, tps: 9999.5 })).toBe(0);
  await page.waitForTimeout(1000);
  await expectTiles(page, past.tiles);
  expect(await root.getAttribute('data-live-ts'), 'no live data on a past range').toBeNull();
  testInfo.annotations.push({
    type: 'live',
    description: `pushed ${JSON.stringify(frame)}; past range tiles ${JSON.stringify(past.tiles)}`,
  });
};

/**
 * 05 §2.0-1 (common.yaml, chart source): a drag on a dashboard chart sets the global absolute range
 * of the dragged span (to the pixel), and the next screen opens with that range.
 */
const dashboardChartDrag: DashboardCheck = async (ctx) => {
  const { page, seed } = ctx;
  const start = pageRange(page);
  await expectDashboardState(page);
  const drag = await dragChart(page, 'response', 0.25, 0.75);
  const s = await expectUrl(
    page,
    (x) => x.time.kind === 'absolute' && absolute(x).fromMs !== start.fromMs,
    'the chart drag writes the dragged range',
  );
  const perPx = (start.toMs - start.fromMs) / drag.box.width;
  const { fromMs, toMs } = absolute(s);
  expect(Math.abs(fromMs - (start.fromMs + drag.startPx * perPx))).toBeLessThanOrEqual(2 * perPx);
  expect(Math.abs(toMs - (start.fromMs + drag.endPx * perPx))).toBeLessThanOrEqual(2 * perPx);
  expect(s.live).toBe(false);
  await expect(page.getByTestId('time-range-preset')).toHaveAttribute('data-range', 'absolute');
  await page.getByTestId('side-menu-xlog').click();
  const xlog = await expectUrl(
    page,
    (x) => x.path === `/apps/${String(seed.primary_app_id)}/xlog`,
    'the XLog screen',
  );
  expect(sameTime(xlog.time, s.time), 'the XLog screen opens with the dragged range').toBe(true);
};

/**
 * 05 §2.0-5 (common.yaml): a click on a time series point opens the menu whose "Open XLog" link is
 * the clicked bucket ± range/60 (at least 1 minute) with kind kept; the events of the range are
 * markers on the charts, as many as GET /events lists for the same range plus the alert transitions
 * of GET /alerts/events (ADR-0010 C-226 (8)).
 */
const dashboardPointMenuMarkers: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = seed.primary_app_id;
  const range = pageRange(page);
  await expectDashboardState(page);
  const data = await apiDashboard(api, appId, { from: range.from, to: range.to });
  const events = await apiEvents(api, appId, range.from, range.to);
  expect(data.events.length, 'events of the dashboard answer = GET /events').toBe(events.length);
  const transitions = await apiAlertTransitions(api, appId, range.from, range.to);
  const seeded = seed.events.filter((e) => e.app_id === appId);
  expect(seeded.length, 'seed events of the primary app').toBe(2);
  for (const id of ['tps', 'response'] as ChartId[]) {
    await expect
      .poll(() => markerTotal(page, id), { message: `${id}: markers (events + alert transitions)` })
      .toBe(events.length + transitions.length);
    for (const event of seeded) {
      await expect(
        chart(page, id).locator(`[data-testid="chart-marker"][data-marker-id="${event.event_id}"]`),
      ).toHaveCount(1);
    }
  }

  const box = await plotBoxOf(page, 'tps');
  const fraction = 0.4;
  await page.mouse.click(box.x + box.width * fraction, box.y + box.height * 0.6);
  await expect(page.getByTestId('chart-click-menu')).toBeVisible();
  await page.getByTestId('chart-menu-xlog').click();
  const xlog = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/xlog`,
    'the menu opens the XLog screen',
  );
  const { fromMs, toMs } = absolute(xlog);
  const length = range.toMs - range.fromMs;
  const half = Math.max(length / 60, MINUTE);
  expect(
    Math.abs(toMs - fromMs - 2 * half),
    'XLog window = ± range/60 (min 1 minute)',
  ).toBeLessThanOrEqual(1);
  const centre = (fromMs + toMs) / 2;
  const buckets = data.series.ts.map((ts) => Date.parse(ts));
  expect(
    buckets.some((b) => Math.abs(b - centre) <= 1),
    'the window is centred on a bucket of the series',
  ).toBe(true);
  const step = buckets.length > 1 ? (buckets[1] ?? 0) - (buckets[0] ?? 0) : length;
  const perPx = length / box.width;
  expect(Math.abs(centre - (range.fromMs + fraction * length))).toBeLessThanOrEqual(
    step + 2 * perPx,
  );
  expect(effectiveKind(xlog)).toBe('HTTP');
  expect(xlog.live).toBe(false);
  testInfo.annotations.push({
    type: 'click-menu',
    description: `centre ${new Date(centre).toISOString()}, window ${String(toMs - fromMs)} ms, ${String(events.length)} events`,
  });
};

/** Allowed difference, CSS px, between a shaded box and the outage's position on the time axis. */
const OUTAGE_BAND_TOLERANCE_PX = 3;

/**
 * 2.3-2 and 05 §2.0-5 collection outage shading on the dashboard (WO-22, D-18, C-5, ADR-0010
 * C-205 (2), C-216 (2)): the stack setup cut the traffic agent's gRPC path until the instance was
 * STALE and restored it (seed manifest `outage`). On the traffic app's dashboard around it, GET
 * /dashboard of the same bounds holds that session_lost outage of the instance (the recorded server
 * times), every one of the nine charts draws one shaded box per API outage at its position on the
 * time axis (±3 px) with its from, and each chart's legend counts the API outages ("N collection
 * gaps").
 */
const dashboardOutageShading: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const recorded = seed.outage;
  if (recorded === null) {
    const why =
      seed.missing_optional.find((m) => m.category === 'outage')?.reason ?? 'no reason recorded';
    throw new Error(`the stack setup made no collection outage (manifest outage null): ${why}`);
  }
  const api = await ctx.apiAs(ORACLE_ROLE);
  const appId = recorded.app_id;
  // From the seed traffic (transactions on the charts) to 10 s after the instance was UP again:
  // the setup stops the agent right after that, and where the stop sends no AgentBye (Windows ends
  // the process at once) the instance turns STALE again 15 s or more after the stop (stale-after
  // 20 s, heartbeat 5 s, ADR-0010 C-216 (1)), a later outage this range leaves out.
  const fromMs = Math.min(
    Math.floor(Date.parse(seed.traffic.range.start) / MINUTE) * MINUTE,
    Math.floor(Date.parse(recorded.cut_at) / MINUTE) * MINUTE - 2 * MINUTE,
  );
  const toMs = Math.ceil((Date.parse(recorded.up_seen_at) + 10_000) / 1000) * 1000;
  const from = new Date(fromMs).toISOString().replace('.000Z', 'Z');
  const to = new Date(toMs).toISOString().replace('.000Z', 'Z');
  const data = await apiDashboard(api, appId, { from, to });
  testInfo.annotations.push({
    type: 'outages',
    description: `${from} .. ${to}: ${JSON.stringify(data.outages)}; seeded cut ${recorded.cut_at}, STALE seen ${recorded.stale_seen_at}, thawed ${recorded.thawed_at}, UP seen ${recorded.up_seen_at}`,
  });
  const ours = data.outages.filter(
    (o) => o.instance_id === recorded.instance_id && o.reason === 'session_lost',
  );
  expect(
    ours.map((o) => [Date.parse(o.from), o.to === null ? null : Date.parse(o.to)]),
    `GET /dashboard outages of instance ${String(recorded.instance_id)} = the seeded session_lost outage`,
  ).toEqual([[Date.parse(recorded.outage_from), Date.parse(recorded.outage_to)]]);
  const inRange = data.outages
    .map((o) => ({ from: Date.parse(o.from), to: o.to === null ? toMs : Date.parse(o.to) }))
    .filter((o) => Math.min(o.to, toMs) > Math.max(o.from, fromMs))
    .sort((a, b) => a.from - b.from);

  await openDashboard(page, dashboardPath(appId, from, to));
  const span = toMs - fromMs;
  const legend = t(
    `common:component.chart.outages_${inRange.length === 1 ? 'one' : 'other'}`,
    'en',
    { count: String(inRange.length) },
  );
  for (const id of CHARTS) {
    const box = await plotBoxOf(page, id);
    await expect(
      chart(page, id).getByTestId('chart-outage-legend'),
      `chart ${id}: the legend counts the API outages`,
    ).toHaveText(legend);
    await expect
      .poll(
        async () => {
          const shaded = await chart(page, id)
            .getByTestId('chart-outage')
            .evaluateAll((nodes) =>
              nodes.map((n) => {
                const r = n.getBoundingClientRect();
                return {
                  from: Number(n.getAttribute('data-from')),
                  left: r.left,
                  right: r.right,
                };
              }),
            );
          shaded.sort((a, b) => a.from - b.from);
          const matched =
            shaded.length === inRange.length &&
            inRange.every((o, i) => {
              const s = shaded[i];
              if (s === undefined || s.from !== o.from) {
                return false;
              }
              const x1 = box.x + ((Math.max(o.from, fromMs) - fromMs) / span) * box.width;
              const x2 = box.x + ((Math.min(o.to, toMs) - fromMs) / span) * box.width;
              return (
                Math.abs(s.left - x1) <= OUTAGE_BAND_TOLERANCE_PX &&
                Math.abs(s.right - Math.max(x2, x1 + 2)) <= OUTAGE_BAND_TOLERANCE_PX
              );
            });
          return matched
            ? 'shading = API outages'
            : `chart ${id}: shaded ${JSON.stringify(shaded)} vs outages ${JSON.stringify(inRange)} (plot x ${String(box.x)}, width ${String(box.width)})`;
        },
        { timeout: 10_000, message: `chart ${id}: shaded boxes vs GET /dashboard outages` },
      )
      .toBe('shading = API outages');
  }
};

/**
 * 2.3-7 against the real WebSocket server of WO-22 (wsMode 'server', ADR-0010 C-216 (2)): the live
 * dashboard subscribes to the server's `counter` topic for the application; the server's own 5 s
 * push is applied to the tiles (each tile shows the pushed value; a field the push leaves out keeps
 * the REST value) and its time becomes data-live-ts; leaving now ends the subscription on the
 * server (no counter push after it).
 */
const dashboardLiveServer: DashboardCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const ws = ctx.ws;
  if (ws?.mode !== 'server') {
    throw new Error('dashboardLiveServer needs the ws fixture in server mode');
  }
  const appId = seed.primary_app_id;
  const hours = liveHours(seed);
  await page.goto(`/apps/${String(appId)}/dashboard?from=now-${String(hours)}h&live=1`);
  const subscription = await ws.waitForSubscription('counter', 20_000);
  expect(subscription.params).toMatchObject({ app: appId });
  const root = await expectDashboardState(page);
  // The server pushes every 5 s; take one that arrived after the subscription was acknowledged.
  const ackedAt = Date.now();
  const first = await ws.waitForServerFrame(
    (f) =>
      f.at >= ackedAt &&
      f.frame?.op === 'push' &&
      f.frame.topic === 'counter' &&
      f.frame.id === subscription.id,
    20_000,
    `a counter push of the server for app ${String(appId)}`,
  );
  const firstT = first.frame?.op === 'push' ? Date.parse(first.frame.t) : Number.NaN;
  // The page applies that push or a newer one: data-live-ts is the time of a server push, and the
  // tiles carry that push's values.
  await expect
    .poll(async () => liveTsMs(await root.getAttribute('data-live-ts')) ?? 0, {
      message: 'data-live-ts reaches the server push time',
      timeout: 15_000,
    })
    .toBeGreaterThanOrEqual(firstT);
  const liveTs = liveTsMs(await root.getAttribute('data-live-ts'));
  const applied = ws
    .serverFrames()
    .filter(
      (f) =>
        f.frame?.op === 'push' &&
        f.frame.topic === 'counter' &&
        f.frame.id === subscription.id &&
        Date.parse(f.frame.t) === liveTs,
    )
    .at(-1);
  expect(applied, 'data-live-ts is the time of a server counter push').toBeDefined();
  const values: Record<string, unknown> = applied?.frame?.op === 'push' ? applied.frame.data : {};
  for (const { id, key } of TILES) {
    const pushed = values[key];
    if (pushed === undefined) {
      continue;
    }
    await expect
      .poll(() => numAttr(tile(page, id)), { message: `tile ${id} = the server push ${key}` })
      .toBe(pushed);
  }
  testInfo.annotations.push({
    type: 'live-server',
    description: `server counter push t=${String(liveTs)} ${JSON.stringify(values)}`,
  });

  // Leaving now ends live and the server subscription: no counter push after the unsubscribe.
  await page.getByTestId('time-range-prev').click();
  await expectUrl(page, (s) => !s.live && s.time.kind === 'absolute', 'prev turns live off');
  await expect
    .poll(() => ws.subscriptions('counter').length, { message: 'no counter subscription' })
    .toBe(0);
  const offAt = Date.now();
  await page.waitForTimeout(6_000);
  expect(
    ws
      .serverFrames()
      .filter((f) => f.at > offAt + 500 && f.frame?.op === 'push' && f.frame.topic === 'counter'),
    'no counter push after leaving live',
  ).toEqual([]);
};

/**
 * The tile of `instanceId` on the dashboard of `appId` over [now - 15 min, now + 1 min) shows the
 * state and WARN reasons of GET /dashboard asked the same question (05 §2.3-3, C-216 (2): reasons
 * only while WARN), with the state label of the badge. Waits until the API reports `expected`
 * (one of the states) first; resolves with the API's tile.
 */
export async function expectInstanceTileOfApi(
  page: Page,
  api: ApiSession,
  appId: number,
  instanceId: number,
  expected: readonly string[],
  timeoutMs = 60_000,
): Promise<{ state: string; warn_reasons: string[] }> {
  const window = (): { from: string; to: string } => {
    const now = Date.now();
    return {
      from: new Date(Math.floor((now - 15 * MINUTE) / 1000) * 1000).toISOString(),
      to: new Date(Math.ceil((now + MINUTE) / 1000) * 1000).toISOString(),
    };
  };
  await expect
    .poll(
      async () => {
        const data = await apiDashboard(api, appId, window());
        return data.instances.find((i) => i.instance_id === instanceId)?.state ?? 'absent';
      },
      { timeout: timeoutMs, intervals: [1000], message: `instance ${String(instanceId)} state` },
    )
    .toMatch(new RegExp(`^(${expected.join('|')})$`));
  const range = window();
  await page.goto(dashboardPath(appId, range.from, range.to));
  await expect(page.getByTestId('dashboard-page')).toHaveAttribute(
    'data-state',
    /^(ready|empty)$/,
    { timeout: 30_000 },
  );
  const data = await apiDashboard(api, appId, range);
  const apiTile = data.instances.find((i) => i.instance_id === instanceId);
  expect(apiTile, `instance ${String(instanceId)} in GET /dashboard`).toBeDefined();
  if (apiTile === undefined) {
    throw new Error('unreachable');
  }
  const reasons = apiTile.state === 'WARN' ? [...new Set(apiTile.warn_reasons)] : [];
  const tileEl = page.locator(
    `[data-testid="dashboard-instance-tile"][data-instance-id="${String(instanceId)}"]`,
  );
  await expect(tileEl).toHaveAttribute('data-state', apiTile.state);
  await expect(tileEl).toHaveAttribute('data-warn-reasons', reasons.join(','));
  await expect(tileEl.getByTestId('dashboard-instance-tile-status')).toHaveAttribute(
    'data-status',
    apiTile.state.toUpperCase(),
  );
  return { state: apiTile.state, warn_reasons: reasons };
}

/**
 * 05 §2.3-3 STALE and WARN tiles on real data (WO-39, ADR-0010 C-217 (2)): an application of the
 * check's own with shop-boot3 and the stack's agent (token issued through the API): the kill
 * switch makes its instance WARN kill_switch, then the agent stops and the instance goes STALE
 * (the E2E server's stale-after is 20 s); each time the tile = GET /dashboard. The app is deleted
 * afterwards. Without a host JVM (e2e:docker) it is skipped with the reason.
 */
const dashboardWarnStaleLive: DashboardCheck = async ({ seed, testInfo, apiAs, page }) => {
  const problem = ownShopProblem(seed.run_id);
  testInfo.skip(problem !== null, problem ?? '');
  testInfo.setTimeout(6 * 60_000);
  const api = await apiAs('admin');
  const suffix = runSuffix(seed.run_id);
  const app = await createApp(api, { name: `wo39-dash-${suffix}`, env: 'e2e' });
  let shop: OwnShop | null = null;
  try {
    const issued = await issueToken(api, app.app_id);
    const tokenFile = writeTokenFile(seed.run_id, `wo39-dash-${suffix}.token`, issued.token);
    shop = await startOwnShop(seed.run_id, api, app, tokenFile, `wo39-dash-${suffix}`);
    const off = await apiKillSwitch(api, app.app_id, false, [shop.instanceId], 'WO-39 E2E 2.3-3');
    expect(await changeResult(api, off.change_id, shop.instanceId)).toBe('applied');
    const warn = await expectInstanceTileOfApi(page, api, app.app_id, shop.instanceId, ['WARN']);
    expect(warn.warn_reasons).toContain('kill_switch');
    await testInfo.attach('dashboard-warn.png', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
    const on = await apiKillSwitch(api, app.app_id, true, [shop.instanceId], 'WO-39 E2E 2.3-3');
    expect(await changeResult(api, on.change_id, shop.instanceId)).toBe('applied');
    await shop.stop();
    const stale = await expectInstanceTileOfApi(
      page,
      api,
      app.app_id,
      shop.instanceId,
      ['STALE'],
      90_000,
    );
    expect(stale.warn_reasons).toEqual([]);
    await testInfo.attach('dashboard-stale.png', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  } finally {
    await shop?.stop();
    await deleteAppQuietly(api, { app_id: app.app_id, name: String(app.name) }, testInfo);
  }
};

export const DASHBOARD_CHECKS: Readonly<Record<string, DashboardCheck>> = {
  dashboardTiles,
  dashboardCharts,
  dashboardInstanceTiles,
  dashboardCompareTable,
  dashboardFiring,
  dashboardFiringAlerts,
  dashboardCollection,
  dashboardLive,
  dashboardChartDrag,
  dashboardPointMenuMarkers,
  dashboardOutageShading,
  dashboardLiveServer,
  dashboardWarnStaleLive,
};
