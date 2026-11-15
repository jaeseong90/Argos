// SPDX-License-Identifier: Apache-2.0
//
// 64-bit identifiers (C-175): tx_id, service_id, sql_id, host_id and error group_id are unsigned
// 64-bit integers that the API sends as decimal strings. A JavaScript number holds integers
// exactly only up to 2^53, so the UI keeps them as opaque strings in state, URLs and cache keys
// and never calls Number() on them. Arithmetic goes through BigInt here.

const UNSIGNED_DECIMAL = /^(0|[1-9]\d{0,19})$/;
const U64_MAX = (1n << 64n) - 1n;
/** tx_id layout (DSN-02-09, D-11): 41-bit epoch ms ‖ 12-bit node ‖ 11-bit sequence. */
const TX_ID_TIME_SHIFT = 23n;

/** True when `value` is an unsigned 64-bit integer in canonical decimal form (no sign, no 0 pad). */
export function isUnsignedDecimalId(value: string): boolean {
  if (!UNSIGNED_DECIMAL.test(value)) {
    return false;
  }
  return BigInt(value) <= U64_MAX;
}

/**
 * The start time (epoch ms) encoded in a tx_id: `tx_id >> 23` (docs/14 §4.6, DSN-02-09). Returns
 * null for a value that is not a canonical unsigned 64-bit decimal string.
 */
export function txIdToEpochMs(txId: string): number | null {
  if (!isUnsignedDecimalId(txId)) {
    return null;
  }
  // 41 bits of milliseconds fit a number exactly (2^41 < 2^53).
  return Number(BigInt(txId) >> TX_ID_TIME_SHIFT);
}

/**
 * Compares two unsigned decimal ids numerically (for client sorting of complete lists). Invalid
 * ids sort after valid ones, then by string.
 */
export function compareDecimalIds(a: string, b: string): number {
  const validA = isUnsignedDecimalId(a);
  const validB = isUnsignedDecimalId(b);
  if (validA && validB) {
    const x = BigInt(a);
    const y = BigInt(b);
    return x < y ? -1 : x > y ? 1 : 0;
  }
  if (validA !== validB) {
    return validA ? -1 : 1;
  }
  return a < b ? -1 : a > b ? 1 : 0;
}
