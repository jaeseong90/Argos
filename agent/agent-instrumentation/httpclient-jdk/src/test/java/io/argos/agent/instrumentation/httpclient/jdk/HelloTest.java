/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HelloTest {

  @Test
  @DisplayName(
      "hello: instrumentation httpclient-jdk tests run on Java 8 and compile to class file major 52 (DSN-19-02)")
  void runsOnJava8AndTargetsJava8() throws IOException {
    assertThat(System.getProperty("java.specification.version")).isEqualTo("1.8");
    assertThat(classFileMajorVersion(HelloTest.class)).isEqualTo(52);
  }

  private static int classFileMajorVersion(Class<?> type) throws IOException {
    try (DataInputStream in =
        new DataInputStream(type.getResourceAsStream(type.getSimpleName() + ".class"))) {
      assertThat(in.readInt()).isEqualTo(0xCAFEBABE);
      in.readUnsignedShort(); // minor version
      return in.readUnsignedShort();
    }
  }
}
