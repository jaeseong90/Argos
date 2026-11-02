/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.AckStatus;
import io.argos.agent.transportpoc.proto.AgentHello;
import io.argos.agent.transportpoc.proto.AgentMessage;
import io.argos.agent.transportpoc.proto.AgentServiceGrpc;
import io.argos.agent.transportpoc.proto.BatchHeader;
import io.argos.agent.transportpoc.proto.CommandResult;
import io.argos.agent.transportpoc.proto.CommandStatus;
import io.argos.agent.transportpoc.proto.ConfigApplyResult;
import io.argos.agent.transportpoc.proto.DictionaryEntries;
import io.argos.agent.transportpoc.proto.DictionaryEntry;
import io.argos.agent.transportpoc.proto.DictionaryKind;
import io.argos.agent.transportpoc.proto.Heartbeat;
import io.argos.agent.transportpoc.proto.RejectCode;
import io.argos.agent.transportpoc.proto.RejectReason;
import io.argos.agent.transportpoc.proto.RejectedKey;
import io.argos.agent.transportpoc.proto.ServerMessage;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.proto.TransactionKind;
import io.argos.agent.transportpoc.proto.TransactionRecord;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The FakeCollector (18 §5.3) against a real gRPC client (grpc-netty over loopback, the PoC
 * protocol's stubs): recording, every FaultInjector behaviour, the session stream with ConfigPush
 * and Command round trips, TLS modes.
 */
@Tag("req:DSN-18-04")
class FakeCollectorTest {
  private final List<ManagedChannel> channels = new java.util.ArrayList<>();
  private final List<FakeCollector> collectors = new java.util.ArrayList<>();

  @AfterEach
  void close() throws InterruptedException {
    for (ManagedChannel channel : channels) {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
    for (FakeCollector collector : collectors) {
      collector.close();
    }
  }

  FakeCollector collector(FakeCollector collector) {
    collectors.add(collector);
    return collector;
  }

  ManagedChannel plaintext(FakeCollector collector) {
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", collector.port()).usePlaintext().build();
    channels.add(channel);
    return channel;
  }

  /** A TLS client of {@code 127.0.0.1:<port>} that trusts the collector's current certificate. */
  ManagedChannel tls(FakeCollector collector) throws Exception {
    return tls(collector, collector.trustPem(), null);
  }

  /**
   * A TLS client of {@code 127.0.0.1:<port>} trusting {@code pem}, optionally with an authority.
   */
  ManagedChannel tls(FakeCollector collector, java.nio.file.Path pem, String authority)
      throws Exception {
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

  /** The exception and its causes, outermost first. */
  static List<Throwable> causes(Throwable error) {
    List<Throwable> chain = new java.util.ArrayList<>();
    for (Throwable t = error; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }

  static TransactionBatch batch(long seq) {
    return TransactionBatch.newBuilder()
        .setHeader(BatchHeader.newBuilder().setInstanceId(7).setAgentEpoch(3).setSeq(seq))
        .addRecords(
            TransactionRecord.newBuilder()
                .setTxId(seq)
                .setKind(TransactionKind.HTTP)
                .setServiceId(100)
                .setHttpStatus(200)
                .setElapsedUs(1000))
        .build();
  }

  static Ack send(ManagedChannel channel, long seq) {
    return AgentServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .sendTransactions(batch(seq));
  }

  /** A client session stream: received server messages and the stream's end. */
  static final class Session {
    final BlockingQueue<ServerMessage> received = new LinkedBlockingQueue<>();
    final CompletableFuture<Status> ended = new CompletableFuture<>();
    final StreamObserver<AgentMessage> out;

    Session(ManagedChannel channel) {
      out =
          AgentServiceGrpc.newStub(channel)
              .session(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                      received.add(message);
                    }

                    @Override
                    public void onError(Throwable error) {
                      ended.complete(Status.fromThrowable(error));
                    }

                    @Override
                    public void onCompleted() {
                      ended.complete(Status.OK);
                    }
                  });
    }

    ServerMessage next() throws InterruptedException {
      ServerMessage message = received.poll(10, TimeUnit.SECONDS);
      assertThat(message).as("server message within 10 s").isNotNull();
      return message;
    }

    void hello(String env) {
      out.onNext(
          AgentMessage.newBuilder()
              .setHello(
                  AgentHello.newBuilder()
                      .setAppName("shop")
                      .setEnv(env)
                      .setAppVersion("1.2.3")
                      .setAgentVersion("0.0.0-SNAPSHOT"))
              .build());
    }
  }

  @Test
  @DisplayName(
      "records every batch (instance, epoch, seq, receive time, body, size) and acknowledges it;"
          + " records become harness transactions with dictionary names from the session")
  void recordsAndAcks() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    Session session = new Session(channel);
    session.hello("test");
    assertThat(session.next().getHello().getAccepted()).isTrue();
    session.out.onNext(
        AgentMessage.newBuilder()
            .setDictionaryEntries(
                DictionaryEntries.newBuilder()
                    .addEntries(
                        DictionaryEntry.newBuilder()
                            .setKind(DictionaryKind.SERVICE)
                            .setHash(100)
                            .setText("GET /api/orders/{id}")))
            .build());
    collector.awaitSessions(1, Duration.ofSeconds(5));
    long before = System.currentTimeMillis();
    for (long seq = 1; seq <= 3; seq++) {
      Ack ack = send(channel, seq);
      assertThat(ack.getLastSeq()).isEqualTo(seq);
      assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    }
    List<ReceivedBatch> batches = collector.batches(BatchKind.TRANSACTIONS);
    assertThat(batches).hasSize(3);
    assertThat(batches).extracting(ReceivedBatch::seq).containsExactly(1L, 2L, 3L);
    assertThat(batches)
        .allSatisfy(
            batch -> {
              assertThat(batch.instanceId()).isEqualTo(7);
              assertThat(batch.agentEpoch()).isEqualTo(3);
              assertThat(batch.acked()).isTrue();
              assertThat(batch.bytes()).isEqualTo(batch(batch.seq()).getSerializedSize());
              assertThat(batch.receivedAt().toEpochMilli()).isGreaterThanOrEqualTo(before - 1000);
              assertThat(batch.body()).isEqualTo(batch(batch.seq()));
            });
    assertThat(collector.dictionary()).containsEntry(100L, "GET /api/orders/{id}");
    assertThat(collector.sessions())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.instanceId()).isEqualTo(1);
              assertThat(s.env()).isEqualTo("test");
            });
    assertThat(collector.collected())
        .transactions()
        .hasSize(3)
        .allSatisfy(
            tx -> tx.hasService("GET /api/orders/{id}").hasStatus(200).cpuTimeMs().isMissing());
    assertThat(collector.collected()).dictionary().serviceCount().isEqualTo(1);
    assertThat(collector.agentSettings())
        .containsEntry("server.address", "127.0.0.1:" + collector.port())
        .containsEntry("server.tls", "false");
  }

  @Test
  @DisplayName("latency(Duration): every answer arrives that much later; clear() removes it")
  void latency() {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    send(channel, 1);
    collector.faults().latency(Duration.ofMillis(400));
    long start = System.nanoTime();
    send(channel, 2);
    long delayed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(delayed).isGreaterThanOrEqualTo(400);
    collector.faults().clear();
    start = System.nanoTime();
    send(channel, 3);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(400);
  }

  @Test
  @DisplayName("failNext(n, status): the next n calls fail with the status, then calls succeed")
  void failNext() {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    collector.faults().failNext(2, Status.RESOURCE_EXHAUSTED);
    for (long seq = 1; seq <= 2; seq++) {
      long s = seq;
      assertThatThrownBy(() -> send(channel, s))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              e ->
                  assertThat(((StatusRuntimeException) e).getStatus().getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    }
    assertThat(send(channel, 3).getLastSeq()).isEqualTo(3);
    assertThat(collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly("FAILED RESOURCE_EXHAUSTED", "FAILED RESOURCE_EXHAUSTED", "ACKED");
  }

  @Test
  @DisplayName("hang(): calls are recorded but never answered (deadline); clear() answers again")
  void hang() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    collector.faults().hang();
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                AgentServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                    .sendTransactions(batch(1)))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .isGreaterThanOrEqualTo(450);
    Session session = new Session(channel);
    session.hello("test");
    assertThat(session.received.poll(500, TimeUnit.MILLISECONDS)).as("hello unanswered").isNull();
    assertThat(collector.batches())
        .singleElement()
        .extracting(ReceivedBatch::outcome)
        .isEqualTo("HUNG");
    collector.faults().clear();
    assertThat(send(channel, 2).getLastSeq()).isEqualTo(2);
  }

  @Test
  @DisplayName("rejectAuth(): unary calls end UNAUTHENTICATED, hellos are rejected TOKEN_INVALID")
  void rejectAuth() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    collector.faults().rejectAuth();
    assertThatThrownBy(() -> send(channel, 1))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e ->
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAUTHENTICATED));
    Session session = new Session(channel);
    session.hello("test");
    ServerMessage hello = session.next();
    assertThat(hello.getHello().getAccepted()).isFalse();
    assertThat(hello.getHello().getRejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
    assertThat(collector.sessions())
        .singleElement()
        .satisfies(s -> assertThat(s.accepted()).isFalse());
    assertThat(collector.batches())
        .singleElement()
        .extracting(ReceivedBatch::outcome)
        .isEqualTo("REJECTED_AUTH");
  }

  @Test
  @DisplayName(
      "session: hello assigns instance ids, heartbeats are answered, ConfigPush and Command round"
          + " trips complete with the agent's results")
  void sessionRoundTrips() throws Exception {
    FakeCollector collector = collector(FakeCollector.builder().firstInstanceId(5).start());
    ManagedChannel channel = plaintext(collector);
    Session session = new Session(channel);
    session.hello("test");
    assertThat(session.next().getHello().getInstanceId()).isEqualTo(5);
    session.out.onNext(
        AgentMessage.newBuilder().setHeartbeat(Heartbeat.newBuilder().setT1Ms(123)).build());
    assertThat(session.next().getHeartbeatAck().getT1Ms()).isEqualTo(123);
    assertThat(collector.openSessionCount()).isEqualTo(1);

    CompletableFuture<ConfigApplyOutcome> applied =
        collector.pushConfig(Map.of("enabled", "false", "server.address", "x"));
    ServerMessage push = session.next();
    assertThat(push.getConfigPush().getValuesMap()).containsEntry("enabled", "false");
    session.out.onNext(
        AgentMessage.newBuilder()
            .setConfigApplyResult(
                ConfigApplyResult.newBuilder()
                    .setChangeId(push.getConfigPush().getChangeId())
                    .addAppliedKeys("enabled")
                    .addRejected(
                        RejectedKey.newBuilder()
                            .setKey("server.address")
                            .setCode(RejectCode.NOT_ALLOWED))
                    .setRetransformedClasses(2))
            .build());
    ConfigApplyOutcome outcome = applied.get(10, TimeUnit.SECONDS);
    assertThat(outcome.appliedKeys()).containsExactly("enabled");
    assertThat(outcome.rejected()).containsEntry("server.address", "NOT_ALLOWED");
    assertThat(outcome.retransformedClasses()).isEqualTo(2);

    CompletableFuture<CommandOutcome> dump = collector.command("THREAD_DUMP", Map.of("a", "b"));
    ServerMessage command = session.next();
    assertThat(command.getCommand().getType().name()).isEqualTo("THREAD_DUMP");
    assertThat(command.getCommand().getArgsMap()).containsEntry("a", "b");
    for (int chunk = 0; chunk < 2; chunk++) {
      session.out.onNext(
          AgentMessage.newBuilder()
              .setCommandResult(
                  CommandResult.newBuilder()
                      .setCommandId(command.getCommand().getCommandId())
                      .setStatus(CommandStatus.OK)
                      .setChunkIndex(chunk)
                      .setChunkCount(2)
                      .setContentType("text/plain")
                      .setPayload(ByteString.copyFromUtf8("part" + chunk + ";")))
              .build());
    }
    CommandOutcome result = dump.get(10, TimeUnit.SECONDS);
    assertThat(result.status()).isEqualTo("OK");
    assertThat(result.payloadText()).isEqualTo("part0;part1;");
    assertThat(result.chunks()).isEqualTo(2);
    assertThat(collector.sessionMessages()).hasSize(1);
  }

  @Test
  @DisplayName("closeStream(): open session streams end with UNAVAILABLE")
  void closeStream() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    Session session = new Session(plaintext(collector));
    session.hello("test");
    session.next();
    collector.faults().closeStream();
    assertThat(session.ended.get(10, TimeUnit.SECONDS).getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(collector.openSessionCount()).isZero();
  }

  @Test
  @DisplayName(
      "serverRestart(id): open streams end, the port stays, the next hello gets the new instance id")
  void serverRestart() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    int port = collector.port();
    Session first = new Session(plaintext(collector));
    first.hello("test");
    assertThat(first.next().getHello().getInstanceId()).isEqualTo(1);
    collector.faults().serverRestart(42);
    assertThat(first.ended.get(10, TimeUnit.SECONDS).isOk()).isFalse();
    assertThat(collector.port()).isEqualTo(port);
    ManagedChannel channel = plaintext(collector);
    Session second = new Session(channel);
    second.hello("test");
    assertThat(second.next().getHello().getInstanceId()).isEqualTo(42);
    assertThat(send(channel, 9).getLastSeq()).isEqualTo(9);
  }

  @Test
  @DisplayName(
      "18 §5.3 no receive limit: a batch above gRPC's default 4 MiB is recorded as sent and"
          + " acknowledged")
  void largeBatch() {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = plaintext(collector);
    // One record with a 5 MiB error message, built once.
    TransactionBatch large =
        batch(11).toBuilder()
            .setRecords(0, batch(11).getRecords(0).toBuilder().setErrorMessage("x".repeat(5 << 20)))
            .build();
    assertThat(large.getSerializedSize()).isGreaterThan(4 << 20);
    Ack ack =
        AgentServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .sendTransactions(large);
    assertThat(ack.getLastSeq()).isEqualTo(11);
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(collector.batches(BatchKind.TRANSACTIONS))
        .singleElement()
        .satisfies(
            batch -> {
              assertThat(batch.seq()).isEqualTo(11);
              assertThat(batch.acked()).isTrue();
              assertThat(batch.bytes()).isEqualTo(large.getSerializedSize());
            });
  }

  @Test
  @DisplayName(
      "TLS: a client trusting the collector's certificate connects to 127.0.0.1; plaintextOnly()"
          + " restarts without TLS (TLS clients fail the handshake, plaintext works);"
          + " wrongHostCertificate() fails host name verification, the same certificate with its"
          + " own authority connects")
  void tlsModes() throws Exception {
    FakeCollector collector = collector(FakeCollector.startTls());
    assertThat(collector.tls()).isTrue();
    assertThat(collector.agentSettings())
        .containsEntry("server.address", "127.0.0.1:" + collector.port())
        .containsEntry("server.tls", "true")
        .containsKey("server.tls.ca-file");
    assertThat(collector.address()).isEqualTo("127.0.0.1:" + collector.port());
    assertThat(send(tls(collector), 1).getLastSeq()).isEqualTo(1);

    java.nio.file.Path pem = collector.trustPem();
    collector.faults().plaintextOnly();
    assertThat(collector.tls()).isFalse();
    ManagedChannel tlsAfter = tls(collector, pem, null);
    assertThatThrownBy(() -> send(tlsAfter, 2))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(
            e -> {
              assertThat(((StatusRuntimeException) e).getStatus().getCode())
                  .isEqualTo(Status.Code.UNAVAILABLE);
              // The TLS handshake met a plaintext server: not a refused connection.
              assertThat(causes(e))
                  .as("cause chain of %s", e)
                  .anyMatch(cause -> cause instanceof javax.net.ssl.SSLException)
                  .noneMatch(cause -> cause instanceof java.net.ConnectException);
            });
    assertThat(send(plaintext(collector), 3).getLastSeq()).isEqualTo(3);

    collector.faults().wrongHostCertificate();
    assertThat(collector.tls()).isTrue();
    java.nio.file.Path wrongHostPem = collector.trustPem();
    // Trusted certificate, wrong name: 127.0.0.1 (and localhost) are not in its SAN.
    for (String authority : java.util.Arrays.asList(null, "localhost")) {
      ManagedChannel wrongHost = tls(collector, wrongHostPem, authority);
      assertThatThrownBy(() -> send(wrongHost, 4))
          .as("authority %s", authority)
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              e -> {
                assertThat(((StatusRuntimeException) e).getStatus().getCode())
                    .isEqualTo(Status.Code.UNAVAILABLE);
                assertThat(causes(e))
                    .as("cause chain of %s", e)
                    .anyMatch(
                        cause ->
                            (cause instanceof javax.net.ssl.SSLHandshakeException
                                    || cause instanceof java.security.cert.CertificateException)
                                && String.valueOf(cause.getMessage())
                                    .contains("No subject alternative"));
              });
    }
    // Positive control: the same certificate and trust anchor with the name it was made for.
    assertThat(send(tls(collector, wrongHostPem, "wrong.example"), 5).getLastSeq()).isEqualTo(5);
    assertThat(collector.batches(BatchKind.TRANSACTIONS))
        .extracting(ReceivedBatch::seq)
        .containsExactly(1L, 3L, 5L);
  }
}
