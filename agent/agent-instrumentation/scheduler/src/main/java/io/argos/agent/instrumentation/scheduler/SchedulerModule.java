/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import static io.argos.agent.instrumentation.api.SafeMatchers.safeImplements;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.argos.agent.instrumentation.api.HelperClasses;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import io.argos.agent.instrumentation.api.TypeTransformer;
import io.argos.agent.instrumentation.api.Witness;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Non-HTTP entry points as BATCH transactions (10 §6.8, F-A24, D-20):
 *
 * <ul>
 *   <li>Spring {@code ScheduledMethodRunnable.run()} (every {@code @Scheduled} method): {@code
 *       BATCH <class>#<method>}, the class being the user class (a CGLIB subclass name is cut
 *       before {@code $$}, a JDK proxy is replaced by the method's declaring class; WO-18
 *       assumption A-1) so that proxies do not add service names;
 *   <li>Quartz {@code Job.execute(JobExecutionContext)} of every class that implements {@code
 *       org.quartz.Job} ({@code QuartzJobBean} included): {@code BATCH <group>.<name>} of the job
 *       key;
 *   <li>Spring Batch {@code AbstractStep.execute(StepExecution)}: {@code BATCH
 *       <jobName>.<stepName>}, one transaction per step execution; a chunk step with more steps
 *       than {@code sampling.profile.max-steps} is folded by the tracer's summary mode (10 §9.1).
 * </ul>
 *
 * <p>Common rule ({@link SchedulerHelper}): the transaction begins only when the thread has none;
 * inside another transaction (a job started by an HTTP request, a Spring Batch job run by a Quartz
 * job) the entry point becomes a METHOD step named after the same text without {@code BATCH }. A
 * nested call of the same entry point inside its own transaction (a {@code Job} delegating to
 * another {@code execute} of the same job, an override calling {@code super.execute}) adds nothing.
 * The service name is fixed. Exceptions and return values are never changed; a thrown exception or
 * a FAILED step execution marks the transaction (or step) as an error.
 *
 * <p>Library types appear only in the helpers ({@link ScheduledHelper}, {@link QuartzHelper},
 * {@link BatchStepHelper}); the advice receives them as {@code Object} (ADV-04b).
 */
public final class SchedulerModule implements InstrumentationModule {
  public static final int MODULE_ID = ModuleIds.SCHEDULER;

  static final String SCHEDULED_METHOD_RUNNABLE =
      "org.springframework.scheduling.support.ScheduledMethodRunnable";
  static final String QUARTZ_JOB = "org.quartz.Job";
  static final String QUARTZ_JOB_EXECUTION_CONTEXT = "org.quartz.JobExecutionContext";
  static final String BATCH_ABSTRACT_STEP = "org.springframework.batch.core.step.AbstractStep";
  static final String BATCH_STEP_EXECUTION = "org.springframework.batch.core.StepExecution";

  @Override
  public String id() {
    return "scheduler";
  }

  @Override
  public int moduleId() {
    return MODULE_ID;
  }

  /** A loader that sees at least one of the three libraries. */
  @Override
  public ElementMatcher<ClassLoader> witness() {
    return Witness.hasClassesNamed(SCHEDULED_METHOD_RUNNABLE)
        .or(Witness.hasClassesNamed(QUARTZ_JOB, QUARTZ_JOB_EXECUTION_CONTEXT))
        .or(Witness.hasClassesNamed(BATCH_ABSTRACT_STEP, BATCH_STEP_EXECUTION));
  }

  @Override
  public List<TypeInstrumentation> types() {
    return Collections.unmodifiableList(
        Arrays.<TypeInstrumentation>asList(
            new ScheduledRunnables(), new QuartzJobs(), new BatchSteps()));
  }

  @Override
  public List<String> helperClassNames() {
    return HelperClasses.of(
        SchedulerModule.class,
        "SchedulerHelper",
        "ScheduledHelper",
        "QuartzHelper",
        "BatchStepHelper");
  }

  @Override
  public String[] libraryVersionRange() {
    return new String[] {
      "org.springframework:spring-context 5.3-7.0 (ScheduledMethodRunnable, @Scheduled)",
      "org.quartz-scheduler:quartz 2.3-2.5 (org.quartz.Job)",
      "org.springframework.batch:spring-batch-core 4.3-5.2 (AbstractStep); 6.x is not supported"
    };
  }

  /** {@code run()} of {@code ScheduledMethodRunnable}. */
  static ElementMatcher.Junction<MethodDescription> scheduledRun() {
    return named("run").and(takesArguments(0)).and(not(isStatic()));
  }

  /** {@code execute(JobExecutionContext)} with a body. */
  static ElementMatcher.Junction<MethodDescription> quartzExecute() {
    return named("execute")
        .and(takesArguments(1))
        .and(takesArgument(0, named(QUARTZ_JOB_EXECUTION_CONTEXT)))
        .and(not(isAbstract()))
        .and(not(isStatic()));
  }

  /** {@code execute(StepExecution)} of Spring Batch 4.3-5.2. */
  static ElementMatcher.Junction<MethodDescription> stepExecute() {
    return named("execute")
        .and(takesArguments(1))
        .and(takesArgument(0, named(BATCH_STEP_EXECUTION)))
        .and(not(isAbstract()))
        .and(not(isStatic()));
  }

  /** Base with a readable name for the agent's debug output. */
  abstract static class Named implements TypeInstrumentation {
    private final String name;

    Named(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "scheduler(" + name + ")";
    }
  }

  /** Spring {@code @Scheduled} methods. */
  static final class ScheduledRunnables extends Named {
    ScheduledRunnables() {
      super("ScheduledMethodRunnable.run");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(SCHEDULED_METHOD_RUNNABLE);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(scheduledRun(), ScheduledRunAdvice.class.getName());
    }
  }

  /** Quartz jobs: every concrete or abstract class that implements {@code org.quartz.Job}. */
  static final class QuartzJobs extends Named {
    QuartzJobs() {
      super("Job.execute");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return not(isInterface()).and(safeImplements(named(QUARTZ_JOB)));
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(quartzExecute(), QuartzExecuteAdvice.class.getName());
    }
  }

  /** Spring Batch steps. */
  static final class BatchSteps extends Named {
    BatchSteps() {
      super("AbstractStep.execute");
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named(BATCH_ABSTRACT_STEP);
    }

    @Override
    public void transform(TypeTransformer transformer) {
      transformer.applyAdvice(stepExecute(), BatchStepExecuteAdvice.class.getName());
    }
  }
}
