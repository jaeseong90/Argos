/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveState;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End to end through the whole server: a protocol-level fake agent (WO-21 fixtures) sends a
 * transaction with its profile and an active snapshot over TLS gRPC; the ingest path (WAL, writer)
 * stores them; the query API returns them: {@code GET /apps/{appId}/xlog}, {@code GET
 * /transactions/{txId}} (restored from the id), {@code GET /apps/{appId}/active}.
 */
@Tag("integration")
@Tag("req:F-U02")
@Tag("req:F-U04")
class FakeAgentQueryE2eIT {

  @Test
  @DisplayName(
      "FakeAgent -> WO-21 ingest -> GET /apps/{id}/xlog and GET /transactions/{txId} return the"
          + " sent transaction; GET /apps/{id}/active returns the sent snapshot")
  void sentTransactionIsQueryable() throws Exception {
    var env = TxApiFixture.env();
    String appName = "e2e-query-" + Long.toHexString(System.nanoTime());
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, appName, "prod"));
    }
    ApiTestClient viewer = TxApiFixture.client(Role.VIEWER);
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls("localhost", env.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(appName)
            .instanceName(appName + "-1:8080")
            .records(new Records(AgentClock.system(), 21, 21))
            .agentEpoch(21)
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      agent.sendNewDictionaryEntries();
      Records records = agent.records();
      long start = agent.clock().nowMs() - 250;
      TransactionRecord tx =
          records.http("GET /api/e2e/query/{id}", start, 230_000).setProfileCaptured(true).build();
      CallResult sent = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
      assertThat(sent.ackStatus()).as(sent.toString()).isEqualTo(AckStatus.ACK_OK);
      List<ProfileStep> steps =
          List.of(records.methodStep(1, 0, 1_000, 200_000, "com.shop.Query.run"));
      CallResult profiles =
          agent.sendProfiles(agent.batches().profiles(List.of(records.profile(tx, steps).build())));
      assertThat(profiles.ackStatus()).as(profiles.toString()).isEqualTo(AckStatus.ACK_OK);
      agent.sendNewDictionaryEntries();

      String txId = Long.toUnsignedString(tx.getTxId());
      Instant from = Instant.ofEpochMilli(start).minus(Duration.ofMinutes(1));
      Instant to = Instant.now().plus(Duration.ofMinutes(1));
      String xlogPath = "/api/v1/apps/" + token.appId() + "/xlog?from=" + from + "&to=" + to;
      JsonNode points = null;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < deadline) {
        ApiResponse r = viewer.get(xlogPath);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        points = r.json().path("data");
        if (points.path("tx_id").toString().contains(txId)) {
          break;
        }
        TimeUnit.MILLISECONDS.sleep(500);
      }
      assertThat(points).isNotNull();
      assertThat(points.path("tx_id").toString()).as("the sent tx in the XLog").contains(txId);
      assertThat(points.path("elapsed_ms").path(0).asLong()).isEqualTo(230);
      assertThat(points.path("instance_id").path(0).asInt()).isEqualTo(agent.instanceId());

      JsonNode detail = null;
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (System.nanoTime() < deadline) {
        detail = viewer.get("/api/v1/transactions/" + txId).json().path("data");
        if (detail.path("profile").path("steps").size() > 0) {
          break;
        }
        TimeUnit.MILLISECONDS.sleep(500);
      }
      assertThat(detail).isNotNull();
      assertThat(detail.path("tx_id").asString()).isEqualTo(txId);
      assertThat(detail.path("lookup").asString()).isEqualTo("restored");
      assertThat(detail.path("app").path("name").asString()).isEqualTo(appName);
      assertThat(detail.path("url").asString()).startsWith("/api/orders/");
      assertThat(detail.path("profile").path("state").asString()).isEqualTo("captured");
      assertThat(detail.path("profile").path("steps").path(0).path("type").asString())
          .isEqualTo("METHOD");

      ActiveTransactionSnapshot snapshot =
          agent
              .batches()
              .active(
                  List.of(records.active(tx, ActiveState.IN_SQL, agent.clock().nowMs()).build()),
                  1);
      assertThat(agent.sendActive(snapshot).ackStatus()).isEqualTo(AckStatus.ACK_OK);
      JsonNode active = null;
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        active =
            viewer.get("/api/v1/apps/" + token.appId() + "/active?kind=ALL").json().path("data");
        if (active.path("items").size() > 0) {
          break;
        }
        TimeUnit.MILLISECONDS.sleep(300);
      }
      assertThat(active).isNotNull();
      assertThat(active.path("items").path(0).path("tx_id").asString()).isEqualTo(txId);
      assertThat(active.path("items").path(0).path("state").asString()).isEqualTo("SQL");
      assertThat(active.path("per_instance").path(0).path("state").asString()).isEqualTo("UP");
      assertThat(active.path("per_instance").path(0).path("count").asLong()).isEqualTo(1);
    }
  }
}
