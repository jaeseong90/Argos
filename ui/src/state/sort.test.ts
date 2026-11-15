// SPDX-License-Identifier: Apache-2.0
//
// Table sort ↔ URL `sort` (docs/15 §6.1, docs/14 §2.3): the conversions use the grammar of the URL
// field, so a table sort written to the URL is read back unchanged (round trip) and the loader
// keeps it.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U16' }
import { describe, expect, it } from 'vitest';

import { sortSpecToUrl, urlToSortSpec } from './sort';
import { CommonQ, parseUrl, writeQuery } from './urlState';

describe('table sort ↔ URL sort (docs/15 §6.1, docs/14 §2.3)', () => {
  it('writes field:asc|desc', () => {
    expect(sortSpecToUrl({ id: 'p95', desc: true })).toBe('p95:desc');
    expect(sortSpecToUrl({ id: 'total_time', desc: false })).toBe('total_time:asc');
    expect(sortSpecToUrl({ id: 'service.name', desc: false })).toBe('service.name:asc');
    expect(sortSpecToUrl(null)).toBeUndefined();
    expect(sortSpecToUrl(undefined)).toBeUndefined();
  });

  it.each(['P95', '9lives', 'a b', 'x:y', '', 'a'.repeat(65), '<script>'])(
    'refuses a column id outside the URL grammar: %j',
    (id) => {
      expect(sortSpecToUrl({ id, desc: true })).toBeUndefined();
    },
  );

  it('reads both URL forms: field:asc|desc (docs/14) and -field / field (docs/15)', () => {
    expect(urlToSortSpec('p95:desc')).toEqual({ id: 'p95', desc: true });
    expect(urlToSortSpec('count:asc')).toEqual({ id: 'count', desc: false });
    expect(urlToSortSpec('-avg')).toEqual({ id: 'avg', desc: true });
    expect(urlToSortSpec('avg')).toEqual({ id: 'avg', desc: false });
    expect(urlToSortSpec('service.name:desc')).toEqual({ id: 'service.name', desc: true });
    expect(urlToSortSpec(undefined)).toBeNull();
  });

  it.each(['p95:down', 'P95:asc', ':asc', 'a:b:asc', '--x', ''])(
    'reads an invalid sort value as no sort: %j',
    (value) => {
      expect(urlToSortSpec(value)).toBeNull();
    },
  );

  it('round-trips through the URL writer and the route loader', () => {
    for (const sort of [
      { id: 'p95', desc: true },
      { id: 'count', desc: false },
      { id: 'error_rate', desc: true },
      { id: 'service.name', desc: false },
    ]) {
      const value = sortSpecToUrl(sort);
      expect(urlToSortSpec(value)).toEqual(sort);
      const query = writeQuery(CommonQ, { ...parseUrl(CommonQ, '').state, sort: value });
      const parsed = parseUrl(CommonQ, query);
      expect(parsed.issues).toEqual([]);
      expect(parsed.state.sort).toBe(value);
      expect(urlToSortSpec(parsed.state.sort)).toEqual(sort);
    }
  });
});
