# Instrumentation: scheduler

@Scheduled, Quartz and Spring Batch jobs as BATCH transactions.

- Gradle project: `:agent:agent-instrumentation:scheduler`
- Java package: `io.argos.agent.instrumentation.scheduler`
- Design: [10 agent design §6.8](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-18 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
