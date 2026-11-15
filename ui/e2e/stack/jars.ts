// SPDX-License-Identifier: Apache-2.0
//
// The build outputs the E2E stack runs (docs/18 §13): the server boot jar (which carries ui/dist
// under static/, WO-20, and serves it with the per-request CSP nonce, WO-24), the agent jar and the
// sample apps of the slice traffic (shop-boot3 with ext-stub, the smoke runner behind
// test/seed/seed-traffic.sh). The stack never builds them: a missing jar, or a boot jar older than
// the UI or server sources it is made of, stops the setup with the Gradle command that builds it
// (like e2e/fixtures/globalSetup.ts refuses a stale dist/). ARGOS_E2E_ALLOW_STALE_JARS=1 turns the
// staleness refusal into a warning (debugging only: the run then tests an older UI or server).
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { newestFile, type FileTime } from '../fixtures/distCheck.ts';

import { REPO_ROOT } from './paths.ts';

/** The Gradle command that builds every jar of the stack. */
export const BUILD_COMMAND =
  './gradlew :server:bootJar :agent:agent-dist:agentJar :sample-apps:shop-boot3:sampleAppJar :sample-apps:ext-stub:sampleAppJar :sample-apps:smoke:sampleAppJar';

/**
 * Inputs of the server boot jar, relative to the repository root: the UI build inputs (the jar
 * holds ui/dist, built from them) and the server's main sources and the ClickHouse scripts it
 * copies (server/build.gradle.kts processResources).
 */
export const BOOT_JAR_INPUTS: readonly string[] = [
  'ui/src',
  'ui/index.html',
  'ui/vite.config.ts',
  'ui/package.json',
  'server/src/main',
  'deploy/clickhouse/schema',
  'deploy/clickhouse/rollups',
  'deploy/clickhouse/migrations',
];

export interface StackJars {
  readonly server: string;
  readonly agent: string;
  readonly shop: string;
  readonly extStub: string;
  readonly smoke: string;
  /** Warnings (a stale jar accepted with ARGOS_E2E_ALLOW_STALE_JARS=1). */
  readonly warnings: readonly string[];
}

/** The project version of gradle.properties (the jar file names carry it). */
export function projectVersion(repoRoot: string = REPO_ROOT): string {
  const text = readFileSync(join(repoRoot, 'gradle.properties'), 'utf8');
  const match = /^version=(\S+)\s*$/m.exec(text);
  if (match?.[1] === undefined) {
    throw new Error('gradle.properties has no version= line');
  }
  return match[1];
}

/** Why the boot jar must not run, or null: missing, or older than the newest input. */
export function bootJarProblem(
  jar: FileTime | null,
  newestInput: FileTime | null,
  jarPath: string,
): string | null {
  if (jar === null) {
    return `${jarPath} does not exist: build the jars first, from the repository root: ${BUILD_COMMAND}`;
  }
  if (newestInput !== null && newestInput.mtimeMs > jar.mtimeMs) {
    return (
      `${jarPath} (${new Date(jar.mtimeMs).toISOString()}) is older than ${newestInput.path} ` +
      `(${new Date(newestInput.mtimeMs).toISOString()}), so the stack would serve an older UI or server: ` +
      `rebuild it, from the repository root: ./gradlew :server:bootJar`
    );
  }
  return null;
}

function fileTime(path: string, label: string): FileTime | null {
  try {
    const stat = statSync(path);
    return stat.isFile() ? { path: label, mtimeMs: stat.mtimeMs } : null;
  } catch {
    return null;
  }
}

function requireFile(path: string, task: string): string {
  if (!existsSync(path)) {
    throw new Error(
      `${path} does not exist: build it first (${task}); all jars of the stack: ${BUILD_COMMAND}`,
    );
  }
  return path;
}

/** Locates the jars; throws with the Gradle command when one is missing or the boot jar is stale. */
export function stackJars(
  env: Readonly<Record<string, string | undefined>> = process.env,
  repoRoot: string = REPO_ROOT,
): StackJars {
  const version = projectVersion(repoRoot);
  const server = join(repoRoot, 'server', 'build', 'libs', `argos-server-${version}.jar`);
  const problem = bootJarProblem(
    fileTime(server, `server/build/libs/argos-server-${version}.jar`),
    newestFile(repoRoot, BOOT_JAR_INPUTS),
    server,
  );
  const warnings: string[] = [];
  if (problem !== null) {
    if (env.ARGOS_E2E_ALLOW_STALE_JARS === '1' && existsSync(server)) {
      warnings.push(`ARGOS_E2E_ALLOW_STALE_JARS=1: ${problem}`);
    } else {
      throw new Error(problem);
    }
  }
  return {
    server,
    agent: requireFile(
      join(repoRoot, 'agent', 'agent-dist', 'build', 'libs', `argos-agent-${version}.jar`),
      './gradlew :agent:agent-dist:agentJar',
    ),
    shop: requireFile(
      join(repoRoot, 'sample-apps', 'shop-boot3', 'build', 'sample-app', 'shop-boot3.jar'),
      './gradlew :sample-apps:shop-boot3:sampleAppJar',
    ),
    extStub: requireFile(
      join(repoRoot, 'sample-apps', 'ext-stub', 'build', 'sample-app', 'ext-stub.jar'),
      './gradlew :sample-apps:ext-stub:sampleAppJar',
    ),
    smoke: requireFile(
      join(repoRoot, 'sample-apps', 'smoke', 'build', 'sample-app', 'smoke.jar'),
      './gradlew :sample-apps:smoke:sampleAppJar',
    ),
    warnings,
  };
}

/**
 * Copies the jars into the run's directory and returns the copies. The other work orders rebuild
 * these build outputs while a stack runs (the server jar by :server:bootJar and Jib, the agent and
 * sample apps by their tests); the stack keeps running exactly what it started with, and a running
 * container never holds a file that Gradle wants to replace.
 */
export function snapshotJars(jars: StackJars, dir: string): StackJars {
  mkdirSync(dir, { recursive: true });
  const copy = (source: string, name: string): string => {
    const target = join(dir, name);
    copyFileSync(source, target);
    return target;
  };
  return {
    server: copy(jars.server, 'argos-server.jar'),
    agent: copy(jars.agent, 'argos-agent.jar'),
    shop: copy(jars.shop, 'shop-boot3.jar'),
    extStub: copy(jars.extStub, 'ext-stub.jar'),
    smoke: copy(jars.smoke, 'smoke.jar'),
    warnings: jars.warnings,
  };
}
