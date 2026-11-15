// SPDX-License-Identifier: Apache-2.0
//
// src/ holds TypeScript sources and static assets only (SEC-60, 03 §3.3, docs/15 §11), plus
// documentation files (.md) that are never imported (docs/16 §9.1: src/security/untrusted.md).
// The lint rules that keep HTML sinks, direct fetch/WebSocket and restricted imports out of the
// bundle are written and tested for TypeScript, and only .ts/.tsx files are type-checked by
// `tsc -b`. A .js/.jsx/.mjs/.cjs/.mts/.cts file would still be bundled by Vite, so this test
// fails as soon as one appears (first line of defence; eslint.config.mjs also lints those
// extensions under src/). Runs with `pnpm test`, so with `./gradlew build` (:ui:test).
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { extname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import { createFixture } from './test-fixtures.mjs';

const UI_ROOT = fileURLToPath(new URL('..', import.meta.url));

/**
 * Extensions allowed under src/: TypeScript (.ts covers .d.ts) and static assets that Vite
 * bundles as data. Never add a script extension here; add asset types only.
 */
const ALLOWED_EXTENSIONS = new Set([
  '.ts',
  '.tsx',
  '.css',
  '.json',
  '.svg',
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.avif',
  '.ico',
  '.woff',
  '.woff2',
]);
/** Exact file names allowed regardless of extension. */
const ALLOWED_NAMES = new Set(['.gitkeep']);
/**
 * Documentation that lives next to the code it describes (docs/16 §9.1 asks for
 * src/security/untrusted.md). It is never imported, which the test below checks, so Vite never
 * bundles it.
 */
const DOC_EXTENSIONS = new Set(['.md']);

/**
 * @param {string} dir
 * @returns {string[]} files under `dir` whose type is not allowed, relative to `dir`, with '/'
 */
function disallowedFiles(dir) {
  /** @type {string[]} */
  const found = [];
  /** @param {string} current */
  const walk = (current) => {
    for (const entry of readdirSync(current).sort()) {
      const path = join(current, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
      } else if (
        !ALLOWED_NAMES.has(entry) &&
        !ALLOWED_EXTENSIONS.has(extname(entry).toLowerCase()) &&
        !DOC_EXTENSIONS.has(extname(entry).toLowerCase())
      ) {
        found.push(relative(dir, path).replaceAll('\\', '/'));
      }
    }
  };
  walk(dir);
  return found;
}

/** A module specifier that names a documentation file, e.g. './untrusted.md' or 'x.md?raw'. */
const DOC_SPECIFIER = /['"`][^'"`\n]*\.md(\?[^'"`\n]*)?['"`]/i;

/**
 * @param {string} dir
 * @returns {string[]} TypeScript files under `dir` that name a documentation file in a string
 */
function docImports(dir) {
  /** @type {string[]} */
  const found = [];
  /** @param {string} current */
  const walk = (current) => {
    for (const entry of readdirSync(current).sort()) {
      const path = join(current, entry);
      if (statSync(path).isDirectory()) {
        walk(path);
      } else if (/\.tsx?$/.test(entry) && DOC_SPECIFIER.test(readFileSync(path, 'utf8'))) {
        found.push(relative(dir, path).replaceAll('\\', '/'));
      }
    }
  };
  walk(dir);
  return found;
}

/** @type {(() => void)[]} */
const cleanups = [];
afterEach(() => {
  cleanups.splice(0).forEach((cleanup) => {
    cleanup();
  });
});

/** @param {string[]} paths */
function fixture(paths) {
  const created = createFixture(
    Object.fromEntries(paths.map((path) => [path, '// SPDX-License-Identifier: Apache-2.0\n'])),
  );
  cleanups.push(created.cleanup);
  return created.root;
}

describe('src/ file types', () => {
  it('src/ contains only TypeScript sources and static assets', () => {
    expect(
      disallowedFiles(join(UI_ROOT, 'src')),
      'write UI code in .ts/.tsx; other script extensions bypass type checking and are forbidden in src/',
    ).toEqual([]);
  });

  it('never imports the documentation files under src/', () => {
    expect(
      docImports(join(UI_ROOT, 'src')),
      'src/**/*.md is documentation only; importing it would put it into the bundle',
    ).toEqual([]);
  });

  it('accepts documentation and detects an import of it', () => {
    const root = fixture(['src/security/untrusted.md', 'src/main.tsx']);
    expect(disallowedFiles(join(root, 'src'))).toEqual([]);
    expect(docImports(join(root, 'src'))).toEqual([]);
    const importing = createFixture({
      'src/security/untrusted.md': '# doc\n',
      'src/main.tsx': "import doc from './security/untrusted.md?raw';\nexport { doc };\n",
    });
    cleanups.push(importing.cleanup);
    expect(docImports(join(importing.root, 'src'))).toEqual(['main.tsx']);
  });

  it.each([
    'legacy/banner.js',
    'legacy/Widget.jsx',
    'legacy/sink.mts',
    'legacy/sink.cts',
    'legacy/loader.mjs',
    'legacy/loader.cjs',
    'legacy/types.d.mts',
    'legacy/BANNER.JS',
    'legacy/page.html',
    'legacy/noextension',
  ])('rejects %s', (path) => {
    const root = fixture([`src/${path}`, 'src/main.tsx']);
    expect(disallowedFiles(join(root, 'src'))).toEqual([path]);
  });

  it('accepts .ts, .tsx, .d.ts and asset files', () => {
    const root = fixture([
      'src/main.tsx',
      'src/vite-env.d.ts',
      'src/i18n/index.ts',
      'src/styles/global.css',
      'src/locales/en/common.json',
      'src/assets/logo.svg',
      'src/assets/fonts/ArgosSans.woff2',
      'src/empty/.gitkeep',
    ]);
    expect(disallowedFiles(join(root, 'src'))).toEqual([]);
  });
});
