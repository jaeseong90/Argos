// SPDX-License-Identifier: Apache-2.0
//
// The distributed trace view on the real stack (WO-34, docs/15 §9.5, docs/05 §2.8, docs/18 §13):
//
//   - T-E2E-trace-1..5: the docs/05 §2.8 checklist of required/trace.yaml, declared by the shared
//     required-elements engine (specs/required.spec.ts) with the checks of required/traceChecks.ts
//     (registered in required/checks.ts; `tx.<category>.trace_id` is in required/variables.ts).
//   - T-E2E-trace-6..: opened from the transaction detail's trace link, keyboard, 1366x768 without
//     a horizontal scroll and axe Critical 0, the searched-range actions, the seeded XSS strings as
//     text, and the screenshots of the loading, empty, error and normal states (pinned Linux image
//     only, e2e:docker).
//   - test.fixme for the kinds the seed cannot produce yet, each naming the owning work order
//     (ADR-0010 C-207 (5)); typed-fixture component tests cover them (TracePage.test.tsx).
//
// Every expected value comes from GET /traces/{traceId} (or the detail) asked inside the test; no
// fixed dates; the page hooks of fixtures/stack.ts (XSS, console, CSP with contextCsp, origins)
// run after every test.
import type { Page } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import { t } from '../fixtures/i18n';
import { shellMasks } from '../fixtures/screenshots';
import { expect, test } from '../fixtures/stack';
import { horizontalOverflow, rendered } from '../fixtures/ui';
import {
  apiTrace,
  collectRows,
  crossAppCallees,
  DASH,
  expectTraceLoaded,
  findTrace,
  seededTrace,
  tracePath,
  viewerSession,
  type ApiTrace,
  type RowInfo,
} from '../required/traceChecks';
import { apiFailKind, detailPath, openDetail, type ApiDetail } from '../required/transactionChecks';
import { PLAYWRIGHT_IMAGE } from '../stack/images';
import type { ManifestHopTrace, SeedManifest } from '../stack/manifest';

const REQ = { type: 'req', description: 'F-U03' };

// ---------------------------------------------------------------------------------------------
// T-E2E-trace-6..: flows around the checklist
// ---------------------------------------------------------------------------------------------

async function openSeededTrace(page: Page, seed: SeedManifest): Promise<void> {
  const { traceId, ts } = seededTrace(seed);
  await page.goto(tracePath(traceId, ts));
  await expectTraceLoaded(page);
}

test(
  'T-E2E-trace-6 the trace opens from the transaction detail trace link with the ts hint',
  { annotation: [REQ, { type: 'req', description: 'F-U02' }] },
  async ({ page, seed, apiAs }) => {
    const { traceId, rootTxId, rootAppId } = seededTrace(seed);
    const tx = seed.tx.trace_root_two_apps;
    await page.goto(`/apps/${String(rootAppId)}/transactions/${rootTxId}?ts=${tx.ts}`);
    const link = page.getByTestId('transaction-trace-link');
    await expect(link).toBeVisible({ timeout: 15_000 });
    await link.click();
    await expect.poll(() => new URL(page.url()).pathname).toBe(`/traces/${traceId}`);
    const hint = new URL(page.url()).searchParams.get('ts');
    expect(hint, 'the trace link carries a ts hint').not.toBeNull();
    await expectTraceLoaded(page);
    const { data: trace } = await apiTrace({ apiAs }, traceId, hint ?? tx.ts);
    const rows = await collectRows(page);
    expect(
      rows
        .filter((r) => r.kind === 'tx')
        .map((r) => r.txId)
        .sort(),
    ).toEqual(trace.nodes.map((n) => n.tx_id).sort());
    // The root's detail is loaded in this session now: Open logs follows its server-built link.
    const api = await viewerSession(apiAs);
    const detail = await api.data<{ links: { logs: string | null } }>(`/transactions/${rootTxId}`, {
      ts: tx.ts,
    });
    if (detail.links.logs === null) {
      // No log link template: the action stays, disabled, with its reason shown (05 §2.8-5).
      await expect(page.getByTestId('trace-open-logs')).toHaveCount(0);
      await expect(page.getByTestId('trace-open-logs-unavailable')).toBeDisabled();
      const reason = page.getByTestId('trace-open-logs-reason');
      await expect(reason).toBeVisible();
      await expect(reason).not.toHaveClass(/visually-hidden/);
      await expect(reason).toHaveText(t('trace:actions.openLogsUnavailable'));
    } else {
      await expect(page.getByTestId('trace-open-logs')).toHaveAttribute('href', detail.links.logs);
    }
    // Detail → trace → detail keeps the time range the detail's links carry and the ts hint.
    const traceUrl = new URL(page.url());
    expect(traceUrl.searchParams.get('from'), 'the trace link carries the range').not.toBeNull();
    const rootNode = page.getByTestId('trace-node').and(page.locator(`[data-tx-id="${rootTxId}"]`));
    await rootNode.getByTestId('trace-node-service').click();
    await expect
      .poll(() => new URL(page.url()).pathname)
      .toBe(`/apps/${String(rootAppId)}/transactions/${rootTxId}`);
    const again = new URL(page.url());
    expect(again.searchParams.get('from')).toBe(traceUrl.searchParams.get('from'));
    expect(again.searchParams.get('to')).toBe(traceUrl.searchParams.get('to'));
    expect(Date.parse(again.searchParams.get('ts') ?? ''), 'ts hint = the root start').toBe(
      Date.parse(hint ?? ''),
    );
    await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
      timeout: 15_000,
    });
  },
);

test(
  'T-E2E-trace-7 keyboard: arrows move, collapse and expand the tree; Enter opens the detail',
  { annotation: [REQ, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed, apiAs }, testInfo) => {
    // A trace with a caller relation (a subtree to collapse), the manifest trace when it has one.
    const found = await findTrace(
      { apiAs, seed, testInfo },
      (trace) => crossAppCallees(trace).length > 0,
      'cross-application call with a caller HTTP step',
    );
    const { traceId, ts } = found ?? seededTrace(seed);
    await page.goto(tracePath(traceId, ts));
    await expectTraceLoaded(page);
    const { data: trace } = await apiTrace({ apiAs }, traceId, ts);
    const rows = await collectRows(page);
    const grid = page.getByRole('treegrid');
    const total = rows.length;
    const parentIndex = rows.findIndex((r, i) => (rows[i + 1]?.level ?? 0) > r.level);
    expect(parentIndex, 'a row with children').toBeGreaterThanOrEqual(0);
    const parent = rows[parentIndex];
    if (parent === undefined) return;
    let descendants = 0;
    for (let i = parentIndex + 1; i < rows.length && (rows[i]?.level ?? 0) > parent.level; i += 1) {
      descendants += 1;
    }
    const row = (index: number) =>
      page.locator(`[data-testid="trace-row"][data-row-index="${String(index)}"]`);
    // The first row is the tab stop of the list (roving tabindex).
    await expect(row(0)).toHaveAttribute('tabindex', '0');
    await row(parentIndex).focus();
    await expect(row(parentIndex)).toHaveAttribute('aria-expanded', 'true');
    await page.keyboard.press('ArrowLeft');
    await expect(grid).toHaveAttribute('aria-rowcount', String(total - descendants));
    await expect(row(parentIndex)).toHaveAttribute('aria-expanded', 'false');
    await page.keyboard.press('ArrowRight');
    await expect(grid).toHaveAttribute('aria-rowcount', String(total));
    await page.keyboard.press('ArrowDown');
    await expect(row(parentIndex + 1)).toBeFocused();
    await page.keyboard.press('ArrowLeft');
    await expect(row(parentIndex)).toBeFocused();
    await page.keyboard.press('End');
    await expect(row(total - 1)).toBeFocused();
    // The node panel follows the focused row; Enter on a transaction opens its detail.
    const root = trace.nodes.find((n) => n.tx_id === trace.root_tx_id);
    expect(root, 'the root transaction').toBeDefined();
    if (root === undefined) return;
    const rootIndex = rows.findIndex((r) => r.kind === 'tx' && r.txId === root.tx_id);
    await row(rootIndex).focus();
    await expect(page.getByTestId('trace-node-panel')).toHaveAttribute(
      'data-row-key',
      `tx:${root.tx_id}`,
    );
    await page.keyboard.press('Enter');
    await expect
      .poll(() => new URL(page.url()).pathname)
      .toBe(`/apps/${String(root.app_id)}/transactions/${root.tx_id}`);
  },
);

test(
  'T-E2E-trace-8 1366x768 without a horizontal scroll; axe finds no critical violation',
  { annotation: [REQ, { type: 'req', description: 'F-U16' }] },
  async ({ page, seed }, testInfo) => {
    await openSeededTrace(page, seed);
    const overflow = await horizontalOverflow(page);
    expect(overflow.scrollWidth, 'document scroll width at 1366x768').toBeLessThanOrEqual(
      overflow.clientWidth,
    );
    await expectNoCriticalA11y(page, testInfo, 'trace');
    // With a row focused (tooltip and node panel) and the 30-day confirmation open.
    await page.locator('[data-testid="trace-row"][data-row-index="0"]').focus();
    await expect(page.getByTestId('trace-tooltip')).toBeVisible();
    await expectNoCriticalA11y(page, testInfo, 'trace-focused');
    await page.getByTestId('trace-window-30d').click();
    await expect(page.getByTestId('trace-window-30d-dialog')).toBeVisible();
    await expectNoCriticalA11y(page, testInfo, 'trace-confirm-30d');
    const after = await horizontalOverflow(page);
    expect(after.scrollWidth).toBeLessThanOrEqual(after.clientWidth);
  },
);

test(
  'T-E2E-trace-9 searched range: basis and ts hint shown; 7 days re-queries; 30 days asks first',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'F-U14' },
      { type: 'req', description: 'SEC-56' },
    ],
  },
  async ({ page, seed, apiAs }) => {
    await openSeededTrace(page, seed);
    const { traceId, ts } = seededTrace(seed);
    const { data: trace } = await apiTrace({ apiAs }, traceId, ts);
    const window = page.getByTestId('trace-window');
    await expect(window).toHaveAttribute('data-basis', trace.window.basis);
    expect(Date.parse((await window.getAttribute('data-from')) ?? '')).toBe(
      Date.parse(trace.window.from),
    );
    expect(Date.parse((await window.getAttribute('data-to')) ?? '')).toBe(
      Date.parse(trace.window.to),
    );
    await expect(page.getByTestId('trace-window-basis')).toHaveText(t('trace:window.basis.tsHint'));
    await expect(page.getByTestId('trace-window-hint')).toBeVisible();
    // 30 days: a confirmation first (heavy, rate-limited); cancel leaves the URL alone.
    await page.getByTestId('trace-window-30d').click();
    await expect(page.getByTestId('trace-window-30d-dialog')).toContainText(
      t('trace:window.confirmBody'),
    );
    await page.getByTestId('trace-window-30d-cancel').click();
    await expect(page.getByTestId('trace-window-30d-dialog')).toHaveCount(0);
    expect(new URL(page.url()).searchParams.get('window')).toBeNull();
    // 7 days: window=7d replaces the hint; the server answers with that window.
    const answered = page.waitForResponse(
      (r) => r.url().includes(`/api/v1/traces/${traceId}`) && r.url().includes('window=7d'),
    );
    await page.getByTestId('trace-window-7d').click();
    expect((await answered).status()).toBe(200);
    await expect.poll(() => new URL(page.url()).searchParams.get('window')).toBe('7d');
    await expectTraceLoaded(page);
    await expect(page.getByTestId('trace-window')).toHaveAttribute('data-basis', 'window');
    await expect(page.getByTestId('trace-window-basis')).toHaveText(
      t('trace:window.basis.window7d'),
    );
    await expect(page.getByTestId('trace-window-7d')).toHaveCount(0);
  },
);

/** A string of the trace response that carries an XSS payload, and the row text that shows it. */
interface PayloadCarrier {
  readonly label: string;
  readonly value: string;
  readonly shown: (rows: readonly RowInfo[]) => string | null;
}

/**
 * The strings of GET /traces/{traceId} the trace rows render (node service, app, environment,
 * instance, version; external host and path) that carry the `__xss` marker of the seed payloads.
 */
function payloadCarriers(trace: ApiTrace): PayloadCarrier[] {
  const carriers: PayloadCarrier[] = [];
  for (const node of trace.nodes) {
    const fields = [
      ['service', node.service],
      ['app', node.app],
      ['env', node.env],
      ['instance', node.instance],
      ['version', node.version],
    ] as const;
    for (const [field, value] of fields) {
      if (value !== null && value.includes('__xss')) {
        carriers.push({
          label: `${field} of transaction ${node.tx_id}`,
          value,
          shown: (rows) =>
            rows.find((r) => r.kind === 'tx' && r.txId === node.tx_id)?.[field] ?? null,
        });
      }
    }
  }
  trace.external_nodes.forEach((external, index) => {
    // The n-th external row of the same caller (the order of the response, as traceChecks.ts).
    const nth = trace.external_nodes
      .slice(0, index)
      .filter((e) => e.caller_tx_id === external.caller_tx_id).length;
    const fields = [
      ['host', external.host],
      ['path', external.path_pattern],
    ] as const;
    for (const [field, value] of fields) {
      if (value !== null && value.includes('__xss')) {
        carriers.push({
          label: `${field} of external node ${String(index)}`,
          value,
          shown: (rows) =>
            rows.filter((r) => r.kind === 'external' && r.callerTxId === external.caller_tx_id)[
              nth
            ]?.[field] ?? null,
        });
      }
    }
  });
  return carriers;
}

test(
  'T-E2E-trace-10 trace strings of the XSS seed that carry a payload (service, app, environment, instance, version, external host and path) render as the payload text',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'SEC-60' },
      { type: 'req', description: 'T-UI-005' },
    ],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    const xss = seed.tx.xss;
    test.skip(
      xss === null,
      'no XSS seed transaction in this stack (manifest missing_optional, WO-46)',
    );
    if (xss === null) return;
    const api = await viewerSession(apiAs);
    const detail = await api.data<{ trace_id: string | null; service: string | null }>(
      `/transactions/${xss.tx_id}`,
      { ts: xss.ts, app: xss.app_id },
    );
    if (detail.trace_id === null) {
      testInfo.annotations.push({
        type: 'seed-gap',
        description: `the XSS seed transaction ${xss.tx_id} has no trace id; TracePage.test.tsx (T-UI-005) renders every XSS payload as text (owner WO-46)`,
      });
      test.skip(
        true,
        'the XSS seed transaction has no trace id (TracePage.test.tsx T-UI-005, owner WO-46)',
      );
      return;
    }
    const { data: trace } = await apiTrace({ apiAs }, detail.trace_id, xss.ts);
    const carriers = payloadCarriers(trace);
    if (carriers.length === 0) {
      // Passing here would verify nothing: say so and skip (the payload sits in fields the trace
      // response does not carry, such as the request URL).
      testInfo.annotations.push({
        type: 'seed-gap',
        description: `the trace ${detail.trace_id} of the XSS seed transaction ${xss.tx_id} has no string with a payload (the seed payload is in: ${String(xss.facts.payload_in ?? 'unknown')}); TracePage.test.tsx (T-UI-005) renders every XSS payload as text (owner WO-46)`,
      });
      test.skip(
        true,
        'no field of the trace response carries a payload; covered by TracePage.test.tsx T-UI-005 (owner WO-46)',
      );
      return;
    }
    await page.goto(tracePath(detail.trace_id, xss.ts));
    await expectTraceLoaded(page);
    const rows = await collectRows(page);
    for (const carrier of carriers) {
      expect(carrier.shown(rows), `${carrier.label}: the payload as text`).toBe(carrier.value);
    }
    testInfo.annotations.push({
      type: 'xss-fields',
      description: carriers.map((c) => c.label).join(', '),
    });
    const injected = await page
      .getByTestId('trace-page')
      .evaluate(
        (root) =>
          root.querySelectorAll('img, script, iframe, svg[onload], [onerror], [onload]').length,
      );
    expect(injected, 'elements built from collected strings').toBe(0);
    expect(await page.evaluate(() => Reflect.get(window, '__xss'))).toBeUndefined();
  },
);

test(
  'T-E2E-trace-18 the empty (not in the searched range) and error states fit 1366x768 without a horizontal scroll and have no critical axe finding',
  { annotation: [REQ, { type: 'req', description: 'F-U16' }] },
  async ({ page }, testInfo) => {
    expect(page.viewportSize(), 'the viewport of the suite').toEqual({ width: 1366, height: 768 });
    // Empty: a well-formed trace id that no transaction has, searched in the last hour.
    await page.goto('/traces/0123456789abcdef0123456789abcdef?window=1h');
    await expect(page.getByTestId('trace-not-found')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('trace-not-found')).toContainText(
      t('trace:notFound.notInWindow'),
    );
    const empty = await horizontalOverflow(page);
    expect(empty.scrollWidth, 'empty state: document scroll width').toBeLessThanOrEqual(
      empty.clientWidth,
    );
    await expectNoCriticalA11y(page, testInfo, 'trace-empty');
    // Error: an unsupported window, so the real server answers 400 with its error body (the
    // request is re-addressed, not stubbed; as T-E2E-trace-17).
    await page.route('**/api/v1/traces/**', (route) =>
      route.continue({ url: route.request().url().replace('window=1h', 'window=2h') }),
    );
    await page.reload();
    await expect(page.getByTestId('error-state')).toBeVisible({ timeout: 15_000 });
    const error = await horizontalOverflow(page);
    expect(error.scrollWidth, 'error state: document scroll width').toBeLessThanOrEqual(
      error.clientWidth,
    );
    await expectNoCriticalA11y(page, testInfo, 'trace-error');
    await page.unroute('**/api/v1/traces/**');
  },
);

test.fixme(
  'T-E2E-trace-11 a "not collected yet" node offers Query again and the late callee appears',
  {
    annotation: [
      REQ,
      {
        type: 'fixme',
        description:
          'owned by WO-21/WO-22: the seed has no trace whose callee arrives after the caller within 2 minutes; covered by TracePage.test.tsx (typed fixture)',
      },
    ],
  },
  () => {
    // Needs a late-arriving callee on the stack (ingest delay control, WO-21/WO-22).
  },
);

// T-E2E-trace-12 / T-ACC-05: the real multi-hop traces of the traffic agent (WO-14, ADR-0010
// C-207 (5)). The stack seed sends GET /api/hop/{id} to shop-boot3 with the agent: the app calls
// itself through HttpURLConnection (traceparent + the argos tracestate entry, argos-state.hosts
// 127.0.0.1) and the last transaction calls ext-stub; the manifest's hop_traces name one checked
// trace per scenario (e2e/stack/seed/resolve.ts).

/** The seed's hop trace of `scenario`, failing with the setup's reason when there is none. */
function hopTrace(seed: SeedManifest, scenario: ManifestHopTrace['scenario']): ManifestHopTrace {
  const found = (seed.hop_traces ?? []).find((h) => h.scenario === scenario);
  const why =
    seed.missing_optional.find((m) => m.category === 'hop_traces')?.reason ??
    'not in the seed manifest';
  expect(found, `the agent's ${scenario} hop trace of the seed (${why})`).toBeDefined();
  return found as ManifestHopTrace;
}

/** The transactions of `trace` from its root down the caller links (a chain). */
function hopChainOf(trace: ApiTrace): ApiTrace['nodes'][number][] {
  const chain = trace.nodes.filter((n) => n.tx_id === trace.root_tx_id);
  for (let guard = 0; guard < trace.nodes.length; guard += 1) {
    const last = chain[chain.length - 1];
    const next = trace.nodes.find((n) => last !== undefined && n.parent_tx_id === last.tx_id);
    if (next === undefined) break;
    chain.push(next);
  }
  return chain;
}

const HOP_FAIL_LABEL: Readonly<Record<string, string>> = {
  timeout: 'tx:ext.fail.timeout',
  connect_fail: 'tx:ext.fail.connectFail',
  http_5xx: 'tx:ext.fail.http5xx',
};

test(
  'T-E2E-trace-12 a three-hop trace from the agent with external failure classes (timeout, connect failure)',
  {
    annotation: [
      REQ,
      { type: 'req', description: 'F-A05' },
      { type: 'req', description: 'F-A06' },
      { type: 'req', description: 'D-05' },
      { type: 'req', description: '2.8-3' },
    ],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    test.slow(true, 'four traces and three transaction details');
    // The normal three-hop trace: three transactions, each callee under its caller.
    const hop = hopTrace(seed, 'three_hop');
    const { data: trace } = await apiTrace({ apiAs }, hop.trace_id, hop.ts);
    expect(trace.nodes, 'three transactions of the agent').toHaveLength(3);
    expect(trace.missing, 'no missing caller').toEqual([]);
    expect(trace.root_tx_id).toBe(hop.root_tx_id);
    const chain = hopChainOf(trace);
    expect(
      chain.map((n) => n.tx_id),
      'root -> second hop -> third hop',
    ).toHaveLength(3);
    await page.goto(tracePath(hop.trace_id, hop.ts));
    await expectTraceLoaded(page);
    await expect(page.getByTestId('trace-summary-transactions')).toHaveText('3');
    await expect(page.getByTestId('trace-summary-root')).toHaveAttribute(
      'data-tx-id',
      hop.root_tx_id,
    );
    const rows = await collectRows(page);
    expect(
      rows
        .filter((r) => r.kind === 'tx')
        .map((r) => r.txId)
        .sort(),
      'one row per transaction of the response',
    ).toEqual(trace.nodes.map((n) => n.tx_id).sort());
    const txRowOf = (txId: string) => {
      const row = rows.find((r) => r.kind === 'tx' && r.txId === txId);
      expect(row, `the row of transaction ${txId}`).toBeDefined();
      return row as RowInfo;
    };
    for (let i = 1; i < chain.length; i += 1) {
      const caller = chain[i - 1];
      const callee = chain[i];
      if (caller === undefined || callee === undefined) continue;
      const calleeRow = txRowOf(callee.tx_id);
      expect(calleeRow.level, `${callee.tx_id} one level under its caller`).toBe(
        txRowOf(caller.tx_id).level + 1,
      );
      const call = rows.find((r) => r.kind === 'call' && r.calleeTxId === callee.tx_id);
      expect(call, `the caller step row of ${callee.tx_id}`).toBeDefined();
      expect(call?.index, 'the caller step row sits right above its callee').toBe(
        calleeRow.index - 1,
      );
      expect(call?.callerMs).toBe(String(callee.caller_step?.elapsed_ms));
      expect(call?.calleeMs).toBe(String(callee.elapsed_ms));
    }
    // The last transaction's ext-stub call: an external node under it, answered 200.
    const leaf = chain[chain.length - 1];
    expect(leaf?.tx_id).toBe(hop.external_caller_tx_id);
    const okExternal = trace.external_nodes.filter(
      (e) => e.caller_tx_id === hop.external_caller_tx_id,
    );
    expect(okExternal, 'one external node under the last transaction').toHaveLength(1);
    expect(okExternal[0]?.status).toBe(200);
    expect(okExternal[0]?.error).toBe(false);
    const okRow = rows.filter(
      (r) => r.kind === 'external' && r.callerTxId === hop.external_caller_tx_id,
    );
    expect(okRow).toHaveLength(1);
    expect(okRow[0]?.status).toBe('200');
    expect(okRow[0]?.host).toBe(okExternal[0]?.host ?? DASH);
    expect(okRow[0]?.level).toBe(txRowOf(hop.external_caller_tx_id).level + 1);
    // Evidence of the real agent trace on the screen (attached, test-results/<test>/).
    const shot = testInfo.outputPath('trace-three-hop.png');
    await page.screenshot({ path: shot, fullPage: true });
    await testInfo.attach('trace-three-hop.png', { path: shot, contentType: 'image/png' });

    // The failed last calls: the external node on the trace screen (no response or 500, error),
    // the failure class on the caller's external tab, both against the API.
    const api = await viewerSession(apiAs);
    for (const scenario of ['timeout', 'refuse', 'status500'] as const) {
      const failed = hopTrace(seed, scenario);
      const { data: ft } = await apiTrace({ apiAs }, failed.trace_id, failed.ts);
      expect(ft.nodes, `${scenario}: transactions`).toHaveLength(failed.nodes);
      expect(ft.missing, `${scenario}: no missing caller`).toEqual([]);
      expect(hopChainOf(ft), `${scenario}: one chain`).toHaveLength(failed.nodes);
      const external = ft.external_nodes.filter(
        (e) => e.caller_tx_id === failed.external_caller_tx_id,
      );
      expect(external, `${scenario}: one external node under the last transaction`).toHaveLength(1);
      const node = external[0];
      if (node === undefined) continue;
      expect(node.error, `${scenario}: the external call failed`).toBe(true);
      expect(node.status, `${scenario}: status`).toBe(scenario === 'status500' ? 500 : null);
      await page.goto(tracePath(failed.trace_id, failed.ts));
      await expectTraceLoaded(page);
      const failedRows = await collectRows(page);
      const extRows = failedRows.filter(
        (r) => r.kind === 'external' && r.callerTxId === failed.external_caller_tx_id,
      );
      expect(extRows, `${scenario}: the external row`).toHaveLength(1);
      const extRow = extRows[0];
      const callerRow = failedRows.find(
        (r) => r.kind === 'tx' && r.txId === failed.external_caller_tx_id,
      );
      expect(extRow?.host).toBe(node.host ?? DASH);
      expect(extRow?.status).toBe(
        node.status === null ? t('trace:row.noResponse') : String(node.status),
      );
      expect(extRow?.error).toBe(true);
      expect(extRow?.elapsedMs).toBe(String(node.elapsed_ms));
      expect(extRow?.level).toBe((callerRow?.level ?? -2) + 1);
      // The failure class of that call: the detail API and the external tab badge.
      const detail = await api.data<ApiDetail>(`/transactions/${failed.external_caller_tx_id}`, {
        ts: failed.external_caller_ts,
        app: failed.app_id,
      });
      const step = detail.profile.steps.find((s) => s.seq === failed.external_step_seq);
      expect(step, `${scenario}: the outgoing call step`).toBeDefined();
      if (step === undefined) continue;
      expect(apiFailKind(step), `${scenario}: failure class`).toBe(failed.failure);
      await openDetail(
        page,
        detailPath(
          {
            app_id: failed.app_id,
            tx_id: failed.external_caller_tx_id,
            ts: failed.external_caller_ts,
          },
          { tab: 'ext' },
        ),
      );
      const badge = page
        .getByTestId('transaction-ext')
        .locator(`[data-testid="data-table-row"][data-row-key="${String(step.seq)}"]`)
        .getByTestId('transaction-fail-kind');
      await expect(badge).toHaveAttribute('data-kind', failed.failure ?? '');
      await expect(badge).toHaveText(t(HOP_FAIL_LABEL[failed.failure ?? ''] ?? ''));
      testInfo.annotations.push({
        type: 'hop-failure',
        description: `${scenario}: trace ${failed.trace_id}, ${failed.external_caller_tx_id} step ${String(step.seq)} ${String(failed.failure)} to ${String(node.host)}`,
      });
    }
  },
);

test(
  'T-ACC-05 (server side) the trace API returns caller and callee of the agent hop traces and every caller step is at least its callee',
  {
    annotation: [REQ, { type: 'req', description: 'F-A06' }, { type: 'req', description: 'D-05' }],
  },
  async ({ page, seed, apiAs }, testInfo) => {
    // Every scenario's trace (two and three hops), real agent data through ClickHouse; the trace
    // screen shows the same caller step and callee times.
    const pairs: string[] = [];
    for (const scenario of ['three_hop', 'timeout', 'refuse', 'status500'] as const) {
      const hop = hopTrace(seed, scenario);
      const { data: trace } = await apiTrace({ apiAs }, hop.trace_id, hop.ts);
      await page.goto(tracePath(hop.trace_id, hop.ts));
      await expectTraceLoaded(page);
      const rows = await collectRows(page);
      expect(trace.nodes, `${scenario}: caller and callee transactions`).toHaveLength(hop.nodes);
      const byId = new Map(trace.nodes.map((n) => [n.tx_id, n]));
      const callees = trace.nodes.filter((n) => n.parent_tx_id !== null);
      expect(callees, `${scenario}: callees`).toHaveLength(hop.nodes - 1);
      for (const callee of callees) {
        expect(
          byId.has(callee.parent_tx_id ?? ''),
          `${scenario}: the caller of ${callee.tx_id} is returned`,
        ).toBe(true);
        const step = callee.caller_step;
        expect(step, `${scenario}: the caller HTTP step of ${callee.tx_id}`).not.toBeNull();
        if (step === null) continue;
        expect(
          step.elapsed_ms,
          `${scenario}: caller step >= callee ${callee.tx_id} (T-ACC-05)`,
        ).toBeGreaterThanOrEqual(callee.elapsed_ms);
        expect(step.network_wait_ms).toBe(step.elapsed_ms - callee.elapsed_ms);
        const call = rows.find((r) => r.kind === 'call' && r.calleeTxId === callee.tx_id);
        expect(call?.callerMs, `${scenario}: caller step on screen`).toBe(String(step.elapsed_ms));
        expect(call?.calleeMs, `${scenario}: callee on screen`).toBe(String(callee.elapsed_ms));
        pairs.push(
          `${scenario} ${callee.tx_id}: caller step ${String(step.elapsed_ms)} ms >= callee ${String(callee.elapsed_ms)} ms`,
        );
      }
    }
    testInfo.annotations.push({ type: 'T-ACC-05', description: pairs.join('; ') });
  },
);

test.fixme(
  'T-E2E-trace-13 a LOST profile in a trace shows the D-07 lost reason',
  {
    annotation: [
      REQ,
      {
        type: 'fixme',
        description:
          'the trace API reports profile_state captured or sampled_out only (TraceService; lost is on the detail), and LOST profiles need the agent profile cap work; covered by TracePage.test.tsx (typed fixture)',
      },
    ],
  },
  () => {
    // Needs LOST profiles in the seed and lost in the trace response (WO-23 follow-up).
  },
);

// ---------------------------------------------------------------------------------------------
// Visual regression (pinned Linux image only)
// ---------------------------------------------------------------------------------------------

function inPinnedLinuxImage(): boolean {
  return process.platform === 'linux' && process.env.ARGOS_E2E_LINUX_IMAGE === PLAYWRIGHT_IMAGE;
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

  /**
   * Values that change per run: the trace id and the transaction ids (generated with the load
   * time), dates of the searched range, the trace start and the node start, request ids.
   */
  function masks(page: Page) {
    return [
      ...shellMasks(page),
      page.getByTestId('time-range-preset'),
      page.getByTestId('error-state-request-id'),
      page.getByTestId('trace-id'),
      page.getByTestId('trace-window-range'),
      page.getByTestId('trace-window-hint'),
      page.getByTestId('trace-summary-start'),
      page.getByTestId('trace-panel-start'),
      page.getByTestId('trace-panel-tx-id'),
    ];
  }

  test(
    'T-E2E-trace-14 visual: the trace view (normal)',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page, seed }) => {
      await openSeededTrace(page, seed);
      await rendered(page);
      // The manifest trace differs per stack run: the generator (seed 42) samples traces by a hash
      // of the tx_id (Manifest.sample), and the tx_id carries the start time, so the load minute
      // decides which trace is first (dry runs one minute apart: root app-01 at +74 s vs app-07
      // at +109 s). Apps, services, instances, durations (bar lengths) and possibly the number of
      // rows change, so the list and the node panel below it would move. The comparison covers
      // the page down to the legend (title, actions, searched range, summary box, legend) with
      // the values of the summary masked; the rows are asserted by the checks of T-E2E-trace-1..5.
      const legend = await page.getByTestId('trace-legend').boundingBox();
      expect(legend, 'the legend is laid out').not.toBeNull();
      const viewport = page.viewportSize() ?? { width: 1366, height: 768 };
      await expect(page).toHaveScreenshot('trace-normal.png', {
        mask: [...masks(page), page.getByTestId('trace-summary')],
        clip: {
          x: 0,
          y: 0,
          width: viewport.width,
          height: Math.ceil((legend?.y ?? 0) + (legend?.height ?? 0)) + 8,
        },
      });
    },
  );

  test(
    'T-E2E-trace-15 visual: the trace view while loading',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page, seed }) => {
      // The trace request is held back until the screenshot is taken, then continues unchanged.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/traces/**', async (route) => {
        await held;
        await route.continue();
      });
      const { traceId, ts } = seededTrace(seed);
      await page.goto(tracePath(traceId, ts));
      await expect(page.getByTestId('loading-state')).toBeVisible();
      await expect(page).toHaveScreenshot('trace-loading.png', { mask: masks(page) });
      release();
      await expectTraceLoaded(page);
      await page.unroute('**/api/v1/traces/**');
    },
  );

  test(
    'T-E2E-trace-16 visual: a trace not in the searched range (empty state)',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page }) => {
      // A well-formed trace id that no transaction has, searched in the last hour.
      await page.goto('/traces/0123456789abcdef0123456789abcdef?window=1h');
      await expect(page.getByTestId('trace-not-found')).toBeVisible();
      await expect(page.getByTestId('trace-not-found')).toContainText(
        t('trace:notFound.notInWindow'),
      );
      await expect(page).toHaveScreenshot('trace-empty.png', { mask: masks(page) });
    },
  );

  test(
    'T-E2E-trace-17 visual: the error state of the trace view',
    { annotation: { type: 'req', description: 'F-U16' } },
    async ({ page }) => {
      // An unsupported window: the server answers 400 (a real server error body, no stub).
      await page.goto('/traces/0123456789abcdef0123456789abcdef?window=1h');
      await page.route('**/api/v1/traces/**', (route) =>
        route.continue({ url: route.request().url().replace('window=1h', 'window=2h') }),
      );
      await page.reload();
      await expect(page.getByTestId('error-state')).toBeVisible();
      await expect(page).toHaveScreenshot('trace-error.png', { mask: masks(page) });
    },
  );
});
