/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The JDK allow-list entries of the executor module (10 §4.5 item 3, 10 §6.6, WO-15): exactly the
 * JDK task and executor classes the module instruments pass the ignore list; other {@code
 * java.util.concurrent} classes (and other executors) stay ignored.
 */
@Tag("req:F-A07")
class IgnoreMatchersTest {
  private static final List<Pattern> NONE = Collections.emptyList();

  @Test
  @DisplayName(
      "T-AG-051b JDK allow-list: the executor module's JDK classes pass the ignore list (8-25 names)")
  void executorClassesAllowed() {
    for (String allowed :
        Arrays.asList(
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask",
            "java.util.concurrent.ForkJoinPool",
            "java.util.concurrent.ForkJoinTask",
            "java.util.concurrent.ForkJoinTask$RunnableExecuteAction",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnable",
            "java.util.concurrent.ForkJoinTask$AdaptedRunnableAction",
            "java.util.concurrent.ForkJoinTask$AdaptedCallable",
            "java.util.concurrent.ForkJoinTask$AdaptedInterruptibleCallable",
            "java.util.concurrent.ForkJoinTask$AdaptedInterruptibleRunnable",
            "java.util.concurrent.FutureTask",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.CompletableFuture$AsyncSupply",
            "java.util.concurrent.CompletableFuture$AsyncRun",
            "java.util.concurrent.CompletableFuture$UniApply",
            "java.util.concurrent.CompletableFuture$UniCompose",
            "java.util.concurrent.ThreadPerTaskExecutor$TaskRunner",
            "java.util.concurrent.ThreadPerTaskExecutor$ThreadBoundFuture")) {
      assertThat(IgnoreMatchers.isJdkAllowlisted(allowed)).as(allowed).isTrue();
      assertThat(IgnoreMatchers.ignored(allowed, NONE)).as(allowed).isFalse();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051c JDK allow-list: other java.util.concurrent classes, executors and FJ internals stay"
          + " ignored")
  void otherConcurrencyClassesIgnored() {
    for (String ignored :
        Arrays.asList(
            "java.util.concurrent.Executors$DelegatedExecutorService",
            "java.util.concurrent.Executors$RunnableAdapter",
            "java.util.concurrent.ExecutorCompletionService",
            "java.util.concurrent.ThreadPoolExecutor$Worker",
            "java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue",
            "java.util.concurrent.ForkJoinTask$ExceptionNode",
            "java.util.concurrent.ForkJoinTask$Aux",
            "java.util.concurrent.ForkJoinTask$InvokeAnyRoot",
            "java.util.concurrent.ForkJoinPool$WorkQueue",
            "java.util.concurrent.CompletableFuture$Signaller",
            "java.util.concurrent.CompletableFuture$BiApply",
            "java.util.concurrent.CompletableFuture$Completion",
            "java.util.concurrent.CompletableFuture$Delayer",
            "java.util.concurrent.LinkedBlockingQueue",
            "java.lang.VirtualThread",
            "java.lang.Thread",
            "jdk.internal.misc.CarrierThread",
            "jdk.internal.net.http.common.SequentialScheduler")) {
      assertThat(IgnoreMatchers.ignored(ignored, NONE)).as(ignored).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-AG-051d the allow-list entries added for the executor module are java.util.concurrent"
          + " names only")
  void allowListStaysInJavaUtilConcurrentForExecutors() {
    for (String name : IgnoreMatchers.JDK_ALLOWLIST) {
      if (name.contains("Executor") || name.contains("ForkJoin") || name.contains("Future")) {
        assertThat(name).startsWith("java.util.concurrent.");
      }
    }
    for (String prefix : IgnoreMatchers.JDK_ALLOWLIST_PREFIXES) {
      if (prefix.contains("ForkJoin") || prefix.contains("ThreadPerTask")) {
        assertThat(prefix).startsWith("java.util.concurrent.").contains("$");
      }
    }
  }
}
