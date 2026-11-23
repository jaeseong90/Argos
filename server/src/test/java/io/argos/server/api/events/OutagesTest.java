/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.events;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.storage.postgres.catalog.CatalogRepository.StateEventRecord;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Collection outages from state events (D-18, C-5). */
@Tag("req:D-18")
@Tag("req:C-5")
class OutagesTest {

  private static final Instant FROM = Instant.parse("2026-11-08T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-11-08T01:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-08T05:00:00Z");

  private static Instant at(int minute) {
    return FROM.plusSeconds(minute * 60L);
  }

  private static StateEventRecord state(int instance, Instant ts, String to, String reason) {
    return new StateEventRecord(instance, ts, "state", to, reason);
  }

  @Test
  @DisplayName("T-ACC-18a a DOWN..UP span is an outage of its instance with its reason")
  void downUp() {
    List<Outages.Outage> out =
        Outages.derive(
            List.of(
                state(7, at(10), "STALE", null),
                state(7, at(11), "DOWN", null),
                state(7, at(20), "UP", null),
                state(8, at(30), "DOWN", "queue_overflow"),
                state(8, at(31), "WARN", null)),
            FROM,
            TO,
            Set.of(),
            NOW);
    assertThat(out)
        .containsExactly(
            new Outages.Outage(at(10), at(20), Outages.SESSION_LOST, 7),
            new Outages.Outage(at(30), at(31), Outages.QUEUE_OVERFLOW, 8));
  }

  @Test
  @DisplayName(
      "T-ACC-18a an outage that began before the range starts at its start; an open one ends at"
          + " the range end, or has no end when the range reaches now")
  void clipping() {
    List<StateEventRecord> events =
        List.of(
            state(7, FROM.minusSeconds(3600), "DOWN", "unknown_reason"),
            state(7, at(5), "UP", null),
            state(8, at(50), "DOWN", null));
    assertThat(Outages.derive(events, FROM, TO, Set.of(), NOW))
        .containsExactly(
            new Outages.Outage(FROM, at(5), Outages.SESSION_LOST, 7),
            new Outages.Outage(at(50), TO, Outages.SESSION_LOST, 8));
    assertThat(Outages.derive(events, FROM, TO, Set.of(), TO.minusSeconds(1)))
        .contains(new Outages.Outage(at(50), null, Outages.SESSION_LOST, 8));
    assertThat(Outages.derive(events, FROM, TO, Set.of(8), NOW))
        .containsExactly(new Outages.Outage(at(50), TO, Outages.SESSION_LOST, 8));
  }

  @Test
  @DisplayName("T-ACC-18a a server stop..start span is a server_down outage of every application")
  void serverDown() {
    List<Outages.Outage> out =
        Outages.derive(
            List.of(
                new StateEventRecord(null, at(40), "stop", null, null),
                new StateEventRecord(null, at(45), "start", null, null),
                state(7, at(1), "UP", null)),
            FROM,
            TO,
            Set.of(99),
            NOW);
    assertThat(out).containsExactly(new Outages.Outage(at(40), at(45), Outages.SERVER_DOWN, null));
  }

  @Test
  @DisplayName(
      "T-ACC-18a a DOWN after the AgentBye grace (agent_bye) is a planned stop, not an outage;"
          + " a lost session still is (ADR-0010 C-214)")
  void agentByeIsNoOutage() {
    List<StateEventRecord> events =
        List.of(
            state(7, at(10), "DOWN", "agent_bye"),
            state(7, at(20), "UP", "recovered"),
            state(8, at(30), "STALE", "session_lost"),
            state(8, at(31), "DOWN", "session_lost"),
            state(9, FROM.minusSeconds(600), "DOWN", "agent_bye"));
    assertThat(Outages.derive(events, FROM, TO, Set.of(), NOW))
        .containsExactly(new Outages.Outage(at(30), TO, Outages.SESSION_LOST, 8));
  }
}
