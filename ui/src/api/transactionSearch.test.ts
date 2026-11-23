// SPDX-License-Identifier: Apache-2.0
//
// The XLog URL state -> transaction search conversion shared by the XLog list and the detail's
// previous/next (ADR-0010 C-206 (3), docs/14 §4.6, §6): bounds from `sel` (inclusive selection,
// exclusive API `to`), the f= vocabulary, keys kept as decimal strings (C-175), filters the API
// cannot express reported instead of emulated, the summary request (C-202) and the server CSV
// export (C-205 (1)). The network is stubbed at the fetch boundary.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'SEC-63' }
import { afterEach, describe, expect, it } from 'vitest';

import { envelope, installApiStub, uninstallApiStub } from '../../test/apiStub';
import type { Filter, Selection } from '../state/urlState';

import {
  DEFAULT_SEARCH_SORT,
  SEARCH_PAGE_LIMIT,
  exportTransactionsCsv,
  queryBounds,
  searchSortOf,
  searchTransactions,
  summarizeXlog,
  toTransactionSearchRequest,
  toXlogPointFilters,
  toXlogSummaryRequest,
  type XLogSearchQuery,
} from './transactionSearch';
import { untrusted } from './untrusted';

afterEach(() => {
  uninstallApiStub();
});

/** A fixed instant derived from the current hour (no calendar constants). */
const HOUR = Math.floor(Date.now() / 3_600_000) * 3_600_000;
const SEL: Selection = { msMin: 200, msMax: 5000, tsFrom: HOUR - 600_000, tsTo: HOUR - 300_000 };

function f(field: string, value: string, op: Filter['op'] = 'eq'): Filter {
  return { field, op, value: untrusted(value) };
}

function query(partial: Partial<XLogSearchQuery> = {}): XLogSearchQuery {
  return {
    time: { kind: 'absolute', from: HOUR - 3_600_000, to: HOUR },
    kind: 'HTTP',
    filters: [],
    sort: undefined,
    sel: SEL,
    ...partial,
  };
}

describe('toTransactionSearchRequest (C-206 (3))', () => {
  it('T-UI-001 takes the range and elapsed bounds from sel, sorts by elapsed and asks for 1,000', () => {
    const call = toTransactionSearchRequest(7, query());
    expect(call.appId).toBe(7);
    expect(call.body).toEqual({
      from: new Date(SEL.tsFrom).toISOString(),
      // The selection is inclusive; the API's `to` is exclusive.
      to: new Date(SEL.tsTo + 1).toISOString(),
      kind: 'HTTP',
      filters: { min_ms: 200, max_ms: 5000 },
      sort: 'elapsed_ms:desc',
      limit: SEARCH_PAGE_LIMIT,
    });
    expect(call.unapplied).toEqual([]);
    expect(call.empty).toBe(false);
    expect(SEARCH_PAGE_LIMIT).toBe(1000);
  });

  it('T-UI-001 uses the resolved time range without a selection', () => {
    const absolute = toTransactionSearchRequest(1, query({ sel: undefined }));
    expect(absolute.body.from).toBe(new Date(HOUR - 3_600_000).toISOString());
    expect(absolute.body.to).toBe(new Date(HOUR).toISOString());
    expect(absolute.body.filters).toEqual({});

    // Relative ranges resolve at the 10-second query clock of docs/15 §4.2.
    const now = HOUR + 12_345;
    const relative = toTransactionSearchRequest(
      1,
      query({ sel: undefined, time: { kind: 'relative', range: '15m' } }),
      undefined,
      { now, timeZone: 'UTC' },
    );
    expect(relative.body.to).toBe(new Date(HOUR + 10_000).toISOString());
    expect(relative.body.from).toBe(new Date(HOUR + 10_000 - 900_000).toISOString());
  });

  it('T-UI-001 maps the f= vocabulary to the search filters, keys stay decimal strings (C-175)', () => {
    const call = toTransactionSearchRequest(
      1,
      query({
        kind: 'ALL',
        filters: [
          f('service', '18446744073709551615'),
          f('service', '42'),
          f('instance', '7'),
          f('status', '500'),
          f('status', '503'),
          f('error_class', '9007199254740993'),
          f('version', '12'),
          f('error', 'true'),
          f('profile', 'false'),
          f('user', 'alice@example.com'),
          f('request', 'req-1'),
          f('trace', '4BF92F3577B34DA6A3CE929D0E0E4736'),
          f('sql', '123'),
          f('error_group', '456'),
          f('remote_ip', '10.0.1.0'),
        ],
      }),
    );
    expect(call.body.kind).toBe('ALL');
    expect(call.body.filters).toEqual({
      service_id: ['18446744073709551615', '42'],
      instance_id: [7],
      http_status: [500, 503],
      // 2^53 + 1 survives because it is never converted to a number.
      error_class_id: ['9007199254740993'],
      version_id: ['12'],
      error: true,
      has_profile: false,
      user_id: 'alice@example.com',
      request_id: 'req-1',
      trace_id: '4bf92f3577b34da6a3ce929d0e0e4736',
      sql_id: '123',
      error_group_id: '456',
      remote_ip: '10.0.1.0',
      min_ms: 200,
      max_ms: 5000,
    });
    expect(call.unapplied).toEqual([]);
  });

  it('T-UI-001 reports exclusions, unknown fields and operators instead of emulating them', () => {
    const exclude = f('service', '42', 'ne');
    const unknown = f('host', 'db-1');
    const contains = f('user', 'ali', 'contains');
    const call = toTransactionSearchRequest(1, query({ filters: [exclude, unknown, contains] }));
    expect(call.unapplied).toEqual([
      { filter: exclude, reason: 'exclude' },
      { filter: unknown, reason: 'unknown_field' },
      { filter: contains, reason: 'operator' },
    ]);
    expect(call.body.filters).toEqual({ min_ms: 200, max_ms: 5000 });
    expect(call.empty).toBe(false);
  });

  it('T-UI-001 drops invalid keys from an "any of" list; only invalid values match nothing', () => {
    const bad = f('service', '<img src=x onerror=alert(1)>');
    const tooBig = f('service', '18446744073709551616');
    const mixed = toTransactionSearchRequest(
      1,
      query({ filters: [bad, f('service', '17'), tooBig] }),
    );
    expect(mixed.body.filters?.service_id).toEqual(['17']);
    expect(mixed.unapplied).toEqual([
      { filter: bad, reason: 'invalid_value' },
      { filter: tooBig, reason: 'invalid_value' },
    ]);
    expect(mixed.empty).toBe(false);

    const only = toTransactionSearchRequest(1, query({ filters: [bad] }));
    expect(only.body.filters?.service_id).toBeUndefined();
    expect(only.empty).toBe(true);

    const flag = toTransactionSearchRequest(1, query({ filters: [f('error', 'maybe')] }));
    expect(flag.empty).toBe(true);
    expect(flag.unapplied.map((u) => u.reason)).toEqual(['invalid_value']);
  });

  it('T-UI-001 refuses several values of a single-valued field', () => {
    const a = f('user', 'alice');
    const b = f('user', 'bob');
    const call = toTransactionSearchRequest(1, query({ filters: [a, b] }));
    expect(call.body.filters?.user_id).toBeUndefined();
    expect(call.unapplied).toEqual([
      { filter: a, reason: 'multiple_values' },
      { filter: b, reason: 'multiple_values' },
    ]);
    // The same value twice is one condition.
    const twice = toTransactionSearchRequest(1, query({ filters: [a, f('user', 'alice')] }));
    expect(twice.body.filters?.user_id).toBe('alice');
    expect(twice.unapplied).toEqual([]);
  });

  it('T-UI-001 intersects elapsed range filters with the selection', () => {
    const call = toTransactionSearchRequest(
      1,
      query({
        filters: [f('elapsed_ms', '1000', 'gte'), f('elapsed_ms', '3000', 'lt')],
      }),
    );
    expect(call.body.filters).toEqual({ min_ms: 1000, max_ms: 2999 });
    const disjoint = toTransactionSearchRequest(
      1,
      query({ filters: [f('elapsed_ms', '9000', 'gt')] }),
    );
    expect(disjoint.body.filters).toEqual({ min_ms: 9001, max_ms: 5000 });
    expect(disjoint.empty).toBe(true);
    const eq = f('elapsed_ms', '5', 'eq');
    expect(toTransactionSearchRequest(1, query({ filters: [eq] })).unapplied).toEqual([
      { filter: eq, reason: 'operator' },
    ]);
  });

  it('T-UI-001 sorts elapsed_ms:desc by default, ts:desc on request, and pages with the cursor', () => {
    expect(DEFAULT_SEARCH_SORT).toBe('elapsed_ms:desc');
    expect(toTransactionSearchRequest(1, query({ sort: 'ts:desc' })).body.sort).toBe('ts:desc');
    expect(toTransactionSearchRequest(1, query({ sort: 'elapsed_ms:asc' })).body.sort).toBe(
      'elapsed_ms:desc',
    );
    expect(searchSortOf('service:asc')).toBe('elapsed_ms:desc');
    expect(toTransactionSearchRequest(1, query(), 'abc').body.cursor).toBe('abc');
    expect(toTransactionSearchRequest(1, query(), null).body).not.toHaveProperty('cursor');
    expect(toTransactionSearchRequest(1, query(), '').body).not.toHaveProperty('cursor');
  });

  it('T-UI-001 queryBounds keeps points at the right edge of the selection', () => {
    expect(queryBounds({ time: query().time, sel: SEL })).toEqual({
      from: SEL.tsFrom,
      to: SEL.tsTo + 1,
      minMs: SEL.msMin,
      maxMs: SEL.msMax,
    });
  });
});

describe('XLog points and summary filters (C-202)', () => {
  it('T-UI-001 keeps only the fields of GET /xlog and the summary', () => {
    const status = f('status', '500');
    const exclude = f('instance', '3', 'ne');
    const points = toXlogPointFilters([
      f('service', '42'),
      f('instance', '3'),
      f('error', 'true'),
      status,
      exclude,
      f('elapsed_ms', '100', 'gte'),
    ]);
    expect(points).toEqual({
      service: ['42'],
      instance: [3],
      error: true,
      minMs: 100,
      unapplied: [
        { filter: status, reason: 'not_supported_here' },
        { filter: exclude, reason: 'exclude' },
      ],
      empty: false,
    });
  });

  it('T-UI-001 builds the summary request of a selection; none without one', () => {
    expect(toXlogSummaryRequest(1, query({ sel: undefined }))).toBeNull();
    const call = toXlogSummaryRequest(
      4,
      query({
        kind: 'BATCH',
        filters: [f('service', '42'), f('instance', '3'), f('version', '1')],
      }),
    );
    expect(call?.appId).toBe(4);
    expect(call?.body).toEqual({
      from: new Date(SEL.tsFrom).toISOString(),
      to: new Date(SEL.tsTo + 1).toISOString(),
      kind: 'BATCH',
      min_ms: 200,
      max_ms: 5000,
      service: ['42'],
      instance: [3],
    });
    expect(call?.unapplied.map((u) => u.reason)).toEqual(['not_supported_here']);
  });
});

describe('search, summary and CSV calls', () => {
  it('T-UI-001 posts the request to transactions/search and returns rows and meta', async () => {
    const stub = installApiStub();
    stub.on('POST /api/v1/apps/7/transactions/search', () =>
      envelope([], { shown: 0, total: 0, next_cursor: null }),
    );
    const call = toTransactionSearchRequest(7, query({ filters: [f('service', '42')] }));
    const result = await searchTransactions(call);
    expect(result.data).toEqual([]);
    expect(result.meta.total).toBe(0);
    const sent = stub.calls('POST /api/v1/apps/7/transactions/search')[0];
    expect(sent?.body).toEqual(call.body);
    expect(sent?.query.get('format')).toBeNull();
  });

  it('T-UI-001 posts the summary request to xlog/summary', async () => {
    const stub = installApiStub();
    stub.on('POST /api/v1/apps/7/xlog/summary', () =>
      envelope({ count: 3, by_service: [], by_instance: [], by_error_class: [], by_status: [] }),
    );
    const call = toXlogSummaryRequest(7, query());
    expect(call).not.toBeNull();
    if (call === null) return;
    const result = await summarizeXlog(call);
    expect(result.data.count).toBe(3);
    expect(stub.calls('POST /api/v1/apps/7/xlog/summary')[0]?.body).toEqual(call.body);
  });

  it('T-UI-004 exports CSV through the server (format=csv), from the first row', async () => {
    const stub = installApiStub();
    stub.on(
      'POST /api/v1/apps/7/transactions/search',
      () =>
        new Response('﻿tx_id\r\n1\r\n', {
          status: 200,
          headers: {
            'Content-Type': 'text/csv; charset=utf-8',
            'Content-Disposition': 'attachment; filename="argos-transactions-a-b.csv"',
          },
        }),
    );
    const clicked: string[] = [];
    const doc = {
      createElement: () => ({
        click: () => {
          clicked.push('click');
        },
        remove: () => undefined,
      }),
      body: { append: () => undefined },
    } as unknown as Document;
    const original = Object.getOwnPropertyDescriptor(URL, 'createObjectURL');
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: () => 'blob:test',
    });
    try {
      const call = toTransactionSearchRequest(7, query(), 'cursor-1');
      const name = await exportTransactionsCsv(call, {
        document: doc,
        setTimeout: () => undefined,
      });
      expect(name).toBe('argos-transactions-a-b.csv');
      const sent = stub.calls('POST /api/v1/apps/7/transactions/search')[0];
      expect(sent?.query.get('format')).toBe('csv');
      expect(sent?.body).not.toHaveProperty('cursor');
      expect(sent?.body).not.toHaveProperty('limit');
      expect(sent?.body).toMatchObject({ from: call.body.from, to: call.body.to, kind: 'HTTP' });
      expect(clicked).toEqual(['click']);
    } finally {
      if (original !== undefined) {
        Object.defineProperty(URL, 'createObjectURL', original);
      }
    }
  });
});
