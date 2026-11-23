// SPDX-License-Identifier: Apache-2.0
//
// Selection summary model (docs/15 §7.4, 05 §2.1-2, ADR-0010 C-202): the points of the URL
// selection are counted in the browser from the XLog buffer (point count, top 10 services, instance
// and status distributions); the HTTP status and error class distributions come from the server
// summary (`POST /xlog/summary` by_status, by_error_class) because the XLog points carry no such
// columns (C-202 (1)). A summary row click toggles the matching f= filter, which filters the scatter
// and the list together; the selection is kept (C-202 (2)).
//
// The selection is read from the URL (`sel`, whole milliseconds, inclusive on both ends, docs/15
// §7.3), not from the drag's floating-point rectangle, so that the browser counts exactly the
// points the server search counts for the same bounds (api/transactionSearch.ts queryBounds).
import { untrusted } from '../../../api/untrusted';
import type { Filter, Selection } from '../../../state/urlState';
import { FILTER_MAX } from '../../../state/urlState';
import { summarizeIndices, type SelectionSummary } from '../../../xlog/selectionSummary';
import type { XLogBuffer, XLogDictionary } from '../../../xlog/XLogBuffer';

/**
 * The buffer's local dictionaries plus the keys behind the local indices: a summary row filters by
 * key (docs/14 §6 `f.service=<service key>`, `f.instance=<instance id>`), never by name.
 */
export interface SummaryDictionary extends XLogDictionary {
  /** service_id (unsigned 64-bit decimal string, C-175) of each local service index. */
  readonly serviceIds: readonly string[];
  /** instance_id of each local instance index. */
  readonly instanceIds: readonly number[];
}

/** Columns the points carry besides the XLog API's (WO-04 C-6). The API has neither (C-202). */
export interface ClientColumns {
  readonly httpStatus: boolean;
  readonly errorClass: boolean;
}

export const NO_CLIENT_COLUMNS: ClientColumns = { httpStatus: false, errorClass: false };

/**
 * Physical indices of the buffer's points inside the selection: tsFrom <= ts <= tsTo and
 * msMin <= elapsed <= msMax (docs/15 §7.3, the bounds the search uses). O(points).
 */
export function selectedIndices(buffer: XLogBuffer, sel: Selection): Uint32Array {
  const out = new Uint32Array(buffer.size);
  let n = 0;
  const { ts, elapsed } = buffer;
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    const t = ts[p] ?? 0;
    const e = elapsed[p] ?? 0;
    if (t >= sel.tsFrom && t <= sel.tsTo && e >= sel.msMin && e <= sel.msMax) {
      out[n] = p;
      n += 1;
    }
  }
  return out.subarray(0, n);
}

export interface ClientSummary {
  readonly summary: SelectionSummary;
  /**
   * Time to find the points of `sel` in the buffer (selectedIndices only, no summary), ms: the
   * screen's selection computation, judged like the canvas hit index query by ADR-0003 C-36
   * (docs/15 §7.3 "10만 점 선택 계산 ≤ 5ms", p95 per run; window.__xlogPerf selectMs).
   */
  readonly selectMs: number;
  /**
   * Time to select and summarise, ms: part of the C-36 screen reflect path (drag release to the
   * summary and the first list row on screen, 100 ms), not judged against the 5 ms budget.
   */
  readonly computeMs: number;
}

/** What the summary panel reports after each new summary (SelectionSummaryPanel onComputed). */
export interface SummaryTiming {
  readonly points: number;
  readonly selectMs: number;
  readonly computeMs: number;
}

/** The client part of the summary of `sel` over the buffer's points (no snapshot copy). */
export function clientSummary(
  buffer: XLogBuffer,
  dictionary: XLogDictionary,
  sel: Selection,
  sampled: boolean,
  now: () => number = () => performance.now(),
): ClientSummary {
  const start = now();
  const indices = selectedIndices(buffer, sel);
  const selected = now();
  const summary = summarizeIndices(buffer, indices, dictionary, { sampled });
  return { summary, selectMs: selected - start, computeMs: now() - start };
}

/** True when `filters` hold the equality filter field = value. */
export function hasFilter(filters: readonly Filter[], field: string, value: string): boolean {
  return filters.some((f) => f.field === field && f.op === 'eq' && f.value === value);
}

/**
 * The filters after clicking a summary row: removes field = value when present, adds it
 * otherwise. Null when adding would exceed the 20 filters of a URL (docs/15 §4.2).
 */
export function toggleFilter(
  filters: readonly Filter[],
  field: string,
  value: string,
): Filter[] | null {
  if (hasFilter(filters, field, value)) {
    return filters.filter((f) => !(f.field === field && f.op === 'eq' && f.value === value));
  }
  if (filters.length >= FILTER_MAX) {
    return null;
  }
  return [...filters, { field, op: 'eq', value: untrusted(value) }];
}
