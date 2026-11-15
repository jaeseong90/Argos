/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.Attacks;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-012 over the wire: the five classes of 12 §3.3 (message size, items, string length, repeated
 * length, format), each with its gRPC code, the {@code argos-reject-reason} trailer and {@code
 * argos_collector_rejected_total{reason}}, the boundary values, and nothing written to the WAL for
 * a rejected batch (ADR-0010 C-130 (2): the whole batch is rejected).
 */
@Tag("integration")
@Tag("req:SEC-11")
@Tag("req:F-S33")
class CollectorLimitsIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness collector;
  private static FakeAgent agent;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector =
        CollectorHarness.builder(env)
            // Keep the quota out of the boundary tests (T-SV-013 covers it).
            .property("argos.collector.quota.tx-per-second", 1_000_000)
            .start();
    String app = "limits-" + UUID.randomUUID().toString().substring(0, 8);
    agent =
        collector
            .agent(collector.token(app, "prod").token())
            .appName(app)
            .compression(FakeAgent.Compression.NONE)
            .build();
    assertThat(agent.connect().accepted()).isTrue();
  }

  @AfterAll
  static void stop() {
    if (agent != null) {
      agent.close();
    }
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static double rejected(String reason) {
    MeterRegistry meters = collector.bean(MeterRegistry.class);
    return Search.in(meters)
        .name("argos.collector.rejected")
        .tag("reason", reason)
        .counters()
        .stream()
        .mapToDouble(c -> c.count())
        .sum();
  }

  private static TransactionBatch batch(
      int records, UnaryOperator<TransactionRecord.Builder> change) {
    List<TransactionRecord> list = new ArrayList<>(records);
    TransactionRecord r = change.apply(agent.records().transaction()).build();
    for (int i = 0; i < records; i++) {
      list.add(r);
    }
    return agent.batches().transactions(list);
  }

  private static void refused(CallResult r, Status.Code code, String reason, double countedBefore) {
    assertThat(r.code()).as(r.toString()).isEqualTo(code);
    assertThat(r.rejectReason()).isEqualTo(reason);
    assertThat(rejected(reason)).isEqualTo(countedBefore + 1);
  }

  @Test
  @DisplayName(
      "T-SV-012 class 1: a gzip bomb (1 MB -> 1 GiB) is RESOURCE_EXHAUSTED message_size before decoding")
  void messageSizeGzip() {
    int walBefore = collector.wal().appended().size();
    double before = rejected("message_size");
    String app = "bomb-" + UUID.randomUUID().toString().substring(0, 6);
    try (FakeAgent bomber =
        FakeAgent.builder(
                collector.channel(AgentChannels.Options.defaults().withPreCompressedGzip()))
            .ownsChannel(true)
            .token(collector.token(app, "prod").token())
            .appName(app)
            .compression(FakeAgent.Compression.NONE)
            .build()) {
      assertThat(bomber.connect().accepted()).isTrue();
      CallResult r =
          bomber.sendRaw(
              BatchKind.TRANSACTIONS,
              Attacks.gzipBomb(Attacks.GZIP_BOMB_INFLATED_BYTES),
              FakeAgent.CallSpec.defaults().withCompression(FakeAgent.Compression.GZIP));
      assertThat(r.code()).as(r.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(r.rejectReason()).isEqualTo("message_size");
      assertThat(rejected("message_size")).isEqualTo(before + 1);
    }
    assertThat(collector.wal().appended()).hasSize(walBefore);
  }

  @Test
  @DisplayName(
      "T-SV-012 class 1: an uncompressed message above 4 MiB is refused by the transport, RESOURCE_EXHAUSTED and counted as message_size")
  void messageSizePlain() {
    double before = rejected("message_size");
    byte[] over = new byte[4 * 1024 * 1024 + 1];
    CallResult r = agent.sendRaw(BatchKind.TRANSACTIONS, over, FakeAgent.CallSpec.defaults());
    assertThat(r.code()).as(r.toString()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(rejected("message_size")).isEqualTo(before + 1));
  }

  @Test
  @DisplayName(
      "T-SV-012 class 2: 5,000 records are accepted, 5,001 are RESOURCE_EXHAUSTED batch_items")
  void items() {
    CallResult ok = agent.sendTransactions(batch(5_000, b -> b));
    assertThat(ok.ackStatus()).as(ok.toString()).isEqualTo(AckStatus.ACK_OK);
    int walBefore = collector.wal().appended().size();
    double before = rejected("batch_items");
    refused(
        agent.sendTransactions(Attacks.oversizedTransactionBatch(agent, Attacks.OVERFLOW_RECORDS)),
        Status.Code.RESOURCE_EXHAUSTED,
        "batch_items",
        before);
    assertThat(collector.wal().appended()).hasSize(walBefore);
  }

  @Test
  @DisplayName(
      "T-SV-012 class 3: url_path 2,048 bytes passes, 2,049 is RESOURCE_EXHAUSTED string_length")
  void strings() {
    assertThat(agent.sendTransactions(batch(1, b -> b.setUrlPath("a".repeat(2_048)))).ok())
        .isTrue();
    double before = rejected("string_length");
    refused(
        agent.sendTransactions(batch(1, b -> b.setUrlPath("a".repeat(2_049)))),
        Status.Code.RESOURCE_EXHAUSTED,
        "string_length",
        before);
  }

  @Test
  @DisplayName("T-SV-012 class 4: 64 sql_ids pass, 65 are RESOURCE_EXHAUSTED repeated_length")
  void repeated() {
    UnaryOperator<TransactionRecord.Builder> ids64 =
        b -> {
          b.clearSqlIds();
          for (int i = 1; i <= 64; i++) {
            b.addSqlIds(i);
          }
          return b;
        };
    assertThat(agent.sendTransactions(batch(1, ids64)).ok()).isTrue();
    double before = rejected("repeated_length");
    refused(
        agent.sendTransactions(batch(1, b -> ids64.apply(b).addSqlIds(65))),
        Status.Code.RESOURCE_EXHAUSTED,
        "repeated_length",
        before);
  }

  @Test
  @DisplayName(
      "T-SV-012 class 5: trace_id of 15 bytes and service_id 0 are INVALID_ARGUMENT format")
  void format() {
    double before = rejected("format");
    refused(
        agent.sendTransactions(batch(1, b -> b.setTraceId(ByteString.copyFrom(new byte[15])))),
        Status.Code.INVALID_ARGUMENT,
        "format",
        before);
    refused(
        agent.sendTransactions(batch(1, b -> b.setServiceId(0))),
        Status.Code.INVALID_ARGUMENT,
        "format",
        before + 1);
  }

  private static StatBatch counters(FakeAgent a, int n) {
    List<CounterDelta> list = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      list.add(Records.counter("limits_counter_" + i, 1));
    }
    long now = a.clock().nowMs();
    return a.batches().stats(now - Math.floorMod(now, 10_000L), List.of(), List.of(), list);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "T-SV-012 C-157 (2) over the wire: a StatBatch with 500 counters is accepted, 501 is"
          + " RESOURCE_EXHAUSTED batch_items as a whole, nothing written")
  void statCounters() {
    CallResult ok = agent.sendStats(counters(agent, LimitsChecker.MAX_STAT_COUNTERS_PER_BATCH));
    assertThat(ok.ackStatus()).as(ok.toString()).isEqualTo(AckStatus.ACK_OK);
    assertThat(ok.ack().getDroppedItems()).isZero();
    int walBefore = collector.wal().appended().size();
    double before = rejected("batch_items");
    refused(
        agent.sendStats(counters(agent, LimitsChecker.MAX_STAT_COUNTERS_PER_BATCH + 1)),
        Status.Code.RESOURCE_EXHAUSTED,
        "batch_items",
        before);
    assertThat(collector.wal().appended()).hasSize(walBefore);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (2) over the wire: a Hello with 256 remote_config_allow and 256 instrumentation"
          + " modules is accepted; 257 of either is refused (REJECT_REASON_UNSPECIFIED,"
          + " RESOURCE_EXHAUSTED, counted repeated_length) and registers nothing")
  void helloLists() {
    String app = "lists-" + UUID.randomUUID().toString().substring(0, 8);
    try (FakeAgent a = collector.agent(collector.token(app, "prod").token()).appName(app).build()) {
      AgentHello.Builder full = a.helloTemplate().toBuilder().clearRemoteConfigAllow();
      full.clearInstrumentationModules();
      for (int i = 0; i < LimitsChecker.MAX_REMOTE_CONFIG_ALLOW; i++) {
        full.addRemoteConfigAllow("key." + i);
      }
      for (int i = 0; i < LimitsChecker.MAX_INSTRUMENTATION_MODULES; i++) {
        full.addInstrumentationModules("module-" + i);
      }
      assertThat(a.connect(full.setInstanceName("lists:ok").build()).accepted()).isTrue();
      for (AgentHello over :
          List.of(
              full.clone().setInstanceName("lists:allow").addRemoteConfigAllow("key.x").build(),
              full.clone()
                  .setInstanceName("lists:modules")
                  .addInstrumentationModules("module-x")
                  .build())) {
        double before = rejected("repeated_length");
        HelloResult r = a.connect(over);
        assertThat(r.accepted()).as(r.toString()).isFalse();
        assertThat(r.rejectReason()).isEqualTo(RejectReason.REJECT_REASON_UNSPECIFIED);
        assertThat(r.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
        assertThat(rejected("repeated_length")).isEqualTo(before + 1);
      }
    }
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (2) over the wire: an InstanceUpdate with 257 connectors is dropped and counted as"
          + " repeated_length, the session goes on; one with 256 connectors is applied")
  void instanceUpdateConnectors() throws Exception {
    String app = "conn-" + UUID.randomUUID().toString().substring(0, 8);
    try (FakeAgent a =
        collector
            .agent(collector.token(app, "prod").token())
            .appName(app)
            .instanceName("conn:1")
            .build()) {
      HelloResult hello = a.connect();
      assertThat(hello.accepted()).isTrue();
      int instanceId = hello.hello().getInstanceId();
      double before = rejected("repeated_length");
      a.sendInstanceUpdate(connectorsUpdate(LimitsChecker.MAX_CONNECTORS + 1, "7.7.7"));
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(rejected("repeated_length")).isEqualTo(before + 1));
      assertThat(versionTag(instanceId)).as("not applied").isNotEqualTo("7.7.7");
      a.sendInstanceUpdate(connectorsUpdate(LimitsChecker.MAX_CONNECTORS, "7.7.7"));
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(versionTag(instanceId)).isEqualTo("7.7.7"));
      assertThat(rejected("repeated_length")).isEqualTo(before + 1);
      assertThat(a.sessionOpen()).isTrue();
      assertThat(
              a.sendTransactions(
                      a.batches().transactions(List.of(a.records().transaction().build())))
                  .ok())
          .isTrue();
    }
  }

  private static InstanceUpdate connectorsUpdate(int connectors, String appVersion) {
    InstanceUpdate.Builder u = InstanceUpdate.newBuilder().setAppVersion(appVersion);
    for (int i = 0; i < connectors; i++) {
      u.addConnectors("http-nio-" + i);
    }
    return u.build();
  }

  private static String versionTag(int instanceId) throws Exception {
    try (java.sql.Connection c = env.connect(ServerTestEnvironment.PgAccount.APP);
        java.sql.PreparedStatement ps =
            c.prepareStatement("SELECT version_tag FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  @Test
  @DisplayName(
      "C-143 (1) over the wire: an unknown kind drops the record, ACK_PARTIAL_DROP, counted")
  void unknownEnum() {
    TransactionBatch b =
        agent
            .batches()
            .transactions(
                List.of(
                    agent.records().transaction().build(),
                    agent.records().transaction().setKindValue(42).build()));
    CallResult r = agent.sendTransactions(b);
    assertThat(r.ackStatus()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
    assertThat(r.ack().getDroppedItems()).isEqualTo(1);
    MeterRegistry meters = collector.bean(MeterRegistry.class);
    assertThat(
            meters
                .get("argos.collector.unknown.enum")
                .tag("field", "transaction.kind")
                .counter()
                .count())
        .isGreaterThanOrEqualTo(1);
  }
}
