# Instrumentation: api

Instrumentation framework shared by every module: `InstrumentationModule`, `TypeInstrumentation`,
`TypeTransformer`, `SafeMatchers`, `Witness`, `HelperClasses`, `ModuleIds`.

- Gradle project: `:agent:agent-instrumentation:api`
- Java package: `io.argos.agent.instrumentation.api`
- Design: [10 agent design §5](../../../docs/10-agent-design.md), [PoC notes](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`). ByteBuddy is an `api` dependency
  (modules compile against `ElementMatcher` and the Advice annotations, ADR-0003 C-26); it is shaded
  below `io.argos.agent.shaded` by `agent-dist`.

## Writing a module

The `servlet` and `spring-webmvc` modules are the reference (WO-02).

1. Gradle: `agent/agent-instrumentation/<module>/build.gradle.kts` with
   `compileOnly(project(":agent:agent-bootstrap"))`, `implementation(project(":agent:agent-instrumentation:api"))`
   and the target library as `compileOnly` only, at the lowest supported version (catalog entry in
   `gradle/libs.versions.toml`; a non-allow-listed license needs a row in
   `docs/licenses/dev-only.md` when it is on a test classpath). Helper tests that want the real
   tracer add `testImplementation(project(":agent:agent-core"))` and a `TestControls` class in
   package `io.argos.agent.bootstrap` of the test sources (see `servlet`).
2. `InstrumentationModule` (public no-arg constructor): `id()` = the Gradle module name (the
   `instrumentation.disabled` value), `moduleId()` from `ModuleIds`, `witness()`
   (`Witness.hasClassesNamed(..)`, resource lookup only), `types()`, `helperClassNames()` via
   `HelperClasses.of(MyModule.class, "MyHelperCommon", "MyHelper")` in dependency order.
3. Index (C-25): `src/main/resources/META-INF/services/io.argos.agent.instrumentation.api.InstrumentationModule`
   with the module class name; `agent-dist` merges the files, `checkModuleIndex` verifies them.
4. `TypeInstrumentation`: `typeMatcher()` by name (`named`, `namedOneOf`); subtype matching only
   with `SafeMatchers` and only for jdbc, executor and servlet filters (10 §5.1); put cheap checks
   (`declaresMethod(..)`) before the hierarchy walk. `transform(t)` calls
   `t.applyAdvice(methodMatcher, XAdvice.class.getName())`; start method matchers with
   `named(..).and(takesArguments(n))` so that the chain is typed for methods.
5. Advice (inlined, never loaded into the application; ArchUnit `./gradlew :agent:agent-testing:archTest`):
   `*Advice`, static methods, `@Advice.OnMethodEnter(suppress = Throwable.class)` /
   `@Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)`; enter starts
   with `if (Argos.skip(MODULE_ID)) return ...;`, exit with `if (<@Advice.Enter value> == null)
   return;` (C-24); library types as `Object` parameters (advice references bootstrap types and
   own helpers only); `@Advice.Local` for values between enter and exit; no lambdas,
   synchronization, blocking, reflection, `String.format`, streams, try-with-resources. Every
   advice gets an exception handler that counts suppressed exceptions against the module.
6. Helpers (`*Helper*`, injected into the application class loader): bootstrap API, JDK and the
   target library only; no static state (constants only); a helper per library namespace when
   the API differs (javax/jakarta), shared logic in a namespace-free helper listed first.
7. JDK classes: only those on `IgnoreMatchers.JDK_ALLOWLIST` (agent-core) are transformed; such
   modules have no helpers (the bootstrap class loader cannot receive them).
8. Checks: module unit tests on Java 8 (matchers against the real API, helper logic against
   `TracerImpl`), `archTest` (advice rules and `ModuleChecks`: index, id, helpers, advice class
   files), the attach test and the sample-app end-to-end test of `agent-testing`.
