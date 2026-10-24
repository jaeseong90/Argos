/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Request parsing, bounds and the bounded request log (no sockets). */
class ExtStubUnitTest {

  @Test
  @DisplayName("Query parameters are decoded; the first value wins")
  void parseQuery() {
    Map<String, String> query = ExtStub.parseQuery("ms=300&to=%2Fpay%2F1&ms=9&flag");
    assertThat(query)
        .containsEntry("ms", "300")
        .containsEntry("to", "/pay/1")
        .containsEntry("flag", "");
    assertThat(ExtStub.parseQuery(null)).isEmpty();
  }

  @Test
  @DisplayName("Delay, hold and status parameters are bounded")
  void bounds() {
    assertThat(ExtStub.bounded(Map.of(), "ms", 30_000, 0, 120_000)).isEqualTo(30_000);
    assertThat(ExtStub.bounded(Map.of("ms", "250"), "ms", 0, 0, 60_000)).isEqualTo(250);
    assertThatThrownBy(() -> ExtStub.bounded(Map.of("ms", "60001"), "ms", 0, 0, 60_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExtStub.bounded(Map.of("ms", "x"), "ms", 0, 0, 60_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ExtStub.statusOf("503")).isEqualTo(503);
    assertThatThrownBy(() -> ExtStub.statusOf("99")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExtStub.statusOf("600")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("JSON strings escape quotes, control and HTML characters")
  void json() {
    assertThat(Json.write(Map.of("p", "<img src=\"x\">\n")))
        .isEqualTo("{\"p\":\"\\u003cimg src=\\\"x\\\"\\u003e\\n\"}");
    assertThat(Json.write(List.of(1, true, Map.of()))).isEqualTo("[1,true,{}]");
  }

  @Test
  @DisplayName("The request log keeps the last N requests and caps header values")
  void requestLogIsBounded() {
    RequestLog log = new RequestLog(3);
    for (int i = 0; i < 5; i++) {
      log.record("GET", "/pay/" + i, null, Map.of("Traceparent", List.of("x".repeat(5000))));
    }
    List<Map<String, Object>> entries = log.snapshot();
    assertThat(entries).hasSize(3);
    assertThat(entries.get(0)).containsEntry("path", "/pay/2");
    @SuppressWarnings("unchecked")
    Map<String, String> headers = (Map<String, String>) entries.get(2).get("headers");
    assertThat(headers.get("traceparent")).hasSize(RequestLog.MAX_VALUE_CHARS);
    log.clear();
    assertThat(log.snapshot()).isEmpty();
  }
}
