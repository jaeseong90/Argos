// SPDX-License-Identifier: Apache-2.0
//
// Start and stop of the E2E stack with its seeds (docs/18 §13), shared by the Playwright projects
// stack-setup / stack-teardown (e2e/setup/) and the CLI (e2e/stack/cli.ts):
//   1. stack: PostgreSQL + roles, ClickHouse, server (e2e/stack/stack.ts)
//   2. seed (a) generator data + PostgreSQL metadata (:server:loadQueryTestData, tiny, recent)
//   3. seed (c) users admin / operator / viewer through the API
//   4. seed (b) shop-boot3 + agent + test/seed/seed-traffic.sh through gRPC (via a TCP proxy)
//   5. seed (d) the manifest, resolved through the API, and at the same time seed (b2) the
//      collection outage of WO-22 (e2e/stack/seed/outage.ts: the agent's gRPC path frozen until
//      the instance is STALE, then restored; recorded in the manifest as `outage`)
//   6. seed (e) a real FIRING alert of the alert engine on the primary app (e2e/stack/seed/alerts.ts,
//      WO-25: a rule made by the operator, its FIRING event and the template rules of the app;
//      recorded in the manifest as `alert`)
//   7. the server's CSP violation counter at the start (one-sided run-level check,
//      e2e/stack/cspCounter.ts: a growth beyond the positive controls' reports fails the run; no
//      growth makes no claim, ADR-0010 C-188)
// A new stack holds the working tree's server-it lock (e2e/stack/lock.ts, ADR-0010 C-235 (8)) from
// before step 1 until its teardown, also when the setup fails; a reused stack takes no lock.
// Environment:
//   ARGOS_LOCK_WAIT_SECONDS  bounded wait for the server-it lock (default 1200)
//   ARGOS_E2E_STACK_URL  reuse a running stack (its seed manifest and secrets must be in
//                        ui/e2e/.stack, e.g. from `node e2e/stack/cli.ts up`); never torn down here
//   ARGOS_E2E_MGMT_URL   management URL of a reused stack (CSP counter); default from run.json
//   ARGOS_E2E_KEEP=1     leave the stack running after the run (debugging; cli.ts down removes it)
import { rmSync } from 'node:fs';
import { join } from 'node:path';

import { ApiSession } from './api.ts';
import {
  clearCspControl,
  judgeCspCounter,
  readCspControl,
  readStackCspCounter,
  type CspControl,
  type CspVerdict,
} from './cspCounter.ts';
import { removeRun } from './docker.ts';
import {
  acquireLock,
  inheritedTokenVariable,
  inspectLock,
  releaseLock,
  SERVER_IT_LOCK,
  type ReleaseResult,
} from './lock.ts';
import {
  manifestApp,
  readManifest,
  recordAlert,
  recordOutage,
  type SeedManifest,
} from './manifest.ts';
import { runDir, secretsDir } from './paths.ts';
import {
  DETACHED_OWNER,
  newRunId,
  readRunState,
  writeRunState,
  type RunState,
} from './runState.ts';
import { seedAlert, type SeedAlert } from './seed/alerts.ts';
import { runOutage, type SeedOutage } from './seed/outage.ts';
import { loadQueryData } from './seed/querydata.ts';
import { resolveManifest } from './seed/resolve.ts';
import { seedTraffic } from './seed/traffic.ts';
import { rolePassword, seedUsers, USERNAMES } from './seed/users.ts';
import { readSession, writeSession, type StackSession } from './session.ts';
import { removeLeftovers, startStack, stopStack, type Log } from './stack.ts';
import { httpGet } from './wait.ts';

type Env = Readonly<Record<string, string | undefined>>;

export function keepStack(env: Env = process.env): boolean {
  return env.ARGOS_E2E_KEEP === '1';
}

function reuseUrl(env: Env): string | null {
  const url = env.ARGOS_E2E_STACK_URL?.trim();
  return url === undefined || url === '' ? null : url.replace(/\/+$/, '');
}

/** Uses a running stack (ARGOS_E2E_STACK_URL). */
async function reuse(url: string, env: Env, log: Log): Promise<StackSession> {
  const manifest: SeedManifest = readManifest();
  const status = await httpGet(`${url}/api/v1/setup/status`);
  if (status.status !== 200) {
    throw new Error(
      `ARGOS_E2E_STACK_URL=${url}: GET /api/v1/setup/status answered ${String(status.status)}`,
    );
  }
  const owner = readRunState();
  const managementUrl =
    env.ARGOS_E2E_MGMT_URL?.trim() ||
    (owner?.stack_url === url ? owner.management_url : null) ||
    null;
  const csp = await readStackCspCounter(managementUrl, manifest.run_id);
  // A control record of an earlier Playwright run on this stack must not be subtracted again.
  clearCspControl(manifest.run_id);
  const session: StackSession = {
    run_id: manifest.run_id,
    reused: true,
    stack_url: url,
    management_url: managementUrl,
    csp_violations_at_start: csp.value,
    csp_counter_problem: csp.problem,
    started_at: new Date().toISOString(),
    setup_seconds: 0,
    timings: {},
  };
  writeSession(session);
  log(`reusing the stack at ${url} (run ${manifest.run_id})`);
  return session;
}

/** Starts and seeds a new stack, or reuses one; writes session.json (and run.json for a new one). */
export async function setUpStack(
  log: Log,
  ownerPid: number,
  env: Env = process.env,
): Promise<StackSession> {
  const url = reuseUrl(env);
  if (url !== null) {
    return reuse(url, env, log);
  }
  const begin = Date.now();
  const runId = newRunId();
  // ADR-0010 C-235 (8): a stack of this tree runs the real server from the tree's boot jar, so it
  // holds the server-it lock from before the jars are read until its teardown (waits, bounded,
  // while another live run holds it). Owner = the process that tears the stack down; a detached
  // `cli.ts up` stack holds it only while it starts (no process of it lives on).
  const lock = await acquireLock(SERVER_IT_LOCK, {
    purpose: `ui e2e stack ${runId} (start, seed and run the E2E stack with the server boot jar)`,
    pid: ownerPid === DETACHED_OWNER ? process.pid : ownerPid,
    token: stackLockToken(runId),
    log,
    env,
  });
  let state: RunState | null = null;
  try {
    await removeLeftovers(log);
    const started = await startStack(runId, log);
    state = { ...started.state, owner_pid: ownerPid };
    writeRunState(state);
    const { secrets, jars } = started;
    const timings = { ...state.timings };

    let phase = Date.now();
    if (
      state.clickhouse_url === null ||
      state.postgres_jdbc_url === null ||
      state.containers === null ||
      state.grpc_port === null
    ) {
      throw new Error('stack state incomplete');
    }
    const querydata = await loadQueryData(
      {
        clickhouseUrl: state.clickhouse_url,
        postgresJdbcUrl: state.postgres_jdbc_url,
        writerPassword: secrets.chWriter,
        appPassword: secrets.pgApp,
        outDir: join(runDir(runId), 'querydata'),
      },
      log,
    );
    timings.seed_generator_s = (Date.now() - phase) / 1000;

    phase = Date.now();
    const users = await seedUsers(state.stack_url, runId, secrets.adminInitial, log);
    timings.seed_users_s = (Date.now() - phase) / 1000;

    phase = Date.now();
    const api = new ApiSession(state.stack_url);
    await api.login(USERNAMES.admin, rolePassword(runId, 'admin'));
    // The collection outage (WO-22, 05 §2.1-10) watches the instance with its own session, so its
    // polling never shares the administrator's rate limit buckets with the resolution.
    const watcher = new ApiSession(state.stack_url);
    await watcher.login(USERNAMES.operator, rolePassword(runId, 'operator'));
    const postgresContainer = state.containers.postgres;
    const stackUrl = state.stack_url;
    let outage: SeedOutage | null = null;
    let outageProblem: string | null = null;
    await seedTraffic(
      {
        runId,
        baseUrl: state.stack_url,
        grpcPort: state.grpc_port,
        postgresContainer,
        api,
        // While shop-boot3 still runs: the outage and the manifest resolution side by side.
        whileRunning: async (traffic, live) => {
          timings.seed_traffic_s = (Date.now() - phase) / 1000;
          const overlap = Date.now();
          const cut = (async () => {
            try {
              outage = await runOutage({
                api: watcher,
                appId: traffic.appId,
                instanceName: live.instanceName,
                proxy: live.proxy,
                log,
              });
            } catch (error) {
              outageProblem = error instanceof Error ? error.message : String(error);
              log(`collection outage not made (the 05 §2.1-10 check fails): ${outageProblem}`);
            } finally {
              timings.seed_outage_s = (Date.now() - overlap) / 1000;
            }
          })();
          const resolve = (async () => {
            await resolveManifest({
              runId,
              baseUrl: stackUrl,
              api,
              querydata,
              traffic,
              users,
              postgresContainer,
              log,
            });
            timings.seed_manifest_s = (Date.now() - overlap) / 1000;
          })();
          const [, resolved] = await Promise.allSettled([cut, resolve]);
          if (resolved.status === 'rejected') {
            throw resolved.reason;
          }
          // What the outage added to the setup beyond the resolution it overlapped.
          timings.seed_outage_extra_s =
            (Date.now() - overlap) / 1000 - (timings.seed_manifest_s ?? 0);
        },
      },
      jars,
      log,
    );
    const resolved = recordOutage(outage, outageProblem);

    // Seed (e): a real FIRING alert of the alert engine on the primary app (WO-25), made by the
    // operator after the traffic seed; recorded in the manifest as `alert`.
    phase = Date.now();
    const primary = manifestApp(resolved, resolved.primary_app_id);
    let alert: SeedAlert | null = null;
    let alertProblem: string | null = null;
    try {
      alert = await seedAlert({ api: watcher, appId: primary.app_id, env: primary.env, log });
    } catch (error) {
      alertProblem = error instanceof Error ? error.message : String(error);
      log(`alert not made (the 05 §2.3-5 and §2.10-4 alert checks fail): ${alertProblem}`);
    }
    recordAlert(
      alert === null ? null : { ...alert, template_rules: [...alert.template_rules] },
      alertProblem,
    );
    timings.seed_alert_s = (Date.now() - phase) / 1000;
    await watcher.logout();
    await api.logout();

    const csp = await readStackCspCounter(state.management_url, runId);
    state = { ...state, csp_violations_at_start: csp.value, timings };
    writeRunState(state);
    const session: StackSession = {
      run_id: runId,
      reused: false,
      stack_url: state.stack_url,
      management_url: state.management_url,
      csp_violations_at_start: csp.value,
      csp_counter_problem: csp.problem,
      started_at: new Date(begin).toISOString(),
      setup_seconds: (Date.now() - begin) / 1000,
      timings,
    };
    writeSession(session);
    log(`stack ready in ${session.setup_seconds.toFixed(1)} s: ${JSON.stringify(timings)}`);
    if (ownerPid === DETACHED_OWNER) {
      logRelease(lock.release(), log, 'the detached stack keeps running without it');
    }
    return session;
  } catch (error) {
    try {
      if (keepStack(env)) {
        log(`ARGOS_E2E_KEEP=1: the failed stack ${runId} is left running for inspection`);
      } else if (state !== null) {
        await stopStack(state, false, log).catch((e: unknown) => {
          log(`teardown after the failed setup: ${String(e)}`);
        });
      } else {
        await removeRun(runId).catch(() => []);
        rmSync(secretsDir(runId), { recursive: true, force: true });
      }
    } finally {
      logRelease(lock.release(), log, 'after the failed setup');
    }
    throw error;
  }
}

/** The token of the server-it lock a stack run holds (the teardown releases it by its run id). */
export function stackLockToken(runId: string): string {
  return `e2e-stack-${runId}`;
}

function logRelease(result: ReleaseResult | 'inherited', log: Log, when: string): void {
  if (result === 'released') {
    log(`lock ${SERVER_IT_LOCK} released (${when})`);
  } else if (result === 'foreign') {
    log(`lock ${SERVER_IT_LOCK} is not this run's any more: left as it is (${when})`);
  }
}

/**
 * Releases the server-it lock of the stack run `runId` (only when its owner file has this run's
 * token; a lock of another run, or of an enclosing `argos-lock.sh run`, stays).
 */
export function releaseStackLock(runId: string, log: Log, env: Env = process.env): void {
  const inspection = inspectLock(SERVER_IT_LOCK, { env });
  const inherited = env[inheritedTokenVariable(SERVER_IT_LOCK)]?.trim();
  if (
    inspection.owner !== null &&
    inherited !== undefined &&
    inspection.owner.token === inherited
  ) {
    return; // The enclosing run releases it.
  }
  logRelease(releaseLock(SERVER_IT_LOCK, stackLockToken(runId), { env }), log, 'teardown');
}

export interface TeardownResult {
  /** argos_csp_violations_total increase over the run, or null when it could not be read. */
  readonly cspIncrease: number | null;
  readonly cspProblem: string | null;
  /** The record of the positive controls (e2e/specs/csp-report.spec.ts), or null. */
  readonly cspControl: CspControl | null;
  /** What the counter shows about the run (e2e/stack/cspCounter.ts, one-sided). */
  readonly cspVerdict: CspVerdict;
}

/**
 * Reads the CSP counter (and the positive controls' record) at the end, then stops the stack this
 * run started (unless kept or reused).
 * `known` is the session the caller started (e2e:docker): the Playwright run inside the container
 * reuses the stack and writes its own session.json (reused: true), which must not stop the owner
 * from removing it.
 */
export async function tearDownStack(
  log: Log,
  env: Env = process.env,
  known?: StackSession,
): Promise<TeardownResult> {
  let session: StackSession;
  if (known !== undefined) {
    session = known;
  } else {
    try {
      session = readSession();
    } catch {
      log('no session.json: nothing to tear down');
      const cspProblem = 'the stack setup did not finish';
      return {
        cspIncrease: null,
        cspProblem,
        cspControl: null,
        cspVerdict: judgeCspCounter(null, cspProblem, null),
      };
    }
  }
  const end = await readStackCspCounter(session.management_url, session.run_id);
  const cspIncrease =
    end.value === null || session.csp_violations_at_start === null
      ? null
      : end.value - session.csp_violations_at_start;
  const cspProblem = session.csp_counter_problem ?? end.problem;
  // Read before the stack (and with it ui/e2e/.stack/<run>/csp-control.json) is removed.
  const cspControl = readCspControl(session.run_id);
  if (!session.reused) {
    try {
      const state = readRunState();
      if (state?.run_id === session.run_id) {
        await stopStack(state, keepStack(env), log);
      }
    } finally {
      // Also with ARGOS_E2E_KEEP=1: no process of this run holds the kept stack.
      releaseStackLock(session.run_id, log, env);
    }
  }
  return {
    cspIncrease,
    cspProblem,
    cspControl,
    cspVerdict: judgeCspCounter(cspIncrease, cspProblem, cspControl),
  };
}
