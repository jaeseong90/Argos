/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

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
 * T-AG-015 (F-A36, 18 §4.1): in a standalone Tomcat with several web applications ({@code
 * shop-war-jakarta} as {@code /shop-a} and {@code /shop-b} on Tomcat 10.1; {@code shop-war-javax}
 * as {@code /shop-war-javax} on Tomcat 9) every service name carries its web application's context
 * path: the Spring pattern ({@code /shop-a GET /api/orders/{id}}) and the F-A29 fallback ({@code
 * /shop-b GET /api/x/{*}}), never {@code /error} (Spring Boot's {@code ErrorPageFilter} forwards
 * errors in a WAR). The full matrix of Tomcat and JDK versions is WO-50. Then the run stops and
 * Tomcat's log is judged ({@link WarRuns#judgeTomcatLog}).
 */
@Tag("req:F-A36")
@ExtendWith(ServletRuns.class)
class WarContextPrefixTest {

  @TestFactory
  @DisplayName("T-AG-015 multi-webapp context prefix in a standalone Tomcat (WAR apps)")
  Stream<DynamicTest> contextPrefix() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.warCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-015 "
                  + combo
                  + ": every web application prefixes its service names with its context path",
              () -> prefixes(ServletRuns.run(combo, Profile.WAR))));
      tests.add(WarRuns.tomcatLog("T-AG-015", combo));
    }
    return tests.stream();
  }

  static void prefixes(Run run) throws Exception {
    List<String> contexts = run.app.app().contextPaths();
    assertThat(contexts).isNotEmpty().doesNotContain("");
    List<String> names = new ArrayList<>();
    for (String context : contexts) {
      TransactionRecord order = WarRuns.single(run, "GET", context, "/api/orders/1", Map.of(), 200);
      assertThat(order.service()).as(order.toString()).isEqualTo(context + " GET /api/orders/{id}");
      TransactionRecord fallback =
          WarRuns.single(run, "GET", context, "/api/x/12345", Map.of(), 404);
      assertThat(fallback.service()).as(fallback.toString()).isEqualTo(context + " GET /api/x/{*}");
      names.add(order.service());
      names.add(fallback.service());
    }
    assertThat(run.transactions()).noneMatch(ServletRuns::isErrorTransaction);
    System.out.println("[wo12] T-AG-015 " + run.combo + ": " + names);
  }
}
