// SPDX-License-Identifier: Apache-2.0
//
// The npm install inside the Playwright container of e2e:docker takes its packages from
// ui/pnpm-lock.yaml (e2e/stack/containerInstall.ts, SEC-73): the generated package-lock.json names
// exactly the transitive closure of the imported packages, each with the version and the sha512
// integrity of the pnpm lockfile, and anything the lockfile does not pin is refused.
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import {
  CONTAINER_PROJECT,
  containerDependencies,
  containerLockfiles,
  parsePnpmLock,
  registryTarball,
  writeContainerLockfiles,
} from './containerInstall.ts';
import { UI_ROOT } from './paths.ts';

const LOCK_TEXT = readFileSync(join(UI_ROOT, 'pnpm-lock.yaml'), 'utf8');
const PKG = JSON.parse(readFileSync(join(UI_ROOT, 'package.json'), 'utf8')) as {
  dependencies: Record<string, string>;
  devDependencies: Record<string, string>;
};

/** The integrity of `name@version` read from pnpm-lock.yaml with a plain regular expression. */
function lockIntegrity(name: string, version: string): string {
  const key = name.startsWith('@') ? `'${name}@${version}'` : `${name}@${version}`;
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = new RegExp(
    `^  ${escaped}:\\n    resolution: \\{integrity: (sha512-[^}]+)\\}`,
    'm',
  ).exec(LOCK_TEXT);
  if (match?.[1] === undefined) {
    throw new Error(`${name}@${version} not found in pnpm-lock.yaml`);
  }
  return match[1];
}

/** A small lockfile in pnpm's v9 format. */
function syntheticLock(extra: { packages?: string; snapshots?: string } = {}): string {
  return `---
lockfileVersion: '9.0'

importers:

  .:
    packageManagerDependencies:
      pnpm:
        specifier: 12.14.0
        version: 12.14.0

packages:

  pnpm@12.14.0:
    resolution: {integrity: sha512-AAAA}

snapshots:

  pnpm@12.14.0: {}

---
lockfileVersion: '9.0'

settings:
  autoInstallPeers: true

importers:

  .:
    devDependencies:
      top:
        specifier: 1.0.0
        version: 1.0.0(peer@2.0.0)

packages:

  top@1.0.0:
    resolution: {integrity: sha512-dG9w}
    engines: {node: '>=20'}
    peerDependencies:
      peer: '>= 1.0.0'

  peer@2.0.0:
    resolution: {integrity: sha512-cGVlcg==}
    hasBin: true

  helper@3.1.4:
    resolution: {integrity: sha512-aGVscGVy}

  native-mac@1.2.3:
    resolution: {integrity: sha512-bWFj}
    cpu: [arm64]
    os: [darwin]
${extra.packages ?? ''}
snapshots:

  top@1.0.0(peer@2.0.0):
    dependencies:
      helper: 3.1.4
      peer: 2.0.0
    optionalDependencies:
      native-mac: 1.2.3

  peer@2.0.0: {}

  helper@3.1.4: {}

  native-mac@1.2.3:
    optional: true
    transitivePeerDependencies:
      - supports-color
${extra.snapshots ?? ''}`;
}

const dirs: string[] = [];

afterEach(() => {
  for (const dir of dirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe('containerLockfiles from ui/pnpm-lock.yaml', () => {
  it('names exactly the closure of the imported packages, with the integrities of pnpm-lock.yaml', () => {
    const { packageJson, packageLock } = containerLockfiles(LOCK_TEXT, PKG);
    const versions = containerDependencies(PKG);
    expect(packageJson.dependencies).toEqual(versions);
    expect(packageLock.lockfileVersion).toBe(3);
    const root = packageLock.packages[''] as { name: string; dependencies: unknown };
    expect(root.name).toBe(CONTAINER_PROJECT);
    expect(root.dependencies).toEqual(packageJson.dependencies);

    const playwright = versions['@playwright/test'] ?? '';
    const axe = versions['axe-core'] ?? '';
    const expected: Record<string, string> = {
      '@axe-core/playwright': versions['@axe-core/playwright'] ?? '',
      '@playwright/test': playwright,
      'axe-core': axe,
      playwright,
      'playwright-core': playwright,
      zod: versions.zod ?? '',
    };
    expect(Object.keys(packageLock.packages).sort()).toEqual(
      ['', ...Object.keys(expected).map((n) => `node_modules/${n}`)].sort(),
    );
    for (const [name, version] of Object.entries(expected)) {
      const entry = packageLock.packages[`node_modules/${name}`] as {
        version: string;
        resolved: string;
        integrity: string;
      };
      expect(entry.version, name).toBe(version);
      expect(entry.integrity, name).toBe(lockIntegrity(name, version));
      expect(entry.resolved, name).toBe(registryTarball(name, version));
    }
    // A peer stays a peer (with its declared range), not a dependency.
    expect(packageLock.packages['node_modules/@axe-core/playwright']).toMatchObject({
      dependencies: { 'axe-core': axe },
      peerDependencies: { 'playwright-core': '>= 1.0.0' },
    });
  });

  it('is deterministic and is written as package.json + package-lock.json', () => {
    const first = containerLockfiles(LOCK_TEXT, PKG);
    expect(JSON.stringify(containerLockfiles(LOCK_TEXT, PKG))).toBe(JSON.stringify(first));
    const dir = mkdtempSync(join(tmpdir(), 'uie2e-install-'));
    dirs.push(dir);
    writeContainerLockfiles(join(dir, 'container-install'), first);
    expect(
      JSON.parse(readFileSync(join(dir, 'container-install', 'package.json'), 'utf8')),
    ).toEqual(first.packageJson);
    expect(
      JSON.parse(readFileSync(join(dir, 'container-install', 'package-lock.json'), 'utf8')),
    ).toEqual(first.packageLock);
  });

  it('refuses a version of package.json that pnpm-lock.yaml does not pin', () => {
    const pkg = { ...PKG, dependencies: { ...PKG.dependencies, zod: '4.6.8' } };
    expect(() => containerLockfiles(LOCK_TEXT, pkg)).toThrow(
      /zod@4\.6\.8 \(ui\/package\.json\) is not an importer dependency at that version/,
    );
  });

  it('refuses a package that is not in pnpm-lock.yaml and a range in package.json', () => {
    expect(() =>
      containerLockfiles(LOCK_TEXT, { devDependencies: { 'left-pad': '1.3.0' } }, ['left-pad']),
    ).toThrow(/left-pad@1\.3\.0 \(ui\/package\.json\) is not an importer dependency/);
    expect(() =>
      containerLockfiles(LOCK_TEXT, { devDependencies: { zod: '^4.6.7' } }, ['zod']),
    ).toThrow(/zod must have an exact version, has \^4\.6\.7/);
  });
});

describe('containerLockfiles (format and refusals)', () => {
  const pkg = { devDependencies: { top: '1.0.0' } };

  it('reads the main document; peers, optional platform packages and integrities as locked', () => {
    const { packageLock } = containerLockfiles(syntheticLock(), pkg, ['top']);
    expect(packageLock.packages).toEqual({
      '': { name: CONTAINER_PROJECT, version: '0.0.0', dependencies: { top: '1.0.0' } },
      'node_modules/helper': {
        version: '3.1.4',
        resolved: 'https://registry.npmjs.org/helper/-/helper-3.1.4.tgz',
        integrity: 'sha512-aGVscGVy',
      },
      'node_modules/native-mac': {
        version: '1.2.3',
        resolved: 'https://registry.npmjs.org/native-mac/-/native-mac-1.2.3.tgz',
        integrity: 'sha512-bWFj',
        optional: true,
        os: ['darwin'],
        cpu: ['arm64'],
      },
      'node_modules/peer': {
        version: '2.0.0',
        resolved: 'https://registry.npmjs.org/peer/-/peer-2.0.0.tgz',
        integrity: 'sha512-cGVlcg==',
      },
      'node_modules/top': {
        version: '1.0.0',
        resolved: 'https://registry.npmjs.org/top/-/top-1.0.0.tgz',
        integrity: 'sha512-dG9w',
        dependencies: { helper: '3.1.4' },
        optionalDependencies: { 'native-mac': '1.2.3' },
        peerDependencies: { peer: '>= 1.0.0' },
      },
    });
  });

  it('refuses a transitive package missing from packages, or one without integrity', () => {
    const missing = syntheticLock().replace(
      '  helper@3.1.4:\n    resolution: {integrity: sha512-aGVscGVy}\n',
      '',
    );
    expect(() => containerLockfiles(missing, pkg, ['top'])).toThrow(
      /packages has no entry helper@3\.1\.4/,
    );
    const noIntegrity = syntheticLock().replace(
      'resolution: {integrity: sha512-aGVscGVy}',
      'resolution: {directory: ../helper}',
    );
    expect(() => containerLockfiles(noIntegrity, pkg, ['top'])).toThrow(
      /packages\.helper@3\.1\.4 has no sha512 integrity/,
    );
  });

  it('refuses a closure that needs two versions of one package, and aliases', () => {
    const conflict = syntheticLock({
      packages: '\n  helper@3.2.0:\n    resolution: {integrity: sha512-b3RoZXI=}\n',
    }).replace('  peer@2.0.0: {}\n', '  peer@2.0.0:\n    dependencies:\n      helper: 3.2.0\n');
    expect(() => containerLockfiles(conflict, pkg, ['top'])).toThrow(
      /the closure needs helper@3\.1\.4 and helper@3\.2\.0/,
    );
    const alias = syntheticLock().replace(
      '      helper: 3.1.4\n',
      '      helper: npm:other@1.0.0\n',
    );
    expect(() => containerLockfiles(alias, pkg, ['top'])).toThrow(/is not a registry version/);
  });

  it('refuses YAML outside the pnpm subset instead of guessing', () => {
    expect(() =>
      parsePnpmLock("lockfileVersion: '6.0'\npackages:\n  a: {}\nsnapshots:\n  a: {}\n"),
    ).toThrow(/lockfileVersion "6\.0" is not supported/);
    expect(() => parsePnpmLock(`${syntheticLock()}\n  extra:\n    - key: value\n`)).toThrow(
      /only block sequences of scalars are supported/,
    );
    expect(() => parsePnpmLock(`${syntheticLock()}\n  extra:\n    a: b\n    - item\n`)).toThrow(
      /a sequence item inside a map/,
    );
  });
});
