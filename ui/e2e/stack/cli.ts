// SPDX-License-Identifier: Apache-2.0
//
// Command line of the E2E stack (Node runs this TypeScript file directly: erasable syntax only).
//
//   node e2e/stack/cli.ts up       start and seed a stack and leave it running (detached); reuse it
//                                  with ARGOS_E2E_STACK_URL=<printed URL> corepack pnpm e2e:stack
//   node e2e/stack/cli.ts restart-server
//                                  restart the server of that stack with the current boot jar (after
//                                  ./gradlew :server:bootJar; the seeded databases stay)
//   node e2e/stack/cli.ts down     remove the stack of ui/e2e/.stack/run.json (only objects labelled
//                                  io.argos.e2e=<its run id>)
//   node e2e/stack/cli.ts docker [playwright args]
//                                  start and seed a stack, run the `stack` project inside the Linux
//                                  Playwright image pinned by digest (e2e/stack/images.ts) with
//                                  --network container:<server>, so the browser still opens
//                                  http://127.0.0.1:<port> (a secure context), then remove the stack.
//                                  The visual-regression baselines are created and compared only
//                                  there (pass --update-snapshots=missing to create missing ones,
//                                  --update-snapshots=all to remake them after a UI change). The npm
//                                  packages inside the image come from pnpm-lock.yaml with their
//                                  integrities (e2e/stack/containerInstall.ts, npm ci).
//   node e2e/stack/cli.ts docker-fresh [playwright args]
//                                  the same for the fresh-install project (WO-46, ADR-0010 C-192
//                                  (1)): a fresh stack (e2e/stack/fresh.ts: no administrator, a
//                                  setup token) and --project=fresh-install inside the pinned image
// up, restart-server, docker and docker-fresh hold the working tree's server-it lock (e2e/stack/lock.ts, ADR-0010
// C-235 (8)) while they build, start or run the stack; up releases it once the detached stack is
// seeded (no process of it lives on to own the lock).
// Exit code: 0 success, 1 failure (the Playwright exit code for docker), 2 usage error.
import { readFileSync, rmSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import { containerLockfiles, writeContainerLockfiles } from './containerInstall.ts';
import { NAME_PREFIX, removeRun, run, STACK_LABEL } from './docker.ts';
import { setUpFreshStack, tearDownFreshStack } from './fresh.ts';
import { PLAYWRIGHT_IMAGE } from './images.ts';
import { acquireLock, SERVER_IT_LOCK } from './lock.ts';
import { containerInstallDir, RUN_FILE, UI_ROOT } from './paths.ts';
import { DETACHED_OWNER, readRunState, removeRunState } from './runState.ts';
import type { StackSession } from './session.ts';
import { setUpStack, tearDownStack } from './setup.ts';
import { PLAYWRIGHT_MEMORY_BYTES, restartServer, stopStack } from './stack.ts';

const log = (line: string): void => {
  process.stdout.write(`[e2e-stack] ${line}\n`);
};

async function up(): Promise<number> {
  const session = await setUpStack(log, DETACHED_OWNER);
  log(`stack ${session.run_id} is running at ${session.stack_url}`);
  log(`reuse it:   ARGOS_E2E_STACK_URL=${session.stack_url} corepack pnpm e2e:stack`);
  log('remove it:  node e2e/stack/cli.ts down');
  return 0;
}

async function restart(): Promise<number> {
  const state = readRunState();
  if (state === null || state.reused) {
    log(
      `${RUN_FILE} names no stack of this working tree: start one with "node e2e/stack/cli.ts up"`,
    );
    return 1;
  }
  // Reads the tree's boot jar and starts the server: under the server-it lock (C-235 (8)).
  const lock = await acquireLock(SERVER_IT_LOCK, {
    purpose: `ui e2e stack ${state.run_id}: restart-server with the current boot jar`,
    log,
  });
  try {
    const next = await restartServer(state, log);
    log(`reuse it:   ARGOS_E2E_STACK_URL=${next.stack_url} corepack pnpm e2e:stack`);
  } finally {
    lock.release();
  }
  return 0;
}

async function down(): Promise<number> {
  const state = readRunState();
  if (state === null) {
    log(`${RUN_FILE} does not exist: no stack of this working tree is known`);
    return 0;
  }
  await stopStack({ ...state, reused: false }, false, log);
  await removeRun(state.run_id);
  removeRunState();
  return 0;
}

/** Single-quotes `value` for bash. */
export function shellQuote(value: string): string {
  return `'${value.replaceAll("'", "'\\''")}'`;
}

async function dockerRun(
  args: readonly string[],
  variant: 'seeded' | 'fresh' = 'seeded',
): Promise<number> {
  const pkg = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8')) as {
    dependencies?: Record<string, string>;
    devDependencies?: Record<string, string>;
  };
  // Before the stack starts: a package.json / pnpm-lock.yaml mismatch fails in seconds.
  const lockfiles = containerLockfiles(readFileSync(join(UI_ROOT, 'pnpm-lock.yaml'), 'utf8'), pkg);
  const session =
    variant === 'fresh'
      ? await setUpFreshStack(log, process.pid)
      : await setUpStack(log, process.pid);
  // The project the container runs and how its setup project finds the stack started here.
  const project = variant === 'fresh' ? 'fresh-install' : 'stack';
  const reuseVariable = variant === 'fresh' ? 'ARGOS_E2E_FRESH_URL' : 'ARGOS_E2E_STACK_URL';
  let code = 1;
  let installDir: string | null = null;
  try {
    const state = readRunState();
    if (state?.containers == null) {
      throw new Error('the stack did not record its containers');
    }
    // The Windows node_modules of the mounted ui/ is hidden by an empty tmpfs (its pnpm links do
    // not resolve on Linux). The container installs only what the stack specs import, with the
    // versions and sha512 integrities of pnpm-lock.yaml (e2e/stack/containerInstall.ts: `npm ci`
    // refuses a tarball whose integrity differs), into /work/node_modules, where Node's resolution
    // from /work/ui finds them; the image's Node.js runs Playwright (the image has no corepack, so
    // no pnpm). The generated lock is in the run's directory below the mounted ui/, deleted after.
    installDir = containerInstallDir(state.run_id);
    writeContainerLockfiles(installDir, lockfiles);
    const mounted = `/work/ui/${relative(UI_ROOT, installDir).split(sep).join('/')}`;
    const script = [
      'set -e',
      `cp ${shellQuote(`${mounted}/package.json`)} ${shellQuote(`${mounted}/package-lock.json`)} /work/`,
      'cd /work',
      'npm ci --ignore-scripts --no-audit --no-fund --update-notifier=false --loglevel=error',
      'cd /work/ui',
      `exec node /work/node_modules/@playwright/test/cli.js test --config /work/ui/playwright.config.ts --project=${project} ${args.map(shellQuote).join(' ')}`,
    ].join('\n');
    const result = await run(
      'docker',
      [
        'run',
        '--rm',
        '--name',
        `${NAME_PREFIX}${state.run_id}-playwright`,
        '--label',
        `${STACK_LABEL}=${state.run_id}`,
        '--network',
        `container:${state.containers.server}`,
        '--memory',
        String(PLAYWRIGHT_MEMORY_BYTES),
        '--memory-swap',
        String(PLAYWRIGHT_MEMORY_BYTES),
        '--shm-size',
        '512m',
        '--mount',
        `type=bind,source=${UI_ROOT},target=/work/ui`,
        '--tmpfs',
        '/work/ui/node_modules',
        '--workdir',
        '/work/ui',
        '-e',
        `${reuseVariable}=${session.stack_url}`,
        // The management port inside the server's network namespace (published elsewhere on the host).
        '-e',
        'ARGOS_E2E_MGMT_URL=http://127.0.0.1:8081',
        '-e',
        `ARGOS_E2E_LINUX_IMAGE=${PLAYWRIGHT_IMAGE}`,
        // CI: blob reports for the merge of shards and forbidOnly (playwright.config.ts).
        ...(process.env.CI === undefined ? [] : ['-e', 'CI=1']),
        PLAYWRIGHT_IMAGE,
        'bash',
        '-c',
        script,
      ],
      {
        allowFailure: true,
        timeoutMs: 60 * 60_000,
        onOutput: (chunk) => {
          process.stdout.write(chunk);
        },
      },
    );
    code = result.code;
    log(`Playwright in ${PLAYWRIGHT_IMAGE} exited with ${String(code)}`);
  } finally {
    if (installDir !== null) {
      rmSync(installDir, { recursive: true, force: true });
    }
    code = variant === 'fresh' ? await endFresh(session, code) : await endSeeded(session, code);
  }
  return code;
}

/** Removes the fresh stack; any growth of the CSP counter fails the run (no positive control). */
async function endFresh(session: StackSession, code: number): Promise<number> {
  const result = await tearDownFreshStack(log, process.env, session);
  log(
    `server CSP counter of the fresh-install run: increase ${String(result.cspIncrease)}${result.cspIncrease === null ? ` (${String(result.cspProblem)})` : ''}`,
  );
  return (result.cspIncrease ?? 0) > 0 && code === 0 ? 1 : code;
}

/** Removes the seeded stack and judges its CSP counter (one-sided, ADR-0010 C-188). */
async function endSeeded(session: StackSession, code: number): Promise<number> {
  const result = await tearDownStack(log, process.env, session);
  // One-sided (ADR-0010 C-188): only a growth beyond the positive controls' reports is a finding.
  const verdict = result.cspVerdict;
  if (verdict.kind === 'violations') {
    log(
      `T-SEC-46: argos_csp_violations_total rose by ${String(verdict.increase)} beyond the positive controls' reports during the run`,
    );
    return code === 0 ? 1 : code;
  }
  if (verdict.kind === 'unread') {
    log(`server CSP counter (informational): ${verdict.reason}`);
  } else {
    log(
      `server CSP counter (informational): increase ${String(result.cspIncrease)}, positive controls' reports at most ${String(result.cspControl?.expected_increase ?? 0)}`,
    );
  }
  return code;
}

async function main(argv: readonly string[]): Promise<number> {
  const [command, ...rest] = argv;
  switch (command) {
    case 'up':
      return up();
    case 'down':
      return down();
    case 'restart-server':
      return restart();
    case 'docker':
      return dockerRun(rest[0] === '--' ? rest.slice(1) : rest);
    case 'docker-fresh':
      return dockerRun(rest[0] === '--' ? rest.slice(1) : rest, 'fresh');
    default:
      process.stderr.write(
        'usage: node e2e/stack/cli.ts up | down | restart-server | docker [playwright args] | docker-fresh [playwright args]\n',
      );
      return 2;
  }
}

main(process.argv.slice(2)).then(
  (code) => {
    process.exitCode = code;
  },
  (error: unknown) => {
    process.stderr.write(`[e2e-stack] ${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  },
);
