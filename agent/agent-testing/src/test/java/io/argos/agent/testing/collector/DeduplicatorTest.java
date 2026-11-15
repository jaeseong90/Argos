/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 11 §6 deduplication of the FakeCollector ({@link Deduplicator}) on the contract vectors
 * ({@code contract/argos-v1-vectors.json}, section {@code dedup}), without network. Steps with a
 * {@code fault} are error answers that never reach the window (the gRPC replay in {@code
 * ProtocolContractTest} sends them).
 */
@Tag("req:D-13")
@Tag("req:C-3")
@Tag("req:DSN-11-02")
class DeduplicatorTest {

  @Test
  @DisplayName(
      "11 §6 vectors: key (instance, epoch, kind, seq), last_seq per kind, window 1,000, an error"
          + " answer writes nothing")
  void vectors() {
    Deduplicator dedup = new Deduplicator();
    int checked = 0;
    for (Map<String, Object> scenario : ContractVectors.maps(ContractVectors.load().get("dedup"))) {
      for (Map<String, Object> step : ContractVectors.maps(scenario.get("steps"))) {
        if (step.containsKey("fault")) {
          continue;
        }
        BatchKind kind = BatchKind.valueOf(ContractVectors.string(step, "kind"));
        long epoch = ContractVectors.number(step, "agent_epoch");
        boolean duplicate = "ACK_DUPLICATE".equals(ContractVectors.string(step, "expected_status"));
        long from = step.containsKey("seq") ? ContractVectors.number(step, "seq") : 0;
        long to = from;
        if (step.containsKey("seq_from")) {
          from = ContractVectors.number(step, "seq_from");
          to = ContractVectors.number(step, "seq_to");
        }
        for (long seq = from; seq <= to; seq++) {
          Deduplicator.Outcome outcome = dedup.offer(1, epoch, kind, seq);
          String what = kind + " epoch " + epoch + " seq " + seq;
          assertThat(outcome.duplicate()).as(what + " duplicate").isEqualTo(duplicate);
          long lastSeq =
              "seq".equals(step.get("expected_last_seq"))
                  ? seq
                  : ContractVectors.number(step, "expected_last_seq");
          assertThat(outcome.lastSeq()).as(what + " last_seq").isEqualTo(lastSeq);
          checked++;
        }
      }
    }
    assertThat(checked).isGreaterThan(Deduplicator.WINDOW);
  }

  @Test
  @DisplayName(
      "C-3: another instance never collides; last_seq is the last write (11 §4.3), not the highest,"
          + " and 0 for an unknown key; seen() writes nothing")
  void instances() {
    Deduplicator dedup = new Deduplicator();
    assertThat(dedup.seen(1, 7, BatchKind.TRANSACTIONS, 1)).isFalse();
    assertThat(dedup.lastSeq(1, 7, BatchKind.TRANSACTIONS)).isZero();
    assertThat(dedup.offer(1, 7, BatchKind.TRANSACTIONS, 1).duplicate()).isFalse();
    assertThat(dedup.seen(1, 7, BatchKind.TRANSACTIONS, 1)).isTrue();
    assertThat(dedup.offer(2, 7, BatchKind.TRANSACTIONS, 1).duplicate()).isFalse();
    assertThat(dedup.offer(1, 7, BatchKind.TRANSACTIONS, 1).duplicate()).isTrue();
    assertThat(dedup.lastSeq(1, 7, BatchKind.TRANSACTIONS)).isEqualTo(1);
    assertThat(dedup.lastSeq(3, 7, BatchKind.TRANSACTIONS)).isZero();
    assertThat(dedup.offer(1, 7, BatchKind.TRANSACTIONS, 9).lastSeq()).isEqualTo(9);
    assertThat(dedup.offer(1, 7, BatchKind.TRANSACTIONS, 8).lastSeq()).as("a retry").isEqualTo(8);
    assertThat(dedup.offer(1, 7, BatchKind.TRANSACTIONS, 9).lastSeq()).isEqualTo(8);
  }

  @Test
  @DisplayName("the vectors file parses and names every section the contract suite replays")
  void sections() {
    Map<String, Object> vectors = ContractVectors.load();
    assertThat(vectors)
        .containsKeys("clock", "dedup", "session", "status_codes", "metadata", "interpretations");
    assertThat(ContractVectors.number(vectors, "protocol_version"))
        .isEqualTo(io.argos.proto.ProtocolVersion.CURRENT);
    assertThat(ContractVectors.parse("{\"a\":[1,2.5,\"x\\n\\u0041\",true,false,null]}"))
        .isEqualTo(Map.of("a", java.util.Arrays.asList(1L, 2.5, "x\nA", true, false, null)));
    assertThat(ContractVectors.maps(List.of(Map.of()))).hasSize(1);
  }
}
