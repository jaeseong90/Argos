/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs every agent rule against the classes of the agent modules (19 §5.3, PR-required through
 * check). Rule names start with their test or rule ID (T-AG-001..004, ADV-04, AGT-01..03,
 * B-1..B-4). Until the modules have code (WO-02, WO-10) the rules find nothing to check; the
 * fixture tests ({@link AdviceRulesTest}, {@link AgentBoundaryRulesTest}) show that each rule
 * rejects violations.
 */
class AgentArchitectureTest {
  private static final ImportOption WITHOUT_TEST_HARNESS =
      location -> !location.contains("/io/argos/agent/testing/");

  static JavaClasses productionClasses() {
    return new ClassFileImporter()
        .withImportOption(WITHOUT_TEST_HARNESS)
        .importPackages(AgentLayout.PRODUCTION_ROOT);
  }

  static List<ArchRule> productionRules() {
    AgentLayout layout = AgentLayout.production();
    return Stream.concat(AdviceRules.all(layout).stream(), AgentBoundaryRules.all(layout).stream())
        .toList();
  }

  @TestFactory
  @Tag("req:F-A15")
  @Tag("req:F-A35")
  @Tag("req:F-A36")
  Stream<DynamicTest> agentClassesFollowEveryRule() {
    JavaClasses classes = productionClasses();
    return productionRules().stream()
        .map(
            rule ->
                DynamicTest.dynamicTest(
                    rule.getDescription(), () -> rule.allowEmptyShould(true).check(classes)));
  }

  @Test
  @DisplayName("ArchUnit scope: every agent module's classes are on the archTest classpath")
  void everyAgentModuleIsScanned() {
    List<String> classpath =
        Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator, -1));
    List<String> modules =
        Arrays.asList(AgentLayout.requiredProperty("argos.archunit.agentModules").split(",", -1));
    assertThat(modules).contains("argos-agent-bootstrap", "argos-agent-core").hasSizeGreaterThan(2);
    for (String module : modules) {
      assertThat(classpath)
          .as("archTest classpath entry for %s", module)
          .anySatisfy(entry -> assertThat(new File(entry).getName()).startsWith(module + "-"));
    }
  }

  @Test
  @DisplayName("ArchUnit scope: the production layout uses the shading list and the allow-list")
  void productionLayoutIsComplete() {
    AgentLayout layout = AgentLayout.production();
    assertThat(layout.relocatedPackages()).contains("net.bytebuddy", "io.netty", "io.grpc");
    assertThat(layout.entryPoints()).containsExactly("io.argos.agent.AgentPremain");
    assertThat(layout.guardHolder()).isEqualTo("io.argos.agent.bootstrap.Argos");
    // Approved 1.0 entries are added with their modules (10 §5.3 A-5); WO-00 has none.
    assertThat(layout.allowlist().entries()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-002c every allow-list entry matches an advice method that uses it (no stale entry)")
  void allowlistEntriesMatchTheCode() {
    assertThat(AdviceRules.allowlistProblems(AgentLayout.production(), productionClasses()))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-AG-002c allow-list module names: every settings.gradle.kts instrumentation module maps to"
          + " the package its sources use")
  void instrumentationModulesMatchTheirPackages() throws IOException {
    AgentLayout layout = AgentLayout.production();
    Path modulesDir = Path.of(AgentLayout.requiredProperty("argos.archunit.instrumentationDir"));
    assertThat(layout.instrumentationModules())
        .contains("api", "servlet-async", "spring-webmvc", "httpclient-jdk11")
        .doesNotHaveDuplicates();
    assertThat(layout.instrumentationModules().stream().map(AgentLayout::packageKeyOf))
        .doesNotHaveDuplicates();
    for (String module : layout.instrumentationModules()) {
      String key = AgentLayout.packageKeyOf(module);
      assertThat(layout.gradleModuleOf(layout.instrumentation() + "." + key + ".SomeAdvice"))
          .as("module of package key %s", key)
          .contains(module);
      Path packageDir =
          Path.of(layout.instrumentation().replace('.', '/') + "/" + key.replace('.', '/'));
      try (Stream<Path> sourceSets = Files.list(modulesDir.resolve(module).resolve("src"))) {
        assertThat(sourceSets.map(sourceSet -> sourceSet.resolve("java").resolve(packageDir)))
            .as("sources of %s in package %s", module, key)
            .anySatisfy(dir -> assertThat(dir).isDirectory());
      }
    }
  }
}
