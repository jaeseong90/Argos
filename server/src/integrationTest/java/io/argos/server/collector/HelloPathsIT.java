/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.RejectReason;
import io.argos.server.session.HelloService;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The seven 11 §5 session start paths over a real TLS gRPC listener and real PostgreSQL: gRPC
 * status, {@code RejectReason}, {@code retry_after_s} and trailers of each, the {@code instances}
 * row of an accepted Hello, and REPLACED (check 7).
 */
@Tag("integration")
@Tag("req:F-S01")
@Tag("req:SEC-01")
class HelloPathsIT {

  private static ServerTestEnvironment env;
  private static CollectorHarness collector;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    collector =
        CollectorHarness.builder(env).property("argos.agent.auto-register.qa", false).start();
  }

  @AfterAll
  static void stop() {
    if (collector != null) {
      collector.close();
    }
    if (env != null) {
      env.close();
    }
  }

  private static String app() {
    return "app-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static void rejected(HelloResult r, RejectReason reason, Status.Code code, int retry) {
    assertThat(r.accepted()).as(r.toString()).isFalse();
    assertThat(r.rejectReason()).isEqualTo(reason);
    assertThat(r.retryAfterS()).isEqualTo(retry);
    assertThat(r.code()).isEqualTo(code);
    if (code != Status.Code.OK) {
      assertThat(r.trailerRejectReason()).isEqualTo(reason.name());
    }
  }

  @Test
  @DisplayName(
      "11 §5 check 1: unknown, missing, expired and revoked tokens: UNAUTHENTICATED TOKEN_*, retry 300 (C-140)")
  void check1() {
    String app = app();
    collector.token(app, "prod");
    try (FakeAgent unknown = collector.agent(AgentTokens.generate()).appName(app).build()) {
      rejected(unknown.connect(), RejectReason.TOKEN_INVALID, Status.Code.UNAUTHENTICATED, 300);
    }
    try (FakeAgent none = collector.agent(null).appName(app).build()) {
      rejected(none.connect(), RejectReason.TOKEN_INVALID, Status.Code.UNAUTHENTICATED, 300);
    }
    String expired =
        collector
            .token(app, "prod", AgentTokens.Spec.expiring(Instant.now().minusSeconds(60)))
            .token();
    try (FakeAgent a = collector.agent(expired).appName(app).build()) {
      HelloResult r = a.connect();
      rejected(r, RejectReason.TOKEN_EXPIRED, Status.Code.UNAUTHENTICATED, 300);
      assertThat(r.trailers().get(CallResult.RETRY_AFTER_MS)).isEqualTo("300000");
    }
    String revoked =
        collector
            .token(app, "prod", AgentTokens.Spec.revoked(Instant.now().minusSeconds(60)))
            .token();
    try (FakeAgent a = collector.agent(revoked).appName(app).build()) {
      rejected(a.connect(), RejectReason.TOKEN_REVOKED, Status.Code.UNAUTHENTICATED, 300);
    }
    String rotated =
        collector
            .token(app, "prod", AgentTokens.Spec.rotating(Instant.now().plusSeconds(600)))
            .token();
    try (FakeAgent a = collector.agent(rotated).appName(app).build()) {
      assertThat(a.connect().accepted()).as("GRACE before grace_until (16 §2.4)").isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-01 11 §5 check 2: another app's token is PERMISSION_DENIED APP_MISMATCH, retry 0")
  @Tag("req:SEC-01")
  void check2() {
    String billing = app();
    String orders = app();
    collector.token(orders, "prod");
    String billingToken = collector.token(billing, "prod").token();
    try (FakeAgent a = collector.agent(billingToken).appName(orders).build()) {
      rejected(a.connect(), RejectReason.APP_MISMATCH, Status.Code.PERMISSION_DENIED, 0);
    }
  }

  @Test
  @DisplayName(
      "11 §5 check 3: a plaintext connection while allow-plaintext=false is PLAINTEXT_NOT_ALLOWED, retry 0")
  void check3() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (CollectorHarness plaintext = CollectorHarness.builder(env).plaintext(false).start();
        FakeAgent a = plaintext.agent(token).appName(app).plaintextFlag(true).build()) {
      rejected(a.connect(), RejectReason.PLAINTEXT_NOT_ALLOWED, Status.Code.FAILED_PRECONDITION, 0);
    }
    try (CollectorHarness allowed = CollectorHarness.builder(env).plaintext(true).start();
        FakeAgent a = allowed.agent(token).appName(app).plaintextFlag(true).build()) {
      assertThat(a.connect().accepted()).isTrue();
    }
    try (FakeAgent overTls = collector.agent(token).appName(app).plaintextFlag(true).build()) {
      assertThat(overTls.connect().accepted())
          .as("decided from the transport: AgentHello.plaintext is informational")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "11 §5 check 4: protocol 2 is PROTOCOL_UNSUPPORTED, agent 9.0.0 AGENT_VERSION_TOO_NEW, retry 0")
  void check4() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (FakeAgent a =
        collector.agent(token).appName(app).hello(h -> h.setProtocolVersion(2)).build()) {
      HelloResult r = a.connect();
      rejected(r, RejectReason.PROTOCOL_UNSUPPORTED, Status.Code.FAILED_PRECONDITION, 0);
      assertThat(r.hello().getRejectMessage()).contains("1 to 1").contains("0.0.x to 0.0.x");
    }
    try (FakeAgent a = collector.agent(token).appName(app).agentVersion("9.0.0").build()) {
      HelloResult r = a.connect();
      rejected(r, RejectReason.AGENT_VERSION_TOO_NEW, Status.Code.FAILED_PRECONDITION, 0);
      assertThat(r.hello().getMinAgentVersion()).isEqualTo("0.0");
      assertThat(r.hello().getMaxAgentVersion()).isEqualTo("0.0");
    }
  }

  @Test
  @Tag("req:C-155")
  @Tag("req:SEC-01")
  @DisplayName(
      "11 §5 check 5 / C-155: auto-register off for the env is APP_NOT_REGISTERED, stream OK, retry"
          + " 300; on, the token's app registers the env as its variant (registered_from_app_id),"
          + " which the token then uses; an administrator-created app of another env is"
          + " PERMISSION_DENIED APP_MISMATCH retry 0 for it, and reached by its own token only")
  void check5() throws Exception {
    String app = app();
    AgentTokens.IssuedToken home = collector.token(app, "prod");
    String token = home.token();
    try (FakeAgent a = collector.agent(token).appName(app).env("qa").build()) {
      rejected(a.connect(), RejectReason.APP_NOT_REGISTERED, Status.Code.OK, 300);
    }
    assertThat(countApps(app, "qa")).isZero();
    assertThat(count("SELECT count(*) FROM argos.pending_registrations WHERE name = ?", app))
        .as("no approval queue in the slice (WO-22)")
        .isZero();

    int stageId;
    try (FakeAgent a = collector.agent(token).appName(app).env("stage").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).as("cross-env with the home token: a variant").isTrue();
      stageId = r.hello().getAppId();
      assertThat(stageId).isNotEqualTo(home.appId());
      CallResult tx =
          a.sendTransactions(a.batches().transactions(List.of(a.records().transaction().build())));
      assertThat(tx.ok()).as(tx.toString()).isTrue();
    }
    assertThat(countApps(app, "stage")).isEqualTo(1);
    assertThat(registeredFrom(stageId)).isEqualTo(home.appId());
    try (FakeAgent again = collector.agent(token).appName(app).env("stage").build()) {
      HelloResult r = again.connect();
      assertThat(r.accepted()).isTrue();
      assertThat(r.hello().getAppId()).as("the existing variant, not a new app").isEqualTo(stageId);
    }

    // An administrator created (app, dev) with a token of its own: not the prod token's.
    int devId;
    try (Connection c = env.connect(PgAccount.APP)) {
      devId = AgentTokens.ensureApp(c, app, "dev");
    }
    try (FakeAgent a = collector.agent(token).appName(app).env("dev").build()) {
      rejected(a.connect(), RejectReason.APP_MISMATCH, Status.Code.PERMISSION_DENIED, 0);
    }
    assertThat(count("SELECT count(*) FROM argos.instances WHERE app_id = ?", devId))
        .as("nothing registered in the denied app")
        .isZero();
    assertThat(registeredFrom(devId)).as("administrator-created: no home app").isZero();
    String devToken = collector.token(app, "dev").token();
    try (FakeAgent a = collector.agent(devToken).appName(app).env("dev").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).as("its own token").isTrue();
      assertThat(r.hello().getAppId()).isEqualTo(devId);
    }
    for (String other : List.of("prod", "stage")) {
      try (FakeAgent a = collector.agent(devToken).appName(app).env(other).build()) {
        rejected(a.connect(), RejectReason.APP_MISMATCH, Status.Code.PERMISSION_DENIED, 0);
      }
    }
  }

  @Test
  @Tag("req:C-155")
  @Tag("req:SEC-14")
  @DisplayName(
      "C-155 (3) 16 parallel Hellos of one token for 16 new environments on two collectors (one"
          + " PostgreSQL): exactly 8 variants are registered, the other 8 are APP_NOT_REGISTERED,"
          + " stream OK, retry 300 (the home app's row lock)")
  void variantCapUnderConcurrentHellos() throws Exception {
    String app = app();
    AgentTokens.IssuedToken home = collector.token(app, "prod");
    int n = 16;
    List<FakeAgent> agents = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(n);
    try (CollectorHarness second = CollectorHarness.start(env)) {
      CountDownLatch go = new CountDownLatch(1);
      List<Future<HelloResult>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        CollectorHarness target = i % 2 == 0 ? collector : second;
        FakeAgent a =
            target
                .agent(home.token())
                .appName(app)
                .env("par-" + i)
                .instanceName("par:" + i)
                .helloTimeout(Duration.ofSeconds(30))
                .build();
        agents.add(a);
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  return a.connect();
                }));
      }
      go.countDown();
      List<HelloResult> results = new ArrayList<>();
      for (Future<HelloResult> f : futures) {
        results.add(f.get(2, TimeUnit.MINUTES));
      }
      assertThat(results).filteredOn(HelloResult::accepted).hasSize(MAX_VARIANTS);
      assertThat(results)
          .filteredOn(r -> !r.accepted())
          .hasSize(n - MAX_VARIANTS)
          .allSatisfy(r -> rejected(r, RejectReason.APP_NOT_REGISTERED, Status.Code.OK, 300));
    } finally {
      for (FakeAgent a : agents) {
        a.close();
      }
      pool.shutdownNow();
    }
    assertThat(
            count("SELECT count(*) FROM argos.apps WHERE registered_from_app_id = ?", home.appId()))
        .as("variants of the home app in PostgreSQL")
        .isEqualTo(MAX_VARIANTS);
    assertThat(count("SELECT count(*) FROM argos.apps WHERE name = ?", app))
        .isEqualTo(1 + MAX_VARIANTS);
  }

  /** {@code HelloService.MAX_APP_VARIANTS} (C-155 (3)). */
  private static final int MAX_VARIANTS = HelloService.MAX_APP_VARIANTS;

  @Test
  @Tag("req:C-155")
  @DisplayName(
      "C-155 (5) after a token rotation (old token GRACE, new token ACTIVE) the new token reaches the"
          + " variant the old one registered: the same app and instance")
  void rotationKeepsVariants() throws Exception {
    String app = app();
    AgentTokens.IssuedToken old = collector.token(app, "prod");
    HelloResult first;
    try (FakeAgent a =
        collector.agent(old.token()).appName(app).env("stage").instanceName("rot:1").build()) {
      first = a.connect();
      assertThat(first.accepted()).isTrue();
    }
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE argos.agent_tokens SET status = 'GRACE', grace_until = now() + interval"
                    + " '10 minutes' WHERE id = ?")) {
      ps.setObject(1, old.id());
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    AgentTokens.IssuedToken rotated = collector.token(app, "prod");
    assertThat(rotated.appId()).isEqualTo(old.appId());
    try (FakeAgent a =
        collector.agent(rotated.token()).appName(app).env("stage").instanceName("rot:1").build()) {
      HelloResult again = a.connect();
      assertThat(again.accepted()).as(again.toString()).isTrue();
      assertThat(again.hello().getAppId()).isEqualTo(first.hello().getAppId());
      assertThat(again.hello().getInstanceId()).isEqualTo(first.hello().getInstanceId());
    }
  }

  @Test
  @Tag("req:C-155")
  @DisplayName(
      "C-155 (5) a deleted home app leaves its variants with registered_from_app_id NULL (ON DELETE"
          + " SET NULL); a variant's own token still reaches it")
  void homeAppDeleted() throws Exception {
    String app = app();
    AgentTokens.IssuedToken home = collector.token(app, "prod");
    int stageId;
    try (FakeAgent a = collector.agent(home.token()).appName(app).env("stage").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).isTrue();
      stageId = r.hello().getAppId();
    }
    String stageToken = collector.token(app, "stage").token();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement tokens =
            c.prepareStatement("DELETE FROM argos.agent_tokens WHERE app_id = ?");
        PreparedStatement apps = c.prepareStatement("DELETE FROM argos.apps WHERE id = ?")) {
      tokens.setInt(1, home.appId());
      tokens.executeUpdate();
      apps.setInt(1, home.appId());
      assertThat(apps.executeUpdate()).isEqualTo(1);
    }
    assertThat(count("SELECT count(*) FROM argos.apps WHERE id = ?", stageId)).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM argos.apps WHERE id = ? AND registered_from_app_id IS NULL",
                stageId))
        .isEqualTo(1);
    try (FakeAgent a = collector.agent(stageToken).appName(app).env("stage").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).as(r.toString()).isTrue();
      assertThat(r.hello().getAppId()).isEqualTo(stageId);
    }
  }

  @Test
  @Tag("req:C-155")
  @Tag("req:SEC-11")
  @DisplayName(
      "C-155 (4) an environment of 33 bytes is refused (RESOURCE_EXHAUSTED, no app row, nothing"
          + " cut to 32); one of exactly 32 characters is registered whole")
  void environmentNeverCut() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    String env32 = "e".repeat(32);
    try (FakeAgent a = collector.agent(token).appName(app).env(env32 + "x").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).isFalse();
      assertThat(r.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(r.rejectReason()).isEqualTo(RejectReason.REJECT_REASON_UNSPECIFIED);
    }
    assertThat(countApps(app, env32)).as("never cut to 32 characters").isZero();
    try (FakeAgent a = collector.agent(token).appName(app).env(env32).build()) {
      assertThat(a.connect().accepted()).isTrue();
    }
    assertThat(countApps(app, env32)).isEqualTo(1);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (4) two instance names that share their first 128 characters are two instances rows,"
          + " each stored as 119 characters, '~' and 8 hex digits")
  void longInstanceNames() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    String prefix = "pod-".repeat(32);
    int a1;
    int a2;
    try (FakeAgent a = collector.agent(token).appName(app).instanceName(prefix + "a").build();
        FakeAgent b = collector.agent(token).appName(app).instanceName(prefix + "b").build()) {
      HelloResult ra = a.connect();
      HelloResult rb = b.connect();
      assertThat(ra.accepted()).isTrue();
      assertThat(rb.accepted()).isTrue();
      a1 = ra.hello().getInstanceId();
      a2 = rb.hello().getInstanceId();
    }
    assertThat(a1).as("two instances, not one row of a cut name").isNotEqualTo(a2);
    List<String> names = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT name FROM argos.instances WHERE id IN (?, ?) ORDER BY id")) {
      ps.setInt(1, a1);
      ps.setInt(2, a2);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
    }
    assertThat(names)
        .hasSize(2)
        .doesNotHaveDuplicates()
        .allSatisfy(
            n ->
                assertThat(n)
                    .hasSize(128)
                    .startsWith(prefix.substring(0, 119) + "~")
                    .matches(".{119}~[0-9a-f]{8}"));
  }

  @Test
  @DisplayName(
      "T-SV-014 11 §5 check 6: instances per app and new sessions per minute: INSTANCE_LIMIT / RATE_LIMITED, retry 60")
  @Tag("req:SEC-14")
  void check6() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (CollectorHarness limited =
            CollectorHarness.builder(env)
                .property("argos.collector.session.instances-per-app", 2)
                .property("argos.collector.session.new-per-minute", 3)
                .start();
        FakeAgent a = limited.agent(token).appName(app).build()) {
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i1").build()).accepted())
          .isTrue();
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i2").build()).accepted())
          .isTrue();
      HelloResult third = a.connect(a.helloTemplate().toBuilder().setInstanceName("i3").build());
      rejected(third, RejectReason.INSTANCE_LIMIT, Status.Code.RESOURCE_EXHAUSTED, 60);
      assertThat(third.trailers().get(CallResult.RETRY_AFTER_MS)).isEqualTo("60000");
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i1").build()).accepted())
          .isTrue();
      HelloResult fourth = a.connect(a.helloTemplate().toBuilder().setInstanceName("i2").build());
      rejected(fourth, RejectReason.RATE_LIMITED, Status.Code.RESOURCE_EXHAUSTED, 60);
    }
  }

  @Test
  @DisplayName(
      "T-SV-014 SEC-14 11 §5 check 6: new instances per app and hour on PostgreSQL first_seen_at:"
          + " the third new instance is INSTANCE_LIMIT, RESOURCE_EXHAUSTED, retry 60")
  @Tag("req:SEC-14")
  void check6NewInstancesPerHour() {
    String app = app();
    String token = collector.token(app, "prod").token();
    // The session rate limits stay out of the way, so that the hourly cap is the one that stops.
    try (CollectorHarness limited =
            CollectorHarness.builder(env)
                .property("argos.collector.session.new-instances-per-hour", 2)
                .property("argos.collector.session.new-per-minute", 1_000)
                .property("argos.collector.session.max-per-token", 1_000)
                .start();
        FakeAgent a = limited.agent(token).appName(app).build()) {
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i1").build()).accepted())
          .isTrue();
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i2").build()).accepted())
          .isTrue();
      HelloResult third = a.connect(a.helloTemplate().toBuilder().setInstanceName("i3").build());
      rejected(third, RejectReason.INSTANCE_LIMIT, Status.Code.RESOURCE_EXHAUSTED, 60);
      assertThat(third.trailers().get(CallResult.RETRY_AFTER_MS)).isEqualTo("60000");
      assertThat(third.hello().getRejectMessage())
          .as("the hourly cap, not the instances-per-app cap")
          .contains("2 new instances per app and hour");
      assertThat(a.connect(a.helloTemplate().toBuilder().setInstanceName("i1").build()).accepted())
          .as("an existing instance is not new")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "12 §3.9 'PostgreSQL 다운 | 새 세션 수락은 토큰 캐시 적중 시만': with the runtime account refused by"
          + " the real PostgreSQL, a known instance reconnects (cached token and registration), a"
          + " new instance is UNAVAILABLE retry 30")
  void postgresDown() throws Exception {
    String app = app();
    String token = collector.token(app, "prod").token();
    int instanceId;
    try (FakeAgent known = collector.agent(token).appName(app).instanceName("known:1").build()) {
      HelloResult first = known.connect();
      assertThat(first.accepted()).isTrue();
      instanceId = first.hello().getInstanceId();
      known.cancelSession();
    }
    // PostgreSQL refuses every new connection of argos_app (the collector harness opens one per
    // statement): a real database failure, not a mock. docker pause would make JDBC wait without a
    // socket timeout instead of failing.
    setAppLogin(false);
    try {
      try (FakeAgent known = collector.agent(token).appName(app).instanceName("known:1").build()) {
        HelloResult again = known.connect();
        assertThat(again.accepted()).as(again.toString()).isTrue();
        assertThat(again.hello().getInstanceId()).isEqualTo(instanceId);
      }
      try (FakeAgent fresh = collector.agent(token).appName(app).instanceName("new:1").build()) {
        HelloResult r = fresh.connect();
        assertThat(r.accepted()).isFalse();
        assertThat(r.code()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(r.retryAfterS()).isEqualTo(30);
        assertThat(r.trailers().get(CallResult.RETRY_AFTER_MS)).isEqualTo("30000");
      }
    } finally {
      setAppLogin(true);
    }
    assertThat(count("SELECT count(*) FROM argos.instances WHERE name = ?", "new:1"))
        .as("nothing was registered meanwhile")
        .isZero();
  }

  /** {@code ALTER ROLE argos_app [NO]LOGIN} as the image's superuser over the local socket. */
  private static void setAppLogin(boolean login) throws Exception {
    var result =
        env.postgresContainer()
            .execInContainer(
                "psql",
                "-X",
                "-q",
                "-v",
                "ON_ERROR_STOP=1",
                "-U",
                "postgres",
                "-d",
                ServerTestEnvironment.DATABASE,
                "-c",
                "ALTER ROLE " + PgAccount.APP.user() + (login ? " LOGIN" : " NOLOGIN"));
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  @Test
  @DisplayName(
      "11 §5 check 7: a second Hello of the instance replaces the open session (REPLACED); the old ids are SESSION_REQUIRED")
  void check7() {
    String app = app();
    String token = collector.token(app, "prod").token();
    try (FakeAgent a = collector.agent(token).appName(app).instanceName("same:1").build();
        FakeAgent b = collector.agent(token).appName(app).instanceName("same:1").build()) {
      HelloResult first = a.connect();
      assertThat(first.accepted()).isTrue();
      HelloResult second = b.connect();
      assertThat(second.accepted()).isTrue();
      assertThat(second.hello().getInstanceId()).isEqualTo(first.hello().getInstanceId());
      HelloResult end = a.awaitSessionEnd(Duration.ofSeconds(10));
      assertThat(end.code()).isEqualTo(Status.Code.OK);
      assertThat(a.sessionClose())
          .hasValueSatisfying(c -> assertThat(c.getReason()).isEqualTo(CloseReason.REPLACED));
      CallResult old =
          a.send(
              BatchKind.TRANSACTIONS,
              a.batches().transactions(List.of(a.records().transaction().build())),
              1,
              FakeAgent.CallSpec.defaults());
      assertThat(old.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
      assertThat(old.status().getDescription()).isEqualTo("SESSION_REQUIRED");
      CallResult current =
          b.sendTransactions(b.batches().transactions(List.of(b.records().transaction().build())));
      assertThat(current.ok()).as(current.toString()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "11 §5 an accepted Hello writes the instances row (identity and metadata, no state) and reuses it")
  void instanceRow() throws Exception {
    String app = app();
    AgentTokens.IssuedToken issued = collector.token(app, "prod");
    int instanceId;
    try (FakeAgent a = collector.agent(issued.token()).appName(app).instanceName("box:9").build()) {
      HelloResult r = a.connect();
      assertThat(r.accepted()).isTrue();
      instanceId = r.hello().getInstanceId();
      assertThat(r.hello().getUserIdSalt().isEmpty()).as("C-125").isTrue();
      assertThat(r.hello().getConfigMap()).isEmpty();
    }
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT name, host, pid, jvm_version, cgroup, virtual_threads, agent_version,"
                    + " protocol_version, version_tag, ip_mode, user_id_mode, query_string_mode,"
                    + " sql_param_mode, config_hash, remote_config_allow, status, clock_offset_ms"
                    + " FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString("name")).isEqualTo("box:9");
        assertThat(rs.getString("host")).isEqualTo("fake-host");
        assertThat(rs.getInt("pid")).isEqualTo(4242);
        assertThat(rs.getString("jvm_version")).isEqualTo("21.0.9");
        assertThat(rs.getString("cgroup")).isEqualTo("v2");
        assertThat(rs.getBoolean("virtual_threads")).isTrue();
        assertThat(rs.getString("agent_version")).isEqualTo("0.0.0");
        assertThat(rs.getInt("protocol_version")).isEqualTo(1);
        assertThat(rs.getString("version_tag")).isEqualTo("1.0.0");
        assertThat(rs.getString("ip_mode")).isEqualTo("masked");
        assertThat(rs.getString("user_id_mode")).isEqualTo("off");
        assertThat(rs.getString("query_string_mode")).isEqualTo("off");
        assertThat(rs.getString("sql_param_mode")).isEqualTo("masked");
        assertThat(rs.getString("config_hash")).isEqualTo("0123456789abcdef");
        assertThat((String[]) rs.getArray("remote_config_allow").getArray())
            .containsExactly("sampling.*", "log.level");
        assertThat(rs.getString("status")).as("state columns are WO-22's").isEqualTo("DOWN");
        assertThat(rs.getObject("clock_offset_ms")).isNull();
      }
    }
    try (FakeAgent again =
        collector.agent(issued.token()).appName(app).instanceName("box:9").build()) {
      assertThat(again.connect().hello().getInstanceId()).isEqualTo(instanceId);
    }
    assertThat(
            count(
                "SELECT count(*) FROM argos.agent_tokens WHERE id = ? AND last_used_at IS NOT NULL",
                issued.id()))
        .as("16 §2.2 last_used_at")
        .isEqualTo(1);
  }

  private static int countApps(String name, String appEnv) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT count(*) FROM argos.apps WHERE name = ? AND env = ?")) {
      ps.setString(1, name);
      ps.setString(2, appEnv);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** {@code apps.registered_from_app_id} of {@code appId}, 0 when NULL. */
  private static int registeredFrom(int appId) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT registered_from_app_id FROM argos.apps WHERE id = ?")) {
      ps.setInt(1, appId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("app %d", appId).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private static int count(String sql, Object arg) throws Exception {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, arg);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
