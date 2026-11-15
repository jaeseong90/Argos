/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Premain class budgets (ADR-0009 C-79): record, compare, transport classes. */
@Tag("req:N-30")
class PremainClassBudgetTest {
  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-NFR-30a a run within the budget passes; more classes fail and are named; transport"
          + " classes always fail")
  void budget() {
    Path file = dir.resolve("premain-classes").resolve(PremainClassBudget.fileName("trivial", 21));
    PremainClassBudget.record(file, "test", List.of("b.B", "a.A", "c.C", "a.A"));
    assertThat(PremainClassBudget.read(file)).containsExactly("a.A", "b.B", "c.C");

    PremainClassBudget.Result same = PremainClassBudget.compare(file, List.of("c.C", "a.A", "b.B"));
    assertThat(same.passed()).isTrue();
    assertThat(same.budget()).isEqualTo(3);

    PremainClassBudget.Result fewer = PremainClassBudget.compare(file, List.of("a.A"));
    assertThat(fewer.passed()).isTrue();
    assertThat(fewer.actual()).isEqualTo(1);

    // Same count, one renamed class: within the budget (the count is the budget), named.
    PremainClassBudget.Result renamed =
        PremainClassBudget.compare(file, List.of("a.A", "b.B", "d.D"));
    assertThat(renamed.passed()).isTrue();
    assertThat(renamed.added()).containsExactly("d.D");

    PremainClassBudget.Result more =
        PremainClassBudget.compare(file, List.of("a.A", "b.B", "c.C", "e.E"));
    assertThat(more.passed()).isFalse();
    assertThat(more.describe()).contains("4 premain classes (budget 3)").contains("e.E");

    String grpc = "io.argos.agent.shaded.io.grpc.ManagedChannel";
    PremainClassBudget.Result transport = PremainClassBudget.compare(file, List.of("a.A", grpc));
    assertThat(transport.passed()).isFalse();
    assertThat(transport.forbidden()).containsExactly(grpc);
    assertThat(
            PremainClassBudget.forbidden(
                List.of(
                    "io.argos.agent.shaded.io.netty.channel.Channel",
                    "io.argos.agent.shaded.com.google.protobuf.Message",
                    "io.argos.agent.shaded.net.bytebuddy.ByteBuddy")))
        .hasSize(2);
  }

  @Test
  @DisplayName(
      "T-NFR-30a companion libraries of the shaded gRPC transport (Guava, PerfMark, Gson, Google"
          + " API types) are transport classes; ByteBuddy, JCTools and the agent's own are not")
  void transportCompanionPackages() {
    String guava = "io.argos.agent.shaded.com.google.common.collect.ImmutableList";
    String perfmark = "io.argos.agent.shaded.io.perfmark.PerfMark";
    String gson = "io.argos.agent.shaded.com.google.gson.Gson";
    String rpcStatus = "io.argos.agent.shaded.com.google.rpc.Status";
    assertThat(
            PremainClassBudget.forbidden(
                List.of(
                    guava,
                    perfmark,
                    gson,
                    rpcStatus,
                    "io.argos.agent.shaded.net.bytebuddy.agent.builder.AgentBuilder",
                    "io.argos.agent.shaded.org.jctools.queues.MpscArrayQueue",
                    "io.argos.agent.core.boot.AgentStarter",
                    "com.google.common.collect.ImmutableList")))
        .containsExactly(guava, perfmark, gson, rpcStatus);
    Path file = dir.resolve(PremainClassBudget.fileName("trivial", 8));
    PremainClassBudget.record(file, "test", List.of("a.A", "b.B"));
    PremainClassBudget.Result result = PremainClassBudget.compare(file, List.of("a.A", guava));
    assertThat(result.passed()).as("within the count, but a transport class").isFalse();
    assertThat(result.forbidden()).containsExactly(guava);
    assertThat(result.describe()).contains("transport classes on the premain thread: [" + guava);
    for (String prefix : PremainClassBudget.FORBIDDEN_PREFIXES) {
      assertThat(prefix).startsWith("io.argos.agent.shaded.").endsWith(".");
    }
  }
}
