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
  @Tag("req:F-A31")
  @DisplayName(
    "T-BLD-001 C-22: own classes must be exactly 52 (51 and 53 fail); shaded classes <= 52 (50 passes, 53 fails)"
  )
  fun majorVersionRuleOfC22() {
    val compliant =
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/shaded/org/jctools/Queue.classdata" to
          classFile("io/argos/agent/shaded/org/jctools/Queue", 50),
        "inst/io/argos/agent/shaded/net/bytebuddy/ByteBuddy.classdata" to
          classFile("io/argos/agent/shaded/net/bytebuddy/ByteBuddy", 52),
      )
    assertThat(ClassFiles.majorVersionViolations(compliant)).isEmpty()

    val violating =
      agentJar(
        "io/argos/agent/bootstrap/Old.class" to classFile("io/argos/agent/bootstrap/Old", 51),
        "inst/io/argos/agent/core/New.classdata" to classFile("io/argos/agent/core/New", 53),
        "inst/io/argos/agent/shaded/io/netty/Java9.classdata" to
          classFile("io/argos/agent/shaded/io/netty/Java9", 53),
        "inst/io/argos/agent/shaded/org/jctools/Ok.classdata" to
          classFile("io/argos/agent/shaded/org/jctools/Ok", 50),
      )
    assertThat(ClassFiles.majorVersionViolations(violating))
      .containsExactly(
        "io/argos/agent/bootstrap/Old.class (major 51)",
        "inst/io/argos/agent/core/New.classdata (major 53)",
        "inst/io/argos/agent/shaded/io/netty/Java9.classdata (major 53, shaded classes must be <= 52)",
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
        import io.argos.buildlogic.CheckManifestVersion
        import io.argos.buildlogic.CheckOwnRelocatedLiterals
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
        tasks.register('checkOwnRelocatedLiterals', CheckOwnRelocatedLiterals) {
          jar = file('${jar.name}')
          prefix = 'io.argos.agent.shaded'
          allowed = providers.gradleProperty('allowed').map { [it] }.orElse([])
          reportFile = layout.buildDirectory.file('own-literals.txt')
        }
        tasks.register('checkManifestVersion', CheckManifestVersion) {
          jar = file('${jar.name}')
          expectedVersion = providers.gradleProperty('expected').getOrElse('0.0.0-SNAPSHOT')
          resultFile = layout.buildDirectory.file('manifest-version.txt')
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
      .contains("checkMajorVersion: 1 class file(s) in agent.jar violate the major version rule")
      .contains("own classes == 52, shaded classes <= 52")
      .contains("inst/io/argos/agent/core/Java11.classdata (major 55)")
  }

  @Test
  @Tag("req:F-A31")
  @DisplayName("T-BLD-001 C-22 the checkMajorVersion task fails on a shaded Java 9 class and passes a shaded Java 6 class")
  fun checkMajorVersionTaskShadedRule() {
    taskProject(
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/shaded/org/jctools/Old.classdata" to
          classFile("io/argos/agent/shaded/org/jctools/Old", 50),
        "inst/io/argos/agent/shaded/io/netty/Java9.classdata" to
          classFile("io/argos/agent/shaded/io/netty/Java9", 53),
      )
    )
    val failed = runner("checkMajorVersion").buildAndFail()
    assertThat(failed.output)
      .contains("checkMajorVersion: 1 class file(s) in agent.jar violate the major version rule")
      .contains("inst/io/argos/agent/shaded/io/netty/Java9.classdata (major 53, shaded classes must be <= 52)")
      .doesNotContain("org/jctools/Old")

    taskProject(
      agentJar(
        "io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A"),
        "inst/io/argos/agent/shaded/org/jctools/Old.classdata" to
          classFile("io/argos/agent/shaded/org/jctools/Old", 50),
      )
    )
    val passed = runner("checkMajorVersion").build()
    assertThat(passed.task(":checkMajorVersion")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/major.txt").readText())
      .contains("own major 52: 1")
      .contains("shaded major 50: 1")
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
  @Test
  @Tag("req:F-A35")
  @DisplayName(
    "C-23 checkOwnRelocatedLiterals fails on an own literal Shadow relocated; shaded and root classes are not scanned"
  )
  fun checkOwnRelocatedLiterals() {
    taskProject(
      agentJar(
        "io/argos/agent/bootstrap/AgentClassLoader.class" to
          classFile("io/argos/agent/bootstrap/AgentClassLoader", strings = listOf("io.argos.agent.shaded.")),
        "inst/io/argos/agent/shaded/io/netty/util/internal/PlatformDependent.classdata" to
          classFile(
            "io/argos/agent/shaded/io/netty/util/internal/PlatformDependent",
            strings = listOf("io.argos.agent.shaded.io.netty.noUnsafe"),
          ),
        "inst/io/argos/agent/core/boot/IgnoreMatchers.classdata" to
          classFile(
            "io/argos/agent/core/boot/IgnoreMatchers",
            strings = listOf("io.argos.", "io.argos.agent.shaded.net.bytebuddy.", "io/argos/agent/shaded/x/Y"),
          ),
      )
    )
    val failed = runner("checkOwnRelocatedLiterals").buildAndFail()
    assertThat(failed.task(":checkOwnRelocatedLiterals")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(failed.output)
      .contains("checkOwnRelocatedLiterals: Shadow relocated 2 string literal(s)")
      .contains("inst/io/argos/agent/core/boot/IgnoreMatchers.classdata: io.argos.agent.shaded.net.bytebuddy.")
      .contains("inst/io/argos/agent/core/boot/IgnoreMatchers.classdata: io/argos/agent/shaded/x/Y")
      .doesNotContain("PlatformDependent.classdata: ")
      .doesNotContain("AgentClassLoader")

    taskProject(
      agentJar(
        "inst/io/argos/agent/core/transport/NettyProps.classdata" to
          classFile(
            "io/argos/agent/core/transport/NettyProps",
            strings = listOf("io.argos.agent.shaded.io.netty.noUnsafe", "true"),
          )
      )
    )
    val allowed =
      runner(
          "checkOwnRelocatedLiterals",
          "-Pallowed=inst/io/argos/agent/core/transport/NettyProps.classdata: io.argos.agent.shaded.io.netty.noUnsafe",
        )
        .build()
    assertThat(allowed.task(":checkOwnRelocatedLiterals")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/own-literals.txt").readText())
      .contains("allowed  inst/io/argos/agent/core/transport/NettyProps.classdata: io.argos.agent.shaded.io.netty.noUnsafe")
  }

  private fun manifestJar(manifest: Map<String, String>?): File =
    writeJar(
      File(dir, "agent.jar"),
      manifest,
      linkedMapOf("io/argos/agent/bootstrap/A.class" to classFile("io/argos/agent/bootstrap/A")),
    )

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName(
    "T-BLD-010 checkManifestVersion fails without Implementation-Version (attribute, blank or no manifest) and on another version; passes on the project version"
  )
  fun checkManifestVersion() {
    val premain = "Premain-Class" to "io.argos.agent.AgentPremain"
    for (manifest in listOf(mapOf(premain), mapOf(premain, "Implementation-Version" to " "), null)) {
      taskProject(manifestJar(manifest))
      val missing = runner("checkManifestVersion").buildAndFail()
      assertThat(missing.task(":checkManifestVersion")?.outcome).`as`("$manifest").isEqualTo(TaskOutcome.FAILED)
      assertThat(missing.output)
        .contains("checkManifestVersion: agent.jar has no Implementation-Version in META-INF/MANIFEST.MF")
        .contains("expected '0.0.0-SNAPSHOT'")
        .contains("ignores argos.test.* (T-BLD-010)")
    }

    taskProject(manifestJar(mapOf(premain, "Implementation-Version" to "0.0.0-SNAPSHOT")))
    val stale = runner("checkManifestVersion", "-Pexpected=1.0.0").buildAndFail()
    assertThat(stale.task(":checkManifestVersion")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(stale.output)
      .contains("checkManifestVersion: agent.jar has Implementation-Version '0.0.0-SNAPSHOT', expected the project version '1.0.0'")

    val snapshot = runner("checkManifestVersion").build()
    assertThat(snapshot.task(":checkManifestVersion")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/manifest-version.txt").readText())
      .isEqualTo("Implementation-Version: 0.0.0-SNAPSHOT\nrelease build (argos.test.* ignored): false\n")

    taskProject(manifestJar(mapOf(premain, "Implementation-Version" to "1.0.0")))
    val release = runner("checkManifestVersion", "-Pexpected=1.0.0").build()
    assertThat(release.task(":checkManifestVersion")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/manifest-version.txt").readText())
      .isEqualTo("Implementation-Version: 1.0.0\nrelease build (argos.test.* ignored): true\n")
  }
}
