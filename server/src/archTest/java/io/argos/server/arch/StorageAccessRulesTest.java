/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.argos.server.arch.fixture.JdbcOutsideStorage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SRV-01, decided on 12 §1 (ADR-0003 notes): the storage clients are used only behind the storage
 * boundary. Classes that use JDBC ({@code java.sql}, {@code javax.sql}, Spring JDBC, HikariCP, the
 * PostgreSQL driver), Flyway or the ClickHouse client live in {@code io.argos.server.storage..}
 * (repositories, writers, data sources) or {@code io.argos.server.ops.migration..} (the migration
 * runners of the start sequence). Everything else reaches the databases through those packages.
 *
 * <p>The controller rule of SRV-01 ({@code @RequiresRole} on every REST handler, DSN-12-11) is
 * WO-24's and is added there.
 */
@Tag("req:SRV-01")
class StorageAccessRulesTest {

  /** Packages allowed to use the storage clients. */
  static final String[] STORAGE_PACKAGES = {
    "io.argos.server.storage..", "io.argos.server.ops.migration.."
  };

  /** The storage client packages. */
  static final String[] STORAGE_CLIENTS = {
    "java.sql..",
    "javax.sql..",
    "org.springframework.jdbc..",
    "com.zaxxer.hikari..",
    "org.postgresql..",
    "org.flywaydb..",
    "com.clickhouse.."
  };

  static final ArchRule STORAGE_ACCESS_ONLY_IN_STORAGE =
      noClasses()
          .that()
          .resideOutsideOfPackages(STORAGE_PACKAGES)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(STORAGE_CLIENTS)
          .because(
              "SRV-01 (12 §1, ADR-0003): JDBC, Flyway and the ClickHouse client are used only in"
                  + " io.argos.server.storage and io.argos.server.ops.migration");

  /** The production classes of the server (the fixtures of this source set excluded). */
  private static JavaClasses serverClasses() {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(location -> !location.contains("/archTest/"))
        .importPackages("io.argos.server");
  }

  @Test
  @DisplayName("SRV-01 server classes use JDBC, Flyway and ClickHouse only in storage packages")
  void productionCode() {
    JavaClasses classes = serverClasses();
    assertThat(classes.contain("io.argos.server.ServerApplication")).isTrue();
    assertThat(classes.contain(JdbcOutsideStorage.class)).isFalse();
    STORAGE_ACCESS_ONLY_IN_STORAGE.check(classes);

    // Not vacuous: the storage packages do use the clients, and pass.
    List<JavaClass> users =
        classes.stream()
            .filter(resideInAnyPackage(STORAGE_PACKAGES))
            .filter(
                c ->
                    c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(
                            d -> resideInAnyPackage(STORAGE_CLIENTS).test(d.getTargetClass())))
            .toList();
    assertThat(users).as("storage classes that use JDBC, Flyway or ClickHouse").isNotEmpty();
  }

  @Test
  @DisplayName("SRV-01 negative fixture: a JDBC user outside storage violates the rule")
  void negativeFixture() {
    EvaluationResult outside =
        STORAGE_ACCESS_ONLY_IN_STORAGE.evaluate(
            new ClassFileImporter().importClasses(JdbcOutsideStorage.class));
    assertThat(outside.hasViolation()).isTrue();
    assertThat(outside.getFailureReport().getDetails())
        .anyMatch(d -> d.contains(JdbcOutsideStorage.class.getName()) && d.contains("java.sql"))
        .anyMatch(d -> d.contains("javax.sql.DataSource"));
  }
}
