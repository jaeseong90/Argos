/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.ProtocolLimits;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.UserIdMode;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.CollectorState;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.collector.limits.CollectorLimits;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.testing.auth.MutableClock;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The 11 §5 check table of {@link HelloService}: each path's gRPC status, {@code RejectReason},
 * {@code retry_after_s} and trailers, and the order (a Hello that fails several checks is refused
 * by the first one). The data path over the wire is {@code HelloPathsIT}.
 */
@Tag("req:F-S01")
@Tag("req:SEC-01")
class HelloServiceTest {

  private final MutableClock clock = new MutableClock();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final CollectorMetrics metrics = new CollectorMetrics(meters);
  private final InMemorySessionStore store = new InMemorySessionStore(clock);

  /** The token's app, its home app (ADR-0010 C-155): it exists before any Hello of its token. */
  private final AppRef home = store.addApp("order-api", "prod");

  private final InMemorySessionRegistry registry = new InMemorySessionRegistry();
  private final CollectorState state = new CollectorState();
  private final TokenRecord token =
      new TokenRecord(
          UUID.randomUUID(),
          home.id(),
          "order-api",
          "prod",
          "argos_AbCdEfGh",
          TokenStatus.ACTIVE,
          null,
          null,
          null);

  private HelloService service(UnaryOperator<Settings> change) {
    Settings s = change.apply(new Settings());
    return new HelloService(
        new CachingSessionStore(store),
        registry,
        new SessionSettings(
            s.allowPlaintext,
            s.autoRegister,
            s.perToken,
            s.perMinute,
            s.perApp,
            s.perHour,
            Duration.ofSeconds(30),
            CollectorLimits.defaults().toProto(),
            SessionSettings.HELLO_TIMEOUT),
        new AgentVersionRange("1.4.0"),
        new LimitsChecker(CollectorLimits.defaults()),
        state,
        metrics,
        clock);
  }

  /** Mutable settings of a test. */
  static final class Settings {
    boolean allowPlaintext;
    Map<String, Boolean> autoRegister = Map.of("default", true);
    int perToken = 100;
    int perMinute = 60;
    int perApp = 1_000;
    int perHour = 100;
  }

  private static AgentHello.Builder hello() {
    return AgentHello.newBuilder()
        .setAgentVersion("1.4.0")
        .setProtocolVersion(ProtocolVersion.CURRENT)
        .setAppName("order-api")
        .setEnv("prod")
        .setInstanceName("host-a:8080")
        .setHost("host-a")
        .setPid(42)
        .setIpMode(IpMode.IP_MASKED)
        .setUserIdMode(UserIdMode.USER_ID_OFF)
        .setQueryStringMode(QueryStringMode.QS_OFF)
        .setSqlParamMode(SqlParamMode.SQL_PARAM_MASKED)
        .setAgentEpoch(77)
        .setSentAtMs(1_000);
  }

  private AuthResult accepted() {
    return new AuthResult(
        AuthResult.Outcome.ACCEPTED, TokenVerdict.ACCEPTED, token, "argos_AbCdEfGh");
  }

  private HelloOutcome run(
      HelloService service, AgentHello.Builder hello, AuthResult auth, boolean plaintext) {
    return service.accept(
        hello.build(), auth, plaintext, new SessionStream(new RecordingObserver<>()));
  }

  private static void rejected(
      HelloOutcome o, RejectReason reason, Status.Code code, int retryAfterS) {
    assertThat(o.accepted()).as("accepted").isFalse();
    ServerHello h = o.hello();
    assertThat(h.getAccepted()).isFalse();
    assertThat(h.getRejectReason()).isEqualTo(reason);
    assertThat(h.getRetryAfterS()).isEqualTo(retryAfterS);
    assertThat(o.status().getCode()).isEqualTo(code);
    assertThat(h.getRejectMessage()).isNotBlank().doesNotContain("argos_AbCdEfGh");
    if (code != Status.Code.OK && reason != RejectReason.REJECT_REASON_UNSPECIFIED) {
      assertThat(o.trailers().get(CollectorCalls.REJECT_REASON)).isEqualTo(reason.name());
    }
    if (code != Status.Code.OK && retryAfterS > 0) {
      assertThat(o.trailers().get(CollectorCalls.RETRY_AFTER_MS))
          .isEqualTo(Long.toString(retryAfterS * 1000L));
    }
    if (code == Status.Code.OK) {
      assertThat(o.trailers().keys()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "11 §5 check 1: missing, expired and revoked tokens are UNAUTHENTICATED TOKEN_* with retry"
          + " 300 (C-140)")
  void check1Token() {
    HelloService s = service(x -> x);
    rejected(
        run(s, hello(), null, false), RejectReason.TOKEN_INVALID, Status.Code.UNAUTHENTICATED, 300);
    for (TokenVerdict v :
        new TokenVerdict[] {TokenVerdict.UNKNOWN, TokenVerdict.EXPIRED, TokenVerdict.REVOKED}) {
      AuthResult auth = new AuthResult(AuthResult.Outcome.REJECTED, v, null, "argos_****");
      rejected(run(s, hello(), auth, false), v.rejectReason(), Status.Code.UNAUTHENTICATED, 300);
    }
    AuthResult down =
        new AuthResult(AuthResult.Outcome.UNAVAILABLE, TokenVerdict.UNKNOWN, null, "argos_****");
    rejected(
        run(s, hello(), down, false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.UNAVAILABLE,
        30);
  }

  @Test
  @Tag("req:C-155")
  @DisplayName(
      "T-SEC-01 11 §5 check 2: another app's token is PERMISSION_DENIED APP_MISMATCH, retry 0")
  void check2App() {
    rejected(
        run(service(x -> x), hello().setAppName("billing"), accepted(), false),
        RejectReason.APP_MISMATCH,
        Status.Code.PERMISSION_DENIED,
        0);
  }

  @Test
  @DisplayName(
      "11 §5 check 3: a plaintext transport while allow-plaintext=false is PLAINTEXT_NOT_ALLOWED,"
          + " decided from the transport")
  void check3Plaintext() {
    rejected(
        run(service(x -> x), hello().setPlaintext(false), accepted(), true),
        RejectReason.PLAINTEXT_NOT_ALLOWED,
        Status.Code.FAILED_PRECONDITION,
        0);
    HelloService allowing =
        service(
            x -> {
              x.allowPlaintext = true;
              return x;
            });
    assertThat(run(allowing, hello().setPlaintext(true), accepted(), true).accepted()).isTrue();
  }

  @ParameterizedTest(name = "11 §5 check 4: protocol_version {0} is PROTOCOL_UNSUPPORTED")
  @CsvSource({"0", "2", "-1"})
  void check4Protocol(int version) {
    HelloOutcome o = run(service(x -> x), hello().setProtocolVersion(version), accepted(), false);
    rejected(o, RejectReason.PROTOCOL_UNSUPPORTED, Status.Code.FAILED_PRECONDITION, 0);
    assertThat(o.hello().getRejectMessage()).contains("1 to 1").contains("1.2.x to 1.4.x");
  }

  @ParameterizedTest(name = "11 §5 check 4: agent {0} is {1}")
  @CsvSource({
    "1.5.0, AGENT_VERSION_TOO_NEW",
    "2.0.0, AGENT_VERSION_TOO_NEW",
    "1.1.9, AGENT_VERSION_TOO_OLD",
    "x, AGENT_VERSION_TOO_OLD"
  })
  void check4AgentVersion(String version, RejectReason reason) {
    HelloOutcome o = run(service(x -> x), hello().setAgentVersion(version), accepted(), false);
    rejected(o, reason, Status.Code.FAILED_PRECONDITION, 0);
    assertThat(o.hello().getMinAgentVersion()).isEqualTo("1.2");
    assertThat(o.hello().getMaxAgentVersion()).isEqualTo("1.4");
  }

  @Test
  @DisplayName(
      "T-SV-043 11 §5 check 5: an unknown app with auto-register off is APP_NOT_REGISTERED, stream"
          + " OK, retry 300, queued for approval (name, env, host, the token's home app); on, it is"
          + " created as an environment variant of the token's app (C-155)")
  @Tag("req:F-S30")
  void check5Registration() {
    HelloService off =
        service(
            x -> {
              x.autoRegister = Map.of("default", true, "qa", false);
              return x;
            });
    rejected(
        run(off, hello().setEnv("qa"), accepted(), false),
        RejectReason.APP_NOT_REGISTERED,
        Status.Code.OK,
        300);
    assertThat(store.apps).containsOnlyKeys("order-api/prod");
    assertThat(store.pending)
        .containsExactly(
            new InMemorySessionStore.Pending(
                "order-api", "qa", "host-a", home.id(), clock.instant()));
    assertThat(run(off, hello(), accepted(), false).accepted())
        .as("the token's own app needs no registration")
        .isTrue();
    HelloOutcome on = run(service(x -> x), hello().setEnv("qa"), accepted(), false);
    assertThat(on.accepted()).isTrue();
    assertThat(store.apps).containsKey("order-api/qa");
    assertThat(store.apps.get("order-api/qa").registeredFromAppId()).isEqualTo(home.id());
    assertThat(on.session().appId()).isEqualTo(store.apps.get("order-api/qa").id());
  }

  @Test
  @Tag("req:F-S30")
  @DisplayName(
      "T-SV-043 a failed approval-queue write does not change the answer: APP_NOT_REGISTERED,"
          + " stream OK, retry 300 (the agent's retry queues it)")
  void queueFailureKeepsAnswer() {
    store.pendingFailing = true;
    HelloService off =
        service(
            x -> {
              x.autoRegister = Map.of("default", true, "qa", false);
              return x;
            });
    rejected(
        run(off, hello().setEnv("qa"), accepted(), false),
        RejectReason.APP_NOT_REGISTERED,
        Status.Code.OK,
        300);
    assertThat(store.pending).isEmpty();
  }

  @Test
  @DisplayName(
      "C-158 (2) a Hello whose instance name is its host:pid (no port yet) has a temporary name;"
          + " a configured or host:port name does not")
  void temporaryName() {
    assertThat(HelloService.temporaryName(hello().setInstanceName("host-a:42").build())).isTrue();
    assertThat(HelloService.temporaryName(hello().setInstanceName("").build())).isTrue();
    assertThat(HelloService.temporaryName(hello().setInstanceName("host-a:8080").build()))
        .isFalse();
    assertThat(HelloService.temporaryName(hello().setInstanceName("order-1").build())).isFalse();
    assertThat(
            HelloService.temporaryName(hello().setHost("").setInstanceName("unknown:42").build()))
        .isTrue();
  }

  @Test
  @DisplayName("11 §5 check 5: an empty env is the 'default' environment")
  void defaultEnv() {
    HelloOutcome o = run(service(x -> x), hello().setEnv(""), accepted(), false);
    assertThat(o.accepted()).isTrue();
    assertThat(o.session().app().env()).isEqualTo("default");
  }

  @Test
  @Tag("req:SEC-01")
  @Tag("req:C-155")
  @DisplayName(
      "C-155 (2) an existing (name, env) app that is neither the token's app nor registered from"
          + " it (administrator-created, or another app's variant) is PERMISSION_DENIED"
          + " APP_MISMATCH, retry 0; nothing is created")
  void foreignEnvironmentAppDenied() {
    AppRef adminCreated = store.addApp("order-api", "dev");
    AppRef otherHome = store.addApp("order-api", "eu-prod");
    AppRef otherVariant = store.addVariantOf("order-api", "eu-qa", otherHome.id());
    HelloService s = service(x -> x);
    for (String env : List.of("dev", "eu-prod", "eu-qa")) {
      rejected(
          run(s, hello().setEnv(env), accepted(), false),
          RejectReason.APP_MISMATCH,
          Status.Code.PERMISSION_DENIED,
          0);
    }
    assertThat(store.apps).hasSize(4);
    assertThat(store.instances).as("no instance of a denied Hello").isEmpty();
    assertThat(rejectedCount("app_mismatch")).isEqualTo(3);
    assertThat(adminCreated.registeredFromAppId()).isZero();
    assertThat(otherVariant.usableWith(home.id())).isFalse();
    assertThat(otherVariant.usableWith(otherHome.id())).isTrue();
  }

  @Test
  @Tag("req:SEC-14")
  @Tag("req:C-155")
  @DisplayName(
      "T-SV-043 C-155 (3) a token's app registers at most 8 environment variants: the 9th"
          + " environment is APP_NOT_REGISTERED, stream OK, retry 300, counted as app_variant_limit"
          + " and queued for approval; the existing variants stay usable")
  @Tag("req:F-S30")
  void variantCap() {
    assertThat(HelloService.MAX_APP_VARIANTS).isEqualTo(8);
    HelloService s = service(x -> x);
    for (int i = 1; i <= 8; i++) {
      HelloOutcome o = run(s, hello().setEnv("env" + i), accepted(), false);
      assertThat(o.accepted()).as("variant %d", i).isTrue();
    }
    HelloOutcome ninth = run(s, hello().setEnv("env9"), accepted(), false);
    rejected(ninth, RejectReason.APP_NOT_REGISTERED, Status.Code.OK, 300);
    assertThat(ninth.hello().getRejectMessage()).contains("maximum of 8");
    assertThat(store.apps).hasSize(1 + 8).doesNotContainKey("order-api/env9");
    assertThat(store.pending)
        .extracting(InMemorySessionStore.Pending::env, InMemorySessionStore.Pending::homeAppId)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("env9", home.id()));
    assertThat(rejectedCount("app_variant_limit")).isEqualTo(1);
    assertThat(rejectedCount("app_not_registered")).isZero();
    assertThat(run(s, hello().setEnv("env3"), accepted(), false).accepted())
        .as("an existing variant is not a new registration")
        .isTrue();
    assertThat(run(s, hello(), accepted(), false).accepted()).as("the home app").isTrue();
  }

  @Test
  @Tag("req:C-155")
  @DisplayName(
      "C-155 (5) a rotated token of the same app reaches the variants registered with the old one")
  void rotationKeepsVariants() {
    HelloService s = service(x -> x);
    HelloOutcome first = run(s, hello().setEnv("stage").setInstanceName("s:1"), accepted(), false);
    assertThat(first.accepted()).isTrue();
    TokenRecord rotated =
        new TokenRecord(
            UUID.randomUUID(),
            home.id(),
            "order-api",
            "prod",
            "argos_NeWtOkEn",
            TokenStatus.ACTIVE,
            null,
            null,
            null);
    AuthResult auth =
        new AuthResult(
            AuthResult.Outcome.ACCEPTED, TokenVerdict.ACCEPTED, rotated, "argos_NeWtOkEn");
    HelloOutcome again = run(s, hello().setEnv("stage").setInstanceName("s:1"), auth, false);
    assertThat(again.accepted()).isTrue();
    assertThat(again.session().appId()).isEqualTo(first.session().appId());
    assertThat(again.session().instanceId()).isEqualTo(first.session().instanceId());
  }

  @Test
  @Tag("req:SEC-11")
  @Tag("req:C-155")
  @DisplayName(
      "C-155 (4) an environment above 32 bytes is refused (RESOURCE_EXHAUSTED, nothing created),"
          + " never cut; one of exactly 32 characters is stored whole")
  void environmentNeverCut() {
    HelloService s = service(x -> x);
    String env32 = "e".repeat(32);
    rejected(
        run(s, hello().setEnv(env32 + "x"), accepted(), false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.RESOURCE_EXHAUSTED,
        0);
    assertThat(store.apps).containsOnlyKeys("order-api/prod");
    HelloOutcome ok = run(s, hello().setEnv(env32), accepted(), false);
    assertThat(ok.accepted()).isTrue();
    assertThat(ok.session().app().env()).isEqualTo(env32);
    assertThat(store.apps).containsKey("order-api/" + env32);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (4) two instance names that share their first 128 characters are two instances; a"
          + " name of exactly 128 characters is stored as is")
  void longInstanceNames() {
    HelloService s = service(x -> x);
    String prefix = "i".repeat(128);
    HelloOutcome a = run(s, hello().setInstanceName(prefix + "-a"), accepted(), false);
    HelloOutcome b = run(s, hello().setInstanceName(prefix + "-b"), accepted(), false);
    HelloOutcome exact = run(s, hello().setInstanceName(prefix), accepted(), false);
    assertThat(a.accepted()).isTrue();
    assertThat(b.accepted()).isTrue();
    assertThat(a.session().instanceId()).isNotEqualTo(b.session().instanceId());
    assertThat(exact.session().instanceId())
        .isNotEqualTo(a.session().instanceId())
        .isNotEqualTo(b.session().instanceId());
    assertThat(store.instances)
        .extracting(InMemorySessionStore.Instance::name)
        .containsExactlyInAnyOrder(
            SessionStore.storedInstanceName(prefix + "-a"),
            SessionStore.storedInstanceName(prefix + "-b"),
            prefix)
        .allSatisfy(n -> assertThat(n).hasSizeLessThanOrEqualTo(128));
    assertThat(exact.session().instanceName()).isEqualTo(prefix);
    assertThat(
            run(s, hello().setInstanceName(prefix + "-a"), accepted(), false)
                .session()
                .instanceId())
        .as("the same long name finds its row again")
        .isEqualTo(a.session().instanceId());
  }

  @Test
  @DisplayName(
      "T-SV-014 11 §5 check 6: instances per app and new instances per hour are INSTANCE_LIMIT,"
          + " retry 60")
  @Tag("req:SEC-14")
  void check6Instances() {
    store.addInstance(home.id(), "other:1", clock.instant().minus(Duration.ofHours(2)));
    HelloService onePerApp =
        service(
            x -> {
              x.perApp = 1;
              return x;
            });
    rejected(
        run(onePerApp, hello(), accepted(), false),
        RejectReason.INSTANCE_LIMIT,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    assertThat(run(onePerApp, hello().setInstanceName("other:1"), accepted(), false).accepted())
        .as("an existing instance is not new")
        .isTrue();
    HelloService onePerHour =
        service(
            x -> {
              x.perHour = 1;
              return x;
            });
    assertThat(run(onePerHour, hello().setInstanceName("n1"), accepted(), false).accepted())
        .isTrue();
    rejected(
        run(onePerHour, hello().setInstanceName("n2"), accepted(), false),
        RejectReason.INSTANCE_LIMIT,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    clock.advance(Duration.ofMinutes(61));
    assertThat(run(onePerHour, hello().setInstanceName("n2"), accepted(), false).accepted())
        .as("an hour later")
        .isTrue();
    assertThat(rejectedCount("instance_limit")).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "T-SEC-05a 11 §5 check 6: sessions per token and new sessions per token and minute are"
          + " RATE_LIMITED, retry 60, counted as session_limit and session_rate (16 §2.5)")
  @Tag("req:SEC-05")
  void check6Sessions() {
    HelloService twoPerToken =
        service(
            x -> {
              x.perToken = 2;
              return x;
            });
    assertThat(run(twoPerToken, hello().setInstanceName("a"), accepted(), false).accepted())
        .isTrue();
    assertThat(run(twoPerToken, hello().setInstanceName("b"), accepted(), false).accepted())
        .isTrue();
    rejected(
        run(twoPerToken, hello().setInstanceName("c"), accepted(), false),
        RejectReason.RATE_LIMITED,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    assertThat(run(twoPerToken, hello().setInstanceName("a"), accepted(), false).accepted())
        .as("replacing the token's own session is not one more")
        .isTrue();
    assertThat(rejectedCount("session_limit")).isEqualTo(1);
    assertThat(rejectedCount("session_rate")).isZero();

    HelloService threePerMinute =
        service(
            x -> {
              x.perMinute = 3;
              return x;
            });
    for (int i = 0; i < 3; i++) {
      assertThat(
              run(threePerMinute, hello().setInstanceName("m" + i), accepted(), false).accepted())
          .isTrue();
    }
    rejected(
        run(threePerMinute, hello().setInstanceName("m9"), accepted(), false),
        RejectReason.RATE_LIMITED,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    assertThat(rejectedCount("session_rate")).isEqualTo(1);
    assertThat(rejectedCount("session_limit")).isEqualTo(1);
    clock.advance(Duration.ofSeconds(61));
    assertThat(run(threePerMinute, hello().setInstanceName("m9"), accepted(), false).accepted())
        .isTrue();
    assertThat(rejectedCount("rate_limited"))
        .as("the per-token limits are not counted under the RejectReason name")
        .isZero();
    assertThat(rejectedCount("server_sessions")).isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-05a 11 §5 check 6: with MAX_SESSIONS live sessions a Hello for a new instance is"
          + " RESOURCE_EXHAUSTED RATE_LIMITED retry 60, counted as server_sessions (not"
          + " rate_limited); a reconnect of a live instance is still accepted")
  @Tag("req:SEC-05")
  void check6ServerSessions() {
    HelloService s = service(x -> x);
    HelloOutcome live = run(s, hello().setInstanceName("live:1"), accepted(), false);
    assertThat(live.accepted()).isTrue();
    // The other sessions of the server: another app and another token, so that only the
    // server-wide bound can refuse (the per-token and per-app limits of `token` stay far away).
    AppRef other = store.addApp("filler", "prod");
    UUID otherToken = UUID.randomUUID();
    for (int id = 1_000_000; registry.size() < HelloService.MAX_SESSIONS; id++) {
      registry.register(filler(other, otherToken, id));
    }
    assertThat(registry.size()).isEqualTo(HelloService.MAX_SESSIONS);

    HelloOutcome refused = run(s, hello().setInstanceName("new:1"), accepted(), false);
    rejected(refused, RejectReason.RATE_LIMITED, Status.Code.RESOURCE_EXHAUSTED, 60);
    assertThat(refused.hello().getRejectMessage()).contains("5000 agent sessions");
    assertThat(rejectedCount("server_sessions")).isEqualTo(1);
    assertThat(rejectedCount("rate_limited")).isZero();
    assertThat(rejectedCount("session_limit")).isZero();
    assertThat(rejectedCount("session_rate")).isZero();
    assertThat(store.instances)
        .as("a refused Hello creates no instance row")
        .noneMatch(i -> i.name().equals("new:1"));
    assertThat(registry.size()).isEqualTo(HelloService.MAX_SESSIONS);

    HelloOutcome reconnect = run(s, hello().setInstanceName("live:1"), accepted(), false);
    assertThat(reconnect.accepted()).as("a live instance reconnects at the bound").isTrue();
    assertThat(reconnect.session().instanceId()).isEqualTo(live.session().instanceId());
    assertThat(registry.find(live.session().instanceId())).contains(reconnect.session());
    assertThat(registry.size())
        .as("the reconnect replaces its session")
        .isEqualTo(HelloService.MAX_SESSIONS);
    assertThat(rejectedCount("server_sessions")).isEqualTo(1);
  }

  /** A live session of another app and token, registered directly to fill the registry. */
  private static AgentSession filler(AppRef app, UUID tokenId, int instanceId) {
    return new AgentSession(
        UUID.randomUUID(),
        tokenId,
        "argos_FiLlEr00",
        app,
        instanceId,
        "filler:" + instanceId,
        1,
        io.argos.server.session.api.IpMode.OFF,
        io.argos.server.session.api.UserIdMode.OFF,
        "1.4.0",
        "",
        null,
        null,
        List.of(),
        0,
        new ClockSync(0, 0),
        new SessionStream(new RecordingObserver<>()));
  }

  private double rejectedCount(String reason) {
    io.micrometer.core.instrument.Counter counter =
        meters.find("argos.collector.rejected").tag("reason", reason).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName(
      "11 §5 check 7: an open session of the instance gets SessionClose REPLACED and completes")
  void check7Replace() {
    HelloService s = service(x -> x);
    RecordingObserver<ServerMessage> first = new RecordingObserver<>();
    HelloOutcome a =
        s.accept(hello().setAgentEpoch(11).build(), accepted(), false, new SessionStream(first));
    HelloOutcome b =
        s.accept(
            hello().setAgentEpoch(11).build(),
            accepted(),
            false,
            new SessionStream(new RecordingObserver<>()));
    assertThat(a.session().instanceId()).isEqualTo(b.session().instanceId());
    assertThat(b.session().sessionId()).isNotEqualTo(a.session().sessionId());
    assertThat(first.messages()).hasSize(1);
    assertThat(first.messages().get(0).getClose().getReason()).isEqualTo(CloseReason.REPLACED);
    assertThat(first.completed).isTrue();
    assertThat(registry.find(b.session().instanceId())).contains(b.session());
  }

  /** One Hello that fails checks {@code from..5}; the first failing one must be reported. */
  @ParameterizedTest(name = "11 §5 order: failing checks {0}..5 reports {1}")
  @CsvSource({
    "1, TOKEN_INVALID",
    "2, APP_MISMATCH",
    "3, PLAINTEXT_NOT_ALLOWED",
    "4, PROTOCOL_UNSUPPORTED",
    "5, APP_NOT_REGISTERED"
  })
  void order(int from, RejectReason expected) {
    HelloService s =
        service(
            x -> {
              x.autoRegister = Map.of("default", false);
              return x;
            });
    AgentHello.Builder h = hello().setAppName("order-api").setEnv("nowhere");
    AuthResult auth = from <= 1 ? null : accepted();
    if (from <= 2) {
      h.setAppName("billing");
    }
    boolean plaintext = from <= 3;
    if (from <= 4) {
      h.setProtocolVersion(9);
    }
    HelloOutcome o = run(s, h, auth, plaintext);
    assertThat(o.hello().getRejectReason()).isEqualTo(expected);
  }

  @Test
  @DisplayName(
      "11 §10 (contract vectors) a Hello during the shutdown is UNAVAILABLE SERVER_SHUTTING_DOWN,"
          + " retry 5")
  void shuttingDown() {
    state.beginShutdown();
    rejected(
        run(service(x -> x), hello(), accepted(), false),
        RejectReason.SERVER_SHUTTING_DOWN,
        Status.Code.UNAVAILABLE,
        5);
  }

  @Test
  @DisplayName(
      "12 §3.9 'PostgreSQL 다운 | 새 세션 수락은 토큰 캐시 적중 시만 (세션 등록 캐시로 버팀)': a cached token and a"
          + " known instance start a session with the same instance_id; the metadata write is"
          + " skipped")
  void storeDownCachedInstanceAccepted() {
    HelloService s = service(x -> x);
    HelloOutcome first = run(s, hello(), accepted(), false);
    assertThat(first.accepted()).isTrue();

    store.failing = true;
    HelloOutcome again = run(s, hello().setJvmVersion("21.0.10"), accepted(), false);
    assertThat(again.accepted()).as("accepted from the registration cache").isTrue();
    assertThat(again.session().instanceId()).isEqualTo(first.session().instanceId());
    assertThat(again.session().appId()).isEqualTo(first.session().appId());
    assertThat(again.hello().getInstanceId()).isEqualTo(first.session().instanceId());
    assertThat(store.metadataWrites).as("PostgreSQL down: metadata not written").isZero();
    assertThat(registry.find(first.session().instanceId())).contains(again.session());
    assertThat(meters.find("argos.collector.rejected").tag("reason", "store_unavailable").counter())
        .isNull();
  }

  @Test
  @DisplayName(
      "12 §3.9 'PostgreSQL 다운' with a cached token but a new instance or app (registration cache"
          + " miss): UNAVAILABLE with retry 30, as '토큰 캐시 미스 + PG 다운 | Hello 거부 UNAVAILABLE"
          + " retry_after 30'")
  void storeDownCacheMissUnavailable() {
    HelloService s = service(x -> x);
    assertThat(run(s, hello(), accepted(), false).accepted()).isTrue();
    store.failing = true;
    rejected(
        run(s, hello().setInstanceName("new:1"), accepted(), false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.UNAVAILABLE,
        30);
    rejected(
        run(s, hello().setAppName("billing"), billingToken(), false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.UNAVAILABLE,
        30);
    rejected(
        run(service(x -> x), hello(), accepted(), false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.UNAVAILABLE,
        30);
    assertThat(store.instances).hasSize(1);
  }

  private AuthResult billingToken() {
    TokenRecord billing =
        new TokenRecord(
            UUID.randomUUID(),
            2,
            "billing",
            "prod",
            "argos_BcDeFgHi",
            TokenStatus.ACTIVE,
            null,
            null,
            null);
    return new AuthResult(
        AuthResult.Outcome.ACCEPTED, TokenVerdict.ACCEPTED, billing, "argos_BcDeFgHi");
  }

  @Test
  @DisplayName(
      "11 §5 the accepted ServerHello: ids, versions, server time, intervals, Limits, empty config"
          + " and salt (C-125)")
  void acceptedHello() {
    HelloOutcome o = run(service(x -> x), hello(), accepted(), false);
    ServerHello h = o.hello();
    assertThat(h.getAccepted()).isTrue();
    assertThat(h.getAppId()).isEqualTo(o.session().appId());
    assertThat(h.getInstanceId()).isEqualTo(o.session().instanceId());
    assertThat(UUID.fromString(h.getSessionId())).isEqualTo(o.session().sessionId());
    assertThat(h.getServerVersion()).isEqualTo("1.4.0");
    assertThat(h.getProtocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
    assertThat(h.getServerTimeMs()).isEqualTo(clock.millis());
    assertThat(h.getHeartbeatIntervalS()).isEqualTo(30);
    assertThat(h.getActiveSnapshotIntervalS()).isEqualTo(5);
    assertThat(h.getStatWindowS()).isEqualTo(10);
    assertThat(h.getLimits()).isEqualTo(ProtocolLimits.defaults());
    assertThat(h.getConfigMap()).isEmpty();
    assertThat(h.getUserIdSalt().isEmpty()).isTrue();
    assertThat(o.session().clock().estimateMs())
        .as("11 §9 first estimate")
        .isEqualTo(clock.millis() - 1_000);
    assertThat(store.touched).containsExactly(token.tokenId());
  }

  @Test
  @DisplayName(
      "11 §5 the instance id is reused for (app, instance_name); unchanged metadata is not"
          + " rewritten")
  void instanceReuse() {
    HelloService s = service(x -> x);
    HelloOutcome a = run(s, hello(), accepted(), false);
    HelloOutcome b = run(s, hello(), accepted(), false);
    assertThat(b.session().instanceId()).isEqualTo(a.session().instanceId());
    assertThat(store.metadataWrites).isZero();
    run(s, hello().setJvmVersion("21.0.10"), accepted(), false);
    assertThat(store.metadataWrites).isEqualTo(1);
    assertThat(
            run(s, hello().setInstanceName("").setHost("box").setPid(7), accepted(), false)
                .session()
                .instanceName())
        .as("12 §4.3 host:pid without a configured name")
        .isEqualTo("box:7");
  }

  @Test
  @DisplayName("C-143 (1) unknown ip_mode/user_id_mode are 'off' and counted")
  void unknownModes() {
    HelloOutcome o =
        run(
            service(x -> x),
            hello().setIpModeValue(9).setUserIdMode(UserIdMode.USER_ID_MODE_UNSPECIFIED),
            accepted(),
            false);
    assertThat(o.session().ipMode()).isEqualTo(io.argos.server.session.api.IpMode.OFF);
    assertThat(o.session().userIdMode()).isEqualTo(io.argos.server.session.api.UserIdMode.OFF);
    assertThat(meters.get("argos.collector.unknown.enum").tag("field", "ip_mode").counter().count())
        .isEqualTo(1);
    assertThat(
            meters
                .get("argos.collector.unknown.enum")
                .tag("field", "user_id_mode")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(store.instances.get(0).metadata().ipMode()).isEqualTo("off");
  }

  // --- check-only Hello (ADR-0010 C-240 (1), T-SV-022b) ---

  private static AgentHello.Builder checkHello() {
    return hello().setInstanceName("host-a-check").setCheckOnly(true);
  }

  @Test
  @Tag("req:C-240")
  @Tag("req:F-A27")
  @DisplayName(
      "T-SV-022b a check-only Hello is accepted without a session: app id, instance id 0, no"
          + " session id, versions, server time, Limits, empty config and salt; no instance row, no"
          + " registry entry, no pending row, nothing counted as rejected, stream ends OK")
  void checkOnlyAccepted() {
    HelloService s = service(x -> x);
    HelloOutcome o = run(s, checkHello(), accepted(), false);
    assertThat(o.checkOnly()).isTrue();
    assertThat(o.accepted()).as("no session").isFalse();
    assertThat(o.session()).isNull();
    assertThat(o.status().getCode()).isEqualTo(Status.Code.OK);
    assertThat(o.trailers().keys()).isEmpty();
    ServerHello h = o.hello();
    assertThat(h.getAccepted()).isTrue();
    assertThat(h.getAppId()).isEqualTo(home.id());
    assertThat(h.getInstanceId()).isZero();
    assertThat(h.getSessionId()).isEmpty();
    assertThat(h.getServerVersion()).isEqualTo("1.4.0");
    assertThat(h.getProtocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
    assertThat(h.getMinAgentVersion()).isNotEmpty();
    assertThat(h.getServerTimeMs()).isEqualTo(clock.millis());
    assertThat(h.getHeartbeatIntervalS()).isEqualTo(30);
    assertThat(h.getLimits()).isEqualTo(ProtocolLimits.defaults());
    assertThat(h.getConfigMap()).isEmpty();
    assertThat(h.getUserIdSalt().isEmpty()).isTrue();
    assertThat(store.instances).isEmpty();
    assertThat(store.pending).isEmpty();
    assertThat(registry.size()).isZero();
    assertThat(store.touched).containsExactly(token.tokenId());
    assertThat(meters.find("argos.collector.rejected").counters()).isEmpty();

    // An open session of the same instance name stays (no check 7 replacement).
    HelloOutcome live = run(s, hello().setInstanceName("host-a-check"), accepted(), false);
    assertThat(live.accepted()).isTrue();
    HelloOutcome again = run(s, checkHello(), accepted(), false);
    assertThat(again.checkOnly()).isTrue();
    assertThat(registry.find(live.session().instanceId())).contains(live.session());
    assertThat(registry.size()).isEqualTo(1);
    assertThat(store.instances).hasSize(1);
  }

  @Test
  @Tag("req:C-240")
  @DisplayName(
      "T-SV-022b check-only and the caps: accepted at the server's MAX_SESSIONS and at the"
          + " per-token session cap (it takes no session); the per-token Hello rate applies and"
          + " counts it; the Hello caps apply")
  void checkOnlyCaps() {
    HelloService s =
        service(
            x -> {
              x.perToken = 0;
              return x;
            });
    AppRef other = store.addApp("filler", "prod");
    UUID otherToken = UUID.randomUUID();
    for (int id = 1_000_000; registry.size() < HelloService.MAX_SESSIONS; id++) {
      registry.register(filler(other, otherToken, id));
    }
    rejected(
        run(s, hello(), accepted(), false),
        RejectReason.RATE_LIMITED,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    HelloOutcome o = run(s, checkHello(), accepted(), false);
    assertThat(o.checkOnly()).as("no session: neither cap applies").isTrue();
    assertThat(registry.size()).isEqualTo(HelloService.MAX_SESSIONS);
    assertThat(store.instances).isEmpty();

    HelloService rate =
        service(
            x -> {
              x.perMinute = 2;
              return x;
            });
    assertThat(run(rate, checkHello(), accepted(), false).checkOnly()).isTrue();
    assertThat(run(rate, checkHello(), accepted(), false).checkOnly()).isTrue();
    rejected(
        run(rate, checkHello(), accepted(), false),
        RejectReason.RATE_LIMITED,
        Status.Code.RESOURCE_EXHAUSTED,
        60);
    assertThat(rejectedCount("session_rate")).isEqualTo(1);

    HelloOutcome big =
        run(service(x -> x), checkHello().setInstanceName("x".repeat(3_000)), accepted(), false);
    assertThat(big.checkOnly()).as("the Hello caps apply first").isFalse();
    assertThat(big.status().getCode()).isNotEqualTo(Status.Code.OK);
  }

  @Test
  @Tag("req:C-240")
  @DisplayName(
      "T-SV-022b check-only and checks 1 to 5: token, app, plaintext and version refuse as for a"
          + " normal Hello; a missing app with automatic registration is answered with app id 0"
          + " (nothing created), without it APP_NOT_REGISTERED and nothing queued; PostgreSQL down"
          + " is UNAVAILABLE")
  void checkOnlyChecks() {
    HelloService s = service(x -> x);
    rejected(
        run(s, checkHello(), null, false),
        RejectReason.TOKEN_INVALID,
        Status.Code.UNAUTHENTICATED,
        300);
    rejected(
        run(s, checkHello().setAppName("other-app"), accepted(), false),
        RejectReason.APP_MISMATCH,
        Status.Code.PERMISSION_DENIED,
        0);
    rejected(
        run(s, checkHello(), accepted(), true),
        RejectReason.PLAINTEXT_NOT_ALLOWED,
        Status.Code.FAILED_PRECONDITION,
        0);
    rejected(
        run(s, checkHello().setAgentVersion("0.1.0"), accepted(), false),
        RejectReason.AGENT_VERSION_TOO_OLD,
        Status.Code.FAILED_PRECONDITION,
        0);

    HelloService auto =
        service(
            x -> {
              x.autoRegister = Map.of("staging", true);
              return x;
            });
    int apps = store.apps.size();
    HelloOutcome variant = run(auto, checkHello().setEnv("staging"), accepted(), false);
    assertThat(variant.checkOnly()).isTrue();
    assertThat(variant.hello().getAppId()).as("not registered yet").isZero();
    assertThat(store.apps).as("no app variant created").hasSize(apps);

    HelloService manual =
        service(
            x -> {
              x.autoRegister = Map.of("default", false);
              return x;
            });
    rejected(
        run(manual, checkHello().setEnv("qa"), accepted(), false),
        RejectReason.APP_NOT_REGISTERED,
        Status.Code.OK,
        300);
    assertThat(store.pending).as("nothing queued for approval").isEmpty();
    assertThat(store.instances).isEmpty();

    store.failing = true;
    rejected(
        run(service(x -> x), checkHello().setEnv("qa2"), accepted(), false),
        RejectReason.REJECT_REASON_UNSPECIFIED,
        Status.Code.UNAVAILABLE,
        30);
  }

  // --- start facts (F-A26, S-17, ADR-0010 C-240 (1), T-SV-023b) ---

  @Test
  @Tag("req:F-A26")
  @DisplayName(
      "T-SV-023b the session carries the Hello's process start and the instance's stored start"
          + " before the Hello: new instance, the previous process start, none, unreadable")
  void startFacts() {
    HelloService s = service(x -> x);
    HelloOutcome first = run(s, hello().setProcessStartMs(5_000), accepted(), false);
    assertThat(first.session().processStartMs()).isEqualTo(5_000);
    assertThat(first.session().storedStartMs()).isEqualTo(AgentSession.STARTED_NEW_INSTANCE);
    HelloOutcome again = run(s, hello().setProcessStartMs(5_000), accepted(), false);
    assertThat(again.session().storedStartMs()).isEqualTo(5_000);
    HelloOutcome restarted = run(s, hello().setProcessStartMs(9_000), accepted(), false);
    assertThat(restarted.session().storedStartMs())
        .as("read before the Hello wrote it")
        .isEqualTo(5_000);
    assertThat(store.instances.get(0).metadata().startedAt().toEpochMilli()).isEqualTo(9_000);
    HelloOutcome none = run(s, hello().setInstanceName("b:1"), accepted(), false);
    HelloOutcome noneAgain = run(s, hello().setInstanceName("b:1"), accepted(), false);
    assertThat(none.session().storedStartMs()).isEqualTo(AgentSession.STARTED_NEW_INSTANCE);
    assertThat(noneAgain.session().storedStartMs()).isEqualTo(AgentSession.STARTED_NONE);
    store.startedAtFailing = true;
    HelloOutcome unknown = run(s, hello().setProcessStartMs(9_000), accepted(), false);
    assertThat(unknown.accepted()).as("an unreadable start does not refuse the session").isTrue();
    assertThat(unknown.session().storedStartMs()).isEqualTo(AgentSession.STARTED_UNKNOWN);
  }
}
