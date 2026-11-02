/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code --check} / {@code --version} (10 §13.3, WO-02 subset: JDK item 6). */
@Tag("req:F-A27")
class CheckCommandTest {

  @Test
  @DisplayName("U-4: Java 8 needs update 252 or later (TLS ALPN); Java 9+ is supported")
  void jdkVersions() {
    assertThat(CheckCommand.jdkSupported("1.8.0_252")).isTrue();
    assertThat(CheckCommand.jdkSupported("1.8.0_504")).isTrue();
    assertThat(CheckCommand.jdkSupported("1.8.0_251")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.8.0_202-b08")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.8.0")).isFalse();
    assertThat(CheckCommand.jdkSupported("1.7.0_80")).isFalse();
    assertThat(CheckCommand.jdkSupported("11.0.32.1")).isTrue();
    assertThat(CheckCommand.jdkSupported("25")).isTrue();
  }

  @Test
  @DisplayName("exit codes: 0 pass/warn, 1 fail, 2 usage; output names the version")
  void exitCodes() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true);
    assertThat(CheckCommand.run(new String[] {"--version"}, null, out, "21")).isZero();
    assertThat(CheckCommand.run(new String[] {"--check"}, null, out, "1.8.0_504")).isZero();
    assertThat(CheckCommand.run(new String[] {"--check"}, null, out, "1.8.0_201")).isEqualTo(1);
    assertThat(CheckCommand.run(new String[0], null, out, "21")).isEqualTo(2);
    String text = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    assertThat(text)
        .contains("Argos agent unknown (commit unknown, protocol unknown)")
        .contains("[PASS] JDK 1.8.0_504")
        .contains("[FAIL] JDK 1.8.0_201")
        .contains("usage: java -jar argos-agent.jar --check | --version");
  }
}
