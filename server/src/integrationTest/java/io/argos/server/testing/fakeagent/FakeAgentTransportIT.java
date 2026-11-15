/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionBatch;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The fake agent's transports over real Netty (no database, no Docker): TLS with a CA generated at
 * run time by {@link TestCertificates} (EC and RSA, the 16 §3.2 suites, JDK provider on both
 * sides), and the pre-compressed gzip path of the 18 §8 bomb against a plain gRPC server whose
 * inbound limit is the protocol's 4 MiB. This checks the fixture, not the collector: the
 * collector's own tests (WO-21 lane {@code collector}) assert T-SEC-07 and T-SEC-11 on the real
 * server.
 */
@Tag("req:SEC-07")
@Tag("req:SEC-11")
class FakeAgentTransportIT {

  /** 16 §3.2. */
  private static final List<String> SUITES =
      List.of(
          "TLS_AES_256_GCM_SHA384",
          "TLS_AES_128_GCM_SHA256",
          "TLS_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");

  @ParameterizedTest(name = "TLS with a run-time {0} CA: Hello accepted, gzip batch acked")
  @EnumSource(TestCertificates.KeyAlgorithm.class)
  void tls(TestCertificates.KeyAlgorithm algorithm, @TempDir Path dir) throws Exception {
    TestCertificates.TestPki pki =
        TestCertificates.generate(dir, algorithm, List.of("localhost"), List.of("127.0.0.1"));
    Server server = tlsServer(pki).build().start();
    ManagedChannel channel = AgentChannels.tls("localhost", server.getPort(), pki.caCertificate());
    try (FakeAgent agent =
        FakeAgent.builder(channel)
            .token(AgentTokens.generate())
            .ownsChannel(true)
            .compression(FakeAgent.Compression.GZIP)
            .build()) {
      assertThat(agent.connect().accepted()).isTrue();
      TransactionBatch batch =
          agent.batches().transactions(List.of(agent.records().transaction().build()));
      CallResult result = agent.sendTransactions(batch);
      assertThat(result.ackStatus()).as("%s", result).isEqualTo(AckStatus.ACK_OK);
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  @DisplayName("TLS: a client restricted to CBC suites finds no common suite with a 16 §3.2 server")
  void cbcOnlyClientFails(@TempDir Path dir) throws Exception {
    TestCertificates.TestPki pki = TestCertificates.generate(dir);
    Server server = tlsServer(pki).build().start();
    ManagedChannel channel =
        AgentChannels.tls(
            "localhost",
            server.getPort(),
            pki.caCertificate(),
            AgentChannels.Options.defaults()
                .withProtocols("TLSv1.2")
                .withCipherSuites(
                    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                    "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384"));
    try (FakeAgent agent =
        FakeAgent.builder(channel).token(AgentTokens.generate()).ownsChannel(true).build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).isFalse();
      assertThat(hello.code()).isEqualTo(Status.Code.UNAVAILABLE);
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "gzip bomb: a 1 GiB batch travels as about 1 MiB of gzip and a 4 MiB inbound limit stops it"
          + " before the service")
  void gzipBombPassesThroughTheFixture() throws Exception {
    AckingService service = new AckingService();
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .maxInboundMessageSize(ProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES)
            .addService(service)
            .build()
            .start();
    ManagedChannel channel =
        AgentChannels.plaintext(
            "127.0.0.1",
            server.getPort(),
            AgentChannels.Options.defaults().withPreCompressedGzip());
    try (FakeAgent agent =
        FakeAgent.builder(channel).token(AgentTokens.generate()).ownsChannel(true).build()) {
      byte[] bomb = Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES);
      assertThat(bomb.length).isBetween(512 * 1024, 2 * 1024 * 1024);

      CallResult result =
          agent.sendRaw(
              BatchKind.TRANSACTIONS,
              bomb,
              FakeAgent.CallSpec.defaults().withCompression(FakeAgent.Compression.GZIP));

      // A plain grpc-java 1.85.1 server inflates lazily while parsing the request and stops at
      // 4 MiB ("Decompressed gRPC message exceeds maximum size"), but the failure happens in the
      // application's parse step, so the client sees UNKNOWN ("Application error processing RPC"),
      // not RESOURCE_EXHAUSTED: the collector maps it itself (12 §3.3). Here only the fixture is
      // checked: the bomb arrives as gzip, is refused, and never reaches the service.
      assertThat(result.ok()).as("%s", result).isFalse();
      assertThat(result.code())
          .as("%s", result)
          .isIn(Status.Code.RESOURCE_EXHAUSTED, Status.Code.UNKNOWN);
      assertThat(service.transactionCalls.get()).isZero();
    } finally {
      server.shutdownNow();
    }
  }

  private static NettyServerBuilder tlsServer(TestCertificates.TestPki pki) throws Exception {
    return NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        .sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(
                        pki.serverCertificate().toFile(), pki.serverKey().toFile()),
                    SslProvider.JDK)
                .protocols("TLSv1.3", "TLSv1.2")
                .ciphers(SUITES)
                .build())
        .addService(new AckingService());
  }

  /** Accepts every Hello and acks every transaction batch. */
  private static final class AckingService extends AgentServiceGrpc.AgentServiceImplBase {
    final java.util.concurrent.atomic.AtomicInteger transactionCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public StreamObserver<AgentMessage> session(StreamObserver<ServerMessage> out) {
      return new StreamObserver<>() {
        @Override
        public void onNext(AgentMessage m) {
          if (m.hasHello()) {
            out.onNext(
                ServerMessage.newBuilder()
                    .setHello(
                        ServerHello.newBuilder()
                            .setAccepted(true)
                            .setInstanceId(1)
                            .setSessionId(UUID.randomUUID().toString()))
                    .build());
          }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
          out.onCompleted();
        }
      };
    }

    @Override
    public void sendTransactions(TransactionBatch request, StreamObserver<Ack> out) {
      transactionCalls.incrementAndGet();
      out.onNext(
          Ack.newBuilder()
              .setStatus(AckStatus.ACK_OK)
              .setLastSeq(request.getHeader().getSeq())
              .build());
      out.onCompleted();
    }
  }
}
