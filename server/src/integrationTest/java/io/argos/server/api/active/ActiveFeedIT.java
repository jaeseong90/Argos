/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.active;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.alert.TestMasterKey;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.ActiveFeed;
import io.argos.server.testing.fakeagent.ActiveFeedScenario;
import io.argos.server.testing.fakeagent.AgentTokens;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The WO-35 active-transaction feed ({@link ActiveFeed}, the dev-only {@code :server:activeFeedJar}
 * of the E2E stack) against the whole server with real PostgreSQL and ClickHouse: the feed's
 * FakeAgent sessions reach the real plaintext collector ({@code allow-plaintext}, as on the E2E
 * stack), and one snapshot round is visible in {@code GET /api/v1/apps/{appId}/active?kind=ALL}
 * exactly as {@link ActiveFeedScenario} computes it for that round. This proves that the screen of
 * WO-35 sees what its E2E expects (four elapsed buckets per instance, five states, resolved names,
 * XSS payloads returned verbatim, user id and address modes), and that the scenario is
 * deterministic: the items are a function of the feed start, the round and the snapshot time.
 *
 * <p>The feed runs with a 10-minute interval, so the server holds the first round for the whole
 * test. Dictionary names reach PostgreSQL asynchronously: the check polls until no name is {@code
 * <unknown:...>}.
 *
 * <p>T-SV-041j (WO-37): with {@code --commands} the feed answers the five remote commands through
 * the real collector, session stream, encrypted result store (a test master key) and REST API, as
 * the E2E command round trip of the instance and dump screens expects.
 */
@Tag("integration")
@Tag("req:F-U04")
class ActiveFeedIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ApiTestClient viewer;
  private static ApiTestClient operator;
  private static ApiTestClient admin;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    // Command results are stored encrypted (T-SV-041j): the server needs a master key.
    TestMasterKey key = TestMasterKey.create(Files.createTempDirectory("argos-active-feed-it"));
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("argos.collector.tls.enabled", "false");
    properties.put("argos.collector.allow-plaintext", "true");
    properties.put(key.property(), key.file().toString());
    server = ArgosTestServer.start(env, properties);
    TestUsers.TestUser user = new TestUsers(env).create(Role.VIEWER);
    viewer = server.client();
    ApiResponse login = viewer.login(user.username(), user.password());
    assertThat(login.status()).as("login " + login.body()).isEqualTo(200);
    operator = login(Role.OPERATOR);
    admin = login(Role.ADMIN);
  }

  private static ApiTestClient login(Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role, o -> o.mustChangePassword(false));
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return client;
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  @Test
  @DisplayName(
      "T-SV-020 (WO-35 feed) the active feed through the real plaintext collector: after one"
          + " snapshot GET /apps/{id}/active?kind=ALL lists 2 instances (UP) with all 4 elapsed"
          + " buckets, the 5 states, dictionary names resolved, the XSS payloads verbatim, the user"
          + " id and address modes; every item equals the deterministic scenario of that round")
  void feedThroughRealCollector(@TempDir Path dir) throws Exception {
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token =
          AgentTokens.issue(
              c, AgentTokens.ensureApp(c, ActiveFeedScenario.APP, ActiveFeedScenario.ENV));
    }
    int appId = token.appId();
    Path tokenFile = dir.resolve("agent.token");
    Files.writeString(tokenFile, token.token(), StandardCharsets.UTF_8);
    Path readyFile = dir.resolve("ready").resolve("active-feed.json");
    List<String> log = Collections.synchronizedList(new ArrayList<>());

    ActiveFeed.Options options =
        ActiveFeed.Options.parse(
            "--target",
            "127.0.0.1:" + server.collectorPort(),
            "--token-file",
            tokenFile.toString(),
            "--instances",
            "e2e-active-1,e2e-active-2",
            "--ready-file",
            readyFile.toString(),
            "--interval",
            "10m",
            "--duration=5m");
    assertThat(options.interval().toMinutes()).isEqualTo(10);
    assertThat(options.app()).isEqualTo("e2e-active");

    try (ActiveFeed feed = ActiveFeed.start(options, log::add)) {
      ActiveFeedScenario scenario = feed.scenario();
      List<Integer> ids = feed.instanceIds();
      assertThat(ids).hasSize(2).doesNotContain(0).doesNotHaveDuplicates();
      ActiveFeed.Round round = feed.lastRound();
      assertThat(round).isNotNull();
      assertThat(round.number()).isZero();

      // The ready file: the scenario with the instance ids (read by
      // ui/e2e/stack/seed/activeFeed.ts).
      assertThat(readyFile).exists();
      JsonNode ready = JSON.readTree(Files.readString(readyFile, StandardCharsets.UTF_8));
      assertThat(ready.path("app").asString()).isEqualTo("e2e-active");
      assertThat(ready.path("feed_start_ms").asLong()).isEqualTo(scenario.feedStartMs());
      assertThat(ready.path("interval_ms").asLong()).isEqualTo(600_000L);
      assertThat(ready.path("instances")).hasSize(2);
      for (int i = 0; i < 2; i++) {
        JsonNode inst = ready.path("instances").get(i);
        assertThat(inst.path("instance_id").asInt()).isEqualTo(ids.get(i));
        assertThat(inst.path("name").asString()).isEqualTo("e2e-active-" + (i + 1));
        assertThat(inst.path("slots")).hasSize(scenario.profile(i).slots().size());
      }
      assertThat(ready.path("instances").get(0).path("slots").size())
          .as("instance 2 has fewer items than instance 1")
          .isGreaterThan(ready.path("instances").get(1).path("slots").size());
      assertThat(ready.path("instances").get(0).path("slots").get(1).path("service").asString())
          .isEqualTo(ActiveFeedScenario.XSS_SERVICE);
      // The token is in no log line and not in the ready file.
      assertThat(String.join("\n", log)).doesNotContain(token.token());
      assertThat(Files.readString(readyFile, StandardCharsets.UTF_8)).doesNotContain(token.token());

      AtomicReference<JsonNode> last = new AtomicReference<>();
      Awaitility.await()
          .pollInterval(500, TimeUnit.MILLISECONDS)
          .atMost(30, TimeUnit.SECONDS)
          .untilAsserted(
              () -> {
                JsonNode data = active(appId);
                last.set(data);
                assertThat(data.path("items")).hasSize(10);
                assertThat(data.toString())
                    .as("dictionary names resolved")
                    .doesNotContain("<unknown:");
              });
      JsonNode data = last.get();
      assertThat(feed.lastRound()).as("still the first round").isEqualTo(round);
      assertThat(Instant.parse(data.path("snapshot_at").asString()).toEpochMilli())
          .isEqualTo(round.snapshotTsMs());

      // per_instance: both instances, UP, count, max, the 4 buckets (each >= 1) as the scenario.
      JsonNode perInstance = data.path("per_instance");
      assertThat(perInstance).hasSize(2);
      for (int i = 0; i < 2; i++) {
        JsonNode p = perInstance.get(i);
        List<ActiveFeedScenario.Item> expected =
            scenario.items(i, round.number(), round.snapshotTsMs());
        long[] bands = ActiveFeedScenario.bands(expected);
        assertThat(p.path("instance_id").asInt()).isEqualTo(ids.get(i));
        assertThat(p.path("name").asString()).isEqualTo("e2e-active-" + (i + 1));
        assertThat(p.path("state").asString()).isEqualTo("UP");
        assertThat(p.path("count").asLong()).isEqualTo(expected.size());
        assertThat(p.path("truncated").asBoolean()).isFalse();
        assertThat(p.path("max_elapsed_ms").asLong()).isEqualTo(expected.get(0).elapsedMs());
        assertThat(Instant.parse(p.path("snapshot_at").asString()).toEpochMilli())
            .isEqualTo(round.snapshotTsMs());
        List<Long> buckets = new ArrayList<>();
        p.path("buckets").forEach(b -> buckets.add(b.asLong()));
        assertThat(buckets)
            .as("buckets of instance %d", i + 1)
            .containsExactly(bands[0], bands[1], bands[2], bands[3])
            .allMatch(b -> b >= 1);
      }

      // items: every scenario item, field by field, as the server shows it.
      Map<String, JsonNode> byTx = new HashMap<>();
      long previous = Long.MAX_VALUE;
      for (JsonNode item : data.path("items")) {
        byTx.put(item.path("tx_id").asString(), item);
        long elapsed = item.path("elapsed_ms").asLong();
        assertThat(elapsed).as("longest first").isLessThanOrEqualTo(previous);
        previous = elapsed;
      }
      Set<String> states = new HashSet<>();
      Set<String> kinds = new HashSet<>();
      for (int i = 0; i < 2; i++) {
        ActiveFeedScenario.Profile profile = scenario.profile(i);
        for (ActiveFeedScenario.Item e : scenario.items(i, round.number(), round.snapshotTsMs())) {
          ActiveFeedScenario.Slot s = e.slot();
          String tx = Long.toUnsignedString(e.txId());
          JsonNode item = byTx.get(tx);
          String what = profile.name() + " slot " + s.slot();
          assertThat(item).as(what).isNotNull();
          assertThat(item.path("instance_id").asInt()).as(what).isEqualTo(ids.get(i));
          assertThat(item.path("service").asString()).as(what).isEqualTo(s.service());
          assertThat(item.path("service_id").isNull()).as(what).isFalse();
          assertThat(item.path("elapsed_ms").asLong()).as(what).isEqualTo(e.elapsedMs());
          assertThat(item.path("state").asString())
              .as(what)
              .isEqualTo(ActiveFeedScenario.stateText(s.state()));
          if (s.detailKind() == null) {
            assertThat(item.path("state_detail").isNull()).as(what).isTrue();
          } else {
            assertThat(item.path("state_detail").asString()).as(what).isEqualTo(s.detail());
          }
          assertThat(item.path("thread").asString()).as(what).isEqualTo(s.thread());
          assertThat(item.path("virtual_thread").asBoolean()).as(what).isEqualTo(s.virtualThread());
          if (s.userId().isEmpty()) {
            assertThat(item.path("user_id").isNull()).as(what).isTrue();
          } else {
            assertThat(item.path("user_id").asString()).as(what).isEqualTo(s.userId());
          }
          assertThat(item.path("user_id_mode").asString())
              .as(what)
              .isEqualTo(ActiveFeedScenario.modeText(profile.userIdMode()));
          String ip = ActiveFeedScenario.shownAddress(s, profile.ipMode());
          if (ip == null) {
            assertThat(item.path("remote_ip").isNull()).as(what).isTrue();
          } else {
            assertThat(item.path("remote_ip").asString()).as(what).isEqualTo(ip);
          }
          assertThat(item.path("ip_mode").asString())
              .as(what)
              .isEqualTo(ActiveFeedScenario.modeText(profile.ipMode()));
          assertThat(item.path("kind").asString()).as(what).isEqualTo(s.kind().name());
          assertThat(Instant.parse(item.path("started_at").asString()).toEpochMilli())
              .as(what)
              .isEqualTo(e.startMs());
          states.add(item.path("state").asString());
          kinds.add(item.path("kind").asString());
        }
      }
      assertThat(states).containsExactlyInAnyOrder("SQL", "HTTP", "DBC", "METHOD", "ASYNC");
      assertThat(kinds).containsExactlyInAnyOrder("HTTP", "BATCH");

      // The XSS payloads come back verbatim (no server escaping, 14 §2.10; the UI renders text).
      String body = data.toString();
      assertThat(itemsWith(data, "service", ActiveFeedScenario.XSS_SERVICE)).isEqualTo(1);
      assertThat(itemsWith(data, "state_detail", ActiveFeedScenario.XSS_SQL)).isEqualTo(1);
      assertThat(itemsWith(data, "thread", ActiveFeedScenario.XSS_THREAD)).isEqualTo(1);
      assertThat(itemsWith(data, "user_id", ActiveFeedScenario.XSS_USER)).isEqualTo(1);
      assertThat(body).doesNotContain("&lt;").doesNotContain("&quot;");

      // Modes: instance 1 plain/full with values, instance 2 off/off without any.
      for (JsonNode item : data.path("items")) {
        if (item.path("instance_id").asInt() == ids.get(1)) {
          assertThat(item.path("user_id_mode").asString()).isEqualTo("off");
          assertThat(item.path("ip_mode").asString()).isEqualTo("off");
          assertThat(item.path("user_id").isNull()).isTrue();
          assertThat(item.path("remote_ip").isNull()).isTrue();
        } else {
          assertThat(item.path("user_id_mode").asString()).isEqualTo("plain");
          assertThat(item.path("ip_mode").asString()).isEqualTo("full");
        }
      }
      assertThat(itemsWith(data, "remote_ip", "2001:db8:0:0:0:0:0:7")).isEqualTo(1);
      assertThat(
              stream(data.path("items")).filter(i -> i.path("virtual_thread").asBoolean()).count())
          .as("one virtual thread")
          .isEqualTo(1);

      // Determinism: a second scenario of the same start computes the same round.
      ActiveFeedScenario again =
          new ActiveFeedScenario(scenario.feedStartMs(), ActiveFeedScenario.DEFAULT_INSTANCES);
      for (int i = 0; i < 2; i++) {
        assertThat(again.items(i, round.number(), round.snapshotTsMs()))
            .isEqualTo(scenario.items(i, round.number(), round.snapshotTsMs()));
      }
      // The fixed long transactions keep their ids in later rounds; fresh ones are new.
      List<ActiveFeedScenario.Item> later =
          scenario.items(0, round.number() + 1, round.snapshotTsMs() + 5_000);
      List<ActiveFeedScenario.Item> now = scenario.items(0, round.number(), round.snapshotTsMs());
      assertThat(later.get(0).txId()).isEqualTo(now.get(0).txId());
      assertThat(later.get(0).elapsedMs()).isEqualTo(now.get(0).elapsedMs() + 5_000);
      assertThat(later.get(later.size() - 1).txId()).isNotEqualTo(now.get(now.size() - 1).txId());
    }
  }

  @Test
  @DisplayName(
      "T-SV-041j (WO-37) the feed with --commands answers all five command types through the real"
          + " collector: thread_dump JSON with the tx_id of every running transaction, one"
          + " deadlock, collapsed idle threads, virtual threads, a 64-frame stack, the XSS payloads"
          + " verbatim; instance 2 truncated; thread_stack of a scenario tx (unknown tx FAILED);"
          + " self_diagnosis with sources and drops 0; env_dump (admin) masked, no raw sentinel;"
          + " reload_config text")
  void commandsThroughRealCollector(@TempDir Path dir) throws Exception {
    String app = "e2e-cmd";
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, app, ActiveFeedScenario.ENV));
    }
    Path tokenFile = dir.resolve("agent.token");
    Files.writeString(tokenFile, token.token(), StandardCharsets.UTF_8);
    Path readyFile = dir.resolve("command-feed.json");
    List<String> log = Collections.synchronizedList(new ArrayList<>());
    ActiveFeed.Options options =
        ActiveFeed.Options.parse(
            "--target",
            "127.0.0.1:" + server.collectorPort(),
            "--token-file",
            tokenFile.toString(),
            "--instances",
            "e2e-cmd-1,e2e-cmd-2",
            "--ready-file",
            readyFile.toString(),
            "--interval",
            "10m",
            "--duration=5m",
            "--app",
            app,
            "--commands");
    assertThat(options.commands()).isTrue();
    assertThat(ActiveFeed.Options.parse("--target", "h:1", "--token-file", "t").commands())
        .as("off by default (WO-35 behaviour)")
        .isFalse();

    try (ActiveFeed feed = ActiveFeed.start(options, log::add)) {
      ActiveFeedScenario scenario = feed.scenario();
      List<Integer> ids = feed.instanceIds();
      ActiveFeed.Round round = feed.lastRound();
      assertThat(round.number()).isZero();

      // The ready file: the feed's app, the command mode and the per-instance truncation.
      JsonNode ready = JSON.readTree(Files.readString(readyFile, StandardCharsets.UTF_8));
      assertThat(ready.path("app").asString()).isEqualTo(app);
      JsonNode commands = ready.path("commands");
      assertThat(commands.path("enabled").asBoolean()).isTrue();
      assertThat(commands.path("env_secret_masked").asString())
          .isEqualTo(ActiveFeedScenario.ENV_SECRET_MASKED);
      assertThat(commands.path("idle_pool_threads").asInt())
          .isEqualTo(ActiveFeedScenario.IDLE_POOL_THREADS);
      assertThat(ready.path("instances").get(0).path("instance_id").asInt()).isEqualTo(ids.get(0));
      assertThat(ready.path("instances").get(0).path("dump_truncated").asBoolean()).isFalse();
      assertThat(ready.path("instances").get(1).path("dump_truncated").asBoolean()).isTrue();

      // thread_dump of instance 1.
      UUID dumpId = command(operator, ids.get(0), "{\"type\":\"thread_dump\"}");
      JsonNode dumpView = awaitFinal(operator, dumpId);
      assertThat(dumpView.path("status").asString()).as(dumpView.toString()).isEqualTo("done");
      assertThat(dumpView.path("truncated").asBoolean()).isFalse();
      assertThat(dumpView.path("duration_ms").isNull()).isFalse();
      JsonNode dump = result(operator, dumpId, "json");
      assertThat(dump.path("truncated").asBoolean()).isFalse();
      List<ActiveFeedScenario.Item> items = scenario.items(0, round.number(), round.snapshotTsMs());
      List<ActiveFeedScenario.DumpThread> expected = scenario.dumpThreads(0, items);
      JsonNode threads = dump.path("threads");
      assertThat(threads).hasSize(expected.size());
      Map<String, JsonNode> byTx = new HashMap<>();
      Map<Long, JsonNode> byId = new HashMap<>();
      Set<String> states = new HashSet<>();
      int virtual = 0;
      int deep = 0;
      int stateless = 0;
      for (JsonNode t : threads) {
        if (!t.path("tx_id").isNull()) {
          byTx.put(t.path("tx_id").asString(), t);
        }
        byId.put(t.path("id").asLong(), t);
        if (t.path("state").isNull()) {
          stateless++;
        } else {
          states.add(t.path("state").asString());
        }
        virtual += t.path("virtual").asBoolean() ? 1 : 0;
        deep += t.path("frames").size() == ActiveFeedScenario.DEEP_FRAMES ? 1 : 0;
      }
      assertThat(byTx).as("one thread per running transaction").hasSize(items.size());
      for (ActiveFeedScenario.Item item : items) {
        JsonNode t = byTx.get(Long.toUnsignedString(item.txId()));
        assertThat(t).as("slot " + item.slot().slot()).isNotNull();
        assertThat(t.path("id").asLong())
            .isEqualTo(ActiveFeedScenario.threadIdBase(0) + item.slot().slot());
        assertThat(t.path("name").asString())
            .isEqualTo(ActiveFeedScenario.concreteThreadName(item.slot()));
        assertThat(t.path("virtual").asBoolean()).isEqualTo(item.slot().virtualThread());
      }
      assertThat(states).contains("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING");
      assertThat(stateless).isEqualTo(1);
      assertThat(virtual).isGreaterThanOrEqualTo(2);
      assertThat(deep).isEqualTo(1);
      JsonNode deadlocks = dump.path("deadlocks");
      assertThat(deadlocks).hasSize(1);
      List<Long> cycle = new ArrayList<>();
      deadlocks.get(0).path("thread_ids").forEach(n -> cycle.add(n.asLong()));
      assertThat(cycle).isEqualTo(ActiveFeedScenario.dumpDeadlocks(0).get(0));
      JsonNode a = byId.get(cycle.get(0));
      JsonNode b = byId.get(cycle.get(1));
      assertThat(a.path("waiting_on").asString()).isEqualTo(b.path("locks_held").get(0).asString());
      assertThat(b.path("waiting_on").asString()).isEqualTo(a.path("locks_held").get(0).asString());
      assertThat(a.path("state").asString()).isEqualTo("BLOCKED");
      Map<String, Integer> stacks = new HashMap<>();
      for (JsonNode t : threads) {
        stacks.merge(t.path("frames").toString(), 1, Integer::sum);
      }
      assertThat(stacks.values()).contains(ActiveFeedScenario.IDLE_POOL_THREADS);
      String dumpText = dump.toString();
      assertThat(itemsWithName(threads, ActiveFeedScenario.XSS_DUMP_THREAD)).isEqualTo(1);
      assertThat(stream(threads).flatMap(t -> stream(t.path("frames"))).map(JsonNode::asString))
          .contains(ActiveFeedScenario.XSS_FRAME);
      assertThat(dumpText).doesNotContain("&lt;");
      // format=text is the agent's JSON as sent (the download of the dump viewer).
      JsonNode dumpRaw = result(operator, dumpId, "text");
      assertThat(dumpRaw.path("text").asString())
          .isEqualTo(ActiveFeedScenario.dumpJson(expected, ActiveFeedScenario.dumpDeadlocks(0)));

      // thread_dump of instance 2: the agent reports it truncated.
      UUID truncatedId = command(operator, ids.get(1), "{\"type\":\"thread_dump\"}");
      JsonNode truncatedView = awaitFinal(operator, truncatedId);
      assertThat(truncatedView.path("status").asString()).isEqualTo("done");
      assertThat(truncatedView.path("truncated").asBoolean()).isTrue();
      assertThat(result(operator, truncatedId, "json").path("truncated").asBoolean()).isTrue();

      // thread_stack of a running transaction, and of an unknown one.
      ActiveFeedScenario.Item longest = items.get(0);
      String tx = Long.toUnsignedString(longest.txId());
      UUID stackId =
          command(
              operator,
              ids.get(0),
              "{\"type\":\"thread_stack\",\"args\":{\"tx_id\":\"" + tx + "\"}}");
      assertThat(awaitFinal(operator, stackId).path("status").asString()).isEqualTo("done");
      JsonNode stack = result(operator, stackId, "json");
      assertThat(stack.path("threads")).hasSize(1);
      assertThat(stack.path("threads").get(0).path("tx_id").asString()).isEqualTo(tx);
      assertThat(stack.path("threads").get(0).path("name").asString())
          .isEqualTo(ActiveFeedScenario.concreteThreadName(longest.slot()));
      assertThat(stack.path("threads").get(0).path("frames").size()).isPositive();
      UUID unknownId =
          command(operator, ids.get(0), "{\"type\":\"thread_stack\",\"args\":{\"tx_id\":\"1\"}}");
      JsonNode unknown = awaitFinal(operator, unknownId);
      assertThat(unknown.path("status").asString()).isEqualTo("failed");
      assertThat(unknown.path("error").asString()).isEqualTo("agent_failed");

      // self_diagnosis in the agent's layout (C-234 (1)): format-1 keys, modules with reason,
      // effective_config as a map key -> {value, source}.
      UUID diagId = command(operator, ids.get(0), "{\"type\":\"self_diagnosis\"}");
      assertThat(awaitFinal(operator, diagId).path("status").asString()).isEqualTo("done");
      JsonNode diag = JSON.readTree(result(operator, diagId, "json").path("text").asString());
      assertThat(diag.path("format").asInt()).isEqualTo(1);
      assertThat(diag.path("agent").path("version").asString()).isNotEmpty();
      assertThat(diag.path("modules")).hasSize(ActiveFeedScenario.MODULES.size());
      assertThat(diag.path("modules").get(4).path("state").asString())
          .isEqualTo("DISABLED_BY_CONFIG");
      assertThat(diag.path("modules").get(4).path("reason").asString())
          .startsWith("instrumentation.disabled");
      assertThat(diag.path("modules").get(0).has("reason")).isFalse();
      assertThat(diag.path("effective_config").isObject()).isTrue();
      Set<String> sources = new HashSet<>();
      diag.path("effective_config").forEach(e -> sources.add(e.path("source").asString()));
      assertThat(sources).containsExactlyInAnyOrder("file", "env", "remote", "default");
      assertThat(diag.toString()).doesNotContain(ActiveFeedScenario.ENV_SECRET_RAW);

      // env_dump: administrators only; masked values, never the raw sentinel.
      ApiResponse forbidden =
          operator.post("/api/v1/instances/" + ids.get(0) + "/commands", "{\"type\":\"env_dump\"}");
      assertThat(forbidden.status()).as(forbidden.toString()).isEqualTo(403);
      UUID envId = command(admin, ids.get(0), "{\"type\":\"env_dump\"}");
      assertThat(awaitFinal(admin, envId).path("status").asString()).isEqualTo("done");
      JsonNode envResult = result(admin, envId, "json");
      assertThat(envResult.path("content_type").asString()).startsWith("text/plain");
      String envText = envResult.path("text").asString();
      // The agent's sections (C-234 (1)).
      assertThat(envText.lines())
          .contains("DB_PASSWORD=" + ActiveFeedScenario.ENV_SECRET_MASKED)
          .contains("JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64")
          .contains("# env (7)", "# properties (6)", "# args (6)");
      assertThat(envText).doesNotContain(ActiveFeedScenario.ENV_SECRET_RAW);

      // reload_config: a short text.
      UUID reloadId = command(admin, ids.get(0), "{\"type\":\"reload_config\"}");
      assertThat(awaitFinal(admin, reloadId).path("status").asString()).isEqualTo("done");
      assertThat(result(admin, reloadId, "json").path("text").asString())
          .startsWith("Configuration reloaded");

      // The history lists them with the requester's name; no token in the feed's log.
      ApiResponse history = operator.get("/api/v1/instances/" + ids.get(0) + "/commands");
      assertThat(history.status()).isEqualTo(200);
      assertThat(history.json().path("data").size()).isGreaterThanOrEqualTo(6);
      assertThat(history.json().path("data").get(0).path("requested_by_name").asString())
          .isNotEmpty();
      assertThat(String.join("\n", log)).doesNotContain(token.token());
    }
  }

  private static UUID command(ApiTestClient client, int instanceId, String body) {
    ApiResponse r = client.post("/api/v1/instances/" + instanceId + "/commands", body);
    assertThat(r.status()).as(r.toString()).isEqualTo(202);
    return UUID.fromString(r.json().path("data").path("command_id").asString());
  }

  private static JsonNode awaitFinal(ApiTestClient client, UUID id) {
    AtomicReference<JsonNode> last = new AtomicReference<>();
    Awaitility.await()
        .pollInterval(250, TimeUnit.MILLISECONDS)
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              ApiResponse r = client.get("/api/v1/commands/" + id);
              assertThat(r.status()).as(r.toString()).isEqualTo(200);
              last.set(r.json().path("data"));
              assertThat(last.get().path("status").asString()).isIn("done", "failed", "expired");
            });
    return last.get();
  }

  private static JsonNode result(ApiTestClient client, UUID id, String format) {
    ApiResponse r = client.get("/api/v1/commands/" + id + "/result?format=" + format);
    assertThat(r.status()).as(r.toString()).isEqualTo(200);
    return r.json().path("data");
  }

  private static long itemsWithName(JsonNode threads, String name) {
    return stream(threads).filter(t -> name.equals(t.path("name").asString())).count();
  }

  private static JsonNode active(int appId) {
    ApiResponse r = viewer.get("/api/v1/apps/" + appId + "/active?kind=ALL");
    assertThat(r.status()).as("GET /active " + r.body()).isEqualTo(200);
    return r.json().path("data");
  }

  private static long itemsWith(JsonNode data, String field, String value) {
    return stream(data.path("items")).filter(i -> value.equals(i.path(field).asString())).count();
  }

  private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    List<JsonNode> list = new ArrayList<>();
    array.forEach(list::add);
    return list.stream();
  }
}
