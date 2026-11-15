/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The wall-clock side of the normalizer budget (10 §9.4 "1ms 시간 예산", DSN-10-17): after warm-up, the
 * time to normalize a 64 KiB worst-case statement stays within 1 ms. The unit test {@code
 * SqlNormalizerBudgetTest} bounds the same inputs by character reads; this one measures them on the
 * machine that runs it (ADR-0010 C-146 (2): real-time bounds are slow-tagged integration tests).
 *
 * <p>Measurement: every input is warmed up before any is measured. An attempt is {@value #ROUNDS}
 * rounds of {@value #CALLS_PER_ROUND} calls, and its value is the median of the per-round minima,
 * the cost of the statement on this machine without the preemptions of single calls. An input
 * passes when one of at most {@value #ATTEMPTS} attempts is within the budget: on the shared
 * development laptop the first attempt of an input sometimes ran slow as a whole (median of minima
 * 1.03-1.12 ms, then 0.69-0.85 ms in the next attempt of the same input, while other builds ran),
 * which a real overrun does not escape three times. Every input is measured before anything is
 * asserted; one {@code [sql-timing]} line per attempt gives the median and the 90th percentile of
 * all its calls and its median of minima (C-146 (2): change the measurement, never the budget), and
 * a closing {@code [sql-timing] attempts} line gives the number of attempts of every input
 * (ADR-0010 C-186 (3): the retries are for shared-machine noise only; a run on a quiet machine,
 * which a human confirms, counts when every input passes in its first attempt).
 */
@Tag("slow")
@Tag("req:SEC-35")
@Tag("req:D-22")
class SqlNormalizerTimingIntegrationTest {
  private static final int WARM_UP_ROUNDS = 300;
  private static final int REWARM_ROUNDS = 50;
  private static final int ROUNDS = 41;
  private static final int CALLS_PER_ROUND = 5;
  private static final int ATTEMPTS = 3;
  private static final long BUDGET_NANOS = 1_000_000L;

  private static List<String> inputs() {
    int n = SqlNormalizer.MAX_LENGTH;
    return Arrays.asList(
        fill("select ", "[ab.cd e", n),
        fill("select ", "in (?, ?, ?, ", n),
        fill("insert into t values ", "(?, 'kim@x.com', 1.5e3), ", n),
        fill("select ", "'a''b' ", n),
        fill("select ", "col_1 = :p1 and ", n),
        fill("select /* c */ ", "a.b, ", n),
        // The highest token density (one- and two-character tokens), as in SqlNormalizerBudgetTest.
        fill("select ", "a,", n),
        fill("select ", "-1,", n));
  }

  private static String fill(String head, String unit, int length) {
    StringBuilder out = new StringBuilder(length + unit.length());
    out.append(head);
    while (out.length() < length) {
      out.append(unit);
    }
    out.setLength(length);
    return out.toString();
  }

  /** Characters of output, so that the calls cannot be optimized away. */
  private long sink;

  @Test
  @DisplayName(
      "T-AG-034 normalization time: a 64 KiB worst-case statement is normalized within the 1 ms"
          + " budget after warm-up (reference value on a shared machine)")
  void withinOneMillisecond() {
    SqlNormalizer normalizer = SqlNormalizer.defaults();
    List<String> over = new ArrayList<String>();
    List<String> attempts = new ArrayList<String>();
    int firstAttempt = 0;
    // Warm up with every input first, so that the compiled code has seen every token mix before
    // anything is measured (switching inputs would otherwise recompile it during a measurement).
    for (int pass = 0; pass < 2; pass++) {
      for (String sql : inputs()) {
        warmUp(normalizer, sql, WARM_UP_ROUNDS / 2);
      }
    }
    for (String sql : inputs()) {
      String label = sql.substring(0, 24);
      boolean within = false;
      String last = null;
      int attempt = 0;
      while (attempt < ATTEMPTS && !within) {
        attempt++;
        warmUp(normalizer, sql, REWARM_ROUNDS);
        long[] all = new long[ROUNDS * CALLS_PER_ROUND];
        long[] minima = new long[ROUNDS];
        for (int round = 0; round < ROUNDS; round++) {
          long min = Long.MAX_VALUE;
          for (int call = 0; call < CALLS_PER_ROUND; call++) {
            long start = System.nanoTime();
            sink += normalizer.normalize(sql).length();
            long took = System.nanoTime() - start;
            all[round * CALLS_PER_ROUND + call] = took;
            min = Math.min(min, took);
          }
          minima[round] = min;
        }
        Arrays.sort(all);
        Arrays.sort(minima);
        long minMedian = minima[minima.length / 2];
        last =
            String.format(
                Locale.ROOT,
                "[sql-timing] %-24s attempt %d: median %7d ns, p90 %7d ns, median of round minima"
                    + " %7d ns",
                label,
                attempt,
                all[all.length / 2],
                all[all.length * 9 / 10],
                minMedian);
        System.out.println(last);
        within = minMedian <= BUDGET_NANOS;
      }
      if (!within) {
        over.add(last);
      }
      attempts.add(label.trim() + ": " + attempt + (within ? "" : " (over)"));
      firstAttempt += within && attempt == 1 ? 1 : 0;
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "[sql-timing] attempts per input (C-186 (3)): %s; first-attempt passes %d of %d",
            String.join(" | ", attempts),
            firstAttempt,
            attempts.size()));
    assertThat(sink).isPositive();
    assertThat(over).as("inputs over the 1 ms budget in every attempt").isEmpty();
  }

  private void warmUp(SqlNormalizer normalizer, String sql, int calls) {
    for (int i = 0; i < calls; i++) {
      sink += normalizer.normalize(sql).length();
    }
  }
}
