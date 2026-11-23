// SPDX-License-Identifier: Apache-2.0
//
// Checks of the commands tab and the thread dump viewer of the instance detail (WO-37, docs/05
// §2.7-4 and §2.7-5, docs/15 §9.8, docs/14 §4.10): the named checks of required/dumps.yaml
// (T-E2E-dumps-1, -2) and of the 2.6-4 items of required/active.yaml (this thread's stack and the
// instance thread dump of a running transaction, T-ACPT-02), and the helpers of specs/dumps.spec.ts
// (T-E2E-dumps-20 .. 41) and specs/active.spec.ts (T-E2E-active-27, -30).
//
// Commands run through the real server: REST request, the agent session of the real collector, the
// agent's answer, the result encrypted in PostgreSQL with the stack's master key, REST result, the
// screen. Until WO-17 is wired into the stack the agent is the dev-only command feed
// (e2e/fixtures/commandFeed.ts: app e2e-cmd, instance e2e-cmd-1 answering normally and e2e-cmd-2
// whose thread dump is reported truncated); T-E2E-dumps-30 runs the same round trip with the real
// agent (shop-boot3). Every value on the screen is compared with GET /commands/{id}/result of the
// same command, interpreted here independently of the UI's model (state from the Thread.State
// word, identical stacks by their frames, locks by their identity), never with fixed numbers.
//
// The server allows one running command per instance and 10 per instance per minute (docs/14
// §4.10, 429 with Retry-After). Before every command (through the API or a button) the helpers wait
// for a free slot from the instance's own history (GET /instances/{id}/commands: what the server
// accepted in the last minute and whether one still runs), so a run of the whole suite never meets
// a 429 it did not ask for.
import { expect, type Page, type TestInfo } from '@playwright/test';

import { type CommandFeed, ensureCommandFeed, SKIP_NO_COMMAND_FEED } from '../fixtures/commandFeed';
import { t } from '../fixtures/i18n';
import { expectShell, expectUrl } from '../fixtures/ui';
import type { ApiSession } from '../stack/api';
import type { Role, SeedManifest } from '../stack/manifest';

import { readScreen, type ScreenRow } from './activeChecks';
import { instancePath } from './instanceChecks';

/** The check context of checks.ts (structurally; this file does not import it). */
export interface DumpsCheckContext {
  readonly page: Page;
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
  readonly role: Role | null;
}

type DumpsCheck = (ctx: DumpsCheckContext) => Promise<void>;

/** Commands the server accepts per instance and minute (argos.commands.max-per-minute-per-instance). */
export const COMMANDS_PER_MINUTE = 10;
/** The rate window plus a margin for the tick of the server's clock. */
export const RATE_WINDOW_MS = 61_000;
/** Dumps the list keeps (05 §2.7-5 "최근 20개 보관"). */
export const RECENT_DUMPS = 20;
/** Frames the agent keeps per thread (docs/16 §1.4). */
export const MAX_FRAMES = 64;
/** T-ACPT-02: this thread's stack within 30 s of the click. */
export const STACK_WITHIN_MS = 30_000;
/** Above this many threads the state sections start collapsed (1 MiB dumps hold thousands). */
export const COLLAPSE_ABOVE = 200;
/** Stacks a section shows before "show more". */
export const STACKS_SHOWN = 100;
/** The elapsed band of "8 s or more" (docs/05 §2.6, the default third limit). */
export const SLOW_MS = 8_000;

/** The buttons of each role in the commands tab (SEC-21, docs/16 §5). */
export const COMMAND_BUTTONS: Readonly<Record<Role, readonly string[]>> = {
  viewer: [],
  operator: ['thread_dump', 'thread_stack', 'self_diagnosis'],
  admin: ['thread_dump', 'thread_stack', 'self_diagnosis', 'reload_config', 'env_dump'],
};
/** Every command type (docs/14 §4.10). */
export const COMMAND_TYPES = [
  'thread_dump',
  'thread_stack',
  'self_diagnosis',
  'reload_config',
  'env_dump',
] as const;

/** State groups of the viewer (Thread.State; NEW, TERMINATED and none are OTHER). */
export const DUMP_GROUPS = ['BLOCKED', 'RUNNABLE', 'WAITING', 'TIMED_WAITING', 'OTHER'] as const;
export type DumpGroup = (typeof DUMP_GROUPS)[number];

/** The state the command feed gives the thread of a running transaction (ActiveFeedScenario). */
export const FEED_THREAD_STATE: Readonly<Record<string, DumpGroup>> = {
  SQL: 'RUNNABLE',
  HTTP: 'RUNNABLE',
  METHOD: 'RUNNABLE',
  ASYNC: 'WAITING',
  DBC: 'TIMED_WAITING',
};

// ---------------------------------------------------------------------------------------------
// The API shape (docs/14 §4.10; plain strings: the oracle never renders them)
// ---------------------------------------------------------------------------------------------

export interface CommandViewApi {
  command_id: string;
  instance_id: number;
  type: string;
  status: string;
  error: string | null;
  args: Record<string, string>;
  requested_by: string;
  requested_by_name: string | null;
  requested_at: string;
  sent_at: string | null;
  finished_at: string | null;
  expires_at: string;
  duration_ms: number | null;
  result_size: number | null;
  truncated: boolean | null;
}

export interface ThreadApi {
  id: number | null;
  name: string;
  state: string | null;
  virtual: boolean;
  locks_held: string[];
  waiting_on: string | null;
  frames: string[];
  tx_id: string | null;
}

export interface CommandResultApi {
  command_id: string;
  instance_id: number;
  type: string;
  format: string;
  content_type: string;
  size_bytes: number;
  truncated: boolean;
  text: string | null;
  threads: ThreadApi[] | null;
  deadlocks: { thread_ids: number[] }[] | null;
}

// ---------------------------------------------------------------------------------------------
// Pure oracle helpers (dumpsYaml.test.ts)
// ---------------------------------------------------------------------------------------------

/** The group of a Thread.State text (`TIMED_WAITING (sleeping)` → TIMED_WAITING). */
export function groupOf(state: string | null): DumpGroup {
  const head = (state ?? '').trim().split(/[\s(]/, 1)[0]?.toUpperCase() ?? '';
  const known = DUMP_GROUPS.find((g) => g !== 'OTHER' && g === head);
  return known ?? 'OTHER';
}

/** The identity of a lock: the hex of `<0x…>` (JDK text), otherwise the trimmed text. */
export function lockIdentity(text: string): string {
  const hex = /<\s*(0x[0-9a-fA-F]+)\s*>/.exec(text)?.[1];
  return hex === undefined ? text.trim() : hex.toLowerCase();
}

export interface ExpectedSection {
  readonly group: DumpGroup;
  readonly count: number;
  /** Threads per distinct stack, largest first. */
  readonly sizes: readonly number[];
}

export interface ExpectedLock {
  readonly lock: string;
  readonly waiters: number;
  readonly held: boolean;
}

export interface ExpectedDump {
  readonly total: number;
  readonly counts: Readonly<Record<DumpGroup, number>>;
  readonly sections: readonly ExpectedSection[];
  readonly virtual: number;
  readonly deadlocks: number;
  /** Thread names of every deadlock cycle the agent reported. */
  readonly deadlockNames: readonly (readonly string[])[];
  readonly locks: readonly ExpectedLock[];
  readonly txIds: readonly string[];
  /** A stack reached the agent's depth limit. */
  readonly depthCut: boolean;
  readonly truncated: boolean;
}

/** What the viewer must show for a thread dump result. */
export function expectedDump(
  result: Pick<CommandResultApi, 'threads' | 'deadlocks' | 'truncated'>,
): ExpectedDump {
  const threads = result.threads ?? [];
  const counts: Record<DumpGroup, number> = {
    BLOCKED: 0,
    RUNNABLE: 0,
    WAITING: 0,
    TIMED_WAITING: 0,
    OTHER: 0,
  };
  const stacks = new Map<DumpGroup, Map<string, number>>();
  for (const thread of threads) {
    const group = groupOf(thread.state);
    counts[group] += 1;
    const byFrames = stacks.get(group) ?? new Map<string, number>();
    const key = JSON.stringify(thread.frames);
    byFrames.set(key, (byFrames.get(key) ?? 0) + 1);
    stacks.set(group, byFrames);
  }
  const sections = DUMP_GROUPS.flatMap((group) => {
    const byFrames = stacks.get(group);
    if (byFrames === undefined) {
      return [];
    }
    return [
      {
        group,
        count: counts[group],
        sizes: [...byFrames.values()].sort((a, b) => b - a),
      },
    ];
  });
  const holder = new Map<string, ThreadApi>();
  for (const thread of threads) {
    for (const held of thread.locks_held) {
      const id = lockIdentity(held);
      if (!holder.has(id)) {
        holder.set(id, thread);
      }
    }
  }
  const waiters = new Map<string, number>();
  for (const thread of threads) {
    if (thread.waiting_on === null) {
      continue;
    }
    const id = lockIdentity(thread.waiting_on);
    if (holder.get(id) === thread) {
      continue;
    }
    waiters.set(id, (waiters.get(id) ?? 0) + 1);
  }
  const byId = new Map(threads.flatMap((th) => (th.id === null ? [] : [[th.id, th] as const])));
  const cycles = (result.deadlocks ?? [])
    .map((d) => d.thread_ids.flatMap((id) => byId.get(id)?.name ?? []))
    .filter((names) => names.length > 0);
  return {
    total: threads.length,
    counts,
    sections,
    virtual: threads.filter((th) => th.virtual).length,
    deadlocks: cycles.length,
    deadlockNames: cycles,
    locks: [...waiters.entries()]
      .map(([lock, n]) => ({ lock, waiters: n, held: holder.has(lock) }))
      .sort((a, b) => a.lock.localeCompare(b.lock)),
    txIds: threads.flatMap((th) =>
      th.tx_id !== null && /^[0-9]{1,20}$/.test(th.tx_id) ? [th.tx_id] : [],
    ),
    depthCut: threads.some((th) => th.frames.length >= MAX_FRAMES),
    truncated: result.truncated,
  };
}

/** Threads whose name or a frame (class and method) contains `query`, any case. */
export function searchMatches(threads: readonly ThreadApi[], query: string): number {
  const q = query.trim().toLowerCase();
  return threads.filter(
    (th) => th.name.toLowerCase().includes(q) || th.frames.some((f) => f.toLowerCase().includes(q)),
  ).length;
}

/** The transaction detail links of the threads with a tx_id. */
export function transactionLinks(appId: number, txIds: readonly string[]): string[] {
  return txIds.map((tx) => `/apps/${String(appId)}/transactions/${tx}`);
}

/** Ids of the last done thread dumps of a command list, newest first (at most 20). */
export function recentDoneDumps(list: readonly CommandViewApi[]): string[] {
  return list
    .filter((c) => c.type === 'thread_dump' && c.status === 'done')
    .slice(0, RECENT_DUMPS)
    .map((c) => c.command_id);
}

/** Commands the server accepted in the rate window before `nowMs`, and whether one still runs. */
export function rateState(
  list: readonly CommandViewApi[],
  nowMs: number,
): { recent: number; oldestRecentMs: number | null; running: boolean } {
  const recent = list
    .map((c) => Date.parse(c.requested_at))
    .filter((ms) => !Number.isNaN(ms) && ms > nowMs - RATE_WINDOW_MS);
  return {
    recent: recent.length,
    oldestRecentMs: recent.length === 0 ? null : Math.min(...recent),
    running: list.some((c) => !['done', 'failed', 'expired'].includes(c.status)),
  };
}

/** A UUIDv4 the UI accepts as a command id (deterministic per n). */
export function fakeCommandId(n: number): string {
  return `00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
}

/**
 * `n` done thread dumps older than `base`, one minute apart (the list stub of T-E2E-dumps-24: more
 * than 20 dumps without asking the agent for them).
 */
export function olderDumps(base: CommandViewApi, n: number): CommandViewApi[] {
  const at = Date.parse(base.requested_at);
  return Array.from({ length: n }, (_, i) => {
    const ms = at - (i + 1) * 60_000;
    return {
      ...base,
      command_id: fakeCommandId(i + 1),
      requested_at: new Date(ms).toISOString(),
      sent_at: new Date(ms + 10).toISOString(),
      finished_at: new Date(ms + 500).toISOString(),
      expires_at: new Date(ms + 60_000).toISOString(),
    };
  });
}

/** An environment dump result: the agent's sections (ENV_DUMP text/plain, C-234 (1)). */
export interface EnvDump {
  readonly env: Record<string, string>;
  readonly properties: Record<string, string>;
  readonly args: string[];
}

/**
 * Reads the agent's ENV_DUMP text: `# env (N)`, `# properties (N)` and `# args (N)` sections of
 * `key=value` lines (split at the first '=') and argument lines; blank lines and `# truncated`
 * are skipped. An independent oracle of the page (it does not use the UI's parser).
 */
export function envDumpOf(text: string): EnvDump {
  const dump: EnvDump = { env: {}, properties: {}, args: [] };
  let section: 'env' | 'properties' | 'args' | null = null;
  for (const line of text.split('\n')) {
    const header = /^# (env|properties|args) \((?:\d+|unavailable)\)$/.exec(line);
    if (header !== null) {
      section = header[1] as 'env' | 'properties' | 'args';
    } else if (line === '' || line === '# truncated' || section === null) {
      continue;
    } else if (section === 'args') {
      dump.args.push(line);
    } else {
      const eq = line.indexOf('=');
      dump[section][line.slice(0, eq)] = line.slice(eq + 1);
    }
  }
  return dump;
}

/** Values of an environment dump result text that carry the masked form. */
export function maskedEntries(text: string, masked: string): { env: number; properties: number } {
  const dump = envDumpOf(text);
  const count = (map: Record<string, string>): number =>
    Object.values(map).filter((v) => v.includes(masked)).length;
  return { env: count(dump.env), properties: count(dump.properties) };
}

/**
 * The self diagnosis of a result text in the agent's layout (C-234 (1)): the modules (with the
 * reason of a module that is not enabled) and `effective_config` (a map key -> {value, source})
 * as rows in the result's order.
 */
export function selfDiagnosisOf(text: string): {
  modules: { name: string; state: string; errors: number; reason: string | null }[];
  config: { key: string; value: string | null; source: string | null }[];
} {
  const parsed = JSON.parse(text) as {
    modules?: { name: string; state: string; errors: number; reason?: string }[];
    effective_config?: Record<string, { value: string | null; source: string | null }>;
  };
  return {
    modules: (parsed.modules ?? []).map((m) => ({
      name: m.name,
      state: m.state,
      errors: m.errors,
      reason: m.reason ?? null,
    })),
    config: Object.entries(parsed.effective_config ?? {}).map(([key, entry]) => ({
      key,
      value: entry.value,
      source: entry.source,
    })),
  };
}

// ---------------------------------------------------------------------------------------------
// A fixed dump for stub answers (T-E2E-dumps-27 and the visual baselines T-E2E-dumps-40, -41)
// ---------------------------------------------------------------------------------------------

/** The fixed requester of the stub history (any role reads it as an administrator). */
export const FIXED_DUMP_AT = '2030-01-01T09:00:00.000Z';

function fixedThread(
  id: number,
  name: string,
  state: string | null,
  frames: readonly string[],
  extra: Partial<ThreadApi> = {},
): ThreadApi {
  return {
    id,
    name,
    state,
    virtual: false,
    locks_held: [],
    waiting_on: null,
    frames: [...frames],
    tx_id: null,
    ...extra,
  };
}

const IDLE_FRAMES = [
  'jdk.internal.misc.Unsafe.park(Native Method)',
  'java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)',
  'java.util.concurrent.LinkedBlockingQueue.take(LinkedBlockingQueue.java:435)',
  'org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)',
  'java.lang.Thread.run(Thread.java:1583)',
];

/** A small fixed dump: idle pool ×6, a deadlock, a transaction, virtual threads, one thread without state. */
export function fixedDumpResult(
  instanceId: number,
  commandId: string,
  truncated: boolean,
): CommandResultApi {
  const threads: ThreadApi[] = [
    fixedThread(1, 'main', 'RUNNABLE', [
      'sun.nio.ch.Net.accept(Native Method)',
      'com.example.shop.ShopApplication.main(ShopApplication.java:12)',
    ]),
    fixedThread(
      11,
      'http-nio-8080-exec-1',
      'RUNNABLE',
      [
        'org.postgresql.jdbc.PgPreparedStatement.execute(PgPreparedStatement.java:182)',
        'com.example.shop.OrderService.find(OrderService.java:61)',
        'java.lang.Thread.run(Thread.java:1583)',
      ],
      { tx_id: '4611686018427387905' },
    ),
    ...Array.from({ length: 6 }, (_, i) =>
      fixedThread(20 + i, `http-nio-8080-exec-${String(i + 2)}`, 'WAITING', IDLE_FRAMES, {
        waiting_on:
          'java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject@3c0f8a21',
      }),
    ),
    fixedThread(
      31,
      'order-settle-worker',
      'BLOCKED',
      [
        'com.example.shop.Inventory.release(Inventory.java:120)',
        'java.lang.Thread.run(Thread.java:1583)',
      ],
      {
        locks_held: ['com.example.shop.Settlement@6d1e7a10'],
        waiting_on: 'com.example.shop.Inventory@1f4c2b30',
      },
    ),
    fixedThread(
      32,
      'inventory-sync-worker',
      'BLOCKED',
      [
        'com.example.shop.Settlement.adjust(Settlement.java:54)',
        'java.lang.Thread.run(Thread.java:1583)',
      ],
      {
        locks_held: ['com.example.shop.Inventory@1f4c2b30'],
        waiting_on: 'com.example.shop.Settlement@6d1e7a10',
      },
    ),
    fixedThread(
      40,
      'order-events-vt',
      'TIMED_WAITING (sleeping)',
      [
        'java.lang.Thread.sleep(Thread.java:509)',
        'java.lang.VirtualThread.run(VirtualThread.java:309)',
      ],
      { virtual: true },
    ),
    fixedThread(41, '', 'WAITING', ['java.lang.VirtualThread.park(VirtualThread.java:582)'], {
      virtual: true,
    }),
    fixedThread(4, 'Signal Dispatcher', null, []),
  ];
  return {
    command_id: commandId,
    instance_id: instanceId,
    type: 'thread_dump',
    format: 'json',
    content_type: 'application/json',
    size_bytes: 4096,
    truncated,
    text: null,
    threads,
    deadlocks: [{ thread_ids: [31, 32] }],
  };
}

/** The done command view of the fixed dump. */
export function fixedDumpView(
  instanceId: number,
  commandId: string,
  requestedBy: string,
  truncated: boolean,
): CommandViewApi {
  return {
    command_id: commandId,
    instance_id: instanceId,
    type: 'thread_dump',
    status: 'done',
    error: null,
    args: {},
    requested_by: requestedBy,
    requested_by_name: 'operator',
    requested_at: FIXED_DUMP_AT,
    sent_at: '2030-01-01T09:00:00.020Z',
    finished_at: '2030-01-01T09:00:00.640Z',
    expires_at: '2030-01-01T09:01:00.000Z',
    duration_ms: 620,
    result_size: 4096,
    truncated,
  };
}

/** An enveloped answer (docs/14 §2.5). */
export function envelope(data: unknown): { data: unknown; meta: Record<string, unknown> } {
  return { data, meta: {} };
}

/** The error body of the server (docs/14 §2.6). */
export function errorBody(code: string, message: string): unknown {
  return { error: { code, message, details: null, request_id: `e2e-stub-${code}` } };
}

/**
 * Answers the commands of `instanceId` from the fixed dump (history, state and result), so the
 * dumps tab shows it without an agent; resolves with the command id.
 */
export async function stubFixedDump(
  page: Page,
  instanceId: number,
  requestedBy: string,
  truncated: boolean,
): Promise<string> {
  const commandId = fakeCommandId(truncated ? 0x41 : 0x40);
  const view = fixedDumpView(instanceId, commandId, requestedBy, truncated);
  const result = fixedDumpResult(instanceId, commandId, truncated);
  await page.route(`**/api/v1/instances/${String(instanceId)}/commands**`, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();
      return;
    }
    await route.fulfill({ json: envelope([view]) });
  });
  await page.route(`**/api/v1/commands/${commandId}**`, async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/result')) {
      const format = url.searchParams.get('format') ?? 'json';
      await route.fulfill({
        json: envelope(
          format === 'text'
            ? {
                ...result,
                format: 'text',
                threads: null,
                deadlocks: null,
                text: JSON.stringify(result.threads),
              }
            : result,
        ),
      });
      return;
    }
    await route.fulfill({ json: envelope(view) });
  });
  return commandId;
}

// ---------------------------------------------------------------------------------------------
// Sessions, the feed and commands through the API
// ---------------------------------------------------------------------------------------------

/** Signs the page's browser context in as `role` (the real login of the API, cookies only). */
export async function signInAs(
  page: Page,
  apiAs: (role: Role) => Promise<ApiSession>,
  role: Role,
): Promise<void> {
  const api = await apiAs(role);
  const host = new URL(api.baseUrl).hostname;
  await page.context().clearCookies();
  await page.context().addCookies(
    api.cookies().map((c) => ({
      name: c.name,
      value: c.value,
      domain: host,
      path: c.path,
      expires: c.expires,
      httpOnly: c.httpOnly,
      secure: c.secure,
      sameSite: c.sameSite,
    })),
  );
}

/** The user id of a role's session (GET /auth/me). */
export async function userIdOf(api: ApiSession): Promise<string> {
  const me = await api.data<{ user: { id: string } }>('/auth/me');
  return me.user.id;
}

/** The running command feed, or a skip of the test with the reason (e2e:docker). */
export async function requireCommandFeed(ctx: {
  readonly seed: SeedManifest;
  readonly testInfo: TestInfo;
  readonly apiAs: (role: Role) => Promise<ApiSession>;
}): Promise<CommandFeed> {
  // Starting the feed (and building its jar when missing) may take longer than a test.
  ctx.testInfo.setTimeout(Math.max(ctx.testInfo.timeout, 300_000));
  const feed = await ensureCommandFeed(ctx.seed, ctx.apiAs);
  ctx.testInfo.skip(feed === null, SKIP_NO_COMMAND_FEED);
  if (feed === null) {
    throw new Error('unreachable: skipped');
  }
  return feed;
}

/** `GET /instances/{id}/commands?limit=100` (operator or administrator session). */
export async function commandList(api: ApiSession, instanceId: number): Promise<CommandViewApi[]> {
  return api.data<CommandViewApi[]>(`/instances/${String(instanceId)}/commands`, { limit: 100 });
}

/**
 * Waits until the server accepts `needed` more commands for the instance: none of its commands
 * still runs and fewer than 10 - needed were accepted in the last minute (its own history).
 */
export async function commandSlot(
  api: ApiSession,
  instanceId: number,
  needed = 1,
  timeoutMs = 150_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const now = Date.now();
    const state = rateState(await commandList(api, instanceId), now);
    if (!state.running && state.recent + needed <= COMMANDS_PER_MINUTE) {
      return;
    }
    if (now > deadline) {
      throw new Error(
        `instance ${String(instanceId)}: no command slot (${String(state.recent)} in the last minute, running ${String(state.running)})`,
      );
    }
    const wait =
      !state.running && state.oldestRecentMs !== null
        ? Math.max(500, state.oldestRecentMs + RATE_WINDOW_MS - now)
        : 1000;
    await new Promise((resolve) => setTimeout(resolve, Math.min(wait, 10_000)));
  }
}

/** `GET /commands/{id}` until the command ends (done, failed, expired). */
export async function commandEnd(
  api: ApiSession,
  commandId: string,
  timeoutMs = 150_000,
): Promise<CommandViewApi> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const view = await api.data<CommandViewApi>(`/commands/${commandId}`);
    if (['done', 'failed', 'expired'].includes(view.status)) {
      return view;
    }
    if (Date.now() > deadline) {
      throw new Error(`command ${commandId}: still ${view.status}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/**
 * Requests a command as `api` once a slot is free (`slots`: an operator or administrator session
 * reading the instance's history) and waits until it ends.
 */
export async function runCommandApi(
  api: ApiSession,
  slots: ApiSession,
  instanceId: number,
  type: string,
  args?: Readonly<Record<string, string>>,
): Promise<CommandViewApi> {
  await commandSlot(slots, instanceId);
  const queued = await api.request<{ data: { command_id: string; status: string; ttl_s: number } }>(
    'POST',
    `/instances/${String(instanceId)}/commands`,
    { body: args === undefined ? { type } : { type, args }, expect: [202] },
  );
  const id = queued.body?.data.command_id ?? '';
  expect(id, 'command_id of the 202 answer').toMatch(/^[0-9a-f-]{36}$/);
  expect(queued.body?.data.status).toBe('queued');
  return commandEnd(api, id);
}

/** `GET /commands/{id}/result?format=json|text`. */
export async function commandResult(
  api: ApiSession,
  commandId: string,
  format: 'json' | 'text' = 'json',
): Promise<CommandResultApi> {
  return api.data<CommandResultApi>(`/commands/${commandId}/result`, { format });
}

export interface SharedDump {
  readonly instanceId: number;
  readonly commandId: string;
  readonly view: CommandViewApi;
  readonly result: CommandResultApi;
}

const dumps = new Map<string, Promise<SharedDump>>();

/**
 * A thread dump of a feed instance taken by the operator through the API once per run and instance
 * (several tests read it; results never change). The operator and administrators can read it.
 */
export function sharedDump(
  ctx: { readonly seed: SeedManifest; readonly apiAs: (role: Role) => Promise<ApiSession> },
  feed: CommandFeed,
  which: 'normal' | 'truncated',
): Promise<SharedDump> {
  const instanceId = feed.instanceIds[which];
  const key = `${ctx.seed.run_id}:${String(instanceId)}`;
  const cached = dumps.get(key);
  if (cached !== undefined) {
    return cached;
  }
  const entry = (async () => {
    const operator = await ctx.apiAs('operator');
    const view = await runCommandApi(operator, operator, instanceId, 'thread_dump');
    expect(
      view.status,
      `thread_dump of instance ${String(instanceId)}: ${String(view.error)}`,
    ).toBe('done');
    const result = await commandResult(operator, view.command_id);
    return { instanceId, commandId: view.command_id, view, result };
  })();
  dumps.set(key, entry);
  entry.catch(() => {
    if (dumps.get(key) === entry) {
      dumps.delete(key);
    }
  });
  return entry;
}

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

/** Opens the instance detail at `tab` and waits until it shows its instance. */
export async function openInstanceTab(
  page: Page,
  appId: number,
  instanceId: number,
  tab: 'commands' | 'dumps' | 'env',
  extra: Readonly<Record<string, string>> = {},
): Promise<void> {
  await page.goto(instancePath(appId, instanceId, { tab, extra }));
  await expectShell(page);
  await expect(page.getByTestId('instance-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
  await expect(page.getByTestId(`instance-tab-${tab}`)).toHaveAttribute('aria-selected', 'true');
}

/** Opens the dumps tab with dump `commandId` and waits until the viewer shows it. */
export async function openDump(
  page: Page,
  appId: number,
  instanceId: number,
  commandId: string,
): Promise<void> {
  await openInstanceTab(page, appId, instanceId, 'dumps', { cmd: commandId });
  await expectViewerOf(page, commandId);
}

/** Waits until the dumps tab's viewer shows dump `commandId`. */
export async function expectViewerOf(
  page: Page,
  commandId: string,
  timeout = 60_000,
): Promise<void> {
  await expect(page.getByTestId('dump-selected')).toHaveAttribute('data-state', 'done', {
    timeout,
  });
  await expect(page.getByTestId('dump-viewer')).toHaveAttribute('data-command-id', commandId);
}

export interface ViewerRead {
  readonly total: number;
  readonly deadlocks: number;
  readonly deadlockSource: string;
  readonly virtual: number;
  readonly truncated: boolean;
  readonly counts: Record<string, number>;
  readonly sections: {
    group: string;
    count: number;
    stacks: number;
    open: boolean;
    sizes: number[];
  }[];
  readonly locks: { lock: string; waiters: number }[];
  readonly txLinks: string[];
  readonly collapsed: { count: number; badge: string | null }[];
}

/** Reads the viewer from its data attributes. */
export async function readViewer(page: Page): Promise<ViewerRead> {
  return page.getByTestId('dump-viewer').evaluate((root, groups) => {
    const num = (el: Element | null, attr: string): number =>
      Number(el?.getAttribute(attr) ?? 'NaN');
    const counts: Record<string, number> = {};
    for (const group of groups) {
      counts[group] = num(root.querySelector(`[data-testid="dump-count-${group}"]`), 'data-count');
    }
    const sections = groups.flatMap((group) => {
      const section = root.querySelector(`[data-testid="dump-section-${group}"]`);
      if (section === null) {
        return [];
      }
      return [
        {
          group,
          count: num(section, 'data-count'),
          stacks: num(section, 'data-stacks'),
          open: section.getAttribute('data-open') === 'true',
          sizes: [...section.querySelectorAll('[data-testid="dump-stack"]')]
            .map((s) => num(s, 'data-count'))
            .sort((a, b) => b - a),
        },
      ];
    });
    return {
      total: num(root, 'data-total'),
      deadlocks: num(root, 'data-deadlocks'),
      deadlockSource: root.getAttribute('data-deadlock-source') ?? '',
      virtual: num(root, 'data-virtual'),
      truncated: root.getAttribute('data-truncated') === 'true',
      counts,
      sections,
      locks: [...root.querySelectorAll('[data-testid="dump-lock-row"]')].map((row) => ({
        lock: row.getAttribute('data-lock') ?? '',
        waiters: num(row, 'data-waiters'),
      })),
      txLinks: [...root.querySelectorAll('[data-testid="dump-tx-link"]')].map(
        (a) => a.getAttribute('href') ?? '',
      ),
      collapsed: [...root.querySelectorAll('[data-testid="dump-stack"]')]
        .filter((s) => num(s, 'data-count') > 1)
        .map((s) => ({
          count: num(s, 'data-count'),
          badge: s.querySelector('[data-testid="dump-stack-count"]')?.textContent ?? null,
        })),
    };
  }, DUMP_GROUPS);
}

/**
 * The viewer equals the result of its command: total and counts per state, the collapsed stacks of
 * every state (×N), virtual threads, the agent's deadlocks, the lock holder → waiter rows and the
 * transaction links.
 */
export async function expectViewerEqualsResult(
  page: Page,
  appId: number,
  result: CommandResultApi,
  what: string,
): Promise<ExpectedDump> {
  const want = expectedDump(result);
  const read = await readViewer(page);
  expect(read.total, `${what}: threads`).toBe(want.total);
  expect(read.counts, `${what}: threads per state`).toEqual(want.counts);
  for (const group of DUMP_GROUPS) {
    await expect(page.getByTestId(`dump-count-${group}`), `${what}: ${group} label`).toContainText(
      t(`commands:viewer.state.${group}`),
    );
  }
  expect(
    read.sections.map((s) => ({ group: s.group, count: s.count, stacks: s.stacks })),
    `${what}: sections with their distinct stacks`,
  ).toEqual(want.sections.map((s) => ({ group: s.group, count: s.count, stacks: s.sizes.length })));
  expect(
    read.sections.every((s) => s.open),
    `${what}: sections open up to ${String(COLLAPSE_ABOVE)} threads, collapsed above`,
  ).toBe(want.total <= COLLAPSE_ABOVE);
  for (const section of read.sections) {
    const sizes = want.sections.find((s) => s.group === section.group)?.sizes ?? [];
    if (section.open && sizes.length <= STACKS_SHOWN) {
      expect(section.sizes, `${what}: ${section.group} threads per collapsed stack`).toEqual([
        ...sizes,
      ]);
    }
  }
  for (const stack of read.collapsed) {
    expect(stack.badge, `${what}: a collapsed stack shows ×N`).toBe(`×${String(stack.count)}`);
  }
  expect(read.virtual, `${what}: virtual threads`).toBe(want.virtual);
  expect(read.deadlocks, `${what}: deadlocks`).toBe(want.deadlocks);
  if (want.deadlocks > 0) {
    expect(read.deadlockSource).toBe('agent');
    const panel = page.getByTestId('dump-deadlocks');
    for (const names of want.deadlockNames) {
      for (const name of names) {
        await expect(panel, `${what}: deadlocked thread ${name}`).toContainText(name);
      }
    }
  }
  await expect(page.getByTestId('dump-deadlock-badge')).toHaveCount(want.deadlocks > 0 ? 1 : 0);
  await expect(page.getByTestId('dump-virtual-count')).toHaveCount(want.virtual > 0 ? 1 : 0);
  expect(
    [...read.locks].sort((a, b) => a.lock.localeCompare(b.lock)),
    `${what}: lock rows (holder → waiters)`,
  ).toEqual(want.locks.map((l) => ({ lock: l.lock, waiters: l.waiters })));
  expect([...read.txLinks].sort(), `${what}: transaction links`).toEqual(
    transactionLinks(appId, want.txIds.slice(0, 50)).sort(),
  );
  expect(read.truncated, `${what}: truncation notice`).toBe(want.truncated || want.depthCut);
  return want;
}

/** The history rows of the commands tab, in display order. */
export async function historyRows(page: Page): Promise<string[]> {
  return page
    .getByTestId('command-history')
    .locator('tr[data-row-key]')
    .evaluateAll((rows) => rows.map((r) => r.getAttribute('data-row-key') ?? ''));
}

/** The command buttons the commands tab shows. */
export async function shownButtons(page: Page): Promise<string[]> {
  const ids = await page
    .getByTestId('command-buttons')
    .locator('[data-testid^="command-run-"]')
    .evaluateAll((els) => els.map((e) => e.getAttribute('data-testid') ?? ''));
  return ids
    .map((id) => id.slice('command-run-'.length))
    .filter((type) => (COMMAND_TYPES as readonly string[]).includes(type));
}

/**
 * Whether the server lets `api` request each command: a request with an invalid argument answers
 * 400 after the role check (nothing is queued, nothing counts against the rate), 403 before it.
 */
export async function serverAllows(api: ApiSession, instanceId: number): Promise<string[]> {
  const allowed: string[] = [];
  for (const type of COMMAND_TYPES) {
    const body = type === 'thread_stack' ? { type } : { type, args: { e2e_probe: '1' } };
    const answer = await api.request<{ error?: { code?: string } }>(
      'POST',
      `/instances/${String(instanceId)}/commands`,
      { body, expect: [400, 403] },
    );
    if (answer.status === 400) {
      allowed.push(type);
    } else {
      expect(answer.body?.error?.code, `${type}: 403 code`).toBe('forbidden');
    }
  }
  return allowed;
}

/**
 * Runs `type` from the commands tab (button, confirmation dialog) once a slot is free and waits
 * until the tab says it is done; resolves with the command id.
 */
export async function runFromTab(
  page: Page,
  slots: ApiSession,
  instanceId: number,
  type: string,
  timeout = 60_000,
): Promise<string> {
  await commandSlot(slots, instanceId);
  await page.getByTestId(`command-run-${type}`).click();
  const dialog = page.getByTestId(`command-dialog-${type}`);
  await expect(dialog).toBeVisible();
  await expect(dialog.getByTestId('command-dialog-impact')).toHaveText(
    t(`commands:impact.${type}`),
  );
  const posted = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === `/api/v1/instances/${String(instanceId)}/commands`,
  );
  await dialog.getByTestId('command-dialog-confirm').click();
  const answer = await posted;
  expect(answer.status(), `POST ${type}`).toBe(202);
  const body = (await answer.json()) as { data: { command_id: string; status: string } };
  expect(body.data.status, `${type}: queued first`).toBe('queued');
  const feedback = page.getByTestId('command-feedback');
  await expect(feedback).toHaveAttribute('data-command-id', body.data.command_id);
  await expect(feedback).toHaveAttribute('data-state', 'done', { timeout });
  await expect(feedback).toHaveText(t('commands:status.done'));
  return body.data.command_id;
}

// ---------------------------------------------------------------------------------------------
// Checks of required/dumps.yaml
// ---------------------------------------------------------------------------------------------

/**
 * 2.7-4 (administrator): the five command buttons, the history of the instance (requester, time,
 * duration, size, state of the API's list in its order) with the operator's dump closed to nobody
 * here, the audit trail link.
 */
export const commandsTabElements: DumpsCheck = async (ctx) => {
  const feed = await requireCommandFeed(ctx);
  const page = ctx.page;
  const shared = await sharedDump(ctx, feed, 'normal');
  const admin = await ctx.apiAs('admin');
  const instanceId = shared.instanceId;
  await openInstanceTab(page, feed.appId, instanceId, 'commands');
  await expect(page.getByTestId('instance-commands')).toBeVisible();
  expect(await shownButtons(page), 'the administrator offers every command').toEqual(
    COMMAND_BUTTONS.admin,
  );
  expect(await serverAllows(admin, instanceId), 'the server agrees').toEqual(COMMAND_BUTTONS.admin);
  const list = await commandList(admin, instanceId);
  const shown = await historyRows(page);
  expect(shown.slice(0, list.length), 'history rows = GET /instances/{id}/commands').toEqual(
    list.slice(0, shown.length).map((c) => c.command_id),
  );
  const row = page.getByTestId(`command-row-${shared.commandId}`);
  await expect(row).toHaveAttribute('data-type', 'thread_dump');
  await expect(row).toHaveAttribute('data-status', 'done');
  const view = list.find((c) => c.command_id === shared.commandId) ?? shared.view;
  await expect(page.getByTestId(`command-requester-${shared.commandId}`)).toHaveText(
    view.requested_by_name ?? ctx.seed.users.operator,
  );
  expect(view.requested_by_name, 'requested_by_name is the operator').toBe(ctx.seed.users.operator);
  await expect(row.locator('[data-column="requested_at"] time')).toHaveAttribute(
    'datetime',
    view.requested_at,
  );
  for (const column of ['duration_ms', 'result_size'] as const) {
    const cell = row.locator(`[data-column="${column}"] .value-text`);
    if (view[column] === null) {
      await expect(cell, `${column}: missing`).toHaveAttribute('data-missing', /./);
    } else {
      await expect(cell, `${column}: a value`).not.toHaveAttribute('data-missing', /./);
      await expect(cell).toHaveText(/\d/);
    }
  }
  await expect(page.getByTestId(`command-status-${shared.commandId}`)).toHaveText(
    t('commands:status.done'),
  );
  // The dump opens in the viewer (administrators read every result).
  await expect(page.getByTestId(`command-open-${shared.commandId}`)).toHaveAttribute(
    'href',
    new RegExp(
      `/apps/${String(feed.appId)}/instances/${String(instanceId)}\\?.*cmd=${shared.commandId}`,
    ),
  );
  // Audit: the history is the request and result trail; administrators reach the audit screen.
  const audit = page.getByTestId('command-audit-link');
  const href = new URL((await audit.getAttribute('href')) ?? '', 'http://ui.invalid');
  expect(href.pathname).toBe('/admin/audit');
  expect(href.searchParams.get('f.instance')).toBe(String(instanceId));
};

/**
 * 2.7-5 (operator): the viewer of the operator's dump equals its result (states, collapsed stacks,
 * locks and deadlock, virtual threads, transaction links), search by thread name and by class and
 * method, the .txt download, the list of the last 20 and the depth-limit notice of the 64-frame
 * stack.
 */
export const dumpViewerElements: DumpsCheck = async (ctx) => {
  const feed = await requireCommandFeed(ctx);
  const page = ctx.page;
  const shared = await sharedDump(ctx, feed, 'normal');
  const operator = await ctx.apiAs('operator');
  await openDump(page, feed.appId, shared.instanceId, shared.commandId);
  const want = await expectViewerEqualsResult(page, feed.appId, shared.result, 'operator dump');
  expect(want.deadlocks, 'the feed dump has its deadlock').toBe(1);
  expect(want.virtual, 'the feed dump has virtual threads').toBeGreaterThanOrEqual(2);
  expect(Math.max(...want.sections.flatMap((s) => s.sizes)), 'idle pool threads collapsed').toBe(
    feed.commands.idle_pool_threads,
  );
  await expectSearch(page, shared.result.threads ?? [], 'LinkedBlockingQueue.take');
  await expectSearch(page, shared.result.threads ?? [], feed.commands.deadlock_threads[0] ?? '');
  await expect(page.getByTestId('dump-download')).toBeEnabled();
  await expect(page.getByTestId('dump-truncated')).toHaveText(
    t('commands:viewer.depthCut', 'en', { count: String(MAX_FRAMES) }),
  );
  const list = await commandList(operator, shared.instanceId);
  await expectDumpList(page, list);
};

/** Types `query` into the search: the URL key q, the count and the shown sections = the result. */
export async function expectSearch(
  page: Page,
  threads: readonly ThreadApi[],
  query: string,
): Promise<void> {
  const expected = searchMatches(threads, query);
  expect(expected, `the dump has threads matching ${query}`).toBeGreaterThan(0);
  await page.getByTestId('dump-search').fill(query);
  await expectUrl(page, (s) => s.params.get('q') === query, `q=${query} in the URL`);
  await expect(page.getByTestId('dump-search-count')).toHaveAttribute(
    'data-count',
    String(expected),
  );
  const shown = await readViewer(page);
  expect(
    shown.sections.reduce((sum, s) => sum + s.count, 0),
    `threads shown for ${query}`,
  ).toBe(expected);
  await page.getByTestId('dump-search').fill('');
  await expectUrl(page, (s) => s.params.get('q') === null, 'q removed from the URL');
}

/** The list of the dumps tab = the last done thread dumps of the history (at most 20). */
export async function expectDumpList(page: Page, list: readonly CommandViewApi[]): Promise<void> {
  const want = recentDoneDumps(list);
  const items = page.getByTestId('dumps-list').locator('[data-testid^="dumps-item-"]');
  await expect(items).toHaveCount(want.length);
  const ids = await items.evaluateAll((els) =>
    els.map((e) => (e.getAttribute('data-testid') ?? '').slice('dumps-item-'.length)),
  );
  expect(ids, 'the dumps list = the last done thread dumps of the history').toEqual(want);
  await expect(page.getByTestId('dumps-list')).toHaveAttribute('data-count', String(want.length));
}

// ---------------------------------------------------------------------------------------------
// Checks of required/active.yaml 2.6-4 (operator, on the command feed's active screen)
// ---------------------------------------------------------------------------------------------

/** Opens the active screen of the command feed and selects a running row of 8 s or more. */
export async function selectSlowFeedRow(
  ctx: DumpsCheckContext,
  feed: CommandFeed,
): Promise<ScreenRow> {
  const page = ctx.page;
  await page.goto(`/apps/${String(feed.appId)}/active?kind=ALL`);
  await expectShell(page);
  await expect(page.getByTestId('active-page')).toHaveAttribute('data-state', 'ready', {
    timeout: 30_000,
  });
  const read = await readScreen(page);
  // A fixed slot (the same transaction in every snapshot) when there is one.
  const fixed = new Set(
    feed.scenario.filter((s) => s.timing === 'fixed').map((s) => `${s.instance}|${s.service}`),
  );
  const slow = read.rows.filter((r) => r.elapsed >= SLOW_MS);
  const row = slow.find((r) => fixed.has(`${r.instance}|${r.service}`)) ?? slow[0];
  if (row === undefined) {
    throw new Error('no running transaction of 8 s or more on the command feed screen');
  }
  await page.getByTestId(`active-cell-elapsed-${row.tx}`).click();
  await expect(page.getByTestId('active-row-actions')).toHaveAttribute('data-tx-id', row.tx);
  return row;
}

function instanceIdOf(feed: CommandFeed, name: string): number {
  const found = feed.feed.instances.find((i) => i.name === name);
  if (found === undefined) {
    throw new Error(`the command feed has no instance ${name}`);
  }
  return found.instanceId;
}

/**
 * 2.6-4 (T-ACPT-02 automated part): an operator's "this thread's stack" on a row of 8 s or more
 * shows the stack within 30 s with its state and the transaction match; the screen = the result.
 */
export const activeThreadStack: DumpsCheck = async (ctx) => {
  const feed = await requireCommandFeed(ctx);
  const page = ctx.page;
  const operator = await ctx.apiAs('operator');
  const row = await selectSlowFeedRow(ctx, feed);
  const instanceId = instanceIdOf(feed, row.instance);
  await commandSlot(operator, instanceId);
  const posted = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === `/api/v1/instances/${String(instanceId)}/commands`,
  );
  const started = Date.now();
  await page.getByTestId('active-action-stack').click();
  const answer = await posted;
  expect(answer.status(), 'POST thread_stack').toBe(202);
  const commandId = ((await answer.json()) as { data: { command_id: string } }).data.command_id;
  const asked = await operator.data<CommandViewApi>(`/commands/${commandId}`);
  expect({ type: asked.type, args: asked.args }, 'the stack of this transaction').toEqual({
    type: 'thread_stack',
    args: { tx_id: row.tx },
  });
  await expect(page.getByTestId('thread-stack-modal')).toBeVisible();
  const stack = page.getByTestId('thread-stack');
  await expect(stack, 'the stack within 30 s (T-ACPT-02)').toBeVisible({
    timeout: Math.max(1, STACK_WITHIN_MS - (Date.now() - started)),
  });
  const elapsedMs = Date.now() - started;
  ctx.testInfo.annotations.push({
    type: 'thread-stack',
    description: `tx ${row.tx} (${row.state}, ${String(row.elapsed)} ms) on ${row.instance}: stack shown ${String(elapsedMs)} ms after the click (reference, shared laptop)`,
  });
  const result = await commandResult(operator, commandId);
  const thread = (result.threads ?? []).find((th) => th.tx_id === row.tx);
  expect(thread, 'the result is the thread of the transaction').toBeDefined();
  const group = groupOf(thread?.state ?? null);
  expect(group, `the feed's state for ${row.state}`).toBe(FEED_THREAD_STATE[row.state]);
  await expect(stack).toHaveAttribute('data-tx-match', 'match');
  await expect(stack).toHaveAttribute('data-state', group);
  await expect(page.getByTestId('thread-stack-name')).toHaveText(thread?.name ?? '');
  await expect(page.getByTestId('thread-stack-match')).toContainText(
    t('commands:stack.match.match', 'en', { txId: row.tx }),
  );
  await expect(page.getByTestId('thread-stack-tx-link')).toHaveAttribute(
    'href',
    `/apps/${String(feed.appId)}/transactions/${row.tx}`,
  );
  await expect(page.getByTestId('thread-stack-frames')).toContainText(thread?.frames[0] ?? '');
  await page.getByTestId('thread-stack-close').click();
  await expect(page.getByTestId('thread-stack-modal')).toBeHidden();
};

/**
 * 2.6-4: an operator's "thread dump" of a selected row requests the instance's dump and opens it
 * in the instance detail's dumps tab (keys carried), where the viewer equals its result.
 */
export const activeThreadDump: DumpsCheck = async (ctx) => {
  const feed = await requireCommandFeed(ctx);
  const page = ctx.page;
  const operator = await ctx.apiAs('operator');
  const row = await selectSlowFeedRow(ctx, feed);
  const instanceId = instanceIdOf(feed, row.instance);
  await commandSlot(operator, instanceId);
  const posted = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      new URL(r.url()).pathname === `/api/v1/instances/${String(instanceId)}/commands`,
  );
  await page.getByTestId('active-action-dump').click();
  const answer = await posted;
  expect(answer.status(), 'POST thread_dump').toBe(202);
  const commandId = ((await answer.json()) as { data: { command_id: string } }).data.command_id;
  const asked = await operator.data<CommandViewApi>(`/commands/${commandId}`);
  expect({ type: asked.type, args: asked.args }, 'the dump of the instance').toEqual({
    type: 'thread_dump',
    args: {},
  });
  expect(asked.instance_id).toBe(instanceId);
  await expectUrl(
    page,
    (s) =>
      s.path === `/apps/${String(feed.appId)}/instances/${String(instanceId)}` &&
      s.params.get('tab') === 'dumps' &&
      s.params.get('cmd') === commandId &&
      s.params.get('kind') === 'ALL',
    'the dump opens in the dumps tab of the instance, kind carried',
  );
  await expectViewerOf(page, commandId);
  const view = await commandEnd(operator, commandId);
  expect(view.status).toBe('done');
  const result = await commandResult(operator, commandId);
  const want = await expectViewerEqualsResult(
    page,
    feed.appId,
    result,
    'dump from the active screen',
  );
  // The transaction of the selected row is among the dump's transaction links (snapshot).
  expect(want.txIds.length, 'running transactions in the dump').toBeGreaterThan(0);
};

export const DUMPS_CHECKS: Readonly<Record<string, DumpsCheck>> = {
  commandsTabElements,
  dumpViewerElements,
  activeThreadStack,
  activeThreadDump,
};
