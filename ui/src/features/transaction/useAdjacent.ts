// SPDX-License-Identifier: Apache-2.0
//
// Previous / next transaction of the list the detail was opened from (05 §2.1-9, docs/15 §9.3,
// ADR-0010 C-206 (2)): the order of the XLog list behind `back`. The list WO-33 showed is used at
// once when it is still in memory (listNeighbours of features/xlog/list/listContext.ts); otherwise
// (a reload, another tab, a shared link with `back`) the same list is asked again with the one
// conversion of src/api/transactionSearch.ts (`sel`, `f`, sort and kind of `back`), and the
// neighbours are read from its order. Without a usable `back`, or when the transaction is not in
// that list, previous/next are unavailable and the reason is shown. Ids stay strings (C-175).
import { useQuery } from '@tanstack/react-query';

import {
  searchTransactions,
  toTransactionSearchRequest,
  type TransactionSearchCall,
} from '../../api/transactionSearch';
import { parseInstant } from '../../state/urlState';
import { listNeighbours } from '../xlog/list/listContext';

import { adjacentUnavailable, type BackLink } from './data/listContext';

/** A neighbour in the list: its id and start time (the next detail's ts hint). */
export interface Neighbour {
  readonly txId: string;
  /** Start time in epoch ms, or null when the list did not give a readable one. */
  readonly ts: number | null;
}

/** Why previous/next cannot be offered. */
export type AdjacentReason =
  /** No `back` (opened from a link without a list). */
  | 'no_list'
  /** `back` is not the XLog list. */
  | 'not_xlog'
  /** The list's conditions can match nothing. */
  | 'list_empty'
  /** The transaction is not among the list's rows (first 1,000). */
  | 'not_in_list'
  /** The list could not be loaded. */
  | 'list_error';

export type AdjacentState =
  | { readonly status: 'unavailable'; readonly reason: AdjacentReason }
  | { readonly status: 'pending' }
  | {
      readonly status: 'ready';
      readonly previous: Neighbour | null;
      readonly next: Neighbour | null;
      /** 0-based position in the list. */
      readonly index: number;
      /** Rows known (the page) and all matches of the search (meta.total), when known. */
      readonly rows: number;
      readonly total: number | null;
      readonly source: 'memory' | 'search';
    };

function tsOf(value: number | string): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }
  return parseInstant(value) ?? (Number.isFinite(Date.parse(value)) ? Date.parse(value) : null);
}

/** Neighbours of `txId` in ordered rows. */
export function neighboursIn(
  rows: readonly { readonly tx_id: string; readonly ts: string }[],
  txId: string,
): { index: number; previous: Neighbour | null; next: Neighbour | null } {
  const index = rows.findIndex((row) => row.tx_id === txId);
  if (index < 0) {
    return { index, previous: null, next: null };
  }
  const before = rows[index - 1];
  const after = rows[index + 1];
  return {
    index,
    previous: before === undefined ? null : { txId: before.tx_id, ts: tsOf(before.ts) },
    next: after === undefined ? null : { txId: after.tx_id, ts: tsOf(after.ts) },
  };
}

/** The previous/next state of a detail opened with `back`. */
export function useAdjacent(back: BackLink | null, txId: string): AdjacentState {
  const unavailable = adjacentUnavailable(back);
  const href = back?.href ?? null;
  const memory = href === null ? null : listNeighbours(href, txId);
  const fromMemory = memory !== null && memory.index >= 0 && !memory.moreAfter;
  // The search of `back`. The query is keyed by the `back` location only: a relative range resolves
  // against the time of the fetch, which happens once per location (staleTime Infinity).
  const xlog = back?.xlog ?? null;
  const call: TransactionSearchCall | null =
    xlog === null ? null : toTransactionSearchRequest(xlog.appId, xlog.state);
  const enabled = unavailable === null && !fromMemory && call !== null && !call.empty;
  const query = useQuery({
    queryKey: ['transactions', 'adjacent', href],
    queryFn: ({ signal }) => {
      if (call === null) {
        throw new Error('no list to search');
      }
      return searchTransactions(call, signal);
    },
    enabled,
    staleTime: Infinity,
  });
  if (unavailable !== null) {
    return { status: 'unavailable', reason: unavailable };
  }
  if (fromMemory) {
    return {
      status: 'ready',
      previous:
        memory.previous === null ? null : { txId: memory.previous.txId, ts: memory.previous.ts },
      next: memory.next === null ? null : { txId: memory.next.txId, ts: memory.next.ts },
      index: memory.index,
      rows: memory.index + 1 + (memory.next === null ? 0 : 1),
      total: null,
      source: 'memory',
    };
  }
  if (call === null || call.empty) {
    return { status: 'unavailable', reason: 'list_empty' };
  }
  if (query.isError) {
    return { status: 'unavailable', reason: 'list_error' };
  }
  if (query.data === undefined) {
    return { status: 'pending' };
  }
  const rows = query.data.data;
  const found = neighboursIn(rows, txId);
  if (found.index < 0) {
    return { status: 'unavailable', reason: 'not_in_list' };
  }
  const total = query.data.meta.total;
  return {
    status: 'ready',
    previous: found.previous,
    next: found.next,
    index: found.index,
    rows: rows.length,
    total: typeof total === 'number' ? total : null,
    source: 'search',
  };
}
