/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SchedulerModule: identity, helpers, witness, the type and method matchers on the real library
 * class files (described from the class path with a type pool: no library class is loaded here, the
 * advice tests need them loaded after the install), and the user class rule of WO-18 assumption
 * A-1.
 */
class SchedulerModuleTest {
  private final SchedulerModule module = new SchedulerModule();
  private final TypePool pool = TypePool.Default.of(getClass().getClassLoader());

  @Test
  void identity() {
    assertThat(module.id()).isEqualTo("scheduler");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.SCHEDULER);
    assertThat(module.helperClassNames())
        .containsExactly(
            "io.argos.agent.instrumentation.scheduler.SchedulerHelper",
            "io.argos.agent.instrumentation.scheduler.ScheduledHelper",
            "io.argos.agent.instrumentation.scheduler.QuartzHelper",
            "io.argos.agent.instrumentation.scheduler.BatchStepHelper");
    assertThat(HelperClasses.problems(module)).isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(3);
  }

  @Test
  @DisplayName("witness: a loader with Spring, Quartz or Spring Batch, not an empty one")
  void witness() throws Exception {
    assertThat(module.witness().matches(getClass().getClassLoader())).isTrue();
    try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
      assertThat(module.witness().matches(empty)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "matchers: ScheduledMethodRunnable.run(), execute(JobExecutionContext) of Job"
          + " implementations (not the interface), AbstractStep.execute(StepExecution)")
  void matchers() {
    List<TypeInstrumentation> rules = module.types();
    assertThat(rules).hasSize(3);

    TypeInstrumentation scheduled = rules.get(0);
    assertThat(matches(scheduled, SchedulerModule.SCHEDULED_METHOD_RUNNABLE)).isTrue();
    assertThat(matches(scheduled, "org.springframework.scheduling.support.CronTrigger")).isFalse();
    assertThat(adviceOf(scheduled)).isEqualTo(ScheduledRunAdvice.class.getName());
    assertThat(methodMatches(scheduled, SchedulerModule.SCHEDULED_METHOD_RUNNABLE, "run")).isTrue();
    assertThat(methodMatches(scheduled, SchedulerModule.SCHEDULED_METHOD_RUNNABLE, "toString"))
        .isFalse();

    TypeInstrumentation quartz = rules.get(1);
    assertThat(matches(quartz, "com.example.jobs.ReportJob")).isTrue();
    assertThat(matches(quartz, "com.example.jobs.DelegatingJob")).isTrue();
    assertThat(matches(quartz, SchedulerModule.QUARTZ_JOB)).as("the interface").isFalse();
    assertThat(matches(quartz, "com.example.jobs.PlainJobs")).isFalse();
    assertThat(adviceOf(quartz)).isEqualTo(QuartzExecuteAdvice.class.getName());
    assertThat(methodMatches(quartz, "com.example.jobs.ReportJob", "execute")).isTrue();

    TypeInstrumentation steps = rules.get(2);
    assertThat(matches(steps, SchedulerModule.BATCH_ABSTRACT_STEP)).isTrue();
    assertThat(matches(steps, "org.springframework.batch.core.step.tasklet.TaskletStep"))
        .as("subclasses inherit the final execute; only AbstractStep is transformed")
        .isFalse();
    assertThat(adviceOf(steps)).isEqualTo(BatchStepExecuteAdvice.class.getName());
    assertThat(methodMatches(steps, SchedulerModule.BATCH_ABSTRACT_STEP, "execute")).isTrue();
    assertThat(methodMatches(steps, SchedulerModule.BATCH_ABSTRACT_STEP, "doExecute")).isFalse();
  }

  @Test
  @DisplayName(
      "A-1 user class: CGLIB subclasses cut before '$$', JDK proxies replaced by the declaring"
          + " class, plain classes unchanged")
  void userClassName() {
    assertThat(SchedulerHelper.userClassName("com.shop.Jobs$$SpringCGLIB$$0", "com.shop.Jobs"))
        .isEqualTo("com.shop.Jobs");
    assertThat(
            SchedulerHelper.userClassName(
                "com.shop.Jobs$$EnhancerBySpringCGLIB$$1a2b3c", "com.shop.BaseJobs"))
        .isEqualTo("com.shop.Jobs");
    assertThat(SchedulerHelper.userClassName("jdk.proxy2.$Proxy42", "com.shop.JobApi"))
        .isEqualTo("com.shop.JobApi");
    assertThat(SchedulerHelper.userClassName("com.sun.proxy.$Proxy7", "com.shop.JobApi"))
        .isEqualTo("com.shop.JobApi");
    assertThat(SchedulerHelper.userClassName("com.shop.Jobs$Inner", "com.shop.Base"))
        .isEqualTo("com.shop.Jobs$Inner");
    assertThat(SchedulerHelper.userClassName(null, "com.shop.Jobs")).isEqualTo("com.shop.Jobs");
  }

  private boolean matches(TypeInstrumentation rule, String typeName) {
    return rule.typeMatcher().matches(pool.describe(typeName).resolve());
  }

  private static String adviceOf(TypeInstrumentation rule) {
    final List<String> advice = new ArrayList<String>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methods, String adviceClassName) {
            advice.add(adviceClassName);
          }
        });
    assertThat(advice).hasSize(1);
    return advice.get(0);
  }

  private boolean methodMatches(TypeInstrumentation rule, String typeName, String methodName) {
    final List<ElementMatcher<? super MethodDescription>> matchers =
        new ArrayList<ElementMatcher<? super MethodDescription>>();
    rule.transform(
        new TypeTransformer() {
          @Override
          public void applyAdvice(
              ElementMatcher<? super MethodDescription> methods, String adviceClassName) {
            matchers.add(methods);
          }
        });
    TypeDescription type = pool.describe(typeName).resolve();
    for (MethodDescription method : type.getDeclaredMethods()) {
      if (method.getName().equals(methodName) && matchers.get(0).matches(method)) {
        return true;
      }
    }
    return false;
  }
}
