// SPDX-License-Identifier: Apache-2.0
//
// Batch transactions between the REAL agent and the REAL server (WO-18, docs/10 §6.8, docs/01
// F-A24 and D-20, docs/18 T-AG-058 and T-ACC-20 end to end): shop-batch
// (sample-apps/shop-batch/build/sample-app/shop-batch.jar, built by
// ./gradlew :sample-apps:shop-batch:sampleAppJar) with the agent jar of the stack reports to the
// stack's real collector as its own application (own agent token, provisioned through psql as
// agent-commands.spec.ts does), with SAMPLE_BATCH_PER_ITEM_SQL=true (settleStep prices each of its
// 20,000 items with its own query) and every profile kept. beforeAll runs settlementJob on a plain
// app thread (?detached=true: one BATCH transaction per Spring Batch step) and then sends 1,000
// HTTP requests (GET /api/orders/{id}); the @Scheduled methods and the Quartz jobs run on their own.
//
// - T-ACC-20 (stack): the service statistics of kind=HTTP count exactly the 1,000 order requests
//   for their service and no BATCH service; the dashboard's HTTP tiles (TPS, count) are the HTTP
//   services only and its BATCH tiles the BATCH services only; kind=BATCH lists
//   'BATCH settlementJob.prepareStep' and '.settleStep' apart. The services screen shows the API's
//   rows for both kinds.
// - T-AG-058 (stack): the XLog of kind=BATCH holds the settleStep point (API) and the screen with
//   the kind filter on Batch draws the API's points; the transaction detail of that point is a
//   BATCH transaction in summary mode whose SUMMARY step of the item query counts the executions
//   not recorded (recorded + summarized = 20,000), and the screen shows the summary-mode notice and
//   the SUMMARY rows with the API's counts.
//
// Every expected value comes from the app's answers and from the API asked inside the test; no
// fixed dates or ids. The jar missing (not built) or the Playwright image of e2e:docker (no JVM)
// skips every test with the reason.
import { existsSync } from 'node:fs';
import { join } from 'node:path';

import type { Page } from '@playwright/test';

import { t } from '../fixtures/i18n';
import { apiAs as loginAs, expect, iso, test } from '../fixtures/stack';
import { expectStatsState, LIST_ROOT, statsPath } from '../fixtures/statsOracle';
import { openShell } from '../fixtures/ui';
import { apiPoints, openXLog, xlogPath, type XlogPoints } from '../fixtures/xlogPage';
import { expectListMatchesApi } from '../required/statsChecks';
import {
  detailPath,
  openDetail,
  revealVirtualRow,
  visibleTreeRows,
  type ApiStep,
} from '../required/transactionChecks';
import type { ApiSession } from '../stack/api';
import { run } from '../stack/docker';
import { shopProblem, startShop, type RunningShop } from '../stack/liveShop';
import type { SeedManifest } from '../stack/manifest';
import { logsDir, REPO_ROOT, secretsDir } from '../stack/paths';
import { retireInstances } from '../stack/retire';
import { readRunState } from '../stack/runState';
import { newAgentToken, readSecret, sha256Hex, writeSecret } from '../stack/secrets';
import { stopProcess, tokenSql } from '../stack/seed/traffic';
import type { StackSession } from '../stack/session';
import { httpGet, waitFor } from '../stack/wait';

/** The batch app's own application (its statistics stay apart from the seed's). */
const BATCH_APP = { name: 'e2e-wo18-batch', env: 'e2e' } as const;
/** Instance name of the batch app. */
const BATCH_INSTANCE = 'wo18-batch';
/** Token file of BATCH_APP inside the run's secrets directory. */
const BATCH_TOKEN_FILE = 'wo18-agent.token';
/** The shop-batch jar of this tree. */
const SHOP_BATCH_JAR = join(
  REPO_ROOT,
  'sample-apps',
  'shop-batch',
  'build',
  'sample-app',
  'shop-batch.jar',
);
/** HTTP requests of the kind separation. */
const HTTP_REQUESTS = 1000;
/** Items of settlementJob's chunk step (shop-batch README). */
const CHUNK_ITEMS = 20_000;
/** Service names of 10 §6.8 for the detached job's steps (WO-18 lane A1). */
const PREPARE_STEP = 'BATCH settlementJob.prepareStep';
const SETTLE_STEP = 'BATCH settlementJob.settleStep';
/** Service of GET /api/orders/{id} (the Spring MVC pattern, WO-12). */
const ORDERS_SERVICE = 'GET /api/orders/{id}';

/** Requirements of every test in this file, as literals the traceability report reads. */
const REQ_BATCH = { type: 'req', description: 'F-A24' };
const REQ_KIND = { type: 'req', description: 'D-20' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [REQ_BATCH, REQ_KIND, ...ids.map((description) => ({ type: 'req', description }))];
}

// ---------------------------------------------------------------------------------------------
// API shapes (the subset these tests read; docs/api/openapi.yaml is the contract)
// ---------------------------------------------------------------------------------------------

interface ServiceRow {
  readonly service: string;
  readonly service_id: string;
  readonly count: number;
  readonly apdex: number | null;
}

interface DashboardAnswer {
  readonly tiles: { readonly count: number; readonly tps: number | null };
}

interface InstanceRow {
  readonly instance_id: number;
  readonly name: string;
  readonly state: string;
  readonly connected_at: string | null;
}

/** A profile step of GET /transactions/{txId} with the fields this spec reads. */
type BatchStep = ApiStep & {
  readonly sql?: string;
  readonly summary?: { readonly step_type: string; readonly count: number };
};

interface BatchDetail {
  readonly tx_id: string;
  readonly ts: string;
  readonly kind: string;
  readonly service: string | null;
  readonly http_status: number;
  readonly url: string | null;
  readonly sql: { readonly count: number };
  readonly flags: { readonly summarized: boolean };
  readonly profile: {
    readonly state: string;
    readonly total_steps: number | null;
    readonly steps: readonly BatchStep[];
  };
}

// ---------------------------------------------------------------------------------------------
// The batch app of this file
// ---------------------------------------------------------------------------------------------

interface BatchContext {
  readonly shop: RunningShop;
  readonly appId: number;
  readonly instanceId: number;
  readonly shopLog: string;
  /** Start of the work this file did (ms), before the detached run. */
  readonly startedAt: number;
  /** End of the work (ms): after the 1,000 requests answered. */
  readonly doneAt: number;
  /** HTTP requests this file sent to the app (orders, the detached start and its state polls). */
  readonly httpSent: number;
}

let ctx: BatchContext | null = null;
let skipReason: string | null = null;

function context(): BatchContext {
  if (ctx === null) {
    throw new Error('the WO-18 batch app did not start (see beforeAll)');
  }
  return ctx;
}

/** Why the batch app cannot run here, or null. */
function batchProblem(runId: string): string | null {
  const problem = shopProblem(runId);
  if (problem !== null) {
    return problem;
  }
  if (!existsSync(SHOP_BATCH_JAR)) {
    return `${SHOP_BATCH_JAR} is missing: ./gradlew :sample-apps:shop-batch:sampleAppJar`;
  }
  return null;
}

/** The app row and an ACTIVE agent token of BATCH_APP (once per run; the token is never printed). */
async function provisionToken(runId: string): Promise<string> {
  const dir = secretsDir(runId);
  const file = join(dir, BATCH_TOKEN_FILE);
  if (existsSync(file) && readSecret(dir, BATCH_TOKEN_FILE).startsWith('argos_')) {
    return file;
  }
  const postgres = readRunState()?.containers?.postgres;
  if (postgres === undefined) {
    throw new Error('ui/e2e/.stack/run.json names no PostgreSQL container to provision the token');
  }
  const token = newAgentToken();
  await run(
    'docker',
    [
      'exec',
      '-i',
      postgres,
      'psql',
      '-X',
      '-q',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      'postgres',
      '-d',
      'argos',
    ],
    {
      input: tokenSql(sha256Hex(token), token.slice(0, 14), BATCH_APP),
      secrets: [token],
      timeoutMs: 60_000,
    },
  );
  writeSecret(dir, BATCH_TOKEN_FILE, token);
  return file;
}

/** POST to the app (Node fetch), resolving with the status and body text. */
async function httpPost(url: string): Promise<{ status: number; text: string }> {
  const response = await fetch(url, { method: 'POST', signal: AbortSignal.timeout(60_000) });
  return { status: response.status, text: await response.text() };
}

/** Starts settlementJob detached and waits for DONE; returns the number of requests it took. */
async function runDetached(shopUrl: string): Promise<number> {
  const started = await httpPost(`${shopUrl}/batch/run/settlementJob?detached=true`);
  expect(started.status, `detached start: ${started.text}`).toBe(202);
  const runId = (JSON.parse(started.text) as { runId: number }).runId;
  let requests = 1;
  const deadline = Date.now() + 180_000;
  for (;;) {
    const state = await httpGet(
      `${shopUrl}/batch/executions/${String(runId)}?awaitMs=20000`,
      {},
      30_000,
    );
    requests += 1;
    expect(state.status, `GET /batch/executions/${String(runId)}`).toBe(200);
    const body = JSON.parse(state.text) as { state: string; status?: string };
    if (body.state !== 'RUNNING') {
      expect(body.state, `detached run ${String(runId)}: ${state.text.slice(0, 300)}`).toBe('DONE');
      expect(body.status).toBe('COMPLETED');
      return requests;
    }
    if (Date.now() > deadline) {
      throw new Error(`detached run ${String(runId)} still RUNNING after 3 minutes`);
    }
  }
}

async function startContext(session: StackSession, seed: SeedManifest): Promise<BatchContext> {
  const runId = session.run_id;
  const tokenFile = await provisionToken(runId);
  const shopLog = 'wo18-batch-shop-batch.log';
  const shop = await startShop(runId, {
    instance: BATCH_INSTANCE,
    logFile: shopLog,
    jar: SHOP_BATCH_JAR,
    env: { SAMPLE_BATCH_PER_ITEM_SQL: 'true', SAMPLE_BATCH_LONG_DURATION_MS: '3000' },
    jvmArgs: [
      '-Dargos.sampling.profile.normal-rate=1.0',
      `-Dargos.log.dir=${join(logsDir(runId), 'wo18-batch-agent')}`,
    ],
    app: { ...BATCH_APP, tokenFile },
  });
  try {
    const admin = await loginAs(session, seed, 'admin');
    const appId = await waitFor(
      async () => {
        const apps = await admin.data<{ app_id: number; name: string; env: string }[]>('/apps');
        return apps.find((a) => a.name === BATCH_APP.name && a.env === BATCH_APP.env)?.app_id;
      },
      { timeoutMs: 60_000, intervalMs: 1000, what: `GET /apps lists ${BATCH_APP.name}` },
    );
    const instanceId = await waitFor(
      async () => {
        const rows = await admin.data<InstanceRow[]>(`/apps/${String(appId)}/instances`);
        const row = rows.find((r) => r.name === BATCH_INSTANCE);
        const live =
          row !== undefined &&
          row.connected_at !== null &&
          (row.state === 'UP' || row.state === 'WARN');
        return live ? row.instance_id : undefined;
      },
      { timeoutMs: 90_000, intervalMs: 1000, what: `${BATCH_INSTANCE} with a live session` },
    );
    const startedAt = Date.now();
    let httpSent = await runDetached(shop.url);
    for (let i = 0; i < HTTP_REQUESTS; i += 1) {
      const answer = await httpGet(`${shop.url}/api/orders/${String(1 + (i % 10))}`, {}, 30_000);
      expect(answer.status, `GET /api/orders/${String(1 + (i % 10))}`).toBe(200);
      httpSent += 1;
    }
    return {
      shop,
      appId,
      instanceId,
      shopLog: join(logsDir(runId), shopLog),
      startedAt,
      doneAt: Date.now(),
      httpSent,
    };
  } catch (error) {
    await stopProcess(shop.child);
    throw error;
  }
}

/**
 * The fixed range of the checks: from a second before the work to a few seconds after it, so that
 * later @Scheduled runs never enter it (absolute, the screens and the API see the same rows).
 */
function range(c: BatchContext): { from: string; to: string } {
  return {
    from: iso(Math.floor(c.startedAt / 1000) * 1000),
    to: iso(Math.ceil(c.doneAt / 1000) * 1000 + 2000),
  };
}

async function serviceRows(api: ApiSession, appId: number, kind: string): Promise<ServiceRow[]> {
  const { from, to } = range(context());
  return api.data<ServiceRow[]>(`/apps/${String(appId)}/services/stats`, {
    from,
    to,
    kind,
    limit: 500,
  });
}

function sum(rows: readonly ServiceRow[]): number {
  return rows.reduce((n, r) => n + r.count, 0);
}

/** Executions of the per-item price query in a profile: recorded (with repeats) and summarized. */
function priceQueries(d: BatchDetail): { recorded: number; summarized: number } {
  const isPrice = (text: string | undefined): boolean =>
    (text ?? '').toUpperCase().replace(/\s+/g, '').includes('FROMPRODUCTSWHEREID=?');
  let recorded = 0;
  let summarized = 0;
  for (const s of d.profile.steps) {
    const type = s.type.toUpperCase();
    if (type === 'SQL' && isPrice(s.sql ?? s.name)) {
      recorded += Math.max(1, s.repeat);
    } else if (
      type === 'SUMMARY' &&
      s.summary?.step_type.toUpperCase() === 'SQL' &&
      isPrice(s.sql ?? s.name)
    ) {
      summarized += s.summary.count;
    }
  }
  return { recorded, summarized };
}

/** The settleStep point of the detached run in the BATCH XLog and its detail (polled). */
async function settleTransaction(
  api: ApiSession,
): Promise<{ points: XlogPoints; detail: BatchDetail }> {
  const c = context();
  const { from, to } = range(c);
  const found: { value: { points: XlogPoints; detail: BatchDetail } | null } = { value: null };
  await expect
    .poll(
      async () => {
        const { data } = await apiPoints(api, c.appId, { from, to, kind: 'BATCH', limit: 100_000 });
        const index = data.tx_id.findIndex(
          (_, i) => data.dictionary.service[data.service_id[i] ?? ''] === SETTLE_STEP,
        );
        const txId = data.tx_id[index];
        const ts = data.ts[index];
        if (txId === undefined || ts === undefined) {
          return 'no settleStep point yet';
        }
        const detail = await api.data<BatchDetail>(`/transactions/${txId}`, {
          ts: new Date(ts).toISOString(),
          app: c.appId,
        });
        if (detail.profile.state !== 'captured') {
          return `profile ${detail.profile.state}`;
        }
        found.value = { points: data, detail };
        return 'found';
      },
      {
        message: 'the settleStep transaction with its captured profile',
        timeout: 120_000,
        intervals: [3000],
      },
    )
    .toBe('found');
  if (found.value === null) throw new Error('unreachable: the settleStep transaction was found');
  return found.value;
}

/** The services screen of `kind` over the fixed range shows the API's rows; returns them. */
async function servicesScreen(
  page: Page,
  api: ApiSession,
  kind: string,
): Promise<readonly unknown[]> {
  const c = context();
  const { from, to } = range(c);
  await page.goto(statsPath(c.appId, 'services', from, to, { kind }));
  await expectStatsState(page, LIST_ROOT.services);
  return expectListMatchesApi(page, api, 'services');
}

test.describe('batch transactions on the real server (WO-18)', () => {
  test.beforeAll(async ({ session, seed }, testInfo) => {
    testInfo.setTimeout(8 * 60_000);
    skipReason = batchProblem(session.run_id);
    test.skip(skipReason !== null, skipReason ?? '');
    ctx = await startContext(session, seed);
  });

  // Every stack test opens the UI (fixtures/stack.ts page hooks): the batch app's dashboard.
  test.beforeEach(async ({ page }) => {
    await openShell(page, `/apps/${String(context().appId)}/dashboard`);
  });

  test.afterAll(async ({ session, seed }, testInfo) => {
    const c = ctx;
    ctx = null;
    if (c !== null) {
      await stopProcess(c.shop.child);
      testInfo.annotations.push({ type: 'wo18-batch-shop-log', description: c.shopLog });
      // C-241 (7): the stopped batch app's instance never stays FIRING as instance_down.
      await retireInstances(await loginAs(session, seed, 'operator'), [c.instanceId]);
    }
  });

  test(
    'T-ACC-20 (stack) kind separation: kind=HTTP service statistics count the 1,000 requests and no batch, the HTTP tiles (TPS, count) exclude batch, kind=BATCH lists the settlementJob steps apart; the services screen = the API',
    { annotation: req('T-ACC-20', 'F-U05') },
    async ({ page, apiAs }, testInfo) => {
      test.setTimeout(5 * 60_000);
      const c = context();
      const api = await apiAs('viewer');
      const { from, to } = range(c);
      // The rollups and the live tail catch up within a minute (ingest, 1m rollup).
      await expect
        .poll(
          async () =>
            (await serviceRows(api, c.appId, 'HTTP')).find((r) => r.service === ORDERS_SERVICE)
              ?.count ?? 0,
          { message: `${ORDERS_SERVICE} count of kind=HTTP`, timeout: 150_000, intervals: [3000] },
        )
        .toBe(HTTP_REQUESTS);
      await expect
        .poll(
          async () => {
            const names = (await serviceRows(api, c.appId, 'BATCH')).map((r) => r.service);
            return [PREPARE_STEP, SETTLE_STEP].filter((n) => !names.includes(n));
          },
          {
            message: 'kind=BATCH lists the settlementJob steps',
            timeout: 150_000,
            intervals: [3000],
          },
        )
        .toEqual([]);
      const http = await serviceRows(api, c.appId, 'HTTP');
      const batch = await serviceRows(api, c.appId, 'BATCH');
      expect(
        http.filter((r) => r.service.startsWith('BATCH ')).map((r) => r.service),
        'no BATCH service in the HTTP statistics',
      ).toEqual([]);
      expect(
        batch.filter((r) => !r.service.startsWith('BATCH ')).map((r) => r.service),
        'only BATCH services in the BATCH statistics',
      ).toEqual([]);
      // Every HTTP request of the range is one this file sent, apart from liveShop's startup check
      // of GET /api/version when it answered in the first second of the range.
      const version = http.find((r) => r.service === 'GET /api/version')?.count ?? 0;
      expect(version, 'GET /api/version of the startup check').toBeLessThanOrEqual(1);
      expect(sum(http) - version, 'Σ kind=HTTP count = the HTTP requests sent').toBe(c.httpSent);
      expect(batch.find((r) => r.service === PREPARE_STEP)?.count, PREPARE_STEP).toBe(1);
      expect(batch.find((r) => r.service === SETTLE_STEP)?.count, SETTLE_STEP).toBe(1);
      const orders = http.find((r) => r.service === ORDERS_SERVICE);
      expect(orders?.apdex ?? null, `Apdex of ${ORDERS_SERVICE}`).not.toBeNull();

      // TPS and count tiles of the dashboard: kind=HTTP = the HTTP services, kind=BATCH apart.
      const tiles = async (kind: string): Promise<DashboardAnswer['tiles']> =>
        (await api.data<DashboardAnswer>(`/apps/${String(c.appId)}/dashboard`, { from, to, kind }))
          .tiles;
      const httpTiles = await tiles('HTTP');
      const batchTiles = await tiles('BATCH');
      expect(httpTiles.count, 'dashboard kind=HTTP count = Σ HTTP services (no batch)').toBe(
        sum(http),
      );
      expect(batchTiles.count, 'dashboard kind=BATCH count = Σ BATCH services').toBe(sum(batch));

      // The screens: the services list of each kind shows the API's rows.
      const shownHttp = await servicesScreen(page, api, 'HTTP');
      const shownBatch = await servicesScreen(page, api, 'BATCH');
      await expect(page.getByTestId(LIST_ROOT.services)).toContainText(SETTLE_STEP);
      testInfo.annotations.push({
        type: 'wo18-acc20',
        description: `range ${from}..${to}: HTTP ${String(sum(http))} in ${String(http.length)} services (${ORDERS_SERVICE} ${String(orders?.count)}), sent ${String(c.httpSent)}; BATCH ${String(sum(batch))} in ${String(batch.length)} services (apdex ${batch.map((r) => `${r.service}=${String(r.apdex)}`).join(', ')}); dashboard HTTP count ${String(httpTiles.count)} tps ${String(httpTiles.tps)}, BATCH count ${String(batchTiles.count)}; screen rows HTTP ${String(shownHttp.length)}, BATCH ${String(shownBatch.length)}`,
      });
    },
  );

  test(
    'T-AG-058 (stack) the BATCH XLog shows the detached settleStep, whose detail is a BATCH transaction in summary mode: SUMMARY count of the item query = 20,000 - recorded; screen = API',
    { annotation: req('T-AG-058', 'F-U02', '05-2.2-9') },
    async ({ page, apiAs }, testInfo) => {
      test.setTimeout(5 * 60_000);
      const c = context();
      const api = await apiAs('viewer');
      const { from, to } = range(c);
      const { detail: d } = await settleTransaction(api);
      const label = `settleStep ${d.tx_id}`;
      expect(d.kind, label).toBe('BATCH');
      expect(d.service, label).toBe(SETTLE_STEP);
      expect(d.http_status, `${label}: BATCH status 0`).toBe(0);
      expect(d.flags.summarized, `${label}: summary mode (C-69)`).toBe(true);
      expect(d.sql.count, `${label}: sql.count`).toBeGreaterThanOrEqual(CHUNK_ITEMS);
      expect(d.profile.total_steps ?? 0, `${label}: total_steps`).toBeGreaterThanOrEqual(
        CHUNK_ITEMS,
      );
      const summaries = d.profile.steps.filter((s) => s.type.toUpperCase() === 'SUMMARY');
      expect(summaries.length, `${label}: SUMMARY steps`).toBeGreaterThan(0);
      const price = priceQueries(d);
      expect(price.summarized, `${label}: SUMMARY count of the item query`).toBe(
        CHUNK_ITEMS - price.recorded,
      );
      // The HTTP XLog of the same range has no batch point.
      const httpPoints = (await apiPoints(api, c.appId, { from, to, kind: 'HTTP', limit: 100_000 }))
        .data;
      expect(
        httpPoints.service_id
          .map((key) => httpPoints.dictionary.service[key] ?? '')
          .filter((name) => name.startsWith('BATCH ')),
        'BATCH services among the kind=HTTP points',
      ).toEqual([]);

      // XLog screen with the kind filter on Batch: the points the API returns for the same question.
      await openXLog(page, xlogPath(c.appId, from, to, { kind: 'BATCH' }));
      const batchPoints = (
        await apiPoints(api, c.appId, { from, to, kind: 'BATCH', limit: 100_000 })
      ).data;
      await expect(page.getByTestId('xlog-page')).toHaveAttribute(
        'data-point-count',
        String(batchPoints.tx_id.length),
      );
      const batchOption = page.getByTestId('kind-filter-batch').first();
      await expect(batchOption).toHaveAttribute('aria-checked', 'true');
      await expect(batchOption).toHaveText(t('common:term.kind.batch'));

      // The detail screen: the summary-mode notice, the service and the SUMMARY rows = the API.
      await openDetail(page, detailPath({ app_id: c.appId, tx_id: d.tx_id, ts: d.ts }));
      await expect(page.getByTestId('transaction-field-service')).toContainText(SETTLE_STEP);
      await expect(page.getByTestId('transaction-profile-notice-summarized')).toBeVisible();
      const rows = visibleTreeRows(d.profile.steps);
      const timeline = page.getByTestId('transaction-timeline');
      await expect(timeline.getByRole('treegrid')).toHaveAttribute(
        'aria-rowcount',
        String(1 + rows.length),
      );
      const shown: number[] = [];
      for (const [index, row] of rows.entries()) {
        if (row.kind !== 'step' || row.type !== 'SUMMARY') continue;
        const element = (await revealVirtualRow(timeline, 1 + index)).getByTestId(
          'transaction-summary-step',
        );
        shown.push(Number(await element.getAttribute('data-count')));
      }
      const expected = summaries.map((s) => s.summary?.count ?? s.repeat);
      expect(
        [...shown].sort((a, b) => a - b),
        `${label}: SUMMARY rows of the timeline = the API`,
      ).toEqual([...expected].sort((a, b) => a - b));
      testInfo.annotations.push({
        type: 'wo18-agent058',
        description: `${label}: ${String(d.profile.steps.length)} steps, total_steps ${String(d.profile.total_steps)}, ${String(summaries.length)} SUMMARY steps (${expected.join(', ')}), item query recorded ${String(price.recorded)} + summarized ${String(price.summarized)}, sql.count ${String(d.sql.count)}; BATCH points ${String(batchPoints.tx_id.length)}`,
      });
    },
  );
});
