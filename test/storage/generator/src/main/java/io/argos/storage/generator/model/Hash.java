/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

/**
 * Deterministic 64-bit hashing (SplitMix64 finalizer). Used for random stream seeds, dictionary
 * keys and per-event decisions (late batch, duplicate block) so that every decision is a pure
 * function of the seed and the event identity, independent of thread scheduling.
 */
public final class Hash {

  private static final long GOLDEN = 0x9E3779B97F4A7C15L;

  private Hash() {}

  /** SplitMix64 finalizer: a bijective, well-mixed function of {@code z}. */
  public static long mix(long z) {
    long x = z;
    x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
    x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
    return x ^ (x >>> 31);
  }

  /** Hash of a seed and a sequence of parts. */
  public static long of(long seed, long... parts) {
    long h = mix(seed + GOLDEN);
    for (long part : parts) {
      h = mix((h + GOLDEN) ^ part);
    }
    return h;
  }

  /** Hash of a seed and a text (UTF-16 code units). */
  public static long of(long seed, String text) {
    long h = mix(seed + GOLDEN);
    int i = 0;
    int n = text.length();
    while (i + 4 <= n) {
      long chunk =
          ((long) text.charAt(i) << 48)
              | ((long) text.charAt(i + 1) << 32)
              | ((long) text.charAt(i + 2) << 16)
              | text.charAt(i + 3);
      h = mix((h + GOLDEN) ^ chunk);
      i += 4;
    }
    long tail = n;
    while (i < n) {
      tail = (tail << 16) | text.charAt(i);
      i++;
    }
    return mix((h + GOLDEN) ^ tail ^ ((long) n << 56));
  }

  /** Uniform double in [0, 1) from a hash. */
  public static double unit(long h) {
    return (h >>> 11) * 0x1.0p-53;
  }
}
