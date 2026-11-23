/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.log.LogCapture;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The fixed rules of {@code instrumentation.custom-methods} (SEC-25, 16 §5.6, 10 §6.10): forbidden
 * class prefixes, the per-item class limit, generated classes; the policy WO-18's matcher reuses.
 */
@Tag("req:SEC-25")
@Tag("req:F-A14")
class CustomMethodPolicyTest {
  @AfterEach
  void reset() {
    CustomMethodPolicy.install(null);
  }

  @Test
  @DisplayName(
      "T-SEC-25 ST-25-1 an item whose class pattern starts with a forbidden prefix is ignored with"
          + " one WARN that names the position and the prefix, never the item")
  void forbiddenPrefixesIgnored() {
    List<String> items =
        Arrays.asList(
            "java.lang.*#*",
            "javax.servlet.**#service",
            "jakarta.servlet.http.HttpServlet#service",
            "jdk.internal.**#*",
            "sun.misc.*#*",
            "com.sun.proxy.*#*",
            "io.argos.agent.core.**#*",
            "io.argos.agent.shaded.net.bytebuddy.**#*",
            "net.bytebuddy.**#*",
            "com.example.service.*Service#*",
            "*.OrderService#find*");
    CustomMethodPolicy policy = CustomMethodPolicy.of(items, 500);
    assertThat(policy.ignoredCount()).isEqualTo(9);
    assertThat(policy.activeItems())
        .extracting(CustomMethodPolicy.Item::position)
        .containsExactly(10, 11);
    assertThat(policy.items().get(0).forbiddenPrefix()).isEqualTo("java.");
    assertThat(policy.items().get(5).forbiddenPrefix()).isEqualTo("com.sun.");
    assertThat(policy.items().get(7).forbiddenPrefix()).isEqualTo("io.argos.");
    assertThat(policy.items().get(0).matchesClass("java.lang.String")).isFalse();
    assertThat(CustomMethodPolicy.FORBIDDEN_PREFIXES)
        .containsExactly(
            "java.",
            "javax.",
            "jakarta.",
            "jdk.",
            "sun.",
            "com.sun.",
            "io.argos.",
            "net.bytebuddy.");

    try (LogCapture log = LogCapture.start("INFO")) {
      policy.warnIgnored("local configuration:");
      List<String> warns = log.linesAt("WARN");
      assertThat(warns).hasSize(9);
      assertThat(warns.get(0))
          .contains("local configuration: instrumentation.custom-methods item 1 ignored")
          .contains("forbidden prefix java.");
      assertThat(log.text()).doesNotContain("java.lang.*#*").doesNotContain("HttpServlet");
    }
  }

  @Test
  @DisplayName(
      "T-SEC-25 a pattern starting with * never matches a class under a forbidden prefix, nor a"
          + " generated class")
  void wildcardCannotBypass() {
    CustomMethodPolicy policy =
        CustomMethodPolicy.of(Arrays.asList("**#*", "*.String#*", "jav?.lang.*#*"), 500);
    assertThat(policy.ignoredCount()).isZero();
    for (CustomMethodPolicy.Item item : policy.items()) {
      assertThat(item.matchesClass("java.lang.String")).as(item.classGlob()).isFalse();
      assertThat(item.matchesClass("io.argos.agent.core.command.Retransformer")).isFalse();
      assertThat(item.matchesClass("net.bytebuddy.ByteBuddy")).isFalse();
      assertThat(item.matchesClass("com.example.Foo$$Lambda$14/0x0000000800c0b000")).isFalse();
      assertThat(item.matchesClass("com.example.Foo$$SpringCGLIB$$0")).isFalse();
      assertThat(item.matchesClass("com.example.Foo$$EnhancerBySpringCGLIB$$1a2b")).isFalse();
      assertThat(item.matchesClass("com.example.$Proxy12")).isFalse();
      assertThat(item.matchesClass("com.example.Foo$HibernateProxy$x1")).isFalse();
    }
    assertThat(policy.items().get(0).matchesClass("com.example.Foo")).isTrue();
    assertThat(policy.items().get(1).matchesClass("com.String")).isTrue();
    assertThat(policy.items().get(1).matchesClass("com.example.String")).isFalse();
    assertThat(CustomMethodPolicy.isGeneratedClass("com.example.Foo$Inner")).isFalse();
    assertThat(CustomMethodPolicy.isGeneratedClass("com.example.ProxyFactory")).isFalse();
    assertThat(CustomMethodPolicy.isGeneratedClass("com.example.$ProxyFactory")).isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-25 globs: * within a package segment, ** across, ? one character; method globs and"
          + " the step type and as-transaction fields")
  void globs() {
    CustomMethodPolicy policy =
        CustomMethodPolicy.of(
            Arrays.asList(
                "com.example.service.*Service#find*",
                "com.example.**.Repo?#save|SQL|true",
                "com.example.Api#<init>|METHOD"),
            500);
    CustomMethodPolicy.Item service = policy.items().get(0);
    assertThat(service.matchesClass("com.example.service.OrderService")).isTrue();
    assertThat(service.matchesClass("com.example.service.sub.OrderService")).isFalse();
    assertThat(service.matchesMethod("findAll")).isTrue();
    assertThat(service.matchesMethod("save")).isFalse();
    assertThat(service.stepType()).isEqualTo("METHOD");
    assertThat(service.asTransaction()).isFalse();
    CustomMethodPolicy.Item repo = policy.items().get(1);
    assertThat(repo.matchesClass("com.example.a.b.RepoX")).isTrue();
    assertThat(repo.matchesClass("com.example.a.b.RepoXY")).isFalse();
    assertThat(repo.stepType()).isEqualTo("SQL");
    assertThat(repo.asTransaction()).isTrue();
    assertThat(policy.items().get(2).matchesMethod("<init>")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-25 ST-25-2 an item that matches more than max-classes-per-pattern loaded classes is"
          + " disabled with one WARN and counted; other items keep their classes")
  void perPatternLimit() {
    CustomMethodPolicy policy =
        CustomMethodPolicy.of(
            Arrays.asList(
                "io.argos.agent.core.command.*#*", // forbidden: ignored
                "org.junit.jupiter.api.*#*", // more than 3 loaded classes: disabled
                "org.assertj.core.api.Assertions#*"),
            3);
    Class<?>[] loaded = {
      org.junit.jupiter.api.Test.class,
      org.junit.jupiter.api.Tag.class,
      org.junit.jupiter.api.DisplayName.class,
      org.junit.jupiter.api.AfterEach.class,
      org.assertj.core.api.Assertions.class,
      String.class,
      CustomMethodPolicy.class,
      int.class,
      String[].class
    };
    CustomMethodPolicy.Selection selection = policy.select(loaded, null);
    assertThat(selection.classes()).containsExactly(org.assertj.core.api.Assertions.class);
    assertThat(selection.newlyDisabled())
        .extracting(CustomMethodPolicy.Item::position)
        .containsExactly(2);
    assertThat(policy.disabledCount()).isEqualTo(1);
    assertThat(policy.ignoredCount()).isEqualTo(1);
    assertThat(policy.items().get(1).matchesClass("org.junit.jupiter.api.Test")).isFalse();
    try (LogCapture log = LogCapture.start("INFO")) {
      policy.warnDisabled(selection.newlyDisabled());
      assertThat(log.linesAt("WARN"))
          .singleElement()
          .asString()
          .contains("instrumentation.custom-methods item 2 disabled")
          .contains("more than 3 classes");
    }
    // exactly at the limit: still active
    CustomMethodPolicy atLimit =
        CustomMethodPolicy.of(Collections.singletonList("org.junit.jupiter.api.*#*"), 4);
    assertThat(atLimit.select(loaded, null).classes()).hasSize(4);
    assertThat(atLimit.disabledCount()).isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-25 the class-load counter of WO-18's matcher disables an item after the limit")
  void countMatch() {
    CustomMethodPolicy policy =
        CustomMethodPolicy.of(Collections.singletonList("com.example.**#*"), 2);
    CustomMethodPolicy.Item item = policy.items().get(0);
    assertThat(item.countMatch()).isTrue();
    assertThat(item.countMatch()).isTrue();
    assertThat(item.countMatch()).isFalse();
    assertThat(item.disabled()).isTrue();
    assertThat(item.matchesClass("com.example.Foo")).isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-25 modifiable filter, each class once across items, an invalid item is ignored")
  void selectionFilters() {
    List<String> items = new ArrayList<String>();
    items.add("org.junit.jupiter.api.*#*");
    items.add("org.junit.jupiter.api.Test#*");
    items.add("no-hash-here");
    CustomMethodPolicy policy = CustomMethodPolicy.of(items, 500);
    assertThat(policy.items().get(2).ignored()).isTrue();
    assertThat(policy.items().get(2).forbiddenPrefix()).isNull();
    Class<?>[] loaded = {org.junit.jupiter.api.Test.class, org.junit.jupiter.api.Tag.class};
    CustomMethodPolicy.Selection all = policy.select(loaded, null);
    assertThat(all.classes())
        .containsExactly(org.junit.jupiter.api.Test.class, org.junit.jupiter.api.Tag.class);
    CustomMethodPolicy.Selection modifiable =
        policy.select(loaded, type -> type != org.junit.jupiter.api.Tag.class);
    assertThat(modifiable.classes()).containsExactly(org.junit.jupiter.api.Test.class);
    assertThat(CustomMethodPolicy.forbiddenPrefixOf("javax.foo.Bar#x|SQL")).isEqualTo("javax.");
    assertThat(CustomMethodPolicy.forbiddenPrefixOf("com.foo.Bar#x")).isNull();
  }
}
