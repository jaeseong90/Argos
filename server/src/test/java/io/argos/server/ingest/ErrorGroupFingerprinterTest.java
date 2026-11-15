/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ingest.hash.Xxh3;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The error group fingerprint (F-S19, SEC-36, ADR-0010 C-131). */
@Tag("req:F-S19")
@Tag("req:SEC-36")
@Tag("req:C-131")
class ErrorGroupFingerprinterTest {

  @Test
  @DisplayName("T-SV-016a fixed vector: xxh3_64(class 8B BE || pattern UTF-8 || frame 8B BE)")
  void fixedVector() {
    long classId = 0x0123456789ABCDEFL;
    long frameId = 0x0FEDCBA987654321L;
    String pattern = "Order # not found for user <email>";
    byte[] input =
        ByteBuffer.allocate(8 + pattern.length() + 8)
            .putLong(classId)
            .put(pattern.getBytes(StandardCharsets.UTF_8))
            .putLong(frameId)
            .array();
    long expected = Xxh3.hash64(input, 0, input.length);
    assertThat(ErrorGroupFingerprinter.groupId(classId, pattern, frameId)).isEqualTo(expected);
    // Locked values of the rule (WO-27 reuses it): computed once with the server's Xxh3, which
    // Xxh3Test checks against the reference vectors of xxHash 0.8.2. A change of the input layout
    // (byte order, separators, the pattern) changes them.
    assertThat(ErrorGroupFingerprinter.groupId(classId, pattern, frameId))
        .isEqualTo(0x5c8147857f92c695L);
    assertThat(ErrorGroupFingerprinter.groupId(1, "", 2)).isEqualTo(0xc4846c74c4b320adL);
    assertThat(ErrorGroupFingerprinter.groupId(1, "", 2))
        .isEqualTo(Xxh3.hash64(new byte[] {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2}, 0, 16));
    assertThat(
            ErrorGroupFingerprinter.groupIdOf(
                true, classId, "Order 1234567 not found for user kim@example.com", frameId))
        .isEqualTo(0x5c8147857f92c695L);
  }

  @Test
  @DisplayName("T-SV-016b the server masks again before the pattern (SEC-36 double safety)")
  void masked() {
    String pattern =
        ErrorGroupFingerprinter.pattern(
            "User kim@example.com at 10.1.2.3 phone 010-1234-5678 card 4111 1111 1111 1111");
    assertThat(pattern).contains("<email>", "<ip>", "<phone>", "<card>");
    assertThat(pattern).doesNotContain("kim@", "10.1.2.3", "5678", "4111");
  }

  @Test
  @DisplayName("T-SV-016c messages differing only in numbers, quoted values or ids share a group")
  void normalized() {
    String a = "Order 1234567 not found (user 'alice', trace 550e8400-e29b-41d4-a716-446655440000)";
    String b = "Order 12345678 not found (user 'bob', trace 123e4567-e89b-12d3-a456-426614174000)";
    assertThat(ErrorGroupFingerprinter.pattern(a)).isEqualTo(ErrorGroupFingerprinter.pattern(b));
    assertThat(ErrorGroupFingerprinter.pattern(a))
        .isEqualTo("Order # not found (user '?', trace ?)");
    assertThat(ErrorGroupFingerprinter.pattern("lock 0x7f3a9c00 held by deadbeef0123  thread"))
        .isEqualTo("lock ? held by ? thread");
    assertThat(ErrorGroupFingerprinter.pattern("can't read \"x.txt\" at line 12"))
        .isEqualTo("can't read '?' at line #");
    assertThat(ErrorGroupFingerprinter.groupIdOf(true, 7, a, 9))
        .isEqualTo(ErrorGroupFingerprinter.groupIdOf(true, 7, b, 9));
    assertThat(ErrorGroupFingerprinter.groupIdOf(true, 7, a, 9))
        .isNotEqualTo(ErrorGroupFingerprinter.groupIdOf(true, 8, a, 9));
  }

  @Test
  @DisplayName("T-SV-016d no error -> 0; the pattern is cut at 256 characters")
  void edges() {
    assertThat(ErrorGroupFingerprinter.groupIdOf(false, 7, "x", 9)).isZero();
    assertThat(ErrorGroupFingerprinter.groupIdOf(true, 0, "", 0)).isZero();
    assertThat(ErrorGroupFingerprinter.groupIdOf(true, 0, "boom", 0)).isNotZero();
    assertThat(ErrorGroupFingerprinter.pattern("x".repeat(1000))).hasSize(256);
  }
}
