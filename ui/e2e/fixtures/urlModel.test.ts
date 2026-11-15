// SPDX-License-Identifier: Apache-2.0
//
// The E2E URL reader (e2e/fixtures/urlModel.ts) understands both grammars the UI accepts
// (docs/14 §6 canonical per ADR-0010 C-166, docs/15 §4.2) so that the specs assert meaning.
import { describe, expect, it } from 'vitest';

import { deepLinkQuery, effectiveKind, presetMs, urlState } from './urlModel';

describe('E2E URL state reader', () => {
  it('reads the docs/14 §6 grammar', () => {
    const s = urlState(
      '/apps/3/dashboard?env=prod&from=2030-01-01T10:00:00Z&to=2030-01-01T10:15:00Z&kind=batch&f.service=17&f.status=!200',
    );
    expect(s.path).toBe('/apps/3/dashboard');
    expect(s.time).toEqual({
      kind: 'absolute',
      fromMs: Date.parse('2030-01-01T10:00:00Z'),
      toMs: Date.parse('2030-01-01T10:15:00Z'),
    });
    expect(s.kind).toBe('BATCH');
    expect(s.env).toBe('prod');
    expect(s.live).toBe(false);
    expect(s.filters).toEqual([
      { field: 'service', op: 'eq', value: '17' },
      { field: 'status', op: 'ne', value: '200' },
    ]);
    expect(urlState('/x?from=now-1h&to=now&live=1').time).toEqual({
      kind: 'relative',
      range: '1h',
    });
    expect(urlState('/x?from=now-15m').time).toEqual({ kind: 'relative', range: '15m' });
    expect(urlState('/x?from=now/d').time).toEqual({ kind: 'relative', range: 'today' });
    expect(urlState('/x?from=now-1d/d&to=now/d').time).toEqual({
      kind: 'relative',
      range: 'yesterday',
    });
    expect(urlState('/x?f.elapsed=%3E%3D500&f.url=~orders&f.user=%5C!x').filters).toEqual([
      { field: 'elapsed', op: 'gte', value: '500' },
      { field: 'url', op: 'contains', value: 'orders' },
      { field: 'user', op: 'eq', value: '!x' },
    ]);
  });

  it('reads the docs/15 §4.2 grammar', () => {
    const s = urlState(
      `/x?range=15m&live=1&kind=http&f=${encodeURIComponent('service:eq:GET /a/{id}')}`,
    );
    expect(s.time).toEqual({ kind: 'relative', range: '15m' });
    expect(s.live).toBe(true);
    expect(effectiveKind(s)).toBe('HTTP');
    expect(s.filters).toEqual([{ field: 'service', op: 'eq', value: 'GET /a/{id}' }]);
    expect(urlState('/x?from=1893492000000&to=1893492900000').time).toEqual({
      kind: 'absolute',
      fromMs: 1893492000000,
      toMs: 1893492900000,
    });
  });

  it('reads a missing time as the default 15m, a broken one as default, a missing kind as HTTP', () => {
    expect(urlState('/x').time).toEqual({ kind: 'relative', range: '15m' });
    expect(urlState('/x?from=yesterday-ish').time).toEqual({ kind: 'default' });
    expect(effectiveKind(urlState('/x'))).toBe('HTTP');
  });

  it('writes canonical deep links and knows preset lengths', () => {
    expect(
      deepLinkQuery({
        from: '2030-01-01T10:00:00Z',
        to: '2030-01-01T10:15:00Z',
        filters: [
          { field: 'service', value: '17' },
          { field: 'status', value: '200', exclude: true },
        ],
      }),
    ).toBe(
      'from=2030-01-01T10%3A00%3A00Z&to=2030-01-01T10%3A15%3A00Z&f.service=17&f.status=%21200',
    );
    // A literal value that starts with an operator character is escaped and reads back unchanged.
    const literal = urlState(
      `/x?${deepLinkQuery({ filters: [{ field: 'service', value: '<img src=x>' }] })}`,
    );
    expect(literal.filters).toEqual([{ field: 'service', op: 'eq', value: '<img src=x>' }]);
    expect(presetMs('15m')).toBe(900_000);
    expect(presetMs('7d')).toBe(604_800_000);
    expect(presetMs('today')).toBeNull();
  });
});
