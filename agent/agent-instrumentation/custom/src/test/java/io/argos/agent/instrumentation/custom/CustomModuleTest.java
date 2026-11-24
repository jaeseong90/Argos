/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.custom;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.command.CustomMethodPolicy;
import io.argos.agent.instrumentation.api.ModuleIds;
import io.argos.agent.instrumentation.api.TypeInstrumentation;
import java.util.Arrays;
import java.util.concurrent.Callable;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CustomModule identity and matchers on type descriptions only (nothing is loaded or transformed):
 * no item means no match; interfaces, annotations, constructors, abstract and bridge methods are
 * never matched; an as-transaction item takes a method from the step advice.
 */
@Tag("req:F-A14")
@Tag("req:SEC-25")
class CustomModuleTest {
  private final CustomModule module = new CustomModule();

  @AfterEach
  void reset() {
    CustomMethodPolicy.install(null);
  }

  private static void policy(String... items) {
    CustomMethodPolicy.install(CustomMethodPolicy.of(Arrays.asList(items), 500));
  }

  private ElementMatcher<TypeDescription> types() {
    TypeInstrumentation only = module.types().get(0);
    return only.typeMatcher();
  }

  @Test
  @DisplayName("custom module identity: id, module id, one type rule, no helper")
  void identity() {
    assertThat(module.id()).isEqualTo("custom");
    assertThat(module.moduleId()).isEqualTo(ModuleIds.CUSTOM);
    assertThat(module.types()).hasSize(1);
    assertThat(module.helperClassNames()).as("bootstrap API only").isEmpty();
    assertThat(module.libraryVersionRange()).hasSize(1);
  }

  @Test
  @DisplayName("10 §6.10 without items the type matcher matches nothing (one volatile read)")
  void noItemNoMatch() {
    assertThat(CustomMethodPolicy.current().isEmpty()).isTrue();
    assertThat(types().matches(TypeDescription.ForLoadedType.of(CustomModuleTest.class))).isFalse();
  }

  @Test
  @DisplayName(
      "10 §6.10 interfaces and annotations never match; a class matches when an item names one"
          + " of its declared methods")
  void typeKinds() {
    policy("io.argos.never.*#*", "java.util.concurrent.*#*", "org.junit.jupiter.api.*#*");
    assertThat(types().matches(TypeDescription.ForLoadedType.of(Callable.class)))
        .as("forbidden prefix and interface")
        .isFalse();
    assertThat(types().matches(TypeDescription.ForLoadedType.of(Tag.class)))
        .as("annotation")
        .isFalse();
    policy("io.argos.agent.instrumentation.custom.CustomModule#*");
    assertThat(types().matches(TypeDescription.ForLoadedType.of(CustomModule.class)))
        .as("io.argos. is a forbidden prefix")
        .isFalse();
  }

  @Test
  @DisplayName(
      "10 §6.10 method rules: no constructor, abstract or bridge method; an as-transaction item"
          + " moves a method from the step advice to the transaction advice")
  void methodRules() throws Exception {
    CustomMatchers.Methods steps = new CustomMatchers.Methods(false);
    CustomMatchers.Methods transactions = new CustomMatchers.Methods(true);
    TypeDescription generic = TypeDescription.ForLoadedType.of(example.custom.Generic.class);
    policy("example.custom.Generic#*");
    int bridges = 0;
    for (MethodDescription.InDefinedShape method : generic.getDeclaredMethods()) {
      boolean matched = steps.matches(method);
      if (method.isConstructor() || method.isBridge()) {
        assertThat(matched).as(method.toString()).isFalse();
        bridges += method.isBridge() ? 1 : 0;
      } else {
        assertThat(matched).as(method.toString()).isTrue();
        assertThat(transactions.matches(method)).isFalse();
      }
    }
    assertThat(bridges).as("javac bridge Object call()").isEqualTo(1);

    policy("example.custom.Generic#call", "example.custom.Generic#call|true");
    MethodDescription call =
        new MethodDescription.ForLoadedMethod(example.custom.Generic.class.getMethod("call"));
    assertThat(steps.matches(call)).isFalse();
    assertThat(transactions.matches(call)).isTrue();
  }
}
