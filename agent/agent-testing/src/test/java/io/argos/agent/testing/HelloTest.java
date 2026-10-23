/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HelloTest {

  @Test
  @DisplayName("hello: agent-testing tests run on the JDK 21 test harness toolchain (DSN-19-02)")
  void runsOnJava21() {
    assertThat(Runtime.version().feature()).isEqualTo(21);
  }
}
