// SPDX-License-Identifier: Apache-2.0
//
// The list a detail was opened from (ADR-0010 C-206): detail links carry `back`, the XLog path and
// query they came from. Back-to-list returns to it; previous/next re-query that list
// (`transactions_search` with its `sel`, `f` and sort, through toTransactionSearchRequest of
// src/api/transactionSearch.ts, owned by WO-33). This module only reads `back`: a same-origin path,
// and when it is an XLog URL its state parsed with WO-30's XLogQ schema.
import { parseUrl, safeNextPath, XLogQ, type XLogState } from '../../../state/urlState';

/** `/apps/<appId>/xlog` (docs/15 §3.1, C-166). */
const XLOG_PATH = /^\/apps\/([1-9]\d{0,9})\/xlog$/;

export interface XLogListContext {
  readonly appId: number;
  /** The XLog URL state (invalid items fall back to their defaults, as on the XLog screen). */
  readonly state: XLogState;
  /** Items of `back` that did not parse (they fell back to defaults). */
  readonly issueCount: number;
}

export interface BackLink {
  /** The path and query to return to (Back to list). */
  readonly href: string;
  /** The XLog list context, or null when `back` is not an XLog URL. */
  readonly xlog: XLogListContext | null;
}

/**
 * Reads the `back` key of the detail URL: null when it is absent or not a same-origin path
 * (safeNextPath rules: `^/(?!/)`, no backslash or control character, at most 2 KB).
 */
export function parseBack(back: string | null | undefined): BackLink | null {
  const href = safeNextPath(back);
  if (href === null) {
    return null;
  }
  const queryAt = href.indexOf('?');
  const path = queryAt < 0 ? href : href.slice(0, queryAt);
  const search = queryAt < 0 ? '' : href.slice(queryAt + 1);
  const match = XLOG_PATH.exec(path.split('#')[0] ?? path);
  if (match === null) {
    return { href, xlog: null };
  }
  const parsed = parseUrl(XLogQ, search.split('#')[0] ?? search);
  return {
    href,
    xlog: { appId: Number(match[1]), state: parsed.state, issueCount: parsed.issues.length },
  };
}

/**
 * Why previous/next cannot be offered (the buttons are disabled and say so, C-206 (2)):
 * `no_list` (no usable `back`), `not_xlog` (opened from a page that is not the XLog list, so there is
 * no list query to repeat). Whether an XLog URL without `sel` still has a list is WO-33's
 * toTransactionSearchRequest's answer, not decided here.
 */
export type AdjacentUnavailable = 'no_list' | 'not_xlog';

/** The reason previous/next is unavailable for a `back` value, or null when a list can be re-queried. */
export function adjacentUnavailable(back: BackLink | null): AdjacentUnavailable | null {
  if (back === null) {
    return 'no_list';
  }
  return back.xlog === null ? 'not_xlog' : null;
}
