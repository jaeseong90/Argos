/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest.hash;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server's XXH3-64 against the agent's reference vectors (WO-10, xxHash 0.8.2): the same file
 * the agent's {@code Xxh3Test} reads, so the two copies cannot drift apart unnoticed.
 */
@Tag("req:D-08")
@Tag("req:DSN-02-10")
class Xxh3Test {

  private static final Path VECTORS =
      Path.of(
          System.getProperty("argos.repositoryRoot", ".."),
          "agent/agent-core/src/test/resources/io/argos/agent/core/hash/xxh3-vectors.txt");

  @Test
  @DisplayName("server Xxh3 equals the agent's reference vectors for every length class")
  void agentReferenceVectors() throws IOException {
    List<String[]> vectors = new ArrayList<>();
    for (String line : Files.readAllLines(VECTORS, StandardCharsets.UTF_8)) {
      if (!line.isBlank() && !line.startsWith("#")) {
        vectors.add(line.strip().split(" ", -1));
      }
    }
    assertThat(vectors).hasSizeGreaterThanOrEqualTo(40);
    Set<Integer> lengths = new HashSet<>();
    for (String[] vector : vectors) {
      byte[] data = data(vector);
      long expected = Long.parseUnsignedLong(vector[vector.length - 1], 16);
      assertThat(Xxh3.raw64(data, 0, data.length))
          .as("%s", String.join(" ", vector))
          .isEqualTo(expected);
      byte[] shifted = new byte[data.length + 11];
      System.arraycopy(data, 0, shifted, 7, data.length);
      assertThat(Xxh3.raw64(shifted, 7, data.length)).isEqualTo(expected);
      assertThat(Xxh3.hash64(data, 0, data.length)).isEqualTo(expected == 0 ? 1 : expected);
      if (vector[0].equals("utf8")) {
        assertThat(Xxh3.hash64(new String(data, StandardCharsets.UTF_8)))
            .isEqualTo(expected == 0 ? 1 : expected);
      }
      lengths.add(data.length);
    }
    assertThat(lengths)
        .contains(0, 1, 3, 4, 8, 9, 16, 17, 128, 129, 240, 241, 1024, 1025, 2049, 100003);
  }

  @Test
  @DisplayName("ids are never 0 and strings hash over their UTF-8 bytes like the agent")
  void idRules() {
    assertThat(Xxh3.nonZero(0)).isEqualTo(1);
    assertThat(Xxh3.nonZero(-1)).isEqualTo(-1);
    assertThat(Xxh3.hash64("")).isEqualTo(0x2d06800538d394c2L);
    assertThat(Xxh3.hash64("a\uD800b")).isEqualTo(Xxh3.hash64("a?b"));
  }

  private static byte[] data(String[] vector) {
    return switch (vector[0]) {
      case "pattern" -> pattern(Integer.parseInt(vector[1]));
      case "fill" -> fill((byte) Integer.parseInt(vector[1], 16), Integer.parseInt(vector[2]));
      case "utf8" -> vector[1].equals("-") ? new byte[0] : HexFormat.of().parseHex(vector[1]);
      default -> throw new IllegalArgumentException("unknown vector kind " + vector[0]);
    };
  }

  private static byte[] pattern(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) ((i * 131 + 17) % 251);
    }
    return data;
  }

  private static byte[] fill(byte value, int length) {
    byte[] data = new byte[length];
    Arrays.fill(data, value);
    return data;
  }
}
