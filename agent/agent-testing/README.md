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
  checked on the bytecode with ASM (`AdviceBytecode.java`). Every enter advice method starts with
  `if (Argos.skip(MODULE_ID)) return;` (10 §5.3 A-2). An exit advice method starts with the same
  guard or, per ADR-0003 C-24, with a null check of its `@Advice.Enter` parameter
  (`if (context == null) return;`): the exit decides by the enter result and does not re-read the
  kill switch. No other guard and no unguarded helper delegation is accepted.
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

## Agent attach tests (WO-02, `integrationTest`)

`AgentAttachTest` starts `example.trivial.TrivialApp` (`src/trivialApp`, Java 8 bytecode, outside
`io.argos.*`) with `-javaagent` on each JDK of `-Pagent.attach.jdks` (default `8,21`; Gradle
toolchains provide the JDKs), with two jars:

- the release jar (`:agent:agent-dist`, variant `agentJarElements`);
- `build/probe-agent/argos-agent-probe.jar` (`probeAgentJar`): the release jar plus the test-only
  modules of `src/probeModule` (`test-probe` on the trivial app with a helper, `test-probe-jdk` on
  `java.util.concurrent.ThreadPoolExecutor`). The probe is compiled against agent-dist's stage 1
  jar (`agentInstElements`, relocated ByteBuddy names) and is never shipped.

Scenarios per JDK: no temporary file (watched `java.io.tmpdir` and `TMP`/`TEMP`/`TMPDIR`,
T-BLD-006), stdout only the application's line and stderr empty except the JVM's CDS notice
(T-AG-060), class loader isolation (10 §4.2), `-Xshare:off` with empty stderr, transactions and
steps in JSONL with the helper in the application loader and the JDK class retransformed (JPMS read
edge), kill switch first and second stage (T-AG-043), advice fault injection (T-AG-040), ByteBuddy's
`sun.misc.Unsafe` code never loaded (`-verbose:class`; JDK 23+ with
`--sun-misc-unsafe-memory-access=deny`), and JDK classes loaded before the Argos premain
(T-AG-070 PoC): `build/preload-agent/preload-agent.jar` (`preloadAgentJar`, `src/preloadAgent`,
test-only) runs first and loads `ThreadPoolExecutor` and `HttpURLConnection`; both are then
retransformed in java.base and the executor advice on the retransformed class propagates the
transaction to the pool thread (ASYNC step with `async.step=true`), with no `IllegalAccessError`.

```
./gradlew :agent:agent-testing:integrationTest -Pagent.attach.jdks=8,11,17,21,25
```

## Harness library and sample apps with the agent (WO-02 minimal, `src/main`)

| Package | Classes |
|---|---|
| `launcher` | `SampleApp` (artifact + `build/sample-app/<app>.properties`), `AppLauncher` (app x JDK x agent options), `RunningApp` (HTTP calls, outputs, startup times, temp entries, stop), `TempWatch` (temp directory watch, T-BLD-006) |
| `export` | `JsonlTransactions` (reader of `-Dargos.exporter=file`, waits for expected transactions), `TransactionRecord` / `StepRecord` |
| `assertion` | `TransactionAssert` (`assertThatTransaction(tx).hasService(..).hasStatus(..)`) |
| `baseline` | `TransformList` (`-Dargos.debug.transform-list` file) |
| `log` | `AppOutput` (stdout lines that are not application log lines, agent mentions, stderr other than the JVM's CDS notice) |

`AppLauncher` gives each run a work directory: `tmp/` (`java.io.tmpdir`, `TMP`/`TEMP`/`TMPDIR`),
`agent/argos-agent.jar` (copy), `logs/` (`argos.log.dir`), `out/tx.jsonl`, `out/transformed.txt`,
`stdout.log`, `stderr.log`. JVM flags `-XX:-UsePerfData` and C-27; the app binds 127.0.0.1 on a
free port, Spring Boot's banner is off, `ARGOS_*` and `JAVA_TOOL_OPTIONS` are not inherited.
Startup: process creation to port binding (C-49 for jar apps) and to the first 200 of
`/actuator/health`. WAR apps (staged Tomcat) arrive with WO-05. On Windows the stop does not run
JVM shutdown hooks, so tests wait for the periodic JSONL flush (about 100 ms) and transform list
write (about 2 s).

`SampleAppAgentTest` (`integrationTest`) starts sample apps with the release agent jar and an
in-process ext-stub (`:sample-apps:ext-stub`, target of the apps' outgoing calls):

```
./gradlew :agent:agent-testing:integrationTest                                  # shop-boot3@21 (default)
./gradlew :agent:agent-testing:integrationTest --tests '*SampleAppAgentTest*' -Pagent.e2e=shop-boot2@8,shop-boot2@11,shop-boot2@17,shop-boot2@21,shop-boot3@17,shop-boot3@21,shop-boot3@25
./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=none                # attach tests only
```

Apps: `shop-boot2`, `shop-boot3`, `shop-boot4`, `shop-heavy`, `shop-batch` (resolved through
their `sampleAppElements` variant; only the requested apps are built). JDKs come from Gradle
toolchains; JDK 25 runs add `--sun-misc-unsafe-memory-access=deny`. Per combination (T-AG-010,
T-AG-070 PoC): no transaction for the readiness polls, exactly one transaction for
`GET /api/orders/1` (`GET /api/orders/{id}`, 200, elapsed, masked remote IP) with as many SQL steps
as the app counted statements (normalized SQL, database target without parameters), 404 fallback
name without `/error`, forward, 500 with root cause and masked message, 503, excluded static file
and preflight; `GET /api/pay/1?client=hurl` (HttpURLConnection, java.base) with one HTTP step and
the transaction's `traceparent` at ext-stub, 5xx and connection refused classified;
`GET /api/async/executor` (`async.step=true`) with the ASYNC step of a lambda run on a
`ThreadPoolExecutor` and `remove(task)` still true; the transformed-class list (jdbc on HikariCP
and H2, `ThreadPoolExecutor` and `sun.net.www.protocol.http.HttpURLConnection` in the bootstrap
loader); no `IllegalAccessError`, no agent output on stdout/stderr, no agent-like temp entry, no
ERROR/WARN in the agent log. The first combination also runs the advice fault injection of the
servlet module and of the jdbc, executor and httpclient-jdk modules (T-AG-040 PoC: the app
answers 2xx) and the T-BLD-006 baseline: the same requests without the agent, every temp entry of
the agent run (`TempWatch`: watch service from before the spawn plus the final tree, digits
normalized) must also appear without the agent. Run directories of the agent run and of the
baseline have names of the same length: on Windows the JDK creates a transient `socket_<n>` file
in `%TEMP%` for the wakeup pipe of every NIO selector (Tomcat, Reactor Netty) only when that path
fits the 108-character AF_UNIX limit.

Each combination writes `build/reports/agent-e2e/<app>-jdk<N>-{transformed.txt,tx.jsonl,
agent.log,stdout.log,temp-events.txt}` (transform baseline input, PoC run table) and one
`[e2e-run]` line per run on standard output.

