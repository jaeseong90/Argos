// SPDX-License-Identifier: Apache-2.0
//
// Pure model of the active transactions screen (docs/15 §9.6, 05 §2.6, WO-35): elapsed bands with
// the viewer's thresholds, per-instance band counts (the server's fixed 1/3/8 s buckets, or a
// recount from the items for custom thresholds), the top summary, the client-side list filters
// (instance, service, state, global kind) and the "as of N s ago" age of a snapshot measured on
// the server clock only (so a skewed browser clock does not change it).
import type { ActiveInstance, ActiveItem, ActiveTransactions } from '../../api/active';
import type { Filter, Kind } from '../../state/urlState';

/** Elapsed thresholds in seconds: t1 < t2 < t3 split the four bands. */
export type Thresholds = readonly [number, number, number];

/** 05 §2.6 default: under 1 s, 1 to 3 s, 3 to 8 s, 8 s and more (the server's buckets). */
export const DEFAULT_THRESHOLDS: Thresholds = [1, 3, 8];
/** Largest threshold accepted (one hour). */
export const MAX_THRESHOLD_S = 3_600;

/** Band index 0..3 (green, yellow, orange, red). */
export type Band = 0 | 1 | 2 | 3;
export const BANDS: readonly Band[] = [0, 1, 2, 3];

/** Why a threshold set is refused, or null when it is valid. */
export type ThresholdProblem = 'notNumber' | 'notPositive' | 'notAscending' | 'tooLarge';

/** Validates 0 < t1 < t2 < t3 <= 3,600 s (finite numbers). */
export function thresholdProblem(values: readonly number[]): ThresholdProblem | null {
  if (values.length !== 3 || values.some((value) => !Number.isFinite(value))) {
    return 'notNumber';
  }
  const [t1, t2, t3] = values as Thresholds;
  if (t1 <= 0) {
    return 'notPositive';
  }
  if (!(t1 < t2 && t2 < t3)) {
    return 'notAscending';
  }
  if (t3 > MAX_THRESHOLD_S) {
    return 'tooLarge';
  }
  return null;
}

/** True for the default 1/3/8 s, where the server's buckets apply as they are. */
export function isDefaultThresholds(thresholds: Thresholds): boolean {
  return thresholds.every((value, index) => value === DEFAULT_THRESHOLDS[index]);
}

/** The band of an elapsed time. */
export function bandOf(elapsedMs: number, thresholds: Thresholds): Band {
  const [t1, t2, t3] = thresholds;
  if (elapsedMs < t1 * 1000) return 0;
  if (elapsedMs < t2 * 1000) return 1;
  if (elapsedMs < t3 * 1000) return 2;
  return 3;
}

/** Band counts of one instance. */
export interface InstanceBands {
  readonly instanceId: number;
  /** Running transactions (the agent's total, may exceed the items it sent). */
  readonly count: number;
  readonly bands: readonly [number, number, number, number];
  /**
   * The split is an estimate: the agent sent only its longest transactions (truncated) and the
   * omitted ones may fall into more than the first band, or the list holds fewer items of this
   * instance than the agent sent.
   */
  readonly approximate: boolean;
}

function sum(values: readonly number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

/**
 * The bands of an instance. With the default thresholds the server's buckets give bands 2..4; the
 * first band is `count` minus those, so the transactions an agent did not send (always shorter than
 * the ones it sent) count there. Custom thresholds recount bands 2..4 from the items of the
 * instance (all kinds) the same way. Approximate when the instance is truncated and its shortest
 * sent transaction is at or above t1 (the omitted ones could be in any band up to it), or when the
 * list has fewer items of the instance than the agent sent (the server's 2,000-item cap).
 */
export function instanceBands(
  instance: ActiveInstance,
  items: readonly ActiveItem[],
  thresholds: Thresholds,
): InstanceBands {
  const count = Math.max(0, instance.count);
  const buckets = [0, 1, 2, 3].map((index) => Math.max(0, instance.buckets[index] ?? 0));
  const sent = sum(buckets);
  let upper: [number, number, number];
  let approximate: boolean;
  if (isDefaultThresholds(thresholds)) {
    upper = [buckets[1] ?? 0, buckets[2] ?? 0, buckets[3] ?? 0];
    // buckets[0] > 0 means the shortest sent one is under 1 s: the omitted ones are too.
    approximate = instance.truncated && (buckets[0] ?? 0) === 0 && count > sent;
  } else {
    upper = [0, 0, 0];
    let shortest = Number.POSITIVE_INFINITY;
    let seen = 0;
    for (const item of items) {
      if (item.instance_id !== instance.instance_id) {
        continue;
      }
      seen += 1;
      shortest = Math.min(shortest, item.elapsed_ms);
      const band = bandOf(item.elapsed_ms, thresholds);
      if (band === 1) upper[0] += 1;
      else if (band === 2) upper[1] += 1;
      else if (band === 3) upper[2] += 1;
    }
    approximate =
      seen < sent ||
      (instance.truncated && count > seen && (seen === 0 || shortest >= thresholds[0] * 1000));
  }
  const first = Math.max(0, count - sum(upper));
  return {
    instanceId: instance.instance_id,
    count,
    bands: [first, upper[0], upper[1], upper[2]],
    approximate,
  };
}

/** The top summary (05 §2.6-5). */
export interface ActiveSummary {
  /** Σ per_instance.count. */
  readonly total: number;
  /** Transactions in the top band (default 8 s and more). */
  readonly slow: number;
  /** True when some instance's split is approximate (the slow count is then an estimate). */
  readonly slowApproximate: boolean;
  /** The instance with the most running transactions, null without instances. */
  readonly max: { readonly instanceId: number; readonly count: number } | null;
}

export function summarize(bands: readonly InstanceBands[]): ActiveSummary {
  let total = 0;
  let slow = 0;
  let slowApproximate = false;
  let max: ActiveSummary['max'] = null;
  for (const entry of bands) {
    total += entry.count;
    slow += entry.bands[3];
    slowApproximate ||= entry.approximate;
    if (max === null || entry.count > max.count) {
      max = { instanceId: entry.instanceId, count: entry.count };
    }
  }
  return { total, slow, slowApproximate, max };
}

// ---------------------------------------------------------------------------------------------
// List filters (docs/14 §6 `f.instance`, `f.state`; `f.service` = service key; global kind)
// ---------------------------------------------------------------------------------------------

/** URL filter fields the screen applies to the list. */
export const ACTIVE_FILTER_FIELDS = ['instance', 'service', 'state'] as const;
export type ActiveFilterField = (typeof ACTIVE_FILTER_FIELDS)[number];

function isActiveField(field: string): field is ActiveFilterField {
  return (ACTIVE_FILTER_FIELDS as readonly string[]).includes(field);
}

function itemValue(item: ActiveItem, field: ActiveFilterField): string {
  switch (field) {
    case 'instance':
      return String(item.instance_id);
    case 'service':
      return item.service_id ?? '';
    case 'state':
      return item.state.toUpperCase();
  }
}

function filterValue(filter: Filter, field: ActiveFilterField): string {
  return field === 'state' ? filter.value.trim().toUpperCase() : filter.value.trim();
}

/** The list after the filters, and the URL filters the screen cannot apply. */
export interface FilteredItems {
  readonly items: readonly ActiveItem[];
  readonly ignored: readonly Filter[];
}

/**
 * Applies the URL filters and the global kind to the items (the bars and the summary stay
 * app-wide). Per field, several `eq` values mean "any of them" and `ne` values exclude
 * (DSN-14-17). Other fields and range operators are returned as ignored, never emulated.
 */
export function filterItems(
  items: readonly ActiveItem[],
  filters: readonly Filter[],
  kind: Kind,
): FilteredItems {
  const include = new Map<ActiveFilterField, Set<string>>();
  const exclude = new Map<ActiveFilterField, Set<string>>();
  const ignored: Filter[] = [];
  for (const filter of filters) {
    if (!isActiveField(filter.field) || (filter.op !== 'eq' && filter.op !== 'ne')) {
      ignored.push(filter);
      continue;
    }
    const target = filter.op === 'eq' ? include : exclude;
    const values = target.get(filter.field) ?? new Set<string>();
    values.add(filterValue(filter, filter.field));
    target.set(filter.field, values);
  }
  const kept = items.filter((item) => {
    if (kind !== 'ALL' && item.kind !== kind) {
      return false;
    }
    for (const field of ACTIVE_FILTER_FIELDS) {
      const value = itemValue(item, field);
      const wanted = include.get(field);
      if (wanted !== undefined && !wanted.has(value)) {
        return false;
      }
      if (exclude.get(field)?.has(value) === true) {
        return false;
      }
    }
    return true;
  });
  return { items: kept, ignored };
}

/** The instance ids of the `f.instance=<id>` include filters. */
export function selectedInstances(filters: readonly Filter[]): ReadonlySet<string> {
  return new Set(
    filters.filter((f) => f.field === 'instance' && f.op === 'eq').map((f) => f.value.trim()),
  );
}

/**
 * Bar click (05 §2.6-1): adds `f.instance=<id>`, or removes it when the instance is already
 * filtered. Other filters stay.
 */
export function toggleInstanceFilter(
  filters: readonly Filter[],
  instanceId: number,
  make: (value: string) => Filter,
): Filter[] {
  const value = String(instanceId);
  const present = filters.some(
    (f) => f.field === 'instance' && f.op === 'eq' && f.value.trim() === value,
  );
  if (present) {
    return filters.filter(
      (f) => !(f.field === 'instance' && f.op === 'eq' && f.value.trim() === value),
    );
  }
  return [...filters, make(value)];
}

/** Replaces the state filters by one `f.state=<state>` (null clears them). */
export function withStateFilter(
  filters: readonly Filter[],
  state: string | null,
  make: (value: string) => Filter,
): Filter[] {
  const rest = filters.filter((f) => f.field !== 'state');
  return state === null ? rest : [...rest, make(state)];
}

/** The single state of the `f.state` include filters, or null (none or several). */
export function selectedState(filters: readonly Filter[]): string | null {
  const states = filters.filter((f) => f.field === 'state');
  if (states.length !== 1 || states[0]?.op !== 'eq') {
    return null;
  }
  return states[0].value.trim().toUpperCase();
}

// ---------------------------------------------------------------------------------------------
// Snapshot age ("N초 전 기준", docs/15 §9.6)
// ---------------------------------------------------------------------------------------------

/** A snapshot as the screen received it. */
export interface ReceivedSnapshot {
  readonly data: ActiveTransactions;
  /** Server time of the answer (push `t` or REST `meta.generated_at`), epoch ms. */
  readonly serverAt: number;
  /**
   * Age of the snapshot at receipt on the server clock: serverAt − snapshot_at (0 when the answer
   * has no snapshot time yet).
   */
  readonly offsetMs: number;
  /** Browser time of receipt, epoch ms (only differences of browser times are used). */
  readonly receivedAt: number;
  readonly source: 'rest' | 'ws';
}

/**
 * The received snapshot of an answer, or null when its server time is not a valid instant. The
 * offset never mixes the two clocks: both ends are server times.
 */
export function receiveSnapshot(
  data: ActiveTransactions,
  serverTime: string,
  receivedAt: number,
  source: 'rest' | 'ws',
): ReceivedSnapshot | null {
  const serverAt = Date.parse(serverTime);
  if (!Number.isFinite(serverAt)) {
    return null;
  }
  const snapshotAt = data.snapshot_at === null ? Number.NaN : Date.parse(data.snapshot_at);
  const offsetMs = Number.isFinite(snapshotAt) ? Math.max(0, serverAt - snapshotAt) : 0;
  return { data, serverAt, offsetMs, receivedAt, source };
}

/** Seconds since the snapshot was taken: offset at receipt plus the browser time since receipt. */
export function asOfSeconds(snapshot: ReceivedSnapshot, now: number): number {
  return Math.max(
    0,
    Math.floor((snapshot.offsetMs + Math.max(0, now - snapshot.receivedAt)) / 1000),
  );
}

/** Seconds since the snapshot was received (browser clock only). */
export function silenceSeconds(snapshot: ReceivedSnapshot, now: number): number {
  return Math.max(0, Math.floor((now - snapshot.receivedAt) / 1000));
}

/**
 * Which snapshot to keep: a newer answer (by server time) replaces the current one as a whole
 * (docs/15 §10 "스냅샷 교체", no merge); an older one (a slow REST answer after a push) is ignored.
 */
export function newerSnapshot(
  current: ReceivedSnapshot | null,
  next: ReceivedSnapshot,
): ReceivedSnapshot {
  return current === null || next.serverAt >= current.serverAt ? next : current;
}

/** Row key of an item: tx ids belong to an agent, so the instance is part of the key. */
export function itemKey(item: ActiveItem): string {
  return `${String(item.instance_id)}:${item.tx_id}`;
}
