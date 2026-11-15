// SPDX-License-Identifier: Apache-2.0
//
// A small Node client of the Argos REST API for the stack setup and the E2E fixtures (seeding users,
// resolving the seed manifest, per-test login). It speaks the real API exactly as the browser does
// (docs/14 §2.2): session cookies (kept in a jar that honours Path and Max-Age), the Origin of the
// public URL on every state-changing request and X-Argos-CSRF = the argos_csrf cookie (double
// submit). It is test code only: the UI itself talks to the server through src/api.
//
// Response bodies of collected data are treated as data; passwords and cookie values are never
// written to logs or errors.

export const API_PREFIX = '/api/v1';

/** One cookie of the jar, with the attributes Playwright's storageState needs. */
export interface JarCookie {
  readonly name: string;
  readonly value: string;
  readonly path: string;
  /** Epoch seconds, or -1 for a session cookie. */
  readonly expires: number;
  readonly httpOnly: boolean;
  readonly secure: boolean;
  readonly sameSite: 'Strict' | 'Lax' | 'None';
}

/** Parses one Set-Cookie header value. `nowMs` is the receive time (Max-Age). */
export function parseSetCookie(header: string, nowMs: number): JarCookie | null {
  const parts = header.split(';').map((p) => p.trim());
  const first = parts.shift();
  if (first === undefined) {
    return null;
  }
  const eq = first.indexOf('=');
  if (eq <= 0) {
    return null;
  }
  let path = '/';
  let expires = -1;
  let httpOnly = false;
  let secure = false;
  let sameSite: JarCookie['sameSite'] = 'Lax';
  for (const attribute of parts) {
    const [rawKey = '', ...rest] = attribute.split('=');
    const key = rawKey.trim().toLowerCase();
    const value = rest.join('=').trim();
    if (key === 'path' && value.startsWith('/')) {
      path = value;
    } else if (key === 'max-age' && /^-?\d+$/.test(value)) {
      expires = Math.floor(nowMs / 1000) + Number.parseInt(value, 10);
    } else if (key === 'expires' && expires === -1) {
      const time = Date.parse(value);
      if (!Number.isNaN(time)) {
        expires = Math.floor(time / 1000);
      }
    } else if (key === 'httponly') {
      httpOnly = true;
    } else if (key === 'secure') {
      secure = true;
    } else if (key === 'samesite') {
      const lower = value.toLowerCase();
      sameSite = lower === 'strict' ? 'Strict' : lower === 'none' ? 'None' : 'Lax';
    }
  }
  return {
    name: first.slice(0, eq).trim(),
    value: first.slice(eq + 1).trim(),
    path,
    expires,
    httpOnly,
    secure,
    sameSite,
  };
}

/** Whether a cookie with path `cookiePath` is sent with a request to `requestPath` (RFC 6265 5.1.4). */
export function pathMatches(cookiePath: string, requestPath: string): boolean {
  if (requestPath === cookiePath) {
    return true;
  }
  if (!requestPath.startsWith(cookiePath)) {
    return false;
  }
  return cookiePath.endsWith('/') || requestPath.charAt(cookiePath.length) === '/';
}

export interface ApiResponse<T = unknown> {
  readonly status: number;
  readonly headers: Headers;
  /** The parsed JSON body, or null for an empty or non-JSON body. */
  readonly body: T | null;
  readonly text: string;
}

export interface RequestOptions {
  readonly query?: Readonly<
    Record<string, string | number | boolean | readonly string[] | undefined>
  >;
  readonly body?: unknown;
  readonly headers?: Readonly<Record<string, string>>;
  /** Fail unless the status is one of these (default: any 2xx). */
  readonly expect?: readonly number[];
}

/** Error of an unexpected status; the message carries the API error code and request id only. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  constructor(method: string, path: string, status: number, body: unknown) {
    const error =
      typeof body === 'object' && body !== null && 'error' in body
        ? (body as { error?: { code?: unknown; request_id?: unknown } }).error
        : undefined;
    const code = typeof error?.code === 'string' ? error.code : null;
    const requestId = typeof error?.request_id === 'string' ? error.request_id : null;
    super(
      `${method} ${path}: HTTP ${String(status)}${code === null ? '' : ` ${code}`}${requestId === null ? '' : ` (request ${requestId})`}`,
    );
    this.status = status;
    this.code = code;
  }
}

/** A cookie session against one server (base URL = the public URL, e.g. http://127.0.0.1:41234). */
export class ApiSession {
  readonly baseUrl: string;
  private readonly jar = new Map<string, JarCookie>();

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  /** The cookies in the jar (expired ones dropped). */
  cookies(): JarCookie[] {
    const now = Math.floor(Date.now() / 1000);
    return [...this.jar.values()].filter((c) => c.expires === -1 || c.expires > now);
  }

  cookie(name: string): JarCookie | undefined {
    return this.cookies().find((c) => c.name === name);
  }

  private key(name: string, path: string): string {
    return `${name};${path}`;
  }

  private store(response: Response): void {
    const now = Date.now();
    for (const header of response.headers.getSetCookie()) {
      const cookie = parseSetCookie(header, now);
      if (cookie === null) {
        continue;
      }
      const key = this.key(cookie.name, cookie.path);
      if (cookie.value === '' || (cookie.expires !== -1 && cookie.expires * 1000 <= now)) {
        this.jar.delete(key);
      } else {
        this.jar.set(key, cookie);
      }
    }
  }

  private cookieHeader(path: string): string {
    return this.cookies()
      .filter((c) => pathMatches(c.path, path))
      .sort((a, b) => b.path.length - a.path.length)
      .map((c) => `${c.name}=${c.value}`)
      .join('; ');
  }

  /** Sends a request to `/api/v1<path>` (or to `path` when it does not start with /api). */
  async request<T = unknown>(
    method: string,
    path: string,
    options: RequestOptions = {},
  ): Promise<ApiResponse<T>> {
    const fullPath =
      path.startsWith('/api/') || path.startsWith('/actuator') ? path : `${API_PREFIX}${path}`;
    const url = new URL(`${this.baseUrl}${fullPath}`);
    for (const [key, value] of Object.entries(options.query ?? {})) {
      if (value === undefined) {
        continue;
      }
      if (Array.isArray(value)) {
        for (const item of value as readonly string[]) {
          url.searchParams.append(key, item);
        }
      } else {
        url.searchParams.set(key, String(value));
      }
    }
    const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
    const cookieHeader = this.cookieHeader(url.pathname);
    if (cookieHeader !== '') {
      headers.Cookie = cookieHeader;
    }
    const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase());
    if (unsafe) {
      headers.Origin = this.baseUrl;
      const csrf = this.cookie('argos_csrf');
      if (csrf !== undefined) {
        headers['X-Argos-CSRF'] = csrf.value;
      }
    }
    let body: string | undefined;
    if (options.body !== undefined) {
      body = JSON.stringify(options.body);
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(url, { method, headers, body, redirect: 'manual' });
    this.store(response);
    const text = await response.text();
    let parsed: T | null = null;
    if (text !== '' && (response.headers.get('content-type') ?? '').includes('json')) {
      parsed = JSON.parse(text) as T;
    }
    const ok =
      options.expect === undefined
        ? response.status >= 200 && response.status < 300
        : options.expect.includes(response.status);
    if (!ok) {
      throw new ApiError(method, url.pathname, response.status, parsed);
    }
    return { status: response.status, headers: response.headers, body: parsed, text };
  }

  async get<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const response: ApiResponse<T> = await this.request('GET', path, options);
    if (response.body === null) {
      throw new Error(`GET ${path}: empty body`);
    }
    return response.body;
  }

  /** GET of an enveloped query response (`{data, meta}`, docs/14 §2.5). */
  // eslint-disable-next-line @typescript-eslint/no-unnecessary-type-parameters -- the caller names the data type of this test-only client
  async query<T = unknown>(
    path: string,
    query?: RequestOptions['query'],
  ): Promise<{ data: T; meta: Record<string, unknown> }> {
    return this.get<{ data: T; meta: Record<string, unknown> }>(path, { query });
  }

  /** Fetches the CSRF cookie (GET /auth/csrf). */
  async csrf(): Promise<void> {
    await this.request('GET', '/auth/csrf');
  }

  /** GET of an enveloped resource; resolves with `data` (docs/14 §2.5). */
  async data<T = unknown>(path: string, query?: RequestOptions['query']): Promise<T> {
    return (await this.query<T>(path, query)).data;
  }

  /** POST /auth/login; resolves with the login data (docs/14 §4.1). */
  async login(
    username: string,
    password: string,
  ): Promise<{ must_change_password: boolean; roles: string[]; session_expires_at: string }> {
    await this.csrf();
    const response = await this.request<{
      data: { must_change_password: boolean; roles: string[]; session_expires_at: string };
    }>('POST', '/auth/login', { body: { username, password } });
    if (response.body === null) {
      throw new Error('POST /auth/login: empty body');
    }
    return response.body.data;
  }

  async logout(): Promise<void> {
    await this.request('POST', '/auth/logout', { expect: [204] });
  }
}
