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
}

const TIME_KEYS = new Set(['time', 'live']);

/** Reads and writes the URL state of `schema` (default: the current route's schema). */
export function useUrlState<S extends UrlSchema = typeof CommonQ>(schema?: S): UrlStateApi<S> {
  const routeSchema = useRouteSchema();
  const effective = (schema ?? routeSchema) as S;
  const location = useLocation();
  const navigate = useNavigate();
  const parsed = useMemo(() => parseUrl(effective, location.search), [effective, location.search]);

  const set = useCallback(
    (partial: Partial<UrlStateOf<S>>, options: SetOptions = {}): boolean => {
      const next = { ...parsed.state, ...partial };
      if (!isValidState(effective, next)) {
        return false;
      }
      const own = writeQuery(effective, next);
      // Keys the schema does not know (written by a screen schema while a shell part writes
      // with CommonQ) stay as they are.
      const foreign: string[] = [];
      if (options.pathname === undefined) {
        for (const [key, value] of new URLSearchParams(location.search)) {
          if (!ownsKey(effective, key)) {
            foreign.push(`${encodeQueryComponent(key)}=${encodeQueryComponent(value)}`);
          }
        }
      }
      const query = [own, ...foreign].filter((part) => part !== '').join('&');
      const pathname = options.pathname ?? location.pathname;
      const url = query === '' ? pathname : `${pathname}?${query}`;
      if (!fitsWriteLimit(url)) {
        useUiStore.getState().setUrlTooLong(true);
        return false;
      }
      const replace = options.replace ?? Object.keys(partial).every((key) => TIME_KEYS.has(key));
      void navigate(url, { replace });
      return true;
    },
    [effective, location.pathname, location.search, navigate, parsed.state],
  );

  return { state: parsed.state, issues: parsed.issues, set };
}
