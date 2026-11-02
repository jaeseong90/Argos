/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.argos.agent.testing.launcher.JvmOptions;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The matrixTest JVM verifies boot class path classes (19 §11, 18 §5.10, T-AG-006). */
class BytecodeVerificationFlagMatrixTest {
  @Test
  @Tag("req:F-A15")
  @DisplayName(
      "T-AG-006 matrixTest runs with the harness preset -XX:+BytecodeVerificationLocal"
          + " (argos.integration-test)")
  void matrixTestJvmVerifiesLocalClasses() {
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
}
