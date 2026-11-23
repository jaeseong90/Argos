// SPDX-License-Identifier: Apache-2.0
//
// React side of the URL state (docs/15 §4.2): route loaders validate and normalise the query
// (validateUrl), components read it and write it through useUrlState().set only.
import { useCallback, useMemo } from 'react';
import { redirect, useLocation, useMatches, useNavigate } from 'react-router';

import { useUiStore } from './uiStore';
import {
  CommonQ,
  encodeQueryComponent,
  fitsWriteLimit,
  isValidState,
  ownsKey,
  parseUrl,
  writeQuery,
  type UrlIssue,
  type UrlSchema,
  type UrlStateOf,
} from './urlState';

/** Route `handle` of the shell routes (routes/routeTree.tsx). */
export interface RouteHandle {
  /** URL schema of the screen (docs/15 §4.2); the loader validates with it. */
  readonly urlSchema?: UrlSchema;
  /** Application-scoped (AppLayout) or global (GlobalLayout) route (docs/15 §3.1). */
  readonly scope?: 'app' | 'global';
}

function isRouteHandle(value: unknown): value is RouteHandle {
  return typeof value === 'object' && value !== null;
}

/** The URL schema of the deepest matched route that declares one, else CommonQ. */
export function useRouteSchema(): UrlSchema {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const handle = matches[i]?.handle;
    if (isRouteHandle(handle) && handle.urlSchema !== undefined) {
      return handle.urlSchema;
    }
  }
  return CommonQ;
}

/** The route handle of the deepest match that has one. */
export function useRouteHandle(): RouteHandle {
  const matches = useMatches();
  const merged: { -readonly [K in keyof RouteHandle]: RouteHandle[K] } = {};
  for (const match of matches) {
    if (isRouteHandle(match.handle)) {
      Object.assign(merged, match.handle);
    }
  }
  return merged;
}

/**
 * Loader step (docs/15 §4.2, SEC-64): parses the request URL with the route schema; invalid items
 * fall back to defaults and raise the url-restore-notice once; a URL that is not in canonical form
 * is replaced by it (redirect), so every screen sees exactly one spelling of its state.
 */
export function validateUrl<S extends UrlSchema>(
  schema: S,
  request: Request,
): UrlStateOf<S> | Response {
  const url = new URL(request.url);
  const parsed = parseUrl(schema, url.search);
  if (parsed.issues.length > 0) {
    useUiStore.getState().showRestoreNotice(parsed.issues, url.pathname);
  }
  const query = parsed.canonical === '' ? '' : `?${parsed.canonical}`;
  // Compare the spellings the URL parser produces, not the raw strings: the browser normalizes
  // the query of the redirect target (percent-encoding), and a raw comparison that never matches
  // would redirect forever.
  if (new URL(`${url.pathname}${query}`, url).search !== url.search) {
    // React Router performs a loader redirect as a replace, so Back does not return to it.
    return redirect(`${url.pathname}${query}${url.hash}`);
  }
  return parsed.state;
}

/** A route loader that validates the URL with `schema` (docs/15 §3.1 "로더 URL 검증"). */
export function urlLoader<S extends UrlSchema>(
  schema: S,
): (args: { request: Request }) => UrlStateOf<S> | Response {
  return ({ request }) => validateUrl(schema, request);
}

export interface SetOptions {
  /**
   * Replace the history entry instead of pushing one. Default: replace when only the time range
   * and live change (docs/15 §4.2), push otherwise (screen, filter, selection).
   */
  readonly replace?: boolean;
  /** Write to another path (app or screen switch) instead of the current one. */
  readonly pathname?: string;
}

export interface UrlStateApi<S extends UrlSchema> {
  readonly state: UrlStateOf<S>;
  readonly issues: readonly UrlIssue[];
  /**
   * The only URL writer: merges `partial` into the current state, validates it with the zod
   * schema, refuses URLs longer than 2 KB (sets the url-too-long notice) and navigates.
   * Returns false when nothing was written.
   */
  readonly set: (partial: Partial<UrlStateOf<S>>, options?: SetOptions) => boolean;
  /**
   * The state of the newest URL: the rendered one, or the last one written through `set` while
   * the render has not caught up with it yet (see `freshUrl`). Event handlers that derive the next
   * state from the current one (the time range reducer) read it, so that two quick writes never
   * build on a stale render.
   */
  readonly fresh: () => UrlStateOf<S>;
}

const TIME_KEYS = new Set(['time', 'live']);

/**
 * URLs written through `set` that the rendered location may not show yet: React Router changes
 * the history entry first and renders the new location in a transition, which a busy page (the
 * dashboard's charts) can delay. A key pressed in between (05 §2.0-3: "[" then "+") would
 * otherwise act on the range of the previous render. `urls` is the rendered location the chain
 * started from and then every URL written, newest last.
 */
let written: { readonly urls: readonly string[]; readonly at: number } | null = null;

/** After this long without catching up the chain is dropped (the rendered location wins). */
export const WRITE_CATCH_UP_MS = 5_000;

/** Longest chain of written URLs kept. */
const WRITE_CHAIN_MAX = 16;

/**
 * The newest URL given the `rendered` one (pathname + search): the last written URL while the
 * render still shows an earlier URL of the chain; otherwise the rendered one (caught up, another
 * navigation, or too old), which ends the chain.
 */
export function freshUrl(rendered: string, now: number = Date.now()): string {
  const w = written;
  if (w === null) {
    return rendered;
  }
  const last = w.urls[w.urls.length - 1] ?? rendered;
  if (rendered === last || !w.urls.includes(rendered) || now - w.at > WRITE_CATCH_UP_MS) {
    written = null;
    return rendered;
  }
  return last;
}

/** Records a write of `url` made on top of `base` (exported for tests). */
export function recordWrite(base: string, url: string, now: number): void {
  const w = written;
  const urls = w !== null && w.urls[w.urls.length - 1] === base ? [...w.urls, url] : [base, url];
  written = { urls: urls.slice(-WRITE_CHAIN_MAX), at: now };
}

/** Test hook: forgets the written URLs (each test starts from the rendered location). */
export function resetWrittenUrls(): void {
  written = null;
}

function splitUrl(url: string): { readonly pathname: string; readonly search: string } {
  const q = url.indexOf('?');
  return q < 0
    ? { pathname: url, search: '' }
    : { pathname: url.slice(0, q), search: url.slice(q) };
}

/** Reads and writes the URL state of `schema` (default: the current route's schema). */
export function useUrlState<S extends UrlSchema = typeof CommonQ>(schema?: S): UrlStateApi<S> {
  const routeSchema = useRouteSchema();
  const effective = (schema ?? routeSchema) as S;
  const location = useLocation();
  const navigate = useNavigate();
  const parsed = useMemo(() => parseUrl(effective, location.search), [effective, location.search]);
  const rendered = `${location.pathname}${location.search}`;

  /** The newest location and its state (the rendered one unless a write is not rendered yet). */
  const latest = useCallback((): {
    readonly url: string;
    readonly pathname: string;
    readonly search: string;
    readonly state: UrlStateOf<S>;
  } => {
    const url = freshUrl(rendered);
    if (url === rendered) {
      return { url, pathname: location.pathname, search: location.search, state: parsed.state };
    }
    const { pathname, search } = splitUrl(url);
    return { url, pathname, search, state: parseUrl(effective, search).state };
  }, [effective, location.pathname, location.search, parsed.state, rendered]);

  const fresh = useCallback((): UrlStateOf<S> => latest().state, [latest]);

  const set = useCallback(
    (partial: Partial<UrlStateOf<S>>, options: SetOptions = {}): boolean => {
      const base = latest();
      const next = { ...base.state, ...partial };
      if (!isValidState(effective, next)) {
        return false;
      }
      const own = writeQuery(effective, next);
      // Keys the schema does not know (written by a screen schema while a shell part writes
      // with CommonQ) stay as they are.
      const foreign: string[] = [];
      if (options.pathname === undefined) {
        for (const [key, value] of new URLSearchParams(base.search)) {
          if (!ownsKey(effective, key)) {
            foreign.push(`${encodeQueryComponent(key)}=${encodeQueryComponent(value)}`);
          }
        }
      }
      const query = [own, ...foreign].filter((part) => part !== '').join('&');
      const pathname = options.pathname ?? base.pathname;
      const url = query === '' ? pathname : `${pathname}?${query}`;
      if (!fitsWriteLimit(url)) {
        useUiStore.getState().setUrlTooLong(true);
        return false;
      }
      const replace = options.replace ?? Object.keys(partial).every((key) => TIME_KEYS.has(key));
      recordWrite(base.url, url, Date.now());
      void navigate(url, { replace });
      return true;
    },
    [effective, latest, navigate],
  );

  return { state: parsed.state, issues: parsed.issues, set, fresh };
}
