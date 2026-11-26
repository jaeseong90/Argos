// SPDX-License-Identifier: Apache-2.0
//
// T-SEC-62 (docs/06 SEC-62, docs/16 §9.4~9.5, docs/18 §4.3 and §13, WO-46): the XSS seed sweep of the
// stack. Collected data with the XSS payloads of docs/18 §4.3 (sample-apps/common Seeds.XSS) and
// docs/16 §9.4 reaches the screens only as text:
//
// - (a) T-SEC-62 locations: every XSS location of e2e/fixtures/xssSeed.ts XSS_LOCATIONS carries a
//   payload in the stack's API data (service path, query string, header, user id, exception
//   message, thread name, external call path, batch job name, deploy event title); the SQL literal
//   and bind location is normalized by design (the stored SQL holds no payload). A missing one fails
//   naming the location. The payloads come from the seed traffic (e2e/stack/seed/traffic.ts), the
//   sweep's own applications (e2e/fixtures/xssApps.ts: shop-boot3 and shop-batch with the captures
//   that keep a payload) and the command feed's thread dump (e2e/fixtures/commandFeed.ts).
// - (b) T-SEC-62 text: for each stored location a screen shows the API's value as exactly that text
//   (getByText exact), with no element or attribute made of it.
// - (c) T-SEC-62 routes: every shell route of e2e/specs/routes.ts is opened as an administrator,
//   once or for each swept application (primary generator app, seed traffic app, the sweep's
//   shop app), with every tab of the transaction and instance details, the alert deep link and a
//   search for a payload. The page hooks of every stack test (e2e/fixtures/fixtures.ts) assert that
//   window.__xss stays undefined and that no dialog, page error, console error or CSP violation
//   happened; the sweep adds the absence of injected elements and attributes on every visit.
// - (d) coverage: a route of routes.ts without an entry in XSS_ROUTE_PLAN fails (also the tooling
//   test e2e/fixtures/xssSeed.test.ts).
// - (e) the server's CSP report counter does not grow over the sweep beyond the reports the
//   positive controls of csp-report.spec.ts may cause in the same run (one-sided, ADR-0010 C-188).
//
// Inside the Playwright image of e2e:docker there is no JVM: the locations of the sweep's own
// applications and the command feed are skipped with the reason there (run it with e2e:stack).
import { expect as baseExpect, type Page } from '@playwright/test';

import { ensureCommandFeed, SKIP_NO_COMMAND_FEED, stopCommandFeed } from '../fixtures/commandFeed';
import { chart } from '../fixtures/dashboardOracle';
import { apiAs as loginAs, expect, test } from '../fixtures/stack';
import { expectShell, rendered } from '../fixtures/ui';
import { ensureXssApps, xssAppsProblem, type XssApp, type XssApps } from '../fixtures/xssApps';
import {
  INJECTED_SELECTOR,
  payloadStrings,
  planCoverage,
  sweepPath,
  XSS_LOCATIONS,
  XSS_MARKER,
  XSS_ROUTE_PLAN,
  type RouteParams,
} from '../fixtures/xssSeed';
import { commandResult, envDumpOf, runCommandApi, sharedDump } from '../required/dumpsChecks';
import type { ApiDetail, ApiStep } from '../required/transactionChecks';
import type { ApiSession } from '../stack/api';
import { judgeCspCounter, readCspControl, readStackCspCounter } from '../stack/cspCounter';
import type { SeedManifest } from '../stack/manifest';

import { SHELL_ROUTES } from './routes';

const REQ = [
  { type: 'req', description: 'SEC-62' },
  { type: 'req', description: 'SEC-60' },
  { type: 'req', description: 'SEC-46' },
];

test.use({ role: 'admin' });

// ---------------------------------------------------------------------------------------------
// The sweep's data (lazily, once per worker)
// ---------------------------------------------------------------------------------------------

interface XlogColumns {
  readonly tx_id: string[];
  readonly ts: (string | number)[];
  readonly service_id: (string | null)[];
  readonly dictionary: { readonly service: Record<string, string> };
}

/** The first transaction of `service` in the app's range, as the administrator reads it. */
async function detailOf(
  api: ApiSession,
  app: { readonly app_id: number; readonly from: string; readonly to: string },
  service: string,
): Promise<ApiDetail | null> {
  const points = await api.data<XlogColumns>(`/apps/${String(app.app_id)}/xlog`, {
    from: app.from,
    to: app.to,
    kind: 'ALL',
    limit: 100_000,
  });
  for (let i = 0; i < points.tx_id.length; i += 1) {
    const name = points.dictionary.service[points.service_id[i] ?? ''] ?? '';
    if (name !== service) {
      continue;
    }
    const raw = points.ts[i] ?? '';
    const ts = typeof raw === 'number' ? new Date(raw).toISOString() : raw;
    return api.data<ApiDetail>(`/transactions/${points.tx_id[i] ?? ''}`, {
      ts: ts.replace(/\.0+Z$/, 'Z'),
      app: app.app_id,
    });
  }
  return null;
}

/** How a screen shows the API's value of a location (b). */
type Screen =
  | {
      readonly kind: 'transaction';
      readonly appId: number;
      readonly d: ApiDetail;
      readonly tab: string;
    }
  | {
      readonly kind: 'error-group';
      readonly appId: number;
      readonly groupId: string;
      readonly from: string;
      readonly to: string;
    }
  | {
      readonly kind: 'dump';
      readonly appId: number;
      readonly instanceId: number;
      readonly commandId: string;
    }
  | {
      readonly kind: 'event';
      readonly appId: number;
      readonly eventId: string;
      readonly from: string;
      readonly to: string;
    };

/** What the sweep found of one location. */
interface Evidence {
  readonly id: string;
  /** The payload as the API answers it (null: not found). */
  readonly value: string | null;
  /** Where it was read (or why it is not there). */
  readonly where: string;
  /** The screen that shows it (null for the normalized SQL location). */
  readonly screen: Screen | null;
  /** Why the location could not be checked here (e2e:docker), or null. */
  readonly skipped: string | null;
}

interface SweepData {
  readonly apps: XssApps | null;
  readonly appsProblem: string | null;
  readonly evidence: readonly Evidence[];
}

let sweep: { readonly runId: string; readonly data: Promise<SweepData> } | null = null;

function sweepData(
  seed: SeedManifest,
  apiAs: (role: 'admin' | 'operator' | 'viewer') => Promise<ApiSession>,
  log: (line: string) => void,
): Promise<SweepData> {
  if (sweep?.runId === seed.run_id) {
    return sweep.data;
  }
  const data = collect(seed, apiAs, log);
  const entry = { runId: seed.run_id, data };
  sweep = entry;
  data.catch(() => {
    if (sweep === entry) {
      sweep = null;
    }
  });
  return data;
}

function txScreen(d: ApiDetail, tab: string): Screen {
  return { kind: 'transaction', appId: d.app.app_id, d, tab };
}

/** The first string with the payload marker of `values`, or null. */
function firstPayload(values: readonly (string | null | undefined)[]): string | null {
  return values.find((v): v is string => typeof v === 'string' && v.includes(XSS_MARKER)) ?? null;
}

async function collect(
  seed: SeedManifest,
  apiAs: (role: 'admin' | 'operator' | 'viewer') => Promise<ApiSession>,
  log: (line: string) => void,
): Promise<SweepData> {
  const admin = await apiAs('admin');
  const appsProblem = xssAppsProblem(seed.run_id);
  const apps =
    appsProblem === null ? await ensureXssApps(seed.run_id, () => apiAs('admin'), log) : null;
  const evidence: Evidence[] = [];
  const skip = (id: string, reason: string): void => {
    evidence.push({ id, value: null, where: reason, screen: null, skipped: reason });
  };
  const add = (id: string, value: string | null, where: string, screen: Screen | null): void => {
    evidence.push({ id, value, where, screen, skipped: null });
  };

  if (apps === null) {
    const reason = appsProblem ?? 'no XSS seed apps';
    for (const id of [
      'query_string',
      'header',
      'user_id',
      'sql',
      'exception_message',
      'external_path',
      'batch_job_name',
    ]) {
      skip(id, reason);
    }
    // The seed traffic's own XSS transaction (service path) is there without a JVM.
    const tx = seed.tx.xss;
    if (tx === null) {
      add(
        'service_path',
        null,
        'seed.tx.xss is null (no XSS transaction in the seed traffic)',
        null,
      );
    } else {
      const d = await admin.data<ApiDetail>(`/transactions/${tx.tx_id}`, {
        ts: tx.ts,
        app: tx.app_id,
      });
      add(
        'service_path',
        d.url?.includes(XSS_MARKER) === true ? d.url : null,
        `seed traffic tx ${tx.tx_id} url`,
        txScreen(d, 'timeline'),
      );
    }
  } else {
    const shop = apps.shop;
    const path = await detailOf(admin, shop, 'GET /seed/xss/**');
    add(
      'service_path',
      firstPayload([path?.url]),
      `${shop.name}: GET /seed/xss/** url`,
      path === null ? null : txScreen(path, 'timeline'),
    );
    const query = await detailOf(admin, shop, 'GET /seed/xss-query');
    add(
      'query_string',
      firstPayload([query?.url]),
      `${shop.name}: GET /seed/xss-query url`,
      query === null ? null : txScreen(query, 'timeline'),
    );
    const header = await detailOf(admin, shop, 'GET /seed/xss-header');
    add(
      'header',
      firstPayload(Object.values(header?.headers ?? {})),
      `${shop.name}: GET /seed/xss-header headers`,
      header === null ? null : txScreen(header, 'headers'),
    );
    const user = await detailOf(admin, shop, 'GET /seed/xss-user');
    add(
      'user_id',
      firstPayload([user?.user_id]),
      `${shop.name}: GET /seed/xss-user user_id`,
      user === null ? null : txScreen(user, 'timeline'),
    );
    const ext = await detailOf(admin, shop, 'GET /seed/xss-ext');
    add(
      'external_path',
      firstPayload((ext?.profile.steps ?? []).map((s) => s.url)),
      `${shop.name}: GET /seed/xss-ext external step url`,
      ext === null ? null : txScreen(ext, 'ext'),
    );
    // SQL: normalized by design. The location holds the seed's SQL steps, and nothing of the
    // transaction detail or the SQL statistics carries the payload.
    const sql = await detailOf(admin, shop, 'GET /seed/xss-sql');
    const sqlSteps = (sql?.profile.steps ?? []).filter((s: ApiStep) => s.type === 'SQL');
    const sqlStats = await admin.data<{ sql_normalized: string }[]>(
      `/apps/${String(shop.app_id)}/sql/stats`,
      { from: shop.from, to: shop.to, limit: 500 },
    );
    add(
      'sql',
      sql !== null &&
        sqlSteps.length > 0 &&
        payloadStrings(sql).length === 0 &&
        payloadStrings(sqlStats).length === 0
        ? `${String(sqlSteps.length)} SQL steps, normalized`
        : null,
      `${shop.name}: GET /seed/xss-sql (${String(sqlSteps.length)} SQL steps; payload strings in detail ${String(sql === null ? 'n/a' : payloadStrings(sql).length)}, in SQL statistics ${String(payloadStrings(sqlStats).length)})`,
      null,
    );
    const groups = await admin.data<{ group_id: string; message_pattern: string | null }[]>(
      `/apps/${String(shop.app_id)}/errors/groups`,
      { from: shop.from, to: shop.to, limit: 500 },
    );
    const group = groups.find((g) => g.message_pattern?.includes(XSS_MARKER) === true);
    add(
      'exception_message',
      group?.message_pattern ?? null,
      `${shop.name}: error groups message_pattern`,
      group === undefined
        ? null
        : {
            kind: 'error-group',
            appId: shop.app_id,
            groupId: group.group_id,
            from: shop.from,
            to: shop.to,
          },
    );
    if (apps.batch === null) {
      add('batch_job_name', null, apps.batch_problem ?? 'no batch app', null);
    } else {
      const batch = await detailOf(admin, apps.batch, 'POST /seed/xss-batch');
      add(
        'batch_job_name',
        firstPayload((batch?.profile.steps ?? []).map((s) => s.name)),
        `${apps.batch.name}: POST /seed/xss-batch step names`,
        batch === null ? null : txScreen(batch, 'timeline'),
      );
    }
  }

  // Thread name: the command feed's thread dump (a protocol-level agent of the stack's collector).
  const feed = await ensureCommandFeed(seed, apiAs).catch((error: unknown) => {
    throw new Error(`command feed: ${error instanceof Error ? error.message : String(error)}`);
  });
  if (feed === null) {
    skip('thread_name', SKIP_NO_COMMAND_FEED);
  } else {
    const dump = await sharedDump({ seed, apiAs }, feed, 'normal');
    const names = (dump.result.threads ?? []).map((t) => t.name);
    const name =
      names.find((n) => n === feed.commands.xss_thread && n.includes(XSS_MARKER)) ?? null;
    add(
      'thread_name',
      name,
      `command feed ${feed.appName}: thread dump ${dump.commandId} threads[].name`,
      { kind: 'dump', appId: feed.appId, instanceId: dump.instanceId, commandId: dump.commandId },
    );
  }

  // Deploy event title: the primary app's seed event, as GET /events lists it.
  const primary = seed.primary_app_id;
  const { start, end } = seed.generator.range;
  const events = await admin.data<{ event_id: string; title: string | null }[]>(
    `/apps/${String(primary)}/events`,
    { from: start, to: end },
  );
  const event = events.find((e) => e.title?.includes(XSS_MARKER) === true);
  add(
    'deploy_event_title',
    event?.title ?? null,
    `app ${String(primary)}: GET /events title`,
    event === undefined
      ? null
      : { kind: 'event', appId: primary, eventId: event.event_id, from: start, to: end },
  );
  await admin.logout();
  return { apps, appsProblem, evidence };
}

// ---------------------------------------------------------------------------------------------
// Page checks
// ---------------------------------------------------------------------------------------------

/** No element or attribute of the page exists only because collected text was parsed as markup. */
async function expectNothingInjected(page: Page, where: string): Promise<void> {
  expect(
    await page.locator(INJECTED_SELECTOR).count(),
    `${where}: injected elements or attributes`,
  ).toBe(0);
  expect(
    await page.evaluate(() => (window as { __xss?: unknown }).__xss),
    `${where}: window.__xss`,
  ).toBeUndefined();
}

/** Waits until the page is quiet (network idle, bounded) and has rendered. */
async function settle(page: Page): Promise<void> {
  // Screens poll (live refresh), so the network is rarely idle: wait (bounded) until no screen part
  // says it is loading, then for two frames.
  await page.waitForLoadState('load');
  await baseExpect
    .poll(() => page.locator('[data-state="loading"], [aria-busy="true"]').count(), {
      timeout: 10_000,
    })
    .toBe(0)
    .catch(() => undefined);
  await rendered(page);
}

/** Scrolls a virtual list until an element with exactly `text` is in the DOM. */
async function revealText(page: Page, listTestId: string, text: string): Promise<void> {
  const target = page.getByText(text, { exact: true });
  const viewport = page.getByTestId(listTestId).locator('.virtual-rows__viewport').first();
  for (let i = 0; i < 200 && (await target.count()) === 0; i += 1) {
    const moved = await viewport
      .evaluate((el) => {
        const before = el.scrollTop;
        el.scrollTop = before + el.clientHeight / 2;
        return el.scrollTop !== before;
      })
      .catch(() => false);
    await rendered(page);
    if (!moved) {
      break;
    }
  }
}

/** Opens the screen of a location and expects the value as exactly that text. */
async function expectShownAsText(page: Page, e: Evidence): Promise<void> {
  const value = e.value ?? '';
  const screen = e.screen;
  if (screen === null) {
    throw new Error(`${e.id}: no screen`);
  }
  switch (screen.kind) {
    case 'transaction': {
      const params = new URLSearchParams({
        ts: new Date(Date.parse(screen.d.ts)).toISOString(),
        tab: screen.tab,
      });
      await page.goto(
        `/apps/${String(screen.appId)}/transactions/${screen.d.tx_id}?${params.toString()}`,
      );
      await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
        timeout: 30_000,
      });
      await rendered(page);
      if (screen.tab === 'timeline' && e.id === 'batch_job_name') {
        await revealText(page, 'transaction-timeline', value);
      }
      break;
    }
    case 'error-group':
      await page.goto(
        `/apps/${String(screen.appId)}/stats/errors/${screen.groupId}?${new URLSearchParams({ from: screen.from, to: screen.to }).toString()}`,
      );
      await expect(page.getByTestId('stats-error-detail')).toHaveAttribute('data-state', 'ready', {
        timeout: 30_000,
      });
      break;
    case 'dump':
      await page.goto(
        `/apps/${String(screen.appId)}/instances/${String(screen.instanceId)}?tab=dumps&cmd=${screen.commandId}`,
      );
      await expect(page.getByTestId('dump-viewer')).toHaveAttribute(
        'data-command-id',
        screen.commandId,
        { timeout: 60_000 },
      );
      await page.getByTestId('dump-search').fill(XSS_MARKER);
      await rendered(page);
      break;
    case 'event': {
      await page.goto(
        `/apps/${String(screen.appId)}/dashboard?${new URLSearchParams({ from: screen.from, to: screen.to, alert: screen.eventId }).toString()}`,
      );
      await expect(page.getByTestId('dashboard-page')).toHaveAttribute('data-state', 'ready', {
        timeout: 30_000,
      });
      // The marker's tooltip lists the event title (T-E2E-085 opens it the same way).
      const marker = chart(page, 'tps').locator(
        `[data-testid="chart-marker"][data-marker-id="${screen.eventId}"]`,
      );
      await expect(marker).toHaveCount(1);
      await marker.hover({ timeout: 15_000 });
      await expect(page.locator('.chart-marker-tip').filter({ visible: true })).toHaveCount(1);
      break;
    }
  }
  await baseExpect(
    page.getByText(value, { exact: true }).first(),
    `${e.id}: the API's value as exactly this text on the screen: ${value}`,
  ).toBeAttached({ timeout: 15_000 });
  await expectNothingInjected(page, e.id);
}

// ---------------------------------------------------------------------------------------------
// (d) coverage
// ---------------------------------------------------------------------------------------------

// Checked when the file loads: a route of routes.ts without a plan entry (or an entry of no route)
// stops the whole spec before any test runs. The tooling test xssSeed.test.ts checks the same.
const coverage = planCoverage(SHELL_ROUTES.map((r) => r.pattern));
if (coverage.unplanned.length > 0 || coverage.stale.length > 0) {
  throw new Error(
    `T-SEC-62 coverage: routes of e2e/specs/routes.ts the XSS sweep does not visit: [${coverage.unplanned.join(', ')}]; plan entries of no route: [${coverage.stale.join(', ')}] (e2e/fixtures/xssSeed.ts XSS_ROUTE_PLAN)`,
  );
}

// ---------------------------------------------------------------------------------------------
// (a) locations and (b) text
// ---------------------------------------------------------------------------------------------

test.describe('XSS seed locations', () => {
  test.afterAll(async ({ session, seed }, testInfo) => {
    testInfo.setTimeout(3 * 60_000);
    await stopCommandFeed(() => loginAs(session, seed, 'operator'));
  });

  test(
    'T-SEC-62 (a) every XSS location of 18 §4.3 and 16 §9.4 carries a payload in the stack API data (SQL: normalized, no payload stored)',
    { tag: '@security', annotation: REQ },
    async ({ page, seed, apiAs }, testInfo) => {
      testInfo.setTimeout(15 * 60_000);
      // Every stack test opens the UI (its page hooks need a document): the overview.
      await page.goto('/overview');
      await expectShell(page);
      const lines: string[] = [];
      const data = await sweepData(seed, apiAs, (line) => lines.push(line));
      await testInfo.attach('xss-seed.log', { body: lines.join('\n'), contentType: 'text/plain' });
      expect(data.evidence.map((e) => e.id).sort(), 'every location resolved').toEqual(
        XSS_LOCATIONS.map((l) => l.id).sort(),
      );
      for (const e of data.evidence) {
        testInfo.annotations.push({
          type: `xss-${e.id}`,
          description:
            e.skipped === null ? `${e.where}: ${String(e.value)}` : `skipped: ${e.skipped}`,
        });
      }
      const missing = data.evidence
        .filter((e) => e.skipped === null && e.value === null)
        .map((e) => `${e.id} (${e.where})`);
      expect(missing, 'XSS locations without a payload in the API data (a seed defect)').toEqual(
        [],
      );
      const skipped = data.evidence.filter((e) => e.skipped !== null);
      test.skip(
        skipped.length > 0,
        `locations not checkable here: ${skipped.map((e) => `${e.id}: ${String(e.skipped)}`).join('; ')}`,
      );
    },
  );

  for (const location of XSS_LOCATIONS.filter((l) => l.stored === 'verbatim')) {
    test(
      `T-SEC-62 (b) ${location.id}: a screen shows the API's payload as exact text, nothing injected`,
      {
        tag: '@security',
        annotation: [...REQ, { type: 'xss-source', description: location.source }],
      },
      async ({ page, seed, apiAs }, testInfo) => {
        testInfo.setTimeout(10 * 60_000);
        const data = await sweepData(seed, apiAs, () => undefined);
        const e = data.evidence.find((x) => x.id === location.id);
        const skipped = e === undefined ? null : e.skipped;
        test.skip(skipped !== null, String(skipped));
        if (e === undefined || e.value === null) {
          throw new Error(`${location.id}: no payload in the API data (${String(e?.where)})`);
        }
        await expectShownAsText(page, e);
      },
    );
  }

  test(
    'T-SEC-62 (b) environment dump viewer: the XSS values of the command feed environment are exact text, nothing injected',
    { tag: '@security', annotation: REQ },
    async ({ page, seed, apiAs }, testInfo) => {
      testInfo.setTimeout(10 * 60_000);
      const feed = await ensureCommandFeed(seed, apiAs);
      test.skip(feed === null, SKIP_NO_COMMAND_FEED);
      if (feed === null) return;
      const admin = await apiAs('admin');
      const instanceId = feed.instanceIds.normal;
      const view = await runCommandApi(admin, admin, instanceId, 'env_dump');
      expect(view.status, `env_dump: ${String(view.error)}`).toBe('done');
      const dump = envDumpOf((await commandResult(admin, view.command_id, 'text')).text ?? '');
      const seeded = Object.values(dump.env).filter((v) => v.includes(XSS_MARKER));
      expect(seeded.length, 'XSS payloads among the environment values').toBeGreaterThan(0);
      await page.goto(`/apps/${String(feed.appId)}/instances/${String(instanceId)}?tab=env`);
      const panel = page.getByTestId('latest-env_dump');
      await expect(panel).toHaveAttribute('data-command-id', view.command_id, { timeout: 30_000 });
      await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      for (const value of seeded) {
        await baseExpect(
          panel.getByText(value, { exact: true }).first(),
          `environment value as exactly this text: ${value}`,
        ).toBeAttached();
      }
      await expectNothingInjected(page, 'environment dump');
      await admin.logout();
    },
  );
});

// ---------------------------------------------------------------------------------------------
// (c) every route, (e) CSP counter
// ---------------------------------------------------------------------------------------------

type SweptApp = 'primary' | 'traffic' | 'xss';

/** The route parameters of a swept application, from the API (ids of rows with payloads first). */
async function routeParams(
  seed: SeedManifest,
  api: ApiSession,
  which: SweptApp,
  xss: XssApp | null,
): Promise<RouteParams | null> {
  let app: { app_id: number; from: string; to: string };
  if (which === 'primary') {
    app = {
      app_id: seed.primary_app_id,
      from: seed.generator.range.start,
      to: seed.generator.range.end,
    };
  } else if (which === 'traffic') {
    app = {
      app_id: seed.traffic.app_id,
      from: seed.traffic.range.start,
      to: seed.traffic.range.end,
    };
  } else if (xss === null) {
    return null;
  } else {
    app = { app_id: xss.app_id, from: xss.from, to: xss.to };
  }
  const q = { from: app.from, to: app.to, limit: 500 };
  const base = `/apps/${String(app.app_id)}`;
  const services = await api.data<{ service_id: string; service: string }[]>(
    `${base}/services/stats`,
    { ...q, kind: 'ALL' },
  );
  const sqls = await api.data<{ sql_id: string }[]>(`${base}/sql/stats`, q);
  const hosts = await api.data<{ host_id: string }[]>(`${base}/external/stats`, q);
  const groups = await api.data<{ group_id: string; message_pattern: string | null }[]>(
    `${base}/errors/groups`,
    q,
  );
  const instances = await api.data<{ instance_id: number }[]>(`${base}/instances`, {
    archived: true,
  });
  const live = await api.data<{ instance_id: number }[]>(`${base}/instances`);
  const pick = <T>(rows: readonly T[], has: (row: T) => boolean): T | undefined =>
    rows.find(has) ?? rows[0];
  const service = pick(services, (s) => s.service.includes('/seed/xss'));
  const group = pick(groups, (g) => g.message_pattern?.includes(XSS_MARKER) === true);
  const tx =
    which === 'primary'
      ? seed.tx.captured_sql_stack
      : which === 'traffic'
        ? (seed.tx.xss ?? seed.tx.captured_sql_stack)
        : null;
  let txId = tx?.tx_id ?? '';
  let txTs = tx?.ts ?? '';
  if (which === 'xss' && xss !== null) {
    const d = await detailOf(api, xss, 'GET /seed/xss-header');
    txId = d?.tx_id ?? '';
    txTs = d?.ts ?? '';
  }
  return {
    appId: app.app_id,
    txId,
    txTs: txTs === '' ? '' : new Date(Date.parse(txTs)).toISOString(),
    instanceId: (live[0] ?? instances[0])?.instance_id ?? 0,
    serviceId: service?.service_id ?? '0',
    sqlId: sqls[0]?.sql_id ?? '0',
    hostId: hosts[0]?.host_id ?? '0',
    groupId: group?.group_id ?? '0',
    traceId: seed.tx.trace_root_two_apps.trace_id,
    traceTs: new Date(Date.parse(seed.tx.trace_root_two_apps.ts)).toISOString(),
    ruleId: seed.alert?.rule_id ?? '0',
    from: app.from,
    to: app.to,
  };
}

/** The extra query of a few routes: the alert deep link, a search for a payload. */
function extraQuery(
  pattern: string,
  seed: SeedManifest,
  p: RouteParams,
  xss: XssApp | null,
): string {
  if (pattern === '/alerts' && seed.alert != null) {
    return `alert=${encodeURIComponent(seed.alert.event_id)}`;
  }
  if (pattern === '/search') {
    // A payload as the search text (it is echoed in the box and the empty state), over the sweep
    // app's range when it exists: user:<payload> lists the transactions of that user id.
    const payload = '<img src=x onerror="window.__xss=1">ARGOS_SEED';
    const params = new URLSearchParams({ q: xss === null ? payload : `user:${payload}` });
    if (xss !== null) {
      params.set('from', xss.from);
      params.set('to', xss.to);
    } else {
      params.set('from', p.from);
      params.set('to', p.to);
    }
    return params.toString();
  }
  return '';
}

let cspStart: number | null = null;
let cspProblem: string | null = null;

test.describe('XSS seed sweep of every route', () => {
  test.beforeAll(async ({ session }) => {
    const reading = await readStackCspCounter(session.management_url, session.run_id);
    cspStart = reading.value;
    cspProblem = reading.problem;
  });

  test(
    'T-SEC-62 (c) positive control: the injected-element check finds the handler attributes, frames, javascript: links and nonce-less scripts that parsed payloads would make',
    { tag: '@security', annotation: REQ },
    async ({ page, browser }) => {
      // The stack page itself stays clean (its hooks need a document)...
      await page.goto('/overview');
      await expectShell(page);
      await expectNothingInjected(page, '/overview');
      // ...and the selector finds what parsed payloads make, on a page of its own context (no
      // CSP there; nothing runs: no src, no navigation, scripting of the content off).
      const control = await browser.newContext({ javaScriptEnabled: false });
      try {
        const other = await control.newPage();
        await other.setContent(
          [
            '<p>plain <b>text</b> <svg aria-hidden="true"></svg> <a href="/overview">link</a></p>',
            '<img onerror="window.__xss=1">',
            '<svg onload="window.__xss=1"></svg>',
            '<iframe srcdoc="x"></iframe>',
            `<a href="${['Java', 'Script:'].join('')}window.__xss=1">x</a>`,
            '<script>window.__xss=1</script>',
          ].join(''),
        );
        expect(await other.locator(INJECTED_SELECTOR).count(), 'the five injected parts').toBe(5);
      } finally {
        await control.close();
      }
    },
  );

  const plan = Object.entries(XSS_ROUTE_PLAN);
  for (const [pattern, visit] of plan) {
    const apps: readonly (SweptApp | null)[] =
      visit.scope === 'once' ? [null] : ['primary', 'traffic', 'xss'];
    for (const which of apps) {
      test(
        `T-SEC-62 (c) ${pattern}${which === null ? '' : ` (${which} app)`}${visit.tabs === undefined ? '' : ' with every tab'}: no payload runs, nothing injected`,
        { tag: '@security', annotation: REQ },
        async ({ page, seed, apiAs }, testInfo) => {
          testInfo.setTimeout(5 * 60_000);
          const data = await sweepData(seed, apiAs, () => undefined);
          const xss = data.apps?.shop ?? null;
          test.skip(which === 'xss' && xss === null, `no sweep app: ${String(data.appsProblem)}`);
          const admin = await apiAs('admin');
          const p = await routeParams(seed, admin, which ?? 'primary', xss);
          await admin.logout();
          if (p === null) {
            throw new Error(`no route parameters for the ${String(which)} app`);
          }
          const tabs = visit.tabs ?? [undefined];
          for (const tab of tabs) {
            let url = sweepPath(pattern, p, tab);
            const extra = extraQuery(pattern, seed, p, xss);
            if (extra !== '') {
              url += `${url.includes('?') ? '&' : '?'}${extra}`;
            }
            await page.goto(url);
            if (pattern !== '/login' && pattern !== '/setup') {
              await expectShell(page);
            }
            await settle(page);
            await expectNothingInjected(page, url);
          }
        },
      );
    }
  }

  test(
    'T-SEC-62 (e) the server CSP report counter did not grow over the sweep (beyond the positive controls of this run)',
    { tag: '@security', annotation: REQ },
    async ({ page, session }, testInfo) => {
      // Every stack test opens the UI (its page hooks need a document).
      await page.goto('/overview');
      await expectShell(page);
      const end = await readStackCspCounter(session.management_url, session.run_id);
      const increase = end.value === null || cspStart === null ? null : end.value - cspStart;
      const verdict = judgeCspCounter(
        increase,
        cspProblem ?? end.problem,
        readCspControl(session.run_id),
      );
      testInfo.annotations.push({
        type: 'csp-counter',
        description:
          verdict.kind === 'unread'
            ? verdict.reason
            : `increase ${String(increase)} over the sweep`,
      });
      expect(
        verdict.kind === 'violations' ? verdict.increase : 0,
        'argos_csp_violations_total growth over the XSS sweep',
      ).toBe(0);
    },
  );
});
