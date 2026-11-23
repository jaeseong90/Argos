/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TraceRuntime;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code THREAD_STACK} (10 §12.1, 11 §4.2): argument rules, lookup by tx_id and thread_id. */
@Tag("req:F-A12")
@Tag("req:SEC-24")
class ThreadStackCommandTest {
  private final TraceRuntime runtime =
      TraceRuntime.create(AgentConfig.defaults(), new AgentCounters());
  private final ThreadStackCommand command =
      new ThreadStackCommand(CommandEnv.builder().runtime(runtime).build());

  private static Map<String, String> args(String... pairs) {
    Map<String, String> args = new HashMap<String, String>();
    for (int i = 0; i < pairs.length; i += 2) {
      args.put(pairs[i], pairs[i + 1]);
    }
    return args;
  }

  @Test
  @DisplayName(
      "thread stack fails with 'invalid arguments' for both, neither or a malformed tx_id or"
          + " thread_id")
  void invalidArguments() {
    for (Map<String, String> bad :
        java.util.Arrays.<Map<String, String>>asList(
            args(),
            args("tx_id", "1", "thread_id", "2"),
            args("tx_id", "abc"),
            args("tx_id", "-5"),
            args("tx_id", "123456789012345678901"),
            args("tx_id", "99999999999999999999"),
            args("thread_id", "1.5"),
            args("thread_id", ""),
            args("other", "1"))) {
      assertThatThrownBy(() -> command.run(bad))
          .as(String.valueOf(bad))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(ThreadStackCommand.INVALID_ARGUMENTS);
    }
    assertThatThrownBy(() -> command.run(null)).hasMessage(ThreadStackCommand.INVALID_ARGUMENTS);
  }

  @Test
  @DisplayName(
      "thread stack of a transaction that is not running fails with 'transaction not running'")
  void notRunning() {
    assertThatThrownBy(() -> command.run(args("tx_id", "42")))
        .hasMessage(ThreadStackCommand.NOT_RUNNING);
    TraceContext ended =
        new TraceContext(43, 1, 2, 3, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
    runtime.activeRegistry().add(ended);
    ended.markEnded();
    assertThatThrownBy(() -> command.run(args("tx_id", "43")))
        .hasMessage(ThreadStackCommand.NOT_RUNNING);
    assertThatThrownBy(() -> command.run(args("thread_id", "999999999")))
        .hasMessage(ThreadStackCommand.THREAD_NOT_FOUND);
    assertThatThrownBy(() -> command.run(args("thread_id", "0")))
        .hasMessage(ThreadStackCommand.THREAD_NOT_FOUND);
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "thread stack by tx_id and by thread_id returns one thread with frames, state and tx_id in"
          + " the thread dump JSON form")
  void stackOfRunningTransaction() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              TraceContext ctx =
                  new TraceContext(
                      -7L, 1, 2, 3, 0, Kind.HTTP, 0, 0, Thread.currentThread(), false, -1, -1);
              runtime.activeRegistry().add(ctx);
              running.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "argos-test-stack-worker");
    worker.setDaemon(true);
    worker.start();
    try {
      assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
      String tx = Long.toUnsignedString(-7L);
      for (Map<String, String> query :
          java.util.Arrays.<Map<String, String>>asList(
              args("tx_id", tx), args("thread_id", Long.toString(worker.getId())))) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Map<String, Object> t;
        do {
          CommandOutput out = command.run(query);
          assertThat(out.contentType()).isEqualTo("application/json");
          assertThat(out.truncated()).isFalse();
          Map<String, Object> root =
              MiniJson.object(new String(out.payload(), StandardCharsets.UTF_8));
          List<Object> threads = (List<Object>) root.get("threads");
          assertThat(threads).hasSize(1);
          t = (Map<String, Object>) threads.get(0);
        } while (!"TIMED_WAITING".equals(t.get("state")) && System.nanoTime() < deadline);
        assertThat(t.get("id")).isEqualTo(worker.getId());
        assertThat(t.get("name")).isEqualTo("argos-test-stack-worker");
        assertThat(t.get("virtual")).isEqualTo(false);
        assertThat(t.get("tx_id")).isEqualTo(tx);
        assertThat(t.get("state")).isEqualTo("TIMED_WAITING");
        assertThat(String.valueOf(t.get("frames"))).contains("CountDownLatch.await");
        assertThat((List<Object>) t.get("frames")).hasSizeLessThanOrEqualTo(64);
      }
      // A platform thread without a transaction by thread_id: no tx_id.
      CommandOutput me =
          command.run(
              Collections.singletonMap("thread_id", Long.toString(Thread.currentThread().getId())));
      Map<String, Object> root = MiniJson.object(new String(me.payload(), StandardCharsets.UTF_8));
      Map<String, Object> t = (Map<String, Object>) ((List<Object>) root.get("threads")).get(0);
      assertThat(t).doesNotContainKey("tx_id");
      assertThat(t.get("state")).isEqualTo("RUNNABLE");
    } finally {
      release.countDown();
      worker.join(5000);
    }
  }
}
