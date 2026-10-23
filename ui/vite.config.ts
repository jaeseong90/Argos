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

export default defineConfig({
  plugins: [react()],
  define: {
    __ARGOS_VERSION__: JSON.stringify(packageVersion()),
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
          include: ['scripts/**/*.test.mjs', 'eslint-rules/**/*.test.mjs'],
        },
      },
    ],
  },
});
