/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository.Approval;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository.PendingRegistration;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The registration policy of 12 §4.4 / F-S30 with ADR-0010 C-155 (3) over the whole server (gRPC
 * Hello, PostgreSQL V004) with fake agents: the approval queue of refused Hellos, its 1,000-row cap
 * and the approval of the repository (the administration API over it is WO-26's).
 */
@Tag("integration")
@Tag("req:F-S30")
@Tag("req:C-155")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistrationPolicyIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env, Map.of("argos.agent.auto-register.qa", "false"));
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

  private static AgentTokens.IssuedToken token(String app) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
  }

  private static FakeAgent agent(AgentTokens.IssuedToken token, String app, String appEnv) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(app)
        .env(appEnv)
        .instanceName("reg-host:8080")
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  private static PendingRegistrationRepository queue() {
    return server.bean(PendingRegistrationRepository.class);
  }

  private static List<PendingRegistration> pending(String app) {
    return queue().list(null).stream().filter(p -> p.name().equals(app)).toList();
  }

  private static void notRegistered(HelloResult r) {
    assertThat(r.accepted()).as(r.toString()).isFalse();
    assertThat(r.rejectReason()).isEqualTo(RejectReason.APP_NOT_REGISTERED);
    assertThat(r.code()).isEqualTo(Status.Code.OK);
    assertThat(r.retryAfterS()).isEqualTo(300);
  }

  private static long count(String sql, Object... args) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-043 auto-register off for env qa: the Hello is APP_NOT_REGISTERED, stream OK, retry"
          + " 300, and queued (name, env, host, home app, attempts 1); a retry counts attempt 2;"
          + " approval creates the home app's variant and the same token's next Hello is accepted")
  void offEnvironmentQueuedAndApproved() throws Exception {
    String app = "reg-off";
    AgentTokens.IssuedToken home = token(app);
    try (FakeAgent a = agent(home, app, "qa")) {
      notRegistered(a.connect());
      List<PendingRegistration> rows = pending(app);
      assertThat(rows).hasSize(1);
      PendingRegistration first = rows.get(0);
      assertThat(first.env()).isEqualTo("qa");
      assertThat(first.host()).isEqualTo("fake-host");
      assertThat(first.homeAppId()).isEqualTo(home.appId());
      assertThat(first.attempts()).isEqualTo(1);
      assertThat(first.firstAttemptAt()).isEqualTo(first.lastAttemptAt());
      assertThat(queue().list("qa")).extracting(PendingRegistration::name).contains(app);
      assertThat(queue().list("prod")).extracting(PendingRegistration::name).doesNotContain(app);

      notRegistered(a.connect());
      PendingRegistration second = pending(app).get(0);
      assertThat(second.id()).isEqualTo(first.id());
      assertThat(second.attempts()).isEqualTo(2);
      assertThat(second.lastAttemptAt()).isAfterOrEqualTo(first.lastAttemptAt());
      assertThat(second.firstAttemptAt()).isEqualTo(first.firstAttemptAt());
      assertThat(count("SELECT count(*) FROM argos.apps WHERE name = ? AND env = 'qa'", app))
          .isZero();

      Approval approval = queue().approve(second.id());
      assertThat(approval.outcome()).isEqualTo(Approval.Outcome.APPROVED);
      assertThat(approval.app().registeredFromAppId()).isEqualTo(home.appId());
      assertThat(approval.app().env()).isEqualTo("qa");
      assertThat(pending(app)).isEmpty();
      assertThat(
              count(
                  "SELECT count(*) FROM argos.apps WHERE id = ? AND NOT auto_register",
                  approval.app().id()))
          .as("an administrator's approval, not an automatic registration")
          .isEqualTo(1);

      HelloResult accepted = a.connect();
      assertThat(accepted.accepted()).as(accepted.toString()).isTrue();
      assertThat(accepted.hello().getAppId()).isEqualTo(approval.app().id());
    }
    assertThat(queue().approve(Integer.MAX_VALUE).outcome()).isEqualTo(Approval.Outcome.NOT_FOUND);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-043 C-155 (3) auto-register on but the home app has its 8 variants: the 9th"
          + " environment is APP_NOT_REGISTERED retry 300 and queued; its approval is not capped"
          + " and does not count against the automatic cap")
  void variantCapQueued() throws Exception {
    String app = "reg-cap";
    AgentTokens.IssuedToken home = token(app);
    for (int i = 1; i <= 8; i++) {
      try (FakeAgent a = agent(home, app, "v" + i)) {
        assertThat(a.connect().accepted()).as("variant %d", i).isTrue();
      }
    }
    try (FakeAgent a = agent(home, app, "v9")) {
      notRegistered(a.connect());
      List<PendingRegistration> rows = pending(app);
      assertThat(rows).extracting(PendingRegistration::env).containsExactly("v9");
      assertThat(rows.get(0).homeAppId()).isEqualTo(home.appId());
      Approval approval = queue().approve(rows.get(0).id());
      assertThat(approval.outcome()).isEqualTo(Approval.Outcome.APPROVED);
      assertThat(a.connect().accepted()).as("the approved 9th variant").isTrue();
    }
    assertThat(
            count("SELECT count(*) FROM argos.apps WHERE registered_from_app_id = ?", home.appId()))
        .isEqualTo(9);
    try (FakeAgent a = agent(home, app, "v10")) {
      notRegistered(a.connect());
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-043 an approval of a (name, env) that belongs to another app (administrator-created)"
          + " is NAME_TAKEN and keeps the request; reject deletes it")
  void nameTakenAndReject() throws Exception {
    String app = "reg-taken";
    AgentTokens.IssuedToken home = token(app);
    try (FakeAgent a = agent(home, app, "qa")) {
      notRegistered(a.connect());
    }
    int id = pending(app).get(0).id();
    try (Connection c = env.connect(PgAccount.APP)) {
      AgentTokens.ensureApp(c, app, "qa");
    }
    assertThat(queue().approve(id).outcome()).isEqualTo(Approval.Outcome.NAME_TAKEN);
    assertThat(pending(app)).hasSize(1);
    assertThat(queue().reject(id)).isTrue();
    assertThat(queue().reject(id)).isFalse();
    assertThat(pending(app)).isEmpty();
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-SV-043 the queue keeps at most 1,000 rows: the 1,001st request replaces the least"
          + " recently attempted row, the others stay")
  void capReplacesOldest() throws Exception {
    String app = "reg-full";
    AgentTokens.IssuedToken home = token(app);
    long existing = count("SELECT count(*) FROM argos.pending_registrations");
    int fillers = (int) (PendingRegistrationRepository.MAX_ROWS - existing);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.pending_registrations"
                    + " (name, env, host, first_attempt_at, last_attempt_at, attempts, home_app_id)"
                    + " SELECT 'filler-' || g, 'qa', 'h', now() - interval '1 day' - g * interval"
                    + " '1 second', now() - interval '1 day' - g * interval '1 second', 1, ?"
                    + " FROM generate_series(1, ?) g")) {
      ps.setInt(1, home.appId());
      ps.setInt(2, fillers);
      ps.executeUpdate();
    }
    assertThat(count("SELECT count(*) FROM argos.pending_registrations"))
        .isEqualTo(PendingRegistrationRepository.MAX_ROWS);
    String oldest = "filler-" + fillers;

    try (FakeAgent a = agent(home, app, "qa")) {
      notRegistered(a.connect());
    }
    assertThat(count("SELECT count(*) FROM argos.pending_registrations"))
        .isEqualTo(PendingRegistrationRepository.MAX_ROWS);
    assertThat(count("SELECT count(*) FROM argos.pending_registrations WHERE name = ?", oldest))
        .as("the least recently attempted row was replaced")
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM argos.pending_registrations WHERE name = ?",
                "filler-" + (fillers - 1)))
        .isEqualTo(1);
    assertThat(pending(app)).hasSize(1);
    assertThat(queue().list(null)).hasSize(PendingRegistrationRepository.MAX_ROWS);

    // A retry of a queued request is not a new row: nothing is replaced.
    try (FakeAgent a = agent(home, app, "qa")) {
      notRegistered(a.connect());
    }
    assertThat(count("SELECT count(*) FROM argos.pending_registrations"))
        .isEqualTo(PendingRegistrationRepository.MAX_ROWS);
    assertThat(
            count(
                "SELECT count(*) FROM argos.pending_registrations WHERE name = ?",
                "filler-" + (fillers - 1)))
        .isEqualTo(1);
    assertThat(pending(app).get(0).attempts()).isEqualTo(2);
  }

  private static void setPolicy(int appId, Boolean autoRegister) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("UPDATE argos.apps SET auto_register = ? WHERE id = ?")) {
      ps.setObject(1, autoRegister);
      ps.setInt(2, appId);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
  }

  @Test
  @Order(5)
  @Tag("req:C-231")
  @DisplayName(
      "T-SV-043c the home app's registration policy wins over argos.agent.auto-register.<env>:"
          + " approval refuses an env that registers by default, auto registers in env qa (off);"
          + " a home app without a policy (NULL) follows the environment")
  void appPolicyWinsOverEnvironment() throws Exception {
    AgentTokens.IssuedToken approval = token("reg-own-approval");
    setPolicy(approval.appId(), Boolean.FALSE);
    try (FakeAgent a = agent(approval, "reg-own-approval", "staging")) {
      notRegistered(a.connect());
    }
    assertThat(pending("reg-own-approval")).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM argos.apps WHERE name = 'reg-own-approval' AND env ="
                    + " 'staging'"))
        .isZero();

    AgentTokens.IssuedToken auto = token("reg-own-auto");
    setPolicy(auto.appId(), Boolean.TRUE);
    try (FakeAgent a = agent(auto, "reg-own-auto", "qa")) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).as(r.toString()).isTrue();
    }
    assertThat(pending("reg-own-auto")).isEmpty();

    AgentTokens.IssuedToken inherit = token("reg-inherit");
    assertThat(
            count(
                "SELECT count(*) FROM argos.apps WHERE id = ? AND auto_register IS NULL",
                inherit.appId()))
        .as("a new app sets no policy (V013)")
        .isEqualTo(1);
    try (FakeAgent a = agent(inherit, "reg-inherit", "staging")) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).as("staging registers by default: " + r).isTrue();
    }
    try (FakeAgent a = agent(inherit, "reg-inherit", "qa")) {
      notRegistered(a.connect());
    }

    // The application views (GET /apps, /apps/{id}): the effective policy and whether it is the
    // environment default (registration_policy_inherited).
    io.argos.server.testing.auth.TestUsers.TestUser user =
        new io.argos.server.testing.auth.TestUsers(env).create(io.argos.server.auth.Role.VIEWER);
    io.argos.server.testing.auth.ApiTestClient viewer = server.client();
    assertThat(viewer.login(user.username(), user.password()).status()).isEqualTo(200);
    tools.jackson.databind.JsonNode own =
        viewer.get("/api/v1/apps/" + approval.appId()).json().path("data");
    assertThat(own.path("registration_policy").asString()).isEqualTo("approval");
    assertThat(own.path("registration_policy_inherited").asBoolean()).isFalse();
    tools.jackson.databind.JsonNode inherited =
        viewer.get("/api/v1/apps/" + inherit.appId()).json().path("data");
    assertThat(inherited.path("registration_policy").asString())
        .as("prod registers by default: " + inherited)
        .isEqualTo("auto");
    assertThat(inherited.path("registration_policy_inherited").asBoolean()).isTrue();
    int qaApp;
    try (Connection c = env.connect(PgAccount.APP)) {
      qaApp = AgentTokens.ensureApp(c, "reg-inherit-qa", "qa");
    }
    tools.jackson.databind.JsonNode qa = viewer.get("/api/v1/apps/" + qaApp).json().path("data");
    assertThat(qa.path("registration_policy").asString())
        .as("argos.agent.auto-register.qa=false: " + qa)
        .isEqualTo("approval");
    assertThat(qa.path("registration_policy_inherited").asBoolean()).isTrue();
  }
}
