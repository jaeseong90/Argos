// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * test/agent/check-shading.sh (19 §5.6) against a compliant jar and against crafted bad jars, one
 * defect each (T-BLD-001 major version, T-BLD-002 packages/exposure/signatures/native/duplicates,
 * T-BLD-003 META-INF, T-BLD-004 Unsafe, T-BLD-006 size).
 */
@Tag("req:F-A31")
class CheckShadingScriptTest {
  private val script = File(repositoryRoot, "test/agent/check-shading.sh")

  @TempDir lateinit var dir: File

  private val manifest =
    linkedMapOf(
      "Premain-Class" to "io.argos.agent.AgentPremain",
      "Agent-Class" to "io.argos.agent.AgentPremain",
      "Can-Retransform-Classes" to "true",
      "Can-Redefine-Classes" to "false",
    )

  /** A jar with every element 19 §5.6 requires and nothing it forbids. */
  private fun compliantEntries(): LinkedHashMap<String, ByteArray> =
    linkedMapOf(
      "io/argos/agent/AgentPremain.class" to classFile("io/argos/agent/AgentPremain"),
      "io/argos/agent/bootstrap/Argos.class" to classFile("io/argos/agent/bootstrap/Argos"),
      "inst/io/argos/agent/core/Core.classdata" to classFile("io/argos/agent/core/Core"),
      "inst/io/argos/agent/instrumentation/servlet/ServletAdvice.classdata" to
        classFile("io/argos/agent/instrumentation/servlet/ServletAdvice"),
      "inst/io/argos/agent/shaded/net/bytebuddy/ByteBuddy.classdata" to
        classFile("io/argos/agent/shaded/net/bytebuddy/ByteBuddy"),
      "inst/META-INF/services/io.argos.agent.shaded.io.grpc.NameResolverProvider" to
        "io.argos.agent.shaded.io.grpc.DnsNameResolverProvider\n".toByteArray(),
      "META-INF/LICENSE" to "Apache License\nVersion 2.0, January 2004\n".toByteArray(),
      "META-INF/NOTICE" to "Argos APM\nCopyright 2026 Argos APM contributors\n".toByteArray(),
      "META-INF/THIRD-PARTY-NOTICES" to "net.bytebuddy:byte-buddy:1.18.15 Apache-2.0\n".toByteArray(),
      "META-INF/argos-deps.txt" to
        "# group:artifact:version:license\nnet.bytebuddy:byte-buddy:1.18.15:Apache-2.0\n".toByteArray(),
      "META-INF/sbom/agent.cdx.json" to
        "{\n  \"bomFormat\" : \"CycloneDX\",\n  \"specVersion\" : \"1.6\"\n}\n".toByteArray(),
    )

  private fun check(
    entries: Map<String, ByteArray> = compliantEntries(),
    manifestAttributes: Map<String, String>? = manifest,
    env: Map<String, String> = emptyMap(),
  ): ScriptResult {
    val jar = writeJar(File(dir, "argos-agent.jar"), manifestAttributes, entries)
    return runScript(script, listOf(jar.invariantSeparatorsPath), env)
  }

  private fun assertFails(result: ScriptResult, check: String, vararg details: String) {
    assertThat(result.exitCode).`as`(result.output).isEqualTo(1)
    assertThat(result.output).contains("FAIL [$check]").contains(*details)
    assertThat(result.output).contains("check-shading: FAILED")
  }

  @Test
  @DisplayName("T-BLD-002 a compliant agent jar passes every check (exit 0)")
  fun compliantJarPasses() {
    val result = check()
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output)
      .contains("ok   [packages] 5 classes")
      .contains("ok   [major-version] 5 classes: own major 52, shaded <= 52 (0 shaded below 52")
      .contains("check-shading: OK (5 classes")
      .doesNotContain("FAIL")
  }

  @Test
  @DisplayName("T-BLD-001 C-22 accepts shaded third-party classes below 52 (here 50, Java 6)")
  fun acceptsOlderShadedClasses() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/shaded/org/jctools/queues/MpscArrayQueue.classdata"] =
      classFile("io/argos/agent/shaded/org/jctools/queues/MpscArrayQueue", 50)
    val result = check(entries)
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("ok   [major-version] 6 classes: own major 52, shaded <= 52 (1 shaded below 52")
  }

  @Test
  @DisplayName("T-BLD-001 C-22 rejects a shaded class above 52 (53) and an own class below 52 (51)")
  fun rejectsShadedAbove52AndOwnBelow52() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/shaded/io/netty/util/Java9.classdata"] =
      classFile("io/argos/agent/shaded/io/netty/util/Java9", 53)
    entries["inst/io/argos/agent/core/Old.classdata"] = classFile("io/argos/agent/core/Old", 51)
    assertFails(
      check(entries),
      "major-version",
      "inst/io/argos/agent/shaded/io/netty/util/Java9.classdata  (major 53, shaded classes must be <= 52)",
      "inst/io/argos/agent/core/Old.classdata  (major 51)",
    )
  }

  @Test
  @DisplayName("T-BLD-002 rejects classes outside io/argos/agent (root and unrelocated inst/)")
  fun rejectsForeignPackages() {
    val entries = compliantEntries()
    entries["org/slf4j/Logger.class"] = classFile("org/slf4j/Logger")
    entries["inst/net/bytebuddy/Unrelocated.classdata"] = classFile("net/bytebuddy/Unrelocated")
    assertFails(check(entries), "packages", "org/slf4j", "net/bytebuddy")
  }

  @Test
  @DisplayName("T-BLD-002 rejects non-bootstrap classes at the root and plain .class below inst/")
  fun rejectsRootExposure() {
    val entries = compliantEntries()
    entries["io/argos/agent/core/Leaked.class"] = classFile("io/argos/agent/core/Leaked")
    entries["inst/io/argos/agent/core/NotRenamed.class"] = classFile("io/argos/agent/core/NotRenamed")
    assertFails(
      check(entries),
      "root-exposure",
      "io/argos/agent/core/Leaked.class  (root class outside io/argos/agent/bootstrap/)",
      "inst/io/argos/agent/core/NotRenamed.class  (plain .class below inst/, expected .classdata)",
    )
  }

  @Test
  @DisplayName("T-BLD-002 accepts only the manifest Premain-Class directly in io/argos/agent at the root")
  fun premainExceptionIsNarrow() {
    val entries = compliantEntries()
    entries["io/argos/agent/Other.class"] = classFile("io/argos/agent/Other")
    assertFails(check(entries), "root-exposure", "io/argos/agent/Other.class")
  }

  @Test
  @DisplayName("T-BLD-001 rejects a class with major version != 52 (here 55, Java 11)")
  fun rejectsWrongMajorVersion() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/core/Java11.classdata"] = classFile("io/argos/agent/core/Java11", 55)
    assertFails(
      check(entries),
      "major-version",
      "inst/io/argos/agent/core/Java11.classdata  (major 55)",
    )
  }

  @Test
  @DisplayName("T-BLD-002 rejects signature files (META-INF/*.SF|DSA|RSA)")
  fun rejectsSignatureFiles() {
    val entries = compliantEntries()
    entries["META-INF/SIGNER.SF"] = "Signature-Version: 1.0\n".toByteArray()
    entries["inst/META-INF/BC.RSA"] = byteArrayOf(1, 2, 3)
    assertFails(check(entries), "signatures", "META-INF/SIGNER.SF", "inst/META-INF/BC.RSA")
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-002 rejects native libraries (.so/.dll/.dylib, META-INF/native/)")
  fun rejectsNativeLibraries() {
    val entries = compliantEntries()
    entries["inst/META-INF/native/libnetty_transport_native_epoll_x86_64.so"] = byteArrayOf(0x7f)
    entries["win32-x86-64/argos.dll"] = byteArrayOf(0x4d)
    assertFails(check(entries), "native", "libnetty_transport_native_epoll_x86_64.so", "argos.dll")
  }

  @ParameterizedTest(name = "T-BLD-003 rejects a jar without {0}")
  @Tag("req:O-L04")
  @Tag("req:SEC-77")
  @ValueSource(
    strings =
      [
        "META-INF/LICENSE",
        "META-INF/NOTICE",
        "META-INF/THIRD-PARTY-NOTICES",
        "META-INF/argos-deps.txt",
        "META-INF/sbom/agent.cdx.json",
      ]
  )
  fun rejectsMissingMetaInfFile(file: String) {
    val entries = compliantEntries()
    entries.remove(file)
    assertFails(check(entries), "meta-inf", "missing $file")
  }

  @Test
  @Tag("req:SEC-77")
  @DisplayName("T-BLD-003 rejects malformed or UNKNOWN argos-deps.txt lines and a non-CycloneDX SBOM")
  fun rejectsMalformedMetadata() {
    val entries = compliantEntries()
    entries["META-INF/argos-deps.txt"] =
      "io.grpc:grpc-api:1.85.1:UNKNOWN\nnot a coordinate\n".toByteArray()
    entries["META-INF/sbom/agent.cdx.json"] = "{\"spdxVersion\": \"SPDX-2.3\"}".toByteArray()
    assertFails(
      check(entries),
      "meta-inf",
      "argos-deps.txt license unknown: io.grpc:grpc-api:1.85.1:UNKNOWN",
      "argos-deps.txt malformed line: not a coordinate",
      "META-INF/sbom/agent.cdx.json is not a CycloneDX JSON document",
    )
  }

  @Test
  @DisplayName("T-BLD-003 rejects a manifest without Premain-Class or with Add-Opens/Boot-Class-Path")
  fun rejectsBadManifest() {
    val attributes = LinkedHashMap(manifest)
    attributes.remove("Premain-Class")
    attributes["Add-Opens"] = "java.base/java.lang"
    attributes["Boot-Class-Path"] = "argos-agent.jar"
    assertFails(
      check(manifestAttributes = attributes),
      "meta-inf",
      "MANIFEST.MF lacks Premain-Class",
      "MANIFEST.MF must not declare Add-Opens",
      "MANIFEST.MF must not declare Boot-Class-Path",
    )
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-004 rejects a sun.misc.Unsafe reference outside the allow-list")
  fun rejectsUnsafe() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/shaded/io/netty/util/internal/PlatformDependent0.classdata"] =
      classFile(
        "io/argos/agent/shaded/io/netty/util/internal/PlatformDependent0",
        classRefs = listOf("sun/misc/Unsafe"),
      )
    entries["inst/io/argos/agent/core/Reflective.classdata"] =
      classFile("io/argos/agent/core/Reflective", strings = listOf("sun.misc.Unsafe"))
    assertFails(
      check(entries),
      "unsafe",
      "io.argos.agent.shaded.io.netty.util.internal.PlatformDependent0",
      "io.argos.agent.core.Reflective",
    )
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName("T-BLD-004 accepts an Unsafe reference listed in the allow-list")
  fun acceptsAllowlistedUnsafe() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/shaded/io/netty/util/internal/PlatformDependent0.classdata"] =
      classFile(
        "io/argos/agent/shaded/io/netty/util/internal/PlatformDependent0",
        classRefs = listOf("sun/misc/Unsafe"),
      )
    val allowlist =
      File(dir, "allow.txt").apply {
        writeText(
          "# test allow-list\n" +
            "io/argos/agent/shaded/io/netty/util/internal/PlatformDependent0  # test reason\n"
        )
      }
    val result = check(entries, env = mapOf("ARGOS_UNSAFE_ALLOWLIST" to allowlist.invariantSeparatorsPath))
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("ok   [unsafe]").contains("(1 entries)")
  }

  @Test
  @DisplayName("T-BLD-002 rejects the same class at the root and below inst/")
  fun rejectsDuplicateClasses() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/bootstrap/Argos.classdata"] = classFile("io/argos/agent/bootstrap/Argos")
    assertFails(check(entries), "duplicates", "io/argos/agent/bootstrap/Argos  (class present more than once")
  }

  @Test
  @DisplayName("T-BLD-002 rejects entries outside inst/ that 10 §4.1 does not list (root META-INF/services)")
  fun rejectsRootServiceFile() {
    val entries = compliantEntries()
    entries["META-INF/services/javax.annotation.processing.Processor"] =
      "io.argos.agent.shaded.Processor\n".toByteArray()
    assertFails(
      check(entries),
      "root-entries",
      "META-INF/services/javax.annotation.processing.Processor  (not allowed outside inst/)",
    )
  }

  @Test
  @DisplayName("T-BLD-002 rejects root properties, module-info and multi-release entries; accepts the 10 §4.1 layout")
  fun rejectsOtherRootEntries() {
    val entries = compliantEntries()
    entries["log4j2.component.properties"] = "x=y\n".toByteArray()
    entries["module-info.class"] = classFile("module-info")
    entries["META-INF/versions/11/io/argos/agent/bootstrap/Argos.class"] =
      classFile("io/argos/agent/bootstrap/Argos")
    entries["io/argos/agent/bootstrap/argos.properties"] = "x=y\n".toByteArray()
    assertFails(
      check(entries),
      "root-entries",
      "log4j2.component.properties  (not allowed outside inst/)",
      "module-info.class  (not allowed outside inst/)",
      "META-INF/versions/11/io/argos/agent/bootstrap/Argos.class  (not allowed outside inst/)",
      "io/argos/agent/bootstrap/argos.properties  (not allowed outside inst/)",
    )

    val layout = compliantEntries()
    layout["argos-agent-default.yml"] = "# reference only\n".toByteArray()
    val result = check(layout)
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("ok   [root-entries]")
  }

  @Test
  @DisplayName("T-BLD-001/002 rejects entries differing only in case (a.classdata major 55 + A.classdata) on any OS")
  fun rejectsCaseInsensitiveDuplicates() {
    val entries = compliantEntries()
    entries["inst/io/argos/agent/core/a.classdata"] = classFile("io/argos/agent/core/a", 55)
    entries["inst/io/argos/agent/core/A.classdata"] = classFile("io/argos/agent/core/A")
    val result = check(entries)
    assertFails(
      result,
      "duplicates",
      "inst/io/argos/agent/core/A.classdata  (case-insensitive duplicate entries)",
      "inst/io/argos/agent/core/a.classdata  (case-insensitive duplicate entries)",
    )
    // Read from the jar entries, not from the extracted files that a case-insensitive file system
    // merges: the Java 11 class is found on Windows too.
    assertFails(result, "major-version", "inst/io/argos/agent/core/a.classdata  (major 55)")
  }

  @Test
  @Tag("req:P-3")
  @DisplayName("T-BLD-006 rejects a jar above 15 MB (15,000,000 bytes) and warns above 12 MB (12,000,000 bytes)")
  fun enforcesSizeLimit() {
    // About 15.3 MB: inside the former gap 15,000,001..15,728,640 bytes (15 MiB), now rejected.
    val big = compliantEntries()
    big["inst/io/argos/agent/core/blob.bin"] = randomBytes(15_300_000)
    val bigResult = check(big)
    assertThat(File(dir, "argos-agent.jar").length()).isBetween(15_000_001L, 15_728_640L)
    assertFails(bigResult, "size", "bytes > 15000000 bytes (15 MB, 10^6 bytes per MB)")

    // About 12.5 MB: warning, still passes (below 12 MiB = 12,582,912 bytes, so this also checks
    // that the warning uses decimal megabytes).
    val large = compliantEntries()
    large["inst/io/argos/agent/core/blob.bin"] = randomBytes(12_500_000)
    val result = check(large)
    assertThat(File(dir, "argos-agent.jar").length()).isBetween(12_000_001L, 12_582_912L)
    assertThat(result.exitCode).`as`(result.output).isEqualTo(0)
    assertThat(result.output).contains("WARN [size]").contains("12 MB warning threshold (12000000 bytes")

    // About 11.5 MB: passes without a warning.
    val medium = compliantEntries()
    medium["inst/io/argos/agent/core/blob.bin"] = randomBytes(11_500_000)
    val mediumResult = check(medium)
    assertThat(File(dir, "argos-agent.jar").length()).isLessThan(12_000_000L)
    assertThat(mediumResult.exitCode).`as`(mediumResult.output).isEqualTo(0)
    assertThat(mediumResult.output).doesNotContain("WARN [size]").contains("ok   [size]")
  }

  @Test
  @DisplayName("check-shading.sh exits 1 on a missing argument or a missing jar")
  fun usageErrors() {
    assertThat(runScript(script, emptyList()).exitCode).isEqualTo(1)
    val missing = runScript(script, listOf(File(dir, "missing.jar").invariantSeparatorsPath))
    assertThat(missing.exitCode).isEqualTo(1)
    assertThat(missing.output).contains("jar not found")
  }

  /** Incompressible content, so the jar really grows. */
  private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { java.util.Random(42).nextBytes(it) }
}
