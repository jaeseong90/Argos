/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaParameters;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * The accounts of users.d/argos.xml (SEC-74, SEC-76, DSN-13-12, C-11), the reader's query limits
 * with their trace in system.query_log (T-ST-053), the writer's settings constraints, the
 * migrator's grants, and the network surface of config.d/argos.xml (SEC-75: listeners, no outbound
 * URL, test port published on loopback only).
 */
@Tag("integration")
class AccountsIT {

  private static SchemaParameters p;
  private static ClickHouseHttp writer;
  private static ClickHouseHttp reader;
  private static ClickHouseHttp migrator;

  @BeforeAll
  static void schema() {
    p = Fixture.schema("acc_");
    writer = Fixture.writer();
    reader = Fixture.reader();
    migrator = Fixture.migrator();
    Workload w = Workload.generate(5, Instant.parse("2026-10-24T08:00:00Z"), 10, 100);
    writer.insert(
        Workload.insert(p, "xlog", Workload.XLOG_COLUMNS),
        String.join("\n", w.xlog) + "\n",
        Map.of("insert_deduplication_token", "argos:xlog:acc"));
  }

  @Test
  @Tag("req:SEC-74")
  @DisplayName("T-SEC-74 the default user is removed: no login with an empty or any password")
  void defaultUserRemoved() {
    for (String password : List.of("", "default", UUID.randomUUID().toString())) {
      ClickHouseException e =
          Fixture.server().client("default", password).failure("SELECT 1").orElseThrow();
      assertThat(e.errorName()).isIn("REQUIRED_PASSWORD", "AUTHENTICATION_FAILED");
    }
    // A failed login cannot tell "removed" from "default with an unknown password": the merged
    // users.xml must define exactly the three Argos accounts. (Names only: the file holds hashes.)
    Element users =
        ServerFiles.child(ServerFiles.preprocessed(Fixture.server(), "users.xml"), "users");
    assertThat(users).isNotNull();
    assertThat(ServerFiles.children(users).stream().map(Element::getTagName).toList())
        .containsExactlyInAnyOrder("argos_writer", "argos_reader", "argos_migrator");
  }

  @Test
  @Tag("req:SEC-74")
  @DisplayName("T-SEC-74 each account accepts only its generated password")
  void wrongPasswordRejected() {
    for (String user : List.of("argos_writer", "argos_reader", "argos_migrator")) {
      ClickHouseException e =
          Fixture.server().client(user, "changeme").failure("SELECT 1").orElseThrow();
      assertThat(e.errorName()).as(user).isIn("REQUIRED_PASSWORD", "AUTHENTICATION_FAILED");
    }
    assertThat(reader.scalar("SELECT currentUser()")).isEqualTo("argos_reader");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-SEC-76 writer: INSERT only (no SELECT, no DDL, no partition drop, no system tables)")
  void writerIsInsertOnly() {
    assertThat(denied(writer, "SELECT count() FROM " + p.table("xlog"))).isEqualTo("ACCESS_DENIED");
    assertThat(denied(writer, "CREATE TABLE " + p.table("w") + " (x UInt8) ENGINE = Memory"))
        .isEqualTo("ACCESS_DENIED");
    assertThat(denied(writer, "ALTER TABLE " + p.table("xlog") + " DROP PARTITION '2026-10-24'"))
        .isEqualTo("ACCESS_DENIED");
    assertThat(denied(writer, "TRUNCATE TABLE " + p.table("xlog"))).isEqualTo("ACCESS_DENIED");
    assertThat(denied(writer, "SELECT count() FROM system.parts")).isEqualTo("ACCESS_DENIED");
    assertThat(writer.scalar("SELECT getSetting('async_insert')")).isEqualTo("false");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-SEC-76 writer constraints: dedup, sync insert, query_log and memory limit cannot be changed")
  void writerSettingsAreConstrained() {
    String table = p.table("writer_contract");
    migrator.execute(
        "CREATE TABLE IF NOT EXISTS "
            + table
            + " (x UInt32) ENGINE = MergeTree ORDER BY x"
            + " SETTINGS non_replicated_deduplication_window = 100");
    String insert = "INSERT INTO " + table + " FORMAT TabSeparated";
    long limit = Fixture.server().memoryBudget().writerQueryBytes();
    List<Map<String, String>> refused =
        List.of(
            Map.of("max_memory_usage", "0"),
            Map.of("max_memory_usage", Long.toString(limit + 1)),
            Map.of("async_insert", "1"),
            Map.of("insert_deduplicate", "0"),
            Map.of("deduplicate_blocks_in_dependent_materialized_views", "0"),
            Map.of("log_queries", "0"),
            Map.of("max_insert_threads", "4"),
            Map.of("readonly", "1"));
    for (Map<String, String> settings : refused) {
      assertThat(insertError(writer, insert, "1\n", settings))
          .as(settings.toString())
          .isEqualTo("SETTING_CONSTRAINT_VIOLATION");
    }
    // allow_ddl = 0 is checked before the constraints: ClickHouse refuses to change it at all.
    assertThat(insertError(writer, insert, "1\n", Map.of("allow_ddl", "1")))
        .isEqualTo("QUERY_IS_PROHIBITED");
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isZero();

    // The batch writer's own settings (ClickHouseInserter): the profile values passed again.
    Map<String, String> writerSettings = new LinkedHashMap<>();
    writerSettings.put("insert_deduplication_token", "argos:writer_contract:1");
    writerSettings.put("deduplicate_blocks_in_dependent_materialized_views", "1");
    writerSettings.put("async_insert", "0");
    writerSettings.put("insert_deduplicate", "1");
    writerSettings.put("max_insert_threads", "1");
    writerSettings.put("max_memory_usage", Long.toString(limit / 2));
    writerSettings.put("wait_end_of_query", "1");
    writer.insert(insert, "1\n2\n", writerSettings);
    writer.insert(insert, "1\n2\n", writerSettings);
    // The SQL form of deploy/clickhouse/README.md (SETTINGS before FORMAT), same token: no-op.
    writer.insert(
        "INSERT INTO "
            + table
            + " (x) SETTINGS insert_deduplication_token = 'argos:writer_contract:1',"
            + " deduplicate_blocks_in_dependent_materialized_views = 1 FORMAT TabSeparated",
        "1\n2\n",
        Map.of());
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isEqualTo(2);
    writer.insert(
        "INSERT INTO "
            + table
            + " (x) SETTINGS insert_deduplication_token = 'argos:writer_contract:2',"
            + " deduplicate_blocks_in_dependent_materialized_views = 1 FORMAT TabSeparated",
        "3\n",
        Map.of());
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isEqualTo(3);
    migrator.execute("DROP TABLE " + table + " SYNC");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName("T-SEC-76 reader: DDL and writes fail (readonly = 1, SELECT grants only)")
  void readerCannotWriteOrRunDdl() {
    List<String> statements =
        List.of(
            "CREATE TABLE " + p.table("r") + " (x UInt8) ENGINE = Memory",
            "DROP TABLE " + p.table("xlog"),
            "ALTER TABLE " + p.table("xlog") + " ADD COLUMN z UInt8",
            "ALTER TABLE " + p.table("xlog") + " DELETE WHERE 1",
            "ALTER TABLE " + p.table("xlog") + " DROP PARTITION '2026-10-24'",
            "TRUNCATE TABLE " + p.table("xlog"),
            "INSERT INTO " + p.table("xlog") + " (app_id) VALUES (1)",
            "CREATE DATABASE argos_other",
            "CREATE TEMPORARY TABLE t (x UInt8)");
    for (String sql : statements) {
      assertThat(denied(reader, sql)).as(sql).isIn("ACCESS_DENIED", "READONLY");
    }
    assertThat(reader.scalarLong("SELECT count() FROM " + p.table("xlog"))).isEqualTo(1000);
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-ST-053 reader limits: lowering allowed, raising or other settings refused, limits enforced")
  void readerQueryLimits() {
    String table = p.table("xlog");
    assertThat(
            reader
                .execute(
                    "SELECT getSetting('max_execution_time'), getSetting('max_result_rows')",
                    Map.of("max_execution_time", "5", "max_result_rows", "120000"))
                .body()
                .strip())
        .isEqualTo("5\t120000");
    assertThat(errorName(reader, "SELECT 1", Map.of("max_execution_time", "20")))
        .isEqualTo("SETTING_CONSTRAINT_VIOLATION");
    assertThat(errorName(reader, "SELECT 1", Map.of("max_memory_usage", "0")))
        .isEqualTo("SETTING_CONSTRAINT_VIOLATION");
    assertThat(errorName(reader, "SELECT 1", Map.of("log_queries", "0"))).isEqualTo("READONLY");
    assertThat(errorName(reader, "SELECT 1", Map.of("readonly", "0"))).isEqualTo("READONLY");
    assertThat(errorName(reader, "SELECT 1", Map.of("result_overflow_mode", "throw")))
        .isEqualTo("READONLY");

    // sum() reads the column (a bare count() is answered from part metadata without reading rows).
    assertThat(
            errorName(
                reader, "SELECT sum(elapsed_ms) FROM " + table, Map.of("max_rows_to_read", "10")))
        .isEqualTo("TOO_MANY_ROWS");
    assertThat(
            errorName(
                reader,
                "SELECT sleepEachRow(0.3) FROM (SELECT tx_id FROM " + table + " LIMIT 8)",
                Map.of("max_execution_time", "1")))
        .isEqualTo("TIMEOUT_EXCEEDED");
    long returned =
        reader
            .rows(
                "SELECT tx_id FROM "
                    + table
                    + " ARRAY JOIN range(300) AS r"
                    + " SETTINGS max_result_rows = 1000")
            .size();
    // result_overflow_mode = break: the result stops after the block that crosses the limit.
    assertThat(returned).isBetween(1000L, 300_000L - 1);
    Evidence.record(
        "accounts", "rows returned for 300,000 with max_result_rows=1000 (break)", returned);
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName("T-ST-053 reader queries and refused DDL are traced in system.query_log")
  void queryLogTracesReader() throws InterruptedException {
    String okId = "t-st-053-ok-" + UUID.randomUUID();
    String deniedId = "t-st-053-denied-" + UUID.randomUUID();
    reader.execute(
        "SELECT count() FROM " + p.table("xlog"),
        Map.of("query_id", okId, "max_execution_time", "5"));
    reader.failure(
        "CREATE TABLE " + p.table("q") + " (x UInt8) ENGINE = Memory",
        Map.of("query_id", deniedId));

    String query =
        "SELECT user, type, exception_code, Settings['max_execution_time'] FROM system.query_log"
            + " WHERE query_id IN ('"
            + okId
            + "', '"
            + deniedId
            + "') AND type != 'QueryStart'"
            + " ORDER BY query_id";
    List<List<String>> rows = List.of();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
    while (rows.size() < 2 && Instant.now().isBefore(deadline)) {
      Thread.sleep(1000);
      try {
        rows = reader.rows(query);
      } catch (ClickHouseException e) {
        // system.query_log is created by the first flush (flush interval 7.5 s).
        assertThat(e.errorName()).isEqualTo("UNKNOWN_TABLE");
      }
    }
    Evidence.record(
        "accounts", "query_log rows (user, type, exception_code, max_execution_time)", rows);
    assertThat(rows).hasSize(2);
    // ORDER BY query_id: "t-st-053-denied-..." < "t-st-053-ok-..."
    assertThat(rows.get(0).subList(0, 3))
        .containsExactly("argos_reader", "ExceptionBeforeStart", "497");
    assertThat(rows.get(1)).containsExactly("argos_reader", "QueryFinish", "0", "5");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName("T-SEC-76 migrator: DDL inside argos only, no other database, no system tables")
  void migratorScope() {
    migrator.execute("CREATE TABLE IF NOT EXISTS " + p.table("m") + " (x UInt8) ENGINE = Memory");
    migrator.execute("DROP TABLE " + p.table("m"));
    assertThat(denied(migrator, "CREATE DATABASE argos_other")).isEqualTo("ACCESS_DENIED");
    assertThat(denied(migrator, "SELECT count() FROM system.parts")).isEqualTo("ACCESS_DENIED");
    assertThat(denied(migrator, "SELECT count() FROM system.merges")).isEqualTo("ACCESS_DENIED");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-SEC-76 migrator: no dictionary, function or named collection DDL, no url() (no outbound"
          + " connection)")
  void migratorCannotCreateOutboundObjects() {
    List<String> statements =
        List.of(
            "CREATE DICTIONARY "
                + p.table("d_http")
                + " (k UInt64, v String) PRIMARY KEY k"
                + " SOURCE(HTTP(URL 'http://192.0.2.1/d.tsv' FORMAT 'TabSeparated'))"
                + " LAYOUT(FLAT()) LIFETIME(0)",
            "CREATE DICTIONARY "
                + p.table("d_ch")
                + " (k UInt64, v String) PRIMARY KEY k"
                + " SOURCE(CLICKHOUSE(HOST '192.0.2.1' PORT 9000 USER 'u' PASSWORD '' DB 'argos'"
                + " TABLE 't')) LAYOUT(FLAT()) LIFETIME(0)",
            "CREATE FUNCTION argos_probe_fn AS x -> x + 1",
            "CREATE NAMED COLLECTION argos_probe_nc AS url = 'http://192.0.2.1/'",
            "SELECT * FROM url('http://192.0.2.1/d.tsv', LineAsString)");
    for (String sql : statements) {
      assertThat(denied(migrator, sql)).as(sql).isIn("ACCESS_DENIED", "UNACCEPTABLE_URL");
    }
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-SEC-76 migrator keeps its work: table and view DDL, REPLACE PARTITION, DELETE mutation")
  void migratorKeepsItsOperations() {
    String source = p.table("mig_src");
    String target = p.table("mig_sum");
    String staging = p.table("mig_stage");
    String view = p.table("mig_mv");
    for (String t : List.of(view, staging, target, source)) {
      migrator.execute("DROP TABLE IF EXISTS " + t + " SYNC");
    }
    migrator.execute(
        "CREATE TABLE "
            + source
            + " (d Date, x UInt32) ENGINE = MergeTree PARTITION BY d ORDER BY x");
    migrator.execute(
        "CREATE TABLE "
            + target
            + " (d Date, c SimpleAggregateFunction(sum, UInt64))"
            + " ENGINE = AggregatingMergeTree PARTITION BY d ORDER BY d");
    migrator.execute(
        "CREATE MATERIALIZED VIEW "
            + view
            + " TO "
            + target
            + " SQL SECURITY DEFINER AS SELECT d, count() AS c FROM "
            + source
            + " GROUP BY d");
    writer.insert(
        "INSERT INTO " + source + " FORMAT TabSeparated",
        "2026-10-24\t1\n2026-10-24\t2\n2026-10-24\t3\n",
        Map.of("insert_deduplication_token", "argos:mig_src:1"));
    assertThat(migrator.scalarLong("SELECT sum(c) FROM " + target)).isEqualTo(3);

    migrator.execute("CREATE TABLE " + staging + " AS " + target);
    migrator.execute(
        "INSERT INTO " + staging + " SELECT d, count() FROM " + source + " GROUP BY d");
    migrator.execute("ALTER TABLE " + target + " REPLACE PARTITION '2026-10-24' FROM " + staging);
    assertThat(migrator.scalarLong("SELECT sum(c) FROM " + target)).isEqualTo(3);

    migrator.execute(
        "ALTER TABLE " + source + " DELETE IN PARTITION '2026-10-24' WHERE x = 2",
        Map.of("mutations_sync", "2"));
    assertThat(migrator.scalarLong("SELECT count() FROM " + source)).isEqualTo(2);
    for (String t : List.of(view, staging, target, source)) {
      migrator.execute("DROP TABLE " + t + " SYNC");
    }
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-SEC-76 remote_url_allow_hosts is empty: url() and an HTTP dictionary source are refused"
          + " even for a user granted them")
  void serverRefusesRemoteUrls() {
    // A test-only fourth user that holds the URL source and dictionary grants none of the Argos
    // accounts has, on a separate server: the server configuration itself must refuse the URL.
    byte[] secret = new byte[24];
    new SecureRandom().nextBytes(secret);
    String password = HexFormat.of().formatHex(secret);
    String probeUser =
        """
        <clickhouse>
          <users>
            <argos_url_probe>
              <password_sha256_hex>%s</password_sha256_hex>
              <networks><ip>::/0</ip></networks>
              <grants>
                <query>GRANT READ ON URL</query>
                <query>GRANT CREATE TEMPORARY TABLE ON *.*</query>
                <query>GRANT CREATE DICTIONARY, dictGet ON argos.*</query>
              </grants>
            </argos_url_probe>
          </users>
        </clickhouse>
        """
            .formatted(ArgosClickHouse.sha256Hex(password));
    try (ArgosClickHouse server =
        ArgosClickHouse.builder().extraConfig("users.d/url_probe.xml", probeUser).start()) {
      server.client(Account.MIGRATOR).execute("CREATE DATABASE IF NOT EXISTS argos");
      ClickHouseHttp probe = server.client("argos_url_probe", password);
      // 192.0.2.1 is TEST-NET-1 (RFC 5737): never a real host, even if the filter failed.
      for (String url : List.of("http://127.0.0.1:8123/ping", "http://192.0.2.1/d.tsv")) {
        assertThat(denied(probe, "SELECT * FROM url('" + url + "', LineAsString)"))
            .as(url)
            .isEqualTo("UNACCEPTABLE_URL");
      }
      probe.execute(
          "CREATE DICTIONARY argos.url_probe_dict (k UInt64, v String) PRIMARY KEY k"
              + " SOURCE(HTTP(URL 'http://192.0.2.1/d.tsv' FORMAT 'TabSeparated'))"
              + " LAYOUT(FLAT()) LIFETIME(0)");
      assertThat(denied(probe, "SELECT dictGet('argos.url_probe_dict', 'v', toUInt64(1))"))
          .isEqualTo("UNACCEPTABLE_URL");
    }
  }

  @Test
  @Tag("req:SEC-75")
  @DisplayName("SEC-75 13§7.2 ClickHouse listens on HTTP 8123 and native 9000 only")
  void listenersAreHttpAndNativeOnly() {
    Set<Integer> ports = ServerFiles.listeningPorts(Fixture.server());
    Evidence.record("accounts", "listening TCP ports in the container", ports);
    assertThat(ports).containsExactlyInAnyOrder(8123, 9000);
  }

  @Test
  @Tag("req:SEC-75")
  @DisplayName("SEC-75 the test launcher publishes the HTTP port on 127.0.0.1 only")
  void testPortPublishedOnLoopbackOnly() {
    assumeTrue(ArgosClickHouse.publishesOnLoopback(), "remote Docker host: published on all");
    ArgosClickHouse.HttpPublication publication = Fixture.server().httpPublication();
    Evidence.record("accounts", "HTTP port publication (HostConfig, NetworkSettings)", publication);
    assertThat(publication.configured()).containsExactly(ArgosClickHouse.LOOPBACK);
    assertThat(publication.effective()).isNotEmpty().containsOnly(ArgosClickHouse.LOOPBACK);
  }

  private static String denied(ClickHouseHttp client, String sql) {
    return client.failure(sql).map(ClickHouseException::errorName).orElse("<succeeded>");
  }

  private static String insertError(
      ClickHouseHttp client, String insert, String data, Map<String, String> settings) {
    try {
      client.insert(insert, data, settings);
      return "<succeeded>";
    } catch (ClickHouseException e) {
      return e.errorName();
    }
  }

  private static String errorName(ClickHouseHttp client, String sql, Map<String, String> settings) {
    return client.failure(sql, settings).map(ClickHouseException::errorName).orElse("<succeeded>");
  }
}
