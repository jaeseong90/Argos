// SPDX-License-Identifier: Apache-2.0
//
// License allowlist check for what the UI ships (O-L03, docs/19 §4·§5.5, T-BLD-007).
//
// 1. Production closure: every package reachable from package.json "dependencies" (and
//    "optionalDependencies", plus non-optional peer dependencies) as installed in node_modules.
//    devDependencies are build/test tools and never reach the bundle: ESLint
//    (import-x/no-extraneous-dependencies) stops src/ from importing them.
// 2. Bundle (when the build output exists, or always with --require-bundle): Vite's
//    build.license manifest lists exactly the packages that were bundled. Each must be in the
//    production closure and carry an allowed license.
//
// 3. Development tools (with --dev): every installed package, devDependencies included. Build and
//    test tools may use licenses outside the allowlist only when they are listed as `npm:<name>`
//    rows in docs/licenses/dev-only.md (`*` matches any characters, e.g. `npm:@csstools/*`).
//    Production packages are never exempted by that list; step 1 checks them.
//
// A license passes when its SPDX expression can be satisfied with allowed licenses only
// (`MIT OR GPL-3.0` passes, `MIT AND GPL-3.0` fails, `X WITH exception` fails unless listed).
// Packages without a usable SPDX expression need an override with a source URL.
//
// Usage: node scripts/check-licenses.mjs [--project <dir>] [--policy <file>] [--bundle <file>]
//                                         [--require-bundle] [--notices <file>]
//                                         [--dev] [--dev-only <file>]
import { existsSync, readdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const LICENSE_FILE = /^(?:licen[cs]e|copying)(?:[.-][\w.-]*)?$/i;

/**
 * @typedef {{ package: string, license: string, source: string }} Override
 * @typedef {{ allowed: string[], overrides: Override[] }} Policy
 * @typedef {{ name: string, version: string, license: string | null, dir: string, via: string[] }} InstalledPackage
 * @typedef {{ name: string, version: string, identifier?: string, text?: string }} BundleEntry
 * @typedef {{ ok: true } | { ok: false, reason: string }} Verdict
 */

/**
 * @param {unknown} data parsed license-policy.json
 * @returns {Policy}
 */
export function parsePolicy(data) {
  const allowed =
    typeof data === 'object' && data !== null ? Reflect.get(data, 'allowed') : undefined;
  const overrides =
    typeof data === 'object' && data !== null ? Reflect.get(data, 'overrides') : undefined;
  if (
    !Array.isArray(allowed) ||
    allowed.length === 0 ||
    !allowed.every((id) => typeof id === 'string')
  ) {
    throw new Error('license policy: "allowed" must be a non-empty array of SPDX identifiers');
  }
  if (!Array.isArray(overrides)) {
    throw new Error('license policy: "overrides" must be an array');
  }
  return {
    allowed: /** @type {string[]} */ (allowed),
    overrides: overrides.map((entry, index) => {
      const pkg =
        typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'package') : undefined;
      const license =
        typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'license') : undefined;
      const source =
        typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'source') : undefined;
      if (typeof pkg !== 'string' || !/^(?:@[^/@]+\/)?[^/@]+@\d[^\s]*$/.test(pkg)) {
        throw new Error(
          `license policy: overrides[${String(index)}].package must be "name@version"`,
        );
      }
      if (typeof license !== 'string' || license === '') {
        throw new Error(
          `license policy: overrides[${String(index)}].license must be an SPDX expression`,
        );
      }
      if (typeof source !== 'string' || !/^https:\/\//.test(source)) {
        throw new Error(
          `license policy: overrides[${String(index)}].source must be the https URL of the license text`,
        );
      }
      return { package: pkg, license, source };
    }),
  };
}

/**
 * Normalises the license fields of a package.json into one SPDX expression (or null).
 *
 * @param {Record<string, unknown>} manifest
 * @returns {string | null}
 */
export function licenseOf(manifest) {
  const { license, licenses } = manifest;
  if (typeof license === 'string' && license.trim() !== '') {
    return license.trim();
  }
  if (typeof license === 'object' && license !== null) {
    const type = Reflect.get(license, 'type');
    return typeof type === 'string' && type.trim() !== '' ? type.trim() : null;
  }
  if (Array.isArray(licenses)) {
    // Legacy `licenses` array: the package is offered under any of them.
    const types = licenses
      .map((entry) =>
        typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'type') : entry,
      )
      .filter((type) => typeof type === 'string' && type.trim() !== '');
    return types.length > 0 ? types.map((type) => `(${String(type).trim()})`).join(' OR ') : null;
  }
  return null;
}

/**
 * Decides whether an SPDX license expression can be satisfied with allowed licenses only.
 *
 * @param {string | null} expression
 * @param {readonly string[]} allowed
 * @returns {Verdict}
 */
export function evaluateLicense(expression, allowed) {
  if (expression === null || expression.trim() === '') {
    return { ok: false, reason: 'no license declared' };
  }
  const allowedSet = new Set(allowed.map((id) => id.toLowerCase()));
  const tokens = expression.match(/\(|\)|[^\s()]+/g) ?? [];
  let position = 0;

  /** @returns {boolean} */
  const parseOr = () => {
    let value = parseAnd();
    while (tokens[position]?.toUpperCase() === 'OR') {
      position += 1;
      const right = parseAnd();
      value = value || right;
    }
    return value;
  };
  /** @returns {boolean} */
  const parseAnd = () => {
    let value = parseTerm();
    while (tokens[position]?.toUpperCase() === 'AND') {
      position += 1;
      const right = parseTerm();
      value = value && right;
    }
    return value;
  };
  /** @returns {boolean} */
  const parseTerm = () => {
    const token = tokens[position];
    if (token === undefined) {
      throw new SyntaxError('unexpected end of expression');
    }
    if (token === '(') {
      position += 1;
      const value = parseOr();
      if (tokens[position] !== ')') {
        throw new SyntaxError('missing ")"');
      }
      position += 1;
      return value;
    }
    if (token === ')' || ['AND', 'OR', 'WITH'].includes(token.toUpperCase())) {
      throw new SyntaxError(`unexpected "${token}"`);
    }
    position += 1;
    if (!/^(?:LicenseRef-[\w.-]+|[A-Za-z0-9][\w.-]*\+?)$/.test(token)) {
      throw new SyntaxError(`"${token}" is not an SPDX license identifier`);
    }
    if (tokens[position]?.toUpperCase() === 'WITH') {
      const exception = tokens[position + 1];
      if (exception === undefined) {
        throw new SyntaxError('missing exception after WITH');
      }
      position += 2;
      return allowedSet.has(`${token} WITH ${exception}`.toLowerCase());
    }
    // "X+" (X or any later version) can always be used under X.
    return allowedSet.has(token.replace(/\+$/, '').toLowerCase());
  };

  try {
    const value = parseOr();
    if (position !== tokens.length) {
      throw new SyntaxError(`unexpected "${String(tokens[position])}"`);
    }
    return value ? { ok: true } : { ok: false, reason: `"${expression}" is not on the allowlist` };
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    return {
      ok: false,
      reason: `"${expression}" is not a valid SPDX expression (${detail}); add an override`,
    };
  }
}

/**
 * Collects the production dependency closure as installed (works with pnpm's symlinked layout
 * and with a flat npm layout: Node's resolution walk from each package's real directory).
 *
 * @param {string} projectDir
 * @returns {{ packages: Map<string, InstalledPackage>, errors: string[] }}
 */
export function collectProductionPackages(projectDir) {
  return collectPackages(projectDir, false);
}

/**
 * Collects every installed package reachable from package.json, devDependencies included (the
 * build and test tool chain). Optional dependencies that are not installed, such as native
 * packages for other platforms, are skipped.
 *
 * @param {string} projectDir
 * @returns {{ packages: Map<string, InstalledPackage>, errors: string[] }}
 */
export function collectInstalledPackages(projectDir) {
  return collectPackages(projectDir, true);
}

/**
 * @param {string} projectDir
 * @param {boolean} includeDev follow the root devDependencies as well
 * @returns {{ packages: Map<string, InstalledPackage>, errors: string[] }}
 */
function collectPackages(projectDir, includeDev) {
  /** @type {Map<string, InstalledPackage>} */
  const packages = new Map();
  /** @type {string[]} */
  const errors = [];
  const root = readManifest(join(projectDir, 'package.json'));
  /** @type {Set<string>} */
  const visitedDirs = new Set();
  /** @type {{ name: string, optional: boolean, from: string, via: string[] }[]} */
  const queue = dependencyEdges(root, true, includeDev).map(({ name, optional }) => ({
    name,
    optional,
    from: projectDir,
    via: [],
  }));

  while (queue.length > 0) {
    const edge = /** @type {(typeof queue)[number]} */ (queue.shift());
    const dir = resolvePackageDir(edge.name, edge.from);
    if (dir === null) {
      if (!edge.optional) {
        errors.push(`${[...edge.via, edge.name].join(' > ')}: not installed (run pnpm install)`);
      }
      continue;
    }
    if (visitedDirs.has(dir)) {
      continue;
    }
    visitedDirs.add(dir);
    const manifest = readManifest(join(dir, 'package.json'));
    const name = typeof manifest['name'] === 'string' ? manifest['name'] : edge.name;
    const version = typeof manifest['version'] === 'string' ? manifest['version'] : '0.0.0';
    const key = `${name}@${version}`;
    const via = [...edge.via, key];
    if (!packages.has(key)) {
      packages.set(key, { name, version, license: licenseOf(manifest), dir, via });
    }
    for (const next of dependencyEdges(manifest, false, false)) {
      queue.push({ name: next.name, optional: next.optional, from: dir, via });
    }
  }
  return { packages, errors };
}

/**
 * @param {Record<string, unknown>} manifest
 * @param {boolean} isRoot
 * @param {boolean} includeDev
 * @returns {{ name: string, optional: boolean }[]}
 */
function dependencyEdges(manifest, isRoot, includeDev) {
  /** @type {{ name: string, optional: boolean }[]} */
  const edges = [];
  const names = (/** @type {unknown} */ field) =>
    typeof field === 'object' && field !== null ? Object.keys(field) : [];
  for (const name of names(manifest['dependencies'])) {
    edges.push({ name, optional: false });
  }
  if (isRoot && includeDev) {
    for (const name of names(manifest['devDependencies'])) {
      edges.push({ name, optional: false });
    }
  }
  for (const name of names(manifest['optionalDependencies'])) {
    edges.push({ name, optional: true });
  }
  if (!isRoot) {
    const meta = manifest['peerDependenciesMeta'];
    for (const name of names(manifest['peerDependencies'])) {
      const optional =
        typeof meta === 'object' &&
        meta !== null &&
        Reflect.get(Reflect.get(meta, name) ?? {}, 'optional') === true;
      if (!optional) {
        // Required peers are provided by the consumer; they are part of what gets bundled.
        edges.push({ name, optional: false });
      }
    }
  }
  return edges;
}

/**
 * @param {string} name
 * @param {string} fromDir
 * @returns {string | null} real path of the installed package directory
 */
function resolvePackageDir(name, fromDir) {
  let dir = fromDir;
  for (;;) {
    if (basename(dir) !== 'node_modules') {
      const candidate = join(dir, 'node_modules', name);
      if (existsSync(join(candidate, 'package.json'))) {
        return realpathSync(candidate);
      }
    }
    const parent = dirname(dir);
    if (parent === dir) {
      return null;
    }
    dir = parent;
  }
}

/**
 * @param {string} path
 * @returns {Record<string, unknown>}
 */
function readManifest(path) {
  const data = /** @type {unknown} */ (JSON.parse(readFileSync(path, 'utf8')));
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    throw new Error(`${path}: not a package manifest`);
  }
  return /** @type {Record<string, unknown>} */ (data);
}

/**
 * @param {unknown} data parsed Vite build.license JSON
 * @returns {BundleEntry[]}
 */
export function parseBundleManifest(data) {
  if (!Array.isArray(data)) {
    throw new Error(
      'bundle license manifest: expected a JSON array (Vite build.license with a .json fileName)',
    );
  }
  return data.map((entry, index) => {
    const name =
      typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'name') : undefined;
    const version =
      typeof entry === 'object' && entry !== null ? Reflect.get(entry, 'version') : undefined;
    if (typeof name !== 'string' || typeof version !== 'string') {
      throw new Error(`bundle license manifest: entry ${String(index)} has no name/version`);
    }
    const identifier = Reflect.get(entry, 'identifier');
    const text = Reflect.get(entry, 'text');
    return {
      name,
      version,
      ...(typeof identifier === 'string' ? { identifier } : {}),
      ...(typeof text === 'string' ? { text } : {}),
    };
  });
}

/**
 * @param {{ projectDir: string, policy: Policy, bundle?: BundleEntry[] | null }} options
 */
export function checkLicenses({ projectDir, policy, bundle = null }) {
  /** @type {string[]} */
  const errors = [];
  const overrides = new Map(policy.overrides.map((entry) => [entry.package, entry]));
  /** @type {Set<string>} */
  const usedOverrides = new Set();

  /**
   * @param {string} key name@version
   * @param {string | null} declared
   */
  const verdictFor = (key, declared) => {
    const override = overrides.get(key);
    if (override !== undefined) {
      usedOverrides.add(key);
      return {
        license: override.license,
        verdict: evaluateLicense(override.license, policy.allowed),
      };
    }
    return { license: declared, verdict: evaluateLicense(declared, policy.allowed) };
  };

  const { packages, errors: collectErrors } = collectProductionPackages(projectDir);
  errors.push(...collectErrors);
  /** @type {{ key: string, license: string | null, ok: boolean }[]} */
  const closure = [];
  for (const [key, pkg] of [...packages].sort(([a], [b]) => a.localeCompare(b))) {
    const { license, verdict } = verdictFor(key, pkg.license);
    closure.push({ key, license, ok: verdict.ok });
    if (!verdict.ok) {
      errors.push(`${key} (${pkg.via.join(' > ')}): ${verdict.reason}`);
    }
  }

  /** @type {{ key: string, license: string | null, ok: boolean }[]} */
  const bundled = [];
  for (const entry of bundle ?? []) {
    const key = `${entry.name}@${entry.version}`;
    const { license, verdict } = verdictFor(key, entry.identifier ?? null);
    bundled.push({ key, license, ok: verdict.ok });
    if (!packages.has(key)) {
      errors.push(
        `${key}: bundled, but not a production dependency (declare it in "dependencies")`,
      );
    }
    if (!verdict.ok) {
      errors.push(`${key} (bundled): ${verdict.reason}`);
    }
  }

  const warnings = policy.overrides
    .filter((entry) => !usedOverrides.has(entry.package))
    .map((entry) => `override is not used (remove it): ${entry.package}`);
  return { errors, warnings, closure, bundled, packages };
}

/**
 * Package name patterns of the `npm:` rows in the machine-readable table of
 * docs/licenses/dev-only.md (first column, in backticks), without the `npm:` prefix.
 *
 * @param {string} markdown
 * @returns {string[]}
 */
export function parseDevOnlyNpm(markdown) {
  /** @type {string[]} */
  const patterns = [];
  for (const line of markdown.split(/\r?\n/)) {
    const pattern = /^\|\s*`npm:([^`]+)`\s*\|/.exec(line)?.[1]?.trim();
    if (pattern !== undefined && pattern !== '') {
      patterns.push(pattern);
    }
  }
  return patterns;
}

/**
 * @param {string} pattern package name; `*` matches any characters (including `/`)
 * @param {string} name
 * @returns {boolean}
 */
export function devOnlyMatches(pattern, name) {
  if (!pattern.includes('*')) {
    return pattern === name;
  }
  const source = pattern
    .split('*')
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('.*');
  return new RegExp(`^${source}$`).test(name);
}

/**
 * Development tool check (--dev): every installed package outside the production closure whose
 * license is not on the allowlist must match an `npm:` row of docs/licenses/dev-only.md.
 *
 * @param {{ projectDir: string, policy: Policy, devOnly: string[], production: Map<string, InstalledPackage> }} options
 */
export function checkDevLicenses({ projectDir, policy, devOnly, production }) {
  /** @type {string[]} */
  const errors = [];
  const overrides = new Map(policy.overrides.map((entry) => [entry.package, entry]));
  const { packages, errors: collectErrors } = collectInstalledPackages(projectDir);
  errors.push(...collectErrors);
  /** @type {Set<string>} */
  const usedPatterns = new Set();
  /** @type {{ key: string, license: string | null, pattern: string }[]} */
  const listed = [];
  for (const [key, pkg] of [...packages].sort(([a], [b]) => a.localeCompare(b))) {
    if (production.has(key)) {
      continue; // shipped: checked against the allowlist only, dev-only.md never applies
    }
    const license = overrides.get(key)?.license ?? pkg.license;
    const verdict = evaluateLicense(license, policy.allowed);
    if (verdict.ok) {
      continue;
    }
    const pattern = devOnly.find((candidate) => devOnlyMatches(candidate, pkg.name));
    if (pattern === undefined) {
      errors.push(
        `${key} (${pkg.via.join(' > ')}): ${verdict.reason}; a development-only package needs ` +
          `a \`npm:${pkg.name}\` row in docs/licenses/dev-only.md`,
      );
      continue;
    }
    usedPatterns.add(pattern);
    listed.push({ key, license, pattern });
  }
  const warnings = devOnly
    .filter((pattern) => !usedPatterns.has(pattern))
    .map(
      (pattern) =>
        `dev-only.md row npm:${pattern} matches no installed package outside the allowlist`,
    );
  return { errors, warnings, listed, installed: packages.size };
}

/**
 * Third-party notices for what the UI ships: name, version, license and license text.
 *
 * @param {{ key: string, license: string | null, text: string | null }[]} entries
 * @returns {string}
 */
export function renderNotices(entries) {
  const lines = [
    'Argos APM web UI - third-party software notices',
    '',
    'The UI bundle includes the following third-party packages.',
  ];
  for (const entry of entries) {
    lines.push(
      '',
      '='.repeat(78),
      `${entry.key}`,
      `License: ${entry.license ?? 'UNKNOWN'}`,
      '='.repeat(78),
    );
    lines.push('', entry.text ?? '(license text not found in the package)');
  }
  return `${lines.join('\n')}\n`;
}

/**
 * @param {string} dir
 * @returns {string | null}
 */
function readLicenseText(dir) {
  const file = readdirSync(dir)
    .sort()
    .find((name) => LICENSE_FILE.test(name));
  return file === undefined ? null : readFileSync(join(dir, file), 'utf8').trim();
}

/**
 * @param {string[]} argv
 * @returns {number} exit code
 */
export function main(argv) {
  const uiRoot = fileURLToPath(new URL('..', import.meta.url));
  const { values } = parseArgs({
    args: argv,
    options: {
      project: { type: 'string', default: uiRoot },
      policy: { type: 'string', default: join(uiRoot, 'scripts', 'license-policy.json') },
      bundle: { type: 'string', default: join(uiRoot, 'dist', '.vite', 'license.json') },
      'require-bundle': { type: 'boolean', default: false },
      notices: { type: 'string' },
      dev: { type: 'boolean', default: false },
      'dev-only': {
        type: 'string',
        default: join(uiRoot, '..', 'docs', 'licenses', 'dev-only.md'),
      },
    },
    strict: true,
  });
  const policy = parsePolicy(JSON.parse(readFileSync(resolve(values.policy), 'utf8')));
  const bundlePath = resolve(values.bundle);
  /** @type {BundleEntry[] | null} */
  let bundle = null;
  if (existsSync(bundlePath)) {
    bundle = parseBundleManifest(JSON.parse(readFileSync(bundlePath, 'utf8')));
  } else if (values['require-bundle']) {
    console.error(`ERROR ${bundlePath}: bundle license manifest not found (run vite build first)`);
    return 1;
  }

  const outcome = checkLicenses({ projectDir: resolve(values.project), policy, bundle });
  for (const entry of outcome.closure) {
    console.log(
      `${entry.ok ? 'ok  ' : 'FAIL'} ${entry.key.padEnd(40)} ${entry.license ?? '(none)'}`,
    );
  }
  for (const warning of outcome.warnings) {
    console.warn(`WARN  ${warning}`);
  }
  for (const error of outcome.errors) {
    console.error(`ERROR ${error}`);
  }
  console.log(
    `check-licenses: production packages=${String(outcome.closure.length)} ` +
      `bundled=${bundle === null ? 'not checked (no build output; run the build or pass --require-bundle)' : String(outcome.bundled.length)} ` +
      `errors=${String(outcome.errors.length)}`,
  );

  let devErrors = 0;
  if (values.dev) {
    const devOnlyPath = resolve(values['dev-only']);
    if (!existsSync(devOnlyPath)) {
      console.error(`ERROR ${devOnlyPath}: dev-only license list not found`);
      return 1;
    }
    const dev = checkDevLicenses({
      projectDir: resolve(values.project),
      policy,
      devOnly: parseDevOnlyNpm(readFileSync(devOnlyPath, 'utf8')),
      production: outcome.packages,
    });
    for (const entry of dev.listed) {
      console.log(
        `dev  ${entry.key.padEnd(40)} ${entry.license ?? '(none)'} (dev-only.md: npm:${entry.pattern})`,
      );
    }
    for (const warning of dev.warnings) {
      console.warn(`WARN  ${warning}`);
    }
    for (const error of dev.errors) {
      console.error(`ERROR ${error}`);
    }
    console.log(
      `check-licenses --dev: installed packages=${String(dev.installed)} ` +
        `listed in dev-only.md=${String(dev.listed.length)} errors=${String(dev.errors.length)}`,
    );
    devErrors = dev.errors.length;
  }
  if (outcome.errors.length > 0 || devErrors > 0) {
    return 1;
  }

  if (values.notices !== undefined) {
    const entries =
      bundle !== null
        ? outcome.bundled.map((entry, index) => ({
            key: entry.key,
            license: entry.license,
            text: bundle?.[index]?.text ?? null,
          }))
        : outcome.closure.map((entry) => {
            const pkg = outcome.packages.get(entry.key);
            return {
              key: entry.key,
              license: entry.license,
              text: pkg === undefined ? null : readLicenseText(pkg.dir),
            };
          });
    const target = resolve(values.notices);
    writeFileSync(target, renderNotices(entries));
    console.log(`check-licenses: wrote ${target} (${String(entries.length)} packages)`);
  }
  return 0;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = main(process.argv.slice(2));
}
