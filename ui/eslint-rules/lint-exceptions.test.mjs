// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import { parseLintExceptions, toConfigBlocks } from './lint-exceptions.mjs';

const VALID = {
  files: ['src/xlog/renderer.ts'],
  rules: ['no-restricted-syntax'],
  reason: 'Canvas text measurement needs a detached element; no untrusted input reaches it.',
};

describe('parseLintExceptions (SEC-60 file allowlist)', () => {
  it('accepts the committed eslint-allow.json', () => {
    const data = JSON.parse(readFileSync(new URL('../eslint-allow.json', import.meta.url), 'utf8'));
    expect(parseLintExceptions(data)).toEqual([]);
  });

  it('turns entries into config blocks that switch the listed rules off for the listed files', () => {
    expect(toConfigBlocks(parseLintExceptions({ exceptions: [VALID] }))).toEqual([
      {
        name: 'argos/lint-exception-0',
        files: ['src/xlog/renderer.ts'],
        rules: { 'no-restricted-syntax': 'off' },
      },
    ]);
  });

  it.each([
    [{}, /"exceptions" array/],
    [{ exceptions: [{ ...VALID, reason: '' }] }, /reason must explain/],
    [{ exceptions: [{ ...VALID, reason: 'because' }] }, /reason must explain/],
    [{ exceptions: [{ ...VALID, files: [] }] }, /files must be a non-empty array/],
    [{ exceptions: [{ ...VALID, files: ['src/**/*.ts'] }] }, /exact relative files/],
    [{ exceptions: [{ ...VALID, files: ['../outside.ts'] }] }, /exact relative files/],
    [{ exceptions: [{ ...VALID, rules: [] }] }, /rules must be a non-empty array/],
  ])('rejects %j', (data, error) => {
    expect(() => parseLintExceptions(data)).toThrow(error);
  });
});
