/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The relocated protocol in the shipped jar (ADR-0010 C-104, proto/README.md): Shadow rewrites
 * string constants, and the generated classes keep their schema in a length-prefixed string. The
 * transport never reads the schema at run time, so a damaged one would only show later ({@code
 * toString}, WO-17): this test loads the classes from {@code inst/*.classdata} and builds every
 * file descriptor, then round-trips a message with reflection on the relocated runtime.
 */
@Tag("req:F-A35")
@Tag("req:C-104")
class ShadedProtocolTest {
  private static final String PACKAGE = "io.argos.agent.shaded.io.argos.proto.v1.";

  /** Loads the classes of the agent jar: {@code inst/**.classdata} and the root classes. */
  private static final class JarClasses extends ClassLoader {
    private final ZipFile zip;

    JarClasses(ZipFile zip) {
      super(ClassLoader.getPlatformClassLoader());
      this.zip = zip;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
      String path = name.replace('.', '/');
      ZipEntry entry = zip.getEntry("inst/" + path + ".classdata");
      if (entry == null) {
        entry = zip.getEntry(path + ".class");
      }
      if (entry == null) {
        throw new ClassNotFoundException(name);
      }
      try (InputStream in = zip.getInputStream(entry)) {
        byte[] bytes = in.readAllBytes();
        return defineClass(name, bytes, 0, bytes.length);
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }

  @Test
  @DisplayName(
      "T-BLD-005 the relocated generated code builds its descriptors and round-trips a message: the"
          + " schema strings were not damaged by relocation")
  void descriptorsAndRoundTrip() throws Exception {
    try (ZipFile zip = new ZipFile(TransportRuns.releaseJar().toFile())) {
      JarClasses loader = new JarClasses(zip);
      for (String outer : List.of("CommonProto", "SessionProto", "DataProto", "ServiceProto")) {
        Class<?> type = loader.loadClass(PACKAGE + outer);
        Object file = type.getMethod("getDescriptor").invoke(null);
        Object name = file.getClass().getMethod("getPackage").invoke(file);
        assertThat(name).as(outer).isEqualTo("argos.v1");
      }

      Class<?> hello = loader.loadClass(PACKAGE + "AgentHello");
      Object builder = hello.getMethod("newBuilder").invoke(null);
      builder.getClass().getMethod("setAppName", String.class).invoke(builder, "orders");
      builder.getClass().getMethod("setAgentEpoch", long.class).invoke(builder, 42L);
      Object message = builder.getClass().getMethod("build").invoke(builder);

      Method toByteArray = hello.getMethod("toByteArray");
      byte[] bytes = (byte[]) toByteArray.invoke(message);
      Object parsed = hello.getMethod("parseFrom", byte[].class).invoke(null, (Object) bytes);
      assertThat(hello.getMethod("getAppName").invoke(parsed)).isEqualTo("orders");
      assertThat(hello.getMethod("getAgentEpoch").invoke(parsed)).isEqualTo(42L);
      assertThat(parsed.toString())
          .as("text form: needs the schema")
          .contains("app_name: \"orders\"")
          .contains("agent_epoch: 42");

      Class<?> version = loader.loadClass("io.argos.agent.shaded.io.argos.proto.ProtocolVersion");
      assertThat(version.getField("CURRENT").getInt(null)).isPositive();
    }
  }
}
