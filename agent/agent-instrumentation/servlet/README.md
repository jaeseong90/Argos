# Instrumentation: servlet

Servlet request boundary for javax.servlet and jakarta.servlet containers (one request, one transaction).

- Gradle project: `:agent:agent-instrumentation:servlet`
- Java package: `io.argos.agent.instrumentation.servlet`
- Design: [10 agent design §6.1](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-12 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
