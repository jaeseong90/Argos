// SPDX-License-Identifier: Apache-2.0
//
// Checks of the active transactions screen /apps/:appId/active (WO-35, docs/05 §2.6, docs/15
// §9.6, F-U04): the named checks of required/active.yaml (T-E2E-active-<n>) and the helpers of
// specs/active.spec.ts (T-E2E-active-20..43). Every value on the screen is compared with what the
// real server said in the same test: the last `active` push the page received from the real /ws
// (wsMode 'server', e2e/fixtures/ws.ts serverFrames) and GET /apps/{appId}/active asked the same
// question (14 §4.9), never with fixed numbers.
//
// Running transactions come from the dev-only active feed of the server test fixtures
// (e2e/stack/seed/activeFeed.ts: protocol-level fake agent sessions for the app `e2e-active`
// through the real collector), which covers every state and band on two instances; the real
// agent's own snapshots (WO-16) are checked by T-E2E-active-31 in the spec with shop-boot3 and the
// agent of the stack. The feed starts once per worker process and is shared by the spec and
// the required items; it stops in the spec's afterAll, at worker exit, or by itself after
// FEED_DURATION_S. Inside the Playwright image of e2e:docker there is no JVM: the checks that need
// it skip with the reason (as T-SV-020e does); the WebSocket stub checks run everywhere.
import { expect, type Page, type TestInfo } from '@playwright/test';

import { MISSING_DASH } from '../../src/utils/missing';
import { t } from '../fixtures/i18n';
import { expectShell, expectUrl, state } from '../fixtures/ui';
import type { RecordedServerFrame, WsStub } from '../fixtures/ws';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';
import {
  elapsedBand,
  expectedBuckets,
  expectedElapsedMs,
  startActiveFeed,
  type RunningActiveFeed,
} from '../stack/seed/activeFeed';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface ActiveCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly ws?: WsStub;
}

type ActiveCheck = (ctx: ActiveCheckContext) => Promise<void>;

/** The role the checks ask the API as (the page's default role). */
export const ACTIVE_ORACLE_ROLE = 'viewer' as const;
/** Lifetime of a feed process started by the checks (it also stops at worker exit). */
export const FEED_DURATION_S = 20 * 60;
/** Default band limits of the screen and the server (05 §2.6: 1 s, 3 s, 8 s). */
export const DEFAULT_LIMITS_MS: readonly [number, number, number] = [1000, 3000, 8000];
/** The custom thresholds of T-E2E-active-26 (seconds) and their limits. */
export const CUSTOM_THRESHOLDS = ['2', '5', '10'] as const;
export const CUSTOM_LIMITS_MS: readonly [number, number, number] = [2000, 5000, 10_000];
/** Server push period of the `active` topic (14 §5.3) and the agents' snapshot period. */
export const ACTIVE_PERIOD_MS = 5000;
/**
 * Largest "as of" the screen may show while pushes arrive: the newest agent snapshot is up to one
 * agent period old when the server pushes, and the push is up to one push period old before the
 * next one (both 5 s, independent clocks), plus 1 s of rounding.
 */
export const MAX_AS_OF_S = (2 * ACTIVE_PERIOD_MS) / 1000 + 1;
/** Pushes compared at most before a check gives up finding REST and push at the same snapshot. */
const MATCH_ATTEMPTS = 6;
const SKIP_NO_FEED =
  'no JVM for the active feed in the Playwright image of e2e:docker (the server half is ActiveFeedIT)';

// ---------------------------------------------------------------------------------------------
// The API shape (docs/14 §4.9; plain strings, the oracle never renders them)
// ---------------------------------------------------------------------------------------------

export interface ActiveInstanceApi {
  instance_id: number;
  name: string | null;
  state: string;
  snapshot_at: string | null;
  count: number;
  max_elapsed_ms: number | null;
  buckets: number[];
  truncated: boolean;
}

export interface ActiveItemApi {
  instance_id: number;
  tx_id: string;
  service: string | null;
  service_id: string | null;
  elapsed_ms: number;
  state: string;
  state_detail: string | null;
  thread: string | null;
  virtual_thread: boolean;
  user_id: string | null;
  user_id_mode: string | null;
  remote_ip: string | null;
  ip_mode: string | null;
  kind: string;
  started_at: string;
}

export interface ActiveApi {
  snapshot_at: string | null;
  per_instance: ActiveInstanceApi[];
  items: ActiveItemApi[];
}

/** GET /apps/{appId}/active (default every kind, as the screen asks). */
export async function restActive(
  api: ApiSession,
  appId: number,
  query: Readonly<Record<string, string | readonly string[]>> = { kind: 'ALL' },
): Promise<ActiveApi> {
  return (await api.query<ActiveApi>(`/apps/${String(appId)}/active`, query)).data;
}

// ---------------------------------------------------------------------------------------------
// The shared feed
// ---------------------------------------------------------------------------------------------

let shared: { readonly runId: string; readonly feed: Promise<RunningActiveFeed | null> } | null =
  null;
const feedLog: string[] = [];

/**
 * The running feed of this worker for the stack of `seed` (started on first use), or null where no
 * JVM is available (e2e:docker). A failed start is not cached: the next caller tries again.
 */
export function ensureActiveFeed(
  seed: SeedManifest,
  apiAs: (role: Role) => Promise<ApiSession>,
): Promise<RunningActiveFeed | null> {
  if (shared?.runId === seed.run_id) {
    return shared.feed;
  }
  const feed = (async () => {
    const api = await apiAs(ACTIVE_ORACLE_ROLE);
    const started = await startActiveFeed({
      stack: { runId: seed.run_id, api },
      log: (line) => {
        feedLog.push(`${new Date().toISOString()} ${line}`);
      },
      durationS: FEED_DURATION_S,
    });
    if (started !== null) {
      // stop() sends the kill signal before its first await: a worker exit still ends the feed.
      process.once('exit', () => {
        void started.stop();
      });
      await expectScenarioInApi(api, started);
    }
    return started;
  })();
  const entry = { runId: seed.run_id, feed };
  shared = entry;
  feed.catch(() => {
    if (shared === entry) {
      shared = null;
    }
  });
  return feed;
}

/** Stops the shared feed (the spec's afterAll); the next ensureActiveFeed starts a new one. */
export async function stopActiveFeed(): Promise<void> {
  const entry = shared;
  shared = null;
  const feed = await entry?.feed.catch(() => null);
  await feed?.stop();
}

/** The feed's progress lines so far (attached by the spec). */
export function activeFeedLog(): string {
  return feedLog.join('\n');
}

/** The shared feed, or a skip of the test with the reason (e2e:docker). */
export async function requireFeed(ctx: ActiveCheckContext): Promise<RunningActiveFeed> {
  // Starting the feed (and building its jar when missing) may take longer than a test.
  ctx.testInfo.setTimeout(Math.max(ctx.testInfo.timeout, 240_000));
  const feed = await ensureActiveFeed(ctx.seed, ctx.apiAs);
  ctx.testInfo.skip(feed === null, SKIP_NO_FEED);
  if (feed === null) {
    throw new Error('unreachable: skipped');
  }
  return feed;
}

/**
 * The feed's scenario is what GET /apps/{appId}/active shows: every instance with all four default
 * bands, the five states, the names resolved (the precondition of every real-data check).
 */
export async function expectScenarioInApi(
  api: ApiSession,
  feed: RunningActiveFeed,
): Promise<ActiveApi> {
  const seen: ActiveApi[] = [];
  await expect
    .poll(
      async () => {
        const data = await restActive(api, feed.appId);
        seen.push(data);
        const problems: string[] = [];
        for (const instance of feed.instances) {
          const row = data.per_instance.find((p) => p.instance_id === instance.instanceId);
          if (row === undefined) {
            problems.push(`${instance.name} missing`);
          } else if (row.buckets.some((b) => b < 1)) {
            problems.push(`${instance.name} buckets ${row.buckets.join(',')}`);
          }
        }
        const states = new Set(data.items.map((i) => i.state));
        for (const s of ['SQL', 'HTTP', 'DBC', 'METHOD', 'ASYNC']) {
          if (!states.has(s)) {
            problems.push(`state ${s} missing`);
          }
        }
        return problems;
      },
      { timeout: 60_000, message: 'GET /active shows all four bands per instance and five states' },
    )
    .toEqual([]);
  const last = seen.at(-1);
  if (last === undefined) {
    throw new Error('unreachable');
  }
  return last;
}

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

export function activePath(appId: number, query = 'kind=ALL'): string {
  return `/apps/${String(appId)}/active${query === '' ? '' : `?${query}`}`;
}

/** Opens the screen of `appId` and waits until it shows a snapshot of the server. */
export async function openActive(page: Page, appId: number, query = 'kind=ALL'): Promise<void> {
  await page.goto(activePath(appId, query));
  await expectShell(page);
  await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
}

/** Opens the feed's screen (every kind, so BATCH rows show too). */
export async function openFeedScreen(
  ctx: ActiveCheckContext,
  query = 'kind=ALL',
): Promise<RunningActiveFeed> {
  const feed = await requireFeed(ctx);
  await openActive(ctx.page, feed.appId, query);
  return feed;
}

export interface ScreenBar {
  readonly id: number;
  readonly state: string;
  readonly count: number;
  readonly buckets: number[];
  readonly approx: boolean;
  readonly selected: boolean;
}

export interface ScreenRow {
  readonly tx: string;
  readonly band: number;
  readonly instance: string;
  readonly service: string;
  readonly elapsed: number;
  readonly cellBand: number;
  readonly state: string;
  readonly detail: string | null;
  readonly thread: string;
  readonly virtual: boolean;
  readonly user: string;
  readonly userMissing: string | null;
  readonly ip: string;
  readonly ipMissing: string | null;
  readonly kind: string;
  readonly started: string | null;
}

export interface ScreenRead {
  readonly state: string | null;
  readonly source: string | null;
  readonly paused: string | null;
  readonly snapshotAt: string | null;
  readonly asOf: number | null;
  readonly bars: ScreenBar[];
  readonly summary: {
    readonly total: number | null;
    readonly slow: number | null;
    readonly max: number | null;
    readonly maxInstance: number | null;
    readonly maxName: string | null;
  };
  readonly rows: ScreenRow[];
}

/** Everything the screen shows, read in one go (so it belongs to one displayed snapshot). */
export async function readScreen(page: Page): Promise<ScreenRead> {
  return page.evaluate(() => {
    const byId = (id: string): HTMLElement | null =>
      document.querySelector<HTMLElement>(`[data-testid="${CSS.escape(id)}"]`);
    const num = (value: string | null | undefined): number | null =>
      value === null || value === undefined || value === '' ? null : Number(value);
    const root = byId('active-page');
    const bars = [
      ...document.querySelectorAll<HTMLElement>('[data-testid="active-bars"] > li'),
    ].map((li) => ({
      id: Number((li.dataset.testid ?? '').slice('active-bar-'.length)),
      state: li.dataset.state ?? '',
      count: Number(li.dataset.count),
      buckets: (li.dataset.buckets ?? '').split(',').map(Number),
      approx: li.dataset.approx === 'true',
      selected: li.dataset.selected === 'true',
    }));
    const rows = [
      ...document.querySelectorAll<HTMLElement>(
        '[data-testid="active-table"] tr[data-testid^="active-row-"]',
      ),
    ].map((tr) => {
      const tx = (tr.dataset.testid ?? '').slice('active-row-'.length);
      const cell = (name: string): HTMLElement | null => byId(`active-cell-${name}-${tx}`);
      const thread = cell('thread');
      const threadText =
        thread === null
          ? ''
          : [...thread.childNodes]
              .filter(
                (n) =>
                  !(
                    n instanceof HTMLElement &&
                    (n.dataset.testid ?? '').startsWith('active-cell-virtual-')
                  ),
              )
              .map((n) => n.textContent)
              .join('');
      const detail = byId(`active-cell-state-detail-${tx}`);
      return {
        tx,
        band: Number(tr.dataset.elapsedBand),
        instance: cell('instance')?.textContent ?? '',
        service: cell('service')?.textContent ?? '',
        elapsed: Number(cell('elapsed')?.dataset.value),
        cellBand: Number(cell('elapsed')?.dataset.band),
        state: cell('state')?.dataset.state ?? '',
        detail: detail === null ? null : detail.textContent,
        thread: threadText,
        virtual: byId(`active-cell-virtual-${tx}`) !== null,
        user: cell('user')?.textContent ?? '',
        userMissing: cell('user')?.dataset.missing ?? null,
        ip: cell('ip')?.textContent ?? '',
        ipMissing: cell('ip')?.dataset.missing ?? null,
        kind: cell('kind')?.dataset.kind ?? '',
        started: cell('started')?.dataset.value ?? null,
      };
    });
    const max = byId('active-summary-max');
    return {
      state: root?.dataset.state ?? null,
      source: root?.dataset.source ?? null,
      paused: root?.dataset.paused ?? null,
      snapshotAt: root?.dataset.snapshotAt ?? null,
      asOf: num(byId('active-asof')?.dataset.seconds),
      bars,
      summary: {
        total: num(byId('active-summary-total')?.dataset.value),
        slow: num(byId('active-summary-slow')?.dataset.value),
        max: num(max?.dataset.value),
        maxInstance: num(max?.dataset.instance),
        maxName: byId('active-summary-max-name')?.textContent ?? null,
      },
      rows,
    };
  });
}

const MISSING = MISSING_DASH;

/** The kinds a global kind shows (ALL: every kind). */
function kindMatches(kind: string, filter: string): boolean {
  return filter === 'ALL' || kind === filter;
}

/** The band of `elapsedMs` for limits (the screen's rule: at or above a limit is the next band). */
export function bandFor(elapsedMs: number, limits: readonly [number, number, number]): number {
  return elapsedBand(elapsedMs, limits);
}

/**
 * The screen shows `data` exactly: the bars (count, the four default bands, the instance state),
 * the summary (Σ count, Σ top band, the busiest instance) and the list (every item of `kind`, the
 * nine columns), sorted by elapsed, longest first.
 */
export function expectScreenShows(
  read: ScreenRead,
  data: ActiveApi,
  options: { readonly kind?: string; readonly what: string },
): void {
  const kind = options.kind ?? 'ALL';
  const what = options.what;
  expect(read.snapshotAt, `${what}: displayed snapshot`).toBe(data.snapshot_at);
  expect(
    read.bars.map((b) => ({ id: b.id, state: b.state, count: b.count, buckets: b.buckets })),
    `${what}: bars = per_instance (count, buckets, state)`,
  ).toEqual(
    data.per_instance.map((p) => ({
      id: p.instance_id,
      state: p.state,
      count: p.count,
      buckets: p.buckets,
    })),
  );
  const total = data.per_instance.reduce((sum, p) => sum + p.count, 0);
  const slow = data.per_instance.reduce((sum, p) => sum + (p.buckets[3] ?? 0), 0);
  let busiest: ActiveInstanceApi | null = null;
  for (const p of data.per_instance) {
    if (busiest === null || p.count > busiest.count) {
      busiest = p;
    }
  }
  expect(read.summary, `${what}: summary (running, top band, busiest instance)`).toEqual({
    total,
    slow,
    max: busiest?.count ?? null,
    maxInstance: busiest?.instance_id ?? null,
    maxName: busiest?.name ?? null,
  });

  const names = new Map(data.per_instance.map((p) => [p.instance_id, p.name] as const));
  const items = data.items.filter((i) => kindMatches(i.kind, kind));
  expect(
    read.rows.map((r) => r.tx).sort(),
    `${what}: rows = items of kind ${kind} (tx ids)`,
  ).toEqual(items.map((i) => i.tx_id).sort());
  for (const item of items) {
    const row = read.rows.find((r) => r.tx === item.tx_id);
    const showsDetail = ['SQL', 'HTTP', 'DBC'].includes(item.state);
    const userOff = item.user_id === null && item.user_id_mode === 'off';
    const ipOff = item.remote_ip === null && item.ip_mode === 'off';
    expect(row, `${what}: row of tx ${item.tx_id}`).toEqual({
      tx: item.tx_id,
      band: bandFor(item.elapsed_ms, DEFAULT_LIMITS_MS),
      instance: names.get(item.instance_id) ?? `Instance ${String(item.instance_id)}`,
      service: item.service ?? MISSING,
      elapsed: item.elapsed_ms,
      cellBand: bandFor(item.elapsed_ms, DEFAULT_LIMITS_MS),
      state: item.state,
      detail: showsDetail ? (item.state_detail ?? MISSING) : null,
      thread: item.thread ?? MISSING,
      virtual: item.virtual_thread,
      user: userOff ? t('active:table.notCollected') : (item.user_id ?? MISSING),
      userMissing: userOff ? 'notCollected' : null,
      ip: ipOff ? t('active:table.notCollected') : (item.remote_ip ?? MISSING),
      ipMissing: ipOff ? 'notCollected' : null,
      kind: item.kind,
      started: item.started_at,
    });
  }
  const elapsed = read.rows.map((r) => r.elapsed);
  expect(elapsed, `${what}: default order elapsed descending`).toEqual(
    [...elapsed].sort((a, b) => b - a),
  );
}

/** The items of a snapshot are the feed's scenario: names, states, details, modes, elapsed. */
export function expectScenario(data: ActiveApi, feed: RunningActiveFeed, what: string): void {
  for (const instance of feed.instances) {
    const p = data.per_instance.find((i) => i.instance_id === instance.instanceId);
    expect(p?.name, `${what}: instance ${String(instance.instanceId)} name`).toBe(instance.name);
    const at = Date.parse(p?.snapshot_at ?? '');
    expect(p?.buckets, `${what}: buckets of ${instance.name} = scenario`).toEqual(
      expectedBuckets(feed.scenario, instance.name, at),
    );
  }
  const names = new Map(feed.instances.map((i) => [i.instanceId, i.name] as const));
  expect(data.items.length, `${what}: items = scenario rows`).toBe(feed.scenario.length);
  for (const item of data.items) {
    const instance = names.get(item.instance_id) ?? '';
    const row = feed.scenario.find(
      (r) => r.instance === instance && r.service === item.service && r.state === item.state,
    );
    expect(
      row,
      `${what}: scenario row of ${instance} ${String(item.service)} ${item.state}`,
    ).toBeDefined();
    if (row === undefined) {
      continue;
    }
    const snapshotMs = Date.parse(
      data.per_instance.find((p) => p.instance_id === item.instance_id)?.snapshot_at ?? '',
    );
    expect(
      {
        kind: item.kind,
        detail: item.state_detail,
        thread: item.thread,
        virtual: item.virtual_thread,
        user: item.user_id,
        userMode: item.user_id_mode,
        ip: item.remote_ip,
        ipMode: item.ip_mode,
        elapsed: item.elapsed_ms,
      },
      `${what}: item of ${instance} ${String(item.service)} ${item.state}`,
    ).toEqual({
      kind: row.kind,
      detail: row.state_detail,
      thread: row.thread,
      virtual: row.virtual_thread,
      user: emptyAsNull(row.user_id),
      userMode: row.user_id_mode,
      ip: emptyAsNull(row.remote_ip),
      ipMode: row.ip_mode,
      elapsed: expectedElapsedMs(row, snapshotMs),
    });
  }
}

function emptyAsNull(value: string | null): string | null {
  return value === null || value === '' ? null : value;
}

// ---------------------------------------------------------------------------------------------
// Real `active` pushes (wsMode 'server')
// ---------------------------------------------------------------------------------------------

export interface ActivePush {
  /** Index in serverFrames(). */
  readonly index: number;
  /** Node time of arrival (epoch ms). */
  readonly at: number;
  /** Server time of the push (`t`, epoch ms). */
  readonly t: number;
  readonly data: ActiveApi;
}

function requireWs(ctx: ActiveCheckContext): WsStub {
  if (ctx.ws === undefined) {
    throw new Error('this check needs the ws fixture');
  }
  return ctx.ws;
}

/** The pushes of subscription `id` received so far. */
export function activePushes(ws: WsStub, id: string): ActivePush[] {
  return ws.serverFrames().flatMap((f: RecordedServerFrame, index) => {
    const frame = f.frame;
    if (frame?.op !== 'push' || frame.topic !== 'active' || frame.id !== id) {
      return [];
    }
    return [{ index, at: f.at, t: Date.parse(frame.t), data: frame.data as unknown as ActiveApi }];
  });
}

/** The page's acknowledged `active` subscription: {app, kind: 'ALL'} (14 §5.3). */
export async function activeSubscription(ws: WsStub, appId: number): Promise<string> {
  const subscription = await ws.waitForSubscription('active', 30_000);
  expect(subscription.params, 'the active subscription of the screen').toEqual({
    app: appId,
    kind: 'ALL',
  });
  return subscription.id;
}

/** Waits for the next push of `id` after the `after`-th recorded frame. */
export async function nextPush(
  ws: WsStub,
  id: string,
  after: number,
  timeoutMs = 3 * ACTIVE_PERIOD_MS,
): Promise<ActivePush> {
  let next: ActivePush | undefined;
  await expect
    .poll(
      () => {
        next = activePushes(ws, id).find((p) => p.index > after);
        return next !== undefined;
      },
      {
        timeout: timeoutMs,
        message: `an active push of subscription ${id} after frame ${String(after)}`,
      },
    )
    .toBe(true);
  if (next === undefined) {
    throw new Error('unreachable');
  }
  return next;
}

/**
 * A push, the screen showing it (read in one go) and the REST answer of the same snapshot. Tries
 * the next push when the screen or REST moved on in between.
 */
export async function matchedSnapshot(
  ctx: ActiveCheckContext,
  appId: number,
): Promise<{ push: ActivePush; read: ScreenRead; rest: ActiveApi }> {
  const ws = requireWs(ctx);
  const api = await ctx.apiAs(ACTIVE_ORACLE_ROLE);
  const id = await activeSubscription(ws, appId);
  let after = ws.serverFrames().length - 1;
  const misses: string[] = [];
  for (let attempt = 0; attempt < MATCH_ATTEMPTS; attempt += 1) {
    const push = await nextPush(ws, id, after);
    after = push.index;
    const rest = await restActive(api, appId);
    await expect(ctx.page.getByTestId('active-page')).toHaveAttribute(
      'data-snapshot-at',
      push.data.snapshot_at ?? '',
      { timeout: ACTIVE_PERIOD_MS },
    );
    const read = await readScreen(ctx.page);
    const sameRest = JSON.stringify(rest) === JSON.stringify(push.data);
    if (read.snapshotAt === push.data.snapshot_at && sameRest) {
      return { push, read, rest };
    }
    misses.push(
      `push ${String(push.data.snapshot_at)}: screen ${String(read.snapshotAt)}, REST ${String(rest.snapshot_at)}${sameRest ? '' : ' (REST differs)'}`,
    );
  }
  throw new Error(`no push matched the screen and REST: ${misses.join('; ')}`);
}

// ---------------------------------------------------------------------------------------------
// Checks of required/active.yaml (and the spec)
// ---------------------------------------------------------------------------------------------

/** 2.6-1 / 2.6-2 / 2.6-5: the screen = the last real push = REST at the same snapshot. */
export const activeMatchesPushAndRest: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const { push, read, rest } = await matchedSnapshot(ctx, feed.appId);
  expect(read.source, 'the displayed snapshot came from the push').toBe('ws');
  expect(rest, 'REST at the same snapshot = the push (14 §5.2: same schema and rules)').toEqual(
    push.data,
  );
  expectScreenShows(read, push.data, { what: 'last real active push' });
  expectScenario(push.data, feed, 'last real active push');
};

/** 2.6-1: bars of every instance with the running count and the four elapsed bands. */
export const activeBars: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const { read, push } = await matchedSnapshot(ctx, feed.appId);
  expectScreenShows(read, push.data, { what: 'bars' });
  for (const bar of read.bars) {
    expect(
      bar.buckets.every((b) => b >= 1),
      `bar ${String(bar.id)} shows all four bands`,
    ).toBe(true);
    const numbers = ctx.page.getByTestId(`active-bar-numbers-${String(bar.id)}`);
    await expect(numbers.locator('[data-band]')).toHaveCount(4);
  }
  await expect(
    ctx.page.getByTestId('active-legend').locator('[data-testid^="active-legend-"]'),
  ).toHaveCount(4);
};

/** 2.6-2: the nine columns of every row = the push and REST, user/IP "not collected" by mode. */
export const activeColumns: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const { read, push } = await matchedSnapshot(ctx, feed.appId);
  expectScreenShows(read, push.data, { what: 'list' });
  expectScenario(push.data, feed, 'list');
  const headers = await ctx.page.getByTestId('active-table').locator('th').allTextContents();
  for (const key of [
    'instance',
    'service',
    'elapsed',
    'state',
    'thread',
    'user',
    'ip',
    'kind',
    'started',
  ]) {
    const label = t(`active:table.${key}`);
    expect(
      headers.some((h) => h.includes(label)),
      `column ${label} in ${headers.join(' | ')}`,
    ).toBe(true);
  }
  // The five states with their detail (SQL text, host, DB target) and a virtual thread.
  const states = new Set(read.rows.map((r) => r.state));
  expect([...states].sort()).toEqual(['ASYNC', 'DBC', 'HTTP', 'METHOD', 'SQL']);
  for (const row of read.rows.slice(0, 1)) {
    await expect(ctx.page.getByTestId(`active-cell-state-${row.tx}`)).toContainText(
      t(`active:state.${row.state}`),
    );
  }
  expect(
    read.rows.some((r) => r.virtual),
    'a virtual thread is marked',
  ).toBe(true);
  expect(
    read.rows.some((r) => r.userMissing === 'notCollected') &&
      read.rows.some((r) => r.userMissing === null && r.user === MISSING),
    'user id "not collected" (mode off) differs from an absent value (dash)',
  ).toBe(true);
};

/** 2.6-3: 5 s refresh from the real pushes and "as of N s ago" measured on the server clock. */
export const activeRefresh: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const ws = requireWs(ctx);
  const id = await activeSubscription(ws, feed.appId);
  const start = ws.serverFrames().length - 1;
  // The first push may be the one sent on subscribing (before the next 5 s tick): start after it.
  const first = await nextPush(ws, id, start);
  // About 20 s: at least three more pushes.
  const samples: {
    at: number;
    asOf: number | null;
    snapshotAt: string | null;
    elapsed: Map<string, number>;
  }[] = [];
  const end = Date.now() + 4 * ACTIVE_PERIOD_MS;
  while (Date.now() < end) {
    const read = await readScreen(ctx.page);
    samples.push({
      at: Date.now(),
      asOf: read.asOf,
      snapshotAt: read.snapshotAt,
      elapsed: new Map(read.rows.map((r) => [r.tx, r.elapsed] as const)),
    });
    await ctx.page.waitForTimeout(400);
  }
  const pushes = activePushes(ws, id).filter((p) => p.index > first.index);
  ctx.testInfo.annotations.push({
    type: 'active-pushes',
    description: pushes
      .map(
        (p) =>
          `${new Date(p.at).toISOString()} snapshot ${String(p.data.snapshot_at)} (t-snapshot ${String(p.t - Date.parse(p.data.snapshot_at ?? ''))} ms)`,
      )
      .join('; '),
  });
  expect(pushes.length, 'real active pushes in about 20 s').toBeGreaterThanOrEqual(3);
  for (let i = 1; i < pushes.length; i += 1) {
    const gap = (pushes[i]?.at ?? 0) - (pushes[i - 1]?.at ?? 0);
    expect(
      gap,
      `push ${String(i)} came ${String(gap)} ms after the one before`,
    ).toBeLessThanOrEqual(ACTIVE_PERIOD_MS + 2000);
    expect(gap).toBeGreaterThanOrEqual(ACTIVE_PERIOD_MS - 2000);
  }
  // The screen took every push but the last (which may have come after the last sample).
  const shown = new Set(samples.map((s) => s.snapshotAt));
  for (const p of pushes.slice(0, -1)) {
    expect(
      shown.has(p.data.snapshot_at),
      `the screen showed snapshot ${String(p.data.snapshot_at)}`,
    ).toBe(true);
  }
  // "As of": the push's age at arrival on the server clock plus the time since, never above the
  // two periods of agent and push; each sample within 1 s of that.
  for (const sample of samples) {
    const push = [...pushes]
      .reverse()
      .find((p) => p.data.snapshot_at === sample.snapshotAt && p.at <= sample.at);
    expect(sample.asOf, 'as of shown').not.toBeNull();
    expect(sample.asOf ?? 99, 'as of (s)').toBeLessThanOrEqual(MAX_AS_OF_S);
    if (push !== undefined) {
      const expected =
        (push.t - Date.parse(push.data.snapshot_at ?? '') + (sample.at - push.at)) / 1000;
      expect(
        Math.abs((sample.asOf ?? 0) - expected),
        `as of ${String(sample.asOf)} s vs ${expected.toFixed(1)} s`,
      ).toBeLessThanOrEqual(2.5);
    }
  }
  // Long transactions keep their row and their elapsed grows with every snapshot.
  const firstSample = samples.find((s) => s.snapshotAt === pushes[0]?.data.snapshot_at);
  const lastSample = samples[samples.length - 1];
  const longTx = [...(firstSample?.elapsed.entries() ?? [])].find(
    ([tx, ms]) => ms >= DEFAULT_LIMITS_MS[2] && lastSample?.elapsed.has(tx) === true,
  );
  expect(longTx, 'a long transaction in the first and the last sample').toBeDefined();
  if (
    longTx !== undefined &&
    lastSample !== undefined &&
    firstSample !== undefined &&
    lastSample.snapshotAt !== firstSample.snapshotAt
  ) {
    expect(lastSample.elapsed.get(longTx[0]) ?? 0, 'its elapsed grew').toBeGreaterThan(longTx[1]);
  }
  // Default sort: elapsed descending (no sort key in the URL).
  expect(state(ctx.page).params.get('sort')).toBeNull();
  const read = await readScreen(ctx.page);
  const elapsed = read.rows.map((r) => r.elapsed);
  expect(elapsed).toEqual([...elapsed].sort((a, b) => b - a));
};

/** 2.6-3: pause freezes the display while pushes keep coming; resume shows the newest at once. */
export const activePause: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const ws = requireWs(ctx);
  const page = ctx.page;
  const id = await activeSubscription(ws, feed.appId);
  const root = page.getByTestId('active-page');
  await nextPush(ws, id, ws.serverFrames().length - 1);
  await page.getByTestId('active-pause').click();
  await expect(page.getByTestId('active-pause')).toHaveAttribute('aria-pressed', 'true');
  await expect(root).toHaveAttribute('data-paused', 'true');
  const frozen = await readScreen(page);
  const pausedText =
    t('active:asOf.paused', 'en', { seconds: String(frozen.asOf ?? 0) })
      .split('·')[0]
      ?.trim() ?? '';
  await expect(page.getByTestId('active-asof')).toContainText(pausedText);
  const pausedAt = ws.serverFrames().length - 1;
  const p1 = await nextPush(ws, id, pausedAt);
  const p2 = await nextPush(ws, id, p1.index);
  expect(p2.data.snapshot_at, 'new snapshots arrived while paused').not.toBe(frozen.snapshotAt);
  const still = await readScreen(page);
  expect(still.snapshotAt, 'the displayed snapshot stays frozen').toBe(frozen.snapshotAt);
  expect(
    still.rows.map((r) => [r.tx, r.elapsed]),
    'rows frozen',
  ).toEqual(frozen.rows.map((r) => [r.tx, r.elapsed]));
  expect(still.asOf ?? 0, 'as of keeps counting while paused').toBeGreaterThan(frozen.asOf ?? 0);
  await page.getByTestId('active-pause').click();
  await expect(root).toHaveAttribute('data-paused', 'false');
  // Resume shows the newest snapshot at once (no wait for the next push).
  await expect
    .poll(
      async () =>
        (await root.getAttribute('data-snapshot-at')) ===
        activePushes(ws, id).at(-1)?.data.snapshot_at,
      { timeout: 1500, message: 'the newest push shows right after resume' },
    )
    .toBe(true);
};

/** Rows of the screen as instance|service|state|kind signatures (stable across snapshots). */
function signatures(rows: readonly ScreenRow[]): string[] {
  return rows.map((r) => `${r.instance}|${r.service}|${r.state}|${r.kind}`).sort();
}

function apiSignatures(
  data: ActiveApi,
  keep: (item: ActiveItemApi) => boolean = () => true,
): string[] {
  const names = new Map(data.per_instance.map((p) => [p.instance_id, p.name] as const));
  return data.items
    .filter(keep)
    .map((i) => `${names.get(i.instance_id) ?? ''}|${i.service ?? MISSING}|${i.state}|${i.kind}`)
    .sort();
}

function barShape(read: ScreenRead): string[] {
  return read.bars.map((b) => `${String(b.id)}:${String(b.count)}:${b.buckets.join(',')}`);
}

async function expectRows(
  page: Page,
  expected: () => Promise<string[]>,
  what: string,
): Promise<ScreenRead> {
  // The list follows the snapshots (fresh rows come and go with new tx ids); the signatures of the
  // scenario stay the same, so screen and API agree as soon as the filter is applied.
  await expect
    .poll(
      async () =>
        JSON.stringify(signatures((await readScreen(page)).rows)) ===
        JSON.stringify(await expected()),
      { message: what, timeout: 15_000 },
    )
    .toBe(true);
  const read = await readScreen(page);
  expect(signatures(read.rows), what).toEqual(await expected());
  return read;
}

/** 2.6-1: a bar click filters the list to that instance (URL f.instance); the bars stay. */
export const activeBarFilter: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const page = ctx.page;
  const api = await ctx.apiAs(ACTIVE_ORACLE_ROLE);
  const before = await readScreen(page);
  const target = feed.instances[feed.instances.length - 1];
  if (target === undefined) {
    throw new Error('the feed has no instance');
  }
  await page.getByTestId(`active-bar-button-${String(target.instanceId)}`).click();
  await expectUrl(
    page,
    (s) => s.params.getAll('f.instance').includes(String(target.instanceId)),
    `f.instance=${String(target.instanceId)}`,
  );
  await expect(page.getByTestId(`active-bar-${String(target.instanceId)}`)).toHaveAttribute(
    'data-selected',
    'true',
  );
  const filtered = await expectRows(
    page,
    async () =>
      apiSignatures(
        await restActive(api, feed.appId, { kind: 'ALL', instance: [String(target.instanceId)] }),
      ),
    `rows = GET /active?instance=${String(target.instanceId)}`,
  );
  expect(filtered.rows.every((r) => r.instance === target.name)).toBe(true);
  expect(barShape(filtered), 'the bars do not filter').toEqual(barShape(before));
  expect(filtered.summary.total, 'the summary does not filter').toBe(before.summary.total);
  // A second click removes the filter.
  await page.getByTestId(`active-bar-button-${String(target.instanceId)}`).click();
  await expectUrl(page, (s) => s.params.getAll('f.instance').length === 0, 'f.instance removed');
  await expect.poll(async () => (await readScreen(page)).rows.length).toBe(before.rows.length);
};

/** 2.6-3: instance, state, service and kind filters: URL keys, rows = the API's answer. */
export const activeFilters: ActiveCheck = async (ctx) => {
  await activeBarFilter(ctx);
  const feed = await requireFeed(ctx);
  const page = ctx.page;
  const api = await ctx.apiAs(ACTIVE_ORACLE_ROLE);
  const before = await readScreen(page);

  // State (14 §6 f.state).
  await page.getByTestId('active-state-filter-SQL').click();
  await expectUrl(page, (s) => s.params.get('f.state') === 'SQL', 'f.state=SQL');
  await expect(page.getByTestId('active-state-filter-SQL')).toHaveAttribute('aria-pressed', 'true');
  const bySql = await expectRows(
    page,
    async () => apiSignatures(await restActive(api, feed.appId, { kind: 'ALL', state: 'SQL' })),
    'rows = GET /active?state=SQL',
  );
  expect(bySql.rows.length).toBeGreaterThan(0);
  expect(barShape(bySql), 'the bars do not filter').toEqual(barShape(before));
  await page.getByTestId('active-state-filter-all').click();
  await expectUrl(page, (s) => s.params.get('f.state') === null, 'f.state removed');

  // Service (f.service = the service key, 14 §6) from the value filter of a cell. The REST API has
  // no service parameter (14 §4.9): its answer is filtered by service_id here.
  const fixed = before.rows.find(
    (r) => r.service === 'GET /api/orders/{id}' && r.elapsed >= DEFAULT_LIMITS_MS[2],
  );
  if (fixed === undefined) {
    throw new Error('the scenario row GET /api/orders/{id} of 8 s or more is not on the screen');
  }
  const item = (await restActive(api, feed.appId)).items.find((i) => i.tx_id === fixed.tx);
  if (item?.service_id == null) {
    throw new Error(`GET /active has no service key for tx ${fixed.tx}`);
  }
  const serviceId = item.service_id;
  const cell = page.getByTestId(`active-cell-service-${fixed.tx}`).locator('xpath=ancestor::td');
  await cell.hover();
  await cell.getByTestId('cell-filter-include').click();
  await expectUrl(page, (s) => s.params.get('f.service') === serviceId, `f.service=${serviceId}`);
  const byService = await expectRows(
    page,
    async () => apiSignatures(await restActive(api, feed.appId), (i) => i.service_id === serviceId),
    `rows = GET /active items of service ${serviceId}`,
  );
  expect(byService.rows.length).toBeGreaterThan(1);
  await expect(page.getByTestId('filter-chip-bar')).toContainText('GET /api/orders/{id}');
  await page.goto(activePath(feed.appId));
  await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready');

  // Kind (the global kind of the top bar).
  const batchOption = page.getByTestId('kind-filter-batch');
  if (await batchOption.isEnabled()) {
    await batchOption.click();
  } else {
    // The top bar offers BATCH only for apps with stored batch transactions (GET /apps/{id}
    // kinds); the feed stores none, so the global kind comes from the URL as a deep link would.
    ctx.testInfo.annotations.push({
      type: 'kind-batch-disabled',
      description: 'GET /apps/{id} kinds has no BATCH for the feed app: kind=BATCH set by URL',
    });
    await page.goto(activePath(feed.appId, 'kind=BATCH'));
    await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready');
  }
  await expectUrl(page, (s) => s.params.get('kind') === 'BATCH', 'kind=BATCH');
  const batch = await expectRows(
    page,
    async () => apiSignatures(await restActive(api, feed.appId, { kind: 'BATCH' })),
    'rows = GET /active?kind=BATCH',
  );
  expect(batch.rows.length).toBeGreaterThan(0);
  expect(batch.rows.every((r) => r.kind === 'BATCH')).toBe(true);
  expect(barShape(batch), 'the bars do not filter by kind').toEqual(barShape(before));
  await page.getByTestId('kind-filter-http').click();
  await expectUrl(
    page,
    (s) => s.params.get('kind') === null || s.params.get('kind') === 'HTTP',
    'kind HTTP',
  );
  await expectRows(
    page,
    async () => apiSignatures(await restActive(api, feed.appId, { kind: 'HTTP' })),
    'rows = GET /active?kind=HTTP',
  );
};

/** The feed's instances in instance id order (the order of the bars). */
function byId(feed: RunningActiveFeed): { name: string; instanceId: number }[] {
  return [...feed.instances].sort((a, b) => a.instanceId - b.instanceId);
}

/** 2.6-1: custom thresholds recolour bars and rows, survive a reload; a bad order is refused. */
export const activeThresholds: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const page = ctx.page;
  const settings = page.getByTestId('active-thresholds');
  await expect(settings).toHaveAttribute('data-custom', 'false');
  const defaults = await readScreen(page);
  for (const instance of feed.instances) {
    expect(
      defaults.bars.find((b) => b.id === instance.instanceId)?.buckets,
      `default bands of ${instance.name}`,
    ).toEqual(expectedBuckets(feed.scenario, instance.name, Date.now(), DEFAULT_LIMITS_MS));
  }
  const fill = async (values: readonly string[]): Promise<void> => {
    for (const [i, value] of values.entries()) {
      await page.getByTestId(`active-threshold-${String(i + 1)}`).fill(value);
    }
    await page.getByTestId('active-thresholds-apply').click();
  };
  await fill(CUSTOM_THRESHOLDS);
  await expect(settings).toHaveAttribute('data-custom', 'true');
  // The oracle is the scenario at the time of the snapshot the screen shows (data-snapshot-at),
  // not the wall clock: this item uses the WebSocket stub, so the screen keeps its REST snapshot,
  // and right after the feed starts that is the first one (the 9 s fixed slot of the first
  // instance still under 10 s), while the scenario at Date.now() has it past 10 s already.
  const expectCustom = async (what: string): Promise<void> => {
    await expect
      .poll(
        async () => {
          const read = await readScreen(page);
          const screen = [...read.bars].sort((a, b) => a.id - b.id).map((b) => b.buckets);
          const snapshotMs = read.snapshotAt === null ? Number.NaN : Date.parse(read.snapshotAt);
          const expected = byId(feed).map((i) =>
            expectedBuckets(feed.scenario, i.name, snapshotMs, CUSTOM_LIMITS_MS),
          );
          return {
            bars:
              JSON.stringify(screen) === JSON.stringify(expected)
                ? 'scenario at the displayed snapshot'
                : { snapshotAt: read.snapshotAt, screen, expected },
            rows: read.rows.every(
              (r) => r.band === bandFor(r.elapsed, CUSTOM_LIMITS_MS) && r.cellBand === r.band,
            ),
          };
        },
        { message: what },
      )
      .toEqual({ bars: 'scenario at the displayed snapshot', rows: true });
    await expect(page.getByTestId('active-legend-3')).toContainText(
      t('active:band.last', 'en', { from: CUSTOM_THRESHOLDS[2] }),
    );
  };
  await expectCustom('bands recounted with 2/5/10 s');
  // The split of the second instance differs from the default one (a visible recolouring).
  const second = feed.instances[1];
  if (second !== undefined) {
    expect(expectedBuckets(feed.scenario, second.name, Date.now(), CUSTOM_LIMITS_MS)).not.toEqual(
      expectedBuckets(feed.scenario, second.name, Date.now(), DEFAULT_LIMITS_MS),
    );
  }
  // Kept across a reload (per browser preference).
  await page.reload();
  await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
  for (const [i, value] of CUSTOM_THRESHOLDS.entries()) {
    await expect(page.getByTestId(`active-threshold-${String(i + 1)}`)).toHaveValue(value);
  }
  await expectCustom('bands after a reload');
  // A bad order is refused and changes nothing.
  await fill(['5', '3', '10']);
  await expect(page.getByTestId('active-thresholds-error')).toHaveAttribute(
    'data-problem',
    'notAscending',
  );
  await expect(page.getByTestId('active-thresholds-error')).toHaveText(
    t('active:thresholds.error.notAscending'),
  );
  await expectCustom('bands unchanged after a refused order');
  // Back to the default: the server's buckets again.
  await page.getByTestId('active-thresholds-reset').click();
  await expect(settings).toHaveAttribute('data-custom', 'false');
  await expect
    .poll(async () =>
      [...(await readScreen(page)).bars].sort((a, b) => a.id - b.id).map((b) => b.buckets),
    )
    .toEqual(
      byId(feed).map((i) => expectedBuckets(feed.scenario, i.name, Date.now(), DEFAULT_LIMITS_MS)),
    );
};

/** A row of the screen whose elapsed is in the top default band and whose kind is `kind`. */
async function slowRow(page: Page, kind: string): Promise<ScreenRow> {
  const read = await readScreen(page);
  const row = read.rows.find((r) => r.elapsed >= DEFAULT_LIMITS_MS[2] && r.kind === kind);
  if (row === undefined) {
    throw new Error(`no running ${kind} transaction of 8 s or more on the screen`);
  }
  return row;
}

async function selectRow(page: Page, row: ScreenRow): Promise<void> {
  await page.getByTestId(`active-cell-elapsed-${row.tx}`).click();
  const actions = page.getByTestId('active-row-actions');
  await expect(actions).toHaveAttribute('data-tx-id', row.tx);
  await expect(page.getByTestId(`active-row-${row.tx}`)).toHaveAttribute('data-selected', 'true');
}

/**
 * 2.6-4 (T-ACPT-02 automated part): a row of 8 s or more offers this thread's stack and the
 * instance's thread dump, disabled with the reason until the remote commands exist (WO-26, WO-17),
 * and the recent completions of the same service in the XLog.
 */
export const activeRowActions: ActiveCheck = async (ctx) => {
  await openFeedScreen(ctx);
  const page = ctx.page;
  const row = await slowRow(page, 'BATCH');
  await selectRow(page, row);
  await expect(page.getByTestId('active-row-actions')).toHaveAttribute('data-running', 'true');
  const reason = t('active:actions.commandsUnavailable');
  for (const id of ['active-action-stack', 'active-action-dump']) {
    const action = page.getByTestId(id);
    await expect(action, `${id} is shown`).toBeVisible();
    await expect(action, `${id} is disabled`).toBeDisabled();
    await expect(action).toHaveAttribute('data-reason', reason);
    await expect(action).toHaveAccessibleDescription(reason);
    const before = page.url();
    await action.click({ force: true });
    expect(page.url(), `${id} does nothing`).toBe(before);
  }
};

/** 2.6-4: "recent completions of the same service" opens the XLog with f.service, 15 min, kind. */
export const activeRecentCompletions: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const page = ctx.page;
  const api = await ctx.apiAs(ACTIVE_ORACLE_ROLE);
  const row = await slowRow(page, 'BATCH');
  const item = (await restActive(api, feed.appId)).items.find(
    (i) => i.service === row.service && i.kind === 'BATCH' && i.state === row.state,
  );
  if (item?.service_id == null) {
    throw new Error(`GET /active has no service key for ${row.service}`);
  }
  const serviceId = item.service_id;
  await selectRow(page, row);
  const link = page.getByTestId('active-action-recent');
  const href = await link.getAttribute('href');
  const url = new URL(href ?? '', 'http://ui.invalid');
  expect(url.pathname).toBe(`/apps/${String(feed.appId)}/xlog`);
  expect(url.searchParams.get('f.service')).toBe(serviceId);
  expect(url.searchParams.get('kind')).toBe('BATCH');
  // The last 15 minutes is the XLog's default range (not written).
  expect(url.searchParams.get('from')).toBeNull();
  const request = page.waitForRequest(
    (r) => {
      const u = new URL(r.url());
      return u.pathname === `/api/v1/apps/${String(feed.appId)}/xlog` && r.method() === 'GET';
    },
    { timeout: 30_000 },
  );
  await link.click();
  await expectUrl(page, (s) => s.path === `/apps/${String(feed.appId)}/xlog`, 'the XLog opened');
  const query = new URL((await request).url()).searchParams;
  expect(query.getAll('service'), 'the XLog asks for the service key').toEqual([serviceId]);
  expect(query.get('kind'), 'the XLog asks for the kind of the transaction').toBe('BATCH');
  const from = Date.parse(query.get('from') ?? '');
  const to = Date.parse(query.get('to') ?? '');
  expect(
    Math.abs(to - from - 15 * 60_000),
    'the XLog asks for the last 15 minutes',
  ).toBeLessThanOrEqual(60_000);
  expect(Math.abs(Date.now() - to)).toBeLessThanOrEqual(2 * 60_000);
  await expect(page.getByTestId('xlog-page')).toBeVisible();
};

/** 2.6-5: the summary = the push and REST; the alert rule link of the active metric. */
export const activeSummaryAndRuleLink: ActiveCheck = async (ctx) => {
  const feed = await openFeedScreen(ctx);
  const page = ctx.page;
  const { read, push } = await matchedSnapshot(ctx, feed.appId);
  expectScreenShows(read, push.data, { what: 'summary' });
  const link = page.getByTestId('active-rule-link');
  await expect(link).toHaveText(t('active:summary.ruleLink'));
  const href = await link.getAttribute('href');
  if (href === null) {
    // A viewer may not edit alert rules: the link is disabled with the reason.
    await expect(link).toHaveAttribute('aria-disabled', 'true');
    await expect(link).toHaveAttribute('title', t('active:summary.ruleLinkNoPermission'));
    return;
  }
  const url = new URL(href, 'http://ui.invalid');
  expect(url.pathname).toBe('/alerts/rules');
  expect(url.searchParams.get('f.metric')).toBe('active_transactions');
  expect(url.searchParams.get('f.app_id')).toBe(String(feed.appId));
  await link.click();
  await expectUrl(page, (s) => s.path === '/alerts/rules', 'the alert rules opened');
  expect(state(page).params.get('f.metric')).toBe('active_transactions');
};

// ---------------------------------------------------------------------------------------------
// WebSocket stub checks (no JVM needed)
// ---------------------------------------------------------------------------------------------

/** A typed snapshot for the stub (instance ids, names and values fixed; times given). */
export function stubSnapshot(options: {
  readonly snapshotAt: string;
  readonly instances?: readonly {
    readonly id: number;
    readonly name: string;
    readonly state: string;
    readonly truncated?: boolean;
    readonly count?: number;
  }[];
  readonly startBase?: number;
}): ActiveApi {
  const instances = options.instances ?? [
    { id: 9101, name: 'stub-node-1', state: 'UP' },
    { id: 9102, name: 'stub-node-2', state: 'UP' },
  ];
  const snapshotMs = Date.parse(options.snapshotAt);
  const elapsed = [12_400, 5_200, 1_800, 350];
  const states = ['SQL', 'HTTP', 'DBC', 'METHOD'] as const;
  const details: Record<string, string | null> = {
    SQL: 'SELECT o.id FROM orders o WHERE o.id = ?',
    HTTP: 'http://pricing.internal:9090',
    DBC: 'jdbc:postgresql://db.internal:5432/shop',
    METHOD: null,
  };
  const items: ActiveItemApi[] = [];
  const perInstance: ActiveInstanceApi[] = instances.map((instance, n) => {
    const own = elapsed.slice(0, 4 - n);
    own.forEach((ms, i) => {
      const s = states[i] ?? 'METHOD';
      items.push({
        instance_id: instance.id,
        tx_id: String(7_000_000_000 + instance.id * 10 + i),
        service:
          ['GET /api/orders/{id}', 'POST /api/orders', 'GET /api/products', 'GET /api/cart'][i] ??
          null,
        service_id: String(500 + i),
        elapsed_ms: ms,
        state: s,
        state_detail: details[s] ?? null,
        thread: 'http-nio-8080-exec-#',
        virtual_thread: false,
        user_id: n === 0 ? 'alice' : null,
        user_id_mode: n === 0 ? 'plain' : 'off',
        remote_ip: n === 0 ? '10.1.2.3' : null,
        ip_mode: n === 0 ? 'full' : 'off',
        kind: 'HTTP',
        started_at: new Date(snapshotMs - ms).toISOString(),
      });
    });
    const buckets = [0, 0, 0, 0];
    for (const ms of own) {
      const band = bandFor(ms, DEFAULT_LIMITS_MS);
      buckets[band] = (buckets[band] ?? 0) + 1;
    }
    return {
      instance_id: instance.id,
      name: instance.name,
      state: instance.state,
      snapshot_at: options.snapshotAt,
      count: instance.count ?? own.length,
      max_elapsed_ms: own[0] ?? null,
      buckets,
      truncated: instance.truncated ?? false,
    };
  });
  items.sort((a, b) => b.elapsed_ms - a.elapsed_ms);
  return { snapshot_at: options.snapshotAt, per_instance: perInstance, items };
}

/** Pushes `data` to the page's active subscription (stub) and waits until the screen shows it. */
export async function pushStub(page: Page, ws: WsStub, data: ActiveApi): Promise<void> {
  await expect
    .poll(() => ws.push('active', data), { message: 'an active subscription took the push' })
    .toBeGreaterThan(0);
  const root = page.getByTestId('active-page');
  await expect(root).toHaveAttribute('data-snapshot-at', data.snapshot_at ?? '');
  await expect(root).toHaveAttribute('data-source', 'ws');
}

/** Opens the screen of an application of the seed with the WebSocket stub. */
async function openStubScreen(ctx: ActiveCheckContext): Promise<{ ws: WsStub; appId: number }> {
  const ws = requireWs(ctx);
  expect(ws.mode, 'this check drives the WebSocket stub').toBe('stub');
  const appId = ctx.seed.primary_app_id;
  await ctx.page.goto(activePath(appId));
  await expectShell(ctx.page);
  await expect(ctx.page.getByTestId('active-page')).toHaveAttribute(
    'data-state',
    /^(ready|empty)$/,
    {
      timeout: 30_000,
    },
  );
  await activeSubscription(ws, appId);
  return { ws, appId };
}

/**
 * 2.6-6: the socket stops answering for more than 10 s -> "updates stopped N s"; the server drops
 * the connection, the client reconnects and subscribes again (and refetches REST); a new snapshot
 * clears the badge.
 */
export const activeDisconnect: ActiveCheck = async (ctx) => {
  const { ws, appId } = await openStubScreen(ctx);
  const page = ctx.page;
  await pushStub(page, ws, stubSnapshot({ snapshotAt: new Date().toISOString() }));
  const badge = page.getByTestId('active-stale-badge');
  await expect(badge).toBeHidden();
  const pauseMs = 13_000;
  const pausedAt = Date.now();
  ws.pause(pauseMs);
  await expect(badge).toBeVisible({ timeout: 15_000 });
  const seconds = Number(await badge.getAttribute('data-seconds'));
  expect(seconds, 'updates stopped for more than 10 s').toBeGreaterThan(10);
  await expect(badge).toHaveText(
    new RegExp(`^${t('active:stale.badge', 'en', { seconds: '0' }).replace('0', '\\d+')}$`),
  );
  await expect(page.getByTestId('active-asof')).toHaveClass(/active-asof--warn/);
  // The stub answers again, then the server goes away (close 1012).
  await page.waitForTimeout(Math.max(0, pausedAt + pauseMs + 200 - Date.now()));
  const connections = ws.connectionCount();
  // onResync refetches REST; its answer must be in before the next push (newer server time wins).
  const refetch = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `/api/v1/apps/${String(appId)}/active`,
    { timeout: 20_000 },
  );
  ws.drop();
  await ws.waitForConnections(connections + 1, 20_000);
  await expect
    .poll(() => ws.subscriptions('active').length, {
      message: 'the new connection subscribes active again',
      timeout: 15_000,
    })
    .toBe(1);
  expect(ws.subscriptions('active')[0]?.params).toEqual({ app: appId, kind: 'ALL' });
  await refetch;
  // The next snapshot clears the badge.
  const next = stubSnapshot({ snapshotAt: new Date().toISOString() });
  await pushStub(page, ws, next);
  await expect(badge).toBeHidden();
  const read = await readScreen(page);
  expectScreenShows(read, next, { kind: 'ALL', what: 'snapshot after the reconnect' });
};

/** Instance states of the session (C-218 (4)) on the bars, the truncated hint and "approx.". */
export const activeInstanceStates: ActiveCheck = async (ctx) => {
  const { ws } = await openStubScreen(ctx);
  const page = ctx.page;
  const data = stubSnapshot({
    snapshotAt: new Date().toISOString(),
    instances: [
      { id: 9101, name: 'stub-up', state: 'UP' },
      { id: 9102, name: 'stub-warn', state: 'WARN' },
      { id: 9103, name: 'stub-stale', state: 'STALE', truncated: true, count: 640 },
    ],
  });
  await pushStub(page, ws, data);
  for (const instance of data.per_instance) {
    const bar = page.getByTestId(`active-bar-${String(instance.instance_id)}`);
    await expect(bar).toHaveAttribute('data-state', instance.state);
    await expect(bar).toHaveAttribute('data-count', String(instance.count));
    const status = page.getByTestId(`active-bar-status-${String(instance.instance_id)}`);
    if (instance.state === 'UP') {
      await expect(status).toHaveCount(0);
    } else {
      await expect(status).toBeVisible();
    }
    const truncated = page.getByTestId(`active-bar-truncated-${String(instance.instance_id)}`);
    if (instance.truncated) {
      await expect(truncated).toHaveText(t('active:bars.truncated'));
      await expect(bar).toHaveAttribute('data-approx', 'true');
      await expect(
        page.getByTestId(`active-bar-approx-${String(instance.instance_id)}`),
      ).toBeVisible();
      // The 638 transactions the agent did not send count in the first band.
      const sent = instance.buckets.reduce((a, b) => a + b, 0);
      await expect(bar).toHaveAttribute(
        'data-buckets',
        [instance.count - (sent - (instance.buckets[0] ?? 0)), ...instance.buckets.slice(1)].join(
          ',',
        ),
      );
    } else {
      await expect(truncated).toHaveCount(0);
    }
  }
  await expect(page.getByTestId('active-summary-total')).toHaveAttribute(
    'data-value',
    String(data.per_instance.reduce((s, p) => s + p.count, 0)),
  );
  await expect(page.getByTestId('active-summary-max')).toHaveAttribute('data-instance', '9103');
  await expect(page.getByTestId('active-summary-slow')).toHaveAttribute('data-approx', 'true');
};

export const ACTIVE_CHECKS: Readonly<Record<string, ActiveCheck>> = {
  activeMatchesPushAndRest,
  activeBars,
  activeColumns,
  activeRefresh,
  activePause,
  activeBarFilter,
  activeFilters,
  activeThresholds,
  activeRowActions,
  activeRecentCompletions,
  activeSummaryAndRuleLink,
  activeDisconnect,
  activeInstanceStates,
};
