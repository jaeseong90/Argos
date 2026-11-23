/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The strict parser of the client frames of 14 §5.2. */
@Tag("req:SEC-44")
@Tag("req:F-S03")
class ClientFrameTest {

  @Test
  @DisplayName("T-API-007a the client frames of 14 §5.2 parse into subscribe, unsubscribe, ping")
  void docExamples() {
    ClientFrame s =
        ClientFrame.parse(
            "{\"op\": \"subscribe\", \"id\": \"s1\", \"topic\": \"xlog\", \"params\": {\"app\": 3,"
                + " \"env\": \"prod\", \"kind\": \"HTTP\", \"service\": [], \"instance\": []}}");
    assertThat(s).isInstanceOf(ClientFrame.Subscribe.class);
    ClientFrame.Subscribe sub = (ClientFrame.Subscribe) s;
    assertThat(sub.id()).isEqualTo("s1");
    assertThat(sub.topic()).isEqualTo("xlog");
    assertThat(sub.params())
        .containsEntry("app", 3)
        .containsEntry("env", "prod")
        .containsEntry("kind", "HTTP")
        .containsEntry("service", List.of())
        .containsEntry("instance", List.of());
    assertThat(ClientFrame.parse("{\"op\": \"unsubscribe\", \"id\": \"s1\"}"))
        .isEqualTo(new ClientFrame.Unsubscribe("s1"));
    assertThat(ClientFrame.parse("{\"op\": \"ping\", \"t\": 1729645323456}"))
        .isEqualTo(new ClientFrame.Ping(1729645323456L));
    assertThat(
            ClientFrame.parse(
                "{\"op\":\"subscribe\",\"id\":\"s6\",\"topic\":\"admin\",\"params\":{}}"))
        .isEqualTo(new ClientFrame.Subscribe("s6", "admin", Map.of()));
  }

  @ParameterizedTest(name = "T-API-007a invalid frame {0} -> invalid_params")
  @ValueSource(
      strings = {
        "not json",
        "[1,2]",
        "\"text\"",
        "{}",
        "{\"op\":1}",
        "{\"op\":\"sub\",\"id\":\"s1\"}",
        "{\"op\":\"subscribe\",\"topic\":\"xlog\",\"params\":{}}",
        "{\"op\":\"subscribe\",\"id\":\"\",\"topic\":\"xlog\",\"params\":{}}",
        "{\"op\":\"subscribe\",\"id\":5,\"topic\":\"xlog\",\"params\":{}}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"params\":{}}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":7,\"params\":{}}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":\"xlog\"}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":\"xlog\",\"params\":[]}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":\"xlog\",\"params\":{},\"x\":1}",
        "{\"op\":\"subscribe\",\"id\":\"s1\",\"id\":\"s2\",\"topic\":\"xlog\",\"params\":{}}",
        "{\"op\":\"unsubscribe\"}",
        "{\"op\":\"unsubscribe\",\"id\":\"s1\",\"topic\":\"xlog\"}",
        "{\"op\":\"ping\"}",
        "{\"op\":\"ping\",\"t\":\"1\"}",
        "{\"op\":\"ping\",\"t\":1.5}",
        "{\"op\":\"ping\",\"t\":-1}",
        "{\"op\":\"ping\",\"t\":1,\"id\":\"s1\"}",
        "{\"op\":\"ping\",\"t\":1} {\"op\":\"ping\",\"t\":2}"
      })
  void invalid(String text) {
    assertThat(ClientFrame.parse(text)).isInstanceOf(ClientFrame.Invalid.class);
  }

  @Test
  @DisplayName(
      "T-API-007a an invalid frame keeps its id when the id itself is valid, never the sent value")
  void invalidKeepsId() {
    ClientFrame f =
        ClientFrame.parse(
            "{\"op\":\"subscribe\",\"id\":\"s9\",\"topic\":\"xlog\",\"params\":{},\"evil\":\"<x>\"}");
    assertThat(f).isEqualTo(new ClientFrame.Invalid("s9", "frame: unknown_field"));
    assertThat(ClientFrame.parse("{\"op\":\"nope\",\"id\":\"s3\"}"))
        .isEqualTo(new ClientFrame.Invalid("s3", "op: unsupported_value"));
    String longId = "x".repeat(ClientFrame.MAX_ID_LENGTH + 1);
    assertThat(
            ClientFrame.parse(
                "{\"op\":\"subscribe\",\"id\":\""
                    + longId
                    + "\",\"topic\":\"xlog\",\"params\":{}}"))
        .isEqualTo(new ClientFrame.Invalid(null, "id: invalid_value"));
  }
}
