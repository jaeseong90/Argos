// SPDX-License-Identifier: Apache-2.0
//
// Server CSV export (T-UI-004, DSN-15-06, docs/14 §4.17, C-176): the request goes through the shared
// transport (format=csv, Accept: text/csv, X-Argos-CSRF on POST, one refresh on 401), the file name
// comes from Content-Disposition (sanitised), a 429 is an ApiError with Retry-After and no retry,
// and the one download helper revokes its object URL. The network is stubbed at the fetch boundary.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U16' }, { type: 'req', description: 'SEC-63' }, { type: 'req', description: 'SEC-43' }
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { errorBody, installApiStub, json, uninstallApiStub } from '../../test/apiStub';

import { isApiError, isRetryable, type ApiError } from './errors';
import {
  csvFileName,
  DEFAULT_CSV_FILE_NAME,
  exportCsv,
  MAX_CSV_FILE_NAME_LENGTH,
  requestCsv,
  REVOKE_DELAY_MS,
  sanitizeCsvFileName,
  saveFile,
} from './exportCsv';

const SQL_STATS = 'GET /api/v1/apps/1/sql/stats';
const SEARCH = 'POST /api/v1/apps/1/transactions/search';
/** The last full hour (no calendar constants in tests). */
const TO = new Date(Math.floor(Date.now() / 3_600_000) * 3_600_000).toISOString();
const FROM = new Date(Date.parse(TO) - 3_600_000).toISOString();
/** The server's file name form (argos-<resource>-<from>-<to>.csv, CsvExport). */
const SERVER_NAME = 'argos-sql-from-to.csv';
/** UTF-8 BOM + header + one row, as the server writes it (CsvWriter). */
const CSV_BYTES = new Uint8Array([
  0xef,
  0xbb,
  0xbf,
  ...new TextEncoder().encode('sql,count\r\n1,2\r\n'),
]);

function csv(fileName: string | null = SERVER_NAME): Response {
  const headers: Record<string, string> = { 'Content-Type': 'text/csv; charset=utf-8' };
  if (fileName !== null) {
    headers['Content-Disposition'] = `attachment; filename="${fileName}"`;
  }
  return new Response(CSV_BYTES, { status: 200, headers });
}

let created: Blob[];
let revoked: string[];

beforeEach(() => {
  created = [];
  revoked = [];
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: vi.fn((blob: Blob) => {
      created.push(blob);
      return `blob:http://localhost:3000/${String(created.length)}`;
    }),
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: vi.fn((url: string) => {
      revoked.push(url);
    }),
  });
});

afterEach(() => {
  uninstallApiStub();
  Reflect.deleteProperty(URL, 'createObjectURL');
  Reflect.deleteProperty(URL, 'revokeObjectURL');
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

describe('T-UI-004 CSV file name from Content-Disposition (RFC 6266)', () => {
  it.each([
    [`attachment; filename="${SERVER_NAME}"`, SERVER_NAME],
    ['attachment; filename=plain.csv', 'plain.csv'],
    // filename* (RFC 8187) wins over filename.
    ['attachment; filename="fallback.csv"; filename*=UTF-8\'\'%EB%B3%B4%EA%B3%A0.csv', '보고.csv'],
    // A broken percent-encoding falls back to filename.
    ['attachment; filename*=UTF-8\'\'%E0%A4%A; filename="fallback.csv"', 'fallback.csv'],
    // Paths are cut to the last segment (both separators).
    ['attachment; filename="../../etc/passwd"', 'passwd.csv'],
    ['attachment; filename="C:\\\\Windows\\\\evil.exe"', 'evil.exe.csv'],
    // Reserved characters, controls and bidi overrides are removed; .csv is enforced.
    ['attachment; filename="a<b>:c|d?*.csv"', 'abcd.csv'],
    ['attachment; filename="report\u202Evsc.exe"', 'reportvsc.exe.csv'],
    ['attachment; filename="tab\there.csv"', 'tabhere.csv'],
    ['attachment; filename="  .hidden.csv.  "', 'hidden.csv'],
    ['attachment; filename="REPORT.CSV"', 'REPORT.CSV'],
    // Nothing usable: the safe default.
    ['attachment; filename="..."', DEFAULT_CSV_FILE_NAME],
    ['attachment; filename=""', DEFAULT_CSV_FILE_NAME],
    ['attachment', DEFAULT_CSV_FILE_NAME],
  ])('%s -> %s', (header, expected) => {
    expect(csvFileName(header)).toBe(expected);
  });

  it('uses the default without a header and caps long names at 128 characters ending in .csv', () => {
    expect(csvFileName(null)).toBe(DEFAULT_CSV_FILE_NAME);
    expect(sanitizeCsvFileName(null)).toBe(DEFAULT_CSV_FILE_NAME);
    const long = sanitizeCsvFileName(`${'x'.repeat(300)}.csv`);
    expect(long).toHaveLength(MAX_CSV_FILE_NAME_LENGTH);
    expect(long.endsWith('.csv')).toBe(true);
  });
});

describe('T-UI-004 CSV request through the shared transport (DSN-15-06)', () => {
  it('GET: format=csv, Accept text/csv, browser time zone by default, no CSRF header', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'csrf-1';
    stub.on(SQL_STATS, () => csv());
    const file = await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO, sort: 'p95:desc' },
    });
    const [call] = stub.calls(SQL_STATS);
    expect(call?.query.get('format')).toBe('csv');
    expect(call?.query.get('from')).toBe(FROM);
    expect(call?.query.get('sort')).toBe('p95:desc');
    expect(call?.query.get('tz')).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(call?.headers.get('Accept')).toBe('text/csv');
    expect(call?.headers.get('X-Argos-CSRF')).toBeNull();
    expect(file.fileName).toBe(SERVER_NAME);
    expect(file.blob.type).toBe('text/csv;charset=utf-8');
    // The server's bytes, BOM included.
    expect(new Uint8Array(await file.blob.arrayBuffer())).toEqual(CSV_BYTES);
  });

  it('keeps an explicit time zone', async () => {
    const stub = installApiStub();
    stub.on(SQL_STATS, () => csv());
    await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO, tz: 'Asia/Seoul' },
    });
    expect(stub.calls(SQL_STATS)[0]?.query.get('tz')).toBe('Asia/Seoul');
  });

  it('T-SEC-43 POST: sends X-Argos-CSRF from the cookie and the JSON body', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'csrf-post';
    stub.on(SEARCH, () => csv('argos-transactions.csv'));
    const body = { from: FROM, to: TO, limit: 100 };
    const file = await requestCsv('post', '/api/v1/apps/{appId}/transactions/search', {
      path: { appId: 1 },
      body,
    });
    const [call] = stub.calls(SEARCH);
    expect(call?.headers.get('X-Argos-CSRF')).toBe('csrf-post');
    expect(call?.headers.get('Accept')).toBe('text/csv');
    expect(call?.query.get('format')).toBe('csv');
    expect(call?.body).toEqual(body);
    expect(file.fileName).toBe('argos-transactions.csv');
  });

  it('refreshes the session once on 401 and retries the export once', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'csrf-1';
    let attempts = 0;
    stub.on(SQL_STATS, () => {
      attempts += 1;
      return attempts === 1 ? errorBody(401, 'unauthenticated') : csv();
    });
    stub.on('POST /api/v1/auth/refresh', () => json({ data: {}, meta: {} }));
    const file = await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO },
    });
    expect(file.fileName).toBe(SERVER_NAME);
    expect(stub.calls(SQL_STATS)).toHaveLength(2);
    expect(stub.calls('POST /api/v1/auth/refresh')).toHaveLength(1);
  });

  it('429 (HEAVY, C-176): an ApiError with Retry-After, sent once, not retried', async () => {
    const stub = installApiStub();
    stub.on(SQL_STATS, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '30' } }));
    const error = await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO },
    }).catch((e: unknown) => e);
    expect(isApiError(error)).toBe(true);
    expect((error as ApiError).status).toBe(429);
    expect((error as ApiError).code).toBe('rate_limited');
    expect((error as ApiError).retryAfterSeconds).toBe(30);
    expect(isRetryable(error)).toBe(false);
    expect(stub.calls(SQL_STATS)).toHaveLength(1);
  });

  it('400 limit_exceeded keeps the server detail (more than 10,000 rows)', async () => {
    const stub = installApiStub();
    stub.on(SQL_STATS, () =>
      errorBody(400, 'limit_exceeded', { details: [{ field: 'format', reason: 'too_many_rows' }] }),
    );
    const error = (await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO },
    }).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('limit_exceeded');
    expect(error.hasDetail('too_many_rows', 'format')).toBe(true);
  });

  it('refuses an answer that is not text/csv', async () => {
    const stub = installApiStub();
    stub.on(SQL_STATS, () => json({ data: [], meta: {} }));
    const error = (await requestCsv('get', '/api/v1/apps/{appId}/sql/stats', {
      path: { appId: 1 },
      query: { from: FROM, to: TO },
    }).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('invalid_response');
  });

  it('accepts only CSV operations of the contract (type level)', () => {
    // Compile-time checks (pnpm typecheck); the calls are never made.
    const neverCalled = (): void => {
      // @ts-expect-error -- /api/v1/apps answers JSON only, it has no text/csv form.
      void requestCsv('get', '/api/v1/apps', { path: {} });
      // @ts-expect-error -- the sql/stats CSV form is a GET, not a POST.
      void requestCsv('post', '/api/v1/apps/{appId}/sql/stats', { path: { appId: 1 } });
      // @ts-expect-error -- from and to are required by sql_stats_list.
      void requestCsv('get', '/api/v1/apps/{appId}/sql/stats', { path: { appId: 1 } });
    };
    expect(typeof neverCalled).toBe('function');
  });
});

describe('T-UI-004 download (saveFile, the one approved object URL)', () => {
  it('clicks a temporary <a download> and revokes the object URL after the delay', () => {
    const clicked: { href: string; download: string; inDocument: boolean }[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push({ href: this.href, download: this.download, inDocument: this.isConnected });
    });
    const timers: { callback: () => void; ms: number }[] = [];
    const blob = new Blob(['a,b\r\n'], { type: 'text/csv' });
    saveFile(blob, '../x.csv', {
      setTimeout: (callback, ms) => {
        timers.push({ callback, ms });
      },
    });
    expect(created).toEqual([blob]);
    expect(clicked).toEqual([
      { href: 'blob:http://localhost:3000/1', download: 'x.csv', inDocument: true },
    ]);
    expect(document.querySelector('a')).toBeNull();
    expect(revoked).toEqual([]);
    expect(timers.map((t) => t.ms)).toEqual([REVOKE_DELAY_MS]);
    timers[0]?.callback();
    expect(revoked).toEqual(['blob:http://localhost:3000/1']);
  });

  it('revokes the object URL also when the click throws', () => {
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {
      throw new Error('blocked');
    });
    const timers: (() => void)[] = [];
    expect(() => {
      saveFile(new Blob(['x']), 'x.csv', { setTimeout: (callback) => timers.push(callback) });
    }).toThrow('blocked');
    timers.forEach((callback) => {
      callback();
    });
    expect(revoked).toEqual(['blob:http://localhost:3000/1']);
  });

  it('exportCsv saves the export under the server name; nothing is saved on an error', async () => {
    const clicked: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push(this.download);
    });
    const stub = installApiStub();
    stub.on(SQL_STATS, () => csv());
    const timers: (() => void)[] = [];
    const env = { setTimeout: (callback: () => void) => timers.push(callback) };
    const name = await exportCsv(
      'get',
      '/api/v1/apps/{appId}/sql/stats',
      { path: { appId: 1 }, query: { from: FROM, to: TO } },
      env,
    );
    expect(name).toBe(SERVER_NAME);
    expect(clicked).toEqual([name]);
    stub.on(SQL_STATS, () => errorBody(429, 'rate_limited', { headers: { 'Retry-After': '5' } }));
    await expect(
      exportCsv(
        'get',
        '/api/v1/apps/{appId}/sql/stats',
        { path: { appId: 1 }, query: { from: FROM, to: TO } },
        env,
      ),
    ).rejects.toMatchObject({ status: 429, retryAfterSeconds: 5 });
    expect(created).toHaveLength(1);
    expect(clicked).toHaveLength(1);
  });
});
