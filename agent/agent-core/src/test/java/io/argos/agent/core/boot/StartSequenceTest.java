/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.masking.Masker;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The start sequence's pieces (10 §4.3, ADR-0009 C-78, C-79, T-BLD-010). */
class StartSequenceTest {
  @TempDir File dir;

  @Test
  @Tag("req:SEC-36")
  @DisplayName(
      "P5 installs the masker of capture.mask-patterns; without user patterns the default set"
          + " stays installed")
  void installMasker() {
    try {
      AgentStarter.installMasker(AgentConfig.of(Collections.<String, String>emptyMap()));
      assertThat(Masker.current()).isSameAs(Masker.defaults());
      AgentStarter.installMasker(
          AgentConfig.of(Collections.singletonMap("capture.mask-patterns", "ORD-[0-9]+")));
      assertThat(Masker.current().userPatternCount()).isEqualTo(1);
      assertThat(Masker.current().mask("ORD-77 for kim@example.test"))
          .isEqualTo("<masked> for <email>");
    } finally {
      Masker.install(null);
    }
  }

  @Test
  @Tag("req:N-30")
  @DisplayName(
      "C-79 start timing: steps add up, the N-30 figure covers P0-P7, loader statistics are"
          + " printed when known")
  void startTimer() {
    long premain = System.nanoTime() - 5_000_000L;
    StartTimer timer = new StartTimer(premain, premain + 2_000_000L);
    timer.begin(StartTimer.P3);
    timer.begin(StartTimer.P4);
    timer.begin(StartTimer.P3);
    timer.begin(StartTimer.P5);
    timer.begin(StartTimer.P6);
    timer.begin(StartTimer.P7);
    timer.begin(StartTimer.P8);
    assertThat(timer.bootstrapMillis()).isEqualTo(2.0);
    assertThat(timer.premainMillis()).isGreaterThanOrEqualTo(timer.n30Millis());
    assertThat(timer.n30Millis()).isGreaterThanOrEqualTo(2.0);
    String line = timer.describe(new long[] {950, 3 * 1024 * 1024, 40_000_000L, 25_500_000L}, 900);
    assertThat(line)
        .startsWith("start timing (ms): P0-P2 2.000, P3 logger ")
        .contains(", P4 config ", ", P5 tracer ", ", P6 builder ", ", P7 threads ")
        .contains("(N-30 budget 500 ms)")
        .contains("agent class loader: 950 classes, 3072 KiB read in 40.000 ms, defined in 25.500")
        .endsWith("900 classes defined on the premain thread");
    assertThat(timer.describe(null, -1)).doesNotContain("class loader").doesNotContain("premain t");
    // Without a class counter no step shows a class count (unknown, not 0: C-4).
    assertThat(line).doesNotContain(" cl)");
    assertThat(timer.classes(StartTimer.P6)).isEqualTo(-1);
    assertThat(StartTimer.format(1.0005)).isEqualTo("1.001");
    assertThat(StartTimer.format(0.04)).isEqualTo("0.040");
    assertThat(StartTimer.format(12)).isEqualTo("12.000");
    assertThat(StartTimer.format(-1.5)).isEqualTo("-1.500");
  }

  @Test
  @Tag("req:N-30")
  @DisplayName(
      "C-79 start timing counts the agent classes each step defined (P3 twice adds up, P0-P2"
          + " shows the classes before the core)")
  void startTimerCountsClasses() {
    final long[] defined = {12};
    StartTimer.ClassCounter counter =
        new StartTimer.ClassCounter() {
          @Override
          public long definedClasses() {
            return defined[0];
          }
        };
    long premain = System.nanoTime() - 1_000_000L;
    StartTimer timer = new StartTimer(premain, premain + 500_000L, counter);
    timer.begin(StartTimer.P3);
    defined[0] += 5;
    timer.begin(StartTimer.P4);
    defined[0] += 30;
    timer.begin(StartTimer.P3);
    defined[0] += 2;
    timer.begin(StartTimer.P5);
    defined[0] += 40;
    timer.begin(StartTimer.P6);
    defined[0] += 900;
    timer.begin(StartTimer.P7);
    defined[0] += 7;
    timer.begin(StartTimer.P8);
    assertThat(timer.bootstrapClasses()).isEqualTo(12);
    assertThat(timer.classes(StartTimer.P3)).isEqualTo(7);
    assertThat(timer.classes(StartTimer.P4)).isEqualTo(30);
    assertThat(timer.classes(StartTimer.P5)).isEqualTo(40);
    assertThat(timer.classes(StartTimer.P6)).isEqualTo(900);
    assertThat(timer.classes(StartTimer.P7)).isEqualTo(7);
    assertThat(timer.describe(null, -1))
        .startsWith("start timing (ms): P0-P2 0.500 (12 cl), P3 logger ")
        .contains(" (7 cl), P4 config ", " (30 cl), P5 tracer ", " (900 cl), P7 threads ");
    // A counter that fails is "unknown" for the step, never a negative count.
    StartTimer broken =
        new StartTimer(
            premain,
            premain,
            new StartTimer.ClassCounter() {
              @Override
              public long definedClasses() {
                throw new IllegalStateException("x");
              }
            });
    broken.begin(StartTimer.P3);
    broken.begin(StartTimer.P8);
    assertThat(broken.describe(null, -1)).doesNotContain(" cl)");
    assertThat(AgentStarter.classCounter(TestControls.newControl()))
        .isSameAs(StartTimer.NO_COUNTER);
  }

  private File jar(String version) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    if (version != null) {
      manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
    }
    File jar = new File(dir, "argos-agent-" + version + ".jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
      out.flush();
    }
    return jar;
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
      "T-BLD-010 the agent identity falls back to the jar when the loader has no manifest; the"
          + " release marker fails closed")
  void agentInfoFailsClosed() throws IOException {
    AgentControl outsideJar = TestControls.newControl();
    assertThat(StartSequenceTest.info(outsideJar, jar("0.0.0-SNAPSHOT")).isReleaseBuild())
        .isFalse();
    assertThat(StartSequenceTest.info(outsideJar, jar("1.2.3")).isReleaseBuild()).isTrue();
    AgentInfo unversioned = StartSequenceTest.info(outsideJar, jar(null));
    assertThat(unversioned.isReleaseBuild()).isTrue();
    assertThat(StartSequenceTest.info(outsideJar, new File(dir, "missing.jar")).isReleaseBuild())
        .isTrue();
    // Unit tests run the core outside a jar: a development build.
    assertThat(StartSequenceTest.info(outsideJar, null).isReleaseBuild()).isFalse();
  }

  private static AgentInfo info(AgentControl control, File jar) {
    return AgentStarter.agentInfo(control, jar);
  }
}
