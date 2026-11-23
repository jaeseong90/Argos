// SPDX-License-Identifier: Apache-2.0
//
// Server state of the transaction detail (DSN-15-01: TanStack Query): the detail with its ?ts hint
// (ADR-0007 C-50) as a typed state (invalid id / pending / not found / error / success), and the
// same-service baseline. 64-bit ids stay strings in keys and requests (C-175); collected strings
// keep their Untrusted brand (render through SafeText / CodeBlock / SafeLink only).
import { useQuery, type QueryClient } from '@tanstack/react-query';

import type { Enveloped, ResponseMeta } from '../../../api/client';
import { errorInfo, type ErrorInfo } from '../../../api/errors';

import {
  fetchTransaction,
  fetchTransactionBaseline,
  isTxIdForm,
  type TransactionBaseline,
  type TransactionDetail,
} from './transactionApi';
import {
  normalizeTimeHint,
  transactionNotFoundReason,
  type TimeHint,
  type TransactionNotFoundReason,
} from './transactionModel';

/** Query keys of the detail screen: [resource, part, txId (string), ts hint (ISO) or null]. */
export const transactionKeys = {
  all: () => ['transactions'] as const,
  detail: (txId: string, ts: string | null) => ['transactions', 'detail', txId, ts] as const,
  baseline: (txId: string, ts: string | null) => ['transactions', 'baseline', txId, ts] as const,
} as const;

/** The detail as the screen renders it. */
export type TransactionState =
  /** The id is not a decimal tx_id: nothing was requested. */
  | { readonly status: 'invalid' }
  | { readonly status: 'pending' }
  | {
      readonly status: 'not_found';
      readonly reason: TransactionNotFoundReason;
      /** The request carried a ts hint (the C-50 advice "open it with ?ts" then does not apply). */
      readonly hinted: boolean;
      readonly requestId: string | null;
    }
  | { readonly status: 'error'; readonly error: unknown; readonly info: ErrorInfo }
  | {
      readonly status: 'success';
      readonly detail: TransactionDetail;
      readonly meta: ResponseMeta;
    };

export interface UseTransactionResult {
  readonly state: TransactionState;
  /** A request is running (first load or refetch). */
  readonly fetching: boolean;
  /** The ts hint sent (ISO-8601 UTC) or undefined. */
  readonly hint: string | undefined;
  readonly refetch: () => void;
}

/**
 * Fetches GET /transactions/{txId}?ts= (docs/14 §4.6). `ts` is the hint from the URL (epoch ms) or
 * an API value (ISO). A 404 becomes `not_found` (reason `clock_skew_possible` for C-50); other
 * failures `error` with the ErrorInfo for ErrorState. A captured profile whose steps may still
 * arrive (`steps_pending`) is refetched when asked; any other detail is final (no refetch).
 */
export function useTransaction(
  txId: string,
  ts?: TimeHint,
  options: { readonly enabled?: boolean } = {},
): UseTransactionResult {
  const valid = isTxIdForm(txId);
  const hint = normalizeTimeHint(ts);
  const query = useQuery({
    queryKey: transactionKeys.detail(txId, hint ?? null),
    queryFn: ({ signal }) => fetchTransaction(txId, hint, signal),
    enabled: valid && options.enabled !== false,
    staleTime: (q) => (q.state.data?.data.profile.steps_pending === true ? 0 : Infinity),
  });
  const refetch = (): void => {
    void query.refetch();
  };
  let state: TransactionState;
  if (!valid) {
    state = { status: 'invalid' };
  } else if (query.data !== undefined) {
    // A failed refetch (steps_pending) keeps the detail already shown.
    state = { status: 'success', detail: query.data.data, meta: query.data.meta };
  } else if (query.isError) {
    const reason = transactionNotFoundReason(query.error);
    const info = errorInfo(query.error);
    state =
      reason === null
        ? { status: 'error', error: query.error, info }
        : {
            status: 'not_found',
            reason,
            hinted: hint !== undefined,
            requestId: info.requestId ?? null,
          };
  } else {
    state = { status: 'pending' };
  }
  return { state, fetching: query.isFetching, hint, refetch };
}

/**
 * Stores a detail fetched elsewhere (the /tx/:txId alias) under the key the detail route asks for,
 * so the redirect does not fetch it again (one request, one SENSITIVE_VIEW audit row for full-mode
 * parameters).
 */
export function primeTransaction(
  client: QueryClient,
  txId: string,
  result: Enveloped<TransactionDetail>,
): void {
  const hint = normalizeTimeHint(result.data.ts);
  if (hint !== undefined) {
    client.setQueryData(transactionKeys.detail(txId, hint), result);
  }
}

/** The baseline as the screen renders it. */
export type BaselineState =
  | { readonly status: 'idle' }
  | { readonly status: 'pending' }
  | { readonly status: 'error'; readonly error: unknown; readonly info: ErrorInfo }
  | {
      readonly status: 'success';
      readonly baseline: TransactionBaseline;
      readonly meta: ResponseMeta;
    };

export interface UseTransactionBaselineResult {
  readonly state: BaselineState;
  readonly refetch: () => void;
}

/**
 * Fetches GET /transactions/{txId}/baseline (docs/14 §4.6): same-service p50/p95 within ±30 minutes,
 * the percentile of this transaction (`percentile_of_this`, null when not computable) and a normal
 * sample (`sample_normal_tx_id` + `sample_normal_ts`, the ts hint of the compare view). Pass the
 * detail's own `ts` so the server finds the transaction by its hint; `enabled: false` waits.
 */
export function useTransactionBaseline(
  txId: string,
  ts?: TimeHint,
  options: { readonly enabled?: boolean } = {},
): UseTransactionBaselineResult {
  const valid = isTxIdForm(txId);
  const hint = normalizeTimeHint(ts);
  const enabled = valid && options.enabled !== false;
  const query = useQuery({
    queryKey: transactionKeys.baseline(txId, hint ?? null),
    queryFn: ({ signal }) => fetchTransactionBaseline(txId, hint, signal),
    enabled,
    staleTime: 60_000,
  });
  const refetch = (): void => {
    void query.refetch();
  };
  let state: BaselineState;
  if (!enabled) {
    state = { status: 'idle' };
  } else if (query.isError) {
    state = { status: 'error', error: query.error, info: errorInfo(query.error) };
  } else if (query.data === undefined) {
    state = { status: 'pending' };
  } else {
    state = { status: 'success', baseline: query.data.data, meta: query.data.meta };
  }
  return { state, refetch };
}
