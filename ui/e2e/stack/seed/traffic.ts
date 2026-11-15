// SPDX-License-Identifier: Apache-2.0
//
// Seed (b): slice traffic through the real collection path (docs/18 §13, SEC-62): shop-boot3 with
// the agent jar, pointed at the stack's gRPC port, then test/seed/seed-traffic.sh (the PII/XSS seed
// endpoints of docs/18 §4.3, sentinel ARGOS_SEED) and a wait until the data is queryable through
// the API. The agent token is provisioned exactly as WO-21's integration tests do it
// (AgentTokens): `argos_` + base64url(32 random bytes), only its SHA-256 and 14-character prefix
// in argos.agent_tokens for an existing app (the slice has no issuance API, WO-26). The token text
// exists only in a file of the run's secrets directory and is never printed.
//
// Host processes (ext-stub, shop-boot3) listen on 127.0.0.1 random ports (never 8080) and are
// stopped as soon as the data is in: afterwards the window totals stay fixed.
import { spawn, type ChildProcess } from 'node:child_process';
import { createWriteStream, mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { ApiSession } from '../api.ts';
import { run } from '../docker.ts';
import { logsDir, runDir, secretsDir } from '../paths.ts';
import { newAgentToken, sha256Hex, writeSecret } from '../secrets.ts';
import type { Log } from '../stack.ts';
import { freePort, httpGet, sleep, waitFor } from '../wait.ts';

import { bashScript, javaExecutable } from './gradle.ts';

/** App of the slice traffic (agent app.name / app.env; letters, digits, '.', '_', '-' only). */
export const TRAFFIC_APP = { name: 'e2e-shop', env: 'e2e' } as const;

/** Seed environment of the app process (sample-apps/common Seeds.environment()). */
export const SEED_ENVIRONMENT: Readonly<Record<string, string>> = {
  DB_PASSWORD: 'ARGOS_SEED_pw',
  AWS_SECRET_ACCESS_KEY: 'ARGOS_SEEDAKIAEXAMPLEONLY',
  ARGOS_TOKEN: 'eyJhbGciOi.ARGOS_SEED.sig',
  JAVA_TOOL_OPTIONS: '-Dsecret=ARGOS_SEED',
};

/** Repeats of every seed endpoint (WO-30: --repeat 2). */
export const SEED_REPEAT = 2;

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
}

export interface TrafficResult {
  readonly appId: number;
  readonly start: string;
  readonly end: string;
  readonly seedRequests: number;
  readonly total: number;
  readonly xssService: string | null;
}

/** SQL (psql stdin) that creates the traffic app and its ACTIVE token row. */
export function tokenSql(tokenHash: string, tokenPrefix: string): string {
  if (!/^[0-9a-f]{64}$/.test(tokenHash) || !/^argos_[A-Za-z0-9_-]{8}$/.test(tokenPrefix)) {
    throw new Error('unexpected token hash or prefix');
  }
  return [
    `INSERT INTO argos.apps (name, env) VALUES ('${TRAFFIC_APP.name}', '${TRAFFIC_APP.env}') ON CONFLICT (name, env) DO NOTHING;`,
    'INSERT INTO argos.agent_tokens (id, app_id, token_hash, token_prefix, expires_at, grace_until, revoked_at, status)',
    `  SELECT gen_random_uuid(), id, '${tokenHash}', '${tokenPrefix}', NULL, NULL, NULL, 'ACTIVE'`,
    `  FROM argos.apps WHERE name = '${TRAFFIC_APP.name}' AND env = '${TRAFFIC_APP.env}';`,
    '',
  ].join('\n');
}

function startProcess(
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

async function stopProcess(child: ChildProcess | null): Promise<void> {
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
  try {
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
        `-Dargos.server.address=127.0.0.1:${String(target.grpcPort)}`,
        '-Dargos.server.tls=false',
        `-Dargos.server.token-file=${tokenFile}`,
        `-Dargos.app.name=${TRAFFIC_APP.name}`,
        `-Dargos.app.env=${TRAFFIC_APP.env}`,
        '-Dargos.app.instance=e2e-shop-1',
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
    await stopProcess(shop);
    shop = null;
    await stopProcess(extStub);
    extStub = null;
    return {
      appId: app.app_id,
      start: isoSeconds(start),
      end: isoSeconds(Date.now() + 60_000),
      seedRequests,
      total,
      xssService,
    };
  } finally {
    await stopProcess(shop);
    await stopProcess(extStub);
  }
}
