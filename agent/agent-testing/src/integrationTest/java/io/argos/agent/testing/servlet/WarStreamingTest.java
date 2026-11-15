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
 * T-AG-016 in a standalone Tomcat (F-A34, ADR-0010 C-127: response flush classes matched by name
 * for Tomcat 9, 10.1, 11): the Spring-free fixture's {@code /stream} (startAsync, three chunks
 * flushed from a container thread {@code 150 ms} apart), its {@code /stream-early} (the same, with
 * the output stream opened before {@code startAsync}: the order of Spring's {@code
 * StreamingResponseBody}) and the shop app's {@code SseEmitter} are one transaction each with the
 * {@code streaming} flag, ended at the first flush; the fixture's streams are named from their
 * exact mappings. The fixture's {@code /async-timeout} (startAsync with a 300 ms timeout, never
 * completed, no error page: 10 §6.7 "타임아웃 시 상태") is one transaction with status 500 and no {@code
 * /error} transaction. Then the run stops and Tomcat's log, with the async cycles of these requests
 * behind it, is judged ({@link WarRuns#judgeTomcatLog}).
 */
@Tag("req:F-A34")
@ExtendWith(ServletRuns.class)
class WarStreamingTest {

  @TestFactory
  @DisplayName("T-AG-016 streaming end in a standalone Tomcat (WAR apps)")
  Stream<DynamicTest> warStreaming() {
    List<DynamicTest> tests = new ArrayList<>();
    for (Combo combo : ServletRuns.warCombos()) {
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-016 "
                  + combo
                  + ": servlet async streaming (fixture /stream, /stream-early with the stream"
                  + " opened before startAsync) and SSE end at the first flush with the streaming"
                  + " flag",
              () -> streaming(ServletRuns.run(combo, Profile.WAR))));
      tests.add(
          DynamicTest.dynamicTest(
              "T-AG-016 "
                  + combo
                  + ": an async request that times out without complete or dispatch (fixture"
                  + " /async-timeout) is one transaction with status 500; no /error transaction",
              () -> asyncTimeout(ServletRuns.run(combo, Profile.WAR))));
      tests.add(WarRuns.tomcatLog("T-AG-016", combo));
    }
    return tests.stream();
  }

  static void streaming(Run run) throws Exception {
    TransactionRecord stream =
        WarRuns.single(run, "GET", FIXTURE_CONTEXT, "/stream", Map.of(), 200);
    assertThat(stream.streaming()).as("F-A34 servlet stream: %s", stream).isTrue();
    assertThat(stream.service()).isEqualTo(FIXTURE_CONTEXT + " GET /stream");
    assertThat(stream.elapsedMs())
        .as("ended at the first flush, before the last chunk (300 ms later): %s", stream)
        .isNotNull()
        .isLessThan(300.0);

    TransactionRecord early =
        WarRuns.single(run, "GET", FIXTURE_CONTEXT, "/stream-early", Map.of(), 200);
    assertThat(early.streaming())
        .as("F-A34 stream opened before startAsync (StreamingResponseBody order): %s", early)
        .isTrue();
    assertThat(early.service()).isEqualTo(FIXTURE_CONTEXT + " GET /stream-early");
    assertThat(early.elapsedMs())
        .as("ended at the first flush, before the last chunk (300 ms later): %s", early)
        .isNotNull()
        .isLessThan(300.0);

    String context = run.app.app().contextPaths().get(0);
    TransactionRecord sse =
        WarRuns.single(run, "GET", context, "/api/sse", Map.of("Accept", "text/event-stream"), 200);
    assertThat(sse.streaming()).as("F-A34 SSE: %s", sse).isTrue();
    assertThat(sse.service()).isEqualTo(context + " GET /api/sse");
    System.out.println(
        "[wo12] T-AG-016 WAR "
            + run.combo
            + ": stream "
            + stream.elapsedMs()
            + " ms, stream-early "
            + early.elapsedMs()
            + " ms, SSE "
            + sse.elapsedMs()
            + " ms");
  }

  /**
   * 10 §6.7 "타임아웃 시 상태" in a real container: Tomcat times the request out (its check runs about
   * once a second), finds no error page, answers 500 with its error report and completes the
   * request; the agent's listener marks the timeout and ends the transaction at {@code onComplete}
   * with 500 (D-21), not at {@code transaction.max-duration-ms}.
   */
  static void asyncTimeout(Run run) throws Exception {
    TransactionRecord tx =
        WarRuns.single(run, "GET", FIXTURE_CONTEXT, "/async-timeout", Map.of(), 500);
    assertThat(tx.status()).as("D-21 after onTimeout: %s", tx).isEqualTo(500);
    assertThat(tx.error()).as(tx.toString()).isTrue();
    assertThat(tx.service()).isEqualTo(FIXTURE_CONTEXT + " GET /async-timeout");
    assertThat(tx.streaming()).as(tx.toString()).isFalse();
    assertThat(tx.timedOut())
        .as("ended by the container's timeout handling, not by max-duration: %s", tx)
        .isFalse();
    assertThat(tx.elapsedMs())
        .as("not before the 300 ms async timeout: %s", tx)
        .isNotNull()
        .isGreaterThanOrEqualTo(300.0);
    assertThat(run.transactions())
        .as("C-48: no transaction is named after or recorded for /error")
        .noneMatch(ServletRuns::isErrorTransaction);
    System.out.println(
        "[wo12] T-AG-016 WAR " + run.combo + ": async timeout " + tx.elapsedMs() + " ms");
  }
}
