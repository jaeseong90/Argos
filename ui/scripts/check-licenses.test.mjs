// SPDX-License-Identifier: Apache-2.0
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, symlinkSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import {
  checkDevLicenses,
  checkLicenses,
  collectInstalledPackages,
  collectProductionPackages,
  devOnlyMatches,
  evaluateLicense,
  licenseOf,
  parseBundleManifest,
  parseDevOnlyNpm,
  parsePolicy,
} from './check-licenses.mjs';
import { createFixture } from './test-fixtures.mjs';

const SCRIPT = fileURLToPath(new URL('./check-licenses.mjs', import.meta.url));
const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));
const POLICY = parsePolicy(
  JSON.parse(readFileSync(join(UI_ROOT, 'scripts', 'license-policy.json'), 'utf8')),
);
const ALLOWED = POLICY.allowed;

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

/**
 * @param {Record<string, string | object>} files
 */
function fixture(files) {
  const created = createFixture(files);
  cleanups.push(created.cleanup);
  return created.root;
}

/**
 * @param {string} name
 * @param {string} license
 * @param {Record<string, unknown>} [extra]
 */
function pkg(name, license, extra = {}) {
  return { name, version: '1.0.0', license, ...extra };
}

// A flat (npm-style) install: app -> a (MIT) -> b (GPL); c is dual licensed; dev is GPL but dev-only.
const GPL_PROJECT = {
  'package.json': {
    name: 'app',
    dependencies: { a: '1.0.0', c: '1.0.0' },
    optionalDependencies: { 'not-installed-optional': '1.0.0' },
    devDependencies: { dev: '1.0.0' },
  },
  'node_modules/a/package.json': pkg('a', 'MIT', { dependencies: { b: '1.0.0' } }),
  'node_modules/a/LICENSE': 'MIT License text for a',
  'node_modules/b/package.json': pkg('b', 'GPL-3.0-only'),
  'node_modules/c/package.json': pkg('c', '(MIT OR GPL-3.0-only)'),
  'node_modules/dev/package.json': pkg('dev', 'GPL-3.0-only'),
};

describe('evaluateLicense (O-L03)', () => {
  it.each([
    ['MIT', true],
    ['mit', true],
    // MIT No Attribution (ADR-0004 C-42).
    ['MIT-0', true],
    ['mit-0', true],
    ['MIT-0 OR GPL-3.0-only', true],
    ['MIT-0 AND GPL-3.0-only', false],
    ['MIT-Modern-Variant', false],
    ['Apache-2.0', true],
    ['BSD-3-Clause', true],
    ['0BSD', true],
    ['Apache-2.0+', true],
    ['(MIT OR GPL-3.0-only)', true],
    ['GPL-3.0-only OR Apache-2.0', true],
    ['(MIT AND BSD-3-Clause) OR GPL-3.0-only', true],
    ['MIT AND (ISC OR GPL-2.0-only)', true],
    ['GPL-3.0-only', false],
    ['LGPL-2.1-or-later', false],
    ['MPL-2.0', false],
    ['MIT AND GPL-2.0-only', false],
    ['Apache-2.0 WITH LLVM-exception', false],
    ['UNLICENSED', false],
    ['SEE LICENSE IN LICENSE.txt', false],
    ['(MIT', false],
    ['MIT OR', false],
    ['', false],
    [null, false],
  ])('%j -> %s', (expression, expected) => {
    expect(evaluateLicense(expression, ALLOWED).ok).toBe(expected);
  });

  it('explains invalid expressions and asks for an override', () => {
    const verdict = evaluateLicense('SEE LICENSE IN LICENSE.txt', ALLOWED);
    expect(verdict).toMatchObject({ ok: false });
    expect(verdict.ok ? '' : verdict.reason).toMatch(
      /not a valid SPDX expression.*add an override/,
    );
  });
});

describe('licenseOf', () => {
  it('reads string, object and legacy array forms', () => {
    expect(licenseOf({ license: ' MIT ' })).toBe('MIT');
    expect(licenseOf({ license: { type: 'ISC', url: 'x' } })).toBe('ISC');
    expect(licenseOf({ licenses: [{ type: 'MIT' }, { type: 'Apache-2.0' }] })).toBe(
      '(MIT) OR (Apache-2.0)',
    );
    expect(licenseOf({})).toBeNull();
  });
});

describe('checkLicenses (T-BLD-007)', () => {
  it('fails when a production dependency is GPL, and names the dependency path', () => {
    const outcome = checkLicenses({ projectDir: fixture(GPL_PROJECT), policy: POLICY });

    expect(outcome.errors).toEqual([
      'b@1.0.0 (a@1.0.0 > b@1.0.0): "GPL-3.0-only" is not on the allowlist',
    ]);
    expect(outcome.closure.map((entry) => entry.key)).toEqual(['a@1.0.0', 'b@1.0.0', 'c@1.0.0']);
  });

  it('passes once the GPL package is gone, ignoring devDependencies', () => {
    const projectDir = fixture({
      ...GPL_PROJECT,
      'node_modules/a/package.json': pkg('a', 'MIT'),
    });

    const outcome = checkLicenses({ projectDir, policy: POLICY });

    expect(outcome.errors).toEqual([]);
    expect(outcome.closure.map((entry) => entry.key)).toEqual(['a@1.0.0', 'c@1.0.0']);
  });

  it('includes required peer dependencies but not optional ones, and reports missing packages', () => {
    const projectDir = fixture({
      'package.json': { name: 'app', dependencies: { lib: '1.0.0', gone: '1.0.0' } },
      'node_modules/lib/package.json': pkg('lib', 'MIT', {
        peerDependencies: { peer: '*', 'optional-peer': '*' },
        peerDependenciesMeta: { 'optional-peer': { optional: true } },
      }),
      'node_modules/peer/package.json': pkg('peer', 'BSD-2-Clause'),
      'node_modules/optional-peer/package.json': pkg('optional-peer', 'GPL-3.0-only'),
    });

    const outcome = checkLicenses({ projectDir, policy: POLICY });

    expect(outcome.closure.map((entry) => entry.key)).toEqual(['lib@1.0.0', 'peer@1.0.0']);
    expect(outcome.errors).toEqual(['gone: not installed (run pnpm install)']);
  });

  it('resolves pnpm-style symlinked layouts', () => {
    const projectDir = fixture({
      'package.json': { name: 'app', dependencies: { a: '1.0.0' } },
      'node_modules/.pnpm/a@1.0.0/node_modules/a/package.json': pkg('a', 'MIT', {
        dependencies: { b: '1.0.0' },
      }),
      'node_modules/.pnpm/b@1.0.0/node_modules/b/package.json': pkg('b', 'AGPL-3.0-only'),
    });
    symlinkSync(
      join(projectDir, 'node_modules/.pnpm/a@1.0.0/node_modules/a'),
      join(projectDir, 'node_modules/a'),
      'junction',
    );
    symlinkSync(
      join(projectDir, 'node_modules/.pnpm/b@1.0.0/node_modules/b'),
      join(projectDir, 'node_modules/.pnpm/a@1.0.0/node_modules/b'),
      'junction',
    );

    const { packages, errors } = collectProductionPackages(projectDir);

    expect(errors).toEqual([]);
    expect([...packages.keys()]).toEqual(['a@1.0.0', 'b@1.0.0']);
    expect(checkLicenses({ projectDir, policy: POLICY }).errors).toEqual([
      'b@1.0.0 (a@1.0.0 > b@1.0.0): "AGPL-3.0-only" is not on the allowlist',
    ]);
  });

  it('accepts an override with a source URL and warns about unused overrides', () => {
    const projectDir = fixture({
      'package.json': { name: 'app', dependencies: { odd: '1.0.0' } },
      'node_modules/odd/package.json': pkg('odd', 'SEE LICENSE IN LICENSE'),
    });
    const policy = parsePolicy({
      allowed: ALLOWED,
      overrides: [
        { package: 'odd@1.0.0', license: 'MIT', source: 'https://example.com/odd/LICENSE' },
        { package: 'stale@2.0.0', license: 'MIT', source: 'https://example.com/stale/LICENSE' },
      ],
    });

    const withoutOverride = checkLicenses({ projectDir, policy: POLICY });
    expect(withoutOverride.errors[0]).toMatch(/^odd@1\.0\.0 \(odd@1\.0\.0\): .*add an override/);

    const outcome = checkLicenses({ projectDir, policy });
    expect(outcome.errors).toEqual([]);
    expect(outcome.warnings).toEqual(['override is not used (remove it): stale@2.0.0']);
  });

  it('rejects malformed policies', () => {
    expect(() => parsePolicy({ allowed: [], overrides: [] })).toThrow(/allowed/);
    expect(() =>
      parsePolicy({
        allowed: ['MIT'],
        overrides: [{ package: 'x@1.0.0', license: 'MIT', source: 'http://x' }],
      }),
    ).toThrow(/https URL/);
    expect(() =>
      parsePolicy({
        allowed: ['MIT'],
        overrides: [{ package: 'x', license: 'MIT', source: 'https://x' }],
      }),
    ).toThrow(/name@version/);
  });

  it('fails when the bundle contains a package that is not a production dependency or not allowed', () => {
    const projectDir = fixture({ ...GPL_PROJECT, 'node_modules/a/package.json': pkg('a', 'MIT') });
    const bundle = parseBundleManifest([
      { name: 'a', version: '1.0.0', identifier: 'MIT', text: 'MIT License' },
      { name: 'dev', version: '1.0.0', identifier: 'GPL-3.0-only' },
    ]);

    const outcome = checkLicenses({ projectDir, policy: POLICY, bundle });

    expect(outcome.errors).toEqual([
      'dev@1.0.0: bundled, but not a production dependency (declare it in "dependencies")',
      'dev@1.0.0 (bundled): "GPL-3.0-only" is not on the allowlist',
    ]);
  });

  it('passes for the real ui dependencies', () => {
    const outcome = checkLicenses({ projectDir: UI_ROOT, policy: POLICY });

    expect(outcome.errors).toEqual([]);
    expect(outcome.closure.map((entry) => entry.key)).toContain('react@19.3.1');
    expect(outcome.closure.some((entry) => entry.key.startsWith('vite@'))).toBe(false);
  });
});

// Development tool chain: app -> lib (MIT); devDependencies: tool (MIT) -> mpl-dep (MPL-2.0),
// @scope/a (CC-BY-4.0), plat-linux (MPL-2.0, a per-platform native package).
const DEV_PROJECT = {
  'package.json': {
    name: 'app',
    dependencies: { lib: '1.0.0' },
    devDependencies: { tool: '1.0.0', '@scope/a': '1.0.0' },
  },
  'node_modules/lib/package.json': pkg('lib', 'MIT'),
  'node_modules/tool/package.json': pkg('tool', 'MIT', {
    dependencies: { 'mpl-dep': '1.0.0' },
    optionalDependencies: { 'plat-linux': '1.0.0', 'plat-win32': '1.0.0' },
  }),
  'node_modules/mpl-dep/package.json': pkg('mpl-dep', 'MPL-2.0'),
  'node_modules/@scope/a/package.json': pkg('@scope/a', 'CC-BY-4.0'),
  'node_modules/plat-linux/package.json': pkg('plat-linux', 'MPL-2.0'),
};

/** @param {string[]} rows first-column cells of dev-only.md rows */
function devOnlyMarkdown(rows) {
  return [
    '| Coordinates | Component | License | Used by | Why it is not shipped |',
    '|---|---|---|---|---|',
    '| `com.example:maven-lib` | x | GPL-3.0-only | x | x |',
    '| `tool:k6` | x | AGPL-3.0-only | x | x |',
    ...rows.map((row) => `| \`${row}\` | x | x | x | x |`),
  ].join('\n');
}

describe('check-licenses --dev (development tools, docs/licenses/dev-only.md)', () => {
  it('reads only npm: rows and matches * wildcards', () => {
    expect(parseDevOnlyNpm(devOnlyMarkdown(['npm:axe-core', 'npm:@csstools/*']))).toEqual([
      'axe-core',
      '@csstools/*',
    ]);
    expect(devOnlyMatches('axe-core', 'axe-core')).toBe(true);
    expect(devOnlyMatches('axe-core', 'axe-core-extra')).toBe(false);
    expect(devOnlyMatches('lightningcss*', 'lightningcss')).toBe(true);
    expect(devOnlyMatches('lightningcss*', 'lightningcss-linux-x64-gnu')).toBe(true);
    expect(devOnlyMatches('@csstools/*', '@csstools/color-helpers')).toBe(true);
    expect(devOnlyMatches('@csstools/*', '@other/color-helpers')).toBe(false);
    expect(devOnlyMatches('a.b*', 'aXb')).toBe(false);
  });

  it('collects devDependencies and skips optional packages for other platforms', () => {
    const { packages, errors } = collectInstalledPackages(fixture(DEV_PROJECT));

    expect(errors).toEqual([]);
    expect([...packages.keys()].sort()).toEqual([
      '@scope/a@1.0.0',
      'lib@1.0.0',
      'mpl-dep@1.0.0',
      'plat-linux@1.0.0',
      'tool@1.0.0',
    ]);
  });

  it('fails when an MPL development package is not listed in dev-only.md', () => {
    const projectDir = fixture(DEV_PROJECT);
    const { packages: production } = checkLicenses({ projectDir, policy: POLICY });

    const outcome = checkDevLicenses({
      projectDir,
      policy: POLICY,
      devOnly: parseDevOnlyNpm(devOnlyMarkdown(['npm:@scope/*', 'npm:plat-*'])),
      production,
    });

    expect(outcome.errors).toEqual([
      'mpl-dep@1.0.0 (tool@1.0.0 > mpl-dep@1.0.0): "MPL-2.0" is not on the allowlist; ' +
        'a development-only package needs a `npm:mpl-dep` row in docs/licenses/dev-only.md',
    ]);
  });

  it('passes when every development package outside the allowlist is listed', () => {
    const projectDir = fixture(DEV_PROJECT);
    const { packages: production } = checkLicenses({ projectDir, policy: POLICY });

    const outcome = checkDevLicenses({
      projectDir,
      policy: POLICY,
      devOnly: parseDevOnlyNpm(devOnlyMarkdown(['npm:mpl-dep', 'npm:@scope/*', 'npm:plat-*'])),
      production,
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.listed).toEqual([
      { key: '@scope/a@1.0.0', license: 'CC-BY-4.0', pattern: '@scope/*' },
      { key: 'mpl-dep@1.0.0', license: 'MPL-2.0', pattern: 'mpl-dep' },
      { key: 'plat-linux@1.0.0', license: 'MPL-2.0', pattern: 'plat-*' },
    ]);
  });

  it('accepts MIT-0 (ADR-0004 C-42) without a dev-only.md row, but not MIT-0 AND GPL', () => {
    const projectDir = fixture({
      'package.json': {
        name: 'app',
        dependencies: { lib: '1.0.0' },
        devDependencies: { tool: '1.0.0' },
      },
      'node_modules/lib/package.json': pkg('lib', 'MIT-0'),
      'node_modules/tool/package.json': pkg('tool', 'MIT-0', { dependencies: { gpl: '1.0.0' } }),
      'node_modules/gpl/package.json': pkg('gpl', 'MIT-0 AND GPL-3.0-only'),
    });
    const outcome = checkLicenses({ projectDir, policy: POLICY });

    const dev = checkDevLicenses({
      projectDir,
      policy: POLICY,
      devOnly: [],
      production: outcome.packages,
    });

    expect(outcome.errors).toEqual([]);
    expect(dev.errors).toEqual([
      'gpl@1.0.0 (tool@1.0.0 > gpl@1.0.0): "MIT-0 AND GPL-3.0-only" is not on the allowlist; ' +
        'a development-only package needs a `npm:gpl` row in docs/licenses/dev-only.md',
    ]);
    expect(dev.listed).toEqual([]);
  });

  it('never exempts a production dependency, even when it is listed', () => {
    const projectDir = fixture({
      ...DEV_PROJECT,
      'node_modules/lib/package.json': pkg('lib', 'MPL-2.0'),
    });
    const outcome = checkLicenses({ projectDir, policy: POLICY });
    const dev = checkDevLicenses({
      projectDir,
      policy: POLICY,
      devOnly: parseDevOnlyNpm(
        devOnlyMarkdown(['npm:lib', 'npm:mpl-dep', 'npm:@scope/*', 'npm:plat-*']),
      ),
      production: outcome.packages,
    });

    expect(outcome.errors).toEqual(['lib@1.0.0 (lib@1.0.0): "MPL-2.0" is not on the allowlist']);
    expect(dev.errors).toEqual([]);
    expect(dev.listed.map((entry) => entry.key)).not.toContain('lib@1.0.0');
  });

  // Walks every installed package (about 380 in ui/node_modules): about 0.5 s on an idle machine,
  // but several seconds with a cold file cache on a busy one, hence the explicit timeout.
  it('passes for the real ui tool chain with docs/licenses/dev-only.md', () => {
    const devOnlyFile = join(UI_ROOT, '..', 'docs', 'licenses', 'dev-only.md');
    const { packages: production } = checkLicenses({ projectDir: UI_ROOT, policy: POLICY });

    const outcome = checkDevLicenses({
      projectDir: UI_ROOT,
      policy: POLICY,
      devOnly: parseDevOnlyNpm(readFileSync(devOnlyFile, 'utf8')),
      production,
    });

    expect(outcome.errors).toEqual([]);
    expect(outcome.listed.some((entry) => entry.key.startsWith('axe-core@'))).toBe(true);
  }, 60_000);

  // Starts two node processes; each takes well under a second unless the machine is overloaded.
  it('CLI: --dev exits 1 for an unlisted MPL development package and 0 once it is listed', () => {
    const projectDir = fixture(DEV_PROJECT);
    const lists = createFixture({
      'unlisted.md': devOnlyMarkdown(['npm:@scope/*', 'npm:plat-*']),
      'listed.md': devOnlyMarkdown(['npm:mpl-dep', 'npm:@scope/*', 'npm:plat-*']),
    });
    cleanups.push(lists.cleanup);
    /** @param {string} list */
    const run = (list) =>
      spawnSync(
        process.execPath,
        [
          SCRIPT,
          '--project',
          projectDir,
          '--bundle',
          join(projectDir, 'none.json'),
          '--dev',
          '--dev-only',
          join(lists.root, list),
        ],
        { encoding: 'utf8' },
      );

    const unlisted = run('unlisted.md');
    expect(unlisted.status).toBe(1);
    expect(unlisted.stderr).toContain('ERROR mpl-dep@1.0.0 (tool@1.0.0 > mpl-dep@1.0.0)');

    const listed = run('listed.md');
    expect(listed.status).toBe(0);
    expect(listed.stdout).toContain('listed in dev-only.md=3 errors=0');
  }, 30_000);

  // Spawns a node process, which can exceed Vitest's 5 s default on a loaded machine.
  it('CLI: without --dev, development packages are not checked', () => {
    const projectDir = fixture(DEV_PROJECT);

    const run = spawnSync(
      process.execPath,
      [SCRIPT, '--project', projectDir, '--bundle', join(projectDir, 'none.json')],
      { encoding: 'utf8' },
    );

    expect(run.status).toBe(0);
    expect(run.stdout).not.toContain('--dev');
  }, 30_000);
});

describe('check-licenses CLI', () => {
  // Spawns a node process, which can exceed Vitest's 5 s default on a loaded machine.
  it('exits 1 for a GPL production dependency', () => {
    const projectDir = fixture(GPL_PROJECT);

    const run = spawnSync(
      process.execPath,
      [SCRIPT, '--project', projectDir, '--bundle', join(projectDir, 'none.json')],
      {
        encoding: 'utf8',
      },
    );

    expect(run.status).toBe(1);
    expect(run.stderr).toContain(
      'ERROR b@1.0.0 (a@1.0.0 > b@1.0.0): "GPL-3.0-only" is not on the allowlist',
    );
  }, 30_000);

  // Spawns node processes, which can exceed Vitest's 5 s default on a loaded machine.
  it('requires the bundle manifest with --require-bundle and writes notices', () => {
    const projectDir = fixture({ ...GPL_PROJECT, 'node_modules/a/package.json': pkg('a', 'MIT') });
    const missing = spawnSync(
      process.execPath,
      [
        SCRIPT,
        '--project',
        projectDir,
        '--bundle',
        join(projectDir, 'dist/license.json'),
        '--require-bundle',
      ],
      { encoding: 'utf8' },
    );
    expect(missing.status).toBe(1);
    expect(missing.stderr).toContain('bundle license manifest not found');

    mkdirSync(join(projectDir, 'dist'));
    const manifest = createFixture({
      'license.json': [{ name: 'a', version: '1.0.0', identifier: 'MIT', text: 'MIT text of a' }],
    });
    cleanups.push(manifest.cleanup);
    const notices = join(projectDir, 'dist', 'licenses.txt');
    const run = spawnSync(
      process.execPath,
      [
        SCRIPT,
        '--project',
        projectDir,
        '--bundle',
        join(manifest.root, 'license.json'),
        '--require-bundle',
        '--notices',
        notices,
      ],
      { encoding: 'utf8' },
    );

    expect(run.status).toBe(0);
    expect(run.stdout).toContain('bundled=1 errors=0');
    expect(existsSync(notices)).toBe(true);
    const text = readFileSync(notices, 'utf8');
    expect(text).toContain('a@1.0.0\nLicense: MIT');
    expect(text).toContain('MIT text of a');
  }, 30_000);
});
