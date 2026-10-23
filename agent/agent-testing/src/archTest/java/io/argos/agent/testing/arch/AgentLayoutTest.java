/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Name mappings of {@link AgentLayout} used by the advice rules. */
class AgentLayoutTest {
  private static final AgentLayout LAYOUT =
      new AgentLayout(
          AgentLayout.PRODUCTION_ROOT,
          List.of(),
          List.of("net.bytebuddy"),
          List.of("api", "servlet-async", "spring-webmvc", "httpclient-jdk11", "jdbc"),
          AdviceAllowlist.empty());

  @Test
  @DisplayName("Advice annotation names are recognised in original and relocated (shaded) form")
  void adviceMemberNames() {
    assertThat(LAYOUT.adviceMemberName("net.bytebuddy.asm.Advice$FieldSetterHandle"))
        .contains("FieldSetterHandle");
    assertThat(
            LAYOUT.adviceMemberName(
                "io.argos.agent.shaded.net.bytebuddy.asm.Advice$SelfCallHandle"))
        .contains("SelfCallHandle");
    assertThat(
            LAYOUT.adviceMemberName(
                "io.argos.agent.shaded.net.bytebuddy.asm.Advice$AssignReturned$ToReturned"))
        .contains("AssignReturned.ToReturned");
    assertThat(LAYOUT.adviceMemberName("net.bytebuddy.asm.Advice")).isEmpty();
    assertThat(LAYOUT.adviceMemberName("com.example.shaded.net.bytebuddy.asm.Advice$OnMethodEnter"))
        .isEmpty();
  }

  @Test
  @DisplayName("Gradle module names map to package keys (02 §4.1, 10 §2) and back")
  void moduleNames() {
    assertThat(AgentLayout.packageKeyOf("httpclient-jdk11")).isEqualTo("httpclient.jdk11");
    assertThat(AgentLayout.packageKeyOf("httpclient-reactornetty"))
        .isEqualTo("httpclient.reactornetty");
    assertThat(AgentLayout.packageKeyOf("servlet-async")).isEqualTo("servletasync");
    assertThat(AgentLayout.packageKeyOf("spring-webmvc")).isEqualTo("springwebmvc");
    assertThat(AgentLayout.packageKeyOf("jdbc")).isEqualTo("jdbc");

    String instrumentation = AgentLayout.PRODUCTION_ROOT + ".instrumentation.";
    assertThat(LAYOUT.gradleModuleOf(instrumentation + "httpclient.jdk11.HttpClientAdvice"))
        .contains("httpclient-jdk11");
    assertThat(LAYOUT.gradleModuleOf(instrumentation + "servletasync.AsyncAdvice"))
        .contains("servlet-async");
    assertThat(LAYOUT.gradleModuleOf(instrumentation + "springwebmvc.HandlerAdvice"))
        .contains("spring-webmvc");
    assertThat(LAYOUT.gradleModuleOf(instrumentation + "jdbc.sub.StatementAdvice"))
        .contains("jdbc");
    // A package without a Gradle module keeps its package key.
    assertThat(LAYOUT.gradleModuleOf(instrumentation + "tomcat.TomcatAdvice")).contains("tomcat");
    assertThat(LAYOUT.gradleModuleOf(AgentLayout.PRODUCTION_ROOT + ".core.Core")).isEmpty();
  }
}
