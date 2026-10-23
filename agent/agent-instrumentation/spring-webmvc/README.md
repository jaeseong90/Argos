# Instrumentation: spring-webmvc

Spring MVC service names from the best matching handler pattern.

- Gradle project: `:agent:agent-instrumentation:spring-webmvc`
- Java package: `io.argos.agent.instrumentation.springwebmvc`
- Design: [10 agent design §6.2](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-12 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
