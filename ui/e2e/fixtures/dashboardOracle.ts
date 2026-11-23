// SPDX-License-Identifier: Apache-2.0
//
// Helpers of the dashboard specs (WO-32, docs/05 §2.3, docs/15 §9.2): open the screen and wait for
// its data, read the values it shows through the data-testid contract of the WO-32 plan (§3), and
// ask the real API the same questions the screen asks (GET /apps/{id}/dashboard, /instances/compare,
// /events of the same bounds) so that the specs assert exact values from the server, never "at
// least one". Contract:
//   dashboard-page                       data-state loading | ready | empty | error, data-live-ts
//   dashboard-tile-<tile>                data-value (raw API value, "null" or absent for null)
//   dashboard-tile-<tile>-delta          data-basis prev | 7d, data-value (raw API delta)
//   dashboard-tile-ref, dashboard-baseline
//   dashboard-chart-<chart>              TimeSeriesChart (chart-legend-item, chart-marker, ...)
//   dashboard-instance-tiles > dashboard-instance-tile   data-instance-id, data-state
//   dashboard-compare-table              DataTable, rows data-row-key = instance_id, cells
//                                        data-column, outlier cells data-outlier p95|error_rate|cpu
//   dashboard-firing-panel               data-state unavailable | empty | list; dashboard-firing-history
//   dashboard-collection-panel > dashboard-collection-<item>   data-value
import { expect, type Locator, type Page } from '@playwright/test';

import type { ApiSession } from '../stack/api';

import type { Box } from './xlogPage';

/**
 * The user of the specs' own API queries: not the browser's (viewer), so that the screen under test
 * keeps its own request budget (the same choice as the XLog specs, xlogPage.ts EXPECTATION_ROLE).
 */
export const ORACLE_ROLE = 'admin' as const;

/** The summary tiles (docs/05 §2.3-1) and their field of `tiles`. */
export const TILES = [
  { id: 'tps', key: 'tps' },
  { id: 'avg', key: 'avg_ms' },
  { id: 'p95', key: 'p95_ms' },
  { id: 'p99', key: 'p99_ms' },
  { id: 'error-rate', key: 'error_rate' },
  { id: 'active', key: 'active' },
] as const;
export type TileId = (typeof TILES)[number]['id'];

/**
 * The tiles with a change (`tiles.delta`: tps, avg, p95, p99 and active relative, error rate
 * absolute; avg, p99 and active since ADR-0010 C-212 (1)).
 */
export const DELTAS = [
  { id: 'tps', key: 'tps' },
  { id: 'avg', key: 'avg_ms' },
  { id: 'p95', key: 'p95_ms' },
  { id: 'p99', key: 'p99_ms' },
  { id: 'error-rate', key: 'error_rate' },
  { id: 'active', key: 'active' },
] as const;

/** The nine charts of docs/15 §9.2, in layout order. */
export const CHARTS = [
  'tps',
  'response',
  'error-rate',
  'active',
  'heap-gc',
  'cpu',
  'tomcat',
  'pool',
  'ext',
] as const;
export type ChartId = (typeof CHARTS)[number];

/** The seven items of the collection status panel (docs/05 §2.3-6) and their field. */
export const COLLECTION_ITEMS = [
  { id: 'agent-drops', key: 'agent_drops' },
  { id: 'server-drops', key: 'server_drops' },
  { id: 'ingest-lag', key: 'ingest_lag_s' },
  { id: 'clock-skew', key: 'clock_skew_instances' },
  { id: 'excluded', key: 'excluded' },
  { id: 'late', key: 'late' },
  { id: 'batch-running', key: 'batch_running' },
] as const;

type Values = (number | null)[];

/** GET /apps/{appId}/dashboard data (docs/api/openapi.yaml Dashboard). */
export interface DashboardData {
  readonly tiles: {
    readonly tps: number | null;
    readonly avg_ms: number | null;
    readonly p95_ms: number | null;
    readonly p99_ms: number | null;
    readonly error_rate: number | null;
    readonly active: number | null;
    readonly count: number;
    readonly delta: {
      readonly basis: string;
      readonly tps: number | null;
      readonly avg_ms: number | null;
      readonly p95_ms: number | null;
      readonly p99_ms: number | null;
      readonly error_rate: number | null;
      readonly active: number | null;
    };
  };
  readonly series: {
    readonly ts: string[];
    readonly tps: Values;
    readonly avg_ms: Values;
    readonly p95_ms: Values;
    readonly p99_ms: Values;
    readonly error_rate: Values;
    readonly count: Values;
    readonly active: Values;
    readonly heap_used: Values;
    readonly heap_max: Values;
    readonly gc_pause_ms: Values;
    readonly cpu: Values;
    readonly system_cpu: Values;
    readonly tomcat_busy_ratio: Values;
    readonly pool_usage_ratio: Readonly<Record<string, Values>>;
    readonly ext_time_ms: Values;
    readonly ext_errors: Values | null;
  };
  readonly baseline?: {
    readonly offset: string;
    readonly ts: string[];
    readonly avg_ms: Values;
    readonly p95_ms: Values;
    readonly p99_ms: Values;
    readonly tps: Values;
    readonly error_rate: Values;
  } | null;
  readonly events: readonly DashboardEvent[];
  readonly outages: readonly {
    from: string;
    to: string | null;
    reason: string;
    instance_id: number | null;
  }[];
  readonly instances: readonly DashboardInstance[];
  readonly collection: Readonly<
    Record<(typeof COLLECTION_ITEMS)[number]['key'] | 'server_drops_quota', number | null>
  >;
  readonly firing: readonly unknown[] | null;
}

export interface DashboardEvent {
  readonly event_id: string;
  readonly type: string;
  readonly ts: string;
  readonly title: string | null;
  readonly version: string | null;
}

export interface DashboardInstance {
  readonly instance_id: number;
  readonly name: string;
  readonly state: string;
  readonly active: number | null;
  readonly tps: number | null;
  readonly p95_ms: number | null;
  /** WARN conditions while WARN (ADR-0010 C-216 (2)), empty otherwise. */
  readonly warn_reasons: readonly string[];
}

/** GET /apps/{appId}/instances/compare row (docs/api/openapi.yaml InstanceComparison). */
export interface InstanceComparison {
  readonly instance_id: number;
  readonly name: string;
  readonly state: string;
  readonly tps: number | null;
  readonly p95_ms: number | null;
  readonly error_rate: number | null;
  readonly active: number | null;
  readonly heap_ratio: number | null;
  readonly cpu: number | null;
  readonly pool_usage_ratio: number | null;
  readonly uptime_s: number | null;
  readonly outlier: {
    readonly p95: boolean | null;
    readonly error_rate: boolean | null;
    readonly cpu: boolean | null;
  };
}

export interface DashboardQuery {
  readonly from: string;
  readonly to: string;
  readonly kind?: string;
  readonly baseline?: '1d' | '7d';
  readonly instance?: readonly number[];
}

/** The dashboard route of an application (absolute range, extra query parameters). */
export function dashboardPath(
  appId: number,
  from: string,
  to: string,
  extra: Readonly<Record<string, string>> = {},
): string {
  const params = new URLSearchParams({ from, to, ...extra });
  return `/apps/${String(appId)}/dashboard?${params.toString()}`;
}

/** GET /apps/{appId}/dashboard as the screen asks it (kind HTTP unless named). */
export async function apiDashboard(
  api: ApiSession,
  appId: number,
  q: DashboardQuery,
): Promise<DashboardData> {
  return api.data<DashboardData>(`/apps/${String(appId)}/dashboard`, {
    from: q.from,
    to: q.to,
    kind: q.kind ?? 'HTTP',
    baseline: q.baseline,
    instance: q.instance?.map(String),
  });
}

/** GET /apps/{appId}/instances/compare of the same bounds. */
export async function apiCompare(
  api: ApiSession,
  appId: number,
  q: { readonly from: string; readonly to: string; readonly kind?: string },
): Promise<InstanceComparison[]> {
  return api.data<InstanceComparison[]>(`/apps/${String(appId)}/instances/compare`, {
    from: q.from,
    to: q.to,
    kind: q.kind ?? 'HTTP',
  });
}

/** GET /apps/{appId}/events of the same bounds. */
export async function apiEvents(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
): Promise<DashboardEvent[]> {
  return api.data<DashboardEvent[]>(`/apps/${String(appId)}/events`, { from, to });
}

/** Waits until the dashboard shows `state` (default ready: every panel has its data). */
export async function expectDashboardState(
  page: Page,
  state: 'ready' | 'empty' | 'error' | 'loading' = 'ready',
): Promise<Locator> {
  const root = page.getByTestId('dashboard-page');
  await expect(root).toHaveAttribute('data-state', state, { timeout: 30_000 });
  return root;
}

/** Opens `path` and waits for the ready dashboard. */
export async function openDashboard(page: Page, path: string): Promise<Locator> {
  await page.goto(path);
  return expectDashboardState(page);
}

/** A raw number attribute: "null", "" or absent mean null (the contract writes null as "null"). */
export function parseValueAttr(value: string | null): number | null {
  if (value === null || value === '' || value === 'null') {
    return null;
  }
  const n = Number(value);
  if (!Number.isFinite(n)) {
    throw new Error(`not a number: ${value}`);
  }
  return n;
}

/** The number of a `data-*` attribute of `locator` (null for "null" or absent). */
export async function numAttr(locator: Locator, name = 'data-value'): Promise<number | null> {
  return parseValueAttr(await locator.getAttribute(name));
}

/** The tile of `id`. */
export function tile(page: Page, id: TileId): Locator {
  return page.getByTestId(`dashboard-tile-${id}`);
}

/** Every tile shows exactly the API value of `tiles` (null as null). */
export async function expectTiles(page: Page, tiles: DashboardData['tiles']): Promise<void> {
  for (const { id, key } of TILES) {
    await expect
      .poll(() => numAttr(tile(page, id)), { message: `tile ${id} = tiles.${key}` })
      .toBe(tiles[key]);
  }
}

/** Every tile with a change shows the API delta and its basis. */
export async function expectDeltas(
  page: Page,
  delta: DashboardData['tiles']['delta'],
): Promise<void> {
  for (const { id, key } of DELTAS) {
    const element = page.getByTestId(`dashboard-tile-${id}-delta`);
    await expect(element, `tile ${id}: delta basis`).toHaveAttribute('data-basis', delta.basis);
    await expect
      .poll(() => numAttr(element), { message: `tile ${id}: delta = tiles.delta.${key}` })
      .toBe(delta[key]);
  }
}

/** The chart of `id`. */
export function chart(page: Page, id: ChartId): Locator {
  return page.getByTestId(`dashboard-chart-${id}`);
}

/**
 * Series each chart draws for an API answer (docs/15 §9.2): TPS; avg, p95, p99; error rate; active;
 * heap used, heap max, GC pause; process and system CPU (ADR-0010 C-212 (1)); Tomcat busy ratio;
 * one per pool; external time and, when the API has them, external errors. Baseline lines are
 * counted apart (`:baseline` legend keys).
 */
export function expectedSeriesCounts(series: DashboardData['series']): Record<ChartId, number> {
  return {
    tps: 1,
    response: 3,
    'error-rate': 1,
    active: 1,
    'heap-gc': 3,
    cpu: 2,
    tomcat: 1,
    pool: Object.keys(series.pool_usage_ratio).length,
    ext: series.ext_errors === null ? 1 : 2,
  };
}

/** Buckets with a value and their sum (6 decimals), as the screen's data-summary has them. */
export interface SeriesSummary {
  readonly n: number;
  readonly sum: number;
}

/** The summary of an API series (null and missing buckets not counted). */
export function summaryOf(values: readonly (number | null)[]): SeriesSummary {
  let n = 0;
  let sum = 0;
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      n += 1;
      sum += value;
    }
  }
  return { n, sum: Math.round(sum * 1e6) / 1e6 };
}

/**
 * The series summary a chart shows (`dashboard-chart-<chart>-values` data-summary, docs/15 §9.2):
 * series key → buckets with a value and their sum.
 */
export async function shownSummary(
  page: Page,
  id: ChartId,
): Promise<Readonly<Record<string, SeriesSummary>>> {
  const text = await page.getByTestId(`dashboard-chart-${id}-values`).getAttribute('data-summary');
  return JSON.parse(text ?? '{}') as Record<string, SeriesSummary>;
}

/** Legend keys (data-series) of a chart, baseline lines included (`<key>:baseline`). */
export async function legendKeys(page: Page, id: ChartId): Promise<string[]> {
  return chart(page, id)
    .getByTestId('chart-legend-item')
    .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-series') ?? ''));
}

/** Sum of the marker groups' data-count of a chart (close events share one marker). */
export async function markerTotal(page: Page, id: ChartId): Promise<number> {
  const counts = await chart(page, id)
    .getByTestId('chart-marker')
    .evaluateAll((nodes) => nodes.map((n) => Number(n.getAttribute('data-count') ?? '0')));
  return counts.reduce((a, b) => a + b, 0);
}

/** The uPlot interaction layer (u-over) of a chart. */
export function plot(page: Page, id: ChartId): Locator {
  return chart(page, id).locator('.u-over');
}

/** Bounding box of a chart's plot area. */
export async function plotBoxOf(page: Page, id: ChartId): Promise<Box> {
  const box = await plot(page, id).boundingBox();
  if (box === null || box.width < 10) {
    throw new Error(`chart ${id}: no plot area`);
  }
  return box;
}

/**
 * Drags over the plot of chart `id` from fraction `a` to `b` of its width (60 % of its height,
 * below the markers); resolves with the pixel positions of the drag inside the plot.
 */
export async function dragChart(
  page: Page,
  id: ChartId,
  a: number,
  b: number,
): Promise<{ box: Box; startPx: number; endPx: number }> {
  const box = await plotBoxOf(page, id);
  const y = box.y + box.height * 0.6;
  const startPx = Math.round(box.width * a);
  const endPx = Math.round(box.width * b);
  await page.mouse.move(box.x + startPx, y);
  await page.mouse.down();
  await page.mouse.move(box.x + endPx, y, { steps: 12 });
  await page.mouse.up();
  return { box, startPx, endPx };
}

/** Requests the page sends to GET /apps/{id}/dashboard from now on. */
export function recordDashboardRequests(page: Page): URL[] {
  const sent: URL[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (request.method() === 'GET' && /^\/api\/v1\/apps\/\d+\/dashboard$/.test(url.pathname)) {
      sent.push(url);
    }
  });
  return sent;
}

/** What the screen shows of its data: tile values and each chart's legend and data table. */
export interface DashboardSnapshot {
  readonly tiles: Record<string, string | null>;
  readonly charts: Record<string, { legend: string[]; table: string }>;
  readonly instances: string[];
}

export async function snapshot(page: Page): Promise<DashboardSnapshot> {
  const tiles: Record<string, string | null> = {};
  for (const { id } of TILES) {
    tiles[id] = await tile(page, id).getAttribute('data-value');
  }
  const charts: Record<string, { legend: string[]; table: string }> = {};
  for (const id of CHARTS) {
    charts[id] = {
      legend: await legendKeys(page, id),
      table: (await chart(page, id).getByTestId('chart-a11y-table').textContent()) ?? '',
    };
  }
  const instances = await page
    .getByTestId('dashboard-instance-tile')
    .evaluateAll((nodes) =>
      nodes.map(
        (n) => `${n.getAttribute('data-instance-id') ?? ''}:${n.getAttribute('data-state') ?? ''}`,
      ),
    );
  return { tiles, charts, instances };
}

/** A data-live-ts value (ISO-8601 or epoch ms) as epoch ms. */
export function liveTsMs(value: string | null): number | null {
  if (value === null || value === '') {
    return null;
  }
  const ms = /^\d+$/.test(value) ? Number(value) : Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}
