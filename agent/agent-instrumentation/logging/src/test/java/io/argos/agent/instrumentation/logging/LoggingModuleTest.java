/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** LoggingModule: identity, helper list, witness, named matchers on the real library classes. */
class LoggingModuleTest {
  private final LoggingModule module = new LoggingModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("logging");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.LOGGING);
    assertThat(module.helperClassNames())
        .containsExactly(
            LoggingHelper.class.getName(),
            LogbackHelper.class.getName(),
            Log4j2Helper.class.getName(),
            Log4j1Helper.class.getName());
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(3);
  }

  @Test
  @DisplayName("witness: a loader that sees one of the logging libraries, not an empty one")
  void witness() throws Exception {
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "library under test: Logback 1.2 / Log4j 2.17 / Log4j 1.2.17 in `test`, Logback 1.5 / Log4j"
          + " 2.25 / reload4j in `testModern`")
  void librariesUnderTest() throws Exception {
    String logback = location("ch.qos.logback.classic.spi.LoggingEvent");
    String log4j2 = location("org.apache.logging.log4j.core.impl.ThreadContextDataInjector");
    String log4j1 = location("org.apache.log4j.spi.LoggingEvent");
    System.out.println("[wo18-libraries] " + logback + " " + log4j2 + " " + log4j1);
    if (System.getProperty("java.specification.version").equals("1.8")) {
      assertThat(logback).contains("logback-classic-1.2.");
      assertThat(log4j2).contains("log4j-core-2.17.");
      assertThat(log4j1).contains("log4j-1.2.17");
    } else {
      assertThat(logback).contains("logback-classic-1.5.");
      assertThat(log4j2).contains("log4j-core-2.25.");
      assertThat(log4j1).contains("reload4j-1.2.");
    }
  }

  private static String location(String className) throws ClassNotFoundException {
    return Class.forName(className)
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .getPath()
        .replaceAll(".*/", "");
  }

  @Test
  @DisplayName(
      "named matching: Logback's LoggingEvent (getMDCPropertyMap/getMdc, prepareForDeferredProcessing,"
          + " constructors with arguments), the three Log4j 2 injectors (injectContextData), Log4j"
          + " 1's LoggingEvent constructors; nothing else")
  void matchers() throws Exception {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(3);
    Class<?> logback = Class.forName(LoggingModule.LOGBACK_EVENT);
    assertThat(rules.get(0).typeMatcher().matches(TypeDescription.ForLoadedType.of(logback)))
        .isTrue();
    assertThat(
            rules
                .get(0)
                .typeMatcher()
                .matches(
                    TypeDescription.ForLoadedType.of(
                        Class.forName("ch.qos.logback.classic.spi.LoggingEventVO"))))
        .isFalse();
    List<ElementMatcher<? super MethodDescription>> logbackMethods = methods(rules.get(0));
    assertThat(logbackMethods).hasSize(3);
    assertThat(matches(logbackMethods.get(0), logback.getMethod("getMDCPropertyMap"))).isTrue();
    assertThat(matches(logbackMethods.get(0), logback.getMethod("getMdc"))).isTrue();
    assertThat(matches(logbackMethods.get(0), logback.getMethod("getMessage"))).isFalse();
    assertThat(matches(logbackMethods.get(1), logback.getMethod("prepareForDeferredProcessing")))
        .isTrue();
    int withArguments = 0;
    for (Constructor<?> constructor : logback.getConstructors()) {
      boolean matched =
          logbackMethods.get(2).matches(new MethodDescription.ForLoadedConstructor(constructor));
      assertThat(matched).isEqualTo(constructor.getParameterTypes().length > 0);
      withArguments += matched ? 1 : 0;
    }
    assertThat(withArguments).isPositive();

    for (String name : LoggingModule.LOG4J2_INJECTORS) {
      Class<?> injector = Class.forName(name);
      assertThat(rules.get(1).typeMatcher().matches(TypeDescription.ForLoadedType.of(injector)))
          .as(name)
          .isTrue();
      int matched = 0;
      for (Method method : injector.getDeclaredMethods()) {
        if (methods(rules.get(1)).get(0).matches(new MethodDescription.ForLoadedMethod(method))) {
          assertThat(method.getName()).isEqualTo("injectContextData");
          matched++;
        }
      }
      assertThat(matched).as("injectContextData of %s", name).isPositive();
    }

    Class<?> log4j1 = Class.forName(LoggingModule.LOG4J1_EVENT);
    assertThat(rules.get(2).typeMatcher().matches(TypeDescription.ForLoadedType.of(log4j1)))
        .isTrue();
    assertThat(
            methods(rules.get(2))
                .get(0)
                .matches(new MethodDescription.ForLoadedConstructor(log4j1.getConstructors()[0])))
        .isTrue();
  }

  private static boolean matches(ElementMatcher<? super MethodDescription> matcher, Method method) {
    return matcher.matches(new MethodDescription.ForLoadedMethod(method));
  }

  private static List<ElementMatcher<? super MethodDescription>> methods(TypeInstrumentation rule) {
    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> matcher, String adviceClassName) {
            methods.add(matcher);
          }
        });
    return methods;
  }
}
