/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging.fixture;

import java.io.StringWriter;
import org.apache.log4j.AsyncAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;

/**
 * Application code of the logging module tests for Log4j 1.2 and reload4j (the same API), loaded by
 * the advising class loader together with the (advised) Log4j classes: a synchronous appender and
 * Log4j's {@code AsyncAppender}, each writing {@link LogbackFixture#PATTERN} into memory.
 */
public final class Log4j1Fixture {
  private static final StringWriter SYNC = new StringWriter();
  private static final StringWriter ASYNC = new StringWriter();
  private static boolean configured;

  private Log4j1Fixture() {}

  /** The two in-memory appenders (once). */
  public static synchronized void configure() {
    if (configured) {
      return;
    }
    Logger sync = Logger.getLogger("fixture.sync");
    sync.addAppender(new WriterAppender(new PatternLayout(LogbackFixture.PATTERN), SYNC));
    sync.setAdditivity(false);
    sync.setLevel(Level.INFO);
    AsyncAppender async = new AsyncAppender();
    async.addAppender(new WriterAppender(new PatternLayout(LogbackFixture.PATTERN), ASYNC));
    Logger asyncLogger = Logger.getLogger("fixture.async");
    asyncLogger.addAppender(async);
    asyncLogger.setAdditivity(false);
    asyncLogger.setLevel(Level.INFO);
    configured = true;
  }

  public static void log(String message) {
    Logger.getLogger("fixture.sync").info(message);
  }

  public static void logAsync(String message) {
    Logger.getLogger("fixture.async").info(message);
  }

  public static void put(String key, String value) {
    MDC.put(key, value);
  }

  public static void clear() {
    MDC.clear();
  }

  public static String output() {
    synchronized (SYNC) {
      return SYNC.toString();
    }
  }

  public static String asyncOutput() {
    synchronized (ASYNC) {
      return ASYNC.toString();
    }
  }
}
