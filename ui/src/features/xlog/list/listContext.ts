// SPDX-License-Identifier: Apache-2.0
//
// List context for the detail's previous/next (05 §2.1-9, docs/15 §9.3, ADR-0010 C-206 (2)): when
// a row of the XLog list opens the detail, the list remembers the order it showed (transaction ids
// with their start times), the query behind it and its paging state, keyed by the `back` location
// that the detail link carries. The detail (WO-34) can take its neighbours from here at once and
// falls back to re-querying `back` with toTransactionSearchRequest when the entry is missing (a
// reload, another tab, a link from elsewhere): the URL stays the source of truth.
//
// The store is memory only (no browser storage: transaction ids are not preferences, SEC-40) and
// bounded: at most MAX_ENTRIES lists of at most SEARCH_PAGE_LIMIT rows (docs/15 §14 tab memory).
import { SEARCH_PAGE_LIMIT, type XLogSearchQuery } from '../../../api/transactionSearch';

/** Lists kept (least recently saved dropped first). */
export const MAX_ENTRIES = 8;

/** One row of a remembered list. */
export interface ListContextRow {
  /** Decimal string (C-175). */
  readonly txId: string;
  /** Start time, epoch ms (the detail link's `ts`). */
  readonly ts: number;
}

export interface ListContext {
  /** The XLog location (pathname + search) the list was shown at: the detail's `back`. */
  readonly back: string;
  readonly appId: number;
  /** The query the rows answer (sel, filters, sort, kind, time). */
  readonly query: XLogSearchQuery;
  /** Rows in list order (server order: elapsed_ms desc or ts desc). */
  readonly rows: readonly ListContextRow[];
  /** meta.total of the search (all matches, not only the rows). */
  readonly total: number | null;
  /** meta.next_cursor of the page, for the row after the last one. */
  readonly nextCursor: string | null;
  readonly savedAt: number;
}

export interface Neighbours {
  /** Position of the transaction in the list (0-based), or -1 when it is not in it. */
  readonly index: number;
  readonly previous: ListContextRow | null;
  readonly next: ListContextRow | null;
  /** True when the list ends here but the server has more rows (use `nextCursor`). */
  readonly moreAfter: boolean;
}

const entries = new Map<string, ListContext>();

/** Remembers a list (replacing an older one of the same `back`). */
export function saveListContext(context: Omit<ListContext, 'savedAt'>, now = Date.now()): void {
  const rows = context.rows.slice(0, SEARCH_PAGE_LIMIT).map((r) => ({ txId: r.txId, ts: r.ts }));
  entries.delete(context.back);
  entries.set(context.back, { ...context, rows, savedAt: now });
  while (entries.size > MAX_ENTRIES) {
    const oldest = entries.keys().next().value;
    if (oldest === undefined) {
      break;
    }
    entries.delete(oldest);
  }
}

/** The list remembered for a `back` location, or null. */
export function getListContext(back: string | null | undefined): ListContext | null {
  if (back === null || back === undefined) {
    return null;
  }
  return entries.get(back) ?? null;
}

/** The neighbours of a transaction in the list remembered for `back`, or null without one. */
export function listNeighbours(back: string | null | undefined, txId: string): Neighbours | null {
  const context = getListContext(back);
  if (context === null) {
    return null;
  }
  const index = context.rows.findIndex((row) => row.txId === txId);
  if (index < 0) {
    return { index, previous: null, next: null, moreAfter: false };
  }
  const isLast = index === context.rows.length - 1;
  return {
    index,
    previous: index > 0 ? (context.rows[index - 1] ?? null) : null,
    next: isLast ? null : (context.rows[index + 1] ?? null),
    moreAfter: isLast && context.nextCursor !== null,
  };
}

/** Forgets every list (tests, sign-out). */
export function clearListContexts(): void {
  entries.clear();
}

/** Number of lists held (tests). */
export function listContextCount(): number {
  return entries.size;
}
