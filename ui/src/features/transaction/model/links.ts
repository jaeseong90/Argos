// SPDX-License-Identifier: Apache-2.0
//
// Links of the transaction detail (docs/15 §3.1 routes, §5 "앱·화면 전환", 05 §2.2-5..2.2-7 and
// 2.2-10): the SQL statistics of a sql_id, the external statistics of a host, the error group, the
// trace view, the calling transaction and the detail itself. Statistics links keep the time range
// of the context the detail was opened in: the XLog list's range when the detail came from a list
// (`back`, ADR-0010 C-206), the detail URL's own range when it carries one, and otherwise a window
// around the transaction (±30 minutes, the baseline window of docs/14 §4.6) with the transaction's
// kind, so that the statistics always cover the transaction. 64-bit keys stay strings (C-175).
import {
  buildUrl,
  carriedQuery,
  carriedState,
  defaultState,
  parseInstant,
  TraceQ,
  TxDetailQ,
  XLogQ,
  type CommonState,
  type Kind,
  type TxDetailState,
} from '../../../state/urlState';
import type { BackLink } from '../data/listContext';
import type { TransactionCaller, TransactionDetail } from '../data/transactionApi';

const MINUTE = 60_000;
/** Half width of the fallback window around the transaction (docs/14 §4.6 baseline ±30 min). */
export const CONTEXT_WINDOW_MS = 30 * MINUTE;
/** Half width of the XLog window of the not-found guidance (C-50). */
export const NOT_FOUND_WINDOW_MS = 15 * MINUTE;

/** Where the time range of the links comes from. */
export type RangeSource = 'list' | 'url' | 'transaction';

export interface LinkContext {
  readonly state: Partial<CommonState>;
  readonly source: RangeSource;
}

function searchParams(search: string): URLSearchParams {
  return new URLSearchParams(search.startsWith('?') ? search.slice(1) : search);
}

/** True when the detail URL itself sets a time range (`from`, `to` or `range`). */
export function urlHasRange(search: string): boolean {
  const params = searchParams(search);
  return params.has('from') || params.has('to') || params.has('range');
}

/** The transaction's kind as a URL kind (HTTP, BATCH, MESSAGE), HTTP for an unknown value. */
export function kindOf(detail: TransactionDetail): Kind {
  const kind = detail.kind.toUpperCase();
  return kind === 'BATCH' || kind === 'MESSAGE' ? kind : 'HTTP';
}

/**
 * The time range, kind and environment the links carry: the list's (`back`), else the URL's (with
 * the URL's kind only when it sets one, else the transaction's own kind), else a whole-minute
 * window of ±30 minutes around the transaction with its kind.
 */
export function linkContext(
  detail: TransactionDetail,
  url: TxDetailState,
  back: BackLink | null,
  search: string,
): LinkContext {
  if (back?.xlog !== null && back?.xlog !== undefined) {
    return { state: { ...carriedState(back.xlog.state), live: false }, source: 'list' };
  }
  if (urlHasRange(search)) {
    const kind = searchParams(search).has('kind') ? url.kind : kindOf(detail);
    return { state: { ...carriedState(url), kind }, source: 'url' };
  }
  const start = parseInstant(detail.ts) ?? Date.parse(detail.ts);
  const end = start + Math.max(0, detail.elapsed_ms);
  const from = Math.floor((start - CONTEXT_WINDOW_MS) / MINUTE) * MINUTE;
  const to = Math.ceil((end + CONTEXT_WINDOW_MS) / MINUTE) * MINUTE;
  return {
    state: {
      ...carriedState(url),
      time: { kind: 'absolute', from, to },
      live: false,
      kind: kindOf(detail),
    },
    source: 'transaction',
  };
}

function withQuery(path: string, ctx: LinkContext): string {
  const query = carriedQuery(ctx.state);
  return query === '' ? path : `${path}?${query}`;
}

/** SQL statistics of a statement (`/apps/:appId/stats/sql/:sqlId`, 05 §2.2-5). */
export function sqlStatsHref(appId: number, sqlId: string, ctx: LinkContext): string {
  return withQuery(`/apps/${String(appId)}/stats/sql/${encodeURIComponent(sqlId)}`, ctx);
}

/** External call statistics of a host (`/apps/:appId/stats/external/:hostId`, 05 §2.2-6). */
export function hostStatsHref(appId: number, hostId: string, ctx: LinkContext): string {
  return withQuery(`/apps/${String(appId)}/stats/external/${encodeURIComponent(hostId)}`, ctx);
}

/** The error group (`/apps/:appId/stats/errors/:groupId`, 05 §2.2-7). */
export function errorGroupHref(appId: number, groupId: string, ctx: LinkContext): string {
  return withQuery(`/apps/${String(appId)}/stats/errors/${encodeURIComponent(groupId)}`, ctx);
}

/**
 * The trace view of the transaction's trace (`/traces/:traceId?ts=`, docs/14 §4.7): the
 * transaction's start as the lookup hint, and the carried range.
 */
export function traceHref(traceId: string, ts: string, ctx: LinkContext): string {
  const hint = parseInstant(ts) ?? Date.parse(ts);
  return buildUrl(`/traces/${encodeURIComponent(traceId)}`, TraceQ, {
    ...defaultState(TraceQ),
    ...ctx.state,
    ts: Number.isFinite(hint) && hint > 0 ? hint : undefined,
    window: undefined,
  });
}

/** The links the tabs and the side panel offer for one detail. */
export interface DetailLinks {
  readonly appId: number;
  readonly context: LinkContext;
  readonly sqlStats: (sqlId: string) => string;
  readonly hostStats: (hostId: string) => string;
  readonly errorGroup: (groupId: string) => string;
  /** The trace view, null without a trace id. */
  readonly trace: string | null;
}

/** The links of a detail in its context (the detail's own application). */
export function detailLinks(detail: TransactionDetail, context: LinkContext): DetailLinks {
  const appId = detail.app.app_id;
  return {
    appId,
    context,
    sqlStats: (sqlId) => sqlStatsHref(appId, sqlId, context),
    hostStats: (hostId) => hostStatsHref(appId, hostId, context),
    errorGroup: (groupId) => errorGroupHref(appId, groupId, context),
    trace: detail.trace_id === null ? null : traceHref(detail.trace_id, detail.ts, context),
  };
}

/** The detail route of a transaction (C-166): `/apps/:appId/transactions/:txId`. */
export function transactionPath(appId: number, txId: string): string {
  return `/apps/${String(appId)}/transactions/${encodeURIComponent(txId)}`;
}

/**
 * The canonical link of a detail (05 §2.2-10 "URL 복사"): the app route with `ts` (the C-50
 * lookup hint, so the link finds the transaction even with a skewed agent clock) and the tab when
 * it is not the default. No `back`: the list a viewer came from is not part of a shared link.
 */
export function canonicalHref(
  appId: number,
  txId: string,
  ts: string,
  tab: TxDetailState['tab'] = 'timeline',
): string {
  const hint = parseInstant(ts) ?? Date.parse(ts);
  return buildUrl(transactionPath(appId, txId), TxDetailQ, {
    ...defaultState(TxDetailQ),
    ts: Number.isFinite(hint) && hint > 0 ? hint : undefined,
    tab,
  });
}

/**
 * The calling transaction's detail (ADR-0010 C-207 (2): `caller.tx_id` of the detail response),
 * or null when the server found no calling transaction (only the calling application). Like the
 * trace link it carries the caller's start as the ts hint (C-50) and the range and environment of
 * the context; the kind is left to the caller (it may differ: a batch job calling a service).
 */
export function callerHref(caller: TransactionCaller | null, ctx?: LinkContext): string | null {
  if (caller === null || caller.tx_id === null) {
    return null;
  }
  const hint = caller.ts === null ? Number.NaN : (parseInstant(caller.ts) ?? Date.parse(caller.ts));
  const defaults = defaultState(TxDetailQ);
  return buildUrl(transactionPath(caller.app_id, caller.tx_id), TxDetailQ, {
    ...defaults,
    ...(ctx?.state ?? {}),
    kind: defaults.kind,
    live: false,
    ts: Number.isFinite(hint) && hint > 0 ? hint : undefined,
  });
}

/**
 * The XLog of a window around `centerMs` (the C-50 guidance: open the transaction from the list of
 * its time): `[center − 15 min, center + 15 min)` in whole minutes, kind ALL so a batch or message
 * transaction is in it too, live off, keeping the environment and kiosk keys of the detail URL
 * (docs/15 §5 carried keys).
 */
export function xlogWindowHref(
  appId: number,
  centerMs: number,
  current: Partial<CommonState> = {},
): string {
  const from = Math.floor((centerMs - NOT_FOUND_WINDOW_MS) / MINUTE) * MINUTE;
  const to = Math.ceil((centerMs + NOT_FOUND_WINDOW_MS + 1) / MINUTE) * MINUTE;
  return buildUrl(`/apps/${String(appId)}/xlog`, XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState(current),
    time: { kind: 'absolute', from, to },
    live: false,
    kind: 'ALL',
  });
}

/** The XLog of an application with the carried keys of the detail URL (range, kind, env). */
export function xlogHref(appId: number, current: Partial<CommonState> = {}): string {
  const query = carriedQuery(current);
  const path = `/apps/${String(appId)}/xlog`;
  return query === '' ? path : `${path}?${query}`;
}
