// SPDX-License-Identifier: Apache-2.0
//
// The stack refuses a missing or stale server boot jar (e2e/stack/jars.ts), naming the Gradle
// command that builds it, and a boot jar without the UI (ADR-0010 C-227 (5)).
import { mkdirSync, mkdtempSync, readFileSync, rmSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { crc32 } from 'node:zlib';

import { afterEach, describe, expect, it } from 'vitest';

import {
  BOOT_JAR_UI_ENTRY,
  BUILD_COMMAND,
  bootJarProblem,
  bootJarUiProblem,
  isTestOnlyInput,
  projectVersion,
  snapshotJars,
  stackJars,
  zipEntryNames,
} from './jars.ts';

const roots: string[] = [];

/** The entries of a boot jar with the UI (a few of the real layout). */
const BOOT_JAR_ENTRIES = [
  'META-INF/MANIFEST.MF',
  'BOOT-INF/classes/application.yml',
  BOOT_JAR_UI_ENTRY,
  'BOOT-INF/classes/static/assets/index-abc.js',
  'BOOT-INF/lib/spring-core.jar',
];

interface ZipOptions {
  /** Write the ZIP64 end record and locator (entry count and offsets 0xFFFF / 0xFFFFFFFF). */
  readonly zip64?: boolean;
  /** Bytes before the archive (a launch script). */
  readonly prefix?: string;
  /** Archive comment. */
  readonly comment?: string;
}

/** A stored (uncompressed) zip of `names`, each holding its own name; no dependency. */
function zipBytes(names: readonly string[], options: ZipOptions = {}): Buffer {
  const prefix = Buffer.from(options.prefix ?? '', 'utf8');
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = prefix.length;
  for (const name of names) {
    const nameBytes = Buffer.from(name, 'utf8');
    const data = Buffer.from(name, 'utf8');
    const crc = crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    locals.push(local, nameBytes, data);
    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const directory = Buffer.concat(centrals);
  const cdOffset = offset;
  const trailer: Buffer[] = [];
  if (options.zip64 === true) {
    const record = Buffer.alloc(56);
    record.writeUInt32LE(0x06064b50, 0);
    record.writeBigUInt64LE(44n, 4);
    record.writeUInt16LE(45, 12);
    record.writeUInt16LE(45, 14);
    record.writeBigUInt64LE(BigInt(names.length), 24);
    record.writeBigUInt64LE(BigInt(names.length), 32);
    record.writeBigUInt64LE(BigInt(directory.length), 40);
    record.writeBigUInt64LE(BigInt(cdOffset), 48);
    const locator = Buffer.alloc(20);
    locator.writeUInt32LE(0x07064b50, 0);
    locator.writeBigUInt64LE(BigInt(cdOffset + directory.length), 8);
    locator.writeUInt32LE(1, 16);
    trailer.push(record, locator);
  }
  const comment = Buffer.from(options.comment ?? '', 'utf8');
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  const count = options.zip64 === true ? 0xffff : names.length;
  end.writeUInt16LE(count, 8);
  end.writeUInt16LE(count, 10);
  end.writeUInt32LE(options.zip64 === true ? 0xffffffff : directory.length, 12);
  end.writeUInt32LE(options.zip64 === true ? 0xffffffff : cdOffset, 16);
  end.writeUInt16LE(comment.length, 20);
  return Buffer.concat([prefix, ...locals, directory, ...trailer, end, comment]);
}

function writeZip(path: string, names: readonly string[], options: ZipOptions = {}): void {
  mkdirSync(join(path, '..'), { recursive: true });
  writeFileSync(path, zipBytes(names, options));
}

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
  writeZip(join(root, SERVER_JAR), BOOT_JAR_ENTRIES);
  return root;
}

const SERVER_JAR = 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar';

/** A fake repository whose boot jar is newer than every input (only its content varies). */
function currentRepo(): string {
  const root = fakeRepo();
  setTime(root, 'ui/src/main.tsx', 1_000);
  setTime(root, 'server/src/main/java/A.java', 1_000);
  setTime(root, SERVER_JAR, 2_000);
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

  it('ignores unit-test sources, which are not in the bundle the boot jar carries', () => {
    const root = fakeRepo();
    const tests = ['ui/src/a/view.test.tsx', 'ui/src/features/x/data/testing/fixtures.ts'];
    for (const file of tests) {
      mkdirSync(join(root, file, '..'), { recursive: true });
      writeFileSync(join(root, file), 'x');
      setTime(root, file, 3_000);
    }
    setTime(root, 'ui/src/main.tsx', 1_000);
    setTime(root, 'server/src/main/java/A.java', 1_000);
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 2_000);
    expect(stackJars({}, root).warnings).toEqual([]);
    expect(isTestOnlyInput('ui/src/a/view.test.tsx')).toBe(true);
    expect(isTestOnlyInput('ui/src/features/x/data/testing/fixtures.ts')).toBe(true);
    expect(isTestOnlyInput('ui/src/features/x/data/testingTools.ts')).toBe(false);
    expect(isTestOnlyInput('ui/src/main.tsx')).toBe(false);
    setTime(root, 'ui/src/main.tsx', 4_000);
    expect(() => stackJars({}, root)).toThrow(/older than ui\/src\/main\.tsx/);
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

  it('checks the copy it starts: a server jar rewritten after the check is refused (C-228 (8))', () => {
    const root = fakeRepo();
    setTime(root, 'ui/src/main.tsx', 1_000);
    setTime(root, 'server/src/main/java/A.java', 1_000);
    setTime(root, 'server/build/libs/argos-server-1.2.3-SNAPSHOT.jar', 2_000);
    const jars = stackJars({}, root);
    // A build replaced the jar between the check and the copy with a half-written file.
    writeFileSync(jars.server, 'PK\u0003\u0004 half written');
    expect(() => snapshotJars(jars, join(root, 'run', 'jars'))).toThrow(
      /the run's copy of .*argos-server-1\.2\.3-SNAPSHOT\.jar is not a readable jar/,
    );
  });
});

describe('E2E stack jars: the boot jar carries the UI (ADR-0010 C-227 (5))', () => {
  it('reads the entry names from the zip central directory', () => {
    const root = currentRepo();
    expect(zipEntryNames(join(root, SERVER_JAR))).toEqual(BOOT_JAR_ENTRIES);
  });

  it('reads the ZIP64 form, an archive comment and a prepended launch script', () => {
    const root = currentRepo();
    const variants: ZipOptions[] = [
      { zip64: true },
      { comment: 'built by Gradle' },
      { prefix: '#!/bin/bash\nexec java -jar "$0" "$@"\n' },
      { zip64: true, prefix: '#!/bin/sh\n', comment: 'x' },
    ];
    for (const options of variants) {
      const path = join(root, 'variant.jar');
      writeZip(path, BOOT_JAR_ENTRIES, options);
      expect(zipEntryNames(path), JSON.stringify(options)).toEqual(BOOT_JAR_ENTRIES);
      expect(bootJarUiProblem(path)).toBeNull();
    }
  });

  it('accepts a boot jar with BOOT-INF/classes/static/index.html', () => {
    const root = currentRepo();
    expect(bootJarUiProblem(join(root, SERVER_JAR))).toBeNull();
    expect(stackJars({}, root).warnings).toEqual([]);
  });

  it('refuses a boot jar without the UI before the stack starts, naming -Pargos.ui.skip=true and the rebuild', () => {
    const root = currentRepo();
    // As :server:bootJar -Pargos.ui.skip=true builds it: classes and libraries, no static/.
    writeZip(
      join(root, SERVER_JAR),
      BOOT_JAR_ENTRIES.filter((name) => !name.includes('/static/')),
    );
    setTime(root, SERVER_JAR, 2_000);
    const message = bootJarUiProblem(join(root, SERVER_JAR), 'server.jar');
    expect(message).toContain('server.jar has no BOOT-INF/classes/static/index.html');
    expect(message).toContain('-Pargos.ui.skip=true');
    expect(message).toContain('./gradlew :server:bootJar');
    expect(() => stackJars({}, root)).toThrow(
      /has no BOOT-INF\/classes\/static\/index\.html.*-Pargos\.ui\.skip=true.*:server:bootJar/,
    );
    // Not a staleness question: the debugging waiver does not let it through.
    expect(() => stackJars({ ARGOS_E2E_ALLOW_STALE_JARS: '1' }, root)).toThrow(
      /static\/index\.html/,
    );
  });

  it('does not take an index.html elsewhere in the jar for the UI', () => {
    const root = currentRepo();
    writeZip(join(root, SERVER_JAR), [
      'META-INF/MANIFEST.MF',
      'BOOT-INF/classes/templates/index.html',
      'static/index.html',
    ]);
    setTime(root, SERVER_JAR, 2_000);
    expect(bootJarUiProblem(join(root, SERVER_JAR))).toContain('has no');
  });

  it('refuses a corrupt boot jar (not a zip, truncated directory, damaged header)', () => {
    const root = currentRepo();
    const path = join(root, SERVER_JAR);
    const good = zipBytes(BOOT_JAR_ENTRIES);
    const cases: [string, Buffer][] = [
      ['empty', Buffer.alloc(0)],
      ['text', Buffer.from('not a jar at all, just some text', 'utf8')],
      // The end record survives but the directory before it is cut off.
      ['truncated', Buffer.concat([good.subarray(0, 40), good.subarray(good.length - 22)])],
    ];
    const damaged = Buffer.from(good);
    const firstCentral = damaged.indexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02]));
    damaged.writeUInt32LE(0xdeadbeef, firstCentral);
    cases.push(['damaged header', damaged]);
    for (const [name, bytes] of cases) {
      writeFileSync(path, bytes);
      setTime(root, SERVER_JAR, 2_000);
      const message = bootJarUiProblem(path, 'server.jar');
      expect(message, name).toMatch(/^server\.jar is not a readable jar \(.+\): rebuild it/);
      expect(message, name).toContain('./gradlew :server:bootJar');
      expect(() => stackJars({}, root), name).toThrow(/is not a readable jar/);
    }
  });
});
