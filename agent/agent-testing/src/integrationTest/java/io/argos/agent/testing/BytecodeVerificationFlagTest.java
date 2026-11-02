/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.argos.agent.testing.launcher.Jdk;
import io.argos.agent.testing.launcher.JvmOptions;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * T-AG-006 (19 §11, 18 §5.10): the integrationTest JVM itself verifies boot class path classes, and
 * the harness preset ({@link JvmOptions#bytecodeVerification}) turns the flag on in every JDK the
 * sample apps run on ({@code -Pagent.attach.jdks}).
 */
class BytecodeVerificationFlagTest {
  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-006 integrationTest runs with the harness preset -XX:+BytecodeVerificationLocal"
          + " (argos.integration-test)")
  void integrationTestJvmVerifiesLocalClasses() {
    assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
        .containsSubsequence(JvmOptions.bytecodeVerification(Runtime.version().feature()));
    assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
        .containsSubsequence("-XX:+UnlockDiagnosticVMOptions", "-XX:+BytecodeVerificationLocal");
    // The flag is in effect, not only on the command line.
    assertThat(
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                .getVMOption("BytecodeVerificationLocal")
                .getValue())
        .isEqualTo("true");
  }

  @TestFactory
  @Tag("req:F-A15")
  Stream<DynamicTest> presetIsInEffectOnEveryJdk() {
    return Arrays.stream(System.getProperty("argos.test.jdks", "8,21").split(",", -1))
        .map(String::trim)
        .map(Integer::parseInt)
        .map(
            jdk ->
                DynamicTest.dynamicTest(
                    "T-AG-006 JDK "
                        + jdk
                        + ": the harness preset enables BytecodeVerificationLocal",
                    () -> assertThat(flagValue(jdk)).isEqualTo("true")));
  }

  /** {@code BytecodeVerificationLocal} of {@code java <presets> -XX:+PrintFlagsFinal -version}. */
  static String flagValue(int feature) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(Jdk.of(feature).java().toString());
    command.addAll(JvmOptions.defaults(feature, true));
    command.add("-XX:+PrintFlagsFinal");
    command.add("-version");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    // JDK 8: "bool BytecodeVerificationLocal := true {product}"; 17+: "= true {diagnostic}".
    return output
        .lines()
        .filter(line -> line.matches("\\s*bool\\s+BytecodeVerificationLocal\\s.*"))
        .map(line -> line.replaceAll(".*:?=\\s*(\\S+).*", "$1"))
        .findFirst()
        .orElse("absent:\n" + output);
  }
}
