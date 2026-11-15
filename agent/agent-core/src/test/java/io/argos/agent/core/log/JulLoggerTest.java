/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The bridge that replaces {@code java.util.logging.Logger} in shaded libraries (ADR-0005). */
@Tag("req:F-A35")
@Tag("req:SEC-06")
class JulLoggerTest {
  @Test
  @DisplayName("ADR-0005 decision 4: SEVERE, WARNING and INFO are loggable, FINE only with debug")
  void levels() {
    JulLogger logger = JulLogger.getLogger("lib");
    assertThat(logger.getName()).isEqualTo("lib");
    assertThat(logger.getLevel()).isNull();
    logger.setLevel(Level.ALL);
    assertThat(logger.isLoggable(Level.SEVERE)).isTrue();
    assertThat(logger.isLoggable(Level.WARNING)).isTrue();
    assertThat(logger.isLoggable(Level.INFO)).isTrue();
    assertThat(logger.isLoggable(Level.FINE)).isEqualTo(AgentLogger.isDebugEnabled());
    assertThat(logger.isLoggable(Level.FINER)).isFalse();
    assertThat(logger.isLoggable(Level.FINEST)).isFalse();
    assertThat(logger.isLoggable(Level.OFF)).isFalse();
    assertThat(logger.isLoggable(null)).isFalse();
    assertThat(JulLogger.getLogger("lib", "bundle").getName()).isEqualTo("lib");
  }

  @Test
  @DisplayName("message formats follow java.text.MessageFormat, bad patterns stay as they are")
  void format() {
    assertThat(JulLogger.format("a {0} b {1}", new Object[] {"x", 2})).isEqualTo("a x b 2");
    assertThat(JulLogger.format("plain", null)).isEqualTo("plain");
    assertThat(JulLogger.format("no args {0}", new Object[0])).isEqualTo("no args {0}");
    assertThat(JulLogger.format(null, new Object[] {1})).isNull();
    assertThat(JulLogger.format("bad {x}", new Object[] {1})).isEqualTo("bad {x}");
    assertThat(JulLogger.format("nothing to fill", new Object[] {1})).isEqualTo("nothing to fill");
  }

  @Test
  @DisplayName(
      "T-SEC-06d gRPC's frame logger stays off at FINE even with log.level=debug: it dumps headers"
          + " with the token and message bytes; its INFO and other loggers' FINE stay")
  void frameLogger() {
    LogPipeline pipeline = AgentLogger.pipeline();
    LogLevel before = pipeline.level();
    pipeline.setLevel(LogLevel.DEBUG);
    try {
      JulLogger frames =
          JulLogger.getLogger("io.argos.agent.shaded.io.grpc.netty.NettyClientHandler");
      assertThat(frames.isLoggable(Level.FINE)).isFalse();
      assertThat(frames.isLoggable(Level.INFO)).isTrue();
      assertThat(JulLogger.getLogger("io.grpc.netty.Other").isLoggable(Level.FINE)).isTrue();
    } finally {
      pipeline.setLevel(before);
    }
  }

  @Test
  @DisplayName("T-SEC-06d Bearer values of any format and argos_ runs of any length are masked")
  void masks() {
    assertThat(JulLogger.mask("authorization: Bearer abc-123 end"))
        .isEqualTo("authorization: Bearer argos_**** end");
    assertThat(JulLogger.mask("headers=[authorization: bearer argos_short]"))
        .doesNotContain("argos_short");
    assertThat(JulLogger.mask("cut argos_abcdefghij")).isEqualTo("cut argos_****");
    assertThat(JulLogger.mask("nothing here")).isEqualTo("nothing here");
  }

  @Test
  @DisplayName(
      "every method the shaded libraries call runs without touching the JDK LogManager handlers")
  void everyMethodRuns() {
    JulLogger logger = JulLogger.getLogger("lib");
    RuntimeException error = new RuntimeException("boom");
    logger.severe("s");
    logger.warning("w");
    logger.info("i");
    logger.config("c");
    logger.fine("f");
    logger.finer("fr");
    logger.finest("ft");
    logger.log(Level.INFO, "m");
    logger.log(Level.INFO, "m {0}", "p");
    logger.log(Level.INFO, "m {0}", new Object[] {"p"});
    logger.log(Level.WARNING, "m", error);
    logger.log(Level.SEVERE, error, () -> "supplied");
    logger.log(Level.INFO, () -> "supplied");
    logger.log(Level.FINEST, () -> "never evaluated at this level");
    logger.logp(Level.INFO, "C", "m", "x");
    logger.logp(Level.INFO, "C", "m", "x {0}", "p");
    logger.logp(Level.INFO, "C", "m", "x {0}", new Object[] {"p"});
    logger.logp(Level.WARNING, "C", "m", "x", error);
    LogRecord record = new LogRecord(Level.INFO, "r {0}");
    record.setParameters(new Object[] {"p"});
    logger.log(record);
    logger.log((LogRecord) null);
  }

  @Test
  @DisplayName(
      "10 §14: a library warning repeated with the same template is written once per minute; the"
          + " repeats are counted in argos_agent_library_log_suppressed_total, without a stack")
  void warningsOncePerMinute() {
    JulLogger logger = JulLogger.getLogger("lib.repeats." + System.nanoTime());
    RuntimeException error = new RuntimeException("connect failed");
    long suppressedBefore = JulLogger.suppressedTotal();
    try (LogCapture log = LogCapture.start("INFO")) {
      for (int i = 0; i < 25; i++) {
        logger.log(Level.WARNING, "[{0}] connection to {1} failed", new Object[] {i, "host"});
        logger.log(Level.WARNING, "transport failed", error);
      }
      logger.log(Level.INFO, "info lines are not limited");
      logger.log(Level.INFO, "info lines are not limited");
      List<String> warnings = log.linesAt("WARN");
      assertThat(warnings).hasSize(2);
      assertThat(warnings.get(0)).contains("[0] connection to host failed");
      assertThat(log.text().split("RuntimeException: connect failed", -1))
          .as("one stack trace, the first line's")
          .hasSize(2);
      assertThat(log.linesAt("INFO")).hasSize(2);
    }
    assertThat(JulLogger.suppressedTotal() - suppressedBefore).isEqualTo(48);
  }

  @Test
  @DisplayName(
      "10 §14: the window, the count of suppressed repeats on the next line, and the bounded key"
          + " set of the limiter")
  void limiterWindowAndBound() {
    final long[] now = {0};
    LibraryLogLimiter limiter =
        new LibraryLogLimiter(
            new java.util.function.LongSupplier() {
              @Override
              public long getAsLong() {
                return now[0];
              }
            });
    assertThat(limiter.admit("a")).isZero();
    assertThat(limiter.admit("a")).isEqualTo(-1);
    assertThat(limiter.admit("a")).isEqualTo(-1);
    now[0] += LibraryLogLimiter.WINDOW_NANOS - 1;
    assertThat(limiter.admit("a")).as("still inside the minute").isEqualTo(-1);
    now[0] += 1;
    assertThat(limiter.admit("a")).as("next minute: written with 3 repeats").isEqualTo(3);
    assertThat(limiter.suppressed()).isEqualTo(3);

    for (int i = 0; i < 1_000; i++) {
      now[0] += 1;
      limiter.admit("key-" + i);
    }
    assertThat(limiter.keys()).isLessThanOrEqualTo(LibraryLogLimiter.MAX_KEYS);
  }

  @Test
  @DisplayName(
      "gRPC's 'Failed to resolve name' warning is DEBUG (the agent's own connection WARN says it"
          + " once per minute; its status carries a whole stack trace)")
  void resolveFailureIsDebug() {
    JulLogger channel =
        JulLogger.getLogger("io.argos.agent.shaded.io.grpc.internal.ManagedChannelImpl");
    Object[] parameters = {"Channel<1>: (collector.invalid:9100)", "Status{code=UNAVAILABLE}"};
    try (LogCapture log = LogCapture.start("INFO")) {
      channel.log(Level.WARNING, "[{0}] Failed to resolve name. status={1}", parameters);
      assertThat(log.lines()).isEmpty();
    }
    try (LogCapture log = LogCapture.start("DEBUG")) {
      channel.log(Level.WARNING, "[{0}] Failed to resolve name. status={1}", parameters);
      assertThat(log.linesAt("DEBUG")).hasSize(1);
      assertThat(log.linesAt("WARN")).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-06f the text of a logged throwable (message and causes) is masked too: Bearer values"
          + " and argos_ runs never reach the log in clear")
  void throwableTextMasked() {
    StringBuilder token = new StringBuilder("argos_");
    for (int i = 0; i < 43; i++) {
      token.append('k');
    }
    RuntimeException cause =
        new RuntimeException("upstream said authorization: Bearer " + token + " is bad");
    IllegalStateException error =
        new IllegalStateException("call with " + token + " failed", cause);
    try (LogCapture log = LogCapture.start("INFO")) {
      JulLogger.getLogger("lib.thrown." + System.nanoTime())
          .log(Level.WARNING, "transport failed", error);
      AgentLogger.warn("agent side: {}", "x", error);
      String text = log.text();
      assertThat(text).doesNotContain(token.toString()).doesNotContain("argos_kkk");
      assertThat(text).contains("call with argos_**** failed");
      assertThat(text).contains("Caused by: java.lang.RuntimeException: upstream said");
      assertThat(text).contains("Bearer argos_****");
    }
  }

  @Test
  @DisplayName(
      "T-SEC-06d a token of the 16 §2.1 format in a library message is masked before it queues")
  void tokenMasked() throws Exception {
    LogPipeline pipeline = AgentLogger.pipeline();
    StringBuilder token = new StringBuilder("argos_");
    for (int i = 0; i < 43; i++) {
      token.append('t');
    }
    AgentLoggerTest.ListSink sink = new AgentLoggerTest.ListSink();
    LogSink before = pipeline.sink();
    pipeline.setSink(sink);
    try {
      JulLogger.getLogger("lib").warning("value " + token + " end");
      pipeline.drain();
    } finally {
      pipeline.setSink(before);
    }
    assertThat(sink.lines).isNotEmpty();
    assertThat(String.join("\n", sink.lines))
        .doesNotContain(token.toString())
        .contains("[lib] value argos_**** end");
  }
}
