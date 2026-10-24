// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { BUILD_INPUTS, checkDist, DIST_INDEX, distProblem, newestFile } from './distCheck';

const T0 = Date.UTC(2026, 9, 23, 12, 0, 0);

describe('distProblem', () => {
  it('accepts a build that is as new as or newer than every input', () => {
    expect(distProblem({ path: DIST_INDEX, mtimeMs: T0 }, { path: 'src/a.ts', mtimeMs: T0 })).toBe(
      null,
    );
    expect(
      distProblem({ path: DIST_INDEX, mtimeMs: T0 + 1 }, { path: 'src/a.ts', mtimeMs: T0 }),
    ).toBe(null);
    expect(distProblem({ path: DIST_INDEX, mtimeMs: T0 }, null)).toBe(null);
  });

  it('rejects a missing build and tells how to build', () => {
    const problem = distProblem(null, { path: 'src/a.ts', mtimeMs: T0 });
    expect(problem).toContain('dist/index.html does not exist');
    expect(problem).toContain('corepack pnpm -C ui build');
  });

  it('rejects a build older than an input and names the input', () => {
    const problem = distProblem(
      { path: DIST_INDEX, mtimeMs: T0 },
      { path: 'src/xlog/engine.ts', mtimeMs: T0 + 1000 },
    );
    expect(problem).toContain('older than src/xlog/engine.ts');
    expect(problem).toContain('corepack pnpm -C ui build');
  });
});

let dir: string | null = null;

afterEach(() => {
  if (dir !== null) {
    rmSync(dir, { recursive: true, force: true });
    dir = null;
  }
});

function file(root: string, path: string, mtimeMs: number): void {
  const full = join(root, path);
  mkdirSync(dirname(full), { recursive: true });
  writeFileSync(full, 'x');
  utimesSync(full, mtimeMs / 1000, mtimeMs / 1000);
}

describe('newestFile and checkDist', () => {
  it('walks the build inputs and compares them with dist/index.html', () => {
    dir = mkdtempSync(join(tmpdir(), 'argos-dist-check-'));
    expect(BUILD_INPUTS).toEqual(['src', 'index.html', 'vite.config.ts', 'package.json']);
    file(dir, 'index.html', T0);
    file(dir, 'package.json', T0);
    file(dir, 'vite.config.ts', T0);
    file(dir, 'src/main.tsx', T0);
    file(dir, 'src/xlog/deep/engine.ts', T0 + 5000);
    // Not a build input: ignored even when newer.
    file(dir, 'e2e/specs/x.spec.ts', T0 + 60_000);
    expect(newestFile(dir, BUILD_INPUTS)).toEqual({
      path: 'src/xlog/deep/engine.ts',
      mtimeMs: T0 + 5000,
    });
    expect(newestFile(dir, ['missing'])).toBe(null);

    expect(checkDist(dir)).toContain('dist/index.html does not exist');
    file(dir, DIST_INDEX, T0 + 1000);
    expect(checkDist(dir)).toContain('older than src/xlog/deep/engine.ts');
    file(dir, DIST_INDEX, T0 + 10_000);
    expect(checkDist(dir)).toBe(null);
    // Touching a source after the build makes it stale again.
    file(dir, 'index.html', T0 + 20_000);
    expect(checkDist(dir)).toContain('older than index.html');
  });
});
