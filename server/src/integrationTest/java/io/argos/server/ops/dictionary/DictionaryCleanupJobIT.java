/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.dictionary;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.DictionaryResolver;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import java.lang.management.ManagementFactory;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The nightly dictionary clean-up against real PostgreSQL 16 and ClickHouse (SEC-13, 17 §9.3,
 * ADR-0010 C-238 (14)). The whole server runs with {@code
 * argos.ops.dictionary-cleanup.enabled=false} (the job bean exists, the cron never fires; the test
 * calls it: the "run now" hook of 18 §19).
 *
 * <ul>
 *   <li>T-SV-014d: rows last seen 91 days and 90 days + 1 µs ago are deleted, 89 days and exactly
 *       90 days ago kept; a second run deletes nothing.
 *   <li>T-SV-014e: a fake agent's service entry, stored through the session stream, is deleted by
 *       the job and dropped from the server's dictionary cache; the agent keeps naming it in rows
 *       (a long-lived agent does not send the text again) and the server requests it, stores it
 *       again and resolves it; a second entry that the agent sends again is stored again too.
 *   <li>T-SV-014f: 100,000 aged rows are deleted in batches; the line {@code [wo29-dict-cleanup]}
 *       records the duration, the relation size before and after and the bytes the run allocated
 *       (reference values: this machine is shared, INVALID for a verdict).
 * </ul>
 */
@Tag("integration")
@Tag("req:SEC-13")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DictionaryCleanupJobIT {

  private static final int BOUNDARY_APP = 9_101;
  private static final int BULK_APP = 9_102;
  private static final int BULK_ROWS = 100_000;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of("argos.ops.dictionary-cleanup.enabled", "false"));
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

  private static DictionaryCleanupJob serverJob() {
    return server.bean(DictionaryCleanupJob.class);
  }

  private static DictionaryResolver resolver() {
    return server.bean(DictionaryResolver.class);
  }

  // --- T-SV-014d ------------------------------------------------------------------------------

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-014d rows last seen 91 d and 90 d + 1 us ago deleted, 89 d and exactly 90 d kept;"
          + " the rerun deletes nothing; enabled=false leaves the bean unscheduled")
  void ninetyDayBoundary() throws Exception {
    assertThat(serverJob().isScheduled()).isFalse();
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant cutoff = now.minus(Duration.ofDays(90));
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.dictionary (app_id, kind, hash64, text, first_seen, last_seen)"
                    + " VALUES (?, 'service', ?, ?, ?, ?)")) {
      insert(st, 1, "GET /seen-91d", now.minus(Duration.ofDays(91)));
      insert(st, 2, "GET /seen-89d", now.minus(Duration.ofDays(89)));
      insert(st, 3, "GET /seen-90d", cutoff);
      insert(st, 4, "GET /seen-90d-1us", cutoff.minus(1, ChronoUnit.MICROS));
    }
    DictionaryCleanupJob job =
        new DictionaryCleanupJob(
            server.bean(ArgosProperties.class),
            server.bean(DictionaryCleanupRepository.class)::deleteUnseenSince,
            Clock.fixed(now, ZoneOffset.UTC),
            resolver()::forgetStored);
    DictionaryCleanupJob.Result first = job.run().orElseThrow();
    assertThat(first.cutoff()).isEqualTo(cutoff);
    assertThat(first.deleted()).isEqualTo(2);
    assertThat(hashes(BOUNDARY_APP)).containsExactly(2L, 3L);

    DictionaryCleanupJob.Result again = job.run().orElseThrow();
    assertThat(again.deleted()).isZero();
    assertThat(hashes(BOUNDARY_APP)).containsExactly(2L, 3L);
    // The server's own bean runs on the system clock, which is strictly later than the fixed
    // 'now' by the time it runs: the row last seen exactly 90 days before 'now' is now past its
    // cutoff and is the only one it deletes; the 89-day row stays.
    assertThat(serverJob().run().orElseThrow().deleted()).isEqualTo(1);
    assertThat(hashes(BOUNDARY_APP)).containsExactly(2L);
  }

  private static void insert(PreparedStatement st, long hash, String text, Instant lastSeen)
      throws SQLException {
    st.setInt(1, BOUNDARY_APP);
    st.setLong(2, hash);
    st.setString(3, text);
    st.setTimestamp(4, Timestamp.from(lastSeen));
    st.setTimestamp(5, Timestamp.from(lastSeen));
    st.executeUpdate();
  }

  // --- T-SV-014e ------------------------------------------------------------------------------

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-014e an entry deleted by the job and evicted from the cache is requested from the"
          + " agent when a row names it again, stored again and resolved; one sent again is stored")
  void deletedEntryComesBack() throws Exception {
    String appName = "dict-cleanup-it";
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, appName, "prod"));
    }
    int appId = token.appId();
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-1:8080")
            .records(new Records(AgentClock.system(), 61, 61))
            .agentEpoch(1_061)
            .answerDictionaryRequests(true)
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      Records records = agent.records();
      String requestedText = "GET /dict/requested-again";
      String resentText = "GET /dict/sent-again";
      long requested = records.id(DictionaryKind.SERVICE, requestedText);
      long resent = records.id(DictionaryKind.SERVICE, resentText);

      // Stored through the session stream (11 §7 pre-send) and known to the resolver.
      TransactionRecord first = tx(records, requestedText);
      TransactionRecord second = tx(records, resentText);
      agent.sendNewDictionaryEntries();
      send(agent, first);
      send(agent, second);
      await(() -> stored(appId, requested) && stored(appId, resent), "first store");
      await(
          () ->
              resolver().isKnown(appId, DictionaryKind.SERVICE, requested)
                  && resolver().isKnown(appId, DictionaryKind.SERVICE, resent),
          "known");

      // 91 days without the agent sending either text again.
      try (Connection c = env.connect(PgAccount.APP);
          PreparedStatement st =
              c.prepareStatement(
                  "UPDATE argos.dictionary SET last_seen = now() - interval '91 days' WHERE"
                      + " app_id = ? AND kind = 'service' AND hash64 IN (?, ?)")) {
        st.setInt(1, appId);
        st.setLong(2, requested);
        st.setLong(3, resent);
        assertThat(st.executeUpdate()).isEqualTo(2);
      }
      DictionaryCleanupJob.Result result = serverJob().run().orElseThrow();
      assertThat(result.deleted()).isEqualTo(2);
      assertThat(result.evicted()).isEqualTo(2);
      assertThat(stored(appId, requested)).isFalse();
      assertThat(stored(appId, resent)).isFalse();
      assertThat(resolver().isKnown(appId, DictionaryKind.SERVICE, requested)).isFalse();
      assertThat(resolver().isKnown(appId, DictionaryKind.SERVICE, resent)).isFalse();

      // A row names the first id again (the agent does not send its text again, it was sent in
      // this session): the server looks it up, asks the agent and stores the answer.
      TransactionRecord again = tx(records, requestedText);
      assertThat(records.drainNewEntries())
          .noneMatch(e -> e.getHash() == requested || e.getHash() == resent);
      send(agent, again);
      await(() -> stored(appId, requested), "requested entry stored again");
      assertThat(text(appId, requested)).isEqualTo(requestedText);
      await(() -> resolver().isKnown(appId, DictionaryKind.SERVICE, requested), "resolved");

      // The agent sends the second text again (a restarted agent pre-sends its entries).
      agent.sendDictionaryEntries(
          List.of(records.entry(DictionaryKind.SERVICE, resent).orElseThrow()));
      await(() -> stored(appId, resent), "re-sent entry stored again");
      assertThat(text(appId, resent)).isEqualTo(resentText);
      await(() -> resolver().isKnown(appId, DictionaryKind.SERVICE, resent), "resolved");
    }
  }

  private static TransactionRecord tx(Records records, String service) {
    return records.http(service, records.clock().nowMs() - 25, 25_000).build();
  }

  private static void send(FakeAgent agent, TransactionRecord tx) {
    CallResult r = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
    assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
  }

  private static boolean stored(int appId, long hash) {
    return text(appId, hash) != null;
  }

  private static String text(int appId, long hash) {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT text FROM argos.dictionary WHERE app_id = ? AND kind = 'service' AND"
                    + " hash64 = ?")) {
      st.setInt(1, appId);
      st.setLong(2, hash);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime() < deadline).as("waiting for " + what).isTrue();
      TimeUnit.MILLISECONDS.sleep(100);
    }
  }

  // --- T-SV-014f ------------------------------------------------------------------------------

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-014f 100,000 aged rows deleted in batches of 10,000; [wo29-dict-cleanup] duration,"
          + " relation size before and after, allocation (reference values)")
  void bulkDelete() throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO argos.dictionary (app_id, kind, hash64, text, first_seen, last_seen)"
              + " SELECT "
              + BULK_APP
              + ", 'sql', g, 'SELECT c' || g || ' FROM t WHERE id = ?', now() - interval '100"
              + " days', now() - interval '100 days' FROM generate_series(1, "
              + BULK_ROWS
              + ") g");
    }
    long before = relationSize();
    long rowsBefore = count();
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long thread = Thread.currentThread().threadId();
    long allocatedBefore = threads.getThreadAllocatedBytes(thread);
    DictionaryCleanupJob.Result result = serverJob().run().orElseThrow();
    long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
    long after = relationSize();
    long rowsAfter = count();
    assertThat(result.deleted()).isEqualTo(BULK_ROWS);
    assertThat(rowsBefore - rowsAfter).isEqualTo(BULK_ROWS);
    assertThat(hashes(BULK_APP)).isEmpty();
    System.out.printf(
        "[wo29-dict-cleanup] deleted=%d batches=%d duration_ms=%d relation_bytes_before=%d"
            + " relation_bytes_after=%d allocated_bytes=%d rows_before=%d rows_after=%d"
            + " (reference values, shared machine: INVALID)%n",
        result.deleted(),
        (result.deleted() + DictionaryCleanupRepository.BATCH - 1)
            / DictionaryCleanupRepository.BATCH,
        result.durationMs(),
        before,
        after,
        allocated,
        rowsBefore,
        rowsAfter);
  }

  private static long relationSize() throws SQLException {
    return scalar("SELECT pg_total_relation_size('argos.dictionary')");
  }

  private static long count() throws SQLException {
    return scalar("SELECT count(*) FROM argos.dictionary");
  }

  private static long scalar(String sql) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static List<Long> hashes(int appId) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT hash64 FROM argos.dictionary WHERE app_id = ? ORDER BY hash64")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        List<Long> out = new java.util.ArrayList<>();
        while (rs.next()) {
          out.add(rs.getLong(1));
        }
        return out;
      }
    }
  }
}
