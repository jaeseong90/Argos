/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import static io.argos.agent.testing.assertion.TransactionAssert.assertThatTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** JSONL reader, records and assertions over the agent's exporter format. */
class JsonlTransactionsTest {
  /** A line as JsonlFileExporter writes it (shop-boot3, GET /api/orders/1). */
  static final String LINE =
      "{\"tx_id\":\"214769035107377152\",\"trace_id\":\"6adc62a002727253debf335d00f52fcb\","
          + "\"span_id\":\"f8675c0a8e28b7b1\",\"parent_span_id\":null,\"kind\":\"HTTP\","
          + "\"service\":\"GET /api/orders/{id}\",\"service_fixed\":true,"
          + "\"start_epoch_ms\":1792828064092,\"elapsed_ms\":187.426,\"status\":200,"
          + "\"error\":false,\"error_class\":null,\"error_message\":null,\"http_method\":\"GET\","
          + "\"path\":\"/api/orders/1\",\"remote_ip\":\"127.0.0.0\",\"user_id\":null,"
          + "\"thread\":\"http-nio-127.0.0.1-18931-exec-2\",\"virtual_thread\":false,"
          + "\"flags\":[\"streaming\"],\"steps\":[{\"seq\":0,\"parent\":-1,\"type\":\"SQL\","
          + "\"name_id\":\"1\",\"name\":\"select o from orders o where o.id = ?\","
          + "\"target_id\":\"2\",\"target\":\"jdbc:h2:mem:shop\",\"start_offset_ms\":1.5,"
          + "\"elapsed_ms\":0.25,\"status\":0,\"error\":false,\"rows\":1,\"fetch_ms\":0.01,"
          + "\"detail\":\"a\\\"b\\\\c\\u00e9\\n\"}],\"summary\":[],\"summary_overflow\":0}";

  @TempDir Path dir;

  @Test
  @DisplayName("a JSONL line parses into typed fields, steps and flags")
  void parsesLine() {
    TransactionRecord tx = TransactionRecord.parse(LINE);
    assertThat(tx.txId()).isEqualTo("214769035107377152");
    assertThat(tx.service()).isEqualTo("GET /api/orders/{id}");
    assertThat(tx.serviceFixed()).isTrue();
    assertThat(tx.status()).isEqualTo(200);
    assertThat(tx.elapsedMs()).isEqualTo(187.426);
    assertThat(tx.startEpochMs()).isEqualTo(1792828064092L);
    assertThat(tx.remoteIp()).isEqualTo("127.0.0.0");
    assertThat(tx.errorClass()).isNull();
    assertThat(tx.userId()).isNull();
    assertThat(tx.flags()).containsExactly("streaming");
    assertThat(tx.steps()).hasSize(1);
    TransactionRecord.StepRecord step = tx.steps("SQL").get(0);
    assertThat(step.name()).isEqualTo("select o from orders o where o.id = ?");
    assertThat(step.rows()).isEqualTo(1L);
    assertThat(step.parent()).isEqualTo(-1);
    assertThat(step.detail()).isEqualTo("a\"b\\cé\n");
    assertThat(tx.steps("HTTP")).isEmpty();
    assertThatTransaction(tx)
        .hasKind("HTTP")
        .hasService("GET /api/orders/{id}")
        .hasStatus(200)
        .isNotError()
        .hasHttpMethod("GET")
        .hasPath("/api/orders/1")
        .hasElapsedAtLeast(100)
        .hasStepCount("SQL", 1);
  }

  @Test
  @DisplayName("assertion failures name the expected value and show the line")
  void assertionMessages() {
    TransactionRecord tx = TransactionRecord.parse(LINE);
    assertThatThrownBy(() -> assertThatTransaction(tx).hasService("GET /error"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("GET /error")
        .hasMessageContaining("214769035107377152");
    assertThatThrownBy(() -> assertThatTransaction(tx).isError())
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(() -> assertThatTransaction(tx).hasStepCount("SQL", 2))
        .hasMessageContaining("expected 2 SQL step(s) but found 1");
  }

  @Test
  @DisplayName("the reader skips an incomplete last line and waits for expected transactions")
  void readsCompleteLinesAndWaits() throws Exception {
    Path file = dir.resolve("tx.jsonl");
    JsonlTransactions reader = new JsonlTransactions(file);
    assertThat(reader.read()).isEmpty();
    Files.writeString(file, LINE + "\n" + "{\"service\":\"GET /x\"", StandardCharsets.UTF_8);
    assertThat(reader.read()).hasSize(1);
    List<TransactionRecord> none =
        reader.await(JsonlTransactions.service("GET /x"), 1, Duration.ofMillis(200));
    assertThat(none).isEmpty();
    Files.writeString(file, LINE + "\n{\"service\":\"GET /x\"}\n", StandardCharsets.UTF_8);
    assertThat(reader.await(JsonlTransactions.service("GET /x"), 1, Duration.ofSeconds(2)))
        .hasSize(1);
    assertThat(reader.read(JsonlTransactions.path("/api/orders/1"))).hasSize(1);
  }

  @Test
  @DisplayName("the JSON reader handles nesting, numbers, literals and rejects malformed text")
  void json() {
    Object value = Json.parse(" {\"a\":[1,-2.5e1,true,false,null,{}],\"b\":\"\\u0041\"} ");
    assertThat(value).isInstanceOf(Map.class);
    Map<?, ?> map = (Map<?, ?>) value;
    @SuppressWarnings("unchecked")
    List<Object> array = (List<Object>) map.get("a");
    assertThat(array).containsExactly(1L, -25.0, true, false, null, Map.of());
    assertThat(map.get("b")).isEqualTo("A");
    assertThatThrownBy(() -> Json.parse("{\"a\":1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("[1] x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("\"\\q\"")).isInstanceOf(IllegalArgumentException.class);
  }
}
