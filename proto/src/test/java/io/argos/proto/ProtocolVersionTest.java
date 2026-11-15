/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.InvalidProtocolBufferException;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proto contract test (d): the generated version constant is the PROTOCOL_VERSION file (19 §3.4).
 */
@Tag("req:O-R01")
@Tag("req:DSN-19-10")
class ProtocolVersionTest {

  @Test
  @DisplayName("ProtocolVersion.CURRENT equals proto/PROTOCOL_VERSION (single source, DSN-19-10)")
  void currentEqualsTheVersionFile() throws IOException {
    // The test task runs in the :proto project directory (proto/build.gradle.kts).
    String file = new String(Files.readAllBytes(Paths.get("PROTOCOL_VERSION")), UTF_8).trim();
    assertThat(file).matches("[1-9][0-9]*");
    assertThat(ProtocolVersion.CURRENT).isEqualTo(Integer.parseInt(file));
  }

  @Test
  @DisplayName("AgentHello carries ProtocolVersion.CURRENT through the session oneof")
  void helloCarriesTheVersion() throws InvalidProtocolBufferException {
    AgentMessage message =
        AgentMessage.newBuilder()
            .setHello(AgentHello.newBuilder().setProtocolVersion(ProtocolVersion.CURRENT))
            .build();
    AgentMessage parsed = AgentMessage.parseFrom(message.toByteArray());
    assertThat(parsed.getBodyCase()).isEqualTo(AgentMessage.BodyCase.HELLO);
    assertThat(parsed.getHello().getProtocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
  }
}
