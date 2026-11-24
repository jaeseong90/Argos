/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging.fixture;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Application code of the logging module tests for Logback through SLF4J, loaded by the advising
 * class loader together with the (advised) Logback classes: a synchronous appender and an {@code
 * AsyncAppender}, each writing {@link #PATTERN} into memory.
 */
public final class LogbackFixture {
  /** The trace id, transaction id and {@code user} MDC values, then the message. */
  public static final String PATTERN = "%X{argos.trace_id}|%X{argos.tx_id}|%X{user}|%m%n";

  private static final ByteArrayOutputStream SYNC = new ByteArrayOutputStream();
  private static final ByteArrayOutputStream ASYNC = new ByteArrayOutputStream();
  private static boolean configured;

  private LogbackFixture() {}

  /** Replaces the default configuration with the two in-memory appenders (once). */
  public static synchronized void configure() {
    if (configured) {
      return;
    }
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.reset();
    Logger sync = context.getLogger("fixture.sync");
    sync.addAppender(appender(context, "sync", SYNC));
    sync.setAdditive(false);
    sync.setLevel(Level.INFO);
    AsyncAppender async = new AsyncAppender();
    async.setContext(context);
    async.setName("async");
    async.addAppender(appender(context, "async-target", ASYNC));
    async.start();
    Logger asyncLogger = context.getLogger("fixture.async");
    asyncLogger.addAppender(async);
    asyncLogger.setAdditive(false);
    asyncLogger.setLevel(Level.INFO);
    configured = true;
  }

  private static OutputStreamAppender<ILoggingEvent> appender(
      LoggerContext context, String name, ByteArrayOutputStream out) {
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(PATTERN);
    encoder.start();
    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<ILoggingEvent>();
    appender.setContext(context);
    appender.setName(name);
    appender.setEncoder(encoder);
    appender.setOutputStream(out);
    appender.start();
    return appender;
  }

  public static void log(String message) {
    LoggerFactory.getLogger("fixture.sync").info(message);
  }

  public static void logAsync(String message) {
    LoggerFactory.getLogger("fixture.async").info(message);
  }

  public static void put(String key, String value) {
    MDC.put(key, value);
  }

  public static void clear() {
    MDC.clear();
  }

  public static String output() {
    return new String(SYNC.toByteArray(), StandardCharsets.UTF_8);
  }

  public static String asyncOutput() {
    return new String(ASYNC.toByteArray(), StandardCharsets.UTF_8);
  }
}
