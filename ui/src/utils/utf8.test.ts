// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { truncateUtf8, utf8ByteLength } from './utf8';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-64' };

describe('UTF-8 byte lengths (SEC-64, docs/14 §7.2)', () => {
  it('counts UTF-8 bytes, not UTF-16 code units', () => {
    expect(utf8ByteLength('')).toBe(0);
    expect(utf8ByteLength('abc')).toBe(3);
    expect(utf8ByteLength('é')).toBe(2);
    expect(utf8ByteLength('가')).toBe(3);
    expect(utf8ByteLength('😀')).toBe(4);
    expect('😀'.length).toBe(2);
  });

  it('truncates to the longest prefix that fits without splitting a character', () => {
    expect(truncateUtf8('abc', 3)).toBe('abc');
    expect(truncateUtf8('abcd', 3)).toBe('abc');
    expect(truncateUtf8('가나다', 7)).toBe('가나');
    expect(truncateUtf8('a😀b', 4)).toBe('a');
    expect(truncateUtf8('a😀b', 5)).toBe('a😀');
    expect(utf8ByteLength(truncateUtf8('가'.repeat(400), 1024))).toBe(1023);
    expect(utf8ByteLength(truncateUtf8('😀'.repeat(300), 1024))).toBe(1024);
  });
});
