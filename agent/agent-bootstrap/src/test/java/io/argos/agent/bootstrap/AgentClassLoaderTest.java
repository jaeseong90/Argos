/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.core.fixture.LoaderFixture;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AgentClassLoader (10 §4.2, DSN-10-01, F-A31): classdata loading, delegation, resources. */
@Tag("req:F-A31")
class AgentClassLoaderTest {
  private static final String FIXTURE = "io.argos.agent.core.fixture.LoaderFixture";
  private static final String FIXTURE_FILE = "io/argos/agent/core/fixture/LoaderFixture.class";
  private static final String LATER = "io.argos.agent.core.fixture.LoaderFixture$Later";

  @TempDir File dir;
  private File jarPath;
  private JarFile jar;

  @BeforeEach
  void writeJar() throws IOException {
    jarPath = new File(dir, "argos-agent.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarPath))) {
      put(
          out,
          "inst/io/argos/agent/core/fixture/LoaderFixture.classdata",
          bytes(LoaderFixture.class));
      put(
          out,
          "inst/io/argos/agent/core/fixture/LoaderFixture$Later.classdata",
          bytes(LoaderFixture.Later.class));
      put(
          out,
          "inst/META-INF/services/example.Service",
          "example.Impl\n".getBytes(StandardCharsets.UTF_8));
      put(out, "io/argos/agent/bootstrap/Argos.class", bytes(Argos.class));
    }
    jar = new JarFile(jarPath, false);
  }

  @AfterEach
  void closeJar() throws IOException {
    jar.close();
  }

  @Test
  @DisplayName("defines agent classes from inst/**.classdata, never from the parent (own packages)")
  void definesOwnClassesFromClassdata() throws Exception {
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, getClass().getClassLoader());
    Class<?> fixture = loader.loadClass(FIXTURE);
    assertThat(fixture.getClassLoader()).isSameAs(loader);
    assertThat(fixture).isNotSameAs(LoaderFixture.class); // the parent also has it: not used
    assertThat(loader.loadClass(FIXTURE)).isSameAs(fixture);
    assertThat(fixture.getPackage()).isNotNull();
    assertThat(fixture.getPackage().getName()).isEqualTo("io.argos.agent.core.fixture");
    assertThat(fixture.getProtectionDomain().getCodeSource().getLocation())
        .isEqualTo(jarPath.toURI().toURL());
    assertThat(fixture.getMethod("hello").invoke(null))
        .isEqualTo("hello from ArgosAgentClassLoader");
  }

  @Test
  @DisplayName("delegates other classes to the parent first (JDK, bootstrap API)")
  void parentFirstForOtherClasses() throws Exception {
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, getClass().getClassLoader());
    assertThat(loader.loadClass("java.lang.String")).isSameAs(String.class);
    assertThat(loader.loadClass(Argos.class.getName())).isSameAs(Argos.class);
    assertThatThrownBy(() -> loader.loadClass("io.argos.agent.core.Missing"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> loader.loadClass("io.argos.agent.shaded.net.bytebuddy.Missing"))
        .isInstanceOf(ClassNotFoundException.class);
  }

  @Test
  @DisplayName(
      "maps resources to inst/: .class to .classdata, META-INF/services/* to inst/META-INF")
  void resources() throws IOException {
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, null);
    URL classFile = loader.getResource(FIXTURE_FILE);
    assertThat(classFile).isNotNull();
    assertThat(classFile.toExternalForm())
        .startsWith("jar:")
        .endsWith("!/inst/io/argos/agent/core/fixture/LoaderFixture.classdata");
    try (InputStream in = loader.getResourceAsStream(FIXTURE_FILE)) {
      assertThat(read(in)).isEqualTo(bytes(LoaderFixture.class));
    }
    List<URL> services = Collections.list(loader.getResources("META-INF/services/example.Service"));
    assertThat(services).hasSize(1);
    URLConnection connection = services.get(0).openConnection();
    connection.setUseCaches(false); // a cached JarFile would keep the jar open (Windows)
    try (InputStream in = connection.getInputStream()) {
      assertThat(new String(read(in), StandardCharsets.UTF_8)).isEqualTo("example.Impl\n");
    }
    assertThat(loader.getResource("META-INF/services/missing")).isNull();
    assertThat(loader.getResource("")).isNull();
  }

  @Test
  @DisplayName(
      "serves the bootstrap API class files from the jar root when the parent cannot (JDK 8)")
  void bootstrapClassFileFallback() throws IOException {
    // Parent = bootstrap loader: it does not see this test's jar.
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, null);
    URL url = loader.getResource("io/argos/agent/bootstrap/Argos.class");
    assertThat(url).isNotNull();
    assertThat(url.toExternalForm()).endsWith("!/io/argos/agent/bootstrap/Argos.class");
    try (InputStream in = loader.getResourceAsStream("io/argos/agent/bootstrap/Argos.class")) {
      assertThat(read(in)).isEqualTo(bytes(Argos.class));
    }
    // Only bootstrap class files: other root entries stay invisible.
    assertThat(loader.getResource("io/argos/agent/bootstrap/Missing.class")).isNull();
  }

  @Test
  @DisplayName(
      "own packages: core, instrumentation, shaded; never bootstrap or application classes")
  void ownPackages() {
    assertThat(AgentClassLoader.isOwnClass("io.argos.agent.core.boot.AgentStarter")).isTrue();
    assertThat(AgentClassLoader.isOwnClass("io.argos.agent.instrumentation.api.ModuleIds"))
        .isTrue();
    assertThat(AgentClassLoader.isOwnClass("io.argos.agent.shaded.net.bytebuddy.ByteBuddy"))
        .isTrue();
    assertThat(AgentClassLoader.isOwnClass("io.argos.agent.bootstrap.Argos")).isFalse();
    assertThat(AgentClassLoader.isOwnClass("net.bytebuddy.ByteBuddy")).isFalse();
    assertThat(AgentClassLoader.entryName("/a/B.class")).isEqualTo("inst/a/B.classdata");
    assertThat(AgentClassLoader.entryName("a/b.txt")).isEqualTo("inst/a/b.txt");
  }

  @Test
  @DisplayName(
      "JDK 8: the platform class loader does not exist, the parent is the bootstrap loader")
  void platformClassLoaderOnJava8() {
    if (System.getProperty("java.specification.version").equals("1.8")) {
      assertThat(AgentBootstrap.platformClassLoader()).isNull();
    } else {
      ClassLoader platform = AgentBootstrap.platformClassLoader();
      assertThat(platform).isNotNull();
      // Found without reflection, and the same loader the JDK reports (C-79).
      assertThat(platform).isSameAs(AgentBootstrap.platformClassLoaderReflectively());
      assertThat(platform.getClass().getName()).isEqualTo(AgentBootstrap.PLATFORM_LOADER_CLASS);
    }
    assertThat(AgentBootstrap.isJava8("1.8")).isTrue();
    assertThat(AgentBootstrap.isJava8("11")).isFalse();
    assertThat(AgentBootstrap.isJava8(null)).isFalse();
    assertThat(AgentBootstrap.topmostAncestor(null)).isNull();
  }

  @Test
  @Tag("req:N-30")
  @DisplayName(
      "C-79: the loader counts defined classes and bytes and records the premain thread's classes"
          + " (bounded)")
  void startPathStatistics() throws Exception {
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, getClass().getClassLoader());
    AgentControl control = new AgentControl(loader);
    assertThat(control.classLoaderStatistics()).containsExactly(0L, 0L, 0L, 0L);
    loader.startRecording(Thread.currentThread());
    loader.loadClass(FIXTURE);
    long[] stats = control.classLoaderStatistics();
    assertThat(stats[0]).isEqualTo(1);
    assertThat(stats[1]).isEqualTo(bytes(LoaderFixture.class).length);
    assertThat(stats[2]).isNotNegative();
    assertThat(stats[3]).isNotNegative();
    assertThat(control.stopPremainRecording()).containsExactly(FIXTURE);
    assertThat(control.agentManifest()).isNull(); // the test jar has no manifest
    AgentControl outsideJar = new AgentControl();
    assertThat(outsideJar.classLoaderStatistics()).isNull();
    assertThat(outsideJar.stopPremainRecording()).isNull();
    assertThat(outsideJar.agentManifest()).isNull();
    assertThat(AgentClassLoader.MAX_RECORDED).isEqualTo(8192);
    // One lock per class name: agent threads and the premain thread do not serialise (C-79).
    assertThat(AgentClassLoader.PARALLEL_CAPABLE).isTrue();
  }

  @Test
  @Tag("req:N-30")
  @DisplayName("C-79: names beyond the recording cap are counted, not kept; other threads are not")
  void recordingIsBounded() throws Exception {
    AgentClassLoader full = new AgentClassLoader(jar, jarPath, getClass().getClassLoader(), 0);
    full.startRecording(Thread.currentThread());
    full.loadClass(FIXTURE);
    assertThat(full.stopRecording()).containsExactly("... 1 more");

    AgentClassLoader other = new AgentClassLoader(jar, jarPath, getClass().getClassLoader(), 4);
    other.startRecording(new Thread());
    other.loadClass(FIXTURE);
    assertThat(other.stopRecording()).isEmpty();
    assertThat(other.statistics()[0]).isEqualTo(1);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "C-79: stopping the premain recording drops the names; classes defined later are not"
          + " recorded and a second stop returns no names (bounded memory)")
  void recordingIsDroppedWhenStopped() throws Exception {
    AgentClassLoader loader = new AgentClassLoader(jar, jarPath, getClass().getClassLoader(), 4);
    loader.startRecording(Thread.currentThread());
    loader.loadClass(FIXTURE);
    assertThat(loader.stopRecording()).containsExactly(FIXTURE);
    assertThat(recordedArray(loader)).isNull(); // nothing kept for the JVM's lifetime

    Class<?> later = loader.loadClass(LATER); // defined after the stop
    assertThat(later.getClassLoader()).isSameAs(loader);
    assertThat(loader.statistics()[0]).isEqualTo(2);
    assertThat(loader.stopRecording()).isEmpty();
    assertThat(recordedArray(loader)).isNull();

    // The overflow count is dropped too, and a new start does not bring the recording back.
    AgentClassLoader full = new AgentClassLoader(jar, jarPath, getClass().getClassLoader(), 0);
    full.startRecording(Thread.currentThread());
    full.loadClass(FIXTURE);
    assertThat(full.stopRecording()).containsExactly("... 1 more");
    full.startRecording(Thread.currentThread());
    full.loadClass(LATER);
    assertThat(full.stopRecording()).isEmpty();
    assertThat(full.statistics()[0]).isEqualTo(2);
    assertThat(recordedArray(full)).isNull();
  }

  private static Object recordedArray(AgentClassLoader loader) throws ReflectiveOperationException {
    Field field = AgentClassLoader.class.getDeclaredField("recorded");
    field.setAccessible(true);
    return field.get(loader);
  }

  @Test
  @Tag("req:N-02")
  @DisplayName(
      "C-79: a class is read straight into an array of its recorded size; an entry above the"
          + " 16 MiB cap is refused (bounded memory)")
  void classBytesAreBounded() throws Exception {
    File bigJar = new File(dir, "big.jar");
    try (JarOutputStream out = new JarOutputStream(new FileOutputStream(bigJar))) {
      put(
          out,
          "inst/io/argos/agent/core/fixture/LoaderFixture.classdata",
          bytes(LoaderFixture.class));
      put(
          out,
          "inst/io/argos/agent/core/fixture/Big.classdata",
          new byte[AgentClassLoader.MAX_CLASS_BYTES + 1]);
    }
    try (JarFile big = new JarFile(bigJar, false)) {
      AgentClassLoader loader = new AgentClassLoader(big, bigJar, getClass().getClassLoader());
      assertThat(loader.loadClass(FIXTURE).getClassLoader()).isSameAs(loader);
      assertThat(loader.statistics()[1]).isEqualTo(bytes(LoaderFixture.class).length);
      assertThatThrownBy(() -> loader.loadClass("io.argos.agent.core.fixture.Big"))
          .isInstanceOf(ClassNotFoundException.class)
          .hasCauseInstanceOf(IOException.class)
          .hasStackTraceContaining("is larger than " + AgentClassLoader.MAX_CLASS_BYTES);
      assertThat(loader.statistics()[0]).isEqualTo(1);
    }
    assertThat(AgentClassLoader.MAX_CLASS_BYTES).isEqualTo(16 * 1024 * 1024);
  }

  private static void put(JarOutputStream out, String name, byte[] content) throws IOException {
    out.putNextEntry(new JarEntry(name));
    out.write(content);
    out.closeEntry();
  }

  private static byte[] bytes(Class<?> type) throws IOException {
    String resource = "/" + type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getResourceAsStream(resource)) {
      return read(in);
    }
  }

  private static byte[] read(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = in.read(buffer)) >= 0) {
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
}
