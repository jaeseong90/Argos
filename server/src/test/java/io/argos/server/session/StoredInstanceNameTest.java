/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ingest.hash.Xxh3;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-157 (4): the stored form of an instance name ({@code instances.name VARCHAR(128)}). An
 * identity column is never merged by cutting: a name above 128 characters keeps its first 119
 * characters, then {@code ~} and the low 32 bits of the XXH3-64 of the whole name as 8 lower-case
 * hex digits.
 */
@Tag("req:C-157")
class StoredInstanceNameTest {

  /** The suffix computed independently of the implementation. */
  private static String suffix(String name) {
    byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
    return String.format(Locale.ROOT, "%08x", Xxh3.raw64(utf8, 0, utf8.length) & 0xFFFF_FFFFL);
  }

  @Test
  @DisplayName("C-157 (4) names up to 128 characters are stored as they are; null stays null")
  void shortNamesUnchanged() {
    assertThat(SessionStore.storedInstanceName("host-a:8080")).isEqualTo("host-a:8080");
    String exact = "x".repeat(128);
    assertThat(SessionStore.storedInstanceName(exact)).isSameAs(exact);
    assertThat(SessionStore.storedInstanceName(null)).isNull();
  }

  @Test
  @DisplayName(
      "C-157 (4) a 129-character name is its first 119 characters, '~' and 8 hex digits of the low"
          + " 32 bits of XXH3-64 of the whole name (128 characters); idempotent")
  void longNameFormat() {
    String name = "a".repeat(100) + "-pod-" + "b".repeat(24);
    assertThat(name).hasSize(129);
    String stored = SessionStore.storedInstanceName(name);
    assertThat(stored)
        .hasSize(128)
        .isEqualTo(name.substring(0, 119) + "~" + suffix(name))
        .matches("a{100}-pod-b{14}~[0-9a-f]{8}");
    assertThat(SessionStore.storedInstanceName(stored)).as("already stored").isEqualTo(stored);
  }

  @Test
  @DisplayName(
      "C-157 (4) two names that share their first 128 characters are stored as two different names")
  void sharedPrefixStaysApart() {
    String prefix = "svc-".repeat(32);
    assertThat(prefix).hasSize(128);
    String a = SessionStore.storedInstanceName(prefix + "pod-1");
    String b = SessionStore.storedInstanceName(prefix + "pod-2");
    assertThat(a).isNotEqualTo(b).hasSize(128);
    assertThat(b).hasSize(128);
    assertThat(a.substring(0, 119))
        .isEqualTo(b.substring(0, 119))
        .isEqualTo(prefix.substring(0, 119));
  }

  @Test
  @DisplayName("C-157 (4) the prefix never splits a surrogate pair")
  void surrogatePairKept() {
    String name = "x".repeat(118) + "😀" + "y".repeat(20);
    String stored = SessionStore.storedInstanceName(name);
    assertThat(stored).isEqualTo("x".repeat(118) + "~" + suffix(name)).hasSize(127);
  }
}
