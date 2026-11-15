/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.session.HelloService;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 18 §8 attacks against the whole server (gRPC collector, WAL, ingest, ClickHouse writer) while
 * a quiet instance of another app keeps sending (18 §12 T-SEC-11~15 "다른 인스턴스 적재 지연 ≤ 5초", N-10d):
 * gzip bombs and 5,001-record batches are refused before decoding (SEC-11), an instance far above
 * its quota gets {@code ACK_PARTIAL_DROP} with {@code reason="quota"} (SEC-12), 100,000 random
 * services are accepted within the instance quota (SEC-13; the dictionary cap itself is T-SV-014),
 * 1,000 new-instance Hellos of one token stop at exactly 100 new instances per app and hour
 * (SEC-14; the per-token session limits are raised for this, so that they do not stop the flood
 * first), one token rotating through 30 environments registers its own app's 8 environment variants
 * and no more (SEC-13 and SEC-14 hold per token, ADR-0010 C-155), and every transaction of the
 * quiet instance is visible to {@code argos_reader} within 5 s of its receipt. The unauthenticated
 * Hello flood (SEC-15) is {@code CollectorHardeningSecurityTest}: every fake agent here shares the
 * loopback address, which that attack blocks.
 */
@Tag("slow")
@Tag("req:SEC-11")
@Tag("req:SEC-12")
@Tag("req:SEC-13")
@Tag("req:SEC-14")
@Tag("req:C-155")
class AttackIsolationSecurityTest {

  /** Session limits of this test's server: above the 1,000 Hellos of the instance flood. */
  static final int INSTANCE_FLOOD_SESSION_LIMIT = 2 * Attacks.INSTANCE_FLOOD_INSTANCES;

  @Test
  @DisplayName(
      "T-SEC-11/T-SEC-12/T-SEC-13/T-SEC-14 gzip bombs, 5,001-record batches, a quota flood,"
          + " 100,000 services, 1,000 instances and an env rotation over 30 environments at once:"
          + " rejected or capped as specified (apps and instances of the rotating token bounded by"
          + " C-155), a quiet instance's transactions reach argos_reader within 5 s")
  void attacksDoNotDelayOtherInstances() throws Exception {
    // The per-token session limits (100 concurrent, 60 new per minute) would stop the instance
    // flood first; raised here, so that the 100 new instances per app and hour (SEC-14) is the cap
    // that stops it.
    Map<String, Object> limits =
        Map.of(
            "argos.collector.session.new-per-minute", INSTANCE_FLOOD_SESSION_LIMIT,
            "argos.collector.session.max-per-token", INSTANCE_FLOOD_SESSION_LIMIT);
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env, limits)) {
      AgentTokens.IssuedToken quietToken;
      AgentTokens.IssuedToken attackerToken;
      AgentTokens.IssuedToken instanceFloodToken;
      AgentTokens.IssuedToken envRotationToken;
      try (Connection c = env.connect(PgAccount.APP)) {
        quietToken = AgentTokens.issue(c, AgentTokens.ensureApp(c, "quiet-app", "prod"));
        attackerToken = AgentTokens.issue(c, AgentTokens.ensureApp(c, "attacker-app", "prod"));
        // An app of its own: no instance of the other attacks counts against its hourly cap.
        instanceFloodToken =
            AgentTokens.issue(c, AgentTokens.ensureApp(c, "instance-flood-app", "prod"));
        envRotationToken =
            AgentTokens.issue(c, AgentTokens.ensureApp(c, "env-rotation-app", "prod"));
      }
      MeterRegistry meters = server.bean(MeterRegistry.class);
      double quotaDropsBefore = counter(meters, "argos.collector.dropped", "reason", "quota");
      double overflowBefore = counterSum(meters, "argos.dictionary.overflow");
      byte[] bomb = Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES);

      Map<Long, Long> sentAt = new ConcurrentHashMap<>();
      Map<Long, Long> seenAt = new ConcurrentHashMap<>();
      List<String> quietFailures = new ArrayList<>();
      AtomicBoolean attacking = new AtomicBoolean(true);
      ClickHouseHttp reader = env.clickHouse(Account.READER);

      try (FakeAgent quiet = agent(env, quietToken, "quiet-app", "quiet-1", 41, false);
          FakeAgent bomber = agent(env, attackerToken, "attacker-app", "bomber", 42, true);
          FakeAgent overflow = agent(env, attackerToken, "attacker-app", "overflow", 43, false);
          FakeAgent noisy = agent(env, attackerToken, "attacker-app", "noisy", 44, false);
          FakeAgent flood = agent(env, attackerToken, "attacker-app", "dict-flood", 45, false);
          FakeAgent instances =
              agent(env, instanceFloodToken, "instance-flood-app", "instances", 46, false);
          FakeAgent rotator =
              agent(env, envRotationToken, "env-rotation-app", "rotator", 47, false)) {
        for (FakeAgent a : List.of(quiet, bomber, overflow, noisy, flood)) {
          HelloResult hello = a.connect();
          assertThat(hello.accepted()).as(a + ": " + hello).isTrue();
        }

        CompletableFuture<Void> quietSender =
            CompletableFuture.runAsync(
                () -> {
                  while (attacking.get()) {
                    TransactionRecord tx = quiet.records().transaction().build();
                    sentAt.put(tx.getTxId(), System.nanoTime());
                    CallResult r =
                        quiet.sendTransactions(quiet.batches().transactions(List.of(tx)));
                    if (r.ackStatus() != AckStatus.ACK_OK) {
                      synchronized (quietFailures) {
                        quietFailures.add(r.toString());
                      }
                    }
                    sleep(200);
                  }
                });
        CompletableFuture<Void> poller =
            CompletableFuture.runAsync(
                () -> {
                  while (true) {
                    List<List<String>> rows =
                        reader.rows(
                            "SELECT toString(tx_id) FROM argos.xlog WHERE app_id = "
                                + quietToken.appId());
                    long now = System.nanoTime();
                    for (List<String> row : rows) {
                      seenAt.putIfAbsent(Long.parseUnsignedLong(row.get(0)), now);
                    }
                    if (!attacking.get() && seenAt.keySet().containsAll(sentAt.keySet())) {
                      return;
                    }
                    sleep(100);
                  }
                });

        List<CallResult> bombs = new ArrayList<>();
        List<CallResult> overflows = new ArrayList<>();
        List<CallResult> noise = new ArrayList<>();
        List<CallResult> floodCalls = new ArrayList<>();
        List<HelloResult> instanceHellos;
        CompletableFuture<Void> bombing =
            CompletableFuture.runAsync(
                () -> {
                  for (int i = 0; i < 3; i++) {
                    bombs.add(
                        bomber.sendRaw(
                            BatchKind.TRANSACTIONS,
                            bomb,
                            FakeAgent.CallSpec.defaults()
                                .withCompression(FakeAgent.Compression.GZIP)
                                .withDeadline(Duration.ofSeconds(60))));
                  }
                });
        CompletableFuture<Void> overflowing =
            CompletableFuture.runAsync(
                () -> {
                  for (int i = 0; i < 10; i++) {
                    TransactionBatch big =
                        Attacks.oversizedTransactionBatch(overflow, Attacks.OVERFLOW_RECORDS);
                    overflows.add(overflow.sendTransactions(big));
                  }
                });
        CompletableFuture<Void> noisySending =
            CompletableFuture.runAsync(
                () -> {
                  // 5,000 records per call, back to back: far above 2,000 tx/s (bucket 4,000).
                  for (int i = 0; i < 20; i++) {
                    List<TransactionRecord> records = new ArrayList<>(5_000);
                    for (int j = 0; j < 5_000; j++) {
                      records.add(noisy.records().transaction().build());
                    }
                    noise.add(noisy.sendTransactions(noisy.batches().transactions(records)));
                  }
                });
        CompletableFuture<Void> flooding =
            CompletableFuture.runAsync(
                () -> {
                  for (TransactionBatch b :
                      Attacks.randomServiceTransactions(
                          flood, Attacks.DICTIONARY_FLOOD_SERVICES, 4_242L)) {
                    floodCalls.add(flood.sendTransactions(b));
                  }
                });
        CompletableFuture<List<HelloResult>> instanceFlood =
            CompletableFuture.supplyAsync(
                () -> Attacks.instanceFlood(instances, "flood", Attacks.INSTANCE_FLOOD_INSTANCES));
        CompletableFuture<List<HelloResult>> envRotation =
            CompletableFuture.supplyAsync(
                () -> Attacks.envRotation(rotator, "rot", Attacks.ENV_ROTATION_ENVIRONMENTS));
        CompletableFuture.allOf(
                bombing, overflowing, noisySending, flooding, instanceFlood, envRotation)
            .get(10, TimeUnit.MINUTES);
        instanceHellos = instanceFlood.get();
        List<HelloResult> envHellos = envRotation.get();
        // Keep the quiet instance sending a little longer, then let the poller finish.
        TimeUnit.SECONDS.sleep(5);
        attacking.set(false);
        quietSender.get(1, TimeUnit.MINUTES);
        poller.get(2, TimeUnit.MINUTES);

        // SEC-11: refused before decoding.
        assertThat(bombs).hasSize(3);
        for (CallResult r : bombs) {
          assertThat(r.code()).as(r.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          assertThat(r.rejectReason()).isEqualTo("message_size");
        }
        assertThat(overflows).hasSize(10);
        for (CallResult r : overflows) {
          assertThat(r.code()).as(r.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
          assertThat(r.rejectReason()).isEqualTo("batch_items");
        }
        // SEC-12: the quota removes items from the end; nothing is refused as a whole.
        assertThat(noise).allMatch(CallResult::ok);
        assertThat(noise)
            .as("ACK_PARTIAL_DROP above the quota")
            .anyMatch(
                r -> r.ackStatus() == AckStatus.ACK_PARTIAL_DROP && r.ack().getDroppedItems() > 0);
        assertThat(counter(meters, "argos.collector.dropped", "reason", "quota"))
            .isGreaterThan(quotaDropsBefore);
        // SEC-13: accepted and quota-trimmed (the dictionary cap: T-SV-014, IngestPipelineIT).
        assertThat(floodCalls).isNotEmpty().allMatch(CallResult::ok);
        // SEC-14: exactly 100 new instances per app and hour; every other Hello stops at that cap
        // (the session limits are out of the way, see above).
        long accepted = instanceHellos.stream().filter(HelloResult::accepted).count();
        assertThat(accepted).isEqualTo(100);
        assertThat(instanceHellos.subList(0, 100)).allMatch(HelloResult::accepted);
        assertThat(instanceHellos)
            .filteredOn(h -> !h.accepted())
            .hasSize(Attacks.INSTANCE_FLOOD_INSTANCES - 100)
            .allSatisfy(
                h -> {
                  assertThat(h.rejectReason()).isEqualTo(RejectReason.INSTANCE_LIMIT);
                  assertThat(h.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
                  assertThat(h.hello().getRejectMessage())
                      .contains("new instances per app and hour");
                });

        // SEC-13/SEC-14 per token (C-155): the rotating token reaches its own app and at most 8
        // environment variants; every other environment is APP_NOT_REGISTERED and creates nothing.
        int variants = HelloService.MAX_APP_VARIANTS;
        assertThat(envHellos).hasSize(Attacks.ENV_ROTATION_ENVIRONMENTS);
        assertThat(envHellos.subList(0, variants)).allMatch(HelloResult::accepted);
        assertThat(envHellos)
            .filteredOn(h -> !h.accepted())
            .hasSize(Attacks.ENV_ROTATION_ENVIRONMENTS - variants)
            .allSatisfy(
                h -> {
                  assertThat(h.rejectReason()).isEqualTo(RejectReason.APP_NOT_REGISTERED);
                  assertThat(h.code()).isEqualTo(Status.Code.OK);
                  assertThat(h.retryAfterS()).isEqualTo(300);
                });
        long rotationApps;
        long rotationInstances;
        try (Connection c = env.connect(PgAccount.APP)) {
          rotationApps =
              scalar(c, "SELECT count(*) FROM argos.apps WHERE name = 'env-rotation-app'");
          rotationInstances =
              scalar(
                  c,
                  "SELECT count(*) FROM argos.instances i JOIN argos.apps a ON a.id = i.app_id"
                      + " WHERE a.name = 'env-rotation-app'");
        }
        assertThat(rotationApps).as("the token's app and its 8 variants").isEqualTo(1 + variants);
        assertThat(rotationInstances).as("one instance per accepted Hello").isEqualTo(variants);
        assertThat(counter(meters, "argos.collector.rejected", "reason", "app_variant_limit"))
            .isEqualTo(Attacks.ENV_ROTATION_ENVIRONMENTS - variants);

        assertThat(quietFailures).as("quiet instance calls not ACK_OK").isEmpty();
        assertThat(seenAt.keySet())
            .as("every quiet transaction visible")
            .containsAll(sentAt.keySet());
        List<Long> latencies = new ArrayList<>();
        sentAt.forEach((tx, t) -> latencies.add(TimeUnit.NANOSECONDS.toMillis(seenAt.get(tx) - t)));
        latencies.sort(null);
        long max = latencies.get(latencies.size() - 1);
        long p50 = latencies.get((int) Math.ceil(0.5 * latencies.size()) - 1);
        System.out.printf(
            Locale.ROOT,
            "T-SEC-11~14 isolation (reference, shared laptop): quiet instance %d transactions,"
                + " receive -> argos_reader p50 %d ms, max %d ms; noisy dropped %d items (quota);"
                + " dictionary overflow +%.0f; instance flood accepted %d of %d; env rotation apps"
                + " %d, instances %d%n",
            latencies.size(),
            p50,
            max,
            noise.stream().filter(CallResult::ok).mapToLong(r -> r.ack().getDroppedItems()).sum(),
            counterSum(meters, "argos.dictionary.overflow") - overflowBefore,
            accepted,
            instanceHellos.size(),
            rotationApps,
            rotationInstances);
        assertThat(max)
            .as("quiet instance receive -> query, max ms (N-10d)")
            .isLessThanOrEqualTo(5_000);
      }
    }
  }

  private static FakeAgent agent(
      ServerTestEnvironment env,
      AgentTokens.IssuedToken token,
      String app,
      String instance,
      int node,
      boolean preCompressed) {
    AgentChannels.Options options =
        preCompressed
            ? AgentChannels.Options.defaults().withPreCompressedGzip()
            : AgentChannels.Options.defaults();
    FakeAgent.Builder b =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", env.collectorPort(), env.collectorCaCertificate(), options))
            .ownsChannel(true)
            .token(token.token())
            .appName(app)
            .instanceName(instance)
            .records(new Records(AgentClock.system(), node, node))
            .agentEpoch(node)
            .callDeadline(Duration.ofSeconds(60));
    if (preCompressed) {
      b.compression(FakeAgent.Compression.NONE);
    }
    return b.build();
  }

  private static long scalar(Connection c, String sql) throws java.sql.SQLException {
    try (java.sql.Statement st = c.createStatement();
        java.sql.ResultSet rs = st.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getLong(1);
    }
  }

  private static double counter(MeterRegistry meters, String name, String tag, String value) {
    Counter c = meters.find(name).tag(tag, value).counter();
    return c == null ? 0 : c.count();
  }

  private static double counterSum(MeterRegistry meters, String name) {
    return meters.find(name).counters().stream().mapToDouble(Counter::count).sum();
  }

  private static void sleep(long ms) {
    try {
      TimeUnit.MILLISECONDS.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
