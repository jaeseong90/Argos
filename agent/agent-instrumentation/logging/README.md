# Instrumentation: logging

Trace id injection into the MDC of Logback, Log4j 2 and Log4j 1.

- Gradle project: `:agent:agent-instrumentation:logging`
- Java package: `io.argos.agent.instrumentation.logging`
- Design: [10 agent design §6.9](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-18 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
