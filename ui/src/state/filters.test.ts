// SPDX-License-Identifier: Apache-2.0
//
// Component filters → URL filters (docs/15 §4.2, docs/14 §6, 05 §2.0-4): field pattern, the
// 1,024-byte value limit in UTF-8 bytes (SEC-64), `in` as one `eq` per value (the URL reader's
// rule, splitInList), and the result written by the one URL serializer.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U16' }, { type: 'req', description: 'SEC-64' }
import { describe, expect, it } from 'vitest';

import type { FilterSpec } from '../components/filters';

import { toUrlFilter, toUrlFilters, type ComponentFilter } from './filters';
import {
  CommonQ,
  FILTER_MAX,
  FILTER_VALUE_MAX,
  parseUrl,
  splitInList,
  writeQuery,
} from './urlState';

describe('toUrlFilter (docs/15 §4.2, SEC-64)', () => {
  it('keeps field, operator and value; the value stays the same string', () => {
    expect(toUrlFilter({ field: 'service', op: 'eq', value: 'GET /api/orders/{id}' })).toEqual({
      field: 'service',
      op: 'eq',
      value: 'GET /api/orders/{id}',
    });
    expect(toUrlFilter({ field: 'status', op: 'ne', value: '200' })?.op).toBe('ne');
    expect(toUrlFilter({ field: 'elapsed', op: 'gte', value: '500' })?.op).toBe('gte');
    expect(toUrlFilter({ field: 'sql', op: 'contains', value: 'select' })?.op).toBe('contains');
  });

  it('writes `in` as eq (not part of the DSN-14-17 grammar)', () => {
    expect(toUrlFilter({ field: 'instance', op: 'in', value: 'api-1' })).toEqual({
      field: 'instance',
      op: 'eq',
      value: 'api-1',
    });
  });

  it.each(['500,503', '500,,503', ',500', '500,', ''])(
    'never writes the in list %j as one literal eq value (single filters only)',
    (value) => {
      expect(toUrlFilter({ field: 'status', op: 'in', value })).toBeNull();
    },
  );

  it('keeps a comma in the value of other operators (no list rule)', () => {
    expect(toUrlFilter({ field: 'sql', op: 'contains', value: 'a,b' })?.value).toBe('a,b');
  });

  it.each(['Service', '1abc', 'a-b', '', 'a'.repeat(33)])('refuses the field name %j', (field) => {
    expect(toUrlFilter({ field, op: 'eq', value: 'x' })).toBeNull();
  });

  it('limits the value to 1,024 UTF-8 bytes, not UTF-16 code units', () => {
    expect(toUrlFilter({ field: 'user', op: 'eq', value: 'x'.repeat(FILTER_VALUE_MAX) })).not.toBe(
      null,
    );
    expect(toUrlFilter({ field: 'user', op: 'eq', value: 'x'.repeat(FILTER_VALUE_MAX + 1) })).toBe(
      null,
    );
    // 342 Korean syllables are 342 code units but 1,026 bytes; 341 are 1,023 bytes.
    expect(toUrlFilter({ field: 'user', op: 'eq', value: '가'.repeat(341) })).not.toBeNull();
    expect(toUrlFilter({ field: 'user', op: 'eq', value: '가'.repeat(342) })).toBeNull();
  });

  it('accepts the components FilterSpec as is (structural type)', () => {
    const spec: FilterSpec = { field: 'host', op: 'in', value: 'db-1' };
    const asComponent: ComponentFilter = spec;
    expect(toUrlFilter(asComponent)).toEqual({ field: 'host', op: 'eq', value: 'db-1' });
  });

  it('gives filters the URL serializer writes and the loader reads back unchanged', () => {
    const filters = [
      toUrlFilter({ field: 'service', op: 'eq', value: '!starts-with-bang' }),
      toUrlFilter({ field: 'status', op: 'ne', value: '500' }),
      toUrlFilter({ field: 'elapsed', op: 'gte', value: '1000' }),
    ].filter((f) => f !== null);
    expect(filters).toHaveLength(3);
    const query = writeQuery(CommonQ, { ...parseUrl(CommonQ, '').state, filters });
    const parsed = parseUrl(CommonQ, query);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters).toEqual(filters);
  });
});

describe('toUrlFilters (docs/15 §4.2 in list, DSN-14-17, SEC-64)', () => {
  it('expands `in` into one eq filter per value, like the URL reader', () => {
    expect(toUrlFilters({ field: 'status', op: 'in', value: '500,503' })).toEqual([
      { field: 'status', op: 'eq', value: '500' },
      { field: 'status', op: 'eq', value: '503' },
    ]);
    expect(toUrlFilters({ field: 'status', op: 'in', value: '500,503' })).toEqual(
      parseUrl(CommonQ, 'f=status:in:500,503').state.filters,
    );
    expect(toUrlFilters({ field: 'instance', op: 'in', value: 'api-1' })).toEqual([
      { field: 'instance', op: 'eq', value: 'api-1' },
    ]);
  });

  it.each(['a,,b', ',a', 'a,', ''])('refuses the in list %j with an empty item', (value) => {
    expect(splitInList(value)).toBeNull();
    expect(toUrlFilters({ field: 'status', op: 'in', value })).toBeNull();
  });

  it('checks every value of an in list against 1,024 UTF-8 bytes', () => {
    const fits = 'x'.repeat(FILTER_VALUE_MAX);
    const tooLong = 'x'.repeat(FILTER_VALUE_MAX + 1);
    expect(toUrlFilters({ field: 'user', op: 'in', value: `a,${fits}` })).toHaveLength(2);
    expect(toUrlFilters({ field: 'user', op: 'in', value: `a,${tooLong}` })).toBeNull();
    expect(toUrlFilters({ field: 'user', op: 'in', value: tooLong })).toBeNull();
  });

  it('refuses an in list of more than FILTER_MAX values (never valid URL state)', () => {
    const values = Array.from({ length: FILTER_MAX + 1 }, (_, i) => String(500 + i));
    expect(
      toUrlFilters({ field: 'status', op: 'in', value: values.slice(0, FILTER_MAX).join(',') }),
    ).toHaveLength(FILTER_MAX);
    expect(toUrlFilters({ field: 'status', op: 'in', value: values.join(',') })).toBeNull();
  });

  it('refuses a bad field name and gives [toUrlFilter(spec)] for other operators', () => {
    expect(toUrlFilters({ field: 'Status', op: 'in', value: '500,503' })).toBeNull();
    expect(toUrlFilters({ field: 'status', op: 'ne', value: '500' })).toEqual([
      { field: 'status', op: 'ne', value: '500' },
    ]);
    expect(toUrlFilters({ field: 'sql', op: 'contains', value: 'a,b' })).toEqual([
      { field: 'sql', op: 'contains', value: 'a,b' },
    ]);
    expect(
      toUrlFilters({ field: 'user', op: 'eq', value: 'x'.repeat(FILTER_VALUE_MAX + 1) }),
    ).toBeNull();
  });

  it('gives eq filters the URL serializer writes and the loader reads back unchanged', () => {
    const filters = toUrlFilters({ field: 'status', op: 'in', value: '500,503' });
    expect(filters).not.toBeNull();
    const query = writeQuery(CommonQ, { ...parseUrl(CommonQ, '').state, filters: filters ?? [] });
    expect(query).toBe('f.status=500&f.status=503');
    const parsed = parseUrl(CommonQ, query);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters).toEqual([
      { field: 'status', op: 'eq', value: '500' },
      { field: 'status', op: 'eq', value: '503' },
    ]);
  });
});
