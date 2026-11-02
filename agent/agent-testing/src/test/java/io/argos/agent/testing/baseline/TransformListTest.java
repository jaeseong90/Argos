/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The transformed-class list format of TransformRecorder (agent-core). */
class TransformListTest {

  @Test
  @DisplayName("lines parse into class, modules, loader and the retransformed flag")
  void parses() {
    TransformList list =
        TransformList.of(
            "# Argos transformed classes: <class> <modules> <loader> [retransformed]",
            "jakarta.servlet.http.HttpServlet servlet org.springframework.boot.loader.launch"
                + ".LaunchedClassLoader",
            "java.util.concurrent.ThreadPoolExecutor executor,test-probe-jdk bootstrap"
                + " retransformed",
            "");
    assertThat(list.entries()).hasSize(2);
    TransformList.Entry jdk = list.entry("java.util.concurrent.ThreadPoolExecutor");
    assertThat(jdk.modules()).containsExactly("executor", "test-probe-jdk");
    assertThat(jdk.loader()).isEqualTo("bootstrap");
    assertThat(jdk.retransformed()).isTrue();
    assertThat(list.entry("jakarta.servlet.http.HttpServlet").retransformed()).isFalse();
    assertThat(list.classesOf("servlet")).containsExactly("jakarta.servlet.http.HttpServlet");
    assertThat(list.classesOf("jdbc")).isEmpty();
    assertThat(list.lines())
        .containsExactly(
            "jakarta.servlet.http.HttpServlet servlet"
                + " org.springframework.boot.loader.launch.LaunchedClassLoader",
            "java.util.concurrent.ThreadPoolExecutor executor,test-probe-jdk bootstrap"
                + " retransformed");
    assertThat(list.entry("x.Y")).isNull();
  }

  @Test
  @DisplayName("an absent file is an empty list; malformed lines are rejected")
  void absentAndMalformed() {
    assertThat(TransformList.read(Path.of("does-not-exist", "transformed.txt")).isEmpty()).isTrue();
    assertThatThrownBy(() -> TransformList.of("only-a-class"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
