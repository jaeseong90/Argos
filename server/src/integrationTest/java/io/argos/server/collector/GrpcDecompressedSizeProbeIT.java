/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The measurement asked by 12 open item 1, 11 open item 1 and 16 §15 #5 (WO-21 확인 작업): does plain
 * grpc-java 1.85.1 apply {@code maxInboundMessageSize} to the decompressed message? A server with
 * the default decompressor registry and the generated stub base class receives the 18 §8 gzip bomb
 * (about 1 MB that inflates to 1 GiB): grpc-java stops reading at the limit (the service method is
 * never called, memory stays flat), but the failure happens while the request is parsed on the
 * application thread and the client sees {@code UNKNOWN "Application error processing RPC"}, not
 * {@code RESOURCE_EXHAUSTED}. Hence the collector's bounded gzip decompressor and its own request
 * marshaller ({@code CollectorLimitsIT} proves {@code RESOURCE_EXHAUSTED message_size} there).
 */
@Tag("integration")
@Tag("req:SEC-11")
class GrpcDecompressedSizeProbeIT {

  @Test
  @DisplayName(
      "12 open item 1: plain grpc-java bounds the inflated size at maxInboundMessageSize but answers UNKNOWN")
  void plainGrpcJava() throws Exception {
    AtomicInteger invoked = new AtomicInteger();
    AgentServiceGrpc.AgentServiceImplBase service =
        new AgentServiceGrpc.AgentServiceImplBase() {
          @Override
          public void sendTransactions(TransactionBatch request, StreamObserver<Ack> out) {
            invoked.incrementAndGet();
            out.onNext(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
            out.onCompleted();
          }
        };
    Server server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .maxInboundMessageSize(4 * 1024 * 1024)
            .addService(service)
            .build()
            .start();
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.plaintext(
                    "127.0.0.1",
                    server.getPort(),
                    AgentChannels.Options.defaults().withPreCompressedGzip()))
            .ownsChannel(true)
            .build()) {
      long start = System.nanoTime();
      CallResult r =
          agent.sendRaw(
              BatchKind.TRANSACTIONS,
              Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES),
              FakeAgent.CallSpec.defaults().withCompression(FakeAgent.Compression.GZIP));
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;
      System.out.println(
          "12 open item 1 (grpc-java 1.85.1, reference): gzip bomb -> "
              + r.code()
              + " '"
              + r.status().getDescription()
              + "' in "
              + elapsedMs
              + " ms; service invoked "
              + invoked.get()
              + " times");
      assertThat(r.code()).isEqualTo(Status.Code.UNKNOWN);
      assertThat(invoked).as("the service method never sees the message").hasValue(0);
    } finally {
      server.shutdownNow().awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}
