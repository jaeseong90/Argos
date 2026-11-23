/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.auth.Role;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CommandReplies;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.ManagedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

/**
 * The security tests of the remote commands (WO-26) on a real server with real login sessions, real
 * PostgreSQL and fake agents over the TLS gRPC listener, the server clock moved by hand:
 *
 * <ul>
 *   <li>T-SEC-21: the permission matrix of 3 roles × 5 commands (viewer none; operator thread dump,
 *       thread stack, self diagnosis; admin all five); a refused request never reaches the agent;
 *   <li>T-SEC-50: command ids are UUIDv4 (other forms 400); another user's command and result are
 *       {@code 403 not_owner}, an administrator sees them;
 *   <li>T-SEC-22: the stored result holds no plaintext (a sentinel in the dump is found in no
 *       column of {@code command_results}, {@code commands} or {@code audit_logs}, nor in the
 *       server log), yet reads back decrypted;
 *   <li>T-SEC-24: the delivered {@code Command} carries {@code ttl_s = 60} and the server's {@code
 *       issued_at} (the agent's TTL basis); a sent command without a result expires after 120 s of
 *       server time and a late result is not stored; an expired command is never delivered to the
 *       agent's next session; an agent {@code COOLDOWN} is recorded as failed/cooldown. A queued
 *       command past its TTL is never sent: CommandRouterTest T-SEC-24a (the session of a real
 *       stream is always ready). The agent's own cooldown and TTL enforcement (WO-17) are
 *       agent-core CommandDispatcherTest and agent-testing CommandRoundTripTest; the cooldown of
 *       the real agent behind this server is ui/e2e/specs/agent-commands.spec.ts T-SEC-24 (stack).
 * </ul>
 */
@Tag("req:SEC-21")
@Tag("req:SEC-22")
@Tag("req:SEC-24")
@Tag("req:SEC-50")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommandSecurityTest {

  private static final String SENTINEL = "ARGOS_SEED_cmdsec" + Long.toHexString(System.nanoTime());
  private static final Duration WAIT = Duration.ofSeconds(15);
  private static final List<String> TYPES =
      List.of("thread_dump", "thread_stack", "self_diagnosis", "reload_config", "env_dump");
  private static final Map<Role, Set<String>> ALLOWED =
      Map.of(
          Role.VIEWER, Set.of(),
          Role.OPERATOR, Set.of("thread_dump", "thread_stack", "self_diagnosis"),
          Role.ADMIN, Set.copyOf(TYPES));

  private static final MutableClock CLOCK = new MutableClock();
  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static final Map<Role, ApiTestClient> CLIENTS = new EnumMap<>(Role.class);
  private static String appName;
  private static AgentTokens.IssuedToken token;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    env = SecuritySuiteAccess.environment();
    Path dir = Files.createTempDirectory("argos-command-security");
    TestMasterKey key = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(key.property(), key.file().toString());
    p.put("argos.ratelimit.login-per-ip-per-minute", "100000");
    p.put("argos.ratelimit.api-per-user-per-second", "100000");
    p.put("argos.ratelimit.api-burst-per-user", "100000");
    server = ArgosTestServer.start(env, p, CLOCK.installer());
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR, Role.ADMIN)) {
      CLIENTS.put(role, login(role));
    }
    appName = "cmd-sec-" + Long.toHexString(System.nanoTime());
    try (Connection c = env.connect(PgAccount.APP)) {
      int appId = AgentTokens.ensureApp(c, appName, "prod");
      token = AgentTokens.issue(c, appId);
    }
  }

  @AfterAll
  static void stop() throws Exception {
    AGENTS.forEach(FakeAgent::close);
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.close();
    }
    String log = Files.readString(SecuritySuiteAccess.logFile(), StandardCharsets.UTF_8);
    assertThat(log).as("server log").doesNotContain(SENTINEL);
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static FakeAgent agent(String suffix, Function<Command, List<CommandResult>> replies) {
    FakeAgent a =
        FakeAgent.builder(channel)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-" + suffix + ":8080")
            .onCommand(replies)
            .build();
    AGENTS.add(a);
    HelloResult hello = a.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return a;
  }

  private static String body(String type) {
    return type.equals("thread_stack")
        ? "{\"type\":\"thread_stack\",\"args\":{\"thread_id\":\"1\"}}"
        : "{\"type\":\"" + type + "\"}";
  }

  private static ApiResponse request(Role role, int instanceId, String type) {
    return CLIENTS.get(role).post("/api/v1/instances/" + instanceId + "/commands", body(type));
  }

  private static UUID accepted(ApiResponse r) {
    assertThat(r.status()).as(r.toString()).isEqualTo(202);
    return UUID.fromString(r.json().path("data").path("command_id").asString());
  }

  private static JsonNode awaitStatus(Role role, UUID id, String status) {
    JsonNode[] last = new JsonNode[1];
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = CLIENTS.get(role).get("/api/v1/commands/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              last[0] = r.json().path("data");
              assertThat(last[0].path("status").asString()).isEqualTo(status);
            });
    return last[0];
  }

  private static long commandsReceived(FakeAgent agent) {
    return agent.serverMessages().stream().filter(ServerMessage::hasCommand).count();
  }

  private static long count(String sql, Object... args) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        st.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = st.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SEC-21 permission matrix 3 roles × 5 commands with real login sessions: allowed → 202"
          + " and delivered, refused → 403 and never delivered nor stored")
  void permissionMatrix() throws Exception {
    FakeAgent agent = agent("matrix", CommandReplies::rejected);
    long rowsBefore =
        count("SELECT count(*) FROM argos.commands WHERE instance_id = ?", agent.instanceId());
    int allowed = 0;
    List<String> table = new ArrayList<>();
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR, Role.ADMIN)) {
      for (String type : TYPES) {
        ApiResponse r = request(role, agent.instanceId(), type);
        table.add(role + " " + type + " → " + r.status());
        if (ALLOWED.get(role).contains(type)) {
          UUID id = accepted(r);
          assertThat(id.version()).isEqualTo(4);
          allowed++;
          JsonNode failed = awaitStatus(role, id, "failed");
          assertThat(failed.path("error").asString()).isEqualTo("rejected");
        } else {
          assertThat(r.status()).as(role + " " + type + ": " + r).isEqualTo(403);
        }
      }
    }
    assertThat(allowed).isEqualTo(8);
    int expected = allowed;
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .untilAsserted(() -> assertThat(commandsReceived(agent)).isEqualTo(expected));
    assertThat(
            count("SELECT count(*) FROM argos.commands WHERE instance_id = ?", agent.instanceId()))
        .as(String.join("; ", table))
        .isEqualTo(rowsBefore + allowed);
    assertThat(
            AuditLog.rows(env, "COMMAND_REJECTED").stream()
                .filter(row -> "forbidden".equals(row.reason())))
        .as("operator env_dump and reload_config refusals audited")
        .hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SEC-50 / T-SEC-22 another user's command and result are 403 not_owner, an admin reads"
          + " them; ids other than UUIDv4 are 400; the result is stored without plaintext (no"
          + " sentinel in any column) yet reads back decrypted")
  void ownershipAndEncryptionAtRest() throws Exception {
    FakeAgent agent = agent("owner", c -> CommandReplies.threadDumpJson(c, SENTINEL));
    UUID id = accepted(request(Role.OPERATOR, agent.instanceId(), "thread_dump"));
    awaitStatus(Role.OPERATOR, id, "done");

    ApiTestClient otherOperator = login(Role.OPERATOR);
    for (String path : List.of("/api/v1/commands/" + id, "/api/v1/commands/" + id + "/result")) {
      ApiResponse foreign = otherOperator.get(path);
      assertThat(foreign.status()).as(path + " " + foreign).isEqualTo(403);
      assertThat(foreign.errorCode()).contains("not_owner");
      assertThat(foreign.body()).doesNotContain(SENTINEL);
      ApiResponse viewer = CLIENTS.get(Role.VIEWER).get(path);
      assertThat(viewer.status()).as(path + " " + viewer).isEqualTo(403);
      assertThat(CLIENTS.get(Role.ADMIN).get(path).status()).isEqualTo(200);
    }
    for (String bad :
        List.of("1", "00000000-0000-1000-8000-000000000000", "1-1-1-1-1", "not-a-uuid")) {
      ApiResponse r = CLIENTS.get(Role.ADMIN).get("/api/v1/commands/" + bad);
      assertThat(r.status()).as(bad + " " + r).isEqualTo(400);
    }

    ApiResponse result = CLIENTS.get(Role.OPERATOR).get("/api/v1/commands/" + id + "/result");
    assertThat(result.status()).isEqualTo(200);
    assertThat(result.body()).contains(SENTINEL);
    assertThat(result.header("Cache-Control").orElse("")).contains("no-store");

    String like = "%" + SENTINEL + "%";
    assertThat(
            count(
                "SELECT count(*) FROM argos.command_results WHERE position(convert_to(?, 'UTF8')"
                    + " IN body_enc) > 0 OR encode(body_enc, 'escape') LIKE ? OR content_type LIKE ?",
                SENTINEL,
                like,
                like))
        .as("command_results rows holding the sentinel")
        .isZero();
    assertThat(count("SELECT count(*) FROM argos.command_results WHERE command_id = ?", id))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM argos.command_results WHERE command_id = ? AND"
                    + " substring(body_enc FROM 1 FOR 3) = convert_to('v1:', 'UTF8')",
                id))
        .as("the stored body is a v1 envelope")
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM argos.commands WHERE args::text LIKE ? OR coalesce(error_code, '') LIKE ?",
                like,
                like))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM argos.audit_logs WHERE coalesce(after::text, '') LIKE ? OR"
                    + " coalesce(before::text, '') LIKE ? OR coalesce(reason, '') LIKE ?",
                like,
                like,
                like))
        .isZero();
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SEC-24 the Command carries ttl_s 60 and the server's issued_at; without a result it"
          + " expires after 120 s of server time (timeout) and a late result is not stored; it is"
          + " never delivered to the agent's next session; a COOLDOWN reply is failed/cooldown")
  void ttlTimeoutAndCooldown() throws Exception {
    FakeAgent silent = agent("silent", c -> null);
    long issuedAt = CLOCK.millis();
    UUID id = accepted(request(Role.OPERATOR, silent.instanceId(), "thread_dump"));
    Command command =
        silent.awaitServerMessage(ServerMessage::hasCommand, WAIT).orElseThrow().getCommand();
    assertThat(command.getTtlS()).isEqualTo(60);
    assertThat(command.getIssuedAtMs()).isEqualTo(issuedAt);
    awaitStatus(Role.OPERATOR, id, "sent");

    CLOCK.advance(Duration.ofSeconds(119));
    Thread.sleep(2_500);
    assertThat(awaitStatus(Role.OPERATOR, id, "sent").path("error").isNull()).isTrue();
    CLOCK.advance(Duration.ofSeconds(2));
    JsonNode expired = awaitStatus(Role.OPERATOR, id, "expired");
    assertThat(expired.path("error").asString()).isEqualTo("timeout");

    for (CommandResult late : CommandReplies.threadDumpText(command, SENTINEL)) {
      silent.send(AgentMessage.newBuilder().setCommandResult(late).build());
    }
    silent.send(
        AgentMessage.newBuilder()
            .setCommandResult(
                CommandResult.newBuilder()
                    .setCommandId(command.getCommandId())
                    .setStatus(CommandStatus.OK)
                    .setChunkCount(1)
                    .setPayload(ByteString.copyFromUtf8(SENTINEL)))
            .build());
    Thread.sleep(1_000);
    assertThat(count("SELECT count(*) FROM argos.command_results WHERE command_id = ?", id))
        .isZero();
    assertThat(awaitStatus(Role.OPERATOR, id, "expired").path("error").asString())
        .isEqualTo("timeout");

    // Session end before any result, then a new session: the expired command is not redelivered.
    UUID second = accepted(request(Role.OPERATOR, silent.instanceId(), "self_diagnosis"));
    awaitStatus(Role.OPERATOR, second, "sent");
    silent.cancelSession();
    assertThat(awaitStatus(Role.OPERATOR, second, "expired").path("error").asString())
        .isEqualTo("session_ended");
    long before = commandsReceived(silent);
    HelloResult again = silent.connect();
    assertThat(again.accepted()).as(again.toString()).isTrue();
    CLOCK.advance(Duration.ofSeconds(5));
    Thread.sleep(2_500);
    assertThat(commandsReceived(silent)).as("commands after reconnecting").isEqualTo(before);

    FakeAgent cooling = agent("cooling", CommandReplies::cooldown);
    UUID cooled = accepted(request(Role.OPERATOR, cooling.instanceId(), "thread_dump"));
    JsonNode failed = awaitStatus(Role.OPERATOR, cooled, "failed");
    assertThat(failed.path("error").asString()).isEqualTo("cooldown");
    assertThat(
            AuditLog.rows(env, "COMMAND_REJECTED").stream()
                .filter(r -> cooled.toString().equals(r.targetId())))
        .singleElement()
        .satisfies(r -> assertThat(r.reason()).isEqualTo("cooldown"));
  }
}
