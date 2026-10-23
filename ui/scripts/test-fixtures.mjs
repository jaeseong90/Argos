// SPDX-License-Identifier: Apache-2.0
// Helpers for the tooling tests: throwaway directory trees under the OS temp directory.
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

/**
 * Creates a temporary directory containing `files` (path -> content; objects are written as JSON).
 *
 * @param {Record<string, string | object>} files
 * @returns {{ root: string, cleanup: () => void }}
 */
export function createFixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'argos-ui-test-'));
  for (const [path, content] of Object.entries(files)) {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, typeof content === 'string' ? content : JSON.stringify(content, null, 2));
  }
  return {
    root,
    cleanup: () => {
      rmSync(root, { recursive: true, force: true });
    },
  };
}
