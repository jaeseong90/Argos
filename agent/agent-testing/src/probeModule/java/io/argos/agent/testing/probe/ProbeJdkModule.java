/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import static io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatchers.named;
import static io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.shaded.net.bytebuddy.description.type.TypeDescription;
import io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatcher;
import java.util.Collections;
import java.util.List;

/**
 * Test-only module of the probe agent jar: instruments a JDK class of the bootstrap class loader
 * ({@code ThreadPoolExecutor.execute}, on the JDK allow-list), so the attach tests exercise
 * retransformation and the JPMS read edge of {@code java.base} (10 §4.4) on JDK 9+. No helpers: the
 * advice uses the bootstrap API only.
 */
public final class ProbeJdkModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.TEST_FIRST + 1;

  @Override
  public String id() {
    return "test-probe-jdk";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.<TypeInstrumentation>singletonList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named("java.util.concurrent.ThreadPoolExecutor");
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(
                named("execute").and(takesArguments(1)), ExecuteAdvice.class.getName());
          }
        });
  }
}
