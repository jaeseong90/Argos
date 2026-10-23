# agent-testing

Agent test harness shared by every agent work order (18 §5): `AppLauncher` (sample app x JDK x
agent options), `FakeCollector` (in-process gRPC), assertion DSL, transform baselines, fail-safe
injection, leak probes, and the compatibility matrix runner.

- Gradle project: `:agent:agent-testing`
- Java package: `io.argos.agent.testing`
- Design: [18 test design §5-§6](../../docs/18-test-design.md)

Never shipped. Compiled and tested on JDK 21 (DSN-19-02); sample apps run on other JDKs as
separate processes. Test suites: `test`, `archTest` (ArchUnit rules over all agent modules,
19 §5.3), `integrationTest`, `securityTest`, `matrixTest` (skipped unless
`ARGOS_RUNNER_LABELS` contains `argos-matrix` or `-Pmatrix.combo=<id>` is given),
`benchTest` (requires `-Pbench.machine-profile=<id>`).

## Agent rules (`archTest`, part of `check`)

`./gradlew :agent:agent-testing:archTest` checks the classes of every agent module
(`agent-bootstrap`, `agent-core`, every `agent-instrumentation` module):

| Rules | Source | Classes |
|---|---|---|
| T-AG-001a..d, T-AG-002a..d, T-AG-003a..e, ADV-04 | 19 §5.3 ADV-01..04, 10 §5.3 A-1..A-6, C-9 | `src/archTest/.../AdviceRules.java` |
| AGT-01..03, B-1..B-4, T-AG-004 | 19 §5.3, 10 §2 | `src/archTest/.../AgentBoundaryRules.java` |

- Advice classes are classes with `@Advice.OnMethodEnter/OnMethodExit` methods; helpers are
  instrumentation classes whose name contains `Helper` (10 §5.4).
- ArchUnit cannot see `invokedynamic`, `monitorenter` or instruction order (19 §13 #8, confirmed in
  `AdviceRulesTest`), so lambdas/method references, synchronization and the `Argos.skip` guard are
  checked on the bytecode with ASM (`AdviceBytecode.java`). Every enter and exit advice method
  starts with `if (Argos.skip(MODULE_ID)) return;` (10 §5.3 A-2); no other guard and no unguarded
  helper delegation is accepted.
- `readOnly = false`, `@Advice.FieldSetterHandle` and `@Advice.AssignReturned` need an entry in
  [`argos-advice-allowlist.txt`](../agent-instrumentation/src/main/resources/argos-advice-allowlist.txt)
  (10 §5.3 A-5), keyed by the Gradle module name (`httpclient-jdk11`); `@Advice.SelfCallHandle` is
  never allowed. Instrumentation code uses no `net.bytebuddy.implementation` type except
  `Assigner`/`Assigner.Typing` and never calls `DynamicType.Builder...intercept(..)` (A-6).

## JVM flags of integration and matrix tests

`integrationTest` and `matrixTest` run with `-XX:+BytecodeVerificationLocal` (19 §11, 18 §5.10), so
the JVM also verifies JDK classes that the agent transforms (application classes are verified by
default). JDK 17, 21 and 25 treat the flag as diagnostic and need `-XX:+UnlockDiagnosticVMOptions`
before it; Java 8 accepts both. Sample apps that a test starts as a separate process (WO-02, WO-19)
must use the same two flags.
- `src/archFixtures` holds a compliant miniature agent and one violating class per rule, compiled
  with `--release 8` like agent code (test-only). `AdviceRulesTest` and `AgentBoundaryRulesTest`
  show that every rule fails on its violating fixture and passes on the compliant one.
