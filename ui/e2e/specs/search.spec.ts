// SPDX-License-Identifier: Apache-2.0
//
// Global search `/search` end to end on the real stack (WO-39, docs/15 §9.12, docs/14 §4.11,
// F-U14, T-ACPT-12 automatic part). The screen runs as the seeded viewer; the API answer of the
// same question is asked as the administrator (search is a HEAVY call, 10 a minute per user,
// docs/16 §7.13, so the screen keeps its budget).
//
// - T-E2E-search-1: the seed's transaction id opens its detail at once (one match = GET /search).
// - T-E2E-search-2: the seed's trace id opens the trace view at once.
// - T-E2E-search-3 (T-ACPT-12): `user:<id>` over a range lists the transactions of that user =
//   GET /search; the ids come from shop-boot3 with the stack's agent collecting plain user ids
//   from a header (an application of this file's own, deleted afterwards). The time to the list is
//   recorded (laptop: INVALID; the 3 s of N-24 is a 사람 확인 item).
// - T-E2E-search-4: `req:` over more than 24 h sends nothing and says why.
// - T-E2E-search-5: a text search lists services, SQL and instances = GET /search.
// - T-E2E-search-6: no match names the next action.
// - T-E2E-search-7 (T-ACPT-12): an empty user id search says when the instances do not collect user
//   ids (only the instance lists are routed: every user_id_mode off).
// - T-E2E-search-8: Ctrl+K in the top bar, Enter opens /search?q=, Back returns.
import { t } from '../fixtures/i18n';
import { expect, iso, test, apiAs } from '../fixtures/stack';
import { expectShell, expectUrl, focusPage } from '../fixtures/ui';
import {
  createApp,
  deleteAppQuietly,
  issueToken,
  ownShopProblem,
  runSuffix,
  startOwnShop,
  writeTokenFile,
  type ApiApp,
  type OwnShop,
} from '../required/adminChecks';
import {
  apiSearchQuery,
  expectResultRows,
  matchKey,
  openSearch,
  SEARCH_ORACLE_ROLE,
} from '../required/searchChecks';
import type { ApiSession } from '../stack/api';
import { httpGet, waitFor } from '../stack/wait';

const SEARCH = { type: 'req', description: 'F-U14' };

function req(...ids: string[]): { type: string; description: string }[] {
  return [SEARCH, ...ids.map((description) => ({ type: 'req', description }))];
}

const MINUTE = 60_000;

test.describe('global search (WO-39)', () => {
  test(
    'T-E2E-search-1 a transaction id typed in the search opens its detail at once (one match of GET /search)',
    { annotation: req() },
    async ({ page, seed, apiAs: as }) => {
      const tx = seed.tx.captured_sql_stack;
      const oracle = await as(SEARCH_ORACLE_ROLE);
      const answer = await apiSearchQuery(oracle, {
        q: tx.tx_id,
        type: 'auto',
        from: iso(Date.now() - 15 * MINUTE),
        to: iso(Date.now()),
      });
      expect(answer.type).toBe('tx');
      expect(answer.matches.length, 'one match').toBe(1);
      await page.goto('/search');
      await expectShell(page);
      await page.getByTestId('search-input').fill(tx.tx_id);
      await expect(page.getByTestId('search-kind')).toHaveAttribute('data-kind', 'tx');
      await page.getByTestId('search-input').press('Enter');
      await expectUrl(
        page,
        (s) => s.path === `/apps/${String(tx.app_id)}/transactions/${tx.tx_id}`,
        'the transaction detail',
      );
    },
  );

  test(
    'T-E2E-search-2 a trace id opens the trace view at once',
    { annotation: req('F-U03') },
    async ({ page, seed, apiAs: as }) => {
      const trace = seed.tx.trace_root_two_apps.trace_id;
      const oracle = await as(SEARCH_ORACLE_ROLE);
      const answer = await apiSearchQuery(oracle, {
        q: trace,
        type: 'auto',
        from: iso(Date.now() - 15 * MINUTE),
        to: iso(Date.now()),
      });
      expect(answer.matches.length, 'one match').toBe(1);
      await page.goto(`/search?q=${trace}`);
      await expectUrl(page, (s) => s.path === `/traces/${trace.toLowerCase()}`, 'the trace view');
    },
  );

  test(
    'T-E2E-search-4 a request id search over more than 24 h sends nothing and asks for a shorter range',
    { annotation: req() },
    async ({ page }) => {
      const sent: string[] = [];
      page.on('request', (r) => {
        if (new URL(r.url()).pathname === '/api/v1/search') {
          sent.push(r.url());
        }
      });
      await page.goto('/search?q=req:wo39-request&range=7d');
      await expectShell(page);
      await expect(page.getByTestId('search-range-required')).toBeVisible();
      await expect(page.getByTestId('search-kind')).toHaveAttribute('data-kind', 'request');
      expect(sent).toEqual([]);
    },
  );

  test(
    'T-E2E-search-5 a text search lists the services, SQL and instances of GET /search',
    { annotation: req('SEC-60') },
    async ({ page, seed, apiAs: as }, testInfo) => {
      const q = seed.tx.captured_sql_stack.service ?? '';
      expect(q.length).toBeGreaterThan(2);
      const { params, result } = await openSearch(page, `/search?q=${encodeURIComponent(q)}`);
      const replay = await apiSearchQuery(
        await as(SEARCH_ORACLE_ROLE),
        Object.fromEntries(params.entries()),
      );
      expect(replay.matches.map(matchKey)).toEqual(result.matches.map(matchKey));
      expect(result.matches.length).toBeGreaterThan(0);
      await expectResultRows(page, result.matches);
      const first = page.getByTestId('search-open').first();
      await expect(first).toBeVisible();
      // Evidence screenshot attached to the report (copied to the PM archive at integration).
      const evidencePath = testInfo.outputPath('search-text-results.png');
      await page.screenshot({ path: evidencePath, fullPage: true });
      await testInfo.attach('search-text-results.png', {
        path: evidencePath,
        contentType: 'image/png',
      });
    },
  );

  test(
    'T-E2E-search-6 a search without matches says so and names the next action',
    { annotation: req() },
    async ({ page }) => {
      const { result } = await openSearch(page, '/search?q=zzwo39nothingmatches');
      expect(result.matches).toEqual([]);
      await expect(page.getByTestId('search-empty')).toContainText(t('search:empty.title'));
      await expect(page.getByTestId('search-empty')).toContainText(
        t('search:empty.descriptionText'),
      );
      await expect(page.getByTestId('search')).toHaveAttribute('data-state', 'empty');
    },
  );

  test(
    'T-E2E-search-7 T-ACPT-12: an empty user id search says that the instances do not collect user ids',
    { annotation: req() },
    async ({ page, session }) => {
      // Only the instance lists are routed: every instance reports user_id_mode off.
      await page.route(
        (url) => /^\/api\/v1\/apps\/\d+\/instances$/.test(url.pathname),
        async (route) => {
          const response = await route.fetch();
          const body = (await response.json()) as { data: { user_id_mode: string | null }[] };
          await route.fulfill({
            response,
            json: { ...body, data: body.data.map((i) => ({ ...i, user_id_mode: 'off' })) },
          });
        },
      );
      const from = iso(Date.now() - 60 * MINUTE);
      const to = iso(Date.now());
      const { result } = await openSearch(
        page,
        `/search?q=${encodeURIComponent(`user:wo39-nobody-${runSuffix(session.run_id)}`)}&from=${from}&to=${to}`,
      );
      expect(result.matches).toEqual([]);
      const hint = page.getByTestId('search-not-collected');
      await expect(hint).toHaveText(t('search:notCollected.all'));
      await expect(hint).toHaveAttribute('data-off', (await hint.getAttribute('data-total')) ?? '');
    },
  );

  test(
    'T-E2E-search-8 Ctrl+K focuses the top bar search; Enter opens /search?q= with the text; Back returns',
    { annotation: req('F-U16') },
    async ({ page, seed }) => {
      const start = `/apps/${String(seed.primary_app_id)}/dashboard`;
      await page.goto(start);
      await expectShell(page);
      await focusPage(page);
      await page.keyboard.press('Control+k');
      const box = page.getByTestId('global-search');
      await expect(box).toBeFocused();
      await box.fill('checkout');
      await box.press('Enter');
      const s = await expectUrl(page, (x) => x.path === '/search', '/search');
      expect(s.params.get('q')).toBe('checkout');
      await expect(page.getByTestId('search-input')).toHaveValue('checkout');
      await page.goBack();
      await expectUrl(page, (x) => x.path === start, 'back on the dashboard');
    },
  );
});

/** The user ids of T-E2E-search-3 and the shop that reports them. */
interface UserSearch {
  readonly app: ApiApp;
  readonly shop: OwnShop;
  readonly userId: string;
  readonly from: number;
  readonly to: number;
  readonly admin: ApiSession;
}

let users: UserSearch | null = null;

test.describe('user id search on a real agent (WO-39)', () => {
  test.beforeAll(async ({ session, seed }, testInfo) => {
    const problem = ownShopProblem(session.run_id);
    test.skip(problem !== null, problem ?? '');
    testInfo.setTimeout(4 * 60_000);
    const admin = await apiAs(session, seed, 'admin');
    const suffix = runSuffix(session.run_id);
    const app = await createApp(admin, { name: `wo39-search-${suffix}`, env: 'e2e' });
    try {
      const issued = await issueToken(admin, app.app_id);
      const tokenFile = writeTokenFile(session.run_id, `wo39-search-${suffix}.token`, issued.token);
      const shop = await startOwnShop(
        session.run_id,
        admin,
        app,
        tokenFile,
        `wo39-search-${suffix}`,
        {
          jvmArgs: [
            '-Dargos.capture.user-id.mode=plain',
            '-Dargos.capture.user-id.source=header:X-User-Id',
          ],
        },
      );
      const userId = `wo39-user-${suffix}`;
      const from = Date.now() - MINUTE;
      for (let i = 0; i < 3; i++) {
        const response = await httpGet(
          `${shop.shop.url}/seed/user`,
          { 'X-User-Id': userId },
          10_000,
        );
        expect(response.status, 'GET /seed/user').toBe(200);
      }
      const to = Date.now() + 5 * MINUTE;
      await waitFor(
        async () => {
          const answer = await apiSearchQuery(admin, {
            q: userId,
            type: 'user',
            from: iso(from),
            to: iso(to),
          });
          return answer.matches.length >= 3 ? answer : undefined;
        },
        { timeoutMs: 120_000, intervalMs: 7000, what: `the transactions of ${userId}` },
      );
      users = { app, shop, userId, from, to, admin };
    } catch (error) {
      await deleteAppQuietly(admin, { app_id: app.app_id, name: String(app.name) }, testInfo);
      throw error;
    }
  });

  // eslint-disable-next-line no-empty-pattern -- Playwright passes fixtures first; the hook uses none
  test.afterAll(async ({}, testInfo) => {
    const u = users;
    users = null;
    if (u === null) {
      return;
    }
    await u.shop.stop();
    await deleteAppQuietly(u.admin, { app_id: u.app.app_id, name: String(u.app.name) }, testInfo);
  });

  test(
    'T-E2E-search-3 T-ACPT-12: user:<id> over the range lists the transactions of that user = GET /search',
    { annotation: req('N-24') },
    async ({ page }, testInfo) => {
      const u = users;
      if (u === null) {
        throw new Error('the user search setup did not run');
      }
      const started = Date.now();
      const { params, result } = await openSearch(
        page,
        `/search?q=${encodeURIComponent(`user:${u.userId}`)}&from=${iso(u.from)}&to=${iso(u.to)}`,
      );
      expect(params.get('type')).toBe('user');
      expect(params.get('q')).toBe(u.userId);
      expect(result.matches.length).toBeGreaterThanOrEqual(3);
      await expectResultRows(page, result.matches);
      const shownMs = Date.now() - started;
      testInfo.annotations.push({
        type: 'wo39-user-search',
        description: `user id search to the list ${String(shownMs)} ms (laptop: INVALID reference; N-24 3 s is a human check)`,
      });
      const replay = await apiSearchQuery(u.admin, Object.fromEntries(params.entries()));
      expect(replay.matches.map(matchKey)).toEqual(result.matches.map(matchKey));
    },
  );
});
