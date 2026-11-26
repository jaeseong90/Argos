/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.SqlParamMode;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One accepted agent session (12 §4.1, minimal per ADR-0010 C-128): the values fixed by the {@code
 * ServerHello}, the few that {@code InstanceUpdate} changes (C-146), the clock offset ({@link
 * ClockSync}), the latest active-transaction snapshot (read by WO-23's {@code /active}) and the
 * session stream. No instance state (UP/STALE/DOWN/WARN, WO-22).
 */
public final class AgentSession {

  /**
   * {@link #storedStartMs}: the stored process start of the instance could not be read (the new
   * process rule of {@code InstanceStateTracker} then cannot tell a restarted process apart).
   */
  public static final long STARTED_UNKNOWN = -1;

  /** {@link #storedStartMs}: the instance row had no {@code started_at} before this Hello. */
  public static final long STARTED_NONE = 0;

  /** {@link #storedStartMs}: this Hello created the instance row (a new instance). */
  public static final long STARTED_NEW_INSTANCE = -2;

  private final UUID sessionId;
  private final UUID tokenId;
  private final String tokenPrefix;
  private final AppRef app;
  private final int instanceId;
  private final long agentEpoch;
  private final IpMode ipMode;
  private final UserIdMode userIdMode;
  private final String agentVersion;
  private final List<String> remoteConfigAllow;
  private final SqlParamMode sqlParamMode;
  private final long acceptedAtMs;
  private final ClockSync clock;
  private final SessionStream stream;
  private final AtomicReference<ActiveSnapshot> latestSnapshot = new AtomicReference<>();
  private final AtomicBoolean bindValuesWarned = new AtomicBoolean();
  private volatile String instanceName;
  private volatile String appVersion;
  private volatile Boolean virtualThreads;
  private volatile String configHash;
  private volatile long lastHeartbeatMs;
  private volatile long lastActivityNanos;
  private volatile long processStartMs;
  private volatile long storedStartMs = STARTED_UNKNOWN;

  /**
   * A session in {@code SQL_PARAM_MASKED} mode (test convenience; the Hello path passes the mode).
   *
   * @param sessionId {@code ServerHello.session_id}
   * @param tokenId the token that opened the session ({@code SessionRegistry.closeByToken})
   * @param tokenPrefix the token's display prefix, for logs
   * @param app the session's app
   * @param instanceId {@code instances.id}
   * @param instanceName the stored instance name
   * @param agentEpoch {@code AgentHello.agent_epoch}
   * @param ipMode the {@code remote_ip} mode after the C-143 (1) mapping
   * @param userIdMode the {@code user_id} mode after the C-143 (1) mapping
   * @param agentVersion {@code AgentHello.agent_version}
   * @param appVersion {@code AgentHello.app_version} ({@code ""} when unknown)
   * @param virtualThreads {@code AgentHello.virtual_threads}
   * @param configHash {@code AgentHello.config_hash}
   * @param remoteConfigAllow {@code AgentHello.remote_config_allow}
   * @param acceptedAtMs server time of the ServerHello
   * @param clock the session's clock offset
   * @param stream the session stream
   */
  public AgentSession(
      UUID sessionId,
      UUID tokenId,
      String tokenPrefix,
      AppRef app,
      int instanceId,
      String instanceName,
      long agentEpoch,
      IpMode ipMode,
      UserIdMode userIdMode,
      String agentVersion,
      String appVersion,
      Boolean virtualThreads,
      String configHash,
      List<String> remoteConfigAllow,
      long acceptedAtMs,
      ClockSync clock,
      SessionStream stream) {
    this(
        sessionId,
        tokenId,
        tokenPrefix,
        app,
        instanceId,
        instanceName,
        agentEpoch,
        ipMode,
        userIdMode,
        agentVersion,
        appVersion,
        virtualThreads,
        configHash,
        remoteConfigAllow,
        SqlParamMode.SQL_PARAM_MASKED,
        acceptedAtMs,
        clock,
        stream);
  }

  /**
   * A session with the SQL parameter mode of its Hello.
   *
   * @param sqlParamMode {@code AgentHello.sql_param_mode}: in {@code SQL_PARAM_FULL} the server
   *     clears the bind values of the session's SQL steps at receipt (ADR-0010 C-148)
   */
  public AgentSession(
      UUID sessionId,
      UUID tokenId,
      String tokenPrefix,
      AppRef app,
      int instanceId,
      String instanceName,
      long agentEpoch,
      IpMode ipMode,
      UserIdMode userIdMode,
      String agentVersion,
      String appVersion,
      Boolean virtualThreads,
      String configHash,
      List<String> remoteConfigAllow,
      SqlParamMode sqlParamMode,
      long acceptedAtMs,
      ClockSync clock,
      SessionStream stream) {
    this.sqlParamMode = Objects.requireNonNull(sqlParamMode, "sqlParamMode");
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
    this.tokenPrefix = Objects.requireNonNull(tokenPrefix, "tokenPrefix");
    this.app = Objects.requireNonNull(app, "app");
    this.instanceId = instanceId;
    this.instanceName = Objects.requireNonNull(instanceName, "instanceName");
    this.agentEpoch = agentEpoch;
    this.ipMode = Objects.requireNonNull(ipMode, "ipMode");
    this.userIdMode = Objects.requireNonNull(userIdMode, "userIdMode");
    this.agentVersion = Objects.requireNonNull(agentVersion, "agentVersion");
    this.appVersion = Objects.requireNonNull(appVersion, "appVersion");
    this.virtualThreads = virtualThreads;
    this.configHash = configHash;
    this.remoteConfigAllow = List.copyOf(remoteConfigAllow);
    this.acceptedAtMs = acceptedAtMs;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.stream = Objects.requireNonNull(stream, "stream");
    this.lastHeartbeatMs = acceptedAtMs;
  }

  /**
   * The session's decode context for one batch (12 §2 {@code SessionContext}); the receipt values
   * are filled by the caller with {@link SessionContext#atReceipt}.
   */
  public SessionContext context() {
    return new SessionContext(
        app.id(),
        instanceId,
        app.env(),
        sessionId,
        agentEpoch,
        ipMode,
        userIdMode,
        0,
        0,
        appVersion,
        0);
  }

  /** {@code ServerHello.session_id}. */
  public UUID sessionId() {
    return sessionId;
  }

  /** The token that opened the session. */
  public UUID tokenId() {
    return tokenId;
  }

  /** The token's display prefix (never the token). */
  public String tokenPrefix() {
    return tokenPrefix;
  }

  /** The session's app. */
  public AppRef app() {
    return app;
  }

  /** {@code apps.id}. */
  public int appId() {
    return app.id();
  }

  /** {@code instances.id}. */
  public int instanceId() {
    return instanceId;
  }

  /** The stored instance name (changed by an {@code InstanceUpdate} rename). */
  public String instanceName() {
    return instanceName;
  }

  /** {@code AgentHello.agent_epoch}. */
  public long agentEpoch() {
    return agentEpoch;
  }

  /** The {@code remote_ip} mode. */
  public IpMode ipMode() {
    return ipMode;
  }

  /** The {@code user_id} mode. */
  public UserIdMode userIdMode() {
    return userIdMode;
  }

  /** {@code AgentHello.agent_version}. */
  public String agentVersion() {
    return agentVersion;
  }

  /** The current {@code app_version} ({@code ""} when unknown). */
  public String appVersion() {
    return appVersion;
  }

  /** The current {@code virtual_threads}. */
  public Boolean virtualThreads() {
    return virtualThreads;
  }

  /** The current {@code config_hash}. */
  public String configHash() {
    return configHash;
  }

  /** {@code AgentHello.remote_config_allow} (SEC-23). */
  public List<String> remoteConfigAllow() {
    return remoteConfigAllow;
  }

  /** {@code AgentHello.sql_param_mode}. */
  public SqlParamMode sqlParamMode() {
    return sqlParamMode;
  }

  /**
   * Whether the session may send unmasked bind parameter values, which the server does not store
   * before envelope encryption exists (ADR-0010 C-148). Only {@code SQL_PARAM_MASKED} and {@code
   * SQL_PARAM_PARTIAL} are trusted; {@code SQL_PARAM_FULL}, an unspecified mode and an unknown
   * number all fail closed (the C-143 (2) rule for session modes).
   */
  public boolean sendsFullBindValues() {
    return sqlParamMode != SqlParamMode.SQL_PARAM_MASKED
        && sqlParamMode != SqlParamMode.SQL_PARAM_PARTIAL;
  }

  /** True the first time it is called for this session (one WARN per session, C-148). */
  public boolean firstBindValueWarning() {
    return bindValuesWarned.compareAndSet(false, true);
  }

  /** Server time of the ServerHello. */
  public long acceptedAtMs() {
    return acceptedAtMs;
  }

  /**
   * The start facts of the Hello (F-A26, S-17, ADR-0010 C-240 (1)): set by {@code HelloService}
   * before the session is registered, read by {@code InstanceStateTracker} to tell a new agent
   * process (an events row {@code type = 'start'}) from a reconnect of the same one.
   *
   * @param processStartMs {@code AgentHello.process_start_ms} (0 when not reported)
   * @param storedStartMs the instance's {@code started_at} in ms before this Hello wrote it, or
   *     {@link #STARTED_NONE}, {@link #STARTED_NEW_INSTANCE}, {@link #STARTED_UNKNOWN}
   */
  public void startFacts(long processStartMs, long storedStartMs) {
    this.processStartMs = processStartMs;
    this.storedStartMs = storedStartMs;
  }

  /** {@code AgentHello.process_start_ms} (0 when not reported or not set). */
  public long processStartMs() {
    return processStartMs;
  }

  /**
   * The instance's stored process start before this Hello ({@link #startFacts}); {@link
   * #STARTED_UNKNOWN} unless set.
   */
  public long storedStartMs() {
    return storedStartMs;
  }

  /** Server time of the last heartbeat (the ServerHello before the first). */
  public long lastHeartbeatMs() {
    return lastHeartbeatMs;
  }

  /** The clock offset. */
  public ClockSync clock() {
    return clock;
  }

  /** The session stream. */
  public SessionStream stream() {
    return stream;
  }

  /** The latest active-transaction snapshot, or {@code null} before the first. */
  public ActiveSnapshot latestSnapshot() {
    return latestSnapshot.get();
  }

  /** Replaces the latest snapshot (12 §3.2: not written to the WAL). */
  public void replaceSnapshot(ActiveSnapshot snapshot) {
    latestSnapshot.set(snapshot);
  }

  /**
   * Records an agent message of this session (a session stream message or a data call) at the
   * monotonic time {@code nanos} (the idle timeout of ADR-0002 C-19, {@code SessionHandler}).
   */
  public void markActive(long nanos) {
    this.lastActivityNanos = nanos;
  }

  /** The monotonic time of the last agent message ({@link #markActive}), 0 before the first. */
  public long lastActivityNanos() {
    return lastActivityNanos;
  }

  /** Records a heartbeat received at {@code receivedAtMs}. */
  void heartbeat(long receivedAtMs) {
    this.lastHeartbeatMs = receivedAtMs;
  }

  /**
   * The {@code InstanceUpdate} values that differ from this session (C-146); {@code null} arguments
   * (absent fields) never change anything.
   */
  public InstanceChanges diff(
      String newName, Boolean newVirtualThreads, String newAppVersion, String newConfigHash) {
    return new InstanceChanges(
        changed(newName, instanceName),
        changed(newVirtualThreads, virtualThreads),
        changed(newAppVersion, appVersion),
        changed(newConfigHash, configHash));
  }

  private static <T> T changed(T proposed, T current) {
    return (proposed != null && !proposed.equals(current)) ? proposed : null;
  }

  /** Applies changes that were stored. */
  public void apply(InstanceChanges changes) {
    if (changes.instanceName() != null) {
      instanceName = changes.instanceName();
    }
    if (changes.virtualThreads() != null) {
      virtualThreads = changes.virtualThreads();
    }
    if (changes.appVersion() != null) {
      appVersion = changes.appVersion();
    }
    if (changes.configHash() != null) {
      configHash = changes.configHash();
    }
  }

  /** App, instance and session ids only (no token). */
  @Override
  public String toString() {
    return "AgentSession[app="
        + app.id()
        + " instance="
        + instanceId
        + " session="
        + sessionId
        + "]";
  }

  /**
   * The latest active-transaction snapshot of a session (12 §3.2).
   *
   * @param receivedAtMs server receive time
   * @param snapshot the snapshot as received
   */
  public record ActiveSnapshot(long receivedAtMs, ActiveTransactionSnapshot snapshot) {

    /** Validates the snapshot. */
    public ActiveSnapshot {
      Objects.requireNonNull(snapshot, "snapshot");
    }
  }
}
