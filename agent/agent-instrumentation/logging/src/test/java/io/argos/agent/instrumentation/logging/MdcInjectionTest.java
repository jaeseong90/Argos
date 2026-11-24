/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import static io.argos.agent.instrumentation.logging.LoggingLoaders.call;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.instrumentation.logging.LoggingLoaders.Framework;
import java.io.File;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T-AG-065 (module level, F-A20, 10 §6.9) and ADR-0010 C-125 (3): Logback, Log4j 2 and Log4j 1.2 /
 * reload4j with the advice inlined into the real library classes, the real tracer and the pattern
 * {@code %X{argos.trace_id}|%X{argos.tx_id}|%X{user}|%m}: the printed ids are the transaction's
 * (the core's formatting), after the application's {@code MDC.clear()} too, on another thread the
 * transaction was handed to, through an {@code AsyncAppender} (Logback, Log4j 1), and absent
 * without a transaction. Log4j 2 adds the keys only where its injector returns a mutable map (10
 * §18 U-13, {@link Log4j2InjectorWitnessTest}); its expectations follow {@link
 * #log4j2KeysExpected}.
 */
@Tag("req:F-A20")
class MdcInjectionTest {
  private static final Map<Framework, Class<?>> FIXTURES =
      new EnumMap<Framework, Class<?>>(Framework.class);
  private static File log4j2File;
  private static boolean log4j2Mutable;
  private static ExecutorService worker;

  private final LoggingTracing tracing = new LoggingTracing();

  @BeforeAll
  static void loadFixtures() throws Exception {
    for (Framework framework : Framework.values()) {
      Class<?> fixture = LoggingLoaders.fixture(framework);
      if (framework == Framework.LOG4J2) {
        log4j2File = File.createTempFile("argos-log4j2-", ".log");
        call(fixture, "configure", log4j2File.getAbsolutePath());
      } else {
        call(fixture, "configure");
      }
      FIXTURES.put(framework, fixture);
    }
    worker = Executors.newSingleThreadExecutor();
  }

  @AfterAll
  static void cleanUp() throws Exception {
    worker.shutdownNow();
    call(FIXTURES.get(Framework.LOG4J2), "stop");
    if (log4j2File != null) {
      Files.deleteIfExists(log4j2File.toPath());
    }
  }

  @BeforeEach
  void install() throws Exception {
    tracing.install();
    // Whether this Log4j 2 (version and default context map) hands out a mutable map (U-13).
    TraceContext probe = tracing.tracer.begin(io.argos.agent.bootstrap.Kind.HTTP, "probe", 0L);
    try {
      String result = (String) call(FIXTURES.get(Framework.LOG4J2), "probe");
      log4j2Mutable = result.contains("frozen=false");
    } finally {
      tracing.tracer.end(probe, 200, null, null, null, System.nanoTime());
    }
  }

  @AfterEach
  void uninstall() throws Exception {
    for (Class<?> fixture : FIXTURES.values()) {
      call(fixture, "clear");
    }
    tracing.uninstall();
  }

  /** Log4j 2 events carry the keys when the injector's map is mutable (U-13). */
  private static boolean keysExpected(Framework framework) {
    return framework != Framework.LOG4J2 || log4j2KeysExpected();
  }

  static boolean log4j2KeysExpected() {
    return log4j2Mutable;
  }

  @ParameterizedTest(
      name = "T-AG-065 {0}: a log line inside a transaction prints its trace and tx id")
  @EnumSource(Framework.class)
  void plain(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    String message = "plain-" + UUID.randomUUID();
    Transaction tx = tracing.inTransaction(() -> call(fixture, "log", message));
    assertIds(framework, line(fixture, "output", message), tx);
  }

  @ParameterizedTest(
      name =
          "T-AG-065 {0}: the application's MDC.clear() before the log line removes nothing of"
              + " the Argos keys")
  @EnumSource(Framework.class)
  void afterClear(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    String message = "clear-" + UUID.randomUUID();
    Transaction tx =
        tracing.inTransaction(
            () -> {
              call(fixture, "put", "user", "before-clear");
              call(fixture, "clear");
              return call(fixture, "log", message);
            });
    String[] fields = line(fixture, "output", message);
    assertIds(framework, fields, tx);
    assertThat(fields[2]).as("the application's own key is gone after clear()").isEmpty();
  }

  @ParameterizedTest(
      name =
          "T-AG-065 {0}: a log line on another thread that holds the transaction (executor"
              + " propagation) prints that transaction's ids")
  @EnumSource(Framework.class)
  void handedOverThread(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    String message = "async-" + UUID.randomUUID();
    Transaction tx =
        tracing.inTransaction(
            () -> {
              TraceContext ctx = tracing.tracer.current();
              return worker
                  .submit(
                      () -> {
                        Scope scope = tracing.tracer.activate(ctx);
                        try {
                          return call(fixture, "log", message);
                        } finally {
                          scope.close();
                        }
                      })
                  .get(10, TimeUnit.SECONDS);
            });
    assertIds(framework, line(fixture, "output", message), tx);
  }

  @ParameterizedTest(
      name =
          "T-AG-065 {0}: an AsyncAppender formats the event on its worker thread with the ids of"
              + " the transaction that created it")
  @EnumSource(
      value = Framework.class,
      names = {"LOGBACK", "LOG4J1"})
  void asyncAppender(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    String message = "appender-" + UUID.randomUUID();
    Transaction tx = tracing.inTransaction(() -> call(fixture, "logAsync", message));
    assertIds(framework, awaitLine(fixture, message), tx);
  }

  @ParameterizedTest(
      name =
          "T-AG-065 {0}: without a transaction the log line has no Argos keys (nothing is"
              + " added)")
  @EnumSource(Framework.class)
  void noContext(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    String message = "none-" + UUID.randomUUID();
    call(fixture, "log", message);
    String[] fields = line(fixture, "output", message);
    assertThat(fields[0]).as("argos.trace_id without a transaction").isEmpty();
    assertThat(fields[1]).as("argos.tx_id without a transaction").isEmpty();
  }

  @ParameterizedTest(
      name =
          "C-125 (3) {0}: capture.user-id.source mdc:user (plain) records the MDC value of the"
              + " first log event of the transaction")
  @EnumSource(Framework.class)
  void userIdPlain(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    tracing.userId(CaptureConfig.USER_ID_PLAIN, "mdc:user");
    String message = "user-" + UUID.randomUUID();
    Transaction tx =
        tracing.inTransaction(
            () -> {
              call(fixture, "put", "user", "kim\u0007");
              call(fixture, "log", message);
              call(fixture, "put", "user", "lee");
              return call(fixture, "log", message + "-second");
            });
    assertThat(tx.userId()).as("first decided value, control character removed").isEqualTo("kim");
    assertThat(tracing.counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT)).isZero();
  }

  @ParameterizedTest(
      name =
          "C-125 (3) {0}: mdc:user hashed records the salted hash, never the value; a"
              + " transaction without the key counts mdc_absent")
  @EnumSource(Framework.class)
  void userIdHashedAndAbsent(Framework framework) throws Exception {
    Class<?> fixture = FIXTURES.get(framework);
    tracing.userId(CaptureConfig.USER_ID_HASHED, "mdc:user");
    String message = "hashed-" + UUID.randomUUID();
    Transaction tx =
        tracing.inTransaction(
            () -> {
              call(fixture, "put", "user", "kim");
              return call(fixture, "log", message);
            });
    assertThat(tx.userId()).hasSize(16).isNotEqualTo("kim").matches("[A-Za-z0-9_-]{16}");
    call(fixture, "clear");
    Transaction without = tracing.inTransaction(() -> call(fixture, "log", message + "-none"));
    assertThat(without.userId()).isNull();
    assertThat(tracing.counters.userIdSkipped(AgentCounters.USER_ID_SKIPPED_MDC_ABSENT))
        .isEqualTo(1);
  }

  private static void assertIds(Framework framework, String[] fields, Transaction tx) {
    if (!keysExpected(framework)) {
      assertThat(fields[0])
          .as("Log4j 2 with a frozen context map (U-13): no keys are added")
          .isEmpty();
      return;
    }
    assertThat(fields[0]).as("argos.trace_id").isEqualTo(LoggingTracing.traceId(tx));
    assertThat(fields[1]).as("argos.tx_id").isEqualTo(LoggingTracing.txId(tx));
    assertThat(fields[0]).matches("[0-9a-f]{32}");
    assertThat(fields[1]).matches("[0-9a-f]{16}");
  }

  /** The fields of the line of {@code message} in the fixture output {@code output}. */
  private static String[] line(Class<?> fixture, String output, String message) throws Exception {
    String text = (String) call(fixture, output);
    for (String line : text.split("\\r?\\n", -1)) {
      if (line.endsWith("|" + message)) {
        return line.split("\\|", -1);
      }
    }
    throw new AssertionError("no line for " + message + " in:\n" + text);
  }

  private static String[] awaitLine(Class<?> fixture, String message) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (true) {
      String text = (String) call(fixture, "asyncOutput");
      if (text.contains("|" + message) || System.nanoTime() > deadline) {
        return line(fixture, "asyncOutput", message);
      }
      Thread.sleep(20);
    }
  }

  @org.junit.jupiter.api.Test
  @DisplayName("T-AG-065 the agent's helpers print the ids exactly as the core formats them")
  void formatting() throws Exception {
    Transaction tx = tracing.inTransaction(() -> null);
    TraceContext ctx = tx.context();
    assertThat(LoggingHelper.traceId(ctx)).isEqualTo(LoggingTracing.traceId(tx));
    assertThat(LoggingHelper.txId(ctx)).isEqualTo(LoggingTracing.txId(tx));
  }
}
