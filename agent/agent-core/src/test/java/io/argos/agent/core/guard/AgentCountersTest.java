/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Agent self counters (10 §11) and the self-diagnostics snapshot (18 §19). */
class AgentCountersTest {

  @Test
  @Tag("req:D-06")
  @DisplayName("counters use fixed label sets; anything else counts as _other_")
  void fixedLabels() {
    AgentCounters counters = new AgentCounters();
    counters.incDropped("transaction");
    counters.addDropped("profile", 3);
    counters.addDropped("profile", 0);
    counters.incDropped("bogus");
    counters.incDropped(null);
    counters.incIgnored("path");
    counters.incIgnored("referer");
    counters.incOrphanStep(StepType.SQL);
    counters.incOrphanStep(StepType.SUMMARY);
    counters.incOrphanStep(-1);
    counters.incDictionaryOverflow(DictKind.FRAME);
    counters.incDictionaryOverflow(99);
    counters.incModuleError(2);
    counters.incModuleError(200);
    counters.setModuleNames(new String[] {"servlet", null, "jdbc"});
    counters.incTransformError();
    counters.incTransformError("circularity");
    counters.incTransformError("no-such-reason");
    counters.incSqlOutsideTx();
    counters.incTransactionLeaked();
    counters.incTransactionUnanchored();
    counters.incServiceDictionaryOverflow();
    counters.incSqlUnparsed();
    counters.incProfileDropped();
    counters.incScopeChainCut();

    assertThat(counters.dropped("transaction")).isEqualTo(1);
    assertThat(counters.dropped("profile")).isEqualTo(3);
    assertThat(counters.dropped("nope")).isEqualTo(2);
    assertThat(counters.droppedTotal()).isEqualTo(6);
    assertThat(counters.ignored("path")).isEqualTo(1);
    assertThat(counters.orphanSteps(StepType.SQL)).isEqualTo(1);
    assertThat(counters.orphanStepsTotal()).isEqualTo(3);
    assertThat(counters.moduleErrors(2)).isEqualTo(1);
    assertThat(counters.dictionaryOverflow(DictKind.FRAME)).isEqualTo(1);
    assertThat(counters.transformErrors()).isEqualTo(3);
    assertThat(counters.transformErrors("circularity")).isEqualTo(1);

    Map<String, Long> snapshot = counters.snapshot();
    assertThat(snapshot)
        .containsEntry("argos_agent_dropped_total{queue=\"transaction\"}", 1L)
        .containsEntry("argos_agent_dropped_total{queue=\"profile\"}", 3L)
        .containsEntry("argos_agent_dropped_total{queue=\"stats\"}", 0L)
        .containsEntry("argos_agent_dropped_total{queue=\"_other_\"}", 2L)
        .containsEntry("argos_agent_ignored_total{reason=\"path\"}", 1L)
        .containsEntry("argos_agent_ignored_total{reason=\"_other_\"}", 1L)
        .containsEntry("argos_agent_orphan_steps_total{type=\"sql\"}", 1L)
        .containsEntry("argos_agent_orphan_steps_total{type=\"_other_\"}", 2L)
        .containsEntry("argos_agent_module_errors_total{module=\"servlet\"}", 0L)
        .containsEntry("argos_agent_module_errors_total{module=\"jdbc\"}", 1L)
        .containsEntry("argos_agent_module_errors_total{module=\"_other_\"}", 1L)
        .containsEntry("argos_agent_transform_errors_total{reason=\"error\"}", 1L)
        .containsEntry("argos_agent_transform_errors_total{reason=\"circularity\"}", 1L)
        .containsEntry("argos_agent_transform_errors_total{reason=\"_other_\"}", 1L)
        .containsEntry("argos_agent_sql_outside_tx_total", 1L)
        .containsEntry("argos_agent_transactions_leaked_total", 1L)
        .containsEntry("argos_agent_transactions_unanchored_total", 1L)
        .containsEntry("argos_agent_dictionary_overflow_total{kind=\"frame\"}", 1L)
        .containsEntry("argos_agent_dictionary_overflow_total{kind=\"_other_\"}", 1L)
        .containsEntry("argos_agent_service_dictionary_overflow_total", 1L)
        .containsEntry("argos_agent_sql_unparsed_total", 1L)
        .containsEntry("argos_agent_profile_dropped_total", 1L)
        .containsEntry("argos_agent_scope_chain_cut_total", 1L)
        .doesNotContainKey("argos_agent_ignored_total{reason=\"referer\"}");
    for (String name : snapshot.keySet()) {
      assertThat(name).startsWith("argos_agent_");
    }
  }

  @Test
  @Tag("req:N-02")
  @DisplayName("10,000 distinct unknown labels keep the snapshot bounded")
  void labelSetsStayBounded() {
    AgentCounters counters = new AgentCounters();
    int before = counters.snapshot().size();
    String[] names = new String[100];
    for (int i = 0; i < names.length; i++) {
      names[i] = "module-" + i; // more names than module ids: the rest is dropped
    }
    counters.setModuleNames(names);
    for (int i = 0; i < 10_000; i++) {
      counters.incDropped("queue-" + i);
      counters.incIgnored("reason-" + i);
      counters.incModuleError(i);
      counters.incOrphanStep(i);
      counters.incDictionaryOverflow(i);
    }
    Map<String, Long> snapshot = counters.snapshot();
    assertThat(snapshot.size()).isLessThanOrEqualTo(110);
    assertThat(snapshot.size()).isGreaterThan(before);
    assertThat(snapshot).containsEntry("argos_agent_dropped_total{queue=\"_other_\"}", 10_000L);
  }

  @Test
  @Tag("req:N-05")
  @DisplayName("concurrent increments from many threads are all counted (LongAdder, no lock)")
  void concurrentIncrements() throws InterruptedException {
    final AgentCounters counters = new AgentCounters();
    final CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<Thread>();
    for (int t = 0; t < 8; t++) {
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
                  for (int i = 0; i < 10_000; i++) {
                    counters.incDropped("transaction");
                    counters.incSqlOutsideTx();
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
    assertThat(counters.dropped("transaction")).isEqualTo(80_000);
    assertThat(counters.sqlOutsideTx()).isEqualTo(80_000);
  }

  @Test
  @Tag("req:D-23")
  @DisplayName(
      "self-diagnostics JSON: measured gauges only (missing is not zero), no collected data")
  void selfDiagnosticsJson() {
    AgentCounters counters = new AgentCounters();
    counters.setModuleNames(new String[] {"servlet"});
    counters.incModuleError(0);
    SelfDiagnostics.Builder builder =
        SelfDiagnostics.builder()
            .threadLocalContextsLive(0)
            .activeRegistrySize(3)
            .premainMs(412.5)
            .agentThreadsCpuSeconds(0.25)
            .counters(counters.snapshot())
            .module(0, "servlet", 0, 1);
    SelfDiagnostics diagnostics = builder.build();
    String json = diagnostics.toJson();
    assertThat(json)
        .startsWith("{\"format\":1,\"threadlocal_contexts_live\":0,\"active_registry_size\":3")
        .contains("\"agent_threads_cpu_seconds_total\":0.250")
        .contains("\"premain_ms\":412.500")
        .contains("\"argos_agent_module_errors_total{module=\\\"servlet\\\"}\":1")
        .contains(
            "\"modules\":[{\"id\":0,\"name\":\"servlet\",\"state\":\"ENABLED\",\"errors\":1}]")
        .doesNotContain("weakmap_size")
        .doesNotContain("direct_memory_bytes")
        .endsWith("]}");
    assertThat(diagnostics.weakMapSize()).isEqualTo(OptionalLong.empty());
    assertThat(diagnostics.activeRegistrySize()).isEqualTo(OptionalLong.of(3));
    assertThat(diagnostics.directMemoryBytes().isPresent()).isFalse();
    assertThat(diagnostics.modules().get(0).stateName()).isEqualTo("ENABLED");

    for (int i = 0; i < 100; i++) {
      builder.module(i, "m" + i, 0, 0);
    }
    assertThat(builder.build().modules()).hasSize(64);

    StringBuilder escaped = new StringBuilder();
    SelfDiagnostics.string(escaped, "a\"b\\c\nd\u0001");
    assertThat(escaped.toString()).isEqualTo("\"a\\\"b\\\\c\\u000ad\\u0001\"");
  }
}
