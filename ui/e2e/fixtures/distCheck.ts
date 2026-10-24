// SPDX-License-Identifier: Apache-2.0
//
// Build freshness guard of the Playwright suites (E2E, performance). They serve dist/ with
// `vite preview` as it is: the `e2e` and `perf` scripts build first, but `playwright test` run
// directly would test whatever build is lying around. The global setups therefore refuse to start
// when dist/index.html is missing or older than the newest build input.
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

/** Inputs of `vite build`, relative to the ui/ directory (directories are walked). */
export const BUILD_INPUTS: readonly string[] = [
  'src',
  'index.html',
  'vite.config.ts',
  'package.json',
];

/** The file whose time stamp stands for the build. */
export const DIST_INDEX = 'dist/index.html';

export interface FileTime {
  /** Path relative to the ui/ directory, with forward slashes. */
  readonly path: string;
  readonly mtimeMs: number;
}

const REBUILD_HINT =
  'run `corepack pnpm -C ui build` first, or use `corepack pnpm -C ui e2e` / `corepack pnpm -C ui perf`, which build before testing';

/**
 * Why dist/ must not be served, or null when it is at least as new as every build input.
 * `dist` is the time of dist/index.html (null when it does not exist), `newestInput` the newest
 * build input (null when there is none).
 */
export function distProblem(dist: FileTime | null, newestInput: FileTime | null): string | null {
  if (dist === null) {
    return `${DIST_INDEX} does not exist: ${REBUILD_HINT}`;
  }
  if (newestInput !== null && newestInput.mtimeMs > dist.mtimeMs) {
    return `${dist.path} (${new Date(dist.mtimeMs).toISOString()}) is older than ${newestInput.path} (${new Date(newestInput.mtimeMs).toISOString()}), so the suite would test a stale build: ${REBUILD_HINT}`;
  }
  return null;
}

function fileTime(root: string, path: string): FileTime | null {
  try {
    const stat = statSync(join(root, path));
    return stat.isFile() ? { path, mtimeMs: stat.mtimeMs } : null;
  } catch {
    return null;
  }
}

/** Newest regular file among `inputs` (files or directories, walked recursively) below `root`. */
export function newestFile(root: string, inputs: readonly string[]): FileTime | null {
  let newest: FileTime | null = null;
  const pending = [...inputs];
  for (let next = pending.pop(); next !== undefined; next = pending.pop()) {
    const path = next;
    const full = join(root, path);
    try {
      const stat = statSync(full);
      if (stat.isDirectory()) {
        pending.push(...readdirSync(full).map((entry) => `${path}/${entry}`));
      } else if (stat.isFile() && (newest === null || stat.mtimeMs > newest.mtimeMs)) {
        newest = { path, mtimeMs: stat.mtimeMs };
      }
    } catch {
      // Missing input (or removed while walking): nothing to compare.
    }
  }
  return newest;
}

/** distProblem for the ui/ directory `root`. */
export function checkDist(root: string): string | null {
  return distProblem(fileTime(root, DIST_INDEX), newestFile(root, BUILD_INPUTS));
}
