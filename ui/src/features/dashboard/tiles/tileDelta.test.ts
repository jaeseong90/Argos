// SPDX-License-Identifier: Apache-2.0
//
// The tile delta formatter (ADR-0010 C-211 (3)): no comparison value → "none", |change| > 999 % →
// ">+999%" / "<−999%" with the comparison value recovered for the tooltip, otherwise the signed
// percent.
import { describe, expect, it } from 'vitest';

import { formatTileRaw, relativeTileDelta, TILE_DELTA_LIMIT } from './tileDelta';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };

describe('tile delta (ADR-0010 C-211 (3))', () => {
  it('T-UI-007 a missing change (no or 0 comparison value: the server sends null) is "none", never a percent', () => {
    expect(relativeTileDelta(null, 12, 'en')).toEqual({ kind: 'none' });
    expect(relativeTileDelta(undefined, 12, 'en')).toEqual({ kind: 'none' });
    expect(relativeTileDelta(Number.NaN, 12, 'en')).toEqual({ kind: 'none' });
    expect(relativeTileDelta(Number.POSITIVE_INFINITY, 12, 'en')).toEqual({ kind: 'none' });
  });

  it('T-UI-007 clamps beyond ±999 % and keeps both raw values (current and recovered previous)', () => {
    const big = relativeTileDelta(5333.46, 533.446, 'en');
    expect(big).toMatchObject({ kind: 'value', text: '>+999%', clamped: true });
    expect(big.kind === 'value' ? big.previous : null).toBeCloseTo(0.1, 6);
    const low = relativeTileDelta(-12, 5, 'en');
    expect(low).toMatchObject({ kind: 'value', text: '<−999%', clamped: true });
    expect(low.kind === 'value' ? low.previous : null).toBeCloseTo(-5 / 11, 9);
    expect(relativeTileDelta(10, null, 'en')).toEqual({
      kind: 'value',
      text: '>+999%',
      clamped: true,
      previous: null,
    });
  });

  it('T-UI-007 shows the signed percent up to and including ±999 %', () => {
    expect(TILE_DELTA_LIMIT).toBe(9.99);
    expect(relativeTileDelta(TILE_DELTA_LIMIT, 10.99, 'en')).toMatchObject({
      text: '+999%',
      clamped: false,
    });
    expect(relativeTileDelta(0.12, 112, 'en')).toMatchObject({
      kind: 'value',
      text: '+12%',
      clamped: false,
    });
    expect(relativeTileDelta(-0.05, 95, 'en')).toMatchObject({ text: '−5%', clamped: false });
    expect(relativeTileDelta(0, 7, 'en')).toMatchObject({ text: '0%', previous: 7 });
    const prev = relativeTileDelta(0.25, 125, 'en');
    expect(prev.kind === 'value' ? prev.previous : null).toBeCloseTo(100, 9);
    expect(relativeTileDelta(-1, 0, 'en')).toMatchObject({ text: '−100%', previous: null });
  });

  it('T-UI-007 the tooltip keeps a near-zero comparison value readable (3 significant digits)', () => {
    expect(formatTileRaw(12.5 / 5334.46, 'rate', 'en')).toBe('0.00234/s');
    expect(formatTileRaw(0.0123, 'ms', 'en')).toBe('0.0123ms');
    expect(formatTileRaw(12.5, 'rate', 'en')).toBe('12.5/s');
    expect(formatTileRaw(0, 'rate', 'en')).toBe('0/s');
    expect(formatTileRaw(300, 'ms', 'en')).toBe('300ms');
  });
});
