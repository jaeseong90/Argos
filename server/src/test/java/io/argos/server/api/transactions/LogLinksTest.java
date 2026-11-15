/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The log link of a transaction detail (F-U18, 14 §4.6 {@code links.logs}). */
@Tag("req:F-U18")
class LogLinksTest {

  private static final Instant START = Instant.parse("2026-11-13T10:00:00.250Z");

  @Test
  @DisplayName(
      "every placeholder is replaced by its value as a URI component; from/to are the run +-1"
          + " minute; unknown values are empty")
  void placeholders() {
    String link =
        LogLinks.transaction(
            "https://logs.example/search?q=trace_id:{trace_id} OR tx:{tx_id}&from={from}&to={to}"
                + "&app={app}&host={instance}",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "225787189382912019",
            START,
            1_500,
            "order api&x=<1>",
            null);
    assertThat(link)
        .isEqualTo(
            "https://logs.example/search?q=trace_id:4bf92f3577b34da6a3ce929d0e0e4736 OR"
                + " tx:225787189382912019&from=2026-11-13T09%3A59%3A00.250Z"
                + "&to=2026-11-13T10%3A01%3A01.750Z&app=order%20api%26x%3D%3C1%3E&host=");
  }

  @Test
  @DisplayName(
      "no template, or one that is not http(s) or a path of this server (javascript:, //host),"
          + " gives no link")
  void unusableTemplates() {
    assertThat(LogLinks.transaction(null, null, "1", START, 0, null, null)).isNull();
    assertThat(LogLinks.transaction("javascript:alert({tx_id})", null, "1", START, 0, null, null))
        .isNull();
    assertThat(LogLinks.transaction("//evil.example/{tx_id}", null, "1", START, 0, null, null))
        .isNull();
    assertThat(LogLinks.transaction("data:text/html,{tx_id}", null, "1", START, 0, null, null))
        .isNull();
    assertThat(LogLinks.transaction("/logs?tx={tx_id}", null, "7", START, 0, null, null))
        .isEqualTo("/logs?tx=7");
    assertThat(LogLinks.transaction("HTTP://l/{trace_id}", null, "7", START, 0, null, null))
        .isEqualTo("HTTP://l/");
  }
}
