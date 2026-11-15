// SPDX-License-Identifier: Apache-2.0
//
// The stack refuses a missing or stale server boot jar (e2e/stack/jars.ts), naming the Gradle
// command that builds it.
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { BUILD_COMMAND, bootJarProblem, projectVersion, snapshotJars, stackJars } from './jars.ts';

const roots: string[] = [];

function fakeRepo(): string {
  // No "argos" prefix: the agent harness rejects agent-like entries in the system temp directory.
  const root = mkdtempSync(join(tmpdir(), 'uie2e-jars-'));
  roots.push(root);
  writeFileSync(join(root, 'gradle.properties'), 'group=x\nversion=1.2.3-SNAPSHOT\n');
  const files = [
    'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar',
    'agent/agent-dist/build/libs/argos-agent-1.2.3-SNAPSHOT.jar',
    'sample-apps/shop-boot3/build/sample-app/shop-boot3.jar',
    'sample-apps/ext-stub/build/sample-app/ext-stub.jar',
    'sample-apps/smoke/build/sample-app/smoke.jar',
    'ui/src/main.tsx',
    'server/src/main/java/A.java',
  ];
  for (const file of files) {
    const path = join(root, file);
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, 'x');
  }
  return root;
}

function setTime(root: string, file: string, epochSeconds: number): void {
  utimesSync(join(root, file), epochSeconds, epochSeconds);
}

afterEach(() => {
  for (const root of roots.splice(0)) {
    rmSync(root, { recursive: true, force: true });
  }
});

describe('E2E stack jars', () => {
  it('reads the project version', () => {
    expect(projectVersion(fakeRepo())).toBe('1.2.3-SNAPSHOT');
  });

  it('accepts a boot jar newer than every input', () => {
    const root = fakeRepo();
    setTime(root, 'ui/src/main.tsx', 1_000);
    setTime(root, 'server/src/main/java/A.java', 1_000);
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 2_000);
    const jars = stackJars({}, root);
    expect(jars.server).toContain('argos-server-1.2.3-SNAPSHOT.jar');
    expect(jars.warnings).toEqual([]);
  });

  it('refuses a boot jar older than a UI source, with the rebuild command', () => {
    const root = fakeRepo();
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 1_000);
    setTime(root, 'server/src/main/java/A.java', 500);
    setTime(root, 'ui/src/main.tsx', 2_000);
    expect(() => stackJars({}, root)).toThrow(/older than ui\/src\/main\.tsx.*:server:bootJar/);
    expect(stackJars({ ARGOS_E2E_ALLOW_STALE_JARS: '1' }, root).warnings).toHaveLength(1);
  });

  it('refuses a missing jar with the Gradle command', () => {
    const root = fakeRepo();
    setTime(root, 'ui/src/main.tsx', 1_000);
    setTime(root, 'server/src/main/java/A.java', 1_000);
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 2_000);
    rmSync(join(root, 'sample-apps/smoke/build/sample-app/smoke.jar'));
    expect(() => stackJars({}, root)).toThrow(/:sample-apps:smoke:sampleAppJar/);
    expect(bootJarProblem(null, null, 'x.jar')).toContain(BUILD_COMMAND);
  });

  it('copies the jars into the run directory, so a rebuild cannot change a running stack', () => {
    const root = fakeRepo();
    setTime(root, 'ui/src/main.tsx', 1_000);
    setTime(root, 'server/src/main/java/A.java', 1_000);
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 2_000);
    const jars = stackJars({}, root);
    const copies = snapshotJars(jars, join(root, 'run', 'jars'));
    expect(copies.server).toBe(join(root, 'run', 'jars', 'argos-server.jar'));
    expect(copies.agent).toBe(join(root, 'run', 'jars', 'argos-agent.jar'));
    writeFileSync(jars.server, 'rebuilt');
    expect(readFileSync(copies.server, 'utf8')).not.toBe('rebuilt');
  });
});
