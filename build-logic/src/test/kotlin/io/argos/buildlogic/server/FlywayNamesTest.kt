// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.server

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The PostgreSQL migration file name rule of ADR-0003 C-28 (FlywayNames) and the checkFlywayNames
 * task of argos.java-server: `V<NNN>__<lower_snake>.sql`, versions 001..n without gaps or
 * duplicates, nothing else in `src/main/resources/db/pg`.
 */
@Tag("req:N-22")
class FlywayNamesTest {

  @TempDir lateinit var dir: File

  @Test
  @DisplayName("valid: empty directory, V001 alone, V001..V003 in any listing order")
  fun validSets() {
    assertThat(FlywayNames.violations(emptyList())).isEmpty()
    assertThat(FlywayNames.violations(listOf("V001__init.sql"))).isEmpty()
    assertThat(
        FlywayNames.violations(listOf("V003__add_audit_partitions.sql", "V001__init.sql", "V002__alert_states_2.sql"))
      )
      .isEmpty()
  }

  @ParameterizedTest(name = "rejects {0}: {1}")
  @CsvSource(
    delimiter = '|',
    value =
      [
        "V1__init.sql|exactly three digits",
        "V01__init.sql|exactly three digits",
        "V0001__init.sql|exactly three digits",
        "v001__init.sql|must start with V",
        "001__init.sql|must start with V",
        "V001_init.sql|two underscores",
        "V001___init.sql|lower_snake_case",
        "V001__Init.sql|lower_snake_case",
        "V001__init-schema.sql|lower_snake_case",
        "V001__init schema.sql|lower_snake_case",
        "V001__init_.sql|lower_snake_case",
        "V001___.sql|lower_snake_case",
        "V001__.sql|lower_snake_case",
        "V001__init__schema.sql|lower_snake_case",
        "V001__init.SQL|not a .sql file",
        "V001__init.sql.bak|not a .sql file",
        "V001__init.java|not a .sql file",
        "README.md|not a .sql file",
        "R__views.sql|only versioned migrations",
        "U001__init.sql|only versioned migrations",
        "B001__baseline.sql|only versioned migrations",
      ],
  )
  fun rejectsName(name: String, reason: String) {
    val violations = FlywayNames.violations(listOf(name))
    assertThat(violations).hasSize(1)
    assertThat(violations[0]).startsWith("$name: ").contains(reason)
  }

  @Test
  @DisplayName("T-BLD-016 rejects version 000, gaps, a missing V001 and duplicate versions")
  fun numbering() {
    assertThat(FlywayNames.violations(listOf("V000__init.sql"))).singleElement().asString().contains("000")
    assertThat(FlywayNames.violations(listOf("V001__init.sql", "V003__three.sql")))
      .containsExactly("versions are not contiguous from 001: missing V002 before V003")
    assertThat(FlywayNames.violations(listOf("V002__two.sql")))
      .containsExactly("versions are not contiguous from 001: missing V001 before V002")
    assertThat(FlywayNames.violations(listOf("V001__init.sql", "V002__a.sql", "V002__b.sql")))
      .containsExactly("version 002 appears 2 times: V002__a.sql, V002__b.sql")
  }

  @Test
  @DisplayName("T-BLD-016 rejects subdirectories (Flyway scans locations recursively)")
  fun subdirectories() {
    assertThat(FlywayNames.violations(listOf("V001__init.sql", "old/V002__x.sql")))
      .singleElement()
      .asString()
      .startsWith("old/V002__x.sql: subdirectories are not allowed")
  }

  @Test
  @DisplayName("checkFlywayNames passes on valid and missing directories and writes its marker")
  fun taskPasses() {
    val migrations = dir.resolve("src/main/resources/db/pg").apply { mkdirs() }
    migrations.resolve("V001__init.sql").writeText("SELECT 1;\n")
    migrations.resolve("V002__next.sql").writeText("SELECT 2;\n")
    val task = task(migrations)

    task.check()

    assertThat(dir.resolve("build/marker.txt")).content().isEqualTo("V001__init.sql\nV002__next.sql\n")
    task(dir.resolve("does-not-exist")).check()
  }

  @Test
  @DisplayName("T-BLD-016 checkFlywayNames fails with every violation of the directory")
  fun taskFails() {
    val migrations = dir.resolve("src/main/resources/db/pg").apply { mkdirs() }
    migrations.resolve("V001__init.sql").writeText("SELECT 1;\n")
    migrations.resolve("V3__late.sql").writeText("SELECT 3;\n")
    migrations.resolve("V004__ok.sql").writeText("SELECT 4;\n")
    migrations.resolve("nested").mkdirs()
    migrations.resolve("nested/V002__hidden.sql").writeText("SELECT 2;\n")

    assertThatThrownBy { task(migrations).check() }
      .isInstanceOf(GradleException::class.java)
      .hasMessageContaining("ADR-0003 C-28")
      .hasMessageContaining("V3__late.sql: name does not match")
      .hasMessageContaining("nested/V002__hidden.sql: subdirectories are not allowed")
      .hasMessageContaining("missing V002, V003 before V004")
  }

  private fun task(migrations: File): CheckFlywayNames {
    val project = ProjectBuilder.builder().withProjectDir(dir).build()
    return project.tasks.register("checkFlywayNames${System.nanoTime()}", CheckFlywayNames::class.java) {
      migrationsDirectory.set(migrations)
      marker.set(dir.resolve("build/marker.txt"))
    }.get()
  }
}
