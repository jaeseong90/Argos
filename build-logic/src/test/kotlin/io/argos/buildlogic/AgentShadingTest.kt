// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The transport's shading rules (ADR-0005 decisions 3, 4, 5, 7; ADR-0008; ADR-0009 C-74; ADR-0010
 * C-104) and checkJulBridge (ADR-0005 decision 4, AGT-02).
 */
class AgentShadingTest {
  @TempDir lateinit var dir: File

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-005 the protocol and the whole gRPC stack are relocated below the shaded prefix")
  fun relocated() {
    assertThat(AgentShading.RELOCATED_PACKAGES)
      .contains("io.argos.proto", "io.grpc", "io.netty", "com.google.protobuf", "com.google.common", "io.perfmark")
    assertThat(AgentShading.RELOCATED_PACKAGES).doesNotHaveDuplicates()
    assertThat(AgentShading.RELOCATED_PACKAGES).noneMatch { it.startsWith(AgentShading.OWN_PACKAGES[0] + ".") }
    assertThat(AgentShading.PROPERTY_STRING_POLICY).isEqualTo(RelocatedStringPolicy.RELOCATED)
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName(
    "T-BLD-005 ADR-0005 decision 7: every Netty property the agent sets is a checked property string and an allowed own literal"
  )
  fun nettyProperties() {
    val set =
      listOf(
        "io.netty.noUnsafe",
        "io.netty.allocator.type",
        "io.netty.noPreferDirect",
        "io.netty.maxDirectMemory",
        "io.netty.tryReflectionSetAccessible",
        "io.netty.eventLoopThreads",
        "io.netty.allocator.numDirectArenas",
        "io.netty.leakDetection.level",
        "io.netty.recycler.maxCapacityPerThread",
        "io.netty.noKeySetOptimization",
      )
    assertThat(AgentShading.PROPERTY_STRINGS).containsAll(set)
    assertThat(AgentShading.PROPERTY_STRINGS)
      .contains("io.grpc.netty.useCustomAllocator", "io.grpc.internal.DnsNameResolverProvider.enable_jndi")
    assertThat(AgentShading.OWN_RELOCATED_LITERALS)
      .containsExactlyInAnyOrderElementsOf(
        set.map { "inst/io/argos/agent/core/transport/NettyProps.classdata: ${AgentShading.PREFIX}.$it" }
      )
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("C-74: never-loaded Unsafe users are original class paths; ADR-0005 decision 5: gRPC is the minimize root")
  fun unsafeAndMinimize() {
    assertThat(AgentShading.TRANSPORT_UNSAFE_EXCLUDED_CLASSES).allMatch { it.endsWith(".class") && !it.startsWith("/") }
    assertThat(AgentShading.TRANSPORT_UNSAFE_EXCLUDED_CLASSES).doesNotHaveDuplicates()
    assertThat(AgentShading.TRANSPORT_UNSAFE_EXCLUDED_CLASSES).doesNotContainAnyElementsOf(AgentShading.UNSAFE_EXCLUDED_CLASSES)
    assertThat(AgentShading.MINIMIZE_ROOT_GROUPS).containsExactly("io.grpc")
    assertThat(AgentShading.JUL_LOGGER_BRIDGE).startsWith("io.argos.agent.")
    assertThat(AgentShading.JUL_FORBIDDEN_CLASSES)
      .containsExactly("java/util/logging/Logger", "java/util/logging/LogManager")
  }

  /** A class with one method calling [owner].[member] with [descriptor], and [declared] methods. */
  private fun classWithCall(
    internalName: String,
    owner: String? = null,
    member: String = "",
    descriptor: String = "",
    declared: List<Pair<String, String>> = emptyList(),
  ): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
      out.writeInt(0xCAFEBABE.toInt())
      out.writeShort(0)
      out.writeShort(52)
      val pool = ByteArrayOutputStream()
      val poolOut = DataOutputStream(pool)
      var count = 1
      fun utf8(value: String): Int {
        poolOut.writeByte(1)
        poolOut.writeUTF(value)
        return count++
      }
      fun classRef(name: String): Int {
        val index = utf8(name)
        poolOut.writeByte(7)
        poolOut.writeShort(index)
        return count++
      }
      val thisClass = classRef(internalName)
      val superClass = classRef("java/lang/Object")
      if (owner != null) {
        val ownerClass = classRef(owner)
        val name = utf8(member)
        val type = utf8(descriptor)
        poolOut.writeByte(12)
        poolOut.writeShort(name)
        poolOut.writeShort(type)
        val natIndex = count++
        poolOut.writeByte(10)
        poolOut.writeShort(ownerClass)
        poolOut.writeShort(natIndex)
        count++
      }
      val methods = declared.map { (name, type) -> utf8(name) to utf8(type) }
      poolOut.flush()
      out.writeShort(count)
      out.write(pool.toByteArray())
      out.writeShort(0x0021)
      out.writeShort(thisClass)
      out.writeShort(superClass)
      out.writeShort(0) // interfaces
      out.writeShort(0) // fields
      out.writeShort(methods.size)
      for ((name, type) in methods) {
        out.writeShort(0x0401) // public abstract: no code attribute needed for this check
        out.writeShort(name)
        out.writeShort(type)
        out.writeShort(0)
      }
      out.writeShort(0)
    }
    return bytes.toByteArray()
  }

  private fun taskProject(vararg entries: Pair<String, ByteArray>) {
    writeJar(File(dir, "agent.jar"), mapOf("Premain-Class" to "io.argos.agent.AgentPremain"), linkedMapOf(*entries))
    File(dir, "settings.gradle").writeText("rootProject.name = 'jul-bridge'\n")
    File(dir, "build.gradle")
      .writeText(
        """
        import io.argos.buildlogic.CheckJulBridge

        plugins { id 'argos.license-check' }

        tasks.register('checkJulBridge', CheckJulBridge) {
          jar = file('agent.jar')
          bridge = 'io.argos.agent.core.log.JulLogger'
          forbidden = ['java/util/logging/Logger', 'java/util/logging/LogManager']
          reportFile = layout.buildDirectory.file('jul-bridge.txt')
        }
        """
          .trimIndent()
      )
  }

  private fun runner(): GradleRunner =
    GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments("checkJulBridge")

  private val bridgeEntry = "inst/io/argos/agent/core/log/JulLogger.classdata"
  private val bridgeName = "io/argos/agent/core/log/JulLogger"

  private fun bridge() =
    classWithCall(bridgeName, declared = listOf("info" to "(Ljava/lang/String;)V"))

  @Test
  @Tag("req:F-A35")
  @DisplayName(
    "ADR-0005 decision 4: checkJulBridge passes when every library call exists in the bridge and writes who uses it"
  )
  fun bridgeIsComplete() {
    taskProject(
      bridgeEntry to bridge(),
      "inst/io/argos/agent/shaded/io/grpc/A.classdata" to
        classWithCall("io/argos/agent/shaded/io/grpc/A", bridgeName, "info", "(Ljava/lang/String;)V"),
    )
    val result = runner().build()
    assertThat(result.task(":checkJulBridge")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    assertThat(File(dir, "build/jul-bridge.txt").readText())
      .contains("1 class(es) log through io.argos.agent.core.log.JulLogger")
      .contains("inst/io/argos/agent/shaded/io/grpc/A.classdata")
      .contains("info(Ljava/lang/String;)V")
      .doesNotContain("PROBLEM")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("AGT-02: a class that still references java.util.logging.Logger fails the build")
  fun forbiddenReference() {
    taskProject(
      bridgeEntry to bridge(),
      "inst/io/argos/agent/shaded/io/grpc/A.classdata" to
        classFile("io/argos/agent/shaded/io/grpc/A", classRefs = listOf("java/util/logging/Logger")),
    )
    val result = runner().buildAndFail()
    assertThat(result.task(":checkJulBridge")?.outcome).isEqualTo(TaskOutcome.FAILED)
    assertThat(result.output).contains("references java.util.logging.Logger")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("AGT-02: LogManager, also as a dotted string, fails the build")
  fun forbiddenLogManager() {
    taskProject(
      bridgeEntry to bridge(),
      "inst/io/argos/agent/shaded/com/google/B.classdata" to
        classFile("io/argos/agent/shaded/com/google/B", strings = listOf("java.util.logging.LogManager")),
    )
    val result = runner().buildAndFail()
    assertThat(result.output).contains("references java.util.logging.LogManager")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName(
    "ADR-0005 decision 4: a call the bridge does not declare fails the build instead of NoSuchMethodError at run time"
  )
  fun missingMethod() {
    taskProject(
      bridgeEntry to bridge(),
      "inst/io/argos/agent/shaded/io/grpc/A.classdata" to
        classWithCall("io/argos/agent/shaded/io/grpc/A", bridgeName, "entering", "(Ljava/lang/String;)V"),
    )
    val result = runner().buildAndFail()
    assertThat(result.output)
      .contains("uses entering(Ljava/lang/String;)V, which io.argos.agent.core.log.JulLogger does not declare")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("ADR-0005 decision 4: a reference to a missing bridge class fails the build")
  fun missingBridge() {
    taskProject(
      "inst/io/argos/agent/shaded/io/grpc/A.classdata" to
        classWithCall("io/argos/agent/shaded/io/grpc/A", bridgeName, "info", "(Ljava/lang/String;)V")
    )
    val result = runner().buildAndFail()
    assertThat(result.output).contains("but $bridgeEntry is missing")
  }
}
