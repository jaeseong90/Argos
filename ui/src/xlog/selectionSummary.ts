// SPDX-License-Identifier: Apache-2.0
//
// Summary of a selected XLog region, computed in the browser because every point is in memory
// (docs/15 §7.4, docs/05 §2.1): point count, top services (count, average, maximum, errors),
// distribution per instance, status, HTTP status and error class, and the slowest points for the
// list. Names come from the local dictionaries and are untrusted text (SEC-60).
import {
  STATUS_ERROR,
  STATUS_SLOW,
  formatTxId,
  type XLogBuffer,
  type XLogDictionary,
} from './XLogBuffer';

export const TOP_SERVICES = 10;
/** Rows of the selected-points list (docs/15 §7.4 "≤ 1,000행"). */
export const LIST_LIMIT = 1000;

export interface ServiceRow {
  readonly service: number;
  readonly name: string | null;
  readonly count: number;
  readonly avgElapsed: number;
  readonly maxElapsed: number;
  readonly errorCount: number;
}

export interface InstanceRow {
  readonly instance: number;
  readonly name: string | null;
  readonly count: number;
  readonly errorCount: number;
}

export interface HttpStatusRow {
  /** HTTP status code; 0 = not an HTTP transaction. */
  readonly code: number;
  readonly count: number;
}

export interface ErrorClassRow {
  /** errorClass column value (dictionary index + 1). */
  readonly errorClass: number;
  readonly name: string | null;
  readonly count: number;
}

export interface SelectionSummary {
  readonly count: number;
  readonly services: readonly ServiceRow[];
  /** Distinct services in the selection (the table shows the top ones). */
  readonly serviceCount: number;
  readonly instances: readonly InstanceRow[];
  readonly status: { readonly normal: number; readonly slow: number; readonly error: number };
  readonly httpStatus: readonly HttpStatusRow[];
  /** null when the points carry no error class (docs/15 §7.4: "목록에서 확인"). */
  readonly errorClasses: readonly ErrorClassRow[] | null;
  /** The response was sampled: the summary covers the points shown only. */
  readonly sampled: boolean;
}

/** A filter set by clicking a summary row (docs/05 §2.1 "요약 행 클릭 → 목록 필터"). */
export interface SummaryFilter {
  readonly field: 'service' | 'instance' | 'status' | 'httpStatus' | 'errorClass';
  readonly value: number;
}

/** Dictionary name of `index`, or null when the index is outside the dictionary. */
function lookup(names: readonly string[] | null, index: number): string | null {
  if (names === null || index < 0 || index >= names.length) {
    return null;
  }
  return names[index] ?? null;
}

/**
 * Summarises every point of `points` (a selection snapshot, see XLogBuffer.extract). Runs in
 * O(points + dictionary).
 */
export function summarizeSelection(
  points: XLogBuffer,
  dictionary: XLogDictionary,
  options: { readonly topServices?: number; readonly sampled?: boolean } = {},
): SelectionSummary {
  const topServices = options.topServices ?? TOP_SERVICES;
  const serviceKeys = Math.max(dictionary.services.length, 1);
  const instanceKeys = Math.max(dictionary.instances.length, 1);
  const svcCount = new Uint32Array(serviceKeys + 1);
  const svcSum = new Float64Array(serviceKeys + 1);
  const svcMax = new Uint32Array(serviceKeys + 1);
  const svcErrors = new Uint32Array(serviceKeys + 1);
  const instCount = new Uint32Array(instanceKeys + 1);
  const instErrors = new Uint32Array(instanceKeys + 1);
  const http = new Map<number, number>();
  const errorClasses = new Map<number, number>();
  // Indices outside the dictionary are pooled in the last slot ("unknown").
  const unknownService = serviceKeys;
  const unknownInstance = instanceKeys;
  let slow = 0;
  let error = 0;

  for (let logical = 0; logical < points.size; logical += 1) {
    const p = points.physical(logical);
    const e = points.elapsed[p] ?? 0;
    const status = points.status[p] ?? 0;
    const isError = status === STATUS_ERROR;
    if (isError) {
      error += 1;
    } else if (status === STATUS_SLOW) {
      slow += 1;
    }
    let s = points.service[p] ?? 0;
    if (s >= dictionary.services.length) {
      s = unknownService;
    }
    svcCount[s] = (svcCount[s] ?? 0) + 1;
    svcSum[s] = (svcSum[s] ?? 0) + e;
    if (e > (svcMax[s] ?? 0)) {
      svcMax[s] = e;
    }
    let inst = points.instance[p] ?? 0;
    if (inst >= dictionary.instances.length) {
      inst = unknownInstance;
    }
    instCount[inst] = (instCount[inst] ?? 0) + 1;
    if (isError) {
      svcErrors[s] = (svcErrors[s] ?? 0) + 1;
      instErrors[inst] = (instErrors[inst] ?? 0) + 1;
    }
    const code = points.httpStatus[p] ?? 0;
    http.set(code, (http.get(code) ?? 0) + 1);
    const errorClass = points.errorClass[p] ?? 0;
    if (errorClass !== 0) {
      errorClasses.set(errorClass, (errorClasses.get(errorClass) ?? 0) + 1);
    }
  }

  const serviceIds: number[] = [];
  for (let s = 0; s <= serviceKeys; s += 1) {
    if ((svcCount[s] ?? 0) > 0) {
      serviceIds.push(s);
    }
  }
  serviceIds.sort((a, b) => (svcCount[b] ?? 0) - (svcCount[a] ?? 0) || a - b);
  const services = serviceIds.slice(0, topServices).map((s) => {
    const count = svcCount[s] ?? 0;
    return {
      service: s === unknownService ? -1 : s,
      name: s === unknownService ? null : lookup(dictionary.services, s),
      count,
      avgElapsed: count > 0 ? (svcSum[s] ?? 0) / count : 0,
      maxElapsed: svcMax[s] ?? 0,
      errorCount: svcErrors[s] ?? 0,
    };
  });

  const instances: InstanceRow[] = [];
  for (let i = 0; i <= instanceKeys; i += 1) {
    const count = instCount[i] ?? 0;
    if (count > 0) {
      instances.push({
        instance: i === unknownInstance ? -1 : i,
        name: i === unknownInstance ? null : lookup(dictionary.instances, i),
        count,
        errorCount: instErrors[i] ?? 0,
      });
    }
  }
  instances.sort((a, b) => b.count - a.count || a.instance - b.instance);

  const httpStatus = [...http.entries()]
    .map(([code, count]) => ({ code, count }))
    .sort((a, b) => b.count - a.count || a.code - b.code);

  const errorRows =
    dictionary.errorClasses === null
      ? null
      : [...errorClasses.entries()]
          .map(([errorClass, count]) => ({
            errorClass,
            name: lookup(dictionary.errorClasses, errorClass - 1),
            count,
          }))
          .sort((a, b) => b.count - a.count || a.errorClass - b.errorClass);

  return {
    count: points.size,
    services,
    serviceCount: serviceIds.length,
    instances,
    status: { normal: points.size - slow - error, slow, error },
    httpStatus,
    errorClasses: errorRows,
    sampled: options.sampled === true,
  };
}

/** True when point `p` passes the filter. */
export function matchesFilter(
  points: XLogBuffer,
  p: number,
  filter: SummaryFilter | null,
): boolean {
  if (filter === null) {
    return true;
  }
  switch (filter.field) {
    case 'service':
      return points.service[p] === filter.value;
    case 'instance':
      return points.instance[p] === filter.value;
    case 'status':
      return points.status[p] === filter.value;
    case 'httpStatus':
      return points.httpStatus[p] === filter.value;
    case 'errorClass':
      return points.errorClass[p] === filter.value;
  }
}

/** One row of the selected-points list; a copy, so later buffer changes cannot alter it. */
export interface PointRow {
  readonly key: string;
  readonly ts: number;
  readonly elapsed: number;
  readonly status: number;
  readonly service: number;
  readonly serviceName: string | null;
  readonly instance: number;
  readonly instanceName: string | null;
  readonly httpStatus: number;
  readonly errorClass: number;
  readonly errorClassName: string | null;
  readonly kind: number;
  readonly txId: string;
}

export interface PointList {
  readonly rows: readonly PointRow[];
  /** Points that pass the filter; the list shows the slowest `limit` of them. */
  readonly matching: number;
  readonly truncated: boolean;
}

/**
 * The slowest points of the selection that pass the filter, by elapsed descending (docs/15 §7.4:
 * "응답시간 내림차순, ≤ 1,000행"). A bounded min-heap keeps it O(n log limit).
 */
export function slowestPoints(
  points: XLogBuffer,
  dictionary: XLogDictionary,
  filter: SummaryFilter | null,
  limit: number = LIST_LIMIT,
): PointList {
  const max = Math.max(0, Math.floor(limit));
  const heap = new Uint32Array(Math.max(1, max));
  let heapSize = 0;
  let matching = 0;
  const elapsed = points.elapsed;
  // Heap order: smaller elapsed first; on ties the later point is "smaller" so earlier ones stay.
  const less = (a: number, b: number): boolean => {
    const ea = elapsed[a] ?? 0;
    const eb = elapsed[b] ?? 0;
    return ea < eb || (ea === eb && a > b);
  };
  const siftDown = (start: number): void => {
    let i = start;
    for (;;) {
      const l = 2 * i + 1;
      const r = l + 1;
      let m = i;
      if (l < heapSize && less(heap[l] ?? 0, heap[m] ?? 0)) {
        m = l;
      }
      if (r < heapSize && less(heap[r] ?? 0, heap[m] ?? 0)) {
        m = r;
      }
      if (m === i) {
        return;
      }
      const tmp = heap[i] ?? 0;
      heap[i] = heap[m] ?? 0;
      heap[m] = tmp;
      i = m;
    }
  };
  for (let logical = 0; logical < points.size; logical += 1) {
    const p = points.physical(logical);
    if (!matchesFilter(points, p, filter)) {
      continue;
    }
    matching += 1;
    if (max === 0) {
      continue;
    }
    if (heapSize < max) {
      let i = heapSize;
      heap[i] = p;
      heapSize += 1;
      while (i > 0) {
        const parent = (i - 1) >> 1;
        if (!less(heap[i] ?? 0, heap[parent] ?? 0)) {
          break;
        }
        const tmp = heap[i] ?? 0;
        heap[i] = heap[parent] ?? 0;
        heap[parent] = tmp;
        i = parent;
      }
    } else if (less(heap[0] ?? 0, p)) {
      heap[0] = p;
      siftDown(0);
    }
  }
  const selected = Array.from(heap.subarray(0, heapSize));
  selected.sort((a, b) => (less(a, b) ? 1 : less(b, a) ? -1 : 0));
  const rows = selected.map((p) => {
    const service = points.service[p] ?? 0;
    const instance = points.instance[p] ?? 0;
    const errorClass = points.errorClass[p] ?? 0;
    const txId = formatTxId(points.txIdHi[p] ?? 0, points.txIdLo[p] ?? 0);
    return {
      key: `${txId}-${String(p)}`,
      ts: points.ts[p] ?? 0,
      elapsed: elapsed[p] ?? 0,
      status: points.status[p] ?? 0,
      service,
      serviceName: lookup(dictionary.services, service),
      instance,
      instanceName: lookup(dictionary.instances, instance),
      httpStatus: points.httpStatus[p] ?? 0,
      errorClass,
      errorClassName: errorClass === 0 ? null : lookup(dictionary.errorClasses, errorClass - 1),
      kind: points.kind[p] ?? 0,
      txId,
    };
  });
  return { rows, matching, truncated: matching > rows.length };
}
