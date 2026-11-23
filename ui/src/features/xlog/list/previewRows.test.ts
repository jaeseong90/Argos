// SPDX-License-Identifier: Apache-2.0
//
// List preview from the chart's points (ADR-0010 C-204, ADR-0003 C-36): the first rows of the URL
// selection in the server's order (elapsed_ms:desc by default, ts:desc; ties by ts and the unsigned
// 64-bit tx_id like the search's ORDER BY), inclusive bounds like the server search, names and keys
// from the local dictionary, 64-bit transaction ids exact (C-175), the same rows as a brute-force
// sort of the selection, and only the rows that are exactly the server's first rows (exactPreview:
// unsampled points, else the slow points of a sample, never truncated points).
import { describe, expect, it } from 'vitest';

import type { Selection } from '../../../state/urlState';
import { KIND_BATCH, KIND_HTTP, STATUS_ERROR, XLogBuffer } from '../../../xlog/XLogBuffer';
import { LocalDictionary } from '../data/dictionary';
import { parseU64 } from '../data/u64';

import {
  canPreview,
  exactPreview,
  PREVIEW_COLUMNS,
  previewIndices,
  previewRows,
} from './previewRows';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const T0 = 1_763_000_000_000;

function bufferOf(points: { ts: number; elapsed: number; status?: number; kind?: number }[]) {
  const buffer = new XLogBuffer(points.length + 4);
  buffer.append(
    points.map((p, i) => ({
      ts: p.ts,
      elapsed: p.elapsed,
      status: p.status ?? 0,
      instance: i % 2,
      service: i % 3,
      txIdLo: i,
      txIdHi: 0,
      kind: p.kind ?? KIND_HTTP,
      httpStatus: 0,
      errorClass: 0,
    })),
  );
  return buffer;
}

function dictionary(): LocalDictionary {
  const d = new LocalDictionary();
  // Local indices follow first use: service 0 = 17, 1 = u64 max, 2 = 42; instance 0 = 12, 1 = 13.
  d.serviceIndex('17');
  d.serviceIndex('18446744073709551615');
  d.serviceIndex('42');
  d.instanceIndex(12);
  d.instanceIndex(13);
  d.nameServices({
    '17': 'GET /a',
    '18446744073709551615': 'GET /q?<img src=x onerror=alert(1)>',
    '42': 'POST /b',
  });
  d.nameInstances({ '12': 'web-01', '13': 'web-02' });
  return d;
}

describe('list preview rows (C-204)', () => {
  const points = Array.from({ length: 200 }, (_, i) => ({
    ts: T0 + i * 1000,
    elapsed: (i * 7919) % 5000,
    status: i % 17 === 0 ? STATUS_ERROR : 0,
    kind: i % 50 === 0 ? KIND_BATCH : KIND_HTTP,
  }));
  const sel: Selection = { msMin: 100, msMax: 4000, tsFrom: T0 + 10_000, tsTo: T0 + 150_000 };

  it('T-UI-001 takes the first rows of the selection in the server order, like a full sort', () => {
    const buffer = bufferOf(points);
    for (const sort of ['elapsed_ms:desc', 'ts:desc'] as const) {
      const inSel = points
        .map((p, i) => ({ ...p, i }))
        .filter(
          (p) =>
            p.ts >= sel.tsFrom &&
            p.ts <= sel.tsTo &&
            p.elapsed >= sel.msMin &&
            p.elapsed <= sel.msMax,
        );
      const key = (p: { ts: number; elapsed: number }) =>
        sort === 'ts:desc' ? [p.ts, p.elapsed] : [p.elapsed, p.ts];
      const expected = [...inSel]
        .sort((a, b) => {
          const [a1, a2] = key(a);
          const [b1, b2] = key(b);
          return (b1 ?? 0) - (a1 ?? 0) || (b2 ?? 0) - (a2 ?? 0) || b.i - a.i;
        })
        .slice(0, 20)
        .map((p) => p.i);
      expect(previewIndices(buffer, sel, sort, 20)).toEqual(expected);
    }
  });

  it('T-UI-001 includes points exactly on the selection edges (inclusive bounds, docs/15 §7.3)', () => {
    const buffer = bufferOf([
      { ts: T0, elapsed: 100 },
      { ts: T0 + 1000, elapsed: 4000 },
      { ts: T0 + 1001, elapsed: 4001 },
      { ts: T0 - 1, elapsed: 200 },
    ]);
    const edge: Selection = { msMin: 100, msMax: 4000, tsFrom: T0, tsTo: T0 + 1000 };
    expect(previewIndices(buffer, edge, 'elapsed_ms:desc')).toEqual([1, 0]);
    expect(previewIndices(buffer, edge, 'elapsed_ms:desc', 0)).toEqual([]);
  });

  it('T-UI-001 fills the point columns from the dictionary and leaves the rest to the server', () => {
    const buffer = bufferOf([
      { ts: T0 + 500, elapsed: 900, status: STATUS_ERROR },
      { ts: T0 + 600, elapsed: 300 },
    ]);
    buffer.txIdHi[0] = 0xffffffff;
    buffer.txIdLo[0] = 0xffffffff;
    const rows = previewRows(
      buffer,
      dictionary(),
      { msMin: 0, msMax: 1000, tsFrom: T0, tsTo: T0 + 1000 },
      'elapsed_ms:desc',
    );
    expect(rows).toHaveLength(2);
    const [first, second] = rows;
    expect(first?.tx_id).toBe('18446744073709551615');
    expect(parseU64(first?.tx_id ?? '')).toEqual({ hi: 0xffffffff, lo: 0xffffffff });
    expect(first?.ts).toBe(new Date(T0 + 500).toISOString());
    expect(first?.elapsed_ms).toBe(900);
    expect(first?.error).toBe(true);
    expect(first?.service).toBe('GET /a');
    expect(first?.service_id).toBe('17');
    expect(first?.instance).toBe('web-01');
    expect(first?.instance_id).toBe(12);
    expect(first?.kind).toBe('HTTP');
    // The collected name stays the exact text (rendered through SafeText by the table).
    expect(second?.service).toBe('GET /q?<img src=x onerror=alert(1)>');
    expect(second?.service_id).toBe('18446744073709551615');
    expect(second?.instance_id).toBe(13);
    // Columns the points do not carry are pending in the list, never shown from these rows.
    expect([...PREVIEW_COLUMNS].sort()).toEqual(
      ['elapsed_ms', 'instance', 'kind', 'service', 'ts', 'tx_id'].sort(),
    );
  });
});

/** A buffer of points with explicit 64-bit transaction ids (hi, lo words). */
function bufferWithIds(
  points: { ts: number; elapsed: number; hi: number; lo: number; status?: number }[],
): XLogBuffer {
  const buffer = new XLogBuffer(points.length + 4);
  buffer.append(
    points.map((p) => ({
      ts: p.ts,
      elapsed: p.elapsed,
      status: p.status ?? 0,
      instance: 0,
      service: 0,
      txIdLo: p.lo,
      txIdHi: p.hi,
      kind: KIND_HTTP,
      httpStatus: 0,
      errorClass: 0,
    })),
  );
  return buffer;
}

/** The server's search order (docs/14 §4.6, transactions/search.*.sql ORDER BY) of the same ids. */
function serverOrder(
  points: readonly { ts: number; elapsed: number; hi: number; lo: number }[],
  sort: 'elapsed_ms:desc' | 'ts:desc',
): number[] {
  const id = (p: { hi: number; lo: number }) => BigInt(p.hi) * 2n ** 32n + BigInt(p.lo);
  return points
    .map((p, i) => ({ ...p, i }))
    .sort((a, b) => {
      if (sort === 'elapsed_ms:desc' && a.elapsed !== b.elapsed) {
        return b.elapsed - a.elapsed;
      }
      if (a.ts !== b.ts) {
        return b.ts - a.ts;
      }
      const ia = id(a);
      const ib = id(b);
      return ia === ib ? 0 : ia > ib ? -1 : 1;
    })
    .map((p) => p.i);
}

describe('list preview order and exactness (C-204, fe-perf-3)', () => {
  const all: Selection = { msMin: 0, msMax: 100_000, tsFrom: T0, tsTo: T0 + 60_000 };

  it('T-UI-001 breaks equal response times like the server: newer first, then the larger unsigned 64-bit id', () => {
    // Equal elapsed and equal ts: the transaction id decides, as an unsigned 64-bit number (a high
    // word with its top bit set is larger than any smaller high word, the low word decides last).
    const points = [
      { ts: T0 + 1000, elapsed: 700, hi: 0x00000001, lo: 0xffffffff },
      { ts: T0 + 1000, elapsed: 700, hi: 0x80000000, lo: 0x00000000 },
      { ts: T0 + 1000, elapsed: 700, hi: 0x00000001, lo: 0x00000002 },
      { ts: T0 + 2000, elapsed: 700, hi: 0, lo: 5 },
      { ts: T0 + 500, elapsed: 900, hi: 0, lo: 1 },
      { ts: T0 + 1000, elapsed: 700, hi: 0xffffffff, lo: 0xffffffff },
    ];
    const buffer = bufferWithIds(points);
    expect(previewIndices(buffer, all, 'elapsed_ms:desc')).toEqual(
      serverOrder(points, 'elapsed_ms:desc'),
    );
    expect(previewIndices(buffer, all, 'elapsed_ms:desc')).toEqual([4, 3, 5, 1, 0, 2]);
    // The time order has no response time key: equal times go by the id alone.
    expect(previewIndices(buffer, all, 'ts:desc')).toEqual(serverOrder(points, 'ts:desc'));
    expect(previewIndices(buffer, all, 'ts:desc')).toEqual([3, 5, 1, 0, 2, 4]);
  });

  it('T-UI-001 keeps the server order for many equal values (brute-force comparison)', () => {
    const points = Array.from({ length: 300 }, (_, i) => ({
      ts: T0 + (i % 7) * 1000,
      elapsed: 100 * (i % 5),
      hi: (i * 2_654_435_761) >>> 0,
      lo: (i * 40_503) >>> 0,
    }));
    const buffer = bufferWithIds(points);
    for (const sort of ['elapsed_ms:desc', 'ts:desc'] as const) {
      expect(previewIndices(buffer, all, sort, 20)).toEqual(serverOrder(points, sort).slice(0, 20));
    }
  });

  const SLOW = 1000;
  /** Rows of the elapsed_ms:desc preview with these response times (already in order). */
  const rowsOf = (elapsed: number[]) => elapsed.map((e) => ({ elapsed_ms: e }));

  it('T-UI-001 previews unsampled points in both orders', () => {
    const rows = rowsOf([1500, 900, 300]);
    expect(exactPreview(rows, 'elapsed_ms:desc', { sampled: false })).toEqual(rows);
    expect(exactPreview(rows, 'ts:desc', { sampled: false })).toEqual(rows);
    expect(canPreview('ts:desc', { sampled: false })).toBe(true);
  });

  it('T-UI-001 previews no row of a sampled selection without a slow point', () => {
    // Every point is below the threshold: the sample thinned them, so even the first row can be
    // one the sample left out.
    const sampling = { sampled: true, truncated: false, slowThresholdMs: SLOW };
    expect(exactPreview(rowsOf([999, 800, 10]), 'elapsed_ms:desc', sampling)).toEqual([]);
    expect(canPreview('elapsed_ms:desc', sampling)).toBe(true);
  });

  it('T-UI-001 previews only the slow points of a sampled selection with fewer than 20 of them', () => {
    const sampling = { sampled: true, truncated: false, slowThresholdMs: SLOW };
    const elapsed = [5000, 3000, 1000, 999, 700, 500];
    expect(exactPreview(rowsOf(elapsed), 'elapsed_ms:desc', sampling)).toEqual(
      rowsOf([5000, 3000, 1000]),
    );
    // With the real buffer: 3 slow points (one exactly at the threshold) and 30 normal ones.
    const points = [
      ...Array.from({ length: 30 }, (_, i) => ({
        ts: T0 + i * 100,
        elapsed: 100 + i,
        hi: 0,
        lo: i,
      })),
      { ts: T0 + 10, elapsed: SLOW, hi: 0, lo: 100 },
      { ts: T0 + 20, elapsed: 4000, hi: 0, lo: 101 },
      { ts: T0 + 30, elapsed: 2500, hi: 0, lo: 102 },
    ];
    const rows = previewRows(bufferWithIds(points), dictionary(), all, 'elapsed_ms:desc');
    expect(rows).toHaveLength(20);
    const exact = exactPreview(rows, 'elapsed_ms:desc', sampling);
    expect(exact.map((r) => r.tx_id)).toEqual(['101', '102', '100']);
  });

  it('T-UI-001 previews no row of truncated points, of the time order of a sample, or with unknown sampling facts', () => {
    const rows = rowsOf([5000, 3000, 1000]);
    // Truncated: slow points can be missing too.
    const truncated = { sampled: true, truncated: true, slowThresholdMs: SLOW };
    expect(exactPreview(rows, 'elapsed_ms:desc', truncated)).toEqual([]);
    expect(canPreview('elapsed_ms:desc', truncated)).toBe(false);
    expect(exactPreview(rows, 'elapsed_ms:desc', { sampled: false, truncated: true })).toEqual([]);
    // The time order of a sample: a thinned normal point can be the newest.
    const sampled = { sampled: true, truncated: false, slowThresholdMs: SLOW };
    expect(exactPreview(rows, 'ts:desc', sampled)).toEqual([]);
    expect(canPreview('ts:desc', sampled)).toBe(false);
    // Unknown threshold or truncation of a sample: nothing (fail closed).
    expect(exactPreview(rows, 'elapsed_ms:desc', { sampled: true, truncated: false })).toEqual([]);
    expect(exactPreview(rows, 'elapsed_ms:desc', { sampled: true, slowThresholdMs: SLOW })).toEqual(
      [],
    );
    expect(
      exactPreview(rows, 'elapsed_ms:desc', {
        sampled: true,
        truncated: false,
        slowThresholdMs: null,
      }),
    ).toEqual([]);
    expect(canPreview('elapsed_ms:desc', { sampled: true })).toBe(false);
  });
});
