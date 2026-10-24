// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';

import react from '@vitejs/plugin-react';
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

export default defineConfig({
  plugins: [react()],
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
