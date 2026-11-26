// SPDX-License-Identifier: Apache-2.0
//
// The fresh-install variant of the E2E stack (WO-46, ADR-0010 C-192 (1)): PostgreSQL, ClickHouse
// and the server of this tree exactly as the seeded stack starts them (e2e/stack/stack.ts, same
// labels, names, memory limits and 127.0.0.1 ports), but as a new installation: no unattended
// administrator (no ARGOS_ADMIN_PASSWORD_FILE), no IP mode, no seed data, no users. The server
// therefore issues a setup token (16 §7.8): this module copies /data/setup-token of the server
// container with `docker exec` into the run's secrets directory (setup.token, never printed; the
// server's one log line with it is redacted when the logs are saved). The Playwright projects
// fresh-setup / fresh-install / fresh-teardown (playwright.config.ts, `corepack pnpm e2e:fresh`)
// run the real setup wizard against it (e2e/specs/fresh/).
//
// One stack at a time: a fresh stack holds the same server-it lock as the seeded one from before it
// reads the jars until its teardown, and shares run.json, so a seeded stack that is running (or a
// detached `cli.ts up` stack) stops it with the leftover check of removeLeftovers, and the other
// way round. Its session is ui/e2e/.stack/fresh-session.json (the seeded specs never read it).
// A fresh stack is single-use: once the wizard created the administrator the installation is not
// fresh any more, so a reused one (ARGOS_E2E_FRESH_URL, e.g. inside e2e:docker) must still say
// needs_setup.
// Environment:
//   ARGOS_E2E_FRESH_URL   reuse the fresh stack of fresh-session.json at this URL (never torn down
//                         here; the process that started it removes it)
//   ARGOS_E2E_MGMT_URL    management URL of a reused stack (CSP counter); default from the session
//   ARGOS_E2E_KEEP=1      leave the stack running after the run (cli.ts down removes it)
import { rmSync } from 'node:fs';
import { join } from 'node:path';

import { readStackCspCounter } from './cspCounter.ts';
import { removeRun, run } from './docker.ts';
import {
  acquireLock,
  inheritedTokenVariable,
  inspectLock,
  releaseLock,
  SERVER_IT_LOCK,
  type ReleaseResult,
} from './lock.ts';
import { secretsDir, STACK_DIR } from './paths.ts';
import {
  DETACHED_OWNER,
  newRunId,
  readRunState,
  writeRunState,
  type RunState,
} from './runState.ts';
import { SETUP_TOKEN_FILE, writeSecret } from './secrets.ts';
import { readSession, writeSession, type StackSession } from './session.ts';
import { removeLeftovers, startStack, stopStack, type Log } from './stack.ts';
import { httpGet, waitFor } from './wait.ts';

type Env = Readonly<Record<string, string | undefined>>;

/** The session of the fresh stack the current Playwright run talks to (no secret in it). */
export const FRESH_SESSION_FILE = join(STACK_DIR, 'fresh-session.json');

/** Where the server writes its setup token (stack.ts serverArguments: argos.auth.setup-token-file). */
export const SERVER_SETUP_TOKEN_PATH = '/data/setup-token';

/** A setup token as the server issues it: base64url of 32 random bytes (43 characters). */
const SETUP_TOKEN = /^[A-Za-z0-9_-]{43}$/;

/** The setup token in the text of the server's token file, or null when it is not one. */
export function parseSetupToken(text: string): string | null {
  const token = text.trim();
  return SETUP_TOKEN.test(token) ? token : null;
}

/** The file of the run's setup token (secrets directory, deleted at teardown). */
export function setupTokenFile(runId: string): string {
  return join(secretsDir(runId), SETUP_TOKEN_FILE);
}

/** The token of the server-it lock a fresh stack run holds. */
export function freshLockToken(runId: string): string {
  return `e2e-fresh-${runId}`;
}

/** The URL of a fresh stack to reuse (ARGOS_E2E_FRESH_URL without trailing slashes), or null. */
export function freshReuseUrl(env: Env): string | null {
  const url = env.ARGOS_E2E_FRESH_URL?.trim();
  return url === undefined || url === '' ? null : url.replace(/\/+$/, '');
}

/** Whether GET /setup/status of `baseUrl` says the installation has no administrator yet. */
export async function needsSetup(baseUrl: string): Promise<boolean> {
  const status = await httpGet(`${baseUrl}/api/v1/setup/status`);
  if (status.status !== 200) {
    throw new Error(`GET ${baseUrl}/api/v1/setup/status answered ${String(status.status)}`);
  }
  const body = JSON.parse(status.text) as { data?: { needs_setup?: unknown } };
  return body.data?.needs_setup === true;
}

/**
 * Copies the server's setup token into the run's secrets directory with `docker exec` (the
 * container's /data is a tmpfs of uid 10001, the user exec runs as). The value is never logged:
 * the command's output is only parsed, and a failure names the file, not its content.
 */
export async function copySetupToken(runId: string, server: string): Promise<string> {
  const token = await waitFor(
    async () => {
      const read = await run('docker', ['exec', server, 'cat', SERVER_SETUP_TOKEN_PATH], {
        allowFailure: true,
        timeoutMs: 30_000,
      });
      return read.code === 0 ? (parseSetupToken(read.stdout) ?? undefined) : undefined;
    },
    { timeoutMs: 60_000, intervalMs: 1000, what: `${SERVER_SETUP_TOKEN_PATH} in ${server}` },
  );
  writeSecret(secretsDir(runId), SETUP_TOKEN_FILE, token);
  return token;
}

/** Uses a running fresh stack (ARGOS_E2E_FRESH_URL); it must still need its setup. */
async function reuseFresh(url: string, env: Env, log: Log): Promise<StackSession> {
  const known = readSession(FRESH_SESSION_FILE);
  if (known.stack_url !== url) {
    throw new Error(
      `ARGOS_E2E_FRESH_URL=${url} is not the fresh stack of ${FRESH_SESSION_FILE} (${known.stack_url})`,
    );
  }
  if (!(await needsSetup(url))) {
    throw new Error(
      `the fresh stack at ${url} has an administrator already (a fresh stack is single-use): start a new one`,
    );
  }
  const managementUrl = env.ARGOS_E2E_MGMT_URL?.trim() || known.management_url;
  const csp = await readStackCspCounter(managementUrl, known.run_id);
  const session: StackSession = {
    ...known,
    reused: true,
    management_url: managementUrl,
    csp_violations_at_start: csp.value,
    csp_counter_problem: csp.problem,
    started_at: new Date().toISOString(),
    setup_seconds: 0,
    timings: {},
  };
  writeSession(session, FRESH_SESSION_FILE);
  log(`reusing the fresh stack at ${url} (run ${known.run_id})`);
  return session;
}

/**
 * Starts a fresh stack (or reuses one, ARGOS_E2E_FRESH_URL), copies its setup token and writes
 * fresh-session.json and run.json. `ownerPid` tears it down (DETACHED_OWNER: kept until
 * `cli.ts down`).
 */
export async function setUpFreshStack(
  log: Log,
  ownerPid: number,
  env: Env = process.env,
): Promise<StackSession> {
  const url = freshReuseUrl(env);
  if (url !== null) {
    return reuseFresh(url, env, log);
  }
  const begin = Date.now();
  const runId = newRunId();
  const lock = await acquireLock(SERVER_IT_LOCK, {
    purpose: `ui e2e fresh-install stack ${runId} (setup wizard against the server boot jar)`,
    pid: ownerPid === DETACHED_OWNER ? process.pid : ownerPid,
    token: freshLockToken(runId),
    log,
    env,
  });
  let state: RunState | null = null;
  try {
    await removeLeftovers(log);
    rmSync(FRESH_SESSION_FILE, { force: true });
    const started = await startStack(runId, log, 'fresh');
    state = { ...started.state, owner_pid: ownerPid };
    writeRunState(state);
    if (state.containers === null) {
      throw new Error('stack state incomplete');
    }
    await copySetupToken(runId, state.containers.server);
    if (!(await needsSetup(state.stack_url))) {
      throw new Error(
        `the fresh stack at ${state.stack_url} reports no setup needed: the server found an administrator`,
      );
    }
    log(`fresh stack: setup token copied to the run's secrets directory (${SETUP_TOKEN_FILE})`);
    const csp = await readStackCspCounter(state.management_url, runId);
    state = { ...state, csp_violations_at_start: csp.value };
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
      timings: { ...state.timings },
    };
    writeSession(session, FRESH_SESSION_FILE);
    log(`fresh stack ready in ${session.setup_seconds.toFixed(1)} s at ${session.stack_url}`);
    if (ownerPid === DETACHED_OWNER) {
      logRelease(lock.release(), log, 'the detached fresh stack keeps running without it');
    }
    return session;
  } catch (error) {
    try {
      if (env.ARGOS_E2E_KEEP === '1') {
        log(`ARGOS_E2E_KEEP=1: the failed fresh stack ${runId} is left running for inspection`);
      } else if (state !== null) {
        await stopStack(state, false, log).catch((e: unknown) => {
          log(`teardown after the failed fresh setup: ${String(e)}`);
        });
      } else {
        await removeRun(runId).catch(() => []);
        rmSync(secretsDir(runId), { recursive: true, force: true });
      }
      rmSync(FRESH_SESSION_FILE, { force: true });
    } finally {
      logRelease(lock.release(), log, 'after the failed fresh setup');
    }
    throw error;
  }
}

function logRelease(result: ReleaseResult | 'inherited', log: Log, when: string): void {
  if (result === 'released') {
    log(`lock ${SERVER_IT_LOCK} released (${when})`);
  } else if (result === 'foreign') {
    log(`lock ${SERVER_IT_LOCK} is not this run's any more: left as it is (${when})`);
  }
}

export interface FreshTeardownResult {
  /** argos_csp_violations_total increase over the run, or null when it could not be read. */
  readonly cspIncrease: number | null;
  readonly cspProblem: string | null;
}

/**
 * Reads the CSP counter at the end, then stops the fresh stack this run started (unless kept or
 * reused) and releases its lock. `known` is the session the caller started (cli.ts docker-fresh).
 */
export async function tearDownFreshStack(
  log: Log,
  env: Env = process.env,
  known?: StackSession,
): Promise<FreshTeardownResult> {
  let session: StackSession;
  try {
    session = known ?? readSession(FRESH_SESSION_FILE);
  } catch {
    log('no fresh-session.json: nothing to tear down');
    return { cspIncrease: null, cspProblem: 'the fresh stack setup did not finish' };
  }
  const end = await readStackCspCounter(session.management_url, session.run_id);
  const cspIncrease =
    end.value === null || session.csp_violations_at_start === null
      ? null
      : end.value - session.csp_violations_at_start;
  const cspProblem = session.csp_counter_problem ?? end.problem;
  if (!session.reused) {
    try {
      const state = readRunState();
      if (state?.run_id === session.run_id) {
        await stopStack(state, env.ARGOS_E2E_KEEP === '1', log);
      }
      if (env.ARGOS_E2E_KEEP !== '1') {
        rmSync(FRESH_SESSION_FILE, { force: true });
      }
    } finally {
      releaseFreshLock(session.run_id, log, env);
    }
  }
  return { cspIncrease, cspProblem };
}

/** Releases the server-it lock of fresh run `runId` (only when it is that run's). */
function releaseFreshLock(runId: string, log: Log, env: Env): void {
  const inspection = inspectLock(SERVER_IT_LOCK, { env });
  const inherited = env[inheritedTokenVariable(SERVER_IT_LOCK)]?.trim();
  if (
    inspection.owner !== null &&
    inherited !== undefined &&
    inspection.owner.token === inherited
  ) {
    return; // The enclosing run releases it.
  }
  logRelease(releaseLock(SERVER_IT_LOCK, freshLockToken(runId), { env }), log, 'teardown');
}
