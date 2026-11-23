/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * W3C {@code traceparent} parser and generator, HOST text (ADR-0010 C-142) and HTTP step detail (10
 * §6.5, §8; WO-14).
 */
@Tag("req:F-A06")
class PropagationTest {
  private static final String VALID = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

  @Test
  @Tag("req:F-A32")
  @DisplayName("T-AG-057a a valid traceparent gives the trace id and the parent span id")
  void parsesValid() {
    long[] ids = Propagation.parseTraceparent(VALID);
    assertThat(ids).containsExactly(0x0af7651916cd43ddL, 0x8448eb211c80319cL, 0xb7ad6b7169203331L);
    assertThat(Propagation.parseTraceparent(VALID.substring(0, 53) + "00"))
        .as("flags 00 (not sampled) is still valid")
        .isNotNull();
  }

  @ParameterizedTest(name = "T-AG-057a rejects traceparent [{0}]")
  @Tag("req:F-A32")
  @ValueSource(
      strings = {
        "",
        // 54 and 56 characters with version 00
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0",
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01-",
        // version ff is invalid
        "ff-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        // all-zero trace id, all-zero parent id
        "00-00000000000000000000000000000000-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-0000000000000000-01",
        // upper-case hex (W3C: lower case only)
        "00-0AF7651916CD43DD8448EB211C80319C-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-B7AD6B7169203331-01",
        "0A-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        // separators and non-hex
        "00_0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c_b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319g-b7ad6b7169203331-01",
        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-0x",
        // a later version: more data must follow a '-'
        "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01x"
      })
  void rejectsInvalid(String value) {
    assertThat(Propagation.parseTraceparent(value)).isNull();
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName(
      "T-AG-057a null, a later version with extra fields, and the 256-character input bound")
  void versionsAndBounds() {
    assertThat(Propagation.parseTraceparent(null)).isNull();
    String later = "cc" + VALID.substring(2) + "-what-the-future-holds";
    assertThat(Propagation.parseTraceparent(later)).isNotNull();
    StringBuilder huge = new StringBuilder("cc" + VALID.substring(2) + "-");
    while (huge.length() <= Propagation.MAX_TRACEPARENT_CHARS) {
      huge.append('a');
    }
    assertThat(Propagation.parseTraceparent(huge.toString())).isNull();
  }

  @Test
  @DisplayName("T-AG-057a the generated traceparent is 55 characters and parses back to its ids")
  void formatRoundTrip() {
    String value = Propagation.formatTraceparent(0x0102L, -1L, 0xabcL);
    assertThat(value).isEqualTo("00-0000000000000102ffffffffffffffff-0000000000000abc-01");
    assertThat(value).hasSize(Propagation.TRACEPARENT_LENGTH);
    assertThat(Propagation.parseTraceparent(value)).containsExactly(0x0102L, -1L, 0xabcL);
  }

  @ParameterizedTest(name = "T-AG-056b C-142 HOST text of ({0}, {1}, {2}) is {3}")
  @Tag("req:F-A05")
  @CsvSource(
      delimiter = '|',
      value = {
        "http|Example.COM|-1|http://example.com:80",
        "HTTPS|api.example.com|0|https://api.example.com:443",
        "https|api.example.com|8443|https://api.example.com:8443",
        "http|127.0.0.1|18080|http://127.0.0.1:18080",
        "http|::1|8080|http://[::1]:8080",
        "http|[2001:DB8::1]|-1|http://[2001:db8::1]:80",
        "http|user:secret@host.example|81|http://host.example:81",
        "http|host.example.|80|http://host.example:80",
        "|host.example|-1|http://host.example:80",
        "ws|socket.example|-1|ws://socket.example:80",
        "wss|socket.example|-1|wss://socket.example:443",
        "grpc|svc.example|-1|grpc://svc.example"
      })
  void hostText(String scheme, String host, int port, String expected) {
    assertThat(Propagation.hostText(scheme, host, port)).isEqualTo(expected);
  }

  @Test
  @Tag("req:F-A05")
  @DisplayName("T-AG-056b no HOST text for a missing, empty, too long or malformed host")
  void hostTextRejects() {
    assertThat(Propagation.hostText("http", null, 80)).isNull();
    assertThat(Propagation.hostText("http", " ", 80)).isNull();
    assertThat(Propagation.hostText("http", "a/b", 80)).isNull();
    assertThat(Propagation.hostText("http", "a b", 80)).isNull();
    StringBuilder longHost = new StringBuilder();
    while (longHost.length() <= Propagation.MAX_HOST_CHARS) {
      longHost.append("a");
    }
    assertThat(Propagation.hostText("http", longHost.toString(), 80)).isNull();
    assertThat(Propagation.hostText("1http", "h", 80))
        .as("an invalid scheme falls back to http")
        .isEqualTo("http://h:80");
  }

  @Test
  @Tag("req:F-A05")
  @DisplayName(
      "T-AG-056b step detail: METHOD host[:port]/path without query, fragment or ;parameters")
  void detail() {
    assertThat(Propagation.detail("GET", "127.0.0.1:8080", "/pay/1?card=4111&x=1", null))
        .isEqualTo("GET 127.0.0.1:8080/pay/1");
    assertThat(Propagation.detail("POST", "h", "/a;jsessionid=abc/b;v=1#frag", null))
        .isEqualTo("POST h/a/b");
    assertThat(Propagation.detail(null, "user:pw@h:81", "", null)).isEqualTo("GET h:81/");
    assertThat(Propagation.detail("GET", "h", "rel", null)).isEqualTo("GET h/rel");
    assertThat(Propagation.detail("GET", "h", "/x\ny", null)).isEqualTo("GET h/x?y");
  }

  @Test
  @Tag("req:F-A32")
  @DisplayName("T-AG-057a a kept application traceparent is appended to the step detail (10 §8)")
  void detailWithPreservedTraceparent() {
    assertThat(Propagation.detail("GET", "h:80", "/a?b", VALID))
        .isEqualTo("GET h:80/a traceparent=" + VALID);
  }

  @Test
  @DisplayName("T-AG-056b the step detail is cut at 4 KiB")
  void detailBound() {
    StringBuilder path = new StringBuilder("/");
    while (path.length() < 10_000) {
      path.append("segment/");
    }
    String detail = Propagation.detail("GET", "h", path.toString(), VALID);
    assertThat(detail).hasSize(Propagation.MAX_DETAIL_CHARS).startsWith("GET h/segment/");
  }
}
