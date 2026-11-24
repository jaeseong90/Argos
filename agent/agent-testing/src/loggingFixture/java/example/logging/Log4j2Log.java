/* SPDX-License-Identifier: Apache-2.0 */
package example.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/** Log4j 2: a console appender on standard output with {@link FixtureLog#PATTERN}. */
final class Log4j2Log implements FixtureLog {
  private final Logger logger;

  Log4j2Log() {
    ConfigurationBuilder<BuiltConfiguration> builder =
        ConfigurationBuilderFactory.newConfigurationBuilder();
    builder.setStatusLevel(Level.WARN);
    builder.setConfigurationName("logging-fixture");
    builder.add(
        builder
            .newAppender("console", "Console")
            .addAttribute("target", "SYSTEM_OUT")
            .add(builder.newLayout("PatternLayout").addAttribute("pattern", PATTERN)));
    builder.add(
        builder
            .newLogger("fixture", Level.INFO)
            .add(builder.newAppenderRef("console"))
            .addAttribute("additivity", false));
    builder.add(builder.newRootLogger(Level.WARN));
    LoggerContext context = Configurator.initialize(builder.build());
    logger = context.getLogger("fixture");
  }

  @Override
  public void info(String message) {
    logger.info(message);
  }

  @Override
  public void put(String key, String value) {
    ThreadContext.put(key, value);
  }

  @Override
  public void remove(String key) {
    ThreadContext.remove(key);
  }

  @Override
  public void clear() {
    ThreadContext.clearMap();
  }
}
