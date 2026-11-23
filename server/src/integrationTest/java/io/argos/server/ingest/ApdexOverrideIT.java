/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.AgentServerStack.assertAckOk;
import static io.argos.server.ingest.IngestFixtures.session;
import static io.argos.server.ingest.IngestFixtures.tx;
import static io.argos.server.ingest.IngestFixtures.txId;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.ingest.IngestStore.ApdexChange;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.postgres.ingest.JdbcIngestStore;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.wal.api.WalRecordType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * T-SV-017 and T-ACC-17 (D-17, F-S16, 12 §3.5 (5), ADR-0010 C-129 (3)): the Apdex T is stamped per
 * row at receipt and a change applies from then on.
 *
 * <ul>
 *   <li>Through the whole server: transactions of an earlier minute are received with T = 500;
 *       {@link ApdexChangeService} sets the app's T to 1,000 and one service's override to 3,000;
 *       transactions of a later minute carry 1,000, or 3,000 for that service, row by row, the
 *       earlier rows keep 500, and {@code app_stat_1m} counts satisfied and tolerating with each
 *       row's T. Each change is one {@code apdex} event in {@code GET /apps/{id}/events?type=apdex}
 *       and in the dashboard's {@code events[]} (the UI marker).
 *   <li>Through the WAL: a record received with T = 800 and a service override of 2,500 that was
 *       not loaded before a crash is loaded by the recovery with those values, although the T and
 *       the override changed in between (the snapshot of the receipt travels in the record, {@code
 *       WalDataPayload} format 2).
 * </ul>
 */
@Tag("integration")
@Tag("req:D-17")
@Tag("req:F-S16")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApdexOverrideIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String S1 = "GET /api/orders/{id}";
  private static final String S2 = "GET /api/reports/{id}";
  private static final long[] ELAPSED_MS = {400, 700, 1_500, 2_500, 4_500};

  private AgentServerStack stack;

  @TempDir Path temp;

  @BeforeAll
  void start() {
    stack = AgentServerStack.start();
  }

  @AfterAll
  void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  /** The transactions of both services in the minute starting at {@code minuteMs}. */
  private static List<TransactionRecord> minute(Records records, long minuteMs) {
    List<TransactionRecord> out = new ArrayList<>();
    int i = 0;
    for (String service : List.of(S1, S2)) {
      for (long ms : ELAPSED_MS) {
        out.add(records.http(service, minuteMs + 5_000 + 1_000L * i++, ms * 1_000).build());
      }
    }
    return out;
  }

  private List<List<String>> apdexOfMinute(int appId, long minuteMs) {
    return stack
        .reader()
        .rows(
            "SELECT sum(count), sum(apdex_satisfied), sum(apdex_tolerating) FROM argos.app_stat_1m"
                + " WHERE app_id = "
                + appId
                + " AND minute = toDateTime("
                + minuteMs / 1_000
                + ", 'UTC')");
  }

  private List<String> stampsOfMinute(int appId, long minuteMs, long serviceId) {
    return stack
        .reader()
        .rows(
            "SELECT apdex_t_ms FROM argos.xlog WHERE app_id = "
                + appId
                + " AND service_id = "
                + Long.toUnsignedString(serviceId)
                + " AND toStartOfMinute(ts) = toDateTime("
                + minuteMs / 1_000
                + ", 'UTC') ORDER BY ts")
        .stream()
        .map(r -> r.get(0))
        .toList();
  }

  @Test
  @Tag("req:T-ACC-17")
  @DisplayName(
      "T-SV-017/T-ACC-17 T 500 -> 1000 and a service override 3000: earlier minute keeps 500,"
          + " later minute uses 1000 or the override per row, app_stat_1m apdex sums match, the"
          + " changes are apdex events in the events API and the dashboard")
  void forwardOnlyChangeWithServiceOverride() throws Exception {
    String appName = "it-apdex-override";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    ApdexChangeService changes = stack.bean(ApdexChangeService.class);
    long now = System.currentTimeMillis();
    long minuteA = Math.floorDiv(now, 60_000L) * 60_000L - 180_000L;
    long minuteB = Math.floorDiv(now, 60_000L) * 60_000L - 60_000L;
    long s1;
    long s2;
    Instant changedAt;
    try (FakeAgent agent = stack.connect(token, appName, appName + "-1:8080", 61)) {
      Records records = agent.records();
      s1 = records.id(DictionaryKind.SERVICE, S1);
      s2 = records.id(DictionaryKind.SERVICE, S2);
      // Before: the app's default T (500), no override.
      assertThat(stack.bean(ApdexStamper.class).apdexTMs(appId)).isEqualTo(500);
      List<TransactionRecord> before = minute(records, minuteA);
      agent.sendNewDictionaryEntries();
      assertAckOk(agent.sendTransactions(agent.batches().transactions(before)));
      String countA =
          "SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = "
              + appId
              + " AND minute = toDateTime("
              + minuteA / 1_000
              + ", 'UTC')";
      assertThat(stack.awaitScalar(countA, before.size(), 60)).isEqualTo(before.size());
      List<List<String>> minuteABefore = apdexOfMinute(appId, minuteA);
      // T = 500: satisfied 400; tolerating 700, 1500 (<= 2000); per service.
      assertThat(minuteABefore).containsExactly(List.of("10", "2", "4"));

      changedAt = Instant.now();
      ApdexChange app = changes.setAppT(appId, 1_000, null).orElseThrow();
      assertThat(app.changed()).isTrue();
      ApdexChange service = changes.setServiceT(appId, s2, 3_000, null).orElseThrow();
      assertThat(service.oldTMs()).isEqualTo(1_000);
      assertThat(service.newTMs()).isEqualTo(3_000);

      List<TransactionRecord> after = minute(records, minuteB);
      assertAckOk(agent.sendTransactions(agent.batches().transactions(after)));
      String countB =
          "SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = "
              + appId
              + " AND minute = toDateTime("
              + minuteB / 1_000
              + ", 'UTC')";
      assertThat(stack.awaitScalar(countB, after.size(), 60)).isEqualTo(after.size());

      assertThat(stampsOfMinute(appId, minuteA, s1)).containsOnly("500").hasSize(5);
      assertThat(stampsOfMinute(appId, minuteA, s2)).containsOnly("500").hasSize(5);
      assertThat(stampsOfMinute(appId, minuteB, s1)).containsOnly("1000").hasSize(5);
      assertThat(stampsOfMinute(appId, minuteB, s2)).containsOnly("3000").hasSize(5);
      // Earlier minute unchanged (forward only).
      assertThat(apdexOfMinute(appId, minuteA)).isEqualTo(minuteABefore);
      // Later minute: S1 (T 1000) satisfied 400, 700, tolerating 1500, 2500;
      // S2 (T 3000) satisfied 400 .. 2500, tolerating 4500.
      assertThat(apdexOfMinute(appId, minuteB)).containsExactly(List.of("10", "6", "3"));
      // The same counts recomputed from the stored rows.
      assertThat(
              stack
                  .reader()
                  .rows(
                      "SELECT count(), countIf(elapsed_ms <= apdex_t_ms), countIf(elapsed_ms >"
                          + " apdex_t_ms AND elapsed_ms <= 4 * apdex_t_ms) FROM argos.xlog WHERE"
                          + " app_id = "
                          + appId
                          + " AND toStartOfMinute(ts) = toDateTime("
                          + minuteB / 1_000
                          + ", 'UTC')"))
          .containsExactly(List.of("10", "6", "3"));
    }

    ApiTestClient viewer = stack.login(Role.VIEWER);
    String range =
        "from="
            + Instant.ofEpochMilli(minuteA).minus(Duration.ofMinutes(5))
            + "&to="
            + Instant.now().plus(Duration.ofMinutes(1));
    ApiResponse events = viewer.get("/api/v1/apps/" + appId + "/events?type=apdex&" + range);
    assertThat(events.status()).as(events.toString()).isEqualTo(200);
    JsonNode items = events.json().path("data");
    assertThat(items.size()).as(items.toString()).isEqualTo(2);
    // Newest first: the service override, then the app's T.
    JsonNode serviceEvent = items.path(0);
    JsonNode appEvent = items.path(1);
    assertThat(serviceEvent.path("type").asString()).isEqualTo("apdex");
    assertThat(appEvent.path("type").asString()).isEqualTo("apdex");
    JsonNode appDetail = JSON.readTree(appEvent.path("detail").asString());
    assertThat(appDetail.path("old_t_ms").asInt()).isEqualTo(500);
    assertThat(appDetail.path("new_t_ms").asInt()).isEqualTo(1_000);
    assertThat(appDetail.path("service_id").isNull()).isTrue();
    JsonNode serviceDetail = JSON.readTree(serviceEvent.path("detail").asString());
    assertThat(serviceDetail.path("old_t_ms").asInt()).isEqualTo(1_000);
    assertThat(serviceDetail.path("new_t_ms").asInt()).isEqualTo(3_000);
    assertThat(serviceDetail.path("service_id").asString()).isEqualTo(Long.toUnsignedString(s2));
    assertThat(Instant.parse(appEvent.path("ts").asString()))
        .isAfterOrEqualTo(changedAt.minusSeconds(1));
    assertThat(appEvent.path("title").asString())
        .isEqualTo("Apdex T changed from 500 ms to 1000 ms");
    assertThat(appEvent.path("instance_id").isNull()).isTrue();

    ApiResponse dashboard = viewer.get("/api/v1/apps/" + appId + "/dashboard?" + range);
    assertThat(dashboard.status()).as(dashboard.toString()).isEqualTo(200);
    List<String> markers = new ArrayList<>();
    for (JsonNode e : dashboard.json().path("data").path("events")) {
      if (e.path("type").asString().equals("apdex")) {
        markers.add(e.path("event_id").asString());
      }
    }
    assertThat(markers)
        .containsExactly(
            serviceEvent.path("event_id").asString(), appEvent.path("event_id").asString());
    assertThat(s1).isNotEqualTo(s2);
  }

  @Test
  @Tag("req:C-129")
  @DisplayName(
      "T-SV-017 a record received with T 800 and a service override 2500, loaded only by the WAL"
          + " recovery after a crash and two changes, keeps 800 and 2500 (format 2 snapshot)")
  void walReplayKeepsReceiptTime() throws Exception {
    int appId = stack.app("it-apdex-replay").appId();
    long service1 = 0x5101L;
    long service2 = 0x5102L;
    Path dir = temp.resolve("apdex-wal");
    Files.createDirectories(dir);
    long now = System.currentTimeMillis();
    IngestStack.Options blocked =
        IngestStack.Options.defaults()
            .withInserter(
                i ->
                    (table, blockToken, rows) -> {
                      throw new IOException("ClickHouse unavailable (test)");
                    });
    IngestStack crashed = IngestStack.start(stack.env, dir, blocked);
    try {
      ApdexChangeService changes = new ApdexChangeService(crashed.store, crashed.apdex);
      changes.setAppT(appId, 800, null);
      changes.setServiceT(appId, service2, 2_500, null);
      // The receipt stamp of the collector (AgentServiceImpl): the app's T and overrides now.
      SessionContext received =
          session(appId, 901, now, 0, 500, "")
              .atReceipt(now, 0, crashed.apdex.apdexTMs(appId), crashed.apdex.overrides(appId));
      assertThat(received.apdexTMs()).isEqualTo(800);
      assertThat(received.apdexOverrides().size()).isEqualTo(1);
      crashed.append(
          WalRecordType.TX,
          received,
          IngestFixtures.txBatch(
              tx(txId(now - 2_000, 1), now - 2_000, 1_200_000, 0, 0).setServiceId(service1),
              tx(txId(now - 1_000, 2), now - 1_000, 1_200_000, 0, 0).setServiceId(service2)),
          1);
      Thread.sleep(1_000); // the writer tries (and fails) at least once
    } finally {
      crashed.crash();
    }
    String rows = "SELECT count() FROM argos.xlog WHERE app_id = " + appId;
    assertThat(stack.scalar(rows)).as("nothing loaded before the crash").isZero();

    // Two changes while the server is down.
    JdbcIngestStore store = new JdbcIngestStore(new IngestStack.EnvDataSource(stack.env));
    ApdexStamper stamper = new ApdexStamper(store);
    ApdexChangeService changes = new ApdexChangeService(store, stamper);
    changes.setAppT(appId, 1_500, null);
    changes.setServiceT(appId, service2, 4_000, null);

    try (IngestStack recovered = IngestStack.start(stack.env, dir)) {
      assertThat(recovered.scan.dataRecords()).isEqualTo(1);
      recovered.awaitLoaded();
      assertThat(recovered.apdex.apdexTMs(appId)).isEqualTo(1_500);
      assertThat(recovered.apdex.overrides(appId).tFor(service2, 1_500)).isEqualTo(4_000);
    }
    assertThat(
            stack
                .reader()
                .rows(
                    "SELECT service_id, apdex_t_ms FROM argos.xlog WHERE app_id = "
                        + appId
                        + " ORDER BY ts"))
        .containsExactly(
            List.of(Long.toUnsignedString(service1), "800"),
            List.of(Long.toUnsignedString(service2), "2500"));
  }
}
