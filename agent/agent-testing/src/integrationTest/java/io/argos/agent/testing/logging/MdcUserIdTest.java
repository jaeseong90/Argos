/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.logging;

import static io.argos.agent.testing.log.AgentLogAssert.assertThatAgentLog;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.testing.launcher.SampleApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.agent.testing.logging.LoggingRuns.Combo;
import io.argos.agent.testing.logging.LoggingRuns.Run;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * ADR-0010 C-125 (3) (SEC-32, 16 §6.3), with the release agent jar: {@code capture.user-id.source:
 * mdc:user} records the {@code user} MDC value of the request's log line ({@code GET
 * /api/mdc-log?user=}) in {@code plain} and in {@code hashed} mode (the FakeCollector's salt); a
 * request whose log line has no {@code user} key records no user id and counts {@code
 * argos_agent_user_id_skipped_total{reason="mdc_absent"}}; the agent log has no WARN about the
 * source (the WO-12 {@code mdc_unsupported} WARN is gone) and never the user id. First shop jar app
 * of {@code -Pagent.e2e}.
 */
@Tag("req:SEC-32")
@Tag("req:F-A20")
class MdcUserIdTest {
  static final String ABSENT = "argos_agent_user_id_skipped_total{reason=\"mdc_absent\"}";

  @TestFactory
  Stream<DynamicTest> mdcUserId() {
    Combo combo = LoggingRuns.shopCombos().get(0);
    List<DynamicTest> tests = new ArrayList<>();
    for (String mode : List.of("plain", "hashed")) {
      tests.add(
          DynamicTest.dynamicTest(
              "C-125 (3) "
                  + combo
                  + " mdc:user "
                  + mode
                  + ": the user MDC value of the log line is the record's user id; a request"
                  + " without it counts mdc_absent",
              () -> run(combo, mode)));
    }
    return tests.stream();
  }

  private static void run(Combo combo, String mode) throws Exception {
    String name = "wo18-userid-" + mode + "-" + combo.app() + "-jdk" + combo.jdk();
    Map<String, String> settings =
        Map.of("capture.user-id.mode", mode, "capture.user-id.source", "mdc:user");
    String userId = "kim-" + LoggingRuns.tag("u");
    try (Run run =
        Run.start(
            name,
            SampleApp.named(combo.app()),
            combo.jdk(),
            settings,
            true,
            List.of(),
            List.of())) {
      SelfDiagnostics before = run.app.awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(30));
      long absentBefore = before.counters().getOrDefault(ABSENT, 0L);

      TransactionRecord with =
          run.request(
              "/api/mdc-log?tag=" + LoggingRuns.tag("user") + "&user=" + userId, "/api/mdc-log");
      String expected = mode.equals("plain") ? userId : LoggingRuns.hashed(userId);
      assertThat(with.userId()).as("user id of %s", with).isEqualTo(expected);

      TransactionRecord async =
          run.request(
              "/api/mdc-log?async=true&tag=" + LoggingRuns.tag("user") + "&user=" + userId,
              "/api/mdc-log");
      assertThat(async.userId())
          .as("the line logged on the app executor thread decides too: %s", async)
          .isEqualTo(expected);

      int requests = 3;
      for (int i = 0; i < requests; i++) {
        TransactionRecord without =
            run.request("/api/mdc-log?tag=" + LoggingRuns.tag("none"), "/api/mdc-log");
        assertThat(without.userId()).as("no user MDC key: %s", without).isNull();
      }
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      long absent;
      do {
        Thread.sleep(500);
        absent =
            run.app
                .awaitSelfDiagnostics(Instant.now(), Duration.ofSeconds(30))
                .counters()
                .getOrDefault(ABSENT, 0L);
      } while (absent < absentBefore + requests && System.nanoTime() < deadline);
      assertThat(absent - absentBefore)
          .as("%s after %d requests without the key (readiness requests may add more)", ABSENT, 3)
          .isGreaterThanOrEqualTo(requests);
      System.out.println(
          "[wo18-userid] "
              + name
              + ": user id "
              + (mode.equals("plain") ? "plain" : "hashed")
              + " recorded from the log line, "
              + ABSENT
              + " +"
              + (absent - absentBefore));

      String log = run.app.agentLog();
      assertThatAgentLog(log).hasStartBanner().hasNoErrors().doesNotContainText(userId);
      assertThat(log).as("no WARN about the mdc: source").doesNotContain("mdc:<key>");
      if (mode.equals("hashed")) {
        assertThatAgentLog(log).doesNotContainText(expected);
      }
    }
  }
}
