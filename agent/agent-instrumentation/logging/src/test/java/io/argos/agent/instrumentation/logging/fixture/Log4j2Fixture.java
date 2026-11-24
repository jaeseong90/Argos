/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.ContextDataInjector;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;
import org.apache.logging.log4j.core.impl.ContextDataInjectorFactory;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;

/**
 * Application code of the logging module tests for Log4j 2, loaded by the advising class loader
 * together with the (advised) Log4j classes: one logger writing {@link LogbackFixture#PATTERN} to a
 * file appender with immediate flush. Each advising loader is a fresh Log4j 2 (its own {@code
 * ThreadContext} map type, chosen from the system properties at first use).
 */
public final class Log4j2Fixture {
  private static LoggerContext context;
  private static Path file;

  private Log4j2Fixture() {}

  /** Configures the file appender writing to {@code path} (once per loader). */
  public static synchronized void configure(String path) {
    if (context != null) {
      return;
    }
    file = Paths.get(path);
    ConfigurationBuilder<BuiltConfiguration> builder =
        ConfigurationBuilderFactory.newConfigurationBuilder();
    builder.setStatusLevel(Level.WARN);
    builder.setConfigurationName("argos-logging-fixture");
    builder.add(
        builder
            .newAppender("file", "File")
            .addAttribute("fileName", path)
            .addAttribute("append", false)
            .addAttribute("immediateFlush", true)
            .add(
                builder
                    .newLayout("PatternLayout")
                    .addAttribute("pattern", LogbackFixture.PATTERN)));
    builder.add(
        builder
            .newLogger("fixture", Level.INFO)
            .add(builder.newAppenderRef("file"))
            .addAttribute("additivity", false));
    builder.add(builder.newRootLogger(Level.ERROR));
    context = Configurator.initialize(builder.build());
  }

  public static void log(String message) {
    context.getLogger("fixture.sync").info(message);
  }

  public static void put(String key, String value) {
    ThreadContext.put(key, value);
  }

  public static void clear() {
    ThreadContext.clearMap();
  }

  /** Stops the logger context (releases the file). */
  public static synchronized void stop() {
    if (context != null) {
      context.stop();
    }
  }

  public static String output() throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  /**
   * 10 §18 U-13: the injector Log4j chose, the thread context map type, and what {@code
   * injectContextData(null, new map)} returns on this thread: {@code <injector>|<context
   * map>|frozen=<b>|argument=<b>|keys=<b>}.
   */
  @SuppressWarnings("ReferenceEquality") // identity: is the result the argument map itself
  public static String probe() {
    ContextDataInjector injector = ContextDataInjectorFactory.createInjector();
    StringMap argument = new SortedArrayStringMap();
    StringMap result = injector.injectContextData(null, argument);
    return injector.getClass().getName()
        + "|"
        + (ThreadContext.getThreadContextMap() == null
            ? "(not read-only: DefaultThreadContextMap)"
            : ThreadContext.getThreadContextMap().getClass().getName())
        + "|frozen="
        + result.isFrozen()
        + "|argument="
        + (result == argument)
        + "|keys="
        + (result.getValue("argos.trace_id") != null);
  }
}
