/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.TestControls;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Self-protection (10 §5.2 step 5, §14, F-A15): advice and transformation error thresholds, the
 * module states they change and the retransform limiter (T-AG-046, agent part).
 */
@Tag("req:F-A15")
class SelfProtectionTest {
  private static final int A = 50;
  private static final int B = 51;
  private static final int C = 52;

  private final AgentControl control = TestControls.newControl();

  @AfterEach
  void resetModules() {
    for (int id : new int[] {A, B, C}) {
      control.setModuleState(id, ModuleHealth.ENABLED);
    }
  }

  private static String[] names() {
    String[] names = new String[64];
    names[A] = "alpha";
    names[B] = "beta";
    names[C] = "gamma";
    return names;
  }

  @Test
  @DisplayName(
      "T-AG-040 advice errors are counted per module; at instrumentation.error-threshold the module"
          + " is DISABLED_BY_ERRORS once")
  void adviceErrorThreshold() {
    AgentCounters counters = new AgentCounters();
    SelfProtection protection = new SelfProtection(control, counters, 3, 10);
    protection.setModuleNames(names());
    control.setModuleState(A, ModuleHealth.ENABLED);
    int before = ModuleHealth.errors(A);
    protection.adviceError(A, new IllegalStateException("secret value"));
    protection.adviceError(A, null);
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.ENABLED);
    protection.adviceError(A, new IllegalStateException());
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    protection.adviceError(A, new IllegalStateException());
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    assertThat(protection.adviceErrors(A)).isEqualTo(4);
    assertThat(ModuleHealth.errors(A) - before).isEqualTo(4);
    assertThat(counters.moduleErrors(A)).isEqualTo(4);
    assertThat(counters.snapshot())
        .containsEntry("argos_agent_module_errors_total{module=\"alpha\"}", 4L);
    // Invalid module ids are counted under _other_ and never index outside the 64 slots.
    protection.adviceError(-1, new RuntimeException());
    protection.adviceError(64, new RuntimeException());
    assertThat(protection.adviceErrors(64)).isZero();
    assertThat(counters.snapshot())
        .containsEntry("argos_agent_module_errors_total{module=\"_other_\"}", 2L);
  }

  @Test
  @DisplayName(
      "T-AG-041 transformation errors disable every module of the failing type at"
          + " instrumentation.transform-error-threshold; circularity is its own reason")
  void transformErrorThreshold() {
    AgentCounters counters = new AgentCounters();
    SelfProtection protection = new SelfProtection(control, counters, 100, 2);
    protection.setModuleNames(names());
    control.setModuleState(A, ModuleHealth.ENABLED);
    control.setModuleState(B, ModuleHealth.ENABLED);
    long both = (1L << A) | (1L << B);
    protection.transformErrors(both, false);
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.ENABLED);
    protection.transformErrors(1L << A, true);
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    assertThat(ModuleHealth.state(B)).isEqualTo(ModuleHealth.ENABLED);
    protection.transformErrors(both, false);
    assertThat(ModuleHealth.state(B)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    assertThat(protection.transformErrorCount(A)).isEqualTo(3);
    assertThat(protection.transformErrorCount(B)).isEqualTo(2);
    assertThat(counters.transformErrors("error")).isEqualTo(2);
    assertThat(counters.transformErrors("circularity")).isEqualTo(1);
    // A failure of a type no module transformed is counted but changes no module.
    protection.transformErrors(0L, false);
    assertThat(counters.transformErrors()).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "a module turned off by configuration or the server stays off; a test-fault module moves to"
          + " DISABLED_BY_ERRORS")
  void onlyEnabledModulesAreDisabledByErrors() {
    SelfProtection protection = new SelfProtection(control, new AgentCounters(), 1, 1);
    control.setModuleState(A, ModuleHealth.DISABLED_BY_CONFIG);
    control.setModuleState(B, ModuleHealth.DISABLED_BY_REMOTE);
    control.setModuleState(C, ModuleHealth.TEST_FAULT);
    protection.adviceError(A, new RuntimeException());
    protection.transformErrors(1L << B, false);
    protection.adviceError(C, new RuntimeException());
    assertThat(ModuleHealth.state(A)).isEqualTo(ModuleHealth.DISABLED_BY_CONFIG);
    assertThat(ModuleHealth.state(B)).isEqualTo(ModuleHealth.DISABLED_BY_REMOTE);
    assertThat(ModuleHealth.state(C)).isEqualTo(ModuleHealth.DISABLED_BY_ERRORS);
    // Thresholds below 1 mean 1 (a misconfiguration never disables protection).
    assertThat(new SelfProtection(control, new AgentCounters(), 0, -5).adviceErrorThreshold())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("advice errors from many threads at once are all counted, nothing blocks (N-05)")
  void concurrentAdviceErrors() throws InterruptedException {
    final AgentCounters counters = new AgentCounters();
    final SelfProtection protection = new SelfProtection(control, counters, 1_000_000, 10);
    control.setModuleState(A, ModuleHealth.ENABLED);
    Thread[] threads = new Thread[8];
    for (int t = 0; t < threads.length; t++) {
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < 10_000; i++) {
                  protection.adviceError(A, null);
                }
              });
      threads[t].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(protection.adviceErrors(A)).isEqualTo(80_000);
    assertThat(counters.moduleErrors(A)).isEqualTo(80_000);
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-046 retransform limiter: at most 50 classes per request and one request per minute"
          + " (injected clock)")
  void retransformLimiter() {
    final AtomicLong now = new AtomicLong(1_000_000L);
    RetransformLimiter limiter = new RetransformLimiter(now::get);
    assertThat(limiter.millisUntilNext()).isZero();
    assertThat(limiter.tryAcquire(51)).isEqualTo(RetransformLimiter.Decision.TOO_MANY_CLASSES);
    // A refused request does not use up the minute; a request without classes neither.
    assertThat(limiter.tryAcquire(0)).isEqualTo(RetransformLimiter.Decision.ALLOWED);
    assertThat(limiter.tryAcquire(50)).isEqualTo(RetransformLimiter.Decision.ALLOWED);
    assertThat(limiter.tryAcquire(1)).isEqualTo(RetransformLimiter.Decision.TOO_SOON);
    assertThat(limiter.tryAcquire(1).allowed()).isFalse();
    now.addAndGet(59_999L);
    assertThat(limiter.millisUntilNext()).isEqualTo(1);
    assertThat(limiter.tryAcquire(1)).isEqualTo(RetransformLimiter.Decision.TOO_SOON);
    now.addAndGet(1L);
    assertThat(limiter.tryAcquire(1)).isEqualTo(RetransformLimiter.Decision.ALLOWED);
    assertThat(limiter.tryAcquire(1)).isEqualTo(RetransformLimiter.Decision.TOO_SOON);
    assertThat(RetransformLimiter.MAX_CLASSES).isEqualTo(50);
    assertThat(RetransformLimiter.MIN_INTERVAL_MILLIS).isEqualTo(60_000L);
    assertThat(RetransformLimiter.REASON).isEqualTo("retransform_limit");
    assertThat(new RetransformLimiter().tryAcquire(1).allowed()).isTrue();
  }

  @Test
  @DisplayName("the retransform limiter lets exactly one of many concurrent requests through")
  void retransformLimiterConcurrent() throws InterruptedException {
    final RetransformLimiter limiter = new RetransformLimiter(() -> 5_000L);
    final AtomicLong allowed = new AtomicLong();
    Thread[] threads = new Thread[8];
    for (int t = 0; t < threads.length; t++) {
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < 1000; i++) {
                  if (limiter.tryAcquire(10).allowed()) {
                    allowed.incrementAndGet();
                  }
                }
              });
      threads[t].start();
    }
    for (Thread thread : threads) {
      thread.join();
    }
    assertThat(allowed.get()).isEqualTo(1);
  }
}
