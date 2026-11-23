/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.HeartbeatAck;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-ACC-02, server side (D-02, 11 §9, ADR-0010 C-139): the server's clock is 3 s ahead of a fake
 * agent (the agent clock 3 s behind the system clock the server uses), so the server's offset
 * (server − agent) is about {@code +3000} ms: the {@code HeartbeatAck} offset (median of the last
 * rounds), the instance WARN {@code clock_skew} (12 §4.2, |offset| ≥ 1,000 ms), {@code
 * instances.clock_offset_ms} and {@code warn_flags}, the REST instance and {@code
 * argos_clock_offset_ms{instance}} (C-158 (4)) and the dashboard instance tile's {@code
 * warn_reasons} (C-216 (2)); an agent without skew stays UP.
 */
@Tag("integration")
@Tag("req:D-02")
@Tag("req:F-S28")
@Tag("req:C-158")
class ClockSkewIT {

  private static StateStack stack;

  @BeforeAll
  static void start() {
    stack = StateStack.start(null);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  @Test
  @DisplayName(
      "T-ACC-02 server 3 s ahead: offset median about +3000 ms (C-139 sign), WARN clock_skew, clock_offset_ms stored, argos_clock_offset_ms; no skew stays UP")
  void serverAhead() throws Exception {
    AgentTokens.IssuedToken token = stack.token("skew-app");
    try (FakeAgent skewed = stack.agent(token, "skew-app", "host-s:8080", Duration.ofSeconds(-3));
        FakeAgent exact = stack.agent(token, "skew-app", "host-e:8080", Duration.ZERO)) {
      assertThat(skewed.connect().accepted()).isTrue();
      assertThat(exact.connect().accepted()).isTrue();
      int id = skewed.instanceId();
      HeartbeatAck last = null;
      for (int i = 0; i < 7; i++) {
        last = skewed.heartbeatRound(Duration.ofSeconds(5)).orElseThrow();
        assertThat(exact.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      }
      long offset = last.getClockOffsetMs();
      System.out.printf(
          Locale.ROOT, "T-ACC-02 offset of an agent 3 s behind the server: %d ms%n", offset);
      assertThat(offset).as("server − agent").isBetween(2_500L, 3_500L);
      assertThat(last.getState()).isEqualTo(io.argos.proto.v1.InstanceState.WARN);
      assertThat(stack.state(id)).isEqualTo(InstanceState.WARN);
      assertThat(stack.server.bean(InstanceStateTracker.class).reasons(id))
          .containsExactly(InstanceStateMachine.CLOCK_SKEW);

      HeartbeatAck exactAck = exact.heartbeatRound(Duration.ofSeconds(5)).orElseThrow();
      assertThat(Math.abs(exactAck.getClockOffsetMs())).isLessThan(1_000);
      assertThat(exactAck.getState()).isEqualTo(io.argos.proto.v1.InstanceState.UP);

      stack.tick();
      stack.flush();
      StateStack.Stored stored = stack.stored(id);
      assertThat(stored.status()).isEqualTo("WARN");
      assertThat(stored.warnFlags()).containsExactly("clock_skew");
      assertThat(stored.clockOffsetMs()).isNotNull();
      assertThat(stored.clockOffsetMs().longValue()).isBetween(2_500L, 3_500L);
      assertThat(stack.stored(exact.instanceId()).status()).isEqualTo("UP");

      JsonNode instance = null;
      for (JsonNode i : stack.get("/api/v1/apps/" + token.appId() + "/instances")) {
        if (i.path("instance_id").asInt() == id) {
          instance = i;
        }
      }
      assertThat(instance).isNotNull();
      assertThat(instance.path("state").asString()).isEqualTo("WARN");
      assertThat(instance.path("clock_skew_ms").asLong()).isBetween(2_500L, 3_500L);

      // the dashboard instance tiles carry the WARN reasons (ADR-0010 C-216 (2))
      java.time.Instant now =
          java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      JsonNode dashboard =
          stack.get(
              "/api/v1/apps/"
                  + token.appId()
                  + "/dashboard?from="
                  + now.minusSeconds(600)
                  + "&to="
                  + now.plusSeconds(60));
      JsonNode skewedTile = null;
      JsonNode exactTile = null;
      for (JsonNode t : dashboard.path("instances")) {
        if (t.path("instance_id").asInt() == id) {
          skewedTile = t;
        } else if (t.path("instance_id").asInt() == exact.instanceId()) {
          exactTile = t;
        }
      }
      assertThat(skewedTile).isNotNull();
      assertThat(skewedTile.path("state").asString()).isEqualTo("WARN");
      assertThat(skewedTile.path("warn_reasons").isArray()).isTrue();
      assertThat(skewedTile.path("warn_reasons").size()).isEqualTo(1);
      assertThat(skewedTile.path("warn_reasons").get(0).asString()).isEqualTo("clock_skew");
      assertThat(exactTile).isNotNull();
      assertThat(exactTile.path("state").asString()).isEqualTo("UP");
      assertThat(exactTile.path("warn_reasons").isArray()).isTrue();
      assertThat(exactTile.path("warn_reasons").size()).isZero();

      Gauge gauge =
          stack
              .server
              .bean(MeterRegistry.class)
              .get("argos.clock.offset.ms")
              .tag("instance", Integer.toString(id))
              .gauge();
      assertThat(gauge.value()).isBetween(2_500.0, 3_500.0);
    }
  }
}
