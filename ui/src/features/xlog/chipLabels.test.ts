// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { untrusted } from '../../api/untrusted';
import { XSS_PAYLOADS } from '../../components/testing/xssPayloads';

import {
  ERROR_CLASS_NAMES_MAX,
  mergeErrorClassNames,
  xlogFilterValueLabel,
  type ErrorClassNames,
} from './chipLabels';
import type { SummaryDictionary } from './summary/summaryModel';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U14' };

const DICTIONARY: SummaryDictionary = {
  services: ['GET /api/orders/{id}', XSS_PAYLOADS[0] ?? '<img>'],
  instances: ['api-1:8080', 'api-2:8080'],
  errorClasses: null,
  serviceIds: ['18446744073709551610', '18446744073709551611'],
  instanceIds: [7, 9],
};

describe('XLog chip labels (docs/15 §6.5, WO-33 review qa-ux-sec-5)', () => {
  it('T-UI-005 names service, instance and error class keys; unknown keys and other fields stay keys', () => {
    const errors: ErrorClassNames = new Map([['31', untrusted('java.sql.SQLTimeoutException')]]);
    const label = xlogFilterValueLabel(DICTIONARY, errors);
    expect(label({ field: 'service', op: 'eq', value: '18446744073709551610' })).toBe(
      'GET /api/orders/{id}',
    );
    // A collected XSS name comes back unchanged (rendered by SafeText as text).
    expect(label({ field: 'service', op: 'ne', value: '18446744073709551611' })).toBe(
      XSS_PAYLOADS[0],
    );
    expect(label({ field: 'instance', op: 'eq', value: '9' })).toBe('api-2:8080');
    expect(label({ field: 'error_class', op: 'eq', value: '31' })).toBe(
      'java.sql.SQLTimeoutException',
    );
    // 64-bit keys compare as text (C-175): a numerically equal spelling is another key.
    expect(label({ field: 'service', op: 'eq', value: '18446744073709551610.0' })).toBeNull();
    expect(label({ field: 'service', op: 'eq', value: '1' })).toBeNull();
    expect(label({ field: 'instance', op: 'eq', value: '8' })).toBeNull();
    expect(label({ field: 'error_class', op: 'eq', value: '32' })).toBeNull();
    expect(label({ field: 'status', op: 'eq', value: '500' })).toBeNull();
  });

  it('remembers error class names of server summaries, bounded, unchanged when nothing is new', () => {
    const empty: ErrorClassNames = new Map();
    const first = mergeErrorClassNames(empty, [
      { id: '1', name: 'A' },
      { id: null, name: 'no key' },
      { id: '2', name: null },
    ]);
    expect([...first.entries()]).toEqual([['1', 'A']]);
    expect(mergeErrorClassNames(first, [{ id: '1', name: 'A' }])).toBe(first);
    const renamed = mergeErrorClassNames(first, [{ id: '1', name: 'B' }]);
    expect(renamed.get('1')).toBe('B');
    const many = Array.from({ length: ERROR_CLASS_NAMES_MAX + 5 }, (_, i) => ({
      id: String(i),
      name: `E${String(i)}`,
    }));
    const bounded = mergeErrorClassNames(empty, many);
    expect(bounded.size).toBe(ERROR_CLASS_NAMES_MAX);
    // The oldest entries are dropped.
    expect(bounded.has('0')).toBe(false);
    expect(bounded.get(String(ERROR_CLASS_NAMES_MAX + 4))).toBe(
      `E${String(ERROR_CLASS_NAMES_MAX + 4)}`,
    );
  });
});
