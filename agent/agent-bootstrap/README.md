# agent-bootstrap

Classes injected into the bootstrap class loader: the only API that Advice code and helpers may use (`Argos`, `Tracer`, `TraceContext`, `Scope`, `CallDepth`, `ArgosThread`, `KillSwitch`, `ContextStore`, `StepType`, `Kind`, `DictKind`, `ModuleHealth`).

- Gradle project: `:agent:agent-bootstrap`
- Java package: `io.argos.agent.bootstrap`
- Design: [10 agent design §3](../../docs/10-agent-design.md)

Zero dependencies (rule B-1): only `java.*` may be referenced. `checkNoDependencies` fails the build if any dependency is declared. Classes are placed at the root of `argos-agent.jar`.
