/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.session.api.ClockOffsetProvider;
import io.argos.server.testing.collector.ContractVectors;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * 11 §9 clock arithmetic of the server ({@link ClockSync}): the WO-11 contract vectors (the agent's
 * FakeCollector computes the same numbers), rtt outliers, and the agent skews of +3 s, +60 s and
 * +25 h with the C-129 correction derived from them.
 */
@Tag("req:D-02")
@Tag("req:DSN-11-05")
class ClockSyncTest {

  @TestFactory
  @DisplayName("11 §16 contract vectors 'clock' replayed against the server's ClockSync")
  List<DynamicTest> contractVectors() {
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode vector : ContractVectors.section("clock")) {
      tests.add(
          DynamicTest.dynamicTest("11 §9 " + vector.get("name").asString(), () -> replay(vector)));
    }
    assertThat(tests).hasSize(3);
    return tests;
  }

  private static void replay(JsonNode vector) {
    JsonNode hello = vector.get("hello");
    ClockSync sync =
        new ClockSync(hello.get("server_time_ms").asLong(), hello.get("sent_at_ms").asLong());
    assertThat(sync.estimateMs()).isEqualTo(hello.get("expected_estimate_ms").asLong());
    int index = 0;
    for (JsonNode hb : vector.get("heartbeats")) {
      ClockSync.Round round =
          sync.onHeartbeat(hb.get("prev_t1").asLong(), hb.get("prev_t4").asLong());
      JsonNode expected = hb.get("expected_round");
      if (expected == null || expected.isNull()) {
        assertThat(round).as("heartbeat %d completes no round", index).isNull();
      } else {
        assertThat(round).as("heartbeat %d", index).isNotNull();
        assertThat(round.offsetMs())
            .as("offset %d", index)
            .isEqualTo(expected.get("offset_ms").asLong());
        assertThat(round.rttMs()).as("rtt %d", index).isEqualTo(expected.get("rtt_ms").asLong());
        assertThat(round.discarded())
            .as("discarded %d", index)
            .isEqualTo(expected.get("discarded").asBoolean());
      }
      assertThat(sync.estimateMs())
          .as("estimate after heartbeat %d", index)
          .isEqualTo(hb.get("expected_estimate_ms").asLong());
      sync.acked(hb.get("t1").asLong(), hb.get("t2").asLong(), hb.get("t3").asLong());
      index++;
    }
  }

  @Test
  @DisplayName(
      "11 §9 a prev_t1 the server never acknowledged, or one already used, completes no round")
  void unknownOrReusedPrevT1() {
    ClockSync sync = new ClockSync(1_000, 1_000);
    sync.acked(100, 110, 111);
    assertThat(sync.onHeartbeat(99, 120)).isNull();
    assertThat(sync.onHeartbeat(100, 120)).isNotNull();
    assertThat(sync.onHeartbeat(100, 120)).as("the same round twice").isNull();
    assertThat(sync.onHeartbeat(0, 120)).isNull();
    assertThat(sync.onHeartbeat(100, 0)).isNull();
  }

  @Test
  @DisplayName(
      "11 §9 the rtt median includes discarded rounds, so a lasting rtt change is followed (C-139 (2))")
  void lastingRttChange() {
    ClockSync sync = new ClockSync(0, 0);
    long t = 1_000_000;
    // three rounds at rtt 10 with offset 0, then rtt 100 with offset 500 for good
    List<Boolean> discarded = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      long rtt = i < 3 ? 10 : 100;
      long offset = i < 3 ? 0 : 500;
      long t1 = t + i * 30_000L;
      long t2 = t1 + offset + rtt / 2;
      long t3 = t2;
      long t4 = t1 + rtt;
      sync.acked(t1, t2, t3);
      ClockSync.Round round = sync.onHeartbeat(t1, t4);
      discarded.add(round.discarded());
    }
    assertThat(discarded)
        .containsExactly(false, false, false, true, true, true, false, false, false);
    assertThat(sync.estimateMs()).isEqualTo(500);
  }

  /**
   * An agent clock {@code skewMs} ahead of the server, heartbeats every 30 s with a 4 ms rtt: the
   * estimate is server − agent, and C-129 corrects a lead above 5 s by the lead rounded to 10 s.
   */
  @ParameterizedTest(name = "C-129 agent clock {0} ms ahead: offset {1}, correction {2}")
  @CsvSource({
    // +3 s: under the 5 s threshold, never corrected
    "3000, -3000, 0",
    // +60 s: corrected by 60 s
    "60000, -60000, 60000",
    // +25 h: corrected by 25 h
    "90000000, -90000000, 90000000",
    // a clock behind the server is never corrected (C-129 (5))
    "-60000, 60000, 0",
    // exactly 5 s ahead is not "more than 5 s"
    "5000, -5000, 0",
    // 5.001 s ahead rounds half up to 10 s
    "5001, -5001, 10000"
  })
  @Tag("req:D-15")
  void skewedAgent(long skewMs, long expectedOffset, long expectedCorrection) {
    long server = 1_700_000_000_000L;
    ClockSync sync = new ClockSync(server, server + skewMs);
    assertThat(sync.estimateMs()).as("Hello estimate").isEqualTo(expectedOffset);
    assertThat(ClockOffsetProvider.correctionMs(OptionalLong.of(sync.estimateMs())))
        .as("correction from the Hello estimate (before the first round, C-129 (1))")
        .isEqualTo(expectedCorrection);
    for (int i = 0; i < 6; i++) {
      long t1 = server + skewMs + i * 30_000L;
      long t2 = server + i * 30_000L + 2;
      long t3 = t2;
      long t4 = t1 + 4;
      sync.acked(t1, t2, t3);
      sync.onHeartbeat(t1, t4);
    }
    assertThat(sync.estimateMs()).isEqualTo(expectedOffset);
    assertThat(ClockOffsetProvider.correctionMs(OptionalLong.of(sync.estimateMs())))
        .isEqualTo(expectedCorrection);
  }

  @Test
  @DisplayName(
      "11 §9 medians: odd count takes the middle value, even count the floor of the middle two")
  void medians() {
    assertThat(ClockSync.median(new long[] {5, 1, 3}, 3)).isEqualTo(3);
    assertThat(ClockSync.median(new long[] {-1001, -1000}, 2)).isEqualTo(-1001);
    assertThat(ClockSync.median(new long[] {3, 0}, 2)).isEqualTo(1);
  }
}
