/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static io.argos.agent.testing.arch.Fixtures.COMPLIANT;
import static io.argos.agent.testing.arch.Fixtures.VIOLATING;
import static io.argos.agent.testing.arch.Fixtures.assertPassesOnCompliant;
import static io.argos.agent.testing.arch.Fixtures.assertViolation;
import static io.argos.agent.testing.arch.Fixtures.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.testing.arch.fixture.violating.core.LogsCollectedData;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ConstructorEnterInstrumentation;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ConstructorThrowableAdvice;
import io.argos.agent.testing.arch.fixture.violating.instrumentation.demo.ConstructorThrowableInstrumentation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Negative and positive tests of LOG-01 (10 §14, SEC-26), A-7 and A-9 (10 §5.3): each rule fails on
 * a violating fixture and passes on the compliant agent.
 */
class AgentCodeRulesTest {
  @Nested
  @Tag("req:SEC-26")
  class Log01 {
    @Test
    @DisplayName(
        "T-SEC-26a LOG-01 rejects a parameter, a field, a non-constant format and a collection"
            + " element in INFO/WARN/ERROR log calls")
    void rejectsCollectedData() {
      assertViolation(
              AgentCodeRules.logArgumentsAreSafe(VIOLATING, LogAllowlist.empty()),
              classes(LogsCollectedData.class),
              "AgentLogger.info argument comes from parameter #0 (java.lang.String) of unknown"
                  + " origin")
          .hasMessageContaining("LogsCollectedData.executed (LogsCollectedData.java:")
          .hasMessageContaining(
              "AgentLogger.warn argument comes from field "
                  + LogsCollectedData.class.getName()
                  + "#lastSql")
          .hasMessageContaining("AgentLogger.error format is not a constant")
          .hasMessageContaining("argument comes from a call of java.util.List#get");
    }

    @Test
    @DisplayName(
        "T-SEC-26a LOG-01 accepts constants, numbers, enums, files, class names and throwables,"
            + " and leaves DEBUG alone")
    void acceptsCompliant() {
      assertPassesOnCompliant(AgentCodeRules.logArgumentsAreSafe(COMPLIANT, LogAllowlist.empty()));
    }

    @Test
    @DisplayName("T-SEC-26a LOG-01 allow-list: a producer or a reviewed site with its reason")
    void allowlist() {
      LogAllowlist list =
          LogAllowlist.parse(
              List.of(
                  "# comment",
                  "site " + LogsCollectedData.class.getName() + "#executed reviewed by hand",
                  "producer java.util.List#get header names only"),
              "test");
      AgentCodeRules.logArgumentsAreSafe(VIOLATING, list).check(classes(LogsCollectedData.class));
      assertThat(list.allowsProducer("java.util.List#get")).isTrue();
      assertThat(list.sites()).hasSize(1);
      assertThatThrownBy(() -> LogAllowlist.parse(List.of("site a.B#c"), "test"))
          .hasMessageContaining("expected '<producer|site> <class>#<member> <reason>'");
      assertThatThrownBy(() -> LogAllowlist.parse(List.of("other a.B#c why"), "test"))
          .hasMessageContaining("unknown kind");
      // Every production entry has a reason (parse would fail otherwise).
      assertThat(LogAllowlist.production().producers()).isNotEmpty();
    }
  }

  @Nested
  @Tag("req:F-A15")
  class A7 {
    @Test
    @DisplayName(
        "T-AG-003f A-7 rejects enter advice on a constructor matcher and an advice that is not a"
            + " class literal")
    void rejectsEnterAdviceOnConstructors() {
      assertViolation(
              AgentCodeRules.constructorsGetExitAdviceOnly(VIOLATING),
              classes(ConstructorEnterInstrumentation.class),
              "constructor advice "
                  + ConstructorEnterInstrumentation.class.getPackageName()
                  + ".ConstructorEnterAdvice declares @Advice.OnMethodEnter")
          .hasMessageContaining("constructor advice is not named by a class literal");
    }

    @Test
    @DisplayName(
        "T-AG-003g A-7 rejects onThrowable on constructor exit advice (ByteBuddy cannot catch in a"
            + " constructor) and one advice on constructors and methods")
    void rejectsOnThrowableOnConstructors() {
      assertViolation(
              AgentCodeRules.constructorsGetExitAdviceOnly(VIOLATING),
              classes(ConstructorThrowableInstrumentation.class, ConstructorThrowableAdvice.class),
              "constructor advice "
                  + ConstructorThrowableAdvice.class.getName()
                  + " declares onThrowable on @Advice.OnMethodExit")
          .hasMessageContaining(
              "advice "
                  + ConstructorThrowableAdvice.class.getName()
                  + " is applied to constructors and to methods");
    }

    @Test
    @DisplayName(
        "T-AG-003f A-7 accepts exit-only advice on constructors (also through a matcher method)")
    void acceptsCompliant() {
      assertPassesOnCompliant(AgentCodeRules.constructorsGetExitAdviceOnly(COMPLIANT));
    }
  }

  @Nested
  @Tag("req:F-A36")
  class A9 {
    @Test
    @DisplayName("T-AG-004a A-9 rejects ContextStore values of an application type and of Object")
    void rejectsApplicationValues() {
      assertViolation(
              AgentCodeRules.contextStoreValuesAreSafe(VIOLATING),
              classes(ConstructorEnterInstrumentation.class),
              "ContextStore value type io.argos.agent.testing.arch.fixture.app.Order may hold an"
                  + " application object")
          .hasMessageContaining("ContextStore value type java.lang.Object may hold");
    }

    @Test
    @DisplayName(
        "T-AG-004a A-9 accepts bootstrap types, boxed primitives, long[] and WeakReference values")
    void acceptsCompliant() {
      assertPassesOnCompliant(AgentCodeRules.contextStoreValuesAreSafe(COMPLIANT));
    }
  }
}
