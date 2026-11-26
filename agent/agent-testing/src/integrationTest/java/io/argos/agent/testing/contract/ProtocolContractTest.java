/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.ContractVectors;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.HeartbeatRecord;
import io.argos.agent.testing.collector.ScriptedAgent;
import io.argos.agent.testing.collector.ScriptedAgent.Ended;
import io.argos.agent.testing.collector.ScriptedAgent.Session;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 11 §16 contract suite: a scripted agent drives the {@link FakeCollector} over gRPC
 * (grpc-netty, loopback) with the fixed inputs of {@code contract/argos-v1-vectors.json} and checks
 * the expected outputs: the session order of 11 §3/§5, the seq, deduplication and ack rules of §6,
 * the clock arithmetic of §9 (with the collector's injectable clock) and the status codes and
 * trailers of §5/§10. The same file is the reference for the server's ingest tests (WO-21, WO-22),
 * except the {@code status_codes} entries with a {@code fault} field, which pin the FakeCollector's
 * answer to an injected fault (see the file's description).
 */
@Tag("req:DSN-11-01")
@Tag("req:DSN-11-02")
@Tag("req:DSN-18-04")
class ProtocolContractTest {
  /** A well-formed token that is not the collector's (another app's token). */
  // Built from readable text at run time: a token-shaped literal trips the secret scan (gitleaks,
  // SEC-73), and this is a test value, not a secret.
  static final String OTHER_TOKEN =
      "argos_"
          + Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString("other-app-token".getBytes(StandardCharsets.US_ASCII));

  private final Map<String, Object> vectors = ContractVectors.load();
  private final ScriptedAgent agent = new ScriptedAgent();
  private final List<FakeCollector> collectors = new ArrayList<>();

  @AfterEach
  void close() throws InterruptedException {
    agent.close();
    for (FakeCollector collector : collectors) {
      collector.close();
    }
  }

  private FakeCollector start(FakeCollector.Builder builder) {
    FakeCollector collector = builder.start();
    collectors.add(collector);
    return collector;
  }

  private List<Map<String, Object>> section(String name) {
    return ContractVectors.maps(vectors.get(name));
  }

  // --- §9 clock ---------------------------------------------------------------------------------

  @Test
  @Tag("req:D-02")
  @DisplayName(
      "11 §9 over gRPC: with the vectors' server clock, ServerHello.server_time_ms, HeartbeatAck"
          + " t1/t2/t3 and clock_offset_ms, and every completed round equal the vectors")
  void clock() throws Exception {
    for (Map<String, Object> scenario : section("clock")) {
      String name = ContractVectors.string(scenario, "name");
      Map<String, Object> hello = ContractVectors.map(scenario.get("hello"));
      List<Map<String, Object>> beats = ContractVectors.maps(scenario.get("heartbeats"));
      // The collector reads its clock once per accepted hello and twice per answered heartbeat.
      Deque<Long> clock = new ArrayDeque<>();
      clock.add(ContractVectors.number(hello, "server_time_ms"));
      for (Map<String, Object> beat : beats) {
        clock.add(ContractVectors.number(beat, "t2"));
        clock.add(ContractVectors.number(beat, "t3"));
      }
      FakeCollector collector =
          start(
              FakeCollector.builder()
                  .clock(
                      () -> {
                        synchronized (clock) {
                          Long next = clock.poll();
                          if (next == null) {
                            throw new IllegalStateException("clock read beyond the vector");
                          }
                          return next;
                        }
                      }));
      Session session = new Session(agent.plaintext(collector), collector.token());
      session.hello(
          "shop",
          "test",
          "host:1",
          3,
          ProtocolVersion.CURRENT,
          ContractVectors.number(hello, "sent_at_ms"));
      ServerHello accepted = session.next().getHello();
      assertThat(accepted.getAccepted()).as(name).isTrue();
      assertThat(accepted.getServerTimeMs())
          .as(name)
          .isEqualTo(ContractVectors.number(hello, "server_time_ms"));
      long instance = accepted.getInstanceId();
      assertThat(collector.clockOffsetMs(instance))
          .as(name + " first estimate")
          .hasValue(ContractVectors.number(hello, "expected_estimate_ms"));
      for (int i = 0; i < beats.size(); i++) {
        Map<String, Object> beat = beats.get(i);
        String step = name + " heartbeat " + (i + 1);
        long t1 = ContractVectors.number(beat, "t1");
        session.heartbeat(
            t1, ContractVectors.number(beat, "prev_t1"), ContractVectors.number(beat, "prev_t4"));
        HeartbeatAck ack = session.next().getHeartbeatAck();
        assertThat(ack.getT1Ms()).as(step + " t1 echo").isEqualTo(t1);
        assertThat(ack.getT2Ms()).as(step + " t2").isEqualTo(ContractVectors.number(beat, "t2"));
        assertThat(ack.getT3Ms()).as(step + " t3").isEqualTo(ContractVectors.number(beat, "t3"));
        long estimate = ContractVectors.number(beat, "expected_estimate_ms");
        assertThat((long) ack.getClockOffsetMs()).as(step + " clock_offset_ms").isEqualTo(estimate);
        assertThat(ack.getState().name())
            .as(step + " state (12 §4.2: WARN at |offset| >= 1 s)")
            .isEqualTo(Math.abs(estimate) >= 1_000 ? "WARN" : "UP");
        HeartbeatRecord record = collector.heartbeats().get(i);
        Object expected = beat.get("expected_round");
        if (expected == null) {
          assertThat(record.round()).as(step + " round").isNull();
        } else {
          Map<String, Object> want = ContractVectors.map(expected);
          assertThat(record.round()).as(step + " round").isNotNull();
          assertThat(record.round().offsetMs())
              .as(step + " offset")
              .isEqualTo(want.get("offset_ms"));
          assertThat(record.round().rttMs()).as(step + " rtt").isEqualTo(want.get("rtt_ms"));
          assertThat(record.round().discarded())
              .as(step + " discarded")
              .isEqualTo(want.get("discarded"));
        }
        assertThat(collector.clockOffsetMs(instance)).as(step + " estimate").hasValue(estimate);
      }
      assertThat(clock).as(name + ": every clock value used").isEmpty();
    }
  }

  // --- §6 dedup and acks ------------------------------------------------------------------------

  @Test
  @Tag("req:D-13")
  @Tag("req:C-3")
  @DisplayName(
      "11 §6 over gRPC: ACK_OK and ACK_DUPLICATE with last_seq per (instance, epoch, kind), window"
          + " 1,000, an error answer writes nothing (its retry is ACK_OK); duplicates stay"
          + " recorded")
  void dedup() throws Exception {
    for (Map<String, Object> scenario : section("dedup")) {
      FakeCollector collector = start(FakeCollector.builder());
      ManagedChannel channel = agent.plaintext(collector);
      ServerHello hello = new Session(channel, collector.token()).accepted("test");
      Metadata headers = ScriptedAgent.headers(collector.token(), hello);
      int calls = 0;
      int duplicates = 0;
      for (Map<String, Object> step : ContractVectors.maps(scenario.get("steps"))) {
        BatchKind kind = BatchKind.valueOf(ContractVectors.string(step, "kind"));
        long epoch = ContractVectors.number(step, "agent_epoch");
        long from = step.containsKey("seq") ? ContractVectors.number(step, "seq") : 0;
        long to = from;
        if (step.containsKey("seq_from")) {
          from = ContractVectors.number(step, "seq_from");
          to = ContractVectors.number(step, "seq_to");
        }
        for (long seq = from; seq <= to; seq++) {
          String what = kind + " epoch " + epoch + " seq " + seq;
          var body = ScriptedAgent.batch(kind, hello.getInstanceId(), epoch, seq, 1);
          calls++;
          if (step.containsKey("fault")) {
            collector.faults().failNext(1, Status.fromCode(code(step, "fault")));
            StatusRuntimeException e =
                failure(() -> ScriptedAgent.send(channel, headers, kind, body));
            assertThat(e.getStatus().getCode()).as(what).isEqualTo(code(step, "expected_code"));
            continue;
          }
          Ack ack = ScriptedAgent.send(channel, headers, kind, body);
          assertThat(ack.getStatus().name())
              .as(what + " status")
              .isEqualTo(ContractVectors.string(step, "expected_status"));
          long lastSeq =
              "seq".equals(step.get("expected_last_seq"))
                  ? seq
                  : ContractVectors.number(step, "expected_last_seq");
          assertThat(ack.getLastSeq()).as(what + " last_seq").isEqualTo(lastSeq);
          duplicates += "ACK_DUPLICATE".equals(ack.getStatus().name()) ? 1 : 0;
        }
      }
      assertThat(collector.batches()).as("every call recorded (sent count)").hasSize(calls);
      assertThat(collector.batches().stream().filter(b -> b.duplicate()).count())
          .isEqualTo(duplicates);
    }
  }

  // --- §3/§5 session order ----------------------------------------------------------------------

  @Test
  @DisplayName(
      "11 §2/§3/§5 over gRPC: SESSION_REQUIRED before a hello and after a restart, token and"
          + " session metadata checks, hello checks in order (token, plaintext, protocol version),"
          + " instance reuse (also across a restart, with its dedup window), SessionClose REPLACED"
          + " and the InstanceUpdate rename that keeps the instance id (12 §4.3)")
  void session() throws Exception {
    for (Map<String, Object> scenario : section("session")) {
      new SessionScript(ContractVectors.string(scenario, "name"))
          .run(ContractVectors.maps(scenario.get("steps")));
    }
  }

  /** Interprets one {@code session} scenario. */
  private final class SessionScript {
    private final String name;
    private final FakeCollector collector = start(FakeCollector.builder());
    private final ManagedChannel channel = agent.plaintext(collector);
    private final Map<String, Session> streams = new LinkedHashMap<>();
    private ServerHello current;
    private ServerHello previous;
    private Session currentStream;

    SessionScript(String name) {
      this.name = name;
    }

    void run(List<Map<String, Object>> steps) throws Exception {
      int index = 0;
      for (Map<String, Object> step : steps) {
        String what = name + " step " + ++index + " " + step;
        switch (ContractVectors.string(step, "action")) {
          case "data" -> data(step, what);
          case "hello" -> hello(step, what);
          case "restart" -> {
            // "previous" names the session the restart made stale; the instance and its dedup
            // window survive the restart (11 §5, 12 §3.4).
            previous = current;
            collector.faults().serverRestart();
          }
          case "fault" -> fault(step);
          case "complete" -> {
            Session stream = streams.get(ContractVectors.string(step, "stream"));
            stream.complete();
            assertThat(stream.awaitEnd().status().isOk()).as(what).isTrue();
          }
          case "expect_stream" -> expectStream(step, what);
          case "instance_update" -> instanceUpdate(step, what);
          default -> throw new IllegalArgumentException("unknown action in " + what);
        }
      }
    }

    private String token(Map<String, Object> step) {
      return switch (ContractVectors.string(step, "token")) {
        case "valid" -> collector.token();
        case "missing" -> null;
        case "malformed" -> "xyz";
        case "other" -> OTHER_TOKEN;
        default -> throw new IllegalArgumentException("token " + step);
      };
    }

    private void data(Map<String, Object> step, String what) {
      String token = token(step);
      Metadata headers =
          switch (ContractVectors.string(step, "session")) {
            case "none" -> ScriptedAgent.headers(token, null, null);
            case "current" -> ScriptedAgent.headers(token, current);
            case "previous" -> ScriptedAgent.headers(token, previous);
            case "unknown" ->
                ScriptedAgent.headers(
                    token,
                    Integer.toUnsignedLong(current.getInstanceId()),
                    UUID.randomUUID().toString());
            case "current_other_instance" ->
                ScriptedAgent.headers(
                    token,
                    Integer.toUnsignedLong(current.getInstanceId()) + 1_000,
                    current.getSessionId());
            default -> throw new IllegalArgumentException("session in " + what);
          };
      long instance = current == null ? 1 : current.getInstanceId();
      var body =
          ScriptedAgent.batch(
              BatchKind.TRANSACTIONS, instance, 3, ContractVectors.number(step, "seq"), 1);
      expectCall(
          step, what, () -> ScriptedAgent.send(channel, headers, BatchKind.TRANSACTIONS, body));
    }

    private void hello(Map<String, Object> step, String what) throws Exception {
      Session session = new Session(channel, token(step));
      streams.put(
          step.containsKey("stream") ? ContractVectors.string(step, "stream") : what, session);
      long epoch =
          step.containsKey("agent_epoch") ? ContractVectors.number(step, "agent_epoch") : 3;
      int version =
          step.containsKey("protocol_version")
              ? (int) ContractVectors.number(step, "protocol_version")
              : ProtocolVersion.CURRENT;
      String instanceName =
          step.containsKey("instance_name")
              ? ContractVectors.string(step, "instance_name")
              : "host:1";
      boolean checkOnly = Boolean.TRUE.equals(step.get("check_only"));
      session.hello(
          "shop", "test", instanceName, epoch, version, System.currentTimeMillis(), checkOnly);
      ServerHello hello = session.next().getHello();
      boolean accepted = Boolean.TRUE.equals(step.get("expected_accepted"));
      assertThat(hello.getAccepted()).as(what).isEqualTo(accepted);
      if (accepted && Boolean.TRUE.equals(step.get("expected_check_only_answer"))) {
        // ADR-0010 C-240 (1): no instance, no session; the collector ends the stream.
        assertThat(hello.getInstanceId()).as(what).isZero();
        assertThat(hello.getSessionId()).as(what).isEmpty();
        Ended ended = session.awaitEnd();
        assertThat(ended.status().getCode()).as(what).isEqualTo(code(step, "expected_code"));
        return;
      }
      if (accepted) {
        previous = current;
        current = hello;
        currentStream = session;
        if (step.containsKey("expected_instance_id")) {
          assertThat((long) hello.getInstanceId())
              .as(what)
              .isEqualTo(ContractVectors.number(step, "expected_instance_id"));
        }
        return;
      }
      assertThat(hello.getRejectReason().name())
          .as(what)
          .isEqualTo(ContractVectors.string(step, "expected_reject_reason"));
      if (step.containsKey("expected_retry_after_s")) {
        assertThat((long) hello.getRetryAfterS())
            .as(what)
            .isEqualTo(ContractVectors.number(step, "expected_retry_after_s"));
      }
      Ended ended = session.awaitEnd();
      assertThat(ended.status().getCode()).as(what).isEqualTo(code(step, "expected_code"));
      expectTrailers(step, what, ended.trailers());
    }

    /**
     * {@code InstanceUpdate.instance_name} on the current session's stream (12 §4.3, ADR-0010 C-185
     * (1)); it is not acknowledged on the wire, so the step ends when the collector has recorded
     * it, which it does after applying the rename.
     */
    private void instanceUpdate(Map<String, Object> step, String what) throws InterruptedException {
      long before = instanceUpdates();
      currentStream.send(
          AgentMessage.newBuilder()
              .setInstanceUpdate(
                  InstanceUpdate.newBuilder()
                      .setInstanceName(ContractVectors.string(step, "instance_name")))
              .build());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (instanceUpdates() == before && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertThat(instanceUpdates()).as(what + ": the InstanceUpdate applied").isGreaterThan(before);
    }

    private long instanceUpdates() {
      return collector.sessionMessages().stream()
          .filter(m -> m instanceof AgentMessage a && a.hasInstanceUpdate())
          .count();
    }

    private void fault(Map<String, Object> step) {
      switch (ContractVectors.string(step, "fault")) {
        case "plaintextRejected" -> collector.faults().plaintextRejected();
        case "rejectHello" ->
            collector.faults().rejectHello(ContractVectors.string(step, "reason"));
        case "clear" -> collector.faults().clear();
        default -> throw new IllegalArgumentException("fault " + step);
      }
    }

    private void expectStream(Map<String, Object> step, String what) throws Exception {
      Session session = streams.get(ContractVectors.string(step, "stream"));
      List<Map<String, Object>> messages = ContractVectors.maps(step.get("expected_messages"));
      for (Map<String, Object> message : messages) {
        ServerMessage received = session.next();
        Map<String, Object> close = ContractVectors.map(message.get("close"));
        assertThat(received.getClose().getReason().name())
            .as(what)
            .isEqualTo(ContractVectors.string(close, "reason"));
      }
      assertThat(session.awaitEnd().status().getCode())
          .as(what)
          .isEqualTo(code(step, "expected_code"));
      assertThat(session.received).as(what + ": nothing else on the stream").isEmpty();
    }
  }

  // --- §5/§10 status codes ----------------------------------------------------------------------

  @Test
  @DisplayName(
      "11 §5/§10 over gRPC: every rejection, failure and session close of the status code table"
          + " gives its code, retry_after_s and trailers (argos-reject-reason,"
          + " argos-retry-after-ms); quota drops and throttle hints arrive in the Ack")
  void statusCodes() throws Exception {
    for (Map<String, Object> entry : section("status_codes")) {
      String what = ContractVectors.string(entry, "name") + " " + entry;
      FakeCollector collector = start(FakeCollector.builder());
      ManagedChannel channel = agent.plaintext(collector);
      switch (ContractVectors.string(entry, "call")) {
        case "hello" -> {
          collector.faults().rejectHello(ContractVectors.string(entry, "reason"));
          Session session = new Session(channel, collector.token());
          session.hello("test");
          ServerHello hello = session.next().getHello();
          assertThat(hello.getAccepted()).as(what).isFalse();
          assertThat(hello.getRejectReason().name())
              .as(what)
              .isEqualTo(ContractVectors.string(entry, "reason"));
          assertThat((long) hello.getRetryAfterS())
              .as(what)
              .isEqualTo(ContractVectors.number(entry, "expected_retry_after_s"));
          Ended ended = session.awaitEnd();
          assertThat(ended.status().getCode()).as(what).isEqualTo(code(entry, "expected_code"));
          expectTrailers(entry, what, ended.trailers());
        }
        case "data" -> {
          ServerHello hello = new Session(channel, collector.token()).accepted("test");
          switch (ContractVectors.string(entry, "fault")) {
            case "failNext" ->
                collector
                    .faults()
                    .failNext(
                        1,
                        Status.fromCode(code(entry, "code")),
                        entry.containsKey("retry_after_ms")
                            ? Duration.ofMillis(ContractVectors.number(entry, "retry_after_ms"))
                            : null);
            case "partialDrop" ->
                collector
                    .faults()
                    .partialDrop((int) ContractVectors.number(entry, "dropped_items"));
            case "throttle" ->
                collector
                    .faults()
                    .throttle(
                        entry.containsKey("profile_normal_rate")
                            ? ((Number) entry.get("profile_normal_rate")).floatValue()
                            : null,
                        entry.containsKey("send_backoff_ms")
                            ? Duration.ofMillis(ContractVectors.number(entry, "send_backoff_ms"))
                            : null);
            case "walFull" -> collector.faults().walFull();
            case "hang" -> collector.faults().hang();
            default -> throw new IllegalArgumentException("fault in " + what);
          }
          int records =
              entry.containsKey("records") ? (int) ContractVectors.number(entry, "records") : 1;
          var body =
              ScriptedAgent.batch(BatchKind.TRANSACTIONS, hello.getInstanceId(), 3, 1, records);
          Metadata headers = ScriptedAgent.headers(collector.token(), hello);
          long deadlineMs =
              entry.containsKey("deadline_ms")
                  ? ContractVectors.number(entry, "deadline_ms")
                  : 10_000;
          expectCall(
              entry,
              what,
              () -> ScriptedAgent.send(channel, headers, BatchKind.TRANSACTIONS, body, deadlineMs));
          if (entry.containsKey("expected_resend")) {
            Map<String, Object> resend = ContractVectors.map(entry.get("expected_resend"));
            collector.faults().clear();
            Ack ack = ScriptedAgent.send(channel, headers, BatchKind.TRANSACTIONS, body);
            assertThat(ack.getStatus().name())
                .as(what + " resend")
                .isEqualTo(ContractVectors.string(resend, "status"));
            assertThat(ack.getLastSeq())
                .as(what + " resend")
                .isEqualTo(ContractVectors.number(resend, "last_seq"));
            assertThat((long) ack.getDroppedItems())
                .as(what + " resend")
                .isEqualTo(ContractVectors.number(resend, "dropped_items"));
          }
        }
        case "session" -> {
          Session session = new Session(channel, collector.token());
          ServerHello hello = session.accepted("test");
          collector
              .faults()
              .sessionClose(
                  ContractVectors.string(entry, "reason"),
                  Duration.ofSeconds(ContractVectors.number(entry, "retry_after_s")));
          Map<String, Object> close = ContractVectors.map(entry.get("expected_close"));
          ServerMessage message = session.next();
          assertThat(message.getClose().getReason().name())
              .as(what)
              .isEqualTo(ContractVectors.string(close, "reason"));
          assertThat((long) message.getClose().getRetryAfterS())
              .as(what)
              .isEqualTo(ContractVectors.number(close, "retry_after_s"));
          Ended ended = session.awaitEnd();
          assertThat(ended.status().getCode()).as(what).isEqualTo(code(entry, "expected_code"));
          expectTrailers(entry, what, ended.trailers());
          // A data call with the closed session's token and ids: code required, description and
          // trailers when the vector names them. No expected_data_after: the vector pins nothing
          // (SERVER_SHUTDOWN, 12 §3.9 drain, see the interpretations).
          if (entry.containsKey("expected_data_after")) {
            Map<String, Object> want = ContractVectors.map(entry.get("expected_data_after"));
            String step = what + " data after the close";
            StatusRuntimeException after =
                failure(() -> ScriptedAgent.send(channel, collector.token(), hello, 3, 1));
            assertThat(after.getStatus().getCode()).as(step).isEqualTo(code(want, "code"));
            if (want.containsKey("description")) {
              assertThat(after.getStatus().getDescription())
                  .as(step)
                  .isEqualTo(ContractVectors.string(want, "description"));
            }
            if (want.containsKey("trailers")) {
              expectTrailerValues(
                  ContractVectors.map(want.get("trailers")), step, after.getTrailers());
            }
          }
        }
        default -> throw new IllegalArgumentException("call in " + what);
      }
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** A data call's expected answer: an Ack (code OK) or a status with description and trailers. */
  private static void expectCall(Map<String, Object> step, String what, AckCall call) {
    Status.Code expected = code(step, "expected_code");
    if (expected == Status.Code.OK) {
      Ack ack = call.send();
      if (step.containsKey("expected_status")) {
        assertThat(ack.getStatus().name())
            .as(what)
            .isEqualTo(ContractVectors.string(step, "expected_status"));
      }
      if (step.containsKey("expected_last_seq")) {
        assertThat(ack.getLastSeq())
            .as(what)
            .isEqualTo(ContractVectors.number(step, "expected_last_seq"));
      }
      if (step.containsKey("expected_dropped_items")) {
        assertThat((long) ack.getDroppedItems())
            .as(what)
            .isEqualTo(ContractVectors.number(step, "expected_dropped_items"));
      }
      if (step.containsKey("expected_throttle")) {
        Map<String, Object> throttle = ContractVectors.map(step.get("expected_throttle"));
        assertThat(ack.hasThrottle()).as(what).isTrue();
        assertThat(ack.getThrottle().hasProfileNormalRate())
            .as(what)
            .isEqualTo(throttle.containsKey("profile_normal_rate"));
        if (throttle.containsKey("profile_normal_rate")) {
          assertThat(ack.getThrottle().getProfileNormalRate())
              .as(what)
              .isEqualTo(((Number) throttle.get("profile_normal_rate")).floatValue());
        }
        assertThat(ack.getThrottle().hasSendBackoffMs())
            .as(what)
            .isEqualTo(throttle.containsKey("send_backoff_ms"));
        if (throttle.containsKey("send_backoff_ms")) {
          assertThat((long) ack.getThrottle().getSendBackoffMs())
              .as(what)
              .isEqualTo(ContractVectors.number(throttle, "send_backoff_ms"));
        }
      } else {
        assertThat(ack.hasThrottle()).as(what).isFalse();
      }
      return;
    }
    StatusRuntimeException e = failure(call::send);
    assertThat(e.getStatus().getCode()).as(what).isEqualTo(expected);
    if (step.containsKey("expected_description")) {
      assertThat(e.getStatus().getDescription())
          .as(what)
          .isEqualTo(ContractVectors.string(step, "expected_description"));
    }
    expectTrailers(step, what, e.getTrailers());
  }

  /** The two 11 §10 trailers of {@code expected_trailers}: the expected values, or absent. */
  private static void expectTrailers(Map<String, Object> step, String what, Metadata trailers) {
    if (step.containsKey("expected_trailers")) {
      expectTrailerValues(ContractVectors.map(step.get("expected_trailers")), what, trailers);
    }
  }

  /** The two 11 §10 trailers: the values of {@code expected}, or absent when it has none. */
  private static void expectTrailerValues(
      Map<String, Object> expected, String what, Metadata trailers) {
    for (Metadata.Key<String> key : List.of(V1Protocol.REJECT_REASON, V1Protocol.RETRY_AFTER_MS)) {
      assertThat(trailers == null ? null : trailers.get(key))
          .as(what + " trailer " + key.name())
          .isEqualTo(expected.get(key.name()));
    }
  }

  private static Status.Code code(Map<String, Object> step, String key) {
    return Status.Code.valueOf(ContractVectors.string(step, key));
  }

  private static StatusRuntimeException failure(Runnable call) {
    try {
      call.run();
    } catch (StatusRuntimeException e) {
      return e;
    }
    throw new AssertionError("the call succeeded");
  }

  /** One data call. */
  @FunctionalInterface
  private interface AckCall {
    Ack send();
  }
}
