// SPDX-License-Identifier: Apache-2.0
//
// Real running transactions for the active-transaction screen (WO-35). The real agent does not send
// active snapshots yet (WO-16), so shop-boot3 traffic never shows running transactions. This module
// runs the dev-only feed of the server test fixtures (`./gradlew :server:activeFeedJar`,
// io.argos.server.testing.fakeagent.ActiveFeed): one protocol-level fake agent session per instance
// against the stack's real plaintext collector, sending the deterministic snapshots of
// ActiveFeedScenario every 5 s. Collector, session registry, GET /apps/{id}/active and the
// WebSocket `active` topic are the real ones.
//
// The feed reports for its own app `e2e-active` (env `e2e`), never for the seeded e2e-shop, so the
// seed manifest's oracles stay untouched. The app and an ACTIVE token row are created through psql
// as the traffic seed does (traffic.ts tokenSql); the token exists only in a file of the run's
// secrets directory, is reused by later calls of the same run and is never printed. Inside the
// Playwright image of e2e:docker there is no JVM: startActiveFeed resolves with null there, and the
// specs skip their real-data tests (as T-SV-020e does).
//
// WO-37: the `commands` option starts the feed with `--commands`, so every instance also answers
// remote commands (thread dump, thread stack, self diagnosis, environment dump, config reload) as
// an agent does, deterministically from the scenario (ActiveFeed and ActiveFeedScenario javadoc).
// `app` and `instances` let a spec run its own feed app next to e2e-active (e2e/fixtures/
// commandFeed.ts: app e2e-cmd); each app has its own token file and log file.
import type { ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { z } from 'zod';

import type { ApiSession } from '../api.ts';
import { run } from '../docker.ts';
import { logsDir, REPO_ROOT, runDir, secretsDir } from '../paths.ts';
import { readRunState, type RunState } from '../runState.ts';
import { newAgentToken, readSecret, sha256Hex, writeSecret } from '../secrets.ts';
import type { Log } from '../stack.ts';
import { FatalWait, waitFor } from '../wait.ts';

import { gradle, javaExecutable } from './gradle.ts';
import { startProcess, stopProcess, tokenSql } from './traffic.ts';

/** The app of the feed (ActiveFeedScenario.APP / ENV). */
export const ACTIVE_FEED_APP = { name: 'e2e-active', env: 'e2e' } as const;

/** The instances of the feed (ActiveFeedScenario.DEFAULT_INSTANCES). */
export const ACTIVE_FEED_INSTANCES = ['e2e-active-1', 'e2e-active-2'] as const;

/** Snapshot interval of the feed (the agent's 5 s, docs/14 §5 `active`). */
export const ACTIVE_FEED_INTERVAL_S = 5;

/** Default lifetime of a feed process (it stops by itself afterwards). */
export const ACTIVE_FEED_DURATION_S = 30 * 60;

/** The feed jar and its libraries (`./gradlew :server:activeFeedJar`). */
export const ACTIVE_FEED_DIR = join(REPO_ROOT, 'server', 'build', 'active-feed');
export const ACTIVE_FEED_JAR = join(ACTIVE_FEED_DIR, 'active-feed.jar');

/** File name of the feed's agent token inside the run's secrets directory. */
export const ACTIVE_FEED_TOKEN_FILE = 'active-feed.token';

/** An app of a feed (plain identifiers: they reach psql and the command line). */
export interface FeedApp {
  readonly name: string;
  readonly env: string;
}

function isDefaultApp(app: FeedApp): boolean {
  return app.name === ACTIVE_FEED_APP.name && app.env === ACTIVE_FEED_APP.env;
}

/** The token file name of `app` in the run's secrets directory (the default app keeps its name). */
export function feedTokenFile(app: FeedApp): string {
  return isDefaultApp(app) ? ACTIVE_FEED_TOKEN_FILE : `active-feed-${app.name}-${app.env}.token`;
}

/** The log file name of a feed of `app` in the run's logs directory. */
export function feedLogFile(app: FeedApp): string {
  return isDefaultApp(app) ? 'active-feed.log' : `active-feed-${app.name}.log`;
}

/** The default elapsed band limits of the server (1 s, 3 s, 8 s; docs/14 §4.9 `buckets`). */
export const DEFAULT_BAND_LIMITS_MS = [1000, 3000, 8000] as const;

/** Inside the pinned Playwright image of e2e:docker (e2e/stack/cli.ts sets it): no JVM. */
function inContainer(env: Readonly<Record<string, string | undefined>>): boolean {
  return (env.ARGOS_E2E_LINUX_IMAGE ?? '') !== '';
}

const ReadySlot = z.object({
  slot: z.number().int(),
  timing: z.enum(['fixed', 'fresh']),
  start_ms: z.number().int().nullable(),
  elapsed_ms: z.number().int().nullable(),
  kind: z.enum(['HTTP', 'BATCH', 'MESSAGE']),
  service: z.string(),
  state: z.enum(['SQL', 'HTTP', 'DBC', 'METHOD', 'ASYNC']),
  state_detail: z.string().nullable(),
  thread: z.string(),
  virtual_thread: z.boolean(),
  user_id: z.string().nullable(),
  remote_ip: z.string().nullable(),
});

/**
 * What the command answers of a `--commands` feed contain (ActiveFeedScenario constants, WO-37):
 * the raw sentinel the environment dump never carries and the masked form it carries instead, the
 * deadlocked thread names, the idle pool threads collapsed into one stack, the deepest stack and
 * the XSS payload thread name.
 */
export const ActiveFeedCommands = z.object({
  enabled: z.literal(true),
  env_secret_sentinel: z.string().min(1),
  env_secret_masked: z.string().regex(/^\*{4}\(len=\d+\)$/),
  deadlock_threads: z.array(z.string()).length(2),
  idle_pool_threads: z.number().int().positive(),
  deep_frames: z.number().int().positive(),
  xss_thread: z.string(),
});
export type ActiveFeedCommands = z.infer<typeof ActiveFeedCommands>;

/** The ready file of the feed (ActiveFeedScenario.toJson). */
export const ActiveFeedReady = z.object({
  app: z.string(),
  env: z.string(),
  feed_start_ms: z.number().int(),
  interval_ms: z.number().int().positive(),
  /** Null (or absent from an older feed) without `--commands`. */
  commands: ActiveFeedCommands.nullable().optional(),
  instances: z
    .array(
      z.object({
        name: z.string(),
        instance_id: z.number().int(),
        user_id_mode: z.enum(['off', 'plain', 'hashed']),
        ip_mode: z.enum(['off', 'masked', 'full']),
        /** With `--commands`: the instance reports its thread dump truncated (the second one). */
        dump_truncated: z.boolean().optional(),
        slots: z.array(ReadySlot).min(1),
      }),
    )
    .min(1),
});
export type ActiveFeedReady = z.infer<typeof ActiveFeedReady>;

/**
 * One running transaction of the scenario as the server shows it (GET /apps/{id}/active item):
 * `fixed` rows keep their start (the same transaction in every snapshot, elapsed growing), `fresh`
 * rows are a new transaction in every snapshot with the same elapsed.
 */
export interface ScenarioRow extends z.infer<typeof ReadySlot> {
  readonly instance: string;
  readonly instance_id: number;
  readonly user_id_mode: 'off' | 'plain' | 'hashed';
  readonly ip_mode: 'off' | 'masked' | 'full';
}

/** The scenario rows of a ready file, instance by instance. */
export function scenarioRows(ready: ActiveFeedReady): ScenarioRow[] {
  return ready.instances.flatMap((instance) =>
    instance.slots.map((slot) => ({
      ...slot,
      instance: instance.name,
      instance_id: instance.instance_id,
      user_id_mode: instance.user_id_mode,
      ip_mode: instance.ip_mode,
    })),
  );
}

/** The elapsed of `row` in the snapshot taken at `snapshotMs` (epoch ms, the agent's clock). */
export function expectedElapsedMs(row: ScenarioRow, snapshotMs: number): number {
  if (row.timing === 'fixed') {
    if (row.start_ms === null) {
      throw new Error(`fixed slot ${String(row.slot)} of ${row.instance} without start_ms`);
    }
    return snapshotMs - row.start_ms;
  }
  if (row.elapsed_ms === null) {
    throw new Error(`fresh slot ${String(row.slot)} of ${row.instance} without elapsed_ms`);
  }
  return row.elapsed_ms;
}

/** The band (0 = under the first limit .. 3 = at or above the last) of `elapsedMs`. */
export function elapsedBand(
  elapsedMs: number,
  limitsMs: readonly [number, number, number] = DEFAULT_BAND_LIMITS_MS,
): 0 | 1 | 2 | 3 {
  let band = 0;
  for (const limit of limitsMs) {
    if (elapsedMs >= limit) {
      band += 1;
    }
  }
  return band as 0 | 1 | 2 | 3;
}

/** Per band the rows of `instance` in the snapshot at `snapshotMs`. */
export function expectedBuckets(
  rows: readonly ScenarioRow[],
  instance: string,
  snapshotMs: number,
  limitsMs: readonly [number, number, number] = DEFAULT_BAND_LIMITS_MS,
): [number, number, number, number] {
  const buckets: [number, number, number, number] = [0, 0, 0, 0];
  for (const row of rows) {
    if (row.instance === instance) {
      buckets[elapsedBand(expectedElapsedMs(row, snapshotMs), limitsMs)] += 1;
    }
  }
  return buckets;
}

export interface ActiveFeedArgs {
  readonly jar: string;
  readonly grpcPort: number;
  readonly tokenFile: string;
  readonly readyFile: string;
  readonly instances?: readonly string[];
  readonly durationS?: number;
  readonly intervalS?: number;
  /** The feed's app (default e2e-active/e2e). */
  readonly app?: FeedApp;
  /** Answer remote commands (`--commands`, WO-37). Off by default. */
  readonly commands?: boolean;
}

const PLAIN_NAME = /^[A-Za-z0-9._:-]{1,128}$/;

/** The java arguments of the feed process (`-jar active-feed.jar ...`). */
export function activeFeedArgs(args: ActiveFeedArgs): string[] {
  const instances = args.instances ?? ACTIVE_FEED_INSTANCES;
  const app = args.app ?? ACTIVE_FEED_APP;
  if (!PLAIN_NAME.test(app.name) || !PLAIN_NAME.test(app.env)) {
    throw new Error('invalid app name or env');
  }
  const durationS = args.durationS ?? ACTIVE_FEED_DURATION_S;
  const intervalS = args.intervalS ?? ACTIVE_FEED_INTERVAL_S;
  if (!Number.isInteger(args.grpcPort) || args.grpcPort < 1 || args.grpcPort > 65_535) {
    throw new Error(`invalid gRPC port ${String(args.grpcPort)}`);
  }
  if (instances.length === 0 || instances.some((i) => !PLAIN_NAME.test(i))) {
    throw new Error('invalid instance names');
  }
  for (const [name, value, max] of [
    ['duration', durationS, 24 * 3600],
    ['interval', intervalS, 3600],
  ] as const) {
    if (!Number.isInteger(value) || value < 1 || value > max) {
      throw new Error(`invalid ${name} ${String(value)} s`);
    }
  }
  return [
    '-Xmx256m',
    '-jar',
    args.jar,
    '--target',
    `127.0.0.1:${String(args.grpcPort)}`,
    '--token-file',
    args.tokenFile,
    '--instances',
    instances.join(','),
    '--ready-file',
    args.readyFile,
    '--duration',
    `${String(durationS)}s`,
    '--interval',
    `${String(intervalS)}s`,
    '--app',
    app.name,
    '--env',
    app.env,
    ...(args.commands === true ? ['--commands'] : []),
  ];
}

/** Why the feed cannot run for run `runId` here, or null. */
export function activeFeedProblem(
  runId: string,
  state: RunState | null = readRunState(),
  env: Readonly<Record<string, string | undefined>> = process.env,
): string | null {
  if (inContainer(env)) {
    return 'no JVM in the Playwright image of e2e:docker to run the active feed (the server half is ActiveFeedIT)';
  }
  if (state?.run_id !== runId || state.grpc_port === null) {
    return `ui/e2e/.stack/run.json does not describe the stack of run ${runId} with a gRPC port`;
  }
  if (state.containers === null) {
    return `ui/e2e/.stack/run.json of run ${runId} names no PostgreSQL container`;
  }
  return null;
}

/** The stack the feed runs against. */
export interface ActiveFeedStack {
  readonly runId: string;
  /** A logged-in API session (any role that sees the app): finds the app and the instance ids. */
  readonly api: ApiSession;
}

/** A running feed. */
export interface RunningActiveFeed {
  readonly appId: number;
  readonly appName: string;
  readonly env: string;
  readonly instances: readonly { readonly name: string; readonly instanceId: number }[];
  /** What the command answers contain; null without `commands`. */
  readonly commands: ActiveFeedCommands | null;
  /** Per instance name: it reports its thread dump truncated (only with `commands`). */
  readonly dumpTruncated: Readonly<Record<string, boolean>>;
  readonly scenario: readonly ScenarioRow[];
  readonly feedStartMs: number;
  readonly intervalMs: number;
  /** Ends the process and waits for it. */
  stop(): Promise<void>;
}

interface ActiveInstanceRow {
  instance_id: number;
  name: string | null;
  count: number;
}

interface ActiveItemRow {
  service: string | null;
  thread: string | null;
  state_detail: string | null;
}

interface ActiveData {
  snapshot_at: string | null;
  per_instance: ActiveInstanceRow[];
  items: ActiveItemRow[];
}

/** The app row and ACTIVE token of the feed; the token file path (created once per run and app). */
async function provisionToken(
  runId: string,
  postgresContainer: string,
  app: FeedApp,
  log: Log,
): Promise<string> {
  const dir = secretsDir(runId);
  const name = feedTokenFile(app);
  const file = join(dir, name);
  if (existsSync(file) && readSecret(dir, name).startsWith('argos_')) {
    return file;
  }
  const token = newAgentToken();
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
    {
      input: tokenSql(sha256Hex(token), token.slice(0, 14), app),
      secrets: [token],
      timeoutMs: 60_000,
    },
  );
  // Written only after the row exists: a later call of the run reuses both.
  writeSecret(dir, name, token);
  log(`active feed app ${app.name}/${app.env} and its agent token provisioned`);
  return file;
}

/** The feed's sources (server test fixtures): a jar older than any of them is rebuilt. */
export const ACTIVE_FEED_SOURCES_DIR = join(
  REPO_ROOT,
  'server',
  'src',
  'testFixtures',
  'java',
  'io',
  'argos',
  'server',
  'testing',
  'fakeagent',
);

/**
 * Whether a jar built at `jarMtimeMs` predates a source modified at one of `sourceMtimesMs` (a jar
 * of WO-35 does not know `--commands`, WO-37).
 */
export function feedJarStale(jarMtimeMs: number, sourceMtimesMs: readonly number[]): boolean {
  return sourceMtimesMs.some((m) => m > jarMtimeMs);
}

function sourceMtimes(): number[] {
  if (!existsSync(ACTIVE_FEED_SOURCES_DIR)) {
    return [];
  }
  return readdirSync(ACTIVE_FEED_SOURCES_DIR)
    .filter((f) => f.endsWith('.java'))
    .map((f) => statSync(join(ACTIVE_FEED_SOURCES_DIR, f)).mtimeMs);
}

/** Builds the feed jar when it or its libraries are missing, or older than its sources. */
async function ensureJar(log: Log): Promise<void> {
  if (
    existsSync(ACTIVE_FEED_JAR) &&
    existsSync(join(ACTIVE_FEED_DIR, 'lib')) &&
    !feedJarStale(statSync(ACTIVE_FEED_JAR).mtimeMs, sourceMtimes())
  ) {
    return;
  }
  log('building the active feed: ./gradlew :server:activeFeedJar');
  await gradle([':server:activeFeedJar', '-Pargos.ui.skip=true']);
  if (!existsSync(ACTIVE_FEED_JAR)) {
    throw new Error(`${ACTIVE_FEED_JAR} is missing after ./gradlew :server:activeFeedJar`);
  }
}

let started = 0;

/**
 * Starts the feed for the stack of `stack.runId` and waits until GET /apps/{id}/active shows every
 * instance with its scenario items and resolved names. Resolves with null where no host JVM is
 * available (e2e:docker); throws when the feed cannot start.
 */
export async function startActiveFeed(options: {
  readonly stack: ActiveFeedStack;
  readonly log: Log;
  readonly durationS?: number;
  /** The feed's app (default e2e-active/e2e). */
  readonly app?: FeedApp;
  /** Instance names (default ACTIVE_FEED_INSTANCES). */
  readonly instances?: readonly string[];
  /** Answer remote commands (WO-37). */
  readonly commands?: boolean;
}): Promise<RunningActiveFeed | null> {
  const { stack, log } = options;
  const feedApp = options.app ?? ACTIVE_FEED_APP;
  const state = readRunState();
  const problem = activeFeedProblem(stack.runId, state);
  if (problem !== null) {
    if (inContainer(process.env)) {
      log(`active feed skipped: ${problem}`);
      return null;
    }
    throw new Error(`active feed: ${problem}`);
  }
  // activeFeedProblem checked both.
  const grpcPort = state?.grpc_port ?? 0;
  const postgres = state?.containers?.postgres ?? '';
  const tokenFile = await provisionToken(stack.runId, postgres, feedApp, log);
  await ensureJar(log);

  started += 1;
  const workDir = join(runDir(stack.runId), 'active-feed');
  mkdirSync(workDir, { recursive: true });
  const readyFile = join(workDir, `ready-${String(process.pid)}-${String(started)}.json`);
  rmSync(readyFile, { force: true });
  const logs = logsDir(stack.runId);
  mkdirSync(logs, { recursive: true });
  const logFile = join(logs, feedLogFile(feedApp));
  const child: ChildProcess = startProcess(
    javaExecutable(),
    activeFeedArgs({
      jar: ACTIVE_FEED_JAR,
      grpcPort,
      tokenFile,
      readyFile,
      durationS: options.durationS,
      instances: options.instances,
      app: feedApp,
      commands: options.commands,
    }),
    {},
    logFile,
  );
  const stop = async (): Promise<void> => {
    await stopProcess(child);
    rmSync(readyFile, { force: true });
  };
  try {
    const ready = await waitFor(
      () => {
        if (child.exitCode !== null) {
          throw new FatalWait(
            `the active feed exited with ${String(child.exitCode)} (log: ${logFile})`,
          );
        }
        if (!existsSync(readyFile)) {
          return Promise.resolve(undefined);
        }
        return Promise.resolve(
          ActiveFeedReady.parse(JSON.parse(readFileSync(readyFile, 'utf8')) as unknown),
        );
      },
      { timeoutMs: 90_000, intervalMs: 500, what: `the active feed ready file (log: ${logFile})` },
    );
    const scenario = scenarioRows(ready);
    const apps = await stack.api.query<{ app_id: number; name: string; env: string }[]>('/apps');
    const app = apps.data.find((a) => a.name === feedApp.name && a.env === feedApp.env);
    if (app === undefined) {
      throw new Error(`GET /apps does not list ${feedApp.name}/${feedApp.env}`);
    }
    if (options.commands === true && (ready.commands ?? null) === null) {
      throw new Error(`the feed of ${feedApp.name} was asked for --commands but reports none`);
    }
    const instances = await waitFor(
      async () => {
        const active = await stack.api.query<ActiveData>(`/apps/${String(app.app_id)}/active`, {
          kind: 'ALL',
        });
        const found = ready.instances.map((instance) => {
          const row = active.data.per_instance.find((p) => p.name === instance.name);
          return row !== undefined && row.count === instance.slots.length
            ? { name: instance.name, instanceId: row.instance_id }
            : undefined;
        });
        const names = active.data.items.flatMap((i) => [i.service, i.thread, i.state_detail]);
        const unresolved = names.some((n) => n?.startsWith('<unknown:') === true);
        if (found.some((f) => f === undefined) || unresolved) {
          return undefined;
        }
        return found.filter((f): f is { name: string; instanceId: number } => f !== undefined);
      },
      {
        timeoutMs: 60_000,
        intervalMs: 1000,
        what: 'the active feed in GET /apps/{id}/active (every instance, names resolved)',
      },
    );
    for (const instance of instances) {
      const fromFeed = ready.instances.find((i) => i.name === instance.name)?.instance_id;
      if (fromFeed !== instance.instanceId) {
        throw new Error(
          `instance ${instance.name}: id ${String(instance.instanceId)} in GET /active, ${String(fromFeed)} in the feed`,
        );
      }
    }
    log(
      `active feed up for ${feedApp.name} (app ${String(app.app_id)}${options.commands === true ? ', answering commands' : ''}): ${instances.map((i) => `${i.name}=${String(i.instanceId)}`).join(' ')}`,
    );
    return {
      appId: app.app_id,
      appName: feedApp.name,
      env: feedApp.env,
      instances,
      commands: ready.commands ?? null,
      dumpTruncated: Object.fromEntries(
        ready.instances.map((i) => [i.name, i.dump_truncated === true]),
      ),
      scenario,
      feedStartMs: ready.feed_start_ms,
      intervalMs: ready.interval_ms,
      stop,
    };
  } catch (error) {
    await stop();
    throw error;
  }
}
