// SPDX-License-Identifier: Apache-2.0
//
// The one place that talks HTTP (docs/15 §11, DSN-15-08, SEC-40, SEC-43). The generated client
// (openapi-fetch) hands every request to this transport, which
//
//  - sends cookies (credentials: include) and never an Authorization header (docs/16 §7.1);
//  - adds X-Argos-CSRF with the value of the argos_csrf cookie to POST, PUT, PATCH and DELETE
//    (double submit, docs/14 §2.2); without the cookie it first calls GET /auth/csrf;
//  - on 403 csrf_invalid (docs/14) or csrf_failed (docs/16) fetches a new CSRF token and retries
//    once;
//  - on 401 refreshes the session once for all concurrent requests (one shared promise per tab,
//    a Web Lock across tabs: the server has no reuse grace, so two refreshes would revoke the
//    session family, 16 §7.2) and retries once; if the refresh fails it reports the session as
//    expired (the shell then goes to /login?next=...).
//  - decides whether another tab already refreshed from the server's answer, never from a value
//    another tab wrote (WO-24 client contract, RefreshRotation): when there is a sign of such a
//    refresh (the lock was busy when this tab asked for it, or the "refreshed at" marker is newer
//    than the failed request), it first retries the original request with the cookies the
//    browser holds now, which every tab shares synchronously, and refreshes only if that retry
//    still answers 401. The localStorage marker is a hint that triggers this check, not a reason
//    to skip a refresh: Chromium hands localStorage writes to other tabs asynchronously, so a tab
//    that just got the lock can still read the old value (the T-SEC-41a race of 2026-11-15).
//
// Tokens never pass through JavaScript: the session cookies are HttpOnly, the CSRF cookie is
// read, never written.
import { readLastRefresh, writeLastRefresh } from '../state/preferences';

import { CSRF_ERROR_CODES } from './errors';

export const API_BASE = '/api/v1';
export const CSRF_COOKIE = 'argos_csrf';
export const CSRF_HEADER = 'X-Argos-CSRF';
const CSRF_PATH = `${API_BASE}/auth/csrf`;
const REFRESH_PATH = `${API_BASE}/auth/refresh`;
const REFRESH_LOCK = 'argos.session-refresh';
const UNSAFE_METHODS: ReadonlySet<string> = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/**
 * Paths whose 401 is an answer, not an expired session: logging in with a wrong password, the
 * refresh itself, logout, CSRF, setup and CSP reports (docs/14 §4.1).
 */
const NO_REFRESH_PATHS: ReadonlySet<string> = new Set([
  `${API_BASE}/auth/login`,
  REFRESH_PATH,
  `${API_BASE}/auth/logout`,
  CSRF_PATH,
  `${API_BASE}/setup/status`,
  `${API_BASE}/setup/admin`,
  `${API_BASE}/csp-report`,
]);

type FetchLike = (input: Request) => Promise<Response>;

/**
 * Minimal Web Locks surface (navigator.locks), absent in jsdom and old browsers. With
 * `ifAvailable` the callback gets null instead of waiting when another tab holds the lock.
 */
export interface LockManagerLike {
  request<T>(
    name: string,
    options: { readonly ifAvailable?: boolean },
    callback: (lock: unknown) => Promise<T>,
  ): Promise<T>;
}

/** Result of the non-waiting lock attempt when another tab holds the lock. */
const LOCK_BUSY = Symbol('lock-busy');

export interface TransportOptions {
  /** The network fetch (tests stub it here, at the fetch boundary). */
  readonly fetch: FetchLike;
  /** Reads document.cookie (tests pass their own jar). */
  readonly readCookies?: () => string;
  /** Cross-tab lock; default navigator.locks when present. */
  readonly locks?: LockManagerLike | null;
  /** Clock for the cross-tab refresh marker. */
  readonly now?: () => number;
  /** Origin of the API (default: the page's origin; requests are always same-origin). */
  readonly origin?: string;
}

export interface TransportListeners {
  /** The session could not be refreshed: expired or revoked (docs/15 §11). */
  onSessionExpired?: () => void;
  /** A refresh succeeded; the payload is the new session_expires_at (ISO) if the body had it. */
  onSessionRefreshed?: (sessionExpiresAt: string | null) => void;
}

/** Value of a cookie in a `document.cookie` string, or null. */
export function readCookie(cookies: string, name: string): string | null {
  for (const part of cookies.split(';')) {
    const index = part.indexOf('=');
    if (index < 0) {
      continue;
    }
    if (part.slice(0, index).trim() === name) {
      const value = part.slice(index + 1).trim();
      try {
        return value === '' ? null : decodeURIComponent(value);
      } catch {
        return null;
      }
    }
  }
  return null;
}

/** The page's origin; 'http://localhost' outside a browser. */
export function pageOrigin(): string {
  return typeof location === 'undefined' ? 'http://localhost' : location.origin;
}

function defaultLocks(): LockManagerLike | null {
  const nav: unknown = typeof navigator === 'undefined' ? undefined : navigator;
  if (typeof nav === 'object' && nav !== null && 'locks' in nav) {
    const locks = (nav as { locks?: unknown }).locks;
    if (typeof locks === 'object' && locks !== null && 'request' in locks) {
      return locks as LockManagerLike;
    }
  }
  return null;
}

async function errorCode(response: Response): Promise<string | null> {
  try {
    const body: unknown = await response.clone().json();
    if (typeof body === 'object' && body !== null && 'error' in body) {
      const error = body.error;
      if (typeof error === 'object' && error !== null && 'code' in error) {
        const code = error.code;
        return typeof code === 'string' ? code : null;
      }
    }
  } catch {
    // Not JSON.
  }
  return null;
}

/** The HTTP transport with its session state. One instance per page (api/client.ts). */
export class Transport {
  private readonly network: FetchLike;
  private readonly readCookies: () => string;
  private readonly locks: LockManagerLike | null;
  private readonly now: () => number;
  private readonly origin: string;
  private readonly listeners: TransportListeners = {};
  private refreshInFlight: Promise<boolean> | null = null;
  private csrfInFlight: Promise<string | null> | null = null;

  constructor(options: TransportOptions) {
    this.network = options.fetch;
    this.readCookies =
      options.readCookies ?? (() => (typeof document === 'undefined' ? '' : document.cookie));
    this.locks = options.locks === undefined ? defaultLocks() : options.locks;
    this.now = options.now ?? Date.now;
    this.origin = options.origin ?? pageOrigin();
  }

  /** Absolute URL of an API path on this page's origin. */
  url(path: string): string {
    return new URL(path, this.origin).toString();
  }

  /** Sets the session listeners (the auth store wires itself here). */
  setListeners(listeners: TransportListeners): void {
    this.listeners.onSessionExpired = listeners.onSessionExpired;
    this.listeners.onSessionRefreshed = listeners.onSessionRefreshed;
  }

  /** The fetch function for openapi-fetch. */
  readonly fetch = (request: Request): Promise<Response> => this.send(request);

  /** Current CSRF token from the cookie. */
  csrfToken(): string | null {
    return readCookie(this.readCookies(), CSRF_COOKIE);
  }

  /**
   * Obtains a CSRF token: GET /auth/csrf sets the cookie (docs/14 §4.1). Called at start and after
   * login by the auth layer, and on demand before the first state-changing request.
   */
  refreshCsrf(): Promise<string | null> {
    this.csrfInFlight ??= this.fetchCsrf().finally(() => {
      this.csrfInFlight = null;
    });
    return this.csrfInFlight;
  }

  private async fetchCsrf(): Promise<string | null> {
    const response = await this.network(
      new Request(this.url(CSRF_PATH), {
        method: 'GET',
        credentials: 'include',
        headers: { Accept: 'application/json' },
      }),
    );
    if (!response.ok) {
      return null;
    }
    const fromCookie = this.csrfToken();
    if (fromCookie !== null) {
      return fromCookie;
    }
    try {
      const body = (await response.json()) as { data?: { csrf_token?: unknown } };
      return typeof body.data?.csrf_token === 'string' ? body.data.csrf_token : null;
    } catch {
      return null;
    }
  }

  private async withCsrf(request: Request, forceNew: boolean): Promise<Request> {
    if (!UNSAFE_METHODS.has(request.method.toUpperCase())) {
      return request;
    }
    let token = forceNew ? null : this.csrfToken();
    token ??= await this.refreshCsrf();
    if (token !== null) {
      request.headers.set(CSRF_HEADER, token);
    }
    return request;
  }

  private async send(original: Request): Promise<Response> {
    const path = new URL(original.url, 'http://localhost').pathname;
    // Keep an untouched copy for the single retry (the body can be read only once).
    const spare = original.clone();
    const prepared = await this.withCsrf(original, false);
    // When the request left: a refresh that another tab (or request) finished after this moment
    // rotated the cookies this request did not carry yet, so a 401 then needs a retry only.
    const sentAt = this.now();
    const first = await this.network(prepared);

    if (first.status === 403 && UNSAFE_METHODS.has(original.method.toUpperCase())) {
      const code = await errorCode(first);
      if (code !== null && CSRF_ERROR_CODES.has(code)) {
        return this.network(await this.withCsrf(spare, true));
      }
      return first;
    }

    if (first.status === 401 && !NO_REFRESH_PATHS.has(path)) {
      // The retry that may already have been answered while checking for another tab's refresh
      // (an object, so that the assignment inside the callback is visible here).
      const checked: { response: Response | null } = { response: null };
      const refreshed = await this.refreshSession(sentAt, async () => {
        const response = await this.network(await this.withCsrf(spare.clone(), false));
        if (response.status === 401) {
          return false;
        }
        checked.response = response;
        return true;
      });
      if (!refreshed) {
        this.listeners.onSessionExpired?.();
        return first;
      }
      const retried = checked.response ?? (await this.network(await this.withCsrf(spare, false)));
      if (retried.status === 401) {
        this.listeners.onSessionExpired?.();
      }
      return retried;
    }
    return first;
  }

  /**
   * One refresh for every request that got 401 in this tab (shared promise) and across tabs (Web
   * Lock). Inside the lock, a sign that another tab refreshed after the failed request left (the
   * lock was busy, or the marker is newer than `failedAt`) makes this tab call `verify` first:
   * it retries the original request with the cookies the browser holds now and answers true when
   * the server took them. Only a failed check, or no sign at all, leads to a refresh. The marker
   * alone never skips one (it reaches other tabs asynchronously). `failedAt` is the send time of
   * the failed request, not the arrival of the 401: a slow 401 can arrive after another tab's
   * refresh although the request left before it. A request that joins a refresh already in flight
   * in this tab waits for it and is retried by the caller.
   *
   * @param verify the retry of the original request; without one (default) nothing can be
   *     checked and a refresh follows
   * @returns whether the session is usable now (checked or refreshed)
   */
  refreshSession(
    failedAt: number,
    verify: () => Promise<boolean> = () => Promise.resolve(false),
  ): Promise<boolean> {
    this.refreshInFlight ??= this.lockedRefresh(failedAt, verify).finally(() => {
      this.refreshInFlight = null;
    });
    return this.refreshInFlight;
  }

  private async lockedRefresh(failedAt: number, verify: () => Promise<boolean>): Promise<boolean> {
    // A check that cannot reach the server proves nothing: the refresh decides, as before.
    const check = async (): Promise<boolean> => {
      try {
        return await verify();
      } catch {
        return false;
      }
    };
    const run = async (lockWasBusy: boolean): Promise<boolean> => {
      if ((lockWasBusy || readLastRefresh() > failedAt) && (await check())) {
        return true;
      }
      return this.doRefresh();
    };
    if (this.locks === null) {
      // No Web Locks (old browsers, tests): the in-tab single flight and the same rule, with the
      // marker as the only sign of another tab's refresh.
      return run(false);
    }
    const locks = this.locks;
    const immediate = await locks.request<boolean | typeof LOCK_BUSY>(
      REFRESH_LOCK,
      { ifAvailable: true },
      (lock) => (lock === null ? Promise.resolve(LOCK_BUSY) : run(false)),
    );
    if (immediate !== LOCK_BUSY) {
      return immediate;
    }
    // Another tab holds the lock, most likely while it refreshes: wait, then check first.
    return locks.request<boolean>(REFRESH_LOCK, {}, () => run(true));
  }

  private async doRefresh(): Promise<boolean> {
    let response: Response;
    try {
      const request = await this.withCsrf(
        new Request(this.url(REFRESH_PATH), {
          method: 'POST',
          credentials: 'include',
          headers: { Accept: 'application/json' },
        }),
        false,
      );
      response = await this.network(request);
      if (response.status === 403 && CSRF_ERROR_CODES.has((await errorCode(response)) ?? '')) {
        response = await this.network(
          await this.withCsrf(
            new Request(this.url(REFRESH_PATH), {
              method: 'POST',
              credentials: 'include',
              headers: { Accept: 'application/json' },
            }),
            true,
          ),
        );
      }
    } catch {
      return false;
    }
    if (!response.ok) {
      return false;
    }
    writeLastRefresh(this.now());
    let expiresAt: string | null = null;
    try {
      const body = (await response.json()) as { data?: { session_expires_at?: unknown } };
      expiresAt =
        typeof body.data?.session_expires_at === 'string' ? body.data.session_expires_at : null;
    } catch {
      // A refresh without a readable body still rotated the cookies.
    }
    this.listeners.onSessionRefreshed?.(expiresAt);
    return true;
  }
}
