/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.argos.agent.testing.collector.ScriptedAgent.Session;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentBye;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceState;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.RejectedKey;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionBatch;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The FakeCollector (18 §5.3, 11 §16) against a real gRPC client (grpc-netty over loopback, the
 * argos.v1 stubs of {@code :proto}): recording, the session contract, every FaultInjector fault,
 * the test accessors and the TLS modes. Each display name cites the clause it shows, and no test
 * ID: these tests show the harness, while the IDs of the agent's acceptance criteria (T-AG-067,
 * T-AG-071, T-ACC-02, T-SEC-07/08) belong to the tests that run the agent ({@code transport}
 * package), and the traceability report reads an ID anywhere in a display name.
 */
@Tag("req:DSN-18-04")
class FakeCollectorTest {
  private final ScriptedAgent agent = new ScriptedAgent();
  private final List<FakeCollector> collectors = new ArrayList<>();

  @AfterEach
  void close() throws InterruptedException {
    agent.close();
    for (FakeCollector collector : collectors) {
      collector.close();
    }
  }

  FakeCollector collector(FakeCollector collector) {
    collectors.add(collector);
    return collector;
  }

  /** The exception and its causes, outermost first. */
  static List<Throwable> causes(Throwable error) {
    List<Throwable> chain = new ArrayList<>();
    for (Throwable t = error; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }

  static StatusRuntimeException failure(Runnable call) {
    try {
      call.run();
    } catch (StatusRuntimeException e) {
      return e;
    }
    throw new AssertionError("the call succeeded");
  }

  static Status.Code code(Runnable call) {
    return failure(call).getStatus().getCode();
  }

  /** A plaintext collector with one accepted session. */
  record Connected(
      FakeCollector collector, ManagedChannel channel, Session session, ServerHello hello) {
    Ack send(long seq) {
      return ScriptedAgent.send(channel, collector.token(), hello, 3, seq);
    }
  }

  Connected connected(FakeCollector collector) throws Exception {
    ManagedChannel channel = agent.plaintext(collector);
    Session session = new Session(channel, collector.token());
    ServerHello hello = session.accepted("test");
    return new Connected(collector, channel, session, hello);
  }

  @Test
  @DisplayName(
      "18 §5.3: records every batch (instance, epoch, seq, receive time, body, size, session,"
          + " masked token) and acknowledges it ACK_OK with last_seq (11 §6); records become"
          + " harness transactions with dictionary names from the session")
  void recordsAndAcks() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.session.send(
        AgentMessage.newBuilder()
            .setDictionaryEntries(
                DictionaryEntries.newBuilder()
                    .addEntries(
                        DictionaryEntry.newBuilder()
                            .setKind(DictionaryKind.SERVICE)
                            .setHash(100)
                            .setText("GET /api/orders/{id}")))
            .build());
    long before = System.currentTimeMillis();
    for (long seq = 1; seq <= 3; seq++) {
      Ack ack = c.send(seq);
      assertThat(ack.getLastSeq()).isEqualTo(seq);
      assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    }
    FakeCollector collector = c.collector;
    List<ReceivedBatch> batches = collector.batches(BatchKind.TRANSACTIONS);
    assertThat(batches).hasSize(3);
    assertThat(collector.seqs(BatchKind.TRANSACTIONS)).containsExactly(1L, 2L, 3L);
    assertThat(batches)
        .allSatisfy(
            batch -> {
              assertThat(batch.instanceId()).isEqualTo(1);
              assertThat(batch.agentEpoch()).isEqualTo(3);
              assertThat(batch.acked()).isTrue();
              assertThat(batch.duplicate()).isFalse();
              assertThat(batch.sessionId()).isEqualTo(c.hello.getSessionId());
              assertThat(batch.token().matches(collector.token())).isTrue();
              assertThat(batch.toString()).contains("argos_****");
              ScriptedAgent.assertNoToken("a batch", batch.toString(), collector.token());
              assertThat(batch.bytes()).isEqualTo(batch.body().getSerializedSize());
              assertThat(batch.receivedAt().toEpochMilli()).isGreaterThanOrEqualTo(before - 1000);
              assertThat(((TransactionBatch) batch.body()).getHeader().getSeq())
                  .isEqualTo(batch.seq());
            });
    assertThat(collector.counts().get(BatchKind.TRANSACTIONS))
        .isEqualTo(
            new KindCounts(
                3, 3, batches.stream().mapToLong(ReceivedBatch::bytes).sum(), 3, 3, 0, 0));
    collector.awaitSessions(1, Duration.ofSeconds(5));
    assertThat(collector.dictionary()).containsEntry(100L, "GET /api/orders/{id}");
    assertThat(collector.dictionaryEntries()).hasSize(1);
    assertThat(collector.sessions())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.instanceId()).isEqualTo(1);
              assertThat(s.env()).isEqualTo("test");
              assertThat(s.sessionId()).isEqualTo(c.hello.getSessionId());
              assertThat(s.plaintext()).isTrue();
              ScriptedAgent.assertNoToken("a session", s.toString(), collector.token());
            });
    assertThat(collector.collected())
        .transactions()
        .hasSize(3)
        .allSatisfy(
            tx -> tx.hasService("GET /api/orders/{id}").hasStatus(200).cpuTimeMs().isMissing());
    assertThat(collector.collected()).dictionary().serviceCount().isEqualTo(1);
    assertThat(collector.agentSettings())
        .containsEntry("server.address", "127.0.0.1:" + collector.port())
        .containsEntry("server.tls", "false")
        .doesNotContainKey("server.token");
    // Compared without printing the value: a failure message is test output (11 §11, SEC-06).
    assertThat(Pattern.matches("argos_[A-Za-z0-9_-]{20,}", collector.token()))
        .as("token format argos_<base64url>")
        .isTrue();
  }

  @Test
  @DisplayName(
      "11 §5: ServerHello carries a UUID session id, server time, Limits defaults (11 §4.2),"
          + " the configured heartbeat interval and config; the same instance identity keeps its"
          + " instance id (reconnect), another identity gets the next one")
  void serverHello() throws Exception {
    FakeCollector collector =
        collector(
            FakeCollector.builder()
                .firstInstanceId(5)
                .heartbeatIntervalS(2)
                .config(Map.of("sampling.profile.normal-rate", "0.5"))
                .start());
    ManagedChannel channel = agent.plaintext(collector);
    Session first = new Session(channel, collector.token());
    long sentAt = System.currentTimeMillis();
    ServerHello hello = first.accepted("test");
    assertThat(hello.getInstanceId()).isEqualTo(5);
    assertThat(java.util.UUID.fromString(hello.getSessionId())).isNotNull();
    assertThat(hello.getServerTimeMs()).isBetween(sentAt - 1000, System.currentTimeMillis() + 1000);
    assertThat(hello.getLimits()).isEqualTo(ProtocolLimits.defaults());
    assertThat(hello.getHeartbeatIntervalS()).isEqualTo(2);
    assertThat(hello.getConfigMap()).containsEntry("sampling.profile.normal-rate", "0.5");
    assertThat(hello.getProtocolVersion()).isEqualTo(io.argos.proto.ProtocolVersion.CURRENT);
    assertThat(hello.getUserIdSalt().isEmpty()).as("no salt over plaintext").isTrue();
    first.complete();
    Session again = new Session(channel, collector.token());
    ServerHello reconnect = again.accepted("test");
    assertThat(reconnect.getInstanceId()).isEqualTo(5);
    assertThat(reconnect.getSessionId()).isNotEqualTo(hello.getSessionId());
    Session other = new Session(channel, collector.token());
    other.hello("shop", "test", "host:2", 9, 1, System.currentTimeMillis());
    assertThat(other.next().getHello().getInstanceId()).isEqualTo(6);
    assertThat(collector.acceptedSessionCount()).isEqualTo(3);
    assertThat(collector.reconnectCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("latency(Duration): every answer arrives that much later; clear() removes it")
  void latency() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.send(1);
    c.collector.faults().latency(Duration.ofMillis(400));
    long start = System.nanoTime();
    c.send(2);
    long delayed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(delayed).isGreaterThanOrEqualTo(400);
    c.collector.faults().clear();
    start = System.nanoTime();
    c.send(3);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(400);
  }

  @Test
  @DisplayName(
      "failNext(n, status[, retryAfter]): the next n calls fail with the status (11 §10 trailer"
          + " argos-retry-after-ms), then calls succeed; a failed seq retried is ACK_OK, not a"
          + " duplicate (11 §6)")
  void failNext() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().failNext(2, Status.RESOURCE_EXHAUSTED, Duration.ofMillis(1500));
    for (int i = 0; i < 2; i++) {
      StatusRuntimeException e = failure(() -> c.send(1));
      assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(e.getTrailers().get(V1Protocol.RETRY_AFTER_MS)).isEqualTo("1500");
    }
    Ack ack = c.send(1);
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(ack.getLastSeq()).isEqualTo(1);
    assertThat(c.collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly("FAILED RESOURCE_EXHAUSTED", "FAILED RESOURCE_EXHAUSTED", "ACKED");
    c.collector.faults().failNext(1, Status.UNAVAILABLE);
    StatusRuntimeException plain = failure(() -> c.send(2));
    assertThat(plain.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(plain.getTrailers().get(V1Protocol.RETRY_AFTER_MS)).isNull();
  }

  @Test
  @DisplayName(
      "hang(): calls, hellos and heartbeats are recorded but never answered (deadline); a hung"
          + " call stays in flight until the client cancels; clear() answers again")
  void hang() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    FakeCollector collector = c.collector;
    collector.faults().hang();
    long start = System.nanoTime();
    assertThat(
            code(
                () ->
                    AgentServiceGrpc.newBlockingStub(
                            ScriptedAgent.with(
                                c.channel, ScriptedAgent.headers(collector.token(), c.hello)))
                        .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                        .sendTransactions(
                            (TransactionBatch)
                                ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 1, 1))))
        .isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
        .isGreaterThanOrEqualTo(450);
    awaitTrue(() -> collector.concurrentUnaryCalls() == 0, "cancelled call ended");
    c.session.heartbeat(System.currentTimeMillis(), 0, 0);
    Session late = new Session(c.channel, collector.token());
    late.hello("test");
    assertThat(late.received.poll(500, TimeUnit.MILLISECONDS)).as("hello unanswered").isNull();
    assertThat(c.session.received.poll(200, TimeUnit.MILLISECONDS))
        .as("heartbeat unanswered")
        .isNull();
    assertThat(collector.heartbeats())
        .singleElement()
        .satisfies(h -> assertThat(h.answered()).isFalse());
    assertThat(collector.batches())
        .singleElement()
        .extracting(ReceivedBatch::outcome)
        .isEqualTo("HUNG");
    collector.faults().clear();
    assertThat(c.send(2).getLastSeq()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "rejectAuth(): data calls end UNAUTHENTICATED (trailer argos-reject-reason TOKEN_INVALID, no"
          + " retry advice), hellos get ServerHello{TOKEN_INVALID, retry_after_s 300} (ADR-0010"
          + " C-140) and the stream ends UNAUTHENTICATED with argos-retry-after-ms 300000 (11 §5"
          + " check 1, §10)")
  void rejectAuth() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().rejectAuth();
    StatusRuntimeException e = failure(() -> c.send(1));
    assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(e.getTrailers().get(V1Protocol.REJECT_REASON)).isEqualTo("TOKEN_INVALID");
    assertThat(e.getTrailers().get(V1Protocol.RETRY_AFTER_MS)).isNull();
    ScriptedAgent.assertNoToken(
        "the status description", e.getStatus().getDescription(), c.collector.token());
    Session session = new Session(c.channel, c.collector.token());
    session.hello("test");
    ServerHello hello = session.next().getHello();
    assertThat(hello.getAccepted()).isFalse();
    assertThat(hello.getRejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
    assertThat(hello.getRetryAfterS()).isEqualTo(300);
    ScriptedAgent.assertNoToken("reject_message", hello.getRejectMessage(), c.collector.token());
    ScriptedAgent.Ended ended = session.awaitEnd();
    assertThat(ended.status().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(ended.trailers().get(V1Protocol.REJECT_REASON)).isEqualTo("TOKEN_INVALID");
    assertThat(ended.trailers().get(V1Protocol.RETRY_AFTER_MS)).isEqualTo("300000");
    assertThat(c.collector.sessions())
        .filteredOn(s -> !s.accepted())
        .singleElement()
        .satisfies(s -> assertThat(s.rejectReason()).isEqualTo("TOKEN_INVALID"));
    assertThat(c.collector.batches())
        .singleElement()
        .extracting(ReceivedBatch::outcome)
        .isEqualTo("REJECTED_AUTH");
  }

  @Test
  @DisplayName(
      "requireToken(t) (11 §2, SEC-06): only that token passes, data calls with another or no"
          + " token are UNAUTHENTICATED, hellos TOKEN_INVALID; received tokens print as argos_****")
  void requireToken() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    collector.faults().requireToken(collector.token());
    ManagedChannel channel = agent.plaintext(collector);
    Session wrong = new Session(channel, "argos_d3JvbmctdG9rZW4");
    wrong.hello("test");
    assertThat(wrong.next().getHello().getRejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
    Session none = new Session(channel, (String) null);
    none.hello("test");
    assertThat(none.next().getHello().getRejectReason()).isEqualTo(RejectReason.TOKEN_INVALID);
    Session right = new Session(channel, collector.token());
    ServerHello hello = right.accepted("test");
    assertThat(ScriptedAgent.send(channel, collector.token(), hello, 3, 1).getStatus())
        .isEqualTo(AckStatus.ACK_OK);
    assertThat(code(() -> ScriptedAgent.send(channel, "argos_d3JvbmctdG9rZW4", hello, 3, 2)))
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(collector.sessions().toString()).contains("argos_****");
    ScriptedAgent.assertNoToken("sessions", collector.sessions().toString(), collector.token());
    ScriptedAgent.assertNoToken("batches", collector.batches().toString(), collector.token());
  }

  @Test
  @DisplayName(
      "11 §3, §8, §7: hello, heartbeat answers, ConfigPush and Command round trips complete with"
          + " the agent's results, DictionaryRequest and Throttle reach the agent")
  void sessionRoundTrips() throws Exception {
    Connected c = connected(collector(FakeCollector.builder().firstInstanceId(5).start()));
    FakeCollector collector = c.collector;
    Session session = c.session;
    assertThat(c.hello.getInstanceId()).isEqualTo(5);
    session.heartbeat(123, 0, 0);
    HeartbeatAck ack = session.next().getHeartbeatAck();
    assertThat(ack.getT1Ms()).isEqualTo(123);
    assertThat(ack.getT3Ms()).isGreaterThanOrEqualTo(ack.getT2Ms());
    assertThat(ack.getState()).isEqualTo(InstanceState.UP);
    assertThat(collector.openSessionCount()).isEqualTo(1);

    CompletableFuture<ConfigApplyOutcome> applied =
        collector.pushConfig(Map.of("enabled", "false", "server.address", "x"));
    ServerMessage push = session.next();
    assertThat(push.getConfigPush().getValuesMap()).containsEntry("enabled", "false");
    session.send(
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
      session.send(
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

    collector.requestDictionary("SQL_TEXT", 11L, 12L);
    ServerMessage request = session.next();
    assertThat(request.getDictionaryRequest().getRefsList())
        .extracting(r -> r.getKind().name() + ":" + r.getHash())
        .containsExactly("SQL_TEXT:11", "SQL_TEXT:12");
    collector.sendThrottle(0.1f, null);
    ServerMessage throttle = session.next();
    assertThat(throttle.getThrottle().hasProfileNormalRate()).isTrue();
    assertThat(throttle.getThrottle().getProfileNormalRate()).isEqualTo(0.1f);
    assertThat(throttle.getThrottle().hasSendBackoffMs()).isFalse();
    assertThat(collector.sessionMessages()).hasSize(1);
    assertThat(collector.heartbeats()).hasSize(1);
  }

  @Test
  @DisplayName(
      "closeStream(): open session streams end with UNAVAILABLE; the session stays known, so data"
          + " calls of the agent's flush are still acknowledged (11 §3, §6)")
  void closeStream() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().closeStream();
    assertThat(c.session.awaitEnd().status().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(c.collector.openSessionCount()).isZero();
    assertThat(c.send(1).getStatus()).isEqualTo(AckStatus.ACK_OK);
  }

  @Test
  @DisplayName(
      "serverRestart(): open streams end, the port stays, data calls of the old session get"
          + " FAILED_PRECONDITION(SESSION_REQUIRED); the next hello of the same identity gets the"
          + " same instance id and its dedup window holds, so a seq written before the restart is"
          + " ACK_DUPLICATE (11 §3, §5, 12 §3.4, D-13); another identity gets the next id")
  void serverRestart() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    FakeCollector collector = c.collector;
    int port = collector.port();
    assertThat(c.hello.getInstanceId()).isEqualTo(1);
    assertThat(c.send(9).getStatus()).isEqualTo(AckStatus.ACK_OK);
    collector.faults().serverRestart();
    assertThat(c.session.awaitEnd().status().isOk()).isFalse();
    assertThat(collector.port()).isEqualTo(port);
    ManagedChannel channel = agent.plaintext(collector);
    StatusRuntimeException stale =
        failure(() -> ScriptedAgent.send(channel, collector.token(), c.hello, 3, 10));
    assertThat(stale.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(stale.getStatus().getDescription()).isEqualTo("SESSION_REQUIRED");
    ServerHello hello = new Session(channel, collector.token()).accepted("test");
    assertThat(hello.getInstanceId()).isEqualTo(1);
    assertThat(hello.getSessionId()).isNotEqualTo(c.hello.getSessionId());
    Ack duplicate = ScriptedAgent.send(channel, collector.token(), hello, 3, 9);
    assertThat(duplicate.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(duplicate.getLastSeq()).isEqualTo(9);
    Ack next = ScriptedAgent.send(channel, collector.token(), hello, 3, 10);
    assertThat(next.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(next.getLastSeq()).isEqualTo(10);
    Session other = new Session(channel, collector.token());
    other.hello("shop", "test", "host:2", 3, 1, System.currentTimeMillis());
    assertThat(other.next().getHello().getInstanceId()).isEqualTo(2);
    assertThat(collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly(
            ReceivedBatch.ACKED,
            ReceivedBatch.SESSION_REQUIRED,
            ReceivedBatch.DUPLICATE,
            ReceivedBatch.ACKED);
  }

  @Test
  @DisplayName(
      "serverRestart(id): as serverRestart(), but the instance registry is lost: the next hello"
          + " gets the new instance id, whose dedup window is empty (a seq written under the old id"
          + " is ACK_OK)")
  void serverRestartWithNewInstanceId() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    FakeCollector collector = c.collector;
    int port = collector.port();
    assertThat(c.hello.getInstanceId()).isEqualTo(1);
    assertThat(c.send(9).getStatus()).isEqualTo(AckStatus.ACK_OK);
    collector.faults().serverRestart(42);
    assertThat(c.session.awaitEnd().status().isOk()).isFalse();
    assertThat(collector.port()).isEqualTo(port);
    ManagedChannel channel = agent.plaintext(collector);
    StatusRuntimeException stale =
        failure(() -> ScriptedAgent.send(channel, collector.token(), c.hello, 3, 9));
    assertThat(stale.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(stale.getStatus().getDescription()).isEqualTo("SESSION_REQUIRED");
    Session second = new Session(channel, collector.token());
    ServerHello hello = second.accepted("test");
    assertThat(hello.getInstanceId()).isEqualTo(42);
    Ack ack = ScriptedAgent.send(channel, collector.token(), hello, 3, 9);
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(ack.getLastSeq()).isEqualTo(9);
    assertThat(collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly(ReceivedBatch.ACKED, ReceivedBatch.SESSION_REQUIRED, ReceivedBatch.ACKED);
    assertThatThrownBy(() -> collector.faults().serverRestart(-1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** How a stream ends after {@code SessionClose{reason}} and what a data call gets afterwards. */
  record CloseCase(
      String reason, Status.Code streamEnd, Status.Code dataAfter, String rejectAfter) {}

  @Test
  @DisplayName(
      "sessionClose(reason, retryAfter): SessionClose{reason, retry_after_s} on every session, then"
          + " the stream ends (TOKEN_REVOKED_CLOSE UNAUTHENTICATED, SERVER_SHUTDOWN UNAVAILABLE with"
          + " argos-retry-after-ms, others OK); afterwards the revoked token is UNAUTHENTICATED"
          + " TOKEN_REVOKED (11 §10, 12 §3.1 token before session, §4.1), IDLE_TIMEOUT, REPLACED and"
          + " PROTOCOL_ERROR need a new session (SESSION_REQUIRED); SERVER_SHUTDOWN pins no data"
          + " answer (12 §3.9 drain not imitated)")
  void sessionClose() throws Exception {
    List<CloseCase> cases =
        List.of(
            new CloseCase(
                "TOKEN_REVOKED_CLOSE",
                Status.Code.UNAUTHENTICATED,
                Status.Code.UNAUTHENTICATED,
                "TOKEN_REVOKED"),
            new CloseCase("SERVER_SHUTDOWN", Status.Code.UNAVAILABLE, null, null),
            new CloseCase("IDLE_TIMEOUT", Status.Code.OK, Status.Code.FAILED_PRECONDITION, null),
            new CloseCase("REPLACED", Status.Code.OK, Status.Code.FAILED_PRECONDITION, null),
            new CloseCase("PROTOCOL_ERROR", Status.Code.OK, Status.Code.FAILED_PRECONDITION, null));
    for (CloseCase expected : cases) {
      String reason = expected.reason();
      // A collector per case: a revocation must not carry over into the next case.
      FakeCollector collector = collector(FakeCollector.start());
      Connected c = connected(collector);
      collector.faults().sessionClose(reason, Duration.ofSeconds(30));
      ServerMessage close = c.session.next();
      assertThat(close.getClose().getReason()).as(reason).isEqualTo(CloseReason.valueOf(reason));
      assertThat(close.getClose().getRetryAfterS()).as(reason).isEqualTo(30);
      ScriptedAgent.Ended ended = c.session.awaitEnd();
      assertThat(ended.status().getCode()).as(reason).isEqualTo(expected.streamEnd());
      if (!ended.status().isOk()) {
        assertThat(ended.trailers().get(V1Protocol.RETRY_AFTER_MS)).as(reason).isEqualTo("30000");
      }
      if (expected.dataAfter() == null) {
        continue;
      }
      StatusRuntimeException after = failure(() -> c.send(1));
      assertThat(after.getStatus().getCode()).as(reason).isEqualTo(expected.dataAfter());
      if (expected.rejectAfter() != null) {
        assertThat(after.getTrailers().get(V1Protocol.REJECT_REASON))
            .as(reason)
            .isEqualTo(expected.rejectAfter());
        assertThat(after.getTrailers().get(V1Protocol.RETRY_AFTER_MS)).as(reason).isNull();
      } else {
        assertThat(after.getStatus().getDescription()).as(reason).isEqualTo("SESSION_REQUIRED");
      }
      ScriptedAgent.assertNoToken(
          reason + " status description", after.getStatus().getDescription(), collector.token());
    }
    FakeCollector collector = collector(FakeCollector.start());
    assertThatThrownBy(() -> collector.faults().sessionClose("NOPE", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "sessionClose(TOKEN_REVOKED_CLOSE) (11 §5 check 1, §10, 12 §4.1): a hello with the revoked"
          + " token gets ServerHello{TOKEN_REVOKED, retry_after_s 300} (ADR-0010 C-140) and"
          + " UNAUTHENTICATED with argos-reject-reason TOKEN_REVOKED and argos-retry-after-ms"
          + " 300000; another token is accepted, and clear() lifts the revocation")
  void revokedToken() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    FakeCollector collector = c.collector;
    collector.faults().sessionClose("TOKEN_REVOKED_CLOSE", Duration.ZERO);
    assertThat(c.session.next().getClose().getReason()).isEqualTo(CloseReason.TOKEN_REVOKED_CLOSE);
    ScriptedAgent.Ended ended = c.session.awaitEnd();
    assertThat(ended.status().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(ended.trailers().get(V1Protocol.RETRY_AFTER_MS)).isNull();

    Session again = new Session(c.channel, collector.token());
    again.hello("test");
    ServerHello refused = again.next().getHello();
    assertThat(refused.getAccepted()).isFalse();
    assertThat(refused.getRejectReason()).isEqualTo(RejectReason.TOKEN_REVOKED);
    assertThat(refused.getRetryAfterS()).isEqualTo(300);
    ScriptedAgent.assertNoToken("reject_message", refused.getRejectMessage(), collector.token());
    ScriptedAgent.Ended refusedEnd = again.awaitEnd();
    assertThat(refusedEnd.status().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(refusedEnd.trailers().get(V1Protocol.REJECT_REASON)).isEqualTo("TOKEN_REVOKED");
    assertThat(refusedEnd.trailers().get(V1Protocol.RETRY_AFTER_MS)).isEqualTo("300000");
    assertThat(collector.sessions())
        .filteredOn(s -> !s.accepted())
        .singleElement()
        .satisfies(s -> assertThat(s.rejectReason()).isEqualTo("TOKEN_REVOKED"));
    assertThat(failure(() -> c.send(1)).getTrailers().get(V1Protocol.REJECT_REASON))
        .isEqualTo("TOKEN_REVOKED");

    // A newly issued token (any well-formed one passes by default) is not revoked.
    String issued =
        "argos_"
            + java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("newly-issued-test-token".getBytes(StandardCharsets.US_ASCII));
    ServerHello other = new Session(c.channel, issued).accepted("test");
    assertThat(ScriptedAgent.send(c.channel, issued, other, 3, 1).getStatus())
        .isEqualTo(AckStatus.ACK_OK);

    collector.faults().clear();
    ServerHello restored = new Session(c.channel, collector.token()).accepted("test");
    assertThat(ScriptedAgent.send(c.channel, collector.token(), restored, 3, 2).getStatus())
        .isEqualTo(AckStatus.ACK_OK);
    assertThat(collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly(ReceivedBatch.REJECTED_AUTH, ReceivedBatch.ACKED, ReceivedBatch.ACKED);
  }

  @Test
  @DisplayName(
      "plaintextRejected() (11 §5 check 3): a plaintext hello gets"
          + " PLAINTEXT_NOT_ALLOWED, retry_after_s 0, FAILED_PRECONDITION; a TLS hello is accepted")
  void plaintextRejected() throws Exception {
    FakeCollector plain = collector(FakeCollector.start());
    plain.faults().plaintextRejected();
    Session session = new Session(agent.plaintext(plain), plain.token());
    session.hello("test");
    ServerHello hello = session.next().getHello();
    assertThat(hello.getRejectReason()).isEqualTo(RejectReason.PLAINTEXT_NOT_ALLOWED);
    assertThat(hello.getRetryAfterS()).isZero();
    ScriptedAgent.Ended ended = session.awaitEnd();
    assertThat(ended.status().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(ended.trailers().get(V1Protocol.REJECT_REASON)).isEqualTo("PLAINTEXT_NOT_ALLOWED");
    assertThat(plain.sessions()).singleElement().satisfies(s -> assertThat(s.plaintext()).isTrue());

    FakeCollector tls =
        collector(FakeCollector.builder().tls(true).userIdSalt(new byte[] {1, 2, 3}).start());
    tls.faults().plaintextRejected();
    Session secure = new Session(agent.tls(tls), tls.token());
    ServerHello accepted = secure.accepted("test");
    assertThat(accepted.getUserIdSalt().toByteArray())
        .as("salt over TLS (16 §6.3)")
        .containsExactly(1, 2, 3);
    assertThat(tls.sessions()).singleElement().satisfies(s -> assertThat(s.plaintext()).isFalse());
  }

  @Test
  @DisplayName(
      "rejectHello(reason[, retryAfter]): ServerHello{reason, retry_after_s} and the 11 §5 status"
          + " with argos-reject-reason and argos-retry-after-ms (INSTANCE_LIMIT: RESOURCE_EXHAUSTED,"
          + " 60 s)")
  void rejectHello() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    collector.faults().rejectHello("INSTANCE_LIMIT");
    Session session = new Session(agent.plaintext(collector), collector.token());
    session.hello("test");
    ServerHello hello = session.next().getHello();
    assertThat(hello.getRejectReason()).isEqualTo(RejectReason.INSTANCE_LIMIT);
    assertThat(hello.getRetryAfterS()).isEqualTo(60);
    ScriptedAgent.Ended ended = session.awaitEnd();
    assertThat(ended.status().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(ended.trailers().get(V1Protocol.RETRY_AFTER_MS)).isEqualTo("60000");
    assertThat(ended.trailers().get(V1Protocol.REJECT_REASON)).isEqualTo("INSTANCE_LIMIT");
    collector.faults().rejectHello("APP_NOT_REGISTERED", Duration.ofSeconds(7));
    Session pending = new Session(agent.plaintext(collector), collector.token());
    pending.hello("test");
    assertThat(pending.next().getHello().getRetryAfterS()).isEqualTo(7);
    assertThat(pending.awaitEnd().status().isOk()).isTrue();
    assertThatThrownBy(() -> collector.faults().rejectHello("NOPE"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "clockOffset(+3 s) (11 §9): server_time_ms and t2/t3 run 3 s ahead,"
          + " the first estimate and the heartbeat rounds give an offset of about +3000 ms, the ack"
          + " reports it with state WARN (|offset| >= 1 s)")
  void clockOffset() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    collector.faults().clockOffset(Duration.ofSeconds(3));
    ManagedChannel channel = agent.plaintext(collector);
    Session session = new Session(channel, collector.token());
    long sentAt = System.currentTimeMillis();
    ServerHello hello = session.accepted("test");
    assertThat(hello.getServerTimeMs() - sentAt).isBetween(2_900L, 3_200L);
    long instance = hello.getInstanceId();
    assertThat(collector.clockOffsetMs(instance)).isPresent();
    assertThat(collector.clockOffsetMs(instance).getAsLong()).isBetween(2_900L, 3_200L);
    long prevT1 = 0;
    long prevT4 = 0;
    HeartbeatAck ack = null;
    for (int round = 0; round < 4; round++) {
      long t1 = System.currentTimeMillis();
      session.heartbeat(t1, prevT1, prevT4);
      ack = session.next().getHeartbeatAck();
      prevT1 = t1;
      prevT4 = System.currentTimeMillis();
    }
    assertThat(ack.getClockOffsetMs()).isBetween(2_950, 3_050);
    assertThat(ack.getState()).isEqualTo(InstanceState.WARN);
    List<HeartbeatRecord> heartbeats = collector.awaitHeartbeats(4, Duration.ofSeconds(5));
    assertThat(heartbeats).hasSize(4);
    assertThat(heartbeats.get(0).round()).isNull();
    assertThat(heartbeats.subList(1, 4))
        .allSatisfy(
            h -> {
              assertThat(h.answered()).isTrue();
              assertThat(h.round()).isNotNull();
              assertThat(h.round().offsetMs()).isBetween(2_950L, 3_050L);
              assertThat(h.round().offsetMs())
                  .isEqualTo(
                      ClockEstimator.offset(
                          h.prevT1(), h.round().t2(), h.round().t3(), h.prevT4()));
              assertThat(h.estimateMs()).isBetween(2_950L, 3_050L);
            });
  }

  @Test
  @DisplayName(
      "partialDrop(n) (11 §6 quota): ACK_PARTIAL_DROP with dropped_items = min(n, items); the whole"
          + " batch is still recorded")
  void partialDrop() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().partialDrop(2);
    Ack ack =
        ScriptedAgent.send(
            c.channel,
            ScriptedAgent.headers(c.collector.token(), c.hello),
            BatchKind.TRANSACTIONS,
            ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 1, 5));
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(ack.getDroppedItems()).isEqualTo(2);
    assertThat(c.collector.batches())
        .singleElement()
        .satisfies(
            b -> {
              assertThat(b.droppedItems()).isEqualTo(2);
              assertThat(((TransactionBatch) b.body()).getRecordsCount()).isEqualTo(5);
            });
    assertThat(c.collector.collected()).transactions().hasSize(5);
  }

  @Test
  @DisplayName(
      "walFull() (11 §6 서버 드롭, DSN-11-09, 12 §3.2): ACK_OK with every item dropped and last_seq"
          + " unchanged; nothing is written, so a resend after clear() is new; a key already written"
          + " stays ACK_DUPLICATE")
  void walFull() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    assertThat(c.send(1).getLastSeq()).isEqualTo(1);
    c.collector.faults().walFull();
    Ack full =
        ScriptedAgent.send(
            c.channel,
            ScriptedAgent.headers(c.collector.token(), c.hello),
            BatchKind.TRANSACTIONS,
            ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 2, 4));
    assertThat(full.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(full.getDroppedItems()).isEqualTo(4);
    assertThat(full.getLastSeq()).isEqualTo(1);
    assertThat(c.send(1).getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(c.collector.batches())
        .extracting(ReceivedBatch::outcome)
        .containsExactly(ReceivedBatch.ACKED, ReceivedBatch.NOT_WRITTEN, ReceivedBatch.DUPLICATE);
    assertThat(c.collector.collected()).transactions().hasSize(1);
    assertThat(c.collector.counts().get(BatchKind.TRANSACTIONS).failed()).isZero();
    c.collector.faults().clear();
    Ack resent = c.send(2);
    assertThat(resent.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(resent.getDroppedItems()).isZero();
    assertThat(resent.getLastSeq()).isEqualTo(2);
    assertThat(c.collector.collected()).transactions().hasSize(2);
  }

  @Test
  @DisplayName(
      "pauseSessionReads() (isReady back pressure): the client's stream stops being ready"
          + " while nothing more is read; resumeSessionReads() reads every message, in order;"
          + " data calls are not affected")
  void pauseSessionReads() throws Exception {
    Connected c =
        connected(collector(FakeCollector.builder().flowControlWindow(64 * 1024).start()));
    AgentMessage message =
        AgentMessage.newBuilder()
            .setDictionaryEntries(
                DictionaryEntries.newBuilder()
                    .addEntries(
                        DictionaryEntry.newBuilder()
                            .setKind(DictionaryKind.SQL_TEXT)
                            .setHash(7)
                            .setText("x".repeat(8 * 1024))))
            .build();
    c.collector.faults().pauseSessionReads();
    int sent = 0;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (c.session.isReady()) {
        c.session.send(message);
        sent++;
        // At most the window and gRPC's 32 KiB buffer fit: a stream that stays ready has failed.
        assertThat(sent).as("the stream must stop being ready").isLessThan(64);
      } else {
        // Not ready can pass while gRPC flushes; with nothing read any more it must stay so.
        Thread.sleep(200);
        if (!c.session.isReady()) {
          break;
        }
      }
    }
    assertThat(c.session.isReady()).as("not ready after %d messages", sent).isFalse();
    int read = c.collector.dictionaryEntries().size();
    assertThat(read).as("the collector held the stream back").isLessThan(sent);
    // A data call is a stream of its own: it does not wait for the session stream.
    assertThat(c.send(1).getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(c.collector.dictionaryEntries()).hasSize(read);

    c.collector.faults().resumeSessionReads();
    int total = sent;
    awaitTrue(() -> c.collector.dictionaryEntries().size() == total, "every message read");
    awaitTrue(c.session::isReady, "ready again");
    c.session.send(message);
    awaitTrue(() -> c.collector.dictionaryEntries().size() == total + 1, "read on");
  }

  @Test
  @DisplayName(
      "11 §2 gzip: a batch compressed with gRPC gzip is recorded and acknowledged, with its"
          + " serialized (uncompressed) size")
  void gzipBatch() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    TransactionBatch batch =
        (TransactionBatch) ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 1, 200);
    Ack ack =
        AgentServiceGrpc.newBlockingStub(
                ScriptedAgent.with(c.channel, ScriptedAgent.headers(c.collector.token(), c.hello)))
            .withCompression("gzip")
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .sendTransactions(batch);
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(c.collector.batches(BatchKind.TRANSACTIONS))
        .singleElement()
        .satisfies(b -> assertThat(b.bytes()).isEqualTo(batch.getSerializedSize()));
    assertThat(c.collector.collected()).transactions().hasSize(200);
  }

  @Test
  @DisplayName(
      "11 §5 order with injected reasons: an earlier check wins (APP_MISMATCH over"
          + " PLAINTEXT_NOT_ALLOWED, PROTOCOL_UNSUPPORTED over INSTANCE_LIMIT)")
  void helloOrder() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    collector.faults().plaintextRejected().rejectHello("APP_MISMATCH");
    ManagedChannel channel = agent.plaintext(collector);
    Session session = new Session(channel, collector.token());
    session.hello("test");
    assertThat(session.next().getHello().getRejectReason()).isEqualTo(RejectReason.APP_MISMATCH);
    collector.faults().clear();
    collector.faults().rejectHello("INSTANCE_LIMIT");
    Session version = new Session(channel, collector.token());
    version.hello("shop", "test", "host:1", 3, 99, 0);
    assertThat(version.next().getHello().getRejectReason())
        .isEqualTo(RejectReason.PROTOCOL_UNSUPPORTED);
    assertThat(FaultInjector.rejectionOf("SERVER_SHUTTING_DOWN").check()).isGreaterThan(6);
  }

  @Test
  @DisplayName(
      "throttle(rate, backoff) (11 §6 back pressure): every ack carries Ack.throttle with only the"
          + " fields given (11 §4.4 optional)")
  void throttle() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().throttle(null, Duration.ofMillis(250));
    Ack ack = c.send(1);
    assertThat(ack.hasThrottle()).isTrue();
    assertThat(ack.getThrottle().getSendBackoffMs()).isEqualTo(250);
    assertThat(ack.getThrottle().hasProfileNormalRate()).isFalse();
    c.collector.faults().clear();
    assertThat(c.send(2).hasThrottle()).isFalse();
  }

  @Test
  @DisplayName(
      "11 §6 dedup: a repeated (instance, epoch, kind, seq) gets ACK_DUPLICATE with last_seq; it is"
          + " recorded (flagged) but is not a transaction again")
  void duplicate() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.send(1);
    c.send(2);
    Ack again = c.send(1);
    assertThat(again.getStatus()).isEqualTo(AckStatus.ACK_DUPLICATE);
    assertThat(again.getLastSeq()).isEqualTo(2);
    assertThat(c.collector.seqs(BatchKind.TRANSACTIONS)).containsExactly(1L, 2L, 1L);
    assertThat(c.collector.batches())
        .extracting(ReceivedBatch::duplicate)
        .containsExactly(false, false, true);
    assertThat(c.collector.collected()).transactions().hasSize(2);
    assertThat(c.collector.counts().get(BatchKind.TRANSACTIONS).duplicates()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "maxConcurrentUnaryCalls(): the high-water mark of concurrently served data calls counts from"
          + " arrival to answer (6 parallel calls under latency give 6; reset starts a new mark)")
  void concurrency() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.collector.faults().latency(Duration.ofMillis(600));
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Ack>> calls = new ArrayList<>();
      for (int i = 1; i <= 6; i++) {
        long seq = i;
        calls.add(pool.submit(() -> c.send(seq)));
      }
      for (Future<Ack> call : calls) {
        assertThat(call.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(AckStatus.ACK_OK);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(c.collector.maxConcurrentUnaryCalls()).isEqualTo(6);
    assertThat(c.collector.concurrentUnaryCalls()).isZero();
    c.collector.resetMaxConcurrentUnaryCalls();
    c.collector.faults().clear();
    c.send(7);
    assertThat(c.collector.maxConcurrentUnaryCalls()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "byes() and flushAfterBye(): AgentBye is recorded with its last seqs, batches after it are the"
          + " shutdown flush (flushAfterBye), and the session stays valid after the agent ends its"
          + " stream (11 §6 종료)")
  void byeAndFlush() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    c.send(1);
    c.session.send(
        AgentMessage.newBuilder()
            .setBye(AgentBye.newBuilder().setReason(ByeReason.SHUTDOWN).setLastTxSeq(3))
            .build());
    c.session.complete();
    List<ByeInfo> byes = awaitList(() -> c.collector.byes(), 1);
    assertThat(byes)
        .singleElement()
        .satisfies(
            b -> {
              assertThat(b.reason()).isEqualTo("SHUTDOWN");
              assertThat(b.lastTxSeq()).isEqualTo(3);
              assertThat(b.instanceId()).isEqualTo(1);
            });
    assertThat(c.collector.flushAfterBye(byes.get(0))).isEmpty();
    Thread.sleep(20);
    c.send(2);
    c.send(3);
    assertThat(c.collector.batchesAfter(byes.get(0).receivedAt()))
        .extracting(ReceivedBatch::seq)
        .containsExactly(2L, 3L);
    assertThat(c.collector.flushAfterBye(byes.get(0)))
        .hasValueSatisfying(d -> assertThat(d).isPositive().isLessThan(Duration.ofSeconds(1)));
  }

  @Test
  @DisplayName(
      "11 §5 check 7, 12 §4.1: a hello for an instance with an open session replaces it (SessionClose"
          + " REPLACED, OK) whatever its agent_epoch; the old session id is refused afterwards, also"
          + " when its stream had already ended")
  void replaced() throws Exception {
    FakeCollector collector = collector(FakeCollector.start());
    ManagedChannel channel = agent.plaintext(collector);
    Session a = new Session(channel, collector.token());
    a.hello("shop", "test", "host:1", 11, 1, 0);
    ServerHello first = a.next().getHello();
    assertThat(first.getAccepted()).isTrue();
    Session b = new Session(channel, collector.token());
    b.hello("shop", "test", "host:1", 11, 1, 0);
    ServerHello second = b.next().getHello();
    assertThat(second.getInstanceId()).isEqualTo(1);
    assertThat(a.next().getClose().getReason()).as("same epoch").isEqualTo(CloseReason.REPLACED);
    assertThat(a.awaitEnd().status().isOk()).isTrue();
    assertThat(failure(() -> ScriptedAgent.send(channel, collector.token(), first, 11, 1)))
        .satisfies(e -> assertThat(e.getStatus().getDescription()).isEqualTo("SESSION_REQUIRED"));
    assertThat(ScriptedAgent.send(channel, collector.token(), second, 11, 1).getStatus())
        .isEqualTo(AckStatus.ACK_OK);
    Session other = new Session(channel, collector.token());
    other.hello("shop", "test", "host:1", 12, 1, 0);
    ServerHello third = other.next().getHello();
    assertThat(third.getInstanceId()).isEqualTo(1);
    assertThat(b.next().getClose().getReason()).as("other epoch").isEqualTo(CloseReason.REPLACED);
    assertThat(b.awaitEnd().status().isOk()).isTrue();
    assertThat(collector.openSessionCount()).isEqualTo(1);
    // The stream ended first: the session is valid for the flush, until the next hello.
    other.complete();
    other.awaitEnd();
    assertThat(ScriptedAgent.send(channel, collector.token(), third, 12, 1).getStatus())
        .isEqualTo(AckStatus.ACK_OK);
    Session again = new Session(channel, collector.token());
    again.hello("shop", "test", "host:1", 12, 1, 0);
    assertThat(again.next().getHello().getAccepted()).isTrue();
    assertThat(failure(() -> ScriptedAgent.send(channel, collector.token(), third, 12, 2)))
        .satisfies(e -> assertThat(e.getStatus().getDescription()).isEqualTo("SESSION_REQUIRED"));
    assertThat(other.received).as("nothing after the stream ended").isEmpty();
  }

  @Test
  @DisplayName(
      "18 §5.3 no receive limit: a batch above gRPC's default 4 MiB is recorded as sent and"
          + " acknowledged")
  void largeBatch() throws Exception {
    Connected c = connected(collector(FakeCollector.start()));
    TransactionBatch small =
        (TransactionBatch) ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 11, 1);
    TransactionBatch large =
        small.toBuilder()
            .setRecords(0, small.getRecords(0).toBuilder().setErrorMessage("x".repeat(5 << 20)))
            .build();
    assertThat(large.getSerializedSize()).isGreaterThan(4 << 20);
    Ack ack =
        AgentServiceGrpc.newBlockingStub(
                ScriptedAgent.with(c.channel, ScriptedAgent.headers(c.collector.token(), c.hello)))
            .withDeadlineAfter(30, TimeUnit.SECONDS)
            .sendTransactions(large);
    assertThat(ack.getLastSeq()).isEqualTo(11);
    assertThat(ack.getStatus()).isEqualTo(AckStatus.ACK_OK);
    assertThat(c.collector.batches(BatchKind.TRANSACTIONS))
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
    assertThat(collector.containerAddress()).isEqualTo("host.docker.internal:" + collector.port());
    ManagedChannel first = agent.tls(collector);
    ServerHello hello = new Session(first, collector.token()).accepted("test");
    assertThat(ScriptedAgent.send(first, collector.token(), hello, 3, 1).getLastSeq()).isEqualTo(1);
    // The certificate also names host.docker.internal (an agent in a container).
    ServerHello docker =
        new Session(
                agent.tls(collector, collector.trustPem(), "host.docker.internal"),
                collector.token())
            .accepted("test");
    assertThat(docker.getAccepted()).isTrue();

    Path pem = collector.trustPem();
    collector.faults().plaintextOnly();
    assertThat(collector.tls()).isFalse();
    ManagedChannel tlsAfter = agent.tls(collector, pem, null);
    StatusRuntimeException handshake =
        failure(() -> ScriptedAgent.send(tlsAfter, collector.token(), hello, 3, 2));
    assertThat(handshake.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    // The TLS handshake met a plaintext server: not a refused connection.
    assertThat(causes(handshake))
        .as("cause chain of %s", handshake)
        .anyMatch(cause -> cause instanceof javax.net.ssl.SSLException)
        .noneMatch(cause -> cause instanceof java.net.ConnectException);
    ManagedChannel plain = agent.plaintext(collector);
    ServerHello plainHello = new Session(plain, collector.token()).accepted("test");
    assertThat(ScriptedAgent.send(plain, collector.token(), plainHello, 3, 3).getLastSeq())
        .isEqualTo(3);

    collector.faults().wrongHostCertificate();
    assertThat(collector.tls()).isTrue();
    Path wrongHostPem = collector.trustPem();
    // Trusted certificate, wrong name: 127.0.0.1 (and localhost) are not in its SAN.
    for (String authority : java.util.Arrays.asList(null, "localhost")) {
      ManagedChannel wrongHost = agent.tls(collector, wrongHostPem, authority);
      StatusRuntimeException e =
          failure(() -> new SessionProbe(wrongHost, collector.token()).run());
      assertThat(e.getStatus().getCode())
          .as("authority %s", authority)
          .isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(causes(e))
          .as("cause chain of %s", e)
          .anyMatch(
              cause ->
                  (cause instanceof javax.net.ssl.SSLHandshakeException
                          || cause instanceof java.security.cert.CertificateException)
                      && String.valueOf(cause.getMessage()).contains("No subject alternative"));
    }
    // Positive control: the same certificate and trust anchor with the name it was made for.
    ManagedChannel named = agent.tls(collector, wrongHostPem, "wrong.example");
    ServerHello namedHello = new Session(named, collector.token()).accepted("test");
    assertThat(ScriptedAgent.send(named, collector.token(), namedHello, 3, 5).getLastSeq())
        .isEqualTo(5);
    assertThat(collector.batches(BatchKind.TRANSACTIONS))
        .filteredOn(ReceivedBatch::acked)
        .extracting(ReceivedBatch::seq)
        .containsExactly(1L, 3L, 5L);
  }

  /** A data call without a session (only the connection matters). */
  record SessionProbe(ManagedChannel channel, String token) implements Runnable {
    @Override
    public void run() {
      ScriptedAgent.send(
          channel,
          ScriptedAgent.headers(token, null, null),
          BatchKind.TRANSACTIONS,
          ScriptedAgent.batch(BatchKind.TRANSACTIONS, 1, 3, 4, 1));
    }
  }

  static <T> List<T> awaitList(java.util.function.Supplier<List<T>> list, int count)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    List<T> current = list.get();
    while (current.size() < count && System.nanoTime() < deadline) {
      Thread.sleep(20);
      current = list.get();
    }
    return current;
  }

  static void awaitTrue(java.util.function.BooleanSupplier condition, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(condition.getAsBoolean()).as(what).isTrue();
  }
}
