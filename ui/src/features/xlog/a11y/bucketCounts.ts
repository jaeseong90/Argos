// SPDX-License-Identifier: Apache-2.0
//
// Data of the XLog chart's alternative table (docs/15 §13 "차트 대체 표", §6.1 "≤ 100행", WO-04
// C-12): the points of the range counted per time bucket and status. One linear pass over the
// buffer; the buckets are whole seconds so their bounds read well.
import { STATUS_ERROR, STATUS_SLOW, type XLogBuffer } from '../../../xlog/XLogBuffer';

/** Most rows of the alternative table (docs/15 §6.1). */
export const ALT_TABLE_MAX_ROWS = 100;

export interface BucketRow {
  /** Bucket start (inclusive) and end (exclusive), epoch ms. */
  readonly from: number;
  readonly to: number;
  readonly normal: number;
  readonly slow: number;
  readonly error: number;
  /** Largest response time in the bucket, ms (0 without points). */
  readonly maxElapsed: number;
}

const NICE_SECONDS = [
  1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 10_800, 21_600,
];

/** Bucket width: the smallest "nice" number of seconds that gives at most `maxRows` buckets. */
export function bucketWidth(
  from: number,
  to: number,
  maxRows: number = ALT_TABLE_MAX_ROWS,
): number {
  const span = Math.max(1, to - from);
  for (const seconds of NICE_SECONDS) {
    if (Math.ceil(span / (seconds * 1000)) <= maxRows) {
      return seconds * 1000;
    }
  }
  return Math.ceil(span / maxRows / 1000) * 1000;
}

/**
 * Counts per bucket of [from, to) and status. Buckets are aligned to their width; at most
 * `maxRows` rows, the empty ones included (an empty bucket is information too).
 */
export function bucketCounts(
  buffer: XLogBuffer,
  from: number,
  to: number,
  maxRows: number = ALT_TABLE_MAX_ROWS,
): BucketRow[] {
  if (!(to > from)) {
    return [];
  }
  // One row of room for the alignment of the first bucket, so no bucket is ever merged.
  const width = bucketWidth(from, to, Math.max(1, maxRows - 1));
  const first = Math.floor(from / width) * width;
  const count = Math.min(maxRows, Math.max(1, Math.ceil((to - first) / width)));
  const normal = new Uint32Array(count);
  const slow = new Uint32Array(count);
  const error = new Uint32Array(count);
  const max = new Uint32Array(count);
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    const t = buffer.ts[p] ?? 0;
    if (t < from || t >= to) {
      continue;
    }
    const b = Math.min(count - 1, Math.floor((t - first) / width));
    const status = buffer.status[p] ?? 0;
    if (status === STATUS_ERROR) {
      error[b] = (error[b] ?? 0) + 1;
    } else if (status === STATUS_SLOW) {
      slow[b] = (slow[b] ?? 0) + 1;
    } else {
      normal[b] = (normal[b] ?? 0) + 1;
    }
    const e = buffer.elapsed[p] ?? 0;
    if (e > (max[b] ?? 0)) {
      max[b] = e;
    }
  }
  const rows: BucketRow[] = [];
  for (let b = 0; b < count; b += 1) {
    const start = first + b * width;
    rows.push({
      from: Math.max(from, start),
      to: Math.min(to, start + width),
      normal: normal[b] ?? 0,
      slow: slow[b] ?? 0,
      error: error[b] ?? 0,
      maxElapsed: max[b] ?? 0,
    });
  }
  return rows;
}
