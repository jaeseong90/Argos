/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TraceRuntime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code THREAD_DUMP} JSON (10 §12.1): the field names and types the server's {@code
 * ThreadDump.parseJson} reads (14 §4.10), 64 frames per thread, the 1 MiB cut by whole threads, and
 * a real dump of this JVM with a deadlock and a running transaction.
 */
@Tag("req:F-A12")
class ThreadDumpJsonTest {
  /** The server's {@code TX_ID} pattern. */
  private static final Pattern SERVER_TX_ID = Pattern.compile("[0-9]{1,20}");

  private static List<String> frames(int n, int width) {
    List<String> frames = new ArrayList<String>();
    for (int i = 0; i < n; i++) {
      StringBuilder f = new StringBuilder("com.shop.Service.m").append(i).append("(Service.java:");
      while (f.length() < width) {
        f.append('9');
      }
      frames.add(f.append(')').toString());
    }
    return frames;
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "thread dump JSON has threads[]{id,name,state,virtual,locks_held,waiting_on,frames,tx_id}"
          + " and deadlocks[]{thread_ids} with the types the server parses")
  void fieldsAndTypes() {
    ThreadDumpJson.ThreadEntry platform =
        new ThreadDumpJson.ThreadEntry(
            7,
            "http-nio-8080-exec-1 \"q\"\n\uD800",
            "BLOCKED",
            false,
            Arrays.asList("java.lang.Object@1b6d3586"),
            "java.lang.Object@7a81197d",
            frames(100, 40),
            true,
            -2L);
    ThreadDumpJson.ThreadEntry virtual =
        new ThreadDumpJson.ThreadEntry(
            90, "", null, true, null, null, Collections.<String>emptyList(), false, 0);
    ThreadDumpJson.Encoded encoded =
        ThreadDumpJson.encode(
            Arrays.asList(platform, virtual),
            Collections.singletonList(new long[] {7, 8}),
            ThreadDumpCommand.SOURCE_DUMP_THREADS,
            false,
            1024 * 1024);
    Map<String, Object> root = MiniJson.object(new String(encoded.utf8, StandardCharsets.UTF_8));
    List<Object> threads = (List<Object>) root.get("threads");
    assertThat(threads).hasSize(2);
    Map<String, Object> t = (Map<String, Object>) threads.get(0);
    assertThat(t.get("id")).isEqualTo(7L);
    assertThat(t.get("name")).isEqualTo("http-nio-8080-exec-1 \"q\"\n�");
    assertThat(t.get("state")).isEqualTo("BLOCKED");
    assertThat(t.get("virtual")).isEqualTo(false);
    assertThat((List<Object>) t.get("locks_held")).containsExactly("java.lang.Object@1b6d3586");
    assertThat(t.get("waiting_on")).isEqualTo("java.lang.Object@7a81197d");
    assertThat((List<Object>) t.get("frames")).hasSize(ThreadDumpJson.MAX_FRAMES);
    assertThat(t.get("tx_id")).isEqualTo(Long.toUnsignedString(-2L));
    assertThat(SERVER_TX_ID.matcher((String) t.get("tx_id")).matches()).isTrue();
    Map<String, Object> v = (Map<String, Object>) threads.get(1);
    assertThat(v.get("virtual")).isEqualTo(true);
    assertThat(v).doesNotContainKeys("tx_id", "waiting_on", "state");
    assertThat((List<Object>) v.get("locks_held")).isEmpty();
    List<Object> deadlocks = (List<Object>) root.get("deadlocks");
    assertThat(((Map<String, Object>) deadlocks.get(0)).get("thread_ids"))
        .isEqualTo(Arrays.asList(7L, 8L));
    assertThat(root.get("truncated")).isEqualTo(false);
    assertThat(root.get("virtual_threads_source")).isEqualTo("dumpThreads");
    assertThat(encoded.truncated).isFalse();
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "thread dump over commands.thread-dump.max-bytes is cut by whole threads, keeps threads"
          + " that run a transaction, and stays valid JSON")
  void cutAtCap() {
    List<ThreadDumpJson.ThreadEntry> threads = new ArrayList<ThreadDumpJson.ThreadEntry>();
    for (int i = 0; i < 1500; i++) {
      threads.add(
          new ThreadDumpJson.ThreadEntry(
              i + 1,
              "worker-" + i,
              "WAITING",
              false,
              null,
              null,
              frames(64, 20),
              i == 1499,
              4242L));
    }
    int cap = 1024 * 1024;
    ThreadDumpJson.Encoded encoded =
        ThreadDumpJson.encode(threads, Collections.<long[]>emptyList(), null, false, cap);
    assertThat(encoded.truncated).isTrue();
    assertThat(encoded.utf8.length).isLessThanOrEqualTo(cap);
    assertThat(encoded.utf8.length).isGreaterThan(cap - 4096);
    Map<String, Object> root = MiniJson.object(new String(encoded.utf8, StandardCharsets.UTF_8));
    assertThat(root.get("truncated")).isEqualTo(true);
    List<Object> kept = (List<Object>) root.get("threads");
    assertThat(kept.size()).isLessThan(1500).isEqualTo(encoded.threads);
    Map<String, Object> last = (Map<String, Object>) kept.get(kept.size() - 1);
    assertThat(last.get("tx_id")).isEqualTo("4242");
    for (Object o : kept) {
      assertThat((List<Object>) ((Map<String, Object>) o).get("frames")).hasSize(64);
    }
    // The configured cap is honoured, never above 1 MiB.
    Map<String, String> small = new HashMap<String, String>();
    small.put("commands.thread-dump.max-bytes", "65536");
    assertThat(ThreadDumpCommand.maxBytes(AgentConfig.of(small))).isEqualTo(65536);
    assertThat(ThreadDumpCommand.maxBytes(null)).isEqualTo(1024 * 1024);
  }

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName(
      "a real thread dump of this JVM lists the platform threads with frames, a deadlock cycle"
          + " and the tx_id of a running transaction")
  void realDump() throws Exception {
    TraceRuntime runtime = TraceRuntime.create(AgentConfig.defaults(), new AgentCounters());
    TraceContext context =
        new TraceContext(
            9_007_199_254_740_993L,
            1,
            2,
            3,
            0,
            Kind.HTTP,
            0,
            0,
            Thread.currentThread(),
            false,
            -1,
            -1);
    runtime.activeRegistry().add(context);
    ReentrantLock a = new ReentrantLock();
    ReentrantLock b = new ReentrantLock();
    CountDownLatch locked = new CountDownLatch(2);
    Thread first = deadlocker("argos-test-deadlock-1", a, b, locked);
    Thread second = deadlocker("argos-test-deadlock-2", b, a, locked);
    first.start();
    second.start();
    try {
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      ThreadDumpCommand command =
          new ThreadDumpCommand(CommandEnv.builder().runtime(runtime).build());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      Map<String, Object> root;
      List<Object> deadlocks;
      do {
        CommandOutput out = command.run(Collections.<String, String>emptyMap());
        assertThat(out.contentType()).isEqualTo("application/json");
        root = MiniJson.object(new String(out.payload(), StandardCharsets.UTF_8));
        deadlocks = (List<Object>) root.get("deadlocks");
      } while (deadlocks.isEmpty() && System.nanoTime() < deadline);
      List<Long> cycle = (List<Long>) ((Map<String, Object>) deadlocks.get(0)).get("thread_ids");
      assertThat(cycle).containsExactlyInAnyOrder(first.getId(), second.getId());
      Map<String, Object> me = thread(root, Thread.currentThread().getId());
      assertThat(me.get("tx_id")).isEqualTo("9007199254740993");
      assertThat(me.get("state")).isEqualTo("RUNNABLE");
      assertThat((List<Object>) me.get("frames")).isNotEmpty();
      Map<String, Object> blocked = thread(root, first.getId());
      assertThat(blocked.get("name")).isEqualTo("argos-test-deadlock-1");
      assertThat(blocked.get("waiting_on")).asString().contains("ReentrantLock");
      assertThat((List<Object>) blocked.get("locks_held")).hasSize(1);
      assertThat(blocked).doesNotContainKey("tx_id");
      String source = (String) root.get("virtual_threads_source");
      assertThat(source)
          .isEqualTo(
              ThreadDumpCommand.featureVersion() >= 21
                  ? ThreadDumpCommand.SOURCE_DUMP_THREADS
                  : ThreadDumpCommand.SOURCE_UNAVAILABLE);
    } finally {
      first.interrupt();
      second.interrupt();
      first.join(5000);
      second.join(5000);
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> thread(Map<String, Object> root, long id) {
    for (Object o : (List<Object>) root.get("threads")) {
      Map<String, Object> t = (Map<String, Object>) o;
      if (Long.valueOf(id).equals(t.get("id"))) {
        return t;
      }
    }
    throw new AssertionError("thread " + id + " not in dump");
  }

  private static Thread deadlocker(
      String name, ReentrantLock mine, ReentrantLock other, CountDownLatch locked) {
    Thread thread =
        new Thread(
            () -> {
              try {
                mine.lockInterruptibly();
                try {
                  // Both threads hold their own lock before either asks for the other's.
                  locked.countDown();
                  locked.await();
                  other.lockInterruptibly();
                  other.unlock();
                } finally {
                  mine.unlock();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            name);
    thread.setDaemon(true);
    return thread;
  }
}
