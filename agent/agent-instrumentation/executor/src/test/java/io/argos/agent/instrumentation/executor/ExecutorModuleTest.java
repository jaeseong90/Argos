/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.Test;

/** ExecutorModule: identity, no helpers (bootstrap loader), matchers against the real JDK class. */
class ExecutorModuleTest {
  private final ExecutorModule module = new ExecutorModule();

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("executor");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.EXECUTOR);
    assertThat(module.helperClassNames()).as("JDK class: no helper").isEmpty();
    assertThat(module.libraryVersionRange()).isNotEmpty();
  }

  @Test
  void matchesThreadPoolExecutorOnly() throws Exception {
    TypeInstrumentation rule = module.types().get(0);
    assertThat(
            rule.typeMatcher().matches(TypeDescription.ForLoadedType.of(ThreadPoolExecutor.class)))
        .isTrue();
    assertThat(
            rule.typeMatcher()
                .matches(TypeDescription.ForLoadedType.of(ScheduledThreadPoolExecutor.class)))
        .isFalse();

    final List<ElementMatcher<? super MethodDescription>> methods =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    final List<String> advice = new ArrayList<String>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methodMatcher, String adviceClassName) {
            methods.add(methodMatcher);
            advice.add(adviceClassName);
          }
        });
    assertThat(advice)
        .containsExactly(
            ExecuteAdvice.class.getName(),
            BeforeExecuteAdvice.class.getName(),
            AfterExecuteAdvice.class.getName());
    assertThat(methods.get(0).matches(method("execute", Runnable.class))).isTrue();
    assertThat(methods.get(1).matches(method("beforeExecute", Thread.class, Runnable.class)))
        .isTrue();
    assertThat(methods.get(2).matches(method("afterExecute", Runnable.class, Throwable.class)))
        .isTrue();
    assertThat(methods.get(0).matches(method("remove", Runnable.class))).isFalse();
  }

  private static MethodDescription method(String name, Class<?>... parameters)
      throws NoSuchMethodException {
    return new MethodDescription.ForLoadedMethod(
        ThreadPoolExecutor.class.getDeclaredMethod(name, parameters));
  }
}
