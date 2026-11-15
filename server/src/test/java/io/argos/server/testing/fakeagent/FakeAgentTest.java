/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.fakeagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.hash.Xxh3;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The fake agent of the test fixtures against an in-process stub of {@code AgentService}: the
 * metadata, session messages, heartbeat arithmetic inputs, recorded answers and the clock skew that
 * the collector tests rely on.
 */
@Tag("req:DSN-11-01")
@Tag("req:D-02")
class FakeAgentTest {

  private static final Metadata.Key<String> AUTHORIZATION = FakeAgent.AUTHORIZATION;
  private static final Context.Key<Metadata> HEADERS = Context.key("headers");

  private final Stub stub = new Stub();
  private Server server;
  private ManagedChannel channel;

  @BeforeEach
  void start() throws Exception {
    String name = "fake-agent-" + UUID.randomUUID();
    ServerInterceptor captureHeaders =
        new ServerInterceptor() {
          @Override
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            if (call.getMethodDescriptor().getBareMethodName().equals("Session")
                && headers.get(AUTHORIZATION) == null) {
              call.close(Status.UNAUTHENTICATED.withDescription("no token"), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            return Contexts.interceptCall(
                Context.current().withValue(HEADERS, headers), call, headers, next);
          }
        };
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(ServerInterceptors.intercept(stub, captureHeaders))
            .build()
            .start();
    channel = AgentChannels.inProcess(name);
  }

  @AfterEach
  void stop() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  @DisplayName(
      "connect sends the token as Bearer and a Hello with the agent clock; accepted ids are used")
  void connectAndSend() {
    String token = AgentTokens.generate();
    AgentClock clock = AgentClock.of(() -> 1_793_000_000_000L, 60_000);
    try (FakeAgent agent =
        FakeAgent.builder(channel).token(token).clock(clock).appName("shop").build()) {
      HelloResult hello = agent.connect();

      assertThat(hello.accepted()).isTrue();
      assertThat(stub.sessionHeaders.get(AUTHORIZATION)).isEqualTo("Bearer " + token);
      AgentMessage first = stub.sessionMessages.get(0);
      assertThat(first.getHello().getAppName()).isEqualTo("shop");
      assertThat(first.getHello().getSentAtMs()).isEqualTo(1_793_000_060_000L);
      assertThat(agent.instanceId()).isEqualTo(17);

      TransactionRecord tx = agent.records().transaction().build();
      TransactionBatch batch = agent.batches().transactions(List.of(tx));
      CallResult result = agent.sendTransactions(batch);

      assertThat(result.ok()).isTrue();
      assertThat(result.ackStatus()).isEqualTo(AckStatus.ACK_OK);
      assertThat(batch.getHeader().getInstanceId()).isEqualTo(17);
      assertThat(batch.getHeader().getSeq()).isEqualTo(1);
      assertThat(batch.getHeader().getSentAtMs()).isEqualTo(1_793_000_060_000L);
      assertThat(tx.getStartTsMs()).isEqualTo(1_793_000_060_000L - 25);
      assertThat(TxIds.startMs(tx.getTxId())).isEqualTo(tx.getStartTsMs());
      assertThat(tx.getTraceId().size()).isEqualTo(16);
      assertThat(tx.hasCpuTimeUs()).isTrue();
      assertThat(tx.getServiceId()).isEqualTo(Xxh3.hash64("GET /api/orders/{id}"));
      Metadata dataHeaders = stub.dataHeaders.get(0);
      assertThat(dataHeaders.get(FakeAgent.INSTANCE_ID)).isEqualTo("17");
      assertThat(dataHeaders.get(FakeAgent.SESSION_ID)).isEqualTo(Stub.SESSION_ID);
      assertThat(dataHeaders.get(AUTHORIZATION)).isEqualTo("Bearer " + token);
      assertThat(agent.calls()).containsExactly(result);
      assertThat(agent.toString()).doesNotContain(token);
    }
  }

  @Test
  @DisplayName(
      "call specs: no token, no session, another session; error status and trailers recorded")
  void callSpecsAndTrailers() {
    try (FakeAgent agent = FakeAgent.builder(channel).token(AgentTokens.generate()).build()) {
      agent.connect();
      stub.failNext.set(true);
      TransactionBatch batch =
          agent.batches().transactions(List.of(agent.records().transaction().build()));

      CallResult failed =
          agent.send(
              BatchKind.TRANSACTIONS,
              batch,
              batch.getHeader().getSeq(),
              FakeAgent.CallSpec.defaults().withoutToken().withoutSession());

      assertThat(failed.ok()).isFalse();
      assertThat(failed.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(failed.rejectReason()).isEqualTo("batch_count");
      assertThat(failed.retryAfterMs()).hasValue(1500);
      Metadata headers = stub.dataHeaders.get(0);
      assertThat(headers.get(AUTHORIZATION)).isNull();
      assertThat(headers.get(FakeAgent.INSTANCE_ID)).isNull();

      agent.send(
          BatchKind.TRANSACTIONS,
          batch,
          batch.getHeader().getSeq(),
          FakeAgent.CallSpec.defaults().withSession(99, "other"));
      assertThat(stub.dataHeaders.get(1).get(FakeAgent.INSTANCE_ID)).isEqualTo("99");
      assertThat(stub.dataHeaders.get(1).get(FakeAgent.SESSION_ID)).isEqualTo("other");

      CallResult raw =
          agent.sendRaw(BatchKind.TRANSACTIONS, new byte[] {8, 1}, FakeAgent.CallSpec.defaults());
      assertThat(raw.ok()).isTrue();
      assertThat(stub.rawSizes).contains(2);
    }
  }

  @Test
  @DisplayName("heartbeats carry prev_t1/prev_t4 of the last round on the skewed agent clock")
  void heartbeatRounds() {
    AtomicLong now = new AtomicLong(1_793_000_000_000L);
    AgentClock clock = AgentClock.of(now::get, -3_000);
    try (FakeAgent agent =
        FakeAgent.builder(channel).token(AgentTokens.generate()).clock(clock).build()) {
      agent.connect();
      Heartbeat first = agent.heartbeat();
      assertThat(first.getPrevT1Ms()).isZero();
      assertThat(first.getPrevT4Ms()).isZero();
      assertThat(agent.awaitServerMessage(ServerMessage::hasHeartbeatAck, Duration.ofSeconds(5)))
          .isPresent();
      now.addAndGet(30_000);
      Heartbeat second = agent.heartbeat();
      assertThat(second.getT1Ms()).isEqualTo(1_793_000_027_000L);
      assertThat(second.getPrevT1Ms()).isEqualTo(first.getT1Ms());
      assertThat(second.getPrevT4Ms()).isEqualTo(1_792_999_997_000L);
      assertThat(agent.heartbeatRound(Duration.ofSeconds(5))).isPresent();
    }
  }

  @Test
  @DisplayName("a DictionaryRequest is answered with the known entries only")
  void dictionaryAnswers() {
    try (FakeAgent agent = FakeAgent.builder(channel).token(AgentTokens.generate()).build()) {
      agent.connect();
      long service = agent.records().id(DictionaryKind.SERVICE, "GET /a");
      stub.dictionaryRequest.set(
          DictionaryRequest.newBuilder()
              .addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.SERVICE).setHash(service))
              .addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.SERVICE).setHash(12345))
              .build());
      agent.heartbeatRound(Duration.ofSeconds(5));

      AgentMessage answer =
          stub.awaitSessionMessage(AgentMessage::hasDictionaryEntries, Duration.ofSeconds(5));
      assertThat(answer.getDictionaryEntries().getEntriesList())
          .singleElement()
          .satisfies(e -> assertThat(e.getText()).isEqualTo("GET /a"));
      assertThat(agent.records().drainNewEntries()).extracting(e -> e.getText()).contains("GET /a");
    }
  }

  @Test
  @DisplayName("a call ended by a server interceptor before the Hello records its status")
  void rejectedBeforeHello() {
    try (FakeAgent agent = FakeAgent.builder(channel).token(null).build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.hello()).isNull();
      assertThat(hello.accepted()).isFalse();
      assertThat(hello.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
      assertThat(agent.sessionOpen()).isFalse();
    }
  }

  @Test
  @DisplayName("a rejected Hello records the reason, retry_after_s and the final status")
  void rejectedHello() {
    stub.reject.set(true);
    try (FakeAgent agent = FakeAgent.builder(channel).token(AgentTokens.generate()).build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).isFalse();
      assertThat(hello.rejectReason()).isEqualTo(RejectReason.APP_MISMATCH);
      assertThat(hello.retryAfterS()).isZero();
      assertThat(hello.code()).isEqualTo(Status.Code.PERMISSION_DENIED);
      assertThat(agent.sessionOpen()).isFalse();
    }
  }

  @Test
  @DisplayName("tokens: argos_ + 43 base64url characters, stored as SHA-256 hex, prefix of 14")
  void tokens() {
    String token = AgentTokens.generate();
    assertThat(token).matches("argos_[A-Za-z0-9_-]{43}");
    assertThat(AgentTokens.sha256Hex(token)).matches("[0-9a-f]{64}");
    assertThat(AgentTokens.prefix(token)).hasSize(14).isEqualTo(token.substring(0, 14));
    assertThat(AgentTokens.generate()).isNotEqualTo(token);
  }

  @Test
  @DisplayName(
      "attack payloads: 5,001 records; the gzip bomb is about 1/1000 and inflates to a valid batch")
  void attackPayloads() throws Exception {
    try (FakeAgent agent = FakeAgent.builder(channel).token(AgentTokens.generate()).build()) {
      assertThat(
              Attacks.oversizedTransactionBatch(agent, Attacks.OVERFLOW_RECORDS).getRecordsCount())
          .isEqualTo(5_001);
      byte[] bomb = Attacks.gzipBomb(64L << 20);
      assertThat(bomb.length).isLessThan((64 << 20) / 900);
      assertThat(bomb[0] & 0xFF).isEqualTo(0x1F);
      assertThat(bomb[1] & 0xFF).isEqualTo(0x8B);
      byte[] small = Attacks.gzipBomb(10_000);
      try (java.util.zip.GZIPInputStream in =
          new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(small))) {
        byte[] inflated = in.readAllBytes();
        assertThat(inflated.length).isBetween(9_980, 10_000);
        TransactionBatch parsed = TransactionBatch.parseFrom(inflated);
        assertThat(parsed.getRecordsCount()).isEqualTo(1);
        assertThat(parsed.getRecords(0).getUrlPath()).hasSize(10_000 - 24).matches("a+");
      }
      assertThat(Attacks.randomServiceTransactions(agent, 12_000, 1))
          .extracting(TransactionBatch::getRecordsCount)
          .containsExactly(5_000, 5_000, 2_000);
    }
  }

  /** A stub AgentService that accepts every Hello and answers every batch. */
  private static final class Stub extends AgentServiceGrpc.AgentServiceImplBase {
    static final String SESSION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

    final List<AgentMessage> sessionMessages = new CopyOnWriteArrayList<>();
    final List<Metadata> dataHeaders = new CopyOnWriteArrayList<>();
    final List<Integer> rawSizes = new CopyOnWriteArrayList<>();
    final java.util.concurrent.atomic.AtomicBoolean failNext =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean reject =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicReference<DictionaryRequest> dictionaryRequest =
        new java.util.concurrent.atomic.AtomicReference<>();
    volatile Metadata sessionHeaders;

    @Override
    public StreamObserver<AgentMessage> session(StreamObserver<ServerMessage> out) {
      sessionHeaders = HEADERS.get();
      return new StreamObserver<>() {
        @Override
        public void onNext(AgentMessage m) {
          sessionMessages.add(m);
          if (m.hasHello()) {
            if (reject.get()) {
              out.onNext(
                  ServerMessage.newBuilder()
                      .setHello(
                          ServerHello.newBuilder()
                              .setAccepted(false)
                              .setRejectReason(RejectReason.APP_MISMATCH)
                              .setRetryAfterS(0))
                      .build());
              out.onError(Status.PERMISSION_DENIED.withDescription("app").asRuntimeException());
              return;
            }
            out.onNext(
                ServerMessage.newBuilder()
                    .setHello(
                        ServerHello.newBuilder()
                            .setAccepted(true)
                            .setAppId(3)
                            .setInstanceId(17)
                            .setSessionId(SESSION_ID)
                            .setServerTimeMs(System.currentTimeMillis()))
                    .build());
          } else if (m.hasHeartbeat()) {
            long t = System.currentTimeMillis();
            out.onNext(
                ServerMessage.newBuilder()
                    .setHeartbeatAck(
                        HeartbeatAck.newBuilder()
                            .setT1Ms(m.getHeartbeat().getT1Ms())
                            .setT2Ms(t)
                            .setT3Ms(t))
                    .build());
            DictionaryRequest request = dictionaryRequest.getAndSet(null);
            if (request != null) {
              out.onNext(ServerMessage.newBuilder().setDictionaryRequest(request).build());
            }
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
      dataHeaders.add(HEADERS.get());
      rawSizes.add(request.getSerializedSize());
      if (failNext.getAndSet(false)) {
        Metadata trailers = new Metadata();
        trailers.put(CallResult.REJECT_REASON, "batch_count");
        trailers.put(CallResult.RETRY_AFTER_MS, "1500");
        out.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException(trailers));
        return;
      }
      out.onNext(
          Ack.newBuilder()
              .setStatus(AckStatus.ACK_OK)
              .setLastSeq(request.getHeader().getSeq())
              .build());
      out.onCompleted();
    }

    AgentMessage awaitSessionMessage(
        java.util.function.Predicate<AgentMessage> filter, Duration timeout) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        for (AgentMessage m : sessionMessages) {
          if (filter.test(m)) {
            return m;
          }
        }
        Thread.onSpinWait();
      }
      throw new AssertionError("no matching session message within " + timeout);
    }
  }
}
