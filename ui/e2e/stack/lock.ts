// SPDX-License-Identifier: Apache-2.0
//
// Working-tree locks under .gradle/argos-locks/<name> (ADR-0010 C-235 (8)), the TypeScript side of
// test/lock/argos-lock.sh (same directory, same owner file, same rules). Several engineers and
// agents share one working tree; `server-it` serialises everything that runs the real server or
// rebuilds its boot jar (the E2E stack among them).
//
// Protocol:
// - The lock is the directory .gradle/argos-locks/<name>, created atomically with mkdir. Right
//   after creating it the holder writes owner.json inside it (written to a temporary file and
//   renamed): version, name, token, pid, pid_kind, host, started_at (ISO-8601 UTC), purpose.
// - pid is the process that holds the lock for its whole duration (for the E2E stack the Playwright
//   runner, or the CLI process), as the platform's own number: on Windows the Windows PID
//   (pid_kind "windows": Node's process.pid, and in Git Bash /proc/<pid>/winpid), elsewhere the POSIX
//   PID (pid_kind "posix"). The liveness check is process.kill(pid, 0) (works on Windows too; EPERM
//   counts as alive).
// - Stale: only a lock whose owner file names this host (case-insensitive), the pid kind of this
//   host and a PID that is not alive. A lock without a readable owner file, of another host or of
//   another PID kind is never stale: the acquirer waits (bounded) and then fails with a message that
//   names the owner and how to remove the lock by hand.
// - A stale lock is renamed to <name>.reap.<random> first (atomic) and deleted only when the renamed
//   owner file still has the token that was judged stale; otherwise it is renamed back.
// - Release deletes the lock only when its owner file has the caller's token. Never another run's.
// - Nesting: a process started under `test/lock/argos-lock.sh run <name>` gets
//   ARGOS_LOCK_TOKEN_<NAME> (e.g. ARGOS_LOCK_TOKEN_SERVER_IT); when that names the current owner's
//   token, the lock is already held for this process tree and is neither taken nor released here.
// - ARGOS_LOCK_DIR overrides the lock root (tests); ARGOS_LOCK_WAIT_SECONDS the default wait.
import { randomBytes } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { hostname } from 'node:os';
import { join } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';

import { z } from 'zod';

import { REPO_ROOT } from './paths.ts';

/** The lock of everything that runs the real server or rebuilds its boot jar (CLAUDE.md). */
export const SERVER_IT_LOCK = 'server-it';

export const OWNER_FILE = 'owner.json';

/** Default bounded wait of the E2E stack: below the 30 min timeout of the stack-setup test. */
export const DEFAULT_WAIT_SECONDS = 20 * 60;

const NAME_PATTERN = /^[a-z0-9][a-z0-9-]{0,63}$/;

type Env = Readonly<Record<string, string | undefined>>;

export type PidKind = 'windows' | 'posix';

export const LockOwner = z.object({
  version: z.literal(1),
  name: z.string(),
  token: z.string().min(1),
  pid: z.number().int().positive(),
  pid_kind: z.enum(['windows', 'posix']),
  host: z.string().min(1),
  started_at: z.string(),
  purpose: z.string(),
});
export type LockOwner = z.infer<typeof LockOwner>;

/** What this host is and how it checks a PID (injected by the tests). */
export interface LockProbe {
  readonly host: string;
  readonly pidKind: PidKind;
  /** True alive, false not alive, null unknown (never stale). */
  readonly alive: (pid: number) => boolean | null;
}

export function hostPidKind(platform: NodeJS.Platform = process.platform): PidKind {
  return platform === 'win32' ? 'windows' : 'posix';
}

/** process.kill(pid, 0): alive, EPERM alive (another user's), ESRCH gone, anything else unknown. */
export function pidAlive(pid: number): boolean | null {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code === 'EPERM') {
      return true;
    }
    return code === 'ESRCH' ? false : null;
  }
}

export function defaultProbe(): LockProbe {
  return { host: hostname(), pidKind: hostPidKind(), alive: pidAlive };
}

export function lockRoot(env: Env = process.env): string {
  const override = env.ARGOS_LOCK_DIR?.trim();
  return override === undefined || override === ''
    ? join(REPO_ROOT, '.gradle', 'argos-locks')
    : override;
}

export function lockDir(name: string, root: string): string {
  if (!NAME_PATTERN.test(name)) {
    throw new Error(`invalid lock name '${name}' (lower-case letters, digits and '-')`);
  }
  return join(root, name);
}

/** ARGOS_LOCK_TOKEN_<NAME>: set by `argos-lock.sh run` for the processes it starts. */
export function inheritedTokenVariable(name: string): string {
  return `ARGOS_LOCK_TOKEN_${name.toUpperCase().replaceAll('-', '_')}`;
}

export function newLockToken(): string {
  return randomBytes(8).toString('hex');
}

/** The owner file of a lock directory: the owner, or null with the reason it is not usable. */
export function readOwner(dir: string): { owner: LockOwner | null; problem: string | null } {
  const file = join(dir, OWNER_FILE);
  let text: string;
  try {
    text = readFileSync(file, 'utf8');
  } catch {
    return { owner: null, problem: `no ${OWNER_FILE}` };
  }
  try {
    return { owner: LockOwner.parse(JSON.parse(text)), problem: null };
  } catch {
    return { owner: null, problem: `${OWNER_FILE} is not readable` };
  }
}

export interface StaleVerdict {
  readonly stale: boolean;
  readonly reason: string;
}

/** The stale rule of the file comment: only a dead PID of this host and PID kind is stale. */
export function judgeStale(owner: LockOwner | null, probe: LockProbe): StaleVerdict {
  if (owner === null) {
    return { stale: false, reason: 'it has no readable owner file' };
  }
  if (owner.host.toLowerCase() !== probe.host.toLowerCase()) {
    return { stale: false, reason: `its owner runs on another host (${owner.host})` };
  }
  if (owner.pid_kind !== probe.pidKind) {
    return {
      stale: false,
      reason: `its owner PID is a ${owner.pid_kind} PID, which this ${probe.pidKind} host cannot check`,
    };
  }
  const alive = probe.alive(owner.pid);
  if (alive === null) {
    return { stale: false, reason: `the liveness of PID ${String(owner.pid)} is unknown` };
  }
  return alive
    ? { stale: false, reason: `its owner process ${String(owner.pid)} is alive` }
    : { stale: true, reason: `its owner process ${String(owner.pid)} is not alive` };
}

export interface LockInspection {
  readonly name: string;
  readonly dir: string;
  readonly exists: boolean;
  readonly owner: LockOwner | null;
  readonly ownerProblem: string | null;
  readonly verdict: StaleVerdict;
}

export interface LockOptions {
  readonly root?: string;
  readonly probe?: LockProbe;
  readonly env?: Env;
}

export function inspectLock(name: string, options: LockOptions = {}): LockInspection {
  const env = options.env ?? process.env;
  const dir = lockDir(name, options.root ?? lockRoot(env));
  if (!existsSync(dir)) {
    return {
      name,
      dir,
      exists: false,
      owner: null,
      ownerProblem: null,
      verdict: { stale: false, reason: 'free' },
    };
  }
  const { owner, problem } = readOwner(dir);
  return {
    name,
    dir,
    exists: true,
    owner,
    ownerProblem: problem,
    verdict: judgeStale(owner, options.probe ?? defaultProbe()),
  };
}

/** One line about a held lock: who holds it and how to remove it by hand. */
export function describeHeld(inspection: LockInspection): string {
  const owner = inspection.owner;
  const who =
    owner === null
      ? `${inspection.dir} (${inspection.ownerProblem ?? 'no owner file'}: made by hand or by the old convention, or its owner is just starting)`
      : `${inspection.dir}: PID ${String(owner.pid)} (${owner.pid_kind}) on ${owner.host} since ${owner.started_at}, purpose "${owner.purpose}"`;
  const check =
    owner === null
      ? ''
      : owner.pid_kind === 'windows'
        ? ` (check: tasklist /FI "PID eq ${String(owner.pid)}")`
        : ` (check: ps -p ${String(owner.pid)})`;
  return `lock ${inspection.name} is held by ${who}; ${inspection.verdict.reason}. If that run is gone${check}, remove the lock by hand: rm -rf "${inspection.dir}"`;
}

function writeOwner(dir: string, owner: LockOwner): void {
  const temporary = join(dir, `${OWNER_FILE}.tmp-${owner.token}`);
  writeFileSync(temporary, `${JSON.stringify(owner, null, 2)}\n`, 'utf8');
  renameSync(temporary, join(dir, OWNER_FILE));
}

/**
 * Removes the stale lock `dir` whose owner had `token`: renamed aside first, deleted only when the
 * renamed directory still has that owner (another process may have reaped and re-taken it between
 * the judgement and the rename), otherwise renamed back. Returns a warning, or null.
 */
export function reapStale(dir: string, token: string): string | null {
  const aside = `${dir}.reap.${newLockToken()}`;
  try {
    renameSync(dir, aside);
  } catch {
    return null; // Already gone (another waiter reaped it) or busy: the next attempt decides.
  }
  if (readOwner(aside).owner?.token === token) {
    rmSync(aside, { recursive: true, force: true });
    return null;
  }
  try {
    renameSync(aside, dir);
    return null;
  } catch {
    return `a fresh lock was moved to ${aside} while reaping a stale one and could not be moved back; its owner keeps running, the lock is free for others`;
  }
}

export interface AcquireOptions extends LockOptions {
  readonly purpose: string;
  /** The process that holds the lock for its whole duration (default: this process). */
  readonly pid?: number;
  readonly token?: string;
  readonly now?: () => Date;
}

export type TryAcquireResult =
  | { readonly acquired: true; readonly owner: LockOwner; readonly dir: string }
  | { readonly acquired: false; readonly inspection: LockInspection; readonly warning?: string };

/** One attempt: mkdir, or reap a stale lock and mkdir once more. Never deletes a live lock. */
export function tryAcquireLock(name: string, options: AcquireOptions): TryAcquireResult {
  const env = options.env ?? process.env;
  const root = options.root ?? lockRoot(env);
  const probe = options.probe ?? defaultProbe();
  const dir = lockDir(name, root);
  mkdirSync(root, { recursive: true });
  let warning: string | undefined;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      mkdirSync(dir);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') {
        throw error;
      }
      const inspection = inspectLock(name, { root, probe, env });
      if (attempt === 0 && inspection.verdict.stale && inspection.owner !== null) {
        warning = reapStale(dir, inspection.owner.token) ?? undefined;
        continue;
      }
      return warning === undefined
        ? { acquired: false, inspection }
        : { acquired: false, inspection, warning };
    }
    const owner: LockOwner = {
      version: 1,
      name,
      token: options.token ?? newLockToken(),
      pid: options.pid ?? process.pid,
      pid_kind: probe.pidKind,
      host: probe.host,
      started_at: (options.now ?? (() => new Date()))().toISOString(),
      purpose: options.purpose,
    };
    try {
      writeOwner(dir, owner);
    } catch (error) {
      rmSync(dir, { recursive: true, force: true });
      throw error;
    }
    return { acquired: true, owner, dir };
  }
  throw new Error('unreachable');
}

export type ReleaseResult = 'released' | 'absent' | 'foreign';

/** Deletes the lock only when its owner file has `token`; a foreign or ownerless lock stays. */
export function releaseLock(name: string, token: string, options: LockOptions = {}): ReleaseResult {
  const dir = lockDir(name, options.root ?? lockRoot(options.env ?? process.env));
  if (!existsSync(dir) || !statSync(dir).isDirectory()) {
    return 'absent';
  }
  if (readOwner(dir).owner?.token !== token) {
    return 'foreign';
  }
  rmSync(dir, { recursive: true, force: true });
  return 'released';
}

export interface LockHandle {
  readonly name: string;
  readonly dir: string;
  readonly token: string;
  /** True when an enclosing `argos-lock.sh run` holds it: release() then does nothing. */
  readonly inherited: boolean;
  release: () => ReleaseResult | 'inherited';
}

export interface WaitingAcquireOptions extends AcquireOptions {
  readonly waitSeconds?: number;
  readonly pollMs?: number;
  /** How often the waiting message is repeated. */
  readonly reportMs?: number;
  readonly log?: (line: string) => void;
}

function waitSeconds(options: WaitingAcquireOptions, env: Env): number {
  if (options.waitSeconds !== undefined) {
    return options.waitSeconds;
  }
  const fromEnv = Number.parseInt(env.ARGOS_LOCK_WAIT_SECONDS ?? '', 10);
  return Number.isFinite(fromEnv) && fromEnv >= 0 ? fromEnv : DEFAULT_WAIT_SECONDS;
}

/**
 * Takes the lock, waiting (bounded) while another live run holds it; throws with the owner and the
 * manual removal hint when the wait is over.
 */
export async function acquireLock(
  name: string,
  options: WaitingAcquireOptions,
): Promise<LockHandle> {
  const env = options.env ?? process.env;
  const root = options.root ?? lockRoot(env);
  const log = options.log ?? ((): void => undefined);
  const inherited = env[inheritedTokenVariable(name)]?.trim();
  if (inherited !== undefined && inherited !== '') {
    const dir = lockDir(name, root);
    if (readOwner(dir).owner?.token === inherited) {
      log(`lock ${name} is held by the enclosing run (${inheritedTokenVariable(name)})`);
      return {
        name,
        dir,
        token: inherited,
        inherited: true,
        release: () => 'inherited',
      };
    }
  }
  const limitMs = waitSeconds(options, env) * 1000;
  const pollMs = options.pollMs ?? 5000;
  const reportMs = options.reportMs ?? 60_000;
  const start = Date.now();
  let lastReport = -Infinity;
  for (;;) {
    const result = tryAcquireLock(name, { ...options, root, env });
    if (result.acquired) {
      const { owner, dir } = result;
      log(`lock ${name} taken (${dir}, PID ${String(owner.pid)}, purpose "${owner.purpose}")`);
      return {
        name,
        dir,
        token: owner.token,
        inherited: false,
        release: () => releaseLock(name, owner.token, { root, env }),
      };
    }
    if (result.warning !== undefined) {
      log(`WARNING ${result.warning}`);
    }
    const waited = Date.now() - start;
    if (waited >= limitMs) {
      throw new Error(
        `gave up waiting ${String(Math.round(waited / 1000))} s for the ${describeHeld(result.inspection)}`,
      );
    }
    if (Date.now() - lastReport >= reportMs) {
      lastReport = Date.now();
      log(
        `waiting up to ${String(Math.round((limitMs - waited) / 1000))} s more: ${describeHeld(result.inspection)}`,
      );
    }
    await sleep(Math.min(pollMs, Math.max(limitMs - waited, 1)));
  }
}
