/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.context.VirtualThreads;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TestVirtualThreads;
import io.argos.agent.core.trace.TraceRuntime;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code THREAD_DUMP} and {@code THREAD_STACK} with a transaction running on a virtual thread
 * (T-AG-054 dump item, 10 U-2). Runs on JDK 21 and 25 ({@code testJdk21}, {@code testJdk25}); on
 * Java 8 only the "no dumpThreads" case runs.
 */
@Tag("req:F-A12")
@Tag("req:F-A30")
class ThreadDumpVirtualThreadTest {

  @Test
  @DisplayName("10 U-2 dumpThreads is used from JDK 21 on and not below")
  void dumpThreadsAvailability() {
    ThreadDumpCommand command = new ThreadDumpCommand(CommandEnv.builder().build());
    assertThat(command.dumpThreadsAvailable()).isEqualTo(ThreadDumpCommand.featureVersion() >= 21);
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "T-AG-054 the thread dump lists a virtual thread with virtual=true and the tx_id it runs;"
          + " THREAD_STACK(tx_id) returns that thread; the temporary JDK dump file is deleted")
  void virtualThreadInDump(@TempDir Path logDir) throws Exception {
    assumeTrue(VirtualThreads.supported(), "virtual threads need JDK 21+");
    TraceRuntime runtime = TraceRuntime.create(AgentConfig.defaults(), new AgentCounters());
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<TraceContext> context = new AtomicReference<TraceContext>();
    Thread vt =
        TestVirtualThreads.start(
            "",
            () -> {
              TraceContext ctx =
                  new TraceContext(
                      77_000_000_001L,
                      1,
                      2,
                      3,
                      0,
                      Kind.HTTP,
                      0,
                      0,
                      Thread.currentThread(),
                      true,
                      -1,
                      -1);
              runtime.activeRegistry().add(ctx);
              context.set(ctx);
              running.countDown();
              try {
                release.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    try {
      assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
      CommandEnv env = CommandEnv.builder().runtime(runtime).logDir(logDir.toFile()).build();
      CommandOutput out =
          new ThreadDumpCommand(env)
              .run(Collections.singletonMap(CommandHandler.COMMAND_ID_ARG, "c0ffee-1"));
      Map<String, Object> root = MiniJson.object(new String(out.payload(), StandardCharsets.UTF_8));
      assertThat(root.get("virtual_threads_source")).isEqualTo("dumpThreads");
      Map<String, Object> t = ThreadDumpJsonTest.thread(root, vt.getId());
      assertThat(t.get("virtual")).isEqualTo(true);
      assertThat(t.get("tx_id")).isEqualTo("77000000001");
      List<Object> frames = (List<Object>) t.get("frames");
      assertThat(frames).isNotEmpty();
      assertThat(String.valueOf(frames)).contains("CountDownLatch.await");
      // Platform threads are not reported twice.
      int me = 0;
      for (Object o : (List<Object>) root.get("threads")) {
        if (Long.valueOf(Thread.currentThread().getId())
            .equals(((Map<String, Object>) o).get("id"))) {
          me++;
        }
      }
      assertThat(me).isEqualTo(1);
      File[] left = logDir.toFile().listFiles();
      assertThat(left).isEmpty();

      CommandOutput stack =
          new ThreadStackCommand(env)
              .run(Collections.singletonMap(ThreadStackCommand.TX_ID, "77000000001"));
      Map<String, Object> one =
          MiniJson.object(new String(stack.payload(), StandardCharsets.UTF_8));
      List<Object> threads = (List<Object>) one.get("threads");
      assertThat(threads).hasSize(1);
      Map<String, Object> s = (Map<String, Object>) threads.get(0);
      assertThat(s.get("id")).isEqualTo(vt.getId());
      assertThat(s.get("virtual")).isEqualTo(true);
      assertThat(s.get("tx_id")).isEqualTo("77000000001");
      assertThat(String.valueOf(s.get("frames"))).contains("CountDownLatch.await");
      assertThat(logDir.toFile().listFiles()).isEmpty();
    } finally {
      release.countDown();
      vt.join(10_000);
    }
  }
}
