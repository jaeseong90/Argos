// SPDX-License-Identifier: Apache-2.0
//
// Links from the XLog screen to the transaction detail (ADR-0010 C-206 (1), docs/15 §3.1, §9.3):
// the canonical route /apps/:appId/transactions/:txId carries `ts` (ISO-8601 UTC, the ADR-0007 C-50
// lookup hint) and `back` (the XLog path and query the user came from, so that the detail can go
// back to the list and offer previous/next by re-querying it, C-206 (2)). The whole link stays
// within the 2 KB URL write limit (docs/14 §6): when the full `back` does not fit, a shorter one
// without the view-only keys is tried, and the link goes without `back` as the last resort (the
// detail then disables previous/next and says why).
import {
  encodeQueryComponent,
  fitsWriteLimit,
  formatInstant,
  safeNextPath,
} from '../../../state/urlState';

/** XLog query keys that only change the view, dropped first when `back` is too long. */
const VIEW_ONLY_KEYS: readonly string[] = ['cols', 'color', 'y', 'live', 'kiosk', 'alert'];

/** The detail route of a transaction (C-166); txId is a decimal string (C-175). */
export function transactionPath(appId: number, txId: string): string {
  return `/apps/${String(appId)}/transactions/${encodeURIComponent(txId)}`;
}

/** `back` without the view-only keys, or null when it has none of them. */
function withoutViewKeys(back: string): string | null {
  const q = back.indexOf('?');
  if (q < 0) {
    return null;
  }
  const params = new URLSearchParams(back.slice(q + 1));
  let dropped = false;
  for (const key of VIEW_ONLY_KEYS) {
    if (params.has(key)) {
      params.delete(key);
      dropped = true;
    }
  }
  if (!dropped) {
    return null;
  }
  const query = [...params]
    .map(([key, value]) => `${encodeQueryComponent(key)}=${encodeQueryComponent(value)}`)
    .join('&');
  const path = back.slice(0, q);
  return query === '' ? path : `${path}?${query}`;
}

function linkOf(path: string, ts: string, back: string | null): string {
  const pairs = [`ts=${encodeQueryComponent(ts)}`];
  if (back !== null) {
    pairs.push(`back=${encodeQueryComponent(back)}`);
  }
  return `${path}?${pairs.join('&')}`;
}

/**
 * The detail link of a transaction started at `ts` (epoch ms or an ISO instant), opened from the
 * XLog location `back` (pathname + search). The result always fits the 2 KB write limit.
 */
export function transactionDetailUrl(
  appId: number,
  txId: string,
  ts: number | string,
  back: string | null,
): string {
  const ms = typeof ts === 'number' ? ts : Date.parse(ts);
  const path = transactionPath(appId, txId);
  const hint = Number.isFinite(ms) ? formatInstant(ms) : null;
  if (hint === null) {
    return path;
  }
  const candidates: (string | null)[] = [];
  const safe = back === null ? null : safeNextPath(back);
  if (safe !== null) {
    candidates.push(safe);
    const shorter = withoutViewKeys(safe);
    if (shorter !== null) {
      candidates.push(shorter);
    }
  }
  candidates.push(null);
  for (const candidate of candidates) {
    const url = linkOf(path, hint, candidate);
    if (fitsWriteLimit(url)) {
      return url;
    }
  }
  return path;
}
