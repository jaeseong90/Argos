/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.hash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** XXH3-64 against reference vectors of the xxHash C library (10 U-17, DSN-10-18). */
@Tag("req:D-08")
class Xxh3Test {
  private static final String VECTORS = "xxh3-vectors.txt";

  @Test
  @Tag("req:DSN-10-18")
  @DisplayName("Xxh3 equals the reference xxHash 0.8.2 for every length class (86 vectors)")
  void referenceVectors() throws IOException {
    List<String[]> vectors = vectors();
    assertThat(vectors).hasSizeGreaterThanOrEqualTo(40);
    Set<Integer> lengths = new HashSet<Integer>();
    for (String[] vector : vectors) {
      byte[] data = data(vector);
      long expected = Long.parseUnsignedLong(vector[vector.length - 1], 16);
      assertThat(Long.toHexString(Xxh3.raw64(data, 0, data.length)))
          .as("%s", String.join(" ", vector))
          .isEqualTo(Long.toHexString(expected));
      // The same bytes at an offset inside a larger array.
      byte[] shifted = new byte[data.length + 11];
      System.arraycopy(data, 0, shifted, 7, data.length);
      assertThat(Xxh3.raw64(shifted, 7, data.length)).isEqualTo(expected);
      assertThat(Xxh3.hash64(data, 0, data.length)).isEqualTo(Xxh3.nonZero(expected));
      if (vector[0].equals("utf8")) {
        String text = new String(data, StandardCharsets.UTF_8);
        assertThat(Xxh3.hash64(text)).isEqualTo(Xxh3.nonZero(expected));
      }
      lengths.add(data.length);
    }
    // Every length class: 0, 1-3, 4-8, 9-16, 17-128, 129-240, > 240 with several stripes, one
    // block of 1,024 bytes, several blocks and a partial last block.
    assertThat(lengths)
        .contains(0, 1, 3, 4, 8, 9, 16, 17, 128, 129, 240, 241, 1024, 1025, 2049, 100003);
  }

  @Test
  @DisplayName("ids are never 0: the raw value 0 maps to 1, every other value is unchanged")
  void nonZeroRemap() {
    assertThat(Xxh3.nonZero(0)).isEqualTo(1);
    assertThat(Xxh3.nonZero(1)).isEqualTo(1);
    assertThat(Xxh3.nonZero(-1)).isEqualTo(-1);
    assertThat(Xxh3.nonZero(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE);
    assertThat(Xxh3.hash64("")).isEqualTo(0x2d06800538d394c2L);
  }

  @Test
  @DisplayName("unpaired surrogates hash like String.getBytes(UTF_8) ('?')")
  void unpairedSurrogate() {
    assertThat(Xxh3.hash64("a\uD800b")).isEqualTo(Xxh3.hash64("a?b"));
  }

  @Test
  @DisplayName("128-bit fold multiply matches BigInteger arithmetic")
  void foldMultiply() {
    long[] samples = {
      0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x9E3779B185EBCA87L, 0xFFFFFFFFL, 0x100000000L
    };
    BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    for (long a : samples) {
      for (long b : samples) {
        BigInteger product = unsigned(a).multiply(unsigned(b));
        long lo = product.and(mask).longValue();
        long hi = product.shiftRight(64).and(mask).longValue();
        assertThat(Xxh3.mul128Fold64(a, b)).isEqualTo(lo ^ hi);
      }
    }
  }

  @Test
  @DisplayName("an out-of-range slice is rejected before any byte is read")
  void bounds() {
    byte[] data = new byte[10];
    assertThatThrownBy(() -> Xxh3.raw64(data, 5, 6)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> Xxh3.raw64(data, -1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> Xxh3.raw64(data, 0, -1)).isInstanceOf(IndexOutOfBoundsException.class);
  }

  private static BigInteger unsigned(long value) {
    BigInteger big = BigInteger.valueOf(value);
    return value < 0 ? big.add(BigInteger.ONE.shiftLeft(64)) : big;
  }

  private static byte[] data(String[] vector) {
    switch (vector[0]) {
      case "pattern":
        {
          int length = Integer.parseInt(vector[1]);
          byte[] data = new byte[length];
          for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * 131 + 17) % 251);
          }
          return data;
        }
      case "fill":
        {
          byte value = (byte) Integer.parseInt(vector[1], 16);
          byte[] data = new byte[Integer.parseInt(vector[2])];
          Arrays.fill(data, value);
          return data;
        }
      case "utf8":
        {
          String hex = vector[1].equals("-") ? "" : vector[1];
          byte[] data = new byte[hex.length() / 2];
          for (int i = 0; i < data.length; i++) {
            data[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
          }
          return data;
        }
      default:
        throw new IllegalArgumentException("unknown vector kind " + vector[0]);
    }
  }

  private static List<String[]> vectors() throws IOException {
    List<String[]> vectors = new ArrayList<String[]>();
    try (InputStream in = Xxh3Test.class.getResourceAsStream(VECTORS)) {
      assertThat(in).as(VECTORS).isNotNull();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isEmpty() && !line.startsWith("#")) {
          vectors.add(line.trim().split(" ", -1));
        }
      }
    }
    return vectors;
  }
}
