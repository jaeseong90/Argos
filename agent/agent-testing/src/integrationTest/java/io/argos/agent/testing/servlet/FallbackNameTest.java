/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.counterSum;
import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The F-A29 fallback name and the service dictionary cap (10 §6.2, ADR-0004 C-48):
 *
 * <ul>
 *   <li>T-AG-013 (every shop jar app, privacy defaults): requests without a handler pattern (404,
 *       405) are named {@code <METHOD> <path>} with numbers, UUIDs and hex strings of 32 characters
 *       or more replaced by {@code {*}} ({@code GET /api/x/12345} -> {@code GET /api/x/{*}}), the
 *       actual request method included; never {@code /error}.
 *   <li>T-AG-013 (first shop jar app, {@code instrumentation.service-dictionary-max=5}): new names
 *       beyond the cap are {@code _other_}, each counted in {@code
 *       argos_agent_service_dictionary_overflow_total}; known names still pass. The literal
 *       10,001st name of the default cap is the unit test {@code ServiceNameResolverTest}
 *       (T-AG-013b).
 * </ul>
 */
@Tag("req:F-A29")
@ExtendWith(ServletRuns.class)
class FallbackNameTest {
  static final String OVERFLOW_COUNTER = "argos_agent_service_dictionary_overflow_total";

  /** Request path, expected status, expected service name. */
  static final List<String[]> FALLBACKS =
      List.of(
          new String[] {"GET", "/api/x/12345", "404", "GET /api/x/{*}"},
          new String[] {
            "GET",
            "/api/x/123e4567-e89b-12d3-a456-426614174000/detail",
            "404",
            "GET /api/x/{*}/detail"
          },
          new String[] {"GET", "/api/x/0123456789abcdef0123456789ABCDEF", "404", "GET /api/x/{*}"},
          new String[] {"GET", "/api/x/v2beta", "404", "GET /api/x/v2beta"},
          new String[] {"GET", "/api/not-found-x", "404", "GET /api/not-found-x"},
          // A GET-only mapping called with DELETE: 405 from Spring, no handler, no pattern.
          new String[] {"DELETE", "/api/orders/77", "405", "DELETE /api/orders/{*}"});

  @TestFactory
  @DisplayName("T-AG-013 fallback service names and the service dictionary cap (shop jar apps)")
  Stream<DynamicTest> fallbackNames() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-013 "
                  + combo
                  + ": requests without a handler pattern get the F-A29 fallback name (GET"
                  + " /api/x/12345 -> GET /api/x/{*}), never /error",
              () -> fallback(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    for (Combo combo : ServletRuns.firstJarCombo()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-013 "
                  + combo
                  + ": instrumentation.service-dictionary-max=5, new names beyond the cap are"
                  + " _other_ and counted in service_dictionary_overflow_total",
              () -> overflow(ServletRuns.run(combo, Profile.LIMITS))));
    }
    return tests.stream();
  }

  static void fallback(Run run) throws Exception {
    for (String[] request : FALLBACKS) {
      int before = run.settle();
      int pathBefore = run.transactions(path(request[1])).size();
      assertThat(run.call(request[0], request[1], Map.of()).status())
          .as("%s %s", request[0], request[1])
          .isEqualTo(Integer.parseInt(request[2]));
      List<TransactionRecord> added = run.newTransactions(path(request[1]), pathBefore, 1);
      assertThat(added).as("%s %s%n%s", request[0], request[1], run.describe()).hasSize(1);
      assertThat(run.transactions().size() - before).as("no /error transaction").isEqualTo(1);
      TransactionRecord tx = added.get(0);
      assertThat(tx.service()).as(tx.toString()).isEqualTo(request[3]);
      assertThat(tx.status()).isEqualTo(Integer.parseInt(request[2]));
      assertThat(tx.path()).isEqualTo(request[1]);
    }
    assertThat(run.transactions()).noneMatch(ServletRuns::isErrorTransaction);
  }

  static void overflow(Run run) throws Exception {
    run.settle();
    SelfDiagnostics before = run.freshSelfDiagnostics();
    long overflowBefore = counterSum(before, OVERFLOW_COUNTER);
    int sent = 12;
    List<String> paths = new ArrayList<>();
    for (int i = 0; i < sent; i++) {
      String path = "/api/limit-" + (char) ('a' + i);
      paths.add(path);
      assertThat(run.get(path).status()).isEqualTo(404);
    }
    // A name admitted before the cap stays known.
    List<TransactionRecord> named = new ArrayList<>();
    for (String path : paths) {
      List<TransactionRecord> matching = run.newTransactions(path(path), 0, 1);
      assertThat(matching).as("transaction of %s%n%s", path, run.describe()).hasSize(1);
      named.add(matching.get(0));
    }
    Set<String> distinct = new TreeSet<>();
    for (TransactionRecord tx : run.transactions()) {
      if (tx.service() != null && !tx.service().equals("_other_")) {
        distinct.add(tx.service());
      }
    }
    assertThat(distinct).as("names kept with service-dictionary-max=5").hasSizeLessThanOrEqualTo(5);
    long others = named.stream().filter(tx -> "_other_".equals(tx.service())).count();
    assertThat(others).as("new names beyond the cap: %s", named).isGreaterThanOrEqualTo(sent - 5);
    for (TransactionRecord tx : named) {
      if (!"_other_".equals(tx.service())) {
        assertThat(tx.service()).isEqualTo("GET " + tx.path());
      }
    }
    SelfDiagnostics after = run.awaitCounter(OVERFLOW_COUNTER, overflowBefore + others);
    assertThat(counterSum(after, OVERFLOW_COUNTER) - overflowBefore)
        .as("service_dictionary_overflow_total counts every _other_ transaction")
        .isEqualTo(others);
    // A name that was admitted is still admitted after the cap.
    String kept =
        named.stream()
            .filter(tx -> !"_other_".equals(tx.service()))
            .map(TransactionRecord::path)
            .findFirst()
            .orElse(null);
    if (kept != null) {
      int keptBefore = run.transactions(path(kept)).size();
      assertThat(run.get(kept).status()).isEqualTo(404);
      List<TransactionRecord> again = run.newTransactions(path(kept), keptBefore, 1);
      assertThat(again).hasSize(1);
      assertThat(again.get(0).service()).isEqualTo("GET " + kept);
    }
    System.out.println(
        "[wo12] T-AG-013 " + run.combo + ": kept " + distinct + ", _other_ " + others);
  }
}
