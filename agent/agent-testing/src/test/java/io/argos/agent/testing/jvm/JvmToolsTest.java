/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsers of jcmd output, the Unsafe scanner and agent jar variants. */
class JvmToolsTest {
  @TempDir Path dir;

  /** A minimal class file whose constant pool holds the given UTF-8 constants (and others). */
  static byte[] classFile(String... utf8) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeInt(0xCAFEBABE);
    out.writeShort(0);
    out.writeShort(52);
    // count: utf8 entries + Integer + Long (2 slots) + Class + MethodHandle + 1
    out.writeShort(utf8.length + 1 + 2 + 1 + 1 + 1);
    for (String constant : utf8) {
      out.writeByte(1);
      out.writeUTF(constant);
    }
    out.writeByte(3);
    out.writeInt(42);
    out.writeByte(5);
    out.writeLong(7L);
    out.writeByte(7);
    out.writeShort(1);
    out.writeByte(15);
    out.writeByte(6);
    out.writeShort(1);
    out.writeShort(0x21);
    return bytes.toByteArray();
  }

  static void jar(Path file, String... entries) throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "0.0.0-SNAPSHOT");
    try (OutputStream stream = Files.newOutputStream(file);
        JarOutputStream out = new JarOutputStream(stream, manifest)) {
      for (int i = 0; i < entries.length; i += 2) {
        out.putNextEntry(new JarEntry(entries[i]));
        out.write(classFile("x/Y", entries[i + 1]));
        out.closeEntry();
      }
    }
  }

  @Test
  @Tag("req:F-A35")
  @DisplayName(
      "T-BLD-004 UnsafeScanner: sun/misc/Unsafe and sun.misc.Unsafe constants, classdata entries,"
          + " the check-shading.sh allow-list format")
  void unsafeScanner() throws Exception {
    assertThat(UnsafeScanner.referencesUnsafe(classFile("java/lang/Object"))).isFalse();
    assertThat(UnsafeScanner.referencesUnsafe(classFile("sun/misc/Unsafe"))).isTrue();
    assertThat(UnsafeScanner.referencesUnsafe(classFile("(Lsun/misc/Unsafe;)V"))).isTrue();
    assertThat(UnsafeScanner.referencesUnsafe(classFile("sun.misc.Unsafe"))).isTrue();
    assertThat(UnsafeScanner.utf8Constants(classFile("a", "b"))).containsExactly("a", "b");
    assertThatThrownBy(() -> UnsafeScanner.utf8Constants(new byte[] {1, 2, 3, 4, 5}))
        .isInstanceOf(IllegalArgumentException.class);

    Path agent = dir.resolve("agent.jar");
    jar(
        agent,
        "io/argos/agent/bootstrap/Argos.class",
        "java/lang/Object",
        "inst/io/argos/agent/shaded/a/UsesUnsafe.classdata",
        "sun/misc/Unsafe",
        "inst/io/argos/agent/shaded/b/Reflective.classdata",
        "sun.misc.Unsafe");
    assertThat(UnsafeScanner.scan(agent))
        .containsExactly(
            "io.argos.agent.shaded.a.UsesUnsafe", "io.argos.agent.shaded.b.Reflective");
    Path allowlist = dir.resolve("unsafe-allowlist.txt");
    Files.writeString(
        allowlist,
        "# comment\n\n  io/argos/agent/shaded/a/UsesUnsafe.classdata  # reason, #1\r\n",
        StandardCharsets.UTF_8);
    assertThat(UnsafeScanner.readAllowlist(allowlist))
        .containsExactly("io.argos.agent.shaded.a.UsesUnsafe");
    assertThat(UnsafeScanner.violations(agent, allowlist))
        .containsExactly("io.argos.agent.shaded.b.Reflective");
    // The repository allow-list (single source with check-shading.sh): ADR-0009 C-74 lets a class
    // stay when a test proves its conditions (UnsafeProofTransportTest), and every line must carry
    // a reason;
    // only shaded dependencies can be on it, never the agent's own code.
    Path repository = Path.of(System.getProperty("argos.test.unsafeAllowlist"));
    assertThat(UnsafeScanner.readAllowlist(repository))
        .allSatisfy(name -> assertThat(name).startsWith("io.argos.agent.shaded."));
    assertThat(Files.readAllLines(repository, StandardCharsets.UTF_8))
        .filteredOn(line -> !line.isBlank() && !line.stripLeading().startsWith("#"))
        .allSatisfy(
            line -> assertThat(line).as("C-74: a reason per line").containsPattern("#\\s*\\S"));
  }

  @Test
  @Tag("req:DSN-18-15")
  @DisplayName("T-BLD-010 release-flavoured jar copy: only Implementation-Version changes")
  void releaseFlavour() throws Exception {
    Path snapshot = dir.resolve("snapshot.jar");
    jar(snapshot, "a/B.class", "x");
    assertThat(AgentJars.version(snapshot)).isEqualTo("0.0.0-SNAPSHOT");
    Path release = AgentJars.withVersion(snapshot, dir.resolve("out/release.jar"), "0.0.0");
    assertThat(AgentJars.version(release)).isEqualTo("0.0.0");
    assertThat(UnsafeScanner.scan(release)).isEmpty();
    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(release.toFile())) {
      assertThat(jar.getEntry("a/B.class")).isNotNull();
    }
  }

  @Test
  @Tag("req:F-A15")
  @DisplayName("C-78 jar copy without one entry: manifest and other entries unchanged")
  void withoutEntry() throws Exception {
    Path source = dir.resolve("full.jar");
    jar(source, "a/B.class", "x", "inst/c/D.classdata", "y");
    Path broken =
        AgentJars.withoutEntry(source, dir.resolve("out/broken.jar"), "inst/c/D.classdata");
    assertThat(AgentJars.version(broken)).isEqualTo("0.0.0-SNAPSHOT");
    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(broken.toFile())) {
      assertThat(jar.getEntry("a/B.class")).isNotNull();
      assertThat(jar.getEntry("inst/c/D.classdata")).isNull();
    }
    assertThatThrownBy(
            () -> AgentJars.withoutEntry(source, dir.resolve("out/other.jar"), "no/such.class"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no/such.class");
  }

  @Test
  @Tag("req:N-02")
  @DisplayName("GC.class_histogram rows (JDK 8 and 9+ formats); io.argos. bytes and instances")
  void histogram() {
    String output =
        "12345:\n"
            + " num     #instances         #bytes  class name (module)\n"
            + "-------------------------------------------------------\n"
            + "   1:         12345         678900  [B (java.base@21.0.1)\n"
            + "   2:           100           2400  io.argos.agent.core.trace.Transaction\n"
            + "   3:            10            320  io.argos.agent.shaded.net.bytebuddy.X\n"
            + "   4:             2             96  org.apache.catalina.loader"
            + ".ParallelWebappClassLoader\n"
            + "Total         12457         681716\n";
    ClassHistogram histogram = ClassHistogram.parse(output);
    assertThat(histogram.rows()).hasSize(4);
    assertThat(histogram.rows().get(0).module()).isEqualTo("java.base@21.0.1");
    assertThat(histogram.bytesOf("io.argos.")).isEqualTo(2720);
    assertThat(histogram.instancesOf("io.argos.")).isEqualTo(110);
    assertThat(histogram.instancesOfClass("org.apache.catalina.loader.ParallelWebappClassLoader"))
        .isEqualTo(2);
    assertThat(histogram.totalBytes()).isEqualTo(681716);
  }

  @Test
  @Tag("req:N-26")
  @DisplayName("VM.native_memory summary: committed KB per category, threads, 18 §5.9 diff limits")
  void nmt() {
    String before =
        """
        Native Memory Tracking:

        Total: reserved=2541623KB, committed=212147KB
        -                 Java Heap (reserved=1048576KB, committed=65536KB)
                                    (mmap: reserved=1048576KB, committed=65536KB)
        -                    Thread (reserved=34867KB, committed=1335KB)
                                    (thread #34)
        -                  Internal (reserved=1166KB, committed=1166KB)
        -                     Other (reserved=48KB, committed=48KB)
        """;
    String after =
        before
            .replace("(thread #34)", "(threads #39)")
            .replace(
                "Internal (reserved=1166KB, committed=1166KB)",
                "Internal (reserved=9166KB, committed=9166KB, peak=9200KB)");
    NmtSummary base = NmtSummary.parse(before);
    assertThat(base.committedKb("Java Heap")).isEqualTo(65536);
    assertThat(base.committedKb("Other")).isEqualTo(48);
    assertThat(base.threads()).isEqualTo(34);
    NmtSummary.Diff diff = base.diff(NmtSummary.parse(after));
    assertThat(diff.internalOtherKb()).isEqualTo(8000);
    assertThat(diff.threads()).isEqualTo(5);
    assertThat(diff.withinLimits()).isTrue();
    assertThat(new NmtSummary.Diff(16 * 1024 + 1, 0).withinLimits()).isFalse();
    assertThat(new NmtSummary.Diff(0, 7).withinLimits()).isFalse();
    assertThatThrownBy(() -> NmtSummary.parse("Native memory tracking is not enabled"))
        .hasMessageContaining("NativeMemoryTracking");
  }

  @Test
  @Tag("req:N-26")
  @DisplayName("Thread.print: thread names and the argos- threads")
  void threads() {
    String dump =
        """
        2026-11-03 03:00:00
        Full thread dump OpenJDK 64-Bit Server VM:

        "main" #1 prio=5 os_prio=0 tid=0x1 nid=0x2 waiting on condition
           java.lang.Thread.State: WAITING

        "argos-sender" #20 daemon prio=5 tid=0x3 nid=0x4 waiting

        "argos-logger" #21 daemon prio=5

        "VM Thread" os_prio=2 tid=0x5 nid=0x6 runnable
        """;
    assertThat(ThreadCounter.names(dump))
        .containsExactly("main", "argos-sender", "argos-logger", "VM Thread");
    assertThat(ThreadCounter.namesStartingWith(dump, "argos-"))
        .isEqualTo(List.of("argos-sender", "argos-logger"));
  }
}
