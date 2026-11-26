/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.PhaseOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

/** The order and the answer parsing of the id sequence guard (ADR-0010 C-259 (1)). */
@Tag("req:N-05")
class IdSequenceGuardPhaseTest {

  @Test
  @DisplayName(
      "T-CH-008a the guard runs first in LISTENERS: after the migrations and the WAL replay, before"
          + " the app bootstrap and the gRPC listener")
  void order() {
    IdSequenceGuardPhase phase = new IdSequenceGuardPhase(null);
    assertThat(phase.order()).isEqualTo(PhaseOrder.LISTENERS);
    assertThat(phase.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    assertThat(phase.name()).isEqualTo("id-sequence-guard");
    assertThat(PhaseOrder.LISTENERS).isGreaterThan(PhaseOrder.WAL_RECOVERY);
  }

  @Test
  @DisplayName("T-CH-008a the ClickHouse answer: two ids, 0 for no rows, anything else refused")
  void parse() {
    assertThat(IdSequenceGuardPhase.MaxIds.parse("42\t77"))
        .isEqualTo(new IdSequenceGuardPhase.MaxIds(42, 77));
    assertThat(IdSequenceGuardPhase.MaxIds.parse("0\t0"))
        .isEqualTo(new IdSequenceGuardPhase.MaxIds(0, 0));
    assertThatThrownBy(() -> IdSequenceGuardPhase.MaxIds.parse("42"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> IdSequenceGuardPhase.MaxIds.parse("x\t1"))
        .isInstanceOf(IllegalStateException.class);
  }
}
