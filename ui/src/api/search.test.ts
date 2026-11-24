// SPDX-License-Identifier: Apache-2.0
//
// Global search calls (docs/14 §4.11, docs/15 §9.12, WO-39): how an input is read (digits up to
// 20 = transaction id when it fits 64 bits, 32 hex = trace id, user:/req: with a range of at most
// 24 h, anything else text), the request (type, from/to, env), the destination of a match (only the
// known routes, ids checked, the ts hint kept as an ISO instant) and the refusal kinds.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U14' }, { type: 'req', description: 'SEC-64' }
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  uninstallApiStub,
  type ApiStub,
} from '../../test/apiStub';

import { ApiError } from './errors';
import {
  instancePath,
  isTraceId,
  isTxId,
  planSearch,
  rangeFits,
  search,
  searchDestination,
  searchErrorKind,
  searchQuery,
} from './search';
import { untrusted } from './untrusted';

export const REQ = { type: 'req', description: 'F-U14' };

const HOUR = 3_600_000;
const TRACE = '4bf92f3577b34da6a3ce929d0e0e4736';

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
});

describe('search: reading the input (docs/15 §9.12)', () => {
  it('reads digits up to 20 as a transaction id only when they fit 64 bits', () => {
    expect(planSearch('123').kind).toBe('tx');
    expect(planSearch('18446744073709551615').kind).toBe('tx'); // 20 digits, 2^64 − 1
    expect(planSearch('18446744073709551616').kind).toBe('text'); // 20 digits, above 2^64 − 1
    expect(planSearch('123456789012345678901').kind).toBe('text'); // 21 digits
    expect(planSearch('  42  ')).toEqual({ kind: 'tx', q: '42', type: 'tx', needsRange: false });
    expect(isTxId('0')).toBe(true);
    expect(isTxId('12a')).toBe(false);
  });

  it('reads 32 hex digits as a trace id (any case, sent in lower case)', () => {
    expect(planSearch(TRACE.toUpperCase())).toEqual({
      kind: 'trace',
      q: TRACE,
      type: 'trace',
      needsRange: false,
    });
    expect(isTraceId(TRACE.slice(1))).toBe(false);
    expect(planSearch(`${TRACE}0`).kind).toBe('text');
  });

  it('reads user: and req: as range searches without the prefix, anything else as text', () => {
    expect(planSearch('user: kim ')).toEqual({
      kind: 'user',
      q: 'kim',
      type: 'user',
      needsRange: true,
    });
    expect(planSearch('USER:kim').kind).toBe('user');
    expect(planSearch('req:abc-1')).toEqual({
      kind: 'request',
      q: 'abc-1',
      type: 'request',
      needsRange: true,
    });
    expect(planSearch('user:').kind).toBe('empty');
    expect(planSearch('order service')).toEqual({
      kind: 'text',
      q: 'order service',
      type: 'auto',
      needsRange: false,
    });
    expect(planSearch('   ').kind).toBe('empty');
  });

  it('an explicit URL type wins and still drops the user:/req: prefix of its own kind', () => {
    expect(planSearch('user:kim', 'user').q).toBe('kim');
    expect(planSearch('123', 'sql')).toEqual({
      kind: 'text',
      q: '123',
      type: 'sql',
      needsRange: false,
    });
    expect(planSearch('abc', 'request').needsRange).toBe(true);
  });

  it('user and request searches fit a range of at most 24 hours only', () => {
    const user = planSearch('user:kim');
    expect(rangeFits(user, { from: 0, to: 24 * HOUR })).toBe(true);
    expect(rangeFits(user, { from: 0, to: 24 * HOUR + 1 })).toBe(false);
    expect(rangeFits(planSearch('123'), { from: 0, to: 30 * 24 * HOUR })).toBe(true);
  });
});

describe('search: request (docs/14 §4.11)', () => {
  it('sends q, the type, the range and the environment', async () => {
    stub.on('GET /api/v1/search', () => envelope({ type: 'user', matches: [] }));
    const plan = planSearch('user:kim');
    await search({
      plan,
      from: Date.UTC(2026, 10, 24, 0),
      to: Date.UTC(2026, 10, 24, 1),
      env: 'prod',
    });
    const query = stub.calls('GET /api/v1/search')[0]?.query;
    expect(query?.get('q')).toBe('kim');
    expect(query?.get('type')).toBe('user');
    expect(query?.get('from')).toBe('2026-11-24T00:00:00.000Z');
    expect(query?.get('to')).toBe('2026-11-24T01:00:00.000Z');
    expect(query?.get('env')).toBe('prod');
    expect(searchQuery({ plan, from: 0, to: 1, env: null })).not.toHaveProperty('env');
  });

  it('maps 429, 422 user_id_hash_unavailable and 400 range errors to screen kinds', async () => {
    stub.on('GET /api/v1/search', () =>
      errorBody(429, 'rate_limited', { headers: { 'Retry-After': '12' } }),
    );
    const error: unknown = await search({
      plan: planSearch('abc'),
      from: 0,
      to: 1,
      env: null,
    }).catch((e: unknown) => e);
    expect(searchErrorKind(error)).toBe('rateLimited');
    const http = (status: number, code: string, field: string | null = null) =>
      new ApiError({
        kind: 'http',
        status,
        code,
        message: code,
        details: field === null ? [] : [{ field, reason: 'too_long' }],
      });
    expect(searchErrorKind(http(422, 'user_id_hash_unavailable'))).toBe('hashUnavailable');
    expect(searchErrorKind(http(400, 'range_invalid', 'to'))).toBe('rangeInvalid');
    expect(searchErrorKind(http(400, 'filter_invalid', 'q'))).toBe('queryInvalid');
    expect(searchErrorKind(http(500, 'internal'))).toBe('other');
    expect(searchErrorKind(new Error('x'))).toBe('other');
  });
});

describe('search: destinations of matches (docs/14 §6, SEC-64)', () => {
  const target = (route: string, params: Record<string, string>) => ({
    route,
    params: Object.fromEntries(Object.entries(params).map(([k, v]) => [k, untrusted(v)])),
  });

  it('opens transactions through the /tx alias and traces with their ts hint', () => {
    expect(
      searchDestination(target('/tx/:txId', { txId: '123', ts: '2026-11-24T01:02:03Z' })),
    ).toEqual({ kind: 'path', path: '/tx/123?ts=2026-11-24T01%3A02%3A03.000Z' });
    expect(searchDestination(target('/traces/:traceId', { traceId: TRACE.toUpperCase() }))).toEqual(
      {
        kind: 'path',
        path: `/traces/${TRACE}`,
      },
    );
    // A ts that is not an instant is dropped, never written as is.
    expect(searchDestination(target('/tx/:txId', { txId: '1', ts: 'data:text/html,x' }))).toEqual({
      kind: 'path',
      path: '/tx/1',
    });
  });

  it('opens services and SQL on the statistics screens with the carried range', () => {
    expect(
      searchDestination(
        target('/apps/:appId/services/:serviceId', { appId: '3', serviceId: '99' }),
        'from=now-1h',
      ),
    ).toEqual({ kind: 'path', path: '/apps/3/stats/services/99?from=now-1h' });
    expect(
      searchDestination(target('/apps/:appId/sql/:sqlId', { appId: '3', sqlId: '7' })),
    ).toEqual({
      kind: 'path',
      path: '/apps/3/stats/sql/7',
    });
  });

  it('asks the caller to look up the application of an instance', () => {
    expect(searchDestination(target('/instances/:instanceId', { instanceId: '12' }))).toEqual({
      kind: 'instance',
      instanceId: 12,
    });
    expect(instancePath(3, 12, 'env=prod')).toBe('/apps/3/instances/12?env=prod');
  });

  it('refuses unknown routes and ids that do not match their pattern', () => {
    const none = { kind: 'none' };
    expect(searchDestination(target('https://evil.example/:x', { x: '1' }))).toEqual(none);
    expect(searchDestination(target('/tx/:txId', { txId: '../admin' }))).toEqual(none);
    expect(searchDestination(target('/traces/:traceId', { traceId: 'abc' }))).toEqual(none);
    expect(
      searchDestination(target('/apps/:appId/sql/:sqlId', { appId: '0', sqlId: '7' })),
    ).toEqual(none);
    expect(
      searchDestination(target('/apps/:appId/sql/:sqlId', { appId: '3', sqlId: '7/../x' })),
    ).toEqual(none);
    expect(searchDestination(target('/instances/:instanceId', { instanceId: '-1' }))).toEqual(none);
    // Inherited keys of the params object are not read.
    expect(searchDestination(target('/tx/:txId', {}))).toEqual(none);
  });
});
