// SPDX-License-Identifier: Apache-2.0
//
// Test double of the HTTP boundary for unit and component tests (docs/15 §15): the real transport
// and generated client run unchanged; only the network fetch is replaced by handlers per
// "METHOD /path". A cookie jar models argos_csrf (the session cookies are HttpOnly and invisible to
// scripts anyway). E2E tests use the real server instead (ui/e2e).
import { createApiRuntime, setApiRuntime, type ApiRuntime } from '../src/api/client';
import type { LockManagerLike } from '../src/api/transport';

export interface StubRequest {
  readonly method: string;
  readonly path: string;
  readonly query: URLSearchParams;
  readonly headers: Headers;
  readonly body: unknown;
}

export type StubHandler = (request: StubRequest) => Response | Promise<Response>;

export const ORIGIN = 'http://localhost:3000';

/** JSON response with the docs/14 §2.5 envelope. */
export function envelope(
  data: unknown,
  meta: Record<string, unknown> = {},
  status = 200,
): Response {
  return json(
    { data, meta: { request_id: 'req-test', generated_at: '2026-01-01T00:00:00.000Z', ...meta } },
    status,
  );
}

/** docs/14 §2.6 error body. */
export function errorBody(
  status: number,
  code: string,
  options: {
    details?: { field?: string; reason: string }[];
    requestId?: string;
    headers?: Record<string, string>;
  } = {},
): Response {
  return json(
    {
      error: {
        code,
        message: code,
        details: options.details ?? [],
        request_id: options.requestId ?? 'req-err',
      },
    },
    status,
    options.headers,
  );
}

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

export function noContent(): Response {
  return new Response(null, { status: 204 });
}

export interface ApiStub {
  readonly runtime: ApiRuntime;
  /** Every request seen, in order. */
  readonly requests: StubRequest[];
  /** The argos_csrf cookie value ('' = none). */
  csrfCookie: string;
  on(route: string, handler: StubHandler): void;
  /** Requests of one route. */
  calls(route: string): StubRequest[];
}

export interface ApiStubOptions {
  readonly locks?: LockManagerLike | null;
  /** CSRF token that GET /api/v1/auth/csrf issues. */
  readonly csrfToken?: string;
}

/**
 * Installs a stub runtime as the page-wide API runtime. Unhandled routes answer 404 not_found.
 * GET /api/v1/auth/csrf is handled by default (sets the cookie).
 */
export function installApiStub(options: ApiStubOptions = {}): ApiStub {
  const handlers = new Map<string, StubHandler>();
  const requests: StubRequest[] = [];
  const stub: ApiStub = {
    runtime: undefined as unknown as ApiRuntime,
    requests,
    csrfCookie: '',
    on(route, handler) {
      handlers.set(route, handler);
    },
    calls(route) {
      return requests.filter((r) => `${r.method} ${r.path}` === route);
    },
  };
  const issued = options.csrfToken ?? 'csrf-token-1';
  handlers.set('GET /api/v1/auth/csrf', () => {
    stub.csrfCookie = issued;
    return envelope({ csrf_token: issued });
  });

  const fetch = async (request: Request): Promise<Response> => {
    const url = new URL(request.url);
    let body: unknown = undefined;
    const text = await request.text();
    if (text !== '') {
      try {
        body = JSON.parse(text);
      } catch {
        body = text;
      }
    }
    const seen: StubRequest = {
      method: request.method.toUpperCase(),
      path: url.pathname,
      query: url.searchParams,
      headers: request.headers,
      body,
    };
    requests.push(seen);
    const handler = handlers.get(`${seen.method} ${seen.path}`);
    return handler === undefined ? errorBody(404, 'not_found') : handler(seen);
  };

  const runtime = createApiRuntime({
    fetch,
    origin: ORIGIN,
    locks: options.locks ?? null,
    readCookies: () => (stub.csrfCookie === '' ? '' : `argos_csrf=${stub.csrfCookie}`),
  });
  Object.assign(stub, { runtime });
  setApiRuntime(runtime);
  return stub;
}

/** Removes the stub runtime (the next api() call creates a fresh default one). */
export function uninstallApiStub(): void {
  setApiRuntime(null);
}

/** A /auth/me body (docs/14 §4.1). */
export function meBody(
  overrides: Partial<{
    username: string;
    roles: string[];
    permissions: string[];
    must_change_password: boolean;
    session_expires_at: string;
    kiosk_allowed: boolean;
  }> = {},
): Record<string, unknown> {
  const roles = overrides.roles ?? ['viewer'];
  const permissions = overrides.permissions ?? [];
  return {
    user: { id: 'u-1', username: overrides.username ?? 'alice', roles, permissions },
    roles,
    permissions,
    must_change_password: overrides.must_change_password ?? false,
    session_expires_at: overrides.session_expires_at ?? '2099-01-01T00:00:00.000Z',
    kiosk_allowed: overrides.kiosk_allowed ?? false,
  };
}
