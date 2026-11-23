/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * T-ACC-18 (D-18, C-5): the collection outages that the query API derives from the events WO-22
 * writes ({@code api.events.Outages} input contract), on the real server with real PostgreSQL and
 * ClickHouse and a server clock moved by hand: a session lost without {@code AgentBye} shows in
 * {@code outages[]} of {@code /apps/{appId}/collection-health} and {@code /apps/{appId}/dashboard}
 * as {@code session_lost} from its STALE transition, open while it lasts and closed by the
 * reconnect; a graceful server stop and start is a {@code server_down} outage; an {@code AgentBye}
 * followed by the 5 minute grace (DOWN, reason {@code agent_bye}) is a planned stop and no outage
 * (ADR-0010 C-214).
 */
@Tag("integration")
@Tag("req:D-18")
@Tag("req:C-5")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutageIT {

  private static final MutableClock CLOCK = new MutableClock();
  private static StateStack stack;

  @BeforeAll
  static void start() {
    stack = StateStack.start(CLOCK);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  private record Outage(Instant from, Instant to, String reason, Integer instanceId) {}

  private static List<Outage> outages(JsonNode data) {
    List<Outage> out = new ArrayList<>();
    for (JsonNode o : data.path("outages")) {
      out.add(
          new Outage(
              Instant.parse(o.path("from").asString()),
              o.path("to").isNull() ? null : Instant.parse(o.path("to").asString()),
              o.path("reason").asString(),
              o.path("instance_id").isNull() ? null : o.path("instance_id").asInt()));
    }
    return out;
  }

  private static String range(Instant from, Instant to) {
    return "from=" + from + "&to=" + to;
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-ACC-18 a session lost without Bye for 5 minutes: outages[] session_lost from the STALE transition in collection-health and dashboard, closed by the reconnect")
  void sessionLost() throws Exception {
    AgentTokens.IssuedToken token = stack.token("outage-app");
    int appId = token.appId();
    Instant t0 = CLOCK.instant();
    int id;
    try (FakeAgent a = stack.agent(token, "outage-app", "host-o:8080", Duration.ZERO)) {
      assertThat(a.connect().accepted()).isTrue();
      id = a.instanceId();
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      a.cancelSession();
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.server.bean(SessionRegistry.class).find(id).isEmpty());
    }
    for (int s = 10; s <= 300; s += 10) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
    }
    assertThat(stack.state(id)).isEqualTo(InstanceState.DOWN);
    stack.flush();
    Instant staleAt = t0.plusSeconds(60);
    Instant now = CLOCK.instant();
    String r = range(t0.minusSeconds(60), now);

    List<Outage> health = outages(stack.get("/api/v1/apps/" + appId + "/collection-health?" + r));
    assertThat(health)
        .as("ongoing: no end while the range reaches the present")
        .containsExactly(new Outage(staleAt, null, "session_lost", id));
    List<Outage> dashboard = outages(stack.get("/api/v1/apps/" + appId + "/dashboard?" + r));
    assertThat(dashboard).containsExactly(new Outage(staleAt, null, "session_lost", id));

    try (FakeAgent back = stack.agent(token, "outage-app", "host-o:8080", Duration.ZERO)) {
      assertThat(back.connect().accepted()).isTrue();
      assertThat(back.instanceId()).isEqualTo(id);
      Instant reconnect = CLOCK.instant();
      stack.flush();
      CLOCK.advance(Duration.ofSeconds(30));
      assertThat(back.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      String after = range(t0.minusSeconds(60), CLOCK.instant());
      assertThat(outages(stack.get("/api/v1/apps/" + appId + "/collection-health?" + after)))
          .containsExactly(new Outage(staleAt, reconnect, "session_lost", id));
      assertThat(outages(stack.get("/api/v1/apps/" + appId + "/dashboard?" + after)))
          .containsExactly(new Outage(staleAt, reconnect, "session_lost", id));
      // a range that ends before the reconnect: the outage ends with the range
      Instant cut = staleAt.plusSeconds(120);
      assertThat(
              outages(
                  stack.get(
                      "/api/v1/apps/"
                          + appId
                          + "/collection-health?"
                          + range(t0.minusSeconds(60), cut))))
          .containsExactly(new Outage(staleAt, cut, "session_lost", id));
    }
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-ACC-18 a graceful server stop and start: server stop and start events, outages[] server_down between them")
  void serverDown() throws Exception {
    AgentTokens.IssuedToken token = stack.token("outage-srv");
    int appId = token.appId();
    Instant before = CLOCK.instant();
    CLOCK.advance(Duration.ofSeconds(10));
    Instant stopAt = CLOCK.instant();
    stack.restartServer(Duration.ofMinutes(2));
    Instant startAt = CLOCK.instant();
    CLOCK.advance(Duration.ofSeconds(10));
    List<StateStack.Event> server = stack.serverEvents();
    assertThat(server)
        .filteredOn(e -> !e.ts().isBefore(stopAt))
        .extracting(e -> e.type() + "@" + e.ts())
        .containsExactly("stop@" + stopAt, "start@" + startAt);
    List<Outage> health =
        outages(
            stack.get(
                "/api/v1/apps/" + appId + "/collection-health?" + range(before, CLOCK.instant())));
    assertThat(health).contains(new Outage(stopAt, startAt, "server_down", null));
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-ACC-18 an AgentBye then the 5 minute grace (DOWN agent_bye) is no outage in collection-health and dashboard; a session lost without Bye in the same app still is (C-214)")
  void byeIsNoOutage() throws Exception {
    AgentTokens.IssuedToken token = stack.token("outage-bye");
    int appId = token.appId();
    CLOCK.advance(Duration.ofSeconds(10));
    Instant t0 = CLOCK.instant();
    int byeId;
    int lostId;
    try (FakeAgent bye = stack.agent(token, "outage-bye", "host-b:8080", Duration.ZERO);
        FakeAgent lost = stack.agent(token, "outage-bye", "host-l:8080", Duration.ZERO)) {
      assertThat(bye.connect().accepted()).isTrue();
      assertThat(lost.connect().accepted()).isTrue();
      byeId = bye.instanceId();
      lostId = lost.instanceId();
      assertThat(bye.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(lost.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      bye.sendBye(ByeReason.SHUTDOWN);
      bye.completeSession();
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(
              () -> {
                stack.flush();
                return stack.events(byeId).stream().anyMatch(e -> e.type().equals("stop"));
              });
      bye.cancelSession();
      lost.cancelSession();
      SessionRegistry registry = stack.server.bean(SessionRegistry.class);
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> registry.find(byeId).isEmpty() && registry.find(lostId).isEmpty());
    }
    for (int s = 10; s <= 310; s += 10) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
    }
    assertThat(stack.state(byeId)).isEqualTo(InstanceState.DOWN);
    assertThat(stack.state(lostId)).isEqualTo(InstanceState.DOWN);
    stack.flush();
    assertThat(stack.events(byeId))
        .filteredOn(e -> e.type().equals("state"))
        .extracting(e -> e.to() + ":" + e.reason())
        .as("the Bye instance is DOWN for agent_bye (instance state kept)")
        .endsWith("DOWN:agent_bye");
    Instant staleAt = t0.plusSeconds(60);
    String r = range(t0.minusSeconds(5), CLOCK.instant());
    List<Outage> health = outages(stack.get("/api/v1/apps/" + appId + "/collection-health?" + r));
    assertThat(health)
        .as("only the lost session; the planned stop after Bye is no outage")
        .containsExactly(new Outage(staleAt, null, "session_lost", lostId));
    assertThat(health).noneMatch(o -> Integer.valueOf(byeId).equals(o.instanceId()));
    List<Outage> dashboard = outages(stack.get("/api/v1/apps/" + appId + "/dashboard?" + r));
    assertThat(dashboard).containsExactly(new Outage(staleAt, null, "session_lost", lostId));
  }
}
