/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Message;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A scripted agent for the FakeCollector tests and the contract suite: a real grpc-netty client of
 * argos.v1 that sends exactly what a test says (11 §2 metadata, hellos, heartbeats with chosen
 * t1/prev_t1/prev_t4, batches with chosen kind, epoch and seq). It is not the agent's transport.
 */
public final class ScriptedAgent implements AutoCloseable {
  private final List<ManagedChannel> channels = new ArrayList<>();

  /** A plaintext connection to {@code 127.0.0.1:<port>}. */
  public ManagedChannel plaintext(FakeCollector collector) {
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", collector.port()).usePlaintext().build();
    channels.add(channel);
    return channel;
  }

  /** A TLS connection trusting the collector's current certificate. */
  public ManagedChannel tls(FakeCollector collector) throws Exception {
    return tls(collector, collector.trustPem(), null);
  }

  /** A TLS connection trusting {@code pem}, optionally with another authority. */
  public ManagedChannel tls(FakeCollector collector, Path pem, String authority) throws Exception {
    NettyChannelBuilder builder =
        NettyChannelBuilder.forAddress("127.0.0.1", collector.port())
            .sslContext(GrpcSslContexts.forClient().trustManager(pem.toFile()).build());
    if (authority != null) {
      builder.overrideAuthority(authority);
    }
    ManagedChannel channel = builder.build();
    channels.add(channel);
    return channel;
  }

  @Override
  public void close() {
    for (ManagedChannel channel : channels) {
      try {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Fails when {@code text} contains {@code token}, without printing either: a failure message is
   * test output, and a token never appears there (11 §11, SEC-06). AssertJ's {@code
   * doesNotContain(token)} would print it.
   */
  public static void assertNoToken(String what, String text, String token) {
    if (text != null && text.contains(token)) {
      throw new AssertionError(what + " contains the token (" + ReceivedToken.MASK + ")");
    }
  }

  // --- metadata (11 §2) -------------------------------------------------------------------------

  /** {@code authorization} (when {@code token} is not null) and the session metadata. */
  public static Metadata headers(String token, Long instanceId, String sessionId) {
    Metadata headers = new Metadata();
    if (token != null) {
      headers.put(V1Protocol.AUTHORIZATION, "Bearer " + token);
    }
    if (instanceId != null) {
      headers.put(V1Protocol.INSTANCE_ID, Long.toString(instanceId));
    }
    if (sessionId != null) {
      headers.put(V1Protocol.SESSION_ID, sessionId);
    }
    return headers;
  }

  /** The metadata of a data call in {@code session}. */
  public static Metadata headers(String token, ServerHello session) {
    return headers(token, Integer.toUnsignedLong(session.getInstanceId()), session.getSessionId());
  }

  static Channel with(Channel channel, Metadata headers) {
    return ClientInterceptors.intercept(
        channel, MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  // --- data calls -------------------------------------------------------------------------------

  /** A batch of {@code kind} with {@code items} items and this header. */
  public static Message batch(BatchKind kind, long instanceId, long epoch, long seq, int items) {
    BatchHeader header =
        BatchHeader.newBuilder()
            .setInstanceId((int) instanceId)
            .setAgentEpoch(epoch)
            .setSeq(seq)
            .setSentAtMs(System.currentTimeMillis())
            .build();
    return switch (kind) {
      case TRANSACTIONS -> {
        TransactionBatch.Builder batch = TransactionBatch.newBuilder().setHeader(header);
        for (int i = 0; i < items; i++) {
          batch.addRecords(
              TransactionRecord.newBuilder()
                  .setTxId(seq * 1_000 + i)
                  .setKind(TransactionKind.HTTP)
                  .setServiceId(100)
                  .setHttpStatus(200)
                  .setElapsedUs(1000));
        }
        yield batch.build();
      }
      case PROFILES -> {
        ProfileBatch.Builder batch = ProfileBatch.newBuilder().setHeader(header);
        for (int i = 0; i < items; i++) {
          batch.addProfiles(TransactionProfile.newBuilder().setTxId(seq * 1_000 + i));
        }
        yield batch.build();
      }
      case METRICS -> {
        MetricBatch.Builder batch = MetricBatch.newBuilder().setHeader(header);
        for (int i = 0; i < items; i++) {
          batch.addSamples(MetricSample.newBuilder().setSeriesIndex(i).setValue(i));
        }
        yield batch.build();
      }
      case STATS -> StatBatch.newBuilder().setHeader(header).build();
      case ACTIVE_SNAPSHOT ->
          ActiveTransactionSnapshot.newBuilder().setHeader(header).setTotalActive(items).build();
    };
  }

  /** Sends {@code body} as the data RPC of {@code kind} with {@code headers}; throws on errors. */
  public static Ack send(Channel channel, Metadata headers, BatchKind kind, Message body) {
    return send(channel, headers, kind, body, 10_000);
  }

  /** {@link #send(Channel, Metadata, BatchKind, Message)} with this deadline. */
  public static Ack send(
      Channel channel, Metadata headers, BatchKind kind, Message body, long deadlineMs) {
    AgentServiceGrpc.AgentServiceBlockingStub stub =
        AgentServiceGrpc.newBlockingStub(with(channel, headers))
            .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    return switch (kind) {
      case TRANSACTIONS -> stub.sendTransactions((TransactionBatch) body);
      case PROFILES -> stub.sendProfiles((ProfileBatch) body);
      case METRICS -> stub.sendMetrics((MetricBatch) body);
      case STATS -> stub.sendStats((StatBatch) body);
      case ACTIVE_SNAPSHOT -> stub.sendActiveSnapshot((ActiveTransactionSnapshot) body);
    };
  }

  /** One transaction record of {@code seq} in {@code session}. */
  public static Ack send(Channel channel, String token, ServerHello session, long epoch, long seq) {
    return send(
        channel,
        headers(token, session),
        BatchKind.TRANSACTIONS,
        batch(BatchKind.TRANSACTIONS, session.getInstanceId(), epoch, seq, 1));
  }

  // --- session stream ---------------------------------------------------------------------------

  /** How a session stream ended. */
  public record Ended(Status status, Metadata trailers) {}

  /** A client session stream: received server messages and the stream's end. */
  public static final class Session {
    public final BlockingQueue<ServerMessage> received = new LinkedBlockingQueue<>();
    public final CompletableFuture<Ended> ended = new CompletableFuture<>();
    private final StreamObserver<AgentMessage> out;

    public Session(Channel channel, Metadata headers) {
      out =
          AgentServiceGrpc.newStub(with(channel, headers))
              .session(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                      received.add(message);
                    }

                    @Override
                    public void onError(Throwable error) {
                      Metadata trailers = Status.trailersFromThrowable(error);
                      ended.complete(
                          new Ended(
                              Status.fromThrowable(error),
                              trailers == null ? new Metadata() : trailers));
                    }

                    @Override
                    public void onCompleted() {
                      ended.complete(new Ended(Status.OK, new Metadata()));
                    }
                  });
    }

    /** A session with only the token (the {@code Session} RPC needs no session metadata). */
    public Session(Channel channel, String token) {
      this(channel, headers(token, null, null));
    }

    /** The next server message (10 s). */
    public ServerMessage next() throws InterruptedException {
      ServerMessage message = received.poll(10, TimeUnit.SECONDS);
      assertThat(message).as("server message within 10 s").isNotNull();
      return message;
    }

    /** Sends any message. */
    public void send(AgentMessage message) {
      out.onNext(message);
    }

    /** True while gRPC accepts more outbound messages without buffering them (11 §6, T-AG-071). */
    public boolean isReady() {
      return ((ClientCallStreamObserver<AgentMessage>) out).isReady();
    }

    /** {@code AgentHello} of app {@code shop}, env {@code test}, epoch 3. */
    public void hello(String env) {
      hello("shop", env, "host:1", 3, ProtocolVersion.CURRENT, System.currentTimeMillis());
    }

    /** {@code AgentHello} with these fields. */
    public void hello(
        String app,
        String env,
        String instanceName,
        long epoch,
        int protocolVersion,
        long sentAtMs) {
      hello(app, env, instanceName, epoch, protocolVersion, sentAtMs, false);
    }

    /**
     * {@code AgentHello} with these fields; {@code checkOnly} sets {@code check_only} (ADR-0010
     * C-240 (1), the {@code --check} Hello).
     */
    public void hello(
        String app,
        String env,
        String instanceName,
        long epoch,
        int protocolVersion,
        long sentAtMs,
        boolean checkOnly) {
      AgentHello.Builder hello =
          AgentHello.newBuilder()
              .setAppName(app)
              .setEnv(env)
              .setInstanceName(instanceName)
              .setAppVersion("1.2.3")
              .setAgentVersion("0.0.0-SNAPSHOT")
              .setProtocolVersion(protocolVersion)
              .setAgentEpoch(epoch)
              .setSentAtMs(sentAtMs);
      if (checkOnly) {
        hello.setCheckOnly(true);
      }
      send(AgentMessage.newBuilder().setHello(hello).build());
    }

    /** Sends a hello and returns the accepted {@code ServerHello}. */
    public ServerHello accepted(String env) throws InterruptedException {
      hello(env);
      ServerHello hello = next().getHello();
      assertThat(hello.getAccepted()).as("hello accepted: %s", hello).isTrue();
      return hello;
    }

    /** {@code Heartbeat{t1, prev_t1, prev_t4}}. */
    public void heartbeat(long t1, long prevT1, long prevT4) {
      send(
          AgentMessage.newBuilder()
              .setHeartbeat(
                  Heartbeat.newBuilder().setT1Ms(t1).setPrevT1Ms(prevT1).setPrevT4Ms(prevT4))
              .build());
    }

    /** Half-closes the stream (the agent ends its session). */
    public void complete() {
      out.onCompleted();
    }

    /** The end of the stream (10 s). */
    public Ended awaitEnd() throws Exception {
      return ended.get(10, TimeUnit.SECONDS);
    }
  }
}
