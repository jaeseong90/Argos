// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { createRandom } from '../features/xlog-poc/dummyData';

import { CELL_SIZE, HitIndex, HOVER_RADIUS } from './hitIndex';
import { createScale, inDataRect, pixelRectToData, xOf, yOf, type PlotScale } from './scale';
import { XLogBuffer, type XLogPoint } from './XLogBuffer';

const FROM = 1_700_000_000_000;
const TO = FROM + 60_000;

function randomBuffer(count: number, seed: number, capacity = count): XLogBuffer {
  const random = createRandom(seed);
  const buffer = new XLogBuffer(capacity);
  const points: XLogPoint[] = [];
  for (let i = 0; i < count; i += 1) {
    points.push({
      ts: FROM - 5000 + random() * 70_000,
      elapsed: Math.floor(Math.exp(random() * 9)),
      status: 0,
      instance: 0,
      service: 0,
      txIdLo: i,
      txIdHi: 0,
      kind: 1,
      httpStatus: 200,
      errorClass: 0,
    });
  }
  points.sort((a, b) => a.ts - b.ts);
  buffer.append(points);
  return buffer;
}

function bruteNearest(buffer: XLogBuffer, scale: PlotScale, x: number, y: number, radius: number) {
  let best = -1;
  let bestDistance = radius * radius;
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    const ts = buffer.ts[p] ?? 0;
    if (ts < scale.from || ts > scale.to) {
      continue;
    }
    const dx = xOf(scale, ts) - x;
    const dy = yOf(scale, buffer.elapsed[p] ?? 0) - y;
    const d = dx * dx + dy * dy;
    if (d <= bestDistance) {
      bestDistance = d;
      best = p;
    }
  }
  return { best, distance: bestDistance };
}

describe('HitIndex (docs/15 §7.3, 8 px grid)', () => {
  const scale = createScale(FROM, TO, 800, 300, 5000, 'linear');

  it('indexes only the points inside the time range', () => {
    const buffer = randomBuffer(5000, 1);
    const index = new HitIndex();
    index.build(buffer, scale);
    let inside = 0;
    for (let i = 0; i < buffer.size; i += 1) {
      const ts = buffer.ts[i] ?? 0;
      if (ts >= FROM && ts <= TO) {
        inside += 1;
      }
    }
    expect(index.size).toBe(inside);
    expect(CELL_SIZE).toBe(8);
  });

  it('finds the nearest point within the hover radius, like a brute-force search', () => {
    const buffer = randomBuffer(20_000, 2);
    const index = new HitIndex();
    index.build(buffer, scale);
    const random = createRandom(99);
    let hits = 0;
    for (let k = 0; k < 500; k += 1) {
      const x = random() * 800;
      const y = random() * 300;
      const found = index.nearest(x, y);
      const expected = bruteNearest(buffer, scale, x, y, HOVER_RADIUS);
      if (expected.best === -1) {
        expect(found).toBe(-1);
        continue;
      }
      hits += 1;
      expect(found).toBeGreaterThanOrEqual(0);
      const position = index.position(found);
      const d = (position.x - x) ** 2 + (position.y - y) ** 2;
      // Same distance as the brute-force optimum (ties may pick a different point).
      expect(d).toBeCloseTo(expected.distance, 2);
    }
    expect(hits).toBeGreaterThan(50);
  });

  it('returns -1 for an empty index', () => {
    const index = new HitIndex();
    index.build(new XLogBuffer(4), scale);
    expect(index.nearest(10, 10)).toBe(-1);
    expect(
      index.queryRect(new XLogBuffer(4), scale, { x1: 0, y1: 0, x2: 800, y2: 300 }),
    ).toHaveLength(0);
  });

  it.each([
    ['a small box', { x1: 100, y1: 50, x2: 180, y2: 120 }],
    ['the whole plot', { x1: 0, y1: 0, x2: 800, y2: 300 }],
    ['a box at the top edge', { x1: 400, y1: 0, x2: 799, y2: 30 }],
    ['a thin column', { x1: 333.3, y1: 10, x2: 334.1, y2: 290 }],
  ])('selects exactly the points of %s (drag selection)', (_, rect) => {
    const buffer = randomBuffer(20_000, 3);
    const index = new HitIndex();
    index.build(buffer, scale);
    const selected = Array.from(index.queryRect(buffer, scale, rect)).sort((a, b) => a - b);
    const data = pixelRectToData(scale, rect);
    const expected: number[] = [];
    for (let p = 0; p < buffer.size; p += 1) {
      if (inDataRect(data, buffer.ts[p] ?? 0, buffer.elapsed[p] ?? 0)) {
        expected.push(p);
      }
    }
    expect(selected).toEqual(expected);
  });

  it('works on a wrapped ring buffer and rebuilds after the buffer changes', () => {
    const buffer = randomBuffer(3000, 4, 2000);
    expect(buffer.overwritten).toBe(1000);
    const index = new HitIndex();
    index.ensure(buffer, scale);
    expect(index.isCurrent(buffer, scale)).toBe(true);
    const all = index.queryRect(buffer, scale, { x1: 0, y1: 0, x2: 800, y2: 300 });
    expect(all.length).toBe(index.size);
    buffer.evictBefore(FROM + 30_000);
    expect(index.isCurrent(buffer, scale)).toBe(false);
    index.ensure(buffer, scale);
    for (const p of index.queryRect(buffer, scale, { x1: 0, y1: 0, x2: 800, y2: 300 })) {
      expect(buffer.ts[p]).toBeGreaterThanOrEqual(FROM + 30_000);
    }
  });

  it('selects 100,000 points in a few milliseconds (budget 5 ms, docs/15 §7.3)', () => {
    const buffer = randomBuffer(100_000, 5);
    const index = new HitIndex();
    index.build(buffer, scale);
    const start = performance.now();
    const selected = index.queryRect(buffer, scale, { x1: 0, y1: 0, x2: 800, y2: 300 });
    const elapsed = performance.now() - start;
    expect(selected.length).toBe(index.size);
    // Recorded, not asserted against 5 ms: CI machines vary; the browser measurement of the page
    // (queryMs of a full-plot drag in perf/xlog-frame.spec.ts, docs/poc/xlog-ui.md §4.5 "선택 계산")
    // is the reference. A gross regression (> 250 ms) still fails.
    expect(elapsed).toBeLessThan(250);
  });

  it('reports whether ensure rebuilt the index: once per buffer or scale change (WO-33 r1-new-3)', () => {
    const buffer = randomBuffer(3000, 7, 4000);
    const index = new HitIndex();
    expect(index.ensure(buffer, scale)).toBe(true);
    expect(index.ensure(buffer, scale)).toBe(false);
    // The same scale values in a new object: still current.
    expect(index.ensure(buffer, createScale(FROM, TO, 800, 300, 5000, 'linear'))).toBe(false);
    expect(index.ensure(buffer, createScale(FROM, TO, 790, 300, 5000, 'linear'))).toBe(true);
    expect(index.ensure(buffer, scale)).toBe(true);
    buffer.append([
      {
        ts: FROM + 1000,
        elapsed: 10,
        status: 0,
        instance: 0,
        service: 0,
        txIdLo: 99_999,
        txIdHi: 0,
        kind: 1,
        httpStatus: 200,
        errorClass: 0,
      },
    ]);
    expect(index.ensure(buffer, scale)).toBe(true);
    expect(index.ensure(buffer, scale)).toBe(false);
  });

  it.each([
    ['a small box', { x1: 100, y1: 50, x2: 180, y2: 120 }],
    ['the whole plot', { x1: 0, y1: 0, x2: 800, y2: 300 }],
    ['an empty box', { x1: 10, y1: 290, x2: 12, y2: 291 }],
  ])(
    'queries %s in place: the count and copyMatches equal queryData and a brute-force scan',
    (_, rect) => {
      const buffer = randomBuffer(20_000, 8);
      const index = new HitIndex();
      index.build(buffer, scale);
      const data = pixelRectToData(scale, rect);
      const expected: number[] = [];
      for (let p = 0; p < buffer.size; p += 1) {
        if (inDataRect(data, buffer.ts[p] ?? 0, buffer.elapsed[p] ?? 0)) {
          expected.push(p);
        }
      }
      const found = index.queryDataInPlace(buffer, rect, data);
      const token = index.queryToken;
      expect(found).toBe(expected.length);
      const copied = index.copyMatches(token, found);
      expect(copied).not.toBeNull();
      expect(Array.from(copied ?? []).sort((a, b) => a - b)).toEqual(expected);
      // The same order as the copying query (cells row by row).
      expect(Array.from(index.queryData(buffer, rect, data))).toEqual(Array.from(copied ?? []));
    },
  );

  it('copies a query in place only until a later query writes the scratch', () => {
    const buffer = randomBuffer(5000, 9);
    const index = new HitIndex();
    index.build(buffer, scale);
    const whole = { x1: 0, y1: 0, x2: 800, y2: 300 };
    const found = index.queryDataInPlace(buffer, whole, pixelRectToData(scale, whole));
    const token = index.queryToken;
    const first = index.copyMatches(token, found);
    expect(first?.length).toBe(index.size);
    // A copy is its own array; asking again gives an equal one.
    expect(Array.from(index.copyMatches(token, found) ?? [])).toEqual(Array.from(first ?? []));
    const part = { x1: 100, y1: 50, x2: 180, y2: 120 };
    index.queryDataInPlace(buffer, part, pixelRectToData(scale, part));
    expect(index.queryToken).not.toBe(token);
    expect(index.copyMatches(token, found)).toBeNull();
    // A copying query also ends the earlier token, and the first copy kept its contents.
    const token2 = index.queryToken;
    index.queryData(buffer, whole, pixelRectToData(scale, whole));
    expect(index.copyMatches(token2, 1)).toBeNull();
    expect(first?.length).toBe(index.size);
  });

  it('queries in place after eviction from a wrapped ring: only the remaining points, like a brute-force scan', () => {
    const buffer = randomBuffer(3000, 10, 2000);
    const index = new HitIndex();
    const whole = { x1: 0, y1: 0, x2: 800, y2: 300 };
    expect(index.ensure(buffer, scale)).toBe(true);
    buffer.evictBefore(FROM + 30_000);
    expect(index.ensure(buffer, scale)).toBe(true);
    const data = pixelRectToData(scale, whole);
    const found = index.queryDataInPlace(buffer, whole, data);
    const copied = Array.from(index.copyMatches(index.queryToken, found) ?? []).sort(
      (a, b) => a - b,
    );
    const expected: number[] = [];
    for (let logical = 0; logical < buffer.size; logical += 1) {
      const p = buffer.physical(logical);
      const ts = buffer.ts[p] ?? 0;
      if (ts >= FROM && ts <= TO && inDataRect(data, ts, buffer.elapsed[p] ?? 0)) {
        expected.push(p);
      }
    }
    expect(copied).toEqual(expected.sort((a, b) => a - b));
    for (const p of copied) {
      expect(buffer.ts[p]).toBeGreaterThanOrEqual(FROM + 30_000);
    }
  });

  it('returns its own array: a later query does not change an earlier result', () => {
    const buffer = randomBuffer(5000, 6);
    const index = new HitIndex();
    index.build(buffer, scale);
    const all = index.queryRect(buffer, scale, { x1: 0, y1: 0, x2: 800, y2: 300 });
    const copy = Array.from(all);
    const part = index.queryRect(buffer, scale, { x1: 100, y1: 50, x2: 180, y2: 120 });
    expect(part.length).toBeGreaterThan(0);
    expect(part.length).toBeLessThan(all.length);
    expect(Array.from(all)).toEqual(copy);
    expect(all.buffer).not.toBe(part.buffer);
  });
});
