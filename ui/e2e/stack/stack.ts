// SPDX-License-Identifier: Apache-2.0
//
// The E2E stack (docs/18 §13, WO-30): PostgreSQL, ClickHouse and the Argos server built from this
// tree, each a Docker container labelled io.argos.e2e=<run id> with the name prefix argos-e2e-, on a
// network of their own, published on 127.0.0.1 only (random ports; never 8080 or 9100 of the host).
//
// - PostgreSQL: deploy/postgres/image.properties, bootstrap superuser `postgres`, then
//   deploy/postgres/roles.sql run with psql as the installer does (passwords as psql variables, given
//   on stdin so that they appear on no command line).
// - ClickHouse: deploy/clickhouse/image.properties with config.d/argos.xml and users.d/argos.xml
//   mounted read-only, the account passwords as the SHA-256 from_env variables of users.d (an env
//   file, like Compose's secrets/clickhouse.env), the RAM variables of a 2 GiB container
//   (test/storage/clickhouse-support MemoryBudget proportions).
// - Server: server/build/libs/argos-server-<version>.jar (with ui/dist under static/) on the Jib base
//   JRE (ServerImage.BASE_IMAGE) as uid 10001, configured through the documented short names and
//   *_FILE secrets (17 §3.8, ADR-0010 C-96) and canonical keys: unattended administrator
//   (ARGOS_ADMIN_USERNAME, ARGOS_ADMIN_PASSWORD_FILE, ARGOS_IP_MODE=masked; WO-24), the public URL =
//   the URL the browser uses (http://127.0.0.1:<port>; Chromium treats it as a secure context, so the
//   Secure session cookies work and cookie.secure stays true), plaintext gRPC only through the
//   collector's test settings (tls.enabled=false + allow-plaintext, as WO-21's tests do), the
//   management port published on 127.0.0.1 for readiness and the CSP violation counter.
// Readiness = /actuator/health/readiness UP (its group includes the migrations, the database pool,
// ClickHouse and the ingest dispatcher).
// Memory limits: ClickHouse 2 GiB + PostgreSQL 0.5 GiB + server 1.25 GiB = 3.75 GiB, plus the Linux
// Playwright container of e2e:docker (PLAYWRIGHT_MEMORY_BYTES, 1.25 GiB) = 5 GiB per run at most.
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import {
  containerLogs,
  docker,
  NAME_PREFIX,
  publishedPort,
  removeRun,
  run,
  STACK_LABEL,
} from './docker.ts';
import { stackImages } from './images.ts';
import { snapshotJars, stackJars, type StackJars } from './jars.ts';
import {
  cspControlFile,
  jarsDir,
  logsDir,
  MANIFEST_FILE,
  REPO_ROOT,
  runDir,
  secretsDir,
  STACK_DIR,
} from './paths.ts';
import {
  DETACHED_OWNER,
  processAlive,
  readRunState,
  removeRunState,
  writeRunState,
  type RunState,
} from './runState.ts';
import {
  createStackSecrets,
  loadStackSecrets,
  SECRET_FILES,
  secretValues,
  sha256Hex,
  type StackSecrets,
} from './secrets.ts';
import { SESSION_FILE } from './session.ts';
import { FatalWait, freePort, httpGet, waitFor } from './wait.ts';

const GIB = 1024 * 1024 * 1024;
export const CLICKHOUSE_MEMORY_BYTES = 2 * GIB;
export const POSTGRES_MEMORY_BYTES = 0.5 * GIB;
/** The server runs with -Xmx768m; heap plus metaspace, code cache and threads stay below this. */
export const SERVER_MEMORY_BYTES = 1.25 * GIB;
/** The Linux Playwright container of e2e:docker (Chromium with one page, Node.js). */
export const PLAYWRIGHT_MEMORY_BYTES = 1.25 * GIB;

/** The unattended administrator (17 §6.4 default name). */
export const ADMIN_USERNAME = 'admin';

/** Mount point of the per-run secrets directory in the PostgreSQL and server containers. */
const SECRETS_MOUNT = '/run/argos-secrets';

/**
 * The login rate limit of the E2E server (argos.ratelimit.login-per-ip-per-minute, default 20): the
 * suite logs in once per test (fresh sessions keep tests independent, and refresh-token rotation
 * makes shared sessions unsafe), all from one address. The limit itself is tested by WO-24's
 * securityTest, not here.
 */
export const E2E_LOGIN_RATE_PER_MINUTE = 600;

export interface StartedStack {
  readonly state: RunState;
  readonly secrets: StackSecrets;
  readonly jars: StackJars;
}

export type Log = (line: string) => void;

/** Names of the objects of a run. */
export function stackNames(runId: string): {
  network: string;
  postgres: string;
  clickhouse: string;
  server: string;
} {
  return {
    network: `${NAME_PREFIX}${runId}`,
    postgres: `${NAME_PREFIX}${runId}-pg`,
    clickhouse: `${NAME_PREFIX}${runId}-ch`,
    server: `${NAME_PREFIX}${runId}-server`,
  };
}

/** The RAM substitution variables of deploy/clickhouse for a container limit (MemoryBudget). */
export function clickHouseMemoryEnv(limitBytes: number): Record<string, string> {
  const server = Math.floor(limitBytes / 10) * 9;
  return {
    ARGOS_CH_MAX_SERVER_MEMORY_USAGE: String(server),
    ARGOS_CH_READER_MAX_MEMORY_USAGE: String(Math.floor(server / 5) * 2),
    ARGOS_CH_WRITER_MAX_MEMORY_USAGE: String(Math.floor(server / 15) * 4),
  };
}

/** The psql script (stdin) that runs roles.sql with the passwords as psql variables. */
export function rolesScript(secrets: StackSecrets): string {
  const set = (name: string, value: string): string => {
    if (!/^[A-Za-z0-9_-]+$/.test(value)) {
      throw new Error(`${name}: generated password has unexpected characters`);
    }
    return `\\set ${name} '${value}'\n`;
  };
  return (
    set('owner_password', secrets.pgOwner) +
    set('app_password', secrets.pgApp) +
    set('audit_password', secrets.pgAudit) +
    set('backup_password', secrets.pgBackup) +
    '\\set owner_role argos\n' +
    '\\i /argos-deploy/roles.sql\n'
  );
}

/** Environment of the server container (documented short names and *_FILE secrets). */
export function serverEnvironment(runId: string, port: number): Record<string, string> {
  const names = stackNames(runId);
  const secret = (key: keyof StackSecrets): string => `${SECRETS_MOUNT}/${SECRET_FILES[key]}`;
  return {
    HOME: '/data',
    LOGGING_FILE_PATH: '/logs/server',
    SERVER_PORT: String(port),
    // The management port listens on every interface of the container so that Docker can publish
    // it, on the host's 127.0.0.1 only (02 C-7 keeps it off the public interface).
    MANAGEMENT_SERVER_ADDRESS: '0.0.0.0',
    ARGOS_LOG_LEVEL: 'INFO',
    ARGOS_PG_URL: `jdbc:postgresql://${names.postgres}:5432/argos`,
    ARGOS_PG_PASSWORD_FILE: secret('pgApp'),
    ARGOS_PG_OWNER_PASSWORD_FILE: secret('pgOwner'),
    ARGOS_CH_URL: `http://${names.clickhouse}:8123`,
    ARGOS_CH_WRITER_PASSWORD_FILE: secret('chWriter'),
    ARGOS_CH_READER_PASSWORD_FILE: secret('chReader'),
    ARGOS_CH_MIGRATOR_PASSWORD_FILE: secret('chMigrator'),
    ARGOS_ADMIN_USERNAME: ADMIN_USERNAME,
    ARGOS_ADMIN_PASSWORD_FILE: secret('adminInitial'),
    ARGOS_IP_MODE: 'masked',
    ARGOS_JWT_KEY_FILE: '/data/jwt.key',
    ARGOS_WAL_DIR: '/data/wal',
  };
}

/** Command line of the server process (canonical keys that have no short name). */
export function serverArguments(publicUrl: string): string[] {
  return [
    'java',
    '-Xmx768m',
    '-XX:+ExitOnOutOfMemoryError',
    '-Djava.io.tmpdir=/data',
    '-jar',
    '/opt/argos/argos-server.jar',
    `--argos.web.public-url=${publicUrl}`,
    '--argos.auth.setup-token-file=/data/setup-token',
    '--argos.collector.tls.enabled=false',
    '--argos.collector.allow-plaintext=true',
    `--argos.metrics.token-file=${SECRETS_MOUNT}/${SECRET_FILES.metricsToken}`,
    '--argos.metrics.allow-cidrs=0.0.0.0/0,::/0',
    `--argos.ratelimit.login-per-ip-per-minute=${String(E2E_LOGIN_RATE_PER_MINUTE)}`,
  ];
}

function envFile(path: string, values: Readonly<Record<string, string>>): string {
  writeFileSync(
    path,
    Object.entries(values)
      .map(([k, v]) => `${k}=${v}\n`)
      .join(''),
    { encoding: 'utf8', mode: 0o600 },
  );
  return path;
}

function labelArgs(runId: string): string[] {
  return ['--label', `${STACK_LABEL}=${runId}`];
}

function memoryArgs(bytes: number): string[] {
  return ['--memory', String(bytes), '--memory-swap', String(bytes)];
}

function bind(source: string, target: string): string[] {
  return ['--mount', `type=bind,source=${source},target=${target},readonly`];
}

/**
 * Removes the containers of an earlier run whose owner process is gone (a run killed before its
 * teardown). Objects of a live run, or of any other label, are never touched.
 */
export async function removeLeftovers(log: Log): Promise<void> {
  const previous = readRunState();
  if (previous === null || previous.reused) {
    pruneRunDirectories(null);
    return;
  }
  if (previous.owner_pid === DETACHED_OWNER) {
    throw new Error(
      `The stack ${previous.run_id} started by "node e2e/stack/cli.ts up" is running at ${previous.stack_url}: reuse it with ARGOS_E2E_STACK_URL=${previous.stack_url}, or remove it with "node e2e/stack/cli.ts down"`,
    );
  }
  if (previous.owner_pid !== process.pid && processAlive(previous.owner_pid)) {
    throw new Error(
      `Another E2E stack (${previous.run_id}, process ${String(previous.owner_pid)}) is running from this working tree: wait for it or stop it (node e2e/stack/cli.ts down)`,
    );
  }
  const removed = await removeRun(previous.run_id);
  if (removed.length > 0) {
    log(`removed leftovers of ${previous.run_id}: ${removed.join(', ')}`);
  }
  removeRunState();
  pruneRunDirectories(null);
}

/**
 * Deletes the directories of earlier runs below ui/e2e/.stack (their logs and any secrets left by
 * a crash), except `keep`, and the manifest and session of an earlier run.
 */
export function pruneRunDirectories(keep: string | null): void {
  if (!existsSync(STACK_DIR)) {
    return;
  }
  for (const entry of readdirSync(STACK_DIR, { withFileTypes: true })) {
    if (
      entry.isDirectory() &&
      /^(e2e-)?\d{8}-\d{6}-[0-9a-f]{4}$/.test(entry.name) &&
      entry.name !== keep
    ) {
      rmSync(join(STACK_DIR, entry.name), { recursive: true, force: true });
    }
  }
  rmSync(MANIFEST_FILE, { force: true });
  rmSync(SESSION_FILE, { force: true });
}

async function waitPostgres(name: string, log: Log): Promise<void> {
  await waitFor(
    async () => {
      await assertRunning(name);
      // TCP, not the socket: the image's init phase runs a socket-only temporary server.
      const result = await run(
        'docker',
        [
          'exec',
          name,
          'pg_isready',
          '-h',
          '127.0.0.1',
          '-p',
          '5432',
          '-U',
          'postgres',
          '-d',
          'argos',
        ],
        { allowFailure: true, timeoutMs: 10_000 },
      );
      return result.code === 0 ? true : undefined;
    },
    { timeoutMs: 120_000, what: `PostgreSQL ${name}`, intervalMs: 1000 },
  );
  log(`PostgreSQL ${name} accepts connections`);
}

async function assertRunning(name: string): Promise<void> {
  const status = await docker(['inspect', '-f', '{{.State.Status}}', name], { allowFailure: true });
  if (status !== 'running') {
    throw new FatalWait(
      `container ${name} is ${status === '' ? 'gone' : status}:\n${await containerLogs(name, 80)}`,
    );
  }
}

/** Starts the stack of a new run and writes run.json. */
export async function startStack(runId: string, log: Log): Promise<StartedStack> {
  const timings: Record<string, number> = {};
  const started = Date.now();
  const built = stackJars();
  for (const warning of built.warnings) {
    log(`WARNING ${warning}`);
  }
  const jars = snapshotJars(built, jarsDir(runId));
  const images = stackImages();
  const names = stackNames(runId);
  const secretDir = secretsDir(runId);
  mkdirSync(logsDir(runId), { recursive: true });
  const secrets = createStackSecrets(secretDir);
  const redact = secretValues(secrets);

  await docker(['network', 'create', ...labelArgs(runId), names.network]);

  // PostgreSQL ---------------------------------------------------------------------------------
  await docker([
    'run',
    '-d',
    '--name',
    names.postgres,
    ...labelArgs(runId),
    '--network',
    names.network,
    ...memoryArgs(POSTGRES_MEMORY_BYTES),
    '-e',
    'POSTGRES_USER=postgres',
    '-e',
    `POSTGRES_PASSWORD_FILE=${SECRETS_MOUNT}/${SECRET_FILES.pgSuperuser}`,
    '-e',
    'POSTGRES_DB=argos',
    ...bind(secretDir, SECRETS_MOUNT),
    ...bind(join(REPO_ROOT, 'deploy', 'postgres'), '/argos-deploy'),
    '-p',
    '127.0.0.1::5432',
    images.postgres,
  ]);
  // ClickHouse ---------------------------------------------------------------------------------
  const chEnv = envFile(join(secretDir, 'clickhouse.env'), {
    CLICKHOUSE_SKIP_USER_SETUP: '1',
    ...clickHouseMemoryEnv(CLICKHOUSE_MEMORY_BYTES),
    ARGOS_CH_WRITER_PASSWORD_SHA256: sha256Hex(secrets.chWriter),
    ARGOS_CH_READER_PASSWORD_SHA256: sha256Hex(secrets.chReader),
    ARGOS_CH_MIGRATOR_PASSWORD_SHA256: sha256Hex(secrets.chMigrator),
  });
  const chDir = join(REPO_ROOT, 'deploy', 'clickhouse');
  await docker([
    'run',
    '-d',
    '--name',
    names.clickhouse,
    ...labelArgs(runId),
    '--network',
    names.network,
    ...memoryArgs(CLICKHOUSE_MEMORY_BYTES),
    '--ulimit',
    'nofile=262144:262144',
    '--env-file',
    chEnv,
    ...bind(join(chDir, 'config.d', 'argos.xml'), '/etc/clickhouse-server/config.d/argos.xml'),
    ...bind(join(chDir, 'users.d', 'argos.xml'), '/etc/clickhouse-server/users.d/argos.xml'),
    '-p',
    '127.0.0.1::8123',
    images.clickhouse,
  ]);

  await waitPostgres(names.postgres, log);
  await run(
    'docker',
    [
      'exec',
      '-i',
      names.postgres,
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
    { input: rolesScript(secrets), secrets: redact, timeoutMs: 60_000 },
  );
  log('PostgreSQL roles created (deploy/postgres/roles.sql)');
  const pgPort = await publishedPort(names.postgres, 5432);
  const chPort = await publishedPort(names.clickhouse, 8123);
  const clickhouseUrl = `http://127.0.0.1:${String(chPort)}`;
  await waitFor(
    async () => {
      await assertRunning(names.clickhouse);
      const ping = await httpGet(`${clickhouseUrl}/ping`);
      return ping.status === 200 ? true : undefined;
    },
    { timeoutMs: 180_000, what: `ClickHouse ${names.clickhouse}` },
  );
  log(`ClickHouse ${names.clickhouse} answers /ping`);
  timings.databases_s = (Date.now() - started) / 1000;

  // Server ---------------------------------------------------------------------------------------
  const serverStart = Date.now();
  const port = await freePort();
  const publicUrl = `http://127.0.0.1:${String(port)}`;
  const serverEnv = envFile(join(secretDir, 'server.env'), serverEnvironment(runId, port));
  await docker([
    'run',
    '-d',
    '--name',
    names.server,
    ...labelArgs(runId),
    '--network',
    names.network,
    ...memoryArgs(SERVER_MEMORY_BYTES),
    '--user',
    '10001:10001',
    '--tmpfs',
    '/data:rw,uid=10001,gid=10001,mode=0700',
    '--tmpfs',
    '/logs:rw,uid=10001,gid=10001,mode=0700',
    '--workdir',
    '/data',
    '--env-file',
    serverEnv,
    ...bind(jars.server, '/opt/argos/argos-server.jar'),
    ...bind(secretDir, SECRETS_MOUNT),
    '-p',
    `127.0.0.1:${String(port)}:${String(port)}`,
    '-p',
    '127.0.0.1::8081',
    '-p',
    '127.0.0.1::9100',
    images.serverJre,
    ...serverArguments(publicUrl),
  ]);
  const { managementUrl, grpcPort } = await waitServerReady(names.server);
  timings.server_s = (Date.now() - serverStart) / 1000;
  log(`server ${names.server} ready at ${publicUrl} (${String(timings.server_s)} s)`);

  const state: RunState = {
    run_id: runId,
    created_at: new Date().toISOString(),
    owner_pid: process.pid,
    reused: false,
    stack_url: publicUrl,
    management_url: managementUrl,
    grpc_port: grpcPort,
    clickhouse_url: clickhouseUrl,
    postgres_jdbc_url: `jdbc:postgresql://127.0.0.1:${String(pgPort)}/argos`,
    containers: names,
    csp_violations_at_start: null,
    timings,
  };
  writeRunState(state);
  return { state, secrets, jars };
}

/**
 * Waits until the server container is ready (/actuator/health/readiness UP on the management
 * port; the group includes the migrations) and returns its published management URL and gRPC port.
 */
async function waitServerReady(
  server: string,
): Promise<{ managementUrl: string; grpcPort: number }> {
  const managementUrl = `http://127.0.0.1:${String(await publishedPort(server, 8081))}`;
  const grpcPort = await publishedPort(server, 9100);
  await waitFor(
    async () => {
      await assertRunning(server);
      const health = await httpGet(`${managementUrl}/actuator/health/readiness`);
      return health.status === 200 && health.text.includes('"UP"') ? true : undefined;
    },
    {
      timeoutMs: 240_000,
      intervalMs: 2000,
      what: `server readiness ${managementUrl}/actuator/health/readiness`,
    },
  );
  return { managementUrl, grpcPort };
}

/**
 * Restarts the server of a running stack with the current boot jar (development of the UI or the
 * server against a seeded stack: `node e2e/stack/cli.ts restart-server`). The databases keep their
 * data; the server's /data tmpfs (JWT key, WAL, setup token) starts empty, so earlier sessions end.
 * Docker publishes the management and gRPC ports anew; run.json gets the new values.
 */
export async function restartServer(state: RunState, log: Log): Promise<RunState> {
  if (state.containers === null) {
    throw new Error(`run ${state.run_id} did not record its containers`);
  }
  const built = stackJars();
  for (const warning of built.warnings) {
    log(`WARNING ${warning}`);
  }
  const server = state.containers.server;
  await docker(['stop', '-t', '30', server]);
  copyFileSync(built.server, join(jarsDir(state.run_id), 'argos-server.jar'));
  await docker(['start', server]);
  const { managementUrl, grpcPort } = await waitServerReady(server);
  const next: RunState = { ...state, management_url: managementUrl, grpc_port: grpcPort };
  writeRunState(next);
  log(`server ${server} restarted with ${built.server}`);
  return next;
}

/** The secrets of a run started by this working tree (for a reused stack: from its run dir). */
export function runSecrets(runId: string): StackSecrets {
  return loadStackSecrets(secretsDir(runId));
}

/**
 * Stops a run: saves the container logs below the run's logs directory, removes the containers,
 * the network and the volumes, and deletes the run directory (secrets) unless `keep`.
 */
export async function stopStack(state: RunState, keep: boolean, log: Log): Promise<void> {
  if (state.reused) {
    log(`stack ${state.run_id} was reused (ARGOS_E2E_STACK_URL): left running`);
    return;
  }
  const secrets = existsSync(secretsDir(state.run_id))
    ? secretValues(runSecrets(state.run_id))
    : [];
  if (state.containers !== null) {
    const dir = logsDir(state.run_id);
    mkdirSync(dir, { recursive: true });
    for (const name of [
      state.containers.server,
      state.containers.clickhouse,
      state.containers.postgres,
    ]) {
      writeFileSync(join(dir, `${name}.log`), await containerLogs(name, 2000, secrets), 'utf8');
    }
  }
  if (keep) {
    log(
      `ARGOS_E2E_KEEP=1: stack ${state.run_id} left running at ${state.stack_url} (node e2e/stack/cli.ts down removes it)`,
    );
    return;
  }
  const removed = await removeRun(state.run_id);
  log(`stack ${state.run_id} removed: ${removed.join(', ') || 'nothing left'}`);
  removeRunOutputs(state.run_id);
  removeRunState();
}

/**
 * Deletes what a removed run leaves below ui/e2e/.stack except its logs: the secrets, the loader
 * and seed-traffic outputs, the CSP control record, the seed manifest and the session. Only `*.log`
 * files stay (diagnostics),
 * so the next `prettier --check .` of the UI does not trip over JSON and Markdown of a finished run.
 */
export function removeRunOutputs(runId: string): void {
  rmSync(secretsDir(runId), { recursive: true, force: true });
  for (const name of ['querydata', 'seed-traffic', 'jars', 'container-install']) {
    rmSync(join(runDir(runId), name), { recursive: true, force: true });
  }
  rmSync(cspControlFile(runId), { force: true });
  rmSync(MANIFEST_FILE, { force: true });
  rmSync(SESSION_FILE, { force: true });
}

/** argos_csp_violations_total summed over its label sets, or null when it cannot be read. */
export function parseCspViolations(prometheusText: string): number | null {
  let found = false;
  let total = 0;
  for (const line of prometheusText.split(/\r?\n/)) {
    const match = /^argos_csp_violations_total(?:\{[^}]*\})?\s+([0-9.eE+-]+)/.exec(line);
    if (match?.[1] !== undefined) {
      found = true;
      total += Number.parseFloat(match[1]);
    }
  }
  return found ? total : null;
}

/** Reads the server's CSP violation counter (management port, Prometheus, metrics token). */
export async function readCspViolations(
  managementUrl: string,
  metricsToken: string,
): Promise<number | null> {
  try {
    const response = await httpGet(`${managementUrl}/actuator/prometheus`, {
      Authorization: `Bearer ${metricsToken}`,
    });
    return response.status === 200 ? parseCspViolations(response.text) : null;
  } catch {
    return null;
  }
}
