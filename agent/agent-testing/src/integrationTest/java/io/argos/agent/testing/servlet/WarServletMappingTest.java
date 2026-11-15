/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.servlet;

import static io.argos.agent.testing.servlet.ServletRuns.FIXTURE_CONTEXT;
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
 * T-AG-020, Spring-free part (F-A02 "Spring 없는 앱은 HttpServletMapping 폴백", 10 §6.1 exit): the
 * agent-testing servlet fixture ({@code src/servletFixture{Jakarta,Javax}}, deployed as {@value
 * ServletRuns#FIXTURE_CONTEXT} next to the WAR app) names its transactions from the Servlet 4+
 * {@code HttpServletMapping} pattern: prefix {@code /items/*}, exact {@code /plain/exact},
 * extension {@code *.do}, a forwarding servlet's own mapping {@code /forward/*}; a request that
 * only Tomcat's default servlet ({@code /}) serves gets the F-A29 path fallback. Every name carries
 * the context path (F-A36). Then the run stops and Tomcat's log is judged ({@link
 * WarRuns#judgeTomcatLog}).
 */
@Tag("req:F-A02")
@ExtendWith(ServletRuns.class)
class WarServletMappingTest {
  /** Request path within the fixture, status, expected name after the context path. */
  static final List<String[]> MAPPINGS =
      List.of(
          new String[] {"/items/42", "200", "GET /items/*"},
          new String[] {"/items/a/b", "200", "GET /items/*"},
          new String[] {"/plain/exact", "200", "GET /plain/exact"},
          new String[] {"/report.do", "200", "GET *.do"},
          new String[] {"/deep/path/list.do", "200", "GET *.do"},
          new String[] {"/forward/7", "200", "GET /forward/*"},
          new String[] {"/nothing/12345", "404", "GET /nothing/{*}"});

  @TestFactory
  @DisplayName("T-AG-020 HttpServletMapping names of a Spring-free web application (WAR apps)")
  Stream<DynamicTest> servletMappings() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.warCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-020 "
                  + combo
                  + ": a Spring-free web application is named from the HttpServletMapping pattern"
                  + " (prefix, exact, extension); the default servlet gets the F-A29 fallback",
              () -> mappings(ServletRuns.run(combo, Profile.WAR))));
      tests.add(WarRuns.tomcatLog("T-AG-020", combo));
    }
    return tests.stream();
  }

  static void mappings(Run run) throws Exception {
    List<String> names = new ArrayList<>();
    for (String[] mapping : MAPPINGS) {
      TransactionRecord tx =
          WarRuns.single(
              run, "GET", FIXTURE_CONTEXT, mapping[0], Map.of(), Integer.parseInt(mapping[1]));
      assertThat(tx.service()).as(tx.toString()).isEqualTo(FIXTURE_CONTEXT + " " + mapping[2]);
      assertThat(tx.status()).isEqualTo(Integer.parseInt(mapping[1]));
      names.add(mapping[0] + " -> " + tx.service());
    }
    System.out.println("[wo12] T-AG-020 servlet fixture " + run.combo + ": " + names);
  }
}
