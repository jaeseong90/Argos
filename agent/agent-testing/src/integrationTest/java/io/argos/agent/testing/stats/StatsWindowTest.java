/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.stats.StatsRuns.Combo;
import io.argos.agent.testing.stats.StatsRuns.Run;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-AG-061 (10 §9.3, F-A22, D-12; WO-16): the 10-second statistics windows of a real agent equal
 * what the application executed. A shop app (shop-boot3@21, shop-boot2@8 of {@code -Pagent.e2e})
 * runs a burst of known SQL inside one window, result pages, and outgoing calls to ext-stub that
 * succeed, time out, are refused and answer 500. The FakeCollector's windows must have:
 *
 * <ul>
 *   <li>{@code window_start_ms % 10 s = 0}, {@code window_len_s = 10}, no {@code counters} (the
 *       agent's own counters travel in the MetricBatch, WO-16 PM question 1);
 *   <li>in the burst window, the count of the burst statement = the executions of the burst (the
 *       transactions' SQL steps, folded repeats counted);
 *   <li>over all windows: Σ {@code SqlStat.count} = Σ {@code TransactionRecord.sql_count}, per
 *       statement the count = its SQL steps, {@code fetch_rows} = the rows its steps read (C-185
 *       (2)), buckets add up to the count, {@code sum_us = sum_exec_us + sum_fetch_us};
 *   <li>external calls exactly: ext-stub's host has every successful, timed out and 500 call with
 *       {@code timeout_count} and {@code error_count} as executed; the refused port's host has
 *       {@code connect_fail_count} = the refused calls.
 * </ul>
 */
@Tag("req:F-A22")
@Tag("req:D-12")
class StatsWindowTest {
  /** Executions of the burst statement. */
  static final int BURST = 20;

  static final int PAGES = 5;
  static final int PAY_OK = 5;
  static final int PAY_TIMEOUT = 2;
  static final int PAY_REFUSED = 3;
  static final int PAY_5XX = 2;
  static final int WARMUP = 5;

  static Stream<Combo> combos() {
    return StatsRuns.combosOf("shop-boot3", "shop-boot2");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("combos")
  @DisplayName(
      "T-AG-061 10 s windows of SQL and external-call statistics equal the executions (count,"
          + " timeout and connect_fail exact, ts aligned to 10 s, fetch_rows = rows read)")
  void windowsEqualExecutions(Combo combo) throws Exception {
    try (Run run =
        StatsRuns.start(
            "stats-" + combo.name(), combo, Map.of("sampling.profile.normal-rate", "1.0"))) {
      int requests = 0;
      for (int i = 0; i < WARMUP; i++) {
        run.get("/api/orders/1", 200);
        run.get("/api/orders?page=0", 200);
        run.get("/api/pay/" + (900 + i) + "?client=hurl", 200);
        requests += 3;
      }

      // The burst starts 0.5 s into a fresh window, so that it normally fits in that window.
      long now = System.currentTimeMillis();
      long windowStart = (now / 10_000L + 1) * 10_000L;
      Thread.sleep(windowStart + 500 - now);
      for (int i = 0; i < BURST; i++) {
        run.get("/api/orders/" + (1 + i % 10), 200);
      }
      long burstEnd = System.currentTimeMillis();
      requests += BURST;
      boolean oneWindow = burstEnd < windowStart + 9_500L;

      for (int i = 0; i < PAGES; i++) {
        run.get("/api/orders?page=" + i, 200);
      }
      for (int i = 0; i < PAY_OK; i++) {
        run.get("/api/pay/" + (1000 + i) + "?client=hurl", 200);
      }
      for (int i = 0; i < PAY_TIMEOUT; i++) {
        run.get("/api/pay/" + (1100 + i) + "?client=hurl&timeout=1", 504);
      }
      for (int i = 0; i < PAY_REFUSED; i++) {
        run.get("/api/pay/" + (1200 + i) + "?client=hurl&refuse=1", 503);
      }
      for (int i = 0; i < PAY_5XX; i++) {
        run.get("/api/pay/" + (1300 + i) + "?client=hurl&status=500", 502);
      }
      requests += PAGES + PAY_OK + PAY_TIMEOUT + PAY_REFUSED + PAY_5XX;

      Collected collected = run.collected();
      List<TransactionRecord> txs =
          collected.awaitTransactions(tx -> tx.path() != null, requests, Duration.ofSeconds(60));
      assertThat(txs).as("transactions of the %d requests", requests).hasSize(requests);
      long sqlCount = txs.stream().mapToLong(tx -> StatsRuns.number(tx, "sql_count")).sum();

      // A window leaves the agent about 1.5 s after its end.
      boolean flushed =
          StatsRuns.await(
              () ->
                  sqlTotal(collected.statWindows().orElseThrow()) == sqlCount
                      && extTotal(collected.statWindows().orElseThrow())
                          == WARMUP + PAY_OK + PAY_TIMEOUT + PAY_REFUSED + PAY_5XX,
              Duration.ofSeconds(40),
              500);
      List<Collected.StatWindow> windows = collected.statWindows().orElseThrow();
      System.out.println(
          "[wo16-stats] "
              + combo
              + ": "
              + windows.size()
              + " windows, sql_count "
              + sqlCount
              + ", stats count "
              + sqlTotal(windows)
              + ", burst in one window "
              + oneWindow);
      assertThat(flushed)
          .as(
              "Σ SqlStat.count = Σ sql_count (%d) and every external call in the windows: %s\n%s",
              sqlCount, windows, run.describe())
          .isTrue();

      for (Collected.StatWindow w : windows) {
        assertThat(w.windowStartMs() % 10_000L).as("window start of %s", w).isZero();
        assertThat(w.windowLenS()).isEqualTo(10);
        assertThat(w.counterNames()).as("StatBatch.counters (PM question 1)").isEmpty();
        for (Collected.SqlRow row : w.sql()) {
          assertThat(row.buckets()).hasSize(15);
          assertThat(row.buckets().stream().mapToLong(Long::longValue).sum())
              .as("buckets of %s", row)
              .isEqualTo(row.count());
          assertThat(row.sumExecUs()).as("sum_exec_us of %s", row).isNotNull();
          assertThat(row.sumFetchUs()).as("sum_fetch_us of %s", row).isNotNull();
          assertThat(row.sumUs()).isEqualTo(row.sumExecUs() + row.sumFetchUs());
          assertThat(row.kind()).isEqualTo("HTTP");
        }
        for (Collected.ExtRow row : w.ext()) {
          assertThat(row.buckets().stream().mapToLong(Long::longValue).sum())
              .as("buckets of %s", row)
              .isEqualTo(row.count());
        }
      }

      // Per statement: count = SQL steps (folded repeats counted), fetch_rows = rows read.
      Map<String, long[]> steps = sqlSteps(txs);
      Map<String, long[]> stats = new HashMap<>();
      for (Collected.StatWindow w : windows) {
        for (Collected.SqlRow row : w.sql()) {
          long[] v = stats.computeIfAbsent(String.valueOf(row.sql()), k -> new long[2]);
          v[0] += row.count();
          v[1] += row.fetchRows();
        }
      }
      boolean rowsChecked = false;
      for (Map.Entry<String, long[]> e : steps.entrySet()) {
        long[] fromStats = stats.getOrDefault(e.getKey(), new long[2]);
        assertThat(fromStats[0]).as("SqlStat.count of %s", e.getKey()).isEqualTo(e.getValue()[0]);
        if (e.getValue()[2] == 0) {
          assertThat(fromStats[1])
              .as("SqlStat.fetch_rows of %s = rows its steps read", e.getKey())
              .isEqualTo(e.getValue()[1]);
          rowsChecked |= e.getValue()[1] > 0;
        }
      }
      assertThat(rowsChecked).as("a statement that read rows was compared").isTrue();

      // The burst window.
      List<TransactionRecord> burst =
          txs.stream()
              .filter(tx -> tx.path().startsWith("/api/orders/"))
              .filter(tx -> tx.startEpochMs() != null && tx.startEpochMs() >= windowStart)
              .toList();
      assertThat(burst).hasSize(BURST);
      Map<String, long[]> burstSteps = sqlSteps(burst);
      if (oneWindow) {
        for (Map.Entry<String, long[]> e : burstSteps.entrySet()) {
          long inWindow =
              windows.stream()
                  .filter(w -> w.windowStartMs() == windowStart)
                  .flatMap(w -> w.sql().stream())
                  .filter(r -> e.getKey().equals(String.valueOf(r.sql())))
                  .mapToLong(Collected.SqlRow::count)
                  .sum();
          assertThat(inWindow)
              .as("executions of %s in the burst window %d", e.getKey(), windowStart)
              .isEqualTo(e.getValue()[0]);
        }
      } else {
        System.out.println(
            "[wo16-stats] "
                + combo
                + ": the burst took "
                + (burstEnd - windowStart - 500)
                + " ms and crossed a window end; per-window check covered by the totals only");
      }

      // External calls.
      int stubPort = run.extStub.port();
      Collected.ExtStat stub = ext(collected, Run.hostText(stubPort));
      assertThat(stub.count()).isEqualTo(WARMUP + PAY_OK + PAY_TIMEOUT + PAY_5XX);
      assertThat(stub.timeouts()).isEqualTo(PAY_TIMEOUT);
      assertThat(stub.errors()).isEqualTo(PAY_5XX);
      assertThat(stub.connectFails()).isZero();
      Collected.ExtStat refused = ext(collected, Run.hostText(1));
      assertThat(refused.count()).isEqualTo(PAY_REFUSED);
      assertThat(refused.connectFails()).isEqualTo(PAY_REFUSED);
      assertThat(refused.timeouts()).isZero();
      assertThat(refused.errors()).isZero();
      // The same calls as the HTTP steps say.
      long timeoutSteps =
          txs.stream()
              .flatMap(tx -> tx.steps("HTTP").stream())
              .filter(s -> "TIMEOUT".equals(s.field("failure")))
              .count();
      assertThat(timeoutSteps).isEqualTo(stub.timeouts());
    }
  }

  private static Collected.ExtStat ext(Collected collected, String host) {
    List<Collected.ExtStat> rows =
        collected.extStats().orElseThrow().stream().filter(s -> host.equals(s.host())).toList();
    assertThat(rows).as("ExtStat of %s in %s", host, collected.extStats()).hasSize(1);
    return rows.get(0);
  }

  /** Per SQL text: {executions, rows, folded steps}. */
  static Map<String, long[]> sqlSteps(List<TransactionRecord> txs) {
    Map<String, long[]> out = new HashMap<>();
    Set<String> folded = new HashSet<>();
    for (TransactionRecord tx : txs) {
      for (TransactionRecord.StepRecord step : tx.steps("SQL")) {
        long[] v = out.computeIfAbsent(String.valueOf(step.name()), k -> new long[3]);
        Integer repeat = step.repeatCount();
        int times = repeat == null || repeat < 1 ? 1 : repeat;
        v[0] += times;
        v[1] += step.rows() == null ? 0 : step.rows();
        if (times > 1) {
          folded.add(String.valueOf(step.name()));
        }
      }
    }
    for (String text : folded) {
      out.get(text)[2] = 1;
    }
    return out;
  }

  static long sqlTotal(List<Collected.StatWindow> windows) {
    return windows.stream().flatMap(w -> w.sql().stream()).mapToLong(Collected.SqlRow::count).sum();
  }

  static long extTotal(List<Collected.StatWindow> windows) {
    return windows.stream().flatMap(w -> w.ext().stream()).mapToLong(Collected.ExtRow::count).sum();
  }
}
