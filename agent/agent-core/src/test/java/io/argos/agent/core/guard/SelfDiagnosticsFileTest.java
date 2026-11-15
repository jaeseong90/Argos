/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Self-diagnostics snapshot and its harness file (18 §19, ADR-0010 C-95): measured gauges only
 * (missing is not zero, C-4), counters, modules, atomic rewrite, the premain class list (C-79).
 */
@Tag("req:N-02")
class SelfDiagnosticsFileTest {
  @TempDir File dir;

  private static String read(File file) throws IOException {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "18 §19 snapshot: the runtime's gauges, agent thread CPU, direct memory, premain_ms,"
          + " counters and named modules")
  void snapshotFields() throws InterruptedException {
    final Map<String, Long> gauges = new HashMap<String, Long>();
    gauges.put(SelfDiagnostics.THREADLOCAL_CONTEXTS_LIVE, 0L);
    gauges.put(SelfDiagnostics.ACTIVE_REGISTRY_SIZE, 2L);
    gauges.put(SelfDiagnostics.WEAKMAP_SIZE, 0L);
    gauges.put(SelfDiagnostics.WEAKMAP_ENTRIES, 7L);
    AgentCounters counters = new AgentCounters();
    counters.incSqlOutsideTx();
    String[] names = new String[64];
    names[53] = "probe";
    TestControls.newControl().setModuleState(53, ModuleHealth.DISABLED_BY_ERRORS);
    SelfDiagnosticsCollector collector =
        new SelfDiagnosticsCollector(() -> gauges, counters, names);
    collector.premainMillis(123.4567);
    // An agent thread with some CPU time.
    final CountDownLatch done = new CountDownLatch(1);
    ArgosThread worker =
        new ArgosThread(
            () -> {
              long x = 0;
              for (int i = 0; i < 2_000_000; i++) {
                x += i * 31L;
              }
              if (x == 42) {
                System.gc();
              }
              try {
                done.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "argos-test-cpu");
    worker.start();
    try {
      SelfDiagnostics snapshot = collector.snapshot();
      assertThat(snapshot.threadLocalContextsLive()).hasValue(0);
      assertThat(snapshot.activeRegistrySize()).hasValue(2);
      assertThat(snapshot.weakMapSize()).hasValue(0);
      assertThat(snapshot.weakMapEntries()).hasValue(7);
      assertThat(snapshot.premainMs().getAsDouble()).isEqualTo(123.4567);
      assertThat(snapshot.agentThreadsCpuSeconds()).isPresent();
      assertThat(snapshot.agentThreadsCpuSeconds().getAsDouble()).isGreaterThanOrEqualTo(0.0);
      assertThat(snapshot.directMemoryBytes()).isPresent();
      assertThat(snapshot.counters()).containsEntry("argos_agent_sql_outside_tx_total", 1L);
      assertThat(snapshot.modules()).hasSize(1);
      assertThat(snapshot.modules().get(0).name()).isEqualTo("probe");
      assertThat(snapshot.modules().get(0).stateName()).isEqualTo("DISABLED_BY_ERRORS");
      assertThat(snapshot.toJson())
          .contains("\"active_registry_size\":2")
          .contains("\"weakmap_size\":0,\"weakmap_entries\":7")
          .contains("\"premain_ms\":123.457")
          .contains("\"state\":\"DISABLED_BY_ERRORS\"");
    } finally {
      done.countDown();
      worker.join();
      TestControls.newControl().setModuleState(53, ModuleHealth.ENABLED);
    }
  }

  @Test
  @DisplayName(
      "missing is not zero (C-4): a runtime without gauges, a failing runtime and no premain time"
          + " leave the fields out")
  void missingGaugesAreAbsent() {
    SelfDiagnosticsCollector none =
        new SelfDiagnosticsCollector(
            Collections::<String, Long>emptyMap, new AgentCounters(), new String[0]);
    SelfDiagnostics empty = none.snapshot();
    assertThat(empty.threadLocalContextsLive()).isEmpty();
    assertThat(empty.activeRegistrySize()).isEmpty();
    assertThat(empty.weakMapSize()).isEmpty();
    assertThat(empty.weakMapEntries()).isEmpty();
    assertThat(empty.premainMs()).isEmpty();
    assertThat(empty.toJson())
        .doesNotContain("threadlocal_contexts_live")
        .doesNotContain("weakmap_entries")
        .doesNotContain("premain_ms");
    SelfDiagnosticsCollector failing =
        new SelfDiagnosticsCollector(
            () -> {
              throw new UnsupportedOperationException("not yet");
            },
            new AgentCounters(),
            null);
    assertThat(failing.snapshot().activeRegistrySize()).isEmpty();
    assertThat(new SelfDiagnosticsCollector(null, new AgentCounters(), null).snapshot().modules())
        .isEmpty();
  }

  @Test
  @DisplayName(
      "C-95 the snapshot file is replaced as a whole (no temporary file left) and rewritten on"
          + " every write")
  void fileIsReplaced() throws IOException {
    final Map<String, Long> gauges = new HashMap<String, Long>();
    gauges.put(SelfDiagnostics.ACTIVE_REGISTRY_SIZE, 1L);
    File file = new File(new File(dir, "out"), "selfdiag.json");
    SelfDiagnosticsFile target =
        new SelfDiagnosticsFile(
            file, new SelfDiagnosticsCollector(() -> gauges, new AgentCounters(), null));
    assertThat(target.file()).isEqualTo(file);
    target.write();
    assertThat(read(file)).startsWith("{\"format\":1").contains("\"active_registry_size\":1");
    gauges.put(SelfDiagnostics.ACTIVE_REGISTRY_SIZE, 0L);
    target.write();
    assertThat(read(file)).contains("\"active_registry_size\":0");
    assertThat(new File(file.getPath() + ".tmp")).doesNotExist();
    assertThat(file.getParentFile().list()).containsExactly("selfdiag.json");
  }

  @Test
  @Tag("req:N-30")
  @DisplayName("C-79 the premain class list is written sorted with a header; null writes nothing")
  void premainClasses() throws IOException {
    File file = new File(dir, "selfdiag.json");
    SelfDiagnosticsFile target =
        new SelfDiagnosticsFile(
            file, new SelfDiagnosticsCollector(null, new AgentCounters(), null));
    target.writePremainClasses(null);
    File list = new File(file.getPath() + SelfDiagnosticsFile.PREMAIN_CLASSES_SUFFIX);
    assertThat(list).doesNotExist();
    target.writePremainClasses(new String[] {"b.B", "a.A"});
    List<String> lines = Files.readAllLines(list.toPath(), StandardCharsets.UTF_8);
    assertThat(lines).containsExactly(SelfDiagnosticsFile.PREMAIN_HEADER, "a.A", "b.B");
  }

  @Test
  @DisplayName("a file that cannot be written is reported once and never throws")
  void unwritableFile() throws IOException {
    File blocker = new File(dir, "blocker");
    Files.write(blocker.toPath(), new byte[] {1});
    SelfDiagnosticsFile target =
        new SelfDiagnosticsFile(
            new File(blocker, "selfdiag.json"),
            new SelfDiagnosticsCollector(null, new AgentCounters(), null));
    target.write();
    target.write();
    target.writePremainClasses(new String[] {"a"});
    assertThat(new File(blocker, "selfdiag.json")).doesNotExist();
  }

  @Test
  @DisplayName("live thread enumeration finds the current thread and stays bounded")
  void liveThreads() {
    Thread[] threads = SelfDiagnosticsCollector.liveThreads();
    assertThat(threads).contains(Thread.currentThread());
    assertThat(threads.length).isLessThanOrEqualTo(SelfDiagnosticsCollector.MAX_THREADS);
  }
}
