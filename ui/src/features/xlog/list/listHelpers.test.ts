// SPDX-License-Identifier: Apache-2.0
//
// Pure parts of the XLog list (05 §2.1-8, -9, ADR-0010 C-206): column mapping with missing values
// as null (T-UI-007), the user id column only when collected, `cols=` round trip, detail links with
// the `ts` hint and the `back` list within the 2 KB write limit, and the bounded list context the
// detail's previous/next reads.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U02' }, { type: 'req', description: 'D-23' }
import type { TFunction } from 'i18next';
import { afterEach, describe, expect, it } from 'vitest';

import { untrusted } from '../../../api/untrusted';
import { createI18n } from '../../../i18n';
import { URL_WRITE_LIMIT, type Filter } from '../../../state/urlState';

import {
  columnsFromUrl,
  columnsToUrl,
  formatListTime,
  httpStatusOf,
  listColumns,
  userIdCollected,
  type TxRow,
} from './columns';
import { transactionDetailUrl, transactionPath } from './detailLink';
import {
  MAX_ENTRIES,
  clearListContexts,
  getListContext,
  listContextCount,
  listNeighbours,
  saveListContext,
} from './listContext';
import { filtersAfterCellFilter, selectionKey, sortToUrl } from './TxListPanel';

const HOUR = Math.floor(Date.now() / 3_600_000) * 3_600_000;

export function row(partial: Partial<TxRow> = {}): TxRow {
  return {
    tx_id: '7312000000000000001',
    ts: new Date(HOUR - 60_000).toISOString(),
    service: untrusted('GET /api/orders/{id}'),
    service_id: '18446744073709551615',
    elapsed_ms: 1832,
    http_status: 500,
    error: true,
    error_class: untrusted('java.sql.SQLTimeoutException'),
    error_class_id: '9007199254740993',
    instance: untrusted('web-01:8080'),
    instance_id: 12,
    sql_count: 23,
    sql_time_ms: 1650,
    ext_count: 0,
    ext_time_ms: 0,
    user_id: null,
    user_id_mode: 'off',
    has_profile: true,
    kind: 'HTTP',
    version: untrusted('1.4.2'),
    version_id: '33',
    ...partial,
  };
}

const i18n = createI18n('en');
const t = i18n.getFixedT('en', ['common', 'xlog']) as unknown as TFunction<['common', 'xlog']>;

function cell(rowValue: TxRow, id: string): string | number | null | undefined {
  const column = listColumns(t, 'en', true).find((c) => c.id === id);
  if (column === undefined) {
    throw new Error(`no column ${id}`);
  }
  return column.value(rowValue);
}

afterEach(() => {
  clearListContexts();
});

describe('list columns (05 §2.1-8)', () => {
  it('T-UI-007 has every required column and keeps missing values null (never 0)', () => {
    const ids = listColumns(t, 'en', true).map((c) => c.id);
    expect(ids).toEqual([
      'ts',
      'service',
      'elapsed_ms',
      'http_status',
      'error_class',
      'instance',
      'sql_count',
      'sql_time_ms',
      'ext_count',
      'ext_time_ms',
      'user_id',
      'has_profile',
      'kind',
      'version',
      'tx_id',
    ]);
    const missing = row({
      service: null,
      service_id: null,
      error_class: null,
      error_class_id: null,
      instance: null,
      version: null,
      version_id: null,
      user_id: null,
    });
    for (const id of ['service', 'error_class', 'instance', 'version', 'user_id']) {
      expect(cell(missing, id), id).toBeNull();
    }
    // Measured zeros stay zeros.
    expect(cell(missing, 'ext_count')).toBe(0);
    expect(cell(missing, 'ext_time_ms')).toBe(0);
    expect(cell(row(), 'elapsed_ms')).toBe(1832);
  });

  it('T-UI-007 shows the start time as month, day and time of day with seconds (qa-ux-sec-7)', () => {
    const ms = Date.parse(row().ts);
    expect(cell(row(), 'ts')).toBe(formatListTime(ms, 'en'));
    expect(formatListTime(ms, 'en')).toMatch(/^\d{2}\/\d{2}, \d{2}:\d{2}:\d{2}$/);
    expect(formatListTime(ms, 'ko')).toMatch(/^\d{2}\. \d{2}\. \d{2}:\d{2}:\d{2}$/);
    expect(formatListTime(ms, 'en')).not.toContain(String(new Date(ms).getFullYear()));
    expect(cell(row({ ts: 'not a time' }), 'ts')).toBeNull();
  });

  it('T-UI-007 shows an HTTP status only for HTTP transactions', () => {
    expect(httpStatusOf(row())).toBe(500);
    // Batch jobs use 1/0 for failure/success (docs/14 §4.6): not an HTTP status.
    expect(httpStatusOf(row({ kind: 'BATCH', http_status: 1 }))).toBeNull();
    expect(httpStatusOf(row({ http_status: 0 }))).toBeNull();
    expect(cell(row({ kind: 'BATCH', http_status: 1 }), 'http_status')).toBeNull();
  });

  it('T-UI-004 filters cells by key: service, error class and version keys stay strings (C-175)', () => {
    const columns = listColumns(t, 'en', false);
    const byId = new Map(columns.map((c) => [c.id, c]));
    expect(byId.get('service')?.filterField).toBe('service');
    expect(byId.get('service')?.filterValue?.(row())).toBe('18446744073709551615');
    expect(byId.get('error_class')?.filterField).toBe('error_class');
    expect(byId.get('error_class')?.filterValue?.(row())).toBe('9007199254740993');
    expect(byId.get('instance')?.filterField).toBe('instance');
    expect(byId.get('instance')?.filterValue?.(row())).toBe('12');
    expect(byId.get('http_status')?.filterField).toBe('status');
    expect(byId.get('http_status')?.filterValue?.(row())).toBe('500');
    expect(byId.get('kind')?.filterValue?.(row())).toBe('HTTP');
    expect(byId.get('version')?.filterValue?.(row())).toBe('33');
    // A row without a key offers no include/exclude on that column.
    expect(byId.get('service')?.filterValue?.(row({ service_id: null }))).toBeNull();
    // Only the two server sorts are sortable.
    expect(columns.filter((c) => c.sortable === true).map((c) => c.id)).toEqual([
      'ts',
      'elapsed_ms',
    ]);
    // Without collection there is no user id column.
    expect(byId.has('user_id')).toBe(false);
  });

  it('T-UI-007 knows when user ids are collected', () => {
    expect(userIdCollected([row(), row({ user_id_mode: null })])).toBe(false);
    expect(
      userIdCollected([row(), row({ user_id_mode: 'hashed', user_id: untrusted('ab') })]),
    ).toBe(true);
    expect(userIdCollected([row({ user_id_mode: 'plain', user_id: untrusted('alice') })])).toBe(
      true,
    );
  });

  it('T-UI-004 keeps the column choice in cols= and drops unknown ids', () => {
    expect(columnsFromUrl(undefined)).toBeUndefined();
    expect(columnsFromUrl('ts,service,bogus')).toEqual(['ts', 'service']);
    expect(columnsFromUrl('bogus')).toBeUndefined();
    const defaults = ['ts', 'service', 'elapsed_ms'];
    expect(columnsToUrl(['elapsed_ms', 'ts', 'service'], defaults)).toBeUndefined();
    expect(columnsToUrl(['ts', 'elapsed_ms'], defaults)).toBe('ts,elapsed_ms');
  });

  it('T-UI-004 writes only the two server sorts and the selection key of the URL', () => {
    expect(sortToUrl({ id: 'ts', desc: false })).toBe('ts:desc');
    expect(sortToUrl({ id: 'elapsed_ms', desc: false })).toBeUndefined();
    expect(sortToUrl(null)).toBeUndefined();
    expect(selectionKey({ sel: { msMin: 1, msMax: 2, tsFrom: 3, tsTo: 4 } })).toBe('ms:1-2,ts:3-4');
    expect(selectionKey({ sel: undefined })).toBe('');
  });

  it('T-UI-004 turns a cell include/exclude into URL filters (flip, limit 20)', () => {
    const current: Filter[] = [{ field: 'service', op: 'eq', value: untrusted('1') }];
    expect(filtersAfterCellFilter(current, { field: 'instance', op: 'ne', value: '12' })).toEqual([
      { field: 'service', op: 'eq', value: '1' },
      { field: 'instance', op: 'ne', value: '12' },
    ]);
    // Exclude after include of the same value flips it.
    expect(filtersAfterCellFilter(current, { field: 'service', op: 'ne', value: '1' })).toEqual([
      { field: 'service', op: 'ne', value: '1' },
    ]);
    const full: Filter[] = Array.from({ length: 20 }, (_, i) => ({
      field: 'status',
      op: 'eq',
      value: untrusted(String(400 + i)),
    }));
    expect(filtersAfterCellFilter(full, { field: 'service', op: 'eq', value: '9' })).toBeNull();
  });
});

describe('detail links (C-206 (1))', () => {
  const back =
    '/apps/3/xlog?from=2026-11-14T00:00:00.000Z&to=2026-11-14T00:15:00.000Z&sel=ms:0-5000,ts:1-2';

  it('T-UI-001 carries the ts hint and the back link', () => {
    const ts = HOUR - 60_000;
    const url = transactionDetailUrl(3, '7312000000000000001', ts, back);
    const [path, query] = url.split('?');
    expect(path).toBe('/apps/3/transactions/7312000000000000001');
    const params = new URLSearchParams(query);
    expect(params.get('ts')).toBe(new Date(ts).toISOString());
    expect(params.get('back')).toBe(back);
    // ISO input gives the same hint.
    expect(transactionDetailUrl(3, '1', new Date(ts).toISOString(), back)).toBe(
      transactionDetailUrl(3, '1', ts, back),
    );
    expect(transactionPath(3, '18446744073709551615')).toBe(
      '/apps/3/transactions/18446744073709551615',
    );
  });

  it('T-UI-003 stays within the 2 KB write limit: shorter back first, none last', () => {
    const long = `${back}&cols=${'a'.repeat(1500)}&color=service&f.user=${'u'.repeat(400)}`;
    const shortened = transactionDetailUrl(3, '1', HOUR, long);
    expect(shortened.length).toBeLessThanOrEqual(URL_WRITE_LIMIT);
    const kept = new URLSearchParams(shortened.split('?')[1]).get('back') ?? '';
    expect(kept).toContain('sel=ms:0-5000');
    expect(kept).not.toContain('cols=');
    expect(kept).not.toContain('color=');

    // A valid XLog URL (at most 2 KB) whose detail link would not fit with it.
    const huge = `${back}&f.user=${'u'.repeat(1880)}`;
    expect(huge.length).toBeLessThanOrEqual(URL_WRITE_LIMIT);
    const none = transactionDetailUrl(3, '1', HOUR, huge);
    expect(none.length).toBeLessThanOrEqual(URL_WRITE_LIMIT);
    expect(new URLSearchParams(none.split('?')[1]).get('back')).toBeNull();
    expect(new URLSearchParams(none.split('?')[1]).get('ts')).toBe(new Date(HOUR).toISOString());
  });

  it('T-UI-003 never carries a back link that is not a same-origin path', () => {
    for (const bad of ['//evil.example/x', 'https://evil.example/', '/login?next=/x', '/a\\b']) {
      const url = transactionDetailUrl(3, '1', HOUR, bad);
      expect(new URLSearchParams(url.split('?')[1]).get('back'), bad).toBeNull();
    }
    expect(transactionDetailUrl(3, '1', 'not a time', back)).toBe('/apps/3/transactions/1');
  });
});

describe('list context (05 §2.1-9, C-206 (2))', () => {
  const query = {
    time: { kind: 'relative' as const, range: '15m' as const },
    kind: 'HTTP' as const,
    filters: [],
    sort: undefined,
    sel: { msMin: 0, msMax: 10, tsFrom: 1, tsTo: 2 },
  };
  const rows = [
    { txId: '1', ts: 10 },
    { txId: '2', ts: 20 },
    { txId: '3', ts: 30 },
  ];

  it('T-UI-001 gives the neighbours of a transaction in list order', () => {
    saveListContext({ back: '/apps/1/xlog?a', appId: 1, query, rows, total: 5, nextCursor: 'c' });
    expect(getListContext('/apps/1/xlog?a')?.rows).toEqual(rows);
    expect(listNeighbours('/apps/1/xlog?a', '2')).toEqual({
      index: 1,
      previous: { txId: '1', ts: 10 },
      next: { txId: '3', ts: 30 },
      moreAfter: false,
    });
    expect(listNeighbours('/apps/1/xlog?a', '1')?.previous).toBeNull();
    expect(listNeighbours('/apps/1/xlog?a', '3')).toMatchObject({ next: null, moreAfter: true });
    expect(listNeighbours('/apps/1/xlog?a', '9')?.index).toBe(-1);
    expect(listNeighbours('/apps/1/xlog?other', '2')).toBeNull();
    expect(listNeighbours(null, '2')).toBeNull();
  });

  it('T-UI-001 is bounded: at most 8 lists of at most 1,000 rows', () => {
    for (let i = 0; i < MAX_ENTRIES + 3; i += 1) {
      saveListContext({
        back: `/x?${String(i)}`,
        appId: 1,
        query,
        rows,
        total: 3,
        nextCursor: null,
      });
    }
    expect(listContextCount()).toBe(MAX_ENTRIES);
    expect(getListContext('/x?0')).toBeNull();
    expect(getListContext(`/x?${String(MAX_ENTRIES + 2)}`)).not.toBeNull();
    const many = Array.from({ length: 1500 }, (_, i) => ({ txId: String(i), ts: i }));
    saveListContext({ back: '/big', appId: 1, query, rows: many, total: 1500, nextCursor: 'n' });
    expect(getListContext('/big')?.rows).toHaveLength(1000);
  });
});
