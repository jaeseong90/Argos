/* SPDX-License-Identifier: Apache-2.0 */
package example.logging;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.PatternLayout;

/**
 * Log4j 1.2 or reload4j (the same API, the jar's class path decides): a console appender on
 * standard output with {@link FixtureLog#PATTERN}.
 */
final class Log4j1Log implements FixtureLog {
  private final Logger logger;

  Log4j1Log() {
    logger = Logger.getLogger("fixture");
    logger.addAppender(new ConsoleAppender(new PatternLayout(PATTERN), ConsoleAppender.SYSTEM_OUT));
    logger.setAdditivity(false);
    logger.setLevel(Level.INFO);
  }

  @Override
  public void info(String message) {
    logger.info(message);
  }

  @Override
  public void put(String key, String value) {
    MDC.put(key, value);
  }

  @Override
  public void remove(String key) {
    MDC.remove(key);
  }

  @Override
  public void clear() {
    MDC.clear();
  }
}
