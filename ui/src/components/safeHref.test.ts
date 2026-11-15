// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { isExternalHref, safeHref } from './safeHref';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'SEC-61' };

const JS = 'javascript';

describe('safeHref (docs/16 §9.3)', () => {
  it('T-SEC-61 keeps http(s) URLs and internal paths', () => {
    expect(safeHref('https://logs.example.test/search?q=a')).toBe(
      'https://logs.example.test/search?q=a',
    );
    expect(safeHref('http://runbook.example.test/x')).toBe('http://runbook.example.test/x');
    expect(safeHref('HTTPS://Example.TEST/a')).toBe('https://example.test/a');
    expect(safeHref('/apps/1/xlog?from=1&to=2')).toBe('/apps/1/xlog?from=1&to=2');
    expect(safeHref('/')).toBe('/');
  });

  it('T-SEC-61 rejects javascript:, data:, vbscript:, protocol-relative and other targets', () => {
    const rejected = [
      `${JS}:window.__xss=1`,
      'Java' + 'Script:alert(1)',
      ` ${JS}:window.__xss=1`,
      `java\tscript:window.__xss=1`,
      'data:text/html,<script>window.__xss=1</script>',
      'vbscript:msgbox(1)',
      '//evil.example.test/path',
      '/\\evil.example.test',
      '/\t/evil.example.test',
      '\\\\evil.example.test',
      'ftp://files.example.test',
      'mailto:a@example.test',
      'relative/path',
      './x',
      'https://',
      '',
      'h'.repeat(9000),
    ];
    for (const value of rejected) {
      expect(safeHref(value), JSON.stringify(value)).toBeUndefined();
    }
    expect(safeHref(null)).toBeUndefined();
    expect(safeHref(undefined)).toBeUndefined();
  });

  it('tells external targets from internal paths', () => {
    expect(isExternalHref('https://a.example.test')).toBe(true);
    expect(isExternalHref('/apps/1')).toBe(false);
  });
});
