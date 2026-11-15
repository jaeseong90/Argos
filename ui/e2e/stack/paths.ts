// SPDX-License-Identifier: Apache-2.0
//
// Locations of the E2E stack (docs/18 §13): the repository, the ui/ directory and the git-ignored
// working directory ui/e2e/.stack/ that holds the state of the running stack (run.json), the seed
// manifest (seed-manifest.json) and the per-run secrets and logs (ui/e2e/.stack/<run id>/). Every
// module of the stack takes its paths from here, so the Playwright setup project, the CLI
// (e2e/stack/cli.ts) and the Linux Playwright container (e2e:docker, which mounts ui/) agree.
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** The ui/ directory. */
export const UI_ROOT = resolve(HERE, '..', '..');

/** The repository root. */
export const REPO_ROOT = resolve(UI_ROOT, '..');

/** Git-ignored working directory of the stack (ui/e2e/.gitignore). */
export const STACK_DIR = join(UI_ROOT, 'e2e', '.stack');

/** State of the running stack: run id, URLs, container names (never a secret). */
export const RUN_FILE = join(STACK_DIR, 'run.json');

/** The seed manifest the specs read (e2e/stack/manifest.ts). */
export const MANIFEST_FILE = join(STACK_DIR, 'seed-manifest.json');

/** Directory of one run: secrets/, logs/, querydata/, seed-traffic/. Deleted at teardown. */
export function runDir(runId: string): string {
  return join(STACK_DIR, runId);
}

/** Password, token and credential files of one run (bind-mounted read-only into the server). */
export function secretsDir(runId: string): string {
  return join(runDir(runId), 'secrets');
}

/** Copies of the jars one run started with (e2e/stack/jars.ts snapshotJars). Deleted at teardown. */
export function jarsDir(runId: string): string {
  return join(runDir(runId), 'jars');
}

/** Process and container logs of one run (kept with ARGOS_E2E_KEEP=1, removed otherwise). */
export function logsDir(runId: string): string {
  return join(runDir(runId), 'logs');
}

/**
 * The record of the server CSP counter's positive control (e2e/specs/csp-report.spec.ts): the
 * increase the control caused, which the teardown subtracts (e2e/stack/cspCounter.ts).
 */
export function cspControlFile(runId: string): string {
  return join(runDir(runId), 'csp-control.json');
}

/**
 * package.json and package-lock.json of the npm install inside the Playwright container of
 * e2e:docker, derived from pnpm-lock.yaml (e2e/stack/containerInstall.ts). Deleted after the run.
 */
export function containerInstallDir(runId: string): string {
  return join(runDir(runId), 'container-install');
}

/** File name of one role's password inside the secrets directory. */
export function credentialFileName(role: string): string {
  return `user-${role}.password`;
}

/** The file of one role's password: e2e/stack/seed/users.ts writes it, the auth fixture reads it. */
export function credentialFile(runId: string, role: string): string {
  return join(secretsDir(runId), credentialFileName(role));
}
