# Instrumentation: executor

Context propagation through allow-listed executors, CompletableFuture and virtual threads.

- Gradle project: `:agent:agent-instrumentation:executor`
- Java package: `io.argos.agent.instrumentation.executor`
- Design: [10 agent design §6.6](../../../docs/10-agent-design.md), [PoC notes §10](../../../docs/poc/agent-poc.md)
- Build rules: Java 8 target, tests on Java 8, no logging framework or servlet
  implementation on the runtime classpath (`argos.java-agent`)

## WO-02 P-6 point: `java.util.concurrent.ThreadPoolExecutor`

One JDK class of `java.base` (on the core's JDK allow-list, retransformed when already loaded at
premain; the core adds the JPMS read edge `java.base` → Argos bootstrap module). No helper: the
advice runs in the bootstrap class loader and uses the bootstrap API only.

| Method | Advice | Thread | What it does |
|---|---|---|---|
| `execute(Runnable)` | `ExecuteAdvice` | submitter | Attaches the current transaction to the task object (`ContextStore<Runnable, TraceContext>`, weak identity keys, bounded). `submit()` and `CompletableFuture.*Async(.., executor)` pass through `execute` |
| `beforeExecute(Thread, Runnable)` | `BeforeExecuteAdvice` | worker | Takes the transaction off the task and activates it (scope kept per worker thread); with `async.step=true` opens an ASYNC step named after the task class (lambda classes cut after `$$Lambda`) |
| `afterExecute(Runnable, Throwable)` | `AfterExecuteAdvice` | worker | Closes the ASYNC step (error flag if the task threw) and the scope |

- The task object is never replaced (no `Runnable` wrapping): `remove(task)`, the queue and
  `shutdownNow()` results stay the application's own.
- `beforeExecute` runs for every task, the first task of a new worker included, so lambdas work
  without instrumenting their (hidden) classes.
- Limits (WO-15 adds `FutureTask.run`/`Runnable.run` and the other executors of 10 §6.6): a
  subclass that overrides `beforeExecute`/`afterExecute` without `super` (Spring 6.1+
  `ThreadPoolTaskExecutor`) gets no propagation; `ScheduledThreadPoolExecutor` schedules without
  `execute`; `ForkJoinPool` (`CompletableFuture` default executor, parallel streams) is not
  covered. If the module is disabled between the two hooks of one task, the worker keeps the
  binding until its next task.

## Supported library versions

`java.util.concurrent.ThreadPoolExecutor` of JDK 8-25. Checked end to end in `shop-boot3` on JDK
17, 21 and 25 (`agent-testing` `JdkClassesAgentTest`, T-AG-070) and on JDK 8 by the module tests.
