/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.fakeagent.Attacks;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
@Tag("req:N-10d")
@Tag("req:C-157")
class PhasesTest {

  @Test
  @DisplayName(
      "T-NFR-06a every 18 §8 attack runs by default (--attack=all), 'none' runs none; full uses"
          + " the 18 §8 sizes, the reduced profiles stay above the caps they test")
  void attackSizes() {
    assertThat(LoadOptions.parse(List.of()).attacks())
        .containsExactlyElementsOf(LoadOptions.ALL_ATTACKS);
    assertThat(LoadOptions.ALL_ATTACKS).containsExactlyInAnyOrderElementsOf(LoadOptions.ATTACKS);
    assertThat(LoadOptions.parse(List.of("--attack=none")).attacks()).isEmpty();
    assertThat(AttackPhase.dictionaryFloodServices(LoadProfile.FULL))
        .isEqualTo(Attacks.DICTIONARY_FLOOD_SERVICES);
    assertThat(AttackPhase.instanceFloodHellos(LoadProfile.FULL))
        .isEqualTo(Attacks.INSTANCE_FLOOD_INSTANCES);
    for (LoadProfile p : LoadProfile.values()) {
      assertThat(AttackPhase.instanceFloodHellos(p)).as(p.id()).isGreaterThan(100);
      assertThat(AttackPhase.dictionaryFloodServices(p)).as(p.id()).isGreaterThan(1_000);
    }
  }

  @Test
  @DisplayName(
      "T-NFR-06a C-157 (1) idle sessions: 5,000 at full scale within the server-wide cap left by"
          + " the live sessions, bytes per session from the difference")
  void idleSessions() {
    assertThat(SessionPhase.toOpen(LoadProfile.FULL, 0)).isEqualTo(4_990);
    assertThat(SessionPhase.toOpen(LoadProfile.FULL, 50)).isEqualTo(4_940);
    assertThat(SessionPhase.toOpen(LoadProfile.SMOKE, 10)).isEqualTo(1_000);
    assertThat(SessionPhase.toOpen(LoadProfile.SMOKE, 4_995)).isZero();
    assertThat(SessionPhase.perSession(10_000_000, 1_000)).isEqualTo(10_000);
    assertThat(SessionPhase.perSession(Long.MIN_VALUE, 1_000)).isEqualTo(-1);
    assertThat(SessionPhase.perSession(1_000, 0)).isEqualTo(-1);
    // 10 MB of drift over 15 s, the sessions' interval 30 s: 20 MB to take off.
    assertThat(SessionPhase.driftOver(110_000_000, 100_000_000, 15_000_000_000L, 30_000_000_000L))
        .isEqualTo(20_000_000);
    assertThat(SessionPhase.driftOver(-1, 100, 1, 1)).isEqualTo(Long.MIN_VALUE);
  }

  @Test
  @DisplayName(
      "T-NFR-06a proposals: HdrHistogram bytes per (app, service) window within the 500-service"
          + " cap, budget use within 85 % keeps a 17 §7.3 ratio")
  void proposals() {
    assertThat(Proposals.hdrBytesPerWindow(5_000_000, 10, 100)).isEqualTo(5_000);
    assertThat(Proposals.hdrBytesPerWindow(5_000_000, 1, 1_000)).isEqualTo(10_000);
    assertThat(Proposals.hdrBytesPerWindow(-1, 10, 100)).isEqualTo(-1);
    assertThat(Proposals.budgetProposal(800, 1_000)).startsWith("within budget (80 %)");
    assertThat(Proposals.budgetProposal(900, 1_000)).startsWith("near budget (90 %)");
    assertThat(Proposals.budgetProposal(1_100, 1_000)).startsWith("over budget (110 %)");
    assertThat(Proposals.budgetProposal(-1, 1_000)).isEqualTo("not measured");
  }

  @Test
  @DisplayName(
      "T-NFR-06a phase rows: a measurement is INVALID outside full scale, an invariant FAIL fails"
          + " the task")
  void phaseRows() {
    LoadOptions smoke = LoadOptions.parse(List.of("--profile=smoke"));
    Criterion m =
        PhaseSupport.measurement(
            smoke, "T-SEC-11", "SEC-11", "t", "<= 200 MB", "1 MB", true, true, "how");
    assertThat(m.verdict()).isEqualTo(Verdict.INVALID);
    assertThat(m.failsTask()).isFalse();
    Criterion i = PhaseSupport.invariant("T-SEC-14", "SEC-14", "t", "100", "99", true, false, "h");
    assertThat(i.verdict()).isEqualTo(Verdict.FAIL);
    assertThat(i.failsTask()).isTrue();
    assertThat(PhaseSupport.table(List.of(List.of("a", "b"), List.of("1", "2"))))
        .isEqualTo("| a | b |\n|---|---|\n| 1 | 2 |\n");
  }
}
