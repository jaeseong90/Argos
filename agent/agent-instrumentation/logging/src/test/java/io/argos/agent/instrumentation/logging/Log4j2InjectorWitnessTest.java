/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import static io.argos.agent.instrumentation.logging.LoggingLoaders.call;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.instrumentation.logging.LoggingLoaders.Framework;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 10 §18 U-13: the {@code injectContextData(List, StringMap)} signature of the three {@code
 * ThreadContextDataInjector} implementations of this Log4j 2 version, and, per thread context map
 * configuration (the default of a non-web application, a web application, the garbage-free map),
 * which injector Log4j chooses, whether the map it returns is frozen, and whether a log line inside
 * a transaction prints the Argos keys. Invariant (10 §6.9 as built): the keys are printed exactly
 * when the returned map is mutable. One {@code [wo18-u13]} line per configuration.
 */
@Tag("req:F-A20")
class Log4j2InjectorWitnessTest {
  private final LoggingTracing tracing = new LoggingTracing();

  @BeforeEach
  void install() {
    tracing.install();
  }

  @AfterEach
  void uninstall() {
    tracing.uninstall();
  }

  @ParameterizedTest(name = "U-13 Log4j 2 context map configuration ''{0}''")
  @ValueSource(strings = {"default", "webapp", "garbagefree"})
  void injector(String configuration) throws Exception {
    URL jar =
        org.apache.logging.log4j.core.LoggerContext.class
            .getProtectionDomain()
            .getCodeSource()
            .getLocation();
    for (String name : LoggingModule.LOG4J2_INJECTORS) {
      Class<?> injector = Class.forName(name);
      Method method = null;
      for (Method candidate : injector.getDeclaredMethods()) {
        if (candidate.getName().equals("injectContextData")
            && candidate.getParameterTypes().length == 2
            && !candidate.isBridge()) {
          method = candidate;
        }
      }
      assertThat(method).as("%s.injectContextData(List, StringMap) in %s", name, jar).isNotNull();
      assertThat(method.getParameterTypes()[0].getName()).isEqualTo("java.util.List");
      assertThat(method.getParameterTypes()[1].getName())
          .isEqualTo("org.apache.logging.log4j.util.StringMap");
    }

    String[] properties = {"log4j2.is.webapp", "log4j2.garbagefree.threadContextMap"};
    if (configuration.equals("webapp")) {
      System.setProperty(properties[0], "true");
    } else if (configuration.equals("garbagefree")) {
      System.setProperty(properties[0], "false");
      System.setProperty(properties[1], "true");
    }
    File file = File.createTempFile("argos-u13-", ".log");
    Class<?> fixture = LoggingLoaders.fixture(Framework.LOG4J2);
    try {
      call(fixture, "configure", file.getAbsolutePath());
      String message = "u13-" + UUID.randomUUID();
      TraceContext ctx = tracing.tracer.begin(Kind.HTTP, "GET /u13", System.nanoTime());
      String probe;
      try {
        probe = (String) call(fixture, "probe");
        call(fixture, "log", message);
      } finally {
        tracing.tracer.end(ctx, 200, null, null, null, System.nanoTime());
      }
      String line = "";
      for (String candidate : ((String) call(fixture, "output")).split("\\r?\\n", -1)) {
        if (candidate.endsWith("|" + message)) {
          line = candidate;
        }
      }
      boolean printed = line.startsWith(LoggingHelper.traceId(ctx) + "|");
      boolean mutable = probe.contains("frozen=false");
      System.out.println(
          "[wo18-u13] log4j-core "
              + jar.getPath().replaceAll(".*/", "")
              + " "
              + configuration
              + ": "
              + probe
              + " printed="
              + printed);
      assertThat(line).as("the log line of %s", message).isNotEmpty();
      assertThat(probe).contains("keys=" + mutable);
      assertThat(printed)
          .as("keys printed exactly when the injector's map is mutable (%s)", probe)
          .isEqualTo(mutable);
      if (configuration.equals("garbagefree")) {
        assertThat(mutable).as("the garbage-free injector fills the event's own map").isTrue();
      }
    } finally {
      call(fixture, "stop");
      for (String property : properties) {
        System.clearProperty(property);
      }
      Files.deleteIfExists(file.toPath());
    }
  }
}
