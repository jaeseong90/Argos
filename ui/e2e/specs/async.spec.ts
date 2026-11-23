// SPDX-License-Identifier: Apache-2.0
//
// Async work inside one transaction on the real stack (WO-15, docs/10 §6.6 and §8, docs/18
// T-AG-052 and T-ACC-23 end to end): the traffic seed sends GET /api/async/executor?work=sql to
// shop-boot3 with the agent (e2e/stack/seed/traffic.ts ASYNC_SERVICE, every profile of that
// service kept, `async.step` off). Each async branch of the app (`@Async`, `supplyAsync`, the
// scheduled task, the app pool, the virtual-thread-per-task executor on JDK 21+) runs its own SQL
// statement on its own thread, and the app answers how many it ran off the request thread
// (`asyncSql`, recorded in the manifest's `async_calls`).
//
//   T-E2E-async-1  the transaction detail of every seed call: the API profile holds at least
//                  `asyncSql` SQL steps with as many distinct statements, every step inside the
//                  transaction's time range; the timeline shows the API's SQL rows (each statement
//                  by its text), the root bar spans the whole axis and the summary equals the API
//   T-E2E-async-2  the call tree of the same transactions: the API oracle's rows and each SQL
//                  statement of the branches on its own row
//   T-E2E-async-3  virtual threads (SAMPLE_VT=true, Boot 3.2+ on JDK 21): a request on a Tomcat
//                  virtual thread shows CPU and allocation as "Not measurable" (never 0), the
//                  virtual thread badge, the normalized thread name tomcat-handler- (10 U-16) and
//                  its async SQL steps. It starts its own shop-boot3 with the stack's agent on a
//                  host JVM (e2e/stack/liveShop.ts), so it is skipped inside the Playwright image
//                  of e2e:docker
//
// Every expected value comes from the app's answer and from GET /transactions/{txId} asked inside
// the test; no fixed dates. The page hooks of fixtures/stack.ts run after every test.
import type { Locator, Page, TestInfo } from '@playwright/test';

import { t } from '../fixtures/i18n';
import { expect, test } from '../fixtures/stack';
import {
  apiDetail,
  detailPath,
  expectSummaryMatches,
  openDetail,
  revealVirtualRow,
  viewerApi,
  visibleTreeRows,
  type ApiDetail,
  type ApiStep,
  type TxCheckContext,
} from '../required/transactionChecks';
import { shopProblem, startShop } from '../stack/liveShop';
import type { SeedManifest } from '../stack/manifest';
import {
  ASYNC_PATH,
  ASYNC_SERVICE,
  asyncCallOf,
  isoSeconds,
  parseAsyncAnswer,
  profileKeptProperties,
  stopProcess,
} from '../stack/seed/traffic';
import { httpGet } from '../stack/wait';

const ASYNC = { type: 'req', description: 'F-A07' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [ASYNC, ...ids.map((description) => ({ type: 'req', description }))];
}

/** Instance name of T-E2E-async-3's own shop (not the seed's, so sessions do not replace each other). */
const VT_INSTANCE = 'e2e-shop-async-vt';

/** A profile step with the SQL text the detail API resolves (docs/14 §4.6 `sql`). */
type AsyncStep = ApiStep & { readonly sql?: string };

/** The detail with the fields this spec reads beyond ApiDetail. */
type AsyncDetail = ApiDetail & {
  readonly flags: ApiDetail['flags'] & { readonly virtual_thread?: boolean };
  readonly profile: ApiDetail['profile'] & { readonly steps: readonly AsyncStep[] };
};

/** A seed call with a usable answer (host clock times, the app's asyncSql). */
interface AnsweredCall {
  readonly sentAt: number;
  readonly doneAt: number;
  readonly asyncSql: number;
}

/** A transaction of an async call with its detail. */
interface AsyncTx {
  readonly call: AnsweredCall;
  readonly tx: { readonly tx_id: string; readonly app_id: number; readonly ts: string };
  readonly detail: AsyncDetail;
}

interface XlogPoints {
  readonly tx_id: string[];
  readonly ts: (string | number)[];
  readonly service_id?: (string | null)[];
  readonly dictionary?: { readonly service?: Readonly<Record<string, string>> };
}

/** ISO-8601 of an XLog point time, without zero sub-second digits (the API form of the setup). */
function pointIso(ts: string | number): string {
  const iso = typeof ts === 'number' ? new Date(ts).toISOString() : ts;
  return iso.replace(/\.0+Z$/, 'Z');
}

/** The seed's answered async calls, or a failure naming why there is none. */
function answeredCalls(seed: SeedManifest): AnsweredCall[] {
  const calls = seed.async_calls;
  if (calls === undefined) {
    throw new Error(
      'the seed manifest has no async_calls (written before WO-15): restart the stack (corepack pnpm e2e:stack:down, then e2e:stack)',
    );
  }
  const answered = calls.flatMap((c) =>
    c.async_sql === null
      ? []
      : [{ sentAt: Date.parse(c.sent_at), doneAt: Date.parse(c.done_at), asyncSql: c.async_sql }],
  );
  if (answered.length === 0) {
    throw new Error(
      `no GET ${ASYNC_PATH} call of the seed has an asyncSql answer: ${calls.map((c) => c.problem ?? `status ${String(c.status)}`).join('; ') || 'no call recorded'}`,
    );
  }
  return answered;
}

/** SQL steps of a profile. */
function sqlSteps(d: AsyncDetail): AsyncStep[] {
  return d.profile.steps.filter((s) => s.type.toUpperCase() === 'SQL');
}

/** Distinct statements of the SQL steps (the SQL id, else the text). */
function statements(d: AsyncDetail): Set<string> {
  return new Set(sqlSteps(d).map((s) => s.sql_id ?? s.sql ?? ''));
}

/**
 * Steps that do not lie inside [0, elapsed] of the transaction. SUMMARY steps carry no interval
 * (proto/README.md profile_step SUMMARY row); start and elapsed are each rounded to whole
 * milliseconds by the server, so an end may pass the transaction's elapsed by 1 ms.
 */
function stepsOutsideTransaction(d: AsyncDetail): AsyncStep[] {
  return d.profile.steps.filter(
    (s) =>
      s.type.toUpperCase() !== 'SUMMARY' &&
      (s.start_offset_ms < 0 || s.start_offset_ms + Math.max(0, s.elapsed_ms) > d.elapsed_ms + 1),
  );
}

/** What is still missing for the transaction of every call (empty: all found and captured). */
function missingOf(calls: readonly AnsweredCall[], found: ReadonlyMap<AnsweredCall, AsyncTx>) {
  return calls
    .filter((c) => found.get(c)?.detail.profile.state !== 'captured')
    .map((c) => {
      const tx = found.get(c);
      return tx === undefined
        ? `call at ${new Date(c.sentAt).toISOString()}: no ${ASYNC_SERVICE} transaction in the XLog`
        : `call at ${new Date(c.sentAt).toISOString()}: tx ${tx.tx.tx_id} profile ${tx.detail.profile.state}`;
    });
}

/**
 * The transaction of each call through the API: XLog points of the app's `ASYNC_SERVICE` around
 * the calls, mapped to the call whose interval holds their start, and their details (polled until
 * every profile is captured; profiles can arrive after the points). `instance` narrows the points
 * to one instance name (T-E2E-async-3's own shop).
 */
async function resolveAsyncTransactions(
  ctx: Pick<TxCheckContext, 'apiAs'>,
  appId: number,
  calls: readonly AnsweredCall[],
  instance: string | null,
  timeoutMs: number,
): Promise<AsyncTx[]> {
  const api = await viewerApi(ctx);
  const from = isoSeconds(Math.min(...calls.map((c) => c.sentAt)) - 5000);
  const to = isoSeconds(Math.max(...calls.map((c) => c.doneAt)) + 60_000);
  const found = new Map<AnsweredCall, AsyncTx>();
  await expect
    .poll(
      async () => {
        const points = await api.data<XlogPoints>(`/apps/${String(appId)}/xlog`, {
          from,
          to,
          kind: 'ALL',
          limit: 100_000,
        });
        const names = points.dictionary?.service ?? {};
        for (const [i, txId] of points.tx_id.entries()) {
          const key = points.service_id?.[i] ?? null;
          const raw = points.ts[i];
          if (key === null || names[key] !== ASYNC_SERVICE || raw === undefined) continue;
          const ts = pointIso(raw);
          const call = asyncCallOf(ts, calls);
          if (call === undefined) continue;
          // A call keeps the first transaction found with a captured profile.
          if (found.get(call)?.detail.profile.state === 'captured') continue;
          const detail = (await apiDetail(ctx, txId, ts)) as AsyncDetail;
          if (instance !== null && detail.instance.name !== instance) continue;
          found.set(call, { call, tx: { tx_id: txId, app_id: appId, ts: detail.ts }, detail });
        }
        return missingOf(calls, found);
      },
      {
        message: `the ${ASYNC_SERVICE} transactions of the async calls with captured profiles`,
        timeout: timeoutMs,
        intervals: [3000],
      },
    )
    .toEqual([]);
  return calls.map((c) => {
    const tx = found.get(c);
    if (tx === undefined) throw new Error('unreachable: every call was resolved');
    return tx;
  });
}

/** The API facts of an async transaction (T-AG-052 end to end). */
function expectAsyncProfile(found: AsyncTx, testInfo: TestInfo): void {
  const { detail: d, call } = found;
  const label = `tx ${d.tx_id} (asyncSql ${String(call.asyncSql)})`;
  expect(d.service, label).toBe(ASYNC_SERVICE);
  expect(d.profile.state, label).toBe('captured');
  expect(d.profile.step_truncated, `${label}: the profile is complete`).toBe(false);
  const sql = sqlSteps(d);
  expect(
    sql.length,
    `${label}: SQL steps of the profile >= the async SQL the app ran`,
  ).toBeGreaterThanOrEqual(call.asyncSql);
  expect(
    statements(d).size,
    `${label}: one statement per async branch (distinct SQL of the profile)`,
  ).toBeGreaterThanOrEqual(call.asyncSql);
  expect(d.sql.count, `${label}: sql.count of the transaction`).toBeGreaterThanOrEqual(
    call.asyncSql,
  );
  expect(
    stepsOutsideTransaction(d).map((s) => `#${String(s.seq)} ${s.type}`),
    `${label}: every step inside [0, ${String(d.elapsed_ms)}] ms of the transaction`,
  ).toEqual([]);
  testInfo.annotations.push({
    type: 'async-tx',
    description: `${label}: ${String(sql.length)} SQL steps, ${String(statements(d).size)} statements, sql.count ${String(d.sql.count)}, elapsed ${String(d.elapsed_ms)} ms`,
  });
}

/** One rendered row of a virtual list: its badge type and its name text. */
interface ShownRow {
  readonly type: string | null;
  readonly name: string;
}

/**
 * The name element of a row: the timeline's waterfall rows (components/waterfall/WaterfallRow.tsx)
 * and the call tree's rows (features/transaction/tabs/TreeTab.tsx: a step label or a folded run).
 */
const ROW_NAME = '.waterfall-row__name, .tx-tree-row__label, .tx-group-name';

/** Reads rows [first, first + count) of a virtual list, scrolling each into view. */
async function shownRows(list: Locator, first: number, count: number): Promise<ShownRow[]> {
  const rows: ShownRow[] = [];
  for (let index = first; index < first + count; index += 1) {
    const row = await revealVirtualRow(list, index);
    const badge = row.getByTestId('transaction-step-type').first();
    rows.push({
      type: (await badge.count()) === 0 ? null : await badge.getAttribute('data-type'),
      name: ((await row.locator(ROW_NAME).first().textContent()) ?? '').trim(),
    });
  }
  return rows;
}

/**
 * The rows of a list equal the API oracle in SQL rows: as many SQL rows as the oracle's first-sight
 * SQL rows, and the SQL texts shown = the SQL texts of the profile's SQL steps (a folded run shows
 * its first member, which has the same statement as the others).
 */
function expectSqlRows(shown: readonly ShownRow[], d: AsyncDetail, label: string): void {
  const oracle = visibleTreeRows(d.profile.steps);
  expect(shown.length, `${label}: rows = the API oracle's first-sight rows`).toBe(oracle.length);
  expect(
    shown.filter((r) => r.type === 'sql').length,
    `${label}: SQL rows = the oracle's SQL rows`,
  ).toBe(oracle.filter((r) => r.type === 'SQL').length);
  const texts = new Set(
    sqlSteps(d)
      .map((s) => s.sql?.trim())
      .filter((s): s is string => s !== undefined && s !== ''),
  );
  expect(
    new Set(shown.filter((r) => r.type === 'sql').map((r) => r.name)),
    `${label}: every SQL statement of the profile is shown by its text`,
  ).toEqual(texts);
}

/** Opens the detail on the timeline and checks summary, rows and the root bar against the API. */
async function expectTimeline(page: Page, found: AsyncTx): Promise<void> {
  const d = found.detail;
  const label = `timeline of ${d.tx_id}`;
  await openDetail(page, detailPath(found.tx));
  await expectSummaryMatches(page, d);
  const timeline = page.getByTestId('transaction-timeline');
  const oracleRows = visibleTreeRows(d.profile.steps).length;
  await expect(timeline.getByRole('treegrid')).toHaveAttribute(
    'aria-rowcount',
    String(1 + oracleRows),
  );
  expectSqlRows(await shownRows(timeline, 1, oracleRows), d, label);
  // Every step inside the transaction: the time axis is the transaction's elapsed time, so the
  // root bar spans all of it (an async step ending later would stretch the axis, docs/15 §8.3),
  // up to the 1 ms rounding stepsOutsideTransaction allows.
  const rootBar = page.getByTestId('transaction-timeline-root').getByTestId('waterfall-bar');
  await expect(rootBar).toBeVisible();
  const width = await rootBar.evaluate((el) => Number.parseFloat((el as HTMLElement).style.width));
  expect(width, `${label}: root bar width in % of the axis`).toBeGreaterThanOrEqual(
    (d.elapsed_ms / (d.elapsed_ms + 1)) * 100 - 1e-6,
  );
  await expect(timeline.locator('[data-testid="waterfall-bar"][data-clipped="true"]')).toHaveCount(
    0,
  );
}

test.describe('async work of the seed traffic', () => {
  test(
    'T-E2E-async-1 executor, CompletableFuture and @Async SQL show inside the request transaction: steps = the app, timeline and summary = the API',
    { annotation: req('T-AG-052', 'T-AG-051', 'F-U02', '05-2.2-1', '05-2.2-3') },
    async ({ page, seed, apiAs }, testInfo) => {
      test.slow();
      const calls = answeredCalls(seed);
      const found = await resolveAsyncTransactions(
        { apiAs },
        seed.traffic.app_id,
        calls,
        null,
        90_000,
      );
      for (const tx of found) {
        expectAsyncProfile(tx, testInfo);
        await expectTimeline(page, tx);
      }
    },
  );

  test(
    'T-E2E-async-2 the call tree lists the API rows with the SQL statement of every async branch',
    { annotation: req('T-AG-052', 'F-U02', '05-2.2-4') },
    async ({ page, seed, apiAs }) => {
      test.slow();
      const found = await resolveAsyncTransactions(
        { apiAs },
        seed.traffic.app_id,
        answeredCalls(seed),
        null,
        90_000,
      );
      for (const tx of found) {
        const d = tx.detail;
        await openDetail(page, detailPath(tx.tx, { tab: 'tree' }));
        const tree = page.getByTestId('transaction-tree');
        const oracleRows = visibleTreeRows(d.profile.steps).length;
        await expect(tree.getByRole('treegrid')).toHaveAttribute(
          'aria-rowcount',
          String(oracleRows),
        );
        expectSqlRows(await shownRows(tree, 0, oracleRows), d, `call tree of ${d.tx_id}`);
      }
    },
  );
});

test(
  'T-E2E-async-3 virtual threads: CPU and allocation "Not measurable", the virtual thread badge, thread tomcat-handler-, async SQL in the transaction',
  { annotation: req('T-ACC-23', 'T-AG-054', 'D-23', 'F-A30', 'T-UI-007') },
  async ({ page, session, seed, apiAs }, testInfo) => {
    test.setTimeout(6 * 60_000);
    const problem = shopProblem(session.run_id);
    test.skip(problem !== null, problem ?? '');
    const shop = await startShop(session.run_id, {
      instance: VT_INSTANCE,
      logFile: 'async-vt-shop-boot3.log',
      env: { SAMPLE_VT: 'true' },
      jvmArgs: profileKeptProperties(),
    });
    try {
      const sentAt = Date.now();
      const response = await httpGet(`${shop.url}${ASYNC_PATH}`, {}, 60_000);
      const doneAt = Date.now();
      expect(response.status, `GET ${ASYNC_PATH} on the virtual-thread shop`).toBe(200);
      const answer = parseAsyncAnswer(response.text);
      expect(answer.problem, 'the async answer of the sample app contract').toBeNull();
      const call: AnsweredCall = { sentAt, doneAt, asyncSql: answer.asyncSql ?? 0 };
      const [found] = await resolveAsyncTransactions(
        { apiAs },
        seed.traffic.app_id,
        [call],
        VT_INSTANCE,
        120_000,
      );
      if (found === undefined) throw new Error('unreachable: the call was resolved');
      const d = found.detail;
      // D-23 / T-ACC-23: not measurable on a virtual thread, null in the API, never 0.
      expect(d.flags.virtual_thread, 'flags.virtual_thread').toBe(true);
      expect(d.measured, 'measured cpu and alloc').toEqual({ cpu: false, alloc: false });
      expect(d.cpu_time_ms, 'cpu_time_ms').toBeNull();
      expect(d.alloc_bytes, 'alloc_bytes').toBeNull();
      // 10 U-16: Boot's virtual-thread Tomcat names its threads tomcat-handler-<n>, normalized.
      expect(d.thread ?? '', 'normalized thread name').toMatch(/^tomcat-handler-/);
      expectAsyncProfile(found, testInfo);
      await openDetail(page, detailPath(found.tx));
      await expectSummaryMatches(page, d);
      for (const name of ['cpu', 'alloc']) {
        await expect(page.getByTestId(`transaction-field-${name}`)).toContainText(
          t('common:component.missing.notMeasurable'),
        );
      }
      await expect(page.getByTestId('transaction-flag-virtualThread')).toBeVisible();
      await expect(page.getByTestId('transaction-field-thread')).toContainText('tomcat-handler-');
      testInfo.annotations.push({
        type: 'async-vt',
        description: `tx ${d.tx_id} on ${String(d.thread)}, virtualPerTaskThread ${JSON.stringify(answer.virtualPerTaskThread)}`,
      });
    } finally {
      await stopProcess(shop.child);
    }
  },
);
