/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.assertj.core.api.AbstractThrowableAssert;

/** Layouts and class sets of the src/archFixtures classes, plus assertion helpers. */
final class Fixtures {
  static final String BASE = "io.argos.agent.testing.arch.fixture";
  static final String COMPLIANT_ROOT = BASE + ".compliant";
  static final String VIOLATING_ROOT = BASE + ".violating";

  /** Packages the fixtures treat as shaded: a stand-in library and ByteBuddy (as in production). */
  static final List<String> RELOCATED = List.of(BASE + ".thirdparty", "net.bytebuddy");

  /** Gradle module names of the compliant instrumentation packages (as in settings.gradle.kts). */
  static final List<String> COMPLIANT_MODULES = List.of("api", "demo", "httpclient-demo11");

  static final AgentLayout COMPLIANT =
      new AgentLayout(
          COMPLIANT_ROOT,
          List.of(COMPLIANT_ROOT + ".AgentPremain"),
          RELOCATED,
          COMPLIANT_MODULES,
          allowlist("fixture-advice-allowlist.txt"));

  static final AgentLayout VIOLATING =
      new AgentLayout(
          VIOLATING_ROOT,
          List.of(),
          RELOCATED,
          List.of("api", "demo", "other"),
          AdviceAllowlist.empty());

  private Fixtures() {}

  /** Every class of the compliant miniature agent. */
  static JavaClasses compliant() {
    return new ClassFileImporter().importPackages(COMPLIANT_ROOT);
  }

  static JavaClasses classes(Class<?>... classes) {
    return new ClassFileImporter().importClasses(classes);
  }

  /** {@code layout} with another allow-list. */
  static AgentLayout withAllowlist(AgentLayout layout, AdviceAllowlist allowlist) {
    return new AgentLayout(
        layout.root(),
        layout.entryPoints(),
        layout.relocatedPackages(),
        layout.instrumentationModules(),
        allowlist);
  }

  /** An allow-list from lines in the argos-advice-allowlist.txt format. */
  static AdviceAllowlist allowlistOf(String... lines) {
    return AdviceAllowlist.parse(List.of(lines), "test");
  }

  /** The rule passes on the compliant agent and actually checked something there. */
  static void assertPassesOnCompliant(ArchRule rule) {
    rule.allowEmptyShould(false).check(compliant());
  }

  /** The rule fails on {@code classes}; returns the assertion on the failure for more checks. */
  static AbstractThrowableAssert<?, ? extends Throwable> assertViolation(
      ArchRule rule, JavaClasses classes, String... messageParts) {
    return assertThatThrownBy(() -> rule.check(classes))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining(rule.getDescription())
        .hasMessageContainingAll(messageParts);
  }

  private static AdviceAllowlist allowlist(String resource) {
    try (InputStream in =
            Objects.requireNonNull(
                Fixtures.class.getResourceAsStream("/" + resource),
                "missing resource " + resource);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return AdviceAllowlist.parse(reader.lines().toList(), resource);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
