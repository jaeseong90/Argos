# Instrumentation: executor

Context propagation through allow-listed executors, `CompletableFuture` and thread-per-task
executors, virtual threads included (10 §6.6, §8; F-A07, F-A30, DSN-02-05).

- Gradle project: `:agent:agent-instrumentation:executor`
- Java package: `io.argos.agent.instrumentation.executor`
- Design: [10 agent design §6.6, §8](../../../docs/10-agent-design.md), [PoC notes §10](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, no logging framework or servlet implementation on the runtime
  classpath (`argos.java-agent`)

## How it works

No task is ever wrapped or replaced (absolute rule). The submitting thread's transaction is
attached to the task object in one task store, `ContextStore.of(Runnable.class, TraceContext.class)`,
used for every task key (`Runnable`, `Callable`, `ForkJoinTask`): the injected `argos$ctx` field of
an application task class (DSN-02-05, the core adds it while the class is first defined) or the
weak identity map (JDK task classes, classes loaded before the agent, lambdas). The method that runs
the task on the worker **removes** the context and activates it, so a reused task object never
carries an old transaction; the scope (and the ASYNC step of `async.step=true`) closes when the
method returns. An ended transaction is never activated. Nothing happens outside a transaction or
on agent threads (`Argos.skip`).

The JDK classes are on the core's JDK allow-list (`IgnoreMatchers`); the advice runs in the
bootstrap class loader and uses the bootstrap API only (no helper).

### Submit side (allow-listed executors only)

| Target | Advice | What it does |
|---|---|---|
| `java.util.concurrent.ThreadPoolExecutor.execute(Runnable)`; Tomcat `org.apache.tomcat.util.threads.ThreadPoolExecutor.execute(Runnable[, ..])`; Spring `SimpleAsyncTaskExecutor.execute(Runnable[, long])` | `ExecuteAdvice` | Attaches the transaction to the task. `submit`, `invokeAll`, `CompletableFuture.*Async(.., pool)` and Spring `submit*` pass through `execute` |
| `ScheduledThreadPoolExecutor$ScheduledFutureTask.<init>` (`schedule*`, `execute`, `submit`) | `CreatedTaskArg1Advice` | Attaches to the task object the pool creates (argument 1: an inner class), before it is queued |
| `ForkJoinPool.execute/submit/invoke` of a `ForkJoinTask` | `ForkJoinSubmitAdvice` | Attaches to the task |
| `ForkJoinTask$Adapted*`, `$RunnableExecuteAction` `<init>` (`ForkJoinPool` with a `Runnable`/`Callable`, `ForkJoinTask.adapt`) | `CreatedTaskAdvice` | Attaches to the adapter the pool creates |
| `ForkJoinTask.fork()` | `ForkAdvice` | Attaches to the forked task: the subtasks of a parallel stream on the common pool |
| `ThreadPerTaskExecutor$TaskRunner`/`$ThreadBoundFuture` `<init>` (JDK 21+, `newVirtualThreadPerTaskExecutor`) | `CreatedTaskArg1Advice` | Attaches to the task object of `execute`/`submit` |
| `CompletableFuture$AsyncSupply`/`$AsyncRun` `<init>` (`supplyAsync`, `runAsync`, `completeAsync`) | `CreatedTaskArg1Advice` | Attaches to the stage: works on any executor, allow-listed or not |
| `CompletableFuture.thenApplyAsync/thenAcceptAsync/thenRunAsync/thenComposeAsync/whenCompleteAsync/handleAsync` + `CompletableFuture$Uni*.<init>` | `AsyncStageAdvice`, `UniCreatedAdvice` | The method arms a one-shot thread marker (`CallDepth.EXECUTOR` value slot); the first `Uni*` stage it constructs takes the transaction. A synchronous `thenApply` arms nothing |

Spring's `ThreadPoolTaskExecutor` delegates every `execute`/`submit*` to the JDK
`ThreadPoolExecutor` it creates, so it has no submit advice of its own (one at the Spring level
would attach to the undecorated task when a `TaskDecorator` is set).

### Execution side

| Target | Advice |
|---|---|
| `FutureTask.run()`, `ForkJoinTask.doExec()` (every fork/join task: the `exec()` of 10 §6.6), `CompletableFuture$AsyncSupply/AsyncRun.run()`, `ThreadPerTaskExecutor$TaskRunner/ThreadBoundFuture.run()` | `RunAdvice` |
| `CompletableFuture$Uni*.tryFire(int)` in mode `ASYNC` (1) only: the call `Completion.run()/exec()` make on the executor thread | `TryFireAdvice` |
| `run()`/`call()` of application `Runnable`/`Callable` classes (not interfaces; plus the context field) | `RunAdvice` |
| `beforeExecute`/`afterExecute` of the JDK and Tomcat `ThreadPoolExecutor` and of the pools of Spring's `ThreadPoolTaskExecutor`/`ThreadPoolTaskScheduler` (they override the hooks without `super` since Spring 6.1): lambda tasks, whose hidden classes cannot be instrumented | `BeforeExecuteAdvice`, `AfterExecuteAdvice` |

When an outer execution point already took the context (the pool hook before `FutureTask.run`,
`doExec` around `run`), the inner one finds the store empty: a task is activated once. A hook
override that calls `super` runs the advice twice; the nested call is recognised by the
`CallDepth.EXECUTOR` depth and does nothing.

### Exclusions

- Task (or function) classes of `java.lang.` (virtual thread continuations), `jdk.`, `sun.`,
  `io.netty.`, `reactor.`, `io.argos.agent.` (the agent itself; the sample apps under
  `io.argos.sample.` are applications and their lambda tasks propagate) and the container
  (`org.apache.tomcat.`, `org.apache.coyote.`, `org.apache.catalina.`) never get a context on the
  submit side and are not matched as application tasks (module-local rules; the agent's global
  ignore list is unchanged, so classes under `io.argos.` are never transformed themselves).
- Netty event executors, Reactor schedulers and every other `Executor` implementation are not
  allow-listed and not transformed (T-AG-051).
- `Thread.startVirtualThread`/`Thread.ofVirtual().start()` and `new Thread(task).start()` are not
  propagated (10 §8).

### Known limits

- A lambda task on a pool whose hooks are overridden without `super` by a class this module does
  not know (an application subclass) gets no propagation; `FutureTask`, `CompletableFuture` stages
  and application task classes still do.
- A lambda task on Spring's `SimpleAsyncTaskExecutor.execute` (no worker hook to read it) gets no
  propagation; `submit*` (a `FutureTask`), `@Async` and application task classes do.
- One task object submitted by two live transactions at once (a shared, non-capturing lambda)
  carries one context: the identity-keyed store cannot tell the two submissions apart. The
  `CompletableFuture` stages avoid this by attaching to the stage object the call creates, never to
  the user function.
- `CompletableFuture` stages of `thenCombineAsync` and the other two-source `*Async` methods are
  outside the 1.0 scope (10 §6.6).

## Supported library versions

| Library | Versions | Witness |
|---|---|---|
| `java.util.concurrent` | JDK 8, 11, 17, 21, 25 (`ThreadPerTaskExecutor` 21+) | `JdkInternalsWitnessTest` (U-12) on every JDK: `CompletableFuture$AsyncSupply/AsyncRun(dep, fn)`, the six `Uni*` stages with `tryFire(int)` and `fn`, `UniCompletion.executor`, `ASYNC = 1`; `ForkJoinTask.doExec()`/`fork()`, the adapters (user task first), `ScheduledFutureTask` (inner, task second), `ThreadPerTaskExecutor$TaskRunner(executor, Runnable)`/`$ThreadBoundFuture(executor, Callable)` |
| Spring Framework | 5.3, 6.x, 7.x (`SimpleAsyncTaskExecutor`, the pools of `ThreadPoolTaskExecutor`/`ThreadPoolTaskScheduler`) | name matchers; end to end in the sample apps (`agent-testing` async suite) |
| Tomcat | 9, 10.1, 11 (`org.apache.tomcat.util.threads.ThreadPoolExecutor`) | name matchers; end to end in the sample apps |

## Tests

The module is installed with the core's own installer into the test JVM (bootstrap API on the boot
class path, a holder `-javaagent`): the advice runs inside the real JDK classes on real threads
against the real tracer. `test` runs on JDK 8, `testJdk11`, `testJdk17`, `testJdk21` and
`testJdk25` on the other JDKs; `check` runs all five.

- `ExecutorPropagationTest`: T-AG-051 (ThreadPoolExecutor, ScheduledThreadPoolExecutor, ForkJoinPool,
  commonPool parallel stream, ThreadPerTaskExecutor on 21+, `remove()`/`cancel()` and queue sizes,
  executors outside the allow-list, excluded task classes), T-AG-052 (1,000 alternating tasks
  without contamination, no store entry left after 10,000 tasks), application task classes and
  their context field.
- `CompletableFuturePropagationTest`: T-AG-052 (the eight `*Async` methods on the common pool, an
  allow-listed pool and a foreign executor, completed and pending sources, ASYNC step parents,
  synchronous `thenApply` untouched, ended transactions).
- `ExecutorAdviceTest`: the pool hooks in a `super`-calling and a `super`-less subclass.
- `ExecutorModuleTest`: T-AG-051 matchers (allow-listed types matched, foreign executors, Netty,
  Reactor and container classes not).
- `JdkInternalsWitnessTest`: U-12 and the installer's transformed JDK classes.
