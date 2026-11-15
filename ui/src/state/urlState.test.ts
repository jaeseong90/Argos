// SPDX-License-Identifier: Apache-2.0
//
// URL state (docs/15 §4.2, docs/14 §6 DSN-14-17, ADR-0010 C-166, SEC-64).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'SEC-64' }
import { describe, expect, it } from 'vitest';

import {
  AlertsQ,
  buildUrl,
  carriedQuery,
  CommonQ,
  DashboardQ,
  defaultState,
  FILTER_MAX,
  FILTER_VALUE_MAX,
  fitsFilterValue,
  fitsWriteLimit,
  isValidState,
  parseInstant,
  parseUrl,
  safeNextPath,
  SearchQ,
  TxDetailQ,
  URL_PARSE_LIMIT,
  URL_WRITE_LIMIT,
  utf8ByteLength,
  writeQuery,
  XLogQ,
  type UrlSchema,
} from './urlState';

const ISO_FROM = '2026-10-23T01:00:00.000Z';
const ISO_TO = '2026-10-23T02:00:00.000Z';
const FROM_MS = Date.parse(ISO_FROM);
const TO_MS = Date.parse(ISO_TO);

function roundTrip(schema: UrlSchema, search: string): string {
  return parseUrl(schema, search).canonical;
}

describe('T-UI-003 URL state: canonical writer (DSN-14-17, C-166)', () => {
  it('T-UI-003 writes nothing for the defaults (15m, HTTP, no filters)', () => {
    expect(writeQuery(CommonQ, defaultState(CommonQ))).toBe('');
    const parsed = parseUrl(CommonQ, '');
    expect(parsed.state.time).toEqual({ kind: 'relative', range: '15m' });
    expect(parsed.state.kind).toBe('HTTP');
    expect(parsed.state.filters).toEqual([]);
    expect(parsed.issues).toEqual([]);
  });

  it('T-UI-003 round-trips every key of the canonical form', () => {
    const search =
      `from=${ISO_FROM}&to=${ISO_TO}&env=prod&kind=BATCH` +
      `&f.service=GET%20/api/orders/%7Bid%7D&f.status=!200&f.elapsed_ms.gte=1000` +
      `&kiosk=1&sort=p95_ms:desc&sel=ms:100-2000,ts:${String(FROM_MS)}-${String(TO_MS)}` +
      `&color=instance&y=log`;
    const parsed = parseUrl(XLogQ, search);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.time).toEqual({ kind: 'absolute', from: FROM_MS, to: TO_MS });
    expect(parsed.state.env).toBe('prod');
    expect(parsed.state.kind).toBe('BATCH');
    expect(parsed.state.filters).toEqual([
      { field: 'service', op: 'eq', value: 'GET /api/orders/{id}' },
      { field: 'status', op: 'ne', value: '200' },
      { field: 'elapsed_ms', op: 'gte', value: '1000' },
    ]);
    expect(parsed.state.sel).toEqual({ msMin: 100, msMax: 2000, tsFrom: FROM_MS, tsTo: TO_MS });
    expect(parsed.state.color).toBe('instance');
    expect(parsed.state.y).toBe('log');
    expect(parsed.state.sort).toBe('p95_ms:desc');
    // The canonical string parses to the same state and writes itself again (idempotent).
    expect(roundTrip(XLogQ, parsed.canonical)).toBe(parsed.canonical);
    expect(parseUrl(XLogQ, parsed.canonical).state).toEqual(parsed.state);
  });

  it('T-UI-003 writes relative ranges as now-<n><unit>, today and yesterday', () => {
    const write = (range: string): string =>
      writeQuery(CommonQ, { ...defaultState(CommonQ), time: { kind: 'relative', range } });
    expect(write('1h')).toBe('from=now-1h');
    expect(write('7d')).toBe('from=now-7d');
    expect(write('today')).toBe('from=now/d');
    expect(write('yesterday')).toBe('from=now-1d/d&to=now/d');
    expect(write('90m')).toBe('from=now-90m');
    for (const range of ['5m', '1h', '6h', '24h', '7d', '30d', 'today', 'yesterday', '90m']) {
      expect(parseUrl(CommonQ, write(range)).state.time).toEqual({ kind: 'relative', range });
    }
  });

  it('T-UI-003 escapes a filter value that starts with an operator character', () => {
    const state = {
      ...defaultState(CommonQ),
      filters: [{ field: 'user', op: 'eq' as const, value: '!vip' as never }],
    };
    const query = writeQuery(CommonQ, state);
    expect(query).toBe('f.user=%5C!vip');
    expect(parseUrl(CommonQ, query).state.filters).toEqual([
      { field: 'user', op: 'eq', value: '!vip' },
    ]);
  });

  it('T-UI-003 writes range and contains filters as f.<field>.<op>', () => {
    const state = {
      ...defaultState(CommonQ),
      filters: [
        { field: 'elapsed_ms', op: 'gt' as const, value: '1000' as never },
        { field: 'elapsed_ms', op: 'lte' as const, value: '5000' as never },
        { field: 'sql', op: 'contains' as const, value: 'orders' as never },
      ],
    };
    const query = writeQuery(CommonQ, state);
    expect(query).toBe('f.elapsed_ms.gt=1000&f.elapsed_ms.lte=5000&f.sql.contains=orders');
    expect(parseUrl(CommonQ, query).state.filters).toEqual(state.filters);
  });

  it('T-UI-003 keeps a script payload in a filter value as plain data (rendered as text)', () => {
    const payload = '<img src=x onerror=window.__xss=1>';
    const parsed = parseUrl(CommonQ, `f.service=${encodeURIComponent(payload)}`);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters[0]?.value).toBe(payload);
  });
});

describe('T-UI-003 URL state: both input forms (docs/14 §6 and docs/15 §4.2)', () => {
  it('T-UI-003 reads range=<preset> and epoch milliseconds (docs/15 §4.2)', () => {
    expect(parseUrl(CommonQ, 'range=1h').state.time).toEqual({ kind: 'relative', range: '1h' });
    expect(parseUrl(CommonQ, 'range=1h').canonical).toBe('from=now-1h');
    const epoch = parseUrl(CommonQ, `from=${String(FROM_MS)}&to=${String(TO_MS)}`);
    expect(epoch.state.time).toEqual({ kind: 'absolute', from: FROM_MS, to: TO_MS });
    expect(epoch.canonical).toBe(`from=${ISO_FROM}&to=${ISO_TO}`);
  });

  it('T-UI-003 reads now-<n><unit> with to=now, weeks as days and lengths of presets as presets', () => {
    expect(parseUrl(CommonQ, 'from=now-1h&to=now').state.time).toEqual({
      kind: 'relative',
      range: '1h',
    });
    expect(parseUrl(CommonQ, 'from=now-60m').state.time).toEqual({ kind: 'relative', range: '1h' });
    expect(parseUrl(CommonQ, 'from=now-1w').state.time).toEqual({ kind: 'relative', range: '7d' });
    expect(parseUrl(CommonQ, 'from=now-2h').state.time).toEqual({ kind: 'relative', range: '2h' });
  });

  it('T-UI-003 reads f=<field>:<op>:<value> and normalises it to f.<field>', () => {
    const parsed = parseUrl(
      CommonQ,
      'f=service:eq:GET%20%2Fapi%2Forders%2F%7Bid%7D&f=status:ne:200',
    );
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters).toEqual([
      { field: 'service', op: 'eq', value: 'GET /api/orders/{id}' },
      { field: 'status', op: 'ne', value: '200' },
    ]);
    expect(parsed.canonical).toBe('f.service=GET%20/api/orders/%7Bid%7D&f.status=!200');
  });

  it('T-UI-003 reads f=<field>:in:<a>,<b> (docs/15 §4.2) as one eq filter per value (any of)', () => {
    const parsed = parseUrl(CommonQ, 'f=status:in:500,503');
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters).toEqual([
      { field: 'status', op: 'eq', value: '500' },
      { field: 'status', op: 'eq', value: '503' },
    ]);
    expect(parsed.canonical).toBe('f.status=500&f.status=503');
    expect(parseUrl(CommonQ, parsed.canonical).state.filters).toEqual(parsed.state.filters);
  });

  it.each([
    ['f=status:in:', 1],
    ['f=status:in:500,,503', 1],
    ['f=status:in:,', 2],
  ])('T-UI-003 refuses the empty values of an in list: %s', (query, empty) => {
    const parsed = parseUrl(CommonQ, `${query}&kind=BATCH`);
    expect(parsed.issues).toEqual(
      Array.from({ length: empty }, () => ({ key: 'f', reason: 'invalid' })),
    );
    expect(parsed.state.filters.map((f) => f.value)).toEqual(
      query.endsWith('503') ? ['500', '503'] : [],
    );
    expect(parsed.state.kind).toBe('BATCH');
  });

  it('T-UI-003 counts every value of an in list towards the 20-filter limit', () => {
    const values = Array.from({ length: FILTER_MAX + 1 }, (_, i) => String(500 + i));
    const parsed = parseUrl(CommonQ, `f=status:in:${values.join(',')}`);
    expect(parsed.state.filters).toHaveLength(FILTER_MAX);
    expect(parsed.state.filters.map((f) => f.value)).toEqual(values.slice(0, FILTER_MAX));
    expect(parsed.issues).toEqual([{ key: 'f', reason: 'too_many' }]);
  });

  it('T-UI-003 checks each value of an in list against the 1,024-byte limit on its own', () => {
    const long = '가'.repeat(342);
    const parsed = parseUrl(CommonQ, `f=user:in:${encodeURIComponent(`a,${long},b`)}`);
    expect(parsed.state.filters.map((f) => f.value)).toEqual(['a', 'b']);
    expect(parsed.issues).toEqual([{ key: 'f', reason: 'too_long' }]);
  });

  it('T-UI-003 reads sel=<t1>,<t2>,<y1>,<y2> (docs/15) and writes ms:..,ts:.. (docs/14)', () => {
    const parsed = parseUrl(XLogQ, `sel=${String(FROM_MS)},${String(TO_MS)},10,500`);
    expect(parsed.state.sel).toEqual({ tsFrom: FROM_MS, tsTo: TO_MS, msMin: 10, msMax: 500 });
    expect(parsed.canonical).toBe(`sel=ms:10-500,ts:${String(FROM_MS)}-${String(TO_MS)}`);
  });

  it('T-UI-003 reads kind in any case and writes the docs/14 upper-case value', () => {
    expect(parseUrl(CommonQ, 'kind=batch').state.kind).toBe('BATCH');
    expect(parseUrl(CommonQ, 'kind=Message').canonical).toBe('kind=MESSAGE');
    expect(parseUrl(CommonQ, 'kind=http').canonical).toBe('');
    expect(parseUrl(CommonQ, 'kind=all').state.kind).toBe('ALL');
  });

  it('T-UI-003 reads sort=-field (docs/15) as field:desc (API form)', () => {
    expect(parseUrl(CommonQ, 'sort=-p95').state.sort).toBe('p95:desc');
    expect(parseUrl(CommonQ, 'sort=count').canonical).toBe('sort=count:asc');
  });

  it('T-UI-003 turns live off for absolute ranges, yesterday and alert deep links (05 §2.0-7 #2)', () => {
    expect(parseUrl(CommonQ, `from=${ISO_FROM}&to=${ISO_TO}&live=1`).state.live).toBe(false);
    expect(parseUrl(CommonQ, 'from=now-1d/d&to=now/d&live=1').state.live).toBe(false);
    expect(parseUrl(CommonQ, 'from=now-1h&live=1&alert=ev1').state.live).toBe(false);
    expect(parseUrl(CommonQ, 'from=now-1h&live=1').state.live).toBe(true);
  });
});

describe('T-UI-003 URL state: invalid items fall back one by one (SEC-64)', () => {
  it('T-UI-003 ignores a query longer than 8 KB as a whole', () => {
    const long = `env=prod&f.q=${'a'.repeat(URL_PARSE_LIMIT)}`;
    const parsed = parseUrl(CommonQ, long);
    expect(parsed.issues).toEqual([{ key: '*', reason: 'too_long' }]);
    expect(parsed.state).toEqual(defaultState(CommonQ));
  });

  it('T-UI-003 accepts a 1,024-character filter value and refuses 1,025', () => {
    const ok = parseUrl(CommonQ, `f.user=${'x'.repeat(FILTER_VALUE_MAX)}`);
    expect(ok.issues).toEqual([]);
    expect(ok.state.filters[0]?.value).toHaveLength(1024);
    const tooLong = parseUrl(CommonQ, `env=prod&f.user=${'x'.repeat(FILTER_VALUE_MAX + 1)}`);
    expect(tooLong.issues).toEqual([{ key: 'f.user', reason: 'too_long' }]);
    expect(tooLong.state.filters).toEqual([]);
    expect(tooLong.state.env).toBe('prod');
  });

  it('T-UI-003 counts the limit after decoding (percent-encoded multi-byte text)', () => {
    // 341 characters, 1,023 UTF-8 bytes, 3,069 characters after percent-encoding: the limit
    // applies to the decoded value.
    const value = '가'.repeat(341);
    const parsed = parseUrl(CommonQ, `f.user=${encodeURIComponent(value)}`);
    expect(parsed.issues).toEqual([]);
    expect(parsed.state.filters[0]?.value).toBe(value);
  });

  it.each([
    ['Hangul, 341 characters = 1,023 bytes', '가'.repeat(341), true],
    ['Hangul, 342 characters = 1,026 bytes', '가'.repeat(342), false],
    ['4-byte emoji, 256 = 1,024 bytes', '😀'.repeat(256), true],
    ['4-byte emoji, 257 = 1,028 bytes', '😀'.repeat(257), false],
    ['ASCII, 1,024 bytes', 'x'.repeat(1024), true],
    ['ASCII, 1,025 bytes', 'x'.repeat(1025), false],
  ])(
    'T-UI-003 limits filter values to 1,024 UTF-8 bytes like the server (SEC-64): %s',
    (_name, value, fits) => {
      expect(utf8ByteLength(value)).toBe(new TextEncoder().encode(value).length);
      expect(fitsFilterValue(value)).toBe(fits);
      const parsed = parseUrl(CommonQ, `f.user=${encodeURIComponent(value)}&env=prod`);
      expect(parsed.state.env).toBe('prod');
      if (fits) {
        expect(parsed.issues).toEqual([]);
        expect(parsed.state.filters).toEqual([{ field: 'user', op: 'eq', value }]);
      } else {
        expect(parsed.issues).toEqual([{ key: 'f.user', reason: 'too_long' }]);
        expect(parsed.state.filters).toEqual([]);
      }
      // The writer's zod check uses the same byte limit.
      expect(
        isValidState(CommonQ, {
          ...defaultState(CommonQ),
          filters: [{ field: 'user', op: 'eq', value: value as never }],
        }),
      ).toBe(fits);
      // So does the search text (docs/15 §9.12: 1 KB).
      expect(parseUrl(SearchQ, `q=${encodeURIComponent(value)}`).state.q).toBe(
        fits ? value : undefined,
      );
    },
  );

  it('T-UI-003 keeps the first 20 filters and refuses the rest', () => {
    const query = Array.from({ length: FILTER_MAX + 2 }, (_, i) => `f.f${String(i)}=v`).join('&');
    const parsed = parseUrl(CommonQ, query);
    expect(parsed.state.filters).toHaveLength(FILTER_MAX);
    expect(parsed.issues).toEqual([
      { key: 'f.f20', reason: 'too_many' },
      { key: 'f.f21', reason: 'too_many' },
    ]);
  });

  it.each([
    ['f.Service=x', 'f.Service'],
    ['f.1abc=x', 'f.1abc'],
    [`f.${'a'.repeat(33)}=x`, `f.${'a'.repeat(33)}`],
    ['f.a-b=x', 'f.a-b'],
    ['f.%3Cscript%3E=x', 'f.<script>'],
    ['f.elapsed_ms.like=x', 'f.elapsed_ms.like'],
  ])('T-UI-003 refuses a bad filter field: %s', (query, key) => {
    const parsed = parseUrl(CommonQ, `${query}&kind=BATCH`);
    expect(parsed.issues).toEqual([{ key, reason: 'bad_field' }]);
    expect(parsed.state.filters).toEqual([]);
    expect(parsed.state.kind).toBe('BATCH');
  });

  it('T-UI-003 refuses from >= to and keeps the other items', () => {
    const parsed = parseUrl(CommonQ, `from=${ISO_TO}&to=${ISO_FROM}&env=prod`);
    expect(parsed.issues).toEqual([{ key: 'from', reason: 'range_order' }]);
    expect(parsed.state.time).toEqual({ kind: 'relative', range: '15m' });
    expect(parsed.state.env).toBe('prod');
    expect(parseUrl(CommonQ, `from=${ISO_FROM}&to=${ISO_FROM}`).issues).toEqual([
      { key: 'from', reason: 'range_order' },
    ]);
  });

  it.each([
    ['from=yesterday', 'from'],
    ['from=2026-02-31T00:00:00Z&to=2026-03-01T00:00:00Z', 'from'],
    ['from=2026-10-23T01:00:00+09:00&to=2026-10-23T02:00:00Z', 'from'],
    [`from=${ISO_FROM}`, 'to'],
    ['to=now', 'to'],
    ['from=now-0m', 'from'],
    ['from=now-400d', 'from'],
    ['range=2y', 'range'],
    ['from=now-1h&to=2026-10-23T02:00:00Z', 'to'],
  ])('T-UI-003 refuses an invalid time range: %s', (query, key) => {
    const parsed = parseUrl(CommonQ, query);
    expect(parsed.issues).toEqual([{ key, reason: 'invalid' }]);
    expect(parsed.state.time).toEqual({ kind: 'relative', range: '15m' });
  });

  it('T-UI-003 refuses an unknown kind', () => {
    const parsed = parseUrl(CommonQ, 'kind=SQL&env=prod');
    expect(parsed.issues).toEqual([{ key: 'kind', reason: 'unknown_kind' }]);
    expect(parsed.state.kind).toBe('HTTP');
    expect(parsed.state.env).toBe('prod');
  });

  it.each([
    ['env', 'env=%3Cscript%3Ealert(1)%3C%2Fscript%3E'],
    ['sort', 'sort=%3Cscript%3E'],
    ['kind', 'kind=%3Cscript%3E'],
    ['cols', 'cols=%22%3E%3Csvg%2Fonload%3Dalert(1)%3E'],
    ['alert', 'alert=javascript:alert(1)'],
    ['live', 'live=%3Cb%3E'],
  ])('T-UI-003 refuses script text in %s', (key, query) => {
    const parsed = parseUrl(CommonQ, query);
    expect(parsed.issues).toHaveLength(1);
    expect(parsed.issues[0]?.key).toBe(key);
    expect(parsed.canonical).toBe('');
  });

  it('T-UI-003 refuses a repeated single-valued key', () => {
    expect(parseUrl(CommonQ, 'env=a&env=b').issues).toEqual([{ key: 'env', reason: 'invalid' }]);
  });

  it('T-UI-003 refuses malformed selections and screen enums', () => {
    expect(parseUrl(XLogQ, 'sel=ms:500-10,ts:1-2').issues).toEqual([
      { key: 'sel', reason: 'invalid' },
    ]);
    expect(parseUrl(XLogQ, 'color=red').issues).toEqual([{ key: 'color', reason: 'invalid' }]);
    expect(parseUrl(DashboardQ, 'baseline=2d').issues).toEqual([
      { key: 'baseline', reason: 'invalid' },
    ]);
    expect(parseUrl(AlertsQ, 'app=0').issues).toEqual([{ key: 'app', reason: 'invalid' }]);
    expect(parseUrl(TxDetailQ, 'compare=12a').issues).toEqual([
      { key: 'compare', reason: 'invalid' },
    ]);
    expect(parseUrl(SearchQ, `q=${'q'.repeat(1025)}`).issues).toEqual([
      { key: 'q', reason: 'invalid' },
    ]);
  });

  it('T-UI-003 drops unknown keys without an issue', () => {
    const parsed = parseUrl(CommonQ, 'utm_source=mail&env=prod');
    expect(parsed.issues).toEqual([]);
    expect(parsed.canonical).toBe('env=prod');
  });
});

describe('T-UI-003 URL state: writer limits and helpers', () => {
  it('T-UI-003 refuses to write a URL longer than 2 KB (docs/14 §6)', () => {
    const value = 'v'.repeat(900);
    const state = {
      ...defaultState(CommonQ),
      filters: ['a', 'b', 'c'].map((field) => ({
        field,
        op: 'eq' as const,
        value: value as never,
      })),
    };
    const fits = buildUrl('/overview', CommonQ, { ...state, filters: state.filters.slice(0, 1) });
    expect(fits.length).toBeLessThanOrEqual(URL_WRITE_LIMIT);
    expect(fitsWriteLimit(fits)).toBe(true);
    const tooLong = buildUrl('/overview', CommonQ, state);
    expect(tooLong.length).toBeGreaterThan(URL_WRITE_LIMIT);
    expect(fitsWriteLimit(tooLong)).toBe(false);
  });

  it('T-UI-003 validates state with the zod schema before writing', () => {
    expect(isValidState(CommonQ, defaultState(CommonQ))).toBe(true);
    expect(
      isValidState(CommonQ, {
        ...defaultState(CommonQ),
        time: { kind: 'absolute', from: 5, to: 4 },
      }),
    ).toBe(false);
    expect(
      isValidState(CommonQ, {
        ...defaultState(CommonQ),
        filters: [{ field: 'Bad', op: 'eq', value: 'x' as never }],
      }),
    ).toBe(false);
  });

  it('T-UI-003 carries time, live, kind, env and kiosk to other screens and drops the rest', () => {
    const state = parseUrl(
      XLogQ,
      `from=now-1h&live=1&kind=BATCH&env=prod&kiosk=1&f.service=x&sel=ms:1-2,ts:1-2&color=service`,
    ).state;
    expect(carriedQuery(state)).toBe('from=now-1h&env=prod&live=1&kind=BATCH&kiosk=1');
  });

  it('T-UI-003 parses instants: ISO UTC and epoch milliseconds only', () => {
    expect(parseInstant(ISO_FROM)).toBe(FROM_MS);
    expect(parseInstant('2026-10-23T01:00:00Z')).toBe(FROM_MS);
    expect(parseInstant(String(FROM_MS))).toBe(FROM_MS);
    expect(parseInstant('2026-10-23 01:00:00')).toBeNull();
    expect(parseInstant('0')).toBeNull();
  });

  it.each([
    ['/apps/3/xlog?from=now-1h', '/apps/3/xlog?from=now-1h'],
    ['//evil.example/x', null],
    ['/\\evil.example', null],
    ['https://evil.example/', null],
    ['javascript' + ':alert(1)', null],
    ['/login?next=/x', null],
    ['/setup', null],
    ['/ok\u0000', null],
    [`/${'a'.repeat(URL_WRITE_LIMIT)}`, null],
    ['', null],
  ])('T-UI-003 accepts only same-origin paths as next (docs/15 §4.3): %s', (next, expected) => {
    expect(safeNextPath(next)).toBe(expected);
  });
});
