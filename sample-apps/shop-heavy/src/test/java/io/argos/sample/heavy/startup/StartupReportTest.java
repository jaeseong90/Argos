/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.SmartLifecycle;

class StartupReportTest {

  private static StartupReport report(boolean enabled, StartupStep... steps) {
    DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    for (int i = 0; i < steps.length; i++) {
      beans.registerSingleton("step" + i, steps[i]);
    }
    return new StartupReport(beans.getBeanProvider(StartupStep.class), enabled);
  }

  @Test
  void runsEveryStepOnceBeforeTheWebServerAndRecordsIt() {
    List<String> ran = new ArrayList<>();
    StartupReport report =
        report(
            true,
            StartupStep.of("first", "lib A", () -> record(ran, "first")),
            StartupStep.of("second", "lib B", () -> record(ran, "second")));

    report.start();
    report.stop();
    report.start();

    assertThat(ran).containsExactly("first", "second");
    assertThat(report.isRunning()).isTrue();
    assertThat(report.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE - 2048);
    Map<String, Object> described = report.describe();
    assertThat(described)
        .containsEntry("heavyWarmup", "ok")
        .containsEntry("startupClassTarget", 30000);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> steps = (List<Map<String, Object>>) described.get("heavySteps");
    assertThat(steps).extracting(s -> s.get("step")).containsExactly("first", "second");
    assertThat(steps.get(0))
        .containsEntry("libraries", "lib A")
        .containsEntry("result", Map.of("ran", "first"))
        .containsKeys("classesLoaded", "ms");
  }

  @Test
  void aFailingStepStopsTheStartupWithItsName() {
    StartupReport report =
        report(
            true,
            StartupStep.of(
                "broken",
                "lib",
                () -> {
                  throw new IllegalArgumentException("no");
                }));

    assertThatThrownBy(report::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("'broken'")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledStepsDoNotRun() {
    List<String> ran = new ArrayList<>();
    StartupReport report = report(false, StartupStep.of("first", "lib", () -> record(ran, "x")));

    report.start();

    assertThat(ran).isEmpty();
    assertThat(report.describe())
        .containsEntry("heavyWarmup", "off")
        .containsEntry("startupLoadedClasses", -1L)
        .containsEntry("startupClassTargetMet", false);
  }

  private static Map<String, Object> record(List<String> ran, String name) {
    ran.add(name);
    return Map.of("ran", name);
  }
}
