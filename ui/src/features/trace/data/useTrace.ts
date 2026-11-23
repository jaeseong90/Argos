// SPDX-License-Identifier: Apache-2.0
//
// Server state of the trace view (DSN-15-01: TanStack Query): GET /traces/{traceId}?ts=&window= as
// a typed state (invalid id / pending / not found / error / success). The success state keeps the
// whole envelope, because the trace JSON export saves the response as received (ADR-0010 C-207
// (1), saveTraceJson) and the partial banner reads meta.truncated / meta.retention_exceeded.
import { useQuery } from '@tanstack/react-query';

import type { Enveloped, ResponseMeta } from '../../../api/client';
import { errorInfo, type ErrorInfo } from '../../../api/errors';
import { normalizeTimeHint, type TimeHint } from '../../transaction/data/transactionModel';

import { fetchTrace, isTraceIdForm, type Trace, type TraceWindow } from './traceApi';
import { traceNotFoundReason, type TraceNotFoundReason } from './traceModel';

/** Query keys: [resource, trace id (lower case), ts hint (ISO) or null, window or null]. */
export const traceKeys = {
  all: () => ['traces'] as const,
  detail: (traceId: string, ts: string | null, window: TraceWindow | null) =>
    ['traces', 'detail', traceId.toLowerCase(), ts, window] as const,
} as const;

export type TraceState =
  /** Not a 32-hex trace id: nothing was requested. */
  | { readonly status: 'invalid' }
  | { readonly status: 'pending' }
  | {
      readonly status: 'not_found';
      readonly reason: TraceNotFoundReason;
      readonly requestId: string | null;
    }
  | { readonly status: 'error'; readonly error: unknown; readonly info: ErrorInfo }
  | {
      readonly status: 'success';
      readonly trace: Trace;
      readonly meta: ResponseMeta;
      /** The response as received (for saveTraceJson). */
      readonly response: Enveloped<Trace>;
    };

export interface UseTraceResult {
  readonly state: TraceState;
  readonly fetching: boolean;
  /** "Query again" of the "not collected yet" missing nodes (docs/15 §9.5). */
  readonly refetch: () => void;
}

/**
 * Fetches GET /traces/{traceId} (docs/14 §4.7) with the `ts` hint (URL epoch ms or an API ISO
 * value) and the `window` of the URL. A 404 becomes `not_found` (reason `not_in_window`); a 429 of
 * the 30d window is an `error` whose ErrorInfo carries Retry-After (no automatic retry, C-176).
 */
export function useTrace(
  traceId: string,
  ts?: TimeHint,
  window?: TraceWindow | null,
  options: { readonly enabled?: boolean } = {},
): UseTraceResult {
  const valid = isTraceIdForm(traceId);
  const hint = normalizeTimeHint(ts);
  const query = useQuery({
    queryKey: traceKeys.detail(traceId, hint ?? null, window ?? null),
    queryFn: ({ signal }) =>
      fetchTrace(
        traceId,
        {
          ...(hint === undefined ? {} : { ts: hint }),
          ...(window === undefined || window === null ? {} : { window }),
        },
        signal,
      ),
    enabled: valid && options.enabled !== false,
    // Late callee transactions (not_yet_collected) appear on "query again", not by themselves.
    staleTime: 30_000,
  });
  const refetch = (): void => {
    void query.refetch();
  };
  let state: TraceState;
  if (!valid) {
    state = { status: 'invalid' };
  } else if (query.data !== undefined) {
    state = {
      status: 'success',
      trace: query.data.data,
      meta: query.data.meta,
      response: query.data,
    };
  } else if (query.isError) {
    const reason = traceNotFoundReason(query.error);
    const info = errorInfo(query.error);
    state =
      reason === null
        ? { status: 'error', error: query.error, info }
        : { status: 'not_found', reason, requestId: info.requestId ?? null };
  } else {
    state = { status: 'pending' };
  }
  return { state, fetching: query.isFetching, refetch };
}
