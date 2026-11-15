/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.TransactionRecord;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServiceDescriptor;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The generated code that agent, server and harness share (ADR-0010 C-104): Java 8 class files (the
 * agent relocates them, C-22) and the gRPC method contract of 11 §3.
 */
class GeneratedCodeTest {

  @Test
  @Tag("req:F-A31")
  @DisplayName("generated messages, gRPC stubs and the version constant are class file major 52")
  void generatedCodeTargetsJava8() throws IOException {
    assertThat(major(AgentHello.class)).isEqualTo(52);
    assertThat(major(TransactionRecord.class)).isEqualTo(52);
    assertThat(major(AgentServiceGrpc.class)).isEqualTo(52);
    assertThat(major(ProtocolVersion.class)).isEqualTo(52);
    assertThat(major(ProtocolLimits.class)).isEqualTo(52);
  }

  @Test
  @Tag("req:DSN-11-01")
  @DisplayName("AgentService: one bidirectional Session stream and five unary batch RPCs (11 §3)")
  void serviceMethods() {
    ServiceDescriptor service = AgentServiceGrpc.getServiceDescriptor();
    assertThat(service.getName()).isEqualTo("argos.v1.AgentService");
    assertThat(service.getMethods()).hasSize(6);
    assertThat(AgentServiceGrpc.getSessionMethod().getType()).isEqualTo(MethodType.BIDI_STREAMING);
    assertThat(AgentServiceGrpc.getSessionMethod().getFullMethodName())
        .isEqualTo("argos.v1.AgentService/Session");
    assertThat(AgentServiceGrpc.getSendTransactionsMethod().getType()).isEqualTo(MethodType.UNARY);
    assertThat(AgentServiceGrpc.getSendProfilesMethod().getType()).isEqualTo(MethodType.UNARY);
    assertThat(AgentServiceGrpc.getSendMetricsMethod().getType()).isEqualTo(MethodType.UNARY);
    assertThat(AgentServiceGrpc.getSendStatsMethod().getType()).isEqualTo(MethodType.UNARY);
    assertThat(AgentServiceGrpc.getSendActiveSnapshotMethod().getType())
        .isEqualTo(MethodType.UNARY);
  }

  private static int major(Class<?> type) throws IOException {
    try (InputStream raw = type.getResourceAsStream(type.getSimpleName() + ".class");
        DataInputStream in = new DataInputStream(raw)) {
      assertThat(in.readInt()).isEqualTo(0xCAFEBABE);
      in.readUnsignedShort(); // minor version
      return in.readUnsignedShort();
    }
  }
}
