/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.apps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The input rules of the application administration (14 §4.3, §7.2, ADR-0010 C-207 (3)). */
@Tag("req:C-153")
class AppInputsTest {

  private static String reason(Runnable call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e.errorCode().code()
          + "/"
          + e.details().get(0).field()
          + "/"
          + e.details().get(0).reason();
    }
    throw new AssertionError("no refusal");
  }

  @Test
  @DisplayName("names and environments are stripped, required, capped at 128 / 32, no controls")
  void namesAndEnvironments() {
    assertThat(AppInputs.name("  shop ")).isEqualTo("shop");
    assertThat(AppInputs.env("prod")).isEqualTo("prod");
    assertThat(reason(() -> AppInputs.name(" "))).isEqualTo("invalid_request/name/required");
    assertThat(reason(() -> AppInputs.name("x".repeat(129)))).endsWith("name/too_long");
    assertThat(AppInputs.name("x".repeat(128))).hasSize(128);
    assertThat(reason(() -> AppInputs.env("p".repeat(33)))).endsWith("env/too_long");
    assertThat(reason(() -> AppInputs.name("a\u0000b"))).endsWith("name/control_character");
    assertThat(AppInputs.group(null)).isNull();
    assertThat(AppInputs.group("  ")).isNull();
    assertThat(reason(() -> AppInputs.group("g".repeat(65)))).endsWith("group/too_long");
  }

  @Test
  @DisplayName("the Apdex T is 1 to 65535 ms and the registration policy is auto or approval")
  void apdexAndPolicy() {
    assertThat(AppInputs.apdexT("apdex_t_ms", 1)).isEqualTo(1);
    assertThat(AppInputs.apdexT("apdex_t_ms", 65_535)).isEqualTo(65_535);
    assertThat(reason(() -> AppInputs.apdexT("apdex_t_ms", 0))).endsWith("out_of_range");
    assertThat(reason(() -> AppInputs.apdexT("apdex_t_ms", 65_536))).endsWith("out_of_range");
    assertThat(AppInputs.autoRegister(null)).isNull();
    assertThat(AppInputs.autoRegister("AUTO")).isTrue();
    assertThat(AppInputs.autoRegister("approval")).isFalse();
    assertThat(reason(() -> AppInputs.autoRegister("manual")))
        .isEqualTo("invalid_request/registration_policy/unsupported_value");
  }

  @Test
  @DisplayName(
      "C-207 (3) app_packages: Java package names, duplicates dropped in order, empty clears")
  void packagesAccepted() {
    assertThat(AppInputs.packages(null)).isNull();
    assertThat(AppInputs.packages(List.of())).isEmpty();
    assertThat(AppInputs.packages(List.of(" com.example.shop ", "org.acme", "com.example.shop")))
        .containsExactly("com.example.shop", "org.acme");
    assertThat(AppInputs.packages(List.of("javaland.app", "sunrise", "io.argosy")))
        .as("only the roots and their subpackages are refused, not lookalike names")
        .containsExactly("javaland.app", "sunrise", "io.argosy");
  }

  @ParameterizedTest(name = "C-207 (3) app_packages refuses ''{0}''")
  @ValueSource(
      strings = {
        "java",
        "java.util",
        "javax.servlet",
        "jdk.internal",
        "sun.misc",
        "io.argos",
        "io.argos.agent"
      })
  void packagesForbidden(String value) {
    assertThat(reason(() -> AppInputs.packages(List.of(value))))
        .isEqualTo("invalid_request/app_packages/forbidden_package");
  }

  @ParameterizedTest(name = "C-207 (3) app_packages refuses the malformed ''{0}''")
  @ValueSource(strings = {"com..example", ".com", "com.", "1com", "com/example", "com example"})
  void packagesMalformed(String value) {
    assertThat(reason(() -> AppInputs.packages(List.of(value))))
        .isEqualTo("invalid_request/app_packages/invalid");
  }

  @Test
  @DisplayName("C-207 (3) app_packages: at most 50 names of at most 255 characters")
  void packagesBounds() {
    List<String> fifty = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      fifty.add("com.example.p" + i);
    }
    assertThat(AppInputs.packages(fifty)).hasSize(50);
    List<String> many = new ArrayList<>(fifty);
    many.add("com.example.extra");
    assertThatThrownBy(() -> AppInputs.packages(many))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
    char[] longName = new char[256];
    Arrays.fill(longName, 'a');
    assertThat(reason(() -> AppInputs.packages(List.of(new String(longName)))))
        .endsWith("app_packages/too_long");
    assertThat(reason(() -> AppInputs.packages(Arrays.asList("com.a", null))))
        .endsWith("app_packages/required");
  }
}
