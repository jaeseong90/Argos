// SPDX-License-Identifier: Apache-2.0
//
// Active transactions /apps/:appId/active on the real stack (WO-35, docs/05 §2.6, docs/15 §9.6,
// F-U04): the side menu opens the screen, and the bars, the summary and the nine columns equal the
// last `active` push of the real WebSocket server of WO-22 (wsMode 'server') and GET
// /apps/{appId}/active of the same snapshot; refresh every 5 s with "as of N s ago", pause, the
// four filters, custom elapsed thresholds, the row actions (T-ACPT-02 automated part), collected
// text as text, layout and accessibility; the disconnect badge and the instance states drive the
// WebSocket stub (e2e/fixtures/ws.ts) on top of the real REST answer.
//
// Running transactions come from the dev-only active feed (e2e/stack/seed/activeFeed.ts, app
// e2e-active with two instances through the real collector, REST and WS), started in beforeAll:
// the feed covers every state and band on two instances. T-E2E-active-31 starts shop-boot3 with the
// agent of the stack (e2e/stack/liveShop.ts, its own instance name) and checks that the real agent
// reports a running request (WO-16). Inside the Playwright image of e2e:docker there is no JVM for
// the feed or the shop: the real-data tests skip with the reason (as T-SV-020e does); the stub
// tests and the visual baselines run there.
// The checks live in e2e/required/activeChecks.ts, which the items of required/active.yaml
// (T-E2E-active-1 .. 12) run too. The row actions this thread's stack and thread dump (WO-37,
// T-ACPT-02) need an agent that answers remote commands: T-E2E-active-30 runs them as the operator
// on the command feed's application (e2e/fixtures/commandFeed.ts, app e2e-cmd: the same active
// scenario, answering commands through the real collector until WO-17's agent is in the stack),
// with the checks of e2e/required/dumpsChecks.ts that required/active.yaml 2.6-4 runs too.
import type { Page } from '@playwright/test';

import { MISSING_DASH } from '../../src/utils/missing';
import { expectNoCriticalA11y } from '../fixtures/a11y';
import { commandFeedLog, stopCommandFeed } from '../fixtures/commandFeed';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { apiAs, expect, test } from '../fixtures/stack';
import { expectShell, expectUrl, horizontalOverflow } from '../fixtures/ui';
import {
  ACTIVE_ORACLE_ROLE,
  activeDisconnect,
  activeFeedLog,
  activeFilters,
  activeInstanceStates,
  activePath,
  activePause,
  activeRefresh,
  activeRowActions,
  activeRecentCompletions,
  activeThresholds,
  ensureActiveFeed,
  expectScenario,
  expectScreenShows,
  matchedSnapshot,
  openActive,
  openFeedScreen,
  pushStub,
  readScreen,
  requireFeed,
  restActive,
  stopActiveFeed,
  stubSnapshot,
  type ActiveApi,
  type ActiveCheckContext,
  type ActiveItemApi,
} from '../required/activeChecks';
import {
  activeThreadDump,
  activeThreadStack,
  signInAs,
  type DumpsCheckContext,
} from '../required/dumpsChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { shopProblem, startShop } from '../stack/liveShop';
import { retireInstancesNamed } from '../stack/retire';
import { stopProcess } from '../stack/seed/traffic';
import { httpGet } from '../stack/wait';

const ACTIVE = { type: 'req', description: 'F-U04' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [ACTIVE, ...ids.map((description) => ({ type: 'req', description }))];
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

test.beforeAll(async ({ session, seed }, testInfo) => {
  // Starts the shared feed (two instances) and waits until GET /active shows all four bands per
  // instance and the five states; null inside e2e:docker (no JVM: the real-data tests skip).
  testInfo.setTimeout(300_000);
  await ensureActiveFeed(seed, (role) => apiAs(session, seed, role));
});

test.afterAll(async ({ session, seed }, testInfo) => {
  testInfo.setTimeout(3 * 60_000);
  // Stopped feeds archive their instances (C-241 (7)): no instance_down stays FIRING after them.
  const operator = () => apiAs(session, seed, 'operator');
  try {
    await stopActiveFeed(operator);
    await stopCommandFeed(operator);
  } finally {
    await testInfo.attach('active-feed.log', { body: activeFeedLog(), contentType: 'text/plain' });
    await testInfo.attach('command-feed.log', {
      body: commandFeedLog(),
      contentType: 'text/plain',
    });
  }
});

test.describe('the real WebSocket server', () => {
  test.use({ wsMode: 'server' });

  test(
    'T-E2E-active-20 the side menu opens the screen; bars, summary and the nine columns equal the last real active push and REST of the same snapshot',
    { annotation: req('05-2.6-1', '05-2.6-2', '05-2.6-5', 'D-23', 'SEC-60') },
    async ({ page, seed, apiAs: as, ws }, testInfo) => {
      const ctx: ActiveCheckContext = { page, seed, testInfo, apiAs: as, ws };
      const feed = await requireFeed(ctx);
      // From another screen of the feed's application through the side menu (kind=ALL carried).
      await page.goto(`/apps/${String(feed.appId)}/instances?kind=ALL`);
      await expectShell(page);
      await page.getByTestId('side-menu-active').click();
      await expectUrl(
        page,
        (s) => s.path === `/apps/${String(feed.appId)}/active` && s.params.get('kind') === 'ALL',
        'the side menu opened the active transactions screen',
      );
      await expect(page.getByTestId('route-pending')).toHaveCount(0);
      await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready', {
        timeout: 30_000,
      });
      const { push, read, rest } = await matchedSnapshot(ctx, feed.appId);
      expect(read.source, 'the displayed snapshot came from the push').toBe('ws');
      expect(rest, 'REST of the same snapshot = the push').toEqual(push.data);
      expectScreenShows(read, push.data, { what: 'last real active push' });
      expectScenario(push.data, feed, 'last real active push');
      // Two instances, the first busier; every instance with all four bands.
      expect(read.bars).toHaveLength(feed.instances.length);
      expect(read.bars.every((b) => b.buckets.every((n) => n >= 1))).toBe(true);
      // The five states with their labels; detail = SQL text, host or DB target.
      for (const row of read.rows) {
        await expect(page.getByTestId(`active-cell-state-${row.tx}`)).toContainText(
          t(`active:state.${row.state}`),
        );
      }
      const dbc = read.rows.find((r) => r.state === 'DBC');
      expect(dbc?.detail, 'a connection wait shows its DB target').toMatch(/^jdbc:/);
      const sql = read.rows.find((r) => r.state === 'SQL' && r.kind === 'BATCH');
      expect(sql?.detail, 'running SQL shows the SQL').toMatch(/^UPDATE /);
      const http = read.rows.find((r) => r.state === 'HTTP');
      expect(http?.detail, 'an outgoing call shows its host').toMatch(/^http:\/\//);
      // A viewer may not edit alert rules: the link is there, disabled with the reason.
      await expect(page.getByTestId('active-rule-link')).toHaveAttribute('aria-disabled', 'true');
      // "As of" on the server clock is shown.
      await expect(page.getByTestId('active-asof')).toHaveAttribute('data-seconds', /^\d+$/);
    },
  );

  test(
    'T-E2E-active-21 refreshed by the real pushes about every 5 s; "as of N s ago" follows the server clock',
    { annotation: req('05-2.6-3') },
    async ({ page, seed, apiAs: as, ws }, testInfo) => {
      await activeRefresh({ page, seed, testInfo, apiAs: as, ws });
    },
  );

  test(
    'T-E2E-active-22 pause freezes the screen while real pushes arrive; resume shows the newest snapshot at once',
    { annotation: req('05-2.6-3') },
    async ({ page, seed, apiAs: as, ws }, testInfo) => {
      await activePause({ page, seed, testInfo, apiAs: as, ws });
    },
  );

  test(
    'T-E2E-active-26 custom thresholds 2/5/10 s recolour bars and rows, survive a reload, a bad order is refused',
    { annotation: req('05-2.6-1') },
    async ({ page, seed, apiAs: as, ws }, testInfo) => {
      await activeThresholds({ page, seed, testInfo, apiAs: as, ws });
    },
  );
});

test(
  'T-E2E-active-23 bar click -> f.instance; state, service and kind filters -> URL keys, rows = the API, bars unchanged',
  { annotation: req('05-2.6-1', '05-2.6-3', 'F-U16') },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    const ctx = { page, seed, testInfo, apiAs: as, ws };
    await activeFilters(ctx);
    // The chip of an instance filter carries its name, not the id.
    const feed = await requireFeed(ctx);
    const first = feed.instances[0];
    if (first !== undefined) {
      await page.goto(activePath(feed.appId, `kind=ALL&f.instance=${String(first.instanceId)}`));
      await expect(page.getByTestId('filter-chip-bar')).toContainText(first.name);
    }
  },
);

test(
  'T-E2E-active-24 stub: a silent socket shows "updates stopped N s"; after a drop the screen reconnects, subscribes again, the next snapshot clears the badge',
  { annotation: req('05-2.6-6', 'N-15') },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    await activeDisconnect({ page, seed, testInfo, apiAs: as, ws });
    // The top bar's connection badge (docs/15 §10) went away with the reconnect too.
    await expect(page.getByTestId('ws-stale-badge')).toHaveCount(0);
  },
);

test(
  'T-E2E-active-25 stub: WARN and STALE instance states as badges on the bars, the truncated hint and the estimated split',
  { annotation: req('05-2.6-1') },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    await activeInstanceStates({ page, seed, testInfo, apiAs: as, ws });
  },
);

test(
  'T-E2E-active-27 a row of 8 s or more: stack and thread dump enabled for an operator, disabled with the permission reason for a viewer; recent completions open the XLog with the service, 15 minutes and the kind (T-ACPT-02 automated part)',
  { annotation: req('05-2.6-4', 'SEC-21') },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    const ctx = { page, seed, testInfo, apiAs: as, ws };
    // The page's viewer: both disabled with the reason "operator role needed", a click does nothing.
    await activeRowActions(ctx);
    await activeRecentCompletions(ctx);
    // An operator: both enabled, no reason (WO-37: the commands exist now; T-E2E-active-30 runs them).
    await signInAs(page, as, 'operator');
    const operatorFeed = await openFeedScreen(ctx);
    const slowRow = (await readScreen(page)).rows.find((r) => r.elapsed >= 8000);
    if (slowRow === undefined) {
      throw new Error('no row of 8 s or more');
    }
    await page.getByTestId(`active-cell-elapsed-${slowRow.tx}`).click();
    await expect(page.getByTestId('active-row-actions')).toHaveAttribute('data-tx-id', slowRow.tx);
    for (const id of ['active-action-stack', 'active-action-dump']) {
      const action = page.getByTestId(id);
      await expect(action, `${id} is enabled for an operator`).toBeEnabled();
      await expect(action).not.toHaveAttribute('aria-disabled', 'true');
      await expect(action).not.toHaveAttribute('data-reason', /./);
    }
    const instanceId = operatorFeed.instances.find((i) => i.name === slowRow.instance)?.instanceId;
    expect(instanceId, `instance id of ${slowRow.instance}`).toBeDefined();
    await expect(page.getByTestId('active-action-instance')).toHaveAttribute(
      'href',
      new RegExp(`^/apps/${String(operatorFeed.appId)}/instances/${String(instanceId)}(\\?|$)`),
    );
    await signInAs(page, as, 'viewer');
    // An HTTP row of 8 s or more: the default kind is not written into the XLog link.
    const feed = await openFeedScreen(ctx);
    const read = await readScreen(page);
    const http = read.rows.find((r) => r.kind === 'HTTP' && r.elapsed >= 8000);
    if (http === undefined) {
      throw new Error('no HTTP row of 8 s or more');
    }
    await page.getByTestId(`active-cell-elapsed-${http.tx}`).click();
    const href = await page.getByTestId('active-action-recent').getAttribute('href');
    const url = new URL(href ?? '', 'http://ui.invalid');
    expect(url.pathname).toBe(`/apps/${String(feed.appId)}/xlog`);
    expect(url.searchParams.get('kind'), 'HTTP is the default kind').toBeNull();
    expect(url.searchParams.get('f.service')).toMatch(/^\d+$/);
  },
);

test(
  'T-E2E-active-28 collected text from the feed (service, thread, SQL, user id) renders as text: no markup, no __xss',
  { annotation: req('SEC-60', 'SEC-61'), tag: '@security' },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    const ctx = { page, seed, testInfo, apiAs: as, ws };
    const feed = await openFeedScreen(ctx);
    const read = await readScreen(page);
    const payloads = feed.scenario.flatMap((row) =>
      [row.service, row.thread, row.state_detail ?? '', row.user_id ?? ''].filter((v) =>
        v.includes('ARGOS_SEED'),
      ),
    );
    expect(payloads.length, 'the feed carries XSS payloads').toBeGreaterThanOrEqual(4);
    const shown = read.rows.flatMap((r) => [r.service, r.thread, r.detail ?? '', r.user]);
    for (const payload of payloads) {
      expect(shown, `rendered as exactly this text: ${payload}`).toContain(payload);
    }
    // Selecting the XSS service shows it in the action bar as text too.
    const xssRow = read.rows.find((r) => r.service.includes('ARGOS_SEED'));
    if (xssRow !== undefined) {
      await page.getByTestId(`active-cell-elapsed-${xssRow.tx}`).click();
      await expect(page.getByTestId('active-row-actions')).toContainText(xssRow.service);
    }
    const injected = await page
      .getByTestId('active-page')
      .locator('img, script, svg[onload], iframe, a[href^="javascript"], form')
      .count();
    expect(injected, 'elements made of collected text').toBe(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
    expect(read.rows.every((r) => r.service !== MISSING_DASH)).toBe(true);
  },
);

test(
  'T-E2E-active-29 1366x768 without horizontal scroll, axe critical 0, with a selected row (evidence screenshot active.png)',
  { annotation: req('F-U16', '05-2.0') },
  async ({ page, seed, apiAs: as, ws }, testInfo) => {
    const ctx = { page, seed, testInfo, apiAs: as, ws };
    await openFeedScreen(ctx);
    expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
    const read = await readScreen(page);
    const slow = read.rows.find((r) => r.elapsed >= 8000);
    if (slow !== undefined) {
      await page.getByTestId(`active-cell-elapsed-${slow.tx}`).click();
      await expect(page.getByTestId('active-row-actions')).toBeVisible();
    }
    const { scrollWidth, clientWidth } = await horizontalOverflow(page);
    expect(scrollWidth, 'scrollWidth <= clientWidth').toBeLessThanOrEqual(clientWidth);
    await expectNoCriticalA11y(page, testInfo, 'active');
    const file = testInfo.outputPath('active.png');
    await page.screenshot({ path: file, fullPage: true });
    await testInfo.attach('active.png', { path: file, contentType: 'image/png' });
  },
);

test.describe('the operator on the command feed', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-active-30 a selected request of 8 s or more shows its thread stack within 30 s, grouped by state and matched to the transaction; the thread dump opens in the dumps tab (T-ACPT-02)',
    { annotation: req('05-2.6-4', '05-2.7-5', 'F-A12') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      await activeThreadStack(ctx);
      await activeThreadDump(ctx);
    },
  );
});

// ---------------------------------------------------------------------------------------------
// T-E2E-active-31: running transactions reported by the real agent (WO-16, ADR-0010 C-225 (1))
// ---------------------------------------------------------------------------------------------

/** Instance name of the shop of T-E2E-active-31 (apart from T-E2E-099's TRAFFIC_INSTANCE). */
const ACTIVE_31_INSTANCE = 'e2e-shop-active-31';
/** The slow request: a 30 s sleep inside H2, long enough for a snapshot, a push and a match. */
const ACTIVE_31_PATH = '/api/slow?ms=30000&sql=1';

test.describe('the real agent', () => {
  test.use({ wsMode: 'server' });

  test(
    'T-E2E-active-31 the real agent reports running transactions: shop-boot3 /api/slow?ms=30000&sql=1 shows as Running SQL (DOM = GET /active = the push)',
    { annotation: req('05-2.6-2', 'F-S03') },
    async ({ page, seed, session, apiAs: as, ws }, testInfo) => {
      test.setTimeout(6 * 60_000);
      const problem = shopProblem(session.run_id);
      test.skip(problem !== null, problem ?? '');
      const shop = await startShop(session.run_id, {
        instance: ACTIVE_31_INSTANCE,
        logFile: 'active-31-shop-boot3.log',
      });
      const appId = seed.traffic.app_id;
      // The request runs in the background while the screen is read; its answer is not awaited.
      const slow = httpGet(`${shop.url}${ACTIVE_31_PATH}`, {}, 60_000).catch(
        (error: unknown) => error,
      );
      try {
        const ctx: ActiveCheckContext = { page, seed, testInfo, apiAs: as, ws };
        const api = await as(ACTIVE_ORACLE_ROLE);
        await openActive(page, appId);
        // The agent snapshots every 5 s and the server pushes every 5 s: the running SQL of the
        // shop's instance appears in GET /active within about 11 s of the request (C-225 (6)).
        await expect
          .poll(
            async () => {
              const data = await restActive(api, appId);
              return runningSql(data) === null ? 'absent' : 'running';
            },
            {
              message: `GET /active shows ${ACTIVE_31_PATH} of ${ACTIVE_31_INSTANCE} as SQL`,
              timeout: 60_000,
              intervals: [1000],
            },
          )
          .toBe('running');
        const { push, read, rest } = await matchedSnapshot(ctx, appId);
        expect(read.source, 'the displayed snapshot came from the push').toBe('ws');
        expect(rest, 'REST of the same snapshot = the push').toEqual(push.data);
        expectScreenShows(read, push.data, { what: 'real agent active push' });
        const item = runningSql(push.data);
        expect(item, `the push still carries the running ${ACTIVE_31_PATH}`).not.toBeNull();
        const row = read.rows.find((r) => r.tx === item?.tx_id);
        expect(row?.state, 'the row of the slow request').toBe('SQL');
        expect(row?.instance).toBe(ACTIVE_31_INSTANCE);
        expect(row?.elapsed ?? 0, 'elapsed of a request that is still running').toBeGreaterThan(0);
        await expect(page.getByTestId(`active-cell-state-${item?.tx_id ?? ''}`)).toContainText(
          t('active:state.SQL'),
        );
        testInfo.annotations.push({
          type: 'active-31',
          description: `tx ${String(item?.tx_id)} ${String(item?.service)} ${String(item?.elapsed_ms)} ms, detail ${String(item?.state_detail)}; shop-boot3 with the agent at ${shop.url}`,
        });
      } finally {
        await stopProcess(shop.child);
        await slow;
        // C-241 (7): the stopped shop's instance never stays FIRING as instance_down.
        await retireInstancesNamed(await as('operator'), appId, [ACTIVE_31_INSTANCE]);
      }
    },
  );
});

/** The running SQL item of the shop of T-E2E-active-31 in `data`, or null. */
function runningSql(data: ActiveApi): ActiveItemApi | null {
  const instance = data.per_instance.find((p) => p.name === ACTIVE_31_INSTANCE);
  if (instance === undefined) {
    return null;
  }
  return (
    data.items.find(
      (i) =>
        i.instance_id === instance.instance_id &&
        i.state === 'SQL' &&
        (i.service ?? '').includes('/api/slow'),
    ) ?? null
  );
}

test.describe('visual baselines', () => {
  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.beforeEach(({}, testInfo) => {
    if (!inPinnedLinuxImage()) {
      testInfo.annotations.push({
        type: 'screenshot-linux-only',
        description: `baselines are made in ${PLAYWRIGHT_IMAGE} only (corepack pnpm e2e:docker)`,
      });
    }
    test.skip(!inPinnedLinuxImage(), 'screenshot-linux-only');
  });

  /** Elements whose content changes per run. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('error-state-request-id'),
      page.getByTestId('active-asof'),
    ];
  }

  test('T-E2E-active-40 visual: loading', { annotation: req('F-U16') }, async ({ page, seed }) => {
    // The REST answer is held back until the screenshot is taken, then continues unchanged.
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route('**/api/v1/apps/*/active**', async (route) => {
      await held;
      await route.continue();
    });
    await page.goto(activePath(seed.primary_app_id));
    await expectShell(page);
    await expect(page.getByTestId('active-loading')).toHaveAttribute('data-slow', 'true');
    await expect(page).toHaveScreenshot('active-loading.png', { mask: masks(page) });
    // Unrouting while a held request is still in the handler hands it to the next handler, and the
    // handler's own continue() then fails with "Route is already handled": wait until the held
    // answer reached the screen first.
    release();
    await expect(page.getByTestId('active-page')).not.toHaveAttribute('data-state', 'loading', {
      timeout: 30_000,
    });
    await page.unroute('**/api/v1/apps/*/active**');
  });

  test(
    'T-E2E-active-41 visual: no instance connected',
    { annotation: req('F-U16') },
    async ({ page, seed, ws }) => {
      // The generator's second application has no live session; an empty snapshot keeps it so.
      await page.goto(activePath(seed.secondary_app_id));
      await expectShell(page);
      await expect(page.getByTestId('active-page')).toHaveAttribute(
        'data-state',
        /^(ready|empty)$/,
      );
      await ws.waitForSubscription('active');
      await pushStub(page, ws, {
        snapshot_at: new Date().toISOString(),
        per_instance: [],
        items: [],
      });
      await expect(page.getByTestId('active-empty')).toContainText(
        t('active:page.empty.noInstance.title'),
      );
      await expect(page).toHaveScreenshot('active-no-instance.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-active-42 visual: error with the request id (400 answer of the real server)',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      // The request goes to the real server with an invalid kind; its 400 answer is shown.
      await page.route('**/api/v1/apps/*/active**', async (route) => {
        const url = new URL(route.request().url());
        url.searchParams.set('kind', 'NOT_A_KIND');
        await route.continue({ url: url.toString() });
      });
      await page.goto(activePath(seed.primary_app_id));
      await expectShell(page);
      await expect(page.getByTestId('active-error')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('error-state-request-id')).toBeVisible();
      await expect(page).toHaveScreenshot('active-error.png', { mask: masks(page) });
      await page.unroute('**/api/v1/apps/*/active**');
    },
  );

  test(
    'T-E2E-active-43 visual: normal (fixed stub snapshot with a selected slow row)',
    { annotation: req('F-U16') },
    async ({ page, seed, ws }) => {
      await page.goto(activePath(seed.primary_app_id));
      await expectShell(page);
      await expect(page.getByTestId('active-page')).toHaveAttribute(
        'data-state',
        /^(ready|empty)$/,
      );
      await ws.waitForSubscription('active');
      // Fixed names, elapsed and start times (the snapshot time is now only for "as of", masked).
      const data = stubSnapshot({ snapshotAt: new Date().toISOString() });
      const fixedStart = Date.parse('2030-01-01T09:00:00Z');
      for (const item of data.items) {
        item.started_at = new Date(fixedStart - item.elapsed_ms).toISOString();
      }
      await pushStub(page, ws, data);
      const slow = data.items[0];
      if (slow !== undefined) {
        await page.getByTestId(`active-cell-elapsed-${slow.tx_id}`).click();
        await expect(page.getByTestId('active-row-actions')).toBeVisible();
      }
      await expect(page.getByTestId('active-stale-badge')).toHaveCount(0);
      await expect(page).toHaveScreenshot('active-normal.png', { mask: masks(page) });
    },
  );
});
