// SPDX-License-Identifier: Apache-2.0
//
// The poc target of the XLog performance spec needs the perf build: a production build has no
// /xlog-poc chunk (ADR-0010 C-203 leaves the WO-04 PoC and its synthetic data generator out of the
// production bundle), only `vite build --mode perf` emits it. The perf build goes to its own
// directory, PERF_DIST (inside build/, which git, ESLint, Prettier and the Gradle inputs already
// ignore), so a production build into dist/ (pnpm build, Gradle :ui:pnpmBuild for the server jar)
// can never replace the files a running perf session serves.
import { existsSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { BUILD_INPUTS, distProblem, newestFile, type FileTime } from '../../e2e/fixtures/distCheck';

/** Output directory of the perf build, relative to ui/ (`vite build --mode perf --outDir`). */
export const PERF_DIST = 'build/perf-dist';

/** Why `<root>/<distDir>` has no /xlog-poc chunk (a production build or no build), or null. */
export function pocBuildProblem(root: string, distDir: string = PERF_DIST): string | null {
  const assets = join(root, distDir, 'assets');
  const files = existsSync(assets) ? readdirSync(assets) : [];
  return files.some((name) => name.startsWith('XLogPocPage-') && name.endsWith('.js'))
    ? null
    : `${distDir}/ has no /xlog-poc chunk: the poc target needs the perf build (ADR-0010 C-203 leaves the PoC out of production builds). Run \`corepack pnpm perf\` (vite build --mode perf --outDir ${distDir})`;
}

/** Why the perf build must not be served: missing, older than its inputs, or not a perf build. */
export function perfBuildProblem(root: string, distDir: string = PERF_DIST): string | null {
  const index = `${distDir}/index.html`;
  let built: FileTime | null;
  try {
    const stat = statSync(join(root, index));
    built = stat.isFile() ? { path: index, mtimeMs: stat.mtimeMs } : null;
  } catch {
    built = null;
  }
  if (built === null) {
    return `${index} does not exist: run \`corepack pnpm perf\` (vite build --mode perf --outDir ${distDir}), which builds before measuring`;
  }
  return distProblem(built, newestFile(root, BUILD_INPUTS)) ?? pocBuildProblem(root, distDir);
}
