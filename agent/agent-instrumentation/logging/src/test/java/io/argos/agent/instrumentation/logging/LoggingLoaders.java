/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import java.util.concurrent.Callable;

/**
 * Advising class loaders of the logging module tests: each one defines a fresh copy of a logging
 * library (advised exactly as the agent does at load time), the module's helpers and the fixture of
 * that library. Library code runs with the loader as the thread's context class loader (Log4j 2
 * loads its plugins and providers through it).
 */
final class LoggingLoaders {
  static final String FIXTURES = "io.argos.agent.instrumentation.logging.fixture.";

  private LoggingLoaders() {}

  /** The library families of 10 §6.9 (Log4j 1 is Log4j 1.2.17 or reload4j, by test task). */
  enum Framework {
    LOGBACK("LogbackFixture", "ch.qos.logback.", "org.slf4j."),
    LOG4J2("Log4j2Fixture", "org.apache.logging.log4j."),
    LOG4J1("Log4j1Fixture", "org.apache.log4j.");

    final String fixture;

    /** Child-first package prefixes, comma separated (an enum keeps no mutable state). */
    final String packages;

    Framework(String fixture, String... packages) {
      this.fixture = fixture;
      this.packages = String.join(",", packages);
    }
  }

  /** A new advising loader for {@code framework} and its fixture class. */
  static Class<?> fixture(Framework framework) throws ClassNotFoundException {
    String[] packages = framework.packages.split(",", -1);
    String[] childFirst = new String[packages.length + 5];
    int i = 0;
    for (String prefix : packages) {
      childFirst[i++] = prefix;
    }
    childFirst[i++] = LoggingHelper.class.getName();
    childFirst[i++] = LogbackHelper.class.getName();
    childFirst[i++] = Log4j2Helper.class.getName();
    childFirst[i++] = Log4j1Helper.class.getName();
    childFirst[i] = FIXTURES;
    AdvisingLoader loader =
        new AdvisingLoader(LoggingLoaders.class.getClassLoader(), new LoggingModule(), childFirst);
    return loader.loadClass(FIXTURES + framework.fixture);
  }

  /** Runs {@code body} with the fixture's loader as the context class loader of this thread. */
  static <T> T with(Class<?> fixture, Callable<T> body) throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(fixture.getClassLoader());
    try {
      return body.call();
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /** {@link LoggingTracing#call} of {@code fixture} under its loader. */
  static Object call(Class<?> fixture, String method, Object... args) throws Exception {
    return with(fixture, () -> LoggingTracing.call(fixture, method, args));
  }
}
