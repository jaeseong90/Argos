/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.errorgroup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.IngestStore;
import io.argos.server.ingest.IngestStore.ErrorGroupRow;
import io.argos.server.ingest.IngestStore.StackCandidate;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.ops.ErrorGroupCleanupRepository;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The nightly error group clean-up against real PostgreSQL 16 (13 §12.4 storage table, ADR-0010
 * C-255 (5)). The whole server runs with {@code argos.ops.error-group-cleanup.enabled=false} (the
 * job bean exists, the cron never fires; the test calls it: the "run now" hook of 18 §19).
 *
 * <ul>
 *   <li>T-SV-014h: groups last seen 91 days and 90 days + 1 µs ago are deleted, 89 days and exactly
 *       90 days ago kept; a second run deletes nothing; 25,000 aged groups go in batches of 10,000.
 *   <li>T-SV-014i: a group that occurs again before the run (the ingest upsert moves {@code
 *       last_seen}) is kept; a deleted group that occurs again is inserted again by the same upsert
 *       with the new occurrence only (first seen, count) and is a stack candidate again; the error
 *       group catalog of the API no longer returns a deleted group.
 * </ul>
 */
@Tag("integration")
@Tag("req:F-S19")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ErrorGroupCleanupJobIT {

  private static final int BOUNDARY_APP = 9_201;
  private static final int REOCCUR_APP = 9_202;
  private static final int BULK_APP = 9_203;
  private static final int BULK_ROWS = 25_000;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of("argos.ops.error-group-cleanup.enabled", "false"));
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static ErrorGroupCleanupJob serverJob() {
    return server.bean(ErrorGroupCleanupJob.class);
  }

  private static ErrorGroupCleanupJob jobAt(Instant now) {
    return new ErrorGroupCleanupJob(
        server.bean(ArgosProperties.class),
        server.bean(ErrorGroupCleanupRepository.class)::deleteUnseenSince,
        Clock.fixed(now, ZoneOffset.UTC));
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-014h groups last seen 91 d and 90 d + 1 us ago deleted, 89 d and exactly 90 d kept;"
          + " the rerun deletes nothing; enabled=false leaves the bean unscheduled; 25,000 aged"
          + " groups deleted in batches")
  void ninetyDayBoundary() throws Exception {
    assertThat(serverJob().isScheduled()).isFalse();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant cutoff = now.minus(Duration.ofDays(90));
    insert(BOUNDARY_APP, 1, now.minus(Duration.ofDays(91)));
    insert(BOUNDARY_APP, 2, now.minus(Duration.ofDays(89)));
    insert(BOUNDARY_APP, 3, cutoff);
    insert(BOUNDARY_APP, 4, cutoff.minus(1, ChronoUnit.MICROS));
    ErrorGroupCleanupJob job = jobAt(now);
    ErrorGroupCleanupJob.Result first = job.run().orElseThrow();
    assertThat(first.cutoff()).isEqualTo(cutoff);
    assertThat(first.deleted()).isEqualTo(2);
    assertThat(groups(BOUNDARY_APP)).containsExactly(2L, 3L);

    ErrorGroupCleanupJob.Result again = job.run().orElseThrow();
    assertThat(again.deleted()).isZero();
    assertThat(groups(BOUNDARY_APP)).containsExactly(2L, 3L);
    // The server's own bean runs on the system clock, strictly later than the fixed 'now': the
    // group last seen exactly 90 days before 'now' is past its cutoff and the only one deleted.
    assertThat(serverJob().run().orElseThrow().deleted()).isEqualTo(1);
    assertThat(groups(BOUNDARY_APP)).containsExactly(2L);

    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO argos.error_groups (app_id, group_id, error_class, message_pattern,"
              + " first_seen, last_seen, count_total) SELECT "
              + BULK_APP
              + ", g, 'java.lang.IllegalStateException', 'bulk <num>', now() - interval '120"
              + " days', now() - interval '100 days', 1 FROM generate_series(1, "
              + BULK_ROWS
              + ") g");
    }
    ErrorGroupCleanupJob.Result bulk = serverJob().run().orElseThrow();
    assertThat(bulk.deleted()).isEqualTo(BULK_ROWS);
    assertThat(groups(BULK_APP)).isEmpty();
    assertThat(groups(BOUNDARY_APP)).containsExactly(2L);
    System.out.printf(
        "[c255-error-group-cleanup] deleted=%d batches=%d duration_ms=%d (reference values)%n",
        bulk.deleted(),
        (bulk.deleted() + ErrorGroupCleanupRepository.BATCH - 1)
            / ErrorGroupCleanupRepository.BATCH,
        bulk.durationMs());
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-014i a group that occurs again before the run is kept; a deleted group that occurs"
          + " again is inserted again with the new occurrence only and is a stack candidate again;"
          + " the API catalog no longer returns a deleted group")
  void reoccurringGroups() throws Exception {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant old = now.minus(Duration.ofDays(100));
    insert(REOCCUR_APP, 11, old); // occurs again before the run
    insert(REOCCUR_APP, 12, old); // deleted, occurs again after the run
    insert(REOCCUR_APP, 13, old); // deleted, stays gone
    setStack(REOCCUR_APP, 12, "at example.Service.run(Service.java)");
    IngestStore store = server.bean(IngestStore.class);
    CatalogRepository catalog = server.bean(CatalogRepository.class);
    assertThat(catalog.errorGroups(REOCCUR_APP, List.of(11L, 12L, 13L))).hasSize(3);

    // The ingest upsert of an occurrence now (ErrorGroupRecorder's flush) refreshes last_seen.
    store.upsertErrorGroups(List.of(row(REOCCUR_APP, 11, now, 2)));
    ErrorGroupCleanupJob.Result result = serverJob().run().orElseThrow();
    assertThat(result.deleted()).isEqualTo(2);
    assertThat(groups(REOCCUR_APP)).containsExactly(11L);
    assertThat(catalog.errorGroups(REOCCUR_APP, List.of(11L, 12L, 13L))).containsOnlyKeys(11L);
    assertThat(countTotal(REOCCUR_APP, 11)).isEqualTo(3);

    // Group 12 occurs again: inserted again, its old first_seen, count and stack are gone.
    Instant later = now.plusSeconds(5);
    store.upsertErrorGroups(List.of(row(REOCCUR_APP, 12, later, 4)));
    assertThat(groups(REOCCUR_APP)).containsExactly(11L, 12L);
    CatalogRepository.ErrorGroupRecord again =
        catalog.errorGroups(REOCCUR_APP, List.of(12L)).get(12L);
    assertThat(again).isNotNull();
    assertThat(again.firstSeen()).isEqualTo(later);
    assertThat(again.lastSeen()).isEqualTo(later);
    assertThat(again.sampleStack()).isNull();
    assertThat(countTotal(REOCCUR_APP, 12)).isEqualTo(4);
    List<StackCandidate> candidates =
        store.groupsWithoutStack(now.minus(Duration.ofHours(1)), 1000);
    assertThat(candidates).contains(new StackCandidate(REOCCUR_APP, 12L));
    assertThat(candidates).doesNotContain(new StackCandidate(REOCCUR_APP, 13L));

    assertThat(serverJob().run().orElseThrow().deleted()).isZero();
    assertThat(groups(REOCCUR_APP)).containsExactly(11L, 12L);
  }

  private static ErrorGroupRow row(int appId, long groupId, Instant at, long count) {
    return new ErrorGroupRow(appId, groupId, 0, 0, "occurs <num> times", at, at, count);
  }

  private static void insert(int appId, long groupId, Instant lastSeen) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.error_groups (app_id, group_id, error_class, message_pattern,"
                    + " first_seen, last_seen, count_total) VALUES (?, ?,"
                    + " 'java.lang.IllegalStateException', 'boundary <num>', ?, ?, 1)")) {
      st.setInt(1, appId);
      st.setLong(2, groupId);
      st.setTimestamp(3, Timestamp.from(lastSeen.minus(Duration.ofDays(10))));
      st.setTimestamp(4, Timestamp.from(lastSeen));
      st.executeUpdate();
    }
  }

  private static void setStack(int appId, long groupId, String stack) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "UPDATE argos.error_groups SET sample_stack = ? WHERE app_id = ? AND group_id"
                    + " = ?")) {
      st.setString(1, stack);
      st.setInt(2, appId);
      st.setLong(3, groupId);
      assertThat(st.executeUpdate()).isEqualTo(1);
    }
  }

  private static long countTotal(int appId, long groupId) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT count_total FROM argos.error_groups WHERE app_id = ? AND group_id = ?")) {
      st.setInt(1, appId);
      st.setLong(2, groupId);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getLong(1);
      }
    }
  }

  private static List<Long> groups(int appId) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT group_id FROM argos.error_groups WHERE app_id = ? ORDER BY group_id")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        List<Long> out = new ArrayList<>();
        while (rs.next()) {
          out.add(rs.getLong(1));
        }
        return out;
      }
    }
  }
}
