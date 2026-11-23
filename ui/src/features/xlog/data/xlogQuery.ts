// SPDX-License-Identifier: Apache-2.0
//
// XLog URL state (state/urlState.ts XLogQ, docs/14 §6 DSN-14-17, ADR-0010 C-166, C-189) → the
// parameters of `GET /apps/{appId}/xlog` (docs/14 §4.5) and of the `xlog` WebSocket topic
// (docs/14 §5.2). The f= vocabulary and what the points endpoint can apply are defined once in
// api/transactionSearch.ts (toXlogPointFilters: service[], instance[], error, min_ms/max_ms); the
// other filters apply to the list only and are listed as such on the screen, never emulated on the
// client (C-202 (1)).
import type { XlogPointFilters } from '../../../api/transactionSearch';
import type { Kind } from '../../../state/urlState';

/** Most points of one XLog response (docs/14 §4.5 `limit ≤ 100000`). */
export const XLOG_POINT_LIMIT = 100_000;

/** Query parameters of `GET /apps/{appId}/xlog` (docs/14 §4.5), as the generated client types. */
export interface XLogRequestQuery {
  from: string;
  to: string;
  kind?: string;
  service?: string[];
  instance?: number[];
  error?: boolean;
  min_ms?: number;
  max_ms?: number;
  limit?: number;
  color_by?: string;
}

/**
 * Request parameters for the absolute range [from, to) (epoch ms). `color_by` is not sent: it is a
 * hint the server only validates (docs/14 §4.5), and changing the colour must not reload 100,000
 * points.
 */
export function xlogRequest(
  range: { readonly from: number; readonly to: number },
  kind: Kind,
  filters: XlogPointFilters,
): XLogRequestQuery {
  const query: XLogRequestQuery = {
    from: new Date(range.from).toISOString(),
    to: new Date(range.to).toISOString(),
    limit: XLOG_POINT_LIMIT,
  };
  if (kind !== 'HTTP') {
    query.kind = kind;
  }
  if (filters.service.length > 0) {
    query.service = [...filters.service];
  }
  if (filters.instance.length > 0) {
    query.instance = [...filters.instance];
  }
  if (filters.error !== undefined) {
    query.error = filters.error;
  }
  if (filters.minMs !== undefined) {
    query.min_ms = filters.minMs;
  }
  if (filters.maxMs !== undefined) {
    query.max_ms = filters.maxMs;
  }
  return query;
}

/**
 * Subscription parameters of the `xlog` topic: exactly the keys of docs/14 §5.2
 * `{app, env, kind, service[], instance[]}`. The topic has no error or response time parameter, so
 * those scatter filters are applied to the received batches on the client
 * (live/XLogLiveController.ts `livePointAccepts`, the same meaning as the points API's `error`,
 * `min_ms` and `max_ms`).
 */
export function xlogTopicParams(
  appId: number,
  env: string | undefined,
  kind: Kind,
  filters: XlogPointFilters,
): Record<string, string | number | boolean | readonly (string | number)[]> {
  const params: Record<string, string | number | boolean | readonly (string | number)[]> = {
    app: appId,
    kind,
    service: [...filters.service],
    instance: [...filters.instance],
  };
  if (env !== undefined) {
    params.env = env;
  }
  return params;
}

/** A stable key of the request (the data hooks reload when it changes). */
export function requestKey(appId: number, query: XLogRequestQuery): string {
  return JSON.stringify([
    appId,
    query.from,
    query.to,
    query.kind ?? 'HTTP',
    query.service ?? [],
    query.instance ?? [],
    query.error ?? null,
    query.min_ms ?? null,
    query.max_ms ?? null,
  ]);
}
