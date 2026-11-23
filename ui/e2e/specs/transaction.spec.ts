// SPDX-License-Identifier: Apache-2.0
//
// The transaction detail on the real stack (WO-34, docs/15 §8, §9.4, docs/05 §2.2, docs/18 §13):
//
//   - T-E2E-transaction-1..10: the docs/05 §2.2 checklist of required/transaction.yaml, declared by
//     the shared required-elements engine (specs/required.spec.ts) with the checks of
//     required/transactionChecks.ts (registered in required/checks.ts).
//   - T-E2E-transaction-11..: from the XLog list with Enter, tabs and tab= by keyboard, links keeping
//     the time range, previous / next in list order, the C-50 guidance for an unknown id with a ts
//     hint, the BATCH and XSS seed transactions, 1366x768 without a horizontal scroll and axe
//     Critical 0, the call tree by keyboard, a profile reported as lost (T-ACC-07 display), and the
//     screenshots of the loading, error, normal and not-found (empty) states (pinned Linux image
//     only, e2e:docker).
//   - What the generator seed holds beyond the manifest categories is searched in the seed's profile
//     window (required/transactionChecks.ts findSeedTransaction, annotation seed-search) and tested:
//     the failure classes of outgoing calls (24a..c), the largest profile as a bounded virtual list
//     (29), batch chunk runs (30); a kind the search does not find is a runtime fixme naming the
//     reason and owner. T-E2E-transaction-31 opens a tampered detail link (SEC-64).
//   - test.fixme for the kinds the seed cannot produce yet, each naming the owner (ADR-0010 C-207
//     (5)); typed-fixture component tests cover them.
//
// Every expected value comes from the API asked inside the test; no fixed dates; retries 0; the
// page hooks of fixtures/stack.ts (XSS, console, CSP with contextCsp, origins) run after each test.
import type { Page } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import { horizontalOverflow, rendered } from '../fixtures/ui';
import { apiSearch, EXPECTATION_ROLE, searchBody } from '../fixtures/xlogPage';
import {
  apiDetail,
  apiFailKind,
  backOf,
  canonicalTs,
  detailPath,
  E2E_VIRTUAL_OVERSCAN,
  E2E_WATERFALL_ROW_HEIGHT,
  expectNoHorizontalScroll,
  expectSummaryMatches,
  expectTreeFolding,
  findSeedTransaction,
  foldedRuns,
  initialTreeRows,
  openDetail,
  profileFrom,
  transactionWithCalls,
  type ApiDetail,
  type SeedTx,
} from '../required/transactionChecks';
import type { ApiSession } from '../stack/api';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import type { Role, SeedManifest } from '../stack/manifest';

const REQ = { type: 'req', description: 'F-U02' };
const TABS = ['timeline', 'tree', 'sql', 'ext', 'error', 'stack', 'headers'] as const;

// ---------------------------------------------------------------------------------------------
// T-E2E-transaction-11..: flows around the checklist
// ---------------------------------------------------------------------------------------------

/** The tab of the URL. */
function tabOf(page: Page): string | null {
  return new URL(page.url()).searchParams.get('tab');
}

test(
  'T-E2E-transaction-11 Enter on a manifest transaction in the XLog list opens its detail; every summary field equals the API',
  { annotation: [REQ, { type: 'req', description: '2.1-9' }] },
  async ({ page, seed, apiAs }) => {
    const tx = seed.tx.captured_sql_stack;
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    // A selection of this transaction's response time and start (the selection needs from < to,
    // so [ts, ts + 1 ms]), so that its row is among the first rows of the (virtualized) list.
    const back = backOf(tx);
    const ms = Date.parse(tx.ts);
    const xlog = `/apps/${String(tx.app_id)}/xlog?from=${back.from}&to=${back.to}&sel=ms:${String(d.elapsed_ms)}-${String(d.elapsed_ms)},ts:${String(ms)}-${String(ms + 1)}`;
    await page.goto(xlog);
    const row = page.getByTestId('xlog-list').locator(`[data-row-key="${tx.tx_id}"]`);
    await expect(row).toBeVisible({ timeout: 60_000 });
    await row.focus();
    await page.keyboard.press('Enter');
    await expect
      .poll(() => new URL(page.url()).pathname)
      .toBe(`/apps/${String(tx.app_id)}/transactions/${tx.tx_id}`);
    expect(new URL(page.url()).searchParams.get('back')).not.toBeNull();
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
      timeout: 20_000,
    });
    await expectSummaryMatches(page, d);
    // Back to the list returns to the XLog location the link carried.
    await page.getByTestId('transaction-back-to-list').click();
    await expect.poll(() => new URL(page.url()).pathname).toBe(`/apps/${String(tx.app_id)}/xlog`);
  },
);

test(
  'T-E2E-transaction-12 tabs switch by keyboard and click and write tab= to the URL',
  { annotation: [REQ] },
  async ({ page, seed }) => {
    const tx = seed.tx.captured_sql_stack;
    await openDetail(page, detailPath(tx));
    const first = page.getByTestId('transaction-tab-timeline');
    await expect(first).toHaveAttribute('aria-selected', 'true');
    await first.focus();
    for (const tab of TABS.slice(1)) {
      await page.keyboard.press('ArrowRight');
      await expect.poll(() => tabOf(page)).toBe(tab);
      await expect(page.getByTestId(`transaction-tab-${tab}`)).toBeFocused();
      await expect(page.getByTestId(`transaction-tabpanel-${tab}`)).toBeVisible();
    }
    await page.keyboard.press('Home');
    await expect.poll(() => tabOf(page)).toBe(null);
    await page.getByTestId('transaction-tab-sql').click();
    await expect.poll(() => tabOf(page)).toBe('sql');
    // A reload keeps the tab (URL state).
    await page.reload();
    await expect(page.getByTestId('transaction-tab-sql')).toHaveAttribute('aria-selected', 'true', {
      timeout: 20_000,
    });
  },
);

test(
  'T-E2E-transaction-13 SQL statistics, error group, host statistics and trace links keep the time range of the list or the URL',
  { annotation: [REQ, { type: 'req', description: 'F-U14' }] },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'several detail pages');
    const withCalls = (await transactionWithCalls({ apiAs, seed })).tx;
    // Each link exists when the API says its target does; every kind is in the generator seed
    // (SQL steps, error groups, outgoing calls, traces), so a case without a target is searched in
    // the seed and a seed without one fails the test (ADR-0010 C-207 (5)).
    const cases = [
      {
        tx: seed.tx.captured_sql_stack,
        tab: 'sql',
        testId: 'transaction-sql-stats-link',
        prefix: '/stats/sql/',
        target: 'a SQL step with a statement key',
        query: { kind: 'HTTP', min_ms: '1000' },
        required: (d: ApiDetail) =>
          d.profile.steps.some((s) => s.type.toUpperCase() === 'SQL' && s.sql_id !== undefined),
      },
      {
        tx: seed.tx.error,
        tab: 'error',
        testId: 'transaction-error-group-link',
        prefix: '/stats/errors/',
        target: 'a failed transaction with an error group',
        query: { error: true },
        required: (d: ApiDetail) => d.error && d.error_group_id !== null,
      },
      {
        tx: withCalls,
        tab: 'ext',
        testId: 'transaction-ext-host-link',
        prefix: '/stats/external/',
        target: 'an outgoing call step with a host key',
        query: { kind: 'HTTP', min_ms: '1000' },
        required: (d: ApiDetail) =>
          d.profile.steps.some(
            (s) => ['HTTP', 'MESSAGE'].includes(s.type.toUpperCase()) && s.name_id !== undefined,
          ),
      },
      {
        tx: seed.tx.trace_root_two_apps,
        tab: 'ext',
        testId: 'transaction-ext-trace-link',
        prefix: '/traces/',
        target: 'a transaction with a trace id',
        query: { kind: 'HTTP' },
        required: (d: ApiDetail) => d.trace_id !== null,
      },
    ] as const;
    for (const c of cases) {
      const search = await findSeedTransaction(
        { apiAs, seed, testInfo },
        {
          purpose: `${c.testId}: ${c.target}`,
          query: c.query,
          order: 'tx_id',
          max: 30,
          first: [c.tx],
          accept: c.required,
        },
      );
      expect(search.found, `${c.testId}: the seed holds ${c.target}`).not.toBeNull();
      if (search.found === null) continue;
      const { tx, detail: d } = search.found;
      const back = backOf(tx);
      // The first case comes from the list (`back`); the others carry the range in the URL, so
      // that only one detail re-queries a list (the search is HEAVY, 10 per user per minute).
      const context: Record<string, string> =
        c === cases[0] ? { back: back.href } : { from: back.from, to: back.to };
      await openDetail(page, detailPath(tx, { tab: c.tab, ...context }));
      const link = page.getByTestId(c.testId).first();
      // The API names a target, so the screen must link it.
      await expect(link, `${c.testId} for ${tx.tx_id}`).toBeVisible();
      testInfo.annotations.push({ type: 'link-case', description: `${c.testId}: ${tx.tx_id}` });
      const href = new URL((await link.getAttribute('href')) ?? '', 'http://x');
      expect(href.pathname, c.testId).toContain(c.prefix);
      if (c.prefix === '/traces/') {
        expect(href.pathname).toBe(`/traces/${d.trace_id ?? ''}`);
        expect(href.searchParams.get('ts')).toBe(canonicalTs(d.ts));
      } else {
        expect(href.pathname.startsWith(`/apps/${String(tx.app_id)}${c.prefix}`), c.testId).toBe(
          true,
        );
      }
      expect(href.searchParams.get('from'), c.testId).toBe(back.from);
      expect(href.searchParams.get('to'), c.testId).toBe(back.to);
      await link.click();
      await expect.poll(() => new URL(page.url()).pathname).toBe(href.pathname);
      expect(new URL(page.url()).searchParams.get('from')).toBe(back.from);
    }
  },
);

test(
  'T-E2E-transaction-14 previous and next follow the order of the list of back',
  { annotation: [REQ, { type: 'req', description: '2.1-9' }] },
  async ({ page, seed, apiAs }) => {
    const tx = seed.tx.captured_sql_stack;
    const back = backOf(tx);
    const api = await apiAs(EXPECTATION_ROLE);
    const rows = (await apiSearch(api, tx.app_id, searchBody(back.sel, 'HTTP'))).data;
    const index = rows.findIndex((r) => r.tx_id === tx.tx_id);
    expect(index, 'the manifest transaction is in the list of its back').toBeGreaterThanOrEqual(0);
    await openDetail(page, detailPath(tx, { back: back.href }));
    const next = rows[index + 1];
    const previous = rows[index - 1];
    if (next === undefined) {
      await expect(page.getByTestId('transaction-next')).toBeDisabled();
    } else {
      await expect(page.getByTestId('transaction-next')).toHaveAttribute(
        'data-target',
        next.tx_id,
        { timeout: 20_000 },
      );
      await page.getByTestId('transaction-next').focus();
      await page.keyboard.press('Enter');
      await expect
        .poll(() => new URL(page.url()).pathname)
        .toBe(`/apps/${String(tx.app_id)}/transactions/${next.tx_id}`);
      expect(new URL(page.url()).searchParams.get('back')).toBe(back.href);
      await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
        timeout: 20_000,
      });
      await expect(page.getByTestId('transaction-prev')).toHaveAttribute('data-target', tx.tx_id, {
        timeout: 20_000,
      });
      await page.getByTestId('transaction-prev').click();
      await expect
        .poll(() => new URL(page.url()).pathname)
        .toBe(`/apps/${String(tx.app_id)}/transactions/${tx.tx_id}`);
    }
    if (previous === undefined) {
      await expect(page.getByTestId('transaction-prev')).toBeDisabled();
    } else {
      await expect(page.getByTestId('transaction-prev')).toHaveAttribute(
        'data-target',
        previous.tx_id,
        { timeout: 20_000 },
      );
    }
    // Without back: disabled with the reason.
    await openDetail(page, detailPath(tx));
    await expect(page.getByTestId('transaction-prev')).toBeDisabled();
    const reason = page.getByTestId('transaction-adjacent-reason');
    await expect(reason).toHaveAttribute('data-state', 'no_list');
    // The message nests the XLog term ($t(common:term.xlog)); compare the text around it.
    const [before = '', after = ''] = t('tx:actions.adjacent.noList').split(/\$t\([^)]*\)/);
    await expect(reason).toContainText(before);
    await expect(reason).toContainText(after);
    await expect(reason).not.toContainText('$t(');
  },
);

test(
  'T-E2E-transaction-15 an unknown transaction id with a ts hint shows the C-50 guidance and the XLog of that time; the state fits 1366x768 with no critical axe finding',
  {
    annotation: [REQ, { type: 'req', description: 'D-11' }, { type: 'req', description: 'F-U16' }],
  },
  async ({ page, seed }, testInfo) => {
    const tx = seed.tx.captured_sql_stack;
    const ms = Date.parse(tx.ts);
    // The id of a transaction that never existed at that time: the time prefix with an unused
    // node and sequence (DSN-02-09: 41-bit ms ‖ 12-bit node ‖ 11-bit sequence).
    const unknown = ((BigInt(ms) << 23n) | (4095n << 11n) | 2047n).toString();
    await page.goto(`/apps/${String(tx.app_id)}/transactions/${unknown}?ts=${canonicalTs(tx.ts)}`);
    const notFound = page.getByTestId('transaction-not-found');
    await expect(notFound).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'not_found');
    const guidance = [t('tx:notFound.hinted'), t('tx:notFound.clockSkew'), t('tx:notFound.other')];
    expect(guidance).toContain((await notFound.locator('.state__description').textContent()) ?? '');
    // The not-found state fits 1366x768 without a horizontal scroll; axe Critical 0.
    await expectNoHorizontalScroll(page, 'not-found state');
    await expectNoCriticalA11y(page, testInfo, 'transaction-not-found');
    await page.getByTestId('transaction-not-found-xlog').click();
    await expect.poll(() => new URL(page.url()).pathname).toBe(`/apps/${String(tx.app_id)}/xlog`);
    const params = new URL(page.url()).searchParams;
    const from = Date.parse(params.get('from') ?? '');
    const to = Date.parse(params.get('to') ?? '');
    expect(from).toBeLessThanOrEqual(ms);
    expect(to).toBeGreaterThan(ms);
    expect(params.get('kind')).toBe('ALL');
  },
);

test(
  'T-E2E-transaction-16 a BATCH transaction shows its kind and a timeline scale in its unit',
  { annotation: [REQ, { type: 'req', description: 'D-20' }] },
  async ({ page, seed, apiAs }) => {
    const tx = seed.tx.batch;
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    await openDetail(page, detailPath(tx));
    await expect(page.getByTestId('transaction-kind-value')).toHaveAttribute('data-kind', 'BATCH');
    await expect(page.getByTestId('transaction-kind-value')).toHaveText(
      t('common:term.kind.batch'),
    );
    await expectSummaryMatches(page, d);
    if (d.profile.steps.length > 0) {
      const unit = d.elapsed_ms < 1000 ? 'ms' : d.elapsed_ms < 120_000 ? 's' : 'min';
      await expect(
        page.getByTestId('transaction-timeline').getByTestId('waterfall-scale'),
      ).toHaveAttribute('data-unit', unit);
    }
  },
);

/** A string of the detail that carries an XSS payload and the element of the screen that shows it. */
interface DetailCarrier {
  readonly label: string;
  readonly value: string;
  /** The tab that shows it, or null for the summary (above every tab). */
  readonly tab: (typeof TABS)[number] | null;
  readonly testId: string;
}

/**
 * Elements a collected string could inject if it were parsed as markup (icons are inline SVG, so
 * only an SVG with a load handler counts).
 */
const INJECTED =
  'iframe, script, object, embed, img, svg[onload], [onerror], [onload], a[href^="javascript"]';

/**
 * The strings of GET /transactions/{txId} that carry the `__xss` marker of the seed payloads
 * (docs/18 §4.3) and that the detail renders: the summary strings (request URL and method, service,
 * instance, environment, version, thread, exception class, user id, request id, caller, header
 * names) and the header values of the headers tab.
 */
function detailCarriers(d: ApiDetail): DetailCarrier[] {
  const summary = [
    ['url', d.url, 'transaction-url-value'],
    ['http_method', d.http_method, 'transaction-http-method'],
    ['service', d.service, 'transaction-field-service'],
    ['instance', d.instance.name, 'transaction-field-instance'],
    ['env', d.app.env, 'transaction-field-env'],
    ['version', d.version, 'transaction-field-version'],
    ['thread', d.thread, 'transaction-field-thread'],
    ['error_class', d.error ? d.error_class : null, 'transaction-field-error'],
    ['user_id', d.user_id, 'transaction-field-user-id'],
    ['request_id', d.request_id, 'transaction-field-request-id'],
    ['caller.app', d.caller?.app ?? null, 'transaction-caller-app'],
    ['caller.service', d.caller?.service ?? null, 'transaction-caller-service'],
  ] as const;
  const carriers: DetailCarrier[] = [];
  for (const [label, value, testId] of summary) {
    if (value !== null && value.includes('__xss')) {
      carriers.push({ label, value, tab: null, testId });
    }
  }
  for (const [name, value] of Object.entries(d.headers)) {
    if (name.includes('__xss')) {
      carriers.push({
        label: 'header name',
        value: name,
        tab: null,
        testId: 'transaction-field-headers',
      });
    }
    if (value.includes('__xss')) {
      carriers.push({
        label: `header value ${name}`,
        value,
        tab: 'headers',
        testId: 'transaction-headers',
      });
    }
  }
  return carriers;
}

test(
  'T-E2E-transaction-17 the XSS seed transaction: every detail field with a payload that the screen shows (request URL, summary strings, header values) renders as the payload text, and no tab has an injected element',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'SEC-60' },
      { type: 'req', description: 'T-UI-005' },
    ],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'several detail pages');
    const tx = seed.tx.xss;
    test.skip(tx === null, 'no XSS seed transaction in this stack (optional category, WO-46)');
    if (tx === null) return;
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    // The fields of this transaction's detail that carry a payload and are on the screen: without
    // one the test would verify nothing (the manifest names where the seed put the payload).
    const carriers = detailCarriers(d);
    expect(
      carriers.length,
      `rendered detail fields with an XSS payload (the seed payload is in: ${String(tx.facts.payload_in ?? 'unknown')})`,
    ).toBeGreaterThan(0);
    testInfo.annotations.push({
      type: 'xss-fields',
      description: carriers.map((c) => c.label).join(', '),
    });
    for (const tab of TABS) {
      await openDetail(page, detailPath(tx, tab === 'timeline' ? {} : { tab }));
      await expect(
        page.locator('iframe, img[onerror], svg[onload], a[href^="javascript"]'),
      ).toHaveCount(0);
      await expect(page.getByTestId('transaction-page').locator(INJECTED)).toHaveCount(0);
      expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
      // The payload text itself in each field of this tab (the summary's once, on the first tab).
      for (const carrier of carriers.filter((c) => (c.tab ?? 'timeline') === tab)) {
        const element = page.getByTestId(carrier.testId);
        await expect(element, `${carrier.label}: the payload as text`).toContainText(carrier.value);
        await expect(
          element.locator(INJECTED),
          `${carrier.label}: no injected element`,
        ).toHaveCount(0);
      }
    }
    for (const name of Object.keys(d.headers)) {
      expect(name).not.toContain('<');
    }
  },
);

test(
  'T-E2E-transaction-18 every tab fits 1366x768 without a horizontal page scroll and has no critical axe finding',
  { annotation: [REQ, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed }, testInfo) => {
    test.slow(true, 'several detail pages');
    for (const tx of [seed.tx.captured_sql_stack, seed.tx.error]) {
      for (const tab of TABS) {
        await openDetail(page, detailPath(tx, tab === 'timeline' ? {} : { tab }));
        await expect(page.getByTestId(`transaction-tabpanel-${tab}`)).toBeVisible();
        const size = await horizontalOverflow(page);
        expect(size.scrollWidth, `${tx.tx_id} ${tab}`).toBeLessThanOrEqual(size.clientWidth);
        if (tx === seed.tx.captured_sql_stack) {
          await expectNoCriticalA11y(page, testInfo, `transaction-${tab}`);
        }
      }
    }
    // The side panel open next to the timeline keeps the width too.
    await openDetail(page, detailPath(seed.tx.captured_sql_stack));
    await page.getByTestId('transaction-timeline').getByTestId('virtual-row').nth(1).click();
    await expect(page.getByTestId('transaction-step-panel')).toBeVisible();
    const size = await horizontalOverflow(page);
    expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth);
    await expectNoCriticalA11y(page, testInfo, 'transaction-step-panel');
  },
);

test(
  'T-E2E-transaction-28 the error state of the detail (400 answer of the server) fits 1366x768 without a horizontal scroll and has no critical axe finding',
  { annotation: [REQ, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed }, testInfo) => {
    // An id beyond the 64-bit range: the real server answers 400 with its error body (as the
    // screenshot of T-E2E-transaction-21, which runs in the pinned Linux image only).
    await page.goto(`/apps/${String(seed.primary_app_id)}/transactions/99999999999999999999`);
    await expect(page.getByTestId('transaction-error-state')).toBeVisible({ timeout: 20_000 });
    await expectNoHorizontalScroll(page, 'error state');
    await expectNoCriticalA11y(page, testInfo, 'transaction-error');
  },
);

test(
  'T-E2E-transaction-19 the timeline and the call tree work by keyboard (expand, collapse, open)',
  { annotation: [REQ, { type: 'req', description: 'DSN-15-11' }] },
  async ({ page, seed }) => {
    // Timeline: the transaction row first; Down moves to the first step row, Left / Right collapse
    // and expand a step with children, Enter on a step opens the side panel.
    await openDetail(page, detailPath(seed.tx.captured_sql_stack));
    const timeline = page.getByTestId('transaction-timeline');
    const lines = timeline.getByTestId('virtual-row');
    await lines.first().focus();
    let index = 0;
    do {
      await page.keyboard.press('ArrowDown');
      index += 1;
      await expect(lines.nth(index)).toBeFocused();
      // A folded run toggles on Enter; the side panel belongs to a step row.
    } while ((await lines.nth(index).getByTestId('transaction-group').count()) > 0 && index < 10);
    const line = lines.nth(index);
    if ((await line.getAttribute('aria-expanded')) === 'true') {
      await page.keyboard.press('ArrowLeft');
      await expect(line).toHaveAttribute('aria-expanded', 'false');
      await page.keyboard.press('ArrowRight');
      await expect(line).toHaveAttribute('aria-expanded', 'true');
    }
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('transaction-step-panel')).toBeVisible();
    await expect(line).toHaveAttribute('aria-selected', 'true');
    // Call tree.
    await openDetail(page, detailPath(seed.tx.captured_sql_stack, { tab: 'tree' }));
    const tree = page.getByTestId('transaction-tree');
    const rows = tree.getByTestId('virtual-row');
    await rows.first().focus();
    await page.keyboard.press('ArrowDown');
    await expect(rows.nth(1)).toBeFocused();
    await page.keyboard.press('Home');
    await expect(rows.first()).toBeFocused();
    const expandable = tree.locator('[role="row"][aria-expanded]').first();
    if ((await expandable.count()) > 0) {
      const before = await expandable.getAttribute('aria-expanded');
      await expandable.focus();
      await page.keyboard.press(before === 'true' ? 'ArrowLeft' : 'ArrowRight');
      await expect(expandable).toHaveAttribute(
        'aria-expanded',
        before === 'true' ? 'false' : 'true',
      );
    }
    const leaf = tree.locator('[role="row"]:not([aria-expanded])').first();
    await leaf.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('transaction-step-panel')).toBeVisible();
    await rendered(page);
  },
);

// ---------------------------------------------------------------------------------------------
// Visual regression (pinned Linux image only)
// ---------------------------------------------------------------------------------------------

function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
}

/** Values that change per run (ids, times, request ids, the range label, baseline numbers). */
function masks(page: Page) {
  return [
    ...shellMasks(page),
    page.getByTestId('time-range-preset'),
    page.getByTestId('error-state-request-id'),
    page.getByTestId('transaction-id'),
    page.getByTestId('transaction-time-value'),
    page.getByTestId('transaction-trace-id'),
    page.getByTestId('transaction-request-id-value'),
    page.getByTestId('transaction-percentile'),
    page.getByTestId('transaction-actions'),
  ];
}

test.describe('visual', () => {
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

  test(
    'T-E2E-transaction-20 visual: the loading state of the detail',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page, seed }) => {
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      const tx = seed.tx.captured_sql_stack;
      const detailRequest = (url: URL): boolean =>
        url.pathname === `/api/v1/transactions/${tx.tx_id}`;
      await page.route(detailRequest, async (route) => {
        await held;
        await route.continue();
      });
      await page.goto(detailPath(tx));
      await expect(page.getByTestId('loading-state')).toHaveAttribute('data-slow', 'true');
      await expect(page).toHaveScreenshot('transaction-loading.png', { mask: masks(page) });
      release();
      await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
        timeout: 20_000,
      });
      await page.unroute(detailRequest);
    },
  );

  test(
    'T-E2E-transaction-21 visual: the error state of the detail (400 answer of the server)',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page, seed }) => {
      await page.goto(`/apps/${String(seed.primary_app_id)}/transactions/99999999999999999999`);
      await expect(page.getByTestId('transaction-error-state')).toBeVisible({ timeout: 20_000 });
      await expect(page).toHaveScreenshot('transaction-error.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-transaction-22 visual: the detail of the captured transaction (normal state)',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page, seed }) => {
      await openDetail(page, detailPath(seed.tx.captured_sql_stack, { from: 'now-1h' }));
      await expect(page.getByTestId('transaction-timeline')).toBeVisible();
      await expect(page.getByTestId('transaction-baseline-loading')).toHaveCount(0, {
        timeout: 15_000,
      });
      await expect(page).toHaveScreenshot('transaction-normal.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-transaction-27 visual: the not-found state of the detail (empty state with the C-50 guidance)',
    {
      annotation: [
        { type: 'req', description: 'F-U16' },
        { type: 'req', description: 'D-11' },
      ],
    },
    async ({ page, seed }) => {
      const tx = seed.tx.captured_sql_stack;
      const ms = Date.parse(tx.ts);
      // An id of that time that never existed (as in T-E2E-transaction-15), with the ts hint.
      const unknown = ((BigInt(ms) << 23n) | (4095n << 11n) | 2047n).toString();
      await page.goto(
        `/apps/${String(tx.app_id)}/transactions/${unknown}?ts=${canonicalTs(tx.ts)}`,
      );
      await expect(page.getByTestId('transaction-not-found')).toBeVisible({ timeout: 20_000 });
      await expect(page).toHaveScreenshot('transaction-empty.png', { mask: masks(page) });
    },
  );
});

// ---------------------------------------------------------------------------------------------
// LOST profiles (the seed has them, beyond the manifest categories) and the kinds the seed cannot
// produce yet (ADR-0010 C-207 (5))
// ---------------------------------------------------------------------------------------------

/**
 * A seed transaction whose profile the server reports as lost before ingest (state lost, retention
 * not exceeded): a manifest transaction first, else one flagged with a profile before the
 * generator's profile window (profileFrom: such transactions have no stored steps, which the server
 * reports as `profile.state` lost once the ingest allowance has passed, docs/14 §4.6, C-154; at
 * most 10 details asked).
 */
async function lostTransaction(
  seed: SeedManifest,
  apiAs: (role: Role) => Promise<ApiSession>,
): Promise<{ tx_id: string; app_id: number; ts: string } | null> {
  const lostBeforeIngest = (d: ApiDetail): boolean =>
    d.profile.state === 'lost' && d.profile.retention_exceeded !== true;
  for (const tx of Object.values(seed.tx)) {
    if (tx === null) continue;
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    if (lostBeforeIngest(d)) return tx;
  }
  const until = profileFrom(seed) ?? Date.parse(seed.generator.range.start) + 15 * 60_000;
  const api = await apiAs(EXPECTATION_ROLE);
  const rows = (
    await apiSearch(api, seed.primary_app_id, {
      from: seed.generator.range.start,
      to: new Date(until - 60_000).toISOString(),
      kind: 'ALL',
      filters: { has_profile: true },
      sort: 'ts:desc',
      limit: 10,
    })
  ).data;
  for (const row of rows) {
    const d = await apiDetail({ apiAs }, row.tx_id, row.ts);
    if (lostBeforeIngest(d)) {
      return { tx_id: row.tx_id, app_id: seed.primary_app_id, ts: d.ts };
    }
  }
  return null;
}

test(
  'T-E2E-transaction-23 a profile the server reports as lost shows the D-07 lost reason (T-ACC-07 display)',
  {
    annotation: [REQ, { type: 'req', description: 'D-07' }, { type: 'req', description: '2.2-9' }],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    const tx = await lostTransaction(seed, apiAs);
    expect(
      tx,
      'the seed holds a transaction flagged with a profile whose steps are not stored (profile.state lost)',
    ).not.toBeNull();
    if (tx === null) return;
    testInfo.annotations.push({ type: 'lost-transaction', description: tx.tx_id });
    const d = await apiDetail({ apiAs }, tx.tx_id, tx.ts);
    expect(d.profile.state).toBe('lost');
    await openDetail(page, detailPath(tx));
    await expect(page.getByTestId('transaction-profile-banner')).toHaveAttribute(
      'data-state',
      'lost',
    );
    await expect(page.getByTestId('transaction-profile-notice-lost')).toHaveText(
      t('tx:profile.lost'),
    );
    await expect(page.getByTestId('transaction-profile-notice-sampled-out')).toHaveCount(0);
    await expect(page.getByTestId('transaction-timeline-empty')).toBeVisible();
  },
);

// ---------------------------------------------------------------------------------------------
// Failure classes of outgoing calls: real agent data first (ADR-0010 C-223 (7)): the manifest's
// hop traces (seed.hop_traces, WO-14) name the last caller of the timeout, refuse and status500
// scenarios with its classified outgoing call step (external_caller_tx_id, external_step_seq,
// failure). A class without a hop trace falls back to the generator seed (timeouts and connection
// failures of 0.3 % of the calls, 5xx of 1 %, the last call of an error caused by an external call
// times out); a class neither has is a fixme.
// ---------------------------------------------------------------------------------------------

/** A failed outgoing call step of the seed. */
interface FailedCall {
  readonly tx: SeedTx;
  readonly seq: number;
  readonly status: number | null;
  /** Where it came from: the agent's hop trace scenario or the generator seed. */
  readonly source: string;
}

const FAIL_CLASSES = [
  { suffix: 'a', kind: 'timeout', key: 'timeout', label: 'TIMEOUT' },
  { suffix: 'b', kind: 'connect_fail', key: 'connectFail', label: 'CONNECT_FAIL' },
  { suffix: 'c', kind: 'http_5xx', key: 'http5xx', label: '5xx' },
] as const;

const FAILED_CALLS = new Map<string, Promise<{ found: Map<string, FailedCall>; asked: number }>>();

/**
 * The outgoing call of each failure class: the agent's hop traces of the manifest first (their
 * last caller's detail, the step and class the manifest names), then for a class still missing the
 * generator seed: failed transactions of the profile window first (an error caused by an external
 * call ends with a timeout), then transactions of 1 s and more (captured), at most 60 + 80
 * details, once per run.
 */
function failedCalls(
  ctx: Parameters<typeof findSeedTransaction>[0],
): Promise<{ found: Map<string, FailedCall>; asked: number }> {
  let pending = FAILED_CALLS.get(ctx.seed.run_id);
  if (pending === undefined) {
    pending = (async () => {
      const found = new Map<string, FailedCall>();
      const complete = (): boolean => FAIL_CLASSES.every((c) => found.has(c.kind));
      let asked = 0;
      for (const hop of ctx.seed.hop_traces ?? []) {
        if (hop.failure === null || found.has(hop.failure)) continue;
        const d = await apiDetail(ctx, hop.external_caller_tx_id, hop.external_caller_ts);
        asked += 1;
        const step = d.profile.steps.find((s) => s.seq === hop.external_step_seq);
        if (step !== undefined && apiFailKind(step) === hop.failure) {
          found.set(hop.failure, {
            tx: { tx_id: d.tx_id, app_id: d.app.app_id, ts: d.ts },
            seq: step.seq,
            status: step.status ?? null,
            source: `agent hop trace ${hop.scenario} (trace ${hop.trace_id})`,
          });
        }
      }
      if (complete()) {
        return { found, asked };
      }
      const record = (d: ApiDetail): boolean => {
        for (const step of d.profile.steps) {
          if (!['HTTP', 'MESSAGE'].includes(step.type.toUpperCase())) continue;
          const kind = apiFailKind(step);
          if (kind !== null && !found.has(kind)) {
            found.set(kind, {
              tx: { tx_id: d.tx_id, app_id: d.app.app_id, ts: d.ts },
              seq: step.seq,
              status: step.status ?? null,
              source: 'generator seed',
            });
          }
        }
        return complete();
      };
      const failed = await findSeedTransaction(ctx, {
        purpose: 'outgoing calls with a failure class (failed transactions)',
        query: { error: true },
        order: 'tx_id',
        max: 60,
        accept: record,
      });
      asked += failed.asked;
      if (failed.found === null) {
        const slow = await findSeedTransaction(ctx, {
          purpose: 'outgoing calls with a failure class (transactions of 1 s and more)',
          query: { kind: 'HTTP', min_ms: '1000' },
          order: 'tx_id',
          max: 80,
          accept: record,
        });
        asked += slow.asked;
      }
      return { found, asked };
    })();
    FAILED_CALLS.set(ctx.seed.run_id, pending);
  }
  return pending;
}

for (const failClass of FAIL_CLASSES) {
  test(
    `T-E2E-transaction-24${failClass.suffix} an outgoing call of the seed that failed with ${failClass.label} shows the ${failClass.label} badge in the external tab`,
    {
      annotation: [
        REQ,
        { type: 'req', description: 'F-U03' },
        { type: 'req', description: '2.2-6' },
      ],
    },
    async ({ page, seed, apiAs }, testInfo) => {
      test.slow(true, 'seed search over the profile window');
      const { found, asked } = await failedCalls({ apiAs, seed, testInfo });
      testInfo.annotations.push({
        type: 'failure-classes',
        description: `found ${[...found.keys()].join(', ') || 'none'} after ${String(asked)} details`,
      });
      const call = found.get(failClass.kind);
      if (call === undefined) {
        test.fixme(
          true,
          `neither the manifest's hop traces nor the seed search of ${String(asked)} details had an outgoing call that failed with ${failClass.label} (hop traces: e2e/stack/seed/resolve.ts; covered by detailViews.test.tsx and model/calls.test.ts)`,
        );
        return;
      }
      testInfo.annotations.push({
        type: 'failed-call',
        description: `${call.tx.tx_id} step ${String(call.seq)}: ${failClass.kind} from ${call.source}`,
      });
      await openDetail(page, detailPath(call.tx, { tab: 'ext' }));
      const row = page
        .getByTestId('transaction-ext')
        .locator(`[data-testid="data-table-row"][data-row-key="${String(call.seq)}"]`);
      const badge = row.getByTestId('transaction-fail-kind');
      await expect(badge).toHaveAttribute('data-kind', failClass.kind);
      await expect(badge).toHaveText(t(`tx:ext.fail.${failClass.key}`));
      // A failure number (timeout 1, connection failure 2) is no HTTP status: the dash; a 5xx is.
      const status = call.status !== null && call.status >= 100 ? String(call.status) : '';
      await expect(row.getByTestId('transaction-ext-status')).toHaveAttribute(
        'data-status',
        status,
      );
    },
  );
}

// ---------------------------------------------------------------------------------------------
// The largest profile of the seed, batch chunks, a tampered link
// ---------------------------------------------------------------------------------------------

/**
 * The BATCH transactions of the generator's profile window with the most SQL calls (rows of POST
 * /apps/{appId}/transactions/search carry sql_count; a big chunk runs hundreds of statements, each
 * its own step), the 10 largest: one search page of 1,000 rows (longest first) per application
 * with BATCH points, at most 4 searches (the search is HEAVY, 10 per user per minute).
 */
async function mostSqlBatches(
  seed: SeedManifest,
  apiAs: (role: Role) => Promise<ApiSession>,
): Promise<(SeedTx & { sqlCount: number })[]> {
  const from = profileFrom(seed);
  if (from === null) return [];
  const api = await apiAs(EXPECTATION_ROLE);
  const range = { from: new Date(from).toISOString(), to: seed.generator.range.end };
  const found: (SeedTx & { sqlCount: number })[] = [];
  let searches = 0;
  for (const app of seed.apps.filter((a) => a.source === 'generator')) {
    if (searches >= 4) break;
    const points = await api
      .data<{ tx_id: string[] }>(`/apps/${String(app.app_id)}/xlog`, {
        ...range,
        kind: 'BATCH',
        limit: 1,
      })
      .catch(() => null);
    if (points === null || points.tx_id.length === 0) continue;
    searches += 1;
    const page = await apiSearch(api, app.app_id, {
      ...range,
      kind: 'BATCH',
      filters: { has_profile: true },
      sort: 'elapsed_ms:desc',
      limit: 1000,
    });
    for (const row of page.data as (SearchRowWithSql | undefined)[]) {
      if (row === undefined) continue;
      found.push({
        tx_id: row.tx_id,
        app_id: app.app_id,
        ts: row.ts,
        sqlCount: row.sql_count ?? 0,
      });
    }
  }
  return found.sort((a, b) => b.sqlCount - a.sqlCount).slice(0, 10);
}

/** A row of the transaction search with its SQL call count (docs/14 §4.5 TransactionSearchRow). */
interface SearchRowWithSql {
  readonly tx_id: string;
  readonly ts: string;
  readonly sql_count?: number;
}

test(
  'T-E2E-transaction-29 the largest captured profile of the seed (summary mode beyond 1,000 steps) keeps the timeline a DOM virtual list of the rows in view plus overscan, also at its end',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'DSN-15-11' },
      { type: 'req', description: 'N-14' },
    ],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'seed search and a large profile');
    // Large profiles are BATCH chunks of the generator (200 to 1,500 items, each its own SQL
    // steps, folded into SUMMARY steps beyond 1,000 steps, C-69 flag summarized): the BATCH
    // transactions with the most SQL calls are asked first, then the longest ones.
    const mostSql = await mostSqlBatches(seed, apiAs);
    testInfo.annotations.push({
      type: 'most-sql-batches',
      description:
        mostSql.map((c) => `${c.tx_id} (${String(c.sqlCount)} SQL)`).join(', ') || 'none',
    });
    const search = await findSeedTransaction(
      { apiAs, seed, testInfo },
      {
        purpose: 'largest captured profile (BATCH)',
        query: { kind: 'BATCH' },
        order: 'elapsed_desc',
        max: 30,
        first: mostSql,
        accept: (d) => d.profile.state === 'captured' && d.profile.steps.length > 0,
        score: (d) => d.profile.steps.length,
      },
    );
    const steps = search.found?.detail.profile.steps.length ?? 0;
    testInfo.annotations.push({
      type: 'largest-profile',
      description: `${search.found?.tx.tx_id ?? 'none'}: ${String(steps)} steps, summarized ${String(search.found?.detail.flags.summarized ?? false)}, after ${String(search.asked)} details`,
    });
    if (search.found === null || steps < 500) {
      test.fixme(
        true,
        `no captured transaction with 500 steps or more in ${String(search.asked)} BATCH details of the seed (largest ${String(steps)}) (owner: WO-30 seed, generator big-chunk BATCH transactions; covered by TimelineView.test.tsx in jsdom)`,
      );
      return;
    }
    const { tx, detail: d } = search.found;
    const started = Date.now();
    await openDetail(page, detailPath(tx));
    const timeline = page.getByTestId('transaction-timeline');
    const count = 1 + initialTreeRows(d.profile.steps).rows;
    await expect(timeline.getByRole('treegrid')).toHaveAttribute('aria-rowcount', String(count));
    testInfo.annotations.push({
      type: 'render-ms',
      description: `${String(Date.now() - started)} ms from navigation to the loaded detail of ${String(steps)} steps (${String(count)} rows; shared laptop, reference only, INVALID)`,
    });
    const viewport = timeline.locator('.virtual-rows__viewport');
    const height = await viewport.evaluate((el) => el.clientHeight);
    const bound = Math.ceil(height / E2E_WATERFALL_ROW_HEIGHT) + 2 * E2E_VIRTUAL_OVERSCAN;
    const rows = timeline.getByTestId('virtual-row');
    expect(await rows.count()).toBeGreaterThan(0);
    expect(await rows.count(), `rows in the DOM of ${String(count)}`).toBeLessThanOrEqual(bound);
    expect(await timeline.locator('canvas').count()).toBe(0);
    // The end of the list: the last row shows and the window stays bounded.
    await viewport.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });
    await expect(
      timeline.locator(`[role="row"][data-row-index="${String(count - 1)}"]`),
    ).toBeVisible();
    expect(await rows.count(), 'rows in the DOM at the end').toBeLessThanOrEqual(bound);
  },
);

test(
  'T-E2E-transaction-30 a BATCH transaction of the seed folds its chunk repetitions in the call tree like any run (DSN-15-12)',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'DSN-15-12' },
      { type: 'req', description: '2.2-4' },
    ],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'seed search over the BATCH transactions');
    const search = await findSeedTransaction(
      { apiAs, seed, testInfo },
      {
        purpose: 'BATCH call tree with a folded run',
        query: { kind: 'BATCH' },
        order: 'tx_id',
        max: 30,
        first: [seed.tx.batch],
        accept: (d) => foldedRuns(d).length > 0,
      },
    );
    const run = search.found === null ? undefined : foldedRuns(search.found.detail)[0];
    if (search.found === null || run === undefined) {
      test.fixme(
        true,
        `no folded run in ${String(search.asked)} BATCH details of the seed: the generator's BATCH profiles have one step per statement run and interleaved chunk statements, no repeated chunk steps (owner: WO-30 seed; batch chunks are covered by model/tree.test.ts)`,
      );
      return;
    }
    testInfo.annotations.push({
      type: 'fold-run',
      description: `${search.found.tx.tx_id}: folded run of ${run.type} x ${String(run.count)}`,
    });
    await openDetail(page, detailPath(search.found.tx, { tab: 'tree' }));
    await expectTreeFolding(page, search.found.detail, run.type);
  },
);

test(
  'T-E2E-transaction-31 a tampered detail link (tab=<script>, compare=12a, ts=x, back=//evil) opens with the defaults and one notice and sends none of it',
  { tag: '@security', annotation: [REQ, { type: 'req', description: 'SEC-64' }] },
  async ({ page, seed }) => {
    const tx = seed.tx.captured_sql_stack;
    const requests: URL[] = [];
    page.on('request', (request) => {
      requests.push(new URL(request.url()));
    });
    const params = new URLSearchParams();
    params.set('tab', '<script>window.__xss=1</script>');
    params.set('compare', '12a');
    params.set('ts', 'x');
    params.set('back', '//evil.example/apps/1/xlog');
    await page.goto(`/apps/${String(tx.app_id)}/transactions/${tx.tx_id}?${params.toString()}`);
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
      timeout: 20_000,
    });
    await rendered(page);
    // One notice for the items that fell back to their defaults.
    await expect(page.getByTestId('url-restore-notice')).toBeVisible();
    await expect(page.getByTestId('url-restore-notice')).toHaveCount(1);
    // tab: the timeline; compare: none; back: no list, so previous and next say why.
    await expect(page.getByTestId('transaction-tab-timeline')).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByTestId('transaction-compare')).toHaveCount(0);
    await expect(page.getByTestId('transaction-prev')).toBeDisabled();
    await expect(page.getByTestId('transaction-next')).toBeDisabled();
    await expect(page.getByTestId('transaction-adjacent-reason')).toHaveAttribute(
      'data-state',
      'no_list',
    );
    // No injected element, the payload never ran.
    await expect(
      page.locator(
        '[data-testid="transaction-page"] :is(script, iframe, img[onerror], svg[onload], a[href^="javascript"], a[href^="//"])',
      ),
    ).toHaveCount(0);
    expect(await page.evaluate(() => (window as { __xss?: unknown }).__xss)).toBeUndefined();
    // None of the tampered values reached the server: the detail is asked without ts, nothing asks
    // for the compare id, no list is re-queried for back, and no request leaves the origin.
    const origin = new URL(page.url()).origin;
    const api = requests.filter((u) => u.origin === origin && u.pathname.startsWith('/api/'));
    const details = api.filter((u) => u.pathname === `/api/v1/transactions/${tx.tx_id}`);
    expect(details.length, 'the detail request').toBeGreaterThan(0);
    for (const u of details) {
      expect(u.searchParams.has('ts'), u.toString()).toBe(false);
    }
    for (const u of api) {
      const text = decodeURIComponent(u.pathname + u.search);
      expect(text, u.toString()).not.toMatch(/evil|<script|__xss/);
      expect(u.pathname, u.toString()).not.toBe('/api/v1/transactions/12a');
      expect(u.searchParams.has('compare'), u.toString()).toBe(false);
      expect(u.pathname, u.toString()).not.toMatch(/\/transactions\/search$/);
    }
    expect(requests.filter((u) => u.hostname.includes('evil'))).toEqual([]);
  },
);

test.fixme(
  'T-E2E-transaction-25 full-mode parameters masked for a viewer without pii.view (owner: WO-71; covered by detailViews.test.tsx and TransactionPage.actions.test.tsx)',
  { annotation: [REQ, { type: 'req', description: 'SEC-3A' }] },
  () => {
    // No instance of the stack runs with params_mode full.
  },
);

test.fixme(
  'T-E2E-transaction-26 application frames in bold (owner: WO-26 app_packages; ADR-0010 C-207 (3))',
  { annotation: [REQ, { type: 'req', description: '2.2-7' }] },
  () => {
    // GET /apps/{appId} has no app_packages until WO-26.
  },
);
