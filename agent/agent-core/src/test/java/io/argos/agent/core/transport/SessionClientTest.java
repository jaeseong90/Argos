/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.log.LogCapture;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.transport.TransportFakes.FakeOpener;
import io.argos.agent.core.transport.TransportFakes.FakeStream;
import io.argos.agent.core.transport.TransportFakes.ManualClock;
import io.argos.agent.core.transport.TransportFakes.ManualLoop;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.proto.v1.Throttle;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The session stream of 11 §5, §7, §8, §9, §10 (10 §10.4) on a manual loop with fake streams. */
@Tag("req:DSN-02-11")
class SessionClientTest {
  private final ManualLoop loop = new ManualLoop();
  private final FakeOpener opener = new FakeOpener();
  private final ManualClock clock = new ManualClock();
  private final Dictionary dictionary = new Dictionary();
  private final TransportCounters counters = new TransportCounters();
  private final List<ServerHello> established = new ArrayList<ServerHello>();
  private final List<Throttle> throttles = new ArrayList<Throttle>();
  private int lost;
  private int stoppedCalls;

  private final SessionClient.Listener listener =
      new SessionClient.Listener() {
        @Override
        public void established(ServerHello hello) {
          established.add(hello);
        }

        @Override
        public void lost() {
          lost++;
        }

        @Override
        public void throttle(Throttle throttle) {
          throttles.add(throttle);
        }

        @Override
        public void stopped() {
          stoppedCalls++;
        }
      };

  private SessionClient client(boolean plaintext, boolean skipVerify) {
    return client(
        new SessionClient.HelloSource() {
          @Override
          public AgentHello hello() {
            return AgentHello.newBuilder().setAppName("orders").build();
          }
        },
        plaintext,
        skipVerify);
  }

  private SessionClient client(
      SessionClient.HelloSource helloSource, boolean plaintext, boolean skipVerify) {
    return new SessionClient(
        loop,
        opener,
        helloSource,
        new SessionClient.HeartbeatSource() {
          @Override
          public Heartbeat.Builder fill(Heartbeat.Builder heartbeat) {
            return heartbeat.setActiveTransactions(3).setDroppedTotal(9).setLastTxSeq(11);
          }
        },
        listener,
        new RejectingDispatcher(AgentConfig.of(Collections.<String, String>emptyMap()), "hash-1"),
        new DictionaryCache(dictionary),
        counters,
        new ClockSync(clock),
        new Backoff(
            new Backoff.Jitter() {
              @Override
              public double next() {
                return 0.5; // no jitter: exact delays
              }
            }),
        plaintext,
        skipVerify);
  }

  private SessionClient started() {
    SessionClient client = client(false, false);
    client.start();
    loop.run();
    return client;
  }

  private static ServerMessage accepted(int instance, String session) {
    return accepted(instance, session, 30);
  }

  private static ServerMessage accepted(int instance, String session, int heartbeat) {
    return ServerMessage.newBuilder()
        .setHello(
            ServerHello.newBuilder()
                .setAccepted(true)
                .setInstanceId(instance)
                .setSessionId(session)
                .setHeartbeatIntervalS(heartbeat))
        .build();
  }

  private static ServerMessage rejected(RejectReason reason, int retryAfter) {
    return ServerMessage.newBuilder()
        .setHello(
            ServerHello.newBuilder()
                .setAccepted(false)
                .setRejectReason(reason)
                .setRetryAfterS(retryAfter))
        .build();
  }

  private static ServerMessage close(CloseReason reason, int retryAfter) {
    return ServerMessage.newBuilder()
        .setClose(SessionClose.newBuilder().setReason(reason).setRetryAfterS(retryAfter))
        .build();
  }

  private SessionClient establishedClient() {
    SessionClient client = started();
    opener.last().server(accepted(7, "session-1"));
    return client;
  }

  /**
   * The production hello source: {@code app.name} and {@code app.version}, no {@code app.instance}
   * (instance name {@code host:pid}), configuration hash {@code h}.
   */
  private static AgentIdentity identity() {
    java.util.Map<String, String> values = new java.util.HashMap<String, String>();
    values.put("app.name", "orders");
    values.put("app.version", "1.0");
    return new AgentIdentity(
        AgentConfig.of(values), "1.0", "h", Collections.<String>emptyList(), 1L, false);
  }

  /** The {@code InstanceUpdate} messages written on {@code stream}. */
  private static List<InstanceUpdate> updates(FakeStream stream) {
    List<InstanceUpdate> out = new ArrayList<InstanceUpdate>();
    for (AgentMessage message : stream.sentOf(AgentMessage.BodyCase.INSTANCE_UPDATE)) {
      out.add(message.getInstanceUpdate());
    }
    return out;
  }

  /** Ends the current stream as a network failure would and opens the next one (1 s back-off). */
  private void reconnect() {
    opener.last().close(Status.UNAVAILABLE);
    loop.advance(1_000);
  }

  @Test
  @DisplayName("11 §5: the stream opens with AgentHello; an accepted ServerHello keeps the ids")
  void hello() {
    SessionClient client = started();
    FakeStream stream = opener.last();
    assertThat(stream.sent).hasSize(1);
    assertThat(stream.sent.get(0).getBodyCase()).isEqualTo(AgentMessage.BodyCase.HELLO);
    assertThat(stream.sent.get(0).getHello().getAppName()).isEqualTo("orders");
    assertThat(client.established()).isFalse();

    stream.server(accepted(7, "session-1"));
    assertThat(client.established()).isTrue();
    assertThat(client.instanceId()).isEqualTo(7);
    assertThat(client.sessionId()).isEqualTo("session-1");
    assertThat(established).hasSize(1);
    assertThat(counters.established()).isEqualTo(1);
    assertThat(counters.up()).isTrue();
  }

  @Test
  @DisplayName("instance ids are unsigned 32-bit values")
  void unsignedInstance() {
    SessionClient client = started();
    opener.last().server(accepted(-2, "session-1"));
    assertThat(client.instanceId()).isEqualTo(0xFFFF_FFFEL);
  }

  @Test
  @DisplayName(
      "11 §5: a rejection with retry_after_s 0 is one refusal: no more connections, the transport"
          + " stops for good")
  void rejectedForGood() {
    SessionClient client = started();
    opener.last().server(rejected(RejectReason.PLAINTEXT_NOT_ALLOWED, 0));
    assertThat(client.established()).isFalse();
    assertThat(opener.last().cancelled).isNotNull();
    assertThat(stoppedCalls).isEqualTo(1);
    loop.advance(3_600_000);
    assertThat(opener.streams).as("no retry").hasSize(1);
    assertThat(counters.rejected()).isEqualTo(1);
  }

  @Test
  @DisplayName("11 §5: a rejection with retry_after_s reconnects after that time")
  void rejectedRetry() {
    started();
    opener.last().server(rejected(RejectReason.APP_NOT_REGISTERED, 300));
    assertThat(stoppedCalls).isZero();
    loop.advance(299_000);
    assertThat(opener.streams).hasSize(1);
    loop.advance(1_000);
    assertThat(opener.streams).hasSize(2);
    // Every retry counts; the log says it once per reason (checked end to end in agent-testing).
    opener.last().server(rejected(RejectReason.APP_NOT_REGISTERED, 300));
    assertThat(counters.rejected()).isEqualTo(2);
    loop.advance(300_000);
    assertThat(opener.streams).hasSize(3);
  }

  @Test
  @Tag("req:SEC-01")
  @DisplayName(
      "ADR-0010 C-102: a token rejection waits the fixed 5 minutes whatever retry_after_s says, and"
          + " the agent returns when the token is accepted again")
  void tokenRejection() {
    started();
    for (int attempt = 1; attempt <= 3; attempt++) {
      opener.last().server(rejected(RejectReason.TOKEN_REVOKED, attempt == 2 ? 3600 : 0));
      assertThat(stoppedCalls).isZero();
      assertThat(loop.nextDelay()).isEqualTo(Backoff.UNAUTHENTICATED_MILLIS);
      loop.advance(Backoff.UNAUTHENTICATED_MILLIS);
      assertThat(opener.streams).hasSize(attempt + 1);
    }
    opener.last().server(accepted(7, "session-2"));
    assertThat(established).hasSize(1);
  }

  @Test
  @DisplayName("ADR-0010 C-102: UNAUTHENTICATED without a hello also waits the fixed 5 minutes")
  void unauthenticatedStream() {
    started();
    opener.last().close(Status.UNAUTHENTICATED);
    assertThat(loop.nextDelay()).isEqualTo(Backoff.UNAUTHENTICATED_MILLIS);
    assertThat(counters.rejected()).isEqualTo(1);
  }

  @Test
  @DisplayName("11 §10: FAILED_PRECONDITION without a hello is not retried")
  void failedPreconditionStream() {
    started();
    opener.last().close(Status.FAILED_PRECONDITION.withDescription("plaintext_not_allowed"));
    assertThat(stoppedCalls).isEqualTo(1);
    loop.advance(3_600_000);
    assertThat(opener.streams).hasSize(1);
  }

  @Test
  @DisplayName("10 §10.2: a lost stream reconnects at 1 s, 2 s, 4 s ...; a new session resets it")
  void reconnectBackoff() {
    started();
    long[] delays = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
    for (int i = 0; i < delays.length; i++) {
      opener.last().close(Status.UNAVAILABLE);
      assertThat(loop.nextDelay()).as("attempt %d", i).isEqualTo(delays[i]);
      loop.advance(delays[i]);
      assertThat(opener.streams).hasSize(i + 2);
    }
    opener.last().server(accepted(7, "session-1"));
    opener.last().close(Status.UNAVAILABLE);
    assertThat(lost).isEqualTo(1);
    assertThat(loop.nextDelay()).isEqualTo(1_000);
  }

  @Test
  @DisplayName("11 §10: argos-retry-after-ms on a failed stream waits at least that long")
  void retryAfterTrailer() {
    started();
    Metadata trailers = new Metadata();
    trailers.put(Sender.RETRY_AFTER_MS, "60000");
    opener.last().close(Status.RESOURCE_EXHAUSTED, trailers);
    assertThat(loop.nextDelay()).isEqualTo(60_000);
  }

  @Test
  @Tag("req:C-19")
  @DisplayName("C-19: the server's idle close (SessionClose IDLE_TIMEOUT) is a normal reconnect")
  void idleClose() {
    SessionClient client = establishedClient();
    opener.last().server(close(CloseReason.IDLE_TIMEOUT, 0));
    assertThat(client.established()).isFalse();
    assertThat(lost).isEqualTo(1);
    assertThat(stoppedCalls).isZero();
    loop.advance(1_000);
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @DisplayName("11 §5, §10: SERVER_SHUTDOWN waits at least retry_after_s, else the back-off step")
  void serverClose() {
    establishedClient();
    opener.last().server(close(CloseReason.SERVER_SHUTDOWN, 20));
    assertThat(loop.nextDelay()).isEqualTo(20_000);
    loop.advance(20_000);
    assertThat(opener.streams).hasSize(2);
    opener.last().server(accepted(7, "session-2"));
    opener.last().server(close(CloseReason.SERVER_SHUTDOWN, 0));
    assertThat(loop.nextDelay()).as("the back-off step after an accepted hello").isEqualTo(1_000);
  }

  @Test
  @DisplayName(
      "12 §16 (asks of 10), 11 §5 check 7: SessionClose REPLACED is not retried: one WARN about the"
          + " duplicate instance, the transport stops, no reconnect is scheduled")
  void replacedStops() {
    try (LogCapture log = LogCapture.start("INFO")) {
      SessionClient client = establishedClient();
      opener.last().server(close(CloseReason.REPLACED, 0));
      assertThat(client.established()).isFalse();
      assertThat(lost).isEqualTo(1);
      assertThat(stoppedCalls).isEqualTo(1);
      assertThat(loop.nextDelay()).as("no reconnect scheduled").isEqualTo(-1);
      loop.advance(3_600_000);
      assertThat(opener.streams).as("no new stream in an hour").hasSize(1);
      List<String> warnings = log.linesAt("WARN");
      assertThat(warnings).hasSize(1);
      assertThat(warnings.get(0))
          .contains("SessionClose REPLACED")
          .contains("stops sending until it is restarted")
          .contains("app.instance");
    }
  }

  @Test
  @DisplayName(
      "11 §5 check 7: a REPLACED for a stream this agent already ended itself (its own reconnect)"
          + " is ignored: the agent's own new session is never stopped by it")
  void replacedOfOwnOldStreamIgnored() {
    SessionClient client = establishedClient();
    FakeStream old = opener.last();
    client.reestablish("session-1");
    loop.run();
    assertThat(opener.streams).hasSize(2);
    FakeStream fresh = opener.last();
    // The collector replaces the old stream when the new hello arrives (FakeCollector, 12 §4.1).
    old.server(close(CloseReason.REPLACED, 0));
    old.close(Status.OK);
    fresh.server(accepted(7, "session-2"));
    assertThat(stoppedCalls).isZero();
    assertThat(fresh.cancelled).isNull();
    assertThat(client.established()).isTrue();
    assertThat(client.sessionId()).isEqualTo("session-2");
  }

  @Test
  @DisplayName(
      "10 §10.2: before every wait for the Backoff the channel goes idle (no connection, no own"
          + " reconnects or name lookups until the next stream): stream failure, server close,"
          + " rejected hello, hello timeout, failing open")
  void idleBeforeEveryWait() {
    started();
    assertThat(opener.events).containsExactly("open");
    opener.last().close(Status.UNAVAILABLE);
    assertThat(opener.events).containsExactly("open", "idle");
    loop.advance(1_000);
    assertThat(opener.events).containsExactly("open", "idle", "open");

    opener.last().server(accepted(7, "session-1"));
    opener.last().server(close(CloseReason.IDLE_TIMEOUT, 0));
    assertThat(opener.events).endsWith("open", "idle");
    loop.advance(1_000);
    assertThat(opener.events).endsWith("idle", "open");

    opener.last().server(rejected(RejectReason.APP_NOT_REGISTERED, 5));
    assertThat(opener.events).endsWith("open", "idle");
    loop.advance(5_000);
    assertThat(opener.events).endsWith("idle", "open");

    loop.advance(SessionClient.HELLO_TIMEOUT_MILLIS);
    assertThat(opener.events).as("hello timeout").endsWith("open", "idle");

    opener.failure = new IllegalStateException("no channel");
    loop.advance(2_000);
    assertThat(opener.events).as("failing open").endsWith("open", "idle");
    opener.failure = null;
    loop.advance(4_000);
    assertThat(opener.events).endsWith("idle", "open");
    assertThat(opener.idles).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "11 §3: SESSION_REQUIRED (reestablish) keeps the connection: the server only forgot the"
          + " session, the new stream opens at once on the same channel")
  void reestablishKeepsConnection() {
    SessionClient client = establishedClient();
    client.reestablish("session-1");
    loop.run();
    assertThat(opener.idles).isZero();
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @Tag("req:SEC-01")
  @DisplayName("ADR-0010 C-102: SessionClose TOKEN_REVOKED_CLOSE waits the fixed 5 minutes")
  void revokedClose() {
    establishedClient();
    opener.last().server(close(CloseReason.TOKEN_REVOKED_CLOSE, 0));
    assertThat(loop.nextDelay()).isEqualTo(Backoff.UNAUTHENTICATED_MILLIS);
    assertThat(counters.rejected()).isEqualTo(1);
  }

  @Test
  @Tag("req:SEC-01")
  @DisplayName("ADR-0010 C-140: SessionClose TOKEN_REVOKED_CLOSE ignores a larger retry_after_s")
  void revokedCloseIgnoresRetryAfter() {
    establishedClient();
    opener.last().server(close(CloseReason.TOKEN_REVOKED_CLOSE, 3600));
    assertThat(loop.nextDelay()).isEqualTo(Backoff.UNAUTHENTICATED_MILLIS);
  }

  @Test
  @DisplayName("a late status after a handled SessionClose schedules nothing more")
  void closeThenStatus() {
    establishedClient();
    FakeStream first = opener.last();
    first.server(close(CloseReason.SERVER_SHUTDOWN, 0));
    long delay = loop.nextDelay();
    first.close(Status.OK);
    assertThat(loop.nextDelay()).isEqualTo(delay);
    loop.advance(delay);
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @DisplayName("no ServerHello within the timeout: the stream is cancelled and opened again")
  void helloTimeout() {
    started();
    FakeStream first = opener.last();
    loop.advance(SessionClient.HELLO_TIMEOUT_MILLIS);
    assertThat(first.cancelled).isNotNull();
    loop.advance(1_000);
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @DisplayName("a failing open is retried with the back-off")
  void openFailure() {
    opener.failure = new IllegalStateException("no channel");
    SessionClient client = client(false, false);
    client.start();
    loop.run();
    assertThat(opener.streams).isEmpty();
    opener.failure = null;
    loop.advance(1_000);
    assertThat(opener.streams).hasSize(1);
  }

  @Test
  @Tag("req:D-02")
  @DisplayName(
      "11 §9: heartbeat every heartbeat_interval_s with t1, the last round's prev_t1 and prev_t4,"
          + " active, dropped_total, last seqs; the ack feeds the offset gauge")
  void heartbeats() {
    SessionClient client = started();
    FakeStream stream = opener.last();
    stream.server(accepted(7, "session-1", 5));
    assertThat(counters.snapshot()).doesNotContainKey(TransportCounters.CLOCK_OFFSET);

    clock.advanceMillis(5_000);
    loop.advance(5_000);
    List<AgentMessage> sent = stream.sentOf(AgentMessage.BodyCase.HEARTBEAT);
    assertThat(sent).hasSize(1);
    Heartbeat first = sent.get(0).getHeartbeat();
    assertThat(first.getT1Ms()).isEqualTo(clock.millis);
    assertThat(first.getPrevT1Ms()).isZero();
    assertThat(first.getPrevT4Ms()).isZero();
    assertThat(first.getActiveTransactions()).isEqualTo(3);
    assertThat(first.getDroppedTotal()).isEqualTo(9);
    assertThat(first.getLastTxSeq()).isEqualTo(11);

    clock.advanceMillis(40);
    stream.server(
        ServerMessage.newBuilder()
            .setHeartbeatAck(
                HeartbeatAck.newBuilder()
                    .setT1Ms(first.getT1Ms())
                    .setT2Ms(1)
                    .setT3Ms(2)
                    .setClockOffsetMs(-3_000))
            .build());
    long t4 = clock.millis;
    assertThat(counters.snapshot().get(TransportCounters.CLOCK_OFFSET)).isEqualTo(-3_000L);

    clock.advanceMillis(5_000);
    loop.advance(5_000);
    Heartbeat second = stream.sentOf(AgentMessage.BodyCase.HEARTBEAT).get(1).getHeartbeat();
    assertThat(second.getPrevT1Ms()).isEqualTo(first.getT1Ms());
    assertThat(second.getPrevT4Ms()).isEqualTo(t4);
    assertThat(client.established()).isTrue();
  }

  @Test
  @DisplayName("11 §9: no heartbeat after the session ended; a new session starts the rounds again")
  void heartbeatStops() {
    establishedClient();
    FakeStream first = opener.last();
    first.close(Status.UNAVAILABLE);
    loop.advance(1_000);
    int before = first.sent.size();
    loop.advance(120_000);
    assertThat(first.sent).hasSize(before);
  }

  @Test
  @DisplayName("11 §7: DictionaryRequest is answered from the cache; a miss is skipped silently")
  void dictionaryRequest() {
    establishedClient();
    long known = dictionary.id(DictKind.SERVICE, "GET /orders");
    FakeStream stream = opener.last();
    stream.server(
        ServerMessage.newBuilder()
            .setDictionaryRequest(
                DictionaryRequest.newBuilder()
                    .addRefs(
                        DictionaryRef.newBuilder().setKind(DictionaryKind.SERVICE).setHash(known))
                    .addRefs(
                        DictionaryRef.newBuilder()
                            .setKind(DictionaryKind.SERVICE)
                            .setHash(123456L)))
            .build());
    List<AgentMessage> answers = stream.sentOf(AgentMessage.BodyCase.DICTIONARY_ENTRIES);
    assertThat(answers).hasSize(1);
    assertThat(answers.get(0).getDictionaryEntries().getEntriesList())
        .extracting(e -> e.getText())
        .containsExactly("GET /orders");

    stream.sent.clear();
    stream.server(
        ServerMessage.newBuilder()
            .setDictionaryRequest(
                DictionaryRequest.newBuilder()
                    .addRefs(
                        DictionaryRef.newBuilder().setKind(DictionaryKind.SERVICE).setHash(999L)))
            .build());
    assertThat(stream.sent).as("no answer for a miss").isEmpty();
  }

  @Test
  @Tag("req:SEC-23")
  @DisplayName(
      "C-118, WO-11: ConfigPush keys are evaluated against the hard cap and answered NOT_ALLOWED,"
          + " nothing applied; pushed values never appear in the answer")
  void configPush() {
    establishedClient();
    FakeStream stream = opener.last();
    stream.server(
        ServerMessage.newBuilder()
            .setConfigPush(
                ConfigPush.newBuilder()
                    .setChangeId("c-1")
                    .putValues("sampling.profile.normal-rate", "0.5")
                    .putValues("server.address", "evil.example:1"))
            .build());
    List<AgentMessage> answers = stream.sentOf(AgentMessage.BodyCase.CONFIG_APPLY_RESULT);
    assertThat(answers).hasSize(1);
    assertThat(answers.get(0).getConfigApplyResult().getChangeId()).isEqualTo("c-1");
    assertThat(answers.get(0).getConfigApplyResult().getAppliedKeysList()).isEmpty();
    assertThat(answers.get(0).getConfigApplyResult().getEffectiveConfigHash()).isEqualTo("hash-1");
    assertThat(answers.get(0).getConfigApplyResult().getRejectedList())
        .hasSize(2)
        .allSatisfy(key -> assertThat(key.getCode()).isEqualTo(RejectCode.NOT_ALLOWED));
    assertThat(answers.get(0).toString()).doesNotContain("evil.example");
  }

  @Test
  @Tag("req:SEC-24")
  @DisplayName("WO-11: every Command is answered REJECTED and nothing runs")
  void command() {
    establishedClient();
    FakeStream stream = opener.last();
    stream.server(
        ServerMessage.newBuilder()
            .setCommand(Command.newBuilder().setCommandId("cmd-1").setType(CommandType.THREAD_DUMP))
            .build());
    stream.server(
        ServerMessage.newBuilder()
            .setCommand(Command.newBuilder().setCommandId("cmd-2").setTypeValue(99))
            .build());
    List<AgentMessage> answers = stream.sentOf(AgentMessage.BodyCase.COMMAND_RESULT);
    assertThat(answers).hasSize(2);
    assertThat(answers.get(0).getCommandResult().getStatus()).isEqualTo(CommandStatus.REJECTED);
    assertThat(answers.get(0).getCommandResult().getCommandId()).isEqualTo("cmd-1");
    assertThat(answers.get(1).getCommandResult().getStatus()).isEqualTo(CommandStatus.REJECTED);
    assertThat(answers.get(0).getCommandResult().getPayload().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("11 §6: a session Throttle goes to the listener")
  void throttle() {
    establishedClient();
    opener
        .last()
        .server(
            ServerMessage.newBuilder()
                .setThrottle(Throttle.newBuilder().setProfileNormalRate(0.01f))
                .build());
    assertThat(throttles).hasSize(1);
  }

  @Test
  @DisplayName("T-AG-071a isReady false: messages wait in the bounded list and flow when ready")
  void isReady() {
    SessionClient client = establishedClient();
    FakeStream stream = opener.last();
    stream.sent.clear();
    stream.ready = false;
    for (int i = 0; i < 3; i++) {
      client.update(InstanceUpdate.newBuilder().setInstanceName("n" + i).build());
    }
    loop.run();
    assertThat(stream.sent).as("nothing written while not ready").isEmpty();
    assertThat(client.pendingMessages()).isEqualTo(3);
    stream.ready = true;
    stream.listener.ready();
    assertThat(stream.sent).hasSize(3);
    assertThat(client.pendingMessages()).isZero();
    assertThat(stream.sent.get(0).getInstanceUpdate().getInstanceName()).isEqualTo("n0");
  }

  @Test
  @DisplayName(
      "T-AG-071a the pending list is bounded: beyond it the oldest messages are dropped and counted")
  void pendingBound() {
    SessionClient client = establishedClient();
    FakeStream stream = opener.last();
    stream.sent.clear();
    stream.ready = false;
    int extra = 100;
    for (int i = 0; i < SessionClient.MAX_PENDING_MESSAGES + extra; i++) {
      client.update(InstanceUpdate.newBuilder().setInstanceName("n" + i).build());
    }
    loop.run();
    assertThat(client.pendingMessages()).isEqualTo(SessionClient.MAX_PENDING_MESSAGES);
    assertThat(counters.sessionMessagesDropped()).isEqualTo(extra);
    stream.ready = true;
    stream.listener.ready();
    assertThat(stream.sent.get(0).getInstanceUpdate().getInstanceName()).isEqualTo("n" + extra);
  }

  @Test
  @DisplayName("T-AG-071a the pending list is also bounded by bytes")
  void pendingBytes() {
    SessionClient client = establishedClient();
    opener.last().ready = false;
    char[] text = new char[1024 * 1024];
    java.util.Arrays.fill(text, 'x');
    String name = new String(text);
    for (int i = 0; i < 6; i++) {
      client.update(InstanceUpdate.newBuilder().setInstanceName(name).build());
    }
    loop.run();
    assertThat(client.pendingMessages()).isLessThanOrEqualTo(4);
    assertThat(counters.sessionMessagesDropped()).isGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("InstanceUpdate is written while a session is established, skipped otherwise")
  void instanceUpdate() {
    SessionClient client = started();
    client.update(InstanceUpdate.newBuilder().setInstanceName("before").build());
    loop.run();
    assertThat(opener.last().sentOf(AgentMessage.BodyCase.INSTANCE_UPDATE)).isEmpty();
    opener.last().server(accepted(7, "session-1"));
    client.update(InstanceUpdate.newBuilder().setInstanceName("after").build());
    loop.run();
    assertThat(opener.last().sentOf(AgentMessage.BodyCase.INSTANCE_UPDATE)).hasSize(1);
  }

  @Test
  @DisplayName(
      "11 §3, §6: reestablish ends the session at once for the sender and only once for all"
          + " in-flight batches, and an older session's report is ignored")
  void reestablish() {
    SessionClient client = establishedClient();
    FakeStream first = opener.last();
    assertThat(client.established()).isTrue();
    client.reestablish("session-1");
    assertThat(client.established()).as("false before the loop ran").isFalse();
    client.reestablish("session-1");
    client.reestablish("session-1");
    loop.run();
    assertThat(first.cancelled).isNotNull();
    assertThat(opener.streams).as("exactly one new stream").hasSize(2);

    opener.last().server(accepted(7, "session-2"));
    assertThat(client.established()).isTrue();
    client.reestablish("session-1");
    loop.run();
    assertThat(client.established()).as("a stale report does not end the new session").isTrue();
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @Tag("req:SEC-01")
  @DisplayName("ADR-0010 C-102: a data RPC's UNAUTHENTICATED ends the session, 5 minutes, once")
  void dataUnauthenticated() {
    SessionClient client = establishedClient();
    client.unauthenticated("session-1");
    client.unauthenticated("session-1");
    assertThat(client.established()).isFalse();
    loop.run();
    assertThat(loop.nextDelay()).isEqualTo(Backoff.UNAUTHENTICATED_MILLIS);
    assertThat(counters.rejected()).isEqualTo(1);
  }

  @Test
  @Tag("req:F-A26")
  @DisplayName("F-A26: AgentBye carries SHUTDOWN and the last seqs, ahead of what waits")
  void bye() throws Exception {
    final SessionClient client = establishedClient();
    final FakeStream stream = opener.last();
    stream.sent.clear();
    stream.ready = false;
    client.update(InstanceUpdate.newBuilder().setInstanceName("waiting").build());
    loop.run();
    Heartbeat seqs =
        Heartbeat.newBuilder()
            .setLastTxSeq(4)
            .setLastProfileSeq(5)
            .setLastMetricSeq(6)
            .setLastStatSeq(7)
            .build();
    // bye() waits for the loop; this loop only runs when told to.
    assertThat(client.bye(ByeReason.SHUTDOWN, seqs, 1))
        .as("the loop did not run in time")
        .isFalse();
    loop.run();
    stream.ready = true;
    stream.listener.ready();
    assertThat(stream.sent).hasSize(2);
    assertThat(stream.sent.get(0).getBodyCase()).isEqualTo(AgentMessage.BodyCase.BYE);
    assertThat(stream.sent.get(0).getBye().getReason()).isEqualTo(ByeReason.SHUTDOWN);
    assertThat(stream.sent.get(0).getBye().getLastTxSeq()).isEqualTo(4);
    assertThat(stream.sent.get(0).getBye().getLastStatSeq()).isEqualTo(7);
  }

  @Test
  @DisplayName("no bye without a session")
  void noBye() {
    SessionClient client = started();
    assertThat(client.bye(ByeReason.SHUTDOWN, Heartbeat.getDefaultInstance(), 1)).isFalse();
  }

  @Test
  @DisplayName("close at shutdown half-closes the stream and stops reconnecting")
  void closeStopsReconnecting() {
    SessionClient client = establishedClient();
    FakeStream stream = opener.last();
    client.close();
    loop.run();
    assertThat(stream.halfClosed).isTrue();
    stream.close(Status.OK);
    loop.advance(600_000);
    assertThat(opener.streams).hasSize(1);
    assertThat(client.established()).isFalse();
  }

  @Test
  @DisplayName("close during a reconnect wait cancels it")
  void closeDuringWait() {
    SessionClient client = started();
    opener.last().close(Status.UNAVAILABLE);
    client.close();
    loop.advance(600_000);
    assertThat(opener.streams).hasSize(1);
  }

  @Test
  @DisplayName(
      "10 §10.4 AgentSession, the API of later work orders: every update goes out as InstanceUpdate"
          + " and into the next session's hello; the salt stays in memory")
  void agentSessionApi() {
    SessionClient client = establishedClient();
    java.util.Map<String, String> values = new java.util.HashMap<String, String>();
    values.put("app.name", "orders");
    AgentIdentity identity =
        new AgentIdentity(
            AgentConfig.of(values), "1.0", "h", Collections.<String>emptyList(), 1L, false);
    AgentSession session = new AgentSession(identity, client);
    AgentSession.install(session);
    assertThat(AgentSession.current()).isSameAs(session);
    assertThat(session.established()).isTrue();
    assertThat(session.instanceId()).isEqualTo(7);
    assertThat(session.userIdSalt()).isEmpty();
    session.userIdSalt(new byte[] {1, 2});
    byte[] salt = session.userIdSalt();
    salt[0] = 9;
    assertThat(session.userIdSalt()).as("a copy").containsExactly(1, 2);

    session.instanceName(" host:8080 ");
    session.instanceName(" ");
    session.virtualThreads(true);
    session.appVersion("2.0");
    session.configHash("h2");
    session.connectors(java.util.Arrays.asList("http-nio-8080"));
    loop.run();
    List<AgentMessage> updates = opener.last().sentOf(AgentMessage.BodyCase.INSTANCE_UPDATE);
    assertThat(updates).hasSize(5);
    assertThat(updates.get(0).getInstanceUpdate().getInstanceName()).isEqualTo("host:8080");
    assertThat(updates.get(1).getInstanceUpdate().getVirtualThreads()).isTrue();
    assertThat(updates.get(1).getInstanceUpdate().hasAppVersion())
        .as("absent, not empty")
        .isFalse();
    assertThat(updates.get(2).getInstanceUpdate().getAppVersion()).isEqualTo("2.0");
    assertThat(updates.get(3).getInstanceUpdate().getConfigHash()).isEqualTo("h2");
    assertThat(updates.get(4).getInstanceUpdate().getConnectorsList())
        .containsExactly("http-nio-8080");
    assertThat(identity.hello().getInstanceName()).isEqualTo("host:8080");
    assertThat(identity.hello().getVirtualThreads()).isTrue();
    AgentSession.install(null);
    assertThat(AgentSession.current()).isNull();
  }

  @Test
  @Tag("req:N-05")
  @DisplayName(
      "10 §10.4 AgentSession from any thread: the same value 1,000 times posts one task to the"
          + " event loop, a changed value one more; nothing queues without bound (N-05)")
  void agentSessionPostsOnlyChanges() {
    SessionClient client = establishedClient();
    java.util.Map<String, String> values = new java.util.HashMap<String, String>();
    values.put("app.name", "orders");
    AgentIdentity identity =
        new AgentIdentity(
            AgentConfig.of(values), "1.0", "h", Collections.<String>emptyList(), 1L, false);
    AgentSession session = new AgentSession(identity, client);
    opener.last().sent.clear();

    int before = loop.submitted;
    for (int i = 0; i < 1_000; i++) {
      session.instanceName("host:8080");
      session.virtualThreads(true);
      session.appVersion("2.0");
      session.configHash("h2");
      session.connectors(java.util.Arrays.asList("http-nio-8080"));
    }
    assertThat(loop.submitted - before).as("one task per method").isEqualTo(5);
    session.instanceName("host:9090");
    session.virtualThreads(false);
    session.appVersion("2.1");
    session.configHash("h3");
    session.connectors(java.util.Arrays.asList("http-nio-9090"));
    assertThat(loop.submitted - before).as("one more per changed value").isEqualTo(10);
    loop.run();
    assertThat(opener.last().sentOf(AgentMessage.BodyCase.INSTANCE_UPDATE)).hasSize(10);
    assertThat(identity.hello().getInstanceName()).isEqualTo("host:9090");
  }

  @Test
  @DisplayName(
      "11 §4.2: connectors reported before the session is established (the hello has none) go out"
          + " as exactly one InstanceUpdate once the ServerHello accepts it; the other optional"
          + " fields stay absent")
  void connectorsBeforeEstablished() {
    AgentIdentity identity = identity();
    SessionClient client = client(identity, false, false);
    AgentSession session = new AgentSession(identity, client);
    client.start();
    loop.run();
    FakeStream stream = opener.last();
    session.connectors(java.util.Arrays.asList("http-nio-8080"));
    loop.run();
    assertThat(updates(stream)).as("no session yet: nothing written").isEmpty();

    stream.server(accepted(7, "session-1"));
    List<InstanceUpdate> updates = updates(stream);
    assertThat(updates).hasSize(1);
    InstanceUpdate update = updates.get(0);
    assertThat(update.getConnectorsList()).containsExactly("http-nio-8080");
    assertThat(update.hasInstanceName()).as("absent").isFalse();
    assertThat(update.hasVirtualThreads()).as("absent").isFalse();
    assertThat(update.hasAppVersion()).as("absent").isFalse();
    assertThat(update.hasConfigHash()).as("absent").isFalse();
    assertThat(stream.sent.get(1).getBodyCase())
        .as("right after the hello")
        .isEqualTo(AgentMessage.BodyCase.INSTANCE_UPDATE);
  }

  @Test
  @DisplayName(
      "11 §4.2: the connectors travel only as InstanceUpdate, so every new session gets them once"
          + " more: one InstanceUpdate on each new stream after a reconnect")
  void connectorsOnEveryNewSession() {
    AgentIdentity identity = identity();
    SessionClient client = client(identity, false, false);
    AgentSession session = new AgentSession(identity, client);
    client.start();
    loop.run();
    opener.last().server(accepted(7, "session-1"));
    session.connectors(java.util.Arrays.asList("http-nio-8080"));
    loop.run();
    FakeStream first = opener.last();
    assertThat(updates(first)).as("reported while established").hasSize(1);

    for (int number = 2; number <= 3; number++) {
      reconnect();
      FakeStream next = opener.last();
      assertThat(next).isNotSameAs(first);
      assertThat(updates(next)).as("not before its ServerHello").isEmpty();
      next.server(accepted(7, "session-" + number));
      List<InstanceUpdate> updates = updates(next);
      assertThat(updates).as("session %d", number).hasSize(1);
      assertThat(updates.get(0).getConnectorsList()).containsExactly("http-nio-8080");
      assertThat(updates.get(0).hasInstanceName()).isFalse();
    }
    assertThat(updates(first)).as("the first stream got nothing more").hasSize(1);
  }

  @Test
  @DisplayName(
      "11 §4.2: hello fields that change after the hello was written and before the ServerHello"
          + " are corrected once the session is established (one InstanceUpdate with the changed"
          + " fields only); the next hello carries them, so that session needs no correction")
  void helloFieldsChangedBeforeServerHello() {
    AgentIdentity identity = identity();
    SessionClient client = client(identity, false, false);
    AgentSession session = new AgentSession(identity, client);
    client.start();
    loop.run();
    FakeStream first = opener.last();
    assertThat(first.sent.get(0).getHello().getInstanceName())
        .isEqualTo(identity.host() + ":" + identity.pid());
    session.instanceName("host:8080");
    loop.run();
    assertThat(updates(first)).as("its own update found no session").isEmpty();

    first.server(accepted(7, "session-1"));
    List<InstanceUpdate> updates = updates(first);
    assertThat(updates).hasSize(1);
    InstanceUpdate update = updates.get(0);
    assertThat(update.getInstanceName()).isEqualTo("host:8080");
    assertThat(update.hasVirtualThreads()).as("unchanged: absent").isFalse();
    assertThat(update.hasAppVersion()).as("unchanged: absent").isFalse();
    assertThat(update.hasConfigHash()).as("unchanged: absent").isFalse();
    assertThat(update.getConnectorsList()).as("none reported").isEmpty();

    // The next hello carries the new name; the other three hello fields change before its answer.
    reconnect();
    FakeStream second = opener.last();
    assertThat(second.sent.get(0).getHello().getInstanceName()).isEqualTo("host:8080");
    session.virtualThreads(true);
    session.appVersion("2.0");
    session.configHash("h2");
    loop.run();
    assertThat(updates(second)).isEmpty();
    second.server(accepted(7, "session-2"));
    updates = updates(second);
    assertThat(updates).hasSize(1);
    update = updates.get(0);
    assertThat(update.hasInstanceName()).as("carried by this hello: absent").isFalse();
    assertThat(update.hasVirtualThreads()).isTrue();
    assertThat(update.getVirtualThreads()).isTrue();
    assertThat(update.getAppVersion()).isEqualTo("2.0");
    assertThat(update.getConfigHash()).isEqualTo("h2");

    reconnect();
    FakeStream third = opener.last();
    third.server(accepted(7, "session-3"));
    assertThat(updates(third)).as("the hello carried every value").isEmpty();
  }

  @Test
  @DisplayName(
      "11 §4.2: no connectors reported and no hello field changed: no InstanceUpdate on"
          + " establishment, also after a reconnect; reporting the value the hello carried is no"
          + " change")
  void nothingToCorrect() {
    AgentIdentity identity = identity();
    SessionClient client = client(identity, false, false);
    AgentSession session = new AgentSession(identity, client);
    client.start();
    loop.run();
    FakeStream first = opener.last();
    session.instanceName(first.sent.get(0).getHello().getInstanceName());
    session.appVersion("1.0");
    session.connectors(Collections.<String>emptyList());
    loop.run();
    first.server(accepted(7, "session-1"));
    assertThat(updates(first)).isEmpty();

    reconnect();
    FakeStream second = opener.last();
    second.server(accepted(7, "session-2"));
    assertThat(updates(second)).isEmpty();
    assertThat(second.sent).as("the hello only").hasSize(1);
  }

  @Test
  @DisplayName("a message of a newer server is ignored (11 §4.4)")
  void unknownMessage() {
    SessionClient client = establishedClient();
    opener.last().server(ServerMessage.getDefaultInstance());
    assertThat(client.established()).isTrue();
  }

  @Test
  @DisplayName("sendDictionary is refused without a session")
  void dictionaryWithoutSession() {
    SessionClient client = started();
    assertThat(client.sendDictionary(Collections.emptyList())).isFalse();
    opener.last().server(accepted(7, "session-1"));
    assertThat(client.sendDictionary(Collections.emptyList())).isTrue();
    client.reestablish("session-1");
    assertThat(client.sendDictionary(Collections.emptyList())).isFalse();
  }

  @Test
  @Tag("req:SEC-08")
  @DisplayName("T-SEC-08a skip-verify and plaintext connect again and again without failing")
  void securityModesConnect() {
    SessionClient skip = client(false, true);
    skip.start();
    loop.run();
    assertThat(opener.streams).hasSize(1);
    SessionClient plain = client(true, false);
    plain.start();
    loop.run();
    assertThat(opener.streams).hasSize(2);
  }

  @Test
  @DisplayName("isTokenRejection names the three TOKEN_* reasons only")
  void tokenReasons() {
    assertThat(SessionClient.isTokenRejection(RejectReason.TOKEN_INVALID)).isTrue();
    assertThat(SessionClient.isTokenRejection(RejectReason.TOKEN_EXPIRED)).isTrue();
    assertThat(SessionClient.isTokenRejection(RejectReason.TOKEN_REVOKED)).isTrue();
    assertThat(SessionClient.isTokenRejection(RejectReason.APP_MISMATCH)).isFalse();
    assertThat(SessionClient.rootCause(null)).isNull();
    RuntimeException root = new RuntimeException("root");
    assertThat(SessionClient.rootCause(new RuntimeException(root))).isSameAs(root);
  }
}
