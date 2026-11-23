/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.IngestFixtures.RAW_TABLES;
import static io.argos.server.ingest.IngestFixtures.eventually;
import static io.argos.server.ingest.IngestFixtures.metric;
import static io.argos.server.ingest.IngestFixtures.profile;
import static io.argos.server.ingest.IngestFixtures.sendSkewed;
import static io.argos.server.ingest.IngestFixtures.session;
import static io.argos.server.ingest.IngestFixtures.stats;
import static io.argos.server.ingest.IngestFixtures.tx;
import static io.argos.server.ingest.IngestFixtures.txBatch;
import static io.argos.server.ingest.IngestFixtures.txId;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.IngestFixtures.SkewedRun;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBlockInserter;
import io.argos.server.storage.clickhouse.RowBinaryBuffer;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * The ingest pipeline after the WAL against real PostgreSQL 16 and ClickHouse 26.8 (lane
 * durable-ingest): timestamp guard and C-84, error groups, dictionary caps, Apdex stamp, the
 * writer's statement and deduplication (WO-21 acceptance, 18 §10, appendix A).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("req:D-13")
@Tag("req:D-15")
class IngestPipelineIT {

  private static final DateTimeFormatter CH_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

  private ServerTestEnvironment env;

  @TempDir Path temp;

  @BeforeAll
  void start() throws Exception {
    env = ServerTestEnvironment.start();
    IngestStack.ensureMigrated(env);
  }

  @AfterAll
  void stop() {
    if (env != null) {
      env.close();
    }
  }

  private int app(String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.ensureApp(c, name, "prod");
    }
  }

  private IngestStack stack(String name) throws Exception {
    Path dir = temp.resolve(name);
    Files.createDirectories(dir);
    return IngestStack.start(env, dir);
  }

  private ClickHouseHttp reader() {
    return IngestFixtures.reader(env);
  }

  // --- T-SV-015 / T-ACC-15 ------------------------------------------------------------------

  @Test
  @Tag("req:D-15")
  @DisplayName(
      "T-SV-015 T-ACC-15 +10 s clamped and counted, -8 d rejected, -90 s late = 1 and never published")
  void timestampGuard() throws Exception {
    int appId = app("it-guard");
    try (IngestStack stack = stack("guard")) {
      long now = System.currentTimeMillis();
      SessionContext s = session(appId, 101, now, 0, 500, "");
      stack.append(
          WalRecordType.TX,
          s,
          txBatch(
              tx(txId(now, 1), now + 10_000, 1_000, 0, 0),
              tx(txId(now, 2), now - TimeUnit.DAYS.toMillis(8), 1_000, 0, 0),
              tx(txId(now, 3), now - 90_000, 1_000, 0, 0),
              tx(txId(now, 4), now - 1_000, 1_000, 0, 0)),
          1);
      stack.awaitLoaded();
      List<List<String>> rows =
          reader()
              .rows(
                  "SELECT tx_id, toUnixTimestamp64Milli(ts), late FROM argos.xlog WHERE app_id = "
                      + appId
                      + " ORDER BY tx_id");
      assertThat(rows).hasSize(3);
      assertThat(rows.get(0))
          .containsExactly(Long.toUnsignedString(txId(now, 1)), String.valueOf(now), "0");
      assertThat(rows.get(1))
          .containsExactly(Long.toUnsignedString(txId(now, 3)), String.valueOf(now - 90_000), "1");
      assertThat(rows.get(2))
          .containsExactly(Long.toUnsignedString(txId(now, 4)), String.valueOf(now - 1_000), "0");
      assertThat(
              stack.registry.get("argos.ts.clamped").tag("kind", "record_clamp").counter().count())
          .isEqualTo(1.0);
      assertThat(stack.registry.get("argos.ts.rejected").counter().count()).isEqualTo(1.0);
      assertThat(stack.registry.get("argos.late.arrivals").counter().count()).isEqualTo(1.0);
      // The real-time port (WO-22's bus) gets only the rows with late = 0 (C-128 proof by port).
      assertThat(stack.publisher.points)
          .extracting(XlogPoint::txId)
          .containsExactlyInAnyOrder(txId(now, 1), txId(now, 4));
    }
  }

  // --- C-84 / C-129 -------------------------------------------------------------------------

  private static long lastMidnightBefore(long ms) {
    return Math.floorDiv(ms, TimeUnit.DAYS.toMillis(1)) * TimeUnit.DAYS.toMillis(1);
  }

  private static LocalDate day(long ms) {
    return LocalDate.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC);
  }

  private static String where(int appId, int instanceId) {
    return " WHERE app_id = " + appId + " AND instance_id = " + instanceId;
  }

  private List<String> days(int appId, int instanceId) {
    List<String> days = new ArrayList<>();
    for (Table table : RAW_TABLES) {
      days.add(
          reader()
              .scalar(
                  "SELECT toString(groupUniqArray(toDate(ts))) FROM argos."
                      + table.tableName()
                      + where(appId, instanceId)));
    }
    return days;
  }

  /** Rows of one instance per raw table. */
  private Map<String, Long> rowCounts(int appId, int instanceId) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Table table : RAW_TABLES) {
      counts.put(
          table.tableName(),
          reader()
              .scalarLong(
                  "SELECT count() FROM argos." + table.tableName() + where(appId, instanceId)));
    }
    return counts;
  }

  private static double clamped(IngestStack stack, String kind) {
    return stack.registry.get("argos.ts.clamped").tag("kind", kind).counter().count();
  }

  /**
   * The rows of {@code run} hold the agent's times minus the session correction, without a clamp
   * (C-129 (2), (4)), on the UTC day of the true transaction start; {@code tx_id} is the agent's
   * (C-129 (6)).
   */
  private void assertStoredCorrected(int appId, SkewedRun run) {
    long correction = run.correctionMs();
    String as = "instance " + run.instanceId() + " (skew " + run.skewMs() + " ms)";
    String trueDay = day(run.trueStartMs()).toString();
    assertThat(List.of(day(run.trueWindowMs()), day(run.trueMetricMs())))
        .as("precondition: one true day")
        .containsOnly(day(run.trueStartMs()));
    assertThat(days(appId, run.instanceId()))
        .as("UTC day of every raw table, %s", as)
        .containsOnly("['" + trueDay + "']");
    String w = where(appId, run.instanceId());
    long tsMs = run.agentStartMs() - correction;
    assertThat(reader().rows("SELECT toUnixTimestamp64Milli(ts), tx_id FROM argos.xlog" + w))
        .as("xlog ts = agent ts - correction, tx_id kept, %s", as)
        .containsExactly(List.of(String.valueOf(tsMs), Long.toUnsignedString(run.txId())));
    assertThat(
            reader().rows("SELECT toUnixTimestamp64Milli(ts), tx_id FROM argos.profile_step" + w))
        .as("profile_step ts = agent ts - correction, %s", as)
        .containsExactly(List.of(String.valueOf(tsMs), Long.toUnsignedString(run.txId())));
    String windowS = String.valueOf(Math.floorDiv(run.agentWindowMs() - correction, 1000L));
    assertThat(reader().rows("SELECT toUnixTimestamp(ts), count FROM argos.sql_stat_raw" + w))
        .as("sql_stat_raw window_start = agent window - correction, %s", as)
        .containsExactly(List.of(windowS, "3"));
    assertThat(reader().rows("SELECT toUnixTimestamp(ts), count FROM argos.ext_stat_raw" + w))
        .as("ext_stat_raw window_start = agent window - correction, %s", as)
        .containsExactly(List.of(windowS, "1"));
    assertThat(reader().rows("SELECT toUnixTimestamp(ts), name FROM argos.metric" + w))
        .as("metric ts = agent ts - correction, %s", as)
        .containsExactly(
            List.of(
                String.valueOf(Math.floorDiv(run.agentMetricMs() - correction, 1000L)),
                "jvm_threads_live"));
  }

  @Test
  @Tag("req:C-84")
  @Tag("req:C-129")
  @Tag("req:C-57")
  @DisplayName(
      "C-84 sessions +60 s and +25 h ahead, before the agent's and before the true UTC midnight:"
          + " xlog, profile_step, statistics and metric rows store agent ts - correction on the"
          + " true day, session_offset counted per item and record_clamp never; Q13 daily: 0 groups")
  void midnightOfCorrectedSessions() throws Exception {
    int appId = app("it-c84");
    long now = System.currentTimeMillis();
    long midnight = lastMidnightBefore(now - TimeUnit.HOURS.toMillis(1));
    List<SkewedRun> runs = new ArrayList<>();
    int instanceId = 201;
    for (long skew : new long[] {60_000L, TimeUnit.HOURS.toMillis(25)}) {
      runs.add(SkewedRun.beforeAgentMidnight(instanceId++, skew, midnight));
      runs.add(SkewedRun.beforeTrueMidnight(instanceId++, skew, midnight));
    }
    for (SkewedRun run : runs) {
      assertThat(run.correctionMs())
          .isEqualTo(run.skewMs()); // multiples of 10 s round to themselves
    }
    // Discrimination of the true-midnight runs: with the clamp alone (no C-84 correction) the
    // window and the sample are stored on the day after the transaction, which Q13 daily reports.
    for (long skew : new long[] {60_000L, TimeUnit.HOURS.toMillis(25)}) {
      SkewedRun atTrueMidnight = SkewedRun.beforeTrueMidnight(0, skew, midnight);
      for (long[] tsAndGrid :
          new long[][] {
            {atTrueMidnight.agentWindowMs(), TimestampGuard.GRID_STAT_MS},
            {atTrueMidnight.agentMetricMs(), TimestampGuard.GRID_METRIC_MS}
          }) {
        TimestampGuard.Result uncorrected =
            TimestampGuard.guard(tsAndGrid[0], 0, atTrueMidnight.statReceivedMs(), tsAndGrid[1]);
        assertThat(day(uncorrected.tsMs())).isEqualTo(day(midnight));
        assertThat(day(midnight)).isNotEqualTo(day(atTrueMidnight.trueStartMs()));
      }
    }
    try (IngestStack stack = stack("c84")) {
      for (SkewedRun run : runs) {
        sendSkewed(stack, appId, run);
      }
      stack.awaitLoaded();
      // C-129 (7): one session_offset per stored item of a corrected session (transaction,
      // profile, SQL statistic, outgoing-call statistic, metric sample); no record needed the
      // clamp.
      assertThat(clamped(stack, "session_offset")).isEqualTo(5.0 * runs.size());
      assertThat(clamped(stack, "record_clamp")).isZero();
      assertThat(stack.registry.get("argos.ts.rejected").counter().count()).isZero();
    }
    for (SkewedRun run : runs) {
      assertStoredCorrected(appId, run);
    }
    // Q13 daily form (13 §6.3 (14), ADR-0007 C-57) over the days of the data: no group of this app
    // beyond the bound.
    List<List<String>> beyond =
        q13Daily(midnight - TimeUnit.DAYS.toMillis(3), midnight + TimeUnit.DAYS.toMillis(1));
    assertThat(beyond.stream().filter(r -> r.get(0).equals(String.valueOf(appId))).toList())
        .as("Q13 daily_30d groups beyond the C-57 bound for app %d", appId)
        .isEmpty();
  }

  /** The {@code daily_30d} variant of test/storage/queries/Q13.sql between two instants. */
  private List<List<String>> q13Daily(long fromMs, long toMs) throws Exception {
    Path q13 = Path.of(System.getProperty("argos.repositoryRoot"), "test/storage/queries/Q13.sql");
    String text = Files.readString(q13, StandardCharsets.UTF_8);
    int start = text.indexOf("-- @variant daily_30d");
    int end = text.indexOf("-- @variant", start + 10);
    String sql =
        text.substring(text.indexOf('\n', start) + 1, end)
            .replace("{db}", ServerTestEnvironment.DATABASE)
            .replace("{p}", "")
            .replace(
                "{check_from:DateTime}",
                "toDateTime('" + CH_TIME.format(Instant.ofEpochMilli(fromMs)) + "', 'UTC')")
            .replace(
                "{check_to:DateTime}",
                "toDateTime('" + CH_TIME.format(Instant.ofEpochMilli(toMs)) + "', 'UTC')");
    StringBuilder noComments = new StringBuilder();
    for (String line : sql.lines().toList()) {
      if (!line.strip().startsWith("--")) {
        noComments.append(line).append('\n');
      }
    }
    return IngestFixtures.migrator(env).rows(noComments.toString());
  }

  // --- C-129 (3): kill and recovery of a corrected instance ---------------------------------

  /** The run without a kill whose stored values the recovered run must equal. */
  private void sendReference(int appId, SkewedRun run, String name) throws Exception {
    try (IngestStack stack = stack(name)) {
      sendSkewed(stack, appId, run);
      stack.awaitLoaded();
    }
  }

  /** Every raw table of {@code got} holds the values of {@code reference} (C-129 (3)). */
  private void assertSameAsReference(int appId, SkewedRun reference, SkewedRun got) {
    for (Table table : RAW_TABLES) {
      String columns =
          switch (table) {
            case XLOG ->
                "toUnixTimestamp64Milli(ts), tx_id, elapsed_ms, sql_count, ext_count, late";
            case PROFILE_STEP -> "toUnixTimestamp64Milli(ts), tx_id, seq, elapsed_ms, exec_ms";
            case METRIC -> "toUnixTimestamp(ts), name, value";
            default -> "toUnixTimestamp(ts), count, sum_ms";
          };
      String ref =
          reader()
              .execute(
                  "SELECT "
                      + columns
                      + " FROM argos."
                      + table.tableName()
                      + where(appId, reference.instanceId())
                      + " ORDER BY 1, 2 FORMAT TSV");
      String actual =
          reader()
              .execute(
                  "SELECT "
                      + columns
                      + " FROM argos."
                      + table.tableName()
                      + where(appId, got.instanceId())
                      + " ORDER BY 1, 2 FORMAT TSV");
      assertThat(ref).as(table + " reference").isNotBlank();
      assertThat(actual.replace(Long.toUnsignedString(got.txId()), "TX"))
          .as(table + " after kill and recovery")
          .isEqualTo(ref.replace(Long.toUnsignedString(reference.txId()), "TX"));
    }
    assertStoredCorrected(appId, got);
  }

  @Test
  @Tag("req:C-129")
  @Tag("req:D-13")
  @DisplayName(
      "C-129 (3) a corrected instance killed after the BLOCK_INTENT of every raw table and before"
          + " any insert: nothing in ClickHouse; the recovery re-inserts each intent's block,"
          + " decoded again from the WAL with the recorded ts_correction_ms, with the values of a"
          + " run without the kill")
  void correctedInstanceSurvivesKill() throws Exception {
    int appId = app("it-c129-kill");
    long midnight = lastMidnightBefore(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
    SkewedRun reference = SkewedRun.beforeTrueMidnight(301, 60_000, midnight);
    SkewedRun killed = SkewedRun.beforeTrueMidnight(302, 60_000, midnight);
    sendReference(appId, reference, "c129-reference");
    // The killed run: each table's writer appends and fsyncs its BLOCK_INTENT, then "dies" before
    // the insert (the inserter blocks without calling ClickHouse; the stack is abandoned).
    Path dir = temp.resolve("c129-killed");
    Files.createDirectories(dir);
    Set<Table> blocked = ConcurrentHashMap.newKeySet();
    CountDownLatch everyTable = new CountDownLatch(RAW_TABLES.size());
    IngestStack crashing =
        IngestStack.start(
            env,
            dir,
            IngestStack.Options.defaults()
                .withFlush(60_000, 1)
                .withInserter(
                    real ->
                        (table, token, data) -> {
                          if (blocked.add(table)) {
                            everyTable.countDown();
                          }
                          Thread.sleep(Long.MAX_VALUE); // dies here, before the insert
                        }));
    sendSkewed(crashing, appId, killed);
    assertThat(everyTable.await(60, TimeUnit.SECONDS))
        .as("tables whose writer reached the insert: %s", blocked)
        .isTrue();
    crashing.crash();
    assertThat(rowCounts(appId, killed.instanceId()).values())
        .as("rows of the killed instance before the recovery")
        .containsOnly(0L);
    try (IngestStack recovered = IngestStack.start(env, dir)) {
      assertThat(recovered.scan.uncommitted())
          .extracting(found -> found.intent().table())
          .containsExactlyInAnyOrderElementsOf(RAW_TABLES);
      recovered.awaitLoaded();
      assertThat(recovered.restoredKeys).hasSize(4); // dedup window restored from the WAL (C-3)
      // Every record is covered by an intent: nothing is loaded as a new block, so every stored
      // value comes from the re-insert of an intent.
      assertThat(recovered.ingest.recordsLoaded()).isZero();
    }
    assertSameAsReference(appId, reference, killed);
  }

  @Test
  @Tag("req:C-129")
  @Tag("req:D-13")
  @DisplayName(
      "C-129 (3) a corrected instance killed after its acks and before any BLOCK_INTENT: nothing in"
          + " ClickHouse and no intent; the recovering dispatcher loads the records as new blocks"
          + " with the values of a run without the kill")
  void correctedRecordsWithoutIntentSurviveKill() throws Exception {
    int appId = app("it-c129-no-intent");
    long midnight = lastMidnightBefore(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
    SkewedRun reference = SkewedRun.beforeTrueMidnight(311, 60_000, midnight);
    SkewedRun killed = SkewedRun.beforeTrueMidnight(312, 60_000, midnight);
    sendReference(appId, reference, "c129-no-intent-reference");
    Path dir = temp.resolve("c129-no-intent");
    Files.createDirectories(dir);
    AtomicInteger insertCalls = new AtomicInteger();
    IngestStack crashing =
        IngestStack.start(
            env,
            dir,
            IngestStack.Options.defaults()
                .withFlush(600_000, 1_000_000) // no flush before the "kill"
                .withInserter(
                    real ->
                        (table, token, data) -> {
                          insertCalls.incrementAndGet();
                          real.insert(table, token, data);
                        }));
    sendSkewed(crashing, appId, killed);
    // Acked (durable) and handed to the writer queues, which never flushed.
    io.argos.server.wal.DurableMark acked = crashing.wal.durableMark();
    assertThat(eventually(60, () -> crashing.ingest.isProcessedThrough(acked))).isTrue();
    crashing.crash();
    assertThat(insertCalls.get()).as("inserts before the kill").isZero();
    assertThat(rowCounts(appId, killed.instanceId()).values())
        .as("rows of the killed instance before the recovery")
        .containsOnly(0L);
    try (IngestStack recovered = IngestStack.start(env, dir)) {
      assertThat(recovered.scan.intents()).isEmpty();
      assertThat(recovered.scan.uncommitted()).isEmpty();
      recovered.awaitLoaded();
      assertThat(recovered.restoredKeys).hasSize(4);
      assertThat(recovered.ingest.recordsLoaded()).isEqualTo(4);
    }
    assertSameAsReference(appId, reference, killed);
  }

  @Test
  @Tag("req:C-129")
  @Tag("req:D-13")
  @Tag("req:T-SV-010")
  @DisplayName(
      "C-129 (3) a corrected instance in a child JVM killed (destroyForcibly) after the BLOCK_INTENT"
          + " of every raw table and before any insert: after the recovery the stored values equal"
          + " a run without the kill")
  void correctedInstanceSurvivesProcessKill() throws Exception {
    int appId = app("it-c129-process");
    long midnight = lastMidnightBefore(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
    long skew = 60_000;
    SkewedRun reference = SkewedRun.beforeTrueMidnight(321, skew, midnight);
    SkewedRun killed = SkewedRun.beforeTrueMidnight(322, skew, midnight);
    sendReference(appId, reference, "c129-process-reference");
    Path work = temp.resolve("c129-process");
    Path dir = work.resolve("wal");
    Files.createDirectories(dir);
    Map<String, String> props = IngestCrashChild.baseProperties(env);
    props.put("mode", "skewed");
    props.put("wal.dir", dir.toString());
    props.put("app.id", String.valueOf(appId));
    props.put("instance.id", String.valueOf(killed.instanceId()));
    props.put("skew.ms", String.valueOf(skew));
    props.put("midnight.ms", String.valueOf(midnight));
    Process child = IngestCrashChild.launch(work, props);
    String last = null;
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = out.readLine()) != null) {
        last = line;
        if (line.equals("BLOCKED") || line.startsWith("TIMEOUT")) {
          break;
        }
      }
      child.destroyForcibly(); // TerminateProcess / SIGKILL
      assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
      }
    }
    assertThat(last)
        .as(
            "child: every table blocked before its insert; stderr: %s",
            Files.readString(work.resolve("child-stderr.txt")))
        .isEqualTo("BLOCKED");
    assertThat(rowCounts(appId, killed.instanceId()).values())
        .as("rows of the killed instance before the recovery")
        .containsOnly(0L);
    try (IngestStack recovered = IngestStack.start(env, dir)) {
      assertThat(recovered.scan.uncommitted())
          .extracting(found -> found.intent().table())
          .containsExactlyInAnyOrderElementsOf(RAW_TABLES);
      recovered.awaitLoaded();
      assertThat(recovered.restoredKeys).hasSize(4);
      assertThat(recovered.ingest.recordsLoaded()).isZero();
    }
    assertSameAsReference(appId, reference, killed);
  }

  // --- T-SV-016 -----------------------------------------------------------------------------

  @Test
  @Tag("req:F-S19")
  @Tag("req:SEC-36")
  @Tag("req:C-131")
  @DisplayName(
      "T-SV-016 masked fingerprint, messages differing only in numbers share a group, dictionary"
          + " before or after the data gives the same group; error_groups upserted")
  void errorGroups() throws Exception {
    int appId = app("it-errors");
    long classId = 0x0C1A55L;
    long frameId = 0x0F2A3EL;
    try (IngestStack stack = stack("errors")) {
      long now = System.currentTimeMillis();
      // Instance 401: dictionary first.
      stack.dictionary.onDictionaryEntries(
          appId,
          401,
          List.of(
              DictionaryEntry.newBuilder()
                  .setKind(DictionaryKind.CLASS)
                  .setHash(classId)
                  .setText("com.example.OrderNotFoundException")
                  .build(),
              DictionaryEntry.newBuilder()
                  .setKind(DictionaryKind.FRAME)
                  .setHash(frameId)
                  .setText("com.example.OrderService.find(OrderService.java)")
                  .build()));
      stack.flushPostgres();
      SessionContext a = session(appId, 401, now, 0, 500, "");
      SessionContext b = session(appId, 402, now, 0, 500, "");
      stack.append(
          WalRecordType.TX,
          a,
          txBatch(
              error(
                  txId(now, 1),
                  now - 500,
                  classId,
                  frameId,
                  "Order 1234 failed for kim@example.com")),
          1);
      // Instance 402: data first, its dictionary arrives later (11 §7).
      stack.append(
          WalRecordType.TX,
          b,
          txBatch(
              error(
                  txId(now, 2),
                  now - 400,
                  classId,
                  frameId,
                  "Order 98765 failed for lee@example.org")),
          1);
      stack.awaitLoaded();
      stack.dictionary.onDictionaryEntries(
          appId,
          402,
          List.of(
              DictionaryEntry.newBuilder()
                  .setKind(DictionaryKind.CLASS)
                  .setHash(classId)
                  .setText("com.example.OrderNotFoundException")
                  .build()));
      stack.flushPostgres();
    }
    String pattern = "Order # failed for <email>";
    long expected = ErrorGroupFingerprinter.groupId(classId, pattern, frameId);
    List<List<String>> groups =
        reader()
            .rows(
                "SELECT instance_id, error_group_id FROM argos.xlog WHERE app_id = "
                    + appId
                    + " ORDER BY instance_id");
    assertThat(groups).hasSize(2);
    assertThat(groups).extracting(r -> r.get(1)).containsOnly(Long.toUnsignedString(expected));
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT group_id, error_class, message_pattern, top_frame, count_total,"
                    + " first_seen <= last_seen FROM argos.error_groups WHERE app_id = ?")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(expected);
        assertThat(rs.getString(2)).isEqualTo("com.example.OrderNotFoundException");
        assertThat(rs.getString(3)).isEqualTo(pattern).doesNotContain("@");
        assertThat(rs.getString(4)).isEqualTo("com.example.OrderService.find(OrderService.java)");
        assertThat(rs.getLong(5)).isEqualTo(2);
        assertThat(rs.getBoolean(6)).isTrue();
        assertThat(rs.next()).isFalse();
      }
    }
  }

  private static TransactionRecord.Builder error(
      long txId, long startMs, long classId, long frameId, String message) {
    return tx(txId, startMs, 5_000, 0, 0)
        .setHttpStatus(500)
        .setError(true)
        .setErrorClassId(classId)
        .setErrorTopFrameId(frameId)
        .setErrorMessage(message);
  }

  // --- T-SV-014 (dictionary part) -----------------------------------------------------------

  @Test
  @Tag("req:SEC-13")
  @Tag("req:T-SEC-13")
  @DisplayName(
      "T-SV-014 100,000 random services from one instance: 10,000 stored, the rest <overflow> in"
          + " rows and counted; PostgreSQL dictionary rows bounded")
  void dictionaryFlood() throws Exception {
    int appId = app("it-dict-flood");
    int instanceId = 501;
    List<Long> hashes = new ArrayList<>();
    java.util.Random random = new java.util.Random(42);
    try (IngestStack stack = stack("dict")) {
      for (int chunk = 0; chunk < 100; chunk++) {
        List<DictionaryEntry> entries = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
          long h = random.nextLong() | 1;
          hashes.add(h);
          entries.add(
              DictionaryEntry.newBuilder()
                  .setKind(DictionaryKind.SERVICE)
                  .setHash(h)
                  .setText("GET /random/" + Long.toHexString(h))
                  .build());
        }
        while (stack.dictionary.queuedEntries() > 20_000) {
          Thread.sleep(20);
        }
        stack.dictionary.onDictionaryEntries(appId, instanceId, entries);
      }
      stack.flushPostgres();
      assertThat(stack.registry.get("argos.dictionary.overflow").counter().count())
          .isEqualTo(90_000.0);
      long now = System.currentTimeMillis();
      SessionContext s = session(appId, instanceId, now, 0, 500, "");
      List<TransactionRecord> records = new ArrayList<>();
      int known = 0;
      int overflowed = 0;
      for (int i = 0; i < 200; i++) {
        long h = hashes.get(i * 499);
        if (stack.dictionary.isOverflowed(appId, DictionaryKind.SERVICE, h)) {
          overflowed++;
        } else {
          known++;
        }
        records.add(tx(txId(now, i), now - 100, 1_000, 0, 0).setServiceId(h).build());
      }
      assertThat(known).isPositive();
      assertThat(overflowed).isPositive();
      stack.append(WalRecordType.TX, s, IngestFixtures.txBatch(records), 1);
      stack.awaitLoaded();
      long overflowRows =
          reader()
              .scalarLong(
                  "SELECT count() FROM argos.xlog WHERE app_id = "
                      + appId
                      + " AND service_id = "
                      + Long.toUnsignedString(DictionaryResolver.OVERFLOW_ID));
      assertThat(overflowRows).isEqualTo(overflowed);
    }
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*), count(*) FILTER (WHERE text = '<overflow>') FROM argos.dictionary"
                    + " WHERE app_id = "
                    + appId
                    + " AND kind = 'service'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong(1)).isEqualTo(10_001);
      assertThat(rs.getLong(2)).isEqualTo(1);
    }
  }

  // --- T-SV-017 -----------------------------------------------------------------------------

  @Test
  @Tag("req:D-17")
  @DisplayName(
      "T-SV-017 apdex_t_ms is stamped; rows received after a change of apps.apdex_t_ms carry the new T")
  void apdexStamp() throws Exception {
    int appId = app("it-apdex");
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement()) {
      st.executeUpdate("UPDATE argos.apps SET apdex_t_ms = 300 WHERE id = " + appId);
    }
    try (IngestStack stack = stack("apdex")) {
      assertThat(stack.apdex.refresh()).isTrue();
      long now = System.currentTimeMillis();
      SessionContext before = session(appId, 601, now, 0, stack.apdex.apdexTMs(appId), "");
      stack.append(WalRecordType.TX, before, txBatch(tx(txId(now, 1), now - 100, 1_000, 0, 0)), 1);
      stack.awaitLoaded();
      try (Connection c = env.connect(PgAccount.APP);
          Statement st = c.createStatement()) {
        st.executeUpdate("UPDATE argos.apps SET apdex_t_ms = 750 WHERE id = " + appId);
      }
      assertThat(stack.apdex.refresh()).isTrue();
      SessionContext after = session(appId, 601, now + 1, 0, stack.apdex.apdexTMs(appId), "");
      stack.append(WalRecordType.TX, after, txBatch(tx(txId(now, 2), now, 1_000, 0, 0)), 2);
      stack.awaitLoaded();
    }
    assertThat(
            reader()
                .rows(
                    "SELECT tx_id, apdex_t_ms FROM argos.xlog WHERE app_id = "
                        + appId
                        + " ORDER BY tx_id"))
        .extracting(r -> r.get(1))
        .containsExactly("300", "750");
  }

  // --- T-SV-011 -----------------------------------------------------------------------------

  private Map<String, String> snapshot(int appId) {
    Map<String, String> totals = new LinkedHashMap<>();
    for (String t : List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw")) {
      totals.put(t, reader().scalar("SELECT count() FROM argos." + t + " WHERE app_id = " + appId));
    }
    for (String t :
        List.of(
            "app_stat_1m",
            "app_stat_1h",
            "service_stat_1m",
            "service_stat_1h",
            "instance_stat_1m",
            "instance_stat_1h",
            "error_stat_1m",
            "sql_stat_1m",
            "sql_stat_1h",
            "ext_stat_1m",
            "ext_stat_1h")) {
      totals.put(
          t, reader().scalar("SELECT sum(count) FROM argos." + t + " WHERE app_id = " + appId));
    }
    for (String t : List.of("metric_1m", "metric_1h")) {
      totals.put(
          t,
          reader().scalar("SELECT sum(sample_count) FROM argos." + t + " WHERE app_id = " + appId));
    }
    return totals;
  }

  @Test
  @Tag("req:D-13")
  @Tag("req:C-61")
  @DisplayName(
      "T-SV-011 one block per raw table re-inserted 100 times with its token: raw and every 1m/1h rollup unchanged")
  void reinsertSameToken() throws Exception {
    int appId = app("it-dedup");
    long now = System.currentTimeMillis();
    SessionContext s = session(appId, 701, now, 0, 500, "");
    Map<WalRecordType, com.google.protobuf.Message> batches = new LinkedHashMap<>();
    batches.put(
        WalRecordType.TX,
        txBatch(
            tx(txId(now, 1), now - 3_000, 20_000, 2, 1)
                .setError(true)
                .setErrorClassId(9)
                .setErrorMessage("boom 1"),
            tx(txId(now, 2), now - 2_000, 30_000, 1, 0)));
    batches.put(WalRecordType.PROFILE, profile(txId(now, 1), now - 3_000));
    batches.put(WalRecordType.STAT, stats(Math.floorDiv(now - 20_000, 10_000) * 10_000, 3, 1));
    batches.put(WalRecordType.METRIC, metric(Math.floorDiv(now - 5_000, 5_000) * 5_000, 3));
    try (IngestStack stack = stack("dedup")) {
      ClickHouseBlockInserter inserter = stack.inserter;
      List<Runnable> sends = new ArrayList<>();
      long seq = 0;
      for (Map.Entry<WalRecordType, com.google.protobuf.Message> e : batches.entrySet()) {
        seq++;
        byte[] payload = WalDataPayload.of(s, e.getValue().toByteString()).encode();
        WalRecord record =
            new WalRecord(
                new WalPosition(0, 16 * seq, 100),
                e.getKey(),
                s.instanceId(),
                s.agentEpoch(),
                seq,
                s.receivedAtMs(),
                0,
                payload);
        Map<Table, EncodedRows> rows =
            stack.decoder.decode(record, EnumSet.allOf(Table.class), false).rows();
        for (Map.Entry<Table, EncodedRows> r : rows.entrySet()) {
          BlockToken token =
              new BlockToken(
                  "it-t-sv-011", 1, seq * 100 + r.getKey().code(), seq * 100 + r.getKey().code());
          ByteString data = RowBinaryBuffer.header(r.getKey()).concat(r.getValue().rowBinary());
          sends.add(
              () -> {
                try {
                  inserter.insert(r.getKey(), token, data);
                } catch (Exception ex) {
                  throw new IllegalStateException(ex);
                }
              });
        }
      }
      assertThat(sends).hasSize(5); // xlog, profile_step, sql_stat_raw, ext_stat_raw, metric
      sends.forEach(Runnable::run);
      Map<String, String> first = snapshot(appId);
      assertThat(first.get("xlog")).isEqualTo("2");
      for (int i = 0; i < 100; i++) {
        sends.forEach(Runnable::run);
      }
      assertThat(snapshot(appId)).isEqualTo(first);
    }
  }

  // --- T-ST-053 write side ------------------------------------------------------------------

  @Test
  @Tag("req:T-ST-053")
  @Tag("req:C-60")
  @DisplayName(
      "T-ST-053 system.query_log: argos_writer inserts name every column, async_insert = 0, a"
          + " C-61 token and dedup in dependent views; never a rollup table")
  void queryLogOfWriter() throws Exception {
    int appId = app("it-querylog");
    try (IngestStack stack = stack("querylog")) {
      long now = System.currentTimeMillis();
      stack.append(
          WalRecordType.TX,
          session(appId, 801, now, 0, 500, ""),
          txBatch(tx(txId(now, 1), now - 100, 1_000, 0, 0)),
          1);
      stack.awaitLoaded();
    }
    String columnList = Table.XLOG.columnList();
    boolean seen =
        eventually(
            60,
            () ->
                reader()
                        .scalarLong(
                            "SELECT count() FROM system.query_log WHERE user = 'argos_writer' AND type = 'QueryFinish'"
                                + " AND query_kind = 'Insert' AND position(query, 'INSERT INTO argos.xlog ("
                                + columnList
                                + ")') > 0")
                    > 0);
    assertThat(seen).as("argos_writer xlog insert in system.query_log").isTrue();
    List<List<String>> rows =
        reader()
            .rows(
                "SELECT Settings['async_insert'], Settings['insert_deduplication_token'],"
                    + " Settings['deduplicate_blocks_in_dependent_materialized_views'],"
                    + " position(query, ' SETTINGS insert_deduplication_token = ') > 0,"
                    + " position(query, 'FORMAT RowBinaryWithNamesAndTypes') > 0"
                    + " FROM system.query_log WHERE user = 'argos_writer' AND type = 'QueryFinish'"
                    + " AND query_kind = 'Insert' AND position(query, 'INSERT INTO argos.xlog (') > 0");
    assertThat(rows).isNotEmpty();
    for (List<String> r : rows) {
      assertThat(r.get(0)).isEqualTo("0");
      assertThat(r.get(1)).matches(BlockToken.FORMAT.pattern());
      assertThat(r.get(2)).isEqualTo("1");
      assertThat(r.get(3)).isEqualTo("1");
      assertThat(r.get(4)).isEqualTo("1");
    }
    assertThat(
            reader()
                .scalarLong(
                    "SELECT count() FROM system.query_log WHERE user = 'argos_writer' AND query_kind = 'Insert'"
                        + " AND match(query, '^INSERT INTO argos\\\\.[a-z_]+_1[mh] ')"))
        .as("inserts of argos_writer into rollup tables (C-60)")
        .isZero();
  }
}
