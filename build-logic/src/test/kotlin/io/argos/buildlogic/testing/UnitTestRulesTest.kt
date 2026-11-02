// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.testing

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The unit-test layer rules of 18 §2.2 (src/test holds fast unit tests without containers). */
@Tag("req:DSN-18-02")
class UnitTestRulesTest {
  @TempDir lateinit var dir: File

  @Test
  @DisplayName("T-BLD-013 slow, integration and Testcontainers tests in src/test are reported with file and line")
  fun forbiddenInUnitTests() {
    val source =
      """
      import org.testcontainers.junit.jupiter.Container;
      import org.testcontainers.utility.DockerImageName; // image names are fine in unit tests
      @Tag("slow")
      class A {
        @Tag( value = "integration" ) @Test void b() {}
        // @Tag("slow") in a comment is ignored
        @Tag("req:slow") @Test void c() {}
        @Container static Object db = null;
      }
      @org.testcontainers.junit.jupiter.Testcontainers class D {}
      """
        .trimIndent()
    val violations = UnitTestSourceRules.scan("src/test/java/A.java", source)
    assertThat(violations.map { it.line to it.reason })
      .containsExactly(
        3 to "@Tag(\"slow\") test in src/test",
        5 to "@Tag(\"integration\") test in src/test",
        8 to "@Container (containers) in src/test",
        10 to "@Testcontainers (containers) in src/test",
      )
    assertThat(UnitTestSourceRules.failureMessage(violations))
      .contains("Move these tests to src/integrationTest:")
      .contains("  src/test/java/A.java:3: @Tag(\"slow\") test in src/test: @Tag(\"slow\")")
  }

  @Test
  @DisplayName("T-BLD-013 unit tests slower than 1 s are listed from the JUnit XML of the run, slowest first")
  fun slowUnitTests() {
    File(dir, "TEST-a.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="a.A" tests="3" timestamp="2026-11-03T00:00:00Z">
          <testcase name="fast()" classname="a.A" time="0.2"/>
          <testcase name="slow()" classname="a.A" time="1.5"/>
          <testcase name="slower()" classname="a.A" time="3.25"><failure message="x"/></testcase>
        </testsuite>
        """
          .trimIndent()
      )
    File(dir, "not-xml.txt").writeText("ignored")
    val slow = SlowUnitTestWarning.slowTests(dir, TestLayers.UNIT_TEST_SLOW_SECONDS)
    assertThat(slow.map { it.name to it.timeSeconds }).containsExactly("slower()" to 3.25, "slow()" to 1.5)
    assertThat(SlowUnitTestWarning.slowTests(File(dir, "missing"), 1.0)).isEmpty()
  }
}
