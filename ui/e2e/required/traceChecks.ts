// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the trace view (WO-34): the docs/05 §2.8 checklist of ui/e2e/required/trace.yaml.
// Each runs on the real stack (server, ClickHouse, PostgreSQL, seed) and compares the screen with
// GET /traces/{traceId} asked the same question (same trace id, same ts hint) inside the check: node
// labels, the caller step against the callee (T-ACC-05 display), external and missing nodes, the
// profile reason (D-07), the critical path, the links (detail with ts, dashboard with the same
// from/to), copy, logs and the JSON export. Nothing is a fixed value or a fixed date.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, type Page, type TestInfo } from '@playwright/test';

import { expectNoCriticalA11y } from '../fixtures/a11y';
import { t } from '../fixtures/i18n';
import { horizontalOverflow, rendered, state } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import { runDir } from '../stack/paths';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface TraceCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}

export type TraceCheck = (ctx: TraceCheckContext) => Promise<void>;

/** GET /traces/{traceId} data (docs/14 §4.7), as much as the checks read. */
export interface ApiTraceNode {
  readonly tx_id: string;
  readonly app_id: number;
  readonly app: string | null;
  readonly env: string | null;
  readonly service: string | null;
  readonly instance: string | null;
  readonly version: string | null;
  readonly ts: string;
  readonly elapsed_ms: number;
  readonly status: number;
  readonly error: boolean;
  readonly parent_tx_id: string | null;
  readonly caller_step: { readonly elapsed_ms: number; readonly network_wait_ms: number } | null;
  readonly profile_state: string;
}

export interface ApiTrace {
  readonly trace_id: string;
  readonly root_tx_id: string | null;
  readonly nodes: readonly ApiTraceNode[];
  readonly external_nodes: readonly {
    readonly caller_tx_id: string;
    readonly host: string | null;
    readonly path_pattern: string | null;
    readonly elapsed_ms: number;
    readonly status: number | null;
    readonly error: boolean;
  }[];
  readonly missing: readonly {
    readonly parent_tx_id: string | null;
    readonly tx_id: string;
    readonly reason: string;
  }[];
  readonly critical_path: readonly string[];
  readonly window: { readonly from: string; readonly to: string; readonly basis: string };
}

/** The missing dash of SafeText for a null value (src/utils/missing.ts). */
export const DASH = '—';

/** The manifest's trace over two applications: id, ts hint (the root's start), root. */
export function seededTrace(seed: SeedManifest): {
  traceId: string;
  ts: string;
  rootTxId: string;
  rootAppId: number;
} {
  const tx = seed.tx.trace_root_two_apps;
  return { traceId: tx.trace_id, ts: tx.ts, rootTxId: tx.tx_id, rootAppId: tx.app_id };
}

/** The trace route with the ts hint (docs/14 §6). */
export function tracePath(traceId: string, ts: string): string {
  return `/traces/${traceId}?ts=${encodeURIComponent(ts)}`;
}

/**
 * One viewer API session per test (the apiAs fixture is created per test): every login is a new
 * server session, and too many sessions of one user would end the oldest one, the browser's.
 */
const SESSIONS = new WeakMap<(role: Role) => Promise<ApiSession>, Promise<ApiSession>>();

export function viewerSession(apiAs: (role: Role) => Promise<ApiSession>): Promise<ApiSession> {
  let session = SESSIONS.get(apiAs);
  if (session === undefined) {
    session = apiAs('viewer');
    SESSIONS.set(apiAs, session);
  }
  return session;
}

/** GET /traces/{traceId}?ts= as the viewer, the question the page asks. */
export async function apiTrace(
  ctx: Pick<TraceCheckContext, 'apiAs'>,
  traceId: string,
  ts: string,
): Promise<{ data: ApiTrace; meta: Record<string, unknown> }> {
  const api = await viewerSession(ctx.apiAs);
  return api.query<ApiTrace>(`/traces/${traceId}`, { ts });
}

/** A trace the checks found: id, ts hint, the API answer, and whether it is the manifest's. */
export interface FoundTrace {
  readonly traceId: string;
  readonly ts: string;
  readonly trace: ApiTrace;
  readonly seeded: boolean;
}

interface QueryDataFile {
  readonly range?: { readonly profile_from?: string };
  readonly samples?: {
    readonly traces?: readonly { trace_id: string; ts: string; transactions: number }[];
  };
}

/**
 * The generator manifest of this stack run (query-test-data.json of :server:loadQueryTestData in
 * the run directory, which the stack setup reads too), or null when it is not there.
 */
function queryData(seed: SeedManifest): QueryDataFile | null {
  try {
    const file = join(runDir(seed.run_id), 'querydata', 'query-test-data.json');
    return JSON.parse(readFileSync(file, 'utf8')) as QueryDataFile;
  } catch {
    return null;
  }
}

/** ISO-8601 ending in Z without zero sub-second digits (the API form of the stack setup). */
function isoNoMillis(ts: string): string {
  return ts.replace(/\.0+Z$/, 'Z');
}

/**
 * Candidate traces of the seed, cheapest first: the generator's trace samples (two or more
 * transactions), then the traces of transactions of 1 s and more in the generator's profile window
 * (their profiles are captured, so their outgoing HTTP calls exist as steps: caller steps and
 * external nodes, generator README "Profile"), a few per application, primary first.
 */
async function* candidateTraces(
  api: ApiSession,
  seed: SeedManifest,
): AsyncGenerator<{ traceId: string; ts: string }> {
  const data = queryData(seed);
  for (const sample of (data?.samples?.traces ?? []).filter((t) => t.transactions >= 2)) {
    yield { traceId: sample.trace_id, ts: sample.ts };
  }
  const from = data?.range?.profile_from;
  if (from === undefined) {
    return;
  }
  const apps = seed.apps
    .filter((a) => a.source === 'generator')
    .map((a) => a.app_id)
    .sort((a, b) => (a === seed.primary_app_id ? -1 : b === seed.primary_app_id ? 1 : a - b));
  for (const appId of apps) {
    const points = await api
      .query<{ tx_id: string[]; ts: (string | number)[] }>(`/apps/${String(appId)}/xlog`, {
        from: isoNoMillis(from),
        to: seed.generator.range.end,
        limit: 100_000,
        kind: 'HTTP',
        min_ms: '1000',
      })
      .catch(() => null);
    const txs = points?.data.tx_id ?? [];
    for (let i = 0; i < Math.min(4, txs.length); i += 1) {
      const raw = points?.data.ts[i];
      const ts = typeof raw === 'number' ? new Date(raw).toISOString() : (raw ?? '');
      const detail = await api
        .data<{ trace_id: string | null; ts: string }>(`/transactions/${txs[i] ?? ''}`, {
          ts: isoNoMillis(ts),
          app: appId,
        })
        .catch(() => null);
      if (detail?.trace_id !== null && detail?.trace_id !== undefined) {
        yield { traceId: detail.trace_id, ts: detail.ts };
      }
    }
  }
}

/** Found traces per run and purpose (the search asks the API, so each worker does it once). */
const FOUND = new Map<string, FoundTrace | null>();

/**
 * The manifest's two-app trace when it satisfies `predicate`, otherwise the first candidate trace
 * of the same seed that does (candidateTraces, at most 60 asked), or null. The seed manifest names
 * one trace; properties such as "a caller HTTP step exists" depend on whether the caller's profile
 * was captured, so the check looks further in the same seed instead of assuming them.
 */
export async function findTrace(
  ctx: Pick<TraceCheckContext, 'apiAs' | 'seed' | 'testInfo'>,
  predicate: (trace: ApiTrace) => boolean,
  purpose: string,
): Promise<FoundTrace | null> {
  const key = `${ctx.seed.run_id}:${purpose}`;
  const seeded = seededTrace(ctx.seed);
  let found = FOUND.get(key);
  if (found === undefined) {
    found = null;
    const first = await apiTrace(ctx, seeded.traceId, seeded.ts);
    if (predicate(first.data)) {
      found = { traceId: seeded.traceId, ts: seeded.ts, trace: first.data, seeded: true };
    } else {
      const api = await viewerSession(ctx.apiAs);
      let asked = 0;
      for await (const candidate of candidateTraces(api, ctx.seed)) {
        if (candidate.traceId === seeded.traceId) {
          continue;
        }
        asked += 1;
        if (asked > 60) {
          break;
        }
        const answer = await apiTrace(ctx, candidate.traceId, candidate.ts).catch(() => null);
        if (answer !== null && predicate(answer.data)) {
          found = { ...candidate, trace: answer.data, seeded: false };
          break;
        }
      }
    }
    FOUND.set(key, found);
  }
  if (found !== null && !found.seeded) {
    ctx.testInfo.annotations.push({
      type: 'trace-sample',
      description: `${purpose}: the manifest trace ${seeded.traceId} has none; used trace ${found.traceId} of the same seed`,
    });
  }
  return found;
}

/** Opens a trace (unless the page is on it already) and waits for the loaded state. */
export async function openTrace(page: Page, traceId: string, ts: string): Promise<void> {
  if (!new URL(page.url()).pathname.endsWith(`/traces/${traceId}`)) {
    await page.goto(tracePath(traceId, ts));
  }
  await expectTraceLoaded(page);
}

export async function expectTraceLoaded(page: Page): Promise<void> {
  await expect(page.getByTestId('trace-page')).toHaveAttribute('data-state', 'success', {
    timeout: 15_000,
  });
  await expect(page.getByTestId('trace-rows')).toBeVisible();
  await rendered(page);
}

/** One rendered row of the trace list, as the DOM shows it. */
export interface RowInfo {
  readonly index: number;
  readonly level: number;
  readonly kind: 'tx' | 'call' | 'external' | 'missing' | 'unknown';
  readonly txId: string | null;
  readonly appId: string | null;
  readonly app: string | null;
  readonly env: string | null;
  readonly service: string | null;
  readonly instance: string | null;
  readonly version: string | null;
  readonly status: string | null;
  readonly error: boolean;
  readonly elapsedMs: string | null;
  readonly critical: boolean;
  readonly criticalBar: boolean;
  readonly hasBar: boolean;
  readonly profile: string | null;
  readonly calleeTxId: string | null;
  readonly callerMs: string | null;
  readonly calleeMs: string | null;
  readonly waitMs: string | null;
  readonly clockOffset: boolean;
  readonly waitSegments: number;
  readonly waitText: string | null;
  readonly host: string | null;
  readonly path: string | null;
  readonly callerTxId: string | null;
  readonly reason: string | null;
}

/** The rows rendered now (the virtual list holds the visible ones plus an overscan). */
async function renderedRows(page: Page): Promise<RowInfo[]> {
  return page.getByTestId('trace-rows').evaluate((root) => {
    const text = (row: Element, id: string): string | null =>
      row.querySelector(`[data-testid="${id}"]`)?.textContent ?? null;
    const rows: RowInfo[] = [];
    for (const row of root.querySelectorAll('[data-testid="trace-row"]')) {
      const node = row.querySelector('[data-testid="trace-node"]');
      const call = row.querySelector('[data-testid="trace-call"]');
      const external = row.querySelector('[data-testid="trace-external"]');
      const missing = row.querySelector('[data-testid="trace-missing"]');
      const bar = row.querySelector('[data-testid="waterfall-bar"]');
      const kind = node
        ? 'tx'
        : call
          ? 'call'
          : external
            ? 'external'
            : missing
              ? 'missing'
              : 'unknown';
      rows.push({
        index: Number(row.getAttribute('data-row-index')),
        level: Number(row.getAttribute('aria-level')),
        kind,
        txId: node?.getAttribute('data-tx-id') ?? missing?.getAttribute('data-tx-id') ?? null,
        appId: node?.getAttribute('data-app-id') ?? null,
        app: text(row, 'trace-node-app'),
        env: text(row, 'trace-node-env'),
        service: text(row, 'trace-node-service'),
        instance: text(row, 'trace-node-instance'),
        version: text(row, 'trace-node-version'),
        status: text(row, 'trace-node-status') ?? text(row, 'trace-external-status'),
        error:
          row.querySelector(
            '[data-testid="trace-node-error"], [data-testid="trace-external-error"]',
          ) !== null,
        elapsedMs:
          row
            .querySelector(
              '[data-testid="trace-node-elapsed"], [data-testid="trace-call-elapsed"], [data-testid="trace-external-elapsed"]',
            )
            ?.getAttribute('data-ms') ?? null,
        critical: (node ?? call)?.getAttribute('data-critical') === 'true',
        criticalBar: bar?.classList.contains('waterfall-bar--critical') ?? false,
        hasBar: bar !== null,
        profile: text(row, 'trace-node-profile'),
        calleeTxId: call?.getAttribute('data-callee-tx-id') ?? null,
        callerMs: call?.getAttribute('data-caller-ms') ?? null,
        calleeMs: call?.getAttribute('data-callee-ms') ?? null,
        waitMs: call?.getAttribute('data-wait-ms') ?? null,
        clockOffset: call?.getAttribute('data-clock-offset') === 'true',
        waitSegments: row.querySelectorAll('[data-testid="waterfall-segment"][data-kind="wait"]')
          .length,
        waitText: text(row, 'trace-call-wait'),
        host: text(row, 'trace-external-host'),
        path: text(row, 'trace-external-path'),
        callerTxId: external?.getAttribute('data-caller-tx-id') ?? null,
        reason: missing?.getAttribute('data-reason') ?? null,
      });
    }
    return rows;
  });
}

/**
 * Every row of the list: scrolls the virtual list's viewport from top to bottom and collects the
 * rows each position renders (DSN-15-11 renders only the visible window), then scrolls back.
 */
export async function collectRows(page: Page): Promise<RowInfo[]> {
  const viewport = page.getByTestId('trace-rows').locator('.virtual-rows__viewport');
  const total = Number(await page.getByRole('treegrid').getAttribute('aria-rowcount'));
  const seen = new Map<number, RowInfo>();
  for (let guard = 0; guard < 1000; guard += 1) {
    for (const row of await renderedRows(page)) {
      seen.set(row.index, row);
    }
    if (seen.size >= total) {
      break;
    }
    const moved = await viewport.evaluate((element) => {
      const before = element.scrollTop;
      element.scrollTop = before + element.clientHeight;
      return element.scrollTop !== before;
    });
    if (!moved) {
      break;
    }
    await rendered(page);
  }
  await viewport.evaluate((element) => {
    element.scrollTop = 0;
  });
  await rendered(page);
  expect(seen.size, 'every row of the list was rendered once while scrolling').toBe(total);
  return [...seen.values()].sort((a, b) => a.index - b.index);
}

function txRow(rows: readonly RowInfo[], txId: string): RowInfo {
  const row = rows.find((r) => r.kind === 'tx' && r.txId === txId);
  expect(row, `a row for transaction ${txId}`).toBeDefined();
  return row as RowInfo;
}

/** Cross-application callees with a caller step: the T-ACC-05 pairs of the trace. */
export function crossAppCallees(trace: ApiTrace): ApiTraceNode[] {
  const byId = new Map(trace.nodes.map((n) => [n.tx_id, n]));
  return trace.nodes.filter((n) => {
    const parent = n.parent_tx_id === null ? undefined : byId.get(n.parent_tx_id);
    return parent !== undefined && parent.app_id !== n.app_id && n.caller_step !== null;
  });
}

// ---------------------------------------------------------------------------------------------
// 2.8-1 tree + timeline, node labels
// ---------------------------------------------------------------------------------------------

const traceTreeAndNodeLabels: TraceCheck = async ({ page, seed, apiAs }) => {
  const { traceId, ts } = seededTrace(seed);
  await openTrace(page, traceId, ts);
  const { data: trace } = await apiTrace({ apiAs }, traceId, ts);
  expect(
    new Set(trace.nodes.map((n) => n.app_id)).size,
    'the seeded trace spans two apps',
  ).toBeGreaterThanOrEqual(2);
  await expect(page.getByRole('treegrid', { name: t('trace:list.label') })).toBeVisible();
  await expect(page.getByTestId('waterfall-scale')).toBeVisible();
  await expect(page.getByTestId('trace-summary-root')).toHaveAttribute(
    'data-tx-id',
    trace.root_tx_id ?? '',
  );
  await expect(page.getByTestId('trace-summary-transactions')).toHaveText(
    String(trace.nodes.length),
  );
  const rows = await collectRows(page);
  const txRows = rows.filter((r) => r.kind === 'tx');
  expect(txRows.map((r) => r.txId).sort(), 'one row per transaction of the response').toEqual(
    trace.nodes.map((n) => n.tx_id).sort(),
  );
  const byId = new Map(trace.nodes.map((n) => [n.tx_id, n]));
  for (const node of trace.nodes) {
    const row = txRow(rows, node.tx_id);
    const label = `node ${node.tx_id}`;
    expect(row.appId, `${label} app id`).toBe(String(node.app_id));
    expect(row.app, `${label} app`).toBe(node.app ?? DASH);
    expect(row.env, `${label} env`).toBe(node.env ?? DASH);
    expect(row.service, `${label} service`).toBe(node.service ?? DASH);
    expect(row.instance, `${label} instance`).toBe(node.instance ?? DASH);
    expect(row.version, `${label} version`).toBe(node.version ?? DASH);
    expect(row.status, `${label} status`).toBe(String(node.status));
    expect(row.error, `${label} error badge`).toBe(node.error);
    expect(row.elapsedMs, `${label} elapsed`).toBe(String(node.elapsed_ms));
    expect(row.hasBar, `${label} timeline bar`).toBe(true);
    const parent = node.parent_tx_id === null ? undefined : byId.get(node.parent_tx_id);
    if (parent !== undefined) {
      expect(row.level, `${label} one level under its caller`).toBe(
        txRow(rows, parent.tx_id).level + 1,
      );
    }
  }
};

// ---------------------------------------------------------------------------------------------
// 2.8-2 caller step and callee, network / wait (T-ACC-05 display, D-05)
// ---------------------------------------------------------------------------------------------

const traceCallerStepAndWait: TraceCheck = async ({ page, seed, apiAs, testInfo }) => {
  const found = await findTrace(
    { apiAs, seed, testInfo },
    (trace) => crossAppCallees(trace).length > 0,
    'cross-application call with a caller HTTP step',
  );
  expect(
    found,
    'the seed holds a trace with a cross-application call and its caller HTTP step (14 §4.7 caller_step)',
  ).not.toBeNull();
  if (found === null) return;
  await openTrace(page, found.traceId, found.ts);
  const { data: trace } = await apiTrace({ apiAs }, found.traceId, found.ts);
  const callees = crossAppCallees(trace);
  const rows = await collectRows(page);
  for (const callee of callees) {
    const step = callee.caller_step;
    if (step === null) {
      continue;
    }
    // T-ACC-05: the trace API returns both, and the caller step is at least the callee.
    expect(
      step.elapsed_ms,
      `caller step >= callee ${callee.tx_id} (T-ACC-05)`,
    ).toBeGreaterThanOrEqual(callee.elapsed_ms);
    const calleeRow = txRow(rows, callee.tx_id);
    const call = rows.find((r) => r.kind === 'call' && r.calleeTxId === callee.tx_id);
    expect(call, `the caller step row of ${callee.tx_id}`).toBeDefined();
    if (call === undefined) {
      continue;
    }
    expect(call.index + 1, 'the caller step row sits right above its callee').toBe(calleeRow.index);
    expect(call.level).toBe(calleeRow.level);
    expect(call.callerMs).toBe(String(step.elapsed_ms));
    expect(call.calleeMs).toBe(String(callee.elapsed_ms));
    expect(call.elapsedMs).toBe(String(step.elapsed_ms));
    expect(calleeRow.elapsedMs).toBe(String(callee.elapsed_ms));
    expect(call.clockOffset).toBe(false);
    expect(call.waitMs).toBe(String(step.network_wait_ms));
    expect(call.waitText ?? '').toContain(t('trace:row.wait', 'en', { value: '' }).trim());
    expect(call.waitSegments, 'the grey network / wait parts').toBe(
      step.network_wait_ms > 0 ? 2 : 0,
    );
    testInfo.annotations.push({
      type: 'T-ACC-05',
      description: `callee ${callee.tx_id}: caller step ${String(step.elapsed_ms)} ms >= callee ${String(callee.elapsed_ms)} ms, network/wait ${String(step.network_wait_ms)} ms`,
    });
  }
};

// ---------------------------------------------------------------------------------------------
// 2.8-3 missing nodes
// ---------------------------------------------------------------------------------------------

/** The external rows of a trace against its external_nodes (host, path, status, error, time). */
function expectExternalRows(trace: ApiTrace, rows: readonly RowInfo[]): void {
  const externals = rows.filter((r) => r.kind === 'external');
  expect(externals, 'one external row per external node').toHaveLength(trace.external_nodes.length);
  trace.external_nodes.forEach((external, index) => {
    const before = trace.external_nodes
      .slice(0, index)
      .filter((e) => e.caller_tx_id === external.caller_tx_id).length;
    const row = externals.filter((r) => r.callerTxId === external.caller_tx_id)[before];
    expect(row, `external node ${String(index)} of caller ${external.caller_tx_id}`).toBeDefined();
    if (row === undefined) return;
    expect(row.host).toBe(external.host ?? DASH);
    expect(row.path).toBe(external.path_pattern ?? DASH);
    expect(row.status).toBe(
      external.status === null ? t('trace:row.noResponse') : String(external.status),
    );
    expect(row.error).toBe(external.error);
    expect(row.elapsedMs).toBe(String(external.elapsed_ms));
    if (trace.nodes.some((n) => n.tx_id === external.caller_tx_id)) {
      expect(row.level, 'an external node hangs under its caller').toBe(
        txRow(rows, external.caller_tx_id).level + 1,
      );
    }
  });
}

/** The missing placeholders and the profile reasons of a trace against the response. */
async function expectMissingAndProfiles(
  page: Page,
  trace: ApiTrace,
  rows: readonly RowInfo[],
): Promise<number> {
  const missing = rows.filter((r) => r.kind === 'missing');
  expect(
    missing.map((r) => `${r.txId ?? ''}:${r.reason ?? ''}`).sort(),
    'one placeholder per missing entry, with the server reason',
  ).toEqual(trace.missing.map((m) => `${m.tx_id}:${m.reason}`).sort());
  for (const entry of trace.missing) {
    const row = missing.find((r) => r.txId === entry.tx_id);
    const label =
      entry.reason === 'not_yet_collected'
        ? t('trace:row.missingCallerNotYet')
        : t('trace:row.missingCallerNotInstrumented');
    const element = page
      .getByTestId('trace-missing')
      .and(page.locator(`[data-tx-id="${entry.tx_id}"]`));
    if (trace.nodes.some((n) => n.tx_id === entry.tx_id)) {
      // The server names the transaction whose caller is missing: it is drawn under the placeholder.
      await expect(element).toContainText(label);
      expect(txRow(rows, entry.tx_id).level).toBe((row?.level ?? 0) + 1);
    }
  }
  let notCaptured = 0;
  for (const node of trace.nodes) {
    const row = txRow(rows, node.tx_id);
    if (node.profile_state === 'sampled_out' || node.profile_state === 'lost') {
      notCaptured += 1;
      expect(row.profile, `profile reason of ${node.tx_id}`).toBe(
        node.profile_state === 'lost'
          ? t('trace:row.profileLost')
          : t('trace:row.profileSampledOut'),
      );
    } else {
      expect(row.profile, `no profile badge on ${node.tx_id} (${node.profile_state})`).toBeNull();
    }
  }
  const sampled = trace.nodes.find((n) => n.profile_state === 'sampled_out');
  if (sampled !== undefined) {
    const element = page
      .getByTestId('trace-node')
      .and(page.locator(`[data-tx-id="${sampled.tx_id}"]`));
    await page.locator('[data-testid="trace-row"]', { has: element }).focus();
    await expect(page.getByTestId('trace-panel-profile')).toHaveText(
      t('common:component.partial.profileSampledOut'),
    );
  }
  return notCaptured;
}

const traceMissingNodes: TraceCheck = async ({ page, seed, apiAs, testInfo }) => {
  // The manifest trace: its missing entries (a caller outside the trace) and profile reasons.
  const { traceId, ts } = seededTrace(seed);
  await openTrace(page, traceId, ts);
  const { data: seeded } = await apiTrace({ apiAs }, traceId, ts);
  const seededRows = await collectRows(page);
  expectExternalRows(seeded, seededRows);
  const notCaptured = await expectMissingAndProfiles(page, seeded, seededRows);
  // The trace with its missing placeholders and profile reasons fits 1366x768 without a
  // horizontal scroll and has no critical axe finding (docs/15 §13, §15).
  expect(page.viewportSize(), 'the viewport of the suite').toEqual({ width: 1366, height: 768 });
  const overflow = await horizontalOverflow(page);
  expect(overflow.scrollWidth, 'missing nodes: document scroll width').toBeLessThanOrEqual(
    overflow.clientWidth,
  );
  await expectNoCriticalA11y(
    page,
    testInfo,
    seeded.missing.length > 0 ? 'trace-missing-nodes' : 'trace-profile-reasons',
  );
  // An outgoing call to an uninstrumented host, if the seed has one.
  const withExternal = await findTrace(
    { apiAs, seed, testInfo },
    (trace) => trace.external_nodes.length > 0,
    'external node',
  );
  if (withExternal === null) {
    testInfo.annotations.push({
      type: 'seed-gap',
      description:
        'no trace of the seed has an outgoing call to an uninstrumented host; external nodes are covered by TracePage.test.tsx (typed fixtures)',
    });
  } else if (!withExternal.seeded) {
    await openTrace(page, withExternal.traceId, withExternal.ts);
    const { data: trace } = await apiTrace({ apiAs }, withExternal.traceId, withExternal.ts);
    const rows = await collectRows(page);
    expectExternalRows(trace, rows);
    await expectMissingAndProfiles(page, trace, rows);
  }
  testInfo.annotations.push({
    type: 'seed-coverage',
    description: `manifest trace: missing ${String(seeded.missing.length)} (${seeded.missing.map((m) => m.reason).join(', ')}), profile not captured ${String(notCaptured)} of ${String(seeded.nodes.length)}; external nodes: ${withExternal === null ? 'none in the seed' : `${String(withExternal.trace.external_nodes.length)} in trace ${withExternal.traceId}`}; not_yet_collected: fixme T-E2E-trace-11`,
  });
};

// ---------------------------------------------------------------------------------------------
// 2.8-4 critical path, node → detail, app → dashboard
// ---------------------------------------------------------------------------------------------

const traceCriticalPathAndLinks: TraceCheck = async ({ page, seed, apiAs }) => {
  const { traceId, ts } = seededTrace(seed);
  await openTrace(page, traceId, ts);
  const { data: trace } = await apiTrace({ apiAs }, traceId, ts);
  const rows = await collectRows(page);
  const path = new Set(trace.critical_path);
  expect(path.size, 'the server sends a critical path').toBeGreaterThan(0);
  await expect(page.getByTestId('trace-critical-source')).toHaveAttribute('data-source', 'server');
  for (const node of trace.nodes) {
    const row = txRow(rows, node.tx_id);
    expect(row.critical, `critical flag of ${node.tx_id}`).toBe(path.has(node.tx_id));
    expect(row.criticalBar, `bold outline of ${node.tx_id}`).toBe(path.has(node.tx_id));
  }
  // Node → its transaction detail on the canonical route with the ts hint.
  const callee = crossAppCallees(trace)[0] ?? trace.nodes.find((n) => n.tx_id !== trace.root_tx_id);
  expect(callee, 'a callee of the root').toBeDefined();
  if (callee === undefined) return;
  const calleeNode = page
    .getByTestId('trace-node')
    .and(page.locator(`[data-tx-id="${callee.tx_id}"]`));
  await calleeNode.scrollIntoViewIfNeeded();
  await calleeNode.getByTestId('trace-node-service').click();
  await expect
    .poll(() => new URL(page.url()).pathname)
    .toBe(`/apps/${String(callee.app_id)}/transactions/${callee.tx_id}`);
  const hint = new URL(page.url()).searchParams.get('ts');
  expect(hint === null ? null : Date.parse(hint), 'ts hint = the node start').toBe(
    Date.parse(callee.ts),
  );
  // App → that app's dashboard for the trace's time range (the searched window).
  await page.goBack();
  await expectTraceLoaded(page);
  const app = page
    .getByTestId('trace-node')
    .and(page.locator(`[data-tx-id="${callee.tx_id}"]`))
    .getByTestId('trace-node-app');
  await app.scrollIntoViewIfNeeded();
  await app.click();
  await expect
    .poll(() => new URL(page.url()).pathname)
    .toBe(`/apps/${String(callee.app_id)}/dashboard`);
  const s = state(page);
  expect(s.time, 'the dashboard opens with the trace view time range').toEqual({
    kind: 'absolute',
    fromMs: Date.parse(trace.window.from),
    toMs: Date.parse(trace.window.to),
  });
  expect(s.live).toBe(false);
};

// ---------------------------------------------------------------------------------------------
// 2.8-5 copy, logs, JSON export
// ---------------------------------------------------------------------------------------------

const traceCopyLogsExport: TraceCheck = async ({ page, seed, apiAs }) => {
  const { traceId, ts, rootTxId } = seededTrace(seed);
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], {
    origin: new URL(seed.stack_url).origin,
  });
  await openTrace(page, traceId, ts);
  const response = await apiTrace({ apiAs }, traceId, ts);
  await page.getByTestId('trace-copy-id').click();
  await expect(page.getByTestId('trace-action-status')).toHaveText(t('trace:actions.copied'));
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(traceId);
  // Open logs: the trace response has no log link; the root's server-built link is used only when
  // its detail is already loaded (opened directly here: not loaded), so the action is disabled
  // with its reason shown next to it (05 §2.8-5).
  await expect(page.getByTestId('trace-open-logs')).toHaveCount(0);
  await expect(page.getByTestId('trace-open-logs-unavailable')).toBeDisabled();
  const logsReason = page.getByTestId('trace-open-logs-reason');
  await expect(logsReason).toBeVisible();
  await expect(logsReason).not.toHaveClass(/visually-hidden/);
  await expect(logsReason).toHaveText(t('trace:actions.openLogsUnavailable'));
  expect(response.data.root_tx_id).toBe(rootTxId);
  // Export: the loaded response as a JSON file (ADR-0010 C-207 (1)).
  const download = page.waitForEvent('download');
  await page.getByTestId('trace-export-json').click();
  const file = await download;
  expect(file.suggestedFilename()).toBe(`argos-trace-${traceId.toLowerCase()}.json`);
  const saved = JSON.parse(readFileSync(await file.path(), 'utf8')) as {
    data: ApiTrace;
    meta: Record<string, unknown>;
  };
  expect(saved.data.trace_id).toBe(traceId);
  expect(saved.data.nodes.map((n) => n.tx_id).sort()).toEqual(
    response.data.nodes.map((n) => n.tx_id).sort(),
  );
  expect(saved.data.window).toEqual(response.data.window);
};

/** The named checks of required/trace.yaml. */
export const TRACE_CHECKS: Readonly<Record<string, TraceCheck>> = {
  traceTreeAndNodeLabels,
  traceCallerStepAndWait,
  traceMissingNodes,
  traceCriticalPathAndLinks,
  traceCopyLogsExport,
};
