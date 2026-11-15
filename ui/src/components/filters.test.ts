// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  loadColumnWidths,
  MAX_COLUMN_WIDTH,
  MIN_COLUMN_WIDTH,
  saveColumnWidths,
} from './columnWidths';
import { addCellFilter, sameFilter, type FilterSpec } from './filters';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U16' };

describe('cell filters (05 §2.0-4)', () => {
  it('T-UI-004 adds include/exclude filters, keeps duplicates out and flips eq/ne', () => {
    const start: FilterSpec[] = [{ field: 'service', op: 'contains', value: 'api' }];
    const included = addCellFilter(start, { field: 'instance', op: 'eq', value: 'a-1' });
    expect(included).toEqual([...start, { field: 'instance', op: 'eq', value: 'a-1' }]);
    expect(addCellFilter(included, { field: 'instance', op: 'eq', value: 'a-1' })).toEqual(
      included,
    );
    expect(addCellFilter(included, { field: 'instance', op: 'ne', value: 'a-1' })).toEqual([
      ...start,
      { field: 'instance', op: 'ne', value: 'a-1' },
    ]);
    expect(
      sameFilter(start[0] as FilterSpec, { field: 'service', op: 'contains', value: 'api' }),
    ).toBe(true);
  });
});

describe('column widths in localStorage (docs/15 §4.1)', () => {
  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('stores widths of every table under the one key argos.ui.cols, clamped', () => {
    expect(saveColumnWidths('services', { name: 300, p95: 10, max: 99999 })).toBe(true);
    expect(saveColumnWidths('sql', { text: 400 })).toBe(true);
    expect(loadColumnWidths('services')).toEqual({
      name: 300,
      p95: MIN_COLUMN_WIDTH,
      max: MAX_COLUMN_WIDTH,
    });
    expect(loadColumnWidths('sql')).toEqual({ text: 400 });
    expect(Object.keys(localStorage)).toEqual(['argos.ui.cols']);
  });

  it('ignores corrupt or foreign content and keeps at most 50 tables', () => {
    localStorage.setItem('argos.ui.cols', '{"t":{"a":"wide","b":120},"bad id!":{"x":1}}');
    expect(loadColumnWidths('t')).toEqual({ b: 120 });
    localStorage.setItem('argos.ui.cols', 'not json');
    expect(loadColumnWidths('t')).toEqual({});
    for (let i = 0; i < 60; i += 1) {
      saveColumnWidths(`table${String(i)}`, { a: 100 });
    }
    const stored = JSON.parse(localStorage.getItem('argos.ui.cols') ?? '{}') as Record<
      string,
      unknown
    >;
    expect(Object.keys(stored)).toHaveLength(50);
    expect(loadColumnWidths('table59')).toEqual({ a: 100 });
    expect(loadColumnWidths('table0')).toEqual({});
  });

  it('never throws when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(loadColumnWidths('t')).toEqual({});
    expect(saveColumnWidths('t', { a: 100 })).toBe(false);
  });
});
