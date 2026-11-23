// SPDX-License-Identifier: Apache-2.0
//
// Unsigned 64-bit ids (tx_id) between their JSON form, a decimal string (ADR-0010 C-175: REST, WS,
// CSV and URL; never a JSON number, never Number()), and the two Uint32 columns of XLogBuffer
// (txIdHi, txIdLo, docs/15 §7.1). The parser runs once per point of a 100,000-point response, so
// it works on 32-bit halves with exact double arithmetic instead of allocating a BigInt per id.

const TWO_32 = 0x1_0000_0000;
/** Digits consumed per step: 10^4 keeps lo × 10^4 + digits below 2^53 (exact in a double). */
const CHUNK = 4;
const POW10 = [1, 10, 100, 1000, 10_000];

/** The largest unsigned 64-bit value, 18446744073709551615. */
export const U64_MAX_DECIMAL = '18446744073709551615';

/** Writes the halves of a 64-bit id; returns false (and writes nothing) for an invalid one. */
export function parseU64Into(
  text: string,
  hi: Uint32Array,
  lo: Uint32Array,
  index: number,
): boolean {
  const length = text.length;
  if (length === 0 || length > 20) {
    return false;
  }
  if (length === 20 && text > U64_MAX_DECIMAL) {
    return false;
  }
  let h = 0;
  let l = 0;
  for (let start = 0; start < length; start += CHUNK) {
    const end = Math.min(length, start + CHUNK);
    let digits = 0;
    for (let i = start; i < end; i += 1) {
      const d = text.charCodeAt(i) - 48;
      if (d < 0 || d > 9) {
        return false;
      }
      digits = digits * 10 + d;
    }
    const m = POW10[end - start] ?? 1;
    const low = l * m + digits;
    const carry = Math.floor(low / TWO_32);
    l = low - carry * TWO_32;
    h = (h * m + carry) % TWO_32;
  }
  hi[index] = h;
  lo[index] = l;
  return true;
}

/** The halves of a 64-bit id, or null when the text is not one. */
export function parseU64(text: string): { hi: number; lo: number } | null {
  const hi = new Uint32Array(1);
  const lo = new Uint32Array(1);
  return parseU64Into(text, hi, lo, 0) ? { hi: hi[0] ?? 0, lo: lo[0] ?? 0 } : null;
}

/** The decimal string of a 64-bit id given as its halves (the detail route and API, C-175). */
export function formatU64(hi: number, lo: number): string {
  return ((BigInt(hi >>> 0) << 32n) | BigInt(lo >>> 0)).toString();
}
