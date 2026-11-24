/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The storage health job on real ClickHouse and PostgreSQL (T-SV-046a; 12 §9.2, 13 §7.5, F-S22,
 * F-S23; WO-28): it runs on its own schedule ({@code argos.ops.storage-health-interval} shortened
 * to 2 s), its gauges equal the system tables it reads ({@code system.parts}, {@code system.disks},
 * {@code system.events}), a partition that disappears without an emergency drop is recorded as the
 * last TTL run (also in {@code settings}), and the health resources fill the fields that were null
 * before WO-28.
 */
@Tag("integration")
@Tag("req:F-S22")
@Tag("req:F-S23")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageHealthIT {

  private static final int APP = 971;
  private static final DateTimeFormatter ID = DateTimeFormatter.BASIC_ISO_DATE;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp reader;
  private static ApiTestClient admin;
  private static LocalDate today;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.ops.storage-health-interval", "2s");
    server = ArgosTestServer.start(env, p);
    reader = env.clickHouse(Account.READER);
    today = LocalDate.now(ZoneOffset.UTC);
    // One insert per table: one part per day partition, so no merge changes the counts.
    List<XlogRow> tx = new ArrayList<>();
    List<StepRow> steps = new ArrayList<>();
    for (int daysAgo : new int[] {0, 1, 3}) {
      for (int i = 0; i < 3; i++) {
        XlogRow r =
            XlogRow.at(APP, 9711, today.minusDays(daysAgo).atTime(8, i).toInstant(ZoneOffset.UTC));
        tx.add(r);
        steps.add(StepRow.of(r, 1, "SQL"));
      }
    }
    HandMadeRows.of(env).xlog(tx);
    HandMadeRows.of(env).steps(steps);
    TestUsers.TestUser a = new TestUsers(env).create(Role.ADMIN);
    admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
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

  private static MeterRegistry registry() {
    return server.bean(MeterRegistry.class);
  }

  private static double gauge(String name, String tag, String value) {
    Gauge g = registry().find(name).tag(tag, value).gauge();
    assertThat(g).as(name + "{" + tag + "=" + value + "}").isNotNull();
    return g.value();
  }

  private static long scalar(String sql) {
    String v = reader.scalar(sql).strip();
    return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-046a the scheduled job fills the gauges; parts, free space and delayed inserts equal"
          + " system.parts, system.disks and system.events; disk usage per mount (wal, clickhouse)")
  void gaugesEqualSystemTables() {
    // The schedule (2 s) runs on its own: the WAL mount is there from the start, the ClickHouse
    // disk appears with the first run.
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> registry().find("argos.server.disk.usage.ratio").gauges().size() >= 2);
    StorageHealthJob job = server.bean(StorageHealthJob.class);
    assertThat(job.lastRunAt()).isNotNull();
    StorageHealthJob.Snapshot s = job.runOnce();

    for (String table : List.of("xlog", "profile_step")) {
      long expected =
          scalar(
              "SELECT max(c) FROM (SELECT count() AS c FROM system.parts WHERE active AND"
                  + " database = 'argos' AND table = '"
                  + table
                  + "' GROUP BY partition_id)");
      assertThat(expected).isPositive();
      assertThat(gauge("argos.ch.parts.active", "table", table)).isEqualTo((double) expected);
      assertThat(s.partsActive()).containsEntry(table, expected);
    }
    long free = scalar("SELECT min(free_space) FROM system.disks");
    double freeGauge = registry().find("argos.ch.disk.free.bytes").gauge().value();
    // Free space moves with every write of the container; the same order of magnitude.
    assertThat(Math.abs(freeGauge - free) / free).isLessThan(0.05);
    long total = scalar("SELECT any(total_space) FROM system.disks WHERE name = 'default'");
    double expectedRatio = (double) (total - free) / total;
    assertThat(gauge("argos.server.disk.usage.ratio", "mount", "clickhouse:default"))
        .isCloseTo(expectedRatio, org.assertj.core.data.Offset.offset(0.02));
    double wal = gauge("argos.server.disk.usage.ratio", "mount", "wal");
    assertThat(wal).isBetween(0.0, 1.0);
    long delayed = scalar("SELECT sum(value) FROM system.events WHERE event = 'DelayedInserts'");
    FunctionCounter counter = registry().find("argos.ch.delayed.inserts").functionCounter();
    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo((double) delayed);
    // query_log is flushed every few seconds; queries ran (the job's own among them).
    Awaitility.await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () -> {
              job.runOnce();
              return Double.isFinite(registry().find("argos.ch.query.p99.seconds").gauge().value());
            });
    assertThat(registry().find("argos.ch.query.p99.seconds").gauge().value()).isNotNegative();
    assertThat(s.clickHouseUsedRatio()).isBetween(0.0, 1.0);
    assertThat(s.emergency().state().active()).isFalse();
    assertThat(registry().find("argos.writer.dead.letter.blocks").gauge().value()).isZero();
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-046a a partition that disappears without an emergency drop is the last TTL run, kept"
          + " in settings")
  void ttlRunRecorded() throws Exception {
    StorageHealthJob job = server.bean(StorageHealthJob.class);
    job.runOnce();
    String oldest = ID.format(today.minusDays(3));
    assertThat(
            reader
                .scalar(
                    "SELECT min(partition_id) FROM system.parts WHERE active AND database = 'argos'"
                        + " AND table = 'xlog'")
                .strip())
        .isEqualTo(oldest);
    // What a TTL merge (ttl_only_drop_parts) does to an expired day.
    env.clickHouse(Account.MIGRATOR)
        .execute("ALTER TABLE argos.xlog DROP PARTITION ID '" + oldest + "'");
    StorageHealthJob.Snapshot s = job.runOnce();
    assertThat(s.ttlLastRunAt()).isNotNull();
    assertThat(job.ttlLastRunAt()).isEqualTo(s.ttlLastRunAt());
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT value #>> '{}' FROM argos.settings WHERE key = 'storage_ttl_last_run_at'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo(s.ttlLastRunAt().toString());
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-046a GET /admin/storage/health fills ttl_last_run_at, emergency_retention_active and"
          + " dead_letter; GET /server/health fills disk_usage_ratio and cert_expiry_days")
  void healthFieldsFilled() {
    ApiResponse storage = admin.get("/api/v1/admin/storage/health");
    assertThat(storage.status()).as(storage.body()).isEqualTo(200);
    JsonNode d = storage.json().path("data");
    assertThat(d.path("ttl_last_run_at").isNull()).as(d.toString()).isFalse();
    assertThat(d.path("ttl_last_run_at").asString()).isNotBlank();
    assertThat(d.path("emergency_retention_active").isBoolean()).as(d.toString()).isTrue();
    assertThat(d.path("emergency_retention_active").asBoolean()).isFalse();
    assertThat(d.path("dead_letter").path("blocks").asLong(-1)).isZero();
    assertThat(d.path("dead_letter").path("bytes").asLong(-1)).isZero();
    ApiResponse health = admin.get("/api/v1/server/health");
    assertThat(health.status()).as(health.body()).isEqualTo(200);
    JsonNode h = health.json().path("data");
    assertThat(h.path("disk_usage_ratio").isNumber()).as(h.toString()).isTrue();
    assertThat(h.path("disk_usage_ratio").asDouble()).isBetween(0.0, 1.0);
    double largest =
        registry().find("argos.server.disk.usage.ratio").gauges().stream()
            .mapToDouble(Gauge::value)
            .filter(Double::isFinite)
            .max()
            .orElseThrow();
    assertThat(h.path("disk_usage_ratio").asDouble())
        .isCloseTo(largest, org.assertj.core.data.Offset.offset(0.01));
    assertThat(h.path("cert_expiry_days").isIntegralNumber()).as(h.toString()).isTrue();
    assertThat(h.path("cert_expiry_days").asLong()).isPositive();
    assertThat(Duration.ofDays(h.path("cert_expiry_days").asLong())).isPositive();
  }
}
