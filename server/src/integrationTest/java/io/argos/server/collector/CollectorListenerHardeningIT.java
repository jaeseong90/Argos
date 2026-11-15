/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.grpc.GrpcCollectorServer;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The hardening of the gRPC listener in action (SEC-15, 16 §4.6, ADR-0010 C-130 (3)), against the
 * production listener of a {@link CollectorHarness} (TLS, real PostgreSQL): at most {@value
 * GrpcCollectorServer#MAX_CALLS_PER_CONNECTION} concurrent calls per connection, metadata at most
 * {@value GrpcCollectorServer#MAX_METADATA_BYTES} bytes, and keepalive pings closer than 60 s
 * answered with {@code GOAWAY(ENHANCE_YOUR_CALM, "too_many_pings")}.
 */
@Tag("integration")
@Tag("req:SEC-15")
class CollectorListenerHardeningIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness collector;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector = CollectorHarness.start(env);
  }

  @AfterAll
  static void stop() {
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static String app() {
    return "hard-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static Metadata bearer(String token) {
    Metadata headers = new Metadata();
    headers.put(FakeAgent.AUTHORIZATION, "Bearer " + token);
    return headers;
  }

  private static ManagedChannel ready(ManagedChannel channel) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (channel.getState(true) != ConnectivityState.READY) {
      assertThat(System.nanoTime()).as("channel READY within 10 s").isLessThan(deadline);
      TimeUnit.MILLISECONDS.sleep(20);
    }
    return channel;
  }

  @Test
  @DisplayName(
      "T-SEC-15a SEC-15 C-130 (3): 8 session streams that only sent their headers hold a"
          + " connection's calls; the 9th call does not reach the server until one of them ends")
  void eightCallsPerConnection() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    ManagedChannel channel = ready(collector.channel());
    List<ClientCall<AgentMessage, ServerMessage>> held = new ArrayList<>();
    List<Listener> heldListeners = new ArrayList<>();
    try (FakeAgent template = FakeAgent.builder(channel).token(token).appName(app).build()) {
      for (int i = 0; i < GrpcCollectorServer.MAX_CALLS_PER_CONNECTION; i++) {
        ClientCall<AgentMessage, ServerMessage> call =
            channel.newCall(AgentServiceGrpc.getSessionMethod(), CallOptions.DEFAULT);
        Listener listener = new Listener();
        call.start(listener, bearer(token));
        call.request(1);
        held.add(call);
        heldListeners.add(listener);
      }
      for (Listener l : heldListeners) {
        assertThat(l.ready.await(5, TimeUnit.SECONDS))
            .as("the first 8 streams are open on the server")
            .isTrue();
      }

      ClientCall<AgentMessage, ServerMessage> ninth =
          channel.newCall(AgentServiceGrpc.getSessionMethod(), CallOptions.DEFAULT);
      Listener ninthListener = new Listener();
      ninth.start(ninthListener, bearer(token));
      ninth.request(1);
      AgentHello hello = template.helloTemplate().toBuilder().setInstanceName("ninth").build();
      ninth.sendMessage(
          AgentMessage.newBuilder()
              .setHello(hello.toBuilder().setSentAtMs(System.currentTimeMillis()))
              .build());

      // Control: a Hello on another connection is answered well within the waiting time.
      try (FakeAgent control =
          collector.agent(token).appName(app).instanceName("control").build()) {
        long started = System.nanoTime();
        assertThat(control.connect().accepted()).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_500);
      }
      TimeUnit.MILLISECONDS.sleep(1_500);
      assertThat(ninthListener.messages).as("no answer to the 9th call while 8 are held").isEmpty();
      assertThat(ninth.isReady()).as("the 9th stream is not open").isFalse();
      assertThat(heldListeners).allSatisfy(l -> assertThat(l.closed.getCount()).isEqualTo(1));

      // End one held call: the agent half-closes it without a Hello and the collector completes
      // the stream. (A client-side cancel, RST_STREAM, did not release grpc-java's buffered 9th
      // stream: observed with grpc-java 1.85.1 / Netty 4.2.19, a client library matter.)
      held.get(0).halfClose();
      assertThat(heldListeners.get(0).closed.await(3, TimeUnit.SECONDS))
          .as("the collector ends the half-closed call")
          .isTrue();
      assertThat(heldListeners.get(0).status.getCode()).isEqualTo(Status.Code.OK);
      assertThat(ninthListener.firstMessage.await(3, TimeUnit.SECONDS))
          .as(
              "the 9th call proceeds once a stream is free (9th: ready %s, status %s; held: %s)",
              ninth.isReady(),
              ninthListener.status,
              heldListeners.stream().map(l -> String.valueOf(l.status)).toList())
          .isTrue();
      ServerMessage answer = ninthListener.messages.get(0);
      assertThat(answer.hasHello()).isTrue();
      assertThat(answer.getHello().getAccepted()).isTrue();
      assertThat(heldListeners.subList(1, heldListeners.size()))
          .as("the other held calls are still open (not freed by the 10 s Hello timeout)")
          .allSatisfy(l -> assertThat(l.closed.getCount()).isEqualTo(1));
      ninth.cancel("done", null);
    } finally {
      for (ClientCall<AgentMessage, ServerMessage> c : held) {
        c.cancel("done", null);
      }
      channel.shutdownNow();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-15a SEC-15 16 §4.6: request metadata above 8 KiB is refused; 7,000 bytes still reach"
          + " the collector")
  void metadataCap() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    ManagedChannel channel = ready(collector.channel());
    try (FakeAgent agent = FakeAgent.builder(channel).token(token).appName(app).build()) {
      TransactionBatch batch =
          agent.batches().transactions(List.of(agent.records().transaction().build()));
      Status small = call(channel, token, 7_000, batch);
      assertThat(small.getCode())
          .as("7,000 bytes of metadata reach the session check: %s", small)
          .isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(small.getDescription()).isEqualTo("SESSION_REQUIRED");

      Status big = call(channel, token, 9_000, batch);
      System.out.printf(
          Locale.ROOT,
          "T-SEC-15a metadata above %d bytes: %s %s%n",
          GrpcCollectorServer.MAX_METADATA_BYTES,
          big.getCode(),
          big.getDescription());
      assertThat(big.getCode()).as("9,000 bytes of metadata: %s", big).isNotEqualTo(Status.Code.OK);
      assertThat(big.getCode())
          .as("refused before the collector's checks: %s", big)
          .isNotEqualTo(Status.Code.FAILED_PRECONDITION);
    } finally {
      channel.shutdownNow();
    }
  }

  private static Status call(
      ManagedChannel channel, String token, int pad, TransactionBatch batch) {
    Metadata headers = bearer(token);
    headers.put(Metadata.Key.of("x-argos-pad", Metadata.ASCII_STRING_MARSHALLER), "a".repeat(pad));
    try {
      AgentServiceGrpc.newBlockingStub(
              ClientInterceptors.intercept(
                  channel, MetadataUtils.newAttachHeadersInterceptor(headers)))
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .sendTransactions(batch);
      return Status.OK;
    } catch (StatusRuntimeException e) {
      return e.getStatus();
    }
  }

  @Test
  @Tag("slow")
  @DisplayName(
      "T-SEC-15a SEC-15 16 §4.6: a client pinging every 10 s without calls gets GOAWAY"
          + " ENHANCE_YOUR_CALM too_many_pings (permitted: 60 s, not without calls)")
  void keepaliveEnforced() throws Exception {
    Logger nettyClient = Logger.getLogger("io.grpc.netty.NettyClientHandler");
    List<String> goAways = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            Object[] params = record.getParameters();
            String data = params == null || params.length == 0 ? "" : String.valueOf(params[0]);
            if (String.valueOf(record.getMessage()).contains("ENHANCE_YOUR_CALM")) {
              goAways.add(data);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Level previous = nettyClient.getLevel();
    nettyClient.setLevel(Level.ALL);
    nettyClient.addHandler(capture);
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("localhost", collector.port())
            .sslContext(
                AgentChannels.clientContext(
                    collector.caCertificate(), AgentChannels.Options.defaults()))
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build();
    try {
      ready(channel);
      long started = System.nanoTime();
      long deadline = started + TimeUnit.SECONDS.toNanos(75);
      while (goAways.isEmpty() && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(200);
      }
      System.out.printf(
          Locale.ROOT,
          "T-SEC-15a keepalive 10 s without calls: GOAWAY %s after %d s; channel %s%n",
          goAways,
          TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started),
          channel.getState(false));
      assertThat(goAways).as("GOAWAY ENHANCE_YOUR_CALM debug data").contains("too_many_pings");
    } finally {
      channel.shutdownNow();
      nettyClient.removeHandler(capture);
      nettyClient.setLevel(previous);
    }
  }

  /** Records what the server sends on one call. */
  private static final class Listener extends ClientCall.Listener<ServerMessage> {
    final List<ServerMessage> messages = new CopyOnWriteArrayList<>();
    final CountDownLatch ready = new CountDownLatch(1);
    final CountDownLatch firstMessage = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    volatile Status status;

    @Override
    public void onReady() {
      ready.countDown();
    }

    @Override
    public void onMessage(ServerMessage message) {
      messages.add(message);
      firstMessage.countDown();
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      this.status = status;
      closed.countDown();
    }
  }
}
