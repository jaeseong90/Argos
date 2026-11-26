// SPDX-License-Identifier: Apache-2.0
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';

import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

// SemVer 2.0.0 (DSN-19-10: one version for agent, server and UI).
const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

/**
 * The UI version is package.json#version and nothing else: the build reads no environment
 * variable, so one commit always yields the same bundle (docs/16 §10.4, ST-73-1). A release sets
 * the version in package.json in the release commit, like gradle.properties.
 */
function packageVersion(): string {
  const pkg: unknown = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
  if (
    typeof pkg !== 'object' ||
    pkg === null ||
    !('version' in pkg) ||
    typeof pkg.version !== 'string'
  ) {
    throw new Error('package.json has no version');
  }
  if (!SEMVER.test(pkg.version)) {
    throw new Error(`package.json version is not SemVer: ${JSON.stringify(pkg.version)}`);
  }
  return pkg.version;
}

/**
 * Content-Security-Policy of `vite preview`, which the Playwright suites (E2E, performance) serve
 * dist/ with. It is the policy of docs/16 §9.9 (SEC-46) without its two server-side parts: no
 * nonce, because a static preview cannot inject a per-request nonce into index.html (the server's
 * IndexHtmlController does that, WO-30 / DSN-15-09), and no report-uri / report-to (there is no
 * report endpoint). connect-src has no wss:// source because nothing here opens a WebSocket yet.
 * The E2E page hook (e2e/fixtures/fixtures.ts) requires this header and zero CSP violations.
 * Not applied to the dev server (`server.headers`): React refresh injects an inline preamble.
 */
const PREVIEW_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob:",
  "font-src 'self'",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ');

/** Chunk graph of the last build: build/, outside dist/ (dist/ ships in the server jar). */
const BUNDLE_GRAPH_FILE = 'build/bundle-graph.json';

/**
 * The graph file of a build mode: the production build's is the one the bundle size report reads;
 * another mode (the perf build into build/perf-dist, ADR-0010 C-203) writes its own, so a perf build
 * running next to a production build never replaces the graph that build is measured with.
 */
function bundleGraphFile(mode: string): string {
  return mode === 'production' ? BUNDLE_GRAPH_FILE : `build/bundle-graph-${mode}.json`;
}

/**
 * Records the chunk graph of a production build for the bundle size report
 * (scripts/bundle-size.mjs, docs/15 §14): per chunk its file, entry kind, static and dynamic
 * imports, the CSS Vite loads with it and the project source modules it contains (to find the
 * chunks of the initial path). Written after the bundle is on disk; never part of dist/.
 */
function bundleGraph(): Plugin {
  let root = process.cwd();
  let file = BUNDLE_GRAPH_FILE;
  /** A module id as a project path (`src/routes/Shell.tsx`), or null for dependencies. */
  const projectPath = (id: string): string | null => {
    if (id.startsWith('\0') || !isAbsolute(id)) {
      return null;
    }
    const path = relative(root, id.replace(/[?#].*$/, ''))
      .split(sep)
      .join('/');
    return path.startsWith('..') || path.includes('node_modules/') ? null : path;
  };
  return {
    name: 'argos:bundle-graph',
    apply: 'build',
    enforce: 'post',
    configResolved(config) {
      root = config.root;
      file = bundleGraphFile(config.mode);
    },
    writeBundle(options, bundle) {
      const chunks = [];
      const assets = [];
      for (const output of Object.values(bundle)) {
        if (output.type === 'chunk') {
          chunks.push({
            fileName: output.fileName,
            name: output.name,
            isEntry: output.isEntry,
            isDynamicEntry: output.isDynamicEntry,
            facadeModule:
              output.facadeModuleId === null ? null : projectPath(output.facadeModuleId),
            modules: output.moduleIds
              .map(projectPath)
              .filter((path): path is string => path !== null)
              .sort(),
            imports: [...output.imports].sort(),
            dynamicImports: [...output.dynamicImports].sort(),
            importedCss: [...(output.viteMetadata?.importedCss ?? [])].sort(),
          });
        } else {
          assets.push({ fileName: output.fileName });
        }
      }
      const outDir = options.dir ?? resolve(root, 'dist');
      const graph = {
        version: 1,
        outDir: relative(root, outDir).split(sep).join('/'),
        chunks: chunks.sort((a, b) => a.fileName.localeCompare(b.fileName)),
        assets: assets.sort((a, b) => a.fileName.localeCompare(b.fileName)),
      };
      const target = resolve(root, file);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, `${JSON.stringify(graph, null, 2)}\n`);
    },
  };
}

export default defineConfig({
  plugins: [react(), bundleGraph()],
  define: {
    __ARGOS_VERSION__: JSON.stringify(packageVersion()),
  },
  preview: {
    headers: { 'Content-Security-Policy': PREVIEW_CSP },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // Source maps would ship the original sources (and their comments) to every browser.
    sourcemap: false,
    // Exact list of bundled third-party packages; checked by scripts/check-licenses.mjs
    // (docs/19 §4, O-L03) and turned into dist/licenses.txt.
    license: { fileName: '.vite/license.json' },
    rolldownOptions: {
      output: {
        // Pinned (Rolldown's current default) so the content hashes in asset file names do not
        // change with a bundler default (docs/16 §10.4).
        hashCharacters: 'base64',
      },
    },
  },
  test: {
    // The JUnit file feeds the traceability report (WO-53, test/README.md section 6): classname is
    // the test file path, name the describe/test titles. Built into Vitest (no extra dependency).
    // Vitest adds its GitHub Actions annotations only when no reporter is configured; keep them.
    reporters: [
      'default',
      ...(process.env.GITHUB_ACTIONS === 'true' ? ['github-actions'] : []),
      ['junit', { outputFile: 'build/test-results/vitest/junit.xml' }],
    ],
    projects: [
      {
        extends: true,
        test: {
          name: 'ui',
          environment: 'jsdom',
          include: ['src/**/*.test.{ts,tsx}'],
          setupFiles: ['./test/setup.ts'],
        },
      },
      {
        test: {
          name: 'tooling',
          environment: 'node',
          // perf/ and e2e/: helpers of the Playwright suites (trace analysis, statistics, server).
          include: [
            'scripts/**/*.test.mjs',
            'eslint-rules/**/*.test.mjs',
            'perf/**/*.test.ts',
            'e2e/**/*.test.ts',
          ],
        },
      },
    ],
  },
});
