/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.hash;

import java.nio.charset.StandardCharsets;

/**
 * XXH3-64 with seed 0 and the default secret on the server: the content hash of dictionary ids
 * (DSN-10-18, DSN-02-10, 11 §7) that the server computes itself, above all the {@code APP_VERSION}
 * key of {@code app_version_id = 0} (ADR-0010 C-143 (6)) and the error group id (ADR-0010 C-131:
 * {@code xxh3_64} over class id, normalized message and top frame id, through {@link #raw64(byte[],
 * int, int)} or {@link #hash64(byte[], int, int)}).
 *
 * <p>Origin (WO-21 decision, no new dependency): a copy of the agent's own implementation {@code
 * io.argos.agent.core.hash.Xxh3} (WO-10, 10 U-17), which is written from the XXH3 specification
 * (xxHash 0.8.x, {@code doc/xxhash_spec.md} and the algorithm description of {@code xxhash.h}) with
 * no code copied from the reference library; it is Argos code under Apache-2.0, so no NOTICE entry
 * applies. The server cannot depend on {@code agent-core} (agent module), and a library ({@code
 * zero-allocation-hashing}, {@code hash4j}) would add a dependency for one function. Both copies
 * must give identical values: {@code Xxh3Test} of the server runs the agent's reference vectors
 * ({@code agent/agent-core/src/test/resources/io/argos/agent/core/hash/xxh3-vectors.txt}, xxHash
 * 0.8.2 through python-xxhash 3.5.0) against this class. Change both or neither.
 *
 * <p>Ids never 0: 11 §4.4 rejects a dictionary key of 0 ({@code hash = 0} is {@code
 * INVALID_ARGUMENT}) and the agent uses 0 for "no name". {@link #hash64(String)} and {@link
 * #hash64(byte[], int, int)} map the raw value 0 to 1 exactly as the agent does ({@link
 * #nonZero(long)}); {@link #raw64(byte[], int, int)} returns the unmapped reference value.
 *
 * <p>Strings are hashed over {@code text.getBytes(UTF_8)}: an unpaired surrogate becomes {@code ?}
 * exactly as in the agent.
 */
public final class Xxh3 {
  private static final long PRIME32_1 = 0x9E3779B1L;
  private static final long PRIME32_2 = 0x85EBCA77L;
  private static final long PRIME32_3 = 0xC2B2AE3DL;
  private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
  private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
  private static final long PRIME64_3 = 0x165667B19E3779F9L;
  private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
  private static final long PRIME64_5 = 0x27D4EB2F165667C5L;
  private static final long PRIME_MX1 = 0x165667919E3779F9L;
  private static final long PRIME_MX2 = 0x9FB21C651E98DF25L;

  private static final int STRIPE_LEN = 64;
  private static final int SECRET_CONSUME_RATE = 8;
  private static final int SECRET_SIZE = 192;
  private static final int STRIPES_PER_BLOCK = (SECRET_SIZE - STRIPE_LEN) / SECRET_CONSUME_RATE;
  private static final int BLOCK_LEN = STRIPE_LEN * STRIPES_PER_BLOCK;
  private static final int SECRET_LASTACC_START = 7;
  private static final int SECRET_MERGEACCS_START = 11;
  private static final int MIDSIZE_MAX = 240;
  private static final int MIDSIZE_STARTOFFSET = 3;
  private static final int MIDSIZE_LASTOFFSET = 17;
  private static final int SECRET_SIZE_MIN = 136;

  /** The default 192-byte secret of XXH3 ({@code XXH3_kSecret}). */
  private static final byte[] SECRET = {
    (byte) 0xb8, (byte) 0xfe, (byte) 0x6c, (byte) 0x39, (byte) 0x23, (byte) 0xa4, (byte) 0x4b,
    (byte) 0xbe, (byte) 0x7c, (byte) 0x01, (byte) 0x81, (byte) 0x2c, (byte) 0xf7, (byte) 0x21,
    (byte) 0xad, (byte) 0x1c, (byte) 0xde, (byte) 0xd4, (byte) 0x6d, (byte) 0xe9, (byte) 0x83,
    (byte) 0x90, (byte) 0x97, (byte) 0xdb, (byte) 0x72, (byte) 0x40, (byte) 0xa4, (byte) 0xa4,
    (byte) 0xb7, (byte) 0xb3, (byte) 0x67, (byte) 0x1f, (byte) 0xcb, (byte) 0x79, (byte) 0xe6,
    (byte) 0x4e, (byte) 0xcc, (byte) 0xc0, (byte) 0xe5, (byte) 0x78, (byte) 0x82, (byte) 0x5a,
    (byte) 0xd0, (byte) 0x7d, (byte) 0xcc, (byte) 0xff, (byte) 0x72, (byte) 0x21, (byte) 0xb8,
    (byte) 0x08, (byte) 0x46, (byte) 0x74, (byte) 0xf7, (byte) 0x43, (byte) 0x24, (byte) 0x8e,
    (byte) 0xe0, (byte) 0x35, (byte) 0x90, (byte) 0xe6, (byte) 0x81, (byte) 0x3a, (byte) 0x26,
    (byte) 0x4c, (byte) 0x3c, (byte) 0x28, (byte) 0x52, (byte) 0xbb, (byte) 0x91, (byte) 0xc3,
    (byte) 0x00, (byte) 0xcb, (byte) 0x88, (byte) 0xd0, (byte) 0x65, (byte) 0x8b, (byte) 0x1b,
    (byte) 0x53, (byte) 0x2e, (byte) 0xa3, (byte) 0x71, (byte) 0x64, (byte) 0x48, (byte) 0x97,
    (byte) 0xa2, (byte) 0x0d, (byte) 0xf9, (byte) 0x4e, (byte) 0x38, (byte) 0x19, (byte) 0xef,
    (byte) 0x46, (byte) 0xa9, (byte) 0xde, (byte) 0xac, (byte) 0xd8, (byte) 0xa8, (byte) 0xfa,
    (byte) 0x76, (byte) 0x3f, (byte) 0xe3, (byte) 0x9c, (byte) 0x34, (byte) 0x3f, (byte) 0xf9,
    (byte) 0xdc, (byte) 0xbb, (byte) 0xc7, (byte) 0xc7, (byte) 0x0b, (byte) 0x4f, (byte) 0x1d,
    (byte) 0x8a, (byte) 0x51, (byte) 0xe0, (byte) 0x4b, (byte) 0xcd, (byte) 0xb4, (byte) 0x59,
    (byte) 0x31, (byte) 0xc8, (byte) 0x9f, (byte) 0x7e, (byte) 0xc9, (byte) 0xd9, (byte) 0x78,
    (byte) 0x73, (byte) 0x64, (byte) 0xea, (byte) 0xc5, (byte) 0xac, (byte) 0x83, (byte) 0x34,
    (byte) 0xd3, (byte) 0xeb, (byte) 0xc3, (byte) 0xc5, (byte) 0x81, (byte) 0xa0, (byte) 0xff,
    (byte) 0xfa, (byte) 0x13, (byte) 0x63, (byte) 0xeb, (byte) 0x17, (byte) 0x0d, (byte) 0xdd,
    (byte) 0x51, (byte) 0xb7, (byte) 0xf0, (byte) 0xda, (byte) 0x49, (byte) 0xd3, (byte) 0x16,
    (byte) 0x55, (byte) 0x26, (byte) 0x29, (byte) 0xd4, (byte) 0x68, (byte) 0x9e, (byte) 0x2b,
    (byte) 0x16, (byte) 0xbe, (byte) 0x58, (byte) 0x7d, (byte) 0x47, (byte) 0xa1, (byte) 0xfc,
    (byte) 0x8f, (byte) 0xf8, (byte) 0xb8, (byte) 0xd1, (byte) 0x7a, (byte) 0xd0, (byte) 0x31,
    (byte) 0xce, (byte) 0x45, (byte) 0xcb, (byte) 0x3a, (byte) 0x8f, (byte) 0x95, (byte) 0x16,
    (byte) 0x04, (byte) 0x28, (byte) 0xaf, (byte) 0xd7, (byte) 0xfb, (byte) 0xca, (byte) 0xbb,
    (byte) 0x4b, (byte) 0x40, (byte) 0x7e,
  };

  /** {@code S64[o]} = the little-endian 64-bit word of {@link #SECRET} at byte offset {@code o}. */
  private static final long[] S64 = new long[SECRET_SIZE - 7];

  static {
    for (int o = 0; o < S64.length; o++) {
      S64[o] = readLong(SECRET, o);
    }
  }

  /** Bit flip of 1-3 byte inputs: the 32-bit secret words at 0 and 4. */
  private static final long BITFLIP_1TO3 = readInt(SECRET, 0) ^ readInt(SECRET, 4);

  private Xxh3() {}

  /**
   * The dictionary id of {@code text}: XXH3-64 of its UTF-8 bytes, 0 mapped to 1. {@code text} must
   * not be null (callers use 0 for "no name" before they get here).
   */
  public static long hash64(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    return nonZero(raw64(bytes, 0, bytes.length));
  }

  /** The id of {@code length} bytes of {@code data} from {@code offset}: XXH3-64, 0 mapped to 1. */
  public static long hash64(byte[] data, int offset, int length) {
    return nonZero(raw64(data, offset, length));
  }

  /**
   * The unmapped XXH3-64 value (seed 0) of {@code length} bytes of {@code data} from {@code
   * offset}, equal to {@code XXH3_64bits()} of the reference library.
   *
   * @throws IndexOutOfBoundsException when the range is outside {@code data}
   */
  public static long raw64(byte[] data, int offset, int length) {
    if (offset < 0 || length < 0 || offset > data.length - length) {
      throw new IndexOutOfBoundsException(
          "range [" + offset + ", " + offset + " + " + length + ") of " + data.length + " bytes");
    }
    if (length <= 16) {
      return length0To16(data, offset, length);
    }
    if (length <= 128) {
      return length17To128(data, offset, length);
    }
    if (length <= MIDSIZE_MAX) {
      return length129To240(data, offset, length);
    }
    return hashLong(data, offset, length);
  }

  /** The 0 -> 1 remap of ids (11 §4.4: a dictionary key is never 0). */
  static long nonZero(long hash) {
    return hash == 0 ? 1 : hash;
  }

  private static long length0To16(byte[] in, int off, int len) {
    if (len > 8) {
      long bitflip1 = S64[24] ^ S64[32];
      long bitflip2 = S64[40] ^ S64[48];
      long inputLo = readLong(in, off) ^ bitflip1;
      long inputHi = readLong(in, off + len - 8) ^ bitflip2;
      long acc = len + Long.reverseBytes(inputLo) + inputHi + mul128Fold64(inputLo, inputHi);
      return avalanche(acc);
    }
    if (len >= 4) {
      long input1 = readInt(in, off);
      long input2 = readInt(in, off + len - 4);
      long bitflip = S64[8] ^ S64[16];
      long input64 = input2 + (input1 << 32);
      return rrmxmx(input64 ^ bitflip, len);
    }
    if (len > 0) {
      int c1 = in[off] & 0xFF;
      int c2 = in[off + (len >> 1)] & 0xFF;
      int c3 = in[off + len - 1] & 0xFF;
      long combined = ((c1 << 16) | (c2 << 24) | c3 | (len << 8)) & 0xFFFFFFFFL;
      return xxh64Avalanche(combined ^ BITFLIP_1TO3);
    }
    return xxh64Avalanche(S64[56] ^ S64[64]);
  }

  private static long length17To128(byte[] in, int off, int len) {
    long acc = len * PRIME64_1;
    if (len > 32) {
      if (len > 64) {
        if (len > 96) {
          acc += mix16(in, off + 48, 96);
          acc += mix16(in, off + len - 64, 112);
        }
        acc += mix16(in, off + 32, 64);
        acc += mix16(in, off + len - 48, 80);
      }
      acc += mix16(in, off + 16, 32);
      acc += mix16(in, off + len - 32, 48);
    }
    acc += mix16(in, off, 0);
    acc += mix16(in, off + len - 16, 16);
    return avalanche(acc);
  }

  private static long length129To240(byte[] in, int off, int len) {
    long acc = len * PRIME64_1;
    int rounds = len / 16;
    for (int i = 0; i < 8; i++) {
      acc += mix16(in, off + (16 * i), 16 * i);
    }
    acc = avalanche(acc);
    for (int i = 8; i < rounds; i++) {
      acc += mix16(in, off + (16 * i), (16 * (i - 8)) + MIDSIZE_STARTOFFSET);
    }
    acc += mix16(in, off + len - 16, SECRET_SIZE_MIN - MIDSIZE_LASTOFFSET);
    return avalanche(acc);
  }

  /**
   * Inputs above 240 bytes: 1,024-byte blocks of 16 stripes (the accumulators are scrambled after
   * each block), the stripes of the last partial block, then the last 64 bytes, and the merge of
   * the eight accumulators.
   */
  private static long hashLong(byte[] in, int off, int len) {
    long a0 = PRIME32_3;
    long a1 = PRIME64_1;
    long a2 = PRIME64_2;
    long a3 = PRIME64_3;
    long a4 = PRIME64_4;
    long a5 = PRIME32_2;
    long a6 = PRIME64_5;
    long a7 = PRIME32_1;
    int blocks = (len - 1) / BLOCK_LEN;
    int partialStripes = ((len - 1) - (BLOCK_LEN * blocks)) / STRIPE_LEN;
    int stripes = (blocks * STRIPES_PER_BLOCK) + partialStripes;
    // Stripe k starts at k * 64 (blocks are contiguous) and uses the secret at (k mod 16) * 8; the
    // extra last iteration is the final stripe over the last 64 bytes of the input.
    for (int k = 0; k <= stripes; k++) {
      int p;
      int s;
      if (k < stripes) {
        p = off + (k * STRIPE_LEN);
        s = (k % STRIPES_PER_BLOCK) * SECRET_CONSUME_RATE;
      } else {
        p = off + len - STRIPE_LEN;
        s = SECRET_SIZE - STRIPE_LEN - SECRET_LASTACC_START;
      }
      long d0 = readLong(in, p);
      long d1 = readLong(in, p + 8);
      long d2 = readLong(in, p + 16);
      long d3 = readLong(in, p + 24);
      long d4 = readLong(in, p + 32);
      long d5 = readLong(in, p + 40);
      long d6 = readLong(in, p + 48);
      long d7 = readLong(in, p + 56);
      long k0 = d0 ^ S64[s];
      long k1 = d1 ^ S64[s + 8];
      long k2 = d2 ^ S64[s + 16];
      long k3 = d3 ^ S64[s + 24];
      long k4 = d4 ^ S64[s + 32];
      long k5 = d5 ^ S64[s + 40];
      long k6 = d6 ^ S64[s + 48];
      long k7 = d7 ^ S64[s + 56];
      // acc[i ^ 1] += data[i]; acc[i] += lo32(key[i]) * hi32(key[i])
      a0 += d1 + ((k0 & 0xFFFFFFFFL) * (k0 >>> 32));
      a1 += d0 + ((k1 & 0xFFFFFFFFL) * (k1 >>> 32));
      a2 += d3 + ((k2 & 0xFFFFFFFFL) * (k2 >>> 32));
      a3 += d2 + ((k3 & 0xFFFFFFFFL) * (k3 >>> 32));
      a4 += d5 + ((k4 & 0xFFFFFFFFL) * (k4 >>> 32));
      a5 += d4 + ((k5 & 0xFFFFFFFFL) * (k5 >>> 32));
      a6 += d7 + ((k6 & 0xFFFFFFFFL) * (k6 >>> 32));
      a7 += d6 + ((k7 & 0xFFFFFFFFL) * (k7 >>> 32));
      if (k < stripes && (k % STRIPES_PER_BLOCK) == STRIPES_PER_BLOCK - 1) {
        // End of a full block (a partial block has at most 15 stripes).
        int t = SECRET_SIZE - STRIPE_LEN;
        a0 = scramble(a0, S64[t]);
        a1 = scramble(a1, S64[t + 8]);
        a2 = scramble(a2, S64[t + 16]);
        a3 = scramble(a3, S64[t + 24]);
        a4 = scramble(a4, S64[t + 32]);
        a5 = scramble(a5, S64[t + 40]);
        a6 = scramble(a6, S64[t + 48]);
        a7 = scramble(a7, S64[t + 56]);
      }
    }
    int m = SECRET_MERGEACCS_START;
    long result = len * PRIME64_1;
    result += mul128Fold64(a0 ^ S64[m], a1 ^ S64[m + 8]);
    result += mul128Fold64(a2 ^ S64[m + 16], a3 ^ S64[m + 24]);
    result += mul128Fold64(a4 ^ S64[m + 32], a5 ^ S64[m + 40]);
    result += mul128Fold64(a6 ^ S64[m + 48], a7 ^ S64[m + 56]);
    return avalanche(result);
  }

  private static long scramble(long acc, long key) {
    long value = acc ^ (acc >>> 47);
    value ^= key;
    return value * PRIME32_1;
  }

  private static long mix16(byte[] in, int p, int s) {
    long lo = readLong(in, p);
    long hi = readLong(in, p + 8);
    return mul128Fold64(lo ^ S64[s], hi ^ S64[s + 8]);
  }

  /** Low 64 bits XOR high 64 bits of the unsigned 128-bit product {@code a * b}. */
  static long mul128Fold64(long a, long b) {
    long aLo = a & 0xFFFFFFFFL;
    long aHi = a >>> 32;
    long bLo = b & 0xFFFFFFFFL;
    long bHi = b >>> 32;
    long loLo = aLo * bLo;
    long hiLo = aHi * bLo;
    long loHi = aLo * bHi;
    long hiHi = aHi * bHi;
    long cross = (loLo >>> 32) + (hiLo & 0xFFFFFFFFL) + loHi;
    long upper = (hiLo >>> 32) + (cross >>> 32) + hiHi;
    long lower = (cross << 32) | (loLo & 0xFFFFFFFFL);
    return lower ^ upper;
  }

  private static long avalanche(long hash) {
    long h = hash ^ (hash >>> 37);
    h *= PRIME_MX1;
    return h ^ (h >>> 32);
  }

  private static long xxh64Avalanche(long hash) {
    long h = hash ^ (hash >>> 33);
    h *= PRIME64_2;
    h ^= h >>> 29;
    h *= PRIME64_3;
    return h ^ (h >>> 32);
  }

  private static long rrmxmx(long hash, int len) {
    long h = hash ^ (Long.rotateLeft(hash, 49) ^ Long.rotateLeft(hash, 24));
    h *= PRIME_MX2;
    h ^= (h >>> 35) + len;
    h *= PRIME_MX2;
    return h ^ (h >>> 28);
  }

  private static long readLong(byte[] b, int p) {
    return (b[p] & 0xFFL)
        | ((b[p + 1] & 0xFFL) << 8)
        | ((b[p + 2] & 0xFFL) << 16)
        | ((b[p + 3] & 0xFFL) << 24)
        | ((b[p + 4] & 0xFFL) << 32)
        | ((b[p + 5] & 0xFFL) << 40)
        | ((b[p + 6] & 0xFFL) << 48)
        | ((b[p + 7] & 0xFFL) << 56);
  }

  private static long readInt(byte[] b, int p) {
    return (b[p] & 0xFFL)
        | ((b[p + 1] & 0xFFL) << 8)
        | ((b[p + 2] & 0xFFL) << 16)
        | ((b[p + 3] & 0xFFL) << 24);
  }
}
