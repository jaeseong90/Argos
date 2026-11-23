// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { PERF_DIST, perfBuildProblem, pocBuildProblem } from './pocBuild';

const roots: string[] = [];

function root(files: readonly string[]): string {
  const dir = mkdtempSync(join(tmpdir(), 'pocbuild-'));
  roots.push(dir);
  if (files.length > 0) {
    mkdirSync(join(dir, PERF_DIST, 'assets'), { recursive: true });
    writeFileSync(join(dir, PERF_DIST, 'index.html'), '');
    for (const file of files) {
      writeFileSync(join(dir, PERF_DIST, 'assets', file), '');
    }
  }
  return dir;
}

afterEach(() => {
  for (const dir of roots.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('the perf build of the poc target (ADR-0010 C-203)', () => {
  it('accepts the perf build with its /xlog-poc chunk', () => {
    const dir = root(['index-a.js', 'XLogPocPage-BtEmy3ob.js']);
    expect(pocBuildProblem(dir)).toBeNull();
    expect(perfBuildProblem(dir)).toBeNull();
  });

  it('refuses a production build and a missing build', () => {
    expect(pocBuildProblem(root(['index-a.js', 'XLogPocPage-x.css']))).toMatch(
      /needs the perf build \(ADR-0010 C-203/,
    );
    expect(pocBuildProblem(root([]))).toMatch(/has no \/xlog-poc chunk/);
    expect(perfBuildProblem(root([]))).toMatch(/build\/perf-dist\/index.html does not exist/);
  });

  it('refuses a perf build older than a source file', () => {
    const dir = root(['XLogPocPage-a.js']);
    mkdirSync(join(dir, 'src'));
    writeFileSync(join(dir, 'src', 'main.tsx'), '');
    const old = new Date(Date.now() - 60_000);
    utimesSync(join(dir, PERF_DIST, 'index.html'), old, old);
    expect(perfBuildProblem(dir)).toMatch(/is older than src\/main\.tsx/);
  });
});
