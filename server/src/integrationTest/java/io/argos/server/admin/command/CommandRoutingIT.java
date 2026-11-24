/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ServerMessage;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * T-SV-041 on a real server, PostgreSQL and the real gRPC listener over TLS with protocol-level
 * fake agents (WO-26): a thread dump requested through the REST API travels as a closed-list {@code
 * Command} on the agent's session stream, the fake agent's chunked {@code CommandResult} comes back
 * through the session handler, is stored gzip-compressed and envelope-encrypted (the bytes in
 * PostgreSQL are not the plaintext) and is read back parsed; a result above one chunk (256 KiB) is
 * assembled; one command per instance is in flight; the end of the agent's session expires its
 * command, after which the instance is {@code 409 instance_offline}. The real agent's side of the
 * round trip (WO-17) is tested against this server by ui/e2e/specs/agent-commands.spec.ts
 * (T-AG-054, T-SEC-24, T-AG-072, T-SEC-21 (stack): shop-boot3 with the agent jar, thread dump with
 * virtual threads, thread stack, cooldown, environment dump, self diagnosis, reload) and against
 * FakeCollector by agent-testing io.argos.agent.testing.command (CommandRoundTripTest).
 */
@Tag("integration")
@Tag("req:F-A12")
@Tag("req:SEC-21")
@Tag("req:SEC-22")
@Tag("req:SEC-24")
class CommandRoutingIT {

  private static final String SENTINEL = "ARGOS_SEED_cmdit" + Long.toHexString(System.nanoTime());
  private static final Duration WAIT = Duration.ofSeconds(15);

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ManagedChannel channel;
  private static ApiTestClient operator;
  private static ApiTestClient admin;
  private static String appName;
  private static AgentTokens.IssuedToken token;
  private static final List<FakeAgent> AGENTS = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Path dir = Files.createTempDirectory("argos-command-it");
    TestMasterKey key = TestMasterKey.create(dir);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put(key.property(), key.file().toString());
    p.put("argos.ratelimit.api-per-user-per-second", "100000");
    p.put("argos.ratelimit.api-burst-per-user", "100000");
    server = ArgosTestServer.start(env, p);
    channel = AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate());
    operator = login(Role.OPERATOR);
    admin = login(Role.ADMIN);
    appName = "cmd-it-" + Long.toHexString(System.nanoTime());
    try (Connection c = env.connect(PgAccount.APP)) {
      int appId = AgentTokens.ensureApp(c, appName, "prod");
      token = AgentTokens.issue(c, appId);
    }
  }

  @AfterAll
  static void stop() {
    AGENTS.forEach(FakeAgent::close);
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  private static FakeAgent agent(
      String suffix, Function<Command, List<io.argos.proto.v1.CommandResult>> replies) {
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

  private static UUID request(ApiTestClient client, int instanceId, String body) {
    ApiResponse r = client.post("/api/v1/instances/" + instanceId + "/commands", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(202);
    JsonNode data = r.json().path("data");
    assertThat(data.path("status").asString()).isEqualTo("queued");
    assertThat(data.path("ttl_s").asInt()).isEqualTo(60);
    UUID id = UUID.fromString(data.path("command_id").asString());
    assertThat(id.version()).isEqualTo(4);
    return id;
  }

  private static JsonNode awaitStatus(ApiTestClient client, UUID id, String status) {
    JsonNode[] last = new JsonNode[1];
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = client.get("/api/v1/commands/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              last[0] = r.json().path("data");
              assertThat(last[0].path("status").asString()).isEqualTo(status);
            });
    return last[0];
  }

  /** {@code command_results} of a command, read directly: body bytes and stored size. */
  private static Optional<Object[]> storedResult(UUID id) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT body_enc, body_size, truncated, content_type FROM argos.command_results"
                    + " WHERE command_id = ?")) {
      st.setObject(1, id);
      try (ResultSet rs = st.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Object[] {rs.getBytes(1), rs.getInt(2), rs.getBoolean(3), rs.getString(4)});
      }
    }
  }

  @Test
  @DisplayName(
      "T-SV-041 a thread dump round trip through the REST API and a fake agent: closed-list"
          + " Command (ttl 60 s) on the session stream, result stored encrypted (no plaintext in"
          + " PostgreSQL), read back parsed with Cache-Control no-store; audited end to end")
  void threadDumpRoundTrip() throws Exception {
    FakeAgent agent = agent("dump", c -> CommandReplies.threadDumpText(c, SENTINEL));
    UUID id = request(operator, agent.instanceId(), "{\"type\":\"thread_dump\"}");

    Optional<ServerMessage> received = agent.awaitServerMessage(ServerMessage::hasCommand, WAIT);
    assertThat(received).isPresent();
    Command command = received.get().getCommand();
    assertThat(command.getCommandId()).isEqualTo(id.toString());
    assertThat(command.getType()).isEqualTo(CommandType.THREAD_DUMP);
    assertThat(command.getTtlS()).isEqualTo(60);
    assertThat(command.getArgsMap()).isEmpty();

    JsonNode done = awaitStatus(operator, id, "done");
    assertThat(done.path("instance_id").asInt()).isEqualTo(agent.instanceId());
    assertThat(done.path("type").asString()).isEqualTo("thread_dump");
    int size =
        CommandReplies.threadDumpTextPayload(SENTINEL).getBytes(StandardCharsets.UTF_8).length;
    assertThat(done.path("result_size").asInt()).isEqualTo(size);

    Object[] stored = storedResult(id).orElseThrow();
    String body = new String((byte[]) stored[0], StandardCharsets.ISO_8859_1);
    assertThat(body).startsWith("v1:").doesNotContain(SENTINEL).doesNotContain("java.lang.Thread");
    assertThat(stored[1]).isEqualTo(size);
    assertThat(stored[3]).isEqualTo("text/plain");

    ApiResponse result = operator.get("/api/v1/commands/" + id + "/result");
    assertThat(result.status()).as(result.toString()).isEqualTo(200);
    assertThat(result.header("Cache-Control").orElse("")).contains("no-store");
    JsonNode data = result.json().path("data");
    assertThat(data.path("format").asString()).isEqualTo("json");
    assertThat(data.path("threads")).hasSize(3);
    assertThat(data.path("threads").get(0).path("name").asString()).isEqualTo("main-" + SENTINEL);
    assertThat(data.path("threads").get(0).path("frames").get(0).asString()).contains(SENTINEL);
    assertThat(data.path("threads").get(2).path("virtual").asBoolean()).isTrue();

    ApiResponse list = operator.get("/api/v1/instances/" + agent.instanceId() + "/commands");
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.json().path("data").get(0).path("command_id").asString())
        .isEqualTo(id.toString());

    List<String> actions = new ArrayList<>();
    for (AuditLog.Row row : AuditLog.all(env)) {
      if (id.toString().equals(row.targetId())) {
        actions.add(row.action());
        assertThat(String.valueOf(row.after())).doesNotContain(SENTINEL);
      }
    }
    assertThat(actions)
        .containsSubsequence(
            "COMMAND_REQUESTED",
            "COMMAND_SENT",
            "COMMAND_COMPLETED",
            "COMMAND_RESULT_VIEWED",
            "SENSITIVE_VIEW");
  }

  @Test
  @DisplayName(
      "T-SV-041i (WO-37) GET /commands/{id} and GET /instances/{id}/commands carry"
          + " requested_by_name (the requester's user name, 05 §2.7-4 history); after the user is"
          + " deleted both show the anonymised deleted-<id> (C-231 (5)), requested_by unchanged")
  void requesterNameInHistory() {
    TestUsers.TestUser user =
        new TestUsers(env).create(Role.OPERATOR, o -> o.mustChangePassword(false));
    ApiTestClient requester = server.client();
    ApiResponse login = requester.login(user.username(), user.password());
    assertThat(login.status()).as(login.toString()).isEqualTo(200);
    FakeAgent agent = agent("names", c -> CommandReplies.threadDumpJson(c, SENTINEL));
    UUID id = request(requester, agent.instanceId(), "{\"type\":\"thread_dump\"}");

    JsonNode done = awaitStatus(requester, id, "done");
    assertThat(done.path("requested_by").asString()).isEqualTo(user.id().toString());
    assertThat(done.path("requested_by_name").asString()).isEqualTo(user.username());
    JsonNode history = commandIn(operator, agent.instanceId(), id);
    assertThat(history.path("requested_by_name").asString()).isEqualTo(user.username());
    assertThat(history.path("result_size").asInt()).isPositive();
    assertThat(history.path("requested_at").asString()).isNotEmpty();

    ApiResponse deleted = admin.delete("/api/v1/admin/users/" + user.id());
    assertThat(deleted.status()).as(deleted.toString()).isEqualTo(204);
    String anonymised = "deleted-" + user.id();
    ApiResponse single = admin.get("/api/v1/commands/" + id);
    assertThat(single.status()).as(single.toString()).isEqualTo(200);
    assertThat(single.json().path("data").path("requested_by_name").asString())
        .isEqualTo(anonymised);
    assertThat(single.json().path("data").path("requested_by").asString())
        .isEqualTo(user.id().toString());
    assertThat(commandIn(operator, agent.instanceId(), id).path("requested_by_name").asString())
        .isEqualTo(anonymised);
    assertThat(single.body()).doesNotContain(user.username());
  }

  /** The command {@code id} in {@code GET /instances/{instanceId}/commands}. */
  private static JsonNode commandIn(ApiTestClient client, int instanceId, UUID id) {
    ApiResponse list = client.get("/api/v1/instances/" + instanceId + "/commands");
    assertThat(list.status()).as(list.toString()).isEqualTo(200);
    for (JsonNode c : list.json().path("data")) {
      if (id.toString().equals(c.path("command_id").asString())) {
        return c;
      }
    }
    throw new AssertionError("command " + id + " not in the history: " + list.body());
  }

  @Test
  @DisplayName(
      "T-SV-041 a result of three chunks (600 KiB > one 256 KiB chunk) is assembled in order;"
          + " format=text returns it whole")
  void chunkedResult() {
    FakeAgent agent = agent("chunks", c -> CommandReplies.chunked(c, 600 * 1024, SENTINEL));
    UUID id = request(operator, agent.instanceId(), "{\"type\":\"self_diagnosis\"}");
    JsonNode done = awaitStatus(operator, id, "done");
    assertThat(done.path("result_size").asInt()).isEqualTo(600 * 1024);
    assertThat(done.path("truncated").asBoolean()).isFalse();
    ApiResponse result = operator.get("/api/v1/commands/" + id + "/result?format=text");
    assertThat(result.status()).as(result.toString()).isEqualTo(200);
    String text = result.json().path("data").path("text").asString();
    assertThat(text).hasSize(600 * 1024).startsWith(SENTINEL);
    assertThat(text.substring(CommandReplies.CHUNK_BYTES)).startsWith(SENTINEL);
    assertThat(text.substring(2 * CommandReplies.CHUNK_BYTES)).startsWith(SENTINEL);
  }

  @Test
  @DisplayName(
      "T-SV-041 one command in flight per instance (409 invalid_state); the end of the agent's"
          + " session expires it (session_ended), then the instance is 409 instance_offline;"
          + " another user's command is 403 not_owner, an administrator sees it")
  void inFlightSessionEndAndOwnership() {
    FakeAgent agent = agent("silent", c -> null);
    UUID id = request(operator, agent.instanceId(), "{\"type\":\"thread_dump\"}");
    awaitStatus(operator, id, "sent");

    ApiResponse second =
        operator.post(
            "/api/v1/instances/" + agent.instanceId() + "/commands", "{\"type\":\"thread_dump\"}");
    assertThat(second.status()).as(second.toString()).isEqualTo(409);
    assertThat(second.errorCode()).contains("invalid_state");

    ApiTestClient otherOperator = login(Role.OPERATOR);
    ApiResponse foreign = otherOperator.get("/api/v1/commands/" + id);
    assertThat(foreign.status()).as(foreign.toString()).isEqualTo(403);
    assertThat(foreign.errorCode()).contains("not_owner");
    assertThat(admin.get("/api/v1/commands/" + id).status()).isEqualTo(200);

    agent.cancelSession();
    JsonNode expired = awaitStatus(operator, id, "expired");
    assertThat(expired.path("error").asString()).isEqualTo("session_ended");
    assertExpiredRow(id, "session_ended", agent.instanceId());

    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              ApiResponse offline =
                  operator.post(
                      "/api/v1/instances/" + agent.instanceId() + "/commands",
                      "{\"type\":\"thread_dump\"}");
              assertThat(offline.status()).as(offline.toString()).isEqualTo(409);
              assertThat(offline.errorCode()).contains("instance_offline");
            });
    ApiResponse unknown =
        operator.post("/api/v1/instances/987654/commands", "{\"type\":\"thread_dump\"}");
    assertThat(unknown.status()).as(unknown.toString()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "T-SV-041 / T-SEC-80c a command whose TTL ran out on the agent (ttl_s measured from the"
          + " agent's receive time, 16 §5.2: the agent answers EXPIRED instead of running it) ends"
          + " expired with error agent_expired, no result stored, and an audit_logs row"
          + " COMMAND_EXPIRED (system actor, result success, reason agent_expired)")
  void agentTtlExpiryIsAudited() throws Exception {
    FakeAgent agent =
        agent(
            "ttl",
            c ->
                List.of(
                    CommandResult.newBuilder()
                        .setCommandId(c.getCommandId())
                        .setStatus(CommandStatus.EXPIRED)
                        .setChunkIndex(0)
                        .setChunkCount(1)
                        .setError("Command TTL elapsed before it ran")
                        .build()));
    UUID id = request(operator, agent.instanceId(), "{\"type\":\"thread_dump\"}");
    JsonNode expired = awaitStatus(operator, id, "expired");
    assertThat(expired.path("error").asString()).isEqualTo("agent_expired");
    assertThat(storedResult(id)).isEmpty();
    assertExpiredRow(id, "agent_expired", agent.instanceId());
  }

  /** The {@code COMMAND_EXPIRED} row of a command: system actor, success with the reason. */
  private static void assertExpiredRow(UUID id, String reason, int instanceId) {
    Awaitility.await()
        .atMost(WAIT.toMillis(), TimeUnit.MILLISECONDS)
        .pollInterval(100, TimeUnit.MILLISECONDS)
        .untilAsserted(
            () -> {
              List<AuditLog.Row> rows = new ArrayList<>();
              for (AuditLog.Row row : AuditLog.rows(env, "COMMAND_EXPIRED")) {
                if (id.toString().equals(row.targetId())) {
                  rows.add(row);
                }
              }
              assertThat(rows).hasSize(1);
              AuditLog.Row row = rows.get(0);
              assertThat(row.actorType()).isEqualTo("system");
              assertThat(row.result()).isEqualTo("success");
              assertThat(row.reason()).isEqualTo(reason);
              assertThat(row.after()).matches("(?s).*\"instance_id\":\\s*" + instanceId + "\\b.*");
            });
  }
}
