// SPDX-License-Identifier: Apache-2.0
//
// API wrapper (docs/14 §2.5 envelope, §2.6 error body, docs/15 §11): unwrapping, error
// normalisation, query keys and the QueryClient retry policy. Fetch is stubbed at the boundary.
// Requirements (test/README.md §5): { type: 'req', description: 'DSN-14-04' }, { type: 'req', description: 'DSN-14-05' }, { type: 'req', description: 'DSN-15-08' }
import { afterEach, describe, expect, it } from 'vitest';

import {
  envelope,
  errorBody,
  installApiStub,
  json,
  meBody,
  noContent,
  uninstallApiStub,
} from '../../test/apiStub';

import { countFiringAlerts, listApps } from './apps';
import { changePassword, createAdmin, fetchMe, login } from './auth';
import { expectNoContent } from './client';
import { ApiError, errorInfo, isRetryable, parseRetryAfter, toApiError } from './errors';
import { createQueryClient, GC_TIME_MS } from './queryClient';
import { normalizeFilters, queryKeys } from './queryKeys';
import { locateTransaction } from './transactions';

afterEach(() => {
  uninstallApiStub();
});

describe('API wrapper: envelope and errors', () => {
  it('unwraps {data, meta} with the typed QueryMeta fields', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/apps', () =>
      envelope([{ app_id: 3, name: 'order-api', env: 'prod' }], {
        source: 'mixed',
        truncated: true,
        shown: 500,
        total: 731,
        sampling: { applied: true, rule: 'error OR slow' },
        retention_exceeded: false,
        outages: [{ from: '2026-01-01T00:00:00Z', reason: 'server_down' }],
        next_cursor: 'abc',
      }),
    );
    const result = await listApps({ env: 'prod' });
    expect(result.data).toHaveLength(1);
    expect(result.meta).toMatchObject({
      source: 'mixed',
      truncated: true,
      shown: 500,
      total: 731,
      next_cursor: 'abc',
    });
    expect(result.meta.sampling).toEqual({ applied: true, rule: 'error OR slow' });
    expect(stub.calls('GET /api/v1/apps')[0]?.query.get('env')).toBe('prod');
  });

  it('normalises the docs/14 §2.6 error body with Retry-After', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/apps', () =>
      errorBody(429, 'rate_limited', { requestId: 'r-9', headers: { 'Retry-After': '7' } }),
    );
    const error = await listApps().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: 'http',
      status: 429,
      code: 'rate_limited',
      requestId: 'r-9',
      retryAfterSeconds: 7,
    });
    expect(errorInfo(error)).toEqual({
      status: 429,
      code: 'rate_limited',
      requestId: 'r-9',
      retryAfterSeconds: 7,
    });
  });

  it('keeps details (field, reason): cursor reset, clock skew and 504 query timeout', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/transactions/123', () =>
      errorBody(404, 'not_found', { details: [{ field: 'tx_id', reason: 'clock_skew_possible' }] }),
    );
    const error = (await locateTransaction('123').catch((e: unknown) => e)) as ApiError;
    expect(error.hasDetail('clock_skew_possible', 'tx_id')).toBe(true);
    expect(error.hasDetail('clock_skew_possible', 'other')).toBe(false);
    stub.on('GET /api/v1/apps', () => errorBody(504, 'query_timeout'));
    await expect(listApps()).rejects.toMatchObject({ status: 504, code: 'query_timeout' });
  });

  it('turns a non-envelope body into invalid_response and a 5xx without body into internal', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/apps', () => json({ hello: 'world' }));
    await expect(listApps()).rejects.toMatchObject({ kind: 'invalid_response', status: 200 });
    stub.on('GET /api/v1/auth/me', () => new Response('<html>proxy error</html>', { status: 502 }));
    await expect(fetchMe()).rejects.toMatchObject({
      kind: 'invalid_response',
      status: 502,
      code: 'internal',
    });
  });

  it('maps a rejected fetch to a network error (status 0)', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/apps', () => Promise.reject(new TypeError('Failed to fetch')));
    const error = await listApps().catch((e: unknown) => e);
    expect(error).toMatchObject({ kind: 'network', status: 0 });
    expect(errorInfo(error)).toEqual({ status: 0 });
    expect(isRetryable(error)).toBe(true);
  });

  it('accepts 204 for logout-like operations', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on('POST /api/v1/auth/logout', () => noContent());
    await expect(
      expectNoContent(stub.runtime.client.POST('/api/v1/auth/logout')),
    ).resolves.toBeUndefined();
  });

  it('parses Retry-After seconds and HTTP dates', () => {
    expect(parseRetryAfter('12')).toBe(12);
    expect(
      parseRetryAfter(new Date(Date.UTC(2030, 0, 1, 0, 0, 30)).toUTCString(), Date.UTC(2030, 0, 1)),
    ).toBe(30);
    expect(parseRetryAfter(null)).toBeNull();
    expect(parseRetryAfter('soon')).toBeNull();
  });

  it('counts the FIRING alerts of the overview alerts API (empty until WO-25)', async () => {
    const stub = installApiStub();
    stub.on('GET /api/v1/overview/alerts', (request) =>
      envelope(request.query.get('state') === 'FIRING' ? [{}, {}] : []),
    );
    await expect(countFiringAlerts('prod')).resolves.toBe(2);
    expect(stub.calls('GET /api/v1/overview/alerts')[0]?.query.get('env')).toBe('prod');
  });

  it('builds error ApiErrors from bodies without details or request id', () => {
    const error = toApiError(new Response(null, { status: 400 }), {
      error: { code: 'invalid_request', message: 'x' },
    });
    expect(error).toMatchObject({ code: 'invalid_request', details: [], requestId: null });
  });
});

describe('API wrapper: auth calls (docs/14 §4.1)', () => {
  it('login sends the credentials and reads the CSRF cookie again afterwards', async () => {
    const stub = installApiStub();
    stub.on('POST /api/v1/auth/login', () => envelope(meBody()));
    const result = await login('alice', 'secret-password');
    expect(result.must_change_password).toBe(false);
    expect(stub.calls('POST /api/v1/auth/login')[0]?.body).toEqual({
      username: 'alice',
      password: 'secret-password',
    });
    expect(stub.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'GET /api/v1/auth/csrf',
      'POST /api/v1/auth/login',
      'GET /api/v1/auth/csrf',
    ]);
  });

  it('password change sends {current, new} with the CSRF header', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on('PUT /api/v1/auth/password', () => noContent());
    await changePassword('old-password-1', 'new-password-12');
    const call = stub.calls('PUT /api/v1/auth/password')[0];
    expect(call?.body).toEqual({ current: 'old-password-1', new: 'new-password-12' });
    expect(call?.headers.get('X-Argos-CSRF')).toBe('c');
  });

  it('setup sends the token in X-Argos-Setup-Token, not in the body', async () => {
    const stub = installApiStub();
    stub.csrfCookie = 'c';
    stub.on('POST /api/v1/setup/admin', () => envelope({ user: meBody().user }, {}, 201));
    await createAdmin({
      setupToken: 'tok',
      username: 'admin',
      password: 'a-long-password',
      ipMode: 'masked',
    });
    const call = stub.calls('POST /api/v1/setup/admin')[0];
    expect(call?.headers.get('X-Argos-Setup-Token')).toBe('tok');
    expect(call?.body).toEqual({
      username: 'admin',
      password: 'a-long-password',
      ip_mode: 'masked',
    });
  });
});

describe('query keys and QueryClient defaults (docs/15 §4.2, §14)', () => {
  it('builds screen keys from absolute ISO ranges and sorted filters', () => {
    const a = queryKeys.screen('xlog', {
      appId: 3,
      range: { from: 0, to: 60_000 },
      kind: 'HTTP',
      filters: [
        { field: 'status', op: 'ne', value: '200' as never },
        { field: 'service', op: 'eq', value: '17' as never },
      ],
      extra: { sel: 'ms:1-2', sort: undefined },
    });
    const b = queryKeys.screen('xlog', {
      appId: 3,
      range: { from: 0, to: 60_000 },
      kind: 'HTTP',
      filters: [
        { field: 'service', op: 'eq', value: '17' as never },
        { field: 'status', op: 'ne', value: '200' as never },
      ],
      extra: { sel: 'ms:1-2' },
    });
    expect(a).toEqual(b);
    expect(a.slice(0, 4)).toEqual([
      'xlog',
      3,
      '1970-01-01T00:00:00.000Z',
      '1970-01-01T00:01:00.000Z',
    ]);
    expect(normalizeFilters([])).toEqual([]);
  });

  it('keeps 64-bit ids as strings in keys (C-175)', () => {
    const key = queryKeys.screen('tx', {
      range: { from: 1, to: 2 },
      extra: { txId: '18446744073709551615' },
    });
    expect(key[4]).toMatchObject({ txId: '18446744073709551615' });
  });

  it('caches for 5 minutes and retries only network errors and 502/503 once', () => {
    const client = createQueryClient();
    const defaults = client.getDefaultOptions().queries;
    expect(defaults?.gcTime).toBe(GC_TIME_MS);
    expect(GC_TIME_MS).toBe(300_000);
    const retry = defaults?.retry as (count: number, error: unknown) => boolean;
    const http = (status: number, code: string) =>
      new ApiError({ kind: 'http', status, code, message: code });
    expect(retry(0, http(400, 'range_invalid'))).toBe(false);
    expect(retry(0, http(404, 'not_found'))).toBe(false);
    expect(retry(0, http(429, 'rate_limited'))).toBe(false);
    expect(retry(0, http(504, 'query_timeout'))).toBe(false);
    expect(retry(0, http(503, 'storage_unavailable'))).toBe(true);
    expect(retry(1, http(503, 'storage_unavailable'))).toBe(false);
    expect(
      retry(0, new ApiError({ kind: 'network', status: 0, code: 'network', message: 'x' })),
    ).toBe(true);
    expect(retry(0, new Error('other'))).toBe(false);
  });
});
