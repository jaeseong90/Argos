/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:F-A15")
class ArgosTest {
  private static final int MODULE = 42;
  private final AgentControl control = new AgentControl();

  @AfterEach
  void reset() {
    control.setEnabled(true);
    control.setModuleState(MODULE, ModuleHealth.ENABLED);
    control.installTracer(null);
  }

  @Test
  @DisplayName("T-AG-043 Argos.skip: false when enabled; true with the kill switch off (1st stage)")
  void killSwitch() {
    assertThat(Argos.skip(MODULE)).isFalse();
    control.setEnabled(false);
    assertThat(Argos.enabled()).isFalse();
    assertThat(Argos.skip(MODULE)).isTrue();
    control.setEnabled(true);
    assertThat(Argos.skip(MODULE)).isFalse();
  }

  @Test
  @DisplayName("Argos.skip: true for disabled modules and module ids outside 0..63")
  void moduleStates() {
    for (int state :
        new int[] {
          ModuleHealth.DISABLED_BY_CONFIG,
          ModuleHealth.DISABLED_BY_ERRORS,
          ModuleHealth.DISABLED_BY_REMOTE
        }) {
      control.setModuleState(MODULE, state);
      assertThat(Argos.skip(MODULE)).as(ModuleHealth.stateName(state)).isTrue();
      assertThat(ModuleHealth.enabled(MODULE)).isFalse();
    }
    assertThat(Argos.skip(-1)).isTrue();
    assertThat(Argos.skip(ModuleHealth.MAX_MODULES)).isTrue();
  }

  @Test
  @DisplayName("T-AG-040 Argos.skip throws for an injected test fault (argos.test.faults)")
  void testFault() {
    control.setModuleState(MODULE, ModuleHealth.TEST_FAULT);
    assertThatThrownBy(() -> Argos.skip(MODULE))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("argos.test.faults");
  }

  @Test
  @DisplayName("T-AG-050 Argos.skip: true on agent threads (re-entrance)")
  void agentThreads() throws InterruptedException {
    final AtomicBoolean skipped = new AtomicBoolean();
    Thread thread =
        ArgosThread.factory("argos-test")
            .newThread(
                new Runnable() {
                  @Override
                  public void run() {
                    skipped.set(Argos.skip(MODULE));
                  }
                });
    thread.start();
    thread.join();
    assertThat(skipped).isTrue();
  }

  @Test
  @DisplayName("Argos.adviceError forwards to Tracer.moduleError and never throws")
  void adviceError() {
    final List<Object> calls = new ArrayList<Object>();
    control.installTracer(tracer(calls, false));
    IllegalStateException error = new IllegalStateException("boom");
    Argos.adviceError(error, MODULE);
    assertThat(calls).containsExactly(MODULE, error);

    control.installTracer(tracer(calls, true));
    Argos.adviceError(error, MODULE); // the tracer throws: swallowed
  }

  @Test
  @DisplayName("Argos.tracer() is the no-op tracer until a tracer is installed")
  void noopTracerByDefault() {
    assertThat(Argos.tracer()).isSameAs(NoopTracer.INSTANCE);
    assertThat(Argos.tracer().begin(Kind.HTTP, "x", 0)).isNull();
    assertThat(Argos.tracer().stepStart(null, StepType.SQL, 0, 0, 0)).isEqualTo(-1);
    assertThat(Argos.tracer().capture()).isSameAs(CaptureConfig.DEFAULT);
  }

  private static Tracer tracer(final List<Object> calls, final boolean fail) {
    return (Tracer)
        Proxy.newProxyInstance(
            Tracer.class.getClassLoader(),
            new Class<?>[] {Tracer.class},
            new InvocationHandler() {
              @Override
              public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getName().equals("moduleError")) {
                  if (fail) {
                    throw new IllegalStateException("tracer failure");
                  }
                  calls.add(args[0]);
                  calls.add(args[1]);
                }
                return null;
              }
            });
  }
}
