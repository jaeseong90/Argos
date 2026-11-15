/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** WO-12 accessors that read the JSONL and the collector shapes of the same fields alike. */
class TransactionRecordTest {

  @Test
  @DisplayName(
      "JSONL shape: headers as \"name: value\" list, request id, dispatch count, timed_out and"
          + " streaming flags")
  void jsonlShape() {
    TransactionRecord tx =
        TransactionRecord.parse(
            "{\"headers\":[\"x-test-header: a: b\",\"accept-language: ko\",\"broken\"],"
                + "\"request_id\":\"req-1\",\"dispatch_count\":2,"
                + "\"flags\":[\"streaming\",\"timed_out\"]}");
    assertThat(tx.headers())
        .containsEntry("X-Test-Header", "a: b")
        .containsEntry("Accept-Language", "ko")
        .hasSize(2);
    assertThat(tx.requestId()).isEqualTo("req-1");
    assertThat(tx.dispatchCount()).isEqualTo(2);
    assertThat(tx.timedOut()).isTrue();
    assertThat(tx.streaming()).isTrue();
    assertThat(RecordSource.JSONL.supportsTransactionField("headers")).isTrue();
    assertThat(RecordSource.JSONL.supportsTransactionField("dispatch_count")).isTrue();
  }

  @Test
  @DisplayName("collector shape: header map, empty request id, booleans; absent values")
  void collectorShape() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("headers", Map.of("x-test-header", "abc"));
    fields.put("request_id", "");
    fields.put("dispatch_count", 1L);
    fields.put("timed_out", true);
    fields.put("streaming", false);
    fields.put("flags", List.of());
    TransactionRecord tx = new TransactionRecord(fields, fields.toString(), RecordSource.JSONL);
    assertThat(tx.headers()).containsEntry("X-TEST-HEADER", "abc").hasSize(1);
    assertThat(tx.requestId()).isNull();
    assertThat(tx.dispatchCount()).isEqualTo(1);
    assertThat(tx.timedOut()).isTrue();
    assertThat(tx.streaming()).isFalse();

    TransactionRecord empty = TransactionRecord.parse("{\"tx_id\":\"1\"}");
    assertThat(empty.headers()).isEmpty();
    assertThat(empty.requestId()).isNull();
    assertThat(empty.dispatchCount()).isNull();
    assertThat(empty.timedOut()).isFalse();
    assertThat(empty.streaming()).isFalse();
  }
}
