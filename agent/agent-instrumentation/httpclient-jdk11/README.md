# Instrumentation: httpclient-jdk11

Outgoing calls through java.net.http.HttpClient (Java 11+).

- Gradle project: `:agent:agent-instrumentation:httpclient-jdk11`
- Java package: `io.argos.agent.instrumentation.httpclient.jdk11`
- Design: [10 agent design §6.5](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Supported library versions

Not instrumented yet. WO-14 records the supported version range here, with an
integration test at each boundary version (03 §3.1). Target libraries are `compileOnly`
dependencies only; at runtime the application's classes are used.

TODO (WO-14, open PM decision from WO-00): `--release 8` (DSN-19-02) hides `java.net.http`, so
`ArgosHttpRequestWrapper extends HttpRequest` (10 §6.5) does not compile in this module yet.
