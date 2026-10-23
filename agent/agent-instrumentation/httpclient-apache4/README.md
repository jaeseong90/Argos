# Instrumentation: httpclient-apache4

Outgoing calls through Apache HttpClient 4.

- Gradle project: `:agent:agent-instrumentation:httpclient-apache4`
- Java package: `io.argos.agent.instrumentation.httpclient.apache4`
- Design: [10 agent design §6.5](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-14 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.
