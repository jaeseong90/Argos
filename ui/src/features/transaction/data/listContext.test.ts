// SPDX-License-Identifier: Apache-2.0
//
// The detail and trace route state (docs/14 §6, ADR-0010 C-206 (1)): `back` (a same-origin XLog
// path and query carried by detail links, validated like ?next=), the trace `window` of docs/14
// §4.7, and parseBack for back-to-list and previous/next (the XLog state parsed with XLogQ).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'SEC-64' }
import { describe, expect, it } from 'vitest';

import {
  encodeQueryComponent,
  parseUrl,
  TraceQ,
  TxDetailQ,
  writeQuery,
} from '../../../state/urlState';

import { adjacentUnavailable, parseBack } from './listContext';

// Concatenated so this file does not trip no-script-url.
const JS = 'javascript';

const XLOG_BACK =
  '/apps/3/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T01:00:00.000Z&f.service=17&sel=ms:500-2000,ts:1763078400000-1763078700000&sort=elapsed_ms:desc';

describe('T-UI-003 detail route state: back (C-206 (1)) and trace window (docs/14 §4.7)', () => {
  it('reads and writes back unchanged (one canonical spelling, no redirect loop)', () => {
    const query = `ts=2026-11-14T00:00:01.000Z&back=${encodeQueryComponent(XLOG_BACK)}`;
    const parsed = parseUrl(TxDetailQ, query);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.back).toBe(XLOG_BACK);
    expect(parsed.canonical).toBe(query);
    expect(writeQuery(TxDetailQ, parsed.state)).toBe(query);
  });

  it.each([
    'https://evil.example/apps/3/xlog',
    '//evil.example/x',
    '/\\evil',
    `${JS}:alert(1)`,
    'apps/3/xlog',
    '/login?next=/x',
    `/apps/3/xlog?x=${'a'.repeat(2100)}`,
  ])('refuses a back that is not a same-origin path: %s', (back) => {
    const parsed = parseUrl(TxDetailQ, `back=${encodeURIComponent(back)}`);
    expect(parsed.state.back).toBeUndefined();
    expect(parsed.issues).toEqual([{ key: 'back', reason: 'invalid' }]);
  });

  it('reads the trace window values of docs/14 §4.7 and refuses others', () => {
    expect(parseUrl(TraceQ, 'window=7d').state.window).toBe('7d');
    expect(parseUrl(TraceQ, 'window=30d&ts=2026-11-14T00:00:01.000Z').canonical).toBe(
      'ts=2026-11-14T00:00:01.000Z&window=30d',
    );
    expect(parseUrl(TraceQ, '').state.window).toBeUndefined();
    const bad = parseUrl(TraceQ, 'window=2d');
    expect(bad.state.window).toBeUndefined();
    expect(bad.issues).toEqual([{ key: 'window', reason: 'invalid' }]);
  });
});

describe('parseBack (C-206 (2): back to the list, previous/next from the same list)', () => {
  it('parses an XLog back with the XLog schema (sel, filters, sort)', () => {
    const back = parseBack(XLOG_BACK);
    expect(back?.href).toBe(XLOG_BACK);
    expect(back?.xlog?.appId).toBe(3);
    expect(back?.xlog?.issueCount).toBe(0);
    expect(back?.xlog?.state.sel).toEqual({
      msMin: 500,
      msMax: 2000,
      tsFrom: 1763078400000,
      tsTo: 1763078700000,
    });
    expect(back?.xlog?.state.filters).toEqual([{ field: 'service', op: 'eq', value: '17' }]);
    expect(back?.xlog?.state.sort).toBe('elapsed_ms:desc');
    expect(adjacentUnavailable(back)).toBeNull();
  });

  it('keeps another same-origin page as a way back, without a list to re-query', () => {
    const back = parseBack('/apps/3/stats/sql/123?from=now-1h');
    expect(back).toEqual({ href: '/apps/3/stats/sql/123?from=now-1h', xlog: null });
    expect(adjacentUnavailable(back)).toBe('not_xlog');
  });

  it('has no way back without a usable back', () => {
    for (const value of [undefined, null, '', '//evil', 'https://x']) {
      expect(parseBack(value), String(value)).toBeNull();
    }
    expect(adjacentUnavailable(null)).toBe('no_list');
  });

  it('counts XLog items that fell back to defaults', () => {
    const back = parseBack('/apps/9/xlog?color=rainbow&y=log');
    expect(back?.xlog?.issueCount).toBe(1);
    expect(back?.xlog?.state.y).toBe('log');
    expect(back?.xlog?.state.color).toBe('status');
  });
});
