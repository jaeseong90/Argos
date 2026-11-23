// SPDX-License-Identifier: Apache-2.0
//
// Seed (b): slice traffic through the real collection path (docs/18 §13, SEC-62): shop-boot3 with
// the agent jar, pointed at the stack's gRPC port through a TCP proxy (e2e/stack/seed/grpcProxy.ts,
// the WO-22 collection outage), then test/seed/seed-traffic.sh (the PII/XSS seed
// endpoints of docs/18 §4.3, sentinel ARGOS_SEED), the GET /api/hop/{id} calls of WO-14 (real
// multi-hop traces, HOP_SCENARIOS), the GET /api/async/executor?work=sql calls of WO-15 (async work
// on executors inside one transaction, ASYNC_SERVICE) and a wait until the data is queryable
// through the API. The
// agent token is provisioned exactly as WO-21's integration tests do it
// (AgentTokens): `argos_` + base64url(32 random bytes), only its SHA-256 and 14-character prefix
// in argos.agent_tokens for an existing app (the slice has no issuance API, WO-26). The token text
// exists only in a file of the run's secrets directory and is never printed.
//
// Host processes (ext-stub, shop-boot3) listen on 127.0.0.1 random ports (never 8080) and are
// stopped as soon as the data is in (shop-boot3 after `whileRunning`, which sends no requests):
// afterwards the window totals stay fixed.
import { spawn, type ChildProcess } from 'node:child_process';
import { createWriteStream, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { ApiSession } from '../api.ts';
import { run } from '../docker.ts';
import type { ManifestAsyncCall } from '../manifest.ts';
import { logsDir, runDir, secretsDir } from '../paths.ts';
import { newAgentToken, sha256Hex, writeSecret } from '../secrets.ts';
import type { Log } from '../stack.ts';
import { freePort, httpGet, sleep, waitFor } from '../wait.ts';

import { bashScript, javaExecutable } from './gradle.ts';
import { GrpcProxy } from './grpcProxy.ts';

/** App of the slice traffic (agent app.name / app.env; letters, digits, '.', '_', '-' only). */
export const TRAFFIC_APP = { name: 'e2e-shop', env: 'e2e' } as const;

/** Instance name of the seeded agent (argos.app.instance); the same row on every reconnect. */
export const TRAFFIC_INSTANCE = 'e2e-shop-1';

/** Seed environment of the app process (sample-apps/common Seeds.environment()). */
export const SEED_ENVIRONMENT: Readonly<Record<string, string>> = {
  DB_PASSWORD: 'ARGOS_SEED_pw',
  AWS_SECRET_ACCESS_KEY: 'ARGOS_SEEDAKIAEXAMPLEONLY',
  ARGOS_TOKEN: 'eyJhbGciOi.ARGOS_SEED.sig',
  JAVA_TOOL_OPTIONS: '-Dsecret=ARGOS_SEED',
};

/** Repeats of every seed endpoint (WO-30: --repeat 2). */
export const SEED_REPEAT = 2;

/**
 * Service name of GET /api/hop/{id} (sample-apps HopController, Spring MVC pattern). The agent
 * keeps every profile of this service (slow threshold 0 for it alone), so the trace API finds
 * the caller HTTP steps of the hop traces; other services keep the default sampling.
 */
export const HOP_SERVICE = 'GET /api/hop/{id}';

/**
 * The real multi-hop traces of WO-14 (ADR-0010 C-207 (5), T-ACC-05 server side): GET
 * /api/hop/{id}?depth=N calls the app itself N times (each call carries traceparent and the argos
 * tracestate entry, propagation.argos-state.hosts=127.0.0.1), and the last hop calls ext-stub, with
 * `fail` selecting a read timeout, a refused connection or a 500 of that last call. `failure` is
 * the failure class the transaction detail reports for the last call (14 §4.6 step `failure`,
 * 5xx from the status), null for none.
 */
export const HOP_SCENARIOS = [
  { scenario: 'three_hop', depth: 2, fail: null, failure: null },
  { scenario: 'timeout', depth: 2, fail: 'timeout', failure: 'timeout' },
  { scenario: 'refuse', depth: 1, fail: 'refuse', failure: 'connect_fail' },
  { scenario: 'status500', depth: 1, fail: 'status500', failure: 'http_5xx' },
] as const;
export type HopScenario = (typeof HOP_SCENARIOS)[number]['scenario'];

/** Calls of every hop scenario (the resolution takes the first complete trace of each). */
export const HOP_REPEAT = 3;

/** One GET /api/hop/{id} call of the seed (host clock times around it). */
export interface HopCall {
  readonly scenario: HopScenario;
  readonly orderId: number;
  readonly depth: number;
  readonly sentAt: number;
  readonly doneAt: number;
  /** The app's answer, null when the call itself failed (`problem`). */
  readonly status: number | null;
  readonly problem: string | null;
}

/** The order id of call `repeat` of scenario `index` (unique per call: the trace's URL path). */
export function hopOrderId(index: number, repeat: number): number {
  return 900_000 + index * 100 + repeat;
}

/** The path and query of a hop call. */
export function hopPath(scenario: (typeof HOP_SCENARIOS)[number], orderId: number): string {
  const query = new URLSearchParams({ depth: String(scenario.depth), client: 'hurl' });
  if (scenario.fail !== null) {
    query.set('fail', scenario.fail);
  }
  return `/api/hop/${String(orderId)}?${query.toString()}`;
}

/** Sends the hop scenarios to the running shop-boot3, one call at a time. */
async function sendHops(shopUrl: string, log: Log): Promise<HopCall[]> {
  const calls: HopCall[] = [];
  for (let repeat = 0; repeat < HOP_REPEAT; repeat += 1) {
    for (const [index, scenario] of HOP_SCENARIOS.entries()) {
      const orderId = hopOrderId(index, repeat);
      const sentAt = Date.now();
      let status: number | null = null;
      let problem: string | null = null;
      try {
        // The longest chain waits for the 1 s read timeout of the last call (plus app start-up
        // of the first requests); 60 s is far above it.
        status = (await httpGet(`${shopUrl}${hopPath(scenario, orderId)}`, {}, 60_000)).status;
      } catch (error) {
        problem = error instanceof Error ? error.message : String(error);
      }
      calls.push({
        scenario: scenario.scenario,
        orderId,
        depth: scenario.depth,
        sentAt,
        doneAt: Date.now(),
        status,
        problem,
      });
    }
  }
  log(
    `hop traffic: ${calls.map((c) => `${c.scenario}#${String(c.orderId)}=${c.status === null ? String(c.problem) : String(c.status)}`).join(' ')}`,
  );
  return calls;
}

/**
 * Service name of GET /api/async/executor (sample-apps AsyncController, Spring MVC pattern). Like
 * the hop service, the agent keeps every profile of this service alone (slow threshold 0), so the
 * transaction detail of each seed call has its steps. `async.step` stays off: the async work shows
 * as the SQL steps of the request's transaction, and the step counts of other screens do not move.
 */
export const ASYNC_SERVICE = 'GET /api/async/executor';

/** Calls of GET /api/async/executor?work=sql (WO-15). */
export const ASYNC_REPEAT = 3;

/** Path and query of the async seed call (WO-15 plan §3: one SQL per async branch). */
export const ASYNC_PATH = '/api/async/executor?work=sql';

/**
 * Pause between two async seed calls. A call takes some tens of milliseconds, about as much as the
 * agent's transaction clock (start of its JVM's time base) and the host's `Date.now()` can differ
 * (Windows timer resolution about 16 ms), so back-to-back calls let a transaction's start fall
 * into its neighbour's interval. With this gap every start is nearest to its own call
 * (`asyncCallOf`).
 */
export const ASYNC_GAP_MS = 2500;

/**
 * One GET /api/async/executor?work=sql call of the seed (host clock times around it) with the
 * app's answer: `asyncSql` is the number of SQL statements the app ran off the request thread
 * (`@Async`, `supplyAsync`, the scheduled task, the app pool, the virtual-thread-per-task executor
 * on JDK 21+, each its own statement), `virtualPerTaskThread` the thread name of the last branch
 * (JDK 21+, null otherwise or when absent).
 */
export interface AsyncCall {
  readonly sentAt: number;
  readonly doneAt: number;
  /** The app's answer, null when the call itself failed (`problem`). */
  readonly status: number | null;
  readonly asyncSql: number | null;
  readonly virtualPerTaskThread: string | null;
  /** Why the call has no usable answer, null when it has one. */
  readonly problem: string | null;
}

/**
 * Reads the async answer of the sample app contract (WO-15 plan §3): a JSON object with a
 * non-negative integer `asyncSql` and, on JDK 21+, a string `virtualPerTaskThread`.
 */
export function parseAsyncAnswer(text: string): {
  asyncSql: number | null;
  virtualPerTaskThread: string | null;
  problem: string | null;
} {
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    return { asyncSql: null, virtualPerTaskThread: null, problem: 'the answer is not JSON' };
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return { asyncSql: null, virtualPerTaskThread: null, problem: 'the answer is not an object' };
  }
  const record = body as Record<string, unknown>;
  const thread =
    typeof record.virtualPerTaskThread === 'string' ? record.virtualPerTaskThread : null;
  const asyncSql = record.asyncSql;
  if (typeof asyncSql !== 'number' || !Number.isInteger(asyncSql) || asyncSql < 0) {
    return {
      asyncSql: null,
      virtualPerTaskThread: thread,
      problem: `the answer has no asyncSql count (${JSON.stringify(asyncSql ?? null)}; sample app contract of WO-15)`,
    };
  }
  return { asyncSql, virtualPerTaskThread: thread, problem: null };
}

/**
 * The async call a transaction that started at `ts` belongs to: the call whose [sentAt, doneAt]
 * holds the start, else the nearest call within `slackMs` (the host and agent clocks are the same
 * machine's; the slack covers rounding and their small difference). Calls are sent one at a time,
 * `ASYNC_GAP_MS` apart, so a start never lies nearer to another call.
 */
export function asyncCallOf<T extends Pick<AsyncCall, 'sentAt' | 'doneAt'>>(
  ts: string | number,
  calls: readonly T[],
  slackMs = 1000,
): T | undefined {
  const at = typeof ts === 'number' ? ts : Date.parse(ts);
  if (!Number.isFinite(at)) {
    return undefined;
  }
  const holding = calls.find((c) => c.sentAt <= at && at <= c.doneAt);
  if (holding !== undefined) {
    return holding;
  }
  let best: T | undefined;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const call of calls) {
    const distance = at < call.sentAt ? call.sentAt - at : at - call.doneAt;
    if (distance <= slackMs && distance < bestDistance) {
      best = call;
      bestDistance = distance;
    }
  }
  return best;
}

/** The async calls as the seed manifest records them (`async_calls`, ISO-8601 with milliseconds). */
export function manifestAsyncCalls(calls: readonly AsyncCall[]): ManifestAsyncCall[] {
  return calls.map((c) => ({
    sent_at: new Date(c.sentAt).toISOString(),
    done_at: new Date(c.doneAt).toISOString(),
    status: c.status,
    async_sql: c.asyncSql,
    virtual_per_task_thread: c.virtualPerTaskThread,
    problem: c.problem,
  }));
}

/**
 * Agent properties that keep every profile of the hop and async services (slow threshold 0 for
 * those services alone; other services keep the default sampling).
 */
export function profileKeptProperties(): string[] {
  return [HOP_SERVICE, ASYNC_SERVICE].map(
    (service) => `-Dargos.sampling.profile.slow-threshold-ms.services.${service}=0`,
  );
}

/** Sends the async seed calls to the running shop-boot3, one call at a time. */
async function sendAsyncCalls(shopUrl: string, log: Log): Promise<AsyncCall[]> {
  const calls: AsyncCall[] = [];
  for (let repeat = 0; repeat < ASYNC_REPEAT; repeat += 1) {
    if (repeat > 0) {
      await sleep(ASYNC_GAP_MS);
    }
    const sentAt = Date.now();
    let status: number | null = null;
    let answer: ReturnType<typeof parseAsyncAnswer> = {
      asyncSql: null,
      virtualPerTaskThread: null,
      problem: null,
    };
    try {
      // Every branch waits at most 10 s in the app (ExecutorProbe); 60 s is far above it.
      const response = await httpGet(`${shopUrl}${ASYNC_PATH}`, {}, 60_000);
      status = response.status;
      answer =
        status === 200
          ? parseAsyncAnswer(response.text)
          : { ...answer, problem: `HTTP ${String(status)}` };
    } catch (error) {
      answer = { ...answer, problem: error instanceof Error ? error.message : String(error) };
    }
    calls.push({ sentAt, doneAt: Date.now(), status, ...answer });
  }
  log(
    `async traffic: ${calls.map((c) => (c.problem === null ? `asyncSql=${String(c.asyncSql)}` : `problem=${c.problem}`)).join(' ')}`,
  );
  return calls;
}

export interface TrafficJars {
  readonly agent: string;
  readonly shop: string;
  readonly extStub: string;
  readonly smoke: string;
}

export interface TrafficTarget {
  readonly runId: string;
  readonly baseUrl: string;
  readonly grpcPort: number;
  readonly postgresContainer: string;
  /** An authenticated API session (admin) to watch the ingestion. */
  readonly api: ApiSession;
  /**
   * Runs once the seed traffic is queryable, while shop-boot3 and its agent still run (WO-22: the
   * collection outage through `live.proxy`, overlapped with the manifest resolution). The
   * processes and the proxy stop when it settles.
   */
  readonly whileRunning?: (traffic: TrafficResult, live: LiveTraffic) => Promise<void>;
}

/** The running traffic seed handed to `whileRunning`. */
export interface LiveTraffic {
  /** The TCP proxy the agent reaches the gRPC port through (e2e/stack/seed/grpcProxy.ts). */
  readonly proxy: GrpcProxy;
  readonly instanceName: string;
}

export interface TrafficResult {
  readonly appId: number;
  readonly start: string;
  readonly end: string;
  readonly seedRequests: number;
  readonly total: number;
  readonly xssService: string | null;
  /** The GET /api/hop/{id} calls (WO-14 multi-hop traces), in the order sent. */
  readonly hops: readonly HopCall[];
  /** The GET /api/async/executor?work=sql calls (WO-15), in the order sent. */
  readonly asyncCalls: readonly AsyncCall[];
}

/**
 * SQL (psql stdin) that creates an app (default: the traffic app) and its ACTIVE token row. The app
 * is created idempotently; WO-35's active feed (e2e/stack/seed/activeFeed.ts) passes its own app.
 */
export function tokenSql(
  tokenHash: string,
  tokenPrefix: string,
  app: { readonly name: string; readonly env: string } = TRAFFIC_APP,
): string {
  if (!/^[0-9a-f]{64}$/.test(tokenHash) || !/^argos_[A-Za-z0-9_-]{8}$/.test(tokenPrefix)) {
    throw new Error('unexpected token hash or prefix');
  }
  if (!/^[A-Za-z0-9._-]{1,64}$/.test(app.name) || !/^[A-Za-z0-9._-]{1,32}$/.test(app.env)) {
    throw new Error('unexpected app name or environment');
  }
  return [
    `INSERT INTO argos.apps (name, env) VALUES ('${app.name}', '${app.env}') ON CONFLICT (name, env) DO NOTHING;`,
    'INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, expires_at, grace_until, revoked_at, status)',
    `  SELECT gen_random_uuid(), id, '${tokenHash}', '${tokenPrefix}', NULL, NULL, NULL, 'ACTIVE'`,
    `  FROM argos.apps WHERE name = '${app.name}' AND env = '${app.env}';`,
    '',
  ].join('\n');
}

/**
 * A deploy event the stack seeds for the primary generator app (WO-33: the XLog event markers and
 * docs/15 §4.3 deep link 4, `alert=<event id>`). Deploy marker creation is WO-26 (ADR-0010 C-153),
 * so the rows go straight into PostgreSQL `argos.events`, next to the traffic app's token rows.
 */
export interface SeedEvent {
  readonly event_id: string;
  readonly app_id: number;
  readonly type: 'deploy';
  readonly title: string;
  readonly version: string | null;
  readonly ts: string;
  /** The title is an XSS seed payload (docs/18 §4.3): it must render verbatim as text. */
  readonly xss: boolean;
}

/** The XSS payload of the second seed event's title (docs/18 §4.3, Seeds.XSS[0]). */
export const SEED_EVENT_XSS_TITLE = '<img src=x onerror="window.__xss=1">ARGOS_SEED';

/**
 * The two seed events of `appId`: at a quarter and at half of the generator range [start, end)
 * (whole seconds), so their positions follow the range's today-UTC anchor (ADR-0010 C-170) and they
 * stay far apart (separate markers) and outside the windows near the range end that other checks
 * open. The first has a plain title and a version, the second an XSS payload as its title.
 */
export function seedEventsOf(
  appId: number,
  range: { readonly start: string; readonly end: string },
  ids: readonly [string, string],
): SeedEvent[] {
  const start = Date.parse(range.start);
  const end = Date.parse(range.end);
  if (!(start < end)) {
    throw new Error(`generator range ${range.start} .. ${range.end} is empty`);
  }
  const at = (fraction: number): string => isoSeconds(start + (end - start) * fraction);
  return [
    {
      event_id: ids[0],
      app_id: appId,
      type: 'deploy',
      title: 'Deploy e2e-1.4.2',
      version: 'e2e-1.4.2',
      ts: at(0.25),
      xss: false,
    },
    {
      event_id: ids[1],
      app_id: appId,
      type: 'deploy',
      title: SEED_EVENT_XSS_TITLE,
      version: null,
      ts: at(0.5),
      xss: true,
    },
  ];
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const ISO_SECONDS = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

/** A SQL string literal (standard_conforming_strings: only the quote is doubled). */
function sqlText(value: string): string {
  if (value.includes('\u0000')) {
    throw new Error('a seed text contains NUL');
  }
  return `'${value.replaceAll("'", "''")}'`;
}

/** SQL (psql stdin) that inserts the seed events of the primary app in its environment. */
export function eventsSql(env: string, events: readonly SeedEvent[]): string {
  if (!/^[A-Za-z0-9._-]{1,32}$/.test(env)) {
    throw new Error('unexpected environment name');
  }
  const rows = events.map((e) => {
    if (!UUID.test(e.event_id) || !ISO_SECONDS.test(e.ts) || !Number.isInteger(e.app_id)) {
      throw new Error('unexpected seed event id, time or app');
    }
    return `  ('${e.event_id}', ${String(e.app_id)}, '${env}', NULL, '${e.type}', ${sqlText(e.title)}, NULL, ${e.version === null ? 'NULL' : sqlText(e.version)}, '${e.ts}', NULL)`;
  });
  return [
    'INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, version, ts, created_by) VALUES',
    `${rows.join(',\n')};`,
    '',
  ].join('\n');
}

/** Inserts the seed events through psql in the stack's PostgreSQL container. */
export async function seedEvents(
  postgresContainer: string,
  env: string,
  events: readonly SeedEvent[],
): Promise<void> {
  await run(
    'docker',
    [
      'exec',
      '-i',
      postgresContainer,
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
    { input: eventsSql(env, events), timeoutMs: 60_000 },
  );
}

export function startProcess(
  command: string,
  args: readonly string[],
  env: Readonly<Record<string, string>>,
  logFile: string,
): ChildProcess {
  const out = createWriteStream(logFile, { flags: 'a' });
  // The suite's own ARGOS_E2E_* switches are not for the app: the agent warns about unknown
  // ARGOS_* variables.
  const inherited = Object.fromEntries(
    Object.entries(process.env).filter(([key]) => !key.startsWith('ARGOS_E2E_')),
  );
  const child = spawn(command, [...args], {
    env: { ...inherited, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  child.stdout.pipe(out);
  child.stderr.pipe(out);
  return child;
}

/** Whether `child` has neither exited nor been ended by a signal (read anew on every call). */
function running(child: ChildProcess): boolean {
  return child.exitCode === null && child.signalCode === null;
}

export async function stopProcess(child: ChildProcess | null): Promise<void> {
  if (child === null || !running(child)) {
    return;
  }
  const exited = new Promise<void>((resolve) => {
    child.once('exit', () => {
      resolve();
    });
  });
  child.kill();
  await Promise.race([exited, sleep(15_000)]);
  if (running(child)) {
    child.kill('SIGKILL');
  }
}

interface AppRow {
  app_id: number;
  name: string;
  env: string;
  instances_up: number;
}

async function findApp(api: ApiSession): Promise<AppRow | undefined> {
  const apps = await api.query<AppRow[]>('/apps');
  return apps.data.find((a) => a.name === TRAFFIC_APP.name && a.env === TRAFFIC_APP.env);
}

/** meta.total of the app's XLog (all kinds) in [from, to). */
export async function xlogTotal(
  api: ApiSession,
  appId: number,
  from: string,
  to: string,
  kind = 'ALL',
): Promise<number> {
  const response = await api.query(`/apps/${String(appId)}/xlog`, {
    from,
    to,
    kind,
    limit: 1,
  });
  const total = response.meta.total;
  if (typeof total !== 'number') {
    throw new Error(`GET /apps/${String(appId)}/xlog: meta.total is ${JSON.stringify(total)}`);
  }
  return total;
}

/** ISO-8601 UTC at whole seconds (the API takes ...Z only). */
export function isoSeconds(ms: number): string {
  return new Date(Math.floor(ms / 1000) * 1000).toISOString().replace('.000Z', 'Z');
}

/** Runs the traffic seed. */
export async function seedTraffic(
  target: TrafficTarget,
  jars: TrafficJars,
  log: Log,
): Promise<TrafficResult> {
  const logs = logsDir(target.runId);
  mkdirSync(logs, { recursive: true });
  const token = newAgentToken();
  const tokenFile = writeSecret(secretsDir(target.runId), 'agent.token', token);
  await run(
    'docker',
    [
      'exec',
      '-i',
      target.postgresContainer,
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
    { input: tokenSql(sha256Hex(token), token.slice(0, 14)), secrets: [token], timeoutMs: 60_000 },
  );
  log(`traffic app ${TRAFFIC_APP.name}/${TRAFFIC_APP.env} and its agent token provisioned`);

  const java = javaExecutable();
  let extStub: ChildProcess | null = null;
  let shop: ChildProcess | null = null;
  // The agent reaches the gRPC port through a TCP proxy, so that the collection outage of WO-22
  // can cut it without stopping the process (e2e/stack/seed/outage.ts).
  const proxy = new GrpcProxy({ targetPort: target.grpcPort });
  try {
    const proxyPort = await proxy.start();
    const extPort = await freePort();
    extStub = startProcess(
      java,
      ['-jar', jars.extStub, '--port', String(extPort), '--bind', '127.0.0.1'],
      {},
      join(logs, 'ext-stub.log'),
    );
    const shopPort = await freePort();
    const shopUrl = `http://127.0.0.1:${String(shopPort)}`;
    shop = startProcess(
      java,
      [
        `-javaagent:${jars.agent}`,
        `-Dargos.server.address=127.0.0.1:${String(proxyPort)}`,
        '-Dargos.server.tls=false',
        `-Dargos.server.token-file=${tokenFile}`,
        `-Dargos.app.name=${TRAFFIC_APP.name}`,
        `-Dargos.app.env=${TRAFFIC_APP.env}`,
        `-Dargos.app.instance=${TRAFFIC_INSTANCE}`,
        // WO-14: the argos tracestate entry (caller app id) on calls to 127.0.0.1 (SEC-38: none
        // by default), so the trace API links the hop callees to their callers; every profile of
        // the hop service is kept (its caller HTTP steps), other services sample as before.
        '-Dargos.propagation.argos-state.hosts=127.0.0.1',
        // WO-15: every profile of the async service is kept as well (its async SQL steps).
        ...profileKeptProperties(),
        '-jar',
        jars.shop,
        `--server.port=${String(shopPort)}`,
        '--server.address=127.0.0.1',
      ],
      { ...SEED_ENVIRONMENT, SAMPLE_EXT_URL: `http://127.0.0.1:${String(extPort)}` },
      join(logs, 'shop-boot3.log'),
    );
    const exited = (name: string, child: ChildProcess): void => {
      if (child.exitCode !== null) {
        throw new Error(`${name} exited with ${String(child.exitCode)} (log: ${logs})`);
      }
    };
    await waitFor(
      async () => {
        exited('ext-stub', extStub as ChildProcess);
        exited('shop-boot3', shop as ChildProcess);
        const response = await httpGet(`${shopUrl}/api/version`);
        return response.status === 200 ? true : undefined;
      },
      { timeoutMs: 180_000, intervalMs: 1000, what: `shop-boot3 at ${shopUrl}` },
    );
    const app = await waitFor(
      async () => {
        exited('shop-boot3', shop as ChildProcess);
        const row = await findApp(target.api);
        return row !== undefined && row.instances_up >= 1 ? row : undefined;
      },
      {
        timeoutMs: 120_000,
        intervalMs: 2000,
        what: 'the agent session of shop-boot3 (GET /apps instances_up)',
      },
    );
    log(`shop-boot3 up at ${shopUrl}, agent session open (app ${String(app.app_id)})`);

    const start = Date.now() - 60_000;
    const reportDir = join(runDir(target.runId), 'seed-traffic');
    const seed = await bashScript(
      'test/seed/seed-traffic.sh',
      [
        '--url',
        shopUrl,
        '--repeat',
        String(SEED_REPEAT),
        '--no-build',
        '--report-dir',
        reportDir,
        '--smoke-jar',
        jars.smoke,
        '--java',
        java,
      ],
      { allowFailure: true, timeoutMs: 10 * 60_000, secrets: [token] },
    );
    if (seed.code !== 0) {
      throw new Error(
        `test/seed/seed-traffic.sh exited with ${String(seed.code)}: ${seed.stdout.slice(-2000)}${seed.stderr.slice(-2000)}`,
      );
    }
    // One result per call: the catalog's seed entries times --repeat.
    const report = JSON.parse(readFileSync(join(reportDir, 'smoke.json'), 'utf8')) as {
      summary?: { passed?: number };
      results?: { id?: string }[];
    };
    const seedRequests = report.summary?.passed ?? 0;
    const entries = new Set((report.results ?? []).map((r) => r.id)).size;
    log(
      `seed-traffic.sh: ${String(seedRequests)} seed calls (${String(entries)} seed endpoints x ${String(SEED_REPEAT)})`,
    );
    // WO-14: real multi-hop traces (self calls through the HTTP client, ending at ext-stub), sent
    // before the stability wait so that the traffic window's totals include them.
    const hops = await sendHops(shopUrl, log);
    // WO-15: async work handed to executors inside one request (one SQL per branch).
    const asyncCalls = await sendAsyncCalls(shopUrl, log);

    // Queryable: the XSS seed service is in the service statistics and the XLog total is stable.
    let last = -1;
    let stable = 0;
    let xssService: string | null = null;
    const total = await waitFor(
      async () => {
        const to = isoSeconds(Date.now() + 60_000);
        const current = await xlogTotal(target.api, app.app_id, isoSeconds(start), to);
        const services = await target.api.query<{ service: string | null }[]>(
          `/apps/${String(app.app_id)}/services/stats`,
          { from: isoSeconds(start), to, limit: 500 },
        );
        // The smallest matching name, so the same seed records the same service every run.
        xssService =
          services.data
            .map((s) => s.service)
            .filter((name): name is string => name?.includes('/seed/xss') === true)
            .sort()[0] ?? null;
        stable = current === last && current > 0 ? stable + 1 : 0;
        last = current;
        return stable >= 2 && current >= seedRequests && xssService !== null ? current : undefined;
      },
      {
        timeoutMs: 180_000,
        intervalMs: 3000,
        what: 'the seed traffic in the query API (stable XLog total, XSS seed service)',
      },
    );
    log(
      `seed traffic queryable: ${String(total)} transactions, XSS seed service ${String(xssService)}`,
    );
    await stopProcess(extStub);
    extStub = null;
    const result: TrafficResult = {
      appId: app.app_id,
      start: isoSeconds(start),
      end: isoSeconds(Date.now() + 60_000),
      seedRequests,
      total,
      xssService,
      hops,
      asyncCalls,
    };
    if (target.whileRunning !== undefined) {
      await target.whileRunning(result, { proxy, instanceName: TRAFFIC_INSTANCE });
    }
    await stopProcess(shop);
    shop = null;
    return result;
  } finally {
    await stopProcess(shop);
    await stopProcess(extStub);
    await proxy.close();
  }
}
