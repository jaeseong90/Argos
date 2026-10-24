// SPDX-License-Identifier: Apache-2.0
//
// Expected selection for the /xlog-poc E2E test. The page generates its points in the browser from
// a seed; this module regenerates the same dataset in Node with the same generator and computes the
// selection of a drag with its own arithmetic (time bounds from the drag's pixel columns), without
// the page's hit index, scale or summary code. Only the seeded test data is shared.
import { createDummyDataset } from '../../src/features/xlog-poc/dummyData';
import { formatTxId } from '../../src/xlog/XLogBuffer';

/** Same range as the page's static dataset (XLogPocPage: 30 minutes ending on a whole minute). */
const STATIC_RANGE_MS = 30 * 60_000;

export interface OraclePoint {
  readonly txId: string;
  readonly ts: number;
  readonly elapsed: number;
  readonly status: number;
  readonly service: number;
}

export interface OracleDataset {
  readonly from: number;
  readonly to: number;
  readonly points: readonly OraclePoint[];
  readonly serviceNames: readonly string[];
}

/**
 * The page's static dataset: 30 minutes ending on the next whole minute after `now`. The page
 * takes its own `now`, which may fall in another minute; that only shifts every timestamp and both
 * range ends by the same amount (the generator places points at from + r × span with seeded r), so
 * a selection by pixel columns picks the same points.
 */
export function staticDataset(
  count: number,
  seed: number,
  now: number = Date.now(),
): OracleDataset {
  const to = Math.ceil(now / 60_000) * 60_000;
  const dataset = createDummyDataset({ count, seed, from: to - STATIC_RANGE_MS, to });
  const b = dataset.buffer;
  const points: OraclePoint[] = [];
  for (let logical = 0; logical < b.size; logical += 1) {
    const p = b.physical(logical);
    points.push({
      txId: formatTxId(b.txIdHi[p] ?? 0, b.txIdLo[p] ?? 0),
      ts: b.ts[p] ?? 0,
      elapsed: b.elapsed[p] ?? 0,
      status: b.status[p] ?? 0,
      service: b.service[p] ?? 0,
    });
  }
  return { from: dataset.from, to: dataset.to, points, serviceNames: dataset.dictionary.services };
}

/**
 * Points of a drag over the full plot height between the plot x positions x1 <= x2 (CSS px from
 * the plot's left edge, plot width `width`): every response time, time in [t(x1), t(x2)] with
 * t(x) = from + x / width × (to − from).
 */
export function selectColumns(
  data: OracleDataset,
  x1: number,
  x2: number,
  width: number,
): OraclePoint[] {
  const span = data.to - data.from;
  const t1 = data.from + (Math.max(0, Math.min(width, x1)) / width) * span;
  const t2 = data.from + (Math.max(0, Math.min(width, x2)) / width) * span;
  return data.points.filter((p) => p.ts >= t1 && p.ts <= t2);
}

export interface ServiceCount {
  readonly name: string;
  readonly count: number;
}

export function serviceCounts(data: OracleDataset, points: readonly OraclePoint[]): ServiceCount[] {
  const counts = new Map<number, number>();
  for (const p of points) {
    counts.set(p.service, (counts.get(p.service) ?? 0) + 1);
  }
  return [...counts]
    .map(([service, count]) => ({
      name: data.serviceNames[service] ?? `#${String(service)}`,
      count,
    }))
    .sort((a, b) => b.count - a.count);
}

export function statusCounts(points: readonly OraclePoint[]): [number, number, number] {
  const counts: [number, number, number] = [0, 0, 0];
  for (const p of points) {
    if (p.status === 0 || p.status === 1 || p.status === 2) {
      counts[p.status] += 1;
    }
  }
  return counts;
}
