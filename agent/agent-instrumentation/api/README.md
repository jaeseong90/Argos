# Instrumentation: api

Instrumentation framework shared by every module: InstrumentationModule, TypeInstrumentation, TypeTransformer, SafeMatchers, Witness, HelperClasses.

- Gradle project: `:agent:agent-instrumentation:api`
- Java package: `io.argos.agent.instrumentation.api`
- Design: [10 agent design §5](../../../docs/10-agent-design.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## Status

Skeleton created by WO-00. WO-02 / WO-10 adds the framework classes.
