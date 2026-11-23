// SPDX-License-Identifier: Apache-2.0
//
// Helpers of the statistics specs (WO-36, docs/05 §2.5, docs/15 §9.7, docs/14 §4.8): open a
// statistics screen and wait for its data, read what it shows through the data-testid contract of
// the WO-36 plan, and ask the real API the same question the screen asks (the eight statistics
// endpoints and GET /events of the same bounds), so that the specs assert exact values from the
// server, never "at least one". Contract (WO-36 plan §1):
//   stats-services | stats-service-detail | stats-sql | stats-sql-detail | stats-external |
//   stats-external-detail | stats-errors | stats-error-detail   screen roots, data-state
//                                        loading | empty | error | ready; <root>-empty,
//                                        <root>-error, <root>-loading
//   DataTable data-table-id              stats-services, stats-sql, stats-external, stats-errors,
//                                        stats-by-version, stats-by-instance, stats-by-service,
//                                        stats-by-db, stats-top-sql, stats-top-external,
//                                        stats-top-errors, stats-related-tx, stats-recent-tx
//   rows                                 data-row-key = the API key (decimal string)
//   cells                                data-column = the API field; stats-value data-value (raw
//                                        API value, "" and data-missing for null);
//                                        stats-delta-<field> data-value (raw API delta)
//   stats-sort-<field> (aria-pressed), stats-limit, stats-split-version, stats-errors-new,
//   estimated-badge, stats-trend, stats-cross-tab-{sql|external|errors}, stats-sql-text,
//   stats-sql-normalized, stats-sql-wait, stats-error-stack, stats-error-message,
//   stats-related-note, stats-raw-window, stats-source, stats-open-xlog, stats-filter-scope
import { expect, type Locator, type Page } from '@playwright/test';

import type { ApiSession } from '../stack/api';

import { parseValueAttr } from './dashboardOracle';
import { absolute } from './ui';
import { effectiveKind, urlState } from './urlModel';

export { apiEvents, numAttr, ORACLE_ROLE, parseValueAttr } from './dashboardOracle';

/** The four statistics lists (side menu ids, route segments and API resources). */
export const STATS_SCREENS = ['services', 'sql', 'external', 'errors'] as const;
export type StatsScreen = (typeof STATS_SCREENS)[number];

/** Root test id of each list screen (= its DataTable id). */
export const LIST_ROOT: Readonly<Record<StatsScreen, string>> = {
  services: 'stats-services',
  sql: 'stats-sql',
  external: 'stats-external',
  errors: 'stats-errors',
};

/** Root test id of each detail screen. */
export const DETAIL_ROOT: Readonly<Record<StatsScreen, string>> = {
  services: 'stats-service-detail',
  sql: 'stats-sql-detail',
  external: 'stats-external-detail',
  errors: 'stats-error-detail',
};

/** The list resource of each screen (docs/14 §4.8). */
export const LIST_API: Readonly<Record<StatsScreen, string>> = {
  services: 'services/stats',
  sql: 'sql/stats',
  external: 'external/stats',
  errors: 'errors/groups',
};

/** The detail resource prefix of each screen (docs/14 §4.8): `<prefix>/{id}`. */
export const DETAIL_API: Readonly<Record<StatsScreen, string>> = {
  services: 'services',
  sql: 'sql',
  external: 'external',
  errors: 'errors/groups',
};

/** Default Top N of the lists (docs/14 §4.8 default 100, the screen's default `limit`). */
export const DEFAULT_LIMIT = 100;
/** Largest JSON page of a statistics list (docs/14 §4.8 limit ≤ 500). */
export const MAX_LIMIT = 500;

// ------------------------------------------------------------------------------- API types

/** docs/api/openapi.yaml StatsDelta. */
export interface StatsDelta {
  readonly count: number | null;
  readonly p95_ms: number | null;
  readonly error_rate: number | null;
}

/** docs/api/openapi.yaml ServiceStats. */
export interface ServiceStats {
  readonly service_id: string;
  readonly service: string;
  readonly version_id?: string;
  readonly version?: string | null;
  readonly count: number;
  readonly errors: number;
  readonly error_rate: number | null;
  readonly avg_ms: number | null;
  readonly p50_ms: number | null;
  readonly p95_ms: number | null;
  readonly p99_ms: number | null;
  readonly max_ms: number | null;
  readonly apdex: number | null;
  readonly sum_ms: number;
  readonly sql_time_ratio: number | null;
  readonly ext_time_ratio: number | null;
  readonly delta?: StatsDelta;
}

/** docs/api/openapi.yaml DbTarget. */
export interface DbTarget {
  readonly db_id: string;
  readonly target: string;
  readonly vendor: string | null;
  readonly host: string | null;
  readonly name: string | null;
}

/** docs/api/openapi.yaml SqlStats. */
export interface SqlStats {
  readonly sql_id: string;
  readonly sql_normalized: string;
  readonly sql_truncated: boolean;
  readonly db: DbTarget;
  readonly count: number;
  readonly errors: number;
  readonly sum_ms: number;
  readonly exec_ms: number;
  readonly fetch_ms: number;
  readonly avg_ms: number | null;
  readonly max_ms: number | null;
  readonly p50_ms: number | null;
  readonly p95_ms: number | null;
  readonly p99_ms: number | null;
  readonly estimated: boolean;
  readonly fetch_rows: number;
  readonly delta?: StatsDelta;
}

/** docs/api/openapi.yaml ExternalStats. */
export interface ExternalStats {
  readonly host_id: string;
  readonly host: string;
  readonly path_pattern: string | null;
  readonly count: number;
  readonly errors: number;
  readonly timeouts: number;
  readonly connect_failures: number;
  readonly sum_ms: number;
  readonly avg_ms: number | null;
  readonly max_ms: number | null;
  readonly p50_ms: number | null;
  readonly p95_ms: number | null;
  readonly p99_ms: number | null;
  readonly estimated: boolean;
  readonly delta?: StatsDelta;
}

/** docs/api/openapi.yaml StatsBreakdown. */
export interface Breakdown {
  readonly id: string;
  readonly name: string | null;
  readonly count: number;
  readonly errors: number | null;
  readonly avg_ms: number | null;
  readonly p95_ms: number | null;
  readonly share: number | null;
}

/** docs/api/openapi.yaml ErrorGroup (+ `delta` of compare_prev, WO-36 lane C, T-API-011b). */
export interface ErrorGroup {
  readonly group_id: string;
  readonly error_class: string | null;
  readonly message_pattern: string | null;
  readonly top_frame: string | null;
  readonly count: number;
  readonly first_seen: string;
  readonly last_seen: string;
  readonly first_seen_ever: string | null;
  readonly is_new: boolean;
  readonly services: readonly Breakdown[];
  readonly versions: readonly { version_id: string; version: string | null }[];
  readonly delta?: StatsDelta;
}

export type StatsRow = ServiceStats | SqlStats | ExternalStats | ErrorGroup;

/** docs/api/openapi.yaml StatsSeries. */
export interface StatsSeries {
  readonly ts: readonly string[];
  readonly count: readonly (number | null)[];
  readonly errors: readonly (number | null)[] | null;
  readonly error_rate: readonly (number | null)[] | null;
  readonly avg_ms: readonly (number | null)[] | null;
  readonly p95_ms: readonly (number | null)[] | null;
  readonly p99_ms?: readonly (number | null)[];
}

/** docs/api/openapi.yaml RelatedTransaction. */
export interface RelatedTx {
  readonly tx_id: string;
  readonly ts: string;
  readonly service_id: string;
  readonly service: string;
  readonly instance_id: number;
  readonly elapsed_ms: number;
}

export interface RawWindow {
  readonly from: string;
  readonly to: string;
}

/** docs/api/openapi.yaml ServiceDetail. */
export interface ServiceDetail {
  readonly service_id: string;
  readonly service: string;
  readonly series: StatsSeries;
  readonly by_version: readonly Breakdown[];
  readonly by_instance: readonly Breakdown[];
  readonly top_sql: readonly Breakdown[];
  readonly top_external: readonly Breakdown[];
  readonly top_error_groups: readonly Breakdown[];
  readonly raw_window: RawWindow | null;
}

/** docs/api/openapi.yaml SqlDetail. */
export interface SqlDetail {
  readonly sql_id: string;
  readonly sql_normalized: string;
  readonly sql_text: string;
  readonly sql_truncated: boolean;
  readonly norm_version: number | null;
  readonly series: StatsSeries;
  readonly estimated: boolean;
  readonly count: number;
  readonly exec_ms_total: number;
  readonly fetch_ms_total: number;
  readonly wait_ms_total: number | null;
  readonly by_db: readonly SqlStats[];
  readonly by_service: readonly Breakdown[];
  readonly by_instance: readonly Breakdown[];
  readonly related_tx: { readonly note: string; readonly items: readonly RelatedTx[] };
  readonly raw_window: RawWindow | null;
}

/** docs/api/openapi.yaml ExternalDetail. */
export interface ExternalDetail {
  readonly host_id: string;
  readonly host: string;
  readonly series: StatsSeries;
  readonly estimated: boolean;
  readonly by_service: readonly Breakdown[];
  readonly related_tx: { readonly note: string; readonly items: readonly RelatedTx[] };
  readonly raw_window: RawWindow | null;
}

/** docs/api/openapi.yaml ErrorGroupDetail. */
export interface ErrorGroupDetail {
  readonly group: ErrorGroup;
  readonly sample_stack: readonly string[] | null;
  readonly series: StatsSeries;
  readonly by_version: readonly Breakdown[];
  readonly by_instance: readonly Breakdown[];
  readonly by_service: readonly Breakdown[];
  readonly recent_tx: readonly RelatedTx[];
  readonly raw_window: RawWindow | null;
}

export interface ListRowOf {
  readonly services: ServiceStats;
  readonly sql: SqlStats;
  readonly external: ExternalStats;
  readonly errors: ErrorGroup;
}

export interface DetailOf {
  readonly services: ServiceDetail;
  readonly sql: SqlDetail;
  readonly external: ExternalDetail;
  readonly errors: ErrorGroupDetail;
}

/** The question a list asks (docs/14 §4.8 query parameters). */
export interface ListQuery {
  readonly from: string;
  readonly to: string;
  readonly kind: string;
  /** `field:asc|desc`, or undefined for the API default (sum_ms:desc, errors count:desc). */
  readonly sort?: string;
  readonly limit: number;
  readonly compare_prev?: boolean;
  readonly split_version?: boolean;
  readonly new_only?: boolean;
  readonly db_id?: string;
}

export interface ListAnswer<Row> {
  readonly data: Row[];
  readonly meta: Record<string, unknown>;
}

/** GET /apps/{appId}/<list resource> as the screen asks it. */
export async function apiStatsList<S extends StatsScreen>(
  api: ApiSession,
  screen: S,
  appId: number,
  q: ListQuery,
): Promise<ListAnswer<ListRowOf[S]>> {
  const answer = await api.query<ListRowOf[S][]>(`/apps/${String(appId)}/${LIST_API[screen]}`, {
    from: q.from,
    to: q.to,
    kind: q.kind,
    sort: q.sort,
    limit: q.limit,
    compare_prev: q.compare_prev === true ? 'true' : undefined,
    split_version: q.split_version === true ? 'true' : undefined,
    new_only: q.new_only === true ? 'true' : undefined,
    db_id: q.db_id,
  });
  return { data: answer.data, meta: answer.meta };
}

/** GET /apps/{appId}/<detail resource>/{id} of the same bounds and kind. */
export async function apiStatsDetail<S extends StatsScreen>(
  api: ApiSession,
  screen: S,
  appId: number,
  id: string,
  q: { readonly from: string; readonly to: string; readonly kind?: string },
): Promise<DetailOf[S]> {
  return api.data<DetailOf[S]>(
    `/apps/${String(appId)}/${DETAIL_API[screen]}/${encodeURIComponent(id)}`,
    { from: q.from, to: q.to, kind: q.kind ?? 'HTTP' },
  );
}

/** The API key of a list row (the DataTable row key of the contract). */
export function rowKey(screen: StatsScreen, row: StatsRow): string {
  switch (screen) {
    case 'services':
      return (row as ServiceStats).service_id;
    case 'sql':
      return (row as SqlStats).sql_id;
    case 'external':
      return (row as ExternalStats).host_id;
    case 'errors':
      return (row as ErrorGroup).group_id;
  }
}

// ------------------------------------------------------------------------------- URL state

/** `-field` (docs/15) or `field:asc|desc` (docs/14) as `field:asc|desc`; null when absent. */
export function normalizeSort(raw: string | null | undefined): string | null {
  if (raw === null || raw === undefined || raw === '') {
    return null;
  }
  if (/^[a-z_][a-z0-9_.]*:(asc|desc)$/.test(raw)) {
    return raw;
  }
  if (raw.startsWith('-')) {
    return `${raw.slice(1)}:desc`;
  }
  return `${raw}:asc`;
}

/** A URL flag (`1` or `true`). */
function flag(params: URLSearchParams, name: string): boolean {
  const value = params.get(name);
  return value === '1' || value === 'true';
}

/** The list question of a statistics address (absolute range, kind, sort, Top N, flags). */
export function listQueryOf(address: string): ListQuery {
  const s = urlState(address);
  const { fromMs, toMs } = absolute(s);
  const limit = s.params.get('limit');
  const sort = normalizeSort(s.params.get('sort'));
  return {
    from: new Date(fromMs).toISOString(),
    to: new Date(toMs).toISOString(),
    kind: effectiveKind(s),
    ...(sort === null ? {} : { sort }),
    limit: limit === null ? DEFAULT_LIMIT : Number(limit),
    ...(flag(s.params, 'split') ? { split_version: true } : {}),
    ...(flag(s.params, 'new') ? { new_only: true } : {}),
  };
}

/** The application id of a `/apps/:appId/...` address. */
export function appIdOf(address: string): number {
  const match = /\/apps\/(\d+)\//.exec(new URL(address, 'http://x').pathname);
  if (match === null) {
    throw new Error(`not an application route: ${address}`);
  }
  return Number(match[1]);
}

/** A statistics list route with an absolute range and extra query parameters. */
export function statsPath(
  appId: number,
  screen: StatsScreen,
  from: string,
  to: string,
  extra: Readonly<Record<string, string>> = {},
): string {
  const params = new URLSearchParams({ from, to, ...extra });
  return `/apps/${String(appId)}/stats/${screen}?${params.toString()}`;
}

/** A statistics detail route. */
export function statsDetailPath(
  appId: number,
  screen: StatsScreen,
  id: string,
  from: string,
  to: string,
  extra: Readonly<Record<string, string>> = {},
): string {
  const params = new URLSearchParams({ from, to, ...extra });
  return `/apps/${String(appId)}/stats/${screen}/${encodeURIComponent(id)}?${params.toString()}`;
}

// ------------------------------------------------------------------------------- page state

export type StatsState = 'loading' | 'empty' | 'error' | 'ready';

/** Waits until the screen `root` shows `state` (default ready). */
export async function expectStatsState(
  page: Page,
  root: string,
  state: StatsState = 'ready',
): Promise<Locator> {
  const locator = page.getByTestId(root);
  await expect(locator, `${root} data-state`).toHaveAttribute('data-state', state, {
    timeout: 30_000,
  });
  return locator;
}

/** Opens `path` and waits for the ready screen `root`. */
export async function openStats(page: Page, path: string, root: string): Promise<Locator> {
  await page.goto(path);
  return expectStatsState(page, root);
}

/** The DataTable of `tableId`. */
export function statsTable(page: Page, tableId: string): Locator {
  return page.locator(`[data-testid="data-table"][data-table-id="${tableId}"]`);
}

/** The body rows of a table (screens may replace data-table-row; the row key stays). */
export function tableRows(table: Locator): Locator {
  return table.locator('tbody tr[data-row-key]');
}

/** One body row of a table by its key. */
export function tableRow(table: Locator, key: string): Locator {
  return table.locator(`tbody tr[data-row-key="${key}"]`);
}

/** What one body row shows: its key, the raw values and the deltas of its cells. */
export interface ShownRow {
  readonly key: string;
  /** data-column → data-value of its stats-value ("" for null, absent when the cell has none). */
  readonly values: Readonly<Record<string, string | null>>;
  /** field → data-value of stats-delta-<field>. */
  readonly deltas: Readonly<Record<string, string | null>>;
  /** data-column → text of the cell. */
  readonly texts: Readonly<Record<string, string>>;
}

/** Reads every body row of a table in display order. */
export async function shownRows(table: Locator): Promise<ShownRow[]> {
  return tableRows(table).evaluateAll((rows) =>
    rows.map((row) => {
      const values: Record<string, string | null> = {};
      const deltas: Record<string, string | null> = {};
      const texts: Record<string, string> = {};
      for (const cell of Array.from(row.querySelectorAll('td[data-column]'))) {
        const column = cell.getAttribute('data-column') ?? '';
        texts[column] = cell.textContent;
        const value = cell.querySelector('[data-testid="stats-value"]');
        if (value !== null) {
          values[column] = value.getAttribute('data-value');
        }
        // The change element carries data-value; its inner text element (…-text) does not.
        for (const delta of Array.from(
          cell.querySelectorAll('[data-testid^="stats-delta-"][data-value]'),
        )) {
          const field = (delta.getAttribute('data-testid') ?? '').slice('stats-delta-'.length);
          deltas[field] = delta.getAttribute('data-value');
        }
      }
      return { key: row.getAttribute('data-row-key') ?? '', values, deltas, texts };
    }),
  );
}

/** The row keys of a table in display order. */
export async function shownKeys(table: Locator): Promise<string[]> {
  return tableRows(table).evaluateAll((rows) =>
    rows.map((row) => row.getAttribute('data-row-key') ?? ''),
  );
}

/**
 * The API key a row key starts with: a composite key (SQL statement per target, service per
 * version) begins with the list key, followed by a non-digit separator.
 */
export function leadingId(key: string): string {
  return /^\d+/.exec(key)?.[0] ?? key;
}

/** Polls until the table shows exactly `keys` (leading ids) in this order. */
export async function expectKeys(
  table: Locator,
  keys: readonly string[],
  message: string,
): Promise<void> {
  await expect
    .poll(async () => (await shownKeys(table)).map(leadingId), { message, timeout: 20_000 })
    .toEqual([...keys]);
}

/** Polls until the table shows exactly the set of `keys` (any order). */
export async function expectKeySet(
  table: Locator,
  keys: readonly string[],
  message: string,
): Promise<void> {
  await expect
    .poll(async () => (await shownKeys(table)).map(leadingId).sort(), { message, timeout: 20_000 })
    .toEqual([...keys].sort());
}

/** A row's raw API value of `field` (numbers and null; undefined when the row has no such field). */
export function apiValue(row: object, field: string): number | null | undefined {
  const value = (row as Record<string, unknown>)[field];
  if (value === undefined) {
    return undefined;
  }
  return typeof value === 'number' ? value : null;
}

/**
 * Differences between shown rows and API rows (index by index): every `required` field must be
 * shown and equal; `optional` fields are compared when shown; every delta shown must equal the
 * API delta, and the `deltas` fields must be shown. Exact numbers; null (missing) is never 0.
 */
export function rowMismatches(
  shown: readonly ShownRow[],
  rows: readonly object[],
  fields: { readonly required: readonly string[]; readonly optional?: readonly string[] },
  deltas: readonly string[] = [],
): string[] {
  const out: string[] = [];
  if (shown.length !== rows.length) {
    out.push(`rows: shown ${String(shown.length)}, API ${String(rows.length)}`);
  }
  rows.forEach((row, index) => {
    const s = shown[index];
    if (s === undefined) {
      return;
    }
    for (const field of [...fields.required, ...(fields.optional ?? [])]) {
      const raw = s.values[field];
      if (raw === undefined) {
        if (fields.required.includes(field)) {
          out.push(`row ${String(index)} (${s.key}): ${field} not shown`);
        }
        continue;
      }
      let value: number | null;
      try {
        value = parseValueAttr(raw);
      } catch {
        out.push(`row ${String(index)} (${s.key}): ${field} data-value ${String(raw)}`);
        continue;
      }
      const want = apiValue(row, field);
      if (value !== want) {
        out.push(
          `row ${String(index)} (${s.key}): ${field} shown ${String(value)}, API ${String(want)}`,
        );
      }
    }
    const delta = (row as { delta?: StatsDelta }).delta;
    for (const field of new Set([...deltas, ...Object.keys(s.deltas)])) {
      const raw = s.deltas[field];
      if (raw === undefined) {
        out.push(`row ${String(index)} (${s.key}): delta ${field} not shown`);
        continue;
      }
      if (delta === undefined) {
        out.push(`row ${String(index)} (${s.key}): the API answered no delta`);
        continue;
      }
      const want = (delta as unknown as Record<string, number | null | undefined>)[field];
      const value = parseValueAttr(raw);
      if (value !== (want ?? null)) {
        out.push(
          `row ${String(index)} (${s.key}): delta ${field} shown ${String(value)}, API ${String(want)}`,
        );
      }
    }
  });
  return out;
}

/** Sum of the data-count of the chart markers inside `chart` (close events share one marker). */
export async function markerTotal(chart: Locator): Promise<number> {
  const counts = await chart
    .getByTestId('chart-marker')
    .evaluateAll((nodes) => nodes.map((n) => Number(n.getAttribute('data-count') ?? '0')));
  return counts.reduce((a, b) => a + b, 0);
}

/** The marker ids (data-marker-id) of a chart. */
export async function markerIds(chart: Locator): Promise<string[]> {
  return chart
    .getByTestId('chart-marker')
    .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-marker-id') ?? ''));
}

/** Path and query of a link (href) as a URL against a dummy origin. */
export async function hrefOf(link: Locator): Promise<URL> {
  return new URL((await link.getAttribute('href')) ?? '', 'http://x');
}

/** Opens the detail of the row `key`: its link to a statistics detail, else Enter on the row. */
export async function openRow(table: Locator, key: string): Promise<void> {
  const row = tableRow(table, key).first();
  await expect(row, `row ${key}`).toBeVisible();
  const link = row.locator('a[href*="/stats/"]');
  if ((await link.count()) > 0) {
    await link.first().click();
    return;
  }
  await row.focus();
  await row.press('Enter');
}

/** Whitespace-free text (a text compare that ignores line breaks and indentation). */
export function squash(text: string): string {
  return text.replace(/\s+/g, '');
}

// ------------------------------------------------------------------------------- CSV

/**
 * Parses a docs/14 §4.17 CSV body (no BOM): quoted cells, `""` for a quote, CRLF line ends. Line
 * breaks inside values are written as `\n` text by the server, so a record is one line; quoted
 * line breaks are accepted anyway.
 */
export function parseCsv(text: string): string[][] {
  const records: string[][] = [];
  let record: string[] = [];
  let cell = '';
  let quoted = false;
  let i = 0;
  while (i < text.length) {
    const c = text.charAt(i);
    if (quoted) {
      if (c === '"') {
        if (text.charAt(i + 1) === '"') {
          cell += '"';
          i += 2;
          continue;
        }
        quoted = false;
        i += 1;
        continue;
      }
      cell += c;
      i += 1;
      continue;
    }
    if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      record.push(cell);
      cell = '';
    } else if (c === '\r' && text.charAt(i + 1) === '\n') {
      record.push(cell);
      records.push(record);
      record = [];
      cell = '';
      i += 2;
      continue;
    } else {
      cell += c;
    }
    i += 1;
  }
  if (quoted) {
    throw new Error('CSV ends inside a quoted cell');
  }
  if (cell !== '' || record.length > 0) {
    record.push(cell);
    records.push(record);
  }
  return records;
}
