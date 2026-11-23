// SPDX-License-Identifier: Apache-2.0
//
// XLog URL state -> transaction search request (ADR-0010 C-206 (3)): the one conversion that the
// XLog transaction list (WO-33) and the previous/next buttons of the transaction detail (WO-34,
// re-query from the `back` link) share, so both always ask the server the same question. The XLog
// URL state is the WO-30 urlState schema XLogQ (docs/14 §6 DSN-14-17, C-166); the request is
// docs/14 §4.6 `POST /apps/{appId}/transactions/search`.
//
// Filter vocabulary of the XLog screen (docs/14 §6 `f.<field>`: `f.service=17`, `f.status=!200`,
// `f.user=abc`; values are keys, never display names, so a deep link stays exact):
//
//   f.<field>      value                      search filter     XLog points / summary (§4.5)
//   service        service key (u64 string)   service_id[]      service[]
//   instance       instance id                instance_id[]     instance[]
//   status         HTTP status code           http_status[]     -
//   error_class    error class key (u64)      error_class_id[]  -
//   version        version key (u64)          version_id[]      -
//   error          true | false               error             error
//   profile        true | false               has_profile       -
//   user           user id text               user_id           -
//   request        request id text            request_id        -
//   trace          trace id (32 hex)          trace_id          -
//   sql            SQL key (u64)              sql_id            -
//   error_group    error group key (u64)      error_group_id    -
//   remote_ip      client address             remote_ip         -
//   elapsed_ms     .gt/.gte/.lt/.lte number   min_ms / max_ms   min_ms / max_ms
//
// Several `eq` values of one field mean "any of them" (DSN-14-17): list fields send them all;
// single-valued fields accept one value. The API has no exclusion filters, so `f.<field>=!value`
// cannot be sent: it is reported in `unapplied` (reason `exclude`) and the screen says so; it is
// never emulated on the client (the rows are a server-side top 1,000). 64-bit keys stay decimal
// strings (C-175): never Number(), never compared as numbers.
import { browserTimeZone, resolveForQuery } from '../state/timeRange';
import {
  formatInstant,
  type Filter,
  type Kind,
  type Selection,
  type TimeRange,
} from '../state/urlState';

import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { SaveFileEnvironment } from './exportCsv';
import type { components } from './generated/schema';

export type TransactionSearchRequest = components['schemas']['TransactionSearchRequest'];
export type TransactionSearchFilters = components['schemas']['TransactionSearchFilters'];
export type TransactionSearchRow = components['schemas']['TransactionSearchRow'];
export type XlogSummaryRequest = components['schemas']['XlogSummaryRequest'];

/** Rows of one search page: the server maximum (docs/14 §4.6, docs/15 §7.4 "≤ 1,000행"). */
export const SEARCH_PAGE_LIMIT = 1000;
/** Sort orders the search accepts (docs/14 §4.6); the first is the default. */
export const SEARCH_SORTS = ['elapsed_ms:desc', 'ts:desc'] as const;
export type SearchSort = (typeof SEARCH_SORTS)[number];
export const DEFAULT_SEARCH_SORT: SearchSort = 'elapsed_ms:desc';
/** Most values of one list filter the server accepts (docs/14 §7.2). */
export const LIST_FILTER_MAX_VALUES = 100;

/** The XLog URL state the conversion reads (urlState XLogQ; WO-34 parses it from `back`). */
export interface XLogSearchQuery {
  readonly time: TimeRange;
  readonly kind: Kind;
  readonly filters: readonly Filter[];
  readonly sort?: string | undefined;
  readonly sel?: Selection | undefined;
}

/** Why a URL filter is not part of a request. */
export type UnappliedReason =
  /** The field is not in the vocabulary above. */
  | 'unknown_field'
  /** The operator is not supported for the field (e.g. `contains` on a key). */
  | 'operator'
  /** `f.<field>=!value`: the API has no exclusion filters (docs/14 §4.6). */
  | 'exclude'
  /** The value is not a valid value of the field (a non-numeric key, `error=maybe`). */
  | 'invalid_value'
  /** A single-valued field with several values ("any of them" cannot be expressed). */
  | 'multiple_values'
  /** More values than the server accepts for one field. */
  | 'too_many_values'
  /** The endpoint has no such filter (the XLog points and summary know fewer fields). */
  | 'not_supported_here';

export interface UnappliedFilter {
  readonly filter: Filter;
  readonly reason: UnappliedReason;
}

/** The search call of an XLog state: path parameter, JSON body and what it could not express. */
export interface TransactionSearchCall {
  readonly appId: number;
  readonly body: TransactionSearchRequest;
  /** URL filters that the body does not contain (shown by the screen, never emulated). */
  readonly unapplied: readonly UnappliedFilter[];
  /**
   * True when the applied conditions can match nothing (all values of a field invalid, an empty
   * elapsed range): the screen shows the empty state without asking the server.
   */
  readonly empty: boolean;
}

/** The summary call of an XLog selection (docs/14 §4.5 `POST /apps/{appId}/xlog/summary`). */
export interface XlogSummaryCall {
  readonly appId: number;
  readonly body: XlogSummaryRequest;
  readonly unapplied: readonly UnappliedFilter[];
  readonly empty: boolean;
}

/** Filters of the XLog points request (docs/14 §4.5 `GET /apps/{appId}/xlog`) and the summary. */
export interface XlogPointFilters {
  readonly service: string[];
  readonly instance: number[];
  readonly error?: boolean;
  readonly minMs?: number;
  readonly maxMs?: number;
  readonly unapplied: readonly UnappliedFilter[];
  readonly empty: boolean;
}

/** Options of the conversions (tests pin the clock and the time zone). */
export interface ConversionOptions {
  /** Current time for relative ranges; default Date.now(). */
  readonly now?: number;
  /** Browser time zone for today/yesterday; default the browser's. */
  readonly timeZone?: string;
}

type ValueType = 'id64' | 'int' | 'bool' | 'text' | 'trace';

interface FieldRule {
  /** Name in TransactionSearchFilters. */
  readonly search: keyof TransactionSearchFilters;
  readonly type: ValueType;
  /** Several values allowed (an array filter). */
  readonly list: boolean;
  /** Name in the XLog points / summary request, when the endpoint has it. */
  readonly points?: 'service' | 'instance' | 'error';
}

/** The f.<field> vocabulary of the XLog screen (see the table at the top). */
export const XLOG_FILTER_FIELDS: Readonly<Record<string, FieldRule>> = {
  service: { search: 'service_id', type: 'id64', list: true, points: 'service' },
  instance: { search: 'instance_id', type: 'int', list: true, points: 'instance' },
  status: { search: 'http_status', type: 'int', list: true },
  error_class: { search: 'error_class_id', type: 'id64', list: true },
  version: { search: 'version_id', type: 'id64', list: true },
  error: { search: 'error', type: 'bool', list: false, points: 'error' },
  profile: { search: 'has_profile', type: 'bool', list: false },
  user: { search: 'user_id', type: 'text', list: false },
  request: { search: 'request_id', type: 'text', list: false },
  trace: { search: 'trace_id', type: 'trace', list: false },
  sql: { search: 'sql_id', type: 'id64', list: false },
  error_group: { search: 'error_group_id', type: 'id64', list: false },
  remote_ip: { search: 'remote_ip', type: 'text', list: false },
};

/** The elapsed-time field of the range operators (`f.elapsed_ms.gte=1000`). */
export const ELAPSED_FIELD = 'elapsed_ms';

const ID64 = /^[0-9]{1,20}$/;
const ID64_MAX = '18446744073709551615';
const INT = /^(0|[1-9]\d{0,9})$/;
const INT32_MAX = 2_147_483_647;
const TRACE = /^[0-9a-f]{32}$/;
/** Largest elapsed value the API takes (int64; JavaScript keeps integers exact to 2^53). */
const ELAPSED_MAX = Number.MAX_SAFE_INTEGER;

/** True for an unsigned 64-bit decimal key (C-175): compared as text, never converted. */
function isId64(value: string): boolean {
  if (!ID64.test(value)) {
    return false;
  }
  const digits = value.replace(/^0+(?=\d)/, '');
  return digits.length < 20 || (digits.length === 20 && digits <= ID64_MAX);
}

type Parsed = { ok: true; value: string | number | boolean } | { ok: false };

function parseValue(type: ValueType, raw: string): Parsed {
  switch (type) {
    case 'id64':
      return isId64(raw) ? { ok: true, value: raw } : { ok: false };
    case 'int': {
      if (!INT.test(raw)) {
        return { ok: false };
      }
      const n = Number(raw);
      return n <= INT32_MAX ? { ok: true, value: n } : { ok: false };
    }
    case 'bool':
      return raw === 'true' || raw === 'false'
        ? { ok: true, value: raw === 'true' }
        : { ok: false };
    case 'trace':
      return TRACE.test(raw.toLowerCase()) ? { ok: true, value: raw.toLowerCase() } : { ok: false };
    case 'text':
      return raw.length > 0 ? { ok: true, value: raw } : { ok: false };
  }
}

function parseElapsed(raw: string): number | null {
  if (!/^(0|[1-9]\d{0,15})$/.test(raw)) {
    return null;
  }
  const n = Number(raw);
  return n <= ELAPSED_MAX ? n : null;
}

interface ConvertedFilters {
  /** Search filter values per search field. */
  readonly values: Map<keyof TransactionSearchFilters, (string | number | boolean)[]>;
  readonly minMs: number | undefined;
  readonly maxMs: number | undefined;
  readonly unapplied: UnappliedFilter[];
  readonly empty: boolean;
}

/**
 * Splits URL filters into what the search can express and what it cannot. `pointsOnly` restricts
 * the vocabulary to the fields of the XLog points and summary requests (docs/14 §4.5).
 */
function convertFilters(filters: readonly Filter[], pointsOnly: boolean): ConvertedFilters {
  const unapplied: UnappliedFilter[] = [];
  const byField = new Map<string, Filter[]>();
  let minMs: number | undefined;
  let maxMs: number | undefined;
  let empty = false;
  for (const filter of filters) {
    if (filter.field === ELAPSED_FIELD) {
      const n = parseElapsed(filter.value);
      if (filter.op === 'eq' || filter.op === 'ne' || filter.op === 'contains') {
        unapplied.push({ filter, reason: 'operator' });
        continue;
      }
      if (n === null) {
        unapplied.push({ filter, reason: 'invalid_value' });
        continue;
      }
      if (filter.op === 'gte' || filter.op === 'gt') {
        const bound = filter.op === 'gt' ? n + 1 : n;
        minMs = minMs === undefined ? bound : Math.max(minMs, bound);
      } else {
        const bound = filter.op === 'lt' ? n - 1 : n;
        maxMs = maxMs === undefined ? bound : Math.min(maxMs, bound);
      }
      continue;
    }
    // No endpoint takes exclusions, whatever the field (docs/14 §4.5, §4.6).
    if (filter.op === 'ne') {
      unapplied.push({ filter, reason: 'exclude' });
      continue;
    }
    const rule = XLOG_FILTER_FIELDS[filter.field];
    if (rule === undefined) {
      unapplied.push({ filter, reason: 'unknown_field' });
      continue;
    }
    if (pointsOnly && rule.points === undefined) {
      unapplied.push({ filter, reason: 'not_supported_here' });
      continue;
    }
    if (filter.op !== 'eq') {
      unapplied.push({ filter, reason: 'operator' });
      continue;
    }
    const list = byField.get(filter.field) ?? [];
    list.push(filter);
    byField.set(filter.field, list);
  }

  const values = new Map<keyof TransactionSearchFilters, (string | number | boolean)[]>();
  for (const [field, list] of byField) {
    const rule = XLOG_FILTER_FIELDS[field];
    if (rule === undefined) {
      continue;
    }
    const valid: (string | number | boolean)[] = [];
    const validFilters: Filter[] = [];
    for (const filter of list) {
      const parsed = parseValue(rule.type, filter.value);
      if (!parsed.ok) {
        // An invalid key can never match: dropping it from an "any of" list is exact.
        unapplied.push({ filter, reason: 'invalid_value' });
        continue;
      }
      if (!valid.includes(parsed.value)) {
        valid.push(parsed.value);
        validFilters.push(filter);
      }
    }
    if (valid.length === 0) {
      // Every value of the field is impossible: nothing matches.
      empty = true;
      continue;
    }
    if (!rule.list && valid.length > 1) {
      for (const filter of validFilters) {
        unapplied.push({ filter, reason: 'multiple_values' });
      }
      continue;
    }
    if (valid.length > LIST_FILTER_MAX_VALUES) {
      for (const filter of validFilters) {
        unapplied.push({ filter, reason: 'too_many_values' });
      }
      continue;
    }
    values.set(rule.search, valid);
  }
  if (minMs !== undefined && maxMs !== undefined && minMs > maxMs) {
    empty = true;
  }
  if (maxMs !== undefined && maxMs < 0) {
    empty = true;
  }
  return { values, minMs, maxMs, unapplied, empty };
}

function intersectMin(a: number | undefined, b: number | undefined): number | undefined {
  return a === undefined ? b : b === undefined ? a : Math.max(a, b);
}

function intersectMax(a: number | undefined, b: number | undefined): number | undefined {
  return a === undefined ? b : b === undefined ? a : Math.min(a, b);
}

/** The time bounds of a query: the selection if there is one, otherwise the resolved range. */
export function queryBounds(
  query: Pick<XLogSearchQuery, 'time' | 'sel'>,
  options: ConversionOptions = {},
): { from: number; to: number; minMs?: number; maxMs?: number } {
  if (query.sel !== undefined) {
    // The selection is inclusive on both ends (docs/15 §7.3 t1 ≤ ts ≤ t2), the API's `to` is
    // exclusive: one millisecond more keeps points at the right edge (ts are whole ms).
    return {
      from: query.sel.tsFrom,
      to: query.sel.tsTo + 1,
      minMs: query.sel.msMin,
      maxMs: query.sel.msMax,
    };
  }
  const range = resolveForQuery(
    query.time,
    options.now ?? Date.now(),
    options.timeZone ?? browserTimeZone(),
  );
  return { from: range.from, to: range.to };
}

/** The sort the search uses for a URL `sort` value: a supported one, otherwise the default. */
export function searchSortOf(sort: string | undefined): SearchSort {
  return SEARCH_SORTS.find((s) => s === sort) ?? DEFAULT_SEARCH_SORT;
}

/**
 * The transaction search request of an XLog URL state (ADR-0010 C-206 (3)): range and elapsed
 * bounds from `sel` (or the time range without a selection), `kind`, the f= filters mapped to the
 * search filters, sort elapsed_ms:desc by default (ts:desc selectable), limit 1,000, and the
 * keyset `cursor` of the previous page when given. Filters the API cannot express are returned in
 * `unapplied`; `empty` says the conditions can match nothing.
 */
export function toTransactionSearchRequest(
  appId: number,
  xlogQuery: XLogSearchQuery,
  cursor?: string | null,
  options: ConversionOptions = {},
): TransactionSearchCall {
  const bounds = queryBounds(xlogQuery, options);
  const converted = convertFilters(xlogQuery.filters, false);
  const filters: TransactionSearchFilters = {};
  for (const [field, values] of converted.values) {
    const rule = Object.values(XLOG_FILTER_FIELDS).find((r) => r.search === field);
    if (rule === undefined) {
      continue;
    }
    // The rule's type decides the JSON shape: lists stay arrays, single fields one value.
    Object.assign(filters, { [field]: rule.list ? values : values[0] });
  }
  const minMs = intersectMin(bounds.minMs, converted.minMs);
  const maxMs = intersectMax(bounds.maxMs, converted.maxMs);
  if (minMs !== undefined) {
    filters.min_ms = minMs;
  }
  if (maxMs !== undefined) {
    filters.max_ms = maxMs;
  }
  const body: TransactionSearchRequest = {
    from: formatInstant(bounds.from),
    to: formatInstant(bounds.to),
    kind: xlogQuery.kind,
    filters,
    sort: searchSortOf(xlogQuery.sort),
    limit: SEARCH_PAGE_LIMIT,
  };
  if (cursor !== undefined && cursor !== null && cursor !== '') {
    body.cursor = cursor;
  }
  const empty = converted.empty || (minMs !== undefined && maxMs !== undefined && minMs > maxMs);
  return { appId, body, unapplied: converted.unapplied, empty };
}

/**
 * The XLog points filters of URL filters (docs/14 §4.5 `GET /apps/{appId}/xlog` and the summary
 * know service[], instance[], error, min_ms and max_ms only): what the scatter and the summary can
 * apply, and the rest in `unapplied` (reason `not_supported_here`, `exclude`, ...).
 */
export function toXlogPointFilters(filters: readonly Filter[]): XlogPointFilters {
  const converted = convertFilters(filters, true);
  const service = (converted.values.get('service_id') ?? []).filter(
    (v): v is string => typeof v === 'string',
  );
  const instance = (converted.values.get('instance_id') ?? []).filter(
    (v): v is number => typeof v === 'number',
  );
  const error = converted.values.get('error')?.[0];
  return {
    service,
    instance,
    ...(typeof error === 'boolean' ? { error } : {}),
    ...(converted.minMs === undefined ? {} : { minMs: converted.minMs }),
    ...(converted.maxMs === undefined ? {} : { maxMs: converted.maxMs }),
    unapplied: converted.unapplied,
    empty: converted.empty,
  };
}

/**
 * The summary request of an XLog selection (docs/14 §4.5): the selection's bounds, `kind` and the
 * point filters; null without a selection.
 */
export function toXlogSummaryRequest(
  appId: number,
  xlogQuery: XLogSearchQuery,
): XlogSummaryCall | null {
  if (xlogQuery.sel === undefined) {
    return null;
  }
  const bounds = queryBounds(xlogQuery);
  const points = toXlogPointFilters(xlogQuery.filters);
  const minMs = intersectMin(bounds.minMs, points.minMs);
  const maxMs = intersectMax(bounds.maxMs, points.maxMs);
  const body: XlogSummaryRequest = {
    from: formatInstant(bounds.from),
    to: formatInstant(bounds.to),
    kind: xlogQuery.kind,
  };
  if (minMs !== undefined) body.min_ms = minMs;
  if (maxMs !== undefined) body.max_ms = maxMs;
  if (points.service.length > 0) body.service = points.service;
  if (points.instance.length > 0) body.instance = points.instance;
  if (points.error !== undefined) body.error = points.error;
  const empty = points.empty || (minMs !== undefined && maxMs !== undefined && minMs > maxMs);
  return { appId, body, unapplied: points.unapplied, empty };
}

/** One page of transactions (docs/14 §4.6): rows, and meta with shown/total/next_cursor. */
export async function searchTransactions(
  call: Pick<TransactionSearchCall, 'appId' | 'body'>,
  signal?: AbortSignal,
): Promise<Enveloped<TransactionSearchRow[]>> {
  return unwrap<EnvelopeOf<'/api/v1/apps/{appId}/transactions/search', 'post'>>(
    api().client.POST('/api/v1/apps/{appId}/transactions/search', {
      params: { path: { appId: call.appId } },
      body: call.body,
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/**
 * Server CSV export of the same search (ADR-0010 C-205 (1), DSN-15-06, docs/14 §4.17): up to
 * 10,000 rows from the first row (no cursor), HEAVY bucket (429 + Retry-After: show the hint, no
 * retry loop), audited as EXPORT_CSV. Returns the saved file name.
 */
export async function exportTransactionsCsv(
  call: Pick<TransactionSearchCall, 'appId' | 'body'>,
  env: SaveFileEnvironment = {},
): Promise<string> {
  // The export always starts at the first row and has its own row limit (no cursor, no limit).
  const body: TransactionSearchRequest = { ...call.body };
  delete body.cursor;
  delete body.limit;
  // Loaded on click: the download helper stays out of the initial path (ADR-0010 C-209).
  const { exportCsv } = await import('./exportCsv');
  return exportCsv(
    'post',
    '/api/v1/apps/{appId}/transactions/search',
    { path: { appId: call.appId }, body },
    env,
  );
}

export type XlogSummary = components['schemas']['XlogSummary'];

/** Server summary of a dragged rectangle (docs/14 §4.5): count and the top groups. */
export async function summarizeXlog(
  call: Pick<XlogSummaryCall, 'appId' | 'body'>,
  signal?: AbortSignal,
): Promise<Enveloped<XlogSummary>> {
  return unwrap<EnvelopeOf<'/api/v1/apps/{appId}/xlog/summary', 'post'>>(
    api().client.POST('/api/v1/apps/{appId}/xlog/summary', {
      params: { path: { appId: call.appId } },
      body: call.body,
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}
