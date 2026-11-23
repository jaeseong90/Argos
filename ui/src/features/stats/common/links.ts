// SPDX-License-Identifier: Apache-2.0
//
// Links of the statistics screens (docs/15 §3.1 routes, §5 carried keys, 05 §2.5): list ↔ detail
// keep the time range, live, kind, environment and kiosk; "related transactions" opens the XLog
// with the view's filter (f.service, f.sql, f.error_group, f.version: the XLog filter vocabulary
// of api/transactionSearch.ts); a chart point opens the XLog around that time (absolute window,
// live off); a related transaction opens its detail with `ts` (the C-50 lookup hint, ADR-0010
// C-206 (1)). 64-bit keys stay decimal strings (C-175) and are percent-encoded in paths.
import type { TimeRange as ChartRange } from '../../../charts/chartModel';
import {
  buildUrl,
  carriedQuery,
  carriedState,
  defaultState,
  XLogQ,
  type CommonState,
  type Filter,
} from '../../../state/urlState';
import { canonicalHref } from '../../transaction/model/links';

/** The carried keys of the current statistics URL (docs/15 §5). */
export type CarriedKeys = Pick<CommonState, 'time' | 'live' | 'kind' | 'env' | 'kiosk'>;

function withQuery(path: string, current: Partial<CommonState>): string {
  const query = carriedQuery(current);
  return query === '' ? path : `${path}?${query}`;
}

function appPath(appId: number, rest: string): string {
  return `/apps/${String(appId)}/${rest}`;
}

/** The services list (`/apps/:appId/stats/services`). */
export function servicesHref(appId: number, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, 'stats/services'), current);
}

/** The SQL list (`/apps/:appId/stats/sql`). */
export function sqlListHref(appId: number, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, 'stats/sql'), current);
}

/** The external calls list (`/apps/:appId/stats/external`). */
export function externalListHref(appId: number, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, 'stats/external'), current);
}

/** The error groups list (`/apps/:appId/stats/errors`). */
export function errorsListHref(appId: number, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, 'stats/errors'), current);
}

/** A service detail (`/apps/:appId/stats/services/:serviceId`). */
export function serviceHref(
  appId: number,
  serviceId: string,
  current: Partial<CommonState>,
): string {
  return withQuery(appPath(appId, `stats/services/${encodeURIComponent(serviceId)}`), current);
}

/** A SQL statement detail (`/apps/:appId/stats/sql/:sqlId`). */
export function sqlHref(appId: number, sqlId: string, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, `stats/sql/${encodeURIComponent(sqlId)}`), current);
}

/** An external host detail (`/apps/:appId/stats/external/:hostId`). */
export function externalHref(appId: number, hostId: string, current: Partial<CommonState>): string {
  return withQuery(appPath(appId, `stats/external/${encodeURIComponent(hostId)}`), current);
}

/** An error group detail (`/apps/:appId/stats/errors/:groupId`). */
export function errorGroupHref(
  appId: number,
  groupId: string,
  current: Partial<CommonState>,
): string {
  return withQuery(appPath(appId, `stats/errors/${encodeURIComponent(groupId)}`), current);
}

/**
 * The XLog of an application with filters (05 §2.5-2 "XLog 필터 적용 상태로 이동"), keeping the
 * range, live, kind, environment and kiosk of the statistics URL. Pass xlogFilters() output.
 */
export function xlogHref(
  appId: number,
  current: Partial<CommonState>,
  filters: readonly Filter[],
): string {
  return buildUrl(appPath(appId, 'xlog'), XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState(current),
    filters: [...filters],
  });
}

/**
 * The XLog around a chart point (05 §2.0-5: ± range/60, at least 1 minute, computed by the chart's
 * click menu) as an absolute range with live off, keeping kind and environment and the filters.
 */
export function xlogAtRangeHref(
  appId: number,
  window: ChartRange,
  current: Pick<CommonState, 'kind' | 'env'>,
  filters: readonly Filter[],
): string {
  return buildUrl(appPath(appId, 'xlog'), XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState({ kind: current.kind, env: current.env }),
    time: { kind: 'absolute', from: Math.floor(window.from), to: Math.ceil(window.to) },
    live: false,
    filters: [...filters],
  });
}

/**
 * The detail of a related transaction with its start as the `ts` hint (C-50, ADR-0010 C-206 (1)).
 * No `back`: the statistics screen is not an XLog list to step through.
 */
export function transactionHref(appId: number, txId: string, ts: string): string {
  return canonicalHref(appId, txId, ts);
}
