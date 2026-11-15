// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest';

import { compareDecimalIds, isUnsignedDecimalId, txIdToEpochMs } from './ids';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'D-11' };

/** Builds a tx_id the way the agent does (DSN-02-09): ms << 23 | node << 11 | seq. */
function txId(epochMs: number, node: number, seq: number): string {
  return ((BigInt(epochMs) << 23n) | (BigInt(node) << 11n) | BigInt(seq)).toString();
}

describe('64-bit ids (C-175, docs/14 §4.6)', () => {
  it('recovers the start time from a tx_id as tx_id >> 23 without losing precision', () => {
    const at = Date.UTC(2026, 10, 14, 1, 2, 3, 456);
    const id = txId(at, 4095, 2047);
    // The id is far above 2^53: Number(id) would round it.
    expect(BigInt(id) > BigInt(Number.MAX_SAFE_INTEGER)).toBe(true);
    expect(txIdToEpochMs(id)).toBe(at);
    expect(txIdToEpochMs(txId(at, 0, 0))).toBe(at);
  });

  it('rejects ids that are not canonical unsigned 64-bit decimals', () => {
    expect(txIdToEpochMs('')).toBeNull();
    expect(txIdToEpochMs('-1')).toBeNull();
    expect(txIdToEpochMs('0123')).toBeNull();
    expect(txIdToEpochMs('12a')).toBeNull();
    expect(txIdToEpochMs('18446744073709551616')).toBeNull();
    expect(isUnsignedDecimalId('18446744073709551615')).toBe(true);
    expect(isUnsignedDecimalId('0')).toBe(true);
  });

  it('compares ids numerically, not as text', () => {
    expect(compareDecimalIds('9', '10')).toBe(-1);
    expect(compareDecimalIds('18446744073709551615', '18446744073709551614')).toBe(1);
    expect(compareDecimalIds('42', '42')).toBe(0);
    expect(compareDecimalIds('x', '1')).toBe(1);
    const ids = ['100', '9', '18446744073709551615', '10'];
    expect([...ids].sort(compareDecimalIds)).toEqual(['9', '10', '100', '18446744073709551615']);
  });
});
