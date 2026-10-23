# agent-core

Transactions and step buffers, full statistics, bounded queues and transport, configuration, remote commands, metrics, self-protection and `--check`.

- Gradle project: `:agent:agent-core`
- Java package: `io.argos.agent.core`
- Design: [10 agent design §2, §4-§14](../../docs/10-agent-design.md)

Loaded by `AgentClassLoader` from `inst/**.classdata`; `agent-bootstrap` is `compileOnly`. Third-party libraries are shaded below `io.argos.agent.shaded` by `agent-dist`.
