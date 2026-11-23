// SPDX-License-Identifier: Apache-2.0
//
// Overview /overview on the seeded stack (WO-31, docs/05 §2.10, docs/15 §9.1, F-U17, F-U21): the
// rows, panels, filters, views, kiosk, sort and dashboard links compared with the GET /overview
// answers of the real server (the checks of e2e/required/overviewChecks.ts, which the data-driven
// T-E2E-overview-<n> items of required/overview.yaml run too), 30 applications at 1366x768
// without scrolling, live refresh from the real WebSocket server of WO-22 (ADR-0010 C-213 (3)) and
// the visual baselines of the loading, empty, error and normal states.
//
// What the seed cannot show is changed in the browser on top of the real answer and named in the
// test: T-E2E-090 pads the ~10 seeded applications to 30 with schema-typed rows, T-E2E-095
// replaces the rows with a typed mix of UP/WARN/STALE/DOWN and firing counts (the seed keeps no
// instance STALE or WARN), T-E2E-091 adds one app group option (the seed has no app groups; the
// request with that group goes to the real server). The firing panel tests (T-E2E-096,
// T-E2E-alerts-2) use the real alerts of the engine and the seed's FIRING alert (WO-25).
//
// T-E2E-099 starts shop-boot3 with the agent of the stack (the seed's application and instance,
// as T-SV-020e does), so the server tracks the application and its overview pushes carry live
// values; inside the Playwright image of e2e:docker there is no JVM, and the test then uses the
// applications the server still tracks from the seed (skipped with the reason when there is none).
import type { Page } from '@playwright/test';

import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import type { RecordedServerFrame, WsStub } from '../fixtures/ws';
import {
  expectOverviewState,
  isOverviewApi,
  openOverview,
  overviewFilters,
  overviewFiringAlerts,
  overviewFiringPanel,
  overviewKiosk,
  overviewOpensDashboard,
  overviewRowFields,
  overviewServerPanel,
  overviewSortPinned,
  overviewThirtyRowsFit,
  overviewViewToggle,
  raw,
  recordOverview,
  rewriteOverview,
  shownRows,
  sortMix,
  sparkTimes,
  worse,
  type OverviewAnswer,
} from '../required/overviewChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import { shopProblem, startShop } from '../stack/liveShop';
import { stopProcess, TRAFFIC_INSTANCE } from '../stack/seed/traffic';
import { httpGet, sleep } from '../stack/wait';

const OVERVIEW = { type: 'req', description: 'F-U17' };
const MINUTE = 60_000;

function req(...ids: string[]): { type: string; description: string }[] {
  return [OVERVIEW, ...ids.map((description) => ({ type: 'req', description }))];
}

/** True inside the pinned Linux image of e2e:docker (the only place baselines are valid). */
function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

test(
  'T-E2E-090 1366x768: 30 applications in the table without any scrolling (T-ACPT-16 automated part; real answer padded to 30 typed rows)',
  { annotation: req('05-2.10-6', 'F-U16', 'S-16') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewThirtyRowsFit({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-091 environment and group filters: URL env/group, the query of GET /overview, rows = its answer',
  { annotation: req('05-2.10-1', 'F-U16') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewFilters({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-092 card and table views; the choice survives a reload',
  { annotation: req('05-2.10-1') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewViewToggle({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-093 kiosk=1: no top bar or menu, the badge and the rows, the overview topic subscribed',
  { annotation: req('05-2.10-1', 'F-U21') },
  async ({ page, seed, apiAs, ws }, testInfo) => {
    await overviewKiosk({ page, seed, apiAs, testInfo, ws });
  },
);

test(
  'T-E2E-094 the name and ten items of every seeded application equal the same-question GET /overview answer, missing values as missing (evidence screenshot)',
  { annotation: req('05-2.10-2', 'D-23', 'SEC-60') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewRowFields({ page, seed, apiAs, testInfo });
    // Collected strings (names, environments, versions) are text: no markup made of them.
    expect(
      await page
        .locator(
          '[data-testid="overview-page"] img, [data-testid="overview-page"] script, [data-testid="overview-page"] iframe, [data-testid="overview-page"] a[href^="javascript"]',
        )
        .count(),
    ).toBe(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
    const file = testInfo.outputPath('overview.png');
    await page.screenshot({ path: file, fullPage: true });
    await testInfo.attach('overview.png', { path: file, contentType: 'image/png' });
  },
);

test(
  'T-E2E-095 sort by status, error rate, p95 and alerts with abnormal applications pinned (typed mix of UP/WARN/STALE/DOWN and firing counts on the real answer)',
  { annotation: req('05-2.10-3') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewSortPinned({ page, seed, apiAs, testInfo });
  },
);

// T-E2E-096 (WO-25, ADR-0010 C-212 (2)): before the alert engine the server answered
// alerts_firing_total null and this test checked "not available"; the engine now always counts, so
// on the real stack the test checks the list itself. The null state stays covered where it can
// still happen (a server without the engine): OverviewPage.test.tsx T-UI-007.
test(
  'T-E2E-096 the firing panel lists GET /overview/alerts (FIRING and ACKED, critical first, eight rows and more) and alerts_firing_total counts exactly those rows',
  { annotation: req('05-2.10-4', 'D-23') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewFiringPanel({ page, seed, apiAs, testInfo });
  },
);

test(
  "T-E2E-alerts-2 the seed's real FIRING alert is listed on the overview and counted in its app's alerts cell; a row opens /alerts?state=firing&alert=<event id>",
  { annotation: req('05-2.10-4', 'F-S06') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewFiringAlerts({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-097 the collection server panel equals server.* of the answer (channel failures of 24 h a value equal to GET /overview), unmeasured values not available',
  { annotation: req('05-2.10-4', 'D-23') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewServerPanel({ page, seed, apiAs, testInfo });
  },
);

test(
  'T-E2E-098 a row opens /apps/<id>/dashboard with the same from, to, kind, env and live',
  { annotation: req('05-2.10-5', 'F-U14') },
  async ({ page, seed, apiAs }, testInfo) => {
    await overviewOpensDashboard({ page, seed, apiAs, testInfo });
  },
);

// ---------------------------------------------------------------------------------------------
// T-E2E-099: live refresh from the real /ws (WO-22)
// ---------------------------------------------------------------------------------------------

/** Requests to the shop until stopped (a few per second), so the overview has live TPS. */
function driveTraffic(url: string): { stop(): Promise<number> } {
  const control = { running: true };
  let sent = 0;
  const loop = (async () => {
    while (control.running) {
      try {
        await httpGet(`${url}/api/version`, {}, 5000);
        sent += 1;
      } catch {
        // A slow answer is not this test's subject; the next request follows.
      }
      await sleep(200);
    }
  })();
  return {
    async stop() {
      control.running = false;
      await loop;
      return sent;
    },
  };
}

/** One application of an overview push (docs/14 §5.3, TopicPayloads.OverviewApp). */
interface PushApp {
  readonly app_id: number;
  readonly status?: string;
  readonly tps?: number | null;
  readonly p95_ms?: number | null;
  readonly error_rate?: number | null;
  readonly active?: number | null;
}

function overviewPush(f: RecordedServerFrame, id: string): { t: number; apps: PushApp[] } | null {
  const frame = f.frame;
  if (frame?.op !== 'push' || frame.topic !== 'overview' || frame.id !== id) {
    return null;
  }
  const apps = (frame.data as { apps?: unknown }).apps;
  return { t: Date.parse(frame.t), apps: Array.isArray(apps) ? (apps as PushApp[]) : [] };
}

/**
 * Differences between the shown rows and the push the page says it applied (data-live), on top of
 * the newest REST answer (src/features/overview/mergeLive.ts rule: tps, p95, error rate and active
 * from the push, the worse status of push and REST). Empty = the DOM shows that push.
 */
async function liveMismatches(
  page: Page,
  ws: WsStub,
  id: string,
  rest: OverviewAnswer,
  compared: { apps: number },
): Promise<string[]> {
  const root = page.getByTestId('overview-page');
  const before = await root.getAttribute('data-live');
  const rows = await shownRows(page);
  const after = await root.getAttribute('data-live');
  if (before === null || before !== after) {
    return [`data-live ${String(before)} -> ${String(after)} (no push applied yet or one arrived)`];
  }
  const liveT = Date.parse(before);
  if (liveT < Date.parse(rest.body.meta.generated_at)) {
    return ['the latest push is older than the REST answer (the page keeps the REST values)'];
  }
  const applied = ws
    .serverFrames()
    .map((f) => overviewPush(f, id))
    .find((p) => p !== null && p.t === liveT);
  if (applied === undefined || applied === null) {
    return [`no recorded server push at data-live ${before}`];
  }
  const out: string[] = [];
  compared.apps = 0;
  for (const live of applied.apps) {
    const row = rows.find((r) => r.appId === live.app_id);
    const restApp = rest.body.data.apps.find((a) => a.app_id === live.app_id);
    if (row === undefined || restApp === undefined) {
      continue;
    }
    compared.apps += 1;
    const pick = (push: number | null | undefined, restValue: number | null) =>
      raw(push === undefined ? restValue : push);
    const expected = {
      tps: pick(live.tps, restApp.tps),
      p95: pick(live.p95_ms, restApp.p95_ms),
      'error-rate': pick(live.error_rate, restApp.error_rate),
      active: pick(live.active, restApp.active),
    } as const;
    for (const [field, value] of Object.entries(expected)) {
      const shown = row.values[field as keyof typeof expected].value;
      if (shown !== value) {
        out.push(`${String(live.app_id)} ${field}: shown ${String(shown)}, push ${value}`);
      }
    }
    const status = live.status === undefined ? restApp.status : worse(restApp.status, live.status);
    if (row.status !== status) {
      out.push(`${String(live.app_id)} status: shown ${String(row.status)}, expected ${status}`);
    }
  }
  if (compared.apps === 0) {
    out.push('the applied push names no application of the table');
  }
  return out;
}

test.describe('live refresh from the real WebSocket server', () => {
  test.use({ wsMode: 'server' });

  test(
    'T-E2E-099 live: a real /ws overview push updates the rows without a REST refetch (DOM = the pushed values)',
    { annotation: req('F-U16', 'N-15') },
    async ({ page, ws, seed, session }, testInfo) => {
      test.setTimeout(6 * MINUTE);
      const problem = shopProblem(session.run_id);
      const shop =
        problem === null
          ? await startShop(session.run_id, {
              instance: TRAFFIC_INSTANCE,
              logFile: 'overview-live-shop-boot3.log',
            })
          : null;
      const traffic = shop === null ? null : driveTraffic(shop.url);
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      try {
        const recorder = recordOverview(page);
        const root = await openOverview(page, '/overview?live=1');
        const subscription = await ws.waitForSubscription('overview', 30_000);
        expect(subscription.params, 'the shell and the screen share one subscription').toEqual({});
        expect(
          ws.subscriptions('overview'),
          'one server subscription of the overview topic',
        ).toHaveLength(1);
        // From here on every REST refresh is held in the browser: what changes on the page comes
        // from the WebSocket.
        let heldRequests = 0;
        await page.route(isOverviewApi, async (route) => {
          heldRequests += 1;
          await held;
          try {
            await route.continue();
          } catch {
            // The page closed meanwhile.
          }
        });
        const holdAt = Date.now();
        const shownIds = new Set((await shownRows(page)).map((r) => r.appId));
        const wanted = shop === null ? null : seed.traffic.app_id;
        let first: RecordedServerFrame;
        try {
          first = await ws.waitForServerFrame(
            (f) =>
              f.at >= holdAt &&
              (overviewPush(f, subscription.id)?.apps ?? []).some((a) =>
                wanted === null
                  ? shownIds.has(a.app_id)
                  : a.app_id === wanted && typeof a.tps === 'number',
              ),
            shop === null ? 20_000 : 90_000,
            wanted === null
              ? 'an overview push naming an application of the table'
              : `an overview push with live TPS of the traffic app ${String(wanted)}`,
          );
        } catch (error) {
          // Without a JVM (e2e:docker) the push can only name applications the server still
          // tracks from the seed (10 minutes after their last data or state change).
          test.skip(
            shop === null,
            `${problem ?? ''}; the server tracks no application of the table now`,
          );
          throw error;
        }
        const firstT = overviewPush(first, subscription.id)?.t ?? Number.NaN;
        await expect
          .poll(async () => Date.parse((await root.getAttribute('data-live')) ?? '') || 0, {
            message: 'data-live reaches the server push time',
            timeout: 15_000,
          })
          .toBeGreaterThanOrEqual(firstT);
        const compared = { apps: 0 };
        await expect
          .poll(() => liveMismatches(page, ws, subscription.id, recorder.last(), compared), {
            message: 'rows = the applied server push on the REST answer',
            timeout: 20_000,
          })
          .toEqual([]);
        const restAfterHold = recorder.answers().filter((a) => a.at > holdAt + 1000);
        expect(restAfterHold, 'no REST answer reached the page after the hold').toEqual([]);
        if (wanted !== null) {
          const row = (await shownRows(page)).find((r) => r.appId === wanted);
          expect(row?.values.tps.value, 'live TPS of the traffic app').not.toBe('null');
        }
        const pushes = ws.serverFrames().filter((f) => overviewPush(f, subscription.id) !== null);
        testInfo.annotations.push({
          type: 'overview-live',
          description: `${String(pushes.length)} overview pushes recorded, ${String(compared.apps)} apps compared with the applied push, ${String(heldRequests)} REST refreshes held; ${shop === null ? `no shop (${problem ?? ''})` : `shop-boot3 with the agent at ${shop.url}`}`,
        });
      } finally {
        release();
        await page.unroute(isOverviewApi).catch(() => undefined);
        const sent = traffic === null ? 0 : await traffic.stop();
        if (shop !== null) {
          testInfo.annotations.push({ type: 'shop-requests', description: String(sent) });
          await stopProcess(shop.child);
        }
      }
    },
  );
});

// ---------------------------------------------------------------------------------------------
// Visual baselines (screenshot-linux-only, made in e2e:docker)
// ---------------------------------------------------------------------------------------------

test.describe('visual', () => {
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

  /** Per-run values: the range label and request ids. */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('error-state-request-id'),
    ];
  }

  test(
    'T-E2E-090a visual: the overview while its data loads',
    { annotation: req('F-U16') },
    async ({ page }) => {
      // The overview request is held until the screenshot is taken, then continues unchanged.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route(isOverviewApi, async (route) => {
        await held;
        await route.continue();
      });
      await page.goto('/overview');
      await expectOverviewState(page, 'loading');
      // The slow-query hint appears after 2 s (LoadingState): wait for it, so the image is stable.
      await expect(page.getByTestId('overview-loading')).toHaveAttribute('data-slow', 'true');
      // The overview has no time range to narrow: the hint only says it is still loading (C-218).
      await expect(page.getByTestId('loading-state-slow')).toHaveText('Still loading…');
      await expect(page).toHaveScreenshot('overview-loading.png', { mask: masks(page) });
      release();
      await expectOverviewState(page);
      await page.unroute(isOverviewApi);
    },
  );

  test(
    'T-E2E-090b visual: the overview of an environment without applications (real empty answer)',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page, apiAs }) => {
      const env = 'e2e-no-such-env';
      const api = await apiAs('viewer');
      expect((await api.data<{ apps: unknown[] }>('/overview', { env })).apps).toEqual([]);
      await openOverview(page, `/overview?env=${env}`, 'empty');
      await expect(page.getByTestId('overview-empty')).toBeVisible();
      await expect(page).toHaveScreenshot('overview-empty.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-090c visual: the overview when the server refuses the request (error state with the request id and retry)',
    { annotation: req('F-U16', '05-2.0-8') },
    async ({ page }) => {
      // The docs/14 §2.6 error envelope of a 400 answer (a 4xx: the page hooks annotate it).
      await page.route(isOverviewApi, (route) =>
        route.fulfill({
          status: 400,
          json: {
            error: {
              code: 'invalid_request',
              message: 'Invalid request.',
              request_id: 'e2e-overview-visual',
            },
          },
        }),
      );
      await openOverview(page, '/overview', 'error');
      const error = page.getByTestId('overview-error');
      await expect(error).toBeVisible();
      await expect(error.getByTestId('error-state-request-id')).toBeVisible();
      await expect(error.getByTestId('error-state-retry')).toBeVisible();
      await expect(page).toHaveScreenshot('overview-error.png', { mask: masks(page) });
      await page.unroute(isOverviewApi);
    },
  );

  test(
    'T-E2E-090d visual: the ready overview (typed rows of every status on the real answer, fixed server values)',
    { annotation: req('F-U16') },
    async ({ page }) => {
      // Fixed rows and server values: the image does not depend on the clock or the seed's
      // live state (the evidence of real data is T-E2E-094's overview.png).
      const rows = sortMix('prod', sparkTimes(Date.parse('2030-01-01T01:00:00Z')));
      await rewriteOverview(page, (body) => ({
        ...body,
        data: {
          // Fixed "not available" (out of the C-212 (2) contract, which always counts): the image
          // stays independent of the seeded alert, whose panel rows T-E2E-096 checks.
          alerts_firing_total: null as unknown as number,
          apps: rows,
          server: {
            ingest_lag_s: 0.8,
            disk_usage_ratio: null,
            channel_failures_24h: null,
            sessions: 3,
            cert_expiry_days: null,
          },
        },
      }));
      await openOverview(page, '/overview');
      await expect(page.locator('[data-testid^="overview-row-"]')).toHaveCount(rows.length);
      await expect(page).toHaveScreenshot('overview-normal.png', { mask: masks(page) });
      await page.unroute(isOverviewApi);
    },
  );
});
