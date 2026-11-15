// SPDX-License-Identifier: Apache-2.0
//
// The npm packages the `stack` specs import inside the Linux Playwright container of e2e:docker,
// installed only as ui/pnpm-lock.yaml pins them (SEC-73: the lockfile decides what is installed).
// The container has npm but no pnpm, and the Windows node_modules of the mounted ui/ is hidden. So
// the host derives from pnpm-lock.yaml the transitive closure of the packages the specs import
// (CONTAINER_PACKAGES, at the exact versions of ui/package.json), with each package's resolved
// tarball URL and its sha512 integrity, and writes a minimal package.json + package-lock.json
// (lockfileVersion 3). The container installs them with `npm ci --ignore-scripts`, which refuses a
// tarball whose integrity differs and installs nothing that the lock does not name.
//
// The parser reads the block-YAML subset pnpm writes (lockfile v9: two-space indentation, quoted
// or plain keys, plain or single-quoted scalars, flow maps and flow sequences on one line, block
// sequences of scalars, several documents separated by `---`); anything else is refused, never
// guessed.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** The packages the stack specs and their fixtures import (Playwright, zod, axe). */
export const CONTAINER_PACKAGES: readonly string[] = [
  '@playwright/test',
  'zod',
  '@axe-core/playwright',
  'axe-core',
];

/** npm's public registry: pnpm-lock.yaml records no tarball URL for packages from it. */
export const NPM_REGISTRY = 'https://registry.npmjs.org/';

/** Name of the generated project (never published). */
export const CONTAINER_PROJECT = 'argos-e2e-container';

type YamlValue = string | boolean | YamlMap | YamlValue[];
interface YamlMap {
  [key: string]: YamlValue;
}

function fail(message: string): never {
  throw new Error(`pnpm-lock.yaml: ${message}`);
}

function unquote(raw: string): string {
  const text = raw.trim();
  if (text.startsWith("'")) {
    if (!text.endsWith("'") || text.length < 2) {
      fail(`unterminated quoted scalar ${text}`);
    }
    return text.slice(1, -1).replaceAll("''", "'");
  }
  if (text.startsWith('"')) {
    fail(`double-quoted scalars are not supported: ${text}`);
  }
  return text;
}

/** Splits a flow collection's body at top-level commas (outside quotes and nested brackets). */
function splitFlow(body: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let quoted = false;
  let start = 0;
  for (let i = 0; i < body.length; i += 1) {
    const c = body[i];
    if (c === "'") {
      quoted = !quoted;
    } else if (!quoted && (c === '{' || c === '[')) {
      depth += 1;
    } else if (!quoted && (c === '}' || c === ']')) {
      depth -= 1;
    } else if (!quoted && depth === 0 && c === ',') {
      parts.push(body.slice(start, i));
      start = i + 1;
    }
  }
  const last = body.slice(start);
  if (last.trim() !== '') {
    parts.push(last);
  }
  return parts;
}

/** Index of the `:` that ends a key (followed by a space or the end), outside quotes. */
function keyEnd(text: string): number {
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (c === "'") {
      quoted = !quoted;
    } else if (!quoted && c === ':' && (i === text.length - 1 || text[i + 1] === ' ')) {
      return i;
    }
  }
  return -1;
}

function scalarOrFlow(raw: string): YamlValue {
  const text = raw.trim();
  if (text.startsWith('{')) {
    if (!text.endsWith('}')) {
      fail(`unterminated flow map ${text}`);
    }
    const map: YamlMap = {};
    for (const part of splitFlow(text.slice(1, -1))) {
      const end = keyEnd(part.trim());
      if (end < 0) {
        fail(`flow map entry without a key: ${part}`);
      }
      const item = part.trim();
      map[unquote(item.slice(0, end))] = scalarOrFlow(item.slice(end + 1));
    }
    return map;
  }
  if (text.startsWith('[')) {
    if (!text.endsWith(']')) {
      fail(`unterminated flow sequence ${text}`);
    }
    return splitFlow(text.slice(1, -1)).map((part) => scalarOrFlow(part));
  }
  if (text === 'true' || text === 'false') {
    return text === 'true';
  }
  return unquote(text);
}

/** Parses one YAML document of the pnpm lockfile subset into nested maps. */
export function parseLockDocument(text: string): YamlMap {
  const root: YamlMap = {};
  // Each entry is a map opened by `key:` with nothing after it; `owner` and `key` locate it, so a
  // block sequence below the key (`- item` lines, e.g. transitivePeerDependencies) replaces it.
  const stack: {
    indent: number;
    map: YamlMap;
    owner?: YamlMap;
    key?: string;
    list?: YamlValue[];
  }[] = [{ indent: -1, map: root }];
  const lines = text.split(/\r?\n/);
  lines.forEach((line, index) => {
    if (line.trim() === '' || line.trimStart().startsWith('#')) {
      return;
    }
    const where = `line ${String(index + 1)}`;
    const indent = line.length - line.trimStart().length;
    const body = line.trim();
    while ((stack[stack.length - 1]?.indent ?? -1) >= indent) {
      stack.pop();
    }
    const parent = stack[stack.length - 1];
    if (parent === undefined) {
      fail(`${where}: bad indentation`);
    }
    if (body.startsWith('- ') || body === '-') {
      const item = body.slice(1).trim();
      if (item === '' || keyEnd(item) >= 0) {
        fail(`${where}: only block sequences of scalars are supported`);
      }
      if (parent.list === undefined) {
        if (parent.owner === undefined || parent.key === undefined) {
          fail(`${where}: a sequence item without a key`);
        }
        if (Object.keys(parent.map).length > 0) {
          fail(`${where}: a sequence item inside a map`);
        }
        parent.list = [];
        parent.owner[parent.key] = parent.list;
      }
      parent.list.push(scalarOrFlow(item));
      return;
    }
    if (parent.list !== undefined) {
      fail(`${where}: a map entry inside a sequence`);
    }
    const end = keyEnd(body);
    if (end < 0) {
      fail(`${where}: not a "key: value" line`);
    }
    const key = unquote(body.slice(0, end));
    const rest = body.slice(end + 1).trim();
    if (rest === '') {
      const child: YamlMap = {};
      parent.map[key] = child;
      stack.push({ indent, map: child, owner: parent.map, key });
    } else {
      parent.map[key] = scalarOrFlow(rest);
    }
  });
  return root;
}

function isMap(value: YamlValue | undefined): value is YamlMap {
  return typeof value === 'object' && !Array.isArray(value);
}

function mapAt(value: YamlValue | undefined, where: string): YamlMap {
  if (value === undefined) {
    return {};
  }
  if (!isMap(value)) {
    fail(`${where} is not a map`);
  }
  return value;
}

function stringMap(value: YamlValue | undefined, where: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, item] of Object.entries(mapAt(value, where))) {
    if (typeof item !== 'string') {
      fail(`${where}.${key} is not a string`);
    }
    out[key] = item;
  }
  return out;
}

function stringList(value: YamlValue | undefined, where: string): string[] | undefined {
  if (value === undefined) {
    return undefined;
  }
  if (!Array.isArray(value) || value.some((v) => typeof v !== 'string')) {
    fail(`${where} is not a list of strings`);
  }
  return value as string[];
}

/** The main document of a pnpm lockfile (the one with the importers' dependencies). */
export function parsePnpmLock(text: string): YamlMap {
  const documents = text
    .split(/^---[ \t]*$/m)
    .filter((d) => d.trim() !== '')
    .map((d) => parseLockDocument(d));
  const main = documents.filter((d) => isMap(d.packages) && isMap(d.snapshots)).pop();
  if (main === undefined) {
    fail('no document with packages and snapshots');
  }
  if (main.lockfileVersion !== '9.0') {
    fail(`lockfileVersion ${JSON.stringify(main.lockfileVersion)} is not supported (9.0)`);
  }
  return main;
}

interface PackageJsonLike {
  readonly dependencies?: Record<string, string>;
  readonly devDependencies?: Record<string, string>;
}

/** The exact versions of `names` in ui/package.json (a range is refused). */
export function containerDependencies(
  pkg: PackageJsonLike,
  names: readonly string[] = CONTAINER_PACKAGES,
): Record<string, string> {
  const all = { ...pkg.dependencies, ...pkg.devDependencies };
  const out: Record<string, string> = {};
  for (const name of names) {
    const version = all[name];
    if (version === undefined || !/^\d+\.\d+\.\d+$/.test(version)) {
      throw new Error(`package.json: ${name} must have an exact version, has ${String(version)}`);
    }
    out[name] = version;
  }
  return out;
}

/** Tarball URL of a registry package (npm's layout: <name>/-/<name without scope>-<version>.tgz). */
export function registryTarball(name: string, version: string): string {
  const base = name.startsWith('@') ? name.slice(name.indexOf('/') + 1) : name;
  return `${NPM_REGISTRY}${name}/-/${base}-${version}.tgz`;
}

interface LockEntry {
  version: string;
  resolved: string;
  integrity: string;
  dependencies?: Record<string, string>;
  optionalDependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
  optional?: true;
  os?: string[];
  cpu?: string[];
  libc?: string[];
}

export interface ContainerLockfiles {
  readonly packageJson: {
    readonly name: string;
    readonly version: string;
    readonly private: true;
    readonly dependencies: Record<string, string>;
  };
  readonly packageLock: {
    readonly name: string;
    readonly version: string;
    readonly lockfileVersion: 3;
    readonly requires: true;
    readonly packages: Record<string, unknown>;
  };
}

function sorted<T>(record: Record<string, T>): Record<string, T> {
  return Object.fromEntries(
    Object.entries(record).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  );
}

/** `version(peer@x)(...)` of a snapshot reference -> `version`; refuses aliases and links. */
function baseVersion(reference: string, where: string): string {
  const version = reference.split('(')[0] ?? '';
  if (!/^\d+\.\d+\.\d+([-+][0-9A-Za-z.+-]+)?$/.test(version)) {
    fail(
      `${where}: "${reference}" is not a registry version (aliases and links are not supported)`,
    );
  }
  return version;
}

/**
 * package.json and package-lock.json (v3) of the container install: `names` at their exact
 * versions in `pkg`, and their transitive closure from the pnpm lockfile `lockText`, flat under
 * node_modules/. Throws when a package or version is not in the lockfile, when a package has no
 * integrity, or when the closure needs two versions of one package.
 */
export function containerLockfiles(
  lockText: string,
  pkg: PackageJsonLike,
  names: readonly string[] = CONTAINER_PACKAGES,
): ContainerLockfiles {
  const lock = parsePnpmLock(lockText);
  const wanted = containerDependencies(pkg, names);
  const importer = mapAt(mapAt(lock.importers, 'importers')['.'], 'importers..');
  const direct: Record<string, YamlMap> = {
    ...mapAt(importer.optionalDependencies, 'importers...optionalDependencies'),
    ...mapAt(importer.devDependencies, 'importers...devDependencies'),
    ...mapAt(importer.dependencies, 'importers...dependencies'),
  } as Record<string, YamlMap>;
  const packages = mapAt(lock.packages, 'packages');
  const snapshots = mapAt(lock.snapshots, 'snapshots');

  const dependencyEdges = (
    name: string,
    reference: string,
    optional: boolean,
  ): { name: string; reference: string; optional: boolean }[] => {
    const where = `${name}@${reference}`;
    const snapshot = mapAt(snapshots[where], `snapshots.${where}`);
    return [
      ...Object.entries(stringMap(snapshot.dependencies, `snapshots.${where}.dependencies`)).map(
        ([dep, ref]) => ({ name: dep, reference: ref, optional }),
      ),
      ...Object.entries(
        stringMap(snapshot.optionalDependencies, `snapshots.${where}.optionalDependencies`),
      ).map(([dep, ref]) => ({ name: dep, reference: ref, optional: true })),
    ];
  };

  // Breadth-first over snapshot keys; a package reached through a required edge is required.
  const entries = new Map<string, LockEntry>();
  const required = new Set<string>();
  const queue: { name: string; reference: string; optional: boolean }[] = [];
  for (const [name, version] of Object.entries(wanted)) {
    const entry = direct[name];
    if (!isMap(entry) || entry.specifier !== version || typeof entry.version !== 'string') {
      fail(`${name}@${version} (ui/package.json) is not an importer dependency at that version`);
    }
    queue.push({ name, reference: entry.version, optional: false });
  }
  for (let next = queue.shift(); next !== undefined; next = queue.shift()) {
    const { name, reference, optional } = next;
    const where = `${name}@${reference}`;
    const version = baseVersion(reference, where);
    const existing = entries.get(name);
    if (existing !== undefined) {
      if (existing.version !== version) {
        fail(`the closure needs ${name}@${existing.version} and ${name}@${version} (not flat)`);
      }
      if (!optional && !required.has(name)) {
        required.add(name);
        delete existing.optional;
        // Its own dependencies become required too.
        queue.push(...dependencyEdges(name, reference, false));
      }
      continue;
    }
    const meta = packages[`${name}@${version}`];
    if (!isMap(meta)) {
      fail(`packages has no entry ${name}@${version}`);
    }
    const resolution = mapAt(meta.resolution, `packages.${name}@${version}.resolution`);
    const integrity = resolution.integrity;
    if (typeof integrity !== 'string' || !/^sha512-[A-Za-z0-9+/]+=*$/.test(integrity)) {
      fail(`packages.${name}@${version} has no sha512 integrity`);
    }
    const tarball = resolution.tarball;
    const entry: LockEntry = {
      version,
      resolved: typeof tarball === 'string' ? tarball : registryTarball(name, version),
      integrity,
    };
    const peers = stringMap(meta.peerDependencies, `packages.${name}@${version}.peerDependencies`);
    if (snapshots[where] === undefined) {
      fail(`snapshots has no entry ${where}`);
    }
    const snapshot = mapAt(snapshots[where], `snapshots.${where}`);
    const deps = stringMap(snapshot.dependencies, `snapshots.${where}.dependencies`);
    const optionalDeps = stringMap(
      snapshot.optionalDependencies,
      `snapshots.${where}.optionalDependencies`,
    );
    const dependencies: Record<string, string> = {};
    const peerDependencies: Record<string, string> = {};
    for (const [dep, ref] of Object.entries(deps)) {
      const range = peers[dep];
      if (range !== undefined) {
        peerDependencies[dep] = range;
      } else {
        dependencies[dep] = baseVersion(ref, `snapshots.${where}.dependencies.${dep}`);
      }
    }
    const optionalDependencies: Record<string, string> = {};
    for (const [dep, ref] of Object.entries(optionalDeps)) {
      optionalDependencies[dep] = baseVersion(
        ref,
        `snapshots.${where}.optionalDependencies.${dep}`,
      );
    }
    if (Object.keys(dependencies).length > 0) {
      entry.dependencies = sorted(dependencies);
    }
    if (Object.keys(optionalDependencies).length > 0) {
      entry.optionalDependencies = sorted(optionalDependencies);
    }
    if (Object.keys(peerDependencies).length > 0) {
      entry.peerDependencies = sorted(peerDependencies);
    }
    if (optional) {
      entry.optional = true;
    } else {
      required.add(name);
    }
    const os = stringList(meta.os, `packages.${name}@${version}.os`);
    const cpu = stringList(meta.cpu, `packages.${name}@${version}.cpu`);
    const libc = stringList(meta.libc, `packages.${name}@${version}.libc`);
    if (os !== undefined) {
      entry.os = os;
    }
    if (cpu !== undefined) {
      entry.cpu = cpu;
    }
    if (libc !== undefined) {
      entry.libc = libc;
    }
    entries.set(name, entry);
    queue.push(...dependencyEdges(name, reference, optional));
  }

  const dependencies = sorted(wanted);
  const lockPackages: Record<string, unknown> = {
    '': { name: CONTAINER_PROJECT, version: '0.0.0', dependencies },
  };
  for (const [name, entry] of [...entries.entries()].sort(([a], [b]) =>
    a < b ? -1 : a > b ? 1 : 0,
  )) {
    lockPackages[`node_modules/${name}`] = entry;
  }
  return {
    packageJson: { name: CONTAINER_PROJECT, version: '0.0.0', private: true, dependencies },
    packageLock: {
      name: CONTAINER_PROJECT,
      version: '0.0.0',
      lockfileVersion: 3,
      requires: true,
      packages: lockPackages,
    },
  };
}

/** Writes package.json and package-lock.json into `dir` (created). */
export function writeContainerLockfiles(dir: string, files: ContainerLockfiles): void {
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, 'package.json'), `${JSON.stringify(files.packageJson, null, 2)}\n`);
  writeFileSync(join(dir, 'package-lock.json'), `${JSON.stringify(files.packageLock, null, 2)}\n`);
}
