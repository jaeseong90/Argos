// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** checkMajorVersion (T-BLD-001) and checkRelocatedStrings (T-BLD-005) of :agent:agent-dist. */
class AgentJarChecksTest {
  @TempDir lateinit var dir: File

  private fun agentJar(vararg entries: Pair<String, ByteArray>): File =
    writeJar(File(dir, "agent.jar"), mapOf("Premain-Class" to "io.argos.agent.AgentPremain"), linkedMapOf(*entries))

  @Test
  @Tag("req:F-A31")
  @DisplayName("T-BLD-001 major version: 52 passes; 55 and non-class .classdata entries are reported")
  fun majorVersionViolations() {
    val good =
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/core/B.classdata" to classFile("io/argos/agent/core/B"),
      )
    assertThat(ClassFiles.majorVersionViolations(good)).isEmpty()

    val bad =
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/core/Java11.classdata" to classFile("io/argos/agent/core/Java11", 55),
        "inst/io/argos/agent/core/Garbage.classdata" to "not a class".toByteArray(),
      )
    assertThat(ClassFiles.majorVersionViolations(bad))
      .containsExactly(
        "inst/io/argos/agent/core/Java11.classdata (major 55)",
        "inst/io/argos/agent/core/Garbage.classdata (not a class file)",
      )
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-005 reads string constants from the constant pool")
  fun readsStringConstants() {
    val bytes = classFile("a/B", strings = listOf("io.netty.noUnsafe", "x"), classRefs = listOf("sun/misc/Unsafe"))
    assertThat(ClassFiles.stringConstants(bytes)).containsExactly("io.netty.noUnsafe", "x")
  }

  private fun taskProject(jar: File) {
    File(dir, "settings.gradle").writeText("rootProject.name = 'agent-jar-checks'\n")
    File(dir, "build.gradle")
      .writeText(
        """
        import io.argos.buildlogic.CheckMajorVersion
        import io.argos.buildlogic.CheckRelocatedStrings
        import io.argos.buildlogic.RelocatedStringPolicy

        plugins { id 'argos.license-check' }

        tasks.register('checkMajorVersion', CheckMajorVersion) {
          jar = file('${jar.name}')
          resultFile = layout.buildDirectory.file('major.txt')
        }
        tasks.register('checkRelocatedStrings', CheckRelocatedStrings) {
          jar = file('${jar.name}')
          prefix = 'io.argos.agent.shaded'
          propertyNames = ['io.netty.noUnsafe', 'net.bytebuddy.experimental']
          policy = RelocatedStringPolicy.valueOf(providers.gradleProperty('policy').getOrElse('UNDECIDED'))
          reportFile = layout.buildDirectory.file('relocated-strings.txt')
        }
        """
          .trimIndent()
      )
  }

  private fun runner(vararg arguments: String): GradleRunner =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(arguments.toList())

  @Test
  @Tag("req:F-A31")
  @DisplayName("T-BLD-001 the checkMajorVersion task fails the build on a Java 11 class")
  fun checkMajorVersionTaskFails() {
    taskProject(
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/core/Java11.classdata" to classFile("io/argos/agent/core/Java11", 55),
      )
    )
    val result = runner("checkMajorVersion").buildAndFail()
    assertThat(result.task(":checkMajorVersion")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output)
      .contains("checkMajorVersion: 1 class file(s) in agent.jar do not have major version 52")
      .contains("inst/io/argos/agent/core/Java11.classdata (major 55)")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-005 checkRelocatedStrings reports both forms; RELOCATED/ORIGINAL policies fail on the other form")
  fun checkRelocatedStringsPolicies() {
    taskProject(
      agentJar(
        "inst/io/argos/agent/shaded/io/netty/util/internal/PlatformDependent.classdata" to
          classFile(
            "io/argos/agent/shaded/io/netty/util/internal/PlatformDependent",
            strings = listOf("io.argos.agent.shaded.io.netty.noUnsafe"),
          ),
        "inst/io/argos/agent/shaded/net/bytebuddy/ClassFileVersion.classdata" to
          classFile(
            "io/argos/agent/shaded/net/bytebuddy/ClassFileVersion",
            strings = listOf("net.bytebuddy.experimental"),
          ),
      )
    )
    val undecided = runner("checkRelocatedStrings").build()
    assertThat(undecided.task(":checkRelocatedStrings")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/relocated-strings.txt").readText())
      .contains("io.netty.noUnsafe\n  original : -\n  relocated: inst/io/argos/agent/shaded/io/netty/util/internal/PlatformDependent.classdata")
      .contains("net.bytebuddy.experimental\n  original : inst/io/argos/agent/shaded/net/bytebuddy/ClassFileVersion.classdata")

    val relocated = runner("checkRelocatedStrings", "-Ppolicy=RELOCATED").buildAndFail()
    assertThat(relocated.output)
      .contains("property names do not follow policy RELOCATED")
      .contains("net.bytebuddy.experimental (original form)")

    val original = runner("checkRelocatedStrings", "-Ppolicy=ORIGINAL").buildAndFail()
    assertThat(original.output).contains("io.argos.agent.shaded.io.netty.noUnsafe in")
  }
}
