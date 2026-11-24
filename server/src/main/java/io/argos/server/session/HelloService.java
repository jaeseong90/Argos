/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import com.google.protobuf.ByteString;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.ServerHello;
import io.argos.server.audit.AgentAuditEvents;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.CollectorState;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.collector.batch.UnknownEnumSink;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.collector.limits.SlidingWindowCounter;
import io.argos.server.collector.limits.Violation;
import io.argos.server.session.api.HelloConfigSource;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.grpc.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 11 §5 checks of a session start, in order, each failing before the next is looked at:
 *
 * <table>
 *   <caption>11 §5 checks, codes and retry advice (ADR-0010 C-140 for TOKEN_*)</caption>
 *   <tr><th>#</th><th>check</th><th>gRPC status</th><th>RejectReason</th><th>retry_after_s</th></tr>
 *   <tr><td>1</td><td>token (16 §2.4)</td><td>UNAUTHENTICATED</td><td>TOKEN_INVALID|EXPIRED|REVOKED</td>
 *       <td>300</td></tr>
 *   <tr><td>2</td><td>token's app (its <i>home app</i>) name = {@code app_name}</td>
 *       <td>PERMISSION_DENIED</td><td>APP_MISMATCH</td><td>0</td></tr>
 *   <tr><td>3</td><td>plaintext transport while {@code allow-plaintext=false}</td>
 *       <td>FAILED_PRECONDITION</td><td>PLAINTEXT_NOT_ALLOWED</td><td>0</td></tr>
 *   <tr><td>4</td><td>{@code protocol_version} in [1, CURRENT], {@code agent_version} in
 *       [X.(Y-2), X.Y]</td><td>FAILED_PRECONDITION</td>
 *       <td>PROTOCOL_UNSUPPORTED|AGENT_VERSION_TOO_NEW|TOO_OLD</td><td>0</td></tr>
 *   <tr><td>5</td><td>app (name, env) exists, or {@code auto-register.<env>} and the home app has
 *       fewer than {@value #MAX_APP_VARIANTS} environment variants</td><td>OK (stream
 *       completes)</td><td>APP_NOT_REGISTERED</td><td>300</td></tr>
 *   <tr><td>5</td><td>the existing app (name, env) is the home app or a variant registered from
 *       it</td><td>PERMISSION_DENIED</td><td>APP_MISMATCH</td><td>0</td></tr>
 *   <tr><td>6</td><td>live sessions of the server ({@link #MAX_SESSIONS}), sessions per token,
 *       new sessions per token and minute; instances per app, new instances per app and hour</td>
 *       <td>RESOURCE_EXHAUSTED</td><td>RATE_LIMITED|INSTANCE_LIMIT</td><td>60</td></tr>
 *   <tr><td>7</td><td>an open session of the instance gets {@code SessionClose{REPLACED}}</td>
 *       <td colspan="3">accepted</td></tr>
 * </table>
 *
 * Besides the table: a Hello during the shutdown is {@code UNAVAILABLE SERVER_SHUTTING_DOWN} with 5
 * s (the contract vectors); a Hello whose strings exceed their caps is refused after check 1 with
 * the limit's code (SEC-11). PostgreSQL unreadable (12 §3.9): a Hello whose token came from the
 * token cache (check 1 passed) and whose app and instance are in the registration cache ({@link
 * CachingSessionStore}, the store the collector configures) is accepted, the metadata write
 * skipped; a Hello that needs a new app or instance is {@code UNAVAILABLE} with 30 s. The trailers
 * are {@code argos-reject-reason} (the RejectReason name) and, when a retry is advised on a non-OK
 * status, {@code argos-retry-after-ms}. {@code argos_collector_rejected_total{reason}} names the
 * limit of a check 6 refusal ({@code server_sessions}, {@code session_limit}, {@code session_rate}
 * of 16 §2.5, {@code instance_limit}); checks 2 to 5 and the shutdown count the RejectReason in
 * lower case.
 *
 * <p>Token scope (ADR-0010 C-155): an agent token reaches its home app and the environment variants
 * that Hellos of the home app's tokens registered automatically ({@code
 * apps.registered_from_app_id}), never an app that an administrator or another app's token created
 * for the same name. A missing (name, env) is registered as a variant of the home app while the
 * home app's registration policy is {@code auto} (or it has none and {@code auto-register.<env>} is
 * on: {@link RegistrationPolicySource}, ADR-0010 C-231 (7)) and the home app has fewer than {@value
 * #MAX_APP_VARIANTS} variants; the count holds under concurrent Hellos on any number of servers
 * (the home app's row is locked, {@link SessionStore#createVariantApp}). A refusal at the cap is
 * counted as {@code app_variant_limit}. The environment is never cut: one above 32 bytes is refused
 * by the Hello caps like any string above its cap. Token rotation keeps the home app, so the new
 * token reaches the same variants.
 *
 * <p>Approval queue (12 §4.4, F-S30, WO-22): a Hello refused {@code APP_NOT_REGISTERED} by check 5
 * (automatic registration off for the environment, or the home app at its variant cap) is queued in
 * {@code pending_registrations} ({@link SessionStore#recordPendingRegistration}: name, env, host,
 * attempts, last attempt, the token's home app); an administrator's approval (WO-26's API over
 * {@code PendingRegistrationRepository.approve}) creates the variant of the home app, and the same
 * token's next retry is accepted.
 *
 * <p>An accepted Hello reuses or creates the {@code instances} row of (app, instance name) with the
 * Hello metadata, registers the session (check 7) and answers with the ids, the server and protocol
 * versions, the accepted agent range, the server time (first clock estimate, 11 §9), the intervals,
 * the configured {@code Limits}, the {@code config} of the {@link HelloConfigSource} (WO-26: the
 * values the server holds for the app within the Hello's {@code remote_config_allow}, 12 §6.2) and
 * the app's {@code user_id_salt} ({@link SaltSource}, 16 §6.3, ADR-0010 C-125 (2), WO-28: created
 * on first use; empty when it cannot be read, which does not refuse the session).
 *
 * <p>Check 6 and the registration run under a per-app lock, so that two Hellos of one app cannot
 * both pass the instance count.
 */
public final class HelloService {

  private static final Logger LOG = LoggerFactory.getLogger(HelloService.class);

  /** The lowest protocol version this server accepts (11 §5: the server starts at [1, 1]). */
  public static final int MIN_PROTOCOL_VERSION = 1;

  /** 11 §5 check 5: retry advice of {@code APP_NOT_REGISTERED}. */
  public static final int APP_NOT_REGISTERED_RETRY_AFTER_S = 300;

  /**
   * ADR-0010 C-155 (3): environment variants an agent token's home app may register automatically.
   * At the cap a Hello for another environment is {@code APP_NOT_REGISTERED} with {@value
   * #APP_NOT_REGISTERED_RETRY_AFTER_S} s, counted as {@code app_variant_limit}.
   */
  public static final int MAX_APP_VARIANTS = 8;

  /** 11 §5 check 6: retry advice of {@code INSTANCE_LIMIT} and {@code RATE_LIMITED}. */
  public static final int LIMIT_RETRY_AFTER_S = 60;

  /** 12 §3.9: retry advice when PostgreSQL cannot be read and the registration cache misses. */
  public static final int STORE_UNAVAILABLE_RETRY_AFTER_S = 30;

  /**
   * Upper bound of live sessions of the whole server: the memory bound of the registry and of the
   * deduplication window ({@code InMemoryDedupWindow.MAX_KEYS} = this × 2 epochs × 4 kinds, about
   * 325 MB with every ring full, 12 §11). 16 times the instances of one server in 01 (10 to 300).
   *
   * <p>At the bound, check 6 refuses the Hello of an instance without a live session: {@code
   * RESOURCE_EXHAUSTED}, {@code RejectReason.RATE_LIMITED}, {@code retry_after_s} = {@value
   * #LIMIT_RETRY_AFTER_S}, counted as {@code
   * argos_collector_rejected_total{reason="server_sessions"}} (the per-token limits of 16 §2.5 are
   * {@code session_limit} and {@code session_rate}). A reconnect of an instance that has a live
   * session replaces it (check 7) and is still accepted. Not configurable in this release
   * (server/README.md, "Collector limits").
   */
  public static final int MAX_SESSIONS = 5_000;

  /** {@code ServerHello.active_snapshot_interval_s} (11 §4.2 default). */
  public static final int ACTIVE_SNAPSHOT_INTERVAL_S = 5;

  /** {@code ServerHello.stat_window_s} (11 §4.2 default). */
  public static final int STAT_WINDOW_S = 10;

  private static final int LOCKS = 64;

  private static final long METADATA_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final AtomicLong lastMetadataWarn = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastVariantWarn = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastPendingWarn = new AtomicLong(Long.MIN_VALUE);
  private final AtomicLong lastConfigWarn = new AtomicLong(Long.MIN_VALUE);
  private volatile HelloConfigSource configSource = HelloConfigSource.NONE;
  private volatile AgentAuditEvents audit = AgentAuditEvents.NONE;
  private volatile SaltSource salts = SaltSource.NONE;
  private volatile boolean saltsWired;
  private volatile RegistrationPolicySource policies = RegistrationPolicySource.NONE;
  private final AtomicLong lastSaltWarn = new AtomicLong(Long.MIN_VALUE);
  private final SessionStore store;
  private final SessionRegistry registry;
  private final SessionSettings settings;
  private final AgentVersionRange versions;
  private final LimitsChecker limits;
  private final CollectorState state;
  private final CollectorMetrics metrics;
  private final Clock clock;
  private final SlidingWindowCounter<UUID> newSessions;
  private final Object[] appLocks = new Object[LOCKS];

  /** The checks with their collaborators. */
  public HelloService(
      SessionStore store,
      SessionRegistry registry,
      SessionSettings settings,
      AgentVersionRange versions,
      LimitsChecker limits,
      CollectorState state,
      CollectorMetrics metrics,
      Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.versions = Objects.requireNonNull(versions, "versions");
    this.limits = Objects.requireNonNull(limits, "limits");
    this.state = Objects.requireNonNull(state, "state");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.newSessions =
        new SlidingWindowCounter<>(
            settings.newSessionsPerMinute(), Duration.ofMinutes(1), 100_000, Duration.ofMinutes(2));
    for (int i = 0; i < LOCKS; i++) {
      appLocks[i] = new Object();
    }
  }

  /**
   * Runs the checks for {@code hello} and, when it passes, registers a session on {@code stream}.
   *
   * <p>A refusal after the token check passed (checks 2 to 6, the Hello caps) is recorded as the
   * audit event {@code AGENT_SESSION_REJECTED} with the refusal code (queued, rate limited per
   * token prefix; ADR-0010 C-158 (1)); token refusals are {@code AGENT_AUTH_FAILED} of the token
   * interceptor, and the shutdown and database-unavailable answers are not audited.
   *
   * @param auth the token check of the call ({@code null} counts as a missing token)
   * @param plaintext whether the call arrived without TLS (11 §5 check 3 is decided from the
   *     transport; {@code AgentHello.plaintext} is informational only)
   */
  public HelloOutcome accept(
      AgentHello hello, AuthResult auth, boolean plaintext, SessionStream stream) {
    HelloOutcome outcome = decide(hello, auth, plaintext, stream);
    if (!outcome.accepted() && auth != null && auth.accepted()) {
      auditRejection(hello, auth, outcome);
    }
    return outcome;
  }

  /**
   * Connects the agent audit events (WO-28 {@code AgentAuditRecorder}); {@link
   * AgentAuditEvents#NONE} until then.
   */
  public void auditEvents(AgentAuditEvents events) {
    this.audit = events == null ? AgentAuditEvents.NONE : events;
  }

  /**
   * The user id salt of an app for {@code ServerHello.user_id_salt} (16 §6.3, ADR-0010 C-125 (2)).
   * Implementations create the salt on first use; they never log it.
   */
  @FunctionalInterface
  public interface SaltSource {

    /**
     * The 32-byte salt of app {@code appId}, or empty when it cannot be read now.
     *
     * @throws RuntimeException never expected; a failure is treated as empty
     */
    Optional<byte[]> salt(int appId);

    /** No salt (the Hello sends an empty one). */
    SaltSource NONE = appId -> Optional.empty();
  }

  /**
   * The registration policy an app sets for the unknown (name, env) of its tokens' Hellos ({@code
   * apps.auto_register}, ADR-0010 C-231 (7)): true {@code auto}, false {@code approval}, null none
   * (the environment default {@code argos.agent.auto-register.<env>} applies).
   */
  @FunctionalInterface
  public interface RegistrationPolicySource {

    /**
     * The policy of app {@code appId}, or null when it has none.
     *
     * @throws RuntimeException when PostgreSQL cannot be read (the Hello is answered UNAVAILABLE)
     */
    Boolean autoRegister(int appId);

    /** No app sets a policy: the environment default applies. */
    RegistrationPolicySource NONE = appId -> null;
  }

  /** Connects the app salts (WO-28); {@link SaltSource#NONE} until then. */
  public void userIdSalts(SaltSource source) {
    this.salts = source == null ? SaltSource.NONE : source;
    this.saltsWired = source != null;
  }

  /**
   * Connects the app registration policies (WO-28); {@link RegistrationPolicySource#NONE} until
   * then.
   */
  public void registrationPolicies(RegistrationPolicySource source) {
    this.policies = source == null ? RegistrationPolicySource.NONE : source;
  }

  private void auditRejection(AgentHello hello, AuthResult auth, HelloOutcome outcome) {
    try {
      Status.Code code = outcome.status().getCode();
      RejectReason reason = outcome.hello().getRejectReason();
      if (code == Status.Code.UNAVAILABLE || reason == RejectReason.SERVER_SHUTTING_DOWN) {
        return;
      }
      String r =
          reason == RejectReason.REJECT_REASON_UNSPECIFIED
              ? code.name().toLowerCase(Locale.ROOT)
              : reason.name().toLowerCase(Locale.ROOT);
      audit.sessionRejected(
          CollectorCalls.PEER.get(),
          auth.tokenPrefix(),
          auth.token().appId(),
          hello.getAppName(),
          hello.getEnv(),
          r);
    } catch (RuntimeException e) {
      LOG.debug("AGENT_SESSION_REJECTED not recorded", e);
    }
  }

  private HelloOutcome decide(
      AgentHello hello, AuthResult auth, boolean plaintext, SessionStream stream) {
    long now = clock.millis();
    ServerHello.Builder base = base(now);
    if (state.shuttingDown()) {
      return reject(
          base,
          RejectReason.SERVER_SHUTTING_DOWN,
          Status.Code.UNAVAILABLE,
          CollectorState.SHUTTING_DOWN_RETRY_AFTER_S,
          "The collector is shutting down; reconnect later",
          true);
    }
    // 1 token (counted and logged by TokenAuthInterceptor)
    if (auth == null || !auth.accepted()) {
      if (auth != null && auth.outcome() == AuthResult.Outcome.UNAVAILABLE) {
        return reject(
            base,
            RejectReason.REJECT_REASON_UNSPECIFIED,
            Status.Code.UNAVAILABLE,
            STORE_UNAVAILABLE_RETRY_AFTER_S,
            "The collector cannot read its token store; retry later",
            false);
      }
      TokenVerdict verdict = auth == null ? TokenVerdict.UNKNOWN : auth.verdict();
      return reject(
          base,
          verdict.rejectReason(),
          Status.Code.UNAUTHENTICATED,
          TokenVerdict.REJECT_RETRY_AFTER_S,
          "The agent token was rejected",
          false);
    }
    TokenRecord token = auth.token();
    Violation caps = limits.check(hello);
    if (caps != null) {
      metrics.rejected(caps.reason());
      return reject(
          base, RejectReason.REJECT_REASON_UNSPECIFIED, caps.code(), 0, caps.description(), false);
    }
    // 2 app
    if (!token.appName().equals(hello.getAppName())) {
      return reject(
          base,
          RejectReason.APP_MISMATCH,
          Status.Code.PERMISSION_DENIED,
          0,
          "The agent token belongs to another app",
          true);
    }
    // 3 plaintext
    if (plaintext && !settings.allowPlaintext()) {
      return reject(
          base,
          RejectReason.PLAINTEXT_NOT_ALLOWED,
          Status.Code.FAILED_PRECONDITION,
          0,
          "Plaintext connections are not allowed by this collector; enable server.tls on the agent",
          true);
    }
    // 4 protocol and agent version
    int protocol = hello.getProtocolVersion();
    if (protocol < MIN_PROTOCOL_VERSION || protocol > ProtocolVersion.CURRENT) {
      return reject(
          base,
          RejectReason.PROTOCOL_UNSUPPORTED,
          Status.Code.FAILED_PRECONDITION,
          0,
          "Protocol version "
              + Integer.toUnsignedString(protocol)
              + " is not supported; this collector speaks "
              + MIN_PROTOCOL_VERSION
              + " to "
              + ProtocolVersion.CURRENT
              + " ("
              + versions.describe()
              + ")",
          true);
    }
    AgentVersionRange.Verdict version = versions.check(hello.getAgentVersion());
    if (version != AgentVersionRange.Verdict.ACCEPTED) {
      return reject(
          base,
          version == AgentVersionRange.Verdict.TOO_NEW
              ? RejectReason.AGENT_VERSION_TOO_NEW
              : RejectReason.AGENT_VERSION_TOO_OLD,
          Status.Code.FAILED_PRECONDITION,
          0,
          "Agent version is not supported: " + versions.describe(),
          true);
    }
    IpMode ipMode = IpMode.fromProto(hello.getIpMode());
    UserIdMode userIdMode = UserIdMode.fromProto(hello.getUserIdMode());
    try {
      // 5 registration, within the token's scope (ADR-0010 C-155)
      String env = hello.getEnv().isBlank() ? SessionSettings.DEFAULT_ENV : hello.getEnv();
      Optional<AppRef> found = store.findApp(hello.getAppName(), env);
      AppRef app;
      if (found.isPresent()) {
        app = found.get();
      } else if (autoRegisters(token.appId(), env)) {
        Optional<AppRef> created =
            store.createVariantApp(hello.getAppName(), env, token.appId(), MAX_APP_VARIANTS);
        if (created.isEmpty()) {
          queue(hello, env, token.appId());
          return variantLimit(base, token.appId());
        }
        app = created.get();
        if (app.registeredFromAppId() == token.appId()) {
          LOG.info(
              "App {} is registered automatically as an environment variant of app {} (12 §4.4,"
                  + " ADR-0010 C-155)",
              app.id(),
              token.appId());
        }
      } else {
        queue(hello, env, token.appId());
        return reject(
            base,
            RejectReason.APP_NOT_REGISTERED,
            Status.Code.OK,
            APP_NOT_REGISTERED_RETRY_AFTER_S,
            "The app is not registered and automatic registration is off for its environment;"
                + " an administrator can approve it",
            true);
      }
      if (!app.usableWith(token.appId())) {
        // C-155 (2): an app of this name that neither is the token's app nor was registered from
        // it (created by an administrator, or another app's variant) is not this token's.
        return reject(
            base,
            RejectReason.APP_MISMATCH,
            Status.Code.PERMISSION_DENIED,
            0,
            "The agent token belongs to another app",
            true);
      }
      countUnknownModes(hello);
      InstanceMetadata metadata = InstanceMetadata.of(hello, ipMode, userIdMode);
      String name = instanceName(hello);
      AgentSession session;
      synchronized (appLocks[Math.floorMod(app.id(), LOCKS)]) {
        // 6 limits
        OptionalInt existing = store.findInstance(app.id(), name);
        Optional<AgentSession> live =
            existing.isPresent() ? registry.find(existing.getAsInt()) : Optional.empty();
        boolean replacesOwn = live.isPresent() && live.get().tokenId().equals(token.tokenId());
        int tokenSessions = registry.countByToken(token.tokenId()) - (replacesOwn ? 1 : 0);
        if (live.isEmpty() && registry.size() >= MAX_SESSIONS) {
          return limited(
              base,
              RejectReason.RATE_LIMITED,
              "server_sessions",
              "The collector holds its maximum of " + MAX_SESSIONS + " agent sessions");
        }
        if (tokenSessions >= settings.maxSessionsPerToken()) {
          return limited(
              base,
              RejectReason.RATE_LIMITED,
              "session_limit",
              "At most " + settings.maxSessionsPerToken() + " concurrent sessions per agent token");
        }
        if (newSessions.atLimit(token.tokenId(), now)) {
          return limited(
              base,
              RejectReason.RATE_LIMITED,
              "session_rate",
              "At most "
                  + settings.newSessionsPerMinute()
                  + " new sessions per minute per agent token");
        }
        int instanceId;
        if (existing.isEmpty()) {
          if (store.countInstances(app.id()) >= settings.instancesPerApp()) {
            return limited(
                base,
                RejectReason.INSTANCE_LIMIT,
                "instance_limit",
                "At most " + settings.instancesPerApp() + " instances per app");
          }
          Instant hourAgo = Instant.ofEpochMilli(now).minus(Duration.ofHours(1));
          if (store.countInstancesCreatedSince(app.id(), hourAgo)
              >= settings.newInstancesPerHour()) {
            return limited(
                base,
                RejectReason.INSTANCE_LIMIT,
                "instance_limit",
                "At most " + settings.newInstancesPerHour() + " new instances per app and hour");
          }
          instanceId = store.createInstance(app.id(), name, metadata);
        } else {
          instanceId = existing.getAsInt();
          updateMetadata(instanceId, metadata);
        }
        session =
            new AgentSession(
                UUID.randomUUID(),
                token.tokenId(),
                auth.tokenPrefix(),
                app,
                instanceId,
                name,
                hello.getAgentEpoch(),
                ipMode,
                userIdMode,
                hello.getAgentVersion(),
                hello.getAppVersion(),
                hello.getVirtualThreads(),
                hello.getConfigHash().isEmpty() ? null : hello.getConfigHash(),
                hello.getRemoteConfigAllowList(),
                hello.getSqlParamMode(),
                now,
                new ClockSync(now, hello.getSentAtMs()),
                stream);
        // 7 replace
        registry.register(session);
        newSessions.record(token.tokenId(), now);
      }
      touchToken(token.tokenId());
      ServerHello accepted =
          base.setAccepted(true)
              .putAllConfig(
                  helloConfig(app.id(), session.instanceId(), hello.getRemoteConfigAllowList()))
              .setAppId(app.id())
              .setInstanceId(session.instanceId())
              .setSessionId(session.sessionId().toString())
              .setHeartbeatIntervalS((int) Math.max(1, settings.heartbeatInterval().toSeconds()))
              .setActiveSnapshotIntervalS(ACTIVE_SNAPSHOT_INTERVAL_S)
              .setStatWindowS(STAT_WINDOW_S)
              .setLimits(settings.limits())
              .setUserIdSalt(userIdSalt(app.id()))
              .build();
      return HelloOutcome.accepted(accepted, session);
    } catch (RuntimeException e) {
      LOG.warn(
          "A session start needs PostgreSQL (a new app or instance, or no registration cache"
              + " entry) and the database failed; answered UNAVAILABLE (12 §3.9)",
          e);
      metrics.rejected("store_unavailable");
      return reject(
          base,
          RejectReason.REJECT_REASON_UNSPECIFIED,
          Status.Code.UNAVAILABLE,
          STORE_UNAVAILABLE_RETRY_AFTER_S,
          "The collector cannot reach its database; retry later",
          false);
    }
  }

  /**
   * Whether an unknown (name, env) of a token of {@code homeAppId} is registered automatically: the
   * home app's own policy when it has one, else {@code argos.agent.auto-register.<env>} (ADR-0010
   * C-231 (7), 12 §4.4).
   */
  private boolean autoRegisters(int homeAppId, String env) {
    Boolean own = policies.autoRegister(homeAppId);
    return own != null ? own : settings.autoRegisters(env);
  }

  /**
   * The salt of {@code appId} for {@code ServerHello.user_id_salt}; empty (the agent then records
   * no hashed user id, C-125 (1)) when it cannot be read, logged at most once a minute without
   * values.
   */
  private ByteString userIdSalt(int appId) {
    SaltSource source = salts;
    if (!saltsWired) {
      // Not wired (collector-only contexts of the tests): nothing to warn about.
      return ByteString.EMPTY;
    }
    try {
      Optional<byte[]> salt = source.salt(appId);
      if (salt.isPresent()) {
        byte[] bytes = salt.get();
        ByteString value = ByteString.copyFrom(bytes);
        Arrays.fill(bytes, (byte) 0);
        return value;
      }
    } catch (RuntimeException e) {
      LOG.debug("user id salt not read", e);
    }
    if (rateLimited(lastSaltWarn)) {
      LOG.warn(
          "The user id salt of app {} cannot be read now; its agents record no hashed user ids"
              + " until a later session start sends it (16 §6.3)",
          appId);
    }
    return ByteString.EMPTY;
  }

  /**
   * Queues the refused (name, env) of {@code hello} for approval (12 §4.4, F-S30, ADR-0010 C-155
   * (3)). A failure does not change the answer ({@code APP_NOT_REGISTERED}, 300 s: the agent
   * retries and is queued then), logged at most once a minute.
   */
  private void queue(AgentHello hello, String env, int homeAppId) {
    try {
      store.recordPendingRegistration(
          hello.getAppName(), env, hello.getHost(), homeAppId, clock.instant());
    } catch (RuntimeException e) {
      long now = System.nanoTime();
      long last = lastPendingWarn.get();
      if ((last == Long.MIN_VALUE || now - last >= METADATA_WARN_INTERVAL_NANOS)
          && lastPendingWarn.compareAndSet(last, now)) {
        LOG.warn(
            "A refused registration of app {} was not queued for approval; the agent's next retry"
                + " queues it (12 §4.4); further failures are not logged for a minute: {}",
            homeAppId,
            e.toString());
      }
    }
  }

  /**
   * The stored instance name of {@code hello}: {@code instance_name}, or {@code host:pid} when it
   * is empty (12 §4.3), in its stored form of at most {@value SessionStore#MAX_INSTANCE_NAME_CHARS}
   * characters ({@link SessionStore#storedInstanceName}, ADR-0010 C-157 (4)).
   */
  static String instanceName(AgentHello hello) {
    String name = hello.getInstanceName().strip();
    if (name.isEmpty()) {
      String host = hello.getHost().isBlank() ? "unknown" : hello.getHost().strip();
      name = host + ":" + Integer.toUnsignedString(hello.getPid());
    }
    return SessionStore.storedInstanceName(name);
  }

  /**
   * Whether the stored instance name of {@code hello} is its temporary {@code host:pid} name (12
   * §4.3: the name before the connector port is known), which an {@code InstanceUpdate} may merge
   * into an existing instance of the new name ({@code SessionHandler}, ADR-0010 C-158 (2)).
   */
  static boolean temporaryName(AgentHello hello) {
    String host = hello.getHost().isBlank() ? "unknown" : hello.getHost().strip();
    return instanceName(hello)
        .equals(
            SessionStore.storedInstanceName(host + ":" + Integer.toUnsignedString(hello.getPid())));
  }

  /**
   * C-155 (3): the home app has {@value #MAX_APP_VARIANTS} environment variants already. Answered
   * like an app that is not registered and queued for approval (an administrator may approve it),
   * counted as {@code app_variant_limit}, with a WARN at most once a minute.
   */
  private HelloOutcome variantLimit(ServerHello.Builder base, int homeAppId) {
    metrics.rejected("app_variant_limit");
    long now = System.nanoTime();
    long last = lastVariantWarn.get();
    if ((last == Long.MIN_VALUE || now - last >= METADATA_WARN_INTERVAL_NANOS)
        && lastVariantWarn.compareAndSet(last, now)) {
      LOG.warn(
          "App {} has its maximum of {} automatically registered environment variants; a Hello for"
              + " another environment was answered APP_NOT_REGISTERED (ADR-0010 C-155); further"
              + " refusals are counted in argos_collector_rejected_total reason app_variant_limit"
              + " without logging for a minute",
          homeAppId,
          MAX_APP_VARIANTS);
    }
    return reject(
        base,
        RejectReason.APP_NOT_REGISTERED,
        Status.Code.OK,
        APP_NOT_REGISTERED_RETRY_AFTER_S,
        "The agent token's app has its maximum of "
            + MAX_APP_VARIANTS
            + " automatically registered environments; an administrator can approve this one",
        false);
  }

  private void countUnknownModes(AgentHello hello) {
    if (!IpMode.isKnown(hello.getIpMode())) {
      metrics.unknownEnum(UnknownEnumSink.IP_MODE, 1);
    }
    if (!UserIdMode.isKnown(hello.getUserIdMode())) {
      metrics.unknownEnum(UnknownEnumSink.USER_ID_MODE, 1);
    }
    if (InstanceMetadata.queryStringMode(hello.getQueryStringMode()) == null) {
      metrics.unknownEnum(UnknownEnumSink.QUERY_STRING_MODE, 1);
    }
    if (InstanceMetadata.sqlParamMode(hello.getSqlParamMode()) == null) {
      metrics.unknownEnum(UnknownEnumSink.SQL_PARAM_MODE, 1);
    }
  }

  /** True at most once a minute per {@code last} (log rate limit). */
  private static boolean rateLimited(AtomicLong last) {
    long now = System.nanoTime();
    long previous = last.get();
    return (previous == Long.MIN_VALUE || now - previous >= METADATA_WARN_INTERVAL_NANOS)
        && last.compareAndSet(previous, now);
  }

  /**
   * Writes the Hello metadata of an existing instance. A failure (PostgreSQL down while the app and
   * the instance come from the registration cache, 12 §3.9) does not refuse the session: the row
   * keeps its previous metadata, logged at most once a minute.
   */
  private void updateMetadata(int instanceId, InstanceMetadata metadata) {
    try {
      store.updateInstanceMetadata(instanceId, metadata);
    } catch (RuntimeException e) {
      long now = System.nanoTime();
      long last = lastMetadataWarn.get();
      if ((last == Long.MIN_VALUE || now - last >= METADATA_WARN_INTERVAL_NANOS)
          && lastMetadataWarn.compareAndSet(last, now)) {
        LOG.warn(
            "The Hello metadata of instance {} was not stored; the session is accepted with the"
                + " previous metadata (12 §3.9); further failures are not logged for a minute: {}",
            instanceId,
            e.toString());
      }
    }
  }

  /**
   * Connects the source of {@code ServerHello.config} (WO-26 {@code ConfigChangeService}, wired at
   * start-up by {@code AdminSessionWiring}); without one the config stays empty.
   */
  public void helloConfig(HelloConfigSource source) {
    this.configSource = Objects.requireNonNull(source, "source");
  }

  /**
   * The {@code ServerHello.config} of an accepted session: the source's values, empty when it fails
   * (the session is accepted anyway; the values arrive with the next change or Hello), the failure
   * logged at most once a minute.
   */
  private Map<String, String> helloConfig(int appId, int instanceId, List<String> allow) {
    try {
      Map<String, String> config = configSource.configFor(appId, instanceId, allow);
      return config == null ? Map.of() : config;
    } catch (RuntimeException e) {
      long now = System.nanoTime();
      long last = lastConfigWarn.get();
      if ((last == Long.MIN_VALUE || now - last >= METADATA_WARN_INTERVAL_NANOS)
          && lastConfigWarn.compareAndSet(last, now)) {
        LOG.warn(
            "The pushed configuration of instance {} could not be read; its ServerHello.config is"
                + " empty (12 §6.2); further failures are not logged for a minute: {}",
            instanceId,
            e.getClass().getSimpleName());
      }
      return Map.of();
    }
  }

  private void touchToken(UUID tokenId) {
    try {
      store.touchToken(tokenId, clock.instant());
    } catch (RuntimeException e) {
      LOG.debug("agent_tokens.last_used_at not written", e);
    }
  }

  private ServerHello.Builder base(long now) {
    return ServerHello.newBuilder()
        .setServerVersion(versions.serverVersion())
        .setProtocolVersion(ProtocolVersion.CURRENT)
        .setMinAgentVersion(versions.min())
        .setMaxAgentVersion(versions.max())
        .setServerTimeMs(now);
  }

  /**
   * A check 6 refusal: {@code RESOURCE_EXHAUSTED} with {@value #LIMIT_RETRY_AFTER_S} s, counted in
   * {@code argos_collector_rejected_total} under {@code metricReason}, which names the limit
   * ({@code server_sessions}, {@code session_limit}, {@code session_rate} (16 §2.5) or {@code
   * instance_limit}) where {@code reason} only says RATE_LIMITED or INSTANCE_LIMIT.
   */
  private HelloOutcome limited(
      ServerHello.Builder base, RejectReason reason, String metricReason, String message) {
    HelloOutcome outcome =
        reject(base, reason, Status.Code.RESOURCE_EXHAUSTED, LIMIT_RETRY_AFTER_S, message, false);
    metrics.rejected(metricReason);
    return outcome;
  }

  /**
   * A rejection: the ServerHello, the status and the trailers.
   *
   * @param count whether to count it in {@code argos_collector_rejected_total} (token rejections
   *     and limit violations are counted where they are detected)
   */
  private HelloOutcome reject(
      ServerHello.Builder base,
      RejectReason reason,
      Status.Code code,
      int retryAfterS,
      String message,
      boolean count) {
    if (count) {
      metrics.rejected(reason.name().toLowerCase(Locale.ROOT));
    }
    ServerHello hello =
        base.setAccepted(false)
            .setRejectReason(reason)
            .setRejectMessage(message)
            .setRetryAfterS(retryAfterS)
            .build();
    String trailerReason = reason == RejectReason.REJECT_REASON_UNSPECIFIED ? null : reason.name();
    long retryMs = code == Status.Code.OK ? 0 : retryAfterS * 1000L;
    return HelloOutcome.rejected(
        hello,
        Status.fromCode(code).withDescription(message),
        code == Status.Code.OK
            ? new io.grpc.Metadata()
            : CollectorCalls.trailers(trailerReason, retryMs));
  }
}
