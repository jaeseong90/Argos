/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.path;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.servlet.ServletRuns.Combo;
import io.argos.agent.testing.servlet.ServletRuns.Profile;
import io.argos.agent.testing.servlet.ServletRuns.Run;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * T-AG-020, Spring part (10 §6.2, F-A02, 10 U-8): on Spring Framework 5.3 ({@code shop-boot2}), 6.2
 * ({@code shop-boot3}) and 7.0 ({@code shop-boot4}) the handler mapping's {@code
 * bestMatchingPattern} names the transaction {@code <METHOD> <pattern>} on the REQUEST dispatch:
 * path variables, a class-level plus method-level mapping, a query string that is not part of the
 * name, a POST mapping and an async handler. The Spring-free part (Servlet 4+ {@code
 * HttpServletMapping}) is {@code WarServletMappingTest}.
 */
@Tag("req:F-A02")
@ExtendWith(ServletRuns.class)
class SpringPatternTest {
  /** Method, request path, expected service. */
  static final List<String[]> PATTERNS =
      List.of(
          new String[] {"GET", "/api/orders/1/items", "GET /api/orders/{id}/items"},
          new String[] {"GET", "/api/orders?page=0", "GET /api/orders"},
          new String[] {"GET", "/api/pay/1?client=hurl", "GET /api/pay/{id}"},
          new String[] {"POST", "/api/tx/commit", "POST /api/tx/commit"},
          new String[] {"GET", "/api/async/callable", "GET /api/async/callable"},
          new String[] {"GET", "/api/version", "GET /api/version"});

  /** The Spring Framework generation of each shop jar app (18 §4.1). */
  static final Map<String, String> FRAMEWORK =
      Map.of("shop-boot2", "5.3", "shop-boot3", "6.2", "shop-boot4", "7.0");

  @TestFactory
  @DisplayName("T-AG-020 Spring MVC bestMatchingPattern names (shop jar apps)")
  Stream<DynamicTest> springPatterns() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.jarCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-020 "
                  + combo
                  + " (Spring Framework "
                  + FRAMEWORK.get(combo.app())
                  + "): bestMatchingPattern names the transactions <METHOD> <pattern>",
              () -> patterns(ServletRuns.run(combo, Profile.DEFAULTS))));
    }
    return tests.stream();
  }

  static void patterns(Run run) throws Exception {
    List<String> seen = new ArrayList<>();
    for (String[] request : PATTERNS) {
      String bare = ServletRuns.bare(request[1]);
      int before = run.settle();
      int pathBefore = run.transactions(path(bare)).size();
      assertThat(run.call(request[0], request[1], Map.of()).status())
          .as("%s %s", request[0], request[1])
          .isEqualTo(200);
      List<TransactionRecord> added = run.newTransactions(path(bare), pathBefore, 1);
      assertThat(added).as("%s %s%n%s", request[0], request[1], run.describe()).hasSize(1);
      assertThat(run.transactions().size() - before).isEqualTo(1);
      TransactionRecord tx = added.get(0);
      assertThat(tx.service()).as(tx.toString()).isEqualTo(request[2]);
      assertThat(tx.serviceFixed()).as("a pattern fixes the name: %s", tx).isTrue();
      assertThat(tx.httpMethod()).isEqualTo(request[0]);
      seen.add(tx.service());
    }
    System.out.println("[wo12] T-AG-020 " + run.combo + ": " + seen);
  }
}
