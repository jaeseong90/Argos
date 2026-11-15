# agent-bootstrap

Classes on the bootstrap class loader: the only API that advice code and helpers may use.

- Gradle project: `:agent:agent-bootstrap`
- Java packages: `io.argos.agent` (entry point `AgentPremain` only), `io.argos.agent.bootstrap`
- Design: [10 agent design §3, §4](../../docs/10-agent-design.md), [PoC notes](../../docs/poc/agent-poc.md)

Zero dependencies (rule B-1): only `java.*` may be referenced. `checkNoDependencies` fails the
build if any dependency is declared. The classes are placed at the root of `argos-agent.jar`;
`AgentPremain` appends the jar to the bootstrap class loader search, so these classes are defined
once, by the bootstrap loader, and are visible from every application class loader.

| Class | Role |
|---|---|
| `io.argos.agent.AgentPremain` | `Premain-Class`/`Agent-Class`/`Main-Class` (C-21). P0 kill switch (`-Dargos.agent.disabled=true`), P1 bootstrap append, P2 reflective call of `AgentBootstrap`. Never refers to bootstrap types directly |
| `AgentBootstrap` | Creates `AgentClassLoader` (parent: platform loader on JDK 9+, bootstrap on 8) and starts `io.argos.agent.core.boot.AgentStarter`; `check(..)` runs `--check` |
| `AgentClassLoader` | Package-private. Defines `inst/**/*.classdata` straight from the jar (no temporary files); own packages (core, instrumentation, shaded) never delegate to the parent; resources map to `inst/` and `.class` to `.classdata` |
| `AgentControl` | Capability handed to the core only: install the tracer, kill switch, module states and error counters |
| `Argos` | `tracer()`, `enabled()`, `skip(moduleId)` (first statement of every enter advice), `adviceError(throwable, moduleId)` (inlined exception handler of every advice) |
| `Tracer`, `NoopTracer` | The recording API (10 §3; target/db ids are `long` dictionary ids). WO-13 adds `stepBatch` (11 `batch_size`), `registerPool`, `poolNameId` (`POOL` dictionary id, C-101), `registerConnector` (10 §6.4), `sqlId(raw, dbId)` (normalized in the dialect of the target) and `sqlParam` (bind values rendered by the core for `capture.sql-parameters`) |
| `SqlState`, `FetchState`, `DbInfo` | WO-13 JDBC `ContextStore` values (10 §6.3, A-9: strings, numbers and bootstrap types only): raw SQL by reference until normalized, sql/db ids, bounded bind values (indexes <= 64, 256 characters each, 4,096 together, first set of a batch) and their step detail, batch count and first batch text, execution start, step and `tx_id`; result set rows and fetch time (the clock read twice per result set); the target of a connection and the last target of a data source |
| `PoolKind` | Pool libraries of `registerPool` (`HIKARI` 1, `TOMCAT_JDBC` 2, `DBCP2` 3; agent-internal numbers) |
| `TraceContext`, `Scope` | Transaction handle (bootstrap types and primitives only, `markEnded()` CAS) and async scope |
| `CallDepth` | Per-thread nesting counters (outermost call detection, D-24); WO-13: one `long` value slot per key (a nested `getConnection` hands its pool name up) and `outsideJdbc()` (no JDBC call in progress on the thread) |
| `ArgosThread` | Daemon agent threads without context class loader; `Argos.skip` ignores them |
| `KillSwitch`, `ModuleHealth` | Kill switch first stage; module states (`ENABLED`, `DISABLED_BY_*`, test-only `TEST_FAULT`) and error counters |
| `ContextStore` | Weak identity map per (key type, value type), bounded (100,000 entries per store by default) |
| `StepType`, `Kind`, `DictKind`, `FailureKind`, `CaptureConfig` | Constants (C-1 step numbers, C-16 dictionary kinds) and the capture-mode snapshot |

Tests (`./gradlew :agent:agent-bootstrap:test`, run on Java 8): class loader isolation and
resources, `Argos.skip` and the kill switch, fault injection, `ContextStore` identity/weak
keys/bound, `CallDepth`, `TraceContext.markEnded`, constant tables, the bounds of the WO-13 JDBC
state (`JdbcStateTest`).
