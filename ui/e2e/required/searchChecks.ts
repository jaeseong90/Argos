// SPDX-License-Identifier: Apache-2.0
//
// Named checks of ui/e2e/required/search.yaml (docs/15 §9.12, F-U14, WO-39) and the helpers
// specs/search.spec.ts shares: the search answer of the API for the screen's question (GET
// /search, asked as another user than the browser's, so that the screen keeps its HEAVY budget of
// 10 searches a minute, docs/16 §7.13), the result table reader and the transaction id check.
import { expect, type Page, type TestInfo } from '@playwright/test';

import type { components } from '../../src/api/generated/schema';
import { expectShell, expectUrl } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { sleep } from '../stack/wait';

import { adminTable, rowKeys, rowsOf } from './adminChecks';
import { capture } from './alertsChecks';

export type ApiSearchResult = components['schemas']['SearchResult'];
export type ApiSearchMatch = components['schemas']['SearchMatch'];

/** The check context of checks.ts (structurally; this file does not import it). */
export interface SearchCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}

type SearchCheck = (ctx: SearchCheckContext) => Promise<void>;

/** The user of the API's answers: not the browser's (viewer), so the screen keeps its budget. */
export const SEARCH_ORACLE_ROLE: Role = 'admin';

/** The row key of a match, as SearchPage keys its table rows. */
export function matchKey(match: ApiSearchMatch): string {
  return `${match.type}:${match.target.route}:${JSON.stringify(match.target.params)}`;
}

/** GET /search with `query`; a 429 waits for its Retry-After once (the oracle, never the screen). */
export async function apiSearchQuery(
  api: ApiSession,
  query: Readonly<Record<string, string>>,
): Promise<ApiSearchResult> {
  let response = await api.request<{ data: ApiSearchResult }>('GET', '/search', {
    query,
    expect: [200, 429],
  });
  if (response.status === 429) {
    const seconds = Number(response.headers.get('retry-after') ?? '60');
    await sleep(Math.min(61, Math.max(1, seconds)) * 1000);
    response = await api.request<{ data: ApiSearchResult }>('GET', '/search', { query });
  }
  if (response.body === null) {
    throw new Error('GET /search: empty body');
  }
  return response.body.data;
}

/**
 * Opens `/search?<query>` and captures the page's GET /search; resolves with its parameters and
 * answer once the screen settled.
 */
export async function openSearch(
  page: Page,
  path: string,
): Promise<{ params: URLSearchParams; result: ApiSearchResult }> {
  const { params, body } = await capture<{ data: ApiSearchResult }>(page, 'GET', '/search', () =>
    page.goto(path),
  );
  return { params, result: body.data };
}

/** The result table equals the matches (keys in API order). */
export async function expectResultRows(
  page: Page,
  matches: readonly ApiSearchMatch[],
): Promise<void> {
  const table = adminTable(page, 'search-results');
  await expect(rowsOf(table).first()).toBeVisible({ timeout: 30_000 });
  expect(await rowKeys(table)).toEqual(matches.map(matchKey));
  const labels = await table.getByTestId('search-label').allTextContents();
  expect(labels.map((l) => l.trim())).toEqual(matches.map((m) => String(m.label)));
}

/** 9.12-1: the kind shown while typing (nothing is sent before Enter). */
const searchKindIndicator: SearchCheck = async ({ page, seed }) => {
  await expectShell(page);
  const input = page.getByTestId('search-input');
  const kind = page.getByTestId('search-kind');
  const cases: [string, string][] = [
    [seed.tx.captured_sql_stack.tx_id, 'tx'],
    // 21 digits are more than an unsigned 64-bit id: text.
    ['123456789012345678901', 'text'],
    [seed.tx.trace_root_two_apps.trace_id, 'trace'],
    ['user:wo39-someone', 'user'],
    ['req:wo39-request', 'request'],
    ['checkout', 'text'],
  ];
  for (const [text, expected] of cases) {
    await input.fill(text);
    await expect(kind, text).toHaveAttribute('data-kind', expected);
  }
  await expect(page.getByTestId('search')).toHaveAttribute('data-kind', 'empty');
};

/** 9.12-2: the one match of a transaction id opens the transaction detail (replaced history). */
const searchTxOpens: SearchCheck = async ({ page, seed }) => {
  const tx = seed.tx.captured_sql_stack;
  await expectUrl(
    page,
    (s) => s.path === `/apps/${String(tx.app_id)}/transactions/${tx.tx_id}`,
    'the transaction detail of the one match',
  );
  await expect(page.getByTestId('search')).toHaveCount(0);
};

/** 9.12-3: a text search (a service of the seed) lists the matches of GET /search. */
const searchTextResults: SearchCheck = async ({ page, seed, apiAs }) => {
  const service = seed.tx.captured_sql_stack.service;
  expect(service, 'the seed transaction names its service').not.toBeNull();
  const q = String(service);
  const { params, result } = await openSearch(page, `/search?q=${encodeURIComponent(q)}`);
  expect(params.get('q')).toBe(q);
  expect(params.get('type')).toBe('auto');
  const api = await apiAs(SEARCH_ORACLE_ROLE);
  const replay = await apiSearchQuery(api, Object.fromEntries(params.entries()));
  expect(replay.matches.map(matchKey)).toEqual(result.matches.map(matchKey));
  expect(result.matches.length, `matches of ${q}`).toBeGreaterThan(0);
  expect(result.matches.some((m) => m.type === 'service')).toBe(true);
  const root = page.getByTestId('search');
  if (result.matches.length === 1) {
    // One text match is listed too (only id lookups open at once).
    await expect(root).toHaveAttribute('data-state', 'ready');
  }
  await expectResultRows(page, result.matches);
};

/** 9.12-4: no request is sent for a request id search over 7 days; the screen says why. */
const searchNoRequestWithoutRange: SearchCheck = async ({ page }) => {
  const sent: string[] = [];
  page.on('request', (r) => {
    if (new URL(r.url()).pathname === '/api/v1/search') {
      sent.push(r.url());
    }
  });
  await page.reload();
  await expect(page.getByTestId('search-range-required')).toBeVisible();
  await expect(page.getByTestId('search')).toHaveAttribute('data-kind', 'request');
  expect(sent).toEqual([]);
};

export const SEARCH_CHECKS: Readonly<Record<string, SearchCheck>> = {
  searchKindIndicator,
  searchTxOpens,
  searchTextResults,
  searchNoRequestWithoutRange,
};
