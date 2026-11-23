// SPDX-License-Identifier: Apache-2.0
//
// The statistics screens on the real stack (WO-36, docs/05 §2.5, docs/15 §9.7, docs/18 §13):
//
//   - T-E2E-stats-1..: the docs/05 §2.5 checklist of required/stats.yaml, declared by the shared
//     required-elements engine (specs/required.spec.ts) with the checks of required/statsChecks.ts.
//   - T-E2E-stats-20..23: each STATISTICS item of the side menu opens its real screen (no
//     placeholder) on the seeded data, equal to the API, at 1366x768 without a horizontal scroll,
//     axe Critical 0, with an evidence screenshot (stats-<screen>.png).
//   - T-E2E-stats-24: list → detail → back by keyboard with the range kept.
//   - T-E2E-stats-25..27: the automatic parts of T-ACPT-03 (SQL Top N of a day, complete counts,
//     batch excluded), T-ACPT-11 (timeouts per host) and T-ACPT-01's last hop (XLog → detail → SQL
//     statistics with the range).
//   - T-E2E-stats-28: the seeded XSS service name is text in the services table.
//   - T-E2E-service-version-1/2 (T-ACPT-04): p95 per version and deploy markers = API, the version
//     split of the list.
//   - T-E2E-errors-1..3 (T-ACPT-13): a new group's representative stack within three clicks of the
//     side menu, the new list and the distributions = API, the last 10 transactions open details.
//   - T-E2E-stats-30..36: screenshots of the loading, empty, error and ready states (pinned Linux
//     image only, e2e:docker).
//   - T-E2E-stats-37: agent-classified TIMEOUT and CONNECT_FAIL of the traffic agent's hop calls in
//     the external statistics (WO-16, ADR-0010 C-223 (7)), DOM = API.
//   - T-E2E-stats-38..39, T-E2E-errors-4: test.fixme for what has no data or screen yet, each
//     naming its owner.
//
// Every expected value comes from the API asked inside the test; no fixed dates or ids; retries 0;
// the page hooks of fixtures/stack.ts (XSS, console, CSP, origins) run after each test.
import type { Page, TestInfo } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import {
  apiEvents,
  apiStatsDetail,
  apiStatsList,
  DETAIL_ROOT,
  expectKeys,
  expectStatsState,
  hrefOf,
  LIST_ROOT,
  listQueryOf,
  openRow,
  ORACLE_ROLE,
  rowMismatches,
  shownRows,
  squash,
  statsDetailPath,
  statsPath,
  statsTable,
  STATS_SCREENS,
  tableRow,
  tableRows,
  type ErrorGroup,
  type ExternalStats,
  type SqlStats,
  type StatsScreen,
} from '../fixtures/statsOracle';
import { expectShell, expectUrl, horizontalOverflow } from '../fixtures/ui';
import {
  expectBreakdown,
  expectCodeText,
  expectErrorDetail,
  expectListMatchesApi,
  expectNewFilter,
  expectRowsShown,
  expectServiceDetail,
  LISTS,
  serviceWithMostVersions,
} from '../required/statsChecks';
import { apiDetail, backOf } from '../required/transactionChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import type { SeedManifest } from '../stack/manifest';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const FEATURE: Readonly<Record<StatsScreen, string>> = {
  services: 'F-U05',
  sql: 'F-U06',
  external: 'F-U05',
  errors: 'F-U07',
};

function req(...ids: string[]): { type: string; description: string }[] {
  return ids.map((description) => ({ type: 'req', description }));
}

/** The generator's range (the primary app's seeded data). */
function seededRange(seed: SeedManifest): { from: string; to: string } {
  return { from: seed.generator.range.start, to: seed.generator.range.end };
}

/** The dashboard of the primary app over the seeded range (where the menu journeys start). */
function dashboardOf(seed: SeedManifest): string {
  const { from, to } = seededRange(seed);
  return `/apps/${String(seed.primary_app_id)}/dashboard?from=${from}&to=${to}`;
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

/** Saves and attaches a full-page screenshot (evidence of what the screen shows). */
async function evidence(page: Page, name: string, testInfo: TestInfo): Promise<void> {
  const file = testInfo.outputPath(name);
  await page.screenshot({ path: file, fullPage: true });
  await testInfo.attach(name, { path: file, contentType: 'image/png' });
}

// ---------------------------------------------------------------------------------------------
// T-E2E-stats-20..23: the STATISTICS menu opens the real screens
// ---------------------------------------------------------------------------------------------

STATS_SCREENS.forEach((screen, index) => {
  const n = 20 + index;
  test(
    `T-E2E-stats-${String(n)} the STATISTICS menu item ${screen} opens the real ${screen} screen on the seeded data (= API), 1366x768 without horizontal scroll, axe Critical 0, evidence screenshot`,
    { annotation: req(FEATURE[screen], 'F-U16', '05-2.5-1') },
    async ({ page, seed, apiAs }, testInfo) => {
      test.slow(true, 'dashboard, list and accessibility scan');
      expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
      const api = await apiAs(ORACLE_ROLE);
      const appId = seed.primary_app_id;
      const { from, to } = seededRange(seed);
      await page.goto(dashboardOf(seed));
      await expectShell(page);
      await page.getByTestId(`side-menu-${screen}`).click();
      await expectUrl(
        page,
        (s) => s.path === `/apps/${String(appId)}/stats/${screen}`,
        `the ${screen} screen`,
      );
      await expectStatsState(page, LIST_ROOT[screen]);
      await expect(page.getByTestId('route-pending'), 'a screen, not the placeholder').toHaveCount(
        0,
      );
      const q = listQueryOf(page.url());
      expect(q.from, 'the menu keeps the range').toBe(new Date(from).toISOString());
      expect(q.to, 'the menu keeps the range').toBe(new Date(to).toISOString());
      const rows = await expectListMatchesApi(page, api, screen);
      const { scrollWidth, clientWidth } = await horizontalOverflow(page);
      expect(scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(clientWidth);
      await expectNoCriticalA11y(page, testInfo, `stats-${screen}`);
      await evidence(page, `stats-${screen}.png`, testInfo);
      testInfo.annotations.push({
        type: 'evidence',
        description: `stats-${screen}.png: ${String(rows.length)} rows`,
      });
    },
  );
});

// ---------------------------------------------------------------------------------------------
// T-E2E-stats-24..28: flows around the checklist
// ---------------------------------------------------------------------------------------------

test(
  'T-E2E-stats-24 keyboard: Down and Enter in the services list open the focused service, back returns to the list with the range kept',
  { annotation: req('F-U05', 'F-U16') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    await page.goto(statsPath(appId, 'services', from, to));
    await expectStatsState(page, LIST_ROOT.services);
    const q = listQueryOf(page.url());
    const rows = (await apiStatsList(api, 'services', appId, q)).data;
    expect(rows.length, 'two services at least').toBeGreaterThanOrEqual(2);
    const keys = rows.map((r) => r.service_id);
    const table = statsTable(page, LIST_ROOT.services);
    await expectKeys(table, keys, 'services');
    await tableRows(table).first().focus();
    await page.keyboard.press('ArrowDown');
    const second = keys[1] ?? '';
    await expect(tableRow(table, second)).toBeFocused();
    await page.keyboard.press('Enter');
    const s = await expectUrl(
      page,
      (x) => x.path === `/apps/${String(appId)}/stats/services/${second}`,
      'Enter opens the focused service',
    );
    expect(listQueryOf(page.url()).from).toBe(q.from);
    expect(listQueryOf(page.url()).to).toBe(q.to);
    expect(s.live).toBe(false);
    await expectStatsState(page, DETAIL_ROOT.services);
    await page.goBack();
    await expectUrl(
      page,
      (x) => x.path === `/apps/${String(appId)}/stats/services`,
      'back on the list',
    );
    expect(listQueryOf(page.url()).from).toBe(q.from);
    expect(listQueryOf(page.url()).to).toBe(q.to);
    await expectStatsState(page, LIST_ROOT.services);
    await expectKeys(table, keys, 'the same services after going back');
  },
);

test(
  'T-E2E-stats-25 T-ACPT-03 (automatic part): the SQL Top N of one day equals sql/stats kind=HTTP (complete counts), and batch SQL is excluded (it differs from kind=ALL)',
  { annotation: req('F-U06', 'D-12', '05-2.5-1') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const end = Date.parse(seed.generator.range.end);
    const from = new Date(end - DAY).toISOString();
    const to = new Date(end).toISOString();
    const listRequest = page.waitForRequest((r) => {
      const url = new URL(r.url());
      return (
        url.pathname === `/api/v1/apps/${String(appId)}/sql/stats` &&
        url.searchParams.get('format') === null
      );
    });
    await page.goto(statsPath(appId, 'sql', from, to));
    const sent = new URL((await listRequest).url());
    expect(sent.searchParams.get('kind') ?? 'HTTP', 'the screen asks for requests only').toBe(
      'HTTP',
    );
    expect(
      Date.parse(sent.searchParams.get('to') ?? '') -
        Date.parse(sent.searchParams.get('from') ?? ''),
    ).toBe(DAY);
    await expectStatsState(page, LIST_ROOT.sql);
    const q = listQueryOf(page.url());
    const http = (await apiStatsList(api, 'sql', appId, { ...q, compare_prev: true })).data;
    expect(http.length, 'SQL of requests in the day').toBeGreaterThan(0);
    await expectRowsShown(statsTable(page, LIST_ROOT.sql), LISTS.sql, http, 'SQL Top N of 24 h');
    const all = (await apiStatsList(api, 'sql', appId, { ...q, kind: 'ALL' })).data;
    const batch = (await apiStatsList(api, 'sql', appId, { ...q, kind: 'BATCH' })).data;
    expect(batch.length, 'the seed has SQL of batch transactions').toBeGreaterThan(0);
    const key = (r: SqlStats): string => `${r.sql_id}:${r.db.db_id}`;
    const allCounts = new Map(all.map((r) => [key(r), r.count]));
    const differs =
      http.length !== all.length || http.some((r) => allCounts.get(key(r)) !== r.count);
    expect(differs, 'kind=HTTP differs from kind=ALL: batch SQL is not counted').toBe(true);
    for (const row of http) {
      const inAll = allCounts.get(key(row));
      if (inAll !== undefined) {
        expect(row.count, `${key(row)}: requests only <= all kinds`).toBeLessThanOrEqual(inAll);
      }
    }
    testInfo.annotations.push({
      type: 'T-ACPT-03',
      description: `HTTP ${String(http.length)} statements (top count ${String(http[0]?.count)}), ALL ${String(all.length)}, BATCH ${String(batch.length)}; 1 s response = T-NFR-07 on target hardware (사람 확인 필요)`,
    });
  },
);

test(
  'T-E2E-stats-26 T-ACPT-11 (automatic part): timeouts and connection failures per host equal the API, the generator seed has timeouts',
  { annotation: req('F-U05', '05-2.5-4') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    await page.goto(statsPath(appId, 'external', from, to));
    await expectStatsState(page, LIST_ROOT.external);
    const q = listQueryOf(page.url());
    const rows = (await apiStatsList(api, 'external', appId, { ...q, compare_prev: true })).data;
    const timeouts = rows.reduce((sum, r) => sum + r.timeouts, 0);
    const connectFailures = rows.reduce((sum, r) => sum + r.connect_failures, 0);
    expect(timeouts, 'timeouts in the generator seed (ADR-0010 C-207 (5))').toBeGreaterThan(0);
    await expectRowsShown(statsTable(page, LIST_ROOT.external), LISTS.external, rows, 'hosts');
    testInfo.annotations.push({
      type: 'T-ACPT-11',
      description: `${String(rows.length)} hosts, ${String(timeouts)} timeouts, ${String(connectFailures)} connection failures (generator seed); agent-classified failures of the traffic agent: T-E2E-stats-37, active "in external call" WO-35 (사람 확인 필요)`,
    });
  },
);

test(
  'T-E2E-stats-27 T-ACPT-01 (last hop): XLog → transaction detail → SQL tab → SQL statistics of that statement with the XLog range',
  { annotation: req('F-U06', 'F-U14', '05-2.2-5') },
  async ({ page, seed, apiAs }) => {
    test.slow(true, 'XLog, detail and SQL statistics');
    const api = await apiAs(ORACLE_ROLE);
    const tx = seed.tx.captured_sql_stack;
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    const back = backOf(tx);
    const ms = Date.parse(tx.ts);
    await page.goto(
      `/apps/${String(tx.app_id)}/xlog?from=${back.from}&to=${back.to}&sel=ms:${String(d.elapsed_ms)}-${String(d.elapsed_ms)},ts:${String(ms)}-${String(ms + 1)}`,
    );
    const row = page.getByTestId('xlog-list').locator(`[data-row-key="${tx.tx_id}"]`);
    await expect(row).toBeVisible({ timeout: 60_000 });
    await row.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
      timeout: 20_000,
    });
    await page.getByTestId('transaction-tab-sql').click();
    const link = page.getByTestId('transaction-sql-stats-link').first();
    await expect(link).toBeVisible();
    await link.click();
    const s = await expectUrl(
      page,
      (x) => x.path.startsWith(`/apps/${String(tx.app_id)}/stats/sql/`),
      'the SQL statistics of the statement',
    );
    const sqlId = s.path.split('/').pop() ?? '';
    const sqlIds = d.profile.steps
      .filter((step) => step.type.toUpperCase() === 'SQL')
      .map((step) => step.sql_id);
    expect(sqlIds, 'a statement of the transaction').toContain(sqlId);
    const q = listQueryOf(page.url());
    expect(q.from, 'the XLog range (from)').toBe(new Date(back.from).toISOString());
    expect(q.to, 'the XLog range (to)').toBe(new Date(back.to).toISOString());
    await expectStatsState(page, DETAIL_ROOT.sql);
    const detail = await apiStatsDetail(api, 'sql', tx.app_id, sqlId, q);
    const root = page.getByTestId(DETAIL_ROOT.sql);
    // The code element only: the block's toolbar (language label, copy button) is not the text.
    await expectCodeText(
      root.getByTestId('stats-sql-normalized'),
      detail.sql_normalized,
      'normalized SQL',
    );
    await expectCodeText(root.getByTestId('stats-sql-text'), detail.sql_text, 'original SQL');
  },
);

test(
  'T-E2E-stats-28 the seeded XSS service name is text in the services table (no markup, nothing runs)',
  { annotation: req('F-U05', 'SEC-60', 'SEC-62') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.traffic.app_id;
    const { start, end } = seed.traffic.range;
    const name = seed.traffic.xss_service;
    expect(name, 'the seed traffic has its XSS seed service').not.toBeNull();
    await page.goto(statsPath(appId, 'services', start, end, { kind: 'ALL' }));
    await expectStatsState(page, LIST_ROOT.services);
    const q = listQueryOf(page.url());
    const rows = (await apiStatsList(api, 'services', appId, { ...q, limit: 500 })).data;
    const row = rows.find((r) => r.service === name);
    expect(row, `the API lists the XSS seed service ${String(name)}`).toBeDefined();
    const table = statsTable(page, LIST_ROOT.services);
    const cell = tableRow(table, row?.service_id ?? '')
      .first()
      .locator('td[data-column="service"]');
    await expect(cell).toContainText(name ?? '');
    const root = page.getByTestId(LIST_ROOT.services);
    expect(
      await root
        .locator(
          'img, iframe, script, object, embed, a[href^="javascript"], svg:not([aria-hidden])',
        )
        .count(),
      'no element made of collected text',
    ).toBe(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
  },
);

// ---------------------------------------------------------------------------------------------
// T-E2E-service-version-1/2 (T-ACPT-04)
// ---------------------------------------------------------------------------------------------

test(
  'T-E2E-service-version-1 T-ACPT-04: the service detail compares p95 per version (= by_version) and marks the deploy events of GET /events on its trend',
  { annotation: req('F-U05', '05-2.5-2') },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'several service details');
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    const q = listQueryOf(statsPath(appId, 'services', from, to));
    const rows = (await apiStatsList(api, 'services', appId, q)).data;
    const chosen = await serviceWithMostVersions(api, appId, rows, q);
    expect(chosen.detail.by_version.length, 'the seed switches versions').toBeGreaterThanOrEqual(2);
    const events = await apiEvents(api, appId, q.from, q.to);
    for (const seeded of seed.events.filter((e) => e.app_id === appId)) {
      expect(
        events.map((e) => e.event_id),
        'seeded deploy event in GET /events',
      ).toContain(seeded.event_id);
    }
    await page.goto(statsDetailPath(appId, 'services', chosen.row.service_id, from, to));
    await expectStatsState(page, DETAIL_ROOT.services);
    await expectServiceDetail(page, api, chosen.detail, listQueryOf(page.url()));
    testInfo.annotations.push({
      type: 'T-ACPT-04',
      description: `${chosen.row.service}: p95 per version ${JSON.stringify(chosen.detail.by_version.map((v) => [v.name ?? v.id, v.p95_ms]))}; ${String(events.length)} events`,
    });
  },
);

test(
  'T-E2E-service-version-2 T-ACPT-04: the version split of the services list shows one row per service and version (= split_version=true) and goes back',
  { annotation: req('F-U05', '05-2.5-1') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    await page.goto(statsPath(appId, 'services', from, to));
    await expectStatsState(page, LIST_ROOT.services);
    const table = statsTable(page, LIST_ROOT.services);
    const q = listQueryOf(page.url());
    const plain = (await apiStatsList(api, 'services', appId, { ...q, compare_prev: true })).data;
    await expectKeys(
      table,
      plain.map((r) => r.service_id),
      'services',
    );
    await page.getByTestId('stats-split-version').click();
    await expectUrl(page, (s) => ['1', 'true'].includes(s.params.get('split') ?? ''), 'split=1');
    const split = (
      await apiStatsList(api, 'services', appId, {
        ...listQueryOf(page.url()),
        compare_prev: true,
        split_version: true,
      })
    ).data;
    expect(
      split.every((r) => r.version_id !== undefined),
      'rows per version',
    ).toBe(true);
    expect(split.length, 'rows per service and version').toBeGreaterThan(0);
    await expect
      .poll(
        async () => {
          const shown = await shownRows(table);
          const out = rowMismatches(shown, split, LISTS.services.values, LISTS.services.deltas);
          split.forEach((row, i) => {
            const s = shown[i];
            if (s === undefined) {
              return;
            }
            if (!s.key.startsWith(row.service_id) || !s.key.endsWith(row.version_id ?? '')) {
              out.push(
                `row ${String(i)}: key ${s.key}, API ${row.service_id} / ${String(row.version_id)}`,
              );
            }
            if (!(s.texts.version ?? '').includes(row.version ?? '—')) {
              out.push(
                `row ${String(i)}: version ${String(s.texts.version)}, API ${String(row.version)}`,
              );
            }
          });
          return out;
        },
        { message: 'split rows = split_version=true', timeout: 20_000 },
      )
      .toEqual([]);
    await page.getByTestId('stats-split-version').click();
    await expectUrl(page, (s) => !['1', 'true'].includes(s.params.get('split') ?? ''), 'split off');
    await expectKeys(
      table,
      plain.map((r) => r.service_id),
      'one row per service again',
    );
  },
);

// ---------------------------------------------------------------------------------------------
// T-E2E-errors-1..3 (T-ACPT-13)
// ---------------------------------------------------------------------------------------------

test(
  'T-E2E-errors-1 T-ACPT-13: from the dashboard, the side menu, the new filter and a group row show a new group representative stack within three clicks',
  { annotation: req('F-U07', 'S-13', '05-2.5-5') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    // The traffic app: its errors came through the agent and the ingest, which keeps a
    // representative stack per group (ErrorGroupStackFiller, WO-27); the generator seed writes its
    // error groups without one.
    const appId = seed.traffic.app_id;
    const { start: from, end: to } = seed.traffic.range;
    await page.goto(`/apps/${String(appId)}/dashboard?from=${from}&to=${to}`);
    await expectShell(page);
    let clicks = 0;
    await page.getByTestId('side-menu-errors').click();
    clicks += 1;
    await expectStatsState(page, LIST_ROOT.errors);
    await page.getByTestId('stats-errors-new').click();
    clicks += 1;
    await expectUrl(page, (s) => ['1', 'true'].includes(s.params.get('new') ?? ''), 'new=1');
    const q = listQueryOf(page.url());
    const fresh = (await apiStatsList(api, 'errors', appId, { ...q, new_only: true })).data;
    expect(fresh.length, 'new groups in the seeded range').toBeGreaterThan(0);
    const table = statsTable(page, LIST_ROOT.errors);
    await expectKeys(
      table,
      fresh.map((g) => g.group_id),
      'new groups',
    );
    // The first new group with a representative stack, else the first new group. The agent sends
    // no ERROR step stack yet (Tracer.stepStack has no caller), so the stack store
    // (ErrorGroupStackFiller, WO-27) has nothing to keep on real data; T-E2E-errors-4 (fixme) holds
    // the non-empty case. The panel always equals the API's sample_stack, empty included.
    let found: ErrorGroup | undefined;
    for (const candidate of fresh) {
      const candidateDetail = await apiStatsDetail(api, 'errors', appId, candidate.group_id, q);
      if ((candidateDetail.sample_stack?.length ?? 0) > 0) {
        found = candidate;
        break;
      }
    }
    const group: ErrorGroup = found ?? (fresh[0] as ErrorGroup);
    await openRow(table, group.group_id);
    clicks += 1;
    await expectUrl(
      page,
      (s) => s.path === `/apps/${String(appId)}/stats/errors/${group.group_id}`,
      'the group detail',
    );
    await expectStatsState(page, DETAIL_ROOT.errors);
    expect(clicks, 'clicks from the side menu to the stack (S-13: 3)').toBeLessThanOrEqual(3);
    const detail = await apiStatsDetail(
      api,
      'errors',
      appId,
      group.group_id,
      listQueryOf(page.url()),
    );
    const stack = page.getByTestId(DETAIL_ROOT.errors).getByTestId('stats-error-stack');
    await expect(stack, 'the representative stack panel').toBeVisible();
    const frames = stack.getByTestId('transaction-frame');
    await expect(frames).toHaveCount(detail.sample_stack?.length ?? 0);
    expect((await frames.allTextContents()).map(squash), 'stack frames = sample_stack').toEqual(
      (detail.sample_stack ?? []).map(squash),
    );
    testInfo.annotations.push({
      type: 'T-ACPT-13',
      description: `${String(clicks)} clicks; group ${group.group_id} ${String(group.error_class)}; stack frames ${String(detail.sample_stack?.length ?? 0)}; versions ${JSON.stringify(detail.by_version.map((v) => [v.name ?? v.id, v.count]))}`,
    });
  },
);

test(
  'T-E2E-errors-2 T-ACPT-13: the new list equals new_only=true; the version and instance distributions of a new group equal the API',
  { annotation: req('F-U07', '05-2.5-5') },
  async ({ page, seed, apiAs }) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    await page.goto(statsPath(appId, 'errors', from, to));
    await expectStatsState(page, LIST_ROOT.errors);
    const table = statsTable(page, LIST_ROOT.errors);
    await expectNewFilter(page, api, table);
    const q = listQueryOf(page.url());
    const fresh = (await apiStatsList(api, 'errors', appId, { ...q, new_only: true })).data;
    const group = fresh[0] as ErrorGroup;
    await openRow(table, group.group_id);
    await expectUrl(
      page,
      (s) => s.path === `/apps/${String(appId)}/stats/errors/${group.group_id}`,
      'the group detail',
    );
    await expectStatsState(page, DETAIL_ROOT.errors);
    const detail = await apiStatsDetail(
      api,
      'errors',
      appId,
      group.group_id,
      listQueryOf(page.url()),
    );
    expect(detail.by_version.length, 'versions of the group').toBeGreaterThan(0);
    await expectBreakdown(page, 'stats-by-version', detail.by_version, ['count']);
    await expectBreakdown(page, 'stats-by-instance', detail.by_instance, ['count']);
  },
);

test(
  'T-E2E-errors-3 T-ACPT-13: the last 10 transactions of a group equal recent_tx and their links open the transaction details',
  { annotation: req('F-U07', '05-2.5-5') },
  async ({ page, seed, apiAs }) => {
    test.slow(true, 'error detail and transaction detail');
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.primary_app_id;
    const { from, to } = seededRange(seed);
    const q = listQueryOf(statsPath(appId, 'errors', from, to));
    const groups = (await apiStatsList(api, 'errors', appId, q)).data;
    let chosen: { group: ErrorGroup; recent: number } | null = null;
    for (const group of groups.slice(0, 5)) {
      const detail = await apiStatsDetail(api, 'errors', appId, group.group_id, q);
      if (detail.recent_tx.length > (chosen?.recent ?? 0)) {
        chosen = { group, recent: detail.recent_tx.length };
      }
    }
    expect(chosen, 'a group with recent transactions in the raw window').not.toBeNull();
    const groupId = chosen?.group.group_id ?? '';
    await page.goto(statsDetailPath(appId, 'errors', groupId, from, to));
    await expectStatsState(page, DETAIL_ROOT.errors);
    await expectErrorDetail(page, api, groupId, listQueryOf(page.url()));
    const detail = await apiStatsDetail(api, 'errors', appId, groupId, listQueryOf(page.url()));
    const first = detail.recent_tx[0];
    if (first === undefined) {
      throw new Error('no recent transaction');
    }
    const link = tableRow(statsTable(page, 'stats-recent-tx'), first.tx_id)
      .first()
      .locator(`a[href*="/transactions/${first.tx_id}"]`);
    const href = await hrefOf(link);
    expect(href.pathname).toBe(`/apps/${String(appId)}/transactions/${first.tx_id}`);
    expect(href.searchParams.get('ts'), 'the C-50 lookup hint (C-206 (1))').not.toBeNull();
    await link.click();
    await expectUrl(
      page,
      (s) => s.path === `/apps/${String(appId)}/transactions/${first.tx_id}`,
      'the transaction detail',
    );
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
      timeout: 20_000,
    });
  },
);

// ---------------------------------------------------------------------------------------------
// T-E2E-stats-30..36: visual regression (pinned Linux image only)
// ---------------------------------------------------------------------------------------------

test.describe('visual', () => {
  test.use({ role: 'operator' });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: 'baselines are made in the pinned Linux image only (corepack pnpm e2e:docker)',
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /**
   * Per-run values: the range label, the chart plots (clock times on the axes, the seed is anchored
   * on the run day), first and last occurrence times, the raw window and request ids.
   */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.locator('.ts-chart__plot'),
      page.locator('[data-column="first_seen"]'),
      page.locator('[data-column="last_seen"]'),
      page.getByTestId('stats-raw-window'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-stats-30 visual: the services list while its data loads',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed }) => {
      const { from, to } = seededRange(seed);
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const pattern = '**/api/v1/apps/*/services/stats?**';
      await page.route(pattern, async (route) => {
        await held;
        await route.continue();
      });
      await page.goto(statsPath(seed.primary_app_id, 'services', from, to));
      await expectStatsState(page, LIST_ROOT.services, 'loading');
      const loading = page.getByTestId(`${LIST_ROOT.services}-loading`);
      await expect(loading).toBeVisible();
      // The slow-query hint appears after 2 s (LoadingState): wait for it, so the image is stable.
      // The screen's LoadingState carries the root's test id itself (StatsScreen).
      await expect(loading).toHaveAttribute('data-slow', 'true');
      await expect(loading.getByTestId('loading-state-slow')).toBeVisible();
      await expect(page).toHaveScreenshot('stats-loading.png', { mask: masks(page) });
      release();
      await expectStatsState(page, LIST_ROOT.services);
      await page.unroute(pattern);
    },
  );

  test(
    'T-E2E-stats-31 visual: the services list of a range without transactions (empty state with the next action)',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(ORACLE_ROLE);
      const start = Date.parse(seed.generator.range.start);
      const from = new Date(start - 30 * MINUTE).toISOString();
      const to = new Date(start - 15 * MINUTE).toISOString();
      const rows = (
        await apiStatsList(api, 'services', seed.primary_app_id, {
          from,
          to,
          kind: 'HTTP',
          limit: 100,
        })
      ).data;
      expect(rows, 'no service before the generator range').toEqual([]);
      await page.goto(statsPath(seed.primary_app_id, 'services', from, to));
      await expectStatsState(page, LIST_ROOT.services, 'empty');
      await expect(page.getByTestId(`${LIST_ROOT.services}-empty`)).toBeVisible();
      await expect(page).toHaveScreenshot('stats-empty.png', { mask: masks(page), fullPage: true });
    },
  );

  test(
    'T-E2E-stats-32 visual: the services list for a range over the API limit (more than 1 year): the server error with the request id and the next actions',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, seed, apiAs }) => {
      const api = await apiAs(ORACLE_ROLE);
      const end = Date.parse(seed.generator.range.end);
      const from = new Date(end - 400 * DAY).toISOString();
      const to = new Date(end).toISOString();
      const answer = await api.request(
        'GET',
        `/apps/${String(seed.primary_app_id)}/services/stats`,
        { query: { from, to }, expect: [400] },
      );
      expect(answer.status).toBe(400);
      await page.goto(statsPath(seed.primary_app_id, 'services', from, to));
      await expectStatsState(page, LIST_ROOT.services, 'error');
      const error = page.getByTestId(`${LIST_ROOT.services}-error`);
      await expect(error).toBeVisible();
      await expect(error.getByTestId('error-state-request-id')).toBeVisible();
      await expect(error.getByTestId('error-state-retry')).toBeVisible();
      await expect(page).toHaveScreenshot('stats-error.png', { mask: masks(page) });
    },
  );

  STATS_SCREENS.forEach((screen, index) => {
    const n = 33 + index;
    test(
      `T-E2E-stats-${String(n)} visual: the ready ${screen} list of the seeded range`,
      { annotation: req('F-U16', FEATURE[screen]) },
      async ({ page, seed }) => {
        const { from, to } = seededRange(seed);
        await page.goto(statsPath(seed.primary_app_id, screen, from, to));
        await expectStatsState(page, LIST_ROOT[screen]);
        await expect(tableRows(statsTable(page, LIST_ROOT[screen])).first()).toBeVisible();
        await expect(page).toHaveScreenshot(`stats-${screen}-normal.png`, {
          mask: masks(page),
          fullPage: true,
        });
      },
    );
  });
});

// ---------------------------------------------------------------------------------------------
// T-E2E-stats-37: agent-classified failures in the external statistics (WO-16, ADR-0010 C-223 (7))
// ---------------------------------------------------------------------------------------------

/**
 * The host (HOST text, `http://127.0.0.1:<port>`) of the classified outgoing call of the seed's hop
 * trace `scenario`: the step the manifest names in its last caller's detail. Fails with the setup's
 * reason when the seed has no such trace.
 */
async function hopHost(
  seed: SeedManifest,
  apiAs: Parameters<typeof apiDetail>[0]['apiAs'],
  scenario: 'timeout' | 'refuse',
): Promise<string> {
  const hop = (seed.hop_traces ?? []).find((h) => h.scenario === scenario);
  const why =
    seed.missing_optional.find((m) => m.category === 'hop_traces')?.reason ??
    'not in the seed manifest';
  expect(hop, `the agent's ${scenario} hop trace of the seed (${why})`).toBeDefined();
  const trace = hop as NonNullable<typeof hop>;
  const detail = await apiDetail({ apiAs }, trace.external_caller_tx_id, trace.external_caller_ts);
  const step = detail.profile.steps.find((st) => st.seq === trace.external_step_seq);
  expect(step?.name, `the outgoing call step of the ${scenario} hop trace`).toMatch(/^https?:\/\//);
  return step?.name ?? '';
}

test(
  'T-E2E-stats-37 external statistics of agent-measured TIMEOUT and CONNECT_FAIL classes from the real HTTP client instrumentation (ext-stub scenarios)',
  { annotation: req('F-U05', '05-2.5-4', 'F-A05', 'D-12') },
  async ({ page, seed, apiAs }, testInfo) => {
    const api = await apiAs(ORACLE_ROLE);
    const appId = seed.traffic.app_id;
    const extStub = await hopHost(seed, apiAs, 'timeout');
    const refused = await hopHost(seed, apiAs, 'refuse');
    expect(refused, 'the refused port is another host than ext-stub').not.toBe(extStub);
    await page.goto(statsPath(appId, 'external', seed.traffic.range.start, seed.traffic.range.end));
    await expectStatsState(page, LIST_ROOT.external);
    const q = listQueryOf(page.url());
    // The full statistics of the shop's last windows reach the server with its shutdown flush:
    // ask until both classes are counted (at most 30 s), then compare the screen.
    let rows: ExternalStats[] = [];
    const classes = async (): Promise<{ timeouts: number; connectFailures: number }> => {
      rows = (await apiStatsList(api, 'external', appId, { ...q, compare_prev: true })).data;
      return {
        timeouts: rows.find((r) => r.host === extStub)?.timeouts ?? 0,
        connectFailures: rows.find((r) => r.host === refused)?.connect_failures ?? 0,
      };
    };
    await expect
      .poll(
        async () => {
          const c = await classes();
          return c.timeouts >= 1 && c.connectFailures >= 1;
        },
        {
          message: `ext-stub ${extStub} with timeouts >= 1 and ${refused} with connection failures >= 1 in GET /external of the traffic app`,
          timeout: 30_000,
          intervals: [2000],
        },
      )
      .toBe(true);
    await page.reload();
    await expectStatsState(page, LIST_ROOT.external);
    const { timeouts, connectFailures } = await classes();
    expect(timeouts, `timeouts of ${extStub}`).toBeGreaterThanOrEqual(1);
    expect(connectFailures, `connection failures of ${refused}`).toBeGreaterThanOrEqual(1);
    await expectRowsShown(
      statsTable(page, LIST_ROOT.external),
      LISTS.external,
      rows,
      'hosts of the traffic agent',
    );
    testInfo.annotations.push({
      type: 'T-E2E-stats-37',
      description: `${String(rows.length)} hosts; ext-stub ${extStub}: ${String(timeouts)} timeouts; ${refused}: ${String(connectFailures)} connection failures (agent-classified, full statistics)`,
    });
  },
);

// ---------------------------------------------------------------------------------------------
// T-E2E-stats-38..39: no data or screen yet (owners named)
// ---------------------------------------------------------------------------------------------

test.fixme(
  'T-E2E-stats-38 application package frames in bold in the representative stack of an error group (app-packages)',
  {
    annotation: [
      ...req('F-U07', '05-2.5-5'),
      {
        type: 'fixme',
        description:
          'owned by WO-26 (app_packages source, ADR-0010 C-207 (3)); until then: the FrameList component test with an appPackages fixture; the 2.5-5 check asserts no emphasis on the real stack',
      },
    ],
  },
  () => {
    // Owned by WO-26 (fixme annotation): the server has no application packages yet.
  },
);

test.fixme(
  'T-E2E-stats-39 external call statistics link to the external node of the application map',
  {
    annotation: [
      ...req('F-U11', '05-2.5-4'),
      {
        type: 'fixme',
        description:
          'owned by F-U11 (S), the application map of WO-70: no map screen in Phase 1; 사람 확인 필요 after WO-70',
      },
    ],
  },
  () => {
    // Owned by F-U11 / WO-70 (fixme annotation): the application map has no screen yet.
  },
);

test.fixme(
  'T-E2E-errors-4 T-ACPT-13: a new error group from real agent data shows a non-empty representative stack',
  {
    annotation: [
      ...req('F-U07', 'S-13', '05-2.5-5'),
      {
        type: 'fixme',
        description:
          'needs ERROR step stacks from the agent (capture.stack-depth, Tracer.stepStack has no caller yet; owner WO-19, ADR-0010 C-222 (1)); the server keeps the first stack of a group (ErrorGroupStackFiller, WO-27, ErrorGroupStackIT); until then: T-E2E-errors-1 asserts the panel equals the API sample_stack (empty on the seed)',
      },
    ],
  },
  () => {
    // Owner to be assigned (fixme annotation): no agent instrumentation records an ERROR step stack.
  },
);
