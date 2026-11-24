// SPDX-License-Identifier: Apache-2.0
//
// Named checks of required/overview.yaml (docs/05 §2.10, docs/15 §9.1, WO-31) and the oracles the
// overview spec (e2e/specs/overview.spec.ts, T-E2E-090..099) shares with them. Every check opens
// the overview on the seeded stack itself and compares what the page shows with the answer of the
// real server to the same question: the GET /api/v1/overview responses the page received are
// recorded (recordOverview) and the DOM must equal the latest of them, cell by cell (data-value
// carries the raw API value, "null" when missing). Where the seed cannot show something (30
// applications, mixed instance states and firing counts, app groups), the real response is fetched
// and changed in the browser with schema-typed rows (page.route): the server, the API envelope and
// the page stay real, only the rows are padded or replaced, and the test says so.
import { expect, type Page, type Route, type TestInfo } from '@playwright/test';

import type { Overview, OverviewApp } from '../../src/api/overview';
import { untrusted } from '../../src/api/untrusted';
import { MISSING_DASH } from '../../src/utils/missing';
import { t } from '../fixtures/i18n';
import { expectShell, expectUrl, sameTime, state } from '../fixtures/ui';
import { effectiveKind } from '../fixtures/urlModel';
import type { WsStub } from '../fixtures/ws';
import type { ApiSession } from '../stack/api';
import { manifestAlert, type Role, type SeedManifest } from '../stack/manifest';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface OverviewCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly ws?: WsStub;
}

type OverviewCheck = (ctx: OverviewCheckContext) => Promise<void>;

/** Path of the overview API (docs/14 §4.2). */
export const OVERVIEW_API = '/api/v1/overview';
/** Path of the all-application firing alerts (docs/14 §4.2). */
export const OVERVIEW_ALERTS_API = '/api/v1/overview/alerts';
/** The role the checks ask the API as (the page's own role: viewer). */
export const OVERVIEW_ORACLE_ROLE = 'viewer' as const;
/** Rows of docs/05 §2.10-6: 30 applications at 1366x768 without scrolling. */
export const FIT_ROWS = 30;
/** First app id of the synthetic rows (int32, far above any id the stack assigns). */
export const PAD_ID_BASE = 1_900_000_000;
/** The group the group filter check offers (the seed apps have no app_group, see overviewFilters). */
export const E2E_GROUP = 'e2e-overview-group';

/** The overview envelope as the API sends it. */
export interface OverviewBody {
  data: Overview;
  meta: { generated_at: string; truncated?: boolean } & Record<string, unknown>;
}

/** One GET /overview response the page received. */
export interface OverviewAnswer {
  /** Epoch ms when the response arrived. */
  readonly at: number;
  readonly query: URLSearchParams;
  readonly body: OverviewBody;
}

export interface OverviewRecorder {
  /** Every response so far, in arrival order. */
  answers(): readonly OverviewAnswer[];
  /** The newest response, or a failure when there is none. */
  last(): OverviewAnswer;
  /** Every GET /overview request the page sent (also those not answered yet), in order. */
  requests(): readonly { readonly at: number; readonly query: URLSearchParams }[];
  /** Requests to GET /overview/alerts. */
  alertRequests(): number;
  /** Resolves with the first response that matches. */
  waitFor(
    match: (answer: OverviewAnswer) => boolean,
    what: string,
    timeoutMs?: number,
  ): Promise<OverviewAnswer>;
}

function pathOf(url: string): string {
  return new URL(url).pathname;
}

/**
 * Records the overview requests and responses of `page` from now on (also responses fulfilled by a
 * page.route of the test: what the page received). Install before the navigation.
 */
export function recordOverview(page: Page): OverviewRecorder {
  const answers: OverviewAnswer[] = [];
  const requests: { at: number; query: URLSearchParams }[] = [];
  let alerts = 0;
  page.on('request', (request) => {
    if (request.method() !== 'GET') {
      return;
    }
    const path = pathOf(request.url());
    if (path === OVERVIEW_API) {
      requests.push({ at: Date.now(), query: new URL(request.url()).searchParams });
    } else if (path === OVERVIEW_ALERTS_API) {
      alerts += 1;
    }
  });
  page.on('response', (response) => {
    if (pathOf(response.url()) !== OVERVIEW_API || !response.ok()) {
      return;
    }
    const at = Date.now();
    const query = new URL(response.url()).searchParams;
    void response
      .json()
      .then((body: OverviewBody) => {
        answers.push({ at, query, body });
        answers.sort((a, b) => a.at - b.at);
      })
      .catch(() => undefined);
  });
  return {
    answers: () => [...answers],
    last: () => {
      const answer = answers.at(-1);
      if (answer === undefined) {
        throw new Error('the page received no GET /api/v1/overview answer');
      }
      return answer;
    },
    requests: () => [...requests],
    alertRequests: () => alerts,
    async waitFor(match, what, timeoutMs = 15_000) {
      await expect
        .poll(() => answers.some(match), {
          message: `overview answer: ${what}`,
          timeout: timeoutMs,
        })
        .toBe(true);
      const found = answers.find(match);
      if (found === undefined) {
        throw new Error(`no overview answer: ${what}`);
      }
      return found;
    },
  };
}

/** The overview root, after it reached `expected` (default ready). */
export async function expectOverviewState(
  page: Page,
  expected: 'loading' | 'error' | 'empty' | 'ready' = 'ready',
) {
  const root = page.getByTestId('overview-page');
  await expect(root).toHaveAttribute('data-state', expected, { timeout: 30_000 });
  return root;
}

/** Opens `path` and waits for the shell and the ready (or `expected`) overview. */
export async function openOverview(
  page: Page,
  path: string,
  expected: 'loading' | 'error' | 'empty' | 'ready' = 'ready',
) {
  await page.goto(path);
  if (!path.includes('kiosk=1')) {
    await expectShell(page);
  }
  return expectOverviewState(page, expected);
}

/** GET /overview through the API with the same question. */
export async function apiOverview(
  api: ApiSession,
  query: { env?: string; group?: string; kind?: string } = {},
): Promise<Overview> {
  return api.data<Overview>('/overview', query);
}

/** URL matcher of GET /overview (one reference, so page.unroute finds the route again). */
export const isOverviewApi = (url: URL): boolean => url.pathname === OVERVIEW_API;
/** URL matcher of GET /apps (the application list the group select reads). */
export const isAppsApi = (url: URL): boolean => url.pathname === '/api/v1/apps';
/** URL matcher of GET /overview/alerts. */
export const isAlertsApi = (url: URL): boolean => url.pathname === OVERVIEW_ALERTS_API;

/**
 * Routes GET /overview of `page` to the real server and lets `change` rewrite the answer (the
 * status, headers and envelope stay the server's). Undo with page.unroute(isOverviewApi).
 */
export async function rewriteOverview(
  page: Page,
  change: (body: OverviewBody) => OverviewBody,
): Promise<void> {
  await page.route(isOverviewApi, async (route: Route) => {
    try {
      const response = await route.fetch();
      const body = (await response.json()) as OverviewBody;
      await route.fulfill({ response, json: change(body) });
    } catch {
      // The page closed while the request was in flight (end of the test): nothing to answer.
    }
  });
}

// ---------------------------------------------------------------------------------------------
// Row oracle (05 §2.10-2): the DOM of a row against the API row
// ---------------------------------------------------------------------------------------------

/** The value cells of a row (testids overview-cell-<field>-<appId>). */
export const VALUE_FIELDS = ['tps', 'p95', 'error-rate', 'active', 'batches'] as const;
export type ValueField = (typeof VALUE_FIELDS)[number];
export const SPARKS = ['tps', 'p95', 'err'] as const;

/** A row or card as the page shows it (attributes and texts read in one pass). */
export interface ShownRow {
  readonly appId: number;
  readonly pinned: string | null;
  readonly rowStatus: string | null;
  readonly name: string | null;
  readonly env: string | null;
  readonly status: string | null;
  readonly up: string | null;
  readonly total: string | null;
  readonly values: Readonly<Record<ValueField, { value: string | null; text: string }>>;
  readonly firing: { state: string | null; value: string | null; text: string };
  readonly deploy: { value: string | null; text: string; version: string | null };
  readonly sparks: Readonly<Record<string, { points: string | null; last: string | null }>>;
}

/** Every row (table view) or card (card view) in display order. */
export async function shownRows(page: Page): Promise<ShownRow[]> {
  return page.evaluate(
    ({ fields, sparks }) => {
      const byTestId = (id: string): Element | null =>
        document.querySelector(`[data-testid="${id}"]`);
      const items = [
        ...document.querySelectorAll(
          '[data-testid^="overview-row-"], [data-testid^="overview-card-"]',
        ),
      ];
      return items.map((el) => {
        const id = el.getAttribute('data-app-id') ?? '';
        const cell = (field: string) => byTestId(`overview-cell-${field}-${id}`);
        const text = (node: Element | null) => node?.textContent ?? '';
        const values: Record<string, { value: string | null; text: string }> = {};
        for (const field of fields) {
          const node = cell(field);
          values[field] = { value: node?.getAttribute('data-value') ?? null, text: text(node) };
        }
        const instances = cell('instances');
        const firing = cell('firing');
        const deploy = cell('deploy');
        const sparkValues: Record<string, { points: string | null; last: string | null }> = {};
        for (const spark of sparks) {
          const node = byTestId(`overview-spark-${spark}-${id}`);
          sparkValues[spark] = {
            points: node?.getAttribute('data-points') ?? null,
            last: node?.getAttribute('data-last') ?? null,
          };
        }
        const name = el.querySelector('.overview-name__text, .overview-card__name');
        const env = el.querySelector('.overview-name__env, .overview-card__env > :first-child');
        return {
          appId: Number(id),
          pinned: el.getAttribute('data-pinned'),
          rowStatus: el.getAttribute('data-status'),
          name: name === null ? null : text(name),
          env: env === null ? null : text(env),
          status: cell('status')?.getAttribute('data-value') ?? null,
          up: instances?.getAttribute('data-up') ?? null,
          total: instances?.getAttribute('data-total') ?? null,
          values: values as ShownRow['values'],
          firing: {
            state: firing?.getAttribute('data-state') ?? null,
            value: firing?.getAttribute('data-value') ?? null,
            text: text(firing),
          },
          deploy: {
            value: deploy?.getAttribute('data-value') ?? null,
            text: text(deploy),
            version: text(byTestId(`overview-deploy-version-${id}`)) || null,
          },
          sparks: sparkValues,
        };
      });
    },
    { fields: [...VALUE_FIELDS], sparks: [...SPARKS] },
  );
}

/** `data-value` of a raw API number: the number, "null" when missing. */
export function raw(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value) ? String(value) : 'null';
}

/** The four states of docs/14 §4.2 (anything else the UI shows as UNKNOWN). */
export function normalStatus(status: string | null | undefined): string {
  return status === 'UP' || status === 'WARN' || status === 'STALE' || status === 'DOWN'
    ? status
    : 'UNKNOWN';
}

/** Firing counts of an API row (docs/14 §4.2 `{critical, warn}`), null when not available. */
export function firingOf(firing: unknown): { critical: number; warn: number } | null {
  if (typeof firing !== 'object' || firing === null) {
    return null;
  }
  const { critical, warn } = firing as { critical?: unknown; warn?: unknown };
  const count = (v: unknown) =>
    v === undefined ? 0 : typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : null;
  const c = count(critical);
  const w = count(warn);
  return c === null || w === null ? null : { critical: c, warn: w };
}

function apiValue(app: OverviewApp, field: ValueField): number | null {
  switch (field) {
    case 'tps':
      return app.tps;
    case 'p95':
      return app.p95_ms;
    case 'error-rate':
      return app.error_rate;
    case 'active':
      return app.active;
    case 'batches':
      return app.batch_running;
  }
}

function sparkOf(values: readonly (number | null)[]): { points: string; last: string } {
  const present = values.filter((v): v is number => typeof v === 'number' && Number.isFinite(v));
  return { points: String(present.length), last: raw(present.at(-1)) };
}

/**
 * Differences between a shown row and the API row (empty = equal): the name and environment as
 * text, the status (worst instance), instances up/total, the five values, firing by severity, the
 * last deploy and the three sparklines. A missing value must show the missing marker (dash), a
 * value (0 included) must not (T-UI-007, D-23).
 */
export function rowMismatches(shown: ShownRow, app: OverviewApp): string[] {
  const out: string[] = [];
  const id = String(app.app_id);
  const same = (what: string, actual: unknown, expected: unknown): void => {
    if (actual !== expected) {
      out.push(`${id} ${what}: shown ${JSON.stringify(actual)}, API ${JSON.stringify(expected)}`);
    }
  };
  same('name', shown.name, app.name);
  same('env', shown.env, app.env);
  same('status', shown.status, app.status);
  same('row status', shown.rowStatus, normalStatus(app.status));
  same('instances up', shown.up, String(app.instances.up));
  same('instances total', shown.total, String(app.instances.total));
  for (const field of VALUE_FIELDS) {
    const expected = raw(apiValue(app, field));
    const cell = shown.values[field];
    same(field, cell.value, expected);
    if (expected === 'null') {
      same(`${field} missing marker`, cell.text.trim(), MISSING_DASH);
    } else if (cell.text.includes(MISSING_DASH) || cell.text.trim() === '') {
      out.push(`${id} ${field}: the value ${expected} is shown as missing (${cell.text})`);
    }
  }
  const firing = firingOf(app.firing);
  if (firing === null) {
    same('firing state', shown.firing.state, 'unavailable');
    same('firing value', shown.firing.value, 'null');
    if (!shown.firing.text.includes(t('overview:firing.notAvailableShort'))) {
      out.push(`${id} firing: "${shown.firing.text}" does not say not available`);
    }
  } else {
    const total = firing.critical + firing.warn;
    same('firing state', shown.firing.state, total > 0 ? 'firing' : 'none');
    same('firing value', shown.firing.value, String(total));
  }
  if (app.last_deploy === null) {
    same('deploy', shown.deploy.value, 'null');
    same('deploy missing marker', shown.deploy.text.trim(), MISSING_DASH);
  } else {
    same('deploy', shown.deploy.value, app.last_deploy.ts);
    same('deploy version', shown.deploy.version, app.last_deploy.version);
  }
  for (const spark of SPARKS) {
    const expected = sparkOf(app.sparkline[spark]);
    same(`spark ${spark} points`, shown.sparks[spark]?.points ?? null, expected.points);
    same(`spark ${spark} last`, shown.sparks[spark]?.last ?? null, expected.last);
  }
  return out;
}

/** Differences between the shown rows and an API answer: the same applications, each equal. */
export function answerMismatches(
  rows: readonly ShownRow[],
  apps: readonly OverviewApp[],
): string[] {
  const out: string[] = [];
  const shownIds = rows.map((r) => r.appId).sort((a, b) => a - b);
  const apiIds = apps.map((a) => a.app_id).sort((a, b) => a - b);
  if (JSON.stringify(shownIds) !== JSON.stringify(apiIds)) {
    out.push(`applications: shown ${JSON.stringify(shownIds)}, API ${JSON.stringify(apiIds)}`);
  }
  for (const app of apps) {
    const row = rows.find((r) => r.appId === app.app_id);
    if (row !== undefined) {
      out.push(...rowMismatches(row, app));
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Sort oracle (05 §2.10-3): status, error rate, p95, alerts; abnormal applications on top
// ---------------------------------------------------------------------------------------------

export type SortId = 'status' | 'error_rate' | 'p95' | 'firing';
export interface SortSpec {
  readonly id: SortId;
  readonly desc: boolean;
}

/** Worst first: DOWN > STALE > WARN > (unknown) > UP (docs/14 §4.2 "최악 기준"). */
const RANK: Readonly<Record<string, number>> = { DOWN: 0, STALE: 1, WARN: 2, UNKNOWN: 3, UP: 4 };

export function rankOf(status: string): number {
  return RANK[normalStatus(status)] ?? 3;
}

/** The worse of two statuses. */
export function worse(a: string, b: string): string {
  return rankOf(b) < rankOf(a) ? normalStatus(b) : normalStatus(a);
}

/**
 * Needs attention (docs/15 §9.1 "이상 앱 상단 고정", plan assumption 3): any status but UP or at
 * least one firing alert.
 */
export function abnormal(app: Pick<OverviewApp, 'status' | 'firing'>): boolean {
  const firing = firingOf(app.firing);
  return normalStatus(app.status) !== 'UP' || (firing?.critical ?? 0) + (firing?.warn ?? 0) > 0;
}

function key(app: OverviewApp, id: SortId): readonly number[] | null {
  switch (id) {
    case 'status':
      return [rankOf(app.status)];
    case 'error_rate':
      return app.error_rate === null ? null : [app.error_rate];
    case 'p95':
      return app.p95_ms === null ? null : [app.p95_ms];
    case 'firing': {
      const firing = firingOf(app.firing);
      return firing === null ? null : [firing.critical, firing.warn];
    }
  }
}

/**
 * The expected display order: abnormal applications first, each group by the sort (default status
 * ascending = worst first), missing values last in both directions, then name, environment, id.
 */
export function expectedOrder(
  apps: readonly OverviewApp[],
  sort: SortSpec | null,
): { appId: number; pinned: boolean }[] {
  const spec = sort ?? { id: 'status', desc: false };
  const text = (a: string, b: string) =>
    a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
  return [...apps]
    .sort((a, b) => {
      const pin = Number(abnormal(b)) - Number(abnormal(a));
      if (pin !== 0) {
        return pin;
      }
      const ka = key(a, spec.id);
      const kb = key(b, spec.id);
      if (ka === null || kb === null) {
        if (ka !== kb) {
          return ka === null ? 1 : -1;
        }
      } else {
        for (let i = 0; i < Math.max(ka.length, kb.length); i += 1) {
          const diff = (ka[i] ?? 0) - (kb[i] ?? 0);
          if (diff !== 0) {
            return spec.desc ? -diff : diff;
          }
        }
      }
      return text(a.name, b.name) || text(a.env, b.env) || a.app_id - b.app_id;
    })
    .map((app) => ({ appId: app.app_id, pinned: abnormal(app) }));
}

// ---------------------------------------------------------------------------------------------
// Synthetic rows (schema-typed: components['schemas']['OverviewApp'])
// ---------------------------------------------------------------------------------------------

/** 60 per-minute instants ending at `endMs` (the sparkline grid of GET /overview). */
export function sparkTimes(endMs: number): string[] {
  const minute = 60_000;
  const end = Math.floor(endMs / minute) * minute;
  return Array.from({ length: 60 }, (_, i) => new Date(end - (59 - i) * minute).toISOString());
}

export interface SyntheticApp {
  readonly app_id: number;
  readonly name: string;
  readonly env: string;
  readonly status?: string;
  readonly tps?: number | null;
  readonly p95_ms?: number | null;
  readonly error_rate?: number | null;
  readonly active?: number | null;
  readonly batch_running?: number | null;
  readonly firing?: unknown;
  readonly instances?: { up: number; total: number };
}

/** A full OverviewApp row with 60-point sparklines (every value present unless given null). */
export function syntheticApp(input: SyntheticApp, ts: readonly string[]): OverviewApp {
  const tps = input.tps === undefined ? 12.5 : input.tps;
  const p95 = input.p95_ms === undefined ? 180 : input.p95_ms;
  const err = input.error_rate === undefined ? 0.004 : input.error_rate;
  const wave = (base: number | null, i: number) =>
    base === null ? null : Math.round(base * (1 + 0.2 * Math.sin(i / 4)) * 1000) / 1000;
  return {
    app_id: input.app_id,
    name: untrusted(input.name),
    env: untrusted(input.env),
    group: null,
    status: input.status ?? 'UP',
    instances: input.instances ?? { up: 2, total: 2 },
    tps,
    p95_ms: p95,
    error_rate: err,
    active: input.active === undefined ? 3 : input.active,
    batch_running: input.batch_running === undefined ? 0 : input.batch_running,
    // null when not given: a row without firing counts (out of the C-212 (2) contract, kept so the
    // padded rows and the fixed image stay "not available", which the checks above accept).
    firing: (input.firing ?? null) as unknown as OverviewApp['firing'],
    last_deploy: null,
    sparkline: {
      ts: [...ts],
      tps: ts.map((_, i) => wave(tps, i)),
      p95: ts.map((_, i) => wave(p95, i)),
      err: ts.map((_, i) => wave(err, i)),
    },
  };
}

/** The real rows padded with synthetic ones to `count` (the first `count` real rows when more). */
export function padApps(
  real: readonly OverviewApp[],
  count: number,
  ts: readonly string[],
): OverviewApp[] {
  const env = real[0]?.env ?? 'e2e';
  const padded = real.slice(0, count);
  for (let i = padded.length; i < count; i += 1) {
    const n = String(i + 1).padStart(2, '0');
    padded.push(
      syntheticApp(
        {
          app_id: PAD_ID_BASE + i,
          // Long enough to need the full name column (worst case for the width).
          name: `e2e-padding-service-${n}`,
          env,
          tps: 1000 + i * 37.25,
          p95_ms: 950 + i * 11,
          error_rate: 0.0125,
          active: 120 + i,
          batch_running: i % 3,
          instances: { up: 12, total: 12 },
        },
        ts,
      ),
    );
  }
  return padded;
}

/**
 * The mixed rows of the sort check (T-E2E-095): every status, firing by severity, missing values,
 * and ties broken by name.
 */
export function sortMix(env: string, ts: readonly string[]): OverviewApp[] {
  const base = PAD_ID_BASE + 500;
  const rows: SyntheticApp[] = [
    { app_id: base + 1, name: 'mix-alpha', env, status: 'UP', p95_ms: 100, error_rate: 0.01 },
    { app_id: base + 2, name: 'mix-bravo', env, status: 'WARN', p95_ms: 300, error_rate: 0 },
    { app_id: base + 3, name: 'mix-charlie', env, status: 'STALE', p95_ms: null, error_rate: 0.05 },
    { app_id: base + 4, name: 'mix-delta', env, status: 'DOWN', p95_ms: null, error_rate: null },
    {
      app_id: base + 5,
      name: 'mix-echo',
      env,
      status: 'UP',
      p95_ms: 50,
      error_rate: 0.2,
      firing: { critical: 1, warn: 0 },
    },
    {
      app_id: base + 6,
      name: 'mix-foxtrot',
      env,
      status: 'UP',
      p95_ms: 800,
      error_rate: null,
      firing: { critical: 0, warn: 2 },
    },
    {
      app_id: base + 7,
      name: 'mix-golf',
      env,
      status: 'UP',
      p95_ms: 200,
      error_rate: 0.002,
      firing: { critical: 0, warn: 0 },
    },
    { app_id: base + 8, name: 'mix-hotel', env, status: 'UP', p95_ms: null, error_rate: 0.03 },
    { app_id: base + 9, name: 'mix-india', env, status: 'DOWN', p95_ms: 120, error_rate: 0.5 },
    { app_id: base + 10, name: 'mix-juliet', env, status: 'UP', p95_ms: 100, error_rate: 0.01 },
  ];
  return rows.map((row) => syntheticApp(row, ts));
}

/** The sort clicks of the sort check and the sort each must give (docs/15 §9.1 toolbar). */
export const SORT_CLICKS: readonly { click: SortId; then: SortSpec }[] = [
  // The default order is status ascending (worst first); a click on it reverses it.
  { click: 'status', then: { id: 'status', desc: true } },
  // A new numeric field starts descending (largest first), a second click ascends.
  { click: 'error_rate', then: { id: 'error_rate', desc: true } },
  { click: 'error_rate', then: { id: 'error_rate', desc: false } },
  { click: 'p95', then: { id: 'p95', desc: true } },
  { click: 'firing', then: { id: 'firing', desc: true } },
  { click: 'firing', then: { id: 'firing', desc: false } },
];

/** URL value of a sort (`sort=<id>:asc|desc`). */
export function sortParam(sort: SortSpec): string {
  return `${sort.id}:${sort.desc ? 'desc' : 'asc'}`;
}

// ---------------------------------------------------------------------------------------------
// Checks
// ---------------------------------------------------------------------------------------------

/** The rows equal the latest answer (polled: the page refreshes every 10 s). */
export async function expectRowsEqualAnswer(page: Page, recorder: OverviewRecorder): Promise<void> {
  await expect
    .poll(async () => answerMismatches(await shownRows(page), recorder.last().body.data.apps), {
      message: 'rows = the latest GET /overview answer of the page',
      timeout: 20_000,
    })
    .toEqual([]);
}

/** 2.10-1: the overview is the screen of "/" (the route after sign-in), ready with rows. */
const overviewDefaultScreen: OverviewCheck = async ({ page }) => {
  const recorder = recordOverview(page);
  await page.goto('/');
  await expectShell(page);
  await expectUrl(page, (s) => s.path === '/overview', '"/" opens /overview');
  await expectOverviewState(page);
  await expect(page.getByTestId('route-pending')).toHaveCount(0);
  await expect(page.getByTestId('side-menu-overview')).toBeVisible();
  await expectRowsEqualAnswer(page, recorder);
};

/** Chooses an environment in the top bar (EnvSelect with search). */
export async function chooseEnv(page: Page, env: string): Promise<void> {
  await page.getByTestId('env-select').locator('.ant-select').click();
  await page.keyboard.type(env);
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: new RegExp(`^${env.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`) })
    .first()
    .click();
}

/**
 * 2.10-1: the environment and group filters are URL state (`env`, `group`), go to the server as
 * the query of GET /overview and the rows are the answer. The seed's applications have no group
 * (app groups are set in the application administration of WO-26), so the group option is added to
 * the GET /apps answer the group select reads; the overview request with that group goes to the
 * real server, whose answer (no application) is what the page shows.
 */
export const overviewFilters: OverviewCheck = async ({ page, seed, apiAs, testInfo }) => {
  const api = await apiAs(OVERVIEW_ORACLE_ROLE);
  const env = seed.apps.find((a) => a.app_id === seed.primary_app_id)?.env ?? '';
  expect(env, 'environment of the primary app').not.toBe('');
  await page.route(isAppsApi, async (route) => {
    try {
      const response = await route.fetch();
      const body = (await response.json()) as { data: { env: string; group: string | null }[] };
      for (const app of body.data) {
        if (app.env === env) {
          app.group = E2E_GROUP;
        }
      }
      await route.fulfill({ response, json: body });
    } catch {
      // The page closed while the request was in flight.
    }
  });
  const recorder = recordOverview(page);
  await openOverview(page, '/overview');
  const all = recorder.last();
  expect(all.query.get('env')).toBeNull();
  expect(all.query.get('group')).toBeNull();
  await expectRowsEqualAnswer(page, recorder);
  const envs = new Set(all.body.data.apps.map((a) => a.env));
  expect(envs.size, 'the seed has applications of more than one environment').toBeGreaterThan(1);

  // Environment (top bar, global key).
  const beforeEnv = Date.now();
  await chooseEnv(page, env);
  await expectUrl(page, (s) => s.env === env, `env=${env} in the URL`);
  const byEnv = await recorder.waitFor(
    (a) => a.at >= beforeEnv && a.query.get('env') === env,
    `env=${env}`,
  );
  expect(byEnv.query.get('group')).toBeNull();
  expect(byEnv.body.data.apps.length).toBeGreaterThan(0);
  expect(new Set(byEnv.body.data.apps.map((a) => a.env))).toEqual(new Set([env]));
  await expectRowsEqualAnswer(page, recorder);
  const apiByEnv = await apiOverview(api, { env });
  expect(apiByEnv.apps.map((a) => a.app_id).sort()).toEqual(
    byEnv.body.data.apps.map((a) => a.app_id).sort(),
  );

  // Group (the overview's own select).
  const select = page.getByTestId('overview-group-select');
  await expect(select).toHaveAccessibleName(t('overview:group.label'));
  const beforeGroup = Date.now();
  await select.selectOption(E2E_GROUP);
  await expectUrl(
    page,
    (s) => s.params.get('group') === E2E_GROUP && s.env === env,
    `group=${E2E_GROUP} next to env=${env}`,
  );
  const byGroup = await recorder.waitFor(
    (a) => a.at >= beforeGroup && a.query.get('group') === E2E_GROUP && a.query.get('env') === env,
    `env=${env}&group=${E2E_GROUP}`,
  );
  const apiByGroup = await apiOverview(api, { env, group: E2E_GROUP });
  expect(apiByGroup.apps.map((a) => a.app_id)).toEqual(byGroup.body.data.apps.map((a) => a.app_id));
  if (byGroup.body.data.apps.length === 0) {
    await expectOverviewState(page, 'empty');
    await expect(page.getByTestId('overview-empty')).toBeVisible();
  } else {
    await expectRowsEqualAnswer(page, recorder);
  }
  testInfo.annotations.push({
    type: 'overview-filters',
    description: `all ${String(all.body.data.apps.length)} apps; env=${env}: ${String(byEnv.body.data.apps.length)}; group ${E2E_GROUP} (option added to GET /apps, no seeded group): ${String(byGroup.body.data.apps.length)} from the real server`,
  });

  // "All groups" removes the key again.
  await select.selectOption({ index: 0 });
  await expectUrl(page, (s) => s.params.get('group') === null, 'group removed from the URL');
  await expectOverviewState(page);
  await page.unroute(isAppsApi);
};

/** 2.10-1: card and table views, the choice kept across a reload (browser preference). */
export const overviewViewToggle: OverviewCheck = async ({ page }) => {
  const recorder = recordOverview(page);
  const root = await openOverview(page, '/overview');
  await expect(root).toHaveAttribute('data-view', 'table');
  await expect(page.getByTestId('overview-table')).toBeVisible();
  await expect(page.getByTestId('overview-view-table')).toHaveAttribute('aria-pressed', 'true');
  await page.getByTestId('overview-view-cards').click();
  await expect(root).toHaveAttribute('data-view', 'cards');
  await expect(page.getByTestId('overview-cards')).toBeVisible();
  await expect(page.getByTestId('overview-table')).toHaveCount(0);
  await expect(page.getByTestId('overview-view-cards')).toHaveAttribute('aria-pressed', 'true');
  await expectRowsEqualAnswer(page, recorder);
  await page.reload();
  await expectOverviewState(page);
  await expect(root).toHaveAttribute('data-view', 'cards');
  await expect(page.getByTestId('overview-cards')).toBeVisible();
  await expectRowsEqualAnswer(page, recorder);
  await page.getByTestId('overview-view-table').click();
  await expect(root).toHaveAttribute('data-view', 'table');
  await page.reload();
  await expectOverviewState(page);
  await expect(root).toHaveAttribute('data-view', 'table');
  await expect(page.getByTestId('overview-table')).toBeVisible();
};

/** 2.10-1 (F-U21): ?kiosk=1 is the status board: no top bar or menu, the badge and the rows. */
export const overviewKiosk: OverviewCheck = async ({ page, ws }) => {
  const recorder = recordOverview(page);
  await openOverview(page, '/overview?kiosk=1');
  await expect(page.getByTestId('app-shell')).toHaveAttribute('data-kiosk', '1');
  await expect(page.getByTestId('kiosk-badge')).toBeVisible();
  await expect(page.getByTestId('topbar')).toHaveCount(0);
  await expect(page.getByTestId('side-menu')).toHaveCount(0);
  await expectRowsEqualAnswer(page, recorder);
  if (ws !== undefined) {
    // Kiosk refreshes live without the live key (docs/15 §3.4): the overview topic of every env.
    const subscription = await ws.waitForSubscription('overview', 15_000);
    expect(subscription.params).toEqual({});
  }
};

/** 2.10-2: every row's name and ten items equal the same-question GET /overview answer. */
export const overviewRowFields: OverviewCheck = async ({ page, apiAs, testInfo }) => {
  const recorder = recordOverview(page);
  await openOverview(page, '/overview');
  await expectRowsEqualAnswer(page, recorder);
  const answer = recorder.last();
  // The same question through the API names the same applications.
  const api = await apiAs(OVERVIEW_ORACLE_ROLE);
  const direct = await apiOverview(api);
  expect(direct.apps.map((a) => [a.app_id, a.name, a.env])).toEqual(
    answer.body.data.apps.map((a) => [a.app_id, a.name, a.env]),
  );
  const apps = answer.body.data.apps;
  const nulls = apps.reduce(
    (n, app) => n + VALUE_FIELDS.filter((f) => apiValue(app, f) === null).length,
    0,
  );
  testInfo.annotations.push({
    type: 'overview-rows',
    description: `${String(apps.length)} applications, ${String(nulls)} missing values shown as ${MISSING_DASH}; statuses ${JSON.stringify(apps.map((a) => `${a.name}:${a.status}`))}`,
  });
};

/** Reads the display order (app id, pinned) of the rows. */
export async function shownOrder(page: Page): Promise<{ appId: number; pinned: boolean }[]> {
  return (await shownRows(page)).map((r) => ({ appId: r.appId, pinned: r.pinned === 'true' }));
}

/**
 * 2.10-3: the four sorts with abnormal applications pinned on top. The seed has no instance that
 * stays STALE or WARN and no alert engine, so the rows of the real answer are replaced by a typed
 * mix (every status, firing by severity, missing values).
 */
export const overviewSortPinned: OverviewCheck = async ({ page, seed }) => {
  const env = seed.apps.find((a) => a.app_id === seed.primary_app_id)?.env ?? 'e2e';
  const mix = sortMix(env, sparkTimes(Date.now()));
  await rewriteOverview(page, (body) => ({ ...body, data: { ...body.data, apps: mix } }));
  await openOverview(page, '/overview');
  await expect(page.getByTestId('overview-sort-status')).toHaveAttribute('aria-pressed', 'true');
  await expect.poll(() => shownOrder(page)).toEqual(expectedOrder(mix, null));
  const order = await shownOrder(page);
  // Pinned rows form one block at the top.
  const firstFree = order.findIndex((r) => !r.pinned);
  expect(
    order.slice(firstFree).every((r) => !r.pinned),
    'pinned rows first',
  ).toBe(true);
  for (const step of SORT_CLICKS) {
    await page.getByTestId(`overview-sort-${step.click.replace('_', '-')}`).click();
    await expectUrl(
      page,
      (s) => s.params.get('sort') === sortParam(step.then),
      `sort=${sortParam(step.then)}`,
    );
    await expect
      .poll(() => shownOrder(page), { message: `order after sort ${sortParam(step.then)}` })
      .toEqual(expectedOrder(mix, step.then));
    await expect(
      page.getByTestId(`overview-sort-${step.then.id.replace('_', '-')}`),
    ).toHaveAttribute('data-desc', String(step.then.desc));
  }
  await page.unroute(isOverviewApi);
};

/** Most rows the all-application firing panel draws (src/features/overview FIRING_PANEL_ROWS_MAX). */
export const OVERVIEW_FIRING_ROWS = 8;

/** One row of the all-application firing panel as an API item says it should be drawn. */
export interface OverviewFiringRowOracle {
  readonly id: string;
  readonly severity: string | null;
}

/**
 * The all-application firing panel that a GET /overview/alerts answer asks for (WO-25 item schema,
 * ADR-0010 C-212 (2), docs/15 §9.1): critical first, then the newest firing start (`ts`), the
 * first `drawn` rows (at most eight; fewer when only they fit the height, C-234 (5)), "more" for
 * the rest; the counts by severity; "empty" for no item.
 */
export function expectedOverviewFiring(
  items: readonly unknown[],
  drawn: number = OVERVIEW_FIRING_ROWS,
): {
  state: 'empty' | 'list';
  rows: OverviewFiringRowOracle[];
  more: number;
  critical: number;
  warn: number;
} {
  const all = items.map((item) => {
    const o = (typeof item === 'object' && item !== null ? item : {}) as {
      event_id?: unknown;
      severity?: unknown;
      ts?: unknown;
    };
    const severity = typeof o.severity === 'string' ? o.severity.toLowerCase() : null;
    const ts = typeof o.ts === 'string' ? Date.parse(o.ts) : Number.NaN;
    return {
      id: String(o.event_id),
      severity: severity === 'warn' || severity === 'critical' ? severity : null,
      since: Number.isFinite(ts) ? ts : 0,
    };
  });
  // A stable sort of the API order: the same input order breaks ties the same way.
  const sorted = [...all].sort(
    (a, b) =>
      Number(b.severity === 'critical') - Number(a.severity === 'critical') || b.since - a.since,
  );
  const limit = Math.max(0, Math.min(drawn, OVERVIEW_FIRING_ROWS));
  const rows = sorted.slice(0, limit).map(({ id, severity }) => ({ id, severity }));
  return {
    state: items.length === 0 ? 'empty' : 'list',
    rows,
    more: items.length - rows.length,
    critical: all.filter((a) => a.severity === 'critical').length,
    warn: all.filter((a) => a.severity === 'warn').length,
  };
}

/** The panel's own GET /overview/alerts answers (no state filter: FIRING and ACKED, WO-25). */
function recordPanelAlerts(page: Page): { last(): Promise<unknown[] | undefined> } {
  const received: Promise<unknown[] | null>[] = [];
  page.on('response', (response) => {
    const url = new URL(response.url());
    if (
      response.request().method() === 'GET' &&
      response.ok() &&
      url.pathname === OVERVIEW_ALERTS_API &&
      !url.searchParams.has('state')
    ) {
      received.push(
        response.json().then(
          (json: { data?: unknown }) => (Array.isArray(json.data) ? json.data : null),
          () => null,
        ),
      );
    }
  });
  return {
    last: async () => (await received.at(-1)) ?? undefined,
  };
}

/** The all-application firing panel as drawn. */
async function shownOverviewFiring(page: Page): Promise<{
  state: string | null;
  total: string | null;
  rows: OverviewFiringRowOracle[];
  more: string | null;
  summary: string | null;
  /** Pixels the side column ends below the page box (0 or less: it fits, C-234 (5)). */
  overflow: number;
}> {
  return page.evaluate(() => {
    const panel = document.querySelector('[data-testid="overview-firing-panel"]');
    const side = panel === null ? null : (panel.closest('.overview-page__side') ?? panel);
    const box = document.getElementById('argos-main');
    const limit =
      box === null
        ? window.innerHeight
        : Math.min(box.getBoundingClientRect().bottom, window.innerHeight) -
          (Number.parseFloat(getComputedStyle(box).paddingBottom) || 0);
    return {
      overflow: side === null ? 0 : side.getBoundingClientRect().bottom - limit,
      state: panel?.getAttribute('data-state') ?? null,
      total: panel?.getAttribute('data-total') ?? null,
      rows: [...(panel?.querySelectorAll('[data-testid="overview-firing-row"]') ?? [])].map(
        (row) => ({
          id: row.getAttribute('data-alert-id') ?? '',
          severity: row.getAttribute('data-severity'),
        }),
      ),
      more: panel?.querySelector('[data-testid="overview-firing-more"]')?.textContent ?? null,
      summary: panel?.querySelector('[data-testid="overview-firing-summary"]')?.textContent ?? null,
    };
  });
}

/** Differences between the drawn panel and the panel an alerts answer and `alerts_firing_total` ask for. */
export function overviewFiringMismatches(
  shown: Awaited<ReturnType<typeof shownOverviewFiring>>,
  items: readonly unknown[],
  total: number | null,
): string[] {
  const out: string[] = [];
  // The panel draws the first rows that fit (C-234 (5)): the drawn count, at most eight, sets the
  // oracle's rows and "more"; the side column must end inside the page box (no page scroll).
  const expected = expectedOverviewFiring(items, shown.rows.length);
  if (shown.rows.length > OVERVIEW_FIRING_ROWS) {
    out.push(`${String(shown.rows.length)} rows drawn, at most ${String(OVERVIEW_FIRING_ROWS)}`);
  }
  if (shown.overflow > 0.5) {
    out.push(`the side column ends ${String(shown.overflow)} px below the page (a page scroll)`);
  }
  if (total === null) {
    out.push('alerts_firing_total is null: the alert engine always counts (WO-25, C-212 (2))');
  } else if (total !== items.length) {
    out.push(
      `alerts_firing_total ${String(total)} != ${String(items.length)} GET /overview/alerts rows`,
    );
  }
  if (shown.total !== String(total)) {
    out.push(`data-total ${String(shown.total)}, API ${String(total)}`);
  }
  if (shown.state !== expected.state) {
    out.push(`state ${String(shown.state)}, expected ${expected.state}`);
  }
  if (JSON.stringify(shown.rows) !== JSON.stringify(expected.rows)) {
    out.push(`rows ${JSON.stringify(shown.rows)}, expected ${JSON.stringify(expected.rows)}`);
  }
  const more =
    expected.more > 0
      ? t(`overview:panel.firing.more_${expected.more === 1 ? 'one' : 'other'}`, 'en', {
          count: String(expected.more),
        })
      : null;
  if (shown.more !== more) {
    out.push(`more "${String(shown.more)}", expected "${String(more)}"`);
  }
  const summary =
    expected.state === 'list'
      ? t('overview:panel.firing.summary', 'en', {
          critical: String(expected.critical),
          warn: String(expected.warn),
        })
      : null;
  if (shown.summary !== summary) {
    out.push(`summary "${String(shown.summary)}", expected "${String(summary)}"`);
  }
  return out;
}

/**
 * 2.10-4: the all-application firing panel lists the engine's active alerts (WO-25, ADR-0010
 * C-212 (2)): the rows equal the panel's own GET /overview/alerts answer (no state filter, FIRING
 * and ACKED; critical first, then the newest, eight rows and "more"), with the counts by severity,
 * and `alerts_firing_total` of GET /overview counts exactly those rows. The panel links to the
 * firing alert history. The "not available" state of a null total (a server without the engine)
 * is the component test OverviewPage.test.tsx (T-UI-007): the stack's engine always counts.
 */
export const overviewFiringPanel: OverviewCheck = async ({ page, testInfo }) => {
  const recorder = recordOverview(page);
  const alerts = recordPanelAlerts(page);
  await openOverview(page, '/overview');
  await expect
    .poll(async () => (await alerts.last()) !== undefined, {
      message: 'the panel asked GET /overview/alerts without a state filter',
      timeout: 20_000,
    })
    .toBe(true);
  // Both answers refresh every 10 s (GET /overview is cached 5 s on the server): the panel must
  // equal the latest pair once they describe the same moment.
  await expect
    .poll(
      async () =>
        overviewFiringMismatches(
          await shownOverviewFiring(page),
          (await alerts.last()) ?? [],
          recorder.last().body.data.alerts_firing_total,
        ),
      { message: 'firing panel = GET /overview/alerts = alerts_firing_total', timeout: 35_000 },
    )
    .toEqual([]);
  const history = page.getByTestId('overview-firing-history');
  const href = (await history.getAttribute('href')) ?? '';
  const link = new URL(href, 'http://x');
  expect(link.pathname).toBe('/alerts');
  expect(link.searchParams.get('state')).toBe('firing');
  const items = (await alerts.last()) ?? [];
  testInfo.annotations.push({
    type: 'overview-firing',
    description: `${String(items.length)} active alert(s), alerts_firing_total ${String(recorder.last().body.data.alerts_firing_total)}`,
  });
};

/**
 * 2.10-4 (WO-25): the seed's real FIRING alert (manifest `alert`, a rule the operator created on
 * the primary app) is in the panel's GET /overview/alerts answer and counted in the primary app's
 * firing cell; a click on a row opens that alert in the history (/alerts?state=firing&alert=<event
 * id>): the seed alert's row when it is among the eight drawn (critical first), else the first row.
 */
export const overviewFiringAlerts: OverviewCheck = async ({ page, seed, testInfo }) => {
  const alert = manifestAlert(seed);
  const recorder = recordOverview(page);
  const alerts = recordPanelAlerts(page);
  await openOverview(page, '/overview');
  await expect
    .poll(
      async () =>
        ((await alerts.last()) ?? []).some(
          (item) => String((item as { event_id?: unknown }).event_id) === alert.event_id,
        ),
      {
        message: `the seed alert ${alert.event_id} is in GET /overview/alerts (a reused stack older than the rule's 1 h window has no generator data left to fire on)`,
        timeout: 20_000,
      },
    )
    .toBe(true);
  const app = recorder.last().body.data.apps.find((a) => a.app_id === alert.app_id);
  const firing = firingOf(app?.firing);
  expect(firing, `firing counts of app ${String(alert.app_id)}`).not.toBeNull();
  expect(
    (firing?.critical ?? 0) + (firing?.warn ?? 0),
    'the primary app counts its active alerts',
  ).toBeGreaterThan(0);
  await expect(page.getByTestId(`overview-cell-firing-${String(alert.app_id)}`)).toHaveAttribute(
    'data-state',
    'firing',
  );
  const seedRow = page.locator(
    `[data-testid="overview-firing-row"][data-alert-id="${alert.event_id}"]`,
  );
  const shown = (await seedRow.count()) > 0;
  const row = shown ? seedRow : page.getByTestId('overview-firing-row').first();
  const id = shown ? alert.event_id : ((await row.getAttribute('data-alert-id')) ?? '');
  if (shown) {
    await expect(row).toContainText(alert.rule_name);
  }
  testInfo.annotations.push({
    type: 'overview-firing-seed',
    description: shown
      ? `seed alert ${alert.event_id} drawn and opened`
      : `seed alert ${alert.event_id} beyond the eight drawn rows (counted in "more"); first row ${id} opened`,
  });
  // Evidence (WO-25): the overview with the engine's real FIRING rows, before a row opens.
  const evidence = testInfo.outputPath('overview-firing.png');
  await page.screenshot({ path: evidence });
  await testInfo.attach('overview-firing.png', { path: evidence, contentType: 'image/png' });
  await row.getByRole('link').click();
  await expectUrl(
    page,
    (s) =>
      s.path === '/alerts' && s.params.get('alert') === id && s.params.get('state') === 'firing',
    'the row opens that alert in the history (/alerts?state=firing&alert=<event id>)',
  );
};

/** Ids of the server panel and their API field (05 §2.10-4, docs/14 §4.2 server). */
export const SERVER_ITEMS = [
  ['ingest-lag', 'ingest_lag_s'],
  ['disk', 'disk_usage_ratio'],
  ['channel-failures', 'channel_failures_24h'],
  ['sessions', 'sessions'],
  ['cert-expiry', 'cert_expiry_days'],
] as const;

/** Differences between the server panel and `server` of an answer. */
export function serverMismatches(
  shown: readonly { id: string; value: string | null; text: string }[],
  server: Overview['server'],
): string[] {
  const out: string[] = [];
  for (const [id, field] of SERVER_ITEMS) {
    const item = shown.find((s) => s.id === id);
    const expected = raw(server[field]);
    if (item?.value !== expected) {
      out.push(`${id}: shown ${String(item?.value)}, API ${expected}`);
    }
    const notAvailable = t('overview:panel.server.notAvailable');
    if (expected === 'null' && item?.text.trim() !== notAvailable) {
      out.push(`${id}: "${String(item?.text)}" instead of ${notAvailable}`);
    }
    if (expected !== 'null' && item?.text.includes(notAvailable) === true) {
      out.push(`${id}: the value ${expected} is shown as not available`);
    }
  }
  return out;
}

/** The five items of the server panel as shown. */
export async function shownServer(
  page: Page,
): Promise<{ id: string; value: string | null; text: string }[]> {
  return page.evaluate(
    (ids) =>
      ids.map((id) => {
        const node = document.querySelector(`[data-testid="overview-server-${id}"]`);
        return {
          id,
          value: node?.getAttribute('data-value') ?? null,
          text: node?.textContent ?? '',
        };
      }),
    SERVER_ITEMS.map(([id]) => id),
  );
}

/**
 * 2.10-4: the collection server panel equals `server.*` of the answer: ingest lag, sessions and
 * channel failures of the last 24 hours (the alert engine's final failed deliveries, WO-25) are
 * values, disk and certificate expiry (WO-28) read "Not available" while the server has not measured
 * them (null), never 0. The channel failures shown also equal a GET /overview the check asks itself.
 */
export const overviewServerPanel: OverviewCheck = async ({ page, testInfo, apiAs }) => {
  const recorder = recordOverview(page);
  await openOverview(page, '/overview');
  await expect(page.getByTestId('overview-server-panel')).toBeVisible();
  await expect
    .poll(async () => serverMismatches(await shownServer(page), recorder.last().body.data.server), {
      message: 'server panel = server.* of the latest answer',
      timeout: 20_000,
    })
    .toEqual([]);
  const server = recorder.last().body.data.server;
  expect(server.sessions, 'sessions is always a value').toEqual(expect.any(Number));
  expect(
    server.channel_failures_24h,
    'channel failures of the last 24 h are counted by the alert engine (WO-25), never null',
  ).toEqual(expect.any(Number));
  const api = await apiAs(OVERVIEW_ORACLE_ROLE);
  await expect
    .poll(
      async () =>
        String((await apiOverview(api)).server.channel_failures_24h) ===
        (await page.getByTestId('overview-server-channel-failures').getAttribute('data-value')),
      {
        message: 'channel failures shown = server.channel_failures_24h of GET /overview',
        timeout: 20_000,
      },
    )
    .toBe(true);
  testInfo.annotations.push({ type: 'overview-server', description: JSON.stringify(server) });
};

/**
 * 2.10-4 (WO-28): disk usage and certificate expiry are measured by the server itself (DSN-12-14,
 * `argos_server_disk_usage_ratio{mount}`, `argos_server_tls_cert_expiry_seconds`). The stack's
 * server has a write-ahead log and ClickHouse disks, so `server.disk_usage_ratio` is a ratio and
 * the panel shows it; its collector runs without TLS (stack.ts), so there is no certificate and
 * `server.cert_expiry_days` stays null, read "Not available", never 0.
 */
export const overviewServerMeasured: OverviewCheck = async ({ page, testInfo }) => {
  const recorder = recordOverview(page);
  await openOverview(page, '/overview');
  await expect(page.getByTestId('overview-server-panel')).toBeVisible();
  await expect
    .poll(() => recorder.last().body.data.server.disk_usage_ratio, {
      message: 'server.disk_usage_ratio is measured by the server (storage health job, 1 min)',
      timeout: 90_000,
    })
    .toEqual(expect.any(Number));
  await expect
    .poll(async () => serverMismatches(await shownServer(page), recorder.last().body.data.server), {
      message: 'server panel = server.* of the latest answer',
      timeout: 20_000,
    })
    .toEqual([]);
  const server = recorder.last().body.data.server;
  const ratio = server.disk_usage_ratio ?? Number.NaN;
  expect(ratio, 'a used ratio of the server disks').toBeGreaterThan(0);
  expect(ratio, 'a used ratio of the server disks').toBeLessThanOrEqual(1);
  const disk = page.getByTestId('overview-server-disk');
  await expect(disk).not.toContainText(t('overview:panel.server.notAvailable'));
  expect(server.cert_expiry_days, 'no collector certificate on the stack (TLS off)').toBeNull();
  await expect(page.getByTestId('overview-server-cert-expiry')).toHaveText(
    t('overview:panel.server.notAvailable'),
  );
  testInfo.annotations.push({
    type: 'overview-server-measured',
    description: JSON.stringify(server),
  });
};

/**
 * 2.10-5: a row opens that application's dashboard with the same time range, kind, environment and
 * live state (absolute range by a row click, relative live range by the name link).
 */
export const overviewOpensDashboard: OverviewCheck = async ({ page, seed }) => {
  const appId = seed.primary_app_id;
  const env = seed.apps.find((a) => a.app_id === appId)?.env ?? '';
  const { start, end } = seed.generator.range;
  await openOverview(
    page,
    `/overview?from=${start}&to=${end}&kind=BATCH&env=${encodeURIComponent(env)}`,
  );
  const before = state(page);
  expect(before.time.kind).toBe('absolute');
  // A click on a cell that is not the link opens the row (DataTable onRowOpen).
  await page.getByTestId(`overview-cell-instances-${String(appId)}`).click();
  const after = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/dashboard`,
    'the row opens the dashboard of its app',
  );
  expect(sameTime(after.time, before.time), 'same absolute range').toBe(true);
  expect(effectiveKind(after)).toBe('BATCH');
  expect(after.env).toBe(env);
  expect(after.live).toBe(false);

  await openOverview(page, '/overview?from=now-1h&live=1');
  const liveBefore = state(page);
  expect(liveBefore.live).toBe(true);
  await page.getByTestId(`overview-link-${String(appId)}`).click();
  const liveAfter = await expectUrl(
    page,
    (s) => s.path === `/apps/${String(appId)}/dashboard`,
    'the name link opens the dashboard',
  );
  expect(liveAfter.time).toEqual({ kind: 'relative', range: '1h' });
  expect(liveAfter.live).toBe(true);
  expect(effectiveKind(liveAfter)).toBe('HTTP');
  expect(liveAfter.env).toBeNull();
};

/** Layout numbers of the 30-row check. */
export interface FitMetrics {
  readonly rows: number;
  readonly rowsOutside: string[];
  readonly scrollHeight: number;
  readonly clientHeight: number;
  readonly scrollWidth: number;
  readonly clientWidth: number;
  /** Elements of the page or the overview that scroll (testid/class and sizes). */
  readonly scrollers: string[];
  /** Numeric cells whose value is cut by the column edge (testid and text). */
  readonly clippedNumbers: string[];
}

export async function fitMetrics(page: Page): Promise<FitMetrics> {
  return page.evaluate(() => {
    const rows = [...document.querySelectorAll('[data-testid^="overview-row-"]')];
    const height = window.innerHeight;
    const width = window.innerWidth;
    const rowsOutside = rows.flatMap((row) => {
      const r = row.getBoundingClientRect();
      return r.top < 0 || r.bottom > height || r.left < 0 || r.right > width
        ? [`${row.getAttribute('data-app-id') ?? '?'} ${JSON.stringify(r)}`]
        : [];
    });
    const root = document.scrollingElement ?? document.documentElement;
    const candidates = [
      document.getElementById('argos-main'),
      ...document.querySelectorAll(
        '[data-testid="overview-page"], [data-testid="overview-page"] *',
      ),
    ].filter((el): el is Element => el !== null);
    const scrollers = candidates.flatMap((el) => {
      const style = getComputedStyle(el);
      const y =
        /(auto|scroll|overlay)/.test(style.overflowY) && el.scrollHeight > el.clientHeight + 1;
      const x =
        /(auto|scroll|overlay)/.test(style.overflowX) && el.scrollWidth > el.clientWidth + 1;
      return y || x
        ? [
            `${el.getAttribute('data-testid') ?? el.getAttribute('class') ?? el.tagName} ${String(el.scrollWidth)}x${String(el.scrollHeight)} in ${String(el.clientWidth)}x${String(el.clientHeight)}`,
          ]
        : [];
    });
    const numeric = document.querySelectorAll(
      ['instances', 'tps', 'p95', 'error-rate', 'active', 'batches', 'firing']
        .map((field) => `[data-testid^="overview-cell-${field}-"]`)
        .join(', '),
    );
    const clippedNumbers = [...numeric].flatMap((value) => {
      const cell = value.closest('.data-table__cell');
      if (cell === null) {
        return [];
      }
      const v = value.getBoundingClientRect();
      const c = cell.getBoundingClientRect();
      return v.left < c.left - 0.5 || v.right > c.right + 0.5
        ? [`${value.getAttribute('data-testid') ?? '?'} "${value.textContent}"`]
        : [];
    });
    return {
      rows: rows.length,
      rowsOutside,
      scrollHeight: root.scrollHeight,
      clientHeight: root.clientHeight,
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      scrollers,
      clippedNumbers,
    };
  });
}

/**
 * 2.10-6 (T-ACPT-16 automated part): at 1366x768, 30 applications in the table without any
 * scrolling. The seed has fewer applications, so the real answer is padded to 30 with typed rows
 * whose values and names are as wide as real ones get (route-mocked padding, the report says so).
 */
export const overviewThirtyRowsFit: OverviewCheck = async ({ page, testInfo }) => {
  expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
  let realCount = -1;
  await rewriteOverview(page, (body) => {
    realCount = body.data.apps.length;
    const ts = body.data.apps[0]?.sparkline.ts ?? sparkTimes(Date.now());
    return { ...body, data: { ...body.data, apps: padApps(body.data.apps, FIT_ROWS, ts) } };
  });
  await openOverview(page, '/overview');
  await expect(page.locator('[data-testid^="overview-row-"]')).toHaveCount(FIT_ROWS);
  const metrics = await fitMetrics(page);
  testInfo.annotations.push({
    type: 'overview-fit',
    description: `${String(realCount)} real rows of the server padded to ${String(FIT_ROWS)}; ${JSON.stringify(metrics)}`,
  });
  expect(metrics.rowsOutside, 'every row inside the 1366x768 viewport').toEqual([]);
  expect(metrics.scrollHeight, 'no vertical page scroll').toBeLessThanOrEqual(metrics.clientHeight);
  expect(metrics.scrollWidth, 'no horizontal page scroll').toBeLessThanOrEqual(metrics.clientWidth);
  expect(metrics.scrollers, 'no scrolling element in the page or the overview').toEqual([]);
  expect(metrics.clippedNumbers, 'no numeric value cut by its column').toEqual([]);
  const file = testInfo.outputPath('overview-1366.png');
  await page.screenshot({ path: file });
  await testInfo.attach('overview-1366.png', { path: file, contentType: 'image/png' });
  await page.unroute(isOverviewApi);
};

export const OVERVIEW_CHECKS: Readonly<Record<string, OverviewCheck>> = {
  overviewDefaultScreen,
  overviewFilters,
  overviewViewToggle,
  overviewKiosk,
  overviewRowFields,
  overviewSortPinned,
  overviewFiringPanel,
  overviewFiringAlerts,
  overviewServerPanel,
  overviewServerMeasured,
  overviewOpensDashboard,
  overviewThirtyRowsFit,
};
