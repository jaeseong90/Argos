/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** TraceContext, ArgosThread, ModuleHealth and the constant tables (10 §3, C-1, C-16). */
class BootstrapTypesTest {

  @Test
  @Tag("req:D-04")
  @DisplayName("TraceContext.markEnded is true for exactly one caller under contention")
  void markEndedOnce() throws InterruptedException {
    final TraceContext context =
        new TraceContext(1, 2, 3, 4, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger winners = new AtomicInteger();
    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < 8; i++) {
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  if (context.markEnded()) {
                    winners.incrementAndGet();
                  }
                }
              });
      thread.start();
      threads.add(thread);
    }
    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(winners.get()).isEqualTo(1);
    assertThat(context.isEnded()).isTrue();
    assertThat(context.thread.get()).isSameAs(Thread.currentThread());
  }

  @Test
  @DisplayName("ArgosThread: daemon, no context class loader, factory names name, name-2, ...")
  void argosThreads() {
    ThreadFactory factory = ArgosThread.factory("argos-x");
    Runnable noop =
        new Runnable() {
          @Override
          public void run() {}
        };
    Thread first = factory.newThread(noop);
    Thread second = factory.newThread(noop);
    assertThat(first).isInstanceOf(ArgosThread.class);
    assertThat(first.isDaemon()).isTrue();
    assertThat(first.getContextClassLoader()).isNull();
    assertThat(first.getName()).isEqualTo("argos-x");
    assertThat(second.getName()).isEqualTo("argos-x-2");
    assertThat(ArgosThread.isAgentThread()).isFalse();
  }

  @Test
  @DisplayName("ModuleHealth: states and error counters per module id; invalid ids are disabled")
  void moduleHealth() {
    AgentControl control = new AgentControl();
    int id = 33;
    control.setModuleState(id, ModuleHealth.ENABLED);
    int before = ModuleHealth.errors(id);
    assertThat(control.incrementModuleErrors(id)).isEqualTo(before + 1);
    assertThat(ModuleHealth.errors(id)).isEqualTo(before + 1);
    control.setModuleState(id, ModuleHealth.DISABLED_BY_ERRORS);
    assertThat(ModuleHealth.state(id)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    assertThat(ModuleHealth.stateName(ModuleHealth.DISABLED_BY_ERRORS))
        .isEqualTo("DISABLED_BY_ERRORS");
    control.setModuleState(id, ModuleHealth.ENABLED);
    assertThat(ModuleHealth.enabled(-1)).isFalse();
    assertThat(ModuleHealth.state(64)).isEqualTo(ModuleHealth.DISABLED_BY_CONFIG);
    assertThat(control.incrementModuleErrors(64)).isZero();
  }

  @Test
  @Tag("req:C-1")
  @DisplayName("C-1 step type numbers: SQL=1 DBC=2 COMMIT=3 ROLLBACK=4 HTTP=5 ... SUMMARY=11")
  void stepTypeNumbers() {
    assertThat(
            new int[] {
              StepType.SQL,
              StepType.DBC,
              StepType.COMMIT,
              StepType.ROLLBACK,
              StepType.HTTP,
              StepType.METHOD,
              StepType.ASYNC,
              StepType.ERROR,
              StepType.STACK,
              StepType.MESSAGE,
              StepType.SUMMARY
            })
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    assertThat(StepType.name(StepType.ROLLBACK)).isEqualTo("ROLLBACK");
    assertThat(StepType.name(99)).isEqualTo("UNKNOWN(99)");
    assertThat(new int[] {Kind.HTTP, Kind.BATCH, Kind.MESSAGE}).containsExactly(1, 2, 3);
    assertThat(
            new int[] {
              DictKind.SERVICE,
              DictKind.SQL,
              DictKind.CLASS,
              DictKind.METHOD,
              DictKind.THREAD,
              DictKind.HOST,
              DictKind.FRAME,
              DictKind.METRIC,
              DictKind.ERROR_GROUP,
              DictKind.DB_TARGET,
              DictKind.APP_VERSION,
              DictKind.POOL
            })
        .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    assertThat(DictKind.COUNT).isEqualTo(13);
    assertThat(DictKind.name(DictKind.DB_TARGET)).isEqualTo("DB_TARGET");
    assertThat(DictKind.name(DictKind.POOL)).isEqualTo("POOL");
  }

  @Test
  @DisplayName("CaptureConfig defaults (10 §15) and defensive copies of its arrays")
  void captureConfig() {
    CaptureConfig defaults = CaptureConfig.DEFAULT;
    assertThat(defaults.queryStringMode()).isEqualTo(CaptureConfig.QUERY_STRING_OFF);
    assertThat(defaults.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_MASKED);
    assertThat(defaults.userIdMode()).isEqualTo(CaptureConfig.USER_ID_OFF);
    assertThat(defaults.sqlParamMode()).isEqualTo(CaptureConfig.SQL_PARAMS_MASKED);
    assertThat(defaults.errorMessageMax()).isEqualTo(1024);
    assertThat(defaults.stackDepth()).isEqualTo(30);
    String[] headers = {"x-request-id"};
    CaptureConfig custom = new CaptureConfig(0, null, headers, 0, null, 0, null, 0, null, 10, 5);
    headers[0] = "changed";
    assertThat(custom.headerWhitelist()).containsExactly("x-request-id");
    assertThat(custom.trustedProxies()).isEmpty();
  }
}
