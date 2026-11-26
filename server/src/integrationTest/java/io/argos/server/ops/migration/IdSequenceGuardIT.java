/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.config.ArgosProperties;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.storage.clickhouse.Account;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The start step {@code id-sequence-guard} against real PostgreSQL and ClickHouse (T-CH-008a,
 * ADR-0010 C-259 (1)): a freshly migrated, empty PostgreSQL next to a ClickHouse that still holds
 * rows of earlier apps and instances hands out ids above the largest ClickHouse ids; a second run,
 * a sequence that is already ahead and an empty ClickHouse move nothing; a ClickHouse that cannot
 * be read leaves the sequences alone and does not fail. A real server start runs the step after the
 * WAL replay and before the app bootstrap and the gRPC listener.
 */
@Tag("integration")
@Tag("req:N-05")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdSequenceGuardIT {

  @TempDir static Path tmp;

  private static ServerTestEnvironment env;
  private static ArgosProperties properties;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    properties = bind(env.springProperties());
    new PostgresMigrationPhase(properties).run();
    new ClickHouseMigrationPhase(
            properties, MigrationSource.fromClasspath(IdSequenceGuardIT.class.getClassLoader()))
        .run();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  private static ArgosProperties bind(Map<String, ?> map) {
    return new Binder(new MapConfigurationPropertySource(map))
        .bind("argos", ArgosProperties.class)
        .get();
  }

  /** The value the next {@code nextval} of {@code sequence} returns, without consuming it. */
  private static long next(String sequence) throws Exception {
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM "
                    + sequence)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static void ch(String sql) {
    env.clickHouse(Account.MIGRATOR).execute(sql);
  }

  @Test
  @Order(1)
  @DisplayName("T-CH-008a empty ClickHouse: the sequences of a fresh database are not moved")
  void emptyClickHouseMovesNothing() throws Exception {
    IdSequenceGuardPhase.Outcome outcome = IdSequenceGuardPhase.guard(properties);

    assertThat(outcome.checked()).as(outcome.failure()).isTrue();
    assertThat(outcome.moved()).isEmpty();
    assertThat(next("argos.apps_id_seq")).isEqualTo(1);
    assertThat(next("argos.instances_id_seq")).isEqualTo(1);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-CH-008a fresh PostgreSQL next to earlier ClickHouse rows: new app and instance ids are"
          + " above the largest ClickHouse ids, a second run moves nothing")
  void freshPostgresContinuesAfterClickHouseIds() throws Exception {
    // Earlier rows: app 42 in app_stat_1h, app 7 / instance 77 in metric_1h, instance 60 in
    // instance_stat_1h. The largest are app 42 and instance 77.
    ch(
        "INSERT INTO argos.app_stat_1h (app_id, env, kind, hour)"
            + " VALUES (42, 'prod', 'HTTP', toStartOfHour(now()))");
    ch(
        "INSERT INTO argos.metric_1h (app_id, env, instance_id, name, kind, hour)"
            + " VALUES (7, 'prod', 77, 'jvm_heap_used_bytes', 'gauge', toStartOfHour(now()))");
    ch(
        "INSERT INTO argos.instance_stat_1h (app_id, env, instance_id, kind, hour)"
            + " VALUES (42, 'prod', 60, 'HTTP', toStartOfHour(now()))");

    IdSequenceGuardPhase.Outcome outcome = IdSequenceGuardPhase.guard(properties);

    assertThat(outcome.checked()).as(outcome.failure()).isTrue();
    assertThat(outcome.moved()).containsExactly("argos.apps_id_seq", "argos.instances_id_seq");
    assertThat(next("argos.apps_id_seq")).isEqualTo(43);
    assertThat(next("argos.instances_id_seq")).isEqualTo(78);

    // What the server does next: a new app and a new instance get ids ClickHouse never had.
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      try (ResultSet rs =
          s.executeQuery(
              "INSERT INTO argos.apps (name, env) VALUES ('t-ch-008a', 'prod') RETURNING id")) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(43);
      }
      try (ResultSet rs = s.executeQuery("SELECT nextval('argos.instances_id_seq')")) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(78);
      }
    }

    IdSequenceGuardPhase.Outcome again = IdSequenceGuardPhase.guard(properties);
    assertThat(again.checked()).isTrue();
    assertThat(again.moved()).isEmpty();
    assertThat(next("argos.apps_id_seq")).isEqualTo(44);
    assertThat(next("argos.instances_id_seq")).isEqualTo(79);
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-CH-008a sequences already past the ClickHouse ids (normal start) are left as they are")
  void sequencesAheadAreNotMoved() throws Exception {
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      s.execute("SELECT setval('argos.apps_id_seq', 500, true)");
      s.execute("SELECT setval('argos.instances_id_seq', 900, false)");
    }

    IdSequenceGuardPhase.Outcome outcome = IdSequenceGuardPhase.guard(properties);

    assertThat(outcome.checked()).isTrue();
    assertThat(outcome.moved()).isEmpty();
    assertThat(next("argos.apps_id_seq")).isEqualTo(501);
    assertThat(next("argos.instances_id_seq")).isEqualTo(900);
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-CH-008a ClickHouse that cannot be read: the start continues, the sequences stay as they"
          + " are")
  void clickHouseFailureDoesNotFailTheStart() throws Exception {
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      s.execute("SELECT setval('argos.apps_id_seq', 1, false)");
      s.execute("SELECT setval('argos.instances_id_seq', 1, false)");
    }
    Map<String, Object> broken = new HashMap<>(env.springProperties());
    broken.put("argos.clickhouse.url", "http://127.0.0.1:1");

    IdSequenceGuardPhase.Outcome outcome = IdSequenceGuardPhase.guard(bind(broken));

    assertThat(outcome.checked()).isFalse();
    assertThat(outcome.failure()).startsWith("clickhouse: ");
    assertThat(outcome.moved()).isEmpty();
    assertThat(next("argos.apps_id_seq")).isEqualTo(1);
    assertThat(next("argos.instances_id_seq")).isEqualTo(1);

    // With ClickHouse back, the next start moves them past the ids again.
    IdSequenceGuardPhase.Outcome recovered = IdSequenceGuardPhase.guard(properties);
    assertThat(recovered.moved()).containsExactly("argos.apps_id_seq", "argos.instances_id_seq");
    assertThat(next("argos.apps_id_seq")).isEqualTo(43);
    assertThat(next("argos.instances_id_seq")).isEqualTo(78);
  }

  @Test
  @Order(5)
  @DisplayName(
      "T-CH-008a server start: id-sequence-guard runs after wal-recovery and before bootstrap-apps"
          + " and grpc-collector, and moves the sequences of the reset database")
  void serverStartRunsTheGuard() throws Exception {
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement()) {
      s.execute("SELECT setval('argos.apps_id_seq', 1, false)");
      s.execute("SELECT setval('argos.instances_id_seq', 1, false)");
    }
    try (ArgosTestServer server =
        ArgosTestServer.start(env, Map.of("argos.wal.dir", tmp.resolve("wal").toString()))) {
      List<StartupSequence.StepResult> results = server.bean(StartupSequence.class).results();
      List<String> names = results.stream().map(StartupSequence.StepResult::name).toList();
      int guard = names.indexOf("id-sequence-guard");
      assertThat(guard).as("steps %s", names).isGreaterThan(names.indexOf("wal-recovery"));
      assertThat(names.indexOf("wal-recovery")).isGreaterThanOrEqualTo(0);
      assertThat(results.get(guard).phase()).isEqualTo(PhaseOrder.LISTENERS);
      assertThat(guard).isLessThan(names.indexOf("bootstrap-apps"));
      assertThat(guard).isLessThan(names.indexOf("grpc-collector"));
    }
    assertThat(next("argos.apps_id_seq")).isEqualTo(43);
    assertThat(next("argos.instances_id_seq")).isEqualTo(78);
  }
}
