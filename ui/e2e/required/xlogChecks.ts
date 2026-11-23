// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the XLog screen (WO-33): the docs/05 §2.1 checklist of ui/e2e/required/xlog.yaml
// and the WO-33 items of ui/e2e/required/common.yaml (05 §2.0-2, 2.0-4, 2.0-7 deep links 4 and 5,
// 2.0-8 excluded requests). Each runs on the real stack (server, ClickHouse, PostgreSQL, seed) and
// asserts exact values: from the URL, the seed manifest or the API asked the same question inside
// the check (search, summary and points of the same bounds). The WebSocket is the docs/14 §5 stub
// (C-205 (3)); items that the seed cannot show yet are test.fixme naming the owning work order.
// The outage shading of 2.1-10 (xlogOutageShading) uses the collection outage the stack setup
// makes since WO-22 (seed manifest `outage`, C-205 (2)).
import { readFileSync } from 'node:fs';

import { expect, type Page, type TestInfo } from '@playwright/test';

import { PLOT_MARGIN } from '../../src/xlog/renderer';
import { formatDuration } from '../../src/xlog/xlogFormat';
import { t } from '../fixtures/i18n';
import { expectUrl, state } from '../fixtures/ui';
import type { WsStub } from '../fixtures/ws';
import {
  apiPoints,
  apiSearch,
  apiSummary,
  dragSelect,
  EXPECTATION_ROLE,
  expectReady,
  expectSelectionShown,
  intAttr,
  listCell,
  listKeys,
  openXLog,
  plotBox,
  searchBody,
  selText,
  unsampledWindow,
  xlogPath,
  type Sel,
} from '../fixtures/xlogPage';
import type { ApiSession } from '../stack/api';
import { manifestAlert, type Role, type SeedManifest } from '../stack/manifest';

import {
  apiAlertTransitions,
  apiEventsPage,
  capture,
  ceilMinute,
  floorMinute,
  isoSeconds,
  queryOf,
  type ApiAlertEvent,
} from './alertsChecks';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface XLogCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  /** The docs/14 §5 WebSocket stub (realtime checks). */
  readonly ws?: WsStub;
}

type XLogCheck = (ctx: XLogCheckContext) => Promise<void>;

const MINUTE = 60_000;

/** An unsampled window of the primary app near the end of the generator range, opened. */
async function openUnsampled(
  ctx: XLogCheckContext,
): Promise<{ api: ApiSession; appId: number; from: string; to: string; total: number }> {
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const appId = ctx.seed.primary_app_id;
  const end = Math.floor(Date.parse(ctx.seed.generator.range.end) / MINUTE) * MINUTE - 2 * MINUTE;
  const window = await unsampledWindow(api, appId, end);
  await openXLog(ctx.page, xlogPath(appId, window.from, window.to));
  return { api, appId, ...window };
}

/** 2.1-1: time and response time axes, linear/log, three status colours, the threshold line. */
const xlogAxesStatusThreshold: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId, from, to } = await openUnsampled(ctx);
  const { meta } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  // Colour by status (default): exactly the three status entries, each with its label.
  const legend = page.getByTestId('xlog-legend');
  await expect(legend.locator('.xlog-legend__label')).toHaveText([
    t('xlog:status.normal'),
    t('xlog:status.slow'),
    t('xlog:status.error'),
  ]);
  // The threshold line is the response's slow threshold (meta.slow_threshold_ms).
  expect(typeof meta.slow_threshold_ms, 'meta.slow_threshold_ms of the API').toBe('number');
  const threshold = page.getByTestId('xlog-threshold');
  const ms = meta.slow_threshold_ms ?? 0;
  const number = new Intl.NumberFormat('en').format(ms >= 10_000 ? ms / 1000 : ms);
  await expect(threshold).toContainText(number);
  // Linear <-> log is URL state (y=log), the default is not written.
  await page.getByTestId('xlog-yscale-log').click();
  await expectUrl(page, (s) => s.params.get('y') === 'log', 'y=log after the log button');
  await expect(page.getByTestId('xlog-yscale-log')).toHaveAttribute('aria-pressed', 'true');
  await page.getByTestId('xlog-yscale-linear').click();
  await expectUrl(page, (s) => !s.params.has('y'), 'y removed for the linear default');
  await expect(page.getByTestId('xlog-yscale-linear')).toHaveAttribute('aria-pressed', 'true');
  // Every point of the window is drawn (not sampled).
  await expect(page.getByTestId('xlog-page')).toHaveAttribute(
    'data-point-count',
    String(meta.total ?? -1),
  );
};

/**
 * 2.1-2: a drag selects a region; the summary counts it as the server does (the window is not
 * sampled, so the browser count equals the server summary count and the search total), the
 * distributions match the server summary, and the list holds the search's rows.
 */
const xlogDragSummaryList: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId } = await openUnsampled(ctx);
  const sel = await dragSelect(page);
  await expectSelectionShown(page, sel);
  const summary = await apiSummary(api, appId, sel, 'HTTP');
  const search = await apiSearch(api, appId, searchBody(sel, 'HTTP'));
  expect(search.meta.total, 'search total = summary count of the same bounds').toBe(summary.count);
  const panel = page.getByTestId('xlog-summary');
  await expect(page.getByTestId('xlog-summary-count')).toHaveAttribute(
    'data-count',
    String(summary.count),
  );
  await expect(panel).toHaveAttribute('data-server-state', 'ready');
  await expect(page.getByTestId('xlog-summary-server-count')).toHaveAttribute(
    'data-count',
    String(summary.count),
  );
  // Top services (count, average, maximum, errors): the server's top service is the browser's.
  const rows = page.getByTestId('xlog-summary-service-row');
  await expect(rows).toHaveCount(
    Math.min(10, new Set(summary.by_service.map((s) => s.service_id)).size),
  );
  const top = summary.by_service[0];
  if (top !== undefined && top.service_id !== null) {
    const row = page.locator(
      `[data-testid="xlog-summary-service-row"][data-service-id="${top.service_id}"]`,
    );
    await expect(row).toHaveAttribute('data-count', String(top.count));
  }
  // Instance distribution: every server group appears with its count.
  for (const group of summary.by_instance) {
    await expect(
      page.locator(
        `[data-testid="xlog-summary-instance-row"][data-instance-id="${String(group.instance_id)}"]`,
      ),
    ).toHaveAttribute('data-count', String(group.count));
  }
  // HTTP status and error class distributions are the server's (C-202), labelled as such.
  const http = page.getByTestId('xlog-summary-http');
  await expect(http.getByTestId('xlog-summary-source')).toHaveAttribute('data-source', 'server');
  await expect(http.getByTestId('xlog-summary-http-row')).toHaveCount(summary.by_status.length);
  for (const group of summary.by_status) {
    await expect(
      http.locator(
        `[data-testid="xlog-summary-http-row"][data-code="${String(group.http_status)}"]`,
      ),
    ).toHaveAttribute('data-count', String(group.count));
  }
  const errors = page.getByTestId('xlog-summary-errors');
  await expect(errors.getByTestId('xlog-summary-error-row')).toHaveCount(
    summary.by_error_class.length,
  );
  // The list: the search's rows in its order (elapsed descending), "top 1,000" when it has more.
  const list = page.getByTestId('xlog-list');
  await expect(list).toHaveAttribute('data-rows', String(search.data.length));
  const keys = await listKeys(page);
  expect(keys.length).toBeGreaterThan(0);
  expect(keys).toEqual(search.data.slice(0, keys.length).map((r) => r.tx_id));
  const elapsed = search.data.map((r) => r.elapsed_ms);
  expect(elapsed, 'elapsed descending').toEqual([...elapsed].sort((a, b) => b - a));
  if ((search.meta.total ?? 0) > search.data.length) {
    await expect(page.getByTestId('xlog-list-top')).toHaveAttribute(
      'data-total',
      String(search.meta.total),
    );
    expect(search.data.length).toBe(1000);
  } else {
    await expect(page.getByTestId('xlog-list-top')).toHaveCount(0);
  }
  // A summary row filters the list: the top service row writes f.service and keeps sel.
  if (top?.service_id !== null && top !== undefined) {
    const serviceId = top.service_id;
    await page
      .locator(`[data-testid="xlog-summary-service-row"][data-service-id="${serviceId}"] button`)
      .click();
    const after = await expectUrl(
      page,
      (s) => s.filters.some((f) => f.field === 'service' && f.value === serviceId),
      'f.service written by the summary row',
    );
    expect(after.params.get('sel')).toBe(selText(sel));
    await expect(list).toHaveAttribute('data-state', /^(ready|empty)$/);
    const filtered = await apiSearch(
      api,
      appId,
      searchBody(sel, 'HTTP', { service_id: [serviceId] }),
    );
    await expect(list).toHaveAttribute('data-rows', String(filtered.data.length));
    expect(await listKeys(page)).toEqual(
      filtered.data.slice(0, (await listKeys(page)).length).map((r) => r.tx_id),
    );
  }
};

/** 2.1-3: colour by status (default), instance, top services; the legend names the colours. */
const xlogColorBy: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId, from, to } = await openUnsampled(ctx);
  const { data } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  const instanceNames = new Set(Object.values(data.dictionary.instance));
  const serviceNames = new Set(Object.values(data.dictionary.service));
  const other = t('xlog:legend.other');
  const labels = page.getByTestId('xlog-legend').locator('.xlog-legend__label');

  await page.getByTestId('xlog-colorby-instance').click();
  await expectUrl(page, (s) => s.params.get('color') === 'instance', 'color=instance');
  await expect(page.getByTestId('xlog-colorby-instance')).toHaveAttribute('aria-pressed', 'true');
  const byInstance = await labels.allTextContents();
  expect(byInstance.length).toBe(
    Math.min(instanceNames.size, 12) + (instanceNames.size > 12 ? 1 : 0),
  );
  for (const label of byInstance) {
    expect(label === other || instanceNames.has(label), `legend instance ${label}`).toBe(true);
  }

  await page.getByTestId('xlog-colorby-service').click();
  await expectUrl(page, (s) => s.params.get('color') === 'service', 'color=service');
  const byService = await labels.allTextContents();
  expect(byService.length).toBe(Math.min(serviceNames.size, 12) + (serviceNames.size > 12 ? 1 : 0));
  for (const label of byService) {
    expect(label === other || serviceNames.has(label), `legend service ${label}`).toBe(true);
  }

  await page.getByTestId('xlog-colorby-status').click();
  await expectUrl(page, (s) => !s.params.has('color'), 'color removed for the status default');
  await expect(labels).toHaveCount(3);
};

/** Hovers points of the plot in a grid until the tooltip shows; returns the hover position. */
async function hoverAnyPoint(page: Page): Promise<{ x: number; y: number }> {
  const box = await plotBox(page);
  const tooltip = page.getByTestId('xlog-tooltip');
  for (let row = 0.95; row > 0.05; row -= 0.1) {
    for (let column = 0.05; column < 0.96; column += 0.03) {
      const x = box.x + box.width * column;
      const y = box.y + box.height * row;
      await page.mouse.move(x, y);
      if (await tooltip.isVisible()) {
        return { x, y };
      }
    }
  }
  throw new Error('no point found under the pointer on the plot');
}

/**
 * 2.1-5: the hover tooltip shows service, response time, status and instance; a click opens the
 * detail of that very point (canonical route with the ts hint, C-206 (1)), whose API values match.
 */
const xlogHoverAndClick: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId, from, to } = await openUnsampled(ctx);
  const { meta } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  const slowMs = meta.slow_threshold_ms;
  expect(typeof slowMs, 'meta.slow_threshold_ms of the points API').toBe('number');
  const at = await hoverAnyPoint(page);
  const tooltip = page.getByTestId('xlog-tooltip');
  const service = (await tooltip.getByTestId('xlog-tooltip-service').textContent()) ?? '';
  const instance = (await tooltip.getByTestId('xlog-tooltip-instance').textContent()) ?? '';
  const status = (await tooltip.getByTestId('xlog-tooltip-status').textContent()) ?? '';
  const elapsed = (await tooltip.getByTestId('xlog-tooltip-elapsed').textContent()) ?? '';
  await page.mouse.click(at.x, at.y);
  const detail = await expectUrl(
    page,
    (s) => /^\/apps\/\d+\/transactions\/\d{1,20}$/.test(s.path),
    'the point click opens the detail route',
  );
  const txId = detail.path.split('/').pop() ?? '';
  const ts = detail.params.get('ts');
  expect(ts, 'ts hint').toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  expect(detail.params.get('back')).toMatch(new RegExp(`^/apps/${String(appId)}/xlog\\?`));
  const tx = await api.data<{
    service: string | null;
    instance: { name: string | null };
    elapsed_ms: number;
    error: boolean;
    ts: string;
  }>(`/transactions/${txId}`, { ts: ts ?? '' });
  expect(Date.parse(tx.ts)).toBe(Date.parse(ts ?? ''));
  // The hovered point is the clicked transaction: every tooltip field equals its API value.
  // Names exactly as collected; a name the API does not have shows as the canvas's
  // "Unknown (#n)" (n = the screen's dictionary index, not an API value).
  const unknown = new RegExp(
    `^${t('xlog:value.unknown')
      .replace(/[.*+?^$()|[\]\\]/g, '\\$&')
      .replace('{{id}}', '\\d+')}$`,
  );
  if (tx.service === null) {
    expect(service, 'tooltip service of a transaction without a name').toMatch(unknown);
  } else {
    expect(service, 'tooltip service').toBe(tx.service);
  }
  if (tx.instance.name === null) {
    expect(instance, 'tooltip instance of a transaction without a name').toMatch(unknown);
  } else {
    expect(instance, 'tooltip instance').toBe(tx.instance.name);
  }
  // Response time formatted as the canvas formats it (src/xlog/xlogFormat.ts, en).
  expect(elapsed, 'tooltip response time').toBe(
    formatDuration((key, values) => t(key, 'en', { value: String(values.value) }), tx.elapsed_ms),
  );
  // Status as the server decides it for the points API (error, else slow at or above
  // meta.slow_threshold_ms, else normal).
  const expectedStatus = tx.error
    ? t('xlog:status.error')
    : tx.elapsed_ms >= (slowMs ?? Number.POSITIVE_INFINITY)
      ? t('xlog:status.slow')
      : t('xlog:status.normal');
  expect(status, 'tooltip status').toBe(expectedStatus);
};

/**
 * 2.1-6: service, instance, error and kind filters apply to the scatter at once (the points API
 * asked with the same filters gives the drawn count); kind BATCH switches the y axis to s or min.
 */
const xlogFiltersAndBatch: XLogCheck = async (ctx) => {
  const { page, seed } = ctx;
  const { api, appId, from, to } = await openUnsampled(ctx);
  const { data } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  const serviceId = data.service_id[0] ?? '';
  const instanceId = data.instance_id[0] ?? 0;
  for (const [extra, query] of [
    [{ 'f.service': serviceId }, { service: [serviceId] }],
    [{ 'f.instance': String(instanceId) }, { instance: [String(instanceId)] }],
    [{ 'f.error': 'true' }, { error: true }],
  ] as const) {
    await openXLog(page, xlogPath(appId, from, to, extra));
    const expected = await apiPoints(api, appId, {
      from,
      to,
      kind: 'HTTP',
      limit: 100_000,
      ...query,
    });
    await expect(page.getByTestId('xlog-page')).toHaveAttribute(
      'data-point-count',
      String(expected.meta.shown ?? -1),
    );
  }
  // Batch jobs of the whole generator range: the y axis is in seconds or minutes.
  const range = seed.generator.range;
  const batch = await apiPoints(api, appId, {
    from: range.start,
    to: range.end,
    kind: 'BATCH',
    limit: 100_000,
  });
  await openXLog(page, xlogPath(appId, range.start, range.end, { kind: 'BATCH' }));
  await expect(page.getByTestId('xlog-page')).toHaveAttribute(
    'data-point-count',
    String(batch.meta.shown ?? -1),
  );
  const maxElapsed = Math.max(...batch.data.elapsed_ms);
  expect(maxElapsed, 'the seed has batch jobs longer than 2 s').toBeGreaterThan(2000);
  await expect(page.getByTestId('xlog-canvas')).toHaveAttribute('data-y-unit', /^(s|min)$/);
};

/**
 * 2.1-7 and deep link 5: a window with more than 100,000 points shows "Showing N of M" with the
 * API's meta.shown and meta.total, and the same absolute URL loads the same transaction ids again
 * (A-5 deterministic sampling).
 */
const xlogSamplingDeterministic: XLogCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const window = seed.windows.find((w) => w.name === 'generator-range');
  testInfo.fixme(
    window === undefined || window.total <= 100_000,
    `the seed window generator-range has ${String(window?.total)} points (needs > 100,000 for sampling; the generator profile of the stack decides it)`,
  );
  if (window === undefined) return;
  const { meta } = await apiPoints(api, window.app_id, {
    from: window.from,
    to: window.to,
    kind: 'HTTP',
    limit: 100_000,
  });
  expect(meta.total).toBe(window.total);
  const path = xlogPath(window.app_id, window.from, window.to);
  const load = async (): Promise<string[]> => {
    const response = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/apps/${String(window.app_id)}/xlog?`) &&
        r.request().method() === 'GET',
      { timeout: 60_000 },
    );
    if (page.url().endsWith(path)) {
      await page.reload();
    } else {
      await page.goto(path);
    }
    const body = (await (await response).json()) as { data: { tx_id: string[] } };
    await expectReady(page);
    return body.data.tx_id;
  };
  const first = await load();
  const indicator = page.getByTestId('xlog-sampling-indicator');
  await expect(indicator).toContainText(new Intl.NumberFormat('en').format(meta.shown ?? 0));
  await expect(indicator).toContainText(new Intl.NumberFormat('en').format(meta.total ?? 0));
  await expect(page.getByTestId('xlog-page')).toHaveAttribute(
    'data-point-count',
    String(meta.shown),
  );
  const second = await load();
  expect(second.length).toBe(first.length);
  expect(new Set(second)).toEqual(new Set(first));
};

/** The events of a range (docs/14 §4.12: `data` is the list of events, newest first). */
async function eventsOf(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
): Promise<{ event_id: string; ts: string; title: string | null }[]> {
  const answer = await api.query<{ event_id: string; ts: string; title: string | null }[]>(
    `/apps/${String(appId)}/events`,
    { from, to },
  );
  return answer.data;
}

/**
 * 2.1-10 markers: the events of the range are markers (as many as the events API lists for the
 * same range, plus the alert transitions of the range, ADR-0010 C-226 (8)); alert=<event id>
 * emphasises exactly that event's marker and keeps live off; an event title that is an XSS
 * payload shows verbatim as text in the marker tooltip. The stack seeds two deploy events of the
 * primary app (e2e/stack/seed/traffic.ts seedEventsOf).
 */
const xlogEventMarkers: XLogCheck = async (ctx) => {
  const { page, seed } = ctx;
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const appId = seed.primary_app_id;
  const range = seed.generator.range;
  const seeded = seed.events.filter((e) => e.app_id === appId);
  expect(seeded.length, 'seed events of the primary app in the manifest').toBe(2);
  const plain = seeded.find((e) => !e.xss);
  const xss = seeded.find((e) => e.xss);
  if (plain === undefined || xss === undefined) {
    throw new Error('the manifest lacks the plain or the XSS seed event');
  }
  const events = await eventsOf(api, appId, range.start, range.end);
  for (const event of seeded) {
    expect(
      events.map((e) => e.event_id),
      `GET /events lists seed event ${event.event_id}`,
    ).toContain(event.event_id);
  }
  // The alert transitions of the same range are markers too (the range ended before the run).
  const transitions = await apiAlertTransitions(api, appId, range.start, range.end);
  await openXLog(page, xlogPath(appId, range.start, range.end, { alert: plain.event_id }));
  await expect(page.getByTestId('xlog-markers')).toBeVisible();
  // Marker count = event count of the API for the same range + its alert transitions (close
  // events share one marker whose data-count says how many; the seed events are far apart, each
  // its own marker).
  await expect
    .poll(() => xlogMarkerTotal(page), { message: 'markers = GET /events + GET /alerts/events' })
    .toBe(events.length + transitions.length);
  for (const event of seeded) {
    await expect(
      page.locator(`[data-testid="xlog-marker"][data-marker-id="${event.event_id}"]`),
    ).toHaveCount(1);
  }
  const emphasized = page.locator('[data-testid="xlog-marker"][data-emphasized="true"]');
  await expect(emphasized).toHaveCount(1);
  await expect(emphasized).toHaveAttribute('data-marker-id', plain.event_id);
  expect(state(page).live).toBe(false);
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-live', 'false');
  // The XSS title: verbatim text in the marker's tooltip, no markup made of it.
  await page.locator(`[data-testid="xlog-marker"][data-marker-id="${xss.event_id}"]`).focus();
  const tip = page.getByTestId('xlog-marker-tip');
  await expect(tip).toBeVisible();
  await expect(tip.locator('li .safe-text').first()).toHaveText(xss.title);
  expect(
    await page
      .locator(
        '[data-testid="xlog-page"] img, [data-testid="xlog-page"] script, [data-testid="xlog-page"] iframe',
      )
      .count(),
  ).toBe(0);
  expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
};

/** 2.1-8: required columns, column chooser (cols=), server sort, CSV through the server. */
const xlogListColumnsSortCsv: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId, total } = await openUnsampled(ctx);
  const sel = await dragSelect(page);
  await expectSelectionShown(page, sel);
  const list = page.getByTestId('xlog-list');
  const headers = (await list.locator('thead th').allTextContents()).map((h) => h.trim());
  for (const key of [
    'time',
    'service',
    'elapsed',
    'http',
    'errorClass',
    'instance',
    'sqlCount',
    'sqlTime',
    'extCount',
    'extTime',
    'profile',
    'kind',
    'version',
  ]) {
    const header = t(`xlog:list.columns.${key}`);
    expect(
      headers.some((h) => h.startsWith(header)),
      `column ${header}`,
    ).toBe(true);
  }
  // User id: shown only when collected, otherwise hidden with a note (05 §2.1-8 "수집 시").
  const search = await apiSearch(api, appId, searchBody(sel, 'HTTP'));
  const collected = search.data.some(
    (r) =>
      (r as { user_id_mode?: string | null }).user_id_mode === 'plain' ||
      (r as { user_id_mode?: string | null }).user_id_mode === 'hashed',
  );
  if (collected) {
    expect(headers.some((h) => h.startsWith(t('xlog:list.columns.userId')))).toBe(true);
  } else {
    await expect(page.getByTestId('xlog-list-user-note')).toBeVisible();
  }
  // Column chooser: hiding "version" writes cols= without it.
  await page.getByTestId('column-chooser').click();
  await page.getByTestId('column-chooser-version').click();
  await expectUrl(
    page,
    (s) =>
      (s.params.get('cols') ?? '').length > 0 &&
      !(s.params.get('cols') ?? '').split(',').includes('version'),
    'cols= without version',
  );
  await page.keyboard.press('Escape');
  // Server sort by time: the list order is the API's ts:desc order.
  await page.getByTestId('data-table-sort-ts').click();
  await expectUrl(page, (s) => s.params.get('sort') === 'ts:desc', 'sort=ts:desc');
  await expect(list).toHaveAttribute('data-state', 'ready', { timeout: 20_000 });
  const byTime = await apiSearch(api, appId, searchBody(sel, 'HTTP', {}, 'ts:desc'));
  await expect(list).toHaveAttribute('data-rows', String(byTime.data.length));
  const keys = await listKeys(page);
  expect(keys).toEqual(byTime.data.slice(0, keys.length).map((r) => r.tx_id));
  // CSV over the export limit: the whole window holds more than 10,000 transactions (the API's
  // total of the same search), the server answers 400 limit_exceeded and the list says so.
  expect(byTime.meta.total ?? 0, 'search total of the whole-window selection').toBeGreaterThan(
    CSV_MAX_ROWS,
  );
  const overLimit = page.waitForRequest(isCsvRequest);
  await page.getByTestId('csv-export').click();
  expect((await overLimit).method()).toBe('POST');
  await expect(page.getByTestId('xlog-list-csv-error')).toContainText('10,000');
  await expectCsvDownload(ctx, api, appId, total);
};

/** The export row limit of the server (docs/14 §4.17: more rows answer 400 limit_exceeded). */
const CSV_MAX_ROWS = 10_000;

function isCsvRequest(request: { url(): string }): boolean {
  const url = new URL(request.url());
  return url.pathname.endsWith('/transactions/search') && url.searchParams.get('format') === 'csv';
}

/**
 * 2.1-8 CSV download (C-205 (1), DSN-15-06): a narrow selection whose search total is within the
 * export limit (asserted, never branched on) downloads the server's file: a POST with format=csv,
 * the server's file name, one data row per transaction of the API's total for the screen's search
 * (bounds, kind, filters, sort), and its rows in the API's order (the first page of the same search
 * equals the file's first rows), so the export asked the server the screen's question. The browser
 * does not expose the request body (the shared transport sends a cloned Request, whose body
 * Chromium's network events leave out: postData is null); the body without cursor and limit is
 * asserted at the fetch boundary by transactionSearch.test.ts and TxListPanel.test.tsx. The format
 * details checked are the docs/14 §4.17 contract only (BOM, quoted cells, one line per row, CRLF).
 */
async function expectCsvDownload(
  ctx: XLogCheckContext,
  api: ApiSession,
  appId: number,
  windowTotal: number,
): Promise<void> {
  const { page } = ctx;
  // About 5,000 transactions on average (at most 30 % of the window), well inside the limit.
  const band = Math.min(0.3, 5_000 / Math.max(1, windowTotal));
  // The export click scrolled the page: center the plot again before dragging on it (a plot whose
  // top edge is under the sticky top bar counts as visible, so scrollIntoViewIfNeeded is not
  // enough; the drag presses on the plot's top edge).
  await page
    .getByTestId('xlog-canvas-points')
    .first()
    .evaluate((canvas) => {
      canvas.scrollIntoView({ block: 'center', inline: 'nearest' });
    });
  const sel = await dragSelect(page, 0, band);
  await expectSelectionShown(page, sel);
  const sort = state(page).params.get('sort') ?? 'elapsed_ms:desc';
  const expected = searchBody(sel, 'HTTP', {}, sort);
  const narrow = await apiSearch(api, appId, expected);
  const total = narrow.meta.total ?? -1;
  expect(total, 'search total of the narrow selection').toBeGreaterThan(0);
  expect(total, 'search total of the narrow selection').toBeLessThanOrEqual(CSV_MAX_ROWS);
  const request = page.waitForRequest(isCsvRequest);
  const download = page.waitForEvent('download');
  await page.getByTestId('csv-export').click();
  const csv = await request;
  expect(csv.method()).toBe('POST');
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^argos-.*\.csv$/);
  const text = readFileSync(await file.path(), 'utf8');
  expect(text.startsWith('\uFEFF'), 'UTF-8 byte order mark').toBe(true);
  expect(text.endsWith('\r\n'), 'every line ends with CRLF').toBe(true);
  const lines = text.slice(1, -2).split('\r\n');
  expect(
    lines.every((line) => line.startsWith('"') && line.endsWith('"')),
    'every cell quoted',
  ).toBe(true);
  expect(lines[0]?.startsWith('"tx_id","ts",'), 'header line').toBe(true);
  // One header line, then one line per transaction (line breaks in values are written as \n).
  expect(lines.length - 1, 'CSV data rows = search total').toBe(total);
  // The first cell is the transaction id (digits, never prefixed or escaped): the file's rows are
  // the search's rows in the screen's order.
  const fileIds = lines.slice(1, 1 + narrow.data.length).map((line) => line.split('"')[1] ?? '');
  expect(fileIds, `CSV rows in the ${sort} order of the search`).toEqual(
    narrow.data.map((r) => r.tx_id),
  );
  await expect(page.getByTestId('xlog-list-csv-error')).toHaveCount(0);
}

/** 2.1-9: Up/Down/Enter in the list opens the detail of the focused row with ts and back. */
const xlogListKeyboard: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId } = await openUnsampled(ctx);
  const sel = await dragSelect(page);
  await expectSelectionShown(page, sel);
  const search = await apiSearch(api, appId, searchBody(sel, 'HTTP'));
  const second = search.data[1];
  expect(second, 'the selection has at least two transactions').toBeDefined();
  if (second === undefined) return;
  const back = new URL(page.url()).pathname + new URL(page.url()).search;
  const rows = page.getByTestId('xlog-list').getByTestId('data-table-row');
  await rows.first().focus();
  await page.keyboard.press('ArrowDown');
  await expect(rows.nth(1)).toBeFocused();
  await page.keyboard.press('Enter');
  const detail = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/transactions/${second.tx_id}`,
    'Enter opens the detail of the focused row',
  );
  expect(Date.parse(detail.params.get('ts') ?? '')).toBe(Date.parse(second.ts));
  expect(detail.params.get('back')).toBe(back);
  // Back to the list: the selection and its list come back.
  await page.goBack();
  await expectUrl(page, (s) => s.params.get('sel') === selText(sel), 'back to the list');
  await expectSelectionShown(page, sel);
};

/** A fresh tx id for stub pushes (decimal, never in the seed: above the seed's time prefix). */
function stubTxId(n: number): string {
  return `18446744073709${String(500000 + n).padStart(6, '0')}`;
}

/** Pushes points through the ws stub as docs/14 §5.3 columns (§4.5 types). */
function pushPoints(
  ws: WsStub,
  points: { txId: string; ts: number; elapsed: number; serviceId: string; instanceId: number }[],
): number {
  return ws.push('xlog', {
    tx_id: points.map((p) => p.txId),
    ts: points.map((p) => p.ts),
    elapsed_ms: points.map((p) => p.elapsed),
    status: points.map(() => 0),
    instance_id: points.map((p) => p.instanceId),
    service_id: points.map((p) => p.serviceId),
    kind: points.map(() => 1),
  });
}

/**
 * A service key that no transaction of the seed has: the live check filters by it, so the REST
 * loads and refills of the window are empty (checked through the API) and every point on the
 * chart is a stub push (the stub sends what the test gives, it does not filter like the server).
 */
const LIVE_SERVICE = '1';

/**
 * 05 §2.0-2 (common.yaml): a drag over the chart while live freezes the window, turns live off and
 * offers "resume live"; resuming turns live on with a range that ends now and refills through REST.
 */
const xlogResumeLiveRefill: XLogCheck = async (ctx) => {
  const { page, seed } = ctx;
  const appId = seed.primary_app_id;
  await page.goto(`/apps/${String(appId)}/xlog?from=now-5m&live=1`);
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-live', 'true');
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
  const sel = await dragSelect(page, 0.2, 0.6);
  const frozen = await expectUrl(
    page,
    (s) => !s.live && s.time.kind === 'absolute',
    'live off, window frozen',
  );
  expect(frozen.params.get('sel')).toBe(selText(sel));
  const resume = page.getByTestId('live-resume');
  await expect(resume).toBeVisible();
  const refill = page.waitForRequest(
    (r) => r.url().includes(`/api/v1/apps/${String(appId)}/xlog?`) && r.method() === 'GET',
  );
  const clickedAt = Date.now();
  await resume.click();
  const after = await expectUrl(
    page,
    (s) => s.live && !s.params.has('sel'),
    'live again, no selection',
  );
  expect(after.time.kind === 'default' || after.time.kind === 'relative').toBe(true);
  const request = new URL((await refill).url());
  // The REST refill reaches the present (within the 10-second query clock).
  expect(Date.parse(request.searchParams.get('to') ?? '')).toBeGreaterThanOrEqual(
    clickedAt - 15_000,
  );
};

/**
 * 2.1-4: realtime 5-minute window through the xlog topic (ws stub): pushed points are drawn once
 * (duplicates by tx_id dropped), the window moves with the clock, pause stops only the window,
 * silence shows the stale badge, and a reconnect refills [last received, now] through REST
 * without duplicates.
 */
const xlogLiveWindow: XLogCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const ws = ctx.ws;
  if (ws === undefined) {
    throw new Error('xlogLiveWindow needs the ws stub fixture');
  }
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const appId = seed.primary_app_id;
  const empty = await apiPoints(api, appId, {
    from: new Date(Date.now() - 10 * MINUTE).toISOString(),
    to: new Date().toISOString(),
    kind: 'HTTP',
    limit: 1,
    service: [LIVE_SERVICE],
  });
  expect(empty.meta.total, `no transaction of service ${LIVE_SERVICE}`).toBe(0);
  testInfo.annotations.push({ type: 'live-filter', description: `f.service=${LIVE_SERVICE}` });
  await page.goto(`/apps/${String(appId)}/xlog?from=now-5m&live=1&f.service=${LIVE_SERVICE}`);
  await ws.waitForSubscription('xlog');
  const pageRoot = page.getByTestId('xlog-page');
  await expect(pageRoot).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
  await expect(pageRoot).toHaveAttribute('data-point-count', '0');
  const now = Date.now();
  const batch = [0, 1, 2, 3, 4].map((i) => ({
    txId: stubTxId(i),
    ts: now - 3000 + i * 500,
    elapsed: 100 + i * 50,
    serviceId: LIVE_SERVICE,
    instanceId: seed.apps.find((a) => a.app_id === appId)?.instances[0]?.instance_id ?? 1,
  }));
  expect(pushPoints(ws, batch)).toBe(1);
  await expect(pageRoot).toHaveAttribute('data-point-count', '5');
  // The same tx ids again plus two new ones: only the new ones are added.
  const template = {
    ...batch[0],
    txId: '',
    ts: 0,
    elapsed: 150,
    serviceId: LIVE_SERVICE,
    instanceId: batch[0]?.instanceId ?? 1,
  };
  const more = [5, 6].map((i) => ({ ...template, txId: stubTxId(i), ts: Date.now() - 200 }));
  pushPoints(ws, [...batch, ...more]);
  await expect(pageRoot).toHaveAttribute('data-point-count', '7');
  // The window moves with the clock.
  const toBefore = await intAttr(pageRoot, 'data-range-to');
  await expect
    .poll(async () => intAttr(pageRoot, 'data-range-to'), { timeout: 5_000 })
    .toBeGreaterThan(toBefore);
  // Pause stops the window, data keeps arriving.
  await page.getByTestId('xlog-pause-button').click();
  await expect(page.getByTestId('xlog-live-status')).toHaveText(t('xlog:live.paused'));
  const pausedTo = await intAttr(pageRoot, 'data-range-to');
  pushPoints(ws, [{ ...template, txId: stubTxId(7), ts: Date.now() - 100 }]);
  // While paused the page learns the new count within one window tick (1 s), not by chance.
  await expect(pageRoot).toHaveAttribute('data-point-count', '8', { timeout: 2_000 });
  await page.waitForTimeout(2_000);
  expect(await intAttr(pageRoot, 'data-range-to')).toBe(pausedTo);
  await page.getByTestId('xlog-resume-button').click();
  await expect
    .poll(async () => intAttr(pageRoot, 'data-range-to'), { timeout: 5_000 })
    .toBeGreaterThan(pausedTo);
  // Silence for more than 10 s: the stale badge (docs/15 §10). Only a data frame ends it (a pong
  // proves the connection, not fresh data: src/realtime/WsManager.ts), so once the stub answers
  // again the already drawn points are pushed again: the badge goes, the count stays (no duplicates).
  // The badge can show early in the pause (10 s after the last frame), so the push is retried for
  // longer than the whole pause.
  ws.pause(13_000);
  await expect(page.getByTestId('ws-stale-badge')).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => pushPoints(ws, batch), { timeout: 20_000 }).toBeGreaterThan(0);
  await expect(page.getByTestId('ws-stale-badge')).toBeHidden({ timeout: 5_000 });
  await expect(pageRoot).toHaveAttribute('data-point-count', '8');
  // A dropped connection reconnects and refills [last received, now] through REST, no duplicates.
  const connections = ws.connectionCount();
  const refill = page.waitForRequest(
    (r) => r.url().includes(`/api/v1/apps/${String(appId)}/xlog?`) && r.method() === 'GET',
    { timeout: 40_000 },
  );
  ws.drop();
  await ws.waitForConnections(connections + 1, 40_000);
  const request = new URL((await refill).url());
  const from = request.searchParams.get('from') ?? '';
  expect(Date.parse(from)).toBeLessThanOrEqual(Date.now());
  // The refill asks with the same filter.
  expect(request.searchParams.getAll('service')).toEqual([LIVE_SERVICE]);
  const filled = await apiPoints(api, appId, {
    from,
    to: request.searchParams.get('to') ?? new Date().toISOString(),
    kind: 'HTTP',
    limit: 100_000,
    service: [LIVE_SERVICE],
  });
  await expect(pageRoot).toHaveAttribute('data-point-count', String(8 + (filled.meta.shown ?? 0)));
  // The pushed points again after the refill: still no duplicates.
  pushPoints(ws, batch);
  await page.waitForTimeout(500);
  await expect(pageRoot).toHaveAttribute('data-point-count', String(8 + (filled.meta.shown ?? 0)));
};

/**
 * 05 §2.0-4 (common.yaml): a list cell's include and exclude become chips and URL filters; the list
 * is asked again with the included value; the exclusion, which the search cannot express, is listed
 * as not applied (never emulated).
 */
const xlogCellFilterChips: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId } = await openUnsampled(ctx);
  const sel = await dragSelect(page);
  await expectSelectionShown(page, sel);
  const search = await apiSearch(api, appId, searchBody(sel, 'HTTP'));
  const first = search.data[0];
  expect(first, 'the selection has transactions').toBeDefined();
  if (first === undefined) return;
  const row = page.getByTestId('xlog-list').getByTestId('data-table-row').first();
  const instanceCell = listCell(row, 'instance');
  await instanceCell.hover();
  await instanceCell.getByTestId('cell-filter-include').click();
  const included = await expectUrl(
    page,
    (s) =>
      s.filters.some(
        (f) => f.field === 'instance' && f.op === 'eq' && f.value === String(first.instance_id),
      ),
    'f.instance from the cell',
  );
  expect(included.params.get('sel')).toBe(selText(sel));
  const chip = page
    .locator('[data-testid="filter-chip"][data-field="instance"][data-op="eq"]')
    .first();
  // The chip names the instance (the search row's name, the points' dictionary); the key is
  // data-value (routes/filterLabels.tsx, qa-ux-sec-5).
  await expect(chip).toHaveAttribute('data-value', String(first.instance_id));
  await expect(chip.locator('.filter-chip__value')).toHaveText(
    first.instance ?? String(first.instance_id),
  );
  const filtered = await apiSearch(
    api,
    appId,
    searchBody(sel, 'HTTP', { instance_id: [first.instance_id] }),
  );
  await expect(page.getByTestId('xlog-list')).toHaveAttribute(
    'data-rows',
    String(filtered.data.length),
  );
  // Exclude a service: a chip with ≠, listed as not applied by the server.
  const target = filtered.data[0];
  if (target?.service_id === null || target === undefined) return;
  const serviceCell = listCell(
    page.getByTestId('xlog-list').getByTestId('data-table-row').first(),
    'service',
  );
  await serviceCell.hover();
  await serviceCell.getByTestId('cell-filter-exclude').click();
  await expectUrl(
    page,
    (s) =>
      s.filters.some(
        (f) => f.field === 'service' && f.op === 'ne' && f.value === target.service_id,
      ),
    'f.service=!<key> from the cell',
  );
  await expect(
    page.locator('[data-testid="filter-chip"][data-field="service"][data-op="ne"]'),
  ).toHaveCount(1);
  const notice = page.getByTestId('xlog-list-unapplied');
  await expect(notice.locator(`li[data-field="service"][data-reason="exclude"]`)).toHaveCount(1);
};

/** The XLog markers' event count (sum of data-count over the marker groups). */
async function xlogMarkerTotal(page: Page): Promise<number> {
  const counts = await page
    .getByTestId('xlog-marker')
    .evaluateAll((nodes) => nodes.map((n) => Number(n.getAttribute('data-count') ?? '0')));
  return counts.reduce((a, b) => a + b, 0);
}

/**
 * 05 §2.0-7 deep link 4 (common.yaml, ADR-0010 C-226 (8), WO-38): first the event markers of
 * 2.1-10 (deploy events, alert= emphasis, the XSS title as text), then the alert part around the
 * seed alert (e2e/stack/seed/alerts.ts): the XLog of fired_at ± 15 minutes (whole minutes, ending
 * at the current minute at the latest) with alert=<the seed FIRING event> draws every alert
 * transition the page asked GET /alerts/events for (the replay of the same question lists them)
 * besides the GET /events markers, emphasises exactly the seed event's marker (an alert marker),
 * keeps live off and shows the alert banner with the rule name.
 */
const xlogAlertMarker: XLogCheck = async (ctx) => {
  await xlogEventMarkers(ctx);
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const alert = manifestAlert(seed);
  const fired = Date.parse(alert.fired_at);
  const now = Date.now();
  const fromMs = floorMinute(fired - 15 * MINUTE);
  const toMs = Math.max(
    Math.min(ceilMinute(fired + 15 * MINUTE), floorMinute(now)),
    ceilMinute(fired + 1),
  );
  const from = isoSeconds(fromMs);
  const to = isoSeconds(toMs);
  const asked = await capture<{ data: ApiAlertEvent[] }>(page, 'GET', '/alerts/events', () =>
    openXLog(page, xlogPath(alert.app_id, from, to, { alert: alert.event_id })),
  );
  expect(asked.params.get('app'), 'alert transitions of the XLog application').toBe(
    String(alert.app_id),
  );
  expect(Date.parse(asked.params.get('from') ?? '')).toBe(fromMs);
  expect(Date.parse(asked.params.get('to') ?? '')).toBe(toMs);
  const shownIds = asked.body.data.map((e) => e.event_id);
  expect(shownIds, 'the seed FIRING event is a transition of the range').toContain(alert.event_id);
  const replay = await apiEventsPage(api, queryOf(asked.params));
  const replayIds = replay.data.map((e) => e.event_id);
  if (toMs <= floorMinute(now)) {
    expect(replayIds, 'GET /alerts/events replayed = the page answer').toEqual(shownIds);
  } else {
    expect(replayIds, 'GET /alerts/events replayed lists the page answer').toEqual(
      expect.arrayContaining(shownIds),
    );
  }
  const events = await eventsOf(api, alert.app_id, from, to);
  testInfo.annotations.push({
    type: 'deep-link-4',
    description: `${from}..${to}: ${String(events.length)} events, ${String(shownIds.length)} alert transitions`,
  });
  await expect
    .poll(() => xlogMarkerTotal(page), { message: 'markers = GET /events + GET /alerts/events' })
    .toBe(events.length + shownIds.length);
  const emphasized = page.locator('[data-testid="xlog-marker"][data-emphasized="true"]');
  await expect(emphasized).toHaveCount(1);
  await expect(emphasized).toHaveAttribute('data-marker-id', alert.event_id);
  await expect(emphasized).toHaveAttribute('data-marker-type', 'alert');
  expect(state(page).live).toBe(false);
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-live', 'false');
  const banner = page.getByTestId('alert-banner');
  await expect(banner).toBeVisible();
  await expect(banner.getByTestId('alert-banner-rule')).toContainText(alert.rule_name);
};

/** 05 §2.0-7 deep link 5 (common.yaml): the same absolute link shows the same data. */
const xlogDeterministicReload: XLogCheck = async (ctx) => {
  const { page } = ctx;
  const { api, appId, from, to } = await openUnsampled(ctx);
  const sel = await dragSelect(page, 0.1, 0.5);
  await expectSelectionShown(page, sel);
  const link = page.url();
  const countA = await intAttr(page.getByTestId('xlog-summary-count'), 'data-count');
  const keysA = await listKeys(page);
  await page.goto(link);
  await expectReady(page);
  await expectSelectionShown(page, sel);
  expect(await intAttr(page.getByTestId('xlog-summary-count'), 'data-count')).toBe(countA);
  expect(await listKeys(page)).toEqual(keysA);
  const { meta } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  await expect(page.getByTestId('xlog-page')).toHaveAttribute(
    'data-point-count',
    String(meta.shown),
  );
};

/** The collection-health excluded total of a range, or null without a source. */
async function excludedTotal(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
): Promise<number | null> {
  const health = await api.data<{
    excluded: { by_reason: Record<string, number>; value: (number | null)[] };
  }>(`/apps/${String(appId)}/collection-health`, { from, to });
  const reasons = Object.values(health.excluded.by_reason);
  if (reasons.length > 0) {
    return reasons.reduce((a, b) => a + b, 0);
  }
  const values = health.excluded.value.filter((v): v is number => typeof v === 'number');
  return values.length === 0 ? null : values.reduce((a, b) => a + b, 0);
}

/**
 * 05 §2.0-8 (common.yaml): an empty list names the requests excluded in its range (D-19) with the
 * count of the collection-health API for the same range, and links to the collection status.
 */
const xlogEmptyExcluded: XLogCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const appId = seed.traffic.app_id;
  const range = seed.traffic.range;
  const fromMs = Date.parse(range.start);
  const toMs = Date.parse(range.end);
  // A region above every response time: no transaction, the empty state.
  const sel: Sel = { msMin: 4_000_000_000, msMax: 4_294_967_295, tsFrom: fromMs, tsTo: toMs - 1 };
  const excluded = await excludedTotal(
    api,
    appId,
    new Date(sel.tsFrom).toISOString(),
    new Date(sel.tsTo + 1).toISOString(),
  );
  testInfo.annotations.push({ type: 'excluded-total', description: String(excluded) });
  testInfo.fixme(
    excluded === null || excluded === 0,
    `the collection-health API reports ${String(excluded)} excluded requests in the traffic window: the agent's ignored counter series (argos_agent_ignored_total) comes with WO-22/WO-28`,
  );
  await openXLog(
    page,
    `${xlogPath(appId, range.start, range.end, { kind: 'ALL' })}&sel=${selText(sel)}`,
  );
  await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-state', 'empty', {
    timeout: 20_000,
  });
  const link = page.getByTestId('xlog-list-empty').getByTestId('empty-state-excluded');
  await expect(link).toContainText(new Intl.NumberFormat('en').format(excluded ?? 0));
};

/** An outage of `meta.outages` (docs/14 §2.5, C-5): `to` missing or null while it lasts. */
/** meta.outages[] of 14 §4.5/§4.15: {from, to, reason}, no instance (filter with instance[]). */
interface ApiOutage {
  readonly from: string;
  readonly to?: string | null;
  readonly reason: string;
}

/** A run of hatched columns of the plot, CSS px from the plot's left edge, [start, end). */
interface ColumnRun {
  readonly start: number;
  readonly end: number;
}

/**
 * The column runs of the overlay canvas that the outage shading covers: a column is shaded when
 * at least 90 % of the plot rows have paint (the band's fill covers the plot height; grid and
 * threshold lines are single rows, vertical grid lines single columns, so runs narrower than 3 px
 * are not bands).
 */
async function shadedRuns(page: Page): Promise<{ plotWidth: number; runs: ColumnRun[] }> {
  const measured = await page
    .getByTestId('xlog-canvas-overlay')
    .first()
    .evaluate((node, margin) => {
      const canvas = node as HTMLCanvasElement;
      const context = canvas.getContext('2d');
      const rect = canvas.getBoundingClientRect();
      if (context === null || rect.width === 0 || canvas.width === 0) {
        return null;
      }
      const dpr = canvas.width / rect.width;
      const plotWidth = Math.max(0, Math.floor(rect.width - margin.left - margin.right));
      const plotHeight = Math.max(0, Math.floor(rect.height - margin.top - margin.bottom));
      const image = context.getImageData(0, 0, canvas.width, canvas.height);
      const y0 = Math.ceil((margin.top + 2) * dpr);
      const y1 = Math.floor((margin.top + plotHeight - 2) * dpr);
      const cover: number[] = [];
      for (let x = 0; x < plotWidth; x += 1) {
        const dx = Math.min(canvas.width - 1, Math.floor((margin.left + x + 0.5) * dpr));
        let painted = 0;
        for (let y = y0; y < y1; y += 1) {
          if ((image.data[(y * canvas.width + dx) * 4 + 3] ?? 0) > 0) {
            painted += 1;
          }
        }
        cover.push(painted / Math.max(1, y1 - y0));
      }
      return { plotWidth, cover };
    }, PLOT_MARGIN);
  if (measured === null) {
    throw new Error('the XLog overlay canvas has no 2D context or no size');
  }
  const runs: ColumnRun[] = [];
  let start = -1;
  measured.cover.forEach((c, x) => {
    if (c >= 0.9 && start < 0) {
      start = x;
    } else if (c < 0.9 && start >= 0) {
      runs.push({ start, end: x });
      start = -1;
    }
  });
  if (start >= 0) {
    runs.push({ start, end: measured.cover.length });
  }
  return { plotWidth: measured.plotWidth, runs: runs.filter((r) => r.end - r.start >= 3) };
}

/** Allowed difference, CSS px, between a shaded run and the outage's position on the time axis. */
const BAND_TOLERANCE_PX = 3;

/**
 * 2.1-10 collection outage shading (WO-22, D-18, C-5, ADR-0010 C-205 (2)): the stack setup cut the
 * traffic agent's gRPC path until the server's state machine marked the instance STALE, then
 * restored it (seed manifest `outage`, e2e/stack/seed/outage.ts). On the XLog of the traffic app
 * around it, the API's meta.outages of the range hold that session_lost outage, the legend counts
 * the API's outages, and the hatched columns of the overlay canvas are exactly where the outages
 * fall on the time axis (±3 px).
 */
const xlogOutageShading: XLogCheck = async (ctx) => {
  const { page, seed, testInfo } = ctx;
  const recorded = seed.outage;
  if (recorded === null) {
    const why =
      seed.missing_optional.find((m) => m.category === 'outage')?.reason ?? 'no reason recorded';
    throw new Error(`the stack setup made no collection outage (manifest outage null): ${why}`);
  }
  const api = await ctx.apiAs(EXPECTATION_ROLE);
  const appId = recorded.app_id;
  // From the seed traffic (points on the plot) to 10 s after the instance was UP again. The setup
  // stops the agent right after that; where the stop sends no AgentBye (Windows ends the process
  // at once) the instance turns STALE again 15 s or more after the stop (the E2E stack's
  // stale-after 20 s and heartbeat 5 s, ADR-0010 C-216 (1)), a later outage this range leaves out.
  const fromMs = Math.min(
    Math.floor(Date.parse(seed.traffic.range.start) / MINUTE) * MINUTE,
    Math.floor(Date.parse(recorded.cut_at) / MINUTE) * MINUTE - 2 * MINUTE,
  );
  const toMs = Math.ceil((Date.parse(recorded.up_seen_at) + 10_000) / 1000) * 1000;
  const from = new Date(fromMs).toISOString().replace('.000Z', 'Z');
  const to = new Date(toMs).toISOString().replace('.000Z', 'Z');
  const { meta } = await apiPoints(api, appId, { from, to, kind: 'HTTP', limit: 100_000 });
  const outages = (meta.outages ?? []) as ApiOutage[];
  testInfo.annotations.push({
    type: 'outages',
    description: `${from} .. ${to}: ${JSON.stringify(outages)}; seeded cut ${recorded.cut_at}, STALE seen ${recorded.stale_seen_at}, thawed ${recorded.thawed_at}, UP seen ${recorded.up_seen_at}`,
  });
  // The recorded outage is the API's session_lost outage of the instance (same server times):
  // meta.outages carry no instance, so ask for the instance alone (its outages and the server's).
  const own = await apiPoints(api, appId, {
    from,
    to,
    kind: 'HTTP',
    limit: 1,
    instance: [String(recorded.instance_id)],
  });
  const ours = ((own.meta.outages ?? []) as ApiOutage[]).filter((o) => o.reason === 'session_lost');
  expect(
    ours.map((o) => [Date.parse(o.from), o.to == null ? null : Date.parse(o.to)]),
    `meta.outages of instance ${String(recorded.instance_id)} = the seeded session_lost outage`,
  ).toEqual([[Date.parse(recorded.outage_from), Date.parse(recorded.outage_to)]]);

  await openXLog(page, xlogPath(appId, from, to));
  const root = page.getByTestId('xlog-page');
  const rangeFrom = await intAttr(root, 'data-range-from');
  const rangeTo = await intAttr(root, 'data-range-to');
  expect([rangeFrom, rangeTo], 'the plot range is the URL range').toEqual([fromMs, toMs]);
  // The legend counts the API's outages of the range.
  const count = outages.length;
  await expect(page.getByTestId('xlog-outage-legend')).toHaveText(
    t(`xlog:chart.outages.legend_${count === 1 ? 'one' : 'other'}`, 'en', {
      count: String(count),
    }),
  );
  // The hatched columns are the outages' positions on the time axis, and nothing else.
  const span = rangeTo - rangeFrom;
  await expect
    .poll(
      async () => {
        const { plotWidth, runs } = await shadedRuns(page);
        const expected = outages
          .map((o) => {
            const a = Math.max(Date.parse(o.from), rangeFrom);
            const b = Math.min(o.to == null ? rangeTo : Date.parse(o.to), rangeTo);
            return {
              x1: ((a - rangeFrom) / span) * plotWidth,
              x2: ((b - rangeFrom) / span) * plotWidth,
            };
          })
          .filter((band) => band.x2 > band.x1)
          .sort((p, q) => p.x1 - q.x1);
        const matched =
          runs.length === expected.length &&
          expected.every((band, i) => {
            const run = runs[i];
            return (
              run !== undefined &&
              Math.abs(run.start - band.x1) <= BAND_TOLERANCE_PX &&
              Math.abs(run.end - Math.max(band.x2, band.x1 + 2)) <= BAND_TOLERANCE_PX
            );
          });
        return matched
          ? 'shading = meta.outages'
          : `shaded runs ${JSON.stringify(runs)} vs outage bands ${JSON.stringify(expected.map((b) => [Math.round(b.x1), Math.round(b.x2)]))} (plot ${String(plotWidth)} px)`;
      },
      { timeout: 10_000, message: 'hatched overlay columns vs API meta.outages' },
    )
    .toBe('shading = meta.outages');
};

export const XLOG_CHECKS: Readonly<Record<string, XLogCheck>> = {
  xlogAxesStatusThreshold,
  xlogDragSummaryList,
  xlogColorBy,
  xlogHoverAndClick,
  xlogFiltersAndBatch,
  xlogSamplingDeterministic,
  xlogEventMarkers,
  xlogListColumnsSortCsv,
  xlogListKeyboard,
  xlogResumeLiveRefill,
  xlogLiveWindow,
  xlogCellFilterChips,
  xlogAlertMarker,
  xlogDeterministicReload,
  xlogEmptyExcluded,
  xlogOutageShading,
};
