/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
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
 * The WO-17 additions of the FakeCollector against a minimal grpc-netty client over loopback:
 * {@code AgentEvent} and {@code InstanceUpdate.config_hash} recording, {@code ConfigPush} with
 * {@code revert} and the full {@code ConfigApplyResult}, commands with {@code ttl_s}, {@code
 * issued_at_ms} and a raw type number, {@code duration_ms} of results, the runtime {@code
 * ServerHello.config} and the {@code SELF_DIAGNOSTICS} command path of the harness (ADR-0010 C-95).
 * These tests show the harness, so they carry no acceptance test ID (see {@code
 * FakeCollectorTest}).
 */
@Tag("req:DSN-18-04")
class FakeCollectorCommandApiTest {
  private FakeCollector collector;
  private ManagedChannel channel;

  @AfterEach
  void close() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (collector != null) {
      collector.close();
    }
  }

  /** A session stream of the minimal client. */
  private final class Client {
    final BlockingQueue<ServerMessage> received = new LinkedBlockingQueue<>();
    final CompletableFuture<Void> ended = new CompletableFuture<>();
    final StreamObserver<AgentMessage> out;

    Client() {
      Metadata headers = new Metadata();
      headers.put(V1Protocol.AUTHORIZATION, "Bearer " + collector.token());
      out =
          AgentServiceGrpc.newStub(
                  ClientInterceptors.intercept(
                      channel, MetadataUtils.newAttachHeadersInterceptor(headers)))
              .session(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(ServerMessage message) {
                      received.add(message);
                    }

                    @Override
                    public void onError(Throwable error) {
                      ended.complete(null);
                    }

                    @Override
                    public void onCompleted() {
                      ended.complete(null);
                    }
                  });
    }

    ServerMessage next() throws InterruptedException {
      ServerMessage message = received.poll(10, TimeUnit.SECONDS);
      assertThat(message).as("server message within 10 s").isNotNull();
      return message;
    }

    ServerHello hello() throws InterruptedException {
      out.onNext(
          AgentMessage.newBuilder()
              .setHello(
                  AgentHello.newBuilder()
                      .setAppName("shop")
                      .setEnv("test")
                      .setInstanceName("host:1")
                      .setAppVersion("1.0")
                      .setAgentVersion("0.0.0-SNAPSHOT")
                      .setProtocolVersion(ProtocolVersion.CURRENT)
                      .setAgentEpoch(7)
                      .setSentAtMs(System.currentTimeMillis()))
              .build());
      ServerHello hello = next().getHello();
      assertThat(hello.getAccepted()).as("hello accepted: %s", hello).isTrue();
      return hello;
    }

    void send(AgentMessage message) {
      out.onNext(message);
    }

    void result(String commandId, CommandStatus status, String payload, String type, int ms) {
      send(
          AgentMessage.newBuilder()
              .setCommandResult(
                  CommandResult.newBuilder()
                      .setCommandId(commandId)
                      .setStatus(status)
                      .setChunkIndex(0)
                      .setChunkCount(1)
                      .setContentType(type)
                      .setDurationMs(ms)
                      .setPayload(ByteString.copyFromUtf8(payload)))
              .build());
    }
  }

  private Client connect(FakeCollector started) {
    collector = started;
    channel = NettyChannelBuilder.forAddress("127.0.0.1", collector.port()).usePlaintext().build();
    return new Client();
  }

  /** Waits until {@code condition} holds (5 s). */
  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertThat(condition.getAsBoolean()).as("condition within 5 s").isTrue();
  }

  @Test
  @DisplayName(
      "11 §4.2: AgentEvent (type, module, detail, at_ms, session) and InstanceUpdate.config_hash"
          + " are recorded per session; unknown event numbers read UNRECOGNIZED")
  void recordsEventsAndConfigHashes() throws Exception {
    Client client = connect(FakeCollector.start());
    ServerHello hello = client.hello();
    client.send(
        AgentMessage.newBuilder()
            .setEvent(
                AgentEvent.newBuilder()
                    .setType(AgentEventType.KILL_SWITCH_ON)
                    .setDetail("remote")
                    .setAtMs(1234))
            .build());
    client.send(
        AgentMessage.newBuilder()
            .setEvent(AgentEvent.newBuilder().setTypeValue(99).setModule("jdbc"))
            .build());
    client.send(
        AgentMessage.newBuilder()
            .setInstanceUpdate(InstanceUpdate.newBuilder().setConfigHash("abc123"))
            .build());
    client.send(
        AgentMessage.newBuilder()
            .setInstanceUpdate(InstanceUpdate.newBuilder().setInstanceName("host:2"))
            .build());

    List<AgentEventInfo> on =
        collector.awaitAgentEvents("KILL_SWITCH_ON", 1, Duration.ofSeconds(5));
    assertThat(on).hasSize(1);
    AgentEventInfo event = on.get(0);
    assertThat(event.detail()).isEqualTo("remote");
    assertThat(event.module()).isEmpty();
    assertThat(event.atMs()).isEqualTo(1234);
    assertThat(event.sessionId()).isEqualTo(hello.getSessionId());
    assertThat(event.instanceId()).isEqualTo(Integer.toUnsignedLong(hello.getInstanceId()));
    assertThat(collector.awaitAgentEvents("UNRECOGNIZED", 1, Duration.ofSeconds(5)))
        .extracting(AgentEventInfo::module)
        .containsExactly("jdbc");
    assertThat(collector.agentEvents()).hasSize(2);
    assertThat(collector.awaitConfigHash("abc123", Duration.ofSeconds(5))).isTrue();
    await(() -> collector.sessionMessages().size() >= 4);
    // An update without config_hash adds no hash; the events also stay among the session messages.
    assertThat(collector.configHashes()).containsExactly("abc123");
    assertThat(collector.configHashUpdates().get(0).sessionId()).isEqualTo(hello.getSessionId());
    assertThat(collector.awaitConfigHash("other", Duration.ofMillis(50))).isFalse();
  }

  @Test
  @DisplayName(
      "11 §4.2, 10 §12.2: pushConfig(values, revert) sends revert; the outcome carries applied and"
          + " rejected keys, reject messages, effective_config_hash, duration_ms and the measured"
          + " round trip")
  void configPushWithRevertAndFullResult() throws Exception {
    Client client = connect(FakeCollector.start());
    client.hello();
    CompletableFuture<ConfigApplyOutcome> future =
        collector.pushConfig(Map.of("sampling.profile.normal-rate", "0.5"), true);
    ConfigPush push = client.next().getConfigPush();
    assertThat(push.getRevert()).isTrue();
    assertThat(push.getValuesMap()).containsEntry("sampling.profile.normal-rate", "0.5");
    client.send(
        AgentMessage.newBuilder()
            .setConfigApplyResult(
                ConfigApplyResult.newBuilder()
                    .setChangeId(push.getChangeId())
                    .addAppliedKeys("sampling.profile.normal-rate")
                    .addRejected(
                        RejectedKey.newBuilder()
                            .setKey("capture.sql-parameters")
                            .setCode(RejectCode.NOT_ALLOWED)
                            .setMessage("not_allowed: outside remote-config.allow"))
                    .addRejected(
                        RejectedKey.newBuilder()
                            .setKey("x.unknown")
                            .setCode(RejectCode.UNKNOWN_KEY))
                    .setEffectiveConfigHash("h-2")
                    .setDurationMs(17))
            .build());
    ConfigApplyOutcome outcome = future.get(10, TimeUnit.SECONDS);
    assertThat(outcome.appliedKeys()).containsExactly("sampling.profile.normal-rate");
    assertThat(outcome.rejected())
        .containsEntry("capture.sql-parameters", "NOT_ALLOWED")
        .containsEntry("x.unknown", "UNKNOWN_KEY");
    assertThat(outcome.messages())
        .containsExactly(
            Map.entry("capture.sql-parameters", "not_allowed: outside remote-config.allow"));
    assertThat(outcome.message("x.unknown")).isEmpty();
    assertThat(outcome.effectiveConfigHash()).isEqualTo("h-2");
    assertThat(outcome.durationMs()).isEqualTo(17);
    assertThat(outcome.roundTripMs()).isBetween(0L, 10_000L);

    // The one-argument form keeps sending revert=false.
    CompletableFuture<ConfigApplyOutcome> plain = collector.pushConfig(Map.of("enabled", "true"));
    assertThat(client.next().getConfigPush().getRevert()).isFalse();
    assertThat(plain).isNotDone();
  }

  @Test
  @DisplayName(
      "11 §8.2: command(type, args, ttlS[, issuedAtMs]) sends ttl_s and issued_at_ms,"
          + " command(type, args) keeps ttl_s 60, commandRaw sends a type number the enum does not"
          + " define; outcomes carry duration_ms and the round trip")
  void commandsWithTtlAndRawType() throws Exception {
    Client client = connect(FakeCollector.start());
    client.hello();

    CompletableFuture<CommandOutcome> dump = collector.command("THREAD_DUMP", Map.of());
    Command first = client.next().getCommand();
    assertThat(first.getType()).isEqualTo(CommandType.THREAD_DUMP);
    assertThat(first.getTtlS()).isEqualTo(FakeCollector.DEFAULT_COMMAND_TTL_S);
    assertThat(first.getIssuedAtMs()).isPositive();
    client.result(first.getCommandId(), CommandStatus.OK, "{}", "application/json", 42);
    CommandOutcome dumped = dump.get(10, TimeUnit.SECONDS);
    assertThat(dumped.ok()).isTrue();
    assertThat(dumped.durationMs()).isEqualTo(42);
    assertThat(dumped.roundTripMs()).isBetween(0L, 10_000L);

    CompletableFuture<CommandOutcome> stack =
        collector.command("THREAD_STACK", Map.of("tx_id", "5"), 1, 1_000L);
    Command second = client.next().getCommand();
    assertThat(second.getTtlS()).isEqualTo(1);
    assertThat(second.getIssuedAtMs()).isEqualTo(1_000L);
    assertThat(second.getArgsMap()).containsEntry("tx_id", "5");
    client.result(second.getCommandId(), CommandStatus.EXPIRED, "", "", 0);
    assertThat(stack.get(10, TimeUnit.SECONDS).status()).isEqualTo("EXPIRED");

    CompletableFuture<CommandOutcome> env = collector.command("ENV_DUMP", Map.of(), 0);
    assertThat(client.next().getCommand().getTtlS()).isZero();
    assertThat(env).isNotDone();

    CompletableFuture<CommandOutcome> raw = collector.commandRaw(42, Map.of("a", "b"));
    Command unknown = client.next().getCommand();
    assertThat(unknown.getTypeValue()).isEqualTo(42);
    assertThat(unknown.getType()).isEqualTo(CommandType.UNRECOGNIZED);
    assertThat(unknown.getArgsMap()).containsEntry("a", "b");
    client.result(unknown.getCommandId(), CommandStatus.REJECTED, "", "", 0);
    CommandOutcome rejected = raw.get(10, TimeUnit.SECONDS);
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.ok()).isFalse();
  }

  @Test
  @DisplayName(
      "C-231 (4) harness: helloConfig(Map) changes ServerHello.config of later sessions only;"
          + " faults().closeStream() ends the current session so the client reconnects")
  void helloConfigAtRunTime() throws Exception {
    Client first = connect(FakeCollector.builder().config(Map.of("enabled", "true")).start());
    assertThat(first.hello().getConfigMap()).containsOnly(Map.entry("enabled", "true"));
    collector.helloConfig(Map.of("enabled", "false", "log.level", "DEBUG"));
    assertThat(collector.helloConfig()).containsEntry("enabled", "false");
    collector.faults().closeStream();
    first.ended.get(10, TimeUnit.SECONDS);
    Client second = new Client();
    assertThat(second.hello().getConfigMap())
        .containsOnly(Map.entry("enabled", "false"), Map.entry("log.level", "DEBUG"));
  }

  @Test
  @DisplayName(
      "C-95: SelfDiagnostics.fromCommand reads the SELF_DIAGNOSTICS result with the file's parser;"
          + " extra members stay readable; a non-OK answer is not available, never zero")
  void selfDiagnosticsByCommand() throws Exception {
    Client client = connect(FakeCollector.start());
    client.hello();
    CompletableFuture<SelfDiagnostics> future =
        CompletableFuture.supplyAsync(() -> SelfDiagnostics.fromCommand(collector));
    Command command = client.next().getCommand();
    assertThat(command.getType()).isEqualTo(CommandType.SELF_DIAGNOSTICS);
    client.result(
        command.getCommandId(),
        CommandStatus.OK,
        "{\"active_registry_size\":3,\"premain_ms\":12.5,\"agent_version\":\"0.0.0-SNAPSHOT\","
            + "\"counters\":{\"argos_agent_sql_outside_tx_total\":4},"
            + "\"modules\":[{\"id\":1,\"name\":\"servlet\",\"state\":\"ENABLED\",\"errors\":0}],"
            + "\"config\":{\"sampling.profile.normal-rate\":{\"value\":\"0.1\","
            + "\"source\":\"default\"}}}",
        "application/json",
        3);
    SelfDiagnostics diagnostics = future.get(30, TimeUnit.SECONDS);
    assertThat(diagnostics.available()).isTrue();
    assertThat(diagnostics.value("active_registry_size")).isEqualTo(3);
    assertThat(diagnostics.decimal("premain_ms")).isEqualTo(12.5);
    assertThat(diagnostics.value("sql_outside_tx_total")).isEqualTo(4);
    assertThat(diagnostics.module("servlet")).isPresent();
    assertThat(diagnostics.json()).containsEntry("agent_version", "0.0.0-SNAPSHOT");
    assertThat(diagnostics.member("config.sampling.profile.normal-rate.source"))
        .contains("default");
    assertThat(diagnostics.member("config.sampling.profile.normal-rate.value")).contains("0.1");
    assertThat(diagnostics.member("config.missing")).isEmpty();

    CompletableFuture<SelfDiagnostics> cooled =
        CompletableFuture.supplyAsync(() -> SelfDiagnostics.fromCommand(collector));
    Command again = client.next().getCommand();
    client.send(
        AgentMessage.newBuilder()
            .setCommandResult(
                CommandResult.newBuilder()
                    .setCommandId(again.getCommandId())
                    .setStatus(CommandStatus.COOLDOWN)
                    .setError("cooldown"))
            .build());
    SelfDiagnostics notAvailable = cooled.get(30, TimeUnit.SECONDS);
    assertThat(notAvailable.available()).isFalse();
    assertThat(notAvailable.reason()).contains("COOLDOWN").contains("cooldown");
  }
}
