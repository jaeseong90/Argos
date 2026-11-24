// SPDX-License-Identifier: Apache-2.0
//
// Global search `/search` (docs/14 §4.11, docs/15 §9.12, F-U14, WO-39): the input's kind as the
// screen shows it (digits up to 20 → transaction id, 32 hex digits → trace id, `user:` and `req:`
// prefixes → user id and request id searches over the selected range of at most 24 h, anything
// else → text over services, SQL and instances), the request (`GET /search`) and the mapping of a
// match's target (`route` + `params`, docs/14 §6) to a UI path.
//
// The target comes from the server but its params are collected strings: only the routes listed
// in TARGET_ROUTES are opened, every id is checked against its pattern and encoded, so a match can
// never send the browser anywhere else (SEC-60, SEC-64).
import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import { isApiError } from './errors';
import type { components, paths } from './generated/schema';

export type SearchResult = components['schemas']['SearchResult'];
export type SearchMatch = components['schemas']['SearchMatch'];

/** The longest query (docs/15 §9.12, docs/14 §7.2: 1 KB of UTF-8). */
export const SEARCH_Q_MAX_BYTES = 1024;
/** The longest range of user id and request id searches (docs/14 §4.11, N-24). */
export const SEARCH_VALUE_RANGE_MAX_MS = 24 * 60 * 60 * 1000;
/** Text searches need at least this many characters (server DictionaryTextSearch.MIN_CHARS). */
export const SEARCH_TEXT_MIN_CHARS = 3;
/** Largest unsigned 64-bit id (transaction ids, ADR-0010 C-175). */
const UINT64_MAX = 18_446_744_073_709_551_615n;

/** The kind the input is read as (docs/15 §9.12). */
export type SearchKind = 'tx' | 'trace' | 'user' | 'request' | 'text' | 'empty';

/** API search types (docs/14 §4.11). */
export type SearchType = NonNullable<
  NonNullable<NonNullable<paths['/api/v1/search']['get']>['parameters']['query']>['type']
>;

/** How the screen reads an input. */
export interface SearchPlan {
  readonly kind: SearchKind;
  /** The text sent as `q` (the prefix of user:/req: removed, trimmed). */
  readonly q: string;
  /** The API `type`. */
  readonly type: SearchType;
  /** user id and request id searches need a range of at most 24 h. */
  readonly needsRange: boolean;
}

const DIGITS = /^\d{1,20}$/;
const TRACE = /^[0-9a-fA-F]{32}$/;

/** True when the digits are an unsigned 64-bit value (at most 20 digits, ≤ 2^64 − 1). */
export function isTxId(text: string): boolean {
  return DIGITS.test(text) && BigInt(text) <= UINT64_MAX;
}

/** True for a W3C trace id (32 hex digits). */
export function isTraceId(text: string): boolean {
  return TRACE.test(text);
}

/**
 * The kind of an input (docs/15 §9.12). An explicit type (URL `type`, not `auto`) wins: the text is
 * then sent as typed (a `user:`/`req:` prefix is still removed for those types).
 */
export function planSearch(input: string, explicit: SearchType = 'auto'): SearchPlan {
  const text = input.trim();
  const lower = text.toLowerCase();
  const prefixed = (prefix: string): string | null =>
    lower.startsWith(prefix) ? text.slice(prefix.length).trim() : null;
  const user = prefixed('user:');
  const req = prefixed('req:');
  if (explicit !== 'auto') {
    const q = explicit === 'user' ? (user ?? text) : explicit === 'request' ? (req ?? text) : text;
    const kind: SearchKind =
      explicit === 'tx' || explicit === 'trace' || explicit === 'user' || explicit === 'request'
        ? explicit
        : 'text';
    return {
      kind: q === '' ? 'empty' : kind,
      q,
      type: explicit,
      needsRange: explicit === 'user' || explicit === 'request',
    };
  }
  if (text === '') {
    return { kind: 'empty', q: '', type: 'auto', needsRange: false };
  }
  if (user !== null) {
    return { kind: user === '' ? 'empty' : 'user', q: user, type: 'user', needsRange: true };
  }
  if (req !== null) {
    return { kind: req === '' ? 'empty' : 'request', q: req, type: 'request', needsRange: true };
  }
  if (isTxId(text)) {
    return { kind: 'tx', q: text, type: 'tx', needsRange: false };
  }
  if (isTraceId(text)) {
    return { kind: 'trace', q: text.toLowerCase(), type: 'trace', needsRange: false };
  }
  return { kind: 'text', q: text, type: 'auto', needsRange: false };
}

/** True when a search of this plan may be sent over the range (user/req: at most 24 h). */
export function rangeFits(plan: SearchPlan, range: { from: number; to: number }): boolean {
  return (
    !plan.needsRange ||
    (range.to > range.from && range.to - range.from <= SEARCH_VALUE_RANGE_MAX_MS)
  );
}

export interface SearchRequest {
  readonly plan: SearchPlan;
  readonly from: number;
  readonly to: number;
  readonly env: string | null;
}

/** The API query of a request (from/to sent for every search; the server needs them for user/req). */
export function searchQuery(request: SearchRequest): {
  q: string;
  type: SearchType;
  from: string;
  to: string;
  env?: string;
} {
  const query: { q: string; type: SearchType; from: string; to: string; env?: string } = {
    q: request.plan.q,
    type: request.plan.type,
    from: new Date(request.from).toISOString(),
    to: new Date(request.to).toISOString(),
  };
  if (request.env !== null) {
    query.env = request.env;
  }
  return query;
}

export const searchKeys = {
  all: () => ['search'] as const,
  find: (request: SearchRequest) => ['search', 'find', searchQuery(request)] as const,
};

/** `GET /search`. */
export async function search(
  request: SearchRequest,
  signal?: AbortSignal,
): Promise<Enveloped<SearchResult>> {
  return unwrap<EnvelopeOf<'/api/v1/search', 'get'>>(
    api().client.GET('/api/v1/search', {
      params: { query: searchQuery(request) },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/** Where a match leads: a UI path, an instance whose application must be looked up, or nothing. */
export type SearchDestination =
  | { readonly kind: 'path'; readonly path: string }
  | { readonly kind: 'instance'; readonly instanceId: number }
  | { readonly kind: 'none' };

const APP_ID = /^[1-9]\d{0,9}$/;
const HASH_ID = /^\d{1,20}$/;
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;

/** The `ts` hint as `?ts=<ISO>` (milliseconds), or '' when absent or not an instant. */
function tsQuery(ts: string | undefined): string {
  if (ts === undefined || !ISO_INSTANT.test(ts)) {
    return '';
  }
  const ms = Date.parse(ts);
  return Number.isNaN(ms) ? '' : `ts=${encodeURIComponent(new Date(ms).toISOString())}`;
}

function withQuery(path: string, ...parts: string[]): string {
  const query = parts.filter((part) => part !== '').join('&');
  return query === '' ? path : `${path}?${query}`;
}

/**
 * The UI destination of a server target (docs/14 §6 routes): `/tx/:txId` (the alias resolves the
 * application), `/traces/:traceId`, `/apps/:appId/services/:serviceId` and `/apps/:appId/sql/:sqlId`
 * (the statistics screens) and `/instances/:instanceId` (application looked up by the caller).
 * `carried` is the query the app screens keep (env, range). Anything else: none.
 */
export function searchDestination(target: SearchMatch['target'], carried = ''): SearchDestination {
  const params: Readonly<Record<string, string>> = target.params;
  const get = (key: string): string | undefined =>
    Object.prototype.hasOwnProperty.call(params, key) ? params[key] : undefined;
  switch (target.route) {
    case '/tx/:txId': {
      const txId = get('txId');
      return txId !== undefined && isTxId(txId)
        ? { kind: 'path', path: withQuery(`/tx/${txId}`, tsQuery(get('ts'))) }
        : { kind: 'none' };
    }
    case '/traces/:traceId': {
      const traceId = get('traceId');
      return traceId !== undefined && isTraceId(traceId)
        ? {
            kind: 'path',
            path: withQuery(`/traces/${traceId.toLowerCase()}`, tsQuery(get('ts'))),
          }
        : { kind: 'none' };
    }
    case '/apps/:appId/services/:serviceId':
    case '/apps/:appId/sql/:sqlId': {
      const sql = target.route.endsWith(':sqlId');
      const appId = get('appId');
      const id = get(sql ? 'sqlId' : 'serviceId');
      if (appId === undefined || id === undefined || !APP_ID.test(appId) || !HASH_ID.test(id)) {
        return { kind: 'none' };
      }
      return {
        kind: 'path',
        path: withQuery(`/apps/${appId}/stats/${sql ? 'sql' : 'services'}/${id}`, carried),
      };
    }
    case '/instances/:instanceId': {
      const instanceId = get('instanceId');
      return instanceId !== undefined && APP_ID.test(instanceId)
        ? { kind: 'instance', instanceId: Number(instanceId) }
        : { kind: 'none' };
    }
    default:
      return { kind: 'none' };
  }
}

/** The path of an instance detail once its application is known. */
export function instancePath(appId: number, instanceId: number, carried = ''): string {
  return withQuery(`/apps/${String(appId)}/instances/${String(instanceId)}`, carried);
}

/** The screen message of a refused search (docs/14 §4.11). */
export type SearchErrorKind =
  'rateLimited' | 'hashUnavailable' | 'rangeInvalid' | 'queryInvalid' | 'other';

export function searchErrorKind(error: unknown): SearchErrorKind {
  if (!isApiError(error)) {
    return 'other';
  }
  if (error.status === 429) {
    return 'rateLimited';
  }
  if (error.status === 422) {
    return error.code === 'user_id_hash_unavailable' ? 'hashUnavailable' : 'queryInvalid';
  }
  if (error.status === 400) {
    return error.details.some((d) => d.field === 'from' || d.field === 'to')
      ? 'rangeInvalid'
      : 'queryInvalid';
  }
  return 'other';
}
