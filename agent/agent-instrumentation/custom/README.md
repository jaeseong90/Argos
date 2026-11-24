# Instrumentation: custom

User-configured method tracing (`instrumentation.custom-methods`, F-A14, SEC-25).

- Gradle project: `:agent:agent-instrumentation:custom`
- Java package: `io.argos.agent.instrumentation.custom`
- Design: [10 agent design §6.10](../../../docs/10-agent-design.md), security rules
  [16 §5.6](../../../docs/16-security-design.md)
- Build rules: Java 8 target, tests on Java 8 and 21, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## What it does

- An item `<class glob>#<method glob>[|<as-transaction>]` (globs: `*` within a
  package segment and any characters of a method name, `**` across segments, `?` one character)
  gives every matching declared method a `METHOD` step named `Class#method` inside a transaction.
  The error flag is set when the method throws; the exception is never changed. Without a
  transaction nothing is recorded and `argos_agent_orphan_steps_total{type="METHOD"}` counts the
  call. The step is always `METHOD` (the one precompiled advice, 16 §1.4); there is no step type
  option. An item with a step type suffix of the earlier format (`|SQL`, `|METHOD`, `|METHOD|true`,
  or a yml `step-type` field) is ignored alone with one WARN locally, and a pushed value with one is
  rejected as a whole (`FORBIDDEN_PATTERN`, `step_type_suffix`), ADR-0010 C-237 (5).
- `as-transaction` (`|true`): without a transaction on the thread the method begins a
  `BATCH` transaction with the fixed service name `BATCH <Class#method>` (status 0, error when it
  threw); inside a transaction it is a `METHOD` step.
- Never matched: classes under `java.`, `javax.`, `jakarta.`, `jdk.`, `sun.`, `com.sun.`,
  `io.argos.` and ByteBuddy (an item starting with one is ignored with a WARN; a wildcard never
  reaches them), generated classes (lambdas, CGLIB/Spring CGLIB, JDK and ByteBuddy proxies),
  interfaces, annotations, constructors, type initializers, abstract, native, bridge and synthetic
  methods (lambda bodies). A proxy that calls the real method therefore gives one step.
- An item that matches more than `instrumentation.custom-methods.max-classes-per-pattern` (500)
  classes is disabled with one WARN and counted in self-diagnostics
  (`custom_methods_items_disabled`). Classes are counted once each, at class load and at
  retransformation; the classes the item had already instrumented stop recording at once (a
  runtime check, no retransformation), unless another active item still matches them.
- A remote change (`remote-config.allow` must list the key) is retransformed by the core's
  `Retransformer` (at most 50 classes and one change per minute): the matchers read the policy in
  force, so new items get the advice and removed items lose it. A pushed value with a forbidden
  item is rejected as a whole (`FORBIDDEN_PATTERN`); a local value ignores only that item.
- With no item the type matcher costs one volatile read per class.

## Supported library versions

### Version range and witness

| Library | Supported range | Minimum verified | Maximum verified | Boundary tests |
|---|---|---|---|---|
| Application classes named by `instrumentation.custom-methods` (no target library) | JDK 8 to 25 (the agent's range) | JDK 8 | JDK 21 | `test` on JDK 8 and `testJdk21` (`CustomMethodAdviceTest`, `CustomLimitTest`, `CustomModuleTest`); end to end `agent-testing` `custom.CustomMethodStepsTest` and `remoteconfig.CustomMethodPolicyE2eTest` (shop-boot2 on JDK 8, shop-boot3 on JDK 21) |

- Witness: none (`CustomModule` keeps the default witness, every class loader). There is no
  target library to look for: the type matcher reads the configured items (one volatile read per
  class when there is none) and never reaches the excluded prefixes listed above.
- Run: `./gradlew :agent:agent-instrumentation:custom:check` (`test` on JDK 8 and
  `:agent:agent-instrumentation:custom:testJdk21`); end to end
  `./gradlew :agent:agent-testing:integrationTest -Pagent.e2e=shop-boot2@8,shop-boot3@21 --tests '*custom*'`.

### Details

Any application class (the targets are named by configuration, not a library). Tested with
fixture classes on JDK 8 and 21 (`test`, `testJdk21`) and in shop-boot3 (JDK 21) and shop-boot2
(JDK 8) with the release agent (`agent-testing` `custom.CustomMethodStepsTest`).
