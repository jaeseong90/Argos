// SPDX-License-Identifier: Apache-2.0
//
// List preview of a new XLog selection (ADR-0003 C-36, ADR-0010 C-204 "요약 먼저·목록 점진 렌더"):
// while the server search of the selection is on its way, the list shows the first rows from the
// chart's own points, then replaces them with the server rows. The points are the XLog response
// (docs/14 §4.5) and the order is the server search's (docs/14 §4.6, the ORDER BY of
// server/src/main/resources/sql/transactions/search.*.sql): elapsed_ms, ts, tx_id descending, or
// ts, tx_id descending; tx_id compares as an unsigned 64-bit number.
//
// The preview shows only rows that are the server's first rows (exactPreview):
//   - unsampled points (meta.shown == meta.total, not truncated) are every transaction of the
//     range, so the first rows of the selection are the server's in both orders;
//   - sampled points (A-5) keep every error and every slow point (elapsed_ms >= the response's
//     meta.slow_threshold_ms, one threshold per application) but thin the normal points below the
//     threshold, so in the response time order only the leading rows at or above the threshold are
//     exact: a normal point the sample left out can be the server's next row. Without such rows (no
//     slow point in the selection) there is no preview, and the time order never previews;
//   - meta.truncated says that points satisfying the rule were left out (slow points too): no
//     preview.
// The preview knows the point columns only (time, service, response time, instance, kind,
// transaction id, error status); the other columns are pending until the server rows arrive and
// render as a pending mark, never as the missing dash (T-UI-007).
import type { SearchSort } from '../../../api/transactionSearch';
import { untrusted } from '../../../api/untrusted';
import type { Selection } from '../../../state/urlState';
import {
  KIND_BATCH,
  KIND_HTTP,
  KIND_MESSAGE,
  STATUS_ERROR,
  type XLogBuffer,
} from '../../../xlog/XLogBuffer';
import { formatU64 } from '../data/u64';
import type { SummaryDictionary } from '../summary/summaryModel';

import type { TxRow } from './columns';

/** Rows of a preview: one list viewport (18 dense rows) and a little more. */
export const PREVIEW_ROWS = 20;

/** List columns the points carry; every other column is pending in a preview. */
export const PREVIEW_COLUMNS: ReadonlySet<string> = new Set([
  'ts',
  'service',
  'elapsed_ms',
  'instance',
  'kind',
  'tx_id',
]);

function kindName(kind: number): string {
  switch (kind) {
    case KIND_HTTP:
      return 'HTTP';
    case KIND_BATCH:
      return 'BATCH';
    case KIND_MESSAGE:
      return 'MESSAGE';
    default:
      return '';
  }
}

/**
 * True when `a` comes before `b` in the server's list order (docs/14 §4.6): `elapsed_ms:desc` is
 * response time, then start time, then transaction id descending; `ts:desc` is start time, then
 * transaction id descending. The transaction id compares as an unsigned 64-bit number (high word,
 * then low word; both Uint32 columns). Equal ids (never two points of one transaction) fall back to
 * the higher physical index, a stable order.
 */
function before(buffer: XLogBuffer, sort: SearchSort, a: number, b: number): boolean {
  if (sort !== 'ts:desc') {
    const ea = buffer.elapsed[a] ?? 0;
    const eb = buffer.elapsed[b] ?? 0;
    if (ea !== eb) {
      return ea > eb;
    }
  }
  const ta = buffer.ts[a] ?? 0;
  const tb = buffer.ts[b] ?? 0;
  if (ta !== tb) {
    return ta > tb;
  }
  const ha = buffer.txIdHi[a] ?? 0;
  const hb = buffer.txIdHi[b] ?? 0;
  if (ha !== hb) {
    return ha > hb;
  }
  const la = buffer.txIdLo[a] ?? 0;
  const lb = buffer.txIdLo[b] ?? 0;
  return la !== lb ? la > lb : a > b;
}

/** What the preview needs to know about the points behind it (docs/14 §2.5, §4.5 `meta`). */
export interface PreviewSampling {
  /** meta.shown < meta.total: the server thinned the normal points (A-5). */
  readonly sampled: boolean;
  /**
   * meta.truncated: points satisfying the rule were left out, slow points included. Undefined when
   * unknown: then a sampled response does not preview.
   */
  readonly truncated?: boolean | undefined;
  /**
   * meta.slow_threshold_ms of the points response (the application's threshold the sample keeps
   * every point at or above). Null or undefined when unknown: then a sampled response does not
   * preview.
   */
  readonly slowThresholdMs?: number | null | undefined;
}

/** The slow threshold of a sampled response when its preview can be exact, otherwise null. */
function sampledThreshold(sort: SearchSort, sampling: PreviewSampling): number | null {
  const threshold = sampling.slowThresholdMs;
  return sort === 'elapsed_ms:desc' &&
    sampling.truncated === false &&
    typeof threshold === 'number' &&
    Number.isFinite(threshold)
    ? threshold
    : null;
}

/**
 * False when no preview row can be exact for these points and this order (truncated points, a
 * sampled response in the time order or with unknown sampling facts): the list then skips the
 * preview without scanning the points.
 */
export function canPreview(sort: SearchSort, sampling: PreviewSampling): boolean {
  if (sampling.truncated === true) {
    return false;
  }
  return !sampling.sampled || sampledThreshold(sort, sampling) !== null;
}

/**
 * The part of the preview rows (in the list order) that are exactly the server's first rows (see
 * the head of this file): all of them for unsampled points; for sampled points only the leading
 * rows of the response time order at or above the slow threshold; none for truncated points, for
 * sampled points in the time order, or when the sampling facts are unknown.
 */
export function exactPreview<R extends { readonly elapsed_ms: number }>(
  rows: readonly R[],
  sort: SearchSort,
  sampling: PreviewSampling,
): R[] {
  if (!canPreview(sort, sampling)) {
    return [];
  }
  if (!sampling.sampled) {
    // Unsampled points are every transaction of the range (truncated implies sampled: shown is
    // then the limit, below the points that matched).
    return [...rows];
  }
  const threshold = sampledThreshold(sort, sampling) ?? Number.POSITIVE_INFINITY;
  const exact: R[] = [];
  for (const row of rows) {
    if (row.elapsed_ms < threshold) {
      break;
    }
    exact.push(row);
  }
  return exact;
}

/**
 * Physical indices of the first `limit` points of the selection in the list order: one pass over
 * the buffer with a sorted top-`limit` array (O(points × limit) worst case, O(points) typically).
 */
export function previewIndices(
  buffer: XLogBuffer,
  sel: Selection,
  sort: SearchSort,
  limit: number = PREVIEW_ROWS,
): number[] {
  const top: number[] = [];
  if (limit <= 0) {
    return top;
  }
  const { ts, elapsed } = buffer;
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    const t = ts[p] ?? 0;
    const e = elapsed[p] ?? 0;
    if (t < sel.tsFrom || t > sel.tsTo || e < sel.msMin || e > sel.msMax) {
      continue;
    }
    const last = top[top.length - 1];
    if (top.length === limit && last !== undefined && !before(buffer, sort, p, last)) {
      continue;
    }
    let at = top.length;
    while (at > 0) {
      const previous = top[at - 1];
      if (previous === undefined || !before(buffer, sort, p, previous)) {
        break;
      }
      at -= 1;
    }
    top.splice(at, 0, p);
    if (top.length > limit) {
      top.pop();
    }
  }
  return top;
}

/**
 * Preview rows of a selection in the shape of the search rows. The columns the points do not carry
 * hold placeholders (0, false, null) that the list never shows: it renders those columns as
 * pending (PREVIEW_COLUMNS).
 */
export function previewRows(
  buffer: XLogBuffer,
  dictionary: SummaryDictionary,
  sel: Selection,
  sort: SearchSort,
  limit: number = PREVIEW_ROWS,
): TxRow[] {
  return previewIndices(buffer, sel, sort, limit).map((p) => {
    const serviceIndex = buffer.service[p] ?? 0;
    const instanceIndex = buffer.instance[p] ?? 0;
    const serviceName = dictionary.services[serviceIndex];
    const instanceName = dictionary.instances[instanceIndex];
    return {
      tx_id: formatU64(buffer.txIdHi[p] ?? 0, buffer.txIdLo[p] ?? 0),
      ts: new Date(buffer.ts[p] ?? 0).toISOString(),
      service: serviceName === undefined ? null : untrusted(serviceName),
      service_id: dictionary.serviceIds[serviceIndex] ?? null,
      elapsed_ms: buffer.elapsed[p] ?? 0,
      http_status: 0,
      error: (buffer.status[p] ?? 0) === STATUS_ERROR,
      error_class: null,
      error_class_id: null,
      instance: instanceName === undefined ? null : untrusted(instanceName),
      instance_id: dictionary.instanceIds[instanceIndex] ?? 0,
      sql_count: 0,
      sql_time_ms: 0,
      ext_count: 0,
      ext_time_ms: 0,
      user_id: null,
      user_id_mode: null,
      has_profile: false,
      kind: kindName(buffer.kind[p] ?? 0),
      version: null,
      version_id: null,
    };
  });
}
