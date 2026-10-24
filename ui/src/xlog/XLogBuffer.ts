// SPDX-License-Identifier: Apache-2.0
//
// Column store for XLog points (docs/15 §7.1): one TypedArray per field (structure of arrays),
// used as a ring buffer in realtime mode. Points are never held as row objects in bulk; only the
// small realtime batches (docs/15 §7.5, 100 ms) arrive as objects.

/** Point status (docs/15 §7.1). The server decides it; the UI only colours it. */
export const STATUS_NORMAL = 0;
export const STATUS_SLOW = 1;
export const STATUS_ERROR = 2;
export type XLogStatus = typeof STATUS_NORMAL | typeof STATUS_SLOW | typeof STATUS_ERROR;

/** Transaction kind (docs/15 §7.1, D-20). */
export const KIND_HTTP = 1;
export const KIND_BATCH = 2;
export const KIND_MESSAGE = 3;

/** Default ring capacity (docs/15 §7.1). */
export const DEFAULT_CAPACITY = 150_000;
/** Hard upper bound of one buffer, so a bad parameter cannot allocate unbounded memory. */
export const MAX_CAPACITY = 1_000_000;

const MAX_U32 = 0xffff_ffff;
const MAX_U16 = 0xffff;

/** One point as delivered by a realtime batch. Index fields refer to the local dictionaries. */
export interface XLogPoint {
  readonly ts: number;
  readonly elapsed: number;
  readonly status: number;
  readonly instance: number;
  readonly service: number;
  readonly txIdLo: number;
  readonly txIdHi: number;
  readonly kind: number;
  /** HTTP status code, 0 when the transaction is not an HTTP request. */
  readonly httpStatus: number;
  /** Local error class index + 1, 0 when there is no error class (docs/15 §7.4). */
  readonly errorClass: number;
}

/**
 * Column-oriented points as the server sends them (`{ts: number[], elapsed: number[], …}`,
 * docs/15 §7.1) or as TypedArrays. All columns must have at least `ts.length` entries.
 */
export interface XLogColumns {
  readonly ts: ArrayLike<number>;
  readonly elapsed: ArrayLike<number>;
  readonly status: ArrayLike<number>;
  readonly instance: ArrayLike<number>;
  readonly service: ArrayLike<number>;
  readonly txIdLo: ArrayLike<number>;
  readonly txIdHi: ArrayLike<number>;
  readonly kind: ArrayLike<number>;
  readonly httpStatus: ArrayLike<number>;
  readonly errorClass: ArrayLike<number>;
}

/**
 * Local dictionaries that resolve the index columns (docs/15 §7.4). Every string is collected data
 * and therefore untrusted: render it as a text node only (SEC-60).
 */
export interface XLogDictionary {
  readonly services: readonly string[];
  readonly instances: readonly string[];
  /** Error class names (index = errorClass - 1); null when the points carry no error class. */
  readonly errorClasses: readonly string[] | null;
}

function clamp(value: number, max: number): number {
  if (!(value > 0)) {
    return 0;
  }
  return value > max ? max : Math.floor(value);
}

function normalizeStatus(value: number): XLogStatus {
  return value === STATUS_SLOW || value === STATUS_ERROR ? value : STATUS_NORMAL;
}

/**
 * Ring buffer of XLog points. Physical slot `p` holds one point in every column; the logical order
 * (oldest first) starts at `head`. When the ring is full, `append` overwrites the oldest point and
 * counts it in `overwritten`, so the loss is visible (docs/15 §1 "숨기지 않는다").
 */
export class XLogBuffer {
  readonly capacity: number;
  readonly ts: Float64Array;
  readonly elapsed: Uint32Array;
  readonly status: Uint8Array;
  readonly instance: Uint16Array;
  readonly service: Uint32Array;
  readonly txIdLo: Uint32Array;
  readonly txIdHi: Uint32Array;
  readonly kind: Uint8Array;
  readonly httpStatus: Uint16Array;
  readonly errorClass: Uint32Array;

  /** Number of points held. */
  size = 0;
  /** Physical index of the oldest point. */
  head = 0;
  /** Points appended since construction (monotonic): the newest `appended - x` points are new. */
  appended = 0;
  /** Points dropped because the ring was full. */
  overwritten = 0;
  /** Points removed by `evictBefore`. */
  evicted = 0;
  /** Points rejected by `append`/`appendColumns` (non-finite time or elapsed). */
  rejected = 0;
  /** Incremented on every change. */
  version = 0;
  /** Incremented when the content is replaced (`clear`). */
  generation = 0;

  private readonly listeners = new Set<() => void>();

  constructor(capacity: number = DEFAULT_CAPACITY) {
    if (!Number.isInteger(capacity) || capacity < 1 || capacity > MAX_CAPACITY) {
      throw new RangeError(
        `XLogBuffer capacity must be an integer in [1, ${String(MAX_CAPACITY)}]: ${String(capacity)}`,
      );
    }
    this.capacity = capacity;
    this.ts = new Float64Array(capacity);
    this.elapsed = new Uint32Array(capacity);
    this.status = new Uint8Array(capacity);
    this.instance = new Uint16Array(capacity);
    this.service = new Uint32Array(capacity);
    this.txIdLo = new Uint32Array(capacity);
    this.txIdHi = new Uint32Array(capacity);
    this.kind = new Uint8Array(capacity);
    this.httpStatus = new Uint16Array(capacity);
    this.errorClass = new Uint32Array(capacity);
  }

  /** Physical slot of the `logical`-th oldest point (0 <= logical < size). */
  physical(logical: number): number {
    const p = this.head + logical;
    return p >= this.capacity ? p - this.capacity : p;
  }

  /** Appends a realtime batch (docs/15 §7.5). Points must be roughly in time order. */
  append(batch: readonly XLogPoint[]): void {
    if (batch.length === 0) {
      return;
    }
    for (const point of batch) {
      this.write(
        point.ts,
        point.elapsed,
        point.status,
        point.instance,
        point.service,
        point.txIdLo,
        point.txIdHi,
        point.kind,
        point.httpStatus,
        point.errorClass,
      );
    }
    this.changed();
  }

  /** Copies column-oriented points in (initial load, docs/15 §7.1). */
  appendColumns(columns: XLogColumns): void {
    const count = columns.ts.length;
    if (count === 0) {
      return;
    }
    for (let i = 0; i < count; i += 1) {
      this.write(
        columns.ts[i] ?? Number.NaN,
        columns.elapsed[i] ?? Number.NaN,
        columns.status[i] ?? 0,
        columns.instance[i] ?? 0,
        columns.service[i] ?? 0,
        columns.txIdLo[i] ?? 0,
        columns.txIdHi[i] ?? 0,
        columns.kind[i] ?? 0,
        columns.httpStatus[i] ?? 0,
        columns.errorClass[i] ?? 0,
      );
    }
    this.changed();
  }

  /**
   * Removes the oldest points whose time is before `ts` (realtime window, docs/15 §7.5). Stops at
   * the first point that is not older, so a point that arrived out of order may stay until the
   * points before it are evicted; renderers clip to the time range anyway.
   *
   * @returns the number of points removed
   */
  evictBefore(ts: number): number {
    let removed = 0;
    while (this.size > 0 && (this.ts[this.head] ?? 0) < ts) {
      this.head = this.head + 1 === this.capacity ? 0 : this.head + 1;
      this.size -= 1;
      removed += 1;
    }
    if (removed > 0) {
      this.evicted += removed;
      this.changed();
    }
    return removed;
  }

  /** Removes every point. */
  clear(): void {
    this.size = 0;
    this.head = 0;
    this.generation += 1;
    this.changed();
  }

  /** Reads one point (tooltips, lists). */
  point(physical: number): XLogPoint {
    return {
      ts: this.ts[physical] ?? 0,
      elapsed: this.elapsed[physical] ?? 0,
      status: this.status[physical] ?? 0,
      instance: this.instance[physical] ?? 0,
      service: this.service[physical] ?? 0,
      txIdLo: this.txIdLo[physical] ?? 0,
      txIdHi: this.txIdHi[physical] ?? 0,
      kind: this.kind[physical] ?? 0,
      httpStatus: this.httpStatus[physical] ?? 0,
      errorClass: this.errorClass[physical] ?? 0,
    };
  }

  /**
   * Copies the given points into a new, independent buffer (index i = indices[i]). Used to snapshot
   * a selection so that later realtime changes cannot alter its summary or list.
   */
  extract(indices: ArrayLike<number>): XLogBuffer {
    const copy = new XLogBuffer(Math.max(1, indices.length));
    for (let i = 0; i < indices.length; i += 1) {
      const p = indices[i] ?? 0;
      copy.ts[i] = this.ts[p] ?? 0;
      copy.elapsed[i] = this.elapsed[p] ?? 0;
      copy.status[i] = this.status[p] ?? 0;
      copy.instance[i] = this.instance[p] ?? 0;
      copy.service[i] = this.service[p] ?? 0;
      copy.txIdLo[i] = this.txIdLo[p] ?? 0;
      copy.txIdHi[i] = this.txIdHi[p] ?? 0;
      copy.kind[i] = this.kind[p] ?? 0;
      copy.httpStatus[i] = this.httpStatus[p] ?? 0;
      copy.errorClass[i] = this.errorClass[p] ?? 0;
    }
    copy.size = indices.length;
    copy.appended = indices.length;
    return copy;
  }

  /** Calls `listener` after every change. Returns the unsubscribe function. */
  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private write(
    ts: number,
    elapsed: number,
    status: number,
    instance: number,
    service: number,
    txIdLo: number,
    txIdHi: number,
    kind: number,
    httpStatus: number,
    errorClass: number,
  ): void {
    if (!Number.isFinite(ts) || !Number.isFinite(elapsed)) {
      this.rejected += 1;
      return;
    }
    let p: number;
    if (this.size < this.capacity) {
      p = this.physical(this.size);
      this.size += 1;
    } else {
      p = this.head;
      this.head = this.head + 1 === this.capacity ? 0 : this.head + 1;
      this.overwritten += 1;
    }
    this.ts[p] = ts;
    this.elapsed[p] = clamp(elapsed, MAX_U32);
    this.status[p] = normalizeStatus(status);
    this.instance[p] = clamp(instance, MAX_U16);
    this.service[p] = clamp(service, MAX_U32);
    this.txIdLo[p] = clamp(txIdLo, MAX_U32);
    this.txIdHi[p] = clamp(txIdHi, MAX_U32);
    this.kind[p] = clamp(kind, 0xff);
    this.httpStatus[p] = clamp(httpStatus, 999);
    this.errorClass[p] = clamp(errorClass, MAX_U32);
    this.appended += 1;
  }

  private changed(): void {
    this.version += 1;
    for (const listener of this.listeners) {
      listener();
    }
  }
}

/** Formats a 64-bit transaction id as 16 lower-case hex digits. */
export function formatTxId(hi: number, lo: number): string {
  return (hi >>> 0).toString(16).padStart(8, '0') + (lo >>> 0).toString(16).padStart(8, '0');
}
