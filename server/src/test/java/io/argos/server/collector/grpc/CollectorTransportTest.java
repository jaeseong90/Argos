/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.collector.CollectorMetrics;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transport settings of the gRPC listener: the 16 §3.2 suite list, TLS 1.2+ only, a clear
 * refusal when the certificate files are missing, and the count of grpc-java's own message-size
 * refusals.
 */
@Tag("req:SEC-07")
class CollectorTransportTest {

  @Test
  @DisplayName(
      "16 §3.2 exactly the AEAD suites: TLS 1.3 three, TLS 1.2 ECDHE with GCM or CHACHA20; no CBC, no RSA key exchange")
  void suites() {
    assertThat(CollectorTls.CIPHER_SUITES).hasSize(9);
    assertThat(CollectorTls.CIPHER_SUITES)
        .allSatisfy(s -> assertThat(s.contains("GCM") || s.contains("CHACHA20")).as(s).isTrue())
        .noneMatch(s -> s.contains("CBC") || s.startsWith("TLS_RSA_"));
    assertThat(CollectorTls.PROTOCOLS).containsExactly("TLSv1.3", "TLSv1.2");
    assertThat(CollectorTls.usableCipherSuites())
        .isNotEmpty()
        .isSubsetOf(CollectorTls.CIPHER_SUITES);
  }

  @Test
  @DisplayName(
      "16 §3.1 missing certificate or key files refuse the listener with the key and the path")
  void missingFiles(@TempDir Path dir) {
    assertThatThrownBy(
            () -> CollectorTls.serverContext(dir.resolve("server.crt"), dir.resolve("server.key")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("argos.collector.tls.cert-file")
        .hasMessageContaining("server.crt");
  }

  @Test
  @DisplayName(
      "12 §3.3 class 1: grpc-java's own 'message exceeds maximum size' refusal is counted as message_size")
  void transportRefusalCounted() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    TransportRejectionCounter counter = new TransportRejectionCounter(new CollectorMetrics(meters));
    ServerStreamTracer tracer =
        counter.newServerStreamTracer("argos.v1.AgentService/SendTransactions", new Metadata());
    tracer.streamClosed(
        Status.RESOURCE_EXHAUSTED.withDescription(
            "gRPC message exceeds maximum size 4194304: 4194305"));
    tracer.streamClosed(
        Status.RESOURCE_EXHAUSTED.withDescription("Message larger than 4194304 bytes"));
    tracer.streamClosed(Status.OK);
    assertThat(
            meters.get("argos.collector.rejected").tag("reason", "message_size").counter().count())
        .isEqualTo(1);
  }
}
