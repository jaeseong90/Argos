/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Message;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.Throttle;
import io.argos.proto.v1.TransactionBatch;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The transport on the real stack the agent ships (grpc-netty on Netty 4.1, Java 8, unrelocated in
 * this test), against a raw gRPC server on the loopback whose behaviour a test controls: a server
 * that stops reading the session stream fills gRPC's flow-control window, so {@code isReady} turns
 * false (T-AG-071 "isReady 백프레셔"); the agent then keeps its messages in the bounded pending list
 * instead of gRPC's unbounded buffer, and they flow when the server reads again.
 */
@Tag("req:DSN-02-11")
class SessionFlowControlIntegrationTest {
  private Server server;
  private GrpcTransport transport;

  @AfterEach
  void stop() throws Exception {
    if (transport != null) {
      transport.shutdownNow();
      transport.awaitTermination(5_000);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  /** The session side of the server: answers the hello, reads further messages when told to. */
  private static final class SessionHandler
      implements ServerCallHandler<AgentMessage, ServerMessage> {
    final List<AgentMessage> received = new CopyOnWriteArrayList<AgentMessage>();
    final CountDownLatch helloDone = new CountDownLatch(1);
    final AtomicReference<ServerCall<AgentMessage, ServerMessage>> call =
        new AtomicReference<ServerCall<AgentMessage, ServerMessage>>();

    @Override
    public ServerCall.Listener<AgentMessage> startCall(
        final ServerCall<AgentMessage, ServerMessage> serverCall, Metadata headers) {
      call.set(serverCall);
      serverCall.sendHeaders(new Metadata());
      serverCall.request(1); // the hello only: nothing more until read() (flow control)
      return new ServerCall.Listener<AgentMessage>() {
        @Override
        public void onMessage(AgentMessage message) {
          received.add(message);
          if (message.getBodyCase() == AgentMessage.BodyCase.HELLO) {
            serverCall.sendMessage(
                ServerMessage.newBuilder()
                    .setHello(
                        ServerHello.newBuilder()
                            .setAccepted(true)
                            .setInstanceId(7)
                            .setSessionId("session-1")
                            .setHeartbeatIntervalS(3600))
                    .build());
            helloDone.countDown();
          }
        }
      };
    }

    void read(int messages) {
      call.get().request(messages);
    }
  }

  private void startServer(SessionHandler session) throws Exception {
    ServerServiceDefinition service =
        ServerServiceDefinition.builder(AgentServiceGrpc.SERVICE_NAME)
            .addMethod(AgentServiceGrpc.getSessionMethod(), session)
            .addMethod(
                AgentServiceGrpc.getSendTransactionsMethod(),
                ServerCalls.asyncUnaryCall(
                    new ServerCalls.UnaryMethod<TransactionBatch, Ack>() {
                      @Override
                      public void invoke(TransactionBatch request, StreamObserver<Ack> observer) {
                        observer.onNext(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
                        observer.onCompleted();
                      }
                    }))
            .build();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .directExecutor()
            .addService(service)
            .flowControlWindow(64 * 1024)
            .build()
            .start();
  }

  private TransportSettings settings() {
    return new TransportSettings(
        "127.0.0.1:" + server.getPort(),
        false,
        null,
        false,
        TokenHolder.NONE,
        5_000,
        5_000,
        500,
        100,
        4,
        true);
  }

  private SessionClient session(
      final GrpcTransport channel, TransportCounters counters, final CountDownLatch established) {
    return new SessionClient(
        channel.loop(),
        channel,
        new SessionClient.HelloSource() {
          @Override
          public AgentHello hello() {
            return AgentHello.newBuilder().setAppName("flow").build();
          }
        },
        new SessionClient.HeartbeatSource() {
          @Override
          public Heartbeat.Builder fill(Heartbeat.Builder heartbeat) {
            return heartbeat;
          }
        },
        new SessionClient.Listener() {
          @Override
          public void established(ServerHello hello) {
            established.countDown();
          }

          @Override
          public void lost() {}

          @Override
          public void throttle(Throttle throttle) {}

          @Override
          public void stopped() {}
        },
        new RejectingDispatcher(AgentConfig.of(Collections.<String, String>emptyMap()), ""),
        new DictionaryCache(new Dictionary()),
        counters,
        new ClockSync(ClockSync.SYSTEM),
        new Backoff(),
        true,
        false);
  }

  private static boolean awaitTrue(java.util.function.BooleanSupplier condition, long millis)
      throws InterruptedException {
    long end = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < end) {
      if (condition.getAsBoolean()) {
        return true;
      }
      Thread.sleep(20);
    }
    return condition.getAsBoolean();
  }

  /** The pending list as read on the loop (its owner thread). */
  private int pending(SessionClient client, GrpcTransport channel) throws InterruptedException {
    final AtomicInteger size = new AtomicInteger(-1);
    final CountDownLatch read = new CountDownLatch(1);
    channel
        .loop()
        .execute(
            new Runnable() {
              @Override
              public void run() {
                size.set(client.pendingMessages());
                read.countDown();
              }
            });
    assertThat(read.await(5, TimeUnit.SECONDS)).isTrue();
    return size.get();
  }

  @Test
  @DisplayName(
      "T-AG-071b a server that stops reading: isReady false, messages wait in the bounded list,"
          + " none is lost below the bound, and everything flows when the server reads again")
  void isReadyBackPressure() throws Exception {
    SessionHandler handler = new SessionHandler();
    startServer(handler);
    GrpcTransport.SessionIds ids =
        new GrpcTransport.SessionIds() {
          @Override
          public long instanceId() {
            return 7;
          }

          @Override
          public String sessionId() {
            return "session-1";
          }
        };
    transport = GrpcTransport.create(settings(), "test", ids);
    TransportCounters counters = new TransportCounters();
    CountDownLatch established = new CountDownLatch(1);
    SessionClient client = session(transport, counters, established);
    client.start();
    assertThat(established.await(20, TimeUnit.SECONDS)).as("session established").isTrue();

    // 64 KiB of flow-control window and gRPC's 32 KiB ready threshold: 300 x 8 KiB does not fit.
    char[] chars = new char[8 * 1024];
    Arrays.fill(chars, 'x');
    String value = new String(chars);
    int messages = 300;
    for (int i = 0; i < messages; i++) {
      client.update(
          InstanceUpdate.newBuilder().setInstanceName(value).setConfigHash("" + i).build());
    }

    final SessionClient view = client;
    assertThat(awaitTrue(() -> pendingOf(view) > 0, 10_000))
        .as("the stream stopped being ready: messages wait in the session's list")
        .isTrue();
    assertThat(pending(client, transport)).isLessThanOrEqualTo(SessionClient.MAX_PENDING_MESSAGES);
    assertThat(counters.sessionMessagesDropped()).as("below the bound nothing is lost").isZero();
    int received = handler.received.size();
    assertThat(received).as("the hello only").isEqualTo(1);

    handler.read(messages);
    assertThat(awaitTrue(() -> handler.received.size() == messages + 1, 20_000))
        .as("everything arrives once the server reads; got %d", handler.received.size())
        .isTrue();
    for (int i = 0; i < messages; i++) {
      assertThat(handler.received.get(i + 1).getInstanceUpdate().getConfigHash())
          .as("order kept")
          .isEqualTo("" + i);
    }
    assertThat(pending(client, transport)).isZero();
    assertThat(counters.sessionMessagesDropped()).isZero();
  }

  private int pendingOf(SessionClient client) {
    try {
      return pending(client, transport);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return -1;
    }
  }

  @Test
  @DisplayName("T-AG-071b unary batches go through GrpcTransport with the session metadata")
  void unaryMetadata() throws Exception {
    final AtomicReference<Metadata> headers = new AtomicReference<Metadata>();
    final CountDownLatch answered = new CountDownLatch(1);
    final AtomicReference<Ack> ack = new AtomicReference<Ack>();
    ServerServiceDefinition.Builder service =
        ServerServiceDefinition.builder(AgentServiceGrpc.SERVICE_NAME);
    service.addMethod(AgentServiceGrpc.getSessionMethod(), new SessionHandler());
    service.addMethod(
        AgentServiceGrpc.getSendTransactionsMethod(),
        new ServerCallHandler<TransactionBatch, Ack>() {
          @Override
          public ServerCall.Listener<TransactionBatch> startCall(
              final ServerCall<TransactionBatch, Ack> call, Metadata metadata) {
            headers.set(metadata);
            call.request(1);
            return new ServerCall.Listener<TransactionBatch>() {
              @Override
              public void onMessage(TransactionBatch message) {
                call.sendHeaders(new Metadata());
                call.sendMessage(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
                call.close(Status.OK, new Metadata());
              }
            };
          }
        });
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .directExecutor()
            .addService(service.build())
            .build()
            .start();
    transport =
        GrpcTransport.create(
            new TransportSettings(
                "127.0.0.1:" + server.getPort(),
                false,
                null,
                false,
                TokenHolder.of("argos_" + repeat('t', 43), TokenHolder.Source.FILE),
                5_000,
                5_000,
                500,
                100,
                4,
                true),
            "test",
            new GrpcTransport.SessionIds() {
              @Override
              public long instanceId() {
                return 4_000_000_000L;
              }

              @Override
              public String sessionId() {
                return "session-9";
              }
            });
    Message batch = TransactionBatch.getDefaultInstance();
    transport.send(
        BatchKind.TRANSACTIONS,
        batch,
        false,
        5_000,
        new BatchTransport.Callback() {
          @Override
          public void acknowledged(Ack value) {
            ack.set(value);
            answered.countDown();
          }

          @Override
          public void failed(Status status, Metadata trailers) {
            answered.countDown();
          }
        });
    assertThat(answered.await(20, TimeUnit.SECONDS)).isTrue();
    assertThat(ack.get()).isNotNull();
    assertThat(headers.get().get(GrpcTransport.INSTANCE_ID)).isEqualTo("4000000000");
    assertThat(headers.get().get(GrpcTransport.SESSION_ID)).isEqualTo("session-9");
    assertThat(headers.get().get(GrpcTransport.AUTHORIZATION))
        .isEqualTo("Bearer argos_" + repeat('t', 43));
    assertThat(
            Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("grpc-"))
                .filter(thread -> thread.getName().contains("default"))
                .count())
        .as("no gRPC shared executor thread (ADR-0005 decision 1) on the client side")
        .isZero();
  }

  @Test
  @Tag("req:DSN-11-02")
  @DisplayName(
      "11 §3 SESSION_REQUIRED on the real stack: one new session, then the same batch with the same"
          + " seq and the new session's id; the transaction is delivered once")
  void sessionRequired() throws Exception {
    final List<String> hellos = new CopyOnWriteArrayList<String>();
    final List<String> calls = new CopyOnWriteArrayList<String>();
    ServerCallHandler<AgentMessage, ServerMessage> sessions =
        new ServerCallHandler<AgentMessage, ServerMessage>() {
          @Override
          public ServerCall.Listener<AgentMessage> startCall(
              final ServerCall<AgentMessage, ServerMessage> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.request(1);
            return new ServerCall.Listener<AgentMessage>() {
              @Override
              public void onMessage(AgentMessage message) {
                call.request(1);
                if (message.getBodyCase() == AgentMessage.BodyCase.HELLO) {
                  hellos.add("hello");
                  call.sendMessage(
                      ServerMessage.newBuilder()
                          .setHello(
                              ServerHello.newBuilder()
                                  .setAccepted(true)
                                  .setInstanceId(7)
                                  .setSessionId("session-" + hellos.size())
                                  .setHeartbeatIntervalS(3600))
                          .build());
                }
              }
            };
          }
        };
    ServerCallHandler<TransactionBatch, Ack> batches =
        new ServerCallHandler<TransactionBatch, Ack>() {
          @Override
          public ServerCall.Listener<TransactionBatch> startCall(
              final ServerCall<TransactionBatch, Ack> call, final Metadata metadata) {
            call.request(1);
            return new ServerCall.Listener<TransactionBatch>() {
              @Override
              public void onMessage(TransactionBatch message) {
                calls.add(
                    metadata.get(GrpcTransport.SESSION_ID)
                        + " seq "
                        + message.getHeader().getSeq());
                if (calls.size() == 1) {
                  call.close(
                      Status.FAILED_PRECONDITION.withDescription(Sender.SESSION_REQUIRED),
                      new Metadata());
                  return;
                }
                call.sendHeaders(new Metadata());
                call.sendMessage(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
                call.close(Status.OK, new Metadata());
              }
            };
          }
        };
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .directExecutor()
            .addService(
                ServerServiceDefinition.builder(AgentServiceGrpc.SERVICE_NAME)
                    .addMethod(AgentServiceGrpc.getSessionMethod(), sessions)
                    .addMethod(AgentServiceGrpc.getSendTransactionsMethod(), batches)
                    .build())
            .build()
            .start();

    GrpcExporter.IdsHolder ids = new GrpcExporter.IdsHolder();
    transport = GrpcTransport.create(settings(), "test", ids);
    TransportCounters counters = new TransportCounters();
    CountDownLatch established = new CountDownLatch(1);
    final SessionClient client = session(transport, counters, established);
    ids.target =
        new GrpcTransport.SessionIds() {
          @Override
          public long instanceId() {
            return client.instanceId();
          }

          @Override
          public String sessionId() {
            return client.sessionId();
          }
        };
    client.start();
    assertThat(established.await(20, TimeUnit.SECONDS)).isTrue();

    AgentCounters agentCounters = new AgentCounters();
    TraceRuntime runtime =
        TraceRuntime.create(AgentConfig.of(Collections.<String, String>emptyMap()), agentCounters);
    TraceContext ctx = runtime.tracerImpl().begin(Kind.HTTP, "GET /a", 0);
    runtime.tracerImpl().end(ctx, 200, null, null, null, 1);
    DictionaryCache cache = new DictionaryCache(runtime.dictionary());
    final Sender sender =
        new Sender(
            runtime.queues(),
            new BatchAssembler(runtime.dictionary(), cache),
            cache,
            client,
            transport,
            agentCounters,
            counters,
            settings(),
            42L,
            Sender.SYSTEM_CLOCK,
            new Runnable() {
              @Override
              public void run() {}
            });
    final AtomicBoolean running = new AtomicBoolean(true);
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                sender.run(running::get);
              }
            });
    thread.start();
    try {
      assertThat(awaitTrue(() -> sender.deliveredTransactions() == 1, 20_000))
          .as("delivered; calls %s, hellos %s", calls, hellos)
          .isTrue();
    } finally {
      running.set(false);
      sender.wake();
      thread.join(5_000);
    }
    assertThat(calls).containsExactly("session-1 seq 1", "session-2 seq 1");
    assertThat(hellos).as("exactly one new session").hasSize(2);
    assertThat(agentCounters.dropped(Queues.TRANSACTION)).isZero();
    assertThat(counters.retries(BatchKind.TRANSACTIONS)).isEqualTo(1);
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }
}
