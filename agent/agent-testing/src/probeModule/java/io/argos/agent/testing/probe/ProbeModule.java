/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.probe;

import static io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatchers.named;
import static io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import io.argos.agent.shaded.net.bytebuddy.description.type.TypeDescription;
import io.argos.agent.shaded.net.bytebuddy.matcher.ElementMatcher;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test-only module of the probe agent jar (WO-02 attach tests): instruments {@code
 * example.trivial.TrivialApp} like a web framework module would. One transaction per {@code
 * Handler.handle}, an SQL step per {@code Store.query}, a helper injected into the application
 * class loader. Compiled against the relocated ByteBuddy names of agent-dist's stage 1 jar.
 */
public final class ProbeModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.TEST_FIRST;

  @Override
  public String id() {
    return "test-probe";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed("example.trivial.TrivialApp");
  }

  /**
   * Test-only initialization fault (T-AG-044, 18 §5.7): with {@code -Dprobe.init-throw=true} this
   * module fails while the agent builds its transformer, i.e. inside the agent's start sequence.
   * Not in the {@code argos.} namespace: the agent's configuration never sees it.
   */
  static final String INIT_THROW_PROPERTY = "probe.init-throw";

  @Override
  public List<TypeInstrumentation> types() {
    if (Boolean.getBoolean(INIT_THROW_PROPERTY)) {
      throw new IllegalStateException("probe.init-throw: injected initialization failure");
    }
    return Arrays.<TypeInstrumentation>asList(
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named("example.trivial.TrivialApp$Handler");
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(
                named("handle").and(takesArguments(1)), HandleAdvice.class.getName());
          }
        },
        new TypeInstrumentation() {
          @Override
          public ElementMatcher<TypeDescription> typeMatcher() {
            return named("example.trivial.TrivialApp$Store");
          }

          @Override
          public void transform(TypeTransformer transformer) {
            transformer.applyAdvice(
                named("query").and(takesArguments(1)), QueryAdvice.class.getName());
          }
        });
  }

  @Override
  public List<String> helperClassNames() {
    return Collections.singletonList(ProbeHelper.class.getName());
  }
}
