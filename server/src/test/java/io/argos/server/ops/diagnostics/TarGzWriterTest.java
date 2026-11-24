/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ustar writer of the diagnostic bundle (12 §9.5): entries read back byte for byte by an
 * independent reader of the format, mode 0600, valid header checksums, long names split into prefix
 * and name, end-of-archive blocks, refused names.
 */
@Tag("req:SEC-06")
class TarGzWriterTest {

  @TempDir Path dir;

  /** One entry read back. */
  static final class Entry {
    private final String name;
    private final int mode;
    private final byte[] data;

    Entry(String name, int mode, byte[] data) {
      this.name = name;
      this.mode = mode;
      this.data = data.clone();
    }

    String name() {
      return name;
    }

    int mode() {
      return mode;
    }

    byte[] data() {
      return data.clone();
    }
  }

  /** A reader of the format, independent of the writer (checks magic and checksum). */
  static Map<String, Entry> read(byte[] gz) throws IOException {
    Map<String, Entry> out = new LinkedHashMap<>();
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
      byte[] all = in.readAllBytes();
      int pos = 0;
      int zeros = 0;
      while (pos + 512 <= all.length) {
        byte[] h = java.util.Arrays.copyOfRange(all, pos, pos + 512);
        pos += 512;
        boolean empty = true;
        for (byte b : h) {
          if (b != 0) {
            empty = false;
            break;
          }
        }
        if (empty) {
          zeros++;
          continue;
        }
        assertThat(zeros).as("no zero block inside the archive").isZero();
        assertThat(new String(h, 257, 5, StandardCharsets.US_ASCII)).isEqualTo("ustar");
        long sum = 0;
        for (int i = 0; i < 512; i++) {
          sum += (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
        }
        assertThat(Long.parseLong(text(h, 148, 6), 8)).as("header checksum").isEqualTo(sum);
        String name = text(h, 0, 100);
        String prefix = text(h, 345, 155);
        String full = prefix.isEmpty() ? name : prefix + "/" + name;
        int mode = Integer.parseInt(text(h, 100, 7), 8);
        long size = Long.parseLong(text(h, 124, 11), 8);
        byte[] data = java.util.Arrays.copyOfRange(all, pos, pos + (int) size);
        pos += (int) ((size + 511) / 512 * 512);
        out.put(full, new Entry(full, mode, data));
      }
      assertThat(zeros).as("two end-of-archive blocks").isGreaterThanOrEqualTo(2);
    }
    return out;
  }

  private static String text(byte[] h, int offset, int length) {
    int end = offset;
    while (end < offset + length && h[end] != 0) {
      end++;
    }
    return new String(h, offset, end - offset, StandardCharsets.US_ASCII).strip();
  }

  @Test
  @DisplayName(
      "T-SEC-06h ustar entries read back byte for byte: bytes and files, sizes on and off the 512"
          + " block, mode 0600, valid checksums, a 180-character name in prefix and name")
  void roundTrip() throws Exception {
    Path file = dir.resolve("big.log");
    byte[] big = new byte[512 * 3 + 17];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) ('a' + i % 26);
    }
    Files.write(file, big);
    String longName =
        "argos-diag-host-20261124T000000Z/" + "d".repeat(80) + "/" + "f".repeat(60) + ".json";
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (TarGzWriter tar = new TarGzWriter(bytes, Instant.parse("2026-11-24T00:00:00Z"))) {
      tar.add("b/empty.txt", new byte[0]);
      tar.add("b/exact.bin", new byte[512]);
      tar.add("b/logs/big.log", file);
      tar.add(longName, "{}".getBytes(StandardCharsets.UTF_8));
    }
    Map<String, Entry> entries = read(bytes.toByteArray());
    assertThat(entries.keySet())
        .containsExactly("b/empty.txt", "b/exact.bin", "b/logs/big.log", longName);
    assertThat(entries.get("b/empty.txt").data()).isEmpty();
    assertThat(entries.get("b/exact.bin").data()).hasSize(512);
    assertThat(entries.get("b/logs/big.log").data()).isEqualTo(big);
    assertThat(new String(entries.get(longName).data(), StandardCharsets.UTF_8)).isEqualTo("{}");
    for (Entry e : entries.values()) {
      assertThat(e.mode()).as(e.name()).isEqualTo(0600);
    }
  }

  @Test
  @DisplayName("T-SEC-06h entry names: absolute, parent, backslash, non-ASCII and too long refused")
  void names() {
    assertThatThrownBy(() -> TarGzWriter.split("/etc/passwd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TarGzWriter.split("a/../b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TarGzWriter.split("a\\b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TarGzWriter.split("café"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TarGzWriter.split("x".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TarGzWriter.split("a/b.txt")).containsExactly("", "a/b.txt");
  }
}
