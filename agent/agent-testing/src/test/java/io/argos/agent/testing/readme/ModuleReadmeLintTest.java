/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.readme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * T-BLD-017 (proposed): every instrumentation module README states its verified version range, its
 * witness and where its boundary versions are tested (03 §3.1), and the lint catches each violation
 * of the fixtures in {@code src/test/resources/readme-fixtures}.
 */
@Tag("req:O-Q05")
class ModuleReadmeLintTest {
  private static final String FIXTURES = "/readme-fixtures/";

  /** The repository the fixtures assume: two test classes, alpha's two tasks, agent-testing. */
  private static final ModuleReadmeLint.Repository FIXTURE_REPOSITORY =
      new ModuleReadmeLint.Repository() {
        @Override
        public boolean testClassExists(String simpleName) {
          return Set.of("AlphaAdviceTest", "AlphaModuleTest").contains(simpleName);
        }

        @Override
        public boolean taskExists(String projectPath, String task) {
          return Set.of(
                  ":agent:agent-instrumentation:alpha:test",
                  ":agent:agent-instrumentation:alpha:testModern",
                  ":agent:agent-testing:integrationTest")
              .contains(projectPath + ":" + task);
        }
      };

  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-BLD-017 every instrumentation module of settings.gradle.kts has a README with its verified"
          + " version range, witness and boundary tests")
  void repositoryReadmes() throws IOException {
    Path root = ModuleReadmeLint.repositoryRoot();
    String settings = Files.readString(root.resolve("settings.gradle.kts"), StandardCharsets.UTF_8);
    List<String> modules = ModuleReadmeLint.instrumentationModules(settings);

    ModuleReadmeLint.Result result = ModuleReadmeLint.lint(root);

    List<String> expected = new ArrayList<>(modules);
    expected.removeAll(ModuleReadmeLint.EXCLUDED.keySet());
    assertThat(result.linted()).as("linted modules").containsExactlyElementsOf(expected);
    assertThat(result.violations()).as("README violations").isEmpty();
    System.out.println(
        "[wo19-readme] linted "
            + result.linted().size()
            + " module READMEs "
            + result.linted()
            + ", excluded "
            + ModuleReadmeLint.EXCLUDED.keySet());
  }

  @Test
  @DisplayName("T-BLD-017 the exclusions are modules of settings.gradle.kts and carry a reason")
  void exclusions() throws IOException {
    Path root = ModuleReadmeLint.repositoryRoot();
    String settings = Files.readString(root.resolve("settings.gradle.kts"), StandardCharsets.UTF_8);
    List<String> modules = ModuleReadmeLint.instrumentationModules(settings);

    assertThat(modules).hasSizeGreaterThan(ModuleReadmeLint.EXCLUDED.size());
    assertThat(modules).containsAll(ModuleReadmeLint.EXCLUDED.keySet());
    assertThat(ModuleReadmeLint.EXCLUDED.values())
        .allSatisfy(r -> assertThat(r).hasSizeGreaterThan(20));
  }

  @Test
  @DisplayName("T-BLD-017 the valid fixture passes")
  void validFixture() throws IOException {
    assertThat(lintFixture("valid.md", alphaWitness())).isEmpty();
  }

  @ParameterizedTest(name = "T-BLD-017 fixture {0} is rejected: {1}")
  @CsvSource(
      delimiter = ';',
      quoteCharacter = '"',
      value = {
        "missing-section.md; no '## Supported library versions' section",
        "missing-subsection.md; no '### Version range and witness' subsection",
        "missing-column.md; no version table with the columns Library, Minimum verified, Maximum"
            + " verified, Boundary tests",
        "no-rows.md; version table has no rows",
        "bad-version.md; minimum verified '1.x' is not a version",
        "min-above-max.md; minimum verified 2.0.1 is above maximum verified 1.10.3",
        "no-test-class.md; boundary tests name no test class",
        "unknown-test-class.md; unknown test class 'AlphaGoneTest'",
        "unknown-task.md; unknown Gradle task ':agent:agent-instrumentation:alpha:testNope'",
        "no-own-task.md; the subsection names no task of :agent:agent-instrumentation:alpha",
        "no-witness.md; no '- Witness' line",
        "witness-mismatch.md; witness line does not name `org.example.alpha.Request`",
        "witness-none.md; witness line says none but the module's witness() checks classes",
      })
  void violationFixtures(String fixture, String message) throws IOException {
    List<String> violations = lintFixture(fixture, alphaWitness());
    assertThat(violations).as("exactly the one violation the fixture plants").hasSize(1);
    assertThat(violations.get(0)).startsWith("alpha: ").contains(message);
  }

  @Test
  @DisplayName(
      "T-BLD-017 a module without witness() must say 'Witness: none', a module with one may not")
  void defaultWitness() throws IOException {
    ModuleReadmeLint.Witness none =
        ModuleReadmeLint.witnessOf(Map.of("NoWitnessModule", fixture("NoWitnessModule.java.txt")));
    assertThat(none.declared()).isFalse();

    assertThat(lintFixture("witness-none.md", none)).isEmpty();
    assertThat(lintFixture("valid.md", none))
        .containsExactly(
            "alpha: the module keeps the default witness (every class loader), so the witness line"
                + " must start with '- Witness: none'");
  }

  @Test
  @DisplayName(
      "T-BLD-017 witness classes are read from the module source: constants, concatenation and a"
          + " witness class of the module")
  void witnessFromSource() throws IOException {
    ModuleReadmeLint.Witness alpha = alphaWitness();
    assertThat(alpha.declared()).isTrue();
    assertThat(alpha.classNames())
        .containsExactly("org.example.alpha.Client", "org.example.alpha.Request");
    assertThat(alpha.unresolved()).isEmpty();

    ModuleReadmeLint.Witness delegating =
        ModuleReadmeLint.witnessOf(
            Map.of(
                "DelegatingModule", fixture("DelegatingModule.java.txt"),
                "DelegateWitness", fixture("DelegateWitness.java.txt")));
    assertThat(delegating.classNames())
        .containsExactly(
            "org.example.alpha.Client",
            "org.example.alpha.Connect$Handler",
            "org.example.alpha.State");
    assertThat(delegating.unresolved()).containsExactly("UNKNOWN_NAME");
  }

  @Test
  @DisplayName("T-BLD-017 versions: JDK prefix, qualifiers and numeric order")
  void versions() {
    assertThat(ModuleReadmeLint.version("`4.5.14`")).containsExactly(4, 5, 14);
    assertThat(ModuleReadmeLint.version("JDK 25")).containsExactly(25);
    assertThat(ModuleReadmeLint.version("12.10.2.jre11")).containsExactly(12, 10, 2);
    assertThat(ModuleReadmeLint.version("4.2.19.Final")).containsExactly(4, 2, 19);
    assertThat(ModuleReadmeLint.version("1.x")).isNull();
    assertThat(ModuleReadmeLint.version("1.2.x")).isNull();
    assertThat(ModuleReadmeLint.version("4")).isNull();
    assertThat(ModuleReadmeLint.version("23.7.0.25.01")).containsExactly(23, 7, 0, 25, 1);
    assertThat(ModuleReadmeLint.version("5.x classic")).isNull();
    assertThat(ModuleReadmeLint.version("")).isNull();
    assertThat(
            ModuleReadmeLint.compare(
                ModuleReadmeLint.version("1.10.3"), ModuleReadmeLint.version("1.2.3")))
        .isPositive();
    assertThat(
            ModuleReadmeLint.compare(
                ModuleReadmeLint.version("JDK 8"), ModuleReadmeLint.version("JDK 8.0")))
        .isZero();
  }

  @Test
  @DisplayName(
      "T-BLD-017 the repository walk: modules from the settings list, exclusions skipped, a module"
          + " without README fails")
  void repositoryWalk() throws IOException {
    write(
        "settings.gradle.kts",
        """
        rootProject.name = "argos"
        val instrumentationModules =
          listOf(
            // Framework ("not-a-module" in a comment is ignored).
            "api",
            "alpha",
            "beta",
          )
        instrumentationModules.forEach { include(":agent:agent-instrumentation:$it") }
        """);
    write("agent/agent-instrumentation/alpha/README.md", fixture("valid.md"));
    write(
        "agent/agent-instrumentation/alpha/build.gradle.kts",
        "val testModern = tasks.register<Test>(\"testModern\")\n");
    write(
        "agent/agent-instrumentation/alpha/src/main/java/org/example/alpha/instrumentation/"
            + "AlphaModule.java",
        fixture("AlphaModule.java.txt"));
    write(
        "agent/agent-instrumentation/alpha/src/test/java/org/example/alpha/AlphaAdviceTest.java",
        "class AlphaAdviceTest {}\n");
    write(
        "agent/agent-testing/src/integrationTest/java/alpha/AlphaModuleTest.java",
        "class AlphaModuleTest {}\n");
    write("agent/agent-instrumentation/beta/build.gradle.kts", "\n");

    assertThat(ModuleReadmeLint.instrumentationModules(read("settings.gradle.kts")))
        .containsExactly("api", "alpha", "beta");
    ModuleReadmeLint.Result result = ModuleReadmeLint.lint(dir);
    assertThat(result.linted()).containsExactly("alpha", "beta");
    assertThat(result.violations()).containsExactly("beta: README.md is missing");

    // A test class that exists only in build output is not a test of the repository.
    Files.delete(
        dir.resolve(
            "agent/agent-instrumentation/alpha/src/test/java/org/example/alpha/AlphaAdviceTest.java"));
    write("agent/agent-instrumentation/alpha/build/generated/AlphaAdviceTest.java", "\n");
    assertThat(ModuleReadmeLint.lint(dir).violations())
        .contains("alpha: row '`org.example:alpha`': unknown test class 'AlphaAdviceTest'");
  }

  private ModuleReadmeLint.Witness alphaWitness() throws IOException {
    return ModuleReadmeLint.witnessOf(Map.of("AlphaModule", fixture("AlphaModule.java.txt")));
  }

  private static List<String> lintFixture(String name, ModuleReadmeLint.Witness witness)
      throws IOException {
    return ModuleReadmeLint.lintReadme("alpha", fixture(name), witness, FIXTURE_REPOSITORY);
  }

  private static String fixture(String name) throws IOException {
    try (InputStream in = ModuleReadmeLintTest.class.getResourceAsStream(FIXTURES + name)) {
      if (in == null) {
        throw new IOException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private void write(String relative, String text) throws IOException {
    Path file = dir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text, StandardCharsets.UTF_8);
  }

  private String read(String relative) throws IOException {
    return Files.readString(dir.resolve(relative), StandardCharsets.UTF_8);
  }
}
