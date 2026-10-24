// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it, vi } from 'vitest';

import {
  DEFAULT_CAPACITY,
  MAX_CAPACITY,
  STATUS_ERROR,
  STATUS_NORMAL,
  XLogBuffer,
  formatTxId,
  type XLogPoint,
} from './XLogBuffer';

function point(ts: number, overrides: Partial<XLogPoint> = {}): XLogPoint {
  return {
    ts,
    elapsed: ts % 1000,
    status: STATUS_NORMAL,
    instance: 1,
    service: 2,
    txIdLo: ts,
    txIdHi: 0,
    kind: 1,
    httpStatus: 200,
    errorClass: 0,
    ...overrides,
  };
}

/** Logical contents (oldest first) of the ts column. */
function times(buffer: XLogBuffer): number[] {
  return Array.from({ length: buffer.size }, (_, i) => buffer.ts[buffer.physical(i)] ?? -1);
}

describe('XLogBuffer (docs/15 §7.1)', () => {
  it('stores points column-wise in TypedArrays with the default capacity of 150,000', () => {
    const buffer = new XLogBuffer();
    expect(buffer.capacity).toBe(DEFAULT_CAPACITY);
    expect(DEFAULT_CAPACITY).toBe(150_000);
    expect(buffer.ts).toBeInstanceOf(Float64Array);
    expect(buffer.elapsed).toBeInstanceOf(Uint32Array);
    expect(buffer.status).toBeInstanceOf(Uint8Array);
    expect(buffer.instance).toBeInstanceOf(Uint16Array);
    expect(buffer.service).toBeInstanceOf(Uint32Array);
    expect(buffer.txIdLo).toBeInstanceOf(Uint32Array);
    expect(buffer.txIdHi).toBeInstanceOf(Uint32Array);
    expect(buffer.kind).toBeInstanceOf(Uint8Array);
  });

  it('rejects capacities outside [1, MAX_CAPACITY]', () => {
    expect(() => new XLogBuffer(0)).toThrow(RangeError);
    expect(() => new XLogBuffer(MAX_CAPACITY + 1)).toThrow(RangeError);
    expect(() => new XLogBuffer(1.5)).toThrow(RangeError);
  });

  it('appends realtime batches and reads points back', () => {
    const buffer = new XLogBuffer(10);
    buffer.append([point(1000), point(2000, { status: STATUS_ERROR, errorClass: 3 })]);
    expect(buffer.size).toBe(2);
    expect(buffer.point(buffer.physical(1))).toEqual(
      point(2000, { status: STATUS_ERROR, errorClass: 3 }),
    );
  });

  it('wraps around: a full ring overwrites the oldest points and counts them', () => {
    const buffer = new XLogBuffer(5);
    buffer.append([1, 2, 3, 4, 5, 6, 7, 8].map((ts) => point(ts)));
    expect(buffer.size).toBe(5);
    expect(buffer.overwritten).toBe(3);
    expect(buffer.appended).toBe(8);
    expect(buffer.head).toBe(3);
    expect(times(buffer)).toEqual([4, 5, 6, 7, 8]);
    // Physical slots wrapped: the newest point sits before the oldest.
    expect(buffer.physical(4)).toBe(2);
  });

  it('evicts points older than the window, also across the wrap point', () => {
    const buffer = new XLogBuffer(5);
    buffer.append([1, 2, 3, 4, 5, 6, 7].map((ts) => point(ts)));
    expect(times(buffer)).toEqual([3, 4, 5, 6, 7]);
    expect(buffer.evictBefore(6)).toBe(3);
    expect(times(buffer)).toEqual([6, 7]);
    expect(buffer.evicted).toBe(3);
    buffer.append([8, 9, 10].map((ts) => point(ts)));
    expect(times(buffer)).toEqual([6, 7, 8, 9, 10]);
    expect(buffer.evictBefore(100)).toBe(5);
    expect(buffer.size).toBe(0);
    expect(buffer.evictBefore(100)).toBe(0);
  });

  it('copies column-oriented input (server JSON or TypedArrays)', () => {
    const buffer = new XLogBuffer(4);
    buffer.appendColumns({
      ts: [10, 20, 30],
      elapsed: [5, 15, 25],
      status: [0, 1, 2],
      instance: [0, 1, 2],
      service: [3, 4, 5],
      txIdLo: [1, 2, 3],
      txIdHi: [0, 0, 1],
      kind: [1, 1, 2],
      httpStatus: [200, 200, 500],
      errorClass: [0, 0, 1],
    });
    expect(buffer.size).toBe(3);
    expect(buffer.point(2)).toMatchObject({ ts: 30, elapsed: 25, status: 2, service: 5, kind: 2 });
  });

  it('clamps and sanitises values instead of letting TypedArrays wrap them', () => {
    const buffer = new XLogBuffer(4);
    buffer.append([
      point(1, { elapsed: -5, status: 9, instance: 70_000, httpStatus: 5000 }),
      point(Number.NaN),
      point(2, { elapsed: Number.POSITIVE_INFINITY }),
    ]);
    expect(buffer.size).toBe(1);
    expect(buffer.rejected).toBe(2);
    expect(buffer.point(0)).toMatchObject({
      elapsed: 0,
      status: STATUS_NORMAL,
      instance: 0xffff,
      httpStatus: 999,
    });
  });

  it('notifies subscribers once per change and stops after unsubscribe', () => {
    const buffer = new XLogBuffer(4);
    const listener = vi.fn();
    const unsubscribe = buffer.subscribe(listener);
    buffer.append([point(1), point(2)]);
    buffer.evictBefore(2);
    buffer.append([]);
    expect(listener).toHaveBeenCalledTimes(2);
    expect(buffer.version).toBe(2);
    unsubscribe();
    buffer.clear();
    expect(listener).toHaveBeenCalledTimes(2);
    expect(buffer.generation).toBe(1);
  });

  it('extracts a selection into an independent snapshot', () => {
    const buffer = new XLogBuffer(3);
    buffer.append([1, 2, 3, 4].map((ts) => point(ts)));
    const snapshot = buffer.extract([buffer.physical(2), buffer.physical(0)]);
    buffer.append([5, 6].map((ts) => point(ts)));
    expect(times(snapshot)).toEqual([4, 2]);
    expect(snapshot.capacity).toBe(2);
  });

  it('formats 64-bit transaction ids as 16 hex digits', () => {
    expect(formatTxId(0, 1)).toBe('0000000000000001');
    expect(formatTxId(0xdeadbeef, 0x0badf00d)).toBe('deadbeef0badf00d');
  });
});
