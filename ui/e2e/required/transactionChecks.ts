// SPDX-License-Identifier: Apache-2.0
//
// Named checks of the transaction detail (WO-34): the docs/05 §2.2 checklist of
// ui/e2e/required/transaction.yaml (2.2-1 .. 2.2-10). Each opens a seed manifest transaction on the
// real stack (server, ClickHouse, PostgreSQL) and compares the screen with the API asked the same
// question inside the check (GET /transactions/{txId}?ts=, /baseline, /export, GET /traces): no fixed
// values, no fixed dates. Expected structures (folded runs, uncovered time, common frames) are
// computed here from the API steps with a small independent oracle, never with the UI's own code.
// Kinds the seed cannot produce yet are covered by component tests and test.fixme entries of
// specs/transaction.spec.ts naming the owning work order (ADR-0010 C-207 (5)). Tab checks open the
// detail with an explicit range (from, to) rather than `back`: a detail with `back` re-queries its
// list, and the transaction search is HEAVY (10 per user per minute, docs/16 §7.13).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, type Page, type TestInfo } from '@playwright/test';

import { WATERFALL_ROW_HEIGHT } from '../../src/components/waterfall/scale';
import { expectNoCriticalA11y } from '../fixtures/a11y';
import { t } from '../fixtures/i18n';
import { horizontalOverflow, rendered } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import type { ManifestTx, Role, SeedManifest } from '../stack/manifest';
import { runDir } from '../stack/paths';

import { crossAppCallees, findTrace } from './traceChecks';

/** Row height of the timeline and call tree virtual lists (VirtualRows, DSN-15-11). */
export const E2E_WATERFALL_ROW_HEIGHT = WATERFALL_ROW_HEIGHT;
/**
 * Rows VirtualRows renders beyond its viewport on each side (VIRTUAL_ROWS_OVERSCAN of
 * src/components/waterfall/VirtualRows.tsx, which imports React and CSS and so cannot load in the
 * Playwright runner; transactionYaml.test.ts asserts that the two are equal).
 */
export const E2E_VIRTUAL_OVERSCAN = 10;

/** The check context of checks.ts (structurally; this file does not import it). */
export interface TxCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}

type TxCheck = (ctx: TxCheckContext) => Promise<void>;

const MINUTE = 60_000;
const DASH = '—';

/** A profile step of GET /transactions/{txId} (docs/14 §4.6), the fields the checks read. */
export interface ApiStep {
  readonly seq: number;
  readonly parent_seq: number;
  readonly type: string;
  readonly start_offset_ms: number;
  readonly elapsed_ms: number;
  readonly name?: string;
  readonly name_id?: string;
  readonly sql_id?: string;
  readonly target?: string;
  readonly params_mode?: string;
  readonly params?: string;
  readonly frames?: string[];
  readonly url?: string;
  readonly status?: number;
  readonly failure?: string | null;
  readonly error: boolean;
  readonly repeat: number;
  readonly summary?: { readonly step_type: string; readonly count: number };
}

/** GET /transactions/{txId} (docs/14 §4.6), the fields the checks read. */
export interface ApiDetail {
  readonly tx_id: string;
  readonly trace_id: string | null;
  readonly app: {
    readonly app_id: number;
    readonly name: string | null;
    readonly env: string | null;
  };
  readonly instance: { readonly instance_id: number; readonly name: string | null };
  readonly kind: string;
  readonly service: string | null;
  readonly service_id: string | null;
  readonly http_method: string;
  readonly url: string | null;
  readonly http_status: number;
  readonly ts: string;
  readonly elapsed_ms: number;
  readonly error: boolean;
  readonly error_class: string | null;
  readonly error_group_id: string | null;
  readonly version: string | null;
  readonly thread: string | null;
  readonly cpu_time_ms: number | null;
  readonly alloc_bytes: number | null;
  readonly measured: { readonly cpu: boolean; readonly alloc: boolean };
  readonly sql: { readonly count: number; readonly time_ms: number; readonly wait_ms: number };
  readonly ext: { readonly count: number; readonly time_ms: number };
  readonly remote_ip: string | null;
  readonly ip_mode: string;
  readonly user_id: string | null;
  readonly user_id_mode: string;
  readonly request_id: string | null;
  readonly headers: Readonly<Record<string, string>>;
  readonly caller: {
    readonly app: string | null;
    readonly app_id: number;
    readonly instance_id: number | null;
    readonly service: string | null;
    readonly ts: string | null;
    readonly tx_id: string | null;
  } | null;
  readonly profile: {
    readonly state: string;
    readonly steps: readonly ApiStep[];
    readonly self_time_ms: number | null;
    readonly self_time_ratio: number | null;
    readonly step_truncated: boolean;
    readonly retention_exceeded?: boolean;
  };
  readonly flags: { readonly incoming_trace: boolean; readonly summarized: boolean };
  readonly links: { readonly trace: string | null; readonly logs: string | null };
}

/** GET /transactions/{txId}/baseline (docs/14 §4.6). */
export interface ApiBaseline {
  readonly service_count: number;
  readonly service_p50_ms: number | null;
  readonly service_p95_ms: number | null;
  readonly percentile_of_this: number | null;
  readonly sample_normal_tx_id: string | null;
  readonly sample_normal_ts: string | null;
}

/** One viewer API session per test (per apiAs fixture instance). */
const viewerSessions = new WeakMap<TxCheckContext['apiAs'], Promise<ApiSession>>();

/**
 * The viewer API session of the test: logged in once and reused. Every login opens a session of
 * the same user as the browser's, and the server keeps a bounded number of sessions per user, so a
 * login per question would end the browser's session (it was the oldest).
 */
export function viewerApi(ctx: Pick<TxCheckContext, 'apiAs'>): Promise<ApiSession> {
  let session = viewerSessions.get(ctx.apiAs);
  if (session === undefined) {
    session = ctx.apiAs('viewer');
    viewerSessions.set(ctx.apiAs, session);
  }
  return session;
}

/** The detail as the viewer (the browser's role) sees it. */
export async function apiDetail(
  ctx: Pick<TxCheckContext, 'apiAs'>,
  txId: string,
  ts: string | null,
): Promise<ApiDetail> {
  const api = await viewerApi(ctx);
  return api.data<ApiDetail>(`/transactions/${txId}`, ts === null ? {} : { ts });
}

/** The canonical ISO spelling the UI writes for an instant (formatInstant). */
export function canonicalTs(ts: string): string {
  return new Date(Date.parse(ts)).toISOString();
}

/** The detail route of a transaction with its ts hint and further query pairs. */
export function detailPath(
  tx: Pick<ManifestTx, 'app_id' | 'tx_id' | 'ts'>,
  extra: Readonly<Record<string, string>> = {},
): string {
  const params = new URLSearchParams({ ts: canonicalTs(tx.ts), ...extra });
  return `/apps/${String(tx.app_id)}/transactions/${tx.tx_id}?${params.toString()}`;
}

/** The XLog list `back` of a transaction: ±5 minutes, a selection of ±1 minute and every elapsed. */
export function backOf(tx: Pick<ManifestTx, 'app_id' | 'ts'>): {
  readonly href: string;
  readonly from: string;
  readonly to: string;
  readonly sel: { msMin: number; msMax: number; tsFrom: number; tsTo: number };
} {
  const ms = Date.parse(tx.ts);
  const from = new Date(Math.floor((ms - 5 * MINUTE) / MINUTE) * MINUTE).toISOString();
  const to = new Date(Math.ceil((ms + 5 * MINUTE) / MINUTE) * MINUTE).toISOString();
  const sel = { msMin: 0, msMax: 2_000_000_000, tsFrom: ms - MINUTE, tsTo: ms + MINUTE };
  const href = `/apps/${String(tx.app_id)}/xlog?from=${from}&to=${to}&sel=ms:${String(sel.msMin)}-${String(sel.msMax)},ts:${String(sel.tsFrom)}-${String(sel.tsTo)}`;
  return { href, from, to, sel };
}

/** Opens a detail and waits until it shows the transaction. */
export async function openDetail(page: Page, path: string): Promise<void> {
  await page.goto(path);
  await expect(page.getByTestId('transaction-page')).toHaveAttribute('data-state', 'success', {
    timeout: 20_000,
  });
  await rendered(page);
}

/** Text of a value as SafeText renders it (null: the dash). */
function shown(value: string | null): string {
  return value ?? DASH;
}

// ---------------------------------------------------------------------------------------------
// Independent oracle of the profile structure (docs/15 §8.2, §8.3)
// ---------------------------------------------------------------------------------------------

const NON_WORK = new Set(['STACK', 'ERROR', 'SUMMARY']);

/**
 * Steps directly under the transaction: parent_seq 0, or a parent the profile does not hold (or a
 * parent that is not an earlier step), as docs/15 §8.2 keeps the steps of a cut profile visible.
 */
function topLevelSteps(steps: readonly ApiStep[]): ApiStep[] {
  const seqs = new Set(steps.map((s) => s.seq));
  return steps.filter(
    (s) => s.parent_seq === 0 || !(s.parent_seq < s.seq && seqs.has(s.parent_seq)),
  );
}

/**
 * Parts [start, end) of [0, elapsed) that no top-level work step covers: the grey segments of the
 * transaction row, whose total is the uninstrumented time of docs/15 §8.3 (union, clipped).
 */
export function uncoveredIntervals(detail: ApiDetail): (readonly [number, number])[] {
  const steps = detail.profile.steps;
  if (steps.length === 0) {
    return [];
  }
  const end = detail.elapsed_ms;
  const intervals = topLevelSteps(steps)
    .filter((s) => !NON_WORK.has(s.type.toUpperCase()))
    .map(
      (s) =>
        [
          Math.max(0, s.start_offset_ms),
          Math.min(end, s.start_offset_ms + Math.max(0, s.elapsed_ms)),
        ] as const,
    )
    .filter(([a, b]) => b > a)
    .sort((x, y) => x[0] - y[0]);
  const parts: (readonly [number, number])[] = [];
  let cursor = 0;
  for (const [a, b] of intervals) {
    if (a > cursor) parts.push([cursor, a]);
    cursor = Math.max(cursor, b);
  }
  if (end > cursor) parts.push([cursor, end]);
  return parts;
}

/** Number of grey segments of the transaction row. */
export function uncoveredParts(detail: ApiDetail): number {
  return uncoveredIntervals(detail).length;
}

/** The uninstrumented time in ms (docs/15 §8.3): the total of the grey segments, null without steps. */
export function uncoveredMs(detail: ApiDetail): number | null {
  if (detail.profile.steps.length === 0) {
    return null;
  }
  return uncoveredIntervals(detail).reduce((sum, [a, b]) => sum + (b - a), 0);
}

interface OracleNode {
  readonly step: ApiStep;
  readonly children: OracleNode[];
}

function oracleTree(steps: readonly ApiStep[]): OracleNode[] {
  const bySeq = new Map<number, OracleNode>();
  const sorted = [...steps].sort((a, b) => a.seq - b.seq);
  for (const step of sorted) {
    if (!bySeq.has(step.seq)) bySeq.set(step.seq, { step, children: [] });
  }
  const roots: OracleNode[] = [];
  for (const step of sorted) {
    const node = bySeq.get(step.seq);
    if (node === undefined || node.step !== step) continue;
    const parent =
      step.parent_seq > 0 && step.parent_seq < step.seq ? bySeq.get(step.parent_seq) : undefined;
    (parent === undefined ? roots : parent.children).push(node);
  }
  const order = (nodes: OracleNode[]): void => {
    nodes.sort(
      (a, b) => a.step.start_offset_ms - b.step.start_offset_ms || a.step.seq - b.step.seq,
    );
    for (const n of nodes) order(n.children);
  };
  order(roots);
  return roots;
}

function key(step: ApiStep): string {
  const type = step.type.toUpperCase();
  return `${type}|${type === 'SQL' ? (step.sql_id ?? '') : (step.name_id ?? '')}|${step.target ?? ''}`;
}

/** One row of the call tree at first sight (oracle). */
export interface OracleRow {
  readonly kind: 'step' | 'group';
  /** Step type in upper case (of the members for a group). */
  readonly type: string;
  /** Members of a group (1 for a step). */
  readonly count: number;
  /** Depth below the transaction (0 = top level). */
  readonly depth: number;
  /** Rows a group adds when expanded (its members with their own first-sight rows); 0 for a step. */
  readonly expandsBy: number;
}

/** The rows of the call tree at first sight in display order: runs >= 3 folded, steps expanded. */
export function visibleTreeRows(steps: readonly ApiStep[]): OracleRow[] {
  const rows: OracleRow[] = [];
  // Walks siblings, pushing their rows to `out` (when given) and returning how many rows they show.
  const walk = (nodes: readonly OracleNode[], depth: number, out: OracleRow[] | null): number => {
    let shown = 0;
    let i = 0;
    while (i < nodes.length) {
      const head = nodes[i];
      if (head === undefined) break;
      let j = i + 1;
      while (j < nodes.length && key(nodes[j]?.step ?? head.step) === key(head.step)) j += 1;
      const type = head.step.type.toUpperCase();
      if (j - i >= 3) {
        const members = nodes.slice(i, j);
        const expandsBy = members.reduce((n, m) => n + 1 + walk(m.children, depth + 2, null), 0);
        out?.push({ kind: 'group', type, count: j - i, depth, expandsBy });
        shown += 1;
      } else {
        for (const node of nodes.slice(i, j)) {
          out?.push({
            kind: 'step',
            type: node.step.type.toUpperCase(),
            count: 1,
            depth,
            expandsBy: 0,
          });
          shown += 1 + walk(node.children, depth + 1, out);
        }
      }
      i = j;
    }
    return shown;
  };
  walk(oracleTree(steps), 0, rows);
  return rows;
}

/** STACK captures and ERROR records are points in time (elapsed 0). */
export function isPointEvent(type: string): boolean {
  return type === 'STACK' || type === 'ERROR';
}

/** Rows of the call tree at first sight: runs >= 3 folded (collapsed), steps expanded. */
export function initialTreeRows(steps: readonly ApiStep[]): { rows: number; groups: number[] } {
  const rows = visibleTreeRows(steps);
  return { rows: rows.length, groups: rows.filter((r) => r.kind === 'group').map((r) => r.count) };
}

/** Frames present in every STACK capture (in the first capture's order), [] with fewer than two. */
export function commonStackFrames(d: ApiDetail): string[] {
  const captures = d.profile.steps
    .filter((s) => s.type.toUpperCase() === 'STACK')
    .sort((a, b) => a.start_offset_ms - b.start_offset_ms || a.seq - b.seq);
  if (captures.length < 2) {
    return [];
  }
  return (captures[0]?.frames ?? []).filter((f) =>
    captures.every((c) => (c.frames ?? []).includes(f)),
  );
}

/** Frames of the ERROR steps of a detail. */
export function errorFrameCount(d: ApiDetail): number {
  return d.profile.steps
    .filter((s) => s.type.toUpperCase() === 'ERROR')
    .reduce((n, s) => n + (s.frames?.length ?? 0), 0);
}

/**
 * The failure class of an outgoing call step as the API states it (docs/14 §4.6): `failure`
 * (timeout, connect_fail, http_5xx, other_error; any other value is other_error), otherwise a 5xx
 * `status` is http_5xx and a failed step other_error; null for a successful call.
 */
export function apiFailKind(step: ApiStep): string | null {
  if (step.failure !== undefined && step.failure !== null && step.failure !== '') {
    return ['timeout', 'connect_fail', 'http_5xx', 'other_error'].includes(step.failure)
      ? step.failure
      : 'other_error';
  }
  if (step.status !== undefined && step.status >= 500 && step.status <= 599) {
    return 'http_5xx';
  }
  return step.error ? 'other_error' : null;
}

// ---------------------------------------------------------------------------------------------
// Seed search: transactions of the seed with a property the manifest does not name (ADR-0010
// C-207 (5): what the generator seed holds is tested on the stack)
// ---------------------------------------------------------------------------------------------

/**
 * The start of the generator's profile window (query-test-data.json of the run, as the stack setup
 * and traceChecks.ts read it), or null: only transactions after it can have stored steps.
 */
export function profileFrom(seed: SeedManifest): number | null {
  try {
    const file = join(runDir(seed.run_id), 'querydata', 'query-test-data.json');
    const data = JSON.parse(readFileSync(file, 'utf8')) as { range?: { profile_from?: string } };
    const ms = Date.parse(data.range?.profile_from ?? '');
    return Number.isFinite(ms) ? ms : null;
  } catch {
    return null;
  }
}

/** A transaction of the seed: what a detail route needs. */
export interface SeedTx {
  readonly tx_id: string;
  readonly app_id: number;
  readonly ts: string;
}

/** What a seed search looks for. */
export interface SeedSearch {
  /** What is looked for (cache key and annotation). */
  readonly purpose: string;
  /** Query of GET /apps/{appId}/xlog over the profile window (kind, error, min_ms). */
  readonly query: Readonly<Record<string, string | boolean>>;
  /** Candidate order: longest response time first, or ascending tx id (deterministic). */
  readonly order: 'elapsed_desc' | 'tx_id';
  /** At most this many details are asked (manifest transactions included). */
  readonly max: number;
  /** The property looked for (it may ask the API further, for example the baseline). */
  readonly accept: (detail: ApiDetail) => boolean | Promise<boolean>;
  /** Manifest transactions asked first (the manifest's choice wins when it has the property). */
  readonly first?: readonly (SeedTx | null)[];
  /**
   * With a score, every candidate up to `max` is asked and the accepted one with the highest score
   * is returned (for example the most steps); without, the first accepted one.
   */
  readonly score?: (detail: ApiDetail) => number;
}

export interface SeedSearchResult {
  readonly found: { readonly tx: SeedTx; readonly detail: ApiDetail } | null;
  /** Details asked. */
  readonly asked: number;
  /** XLog candidates of the profile window (before the cap). */
  readonly candidates: number;
}

/** Results per run and purpose (each worker searches once). */
const SEARCHES = new Map<string, Promise<SeedSearchResult>>();

/** ISO-8601 ending in Z without zero sub-second digits (the API form of the stack setup). */
function isoNoMillis(ts: string): string {
  return ts.replace(/\.0+Z$/, 'Z');
}

async function runSeedSearch(
  ctx: Pick<TxCheckContext, 'apiAs' | 'seed'>,
  search: SeedSearch,
): Promise<SeedSearchResult> {
  const api = await viewerApi(ctx);
  const seen = new Set<string>();
  let asked = 0;
  let best: { tx: SeedTx; detail: ApiDetail; score: number } | null = null;
  const consider = async (tx: SeedTx): Promise<boolean> => {
    if (seen.has(tx.tx_id) || asked >= search.max) {
      return false;
    }
    seen.add(tx.tx_id);
    asked += 1;
    const detail = await apiDetail(ctx, tx.tx_id, tx.ts).catch(() => null);
    if (detail === null || !(await search.accept(detail))) {
      return false;
    }
    const found = { tx: { tx_id: tx.tx_id, app_id: detail.app.app_id, ts: detail.ts }, detail };
    if (search.score === undefined) {
      best = { ...found, score: 0 };
      return true;
    }
    const score = search.score(detail);
    if (best === null || score > best.score) {
      best = { ...found, score };
    }
    return false;
  };
  for (const tx of search.first ?? []) {
    if (tx !== null && (await consider(tx))) {
      return { found: best, asked, candidates: 0 };
    }
  }
  const from = profileFrom(ctx.seed);
  if (from === null) {
    return { found: best, asked, candidates: 0 };
  }
  const apps = ctx.seed.apps
    .filter((a) => a.source === 'generator')
    .map((a) => a.app_id)
    .sort((a, b) =>
      a === ctx.seed.primary_app_id ? -1 : b === ctx.seed.primary_app_id ? 1 : a - b,
    );
  const points: (SeedTx & { elapsed: number })[] = [];
  for (const appId of apps) {
    const answer = await api
      .data<{ tx_id: string[]; ts: (string | number)[]; elapsed_ms: number[] }>(
        `/apps/${String(appId)}/xlog`,
        {
          from: isoNoMillis(new Date(from).toISOString()),
          to: ctx.seed.generator.range.end,
          limit: 100_000,
          ...search.query,
        },
      )
      .catch(() => null);
    answer?.tx_id.forEach((txId, i) => {
      const raw = answer.ts[i];
      points.push({
        tx_id: txId,
        app_id: appId,
        ts: typeof raw === 'number' ? new Date(raw).toISOString() : (raw ?? ''),
        elapsed: answer.elapsed_ms[i] ?? 0,
      });
    });
  }
  const byId = (a: SeedTx, b: SeedTx): number =>
    a.tx_id.length - b.tx_id.length || a.tx_id.localeCompare(b.tx_id);
  points.sort(
    search.order === 'elapsed_desc' ? (a, b) => b.elapsed - a.elapsed || byId(a, b) : byId,
  );
  for (const point of points) {
    if (asked >= search.max) break;
    if (await consider(point)) break;
  }
  return { found: best, asked, candidates: points.length };
}

/**
 * A seed transaction with a property (manifest transactions first, then XLog points of the
 * generator's profile window, where profiles are stored), searched once per run and purpose. The
 * annotation `seed-search` says what was searched, how many details were asked and what was found.
 */
export async function findSeedTransaction(
  ctx: Pick<TxCheckContext, 'apiAs' | 'seed' | 'testInfo'>,
  search: SeedSearch,
): Promise<SeedSearchResult> {
  const cacheKey = `${ctx.seed.run_id}:${search.purpose}`;
  let pending = SEARCHES.get(cacheKey);
  if (pending === undefined) {
    pending = runSeedSearch(ctx, search);
    SEARCHES.set(cacheKey, pending);
  }
  const result = await pending;
  ctx.testInfo.annotations.push({
    type: 'seed-search',
    description: `${search.purpose}: ${
      result.found === null
        ? 'none found'
        : `tx ${result.found.tx.tx_id} (app ${String(result.found.tx.app_id)}, ${String(result.found.detail.profile.steps.length)} steps)`
    } after ${String(result.asked)} details (cap ${String(search.max)}) of ${String(result.candidates)} XLog candidates of the profile window (${JSON.stringify(search.query)})`,
  });
  return result;
}

// ---------------------------------------------------------------------------------------------
// 2.2-1 .. 2.2-10
// ---------------------------------------------------------------------------------------------

/** 2.2-1: every item of the summary equals the API value of the same transaction. */
export async function expectSummaryMatches(page: Page, d: ApiDetail): Promise<void> {
  const field = (name: string) => page.getByTestId(`transaction-field-${name}`);
  await expect(field('service')).toContainText(shown(d.service));
  await expect(page.getByTestId('transaction-url-value')).toHaveText(shown(d.url));
  if (d.http_method !== '') {
    await expect(page.getByTestId('transaction-http-method')).toHaveText(d.http_method);
  }
  await expect(field('elapsed')).toHaveAttribute('data-value', String(d.elapsed_ms));
  await expect(page.getByTestId('transaction-status-value')).toHaveAttribute(
    'data-status',
    String(d.http_status),
  );
  await expect(page.getByTestId('transaction-time-value')).toHaveAttribute('data-ts', d.ts);
  await expect(field('instance')).toContainText(shown(d.instance.name));
  await expect(field('env')).toContainText(shown(d.app.env));
  await expect(field('version')).toContainText(shown(d.version));
  await expect(page.getByTestId('transaction-kind-value')).toHaveAttribute(
    'data-kind',
    d.kind.toUpperCase(),
  );
  await expect(field('thread')).toContainText(shown(d.thread));
  for (const [name, value, measured] of [
    ['cpu', d.cpu_time_ms, d.measured.cpu],
    ['alloc', d.alloc_bytes, d.measured.alloc],
  ] as const) {
    await expect(field(name)).toHaveAttribute(
      'data-value',
      measured ? String(value ?? '') : 'not_measured',
    );
    if (!measured) {
      // D-23 / T-ACC-23: "Not measurable", never 0.
      await expect(field(name)).toContainText(t('common:component.missing.notMeasurable'));
    }
  }
  await expect(page.getByTestId('transaction-sql-count')).toHaveAttribute(
    'data-value',
    String(d.sql.count),
  );
  await expect(field('sql')).toHaveAttribute('data-value', String(d.sql.time_ms));
  await expect(field('sql-wait')).toHaveAttribute('data-value', String(d.sql.wait_ms));
  await expect(page.getByTestId('transaction-ext-count')).toHaveAttribute(
    'data-value',
    String(d.ext.count),
  );
  await expect(field('ext')).toHaveAttribute('data-value', String(d.ext.time_ms));
  await expect(page.getByTestId('transaction-error-value')).toHaveAttribute(
    'data-error',
    String(d.error),
  );
  if (d.error) {
    await expect(field('error')).toContainText(shown(d.error_class));
  }
  await expect(field('trace-id')).toContainText(shown(d.trace_id));
  await expect(page.getByTestId('transaction-user-id-mode')).toHaveAttribute(
    'data-mode',
    d.user_id_mode,
  );
  await expect(field('user-id')).toContainText(
    d.user_id === null && d.user_id_mode === 'off'
      ? t('tx:summary.notCollected')
      : shown(d.user_id),
  );
  await expect(page.getByTestId('transaction-ip-mode')).toHaveAttribute('data-mode', d.ip_mode);
  await expect(field('remote-ip')).toContainText(
    d.remote_ip === null && d.ip_mode === 'off' ? t('tx:summary.notCollected') : shown(d.remote_ip),
  );
  const names = Object.keys(d.headers);
  await expect(page.getByTestId('transaction-header-names')).toHaveAttribute(
    'data-count',
    String(names.length),
  );
  for (const name of names) {
    await expect(field('headers')).toContainText(name);
  }
  await expect(field('request-id')).toContainText(shown(d.request_id));
  if (d.caller === null) {
    await expect(field('caller')).toContainText(
      t(d.flags.incoming_trace ? 'tx:summary.callerUnknown' : 'tx:summary.callerNone'),
    );
  } else {
    await expect(page.getByTestId('transaction-caller-app')).toContainText(shown(d.caller.app));
    await expect(page.getByTestId('transaction-caller-service')).toContainText(
      shown(d.caller.service),
    );
    // The API names the caller's instance by id only (docs/14 §4.6 caller): "Instance #id".
    await expect(page.getByTestId('transaction-caller-instance')).toHaveText(
      d.caller.instance_id === null
        ? t('tx:summary.callerInstanceUnknown')
        : t('tx:summary.instanceId', 'en', { id: String(d.caller.instance_id) }),
    );
  }
}

const txSummaryFields: TxCheck = async (ctx) => {
  const tx = ctx.seed.tx.captured_sql_stack;
  const api = await viewerApi(ctx);
  // Reference timing of the detail call (docs/01 N-07 detail 0.5 s; shared laptop, INVALID).
  const started = performance.now();
  const d = await api.data<ApiDetail>(`/transactions/${tx.tx_id}`, { ts: tx.ts });
  ctx.testInfo.annotations.push({
    type: 'detail-api-ms',
    description: `${(performance.now() - started).toFixed(0)} ms for GET /transactions/{txId}?ts= (${String(d.profile.steps.length)} steps; reference only, INVALID)`,
  });
  await expectSummaryMatches(ctx.page, d);
};

/**
 * The scale of a timeline: the response time or the latest end of a step. SUMMARY steps (summary
 * mode, ADR-0010 C-141: start 0, elapsed = the total of the summarized steps) are no interval of
 * the time axis and stay out (proto/README.md profile_step SUMMARY row).
 */
function profileTotal(d: ApiDetail): number {
  let total = Math.max(0, d.elapsed_ms);
  for (const s of d.profile.steps) {
    if (s.type.toUpperCase() === 'SUMMARY') continue;
    total = Math.max(total, s.start_offset_ms + Math.max(0, s.elapsed_ms));
  }
  return Math.max(1, total);
}

/** The page has no horizontal scroll at the 1366x768 viewport of the suite (docs/15 §13). */
export async function expectNoHorizontalScroll(page: Page, label: string): Promise<void> {
  expect(page.viewportSize(), 'the viewport of the suite').toEqual({ width: 1366, height: 768 });
  const size = await horizontalOverflow(page);
  expect(size.scrollWidth, `${label}: document scroll width at 1366x768`).toBeLessThanOrEqual(
    size.clientWidth,
  );
}

/** 2.2-2: p50 / p95 / percentile of /baseline, and the normal request side by side. */
const txPercentileCompare: TxCheck = async (ctx) => {
  const { page } = ctx;
  const tx = ctx.seed.tx.captured_sql_stack;
  const api = await viewerApi(ctx);
  const d = await apiDetail(ctx, tx.tx_id, tx.ts);
  const baseline = await api.data<ApiBaseline>(`/transactions/${tx.tx_id}/baseline`, { ts: d.ts });
  if (
    baseline.service_count === 0 ||
    (baseline.service_p50_ms === null && baseline.service_p95_ms === null)
  ) {
    await expect(page.getByTestId('transaction-baseline-empty')).toBeVisible();
    ctx.testInfo.annotations.push({
      type: 'baseline-path',
      description: `empty: no request of the service of ${tx.tx_id} within 30 minutes; the compare path runs on another seed transaction`,
    });
    await compareOnAnotherTransaction(ctx, d);
    return;
  }
  await expect(page.getByTestId('transaction-baseline-p50')).toHaveAttribute(
    'data-value',
    String(baseline.service_p50_ms ?? ''),
  );
  await expect(page.getByTestId('transaction-baseline-p95')).toHaveAttribute(
    'data-value',
    String(baseline.service_p95_ms ?? ''),
  );
  await expect(page.getByTestId('transaction-baseline-percentile')).toHaveAttribute(
    'data-value',
    String(baseline.percentile_of_this ?? ''),
  );
  if (comparable(baseline, d)) {
    ctx.testInfo.annotations.push({
      type: 'baseline-path',
      description: `compare: ${tx.tx_id} with its normal sample ${baseline.sample_normal_tx_id ?? ''}`,
    });
    await expectCompare(ctx, d, baseline);
    return;
  }
  await expect(page.getByTestId('transaction-compare-unavailable')).toBeVisible();
  ctx.testInfo.annotations.push({
    type: 'baseline-path',
    description: `unavailable: ${tx.tx_id} has no normal sample other than itself; the compare path runs on another seed transaction`,
  });
  await compareOnAnotherTransaction(ctx, d);
};

/** The baseline names a normal sample other than the transaction itself. */
function comparable(baseline: ApiBaseline, d: ApiDetail): boolean {
  return baseline.sample_normal_tx_id !== null && baseline.sample_normal_tx_id !== d.tx_id;
}

/** Opens the comparison with the baseline's normal sample on the open detail and checks it. */
async function expectCompare(
  ctx: TxCheckContext,
  d: ApiDetail,
  baseline: ApiBaseline,
): Promise<void> {
  const { page } = ctx;
  const sample = baseline.sample_normal_tx_id ?? '';
  await page.getByTestId('transaction-compare-open').click();
  await expect.poll(() => new URL(page.url()).searchParams.get('compare')).toBe(sample);
  const other = page.getByTestId('transaction-compare-other');
  await expect(other).toHaveAttribute('data-tx-id', sample, { timeout: 15_000 });
  const o = await apiDetail(ctx, sample, baseline.sample_normal_ts);
  const shared = Math.max(profileTotal(d), profileTotal(o));
  await expect(page.getByTestId('transaction-compare-this')).toHaveAttribute(
    'data-total-ms',
    String(shared),
  );
  await expect(other).toHaveAttribute('data-total-ms', String(shared));
  // Both timelines side by side fit 1366x768 without a horizontal scroll; axe Critical 0.
  await expectNoHorizontalScroll(page, 'compare view');
  await expectNoCriticalA11y(page, ctx.testInfo, 'transaction-compare');
  await page.getByTestId('transaction-compare-scale-own').click();
  await expect(page.getByTestId('transaction-compare-this')).toHaveAttribute(
    'data-total-ms',
    String(profileTotal(d)),
  );
  await expect(other).toHaveAttribute('data-total-ms', String(profileTotal(o)));
}

/**
 * The compare path on a seed transaction whose baseline has a normal sample (the same service as
 * `d` first): searched, opened and checked; a seed without any is a fixme naming the reason.
 */
async function compareOnAnotherTransaction(ctx: TxCheckContext, d: ApiDetail): Promise<void> {
  const api = await viewerApi(ctx);
  const baselines = new Map<string, ApiBaseline>();
  const accept = async (candidate: ApiDetail): Promise<boolean> => {
    const b = await api
      .data<ApiBaseline>(`/transactions/${candidate.tx_id}/baseline`, { ts: candidate.ts })
      .catch(() => null);
    if (b === null || !comparable(b, candidate)) return false;
    baselines.set(candidate.tx_id, b);
    return true;
  };
  let result = await findSeedTransaction(ctx, {
    purpose: `normal sample to compare with (service ${d.service_id ?? 'unknown'})`,
    query: d.service_id === null ? { kind: 'HTTP' } : { kind: 'HTTP', service: d.service_id },
    order: 'tx_id',
    max: 15,
    accept,
  });
  if (result.found === null) {
    result = await findSeedTransaction(ctx, {
      purpose: 'normal sample to compare with (any service)',
      query: { kind: 'HTTP', min_ms: '1000' },
      order: 'tx_id',
      max: 15,
      accept,
    });
  }
  if (result.found === null) {
    ctx.testInfo.fixme(
      true,
      'compare path: no seed transaction searched has a baseline with a normal sample other than itself (owner: WO-30 seed; compare is covered by TransactionPage.actions.test.tsx)',
    );
    return;
  }
  const { tx, detail } = result.found;
  const baseline =
    baselines.get(tx.tx_id) ??
    (await api.data<ApiBaseline>(`/transactions/${tx.tx_id}/baseline`, { ts: detail.ts }));
  ctx.testInfo.annotations.push({
    type: 'baseline-path',
    description: `compare: ${tx.tx_id} with its normal sample ${baseline.sample_normal_tx_id ?? ''}`,
  });
  await openDetail(ctx.page, detailPath(tx));
  await expect(ctx.page.getByTestId('transaction-baseline-p50')).toHaveAttribute(
    'data-value',
    String(baseline.service_p50_ms ?? ''),
    { timeout: 15_000 },
  );
  await expectCompare(ctx, detail, baseline);
}

/**
 * 2.2-3: bars, uncovered time as grey segments, the uninstrumented time of the summary equal to the
 * grey time (docs/15 §8.3: union of the top-level steps, clipped, orphans included) with the 20 %
 * highlight, the server's self_time_ms the source when it is the same number, STACK icons.
 */
const txTimeline: TxCheck = async (ctx) => {
  const { page } = ctx;
  const tx = ctx.seed.tx.captured_sql_stack;
  const d = await apiDetail(ctx, tx.tx_id, tx.ts);
  const expectedMs = uncoveredMs(d);
  expect(expectedMs, 'the captured transaction has steps').not.toBeNull();
  const server = d.profile.self_time_ms;
  const value = page.getByTestId('transaction-uninstrumented');
  await expect(value).toHaveAttribute('data-ms', String(expectedMs));
  if (server === expectedMs) {
    await expect(value).toHaveAttribute('data-source', 'server');
    await expect(value).not.toHaveAttribute('data-server-ms');
  } else {
    await expect(value).toHaveAttribute('data-source', 'client');
    await expect(value).toHaveAttribute('data-server-ms', String(server ?? ''));
  }
  await expect(value).toHaveAttribute(
    'data-highlight',
    String((expectedMs ?? 0) / d.elapsed_ms >= 0.2),
  );
  ctx.testInfo.annotations.push({
    type: 'uninstrumented',
    description: `${tx.tx_id}: timeline ${String(expectedMs)} ms of ${String(d.elapsed_ms)} ms, server self_time_ms ${String(server)} (${server === expectedMs ? 'same, source server' : 'differs, source client'})`,
  });
  const root = page.getByTestId('transaction-timeline-root');
  await expect(root.locator('[data-testid="waterfall-segment"][data-kind="self"]')).toHaveCount(
    uncoveredParts(d),
  );
  const stacks = d.profile.steps.filter((s) => s.type.toUpperCase() === 'STACK').length;
  await expect(root.locator('[data-testid="waterfall-marker"][data-kind="stack"]')).toHaveCount(
    stacks,
  );
  const timeline = page.getByTestId('transaction-timeline');
  await expect(timeline.getByRole('treegrid')).toHaveAttribute(
    'aria-rowcount',
    String(1 + initialTreeRows(d.profile.steps).rows),
  );
  // Every visible step row has a bar or a STACK marker; a click opens the side panel.
  await expect(timeline.getByTestId('waterfall-bar').first()).toBeVisible();
  await timeline.getByTestId('virtual-row').nth(1).click();
  await expect(page.getByTestId('transaction-step-panel')).toBeVisible();
};

/** The badge type (data-type of StepTypeBadge) of a step type. */
function badgeType(type: string): string {
  const lower = type.toLowerCase();
  return [
    'sql',
    'dbc',
    'commit',
    'rollback',
    'http',
    'method',
    'async',
    'error',
    'stack',
    'message',
    'summary',
  ].includes(lower)
    ? lower
    : 'unknown';
}

/** Scrolls a virtual list (VirtualRows, 24 px rows) so that row `index` is in view. */
export async function revealVirtualRow(
  list: ReturnType<Page['getByTestId']>,
  index: number,
): Promise<ReturnType<Page['locator']>> {
  await list.locator('.virtual-rows__viewport').evaluate(
    (el, top) => {
      el.scrollTop = top;
    },
    Math.max(0, index - 2) * E2E_WATERFALL_ROW_HEIGHT,
  );
  const row = list.locator(`[role="row"][data-row-index="${String(index)}"]`);
  await expect(row).toBeVisible();
  return row;
}

/**
 * 2.2-4: the call tree shows the first-sight rows of the oracle; the first folded run of `type`
 * starts collapsed with its count and type, and Right expands it by its members' rows.
 */
export async function expectTreeFolding(page: Page, d: ApiDetail, type: string): Promise<void> {
  const rows = visibleTreeRows(d.profile.steps);
  const tree = page.getByTestId('transaction-tree');
  await expect(tree.getByRole('treegrid')).toHaveAttribute('aria-rowcount', String(rows.length));
  const index = rows.findIndex((r) => r.kind === 'group' && r.type === type);
  const target = rows[index];
  expect(target, `a folded run of ${type} in the oracle rows`).toBeDefined();
  if (target === undefined) return;
  const row = await revealVirtualRow(tree, index);
  await expect(row.getByTestId('transaction-group')).toHaveAttribute(
    'data-count',
    String(target.count),
  );
  await expect(row.getByTestId('transaction-step-type').first()).toHaveAttribute(
    'data-type',
    badgeType(type),
  );
  await expect(row).toHaveAttribute('aria-expanded', 'false');
  await row.focus();
  await page.keyboard.press('ArrowRight');
  await expect(row).toHaveAttribute('aria-expanded', 'true');
  await expect(tree.getByRole('treegrid')).toHaveAttribute(
    'aria-rowcount',
    String(rows.length + target.expandsBy),
  );
}

/** Folded runs of a detail's call tree at first sight. */
export function foldedRuns(d: ApiDetail): OracleRow[] {
  return visibleTreeRows(d.profile.steps).filter((r) => r.kind === 'group');
}

/**
 * 2.2-4 on the seed: a captured transaction whose call tree folds a run of work steps (searched in
 * the profile window, the manifest's first); without one, a run of STACK captures (transactions of
 * 7 s and more have three, generator ProfileBuilder). The batch chunk path is its own test
 * (specs/transaction.spec.ts). A seed without any folded run is a fixme naming the reason.
 */
const txTreeFolding: TxCheck = async (ctx) => {
  ctx.testInfo.slow();
  const { page } = ctx;
  const work = await findSeedTransaction(ctx, {
    purpose: 'call tree with a folded run of work steps',
    query: { kind: 'HTTP', min_ms: '1000' },
    order: 'tx_id',
    max: 40,
    first: [ctx.seed.tx.captured_sql_stack],
    accept: (d) => foldedRuns(d).some((r) => !isPointEvent(r.type)),
  });
  let found = work.found;
  let type =
    found === null
      ? null
      : (foldedRuns(found.detail).find((r) => !isPointEvent(r.type))?.type ?? null);
  if (found === null) {
    const stack = await findSeedTransaction(ctx, {
      purpose: 'call tree with a folded run of STACK captures',
      query: { kind: 'HTTP', min_ms: '7000' },
      order: 'elapsed_desc',
      max: 10,
      accept: (d) => foldedRuns(d).length > 0,
    });
    found = stack.found;
    type = found === null ? null : (foldedRuns(found.detail)[0]?.type ?? null);
  }
  if (found === null || type === null) {
    ctx.testInfo.fixme(
      true,
      'no searched seed transaction folds a run of three equal consecutive steps (owner: WO-30 seed; the run 2/3 boundary, nested runs and batch chunks are covered by model/tree.test.ts)',
    );
    return;
  }
  ctx.testInfo.annotations.push({
    type: 'fold-run',
    description: `${found.tx.tx_id}: folded run of ${type} (${isPointEvent(type) ? 'STACK captures, no work-step run in the searched seed' : 'work steps'})`,
  });
  await openDetail(page, detailPath(found.tx, { tab: 'tree' }));
  await expectTreeFolding(page, found.detail, type);
};

/** `from` and `to` of a link. */
async function rangeOf(
  link: ReturnType<Page['getByTestId']>,
): Promise<{ from: string | null; to: string | null; path: string }> {
  const href = (await link.getAttribute('href')) ?? '';
  const url = new URL(href, 'http://x');
  return { from: url.searchParams.get('from'), to: url.searchParams.get('to'), path: url.pathname };
}

/** 2.2-5: totals with the wait apart from execution, pools, parameter modes, SQL statistics links. */
const txSqlTab: TxCheck = async (ctx) => {
  const { page } = ctx;
  const tx = ctx.seed.tx.captured_sql_stack;
  const back = backOf(tx);
  const d = await apiDetail(ctx, tx.tx_id, tx.ts);
  await openDetail(page, detailPath(tx, { tab: 'sql', from: back.from, to: back.to }));
  await expect(page.getByTestId('transaction-sql-field-exec')).toHaveAttribute(
    'data-value',
    String(d.sql.time_ms),
  );
  await expect(page.getByTestId('transaction-sql-field-wait')).toHaveAttribute(
    'data-value',
    String(d.sql.wait_ms),
  );
  await expect(page.getByTestId('transaction-sql-field-count')).toHaveAttribute(
    'data-value',
    String(d.sql.count),
  );
  const dbc = d.profile.steps.filter((s) => s.type.toUpperCase() === 'DBC');
  const pools = page.getByTestId('transaction-sql-pools');
  for (const step of dbc) {
    if (step.name !== undefined) {
      await expect(pools).toContainText(step.name);
    }
  }
  const sqlSteps = d.profile.steps.filter((s) => s.type.toUpperCase() === 'SQL');
  const modes = new Set(
    sqlSteps.filter((s) => s.params_mode !== undefined).map((s) => s.params_mode),
  );
  const shownModes = await page
    .getByTestId('transaction-sql-modes')
    .getByTestId('transaction-params-mode')
    .evaluateAll((nodes) => nodes.map((n) => n.getAttribute('data-mode')));
  expect(new Set(shownModes)).toEqual(
    new Set(
      [...modes].map((m) =>
        ['masked', 'partial', 'full', 'masked_by_role'].includes(m ?? '') ? m : 'unknown',
      ),
    ),
  );
  if (modes.has('masked_by_role')) {
    await expect(page.getByTestId('transaction-sql-masked-by-role')).toBeVisible();
  }
  const links = page.getByTestId('transaction-sql-stats-link');
  expect(await links.count()).toBeGreaterThan(0);
  const first = await rangeOf(links.first());
  expect(first.path.startsWith(`/apps/${String(tx.app_id)}/stats/sql/`)).toBe(true);
  expect(sqlSteps.map((s) => s.sql_id)).toContain(first.path.split('/').pop());
  expect(first.from).toBe(back.from);
  expect(first.to).toBe(back.to);
};

/** 2.2-6: host, path, time, status of each call, host statistics and trace links with the range. */
/** Outgoing call steps of a detail (HTTP, MESSAGE). */
function callSteps(d: ApiDetail): ApiStep[] {
  return d.profile.steps.filter((s) => ['HTTP', 'MESSAGE'].includes(s.type.toUpperCase()));
}

/**
 * The first manifest transaction whose profile has outgoing calls (the trace root first), with its
 * detail; the trace root when none has (its tab then shows the empty state).
 */
export async function transactionWithCalls(
  ctx: Pick<TxCheckContext, 'apiAs' | 'seed'>,
): Promise<{ tx: ManifestTx; detail: ApiDetail }> {
  const { tx: all } = ctx.seed;
  const candidates = [all.trace_root_two_apps, all.captured_sql_stack, all.error, all.batch];
  let first: { tx: ManifestTx; detail: ApiDetail } | null = null;
  for (const tx of candidates) {
    const detail = await apiDetail(ctx, tx.tx_id, tx.ts);
    first ??= { tx, detail };
    if (callSteps(detail).length > 0) {
      return { tx, detail };
    }
  }
  if (first === null) {
    throw new Error('no manifest transaction');
  }
  return first;
}

const txExtTab: TxCheck = async (ctx) => {
  const { page } = ctx;
  const { tx, detail: d } = await transactionWithCalls(ctx);
  const back = backOf(tx);
  ctx.testInfo.annotations.push({
    type: 'ext-transaction',
    description: `${tx.tx_id}: ${String(callSteps(d).length)} outgoing call steps`,
  });
  await openDetail(page, detailPath(tx, { tab: 'ext', from: back.from, to: back.to }));
  await expect(page.getByTestId('transaction-ext-total-count')).toBeVisible();
  const calls = callSteps(d);
  const table = page.getByTestId('transaction-ext');
  if (calls.length === 0) {
    await expect(page.getByTestId('transaction-ext-empty')).toBeVisible();
  } else {
    await expect(table.getByTestId('data-table-row')).toHaveCount(Math.min(calls.length, 100));
    for (const call of calls.slice(0, 5)) {
      if (call.name !== undefined) await expect(table).toContainText(call.name);
    }
    const host = await rangeOf(table.getByTestId('transaction-ext-host-link').first());
    expect(calls.map((c) => c.name_id)).toContain(host.path.split('/').pop());
    expect(host.from).toBe(back.from);
    expect(host.to).toBe(back.to);
  }
  if (d.trace_id !== null) {
    const trace = await rangeOf(page.getByTestId('transaction-ext-trace-link'));
    expect(trace.path).toBe(`/traces/${d.trace_id}`);
    expect(trace.from).toBe(back.from);
    const href = (await page.getByTestId('transaction-ext-trace-link').getAttribute('href')) ?? '';
    expect(new URL(href, 'http://x').searchParams.get('ts')).toBe(canonicalTs(d.ts));
  }
};

/**
 * 2.2-7: exception class, the message note, frames and the error group link with the range, on a
 * failed transaction whose captured profile has an ERROR step with frames (the manifest's error
 * transaction when it has one, else searched among the failed transactions of the profile window:
 * the generator records 20 to 40 frames per ERROR step, so the seed holds them, C-207 (5)).
 */
const txErrorTab: TxCheck = async (ctx) => {
  const { page } = ctx;
  const search = await findSeedTransaction(ctx, {
    purpose: 'failed transaction with an ERROR step with frames',
    query: { error: true },
    order: 'tx_id',
    max: 40,
    first: [ctx.seed.tx.error],
    accept: (d) => d.error && errorFrameCount(d) > 0,
  });
  expect(
    search.found,
    'the seed holds a failed transaction whose profile has an ERROR step with frames',
  ).not.toBeNull();
  if (search.found === null) return;
  const { tx, detail: d } = search.found;
  const back = backOf(tx);
  await openDetail(page, detailPath(tx, { tab: 'error', from: back.from, to: back.to }));
  await expect(page.getByTestId('transaction-error-class')).toHaveText(shown(d.error_class));
  await expect(page.getByTestId('transaction-error-message')).toBeVisible();
  const frames = errorFrameCount(d);
  expect(frames, 'frames of the ERROR steps in the API').toBeGreaterThan(0);
  await expect(page.getByTestId('transaction-error').getByTestId('transaction-frame')).toHaveCount(
    frames,
  );
  // app_packages is not in the API yet (C-207 (3)): no bold frame, and the tab says why.
  await expect(page.getByTestId('transaction-error-app-frames-note')).toBeVisible();
  if (d.error_group_id === null) {
    await expect(page.getByTestId('transaction-error-no-group')).toBeVisible();
  } else {
    const group = await rangeOf(page.getByTestId('transaction-error-group-link'));
    expect(group.path).toBe(`/apps/${String(tx.app_id)}/stats/errors/${d.error_group_id}`);
    expect(group.from).toBe(back.from);
    expect(group.to).toBe(back.to);
  }
};

/**
 * 2.2-8: captures side by side, common frames highlighted, the deepest common frame marked, on a
 * captured transaction with two or more STACK captures that share frames (the manifest's captured
 * transaction when it has them, else one of 4 s and more of the profile window: the generator
 * captures from the 1 s threshold every 3 s with frames of a common base, C-207 (5)).
 */
const txStackView: TxCheck = async (ctx) => {
  const { page } = ctx;
  const search = await findSeedTransaction(ctx, {
    purpose: 'two or more STACK captures with common frames',
    query: { kind: 'HTTP', min_ms: '4000' },
    order: 'elapsed_desc',
    max: 20,
    first: [ctx.seed.tx.captured_sql_stack],
    accept: (d) => commonStackFrames(d).length > 0,
  });
  expect(
    search.found,
    'the seed holds a captured transaction with two or more STACK captures sharing frames',
  ).not.toBeNull();
  if (search.found === null) return;
  const { tx, detail: d } = search.found;
  await openDetail(page, detailPath(tx, { tab: 'stack' }));
  const captures = d.profile.steps
    .filter((s) => s.type.toUpperCase() === 'STACK')
    .sort((a, b) => a.start_offset_ms - b.start_offset_ms || a.seq - b.seq);
  expect(captures.length, 'STACK captures in the API').toBeGreaterThanOrEqual(2);
  const columns = page.getByTestId('transaction-stack-column');
  await expect(columns).toHaveCount(captures.length);
  await expect(page.getByTestId('transaction-stack')).toHaveAttribute(
    'data-captures',
    String(captures.length),
  );
  const firstFrames = captures[0]?.frames ?? [];
  const common = commonStackFrames(d);
  expect(common.length, 'frames common to every capture in the API').toBeGreaterThan(0);
  await expect(page.getByTestId('transaction-stack')).toHaveAttribute(
    'data-common',
    String(new Set(common).size),
  );
  const marked = columns.first().locator('[data-testid="transaction-frame"][data-common="true"]');
  await expect(marked).toHaveCount(common.length);
  await expect(columns.first().locator('[data-stuck="true"]')).toHaveCount(
    firstFrames.filter((f) => f === common[0]).length,
  );
  ctx.testInfo.annotations.push({
    type: 'stack-view',
    description: `${tx.tx_id}: ${String(captures.length)} captures, ${String(common.length)} common frames`,
  });
};

/** 2.2-9: the reason a profile was not captured (sampling) is shown (D-07, T-ACC-07 display). */
const txProfileReason: TxCheck = async (ctx) => {
  const { page } = ctx;
  const tx = ctx.seed.tx.sampled_out;
  const d = await apiDetail(ctx, tx.tx_id, tx.ts);
  expect(d.profile.state).toBe('sampled_out');
  await expect(page.getByTestId('transaction-profile-banner')).toHaveAttribute(
    'data-state',
    'sampled_out',
  );
  await expect(page.getByTestId('transaction-profile-notice-sampled-out')).toHaveText(
    t('tx:profile.sampledOut'),
  );
  await expect(page.getByTestId('transaction-timeline-empty')).toBeVisible();
};

/**
 * A callee: a transaction called by another transaction of its trace, with its ts. The two-app
 * trace of the manifest first (the root's callee first); when no node of it has a known parent
 * (the caller's profile may not be captured), a trace of the same seed with a cross-application
 * call (findTrace of traceChecks.ts, the search the trace view's checks use).
 */
async function calleesOf(
  ctx: TxCheckContext,
): Promise<{ tx_id: string; app_id: number; ts: string }[]> {
  const api = await viewerApi(ctx);
  const root = ctx.seed.tx.trace_root_two_apps;
  const trace = await api.data<{
    nodes: { tx_id: string; app_id: number; ts: string; parent_tx_id: string | null }[];
  }>(`/traces/${root.trace_id}`, { ts: root.ts });
  // Nodes called by another transaction of the trace (the root's callees first).
  const seeded = [
    ...trace.nodes.filter((n) => n.parent_tx_id === root.tx_id),
    ...trace.nodes.filter(
      (n) => n.parent_tx_id !== null && n.parent_tx_id !== root.tx_id && n.tx_id !== root.tx_id,
    ),
  ];
  if (seeded.length > 0) {
    return seeded;
  }
  const found = await findTrace(
    ctx,
    (candidate) => crossAppCallees(candidate).length > 0,
    'cross-application call with a caller HTTP step',
  );
  return found === null ? [] : crossAppCallees(found.trace);
}

/**
 * A callee whose detail names its calling transaction (caller.tx_id), with that detail: the
 * two-app trace is in the seed (ADR-0010 C-207 (5)), so none is a failure, not a skip.
 */
async function calleeWithCaller(
  ctx: TxCheckContext,
): Promise<{ callee: { tx_id: string; app_id: number; ts: string }; detail: ApiDetail } | null> {
  const callees = await calleesOf(ctx);
  expect(callees.length, 'a trace of the seed has a node called by another node').toBeGreaterThan(
    0,
  );
  for (const callee of callees.slice(0, 5)) {
    const detail = await apiDetail(ctx, callee.tx_id, callee.ts);
    if (detail.caller?.tx_id !== null && detail.caller?.tx_id !== undefined) {
      return { callee, detail };
    }
  }
  return null;
}

/** 2.2-10: caller, logs, JSON export download and the copied link. */
const txActions: TxCheck = async (ctx) => {
  // Opens several details (root, callee, caller, captured) and downloads the export.
  ctx.testInfo.slow();
  const { page } = ctx;
  const root = ctx.seed.tx.trace_root_two_apps;
  // The root of the trace has no caller: disabled with the reason shown next to it (not only to
  // screen readers: a disabled button takes no focus).
  const r = await apiDetail(ctx, root.tx_id, root.ts);
  await openDetail(page, detailPath(root));
  await expect(page.getByTestId('transaction-caller')).toBeDisabled();
  if (r.caller === null) {
    const reason = page.getByTestId('transaction-caller-reason');
    await expect(reason).toBeVisible();
    await expect(reason).not.toHaveClass(/visually-hidden/);
    await expect(reason).toHaveText(
      t(r.flags.incoming_trace ? 'tx:actions.callerUnknown' : 'tx:actions.callerNone'),
    );
  }
  const found = await calleeWithCaller(ctx);
  expect(
    found,
    'a callee of a seed trace whose detail names its calling transaction (caller.tx_id)',
  ).not.toBeNull();
  if (found !== null) {
    const { callee, detail: c } = found;
    await openDetail(page, detailPath(callee));
    // The summary of a called transaction: caller application, service and instance (05 §2.2-1).
    await expectSummaryMatches(page, c);
    if (c.caller !== null && c.caller.tx_id !== null) {
      const link = page.getByTestId('transaction-caller');
      const href = (await link.getAttribute('href')) ?? '';
      const url = new URL(href, 'http://x');
      expect(url.pathname).toBe(`/apps/${String(c.caller.app_id)}/transactions/${c.caller.tx_id}`);
      expect(url.searchParams.get('ts')).toBe(
        c.caller.ts === null ? null : canonicalTs(c.caller.ts),
      );
      // The caller link keeps the time range of the detail's links (same as the trace link).
      const traceLink = page.getByTestId('transaction-trace-link');
      const traceHref = (await traceLink.count()) > 0 ? await traceLink.getAttribute('href') : null;
      if (traceHref !== null) {
        const trace = new URL(traceHref, 'http://x');
        expect(url.searchParams.get('from')).toBe(trace.searchParams.get('from'));
        expect(url.searchParams.get('to')).toBe(trace.searchParams.get('to'));
      }
      await link.click();
      await expect(page.getByTestId('transaction-page')).toHaveAttribute(
        'data-tx-id',
        c.caller.tx_id,
        { timeout: 15_000 },
      );
      ctx.testInfo.annotations.push({
        type: 'caller',
        description: `callee ${callee.tx_id} opened its caller ${c.caller.tx_id} (app ${String(c.caller.app_id)})`,
      });
    }
  }
  // Logs, export and copy on the captured transaction.
  const tx = ctx.seed.tx.captured_sql_stack;
  const d = await apiDetail(ctx, tx.tx_id, tx.ts);
  await openDetail(page, detailPath(tx));
  if (d.links.logs === null) {
    await expect(page.getByTestId('transaction-logs-unavailable')).toBeDisabled();
    const reason = page.getByTestId('transaction-logs-reason');
    await expect(reason).toBeVisible();
    await expect(reason).not.toHaveClass(/visually-hidden/);
    await expect(reason).toHaveText(t('tx:actions.logsUnavailable'));
  } else {
    await expect(page.getByTestId('transaction-logs')).toHaveAttribute('href', d.links.logs);
  }
  await expectExportDownload(ctx, d);
  await expectCopiedLink(page, d);
};

/** The JSON export downloads the server's file (Content-Disposition name, JSON of the tx). */
export async function expectExportDownload(ctx: TxCheckContext, d: ApiDetail): Promise<void> {
  const { page } = ctx;
  const download = page.waitForEvent('download');
  await page.getByTestId('transaction-export').click();
  const file = await download;
  const path = await file.path();
  const body = JSON.parse(readFileSync(path, 'utf8')) as {
    tx_id?: unknown;
    data?: { tx_id?: unknown };
  };
  expect(body.tx_id ?? body.data?.tx_id).toBe(d.tx_id);
  // The name the server gives (Content-Disposition), asked as another user (HEAVY bucket).
  const api = await ctx.apiAs('admin');
  const response = await api.request('GET', `/transactions/${d.tx_id}/export`, {
    query: { ts: d.ts },
  });
  const disposition = response.headers.get('content-disposition') ?? '';
  const name = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)?.[1] ?? '';
  expect(name, 'Content-Disposition file name').not.toBe('');
  expect(file.suggestedFilename()).toBe(decodeURIComponent(name));
  await expect(page.getByTestId('transaction-export-status')).toContainText(
    file.suggestedFilename(),
  );
}

/** Copy link puts the canonical URL with ts on the clipboard. */
export async function expectCopiedLink(page: Page, d: ApiDetail): Promise<void> {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByTestId('transaction-copy-link').click();
  await expect(page.getByTestId('transaction-copy-link')).toHaveAttribute(
    'data-copy-state',
    'copied',
  );
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  const origin = new URL(page.url()).origin;
  expect(copied).toBe(
    `${origin}/apps/${String(d.app.app_id)}/transactions/${d.tx_id}?ts=${canonicalTs(d.ts)}`,
  );
}

export const TRANSACTION_CHECKS: Readonly<Record<string, TxCheck>> = {
  txSummaryFields,
  txPercentileCompare,
  txTimeline,
  txTreeFolding,
  txSqlTab,
  txExtTab,
  txErrorTab,
  txStackView,
  txProfileReason,
  txActions,
};
