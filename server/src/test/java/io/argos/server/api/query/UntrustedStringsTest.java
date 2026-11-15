/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.openapi.ArgosUntrusted;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The rules of 14 §2.10 (DSN-14-13) for collected strings, and their JSON writer. */
@Tag("req:SEC-60")
@Tag("req:DSN-14-13")
class UntrustedStringsTest {

  private static final JsonMapper JSON =
      JsonMapper.builder().addModule(new UntrustedStringsModule()).build();

  @Test
  @DisplayName(
      "T-SEC-60a control characters except tab and newline become U+FFFD; nothing is escaped")
  void controlCharacters() {
    String raw = "<script>alert('x')</script>\u0000a\tb\nc\rd\u001b[31m\u007f";
    assertThat(UntrustedStrings.clean(raw))
        .isEqualTo("<script>alert('x')</script>�a\tb\nc�d�[31m\u007f");
    String plain = "GET /api/orders/{id}";
    assertThat(UntrustedStrings.clean(plain)).isSameAs(plain);
    assertThat(UntrustedStrings.clean(null)).isNull();
  }

  @Test
  @DisplayName("T-SEC-60a names 2 KB, SQL 64 KB, command bodies 1 MB with …[truncated] and a flag")
  void limits() {
    UntrustedStrings.Text exact = UntrustedStrings.name("a".repeat(2048));
    assertThat(exact.truncated()).isFalse();
    assertThat(exact.value()).hasSize(2048);
    UntrustedStrings.Text cut = UntrustedStrings.name("a".repeat(2049));
    assertThat(cut.truncated()).isTrue();
    assertThat(cut.value()).isEqualTo("a".repeat(2048) + "…[truncated]");
    // Multi-byte characters are never split: 683 x 3 bytes = 2,049 > 2,048.
    UntrustedStrings.Text korean = UntrustedStrings.name("가".repeat(683));
    assertThat(korean.value()).isEqualTo("가".repeat(682) + UntrustedStrings.TRUNCATED_SUFFIX);
    UntrustedStrings.Text emoji = UntrustedStrings.cap("ab😀", 5);
    assertThat(emoji.value()).isEqualTo("ab" + UntrustedStrings.TRUNCATED_SUFFIX);
    assertThat(UntrustedStrings.sql("s".repeat(64 * 1024)).truncated()).isFalse();
    assertThat(UntrustedStrings.sql("s".repeat(64 * 1024 + 1)).truncated()).isTrue();
    assertThat(UntrustedStrings.commandBody("c".repeat(1024 * 1024 + 1)).truncated()).isTrue();
    assertThat(UntrustedStrings.name(null)).isEqualTo(new UntrustedStrings.Text(null, false));
  }

  /** A response type as an endpoint would write it. */
  record Item(
      @ArgosUntrusted String service,
      @ArgosUntrusted List<String> frames,
      @ArgosUntrusted Map<String, String> headers,
      String trusted) {}

  @Test
  @DisplayName(
      "T-SEC-60a the JSON writer cleans every @ArgosUntrusted string, list and map; XSS payloads"
          + " stay text")
  void jsonWriter() {
    Item item =
        new Item(
            "<img src=x onerror=alert(1)>\u0007",
            List.of("a\u0000b", "c"),
            Map.of("X-Evil\u0001", "v\u0002"),
            "kept\u0001");
    String json = JSON.writeValueAsString(item);
    assertThat(json)
        .isEqualTo(
            "{\"service\":\"<img src=x onerror=alert(1)>�\",\"frames\":[\"a�b\",\"c\"],"
                + "\"headers\":{\"X-Evil�\":\"v�\"},\"trusted\":\"kept\\u0001\"}");
  }
}
