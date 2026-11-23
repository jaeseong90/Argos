// SPDX-License-Identifier: Apache-2.0
//
// Helpers of the XLog screen specs (WO-33, docs/05 §2.1, docs/15 §9.3): open the screen and wait
// for its first full frame, drag on the plot, read the URL selection, and ask the real API the same
// questions the screen asks (points, summary, search of the same bounds) so that the specs assert
// exact values from the server, never "at least one". The request bodies follow the conversion of
// src/api/transactionSearch.ts (the selection is inclusive, the API's `to` exclusive: to = tsTo + 1).
import { expect, type Locator, type Page } from '@playwright/test';

import type { ApiSession } from '../stack/api';

import { state } from './ui';

export interface Box {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** The URL selection (docs/14 §6 `sel=ms:<min>-<max>,ts:<from>-<to>`). */
export interface Sel {
  readonly msMin: number;
  readonly msMax: number;
  readonly tsFrom: number;
  readonly tsTo: number;
}

/** GET /apps/{id}/xlog data (docs/14 §4.5). */
export interface XlogPoints {
  readonly ts: number[];
  readonly elapsed_ms: number[];
  readonly status: number[];
  readonly kind: number[];
  readonly service_id: string[];
  readonly instance_id: number[];
  readonly tx_id: string[];
  readonly dictionary: {
    readonly service: Record<string, string>;
    readonly instance: Record<string, string>;
  };
}

export interface XlogMeta {
  readonly shown?: number;
  readonly total?: number;
  readonly truncated?: boolean;
  readonly slow_threshold_ms?: number;
  readonly outages?: unknown[];
}

export interface SearchRow {
  readonly tx_id: string;
  readonly ts: string;
  readonly elapsed_ms: number;
  readonly service: string | null;
  readonly service_id: string | null;
  readonly instance: string | null;
  readonly instance_id: number;
  readonly http_status: number;
  readonly kind: string;
  readonly error_class: string | null;
}

export interface SearchPage {
  readonly data: SearchRow[];
  readonly meta: {
    readonly total?: number;
    readonly shown?: number;
    readonly next_cursor?: string | null;
  };
}

export interface SummaryData {
  readonly count: number;
  readonly by_service: { service_id: string | null; name: string | null; count: number }[];
  readonly by_instance: { instance_id: number; name: string | null; count: number }[];
  readonly by_error_class: { error_class_id: string | null; name: string | null; count: number }[];
  readonly by_status: { http_status: number; count: number }[];
}

const SEL_PATTERN = /^ms:(\d+)-(\d+),ts:(\d+)-(\d+)$/;

/** The selection of the page's URL, or null. */
export function selOf(page: Page): Sel | null {
  const raw = state(page).params.get('sel');
  const match = raw === null ? null : SEL_PATTERN.exec(raw);
  if (match === null) {
    return null;
  }
  return {
    msMin: Number(match[1]),
    msMax: Number(match[2]),
    tsFrom: Number(match[3]),
    tsTo: Number(match[4]),
  };
}

/** The `sel` text of a selection (what `data-sel` of the summary and the list carries). */
export function selText(sel: Sel): string {
  return `ms:${String(sel.msMin)}-${String(sel.msMax)},ts:${String(sel.tsFrom)}-${String(sel.tsTo)}`;
}

/** The XLog path of an app with an absolute range and further query pairs. */
export function xlogPath(
  appId: number,
  from: string,
  to: string,
  extra: Readonly<Record<string, string>> = {},
): string {
  const params = new URLSearchParams({ from, to, ...extra });
  return `/apps/${String(appId)}/xlog?${params.toString()}`;
}

/** Opens the XLog screen and waits for its first full frame (`data-ready`). */
export async function openXLog(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expectReady(page);
}

/** Waits until the screen has drawn its data (xlog-page data-ready=true). */
export async function expectReady(page: Page): Promise<void> {
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-ready', 'true', {
    timeout: 60_000,
  });
}

/** The plot area (the point canvas) in page coordinates. */
export async function plotBox(page: Page): Promise<Box> {
  const box = await page.getByTestId('xlog-canvas-points').first().boundingBox();
  if (box === null) {
    throw new Error('the XLog point canvas is not visible');
  }
  return box;
}

/**
 * Drags over the full plot height between the columns `fromX` and `toX` (fractions 0..1 of the plot
 * width): presses on the top edge (points above the axis maximum count) and releases below the
 * bottom edge (0 ms), like the WO-04 PoC specs.
 */
export async function dragColumns(page: Page, fromX: number, toX: number): Promise<void> {
  const box = await plotBox(page);
  await page.mouse.move(box.x + box.width * fromX, box.y);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * toX, box.y + box.height + 8, { steps: 8 });
  await page.mouse.up();
}

/** Drags and waits for the selection in the URL; returns it. */
export async function dragSelect(page: Page, fromX = 0, toX = 1): Promise<Sel> {
  const before = state(page).params.get('sel');
  await dragColumns(page, fromX, toX);
  await expect
    .poll(() => state(page).params.get('sel'), { message: 'sel= written after the drag' })
    .not.toBe(before);
  const sel = selOf(page);
  if (sel === null) {
    throw new Error(`no valid sel in ${page.url()}`);
  }
  return sel;
}

/** Waits until the summary and the list show the selection (data-sel, list ready or empty). */
export async function expectSelectionShown(page: Page, sel: Sel): Promise<void> {
  const key = selText(sel);
  await expect(page.getByTestId('xlog-summary')).toHaveAttribute('data-sel', key);
  await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-sel', key);
  await expect(page.getByTestId('xlog-list')).toHaveAttribute('data-state', /^(ready|empty)$/, {
    timeout: 20_000,
  });
}

/** The filters of the f= vocabulary as search filters (src/api/transactionSearch.ts). */
export interface SearchFilters {
  service_id?: string[];
  instance_id?: number[];
  http_status?: number[];
  error_class_id?: string[];
  error?: boolean;
}

/** The body of the search the list sends for a selection (sort elapsed_ms:desc, 1,000 rows). */
export function searchBody(
  sel: Sel,
  kind: string,
  filters: SearchFilters = {},
  sort = 'elapsed_ms:desc',
): Record<string, unknown> {
  return {
    from: new Date(sel.tsFrom).toISOString(),
    to: new Date(sel.tsTo + 1).toISOString(),
    kind,
    filters: { ...filters, min_ms: sel.msMin, max_ms: sel.msMax },
    sort,
    limit: 1000,
  };
}

/**
 * POST /apps/{id}/transactions/search through the API (same session rules as the browser). The
 * search is in the HEAVY bucket (10 per user per minute, docs/14 §7, C-136 (2)): the specs ask it
 * as another user than the browser's (EXPECTATION_ROLE), and a 429 waits for its Retry-After once
 * (the expectation query, never the screen under test).
 */
export async function apiSearch(
  api: ApiSession,
  appId: number,
  body: Record<string, unknown>,
): Promise<SearchPage> {
  await api.csrf();
  const path = `/apps/${String(appId)}/transactions/search`;
  let response = await api.request<SearchPage>('POST', path, { body, expect: [200, 429] });
  if (response.status === 429) {
    const seconds = Number(response.headers.get('retry-after') ?? '60');
    await new Promise((resolve) => setTimeout(resolve, Math.min(61, Math.max(1, seconds)) * 1000));
    response = await api.request<SearchPage>('POST', path, { body });
  }
  if (response.body === null) {
    throw new Error('empty search answer');
  }
  return response.body;
}

/**
 * The user of the specs' own API queries: not the browser's default (viewer), so that the screen
 * under test keeps its HEAVY search budget (10 per user per minute).
 */
export const EXPECTATION_ROLE = 'admin' as const;

/** POST /apps/{id}/xlog/summary of a selection. */
export async function apiSummary(
  api: ApiSession,
  appId: number,
  sel: Sel,
  kind: string,
  points: { service?: string[]; instance?: number[]; error?: boolean } = {},
): Promise<SummaryData> {
  await api.csrf();
  const response = await api.request<{ data: SummaryData }>(
    'POST',
    `/apps/${String(appId)}/xlog/summary`,
    {
      body: {
        from: new Date(sel.tsFrom).toISOString(),
        to: new Date(sel.tsTo + 1).toISOString(),
        min_ms: sel.msMin,
        max_ms: sel.msMax,
        kind,
        ...points,
      },
    },
  );
  if (response.body === null) {
    throw new Error('empty summary answer');
  }
  return response.body.data;
}

/** GET /apps/{id}/xlog of a range. */
export async function apiPoints(
  api: ApiSession,
  appId: number,
  query: Readonly<Record<string, string | number | boolean | readonly string[]>>,
): Promise<{ data: XlogPoints; meta: XlogMeta }> {
  const answer = await api.query<XlogPoints>(`/apps/${String(appId)}/xlog`, query);
  return { data: answer.data, meta: answer.meta };
}

/**
 * A window ending at `end` (2 minutes, else 1) whose points are not sampled (meta.shown =
 * meta.total > 0), so that the browser summary can count every point of a drag. Short windows keep
 * the specs fast; the tiny seed profile has several thousand points a minute.
 */
export async function unsampledWindow(
  api: ApiSession,
  appId: number,
  end: number,
  kind = 'HTTP',
): Promise<{ from: string; to: string; total: number }> {
  for (const minutes of [2, 1]) {
    const from = new Date(end - minutes * 60_000).toISOString();
    const to = new Date(end).toISOString();
    const { meta } = await apiPoints(api, appId, { from, to, kind, limit: 100_000 });
    if (typeof meta.total === 'number' && meta.total > 0 && meta.shown === meta.total) {
      return { from, to, total: meta.total };
    }
  }
  throw new Error(
    `no unsampled XLog window of app ${String(appId)} ending at ${new Date(end).toISOString()}`,
  );
}

/** Row keys (tx ids) of the rendered list rows, in order. */
export async function listKeys(page: Page): Promise<string[]> {
  return page
    .getByTestId('xlog-list')
    .getByTestId('data-table-row')
    .evaluateAll((rows) => rows.map((r) => r.getAttribute('data-row-key') ?? ''));
}

/** The cell of `column` in a list row. */
export function listCell(row: Locator, column: string): Locator {
  return row.locator(`[data-column="${column}"]`);
}

/** Integer of a `data-*` attribute. */
export async function intAttr(locator: Locator, name: string): Promise<number> {
  const value = await locator.getAttribute(name);
  if (value === null || !/^-?\d+$/.test(value)) {
    throw new Error(`${name} is ${String(value)}`);
  }
  return Number(value);
}
