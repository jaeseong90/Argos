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
      assertThat(AgentBootstrap.platformClassLoader()).isNotNull();
    }
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
