# Instrumentation: custom

User-configured method tracing.

- Gradle project: `:agent:agent-instrumentation:custom`
- Java package: `io.argos.agent.instrumentation.custom`
- Design: [10 agent design §6.10](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-18 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
