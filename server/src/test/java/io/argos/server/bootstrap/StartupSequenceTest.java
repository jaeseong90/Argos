/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/** The start order of DSN-12-15 as enforced by {@link StartupSequence} (no Spring context). */
@Tag("req:DSN-12-15")
@Tag("req:N-22")
class StartupSequenceTest {

  private final List<String> ran = new ArrayList<>();

  /** A step that records its name when it runs. */
  private class Step implements StartupPhase {
    private final PhaseOrder order;
    private final String name;

    Step(PhaseOrder order, String name) {
      this.order = order;
      this.name = name;
    }

    @Override
    public PhaseOrder order() {
      return order;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public void run() throws Exception {
      ran.add(name);
    }
  }

  @Order(1)
  private final class FirstStep extends Step {
    FirstStep(PhaseOrder order, String name) {
      super(order, name);
    }
  }

  private final class OrderedStep extends Step implements Ordered {
    private final int value;

    OrderedStep(PhaseOrder order, String name, int value) {
      super(order, name);
      this.value = value;
    }

    @Override
    public int getOrder() {
      return value;
    }
  }

  @Test
  @DisplayName("phases run in the fixed order CONFIG < KEYS < PG < CH < WAL < LISTENERS")
  void phasesRunInFixedOrder() {
    // Registered in reverse order on purpose: bean order must not matter.
    List<StartupPhase> steps = new ArrayList<>();
    PhaseOrder[] phases = PhaseOrder.values();
    for (int i = phases.length - 1; i >= 0; i--) {
      steps.add(new Step(phases[i], phases[i].name().toLowerCase(Locale.ROOT)));
    }
    StartupSequence sequence = new StartupSequence(steps);

    sequence.run();

    assertThat(ran)
        .containsExactly(
            "config",
            "keys",
            "postgres_migration",
            "clickhouse_migration",
            "wal_recovery",
            "listeners");
    assertThat(sequence.isComplete()).isTrue();
    assertThat(sequence.results())
        .extracting(StartupSequence.StepResult::phase)
        .containsExactly(PhaseOrder.values());
    assertThat(sequence.results()).allSatisfy(r -> assertThat(r.durationMillis()).isNotNegative());
  }

  @Test
  @DisplayName("the PhaseOrder constants are the DSN-12-15 order (contract for later WOs)")
  void phaseOrderIsTheContract() {
    assertThat(PhaseOrder.values())
        .containsExactly(
            PhaseOrder.CONFIG,
            PhaseOrder.KEYS,
            PhaseOrder.POSTGRES_MIGRATION,
            PhaseOrder.CLICKHOUSE_MIGRATION,
            PhaseOrder.WAL_RECOVERY,
            PhaseOrder.LISTENERS);
  }

  @Test
  @DisplayName("within one phase: @Order / Ordered first, then the step name")
  void orderWithinPhase() {
    StartupSequence sequence =
        new StartupSequence(
            List.of(
                new Step(PhaseOrder.KEYS, "z-unordered"),
                new Step(PhaseOrder.KEYS, "a-unordered"),
                new OrderedStep(PhaseOrder.KEYS, "ordered-5", 5),
                new FirstStep(PhaseOrder.KEYS, "annotated-1"),
                new Step(PhaseOrder.CONFIG, "config")));

    sequence.run();

    assertThat(ran)
        .containsExactly("config", "annotated-1", "ordered-5", "a-unordered", "z-unordered");
  }

  @Test
  @DisplayName("a failing step stops the sequence: later steps and phases do not run")
  void failureStopsSequence() {
    StartupPhase failing =
        new Step(PhaseOrder.POSTGRES_MIGRATION, "postgres-flyway") {
          @Override
          public void run() throws Exception {
            ran.add("postgres-flyway");
            throw new StartupPhaseException("migration V002 failed: relation exists");
          }
        };
    StartupSequence sequence =
        new StartupSequence(
            List.of(
                new Step(PhaseOrder.CONFIG, "config"),
                failing,
                new Step(PhaseOrder.POSTGRES_MIGRATION, "zz-after-in-same-phase"),
                new Step(PhaseOrder.CLICKHOUSE_MIGRATION, "clickhouse"),
                new Step(PhaseOrder.LISTENERS, "grpc")));

    assertThatThrownBy(sequence::run)
        .isInstanceOfSatisfying(
            StartupAbortedException.class,
            e -> {
              assertThat(e.phase()).isEqualTo(PhaseOrder.POSTGRES_MIGRATION);
              assertThat(e.step()).isEqualTo("postgres-flyway");
              assertThat(e.getMessage())
                  .startsWith("Startup phase POSTGRES_MIGRATION step postgres-flyway failed after ")
                  .contains(
                      "ms: migration V002 failed: relation exists. The server does not start.");
              assertThat(e).isInstanceOf(ExitCodeGenerator.class);
              assertThat(e.getExitCode()).isEqualTo(1);
              assertThat(e.getCause()).isInstanceOf(StartupPhaseException.class);
            });
    assertThat(ran).containsExactly("config", "postgres-flyway");
    assertThat(sequence.isComplete()).isFalse();
    assertThat(sequence.results())
        .extracting(StartupSequence.StepResult::name)
        .containsExactly("config");
  }

  @Test
  @DisplayName("an unexpected exception is reported by class and message on one bounded line")
  void unexpectedExceptionReason() {
    IllegalStateException failure =
        new IllegalStateException("line one\nline two\r\n\tline three" + "x".repeat(1000));

    String reason = StartupSequence.reason(failure);

    assertThat(reason).startsWith("java.lang.IllegalStateException: line one line two line three");
    assertThat(reason).doesNotContain("\n", "\r", "\t");
    assertThat(reason).hasSize(StartupSequence.MAX_REASON_LENGTH + 3).endsWith("...");
    assertThat(StartupSequence.reason(new NullPointerException()))
        .isEqualTo("java.lang.NullPointerException");
  }

  @Test
  @DisplayName("a StartupPhaseException contributes its message only, never its cause")
  void phaseExceptionReasonHidesCause() {
    StartupPhaseException failure =
        new StartupPhaseException(
            "PostgreSQL rejected the owner account",
            new IllegalStateException("cause with a secret-like value"));

    assertThat(StartupSequence.reason(failure)).isEqualTo("PostgreSQL rejected the owner account");
  }

  @Test
  @DisplayName("an interrupted step keeps the interrupt flag")
  void interruptedStep() {
    StartupPhase interrupted =
        new Step(PhaseOrder.WAL_RECOVERY, "wal") {
          @Override
          public void run() throws Exception {
            throw new InterruptedException("stop");
          }
        };
    try {
      assertThatThrownBy(new StartupSequence(List.of(interrupted))::run)
          .isInstanceOf(StartupAbortedException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("two steps with the same name are rejected; the sequence runs only once")
  void duplicatesAndRerun() {
    assertThatThrownBy(
            () ->
                new StartupSequence(
                    List.of(new Step(PhaseOrder.CONFIG, "x"), new Step(PhaseOrder.KEYS, "x"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Duplicate startup step name: x");

    StartupSequence sequence = new StartupSequence(List.of());
    sequence.run();
    assertThat(sequence.isComplete()).isTrue();
    assertThatThrownBy(sequence::run).isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("the failure reporter claims aborted starts only (anywhere in the cause chain)")
  void failureReporter() {
    StartupAbortedException aborted =
        new StartupAbortedException(PhaseOrder.CONFIG, "config", "message", null);
    StartupFailureReporter reporter = new StartupFailureReporter();

    assertThat(reporter.reportException(aborted)).isTrue();
    assertThat(reporter.reportException(new IllegalStateException("wrapped", aborted))).isTrue();
    assertThat(reporter.reportException(new IllegalStateException("other"))).isFalse();
    assertThat(StartupFailureReporter.find(new RuntimeException(new RuntimeException(aborted))))
        .isSameAs(aborted);
  }
}
