// SPDX-License-Identifier: Apache-2.0
//
// Commands tab and thread dump viewer of the instance detail on the real stack (WO-37, docs/05
// §2.7-4 and §2.7-5, docs/15 §9.8, docs/14 §4.10, docs/16 §5, F-U08): the command round trip from
// the buttons (REST request → the agent session of the real collector → the agent's answer → the
// result encrypted in PostgreSQL with the stack's master key → REST result → the screen), the
// buttons of each role with the server's 403, the viewer against GET /commands/{id}/result of the
// same command (states, collapsed stacks, locks and deadlock, virtual threads, search, transaction
// links), the .txt download = format=text, the last 20 dumps and the truncation notice, the masked
// environment dump, the self diagnosis, error and expiry states, collected text as text, layout and
// accessibility.
//
// The agent is the dev-only command feed (e2e/fixtures/commandFeed.ts: app e2e-cmd, e2e-cmd-1
// answering normally, e2e-cmd-2 reporting its thread dump truncated; ActiveFeed --commands through
// the real collector) until WO-17's agent is in the stack; T-E2E-dumps-30 takes a dump of the real
// agent (shop-boot3 with SAMPLE_VT=true on JDK 21). Inside the Playwright image of e2e:docker there
// is no JVM: those tests skip with the reason; T-E2E-dumps-27 (stub answers on the seed's
// instance) runs everywhere. The visual baselines T-E2E-dumps-40/41 are in specs/screenshots.spec.ts.
// The checks and the oracle live in e2e/required/dumpsChecks.ts, which required/dumps.yaml
// (T-E2E-dumps-1, -2) runs too.
import { readFileSync } from 'node:fs';

import { MISSING_DASH } from '../../src/utils/missing';
import { expectNoCriticalA11y } from '../fixtures/a11y';
import { commandFeedLog, stopCommandFeed } from '../fixtures/commandFeed';
import { t } from '../fixtures/i18n';
import { apiAs, expect, test } from '../fixtures/stack';
import { expectUrl, horizontalOverflow } from '../fixtures/ui';
import {
  COMMAND_BUTTONS,
  commandEnd,
  commandList,
  commandResult,
  commandSlot,
  envelope,
  errorBody,
  expectDumpList,
  expectedDump,
  expectSearch,
  expectViewerEqualsResult,
  expectViewerOf,
  fakeCommandId,
  fixedDumpResult,
  historyRows,
  maskedEntries,
  olderDumps,
  openDump,
  openInstanceTab,
  readViewer,
  recentDoneDumps,
  requireCommandFeed,
  runFromTab,
  searchMatches,
  selfDiagnosisOf,
  serverAllows,
  sharedDump,
  shownButtons,
  signInAs,
  stubFixedDump,
  userIdOf,
  type CommandViewApi,
  type DumpsCheckContext,
} from '../required/dumpsChecks';
import { trafficInstance } from '../required/instanceChecks';
import { shopProblem, startShop } from '../stack/liveShop';
import type { Role } from '../stack/manifest';
import { retireInstancesNamed } from '../stack/retire';
import { stopProcess } from '../stack/seed/traffic';
import { httpGet } from '../stack/wait';

const INSTANCE = { type: 'req', description: 'F-U08' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [INSTANCE, ...ids.map((description) => ({ type: 'req', description }))];
}

/** Thread names a collapsed stack lists before "show more" (ThreadDumpViewer). */
const NAMES_SHOWN = 10;

/** Elements made of collected text if it were parsed as markup. */
const INJECTED = 'img, script, svg[onload], iframe, a[href^="javascript"], form';

test.afterAll(async ({ session, seed }, testInfo) => {
  testInfo.setTimeout(3 * 60_000);
  try {
    // The stopped feed archives its instances (C-241 (7)): no instance_down stays FIRING.
    await stopCommandFeed(() => apiAs(session, seed, 'operator'));
  } finally {
    await testInfo.attach('command-feed.log', {
      body: commandFeedLog(),
      contentType: 'text/plain',
    });
  }
});

test.describe('the operator', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-dumps-20 thread dump round trip from the commands tab: queued -> done through the real server, history row, the viewer = GET /commands/{id}/result',
    { annotation: req('05-2.7-4', '05-2.7-5', 'F-A12') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const operator = await as('operator');
      const instanceId = feed.instanceIds.normal;
      await openInstanceTab(page, feed.appId, instanceId, 'commands');
      expect(await shownButtons(page), "the operator's buttons").toEqual(COMMAND_BUTTONS.operator);
      // Every state the screen polled (GET /commands/{id}, every second, C-231 (3)).
      const polled: string[] = [];
      page.on('response', (response) => {
        const path = new URL(response.url()).pathname;
        if (
          response.request().method() === 'GET' &&
          /^\/api\/v1\/commands\/[0-9a-f-]{36}$/.test(path)
        ) {
          void response
            .json()
            .then((body: { data?: { status?: string } }) => {
              polled.push(body.data?.status ?? '?');
            })
            .catch(() => undefined);
        }
      });
      const started = Date.now();
      const commandId = await runFromTab(page, operator, instanceId, 'thread_dump');
      const roundTripMs = Date.now() - started;
      expect(polled.at(-1), 'the last polled state').toBe('done');
      expect(
        polled.every((s) => ['queued', 'sent', 'done'].includes(s)),
        polled.join(','),
      ).toBe(true);
      const view = await commandEnd(operator, commandId);
      expect(view.status).toBe('done');
      expect(view.requested_by_name).toBe(seed.users.operator);
      expect(view.result_size ?? 0, 'an encrypted result of some size').toBeGreaterThan(0);
      // The history shows it with the requester's name.
      const row = page.getByTestId(`command-row-${commandId}`);
      await expect(row).toHaveAttribute('data-status', 'done', { timeout: 15_000 });
      await expect(page.getByTestId(`command-requester-${commandId}`)).toHaveText(
        seed.users.operator,
      );
      expect((await historyRows(page))[0], 'the newest command first').toBe(commandId);
      // Opening it shows the viewer of exactly this result.
      await page.getByTestId('command-run-open-dump').click();
      await expectUrl(
        page,
        (s) => s.params.get('tab') === 'dumps' && s.params.get('cmd') === commandId,
        'the dumps tab of this dump',
      );
      await expectViewerOf(page, commandId);
      const result = await commandResult(operator, commandId);
      expect(result.type).toBe('thread_dump');
      await expectViewerEqualsResult(page, feed.appId, result, 'round trip dump');
      testInfo.annotations.push({
        type: 'round-trip',
        description: `thread_dump ${commandId} on ${String(instanceId)}: states ${polled.join('>')}, done ${String(roundTripMs)} ms after the click (reference, shared laptop), ${String(result.threads?.length)} threads, ${String(view.result_size)} bytes`,
      });
    },
  );

  test(
    'T-E2E-dumps-22 collapsed identical stacks xN, deadlock badge and lock rows, virtual threads, search by name, class and method, transaction links = the result',
    { annotation: req('05-2.7-5') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const dump = await sharedDump(ctx, feed, 'normal');
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      const want = await expectViewerEqualsResult(page, feed.appId, dump.result, 'feed dump');
      const threads = dump.result.threads ?? [];
      // The idle pool threads of one stack: one entry ×N.
      const idle = feed.commands.idle_pool_threads;
      const pool = page.locator(`[data-testid="dump-stack"][data-count="${String(idle)}"]`);
      await expect(pool).toHaveCount(1);
      await expect(pool.getByTestId('dump-stack-count')).toHaveText(`×${String(idle)}`);
      await expect(pool.getByTestId('dump-thread')).toHaveCount(Math.min(idle, NAMES_SHOWN));
      // The deadlock: badge, the cycle with both threads, their locks marked.
      expect(want.deadlockNames).toEqual([feed.commands.deadlock_threads]);
      await expect(page.getByTestId('dump-deadlock-badge')).toBeVisible();
      for (const name of feed.commands.deadlock_threads) {
        await expect(page.getByTestId('dump-deadlocks')).toContainText(name);
      }
      await expect(
        page.locator('[data-testid="dump-lock-row"][data-deadlocked="true"]'),
        'both locks of the cycle',
      ).toHaveCount(2);
      await expect(
        page
          .locator('[data-testid="dump-stack"]')
          .filter({ has: page.getByTestId('dump-stack-deadlock') }),
      ).toHaveCount(2);
      // Virtual threads are told apart.
      expect(want.virtual).toBeGreaterThanOrEqual(2);
      await expect(page.getByTestId('dump-virtual-count')).toBeVisible();
      const vtStacks = await page
        .locator('[data-testid="dump-stack"]')
        .evaluateAll((els) =>
          els.reduce((sum, e) => sum + Number(e.getAttribute('data-virtual')), 0),
        );
      expect(vtStacks, 'virtual threads marked on their stacks').toBe(want.virtual);
      // Search by class and method, by thread name, by the deep stack's class.
      await expectSearch(page, threads, 'LinkedBlockingQueue.take');
      await expectSearch(page, threads, 'order-events-vt');
      await expectSearch(page, threads, 'CategoryNode.visit');
      expect(searchMatches(threads, 'CategoryNode.visit')).toBe(1);
      // A deep link with q shows the search (URL state).
      await page.goto(
        `${new URL(page.url()).pathname}?tab=dumps&cmd=${dump.commandId}&q=${encodeURIComponent(feed.commands.deadlock_threads[1] ?? '')}`,
      );
      await expectViewerOf(page, dump.commandId);
      await expect(page.getByTestId('dump-search')).toHaveValue(
        feed.commands.deadlock_threads[1] ?? '',
      );
      await expect(page.getByTestId('dump-search-count')).toHaveAttribute('data-count', '1');
      // Transaction links: one per thread with a tx_id (= the result), opening the detail.
      expect(want.txIds.length, 'threads running a transaction').toBeGreaterThan(0);
      const link = page.getByTestId('dump-tx-link').first();
      const href = (await link.getAttribute('href')) ?? '';
      await expect(page.getByTestId('dump-transactions')).toContainText(
        t('commands:viewer.snapshotNote'),
      );
      await link.click();
      await expectUrl(page, (s) => s.path === href, 'the transaction detail of the thread');
    },
  );

  test(
    'T-E2E-dumps-23 the .txt download equals GET /commands/{id}/result?format=text; the file name holds only the instance id and the time',
    { annotation: req('05-2.7-5', 'SEC-62') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const dump = await sharedDump(ctx, feed, 'normal');
      const operator = await as('operator');
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      const downloaded = page.waitForEvent('download');
      await page.getByTestId('dump-download').click();
      const download = await downloaded;
      expect(download.suggestedFilename()).toMatch(
        new RegExp(`^argos-thread-dump-${String(dump.instanceId)}-\\d{8}T\\d{6}Z\\.txt$`),
      );
      const file = await download.path();
      const text = (await commandResult(operator, dump.commandId, 'text')).text ?? '';
      expect(text.length, 'the text form of the dump').toBeGreaterThan(0);
      expect(readFileSync(file, 'utf8'), 'the download = format=text').toBe(text);
      await expect(page.getByTestId('dump-download-error')).toHaveCount(0);
    },
  );

  test(
    'T-E2E-dumps-24 the truncated dump of e2e-cmd-2 shows the notice; the list keeps the last 20 done dumps of the history',
    { annotation: req('05-2.7-5') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const dump = await sharedDump(ctx, feed, 'truncated');
      const operator = await as('operator');
      expect(dump.result.truncated, 'the agent reported the dump truncated').toBe(true);
      expect(dump.view.truncated).toBe(true);
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      const read = await readViewer(page);
      expect(read.truncated).toBe(true);
      await expectViewerEqualsResult(page, feed.appId, dump.result, 'truncated dump');
      await expect(page.getByTestId('dump-truncated')).toHaveText(t('commands:viewer.truncated'));
      await expect(page.getByTestId(`dumps-item-${dump.commandId}`)).toHaveAttribute(
        'data-truncated',
        'true',
      );
      const list = await commandList(operator, dump.instanceId);
      await expectDumpList(page, list);
      // More than 20 done dumps (older ones added to the server's answer): the newest 20 only.
      const real = list.find((c) => c.command_id === dump.commandId) ?? dump.view;
      const extended: CommandViewApi[] = [...list, ...olderDumps(real, 25)];
      await page.route(
        `**/api/v1/instances/${String(dump.instanceId)}/commands**`,
        async (route) => {
          if (route.request().method() !== 'GET') {
            await route.continue();
            return;
          }
          const response = await route.fetch();
          const body = (await response.json()) as { data: CommandViewApi[]; meta: unknown };
          await route.fulfill({ response, json: { ...body, data: extended } });
        },
      );
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      const want = recentDoneDumps(extended);
      expect(want).toHaveLength(20);
      await expectDumpList(page, extended);
      await expect(page.getByTestId('dumps-list')).toHaveAttribute('data-count', '20');
      await page.unroute(`**/api/v1/instances/${String(dump.instanceId)}/commands**`);
      // The history marks the truncated result too.
      await openInstanceTab(page, feed.appId, dump.instanceId, 'commands');
      await expect(page.getByTestId(`command-status-${dump.commandId}`)).toContainText(
        t('commands:history.truncated'),
      );
    },
  );

  test(
    'T-E2E-dumps-26 self diagnosis from the commands tab: modules and effective configuration with sources = the result; the environment tab reads it only on request (C-234 (3))',
    { annotation: req('05-2.7-3', '05-2.7-4') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const operator = await as('operator');
      const instanceId = feed.instanceIds.normal;
      await openInstanceTab(page, feed.appId, instanceId, 'commands');
      const commandId = await runFromTab(page, operator, instanceId, 'self_diagnosis');
      await page.getByTestId('command-run-open-result').click();
      const panel = page.getByTestId('command-result');
      await expect(panel).toHaveAttribute('data-type', 'self_diagnosis');
      await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      const result = await commandResult(operator, commandId);
      const diag = selfDiagnosisOf(result.text ?? '{}');
      expect(diag.modules.length, 'modules in the result').toBeGreaterThan(0);
      expect(diag.config.length, 'effective configuration in the result').toBeGreaterThan(0);
      await expect(panel.getByTestId('self-diagnosis-report')).toHaveAttribute('data-format', '1');
      const rows = async (testId: string): Promise<string[][]> =>
        panel
          .getByTestId(testId)
          .locator('tbody tr')
          .evaluateAll((trs) => trs.map((tr) => [...tr.children].map((c) => c.textContent.trim())));
      expect(await rows('self-diagnosis-modules'), 'modules = the result').toEqual(
        diag.modules.map((m) => [m.name, m.state, String(m.errors), m.reason ?? MISSING_DASH]),
      );
      expect(await rows('self-diagnosis-config'), 'effective configuration = the result').toEqual(
        diag.config.map((c) => [c.key, c.value ?? MISSING_DASH, c.source ?? MISSING_DASH]),
      );
      // The environment tab's latest self diagnosis is this one, read only on request
      // (C-234 (3): opening the tab reads no result, the result read is audited).
      const resultReads: string[] = [];
      page.on('request', (request) => {
        if (/\/api\/v1\/commands\/[^/]+\/result/.test(request.url())) {
          resultReads.push(request.url());
        }
      });
      await openInstanceTab(page, feed.appId, instanceId, 'env');
      const latest = page.getByTestId('latest-self_diagnosis');
      await expect(latest).toHaveAttribute('data-state', 'hidden', { timeout: 30_000 });
      await expect(latest).toHaveAttribute('data-command-id', commandId);
      expect(resultReads, 'no result read when the tab opens').toEqual([]);
      await latest.getByTestId('latest-self_diagnosis-show').click();
      await expect(latest).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      expect(resultReads.filter((u) => u.includes(commandId)).length).toBeGreaterThan(0);
    },
  );

  test(
    'T-E2E-dumps-27 stub answers: instance offline, rate limited with Retry-After, expired by the TTL; a truncated stub dump shows the notice (runs in e2e:docker too)',
    { annotation: req('05-2.7-4', '05-2.0') },
    async ({ page, seed, apiAs: as }) => {
      const target = trafficInstance(seed);
      const operator = await as('operator');
      await openInstanceTab(page, target.appId, target.instanceId, 'commands');
      const post = `**/api/v1/instances/${String(target.instanceId)}/commands`;
      const fake = fakeCommandId(0x27);
      let mode: 'offline' | 'rate' | 'expired' = 'offline';
      await page.route(post, async (route) => {
        if (route.request().method() !== 'POST') {
          await route.continue();
          return;
        }
        if (mode === 'offline') {
          await route.fulfill({
            status: 409,
            json: errorBody('instance_offline', 'The instance is not connected'),
          });
        } else if (mode === 'rate') {
          await route.fulfill({
            status: 429,
            headers: { 'Retry-After': '17' },
            json: errorBody('rate_limited', 'Too many commands for this instance'),
          });
        } else {
          await route.fulfill({
            status: 202,
            json: envelope({ command_id: fake, status: 'queued', ttl_s: 60 }),
          });
        }
      });
      const now = Date.now();
      await page.route(`**/api/v1/commands/${fake}`, async (route) => {
        await route.fulfill({
          json: envelope({
            command_id: fake,
            instance_id: target.instanceId,
            type: 'self_diagnosis',
            status: 'expired',
            error: 'ttl',
            args: {},
            requested_by: await userIdOf(operator),
            requested_by_name: seed.users.operator,
            requested_at: new Date(now - 61_000).toISOString(),
            sent_at: null,
            finished_at: new Date(now - 1000).toISOString(),
            expires_at: new Date(now - 1000).toISOString(),
            duration_ms: null,
            result_size: null,
            truncated: null,
          }),
        });
      });
      const feedback = page.getByTestId('command-feedback');
      const request = async (): Promise<void> => {
        await page.getByTestId('command-run-self_diagnosis').click();
        await page.getByTestId('command-dialog-confirm').click();
      };
      await request();
      await expect(feedback).toHaveAttribute('data-state', 'request-error');
      await expect(feedback).toHaveText(t('commands:error.instance_offline'));
      mode = 'rate';
      await request();
      await expect(feedback).toHaveText(
        t('commands:error.rate_limitedWait', 'en', { seconds: '17' }),
      );
      mode = 'expired';
      await request();
      await expect(feedback).toHaveAttribute('data-state', 'expired');
      await expect(feedback).toHaveAttribute('data-reason', 'ttl');
      await expect(feedback).toHaveText(
        t('commands:status.withReason', 'en', {
          status: t('commands:status.expired'),
          reason: t('commands:reason.ttl'),
        }),
      );
      await page.unroute(post);
      // A truncated dump answered by stubs (the same fixture as the visual baseline T-E2E-dumps-41).
      const cmd = await stubFixedDump(page, target.instanceId, await userIdOf(operator), true);
      await openDump(page, target.appId, target.instanceId, cmd);
      await expectViewerEqualsResult(
        page,
        target.appId,
        fixedDumpResult(target.instanceId, cmd, true),
        'stub dump',
      );
      await expect(page.getByTestId('dump-truncated')).toHaveText(t('commands:viewer.truncated'));
      expect(expectedDump(fixedDumpResult(target.instanceId, cmd, true)).truncated).toBe(true);
    },
  );

  test(
    'T-E2E-dumps-28 collected text of the dump (XSS thread name and frame) renders as text: no markup, no __xss',
    { annotation: req('05-2.7-5', 'SEC-60', 'SEC-61'), tag: '@security' },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const dump = await sharedDump(ctx, feed, 'normal');
      const xss = (dump.result.threads ?? []).find((th) => th.name === feed.commands.xss_thread);
      expect(xss, 'the dump carries the XSS thread').toBeDefined();
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      await expectSearch(page, dump.result.threads ?? [], 'ARGOS_SEED');
      await page.getByTestId('dump-search').fill('ARGOS_SEED');
      await expectUrl(page, (s) => s.params.get('q') === 'ARGOS_SEED', 'q=ARGOS_SEED');
      const viewer = page.getByTestId('dump-viewer');
      await expect(viewer).toContainText(feed.commands.xss_thread);
      for (const frame of (xss?.frames ?? []).filter((f) => f.includes('ARGOS_SEED'))) {
        await expect(viewer, `frame as exactly this text: ${frame}`).toContainText(frame);
      }
      expect(await viewer.locator(INJECTED).count(), 'elements made of collected text').toBe(0);
      expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
    },
  );

  test(
    'T-E2E-dumps-29 1366x768 without horizontal scroll and axe critical 0 on the dump viewer and the commands tab (evidence screenshot dumps.png)',
    { annotation: req('05-2.0', 'F-U16') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'operator' };
      const feed = await requireCommandFeed(ctx);
      const dump = await sharedDump(ctx, feed, 'normal');
      expect(page.viewportSize()).toEqual({ width: 1366, height: 768 });
      await openDump(page, feed.appId, dump.instanceId, dump.commandId);
      let overflow = await horizontalOverflow(page);
      expect(overflow.scrollWidth, 'dumps: scrollWidth <= clientWidth').toBeLessThanOrEqual(
        overflow.clientWidth,
      );
      await expectNoCriticalA11y(page, testInfo, 'dumps');
      const file = testInfo.outputPath('dumps.png');
      await page.screenshot({ path: file, fullPage: true });
      await testInfo.attach('dumps.png', { path: file, contentType: 'image/png' });
      await openInstanceTab(page, feed.appId, dump.instanceId, 'commands');
      await expect(page.getByTestId(`command-row-${dump.commandId}`)).toBeVisible();
      overflow = await horizontalOverflow(page);
      expect(overflow.scrollWidth, 'commands: scrollWidth <= clientWidth').toBeLessThanOrEqual(
        overflow.clientWidth,
      );
      await expectNoCriticalA11y(page, testInfo, 'commands');
    },
  );
});

test(
  'T-E2E-dumps-21 buttons per role (viewer none, operator 3, admin 5) and the server agrees (403 for the others)',
  { annotation: req('05-2.7-4', 'SEC-21') },
  async ({ page, seed, apiAs: as }, testInfo) => {
    const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'viewer' };
    const feed = await requireCommandFeed(ctx);
    const instanceId = feed.instanceIds.normal;
    for (const role of ['viewer', 'operator', 'admin'] as const satisfies readonly Role[]) {
      await signInAs(page, as, role);
      await openInstanceTab(page, feed.appId, instanceId, 'commands');
      expect(await shownButtons(page), `${role}: buttons`).toEqual(COMMAND_BUTTONS[role]);
      expect(await serverAllows(await as(role), instanceId), `${role}: the server agrees`).toEqual(
        COMMAND_BUTTONS[role],
      );
      if (role === 'viewer') {
        // A viewer gets the reason instead of the tabs' contents (no buttons, no history, no dumps).
        await expect(page.getByTestId('instance-operator-only')).toHaveText(
          t('instance:page.operatorOnly'),
        );
        await expect(page.getByTestId('instance-commands')).toHaveCount(0);
        await openInstanceTab(page, feed.appId, instanceId, 'dumps');
        await expect(page.getByTestId('instance-operator-only')).toHaveText(
          t('instance:page.operatorOnly'),
        );
        await expect(page.getByTestId('instance-dumps')).toHaveCount(0);
      } else {
        await expect(page.getByTestId('command-history')).toBeVisible();
        await expect(page.getByTestId('command-audit-link')).toHaveCount(role === 'admin' ? 1 : 0);
      }
    }
  },
);

test.describe('the administrator', () => {
  test.use({ role: 'admin' });

  test(
    'T-E2E-dumps-25 environment dump (admin): masked values ****(len=N) as received, never the raw secret; the operator may not read it',
    { annotation: req('05-2.7-3', '05-2.7-4', 'SEC-20', 'SEC-21', 'SEC-60'), tag: '@security' },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const ctx: DumpsCheckContext = { page, seed, testInfo, apiAs: as, role: 'admin' };
      const feed = await requireCommandFeed(ctx);
      const admin = await as('admin');
      const instanceId = feed.instanceIds.normal;
      const { env_secret_masked: masked, env_secret_sentinel: sentinel } = feed.commands;
      await openInstanceTab(page, feed.appId, instanceId, 'commands');
      const commandId = await runFromTab(page, admin, instanceId, 'env_dump');
      await page.getByTestId('command-run-open-result').click();
      const panel = page.getByTestId('command-result');
      await expect(panel).toHaveAttribute('data-type', 'env_dump');
      await expect(panel).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      const result = await commandResult(admin, commandId);
      const text = result.text ?? '';
      expect(text.includes(sentinel), 'the stored result never holds the raw secret').toBe(false);
      const want = maskedEntries(text, masked);
      expect(want.env, 'masked environment variables in the result').toBeGreaterThan(0);
      await expect(
        panel.locator('[data-testid="env-dump-env"] tr[data-masked="true"]'),
      ).toHaveCount(want.env);
      await expect(
        panel.locator('[data-testid="env-dump-system-properties"] tr[data-masked="true"]'),
      ).toHaveCount(want.properties);
      await expect(panel.getByTestId('env-dump-env')).toContainText(masked);
      await expect(panel.getByTestId('env-dump-jvm-arguments')).toContainText(masked);
      expect((await page.content()).includes(sentinel), 'the raw secret on the page').toBe(false);
      expect(await panel.locator(INJECTED).count(), 'elements made of collected values').toBe(0);
      expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
      // The environment tab shows the latest one to the administrator.
      await openInstanceTab(page, feed.appId, instanceId, 'env');
      const latest = page.getByTestId('latest-env_dump');
      await expect(latest).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      await expect(latest).toHaveAttribute('data-command-id', commandId);
      await expect(latest.locator('tr[data-masked="true"]')).toHaveCount(
        want.env + want.properties,
      );
      expect((await page.content()).includes(sentinel)).toBe(false);
      // An operator neither sees it nor reads it through the API.
      const operator = await as('operator');
      await operator.request('GET', `/commands/${commandId}/result`, { expect: [403] });
      await signInAs(page, as, 'operator');
      await openInstanceTab(page, feed.appId, instanceId, 'env');
      await expect(page.getByTestId('instance-env-dump-denied')).toHaveText(
        t('instance:env.dump.adminOnly'),
      );
      await expect(page.getByTestId('latest-env_dump')).toHaveCount(0);
    },
  );
});

// ---------------------------------------------------------------------------------------------
// T-E2E-dumps-30: the real agent (WO-17) answers the thread dump of the viewer
// ---------------------------------------------------------------------------------------------

/** Instance name of the shop of T-E2E-dumps-30 (apart from the other specs' shops). */
const DUMPS_30_INSTANCE = 'e2e-shop-dumps-30';
/** A running request on a virtual thread (SAMPLE_VT=true): its thread carries the tx_id. */
const DUMPS_30_PATH = '/api/slow?ms=30000&sql=1';

test.describe('the real agent', () => {
  test.use({ role: 'operator' });

  test(
    'T-E2E-dumps-30 real agent round trip: shop-boot3 (JDK 21, virtual threads) answers the thread dump requested in the dumps tab; the viewer = the result, virtual threads and the running transaction shown',
    { annotation: req('05-2.7-5', 'F-A12') },
    async ({ page, seed, session, apiAs: as }, testInfo) => {
      test.setTimeout(8 * 60_000);
      const problem = shopProblem(session.run_id);
      test.skip(problem !== null, problem ?? '');
      const operator = await as('operator');
      const appId = seed.traffic.app_id;
      const shop = await startShop(session.run_id, {
        instance: DUMPS_30_INSTANCE,
        logFile: 'dumps-30-shop-boot3.log',
        env: { SAMPLE_VT: 'true' },
      });
      const slow = httpGet(`${shop.url}${DUMPS_30_PATH}`, {}, 60_000).catch(
        (error: unknown) => error,
      );
      try {
        let instanceId = 0;
        await expect
          .poll(
            async () => {
              const list = await operator.data<
                { instance_id: number; name: string; state: string }[]
              >(`/apps/${String(appId)}/instances`);
              const found = list.find((i) => i.name === DUMPS_30_INSTANCE);
              instanceId = found?.instance_id ?? 0;
              return found?.state ?? 'absent';
            },
            { message: `${DUMPS_30_INSTANCE} is UP`, timeout: 90_000, intervals: [1000] },
          )
          .toBe('UP');
        await openInstanceTab(page, appId, instanceId, 'dumps');
        await commandSlot(operator, instanceId);
        await page.getByTestId('command-run-thread_dump').click();
        await page.getByTestId('command-dialog-confirm').click();
        const url = await expectUrl(
          page,
          (s) => /^[0-9a-f-]{36}$/.test(s.params.get('cmd') ?? ''),
          'the new dump in the URL',
        );
        const commandId = url.params.get('cmd') ?? '';
        const view = await commandEnd(operator, commandId);
        expect(view.status, `the real agent's answer: ${String(view.error)}`).toBe('done');
        await expectViewerOf(page, commandId, 30_000);
        const result = await commandResult(operator, commandId);
        const want = await expectViewerEqualsResult(page, appId, result, 'real agent dump');
        expect(want.virtual, 'virtual threads of the shop (SAMPLE_VT=true)').toBeGreaterThanOrEqual(
          1,
        );
        expect(want.txIds.length, 'the running request with its tx_id').toBeGreaterThanOrEqual(1);
        testInfo.annotations.push({
          type: 'real-agent-dump',
          description: `${commandId}: ${String(want.total)} threads, ${String(want.virtual)} virtual, ${String(want.txIds.length)} with a tx_id, ${String(view.result_size)} bytes, ${String(view.duration_ms)} ms`,
        });
      } finally {
        await stopProcess(shop.child);
        await slow;
        // C-241 (7): the stopped shop's instance never stays FIRING as instance_down.
        await retireInstancesNamed(operator, appId, [DUMPS_30_INSTANCE]);
      }
    },
  );
});
