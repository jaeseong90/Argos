/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.ServerMessage;
import io.argos.server.session.InstanceRetentionJob.Action;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.testing.auth.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The daily instance retention of 12 §4.3 ({@code archive-after} 7 days, {@code purge-after} 30
 * days) on a fake clock: the boundaries (exactly 7 days of silence archives, 7 days minus a second
 * does not; 30 days purges an archived instance), live instances untouched, a failed step not
 * stopping the other. The SQL of the store is checked on PostgreSQL by {@code InstanceNamingIT}.
 */
@Tag("req:F-S28")
@Tag("req:DSN-12-04")
class InstanceRetentionJobTest {

  private static final Duration ARCHIVE = Duration.ofDays(7);
  private static final Duration PURGE = Duration.ofDays(30);

  private final MutableClock clock = new MutableClock();
  private final InMemorySessionRegistry registry = new InMemorySessionRegistry();
  private final MemoryStore store = new MemoryStore();
  private final InstanceRetentionJob job =
      new InstanceRetentionJob(store, registry, clock, ARCHIVE, PURGE);

  @Test
  @DisplayName(
      "12 §4.3 the rule: silent 7 days archives (the boundary included), 7 days minus 1 s keeps;"
          + " an archived instance silent 30 days is deleted, 30 days minus 1 s keeps; live never")
  void decideBoundaries() {
    Instant now = Instant.parse("2026-11-22T00:00:00Z");
    assertThat(InstanceRetentionJob.decide(now.minus(ARCHIVE), false, false, now, ARCHIVE, PURGE))
        .isEqualTo(Action.ARCHIVE);
    assertThat(
            InstanceRetentionJob.decide(
                now.minus(ARCHIVE).plusSeconds(1), false, false, now, ARCHIVE, PURGE))
        .isEqualTo(Action.KEEP);
    assertThat(InstanceRetentionJob.decide(now.minus(PURGE), true, false, now, ARCHIVE, PURGE))
        .isEqualTo(Action.PURGE);
    assertThat(
            InstanceRetentionJob.decide(
                now.minus(PURGE).plusSeconds(1), true, false, now, ARCHIVE, PURGE))
        .isEqualTo(Action.KEEP);
    assertThat(InstanceRetentionJob.decide(now.minus(PURGE), false, false, now, ARCHIVE, PURGE))
        .as("not archived yet: archived first, deleted by a later run")
        .isEqualTo(Action.ARCHIVE);
    assertThat(InstanceRetentionJob.decide(now.minus(PURGE), true, true, now, ARCHIVE, PURGE))
        .isEqualTo(Action.KEEP);
    assertThat(InstanceRetentionJob.decide(now.minus(ARCHIVE), false, true, now, ARCHIVE, PURGE))
        .isEqualTo(Action.KEEP);
  }

  @Test
  @DisplayName(
      "12 §4.3 a run passes now − 7 days, now − 30 days and the live instance ids to the store;"
          + " day by day an instance is archived on day 7 and deleted on day 30")
  void runOverDays() {
    Instant start = clock.instant();
    store.add(1, start, null);
    store.add(2, start.plus(Duration.ofDays(1)), null);
    AgentSession live = session(3);
    registry.register(live);
    store.add(3, start, null);

    clock.advance(ARCHIVE.minusSeconds(1));
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(0, 0));
    assertThat(store.lastArchiveCutoff).isEqualTo(clock.instant().minus(ARCHIVE));
    assertThat(store.lastPurgeCutoff).isEqualTo(clock.instant().minus(PURGE));
    assertThat(store.lastLive).containsExactly(3);

    clock.advance(Duration.ofSeconds(1));
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(1, 0));
    assertThat(store.rows.get(1).archivedAt).isEqualTo(clock.instant());
    assertThat(store.rows.get(2).archivedAt).isNull();
    assertThat(store.rows.get(3).archivedAt).as("live").isNull();

    clock.advance(Duration.ofDays(1));
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(1, 0));
    assertThat(store.rows.get(2).archivedAt).isNotNull();

    clock.advance(PURGE.minus(ARCHIVE).minus(Duration.ofDays(1)).minusSeconds(1));
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(0, 0));
    clock.advance(Duration.ofSeconds(1));
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(0, 1));
    assertThat(store.rows).containsOnlyKeys(2, 3);
  }

  @Test
  @DisplayName("12 §4.3 a failing archive step does not stop the purge step, and no exception")
  void failingStep() {
    store.add(1, clock.instant().minus(PURGE), clock.instant().minus(Duration.ofDays(20)));
    store.failArchive = true;
    assertThat(job.run()).isEqualTo(new InstanceRetentionJob.Result(0, 1));
  }

  @Test
  @DisplayName("12 §4.3 without a store (no PostgreSQL in the context) a run does nothing")
  void noStore() {
    InstanceRetentionJob none = new InstanceRetentionJob(null, registry, clock, ARCHIVE, PURGE);
    assertThat(none.run()).isEqualTo(new InstanceRetentionJob.Result(0, 0));
    assertThatThrownBy(() -> new InstanceRetentionJob(store, registry, clock, Duration.ZERO, PURGE))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private AgentSession session(int instanceId) {
    return new AgentSession(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "argos_AbCdEfGh",
        new AppRef(7, "order-api", "prod"),
        instanceId,
        "host-" + instanceId + ":8080",
        1,
        IpMode.MASKED,
        UserIdMode.OFF,
        "1.4.0",
        "",
        Boolean.FALSE,
        null,
        List.of(),
        clock.millis(),
        new ClockSync(clock.millis(), clock.millis()),
        new SessionStream(new RecordingObserver<ServerMessage>()));
  }

  /** The store applying {@link InstanceRetentionJob#decide} to rows in memory. */
  private static final class MemoryStore implements InstanceRetentionStore {
    final Map<Integer, Row> rows = new LinkedHashMap<>();
    Instant lastArchiveCutoff;
    Instant lastPurgeCutoff;
    List<Integer> lastLive = List.of();
    boolean failArchive;

    void add(int id, Instant silentSince, Instant archivedAt) {
      Row r = new Row();
      r.silentSince = silentSince;
      r.archivedAt = archivedAt;
      rows.put(id, r);
    }

    @Override
    public int archiveSilentSince(Instant silentSince, Set<Integer> live, Instant now) {
      if (failArchive) {
        throw new IllegalStateException("PostgreSQL down");
      }
      lastArchiveCutoff = silentSince;
      lastLive = new ArrayList<>(live);
      int n = 0;
      for (Map.Entry<Integer, Row> e : rows.entrySet()) {
        Row r = e.getValue();
        if (r.archivedAt == null
            && InstanceRetentionJob.decide(
                    r.silentSince,
                    false,
                    live.contains(e.getKey()),
                    now,
                    Duration.between(silentSince, now),
                    Duration.ofDays(36_500))
                == Action.ARCHIVE) {
          r.archivedAt = now;
          n++;
        }
      }
      return n;
    }

    @Override
    public int purgeSilentSince(Instant silentSince, Set<Integer> live) {
      lastPurgeCutoff = silentSince;
      int before = rows.size();
      rows.entrySet()
          .removeIf(
              e ->
                  e.getValue().archivedAt != null
                      && !live.contains(e.getKey())
                      && !e.getValue().silentSince.isAfter(silentSince));
      return before - rows.size();
    }

    static final class Row {
      Instant silentSince;
      Instant archivedAt;
    }
  }
}
