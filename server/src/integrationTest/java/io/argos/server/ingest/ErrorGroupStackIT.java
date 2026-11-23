/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.AgentServerStack.assertAckOk;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * T-SV-016e (F-S19, F-U07, SEC-36, 12 §3.5 (4) "대표 스택은 ERROR step이 있는 첫 프로파일에서 1회 채운다"): the
 * representative stack of an error group through the whole server. A fake agent sends a failed,
 * captured transaction with its profile (an ERROR step with frames); the server's {@link
 * ErrorGroupStackFiller} sets {@code error_groups.sample_stack} once, from the frames of the FRAME
 * dictionary masked again with the server's default set, and {@code GET
 * /apps/{id}/errors/groups/{groupId}} returns it one frame per element. A later occurrence with
 * another stack does not replace it, and a group whose frame texts the agent sends late gets its
 * stack once they arrive.
 */
@Tag("integration")
@Tag("req:F-S19")
@Tag("req:F-U07")
@Tag("req:SEC-36")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorGroupStackIT {

  private static final String EXCEPTION = "com.shop.PaymentException";
  private static final String TOP = "com.shop.PayService.charge(PayService.java)";
  private static final List<String> FRAMES =
      List.of(
          TOP,
          "com.shop.Mailer.notify(kim@example.com)",
          "com.shop.Gateway.call(10.20.30.40)",
          "org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java)");
  private static final List<String> MASKED =
      List.of(
          TOP,
          "com.shop.Mailer.notify(<email>)",
          "com.shop.Gateway.call(<ip>)",
          "org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java)");

  private AgentServerStack stack;
  private ErrorGroupStackFiller filler;

  @BeforeAll
  void start() {
    stack = AgentServerStack.start();
    filler = stack.bean(ErrorGroupStackFiller.class);
  }

  @AfterAll
  void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  /** A failed, captured transaction of {@code records} whose message differs only in numbers. */
  private static TransactionRecord error(Records records, int n) {
    long start = records.clock().nowMs() - 2_000;
    return records
        .withError(
            records.http("POST /api/pay", start, 80_000).setProfileCaptured(true),
            EXCEPTION,
            "Payment " + (4711 + n) + " failed",
            TOP)
        .build();
  }

  private static List<ProfileStep> steps(Records records, List<String> frames) {
    return List.of(
        records.methodStep(1, 0, 1_000, 70_000, "com.shop.PayController#pay"),
        records.errorStep(2, 1, 60_000, EXCEPTION, frames));
  }

  /** Sends the transaction and its profile; returns its error group id once it is stored. */
  private long send(FakeAgent agent, TransactionRecord tx, List<ProfileStep> steps)
      throws Exception {
    assertAckOk(agent.sendTransactions(agent.batches().transactions(List.of(tx))));
    assertAckOk(
        agent.sendProfiles(
            agent.batches().profiles(List.of(agent.records().profile(tx, steps).build()))));
    String txId = Long.toUnsignedString(tx.getTxId());
    assertThat(
            stack.awaitScalar(
                "SELECT count() FROM argos.profile_step WHERE type = 'ERROR' AND tx_id = " + txId,
                1,
                60))
        .isEqualTo(1);
    long groupId =
        Long.parseUnsignedLong(
            stack
                .reader()
                .scalar("SELECT error_group_id FROM argos.xlog WHERE tx_id = " + txId)
                .strip());
    assertThat(groupId).isNotZero();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!groupExists(tx, groupId) && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(200);
    }
    assertThat(groupExists(tx, groupId)).as("error_groups row").isTrue();
    return groupId;
  }

  private int appOf(TransactionRecord tx) throws Exception {
    return Integer.parseInt(
        stack
            .reader()
            .scalar(
                "SELECT app_id FROM argos.xlog WHERE tx_id = "
                    + Long.toUnsignedString(tx.getTxId()))
            .strip());
  }

  private boolean groupExists(TransactionRecord tx, long groupId) throws Exception {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT 1 FROM argos.error_groups WHERE app_id = ? AND group_id = ?")) {
      st.setInt(1, appOf(tx));
      st.setLong(2, groupId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next();
      }
    }
  }

  private String sampleStack(int appId, long groupId) throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT sample_stack FROM argos.error_groups WHERE app_id = ? AND group_id = ?")) {
      st.setInt(1, appId);
      st.setLong(2, groupId);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private long frameTexts(int appId) throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "SELECT count(*) FROM argos.dictionary WHERE app_id = ? AND kind = 'frame'")) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /**
   * Runs the filler (as its 60 s period would) until the group has a stack, at most 60 s; the retry
   * wait of a group whose texts were missing is cleared before each run.
   */
  private String fill(int appId, long groupId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    String stack = sampleStack(appId, groupId);
    while (stack == null && System.nanoTime() < deadline) {
      filler.clearWaits();
      ErrorGroupStackFiller.Run run = filler.runOnce();
      assertThat(run.failures()).as(run.toString()).isZero();
      stack = sampleStack(appId, groupId);
      if (stack == null) {
        TimeUnit.MILLISECONDS.sleep(500);
      }
    }
    return stack;
  }

  @Test
  @DisplayName(
      "T-SV-016e the first ERROR step's frames become sample_stack once, masked (<email>, <ip>),"
          + " one frame per line; GET errors/groups/{id} returns them; a later stack of the group"
          + " does not replace it")
  void representativeStackOnce() throws Exception {
    String appName = "it-error-stack";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    try (FakeAgent agent = stack.connect(token, appName, appName + "-1:8080", 51)) {
      Records records = agent.records();
      TransactionRecord first = error(records, 0);
      List<ProfileStep> firstSteps = steps(records, FRAMES);
      agent.sendNewDictionaryEntries();
      long groupId = send(agent, first, firstSteps);
      assertThat(
              AgentServerStack.await(
                  () -> {
                    try {
                      return frameTexts(appId);
                    } catch (SQLException e) {
                      throw new IllegalStateException(e);
                    }
                  },
                  FRAMES.size(),
                  30))
          .isEqualTo(FRAMES.size());

      String stored = fill(appId, groupId);
      assertThat(stored).isEqualTo(String.join("\n", MASKED));
      assertThat(stored).doesNotContain("kim@example.com").doesNotContain("10.20.30.40");

      ApiTestClient viewer = stack.login(Role.VIEWER);
      Instant now = Instant.now();
      ApiResponse r =
          viewer.get(
              "/api/v1/apps/"
                  + appId
                  + "/errors/groups/"
                  + Long.toUnsignedString(groupId)
                  + "?from="
                  + now.minus(Duration.ofHours(1))
                  + "&to="
                  + now.plus(Duration.ofMinutes(1)));
      assertThat(r.status()).as(r.toString()).isEqualTo(200);
      JsonNode sample = r.json().path("data").path("sample_stack");
      List<String> frames = new ArrayList<>();
      sample.forEach(f -> frames.add(f.asString()));
      assertThat(frames).containsExactlyElementsOf(MASKED);

      // A later occurrence of the same group with a deeper stack: the stack stays the first one.
      List<String> deeper = new ArrayList<>(FRAMES);
      deeper.add(1, "com.shop.Retry.again(Retry.java)");
      TransactionRecord second = error(records, 1);
      List<ProfileStep> secondSteps = steps(records, deeper);
      agent.sendNewDictionaryEntries();
      assertThat(send(agent, second, secondSteps)).isEqualTo(groupId);
      filler.clearWaits();
      filler.runOnce();
      assertThat(sampleStack(appId, groupId)).isEqualTo(stored);
      assertThat(stack.bean(IngestStore.class).fillSampleStack(appId, groupId, "replaced"))
          .as("UPDATE ... WHERE sample_stack IS NULL")
          .isFalse();
      assertThat(sampleStack(appId, groupId)).isEqualTo(stored);
    }
  }

  @Test
  @DisplayName(
      "T-SV-016e frame texts that the agent sends late: no stack while one is missing, the masked"
          + " stack once they arrive")
  void lateDictionary() throws Exception {
    String appName = "it-error-stack-late";
    AgentTokens.IssuedToken token = stack.app(appName);
    int appId = token.appId();
    try (FakeAgent agent = stack.connect(token, appName, appName + "-1:8080", 52, false)) {
      Records records = agent.records();
      TransactionRecord tx = error(records, 0);
      List<ProfileStep> steps = steps(records, FRAMES);
      List<DictionaryEntry> frames = new ArrayList<>();
      List<DictionaryEntry> others = new ArrayList<>();
      for (DictionaryEntry e : records.drainNewEntries()) {
        (e.getKind() == DictionaryKind.FRAME ? frames : others).add(e);
      }
      assertThat(frames).hasSize(FRAMES.size());
      agent.sendDictionaryEntries(others);
      long groupId = send(agent, tx, steps);

      for (int i = 0; i < 3; i++) {
        filler.clearWaits();
        ErrorGroupStackFiller.Run run = filler.runOnce();
        assertThat(run.failures()).isZero();
        assertThat(sampleStack(appId, groupId)).as("no stack while the texts are missing").isNull();
        TimeUnit.MILLISECONDS.sleep(300);
      }
      assertThat(frameTexts(appId)).isZero();

      agent.sendDictionaryEntries(frames);
      assertThat(
              AgentServerStack.await(
                  () -> {
                    try {
                      return frameTexts(appId);
                    } catch (SQLException e) {
                      throw new IllegalStateException(e);
                    }
                  },
                  FRAMES.size(),
                  30))
          .isEqualTo(FRAMES.size());
      assertThat(fill(appId, groupId)).isEqualTo(String.join("\n", MASKED));
    }
  }
}
