/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.stats.StatsRuns.Combo;
import io.argos.agent.testing.stats.StatsRuns.Run;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-ACC-12, the application side (ADR-0010 C-220 (12), D-12; WO-16): with profile sampling off
 * ({@code sampling.profile.normal-rate=0}), the full SQL statistics still count every statement the
 * application ran. {@value #REQUESTS} requests to the JPA endpoints of shop-boot3 ({@code GET
 * /api/orders/{id}}: 1 statement, {@code GET /api/orders/{id}/items}: N+1 = 21), whose answers
 * carry {@code sqlStatements} (Hibernate's statement inspector, the application's own counter):
 *
 * <pre>
 *   Σ answer sqlStatements = Σ TransactionRecord.sql_count (those transactions)
 *   Σ TransactionRecord.sql_count (all transactions) = Σ SqlStat.count (all windows)
 * </pre>
 *
 * The server half (Σ SqlStat.count = sql_stat_raw = the statistics API) is the server's {@code
 * FullStatsIT}.
 */
@Tag("req:D-12")
@Tag("req:F-A22")
class FullStatsAppCounterTest {
  /** Requests of the test (half single, half N+1). */
  static final int REQUESTS = 10_000;

  static final int CLIENTS = 8;

  private static final Pattern SQL_STATEMENTS = Pattern.compile("\"sqlStatements\"\\s*:\\s*(\\d+)");

  static Stream<Combo> combos() {
    return StatsRuns.combosOf("shop-boot3");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("combos")
  @DisplayName(
      "T-ACC-12 full statistics = the application's own counter with profile sampling off: Σ"
          + " sqlStatements = Σ sql_count = Σ SqlStat.count over 10,000 JPA requests")
  void fullStatisticsEqualTheApplicationCounter(Combo combo) throws Exception {
    try (Run run =
        StatsRuns.start(
            "fullstats-" + combo.name(), combo, Map.of("sampling.profile.normal-rate", "0"))) {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
      String base = run.app.baseUrl();
      AtomicLong answered = new AtomicLong();
      AtomicLong failures = new AtomicLong();
      AtomicInteger next = new AtomicInteger();
      long begin = System.nanoTime();
      ExecutorService pool = Executors.newFixedThreadPool(CLIENTS);
      try {
        List<Future<?>> workers = new ArrayList<>();
        for (int c = 0; c < CLIENTS; c++) {
          workers.add(
              pool.submit(
                  () -> {
                    int i;
                    while ((i = next.getAndIncrement()) < REQUESTS) {
                      int id = 1 + (i / 2) % 10;
                      String path =
                          (i % 2 == 0) ? "/api/orders/" + id : "/api/orders/" + id + "/items";
                      HttpResponse<String> response =
                          client.send(
                              HttpRequest.newBuilder(URI.create(base + path))
                                  .timeout(Duration.ofSeconds(30))
                                  .GET()
                                  .build(),
                              HttpResponse.BodyHandlers.ofString());
                      Matcher m = SQL_STATEMENTS.matcher(response.body());
                      if (response.statusCode() != 200 || !m.find()) {
                        failures.incrementAndGet();
                      } else {
                        answered.addAndGet(Long.parseLong(m.group(1)));
                      }
                    }
                    return null;
                  }));
        }
        for (Future<?> worker : workers) {
          worker.get();
        }
      } finally {
        pool.shutdownNow();
      }
      long loadMs = (System.nanoTime() - begin) / 1_000_000L;
      assertThat(failures.get()).as("requests without 200 and sqlStatements").isZero();

      Collected collected = run.collected();
      boolean complete =
          StatsRuns.await(
              () -> jpaTransactions(collected.transactions()).size() >= REQUESTS,
              Duration.ofMinutes(2),
              2_000);
      List<TransactionRecord> all = collected.transactions();
      List<TransactionRecord> jpa = jpaTransactions(all);
      assertThat(complete).as("%d JPA transactions received", REQUESTS).isTrue();
      assertThat(jpa).hasSize(REQUESTS);
      long jpaSqlCount = jpa.stream().mapToLong(tx -> StatsRuns.number(tx, "sql_count")).sum();
      assertThat(jpaSqlCount)
          .as("Σ TransactionRecord.sql_count = Σ the answers' sqlStatements")
          .isEqualTo(answered.get());

      long allSqlCount = all.stream().mapToLong(tx -> StatsRuns.number(tx, "sql_count")).sum();
      boolean flushed =
          StatsRuns.await(
              () -> StatsWindowTest.sqlTotal(collected.statWindows().orElseThrow()) == allSqlCount,
              Duration.ofSeconds(40),
              1_000);
      long statsCount = StatsWindowTest.sqlTotal(collected.statWindows().orElseThrow());
      System.out.println(
          "[wo16-fullstats] "
              + combo
              + ": "
              + REQUESTS
              + " requests in "
              + loadMs
              + " ms, sqlStatements "
              + answered.get()
              + ", sql_count (JPA) "
              + jpaSqlCount
              + ", sql_count (all) "
              + allSqlCount
              + ", SqlStat.count "
              + statsCount
              + ", profiles captured "
              + all.stream().filter(tx -> Boolean.TRUE.equals(tx.profileCaptured())).count());
      assertThat(flushed)
          .as(
              "Σ SqlStat.count (%d) = Σ sql_count (%d)\n%s",
              statsCount, allSqlCount, run.describe())
          .isTrue();
      assertThat(statsCount).isGreaterThanOrEqualTo(answered.get());
    }
  }

  private static List<TransactionRecord> jpaTransactions(List<TransactionRecord> all) {
    return all.stream()
        .filter(tx -> tx.path() != null && tx.path().startsWith("/api/orders/"))
        .toList();
  }
}
