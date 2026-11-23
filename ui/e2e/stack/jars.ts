// SPDX-License-Identifier: Apache-2.0
//
// The build outputs the E2E stack runs (docs/18 §13): the server boot jar (which carries ui/dist
// under static/, WO-20, and serves it with the per-request CSP nonce, WO-24), the agent jar and the
// sample apps of the slice traffic (shop-boot3 with ext-stub, the smoke runner behind
// test/seed/seed-traffic.sh). The stack never builds them: a missing jar, or a boot jar older than
// the UI or server sources it is made of, stops the setup with the Gradle command that builds it
// (like e2e/fixtures/globalSetup.ts refuses a stale dist/). ARGOS_E2E_ALLOW_STALE_JARS=1 turns the
// staleness refusal into a warning (debugging only: the run then tests an older UI or server).
// A boot jar without the UI (built with -Pargos.ui.skip=true: no BOOT-INF/classes/static/index.html
// in its zip central directory) is always refused before anything starts (ADR-0010 C-227 (5)): the
// stack would serve the API only and every screen test would fail on a blank page.
import {
  closeSync,
  copyFileSync,
  existsSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  readSync,
  statSync,
} from 'node:fs';
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

/**
 * Unit-test sources below ui/src (`*.test.*` files and `testing/` fixture folders): they are not
 * part of the bundle, so a change to them leaves the boot jar up to date (Gradle rebuilds ui/dist
 * with the same content and does not rewrite the jar). Counting them would refuse a current jar.
 */
export function isTestOnlyInput(path: string): boolean {
  return /\.test\.[cm]?[jt]sx?$/.test(path) || /^ui\/src\/(?:.+\/)?testing\//.test(path);
}

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

/** The UI entry the boot jar must carry (server/build.gradle.kts copies ui/dist to static/). */
export const BOOT_JAR_UI_ENTRY = 'BOOT-INF/classes/static/index.html';

const EOCD_SIGNATURE = 0x06054b50;
const EOCD_SIZE = 22;
const ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
const ZIP64_LOCATOR_SIZE = 20;
const ZIP64_EOCD_SIGNATURE = 0x06064b50;
/** The fixed part of the ZIP64 end of central directory record. */
const ZIP64_EOCD_SIZE = 56;
const CENTRAL_HEADER_SIGNATURE = 0x02014b50;
const CENTRAL_HEADER_SIZE = 46;
/** The end of central directory record plus the longest archive comment (65,535 bytes). */
const EOCD_SEARCH = EOCD_SIZE + 0xffff;
/** A central directory larger than this is not a jar of this build (refused as corrupt). */
const MAX_CENTRAL_DIRECTORY = 64 * 1024 * 1024;

function readAt(fd: number, position: number, length: number): Buffer {
  const buffer = Buffer.alloc(length);
  let done = 0;
  while (done < length) {
    const n = readSync(fd, buffer, done, length - done, position + done);
    if (n === 0) {
      throw new Error(`unexpected end of file at byte ${String(position + done)}`);
    }
    done += n;
  }
  return buffer;
}

function safeNumber(value: bigint, what: string): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new Error(`${what} out of range`);
  }
  return Number(value);
}

/**
 * The entry names of a zip file (a jar), read from its central directory only (dependency free:
 * the end of central directory record, with its ZIP64 form, then the central file headers). Data
 * prepended to the archive (a launch script) is allowed: the directory is located backwards from
 * the end record. Throws when the file is not a readable zip.
 */
export function zipEntryNames(path: string): string[] {
  const fd = openSync(path, 'r');
  try {
    const size = fstatSync(fd).size;
    if (size < EOCD_SIZE) {
      throw new Error('too short for a zip file');
    }
    const tailLength = Math.min(size, EOCD_SEARCH);
    const tailStart = size - tailLength;
    const tail = readAt(fd, tailStart, tailLength);
    let eocd = -1;
    for (let i = tailLength - EOCD_SIZE; i >= 0; i--) {
      if (tail.readUInt32LE(i) === EOCD_SIGNATURE) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) {
      throw new Error('no end of central directory record');
    }
    let entries = tail.readUInt16LE(eocd + 10);
    let cdSize = tail.readUInt32LE(eocd + 12);
    let cdEnd = tailStart + eocd;
    if (
      entries === 0xffff ||
      cdSize === 0xffffffff ||
      tail.readUInt32LE(eocd + 16) === 0xffffffff
    ) {
      // ZIP64: the locator right before the end record names the ZIP64 end record.
      const locatorAt = tailStart + eocd - ZIP64_LOCATOR_SIZE;
      if (locatorAt < 0) {
        throw new Error('ZIP64 locator missing');
      }
      const locator = readAt(fd, locatorAt, ZIP64_LOCATOR_SIZE);
      if (locator.readUInt32LE(0) !== ZIP64_LOCATOR_SIGNATURE) {
        throw new Error('ZIP64 locator missing');
      }
      // The ZIP64 end record sits right before the locator when it has no extensible data (the
      // jar tools write none); otherwise at the offset the locator names (no prepended data).
      let recordAt = locatorAt - ZIP64_EOCD_SIZE;
      let record = recordAt >= 0 ? readAt(fd, recordAt, ZIP64_EOCD_SIZE) : null;
      if (record?.readUInt32LE(0) !== ZIP64_EOCD_SIGNATURE) {
        recordAt = safeNumber(locator.readBigUInt64LE(8), 'ZIP64 end record offset');
        if (recordAt + ZIP64_EOCD_SIZE > size) {
          throw new Error('ZIP64 end of central directory record missing');
        }
        record = readAt(fd, recordAt, ZIP64_EOCD_SIZE);
      }
      if (record.readUInt32LE(0) !== ZIP64_EOCD_SIGNATURE) {
        throw new Error('ZIP64 end of central directory record missing');
      }
      entries = safeNumber(record.readBigUInt64LE(32), 'entry count');
      cdSize = safeNumber(record.readBigUInt64LE(40), 'central directory size');
      cdEnd = recordAt;
    }
    if (cdSize > MAX_CENTRAL_DIRECTORY || cdSize > cdEnd) {
      throw new Error(`central directory size ${String(cdSize)} does not fit the file`);
    }
    const directory = readAt(fd, cdEnd - cdSize, cdSize);
    const names: string[] = [];
    let offset = 0;
    for (let i = 0; i < entries; i++) {
      if (
        offset + CENTRAL_HEADER_SIZE > directory.length ||
        directory.readUInt32LE(offset) !== CENTRAL_HEADER_SIGNATURE
      ) {
        throw new Error(`central directory header ${String(i)} is damaged`);
      }
      const nameLength = directory.readUInt16LE(offset + 28);
      const extraLength = directory.readUInt16LE(offset + 30);
      const commentLength = directory.readUInt16LE(offset + 32);
      const nameStart = offset + CENTRAL_HEADER_SIZE;
      if (nameStart + nameLength > directory.length) {
        throw new Error(`central directory header ${String(i)} is damaged`);
      }
      names.push(directory.toString('utf8', nameStart, nameStart + nameLength));
      offset = nameStart + nameLength + extraLength + commentLength;
    }
    return names;
  } finally {
    closeSync(fd);
  }
}

/**
 * Why the boot jar cannot serve the UI, or null: its zip central directory has no
 * BOOT-INF/classes/static/index.html (a jar built with -Pargos.ui.skip=true), or it is not a
 * readable zip at all (ADR-0010 C-227 (5)).
 */
export function bootJarUiProblem(jarPath: string, label: string = jarPath): string | null {
  let names: string[];
  try {
    names = zipEntryNames(jarPath);
  } catch (error) {
    return (
      `${label} is not a readable jar (${(error as Error).message}): ` +
      'rebuild it, from the repository root: ./gradlew :server:bootJar'
    );
  }
  if (!names.includes(BOOT_JAR_UI_ENTRY)) {
    return (
      `${label} has no ${BOOT_JAR_UI_ENTRY}, so the stack would serve no UI: ` +
      'built with -Pargos.ui.skip=true? rebuild it with the UI, from the repository root: ' +
      './gradlew :server:bootJar'
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
    newestFile(repoRoot, BOOT_JAR_INPUTS, isTestOnlyInput),
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
  // Never waived (not even with ARGOS_E2E_ALLOW_STALE_JARS): without the UI no screen can run.
  const noUi = bootJarUiProblem(server, `server/build/libs/argos-server-${version}.jar`);
  if (noUi !== null) {
    throw new Error(noUi);
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
 * Copies the jars into the run's directory and returns the copies; the containers and processes
 * of the run bind-mount and start only these copies (ADR-0010 C-228 (8)). The other work orders
 * rebuild these build outputs while a stack runs (the server jar by :server:bootJar and Jib, the
 * agent and sample apps by their tests); the stack keeps running exactly what it started with, and
 * a running container never holds a file that Gradle wants to replace. A source that changes while
 * it is copied (a build rewriting it) is refused, and the server copy itself must be a readable
 * jar with the UI, so a torn copy never starts.
 */
export function snapshotJars(jars: StackJars, dir: string): StackJars {
  mkdirSync(dir, { recursive: true });
  const copy = (source: string, name: string): string => {
    const target = join(dir, name);
    const before = statSync(source);
    copyFileSync(source, target);
    const after = statSync(source);
    if (
      after.mtimeMs !== before.mtimeMs ||
      after.size !== before.size ||
      statSync(target).size !== before.size
    ) {
      throw new Error(
        `${source} changed while the stack copied it (a build is rewriting it): start the stack again when the build is done`,
      );
    }
    return target;
  };
  const server = copy(jars.server, 'argos-server.jar');
  const torn = bootJarUiProblem(server, `the run's copy of ${jars.server}`);
  if (torn !== null) {
    throw new Error(torn);
  }
  return {
    server,
    agent: copy(jars.agent, 'argos-agent.jar'),
    shop: copy(jars.shop, 'shop-boot3.jar'),
    extStub: copy(jars.extStub, 'ext-stub.jar'),
    smoke: copy(jars.smoke, 'smoke.jar'),
    warnings: jars.warnings,
  };
}
