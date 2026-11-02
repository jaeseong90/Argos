/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HelperClasses (10 §5.4): helper names in the module package, by simple name. */
class HelperClassesTest {

  @Test
  @DisplayName("of() qualifies simple names with the module package, keeping the order")
  void qualifies() {
    assertThat(HelperClasses.of(DemoModule.class, "BHelper", "AHelper$Nested"))
        .containsExactly(
            "io.argos.agent.instrumentation.api.BHelper",
            "io.argos.agent.instrumentation.api.AHelper$Nested");
    assertThat(HelperClasses.of(DemoModule.class)).isEmpty();
  }

  @Test
  @DisplayName("of() rejects qualified, non-helper, empty and repeated names")
  void rejects() {
    assertThatThrownBy(() -> HelperClasses.of(DemoModule.class, "x.YHelper"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("simple name");
    assertThatThrownBy(() -> HelperClasses.of(DemoModule.class, "Support"))
        .hasMessageContaining("'Helper'");
    assertThatThrownBy(() -> HelperClasses.of(DemoModule.class, "")).hasMessageContaining("empty");
    assertThatThrownBy(() -> HelperClasses.of(DemoModule.class, "AHelper", "AHelper"))
        .hasMessageContaining("twice");
  }

  @Test
  @DisplayName("problems() reports helpers outside the module package, misnamed or repeated")
  void problems() {
    assertThat(HelperClasses.problems(new DemoModule(Collections.<String>emptyList()))).isEmpty();
    List<String> problems =
        HelperClasses.problems(
            new DemoModule(
                Arrays.asList(
                    "io.argos.agent.instrumentation.api.AHelper",
                    "io.argos.agent.instrumentation.api.AHelper",
                    "io.argos.agent.instrumentation.other.BHelper",
                    "io.argos.agent.instrumentation.api.sub.CHelper",
                    "io.argos.agent.instrumentation.api.Support")));
    assertThat(problems).hasSize(4);
    assertThat(problems.get(0)).contains("AHelper is listed twice");
    assertThat(problems.get(1)).contains("other.BHelper is not in the module package");
    assertThat(problems.get(2)).contains("sub.CHelper is not in the module package");
    assertThat(problems.get(3)).contains("Support: helper class names contain 'Helper'");
  }

  /** A module with a given helper list. */
  static final class DemoModule implements InstrumentationModule {
    private final List<String> helpers;

    DemoModule(List<String> helpers) {
      this.helpers = helpers;
    }

    @Override
    public String id() {
      return "demo";
    }

    @Override
    public int moduleId() {
      return ModuleIds.TEST_FIRST;
    }

    @Override
    public List<TypeInstrumentation> types() {
      return Collections.emptyList();
    }

    @Override
    public List<String> helperClassNames() {
      return helpers;
    }
  }
}
