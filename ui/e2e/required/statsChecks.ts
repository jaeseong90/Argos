// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the statistics screens (WO-36): the docs/05 §2.5 checklist of
// ui/e2e/required/stats.yaml. Each runs on the real stack (server, ClickHouse, PostgreSQL, seed)
// after the item's route and asserts exact values: from the URL, the seed manifest or the API asked
// the same question inside the check (the eight statistics endpoints of docs/14 §4.8 and GET
// /events of the same bounds, e2e/fixtures/statsOracle.ts). Service, SQL, host and group ids are
// resolved through the API inside the checks (never fixed). Parts the seed cannot show yet are
// test.fixme entries of the YAML and of specs/stats.spec.ts naming their owner (WO-16, WO-19, WO-26,
// F-U11 / WO-70).
//
// Filter placement (WO-36 PM question 2, assumption): a value click or a column filter goes to the
// URL (f.<field>); a field with a server parameter (SQL db → db_id) is sent to the server, the
// others narrow the received Top N on the client and the screen says so (stats-filter-scope).
import { readFileSync } from 'node:fs';

import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';

import { t } from '../fixtures/i18n';
import {
  apiEvents,
  apiStatsDetail,
  apiStatsList,
  appIdOf,
  DETAIL_ROOT,
  expectKeys,
  expectKeySet,
  expectStatsState,
  hrefOf,
  leadingId,
  LIST_API,
  LIST_ROOT,
  listQueryOf,
  markerTotal,
  normalizeSort,
  openRow,
  ORACLE_ROLE,
  parseCsv,
  parseValueAttr,
  rowKey,
  rowMismatches,
  shownRows,
  squash,
  statsTable,
  tableRow,
  tableRows,
  type ErrorGroup,
  type ExternalStats,
  type ListQuery,
  type ListRowOf,
  type ServiceDetail,
  type ServiceStats,
  type SqlStats,
  type StatsScreen,
} from '../fixtures/statsOracle';
import { expectUrl } from '../fixtures/ui';
import { effectiveKind, filters } from '../fixtures/urlModel';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface StatsCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}

type StatsCheck = (ctx: StatsCheckContext) => Promise<void>;

/** Text of a value ValueText marks "not measured" (C-178 (2): SQL connection wait). */
export const NOT_MEASURABLE = (): string => t('common:component.missing.notMeasurable');

// ------------------------------------------------------------------------------- list specs

type Candidates = readonly (string | null | undefined)[];

/** What one list must show and how its values filter (docs/05 §2.5-1, docs/14 §4.8). */
export interface ListSpec<S extends StatsScreen> {
  readonly screen: S;
  /**
   * The sort segments (05 §2.5-1: total time, count, average, p95, errors) as API sort fields.
   * Services sort errors by error_rate (no `errors` sort, docs/14 §4.8); the error groups sort by
   * count, first and last occurrence.
   */
  readonly sortFields: readonly string[];
  /** The API default sort (no `sort` in the URL). */
  readonly defaultSort: string;
  /** Raw values compared in every row: required (must be shown) and optional (when shown). */
  readonly values: { readonly required: readonly string[]; readonly optional: readonly string[] };
  /** Change columns (05 §2.5-1 count, p95, error rate) that must be shown. */
  readonly deltas: readonly string[];
  /** Columns whose header carries estimated-badge (bucket estimates, C-14); empty: none at all. */
  readonly estimated: readonly string[];
  /** Columns offering the value click, first shown one used (API field names). */
  readonly clickColumns: readonly string[];
  /** Filter field → the row values that field matches (key and name). */
  readonly fieldValues: Readonly<Record<string, (row: ListRowOf[S]) => Candidates>>;
  /** Server parameters of a filter (SQL db → db_id), or null when the filter is client side. */
  readonly serverFilter?: (
    field: string,
    value: string,
    rows: readonly ListRowOf[S][],
  ) => Partial<ListQuery> | null;
  /** The first CSV column (the row key). */
  readonly csvKey: string;
}

const SERVICES: ListSpec<'services'> = {
  screen: 'services',
  sortFields: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'error_rate'],
  defaultSort: 'sum_ms:desc',
  values: {
    required: ['count', 'errors', 'error_rate', 'avg_ms', 'p95_ms', 'sum_ms'],
    optional: ['p50_ms', 'p99_ms', 'max_ms', 'apdex', 'sql_time_ratio', 'ext_time_ratio'],
  },
  deltas: ['count', 'p95_ms', 'error_rate'],
  estimated: [],
  clickColumns: ['service'],
  fieldValues: {
    service: (r) => [r.service_id, r.service],
    service_id: (r) => [r.service_id],
    version: (r) => [r.version_id, r.version],
  },
  csvKey: 'service_id',
};

const SQL: ListSpec<'sql'> = {
  screen: 'sql',
  sortFields: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'errors'],
  defaultSort: 'sum_ms:desc',
  values: {
    required: ['count', 'errors', 'sum_ms', 'avg_ms', 'p95_ms'],
    optional: ['exec_ms', 'fetch_ms', 'max_ms', 'p50_ms', 'p99_ms', 'fetch_rows'],
  },
  deltas: ['count', 'p95_ms'],
  estimated: ['p50_ms', 'p95_ms', 'p99_ms'],
  clickColumns: ['db', 'db_target', 'db_id'],
  fieldValues: {
    db: (r) => [r.db.db_id, r.db.target],
    db_id: (r) => [r.db.db_id],
    db_target: (r) => [r.db.db_id, r.db.target],
    sql: (r) => [r.sql_id],
  },
  serverFilter: (field, value, rows) => {
    if (!['db', 'db_id', 'db_target'].includes(field)) {
      return null;
    }
    const row = rows.find((r) => r.db.db_id === value || r.db.target === value);
    return { db_id: row?.db.db_id ?? value };
  },
  csvKey: 'sql_id',
};

const EXTERNAL: ListSpec<'external'> = {
  screen: 'external',
  sortFields: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'errors'],
  defaultSort: 'sum_ms:desc',
  values: {
    required: ['count', 'errors', 'timeouts', 'connect_failures', 'p50_ms', 'p95_ms'],
    optional: ['sum_ms', 'avg_ms', 'max_ms', 'p99_ms'],
  },
  deltas: ['count', 'p95_ms'],
  estimated: ['p50_ms', 'p95_ms', 'p99_ms'],
  clickColumns: ['host'],
  fieldValues: {
    host: (r) => [r.host_id, r.host],
    host_id: (r) => [r.host_id],
  },
  csvKey: 'host_id',
};

const ERRORS: ListSpec<'errors'> = {
  screen: 'errors',
  sortFields: ['count', 'first_seen', 'last_seen'],
  defaultSort: 'count:desc',
  values: { required: ['count'], optional: [] },
  deltas: ['count'],
  estimated: [],
  clickColumns: ['error_class'],
  fieldValues: {
    error_class: (r) => [r.error_class],
    error_group: (r) => [r.group_id],
    group_id: (r) => [r.group_id],
    service: (r) => r.services.flatMap((s) => [s.id, s.name]),
    version: (r) => r.versions.flatMap((v) => [v.version_id, v.version]),
  },
  csvKey: 'group_id',
};

export const LISTS: { readonly [S in StatsScreen]: ListSpec<S> } = {
  services: SERVICES,
  sql: SQL,
  external: EXTERNAL,
  errors: ERRORS,
};

// ------------------------------------------------------------------------------- list steps

function keysOf<S extends StatsScreen>(screen: S, rows: readonly ListRowOf[S][]): string[] {
  return rows.map((r) => rowKey(screen, r));
}

/** The list rows of the page's question (URL), with the changes the screen always asks for. */
async function apiRowsOfPage<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  extra: Partial<ListQuery> = {},
): Promise<ListRowOf[S][]> {
  const q: ListQuery = { ...listQueryOf(page.url()), compare_prev: true, ...extra };
  return (await apiStatsList(api, spec.screen, appIdOf(page.url()), q)).data;
}

/** The table shows `rows` in their order with their values and changes (exact, null ≠ 0). */
export async function expectRowsShown<S extends StatsScreen>(
  table: Locator,
  spec: ListSpec<S>,
  rows: readonly ListRowOf[S][],
  message: string,
): Promise<void> {
  await expectKeys(table, keysOf(spec.screen, rows), `${message}: rows in API order`);
  await expect
    .poll(async () => rowMismatches(await shownRows(table), rows, spec.values, spec.deltas), {
      message: `${message}: values and changes = API`,
      timeout: 20_000,
    })
    .toEqual([]);
}

/**
 * The list `screen` of the page shows the API rows of the page's question (rows in order, values,
 * changes); resolves with those rows (at least one).
 */
export async function expectListMatchesApi<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  screen: S,
): Promise<ListRowOf[S][]> {
  const spec: ListSpec<S> = LISTS[screen];
  const rows = await apiRowsOfPage(page, api, spec);
  expect(rows.length, `${screen}: API rows of the range`).toBeGreaterThan(0);
  await expectRowsShown(statsTable(page, LIST_ROOT[screen]), spec, rows, screen);
  return rows;
}

/** Selects a Top N (native select, or a stats-limit-<n> option of a custom control). */
export async function setLimit(page: Page, limit: number): Promise<void> {
  const control = page.getByTestId('stats-limit');
  const tag = await control.evaluate((node) => node.tagName);
  if (tag === 'SELECT') {
    await control.selectOption(String(limit));
  } else {
    await control.click();
    await page.getByTestId(`stats-limit-${String(limit)}`).click();
  }
}

/** The first of `columns` the table has a header for. */
async function firstColumn(table: Locator, columns: readonly string[]): Promise<string> {
  for (const column of columns) {
    if ((await table.locator(`thead th[data-column="${column}"]`).count()) > 0) {
      return column;
    }
  }
  throw new Error(`none of the columns ${columns.join(', ')} is shown`);
}

/** 05 §2.5-1 sort switch: each segment sorts the server's Top N (URL sort, API order). */
async function sortSegments<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
): Promise<void> {
  for (const field of spec.sortFields) {
    const button = page.getByTestId(`stats-sort-${field}`);
    await button.click();
    await expect(button, `segment ${field} pressed`).toHaveAttribute('aria-pressed', 'true');
    const s = await expectUrl(
      page,
      (x) => (normalizeSort(x.params.get('sort')) ?? spec.defaultSort).split(':')[0] === field,
      `sort ${field} in the URL`,
    );
    const sort = normalizeSort(s.params.get('sort')) ?? spec.defaultSort;
    const rows = await apiRowsOfPage(page, api, spec);
    await expectRowsShown(table, spec, rows, `sort ${sort}`);
  }
}

/** 05 §2.5-1 Top N: 10 rows of the API's top 10, then the default again. */
async function topN<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
): Promise<void> {
  await setLimit(page, 10);
  await expectUrl(page, (s) => s.params.get('limit') === '10', 'limit=10 in the URL');
  const top = await apiRowsOfPage(page, api, spec);
  expect(top.length, 'API top 10').toBeLessThanOrEqual(10);
  await expectKeys(table, keysOf(spec.screen, top), 'Top 10 = API limit=10');
  await setLimit(page, 100);
  await expectUrl(
    page,
    (s) => (s.params.get('limit') ?? '100') === '100',
    'the default Top N (100) again',
  );
}

/** Rows of `base` whose `field` matches `value` (client filter of the received Top N). */
function matching<S extends StatsScreen>(
  spec: ListSpec<S>,
  field: string,
  value: string,
  base: readonly ListRowOf[S][],
): ListRowOf[S][] {
  const candidates = spec.fieldValues[field];
  if (candidates === undefined) {
    throw new Error(`${spec.screen}: no oracle for the filter field ${field}`);
  }
  return base.filter((row) =>
    candidates(row).some((c) => c !== null && c !== undefined && c === value),
  );
}

/** Removes the chip of `field` (FilterChipBar) and waits until the URL has no filter left. */
async function removeChip(page: Page, field: string): Promise<void> {
  await page
    .locator(`[data-testid="filter-chip"][data-field="${field}"]`)
    .first()
    .getByTestId('filter-chip-remove')
    .click();
  await expectUrl(page, (s) => s.filters.every((f) => f.field !== field), `${field} chip removed`);
}

/**
 * 05 §2.5-1 value click (2.0-4): include on the first row's cell → a chip and f.<field>=<value> in
 * the URL, the rows the filter keeps (server parameter or the received Top N narrowed, with the
 * scope notice); exclude keeps the others; removing the chip restores the list.
 */
async function valueClick<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
): Promise<void> {
  const base = await apiRowsOfPage(page, api, spec);
  await expectKeys(table, keysOf(spec.screen, base), 'rows before the value click');
  const column = await firstColumn(table, spec.clickColumns);
  const cell = tableRows(table).first().locator(`td[data-column="${column}"]`);
  await cell.hover();
  await cell.getByTestId('cell-filter-include').click();
  const chip = page.getByTestId('filter-chip');
  await expect(chip, 'one chip after the value click').toHaveCount(1);
  await expect(chip).toHaveAttribute('data-op', 'eq');
  const field = (await chip.getAttribute('data-field')) ?? '';
  const value = (await chip.getAttribute('data-value')) ?? '';
  await expectUrl(
    page,
    (s) => s.filters.some((f) => f.field === field && f.op === 'eq' && f.value === value),
    `f.${field}=${value} in the URL`,
  );
  const server = spec.serverFilter?.(field, value, base) ?? null;
  const included =
    server === null
      ? matching(spec, field, value, base)
      : await apiRowsOfPage(page, api, spec, server);
  expect(included.length, `rows of ${field} = ${value}`).toBeGreaterThan(0);
  if (server === null) {
    await expect(page.getByTestId('stats-filter-scope'), 'filter inside the Top N').toBeVisible();
  }
  await expectKeys(table, keysOf(spec.screen, included), `include ${field} = ${value}`);
  await removeChip(page, field);
  await expectKeys(table, keysOf(spec.screen, base), 'all rows after removing the chip');

  // Exclusion: the API has no exclusion parameter, so the received rows are narrowed.
  const sameCell = tableRows(table).first().locator(`td[data-column="${column}"]`);
  await sameCell.hover();
  await sameCell.getByTestId('cell-filter-exclude').click();
  await expect(chip).toHaveCount(1);
  await expect(chip).toHaveAttribute('data-op', 'ne');
  await expectUrl(
    page,
    (s) => s.filters.some((f) => f.field === field && f.op === 'ne' && f.value === value),
    `f.${field}=!${value} in the URL`,
  );
  const kept = base.filter((r) => matching(spec, field, value, [r]).length === 0);
  await expectKeys(table, keysOf(spec.screen, kept), `exclude ${field} = ${value}`);
  await removeChip(page, field);
}

/** 05 §2.5-1 column filter: count ≥ the median count keeps exactly those rows. */
async function columnFilter<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
): Promise<void> {
  const base = await apiRowsOfPage(page, api, spec);
  const counts = base.map((r) => r.count).sort((a, b) => b - a);
  const threshold = counts[Math.floor(counts.length / 2)] ?? 0;
  const kept = base.filter((r) => r.count >= threshold);
  await table.getByTestId('data-table-filter-button-count').click();
  await page.getByTestId('data-table-filter-min').fill(String(threshold));
  await page.getByTestId('data-table-filter-apply').click();
  await expectUrl(
    page,
    (s) =>
      s.filters.some((f) => f.field === 'count' && f.op === 'gte' && f.value === String(threshold)),
    `f.count.gte=${String(threshold)} (or f.count=>=${String(threshold)}) in the URL`,
  );
  await expectKeys(table, keysOf(spec.screen, kept), `count >= ${String(threshold)}`);
  await removeChip(page, 'count');
  await expectKeys(table, keysOf(spec.screen, base), 'all rows after clearing the column filter');
}

/** 05 §2.5-1 kind and environment: BATCH asks the server for BATCH; the env is the app's. */
async function kindAndEnv<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
  seed: SeedManifest,
): Promise<void> {
  const root = LIST_ROOT[spec.screen];
  const base = await apiRowsOfPage(page, api, spec);
  await page.getByTestId('kind-filter-batch').click();
  await expectUrl(page, (s) => effectiveKind(s) === 'BATCH', 'kind BATCH in the URL');
  const batch = await apiRowsOfPage(page, api, spec);
  if (batch.length === 0) {
    await expectStatsState(page, root, 'empty');
    await expect(page.getByTestId(`${root}-empty`)).toBeVisible();
  } else {
    await expectStatsState(page, root);
    await expectRowsShown(table, spec, batch, 'kind BATCH');
  }
  await page.getByTestId('kind-filter-http').click();
  await expectUrl(page, (s) => effectiveKind(s) === 'HTTP', 'kind HTTP again');
  await expectStatsState(page, root);
  await expectKeys(table, keysOf(spec.screen, base), 'kind HTTP rows again');
  const appId = appIdOf(page.url());
  const env = seed.apps.find((a) => a.app_id === appId)?.env ?? '';
  expect(env, 'environment of the app in the manifest').not.toBe('');
  await expect(page.getByTestId('env-select')).toContainText(env);
}

/** The request is the server CSV export of the list `screen`. */
function isCsvRequest(screen: StatsScreen, appId: number) {
  return (request: { url(): string }): boolean => {
    const url = new URL(request.url());
    return (
      url.pathname === `/api/v1/apps/${String(appId)}/${LIST_API[screen]}` &&
      url.searchParams.get('format') === 'csv'
    );
  };
}

/**
 * 05 §2.5-1 CSV (DSN-15-06, docs/14 §4.17): the server's file of the screen's question (bounds,
 * kind, sort), its rows the API's rows in the API order.
 */
async function csvDownload<S extends StatsScreen>(
  page: Page,
  api: ApiSession,
  spec: ListSpec<S>,
  table: Locator,
): Promise<void> {
  const appId = appIdOf(page.url());
  const q = listQueryOf(page.url());
  const request = page.waitForRequest(isCsvRequest(spec.screen, appId));
  const download = page.waitForEvent('download');
  await table.getByTestId('csv-export').click();
  const url = new URL((await request).url());
  expect(Date.parse(url.searchParams.get('from') ?? ''), 'CSV from').toBe(Date.parse(q.from));
  expect(Date.parse(url.searchParams.get('to') ?? ''), 'CSV to').toBe(Date.parse(q.to));
  expect(url.searchParams.get('kind') ?? 'HTTP', 'CSV kind').toBe(q.kind);
  expect(
    normalizeSort(url.searchParams.get('sort')) ?? spec.defaultSort,
    'CSV sort = the screen sort',
  ).toBe(q.sort ?? spec.defaultSort);
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/\.csv$/);
  const text = readFileSync(await file.path(), 'utf8');
  expect(text.startsWith('﻿'), 'UTF-8 byte order mark').toBe(true);
  expect(text.endsWith('\r\n'), 'CRLF line ends').toBe(true);
  const records = parseCsv(text.slice(1));
  expect(records[0]?.[0], 'first CSV column').toBe(spec.csvKey);
  const csvLimit = Number(url.searchParams.get('limit') ?? String(Number.MAX_SAFE_INTEGER));
  const rows = (
    await apiStatsList(api, spec.screen, appId, {
      ...q,
      limit: Math.min(csvLimit, 500),
    })
  ).data;
  const fileKeys = records.slice(1).map((r) => r[0] ?? '');
  if (csvLimit <= 500) {
    expect(fileKeys, 'CSV rows = API rows in order').toEqual(keysOf(spec.screen, rows));
  } else {
    expect(fileKeys.length, 'CSV rows').toBeGreaterThanOrEqual(rows.length);
    expect(fileKeys.slice(0, rows.length), 'CSV rows start with the API rows').toEqual(
      keysOf(spec.screen, rows),
    );
  }
}

/** C-14: estimated-badge on the bucket-estimated percentile headers only (SQL, external). */
async function estimatedBadges<S extends StatsScreen>(
  page: Page,
  spec: ListSpec<S>,
  table: Locator,
  rows: readonly ListRowOf[S][],
): Promise<void> {
  const root = page.getByTestId(LIST_ROOT[spec.screen]);
  if (spec.estimated.length === 0) {
    await expect(root.getByTestId('estimated-badge'), 'no estimate on this list').toHaveCount(0);
    return;
  }
  expect(
    rows.every((r) => (r as { estimated?: boolean }).estimated === true),
    'API rows are bucket estimates (C-14)',
  ).toBe(true);
  for (const column of spec.estimated) {
    const header = table.locator(`thead th[data-column="${column}"]`);
    if (column !== 'p95_ms' && (await header.count()) === 0) {
      continue;
    }
    await expect(
      header.getByTestId('estimated-badge'),
      `${column} header: estimated`,
    ).toBeVisible();
  }
}

/** 05 §2.5-1 on one list: every part of the common table against the API. */
async function statsList<S extends StatsScreen>(
  ctx: StatsCheckContext,
  spec: ListSpec<S>,
): Promise<void> {
  const { page, seed, testInfo } = ctx;
  testInfo.setTimeout(300_000);
  const api = await ctx.apiAs(ORACLE_ROLE);
  await expectStatsState(page, LIST_ROOT[spec.screen]);
  const table = statsTable(page, LIST_ROOT[spec.screen]);
  const rows = await apiRowsOfPage(page, api, spec);
  expect(rows.length, `${spec.screen}: API rows of the seeded range`).toBeGreaterThan(0);
  await expectRowsShown(table, spec, rows, 'default list');
  await estimatedBadges(page, spec, table, rows);
  await sortSegments(page, api, spec, table);
  await topN(page, api, spec, table);
  await valueClick(page, api, spec, table);
  await columnFilter(page, api, spec, table);
  await kindAndEnv(page, api, spec, table, seed);
  await csvDownload(page, api, spec, table);
  testInfo.annotations.push({
    type: `stats-${spec.screen}`,
    description: `${String(rows.length)} rows; first ${rowKey(spec.screen, rows[0] as ListRowOf[S])}`,
  });
}

// ------------------------------------------------------------------------------- details

/** A breakdown row as the tables show it (id = the row key). */
interface BreakdownLike {
  readonly id: string;
}

/**
 * A breakdown table (`tableId`) shows exactly the API rows (any order: the screen may sort them),
 * each with the raw values of `fields`.
 */
export async function expectBreakdown(
  page: Page,
  tableId: string,
  rows: readonly BreakdownLike[],
  fields: readonly string[],
): Promise<void> {
  const table = statsTable(page, tableId);
  if (rows.length === 0) {
    await expect(tableRows(table), `${tableId}: no rows`).toHaveCount(0);
    return;
  }
  await expectKeySet(
    table,
    rows.map((r) => r.id),
    `${tableId}: rows = API`,
  );
  await expect
    .poll(
      async () => {
        const shown = new Map((await shownRows(table)).map((r) => [leadingId(r.key), r]));
        const out: string[] = [];
        for (const row of rows) {
          for (const field of fields) {
            const raw = shown.get(row.id)?.values[field];
            if (raw === undefined) {
              out.push(`${row.id}: ${field} not shown`);
              continue;
            }
            const want = (row as unknown as Record<string, unknown>)[field];
            const value = parseValueAttr(raw);
            if (value !== (typeof want === 'number' ? want : null)) {
              out.push(`${row.id}: ${field} shown ${String(value)}, API ${String(want)}`);
            }
          }
        }
        return out;
      },
      { message: `${tableId}: values = API`, timeout: 20_000 },
    )
    .toEqual([]);
}

/** The XLog link of a detail: the app's XLog with the range and the filter `field` = `value`. */
async function expectXlogLink(
  page: Page,
  root: string,
  q: { readonly from: string; readonly to: string },
  filter: { readonly field: string; readonly value: string } | null,
): Promise<void> {
  const link = page.getByTestId(root).getByTestId('stats-open-xlog');
  await expect(link).toBeVisible();
  const url = await hrefOf(link);
  expect(url.pathname, 'XLog link path').toBe(`/apps/${String(appIdOf(page.url()))}/xlog`);
  expect(Date.parse(url.searchParams.get('from') ?? ''), 'XLog link from').toBe(Date.parse(q.from));
  expect(Date.parse(url.searchParams.get('to') ?? ''), 'XLog link to').toBe(Date.parse(q.to));
  if (filter !== null) {
    expect(filters(url.searchParams), 'XLog link filter').toContainEqual({
      field: filter.field,
      op: 'eq',
      value: filter.value,
    });
  }
}

/** Opens the detail of the list row `key` and checks the path and the kept range. */
async function openDetailFromList(
  page: Page,
  screen: StatsScreen,
  key: string,
): Promise<ListQuery> {
  const before = listQueryOf(page.url());
  const appId = appIdOf(page.url());
  await openRow(statsTable(page, LIST_ROOT[screen]), key);
  await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/stats/${screen}/${key}`,
    `the ${screen} detail of ${key}`,
  );
  const after = listQueryOf(page.url());
  expect(after.from, 'detail keeps the range (from)').toBe(before.from);
  expect(after.to, 'detail keeps the range (to)').toBe(before.to);
  expect(after.kind, 'detail keeps the kind').toBe(before.kind);
  await expectStatsState(page, DETAIL_ROOT[screen]);
  return after;
}

/**
 * 05 §2.5-2 / T-ACPT-04: the trend with the deploy markers of GET /events, the version table (p95
 * per version), the instance distribution, the cross view tabs and the XLog link with the service
 * filter; no estimate badge (DD sketches are not bucket estimates).
 */
export async function expectServiceDetail(
  page: Page,
  api: ApiSession,
  detail: ServiceDetail,
  q: ListQuery,
): Promise<void> {
  const appId = appIdOf(page.url());
  const root = page.getByTestId(DETAIL_ROOT.services);
  const trend = root.getByTestId('stats-trend');
  await expect(trend).toBeVisible();
  await expect(trend.locator('canvas').first()).toBeVisible();
  const events = await apiEvents(api, appId, q.from, q.to);
  await expect
    .poll(() => markerTotal(trend), { message: 'trend markers = GET /events of the range' })
    .toBe(events.length);
  await expectBreakdown(page, 'stats-by-version', detail.by_version, ['count', 'p95_ms']);
  await expectBreakdown(page, 'stats-by-instance', detail.by_instance, ['count']);
  if (detail.raw_window !== null) {
    await expect(root.getByTestId('stats-raw-window')).toBeVisible();
  }
  const cross = [
    ['sql', 'stats-top-sql', detail.top_sql, 'sql'],
    ['external', 'stats-top-external', detail.top_external, 'external'],
    ['errors', 'stats-top-errors', detail.top_error_groups, 'errors'],
  ] as const;
  for (const [tab, tableId, rows, segment] of cross) {
    await root.getByTestId(`stats-cross-tab-${tab}`).click();
    await expectBreakdown(page, tableId, rows, ['count']);
    const first = rows[0];
    if (first !== undefined) {
      const link = tableRow(statsTable(page, tableId), first.id)
        .first()
        .locator(`a[href*="/stats/${segment}/${first.id}"]`);
      await expect(link, `${tab}: the row opens its ${segment} detail`).toHaveCount(1);
    }
  }
  await expect(root.getByTestId('estimated-badge'), 'services: no estimate badge').toHaveCount(0);
  await expectXlogLink(page, DETAIL_ROOT.services, q, {
    field: 'service',
    value: detail.service_id,
  });
}

/** 2.5-2: service detail from the list (the service of the top 5 with the most versions). */
const statsServiceDetail: StatsCheck = async (ctx) => {
  const { page, testInfo } = ctx;
  testInfo.setTimeout(180_000);
  const api = await ctx.apiAs(ORACLE_ROLE);
  await expectStatsState(page, LIST_ROOT.services);
  const appId = appIdOf(page.url());
  const q = listQueryOf(page.url());
  const list = await apiStatsList(api, 'services', appId, q);
  const chosen = await serviceWithMostVersions(api, appId, list.data, q);
  await expectKeys(
    statsTable(page, LIST_ROOT.services),
    keysOf('services', list.data),
    'services before opening one',
  );
  const after = await openDetailFromList(page, 'services', chosen.row.service_id);
  await expectServiceDetail(page, api, chosen.detail, after);
  testInfo.annotations.push({
    type: 'service',
    description: `${chosen.row.service_id}: ${String(chosen.detail.by_version.length)} versions, ${String(chosen.detail.by_instance.length)} instances`,
  });
};

/** Of the first five services, the one whose detail has the most versions (T-ACPT-04). */
export async function serviceWithMostVersions(
  api: ApiSession,
  appId: number,
  rows: readonly ServiceStats[],
  q: ListQuery,
): Promise<{ row: ServiceStats; detail: ServiceDetail }> {
  let best: { row: ServiceStats; detail: ServiceDetail } | null = null;
  for (const row of rows.slice(0, 5)) {
    const detail = await apiStatsDetail(api, 'services', appId, row.service_id, q);
    if (best === null || detail.by_version.length > best.detail.by_version.length) {
      best = { row, detail };
    }
  }
  if (best === null) {
    throw new Error('no service in the seeded range');
  }
  return best;
}

/** The text of a CodeBlock is text nodes in spans only, equal to `text` (whitespace aside). */
export async function expectCodeText(block: Locator, text: string, label: string): Promise<void> {
  await expect(block, label).toBeVisible();
  const fold = block.getByTestId('code-block-fold');
  if ((await fold.count()) > 0 && (await fold.getAttribute('aria-expanded')) !== 'true') {
    await fold.click();
  }
  await expect
    .poll(async () => squash((await block.locator('code').textContent()) ?? ''), {
      message: `${label} = API text`,
    })
    .toBe(squash(text));
  const tags = await block
    .locator('code *')
    .evaluateAll((nodes) => [...new Set(nodes.map((n) => n.tagName))]);
  expect(
    tags.every((tag) => tag === 'SPAN'),
    `${label}: only text spans, got ${tags.join(',')}`,
  ).toBe(true);
}

/**
 * 05 §2.5-3: trend (estimated), calling services (count, share), instances, per DB, connection
 * wait "not measured" (C-178 (2)), normalized and original text as text nodes, related
 * transactions with the profiled-only notice, XLog with the SQL filter.
 */
const statsSqlDetail: StatsCheck = async (ctx) => {
  const { page, testInfo } = ctx;
  testInfo.setTimeout(180_000);
  const api = await ctx.apiAs(ORACLE_ROLE);
  await expectStatsState(page, LIST_ROOT.sql);
  const appId = appIdOf(page.url());
  const q = listQueryOf(page.url());
  const list = await apiStatsList(api, 'sql', appId, q);
  const first = list.data[0];
  expect(first, 'a SQL statement in the seeded range').toBeDefined();
  const sqlId = (first as SqlStats).sql_id;
  await expectKeys(statsTable(page, LIST_ROOT.sql), keysOf('sql', list.data), 'SQL list');
  const after = await openDetailFromList(page, 'sql', sqlId);
  await expectSqlDetail(page, api, sqlId, after);
};

/** The SQL detail of `sqlId` shows the API's sql/{sqlId} of the range `q`. */
export async function expectSqlDetail(
  page: Page,
  api: ApiSession,
  sqlId: string,
  q: ListQuery,
): Promise<void> {
  const appId = appIdOf(page.url());
  const detail = await apiStatsDetail(api, 'sql', appId, sqlId, q);
  const root = page.getByTestId(DETAIL_ROOT.sql);
  // The code element only: the block's toolbar (language label, copy button) is not the text.
  await expectCodeText(
    root.getByTestId('stats-sql-normalized'),
    detail.sql_normalized,
    'normalized SQL',
  );
  await expectCodeText(root.getByTestId('stats-sql-text'), detail.sql_text, 'original SQL');
  expect(detail.wait_ms_total, 'connection wait is not measured (C-178 (2))').toBeNull();
  const wait = root.getByTestId('stats-sql-wait');
  // The value cell marks the null (data-value '' and data-missing); its ValueText names the reason.
  await expect(wait).toHaveAttribute('data-value', '');
  await expect(wait).toHaveAttribute('data-missing', 'true');
  await expect(wait.locator('[data-missing="notMeasurable"]')).toHaveCount(1);
  await expect(wait).toHaveText(NOT_MEASURABLE());
  expect(detail.estimated, 'SQL percentiles are bucket estimates').toBe(true);
  await expect(root.getByTestId('estimated-badge').first(), 'estimated (C-14)').toBeVisible();
  await expect(root.getByTestId('stats-trend')).toBeVisible();
  await expectBreakdown(page, 'stats-by-service', detail.by_service, ['count', 'share']);
  await expectBreakdown(page, 'stats-by-instance', detail.by_instance, ['count']);
  await expectBreakdown(
    page,
    'stats-by-db',
    detail.by_db.map((r) => ({ ...r, id: r.db.db_id })),
    ['count'],
  );
  await expect(root.getByTestId('stats-related-note')).toBeVisible();
  await expectBreakdown(
    page,
    'stats-related-tx',
    detail.related_tx.items.map((r) => ({ ...r, id: r.tx_id })),
    [],
  );
  await expectXlogLink(page, DETAIL_ROOT.sql, q, { field: 'sql', value: sqlId });
}

/**
 * 05 §2.5-4: per host count, p50/p95 (estimated), errors, timeouts and connection failures = API
 * (the generator seed has timeouts), trend, calling services, related transactions.
 */
const statsExternalDetail: StatsCheck = async (ctx) => {
  const { page, testInfo } = ctx;
  testInfo.setTimeout(180_000);
  const api = await ctx.apiAs(ORACLE_ROLE);
  await expectStatsState(page, LIST_ROOT.external);
  const appId = appIdOf(page.url());
  const q = listQueryOf(page.url());
  const list = (await apiStatsList(api, 'external', appId, { ...q, compare_prev: true })).data;
  const timeouts = list.reduce((sum, r) => sum + r.timeouts, 0);
  expect(timeouts, 'timeouts in the generator seed (C-207 (5))').toBeGreaterThan(0);
  const table = statsTable(page, LIST_ROOT.external);
  await expectRowsShown(table, EXTERNAL, list, 'external calls per host');
  const host = [...list].sort((a, b) => b.timeouts - a.timeouts)[0] as ExternalStats;
  const after = await openDetailFromList(page, 'external', host.host_id);
  const detail = await apiStatsDetail(api, 'external', appId, host.host_id, after);
  const root = page.getByTestId(DETAIL_ROOT.external);
  expect(detail.estimated, 'host percentiles are bucket estimates').toBe(true);
  await expect(root.getByTestId('estimated-badge').first(), 'estimated (C-14)').toBeVisible();
  await expect(root.getByTestId('stats-trend')).toBeVisible();
  await expectBreakdown(page, 'stats-by-service', detail.by_service, ['count']);
  await expect(root.getByTestId('stats-related-note')).toBeVisible();
  await expectBreakdown(
    page,
    'stats-related-tx',
    detail.related_tx.items.map((r) => ({ ...r, id: r.tx_id })),
    [],
  );
  await expectXlogLink(page, DETAIL_ROOT.external, after, null);
  testInfo.annotations.push({
    type: 'external',
    description: `${String(list.length)} hosts, ${String(timeouts)} timeouts, ${String(list.reduce((s, r) => s + r.connect_failures, 0))} connection failures; opened ${host.host}`,
  });
};

/**
 * 05 §2.5-5: the new filter = new_only, then a group's representative stack, masked message
 * pattern, version / instance / service distributions and its last 10 transactions.
 */
const statsErrorDetail: StatsCheck = async (ctx) => {
  const { page, testInfo } = ctx;
  testInfo.setTimeout(180_000);
  const api = await ctx.apiAs(ORACLE_ROLE);
  await expectStatsState(page, LIST_ROOT.errors);
  const table = statsTable(page, LIST_ROOT.errors);
  const all = await apiRowsOfPage(page, api, ERRORS);
  expect(all.length, 'error groups in the seeded range').toBeGreaterThan(0);
  await expectKeys(table, keysOf('errors', all), 'error groups');
  await expectNewFilter(page, api, table);
  await page.getByTestId('stats-errors-new').click();
  await expectUrl(page, (s) => !['1', 'true'].includes(s.params.get('new') ?? ''), 'new off');
  await expectKeys(table, keysOf('errors', all), 'all groups again');
  const group = all[0] as ErrorGroup;
  const after = await openDetailFromList(page, 'errors', group.group_id);
  await expectErrorDetail(page, api, group.group_id, after);
};

/** The "new" filter of the error list: new=1 in the URL, rows = new_only=true, all is_new. */
export async function expectNewFilter(page: Page, api: ApiSession, table: Locator): Promise<void> {
  await page.getByTestId('stats-errors-new').click();
  await expectUrl(
    page,
    (s) => ['1', 'true'].includes(s.params.get('new') ?? ''),
    'new=1 in the URL',
  );
  const fresh = await apiRowsOfPage(page, api, ERRORS, { new_only: true });
  expect(fresh.length, 'new groups in the seeded range').toBeGreaterThan(0);
  expect(
    fresh.every((g) => g.is_new),
    'new_only answers new groups only',
  ).toBe(true);
  await expectRowsShown(table, ERRORS, fresh, 'new groups');
}

/** The error group detail of `groupId` shows the API's errors/groups/{groupId} of `q`. */
export async function expectErrorDetail(
  page: Page,
  api: ApiSession,
  groupId: string,
  q: ListQuery,
): Promise<void> {
  const appId = appIdOf(page.url());
  const detail = await apiStatsDetail(api, 'errors', appId, groupId, q);
  const root = page.getByTestId(DETAIL_ROOT.errors);
  const message = root.getByTestId('stats-error-message');
  await expect(message).toContainText(detail.group.message_pattern ?? '—');
  const stack = root.getByTestId('stats-error-stack');
  await expect(stack).toBeVisible();
  const frames = stack.getByTestId('transaction-frame');
  await expect(frames).toHaveCount(detail.sample_stack?.length ?? 0);
  if (detail.sample_stack !== null && detail.sample_stack.length > 0) {
    expect((await frames.allTextContents()).map(squash), 'stack frames = sample_stack').toEqual(
      detail.sample_stack.map(squash),
    );
  }
  // No application packages are configured yet (C-207 (3), WO-26): no frame is emphasised.
  await expect(stack.locator('[data-app="true"]')).toHaveCount(0);
  await expectBreakdown(page, 'stats-by-version', detail.by_version, ['count']);
  await expectBreakdown(page, 'stats-by-instance', detail.by_instance, ['count']);
  await expectBreakdown(page, 'stats-by-service', detail.by_service, ['count']);
  expect(detail.recent_tx.length, 'at most 10 recent transactions').toBeLessThanOrEqual(10);
  await expectBreakdown(
    page,
    'stats-recent-tx',
    detail.recent_tx.map((r) => ({ ...r, id: r.tx_id })),
    [],
  );
  for (const tx of detail.recent_tx) {
    const link = tableRow(statsTable(page, 'stats-recent-tx'), tx.tx_id)
      .first()
      .locator(`a[href*="/transactions/${tx.tx_id}"]`);
    await expect(link, `recent ${tx.tx_id}: detail link`).toHaveCount(1);
    expect(
      (await hrefOf(link)).searchParams.get('ts'),
      `recent ${tx.tx_id}: ts hint`,
    ).not.toBeNull();
  }
  await expectXlogLink(page, DETAIL_ROOT.errors, q, { field: 'error_group', value: groupId });
}

export const STATS_CHECKS: Readonly<Record<string, StatsCheck>> = {
  statsServicesTable: (ctx) => statsList(ctx, SERVICES),
  statsSqlTable: (ctx) => statsList(ctx, SQL),
  statsExternalTable: (ctx) => statsList(ctx, EXTERNAL),
  statsErrorsTable: (ctx) => statsList(ctx, ERRORS),
  statsServiceDetail,
  statsSqlDetail,
  statsExternalDetail,
  statsErrorDetail,
};
