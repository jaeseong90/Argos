/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-BLD-004 (18 §5.10, F-A35) with the harness's {@link UnsafeScanner} on the release agent jar,
 * against the same allow-list as {@code test/agent/check-shading.sh} ({@code
 * test/agent/unsafe-allowlist.txt}, empty).
 */
@Tag("req:F-A35")
class AgentJarScanTest {
  @Test
  @DisplayName(
      "T-BLD-004 the release agent jar has no sun.misc.Unsafe reference outside the allow-list")
  void releaseJarHasNoUnsafeReference() {
    Path jar = Path.of(System.getProperty("argos.test.releaseAgentJar"));
    Path allowlist = Path.of(System.getProperty("argos.test.unsafeAllowlist"));
    assertThat(UnsafeScanner.readAllowlist(allowlist)).isEmpty();
    assertThat(UnsafeScanner.violations(jar, allowlist)).isEmpty();
    // The scanner reads the jar's classes (bootstrap at the root, the rest as inst/**.classdata).
    assertThat(AgentJars.version(jar)).endsWith("-SNAPSHOT");
  }
}
