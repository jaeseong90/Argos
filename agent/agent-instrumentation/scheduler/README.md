# Instrumentation: scheduler

@Scheduled, Quartz and Spring Batch jobs as BATCH transactions.

- Gradle project: `:agent:agent-instrumentation:scheduler`
- Java package: `io.argos.agent.instrumentation.scheduler`
- Design: [10 agent design §6.8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)
- Disable: `instrumentation.disabled=scheduler`

## What is recorded

| Entry point | Transaction (kind `BATCH`, service name fixed) |
|---|---|
| Spring `ScheduledMethodRunnable.run()` (every `@Scheduled` method) | `BATCH <class>#<method>` |
| Quartz `execute(JobExecutionContext)` of every `org.quartz.Job` implementation (`QuartzJobBean` included) | `BATCH <group>.<name>` of the job key |
| Spring Batch `AbstractStep.execute(StepExecution)` | `BATCH <job>.<step>`, one transaction per step execution |

- `<class>` is the user class: a CGLIB subclass name (`$$SpringCGLIB$$0`,
  `$$EnhancerBySpringCGLIB$$…`) is cut before `$$`, a JDK proxy is replaced by the method's
  declaring class, so proxies add no service names.
- Inside another transaction (a job started by an HTTP request, a Spring Batch job run by a Quartz
  job) no transaction is created; the entry point becomes a `METHOD` step named after the same
  text without `BATCH ` (`settlementJob.settleStep`). A job that delegates to another
  `Job.execute` of the same job (or an override calling `super.execute`) adds nothing.
- A chunk step that records more steps than `sampling.profile.max-steps` (1,000) is folded by the
  tracer's summary mode: one `SUMMARY` entry per (type, name), with count and total time.
- Errors: a thrown exception, or for Spring Batch a step execution that ended `FAILED` (its first
  failure exception; `AbstractStep` records failures instead of throwing them), marks the
  transaction or step as an error. Exceptions and return values are never changed. Status is 0
  (protocol `http_status` of BATCH).
- Context propagation is not changed: a job started on an application-created `java.lang.Thread`
  is a new BATCH transaction (10 §8), a partitioned or multi-threaded step's worker threads are not
  linked to the step's transaction.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| Spring Framework (`org.springframework:spring-context`, `@Scheduled`) | 5.3 to 7.0 | 5.3.31 | 7.0.10 | `test` on JDK 8 (5.3.31), `testModern` (6.2.19) and `testSpring7` (7.0.10) on JDK 17: `ScheduledAdviceTest`, `SchedulerModuleTest` |
| Quartz (`org.quartz-scheduler:quartz`) | 2.3 to 2.5 | 2.3.2 | 2.5.2 | `test` (2.3.2), `testModern` and `testSpring7` (2.5.2): `QuartzAdviceTest` |
| Spring Batch (`org.springframework.batch:spring-batch-core`) | 4.3 to 5.2 | 4.3.10 | 5.2.6 | `test` (4.3.10) and `testModern` (5.2.6): `SpringBatchAdviceTest`; end to end `agent-testing` `batch.BatchTransactionsTest` (shop-batch, Spring Boot 3.5) |

- Witness (`SchedulerModule.witness()`, resource lookup only), one of:
  `org.springframework.scheduling.support.ScheduledMethodRunnable` (Spring `@Scheduled`);
  `org.quartz.Job` with `org.quartz.JobExecutionContext` (Quartz);
  `org.springframework.batch.core.step.AbstractStep` with `org.springframework.batch.core.StepExecution`
  (Spring Batch; 6.x moved `StepExecution`, so its loaders do not match this part).
- Run: `./gradlew :agent:agent-instrumentation:scheduler:check` (`test`,
  `:agent:agent-instrumentation:scheduler:testModern`, `:agent:agent-instrumentation:scheduler:testSpring7`);
  end to end `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-batch@17,shop-batch@21 --tests '*batch*'`.

### Details

Target libraries are `compileOnly` dependencies at the oldest supported line; at run time the
application's classes are used. Each boundary is covered by the module tests (03 §3.1):

| Library | Range | Tested (task) |
|---|---|---|
| Spring Framework (`spring-context`, `@Scheduled`) | 5.3 – 7.0 | 5.3.31 (`test`, JDK 8), 6.2.x (`testModern`, JDK 17), 7.0.x (`testSpring7`, JDK 17) |
| Quartz (`org.quartz-scheduler:quartz`) | 2.3 – 2.5 | 2.3.2 (`test`), 2.5.2 (`testModern`, `testSpring7`) |
| Spring Batch (`spring-batch-core`) | 4.3 – 5.2 | 4.3.10 (`test`), 5.2.6 (`testModern`) |

Spring Batch 6.x (Spring Boot 4) moved `StepExecution` to another package and is not supported:
its steps are not recorded. Reactive `@Scheduled` methods (Spring 6.1+, `Publisher` return types)
run through another runnable and are not recorded. Message consumers (kind `MESSAGE`) are a later
release.

```sh
./gradlew :agent:agent-instrumentation:scheduler:check   # test, testModern, testSpring7
```
