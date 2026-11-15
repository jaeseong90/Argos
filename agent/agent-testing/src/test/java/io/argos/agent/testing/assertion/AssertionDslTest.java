/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static io.argos.agent.testing.assertion.ArgosAssertions.startsWith;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.JsonlCollected;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.agent.testing.export.FileExporterReader;
import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The 18 §5.5 DSL: counts, fields, steps, missing vs zero, unsupported, stats and metrics. */
class AssertionDslTest {
  static final String LINE =
      "{\"tx_id\":\"1\",\"kind\":\"HTTP\",\"service\":\"GET /api/orders/{id}\",\"elapsed_ms\":2.5,"
          + "\"status\":200,\"error\":false,\"path\":\"/api/orders/1\",\"thread\":\"http-nio-1\","
          + "\"steps\":[{\"seq\":0,\"parent\":-1,\"type\":\"METHOD\",\"name\":\"handle\","
          + "\"elapsed_ms\":2.0},{\"seq\":1,\"parent\":0,\"type\":\"SQL\",\"name\":\"select ?\","
          + "\"elapsed_ms\":0.5,\"rows\":0,\"fetch_ms\":0.0}]}";

  @TempDir Path dir;

  Collected jsonl(String... lines) throws Exception {
    Path file = dir.resolve(FileExporterReader.FILE_NAME);
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return new JsonlCollected(FileExporterReader.inDirectory(dir));
  }

  @Test
  @DisplayName("transactions: count, first, service, status, steps by type, step tree, threads")
  void transactions() throws Exception {
    Collected collected = jsonl(LINE);
    assertThat(collected)
        .transactions()
        .hasSize(1)
        .first()
        .hasKind("HTTP")
        .hasService("GET /api/orders/{id}")
        .hasStatus(200)
        .isNotError()
        .elapsedMs()
        .isGreaterThan(0)
        .steps()
        .ofType("SQL")
        .single()
        .hasParent(0)
        .rows()
        .isZero()
        .fetchMs()
        .isZero()
        .steps()
        .ofType("METHOD")
        .first()
        .isTopLevel()
        .transaction()
        .hasStepTree("METHOD handle", "  SQL select ?")
        .isNotFromThread(startsWith("argos-"));
    assertThat(collected).transactions().noneFromThread(startsWith("argos-"));
    assertThat(collected).transactions().withPath("/api/orders/1").single();
    assertThatThrownBy(() -> assertThat(collected).transactions().hasSize(2))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("expected 2 transaction(s) but found 1");
    assertThatThrownBy(
            () -> assertThat(collected).transactions().first().steps().ofType("SQL").hasSize(2))
        .hasMessageContaining("expected 2 step(s)");
    String argosThread = LINE.replace("http-nio-1", "argos-sender");
    assertThatThrownBy(
            () ->
                assertThat(jsonl(argosThread)).transactions().noneFromThread(startsWith("argos-")))
        .hasMessageContaining("startsWith(\"argos-\")");
    // No thread name: the re-entrance check has nothing to compare and fails (18 §7.6).
    String noThread = LINE.replace("\"thread\":\"http-nio-1\",", "");
    assertThatThrownBy(
            () -> assertThat(jsonl(noThread)).transactions().noneFromThread(startsWith("argos-")))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("thread unknown");
  }

  @Test
  @DisplayName(
      "JSONL from WO-10: env, version, cpu_time_ms, alloc_bytes, flags, sampler decision and step"
          + " threads are supported (missing vs present); headers, request id, stats, metrics,"
          + " dictionary and counters still fail as unsupported")
  void jsonlFieldsOfWo10() throws Exception {
    Collected collected = jsonl(LINE);
    TransactionAssert tx = assertThat(collected).transactions().first();
    // Supported now: absent means "not recorded" (missing), never "unsupported".
    tx.cpuTimeMs().isMissing().allocBytes().isMissing().field("version").isMissing();
    assertThatThrownBy(() -> tx.hasEnv("test"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("env")
        .hasMessageNotContaining("unsupported");
    tx.noStepFromThread(startsWith("argos-"));
    String measured =
        LINE.replace(
                "\"thread\":\"http-nio-1\",",
                "\"thread\":\"http-nio-1\",\"env\":\"test\",\"version\":\"1.2.3\","
                    + "\"cpu_time_ms\":0.0,\"alloc_bytes\":4096,\"flags\":[\"leaked\"],"
                    + "\"xlog_flags\":2,\"profile_captured\":true,\"truncated\":false,"
                    + "\"total_steps\":2,")
            .replace(
                "\"type\":\"SQL\",",
                "\"type\":\"SQL\",\"thread\":\"worker-1\",\"repeat_count\":3,");
    TransactionAssert full = assertThat(jsonl(measured)).transactions().first();
    full.hasEnv("test")
        .hasVersion("1.2.3")
        .hasFlag("leaked")
        .doesNotHaveFlag("summarized")
        .isProfileCaptured()
        .cpuTimeMs()
        .isZero()
        .allocBytes()
        .isEqualTo(4096L)
        .steps()
        .ofType("SQL")
        .single()
        .repeatCount()
        .isEqualTo(3L)
        .noStepFromThread(startsWith("argos-"));
    assertThatThrownBy(() -> full.noStepFromThread(startsWith("worker-")))
        .hasMessageContaining("worker-1");
    full.totalSteps().isEqualTo(2L).truncated().isEqualTo(false);
    tx.totalSteps().isMissing().threadName().isMissing();
    assertThatThrownBy(() -> full.isNotProfileCaptured())
        .hasMessageContaining("profile_captured=false");
    // Still not produced by the JSONL exporter: unsupported, never passing silently.
    assertThatThrownBy(() -> tx.field("dbc_wait_ms").isMissing())
        .hasMessageStartingWith("unsupported: transaction field 'dbc_wait_ms'")
        .hasMessageContaining("JSONL exporter");
    assertThatThrownBy(() -> tx.field("sql_ids").isMissing()).hasMessageStartingWith("unsupported");
    // WO-12: the JSONL exporter carries headers, request id and dispatch count (absent here).
    tx.field("headers").isMissing();
    tx.field("request_id").isMissing();
    tx.field("dispatch_count").isMissing();
    assertThatThrownBy(() -> tx.steps().first().field("batch_size"))
        .hasMessageStartingWith("unsupported: step field 'batch_size'");
    assertThatThrownBy(() -> assertThat(collected).stats())
        .hasMessageStartingWith("unsupported: SQL statistics");
    assertThatThrownBy(() -> assertThat(collected).metrics())
        .hasMessageStartingWith("unsupported: metrics");
    assertThatThrownBy(() -> assertThat(collected).dictionary())
        .hasMessageStartingWith("unsupported: dictionary");
    assertThatThrownBy(() -> assertThat(collected).counters())
        .hasMessageStartingWith("unsupported: agent counters");
  }

  /** A collector-like source that carries everything (the shape V1Protocol produces). */
  static Collected full(List<TransactionRecord> transactions) {
    return new Collected() {
      @Override
      public String description() {
        return "test source";
      }

      @Override
      public RecordSource source() {
        return V1Protocol.SOURCE;
      }

      @Override
      public List<TransactionRecord> transactions() {
        return transactions;
      }

      @Override
      public List<TransactionRecord> awaitTransactions(
          Predicate<TransactionRecord> filter, int count, Duration timeout) {
        return transactions.stream().filter(filter).toList();
      }

      @Override
      public Optional<List<SqlStat>> sqlStats() {
        return Optional.of(
            List.of(new SqlStat("select ?", 600, 1, 12.5), new SqlStat("select ?", 400, 0, 3.0)));
      }

      @Override
      public Optional<List<MetricPoint>> metrics() {
        return Optional.of(
            List.of(
                new MetricPoint(
                    "jdbc_pool_active", Map.of("pool", "HikariPool-1"), "GAUGE", 3, 1000)));
      }

      @Override
      public Optional<List<DictionaryItem>> dictionary() {
        return Optional.of(
            List.of(
                new DictionaryItem("SERVICE", 1, "GET /a"),
                new DictionaryItem("SERVICE", 1, "GET /a"),
                new DictionaryItem("SERVICE", 2, "GET /b"),
                new DictionaryItem("SQL_TEXT", 3, "select ?")));
      }

      @Override
      public Optional<Map<String, Long>> counters() {
        return Optional.of(Map.of("orphan_steps_total", 0L));
      }
    };
  }

  @Test
  @DisplayName(
      "missing vs zero on a source with the fields: cpu_time_ms absent is missing, 0 is zero;"
          + " env and version; step threads")
  void missingVersusZero() {
    Map<String, Object> virtual = new LinkedHashMap<>();
    virtual.put("kind", "HTTP");
    virtual.put("env", "test");
    virtual.put("version", "1.2.3");
    virtual.put("alloc_bytes", 0L);
    Map<String, Object> step = new LinkedHashMap<>();
    step.put("type", "SQL");
    step.put("thread", "http-nio-1");
    List<Object> steps = new ArrayList<>();
    steps.add(step);
    virtual.put("steps", steps);
    TransactionRecord tx = new TransactionRecord(virtual, "vt", V1Protocol.SOURCE);
    assertThat(tx).hasEnv("test").hasVersion("1.2.3").cpuTimeMs().isMissing().allocBytes().isZero();
    assertThatThrownBy(() -> assertThat(tx).cpuTimeMs().isZero())
        .hasMessageContaining("cpu_time_ms to be present but it is missing");
    assertThatThrownBy(() -> assertThat(tx).allocBytes().isMissing())
        .hasMessageContaining("alloc_bytes to be missing but was <0>");
    // V1Protocol.SOURCE has no step thread either: still unsupported.
    assertThatThrownBy(() -> assertThat(tx).noStepFromThread(startsWith("argos-")))
        .hasMessageStartingWith("unsupported");
    RecordSource withThreads =
        new RecordSource(
            "threads", V1Protocol.SOURCE.transactionFields(), java.util.Set.of("thread", "type"));
    TransactionRecord threaded = new TransactionRecord(virtual, "vt", withThreads);
    assertThat(threaded).noStepFromThread(startsWith("argos-"));
    step.put("thread", "argos-sender");
    assertThatThrownBy(() -> assertThat(threaded).noStepFromThread(startsWith("argos-")))
        .hasMessageContaining("argos-sender");
  }

  @Test
  @DisplayName("stats, metrics, dictionary and counters on a collector source")
  void collectorParts() {
    Collected collected = full(List.of());
    assertThat(collected).stats().sqlCount("select ?").isEqualTo(1000);
    assertThat(collected).stats().sqlCount("select 1").isZero();
    assertThat(collected).metrics().gauge("jdbc_pool_active", "pool", "HikariPool-1").exists();
    assertThat(collected)
        .metrics()
        .gauge("jdbc_pool_active", "pool", "HikariPool-1")
        .lastValue()
        .isEqualTo(3);
    assertThat(collected).metrics().gauge("jdbc_pool_active", "pool", "Other").isMissing();
    assertThatThrownBy(() -> assertThat(collected).metrics().gauge("jvm_threads").exists())
        .hasMessageContaining("expected samples of jvm_threads");
    assertThat(collected).dictionary().serviceCount().isEqualTo(2);
    assertThat(collected).dictionary().count("SQL_TEXT").isEqualTo(1);
    assertThat(collected).counters().counter("orphan_steps_total").isZero();
    assertThat(collected).counters().counter("sql_outside_tx_total").isMissing();
  }
}
