/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ArgosClickHouse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * 13 §7.2: the system logs of the bundled ClickHouse have an upper bound (N-19 disk budget, R-07).
 * The merged server configuration is read from the running server, so a system log that a later LTS
 * enables by default fails this test until config.d/argos.xml gives it a TTL or removes it.
 */
@Tag("integration")
@Tag("req:N-19")
class SystemLogsIT {

  /** A TTL clause of a table definition (column names such as "...TTLNodes" do not match). */
  private static final Pattern TTL_CLAUSE = Pattern.compile("(?m)^TTL\\s");

  private static final Pattern TTL_WORD = Pattern.compile("(?i)\\bTTL\\b");

  private static final String METADATA = "/var/lib/clickhouse/metadata/system/";

  @Test
  @DisplayName("13§7.2 every *_log section of the merged server configuration has a TTL")
  void everyLogSectionHasTtl() {
    Element root = ServerFiles.preprocessed(Fixture.server(), "config.xml");
    Map<String, String> logs = new LinkedHashMap<>();
    for (Element section : ServerFiles.children(root)) {
      String name = section.getTagName();
      if (!name.endsWith("_log")) {
        continue;
      }
      Element ttl = ServerFiles.child(section, "ttl");
      Element engine = ServerFiles.child(section, "engine");
      if (ttl != null) {
        logs.put(name, ttl.getTextContent().strip());
      } else if (engine != null && TTL_WORD.matcher(engine.getTextContent()).find()) {
        logs.put(name, engine.getTextContent().strip());
      } else {
        logs.put(name, "<no TTL>");
      }
    }
    Evidence.record("system-logs", "enabled system logs and their TTL (merged config)", logs);
    assertThat(logs)
        .containsKeys(
            "query_log",
            "trace_log",
            "metric_log",
            "asynchronous_metric_log",
            "error_log",
            "crash_log",
            "backup_log");
    assertThat(logs.keySet())
        .doesNotContain(
            "part_log",
            "text_log",
            "query_thread_log",
            "query_views_log",
            "query_metric_log",
            "processors_profile_log",
            "background_schedule_pool_log",
            "opentelemetry_span_log",
            "s3queue_log");
    assertThat(logs).doesNotContainValue("<no TTL>");
  }

  @Test
  @DisplayName("13§7.2 every system log table the server has created carries a TTL clause")
  void createdLogTablesHaveTtl() throws InterruptedException {
    ArgosClickHouse server = Fixture.server();
    // query_log needs a query; metric_log and asynchronous_metric_log fill on their own. The first
    // flush creates the tables (flush interval 7 to 7.5 s).
    Fixture.reader().execute("SELECT 1");
    List<String> tables = List.of();
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      tables = logTables(server);
      if (tables.containsAll(List.of("query_log", "metric_log", "asynchronous_metric_log"))) {
        break;
      }
      Thread.sleep(1000);
    }
    assertThat(tables).contains("query_log", "metric_log", "asynchronous_metric_log");

    List<String> withoutTtl = new ArrayList<>();
    for (String table : tables) {
      String definition = ServerFiles.read(server, METADATA + table + ".sql");
      if (!TTL_CLAUSE.matcher(definition).find()) {
        withoutTtl.add(table);
      }
    }
    Evidence.record("system-logs", "system log tables created after start", tables);
    assertThat(withoutTtl).as("system log tables without a TTL clause").isEmpty();
  }

  private static List<String> logTables(ArgosClickHouse server) {
    return ServerFiles.shell(server, "ls -1 " + METADATA)
        .lines()
        .map(String::strip)
        .filter(f -> f.endsWith("_log.sql"))
        .map(f -> f.substring(0, f.length() - ".sql".length()))
        .sorted()
        .toList();
  }
}
