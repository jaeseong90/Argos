// SPDX-License-Identifier: Apache-2.0
//
// Remote commands, remote configuration and the remote kill switch between the REAL agent and the
// REAL server (WO-17 on top of the WO-26 API, docs/10 §12.1-§12.2, docs/11 §8.1-§8.2, docs/16
// §5.2-§5.5, SEC-20 to SEC-25): shop-boot3 with the agent jar of the stack (virtual threads,
// SAMPLE_VT=true) reports to the stack's real collector as its own application, and the tests talk
// only to the public REST API with the seeded admin and operator sessions:
//
// - T-AG-054 (stack): thread_dump of a running request on a virtual thread, parsed by the server
//   (ThreadDump) into threads[] with virtual=true and the request's tx_id.
// - T-SEC-24 (stack): thread_stack of the running tx_id (one thread with frames); a thread_dump
//   right after a thread_dump fails with the agent's cooldown.
// - T-AG-072 (stack): env_dump with ARGOS_SEED sentinel secrets in the environment, in
//   JAVA_TOOL_OPTIONS and as -D system property: the sentinels never reach the result or the agent
//   log, the masked form ****(len=N) does; an operator gets 403.
// - T-SEC-21 (stack): self_diagnosis (operator) carries the agent version and the effective
//   configuration; reload_config is for administrators and is done by the agent.
// - T-AG-069 (stack): a change of sampling.profile.normal-rate (default remote-config.allow) is
//   applied by the instance within 10 s ([wo17-n25] line), the server's effective-config and the
//   agent's self diagnosis show it as remote.
// - T-SEC-23 (stack): capture.sql-parameters (outside the allow list) is refused with 422
//   remote_key_not_allowed and the agent's local value is unchanged.
// - T-AG-043 (stack): the kill switch for this instance makes it WARN kill_switch and stops its
//   transactions while the session stays; turned back on, new transactions arrive.
//
// Remote configuration and the kill switch are held per application (app_config) and reach every
// later agent of the app in its ServerHello.config, so the shop reports as its own app WO17_APP
// (own agent token, provisioned through psql as the active feed does) and never touches the seed's
// e2e-shop. afterAll always turns the kill switch back on and stops the shop. Inside the Playwright
// image of e2e:docker there is no JVM: every test skips with the reason of shopProblem.
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { apiAs, expect, iso, test } from '../fixtures/stack';
import { openShell } from '../fixtures/ui';
import type { ApiResponse, ApiSession } from '../stack/api';
import { run } from '../stack/docker';
import { shopProblem, startShop, type RunningShop } from '../stack/liveShop';
import type { SeedManifest } from '../stack/manifest';
import { logsDir, secretsDir } from '../stack/paths';
import { retireInstances } from '../stack/retire';
import { readRunState } from '../stack/runState';
import { newAgentToken, readSecret, sha256Hex, writeSecret } from '../stack/secrets';
import { stopProcess, tokenSql } from '../stack/seed/traffic';
import type { StackSession } from '../stack/session';
import { httpGet, sleep, waitFor } from '../stack/wait';

/** The shop's own application (remote configuration and the kill switch are per app). */
const WO17_APP = { name: 'e2e-wo17', env: 'e2e' } as const;
/** Instance name of the shop. */
const WO17_INSTANCE = 'wo17-cmd';
/** Token file of WO17_APP inside the run's secrets directory. */
const WO17_TOKEN_FILE = 'wo17-agent.token';
/** A running request: a 30 s sleep inside H2 on a (virtual) request thread. */
const SLOW_PATH = '/api/slow?ms=30000&sql=1';
/** A short request (kill switch windows). */
const QUICK_PATH = '/api/slow?ms=5';

/**
 * Secrets of the seed sentinel (18 §4.3, ARGOS_SEED): none of them may appear in an env_dump result
 * or in the agent log (T-AG-072, T-SEC-20).
 */
const SECRET = {
  dbPassword: 'ARGOS_SEED-wo17-db-password',
  githubToken: 'ghp_ARGOS_SEED_wo17_github_token',
  jwt: 'eyJhbGciOiJIUzI1NiJ9.ARGOS_SEED-wo17-jwt-claims.ARGOS_SEED-wo17-jwt-signature',
  toolOptionsPassword: 'ARGOS_SEED-wo17-tool-options-password',
  sysPropToken: 'ARGOS_SEED-wo17-sysprop-token',
} as const;

/** Environment of the shop: virtual threads and the sentinel secrets. */
const SHOP_ENV: Readonly<Record<string, string>> = {
  SAMPLE_VT: 'true',
  DB_PASSWORD: SECRET.dbPassword,
  GITHUB_TOKEN: SECRET.githubToken,
  // A neutral key: only the JWT value rule of 16 §5.4 masks it.
  WO17_UPSTREAM_HINT: SECRET.jwt,
  JAVA_TOOL_OPTIONS: `-Dwo17.db.password=${SECRET.toolOptionsPassword}`,
};

function req(...ids: string[]): { type: string; description: string }[] {
  return ids.map((description) => ({ type: 'req', description }));
}

// ---------------------------------------------------------------------------------------------
// API shapes (the subset these tests read; docs/api/openapi.yaml is the contract)
// ---------------------------------------------------------------------------------------------

interface CommandView {
  command_id: string;
  type: string;
  instance_id: number;
  status: string;
  error: string | null;
  duration_ms: number | null;
  truncated: boolean | null;
  result_size: number | null;
}

interface CommandThread {
  id: number | null;
  name: string;
  state: string | null;
  virtual: boolean;
  frames: string[];
  tx_id: string | null;
}

interface CommandResult {
  command_id: string;
  type: string;
  format: string;
  content_type: string;
  truncated: boolean;
  size_bytes: number;
  threads: CommandThread[] | null;
  deadlocks: { thread_ids: number[] }[] | null;
  text: string | null;
}

interface InstanceRow {
  instance_id: number;
  name: string;
  state: string;
  connected_at: string | null;
  remote_config_allow: string[] | null;
}

interface ActiveItem {
  instance_id: number;
  tx_id: string;
  service: string | null;
  state: string;
  virtual_thread: boolean;
  started_at: string;
}

interface ConfigChangeResult {
  instance_id: number;
  status: string;
  applied_keys: string[];
  rejected_keys: string[];
  duration_ms: number | null;
  pushed_at: string | null;
  applied_at: string | null;
  error: string | null;
}

interface ConfigChange {
  change_id: string;
  kind: string;
  status: string;
  preview: { rejected_keys_by_instance: { instance_id: number; keys: string[] }[] };
  results: ConfigChangeResult[];
}

interface EffectiveConfigEntry {
  key: string;
  value: string | null;
  source: string | null;
  remote_allowed: boolean;
  server_value: string | null;
  server_status: string | null;
}

interface DashboardInstance {
  instance_id: number;
  state: string;
  warn_reasons: string[];
}

interface XlogPoints {
  tx_id: string[];
  ts: number[];
  instance_id: number[];
}

/** The agent's SELF_DIAGNOSTICS object (agent-core SelfDiagCommand), the members read here. */
interface SelfDiagnosis {
  agent?: { version?: unknown };
  effective_config?: Record<string, { value: string | null; source: string | null } | undefined>;
}

/** The API error code of a response body (docs/14 §2.6), or null. */
function errorCode(response: ApiResponse): string | null {
  const body = response.body as { error?: { code?: unknown } } | null;
  return typeof body?.error?.code === 'string' ? body.error.code : null;
}

// ---------------------------------------------------------------------------------------------
// The shop of this file
// ---------------------------------------------------------------------------------------------

interface Wo17Context {
  readonly shop: RunningShop;
  readonly appId: number;
  readonly instanceId: number;
  readonly admin: ApiSession;
  readonly operator: ApiSession;
  /** The agent's log directory (-Dargos.log.dir), scanned for the sentinels. */
  readonly agentLogDir: string;
  readonly shopLog: string;
}

let ctx: Wo17Context | null = null;

function context(): Wo17Context {
  if (ctx === null) {
    throw new Error('the WO-17 shop did not start (see beforeAll)');
  }
  return ctx;
}

/** The app row and an ACTIVE agent token of WO17_APP (once per run; the token is never printed). */
async function provisionToken(runId: string): Promise<string> {
  const dir = secretsDir(runId);
  const file = join(dir, WO17_TOKEN_FILE);
  if (existsSync(file) && readSecret(dir, WO17_TOKEN_FILE).startsWith('argos_')) {
    return file;
  }
  const postgres = readRunState()?.containers?.postgres;
  if (postgres === undefined) {
    throw new Error('ui/e2e/.stack/run.json names no PostgreSQL container to provision the token');
  }
  const token = newAgentToken();
  await run(
    'docker',
    [
      'exec',
      '-i',
      postgres,
      'psql',
      '-X',
      '-q',
      '-v',
      'ON_ERROR_STOP=1',
      '-U',
      'postgres',
      '-d',
      'argos',
    ],
    {
      input: tokenSql(sha256Hex(token), token.slice(0, 14), WO17_APP),
      secrets: [token],
      timeoutMs: 60_000,
    },
  );
  writeSecret(dir, WO17_TOKEN_FILE, token);
  return file;
}

async function startContext(session: StackSession, seed: SeedManifest): Promise<Wo17Context> {
  const runId = session.run_id;
  const tokenFile = await provisionToken(runId);
  const agentLogDir = join(logsDir(runId), 'wo17-cmd-agent');
  const shopLog = 'wo17-cmd-shop-boot3.log';
  const shop = await startShop(runId, {
    instance: WO17_INSTANCE,
    logFile: shopLog,
    env: SHOP_ENV,
    jvmArgs: [`-Dwo17.secret.token=${SECRET.sysPropToken}`, `-Dargos.log.dir=${agentLogDir}`],
    app: { ...WO17_APP, tokenFile },
  });
  try {
    const admin = await apiAs(session, seed, 'admin');
    const operator = await apiAs(session, seed, 'operator');
    const appId = await waitFor(
      async () => {
        const apps = await admin.data<{ app_id: number; name: string; env: string }[]>('/apps');
        return apps.find((a) => a.name === WO17_APP.name && a.env === WO17_APP.env)?.app_id;
      },
      { timeoutMs: 60_000, intervalMs: 1000, what: `GET /apps lists ${WO17_APP.name}` },
    );
    const instanceId = await waitFor(
      async () => {
        const rows = await admin.data<InstanceRow[]>(`/apps/${String(appId)}/instances`);
        const row = rows.find((r) => r.name === WO17_INSTANCE);
        const live =
          row !== undefined &&
          row.connected_at !== null &&
          (row.state === 'UP' || row.state === 'WARN');
        return live ? row.instance_id : undefined;
      },
      { timeoutMs: 90_000, intervalMs: 1000, what: `${WO17_INSTANCE} with a live session` },
    );
    const c: Wo17Context = {
      shop,
      appId,
      instanceId,
      admin,
      operator,
      agentLogDir,
      shopLog: join(logsDir(runId), shopLog),
    };
    // A run that ended before its afterAll may have left the kill switch off in app_config: the
    // agent then got enabled=false in its ServerHello.config.
    const held = await admin.data<{ current: Record<string, string> }>(
      `/admin/apps/${String(appId)}/config`,
    );
    if (held.current.enabled === 'false') {
      await setKillSwitch(c, true);
    }
    return c;
  } catch (error) {
    await stopProcess(shop.child);
    throw error;
  }
}

// ---------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------

/** Final command states (docs/14 §4.10). */
const FINAL = new Set(['done', 'failed', 'expired']);

/** POST /instances/{id}/commands until the server queues it; resolves with the command id. */
async function queueCommand(
  api: ApiSession,
  instanceId: number,
  type: string,
  args: Record<string, string>,
): Promise<string> {
  const deadline = Date.now() + 120_000;
  for (;;) {
    const response = await api.request<{ data: { command_id: string } }>(
      'POST',
      `/instances/${String(instanceId)}/commands`,
      { body: { type, args }, expect: [202, 409, 429] },
    );
    if (response.status === 202 && response.body !== null) {
      return response.body.data.command_id;
    }
    const code = errorCode(response);
    if (Date.now() > deadline || (response.status === 409 && code !== 'invalid_state')) {
      throw new Error(`POST ${type}: HTTP ${String(response.status)} ${String(code)}`);
    }
    const retryAfter = Number.parseInt(response.headers.get('retry-after') ?? '1', 10);
    await sleep(Math.min(Number.isNaN(retryAfter) ? 1 : retryAfter, 60) * 1000);
  }
}

/**
 * Requests a command and waits for its final state. 429 waits for Retry-After (10 per instance per
 * minute) and 409 invalid_state for the instance's running command; other refusals fail.
 */
async function command(
  api: ApiSession,
  type: string,
  args: Record<string, string> = {},
): Promise<CommandView> {
  const id = await queueCommand(api, context().instanceId, type, args);
  return waitFor(
    async () => {
      const view = await api.data<CommandView>(`/commands/${id}`);
      return FINAL.has(view.status) ? view : undefined;
    },
    { timeoutMs: 150_000, intervalMs: 250, what: `command ${type} ${id} to finish` },
  );
}

/** A command that may meet its own cooldown from an earlier test: waits `waitMs` and asks again. */
async function commandPastCooldown(
  api: ApiSession,
  type: string,
  args: Record<string, string>,
  waitMs: number,
): Promise<CommandView> {
  for (let attempt = 0; ; attempt++) {
    const view = await command(api, type, args);
    if (view.status !== 'failed' || view.error !== 'cooldown' || attempt >= 3) {
      return view;
    }
    await sleep(waitMs);
  }
}

async function result(api: ApiSession, id: string, format = 'json'): Promise<CommandResult> {
  return api.data<CommandResult>(`/commands/${id}/result`, { format });
}

/** The operator's self diagnosis of the shop (past the 1 s SELF_DIAGNOSTICS cooldown). */
async function selfDiagnosis(): Promise<SelfDiagnosis> {
  const api = context().operator;
  const view = await commandPastCooldown(api, 'self_diagnosis', {}, 1500);
  expect(view.status, `self_diagnosis ${view.command_id}: ${String(view.error)}`).toBe('done');
  const r = await result(api, view.command_id);
  expect(r.content_type).toBe('application/json');
  expect(r.text, 'self_diagnosis comes back as text (not a thread dump)').not.toBeNull();
  return JSON.parse(r.text ?? '{}') as SelfDiagnosis;
}

function effective(diag: SelfDiagnosis, key: string): { value: string | null; source: string } {
  const entry = diag.effective_config?.[key];
  if (entry === undefined) {
    throw new Error(`self_diagnosis effective_config has no ${key}`);
  }
  return { value: entry.value, source: entry.source ?? 'null' };
}

/** The running SLOW_PATH request of the shop in GET /apps/{appId}/active, or undefined. */
/**
 * Slack of `started_at` against the time the request was sent: the agent stamps the transaction
 * start in milliseconds on the same host, after the request left this process.
 */
const STARTED_SLACK_MS = 1000;

async function runningSlow(api: ApiSession, sentAtMs: number): Promise<ActiveItem | undefined> {
  const c = context();
  const active = await api.data<{ items: ActiveItem[] }>(`/apps/${String(c.appId)}/active`, {
    kind: 'ALL',
  });
  // The server answers with the agent's last snapshot (every 5 s, C-225): right after an earlier
  // slow request ended, that snapshot still lists it although the agent no longer runs it. Only a
  // transaction that started after this request was sent is this request's.
  return active.items.find(
    (i) =>
      i.instance_id === c.instanceId &&
      (i.service ?? '').includes('/api/slow') &&
      Date.parse(i.started_at) >= sentAtMs - STARTED_SLACK_MS,
  );
}

/** Starts SLOW_PATH in the background and waits until the agent reports it running. */
async function startSlowRequest(
  api: ApiSession,
): Promise<{ item: ActiveItem; done: Promise<unknown> }> {
  const sentAtMs = Date.now();
  const done = httpGet(`${context().shop.url}${SLOW_PATH}`, {}, 60_000).catch(
    (error: unknown) => error,
  );
  // The agent snapshots every 5 s and the server keeps the last snapshot (C-225).
  const item = await waitFor(() => runningSlow(api, sentAtMs), {
    timeoutMs: 60_000,
    intervalMs: 1000,
    what: `GET /active shows ${SLOW_PATH} of ${WO17_INSTANCE}`,
  });
  return { item, done };
}

// ---------------------------------------------------------------------------------------------
// Configuration and kill switch
// ---------------------------------------------------------------------------------------------

/** Waits until the change's result for the shop's instance leaves pending; resolves with it. */
async function instanceResult(
  api: ApiSession,
  changeId: string,
  instanceId: number,
  timeoutMs: number,
  intervalMs = 100,
): Promise<ConfigChangeResult> {
  return waitFor(
    async () => {
      const change = await api.data<ConfigChange>(`/admin/config/changes/${changeId}`);
      const r = change.results.find((x) => x.instance_id === instanceId);
      return r !== undefined && r.status !== 'pending' ? r : undefined;
    },
    { timeoutMs, intervalMs, what: `the result of change ${changeId} for ${WO17_INSTANCE}` },
  );
}

async function setKillSwitch(c: Wo17Context, enabled: boolean): Promise<ConfigChangeResult> {
  const response = await c.admin.request<{ data: ConfigChange }>(
    'POST',
    `/admin/apps/${String(c.appId)}/kill-switch`,
    {
      body: {
        enabled,
        instance_ids: [c.instanceId],
        reason: `WO-17 E2E: kill switch ${enabled ? 'off' : 'on'}`,
      },
      expect: [202],
    },
  );
  const change = response.body?.data;
  if (change === undefined) {
    throw new Error('POST /kill-switch: empty body');
  }
  return instanceResult(c.admin, change.change_id, c.instanceId, 30_000, 250);
}

async function dashboardInstance(): Promise<DashboardInstance | undefined> {
  const c = context();
  const now = Date.now();
  const dashboard = await c.admin.data<{ instances: DashboardInstance[] }>(
    `/apps/${String(c.appId)}/dashboard`,
    { from: iso(now - 15 * 60_000), to: iso(now) },
  );
  return dashboard.instances.find((i) => i.instance_id === c.instanceId);
}

/** Transactions of the shop's instance with a start in [fromMs, toMs). */
async function xlogCount(fromMs: number, toMs: number): Promise<number> {
  const c = context();
  const points = await c.admin.data<XlogPoints>(`/apps/${String(c.appId)}/xlog`, {
    from: iso(fromMs),
    to: iso(toMs),
    kind: 'ALL',
    instance: [String(c.instanceId)],
  });
  return points.tx_id.filter((_, i) => {
    const ts = points.ts[i] ?? 0;
    return points.instance_id[i] === c.instanceId && ts >= fromMs && ts < toMs;
  }).length;
}

async function quickRequests(n: number): Promise<void> {
  const url = `${context().shop.url}${QUICK_PATH}`;
  for (let i = 0; i < n; i++) {
    const response = await httpGet(url, {}, 10_000);
    expect(response.status, `GET ${QUICK_PATH}`).toBe(200);
  }
}

/** Every file under `dir` (the agent's log directory is flat; one level of subdirectories). */
function filesUnder(dir: string): string[] {
  if (!existsSync(dir)) {
    return [];
  }
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      out.push(...filesUnder(path));
    } else {
      out.push(path);
    }
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------------------------

test.describe('agent commands (WO-17)', () => {
  test.beforeAll(async ({ session, seed }, testInfo) => {
    testInfo.setTimeout(5 * 60_000);
    const problem = shopProblem(session.run_id);
    test.skip(problem !== null, problem ?? '');
    ctx = await startContext(session, seed);
  });

  // Every stack test opens the UI (fixtures.ts: main-frame documents with a CSP header, no console
  // error): the dashboard of the shop's own application, where its instance and state show.
  test.beforeEach(async ({ page }) => {
    await openShell(page, `/apps/${String(context().appId)}/dashboard`);
  });

  test.afterAll(async ({ session, seed }, testInfo) => {
    testInfo.setTimeout(3 * 60_000);
    const c = ctx;
    ctx = null;
    if (c === null) {
      return;
    }
    try {
      // Always back on: app_config keeps the value for the app's next agents.
      await setKillSwitch(c, true);
    } catch (error) {
      testInfo.annotations.push({
        type: 'wo17-kill-switch-restore',
        description: `turning the kill switch back on failed: ${error instanceof Error ? error.message : String(error)}`,
      });
    } finally {
      await stopProcess(c.shop.child);
      testInfo.annotations.push({ type: 'wo17-shop-log', description: c.shopLog });
      // C-241 (7): the stopped shop's instance never stays FIRING as instance_down.
      await retireInstances(await apiAs(session, seed, 'operator'), [c.instanceId]);
    }
  });

  test(
    'T-AG-054 (stack) thread_dump of a request running on a virtual thread: threads[] has virtual=true with its tx_id, state done',
    { annotation: req('F-A30', 'F-A12', 'D-23') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      test.setTimeout(4 * 60_000);
      const c = context();
      const { item, done } = await startSlowRequest(c.operator);
      try {
        expect(item.virtual_thread, 'SAMPLE_VT=true: the request runs on a virtual thread').toBe(
          true,
        );
        const view = await commandPastCooldown(c.operator, 'thread_dump', {}, 6000);
        expect(view.status, `thread_dump: ${String(view.error)}`).toBe('done');
        const r = await result(c.operator, view.command_id);
        expect(r.content_type).toBe('application/json');
        expect(r.threads, 'the server parsed the dump (ThreadDump)').not.toBeNull();
        const threads = r.threads ?? [];
        const virtual = threads.filter((t) => t.virtual);
        expect(virtual.length, 'virtual threads in the dump').toBeGreaterThan(0);
        const running = threads.find((t) => t.tx_id === item.tx_id);
        expect(running, `a thread runs tx ${item.tx_id}`).toBeDefined();
        expect(running?.virtual, 'the thread of the running request is virtual').toBe(true);
        expect(running?.frames.length ?? 0, 'frames of the running thread').toBeGreaterThan(0);
        testInfo.annotations.push({
          type: 'wo17-thread-dump',
          description: `${String(threads.length)} threads, ${String(virtual.length)} virtual, ${String(r.size_bytes)} bytes, agent ${String(view.duration_ms)} ms; tx ${item.tx_id} on ${running?.name ?? '?'}`,
        });
      } finally {
        await done;
      }
    },
  );

  test(
    'T-SEC-24 (stack) thread_stack of the running tx_id returns one thread with frames; a thread_dump right after a thread_dump fails with cooldown',
    { annotation: req('SEC-24', 'F-A12') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      test.setTimeout(4 * 60_000);
      const c = context();
      const { item, done } = await startSlowRequest(c.operator);
      try {
        const stack = await command(c.operator, 'thread_stack', { tx_id: item.tx_id });
        expect(stack.status, `thread_stack: ${String(stack.error)}`).toBe('done');
        const r = await result(c.operator, stack.command_id);
        const threads = r.threads ?? [];
        expect(threads, 'one thread').toHaveLength(1);
        expect(threads[0]?.frames.length ?? 0, 'frames').toBeGreaterThan(0);
        expect(threads[0]?.tx_id, 'the thread of the requested transaction').toBe(item.tx_id);
        // The first dump may still be in the cooldown of T-AG-054's dump (5 s).
        const first = await commandPastCooldown(c.operator, 'thread_dump', {}, 6000);
        expect(first.status, `first thread_dump: ${String(first.error)}`).toBe('done');
        const second = await command(c.operator, 'thread_dump');
        expect(
          { status: second.status, error: second.error },
          'a second thread_dump within commands.thread-dump.cooldown-seconds (5 s)',
        ).toEqual({ status: 'failed', error: 'cooldown' });
        testInfo.annotations.push({
          type: 'wo17-cooldown',
          description: `thread_stack ${String(stack.duration_ms)} ms; dump done then ${second.status}/${String(second.error)}`,
        });
      } finally {
        await done;
      }
    },
  );

  test(
    'T-AG-072 (stack) env_dump (admin) masks the seed sentinel secrets agent-side: 0 occurrences in the result and the agent log, ****(len=N) present; operator 403',
    { annotation: req('SEC-20', 'SEC-21') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      test.setTimeout(4 * 60_000);
      const c = context();
      const view = await commandPastCooldown(c.admin, 'env_dump', {}, 61_000);
      expect(view.status, `env_dump: ${String(view.error)}`).toBe('done');
      const r = await result(c.admin, view.command_id);
      expect(r.content_type).toBe('text/plain');
      const text = r.text ?? '';
      expect(text.length, 'env_dump text').toBeGreaterThan(0);
      for (const [name, secret] of Object.entries(SECRET)) {
        expect(text.includes(secret), `env_dump contains the ${name} sentinel`).toBe(false);
      }
      expect(text.includes('ARGOS_SEED'), 'env_dump contains ARGOS_SEED').toBe(false);
      expect(text).toContain('****(len=');
      const lines = text.split('\n');
      // Key rule (16 §5.4): the whole value becomes ****(len=N).
      expect(lines).toContain(`DB_PASSWORD=****(len=${String(SECRET.dbPassword.length)})`);
      expect(lines).toContain(`GITHUB_TOKEN=****(len=${String(SECRET.githubToken.length)})`);
      expect(lines).toContain(`wo17.secret.token=****(len=${String(SECRET.sysPropToken.length)})`);
      expect(lines).toContain(
        `wo17.db.password=****(len=${String(SECRET.toolOptionsPassword.length)})`,
      );
      // Value rule: the JWT under a neutral key; composite JAVA_TOOL_OPTIONS item -Dkey=value.
      expect(lines.find((l) => l.startsWith('WO17_UPSTREAM_HINT=')) ?? '').toContain('****(len=');
      expect(lines.find((l) => l.startsWith('JAVA_TOOL_OPTIONS=')) ?? '').toContain(
        `-Dwo17.db.password=****(len=${String(SECRET.toolOptionsPassword.length)})`,
      );
      // The agent log: written by the agent itself (-Dargos.log.dir), never a value.
      const logs = filesUnder(c.agentLogDir);
      expect(logs.length, `agent log files in ${c.agentLogDir}`).toBeGreaterThan(0);
      for (const file of logs) {
        expect(readFileSync(file, 'utf8').includes('ARGOS_SEED'), `ARGOS_SEED in ${file}`).toBe(
          false,
        );
      }
      // Role separation (SEC-21): the operator may not ask for an environment dump.
      const denied = await c.operator.request(
        'POST',
        `/instances/${String(c.instanceId)}/commands`,
        { body: { type: 'env_dump', args: {} }, expect: [403] },
      );
      expect(denied.status).toBe(403);
      testInfo.annotations.push({
        type: 'wo17-env-dump',
        description: `${String(lines.length)} lines, ${String(r.size_bytes)} bytes, ${String((text.match(/\*\*\*\*\(len=/g) ?? []).length)} masked values; agent log files ${String(logs.length)}`,
      });
    },
  );

  test(
    'T-SEC-21 (stack) self_diagnosis (operator) carries the agent version and the effective configuration; reload_config is admin only and done',
    { annotation: req('SEC-21', 'F-A12') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      test.setTimeout(4 * 60_000);
      const c = context();
      const diag = await selfDiagnosis();
      expect(typeof diag.agent?.version, 'agent.version').toBe('string');
      expect(String(diag.agent?.version)).not.toBe('');
      expect(
        Object.keys(diag.effective_config ?? {}).length,
        'effective_config keys',
      ).toBeGreaterThan(0);
      expect(effective(diag, 'enabled').value).toBe('true');
      const denied = await c.operator.request(
        'POST',
        `/instances/${String(c.instanceId)}/commands`,
        { body: { type: 'reload_config', args: {} }, expect: [403] },
      );
      expect(denied.status).toBe(403);
      const reload = await commandPastCooldown(c.admin, 'reload_config', {}, 61_000);
      expect(reload.status, `reload_config: ${String(reload.error)}`).toBe('done');
      testInfo.annotations.push({
        type: 'wo17-self-diagnosis',
        description: `agent ${String(diag.agent?.version)}, ${String(Object.keys(diag.effective_config ?? {}).length)} effective keys; reload_config ${String(reload.duration_ms)} ms`,
      });
    },
  );

  test(
    'T-AG-069 (stack) a change of sampling.profile.normal-rate is applied by the instance within 10 s and runs as remote ([wo17-n25])',
    { annotation: req('SEC-23', 'N-25', 'F-S29') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      // Commands are limited to 10 per instance per minute: a 429 waits for Retry-After.
      test.setTimeout(3 * 60_000);
      const c = context();
      const key = 'sampling.profile.normal-rate';
      const held = await c.admin.data<{ current: Record<string, string> }>(
        `/admin/apps/${String(c.appId)}/config`,
      );
      // A value the app does not hold yet (a reused stack may hold one of an earlier run).
      const value = ['0.25', '0.5', '0.75'].find((v) => v !== held.current[key]) ?? '0.25';
      const proposed = await c.admin.request<{ data: ConfigChange }>(
        'POST',
        `/admin/apps/${String(c.appId)}/config/changes`,
        {
          body: {
            reason: 'WO-17 E2E: remote sampling rate',
            changes: { [key]: value },
            targets: { instance_ids: [c.instanceId] },
          },
          expect: [201],
        },
      );
      const change = proposed.body?.data;
      if (change === undefined) {
        throw new Error('POST /config/changes: empty body');
      }
      const refusedHere = change.preview.rejected_keys_by_instance.find(
        (e) => e.instance_id === c.instanceId,
      );
      expect(refusedHere?.keys ?? [], 'keys the server refuses for this instance').toEqual([]);
      const applyAt = Date.now();
      await c.admin.request('POST', `/admin/config/changes/${change.change_id}/apply`, {
        expect: [202],
      });
      const r = await instanceResult(c.admin, change.change_id, c.instanceId, 30_000);
      const appliedMs = Date.now() - applyAt;
      expect(r.status, `result ${JSON.stringify(r)}`).toBe('applied');
      expect(r.applied_keys).toContain(key);
      const pushedToApplied =
        r.pushed_at !== null && r.applied_at !== null
          ? Date.parse(r.applied_at) - Date.parse(r.pushed_at)
          : null;
      const line = `[wo17-n25] apply_to_applied_ms=${String(appliedMs)} pushed_to_applied_ms=${String(pushedToApplied)} agent_duration_ms=${String(r.duration_ms)} instances=1 (laptop: INVALID reference)`;
      console.log(line);
      testInfo.annotations.push({ type: 'wo17-n25', description: line });
      expect(appliedMs, 'N-25: applied within 10 s of the apply request').toBeLessThanOrEqual(
        10_000,
      );
      const entries = await c.operator.data<EffectiveConfigEntry[]>(
        `/instances/${String(c.instanceId)}/effective-config`,
      );
      const entry = entries.find((e) => e.key === key);
      expect(entry, `effective-config lists ${key}`).toBeDefined();
      expect({
        value: Number(entry?.value),
        source: entry?.source,
        status: entry?.server_status,
        allowed: entry?.remote_allowed,
      }).toEqual({ value: Number(value), source: 'server', status: 'applied', allowed: true });
      const diag = effective(await selfDiagnosis(), key);
      expect({ value: Number(diag.value), source: diag.source }, 'the agent runs it').toEqual({
        value: Number(value),
        source: 'remote',
      });
    },
  );

  test(
    'T-SEC-23 (stack) capture.sql-parameters is outside remote-config.allow: 422 remote_key_not_allowed, the agent keeps its local value',
    { annotation: req('SEC-23', 'F-S29') },
    async () => {
      test.setTimeout(3 * 60_000);
      const c = context();
      const key = 'capture.sql-parameters';
      const before = effective(await selfDiagnosis(), key);
      expect(before.source, 'a local value').not.toBe('remote');
      const refused = await c.admin.request(
        'POST',
        `/admin/apps/${String(c.appId)}/config/changes`,
        {
          body: {
            reason: 'WO-17 E2E: widening capture must be refused',
            changes: { [key]: before.value === 'full' ? 'partial' : 'full' },
            targets: { instance_ids: [c.instanceId] },
          },
          expect: [422],
        },
      );
      expect(errorCode(refused)).toBe('remote_key_not_allowed');
      const entries = await c.operator.data<EffectiveConfigEntry[]>(
        `/instances/${String(c.instanceId)}/effective-config`,
      );
      expect(entries.find((e) => e.key === key)?.remote_allowed, `${key} remote_allowed`).toBe(
        false,
      );
      const after = effective(await selfDiagnosis(), key);
      expect(after, 'the local value is unchanged').toEqual(before);
    },
  );

  test(
    'T-AG-043 (stack) the kill switch for this instance: WARN kill_switch and no transactions while off, the session stays; back on, new transactions arrive',
    { annotation: req('F-A15') },
    // eslint-disable-next-line no-empty-pattern -- the shop and sessions come from beforeAll
    async ({}, testInfo) => {
      test.setTimeout(5 * 60_000);
      const c = context();
      const off = await setKillSwitch(c, false);
      expect(off.status, `kill switch off: ${JSON.stringify(off)}`).toBe('applied');
      const warn = await waitFor(
        async () => {
          const tile = await dashboardInstance();
          return tile?.warn_reasons.includes('kill_switch') === true ? tile : undefined;
        },
        { timeoutMs: 30_000, intervalMs: 500, what: `${WO17_INSTANCE} WARN kill_switch` },
      );
      expect(warn.state).toBe('WARN');
      const offStart = Date.now();
      await quickRequests(10);
      const offEnd = Date.now() + 1;
      // Still connected while off: the session and the metrics stay (state WARN, not STALE).
      const during = await c.admin.data<InstanceRow>(`/instances/${String(c.instanceId)}`);
      expect(during.state, 'the instance keeps its session while the kill switch is on').toBe(
        'WARN',
      );
      const on = await setKillSwitch(c, true);
      expect(on.status, `kill switch on: ${JSON.stringify(on)}`).toBe('applied');
      await waitFor(
        async () => {
          const tile = await dashboardInstance();
          return tile !== undefined && !tile.warn_reasons.includes('kill_switch')
            ? tile
            : undefined;
        },
        { timeoutMs: 30_000, intervalMs: 500, what: `${WO17_INSTANCE} without kill_switch` },
      );
      const onStart = Date.now();
      await quickRequests(5);
      const onEnd = Date.now() + 1;
      // Ingest keeps the order of one session's stream: once the later transactions are stored,
      // anything the agent had sent during the off window is stored too.
      const arrived = await waitFor(
        async () => {
          const n = await xlogCount(onStart - 1000, onEnd + 1000);
          return n >= 5 ? n : undefined;
        },
        { timeoutMs: 90_000, intervalMs: 1000, what: 'the transactions sent after turning it on' },
      );
      const duringOff = await xlogCount(offStart, offEnd);
      expect(duringOff, 'transactions of the 10 requests sent while off').toBe(0);
      testInfo.annotations.push({
        type: 'wo17-kill-switch',
        description: `off applied (agent ${String(off.duration_ms)} ms), 10 requests -> ${String(duringOff)} transactions; on -> ${String(arrived)} of 5`,
      });
    },
  );
});
