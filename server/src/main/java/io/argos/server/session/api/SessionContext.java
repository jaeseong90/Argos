/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The values of an accepted agent session that one received batch is decoded with (12 §2 {@code
 * SessionContext}, extended for ADR-0010 C-129 and C-143).
 *
 * <p>A context describes one data RPC: the collector takes the session's values (fixed at the
 * {@code ServerHello}, {@code appVersion} also by {@code InstanceUpdate}, C-146) and stamps the
 * receipt values with {@link #atReceipt} before the WAL append:
 *
 * <ul>
 *   <li>{@code receivedAtMs}: the server clock at receipt, the reference of the D-15 guard (future
 *       clamp, 7-day rejection, {@code late}) and of {@code argos_ingest_lag_seconds} (N-15);
 *   <li>{@code tsCorrectionMs}: the C-84 correction decided at receipt ({@link
 *       ClockOffsetProvider#tsCorrectionMs}, ADR-0010 C-129 (3));
 *   <li>{@code apdexTMs} and {@code apdexOverrides}: the app's Apdex T and its service overrides at
 *       receipt (D-17 forward only, T-SV-017, WO-27), so that a replay of the record stamps the
 *       same values.
 * </ul>
 *
 * <p>Everything here survives a restart in the WAL (D-13, DSN-12-01): {@code instanceId}, {@code
 * agentEpoch}, {@code receivedAtMs} and {@code tsCorrectionMs} in the record header ({@code
 * instance_id}, {@code epoch}, {@code received_ms}, {@code ts_correction_ms}, 12 §3.4), the other
 * components in {@link io.argos.server.wal.api.WalDataPayload}. Recovery and replay rebuild the
 * identical context and never recompute a value (C-129 (3)): the stored rows of a replayed record
 * equal those of the original path.
 *
 * @param appId {@code apps.id} ({@code ServerHello.app_id}, ClickHouse {@code app_id})
 * @param instanceId {@code instances.id} ({@code ServerHello.instance_id}, metadata {@code
 *     argos-instance-id}); every accepted batch header carries the same value
 * @param env the app's environment, denormalized into every row (DSN-02-14)
 * @param sessionId {@code ServerHello.session_id} (metadata {@code argos-session-id})
 * @param agentEpoch {@code AgentHello.agent_epoch}, part of the deduplication key (C-3); for a
 *     context rebuilt from the WAL, the epoch of the record header
 * @param ipMode the session's {@code remote_ip} mode after the C-143 (1) mapping
 * @param userIdMode the session's {@code user_id} mode after the C-143 (1) mapping
 * @param apdexTMs Apdex T of the app at receipt, milliseconds (D-17; {@code xlog.apdex_t_ms})
 * @param receivedAtMs server receive time, epoch milliseconds
 * @param appVersion the session's current {@code app_version} ({@code AgentHello.app_version}, then
 *     {@code InstanceUpdate.app_version}); {@code ""} when unknown. Source of {@code
 *     app_version_id} when a record sends 0 (ADR-0010 C-143 (6))
 * @param tsCorrectionMs milliseconds subtracted from the agent timestamps of this batch (C-129): 0
 *     or a positive multiple of 10 s
 * @param apdexOverrides the app's service Apdex T overrides at receipt ({@code
 *     service_apdex_overrides}, WO-27); a transaction of an overridden service is stamped with its
 *     override instead of {@code apdexTMs}
 */
public record SessionContext(
    int appId,
    int instanceId,
    String env,
    UUID sessionId,
    long agentEpoch,
    IpMode ipMode,
    UserIdMode userIdMode,
    int apdexTMs,
    long receivedAtMs,
    String appVersion,
    long tsCorrectionMs,
    ApdexOverrides apdexOverrides) {

  /** Validates the components that every consumer relies on. */
  public SessionContext {
    Objects.requireNonNull(apdexOverrides, "apdexOverrides");
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(ipMode, "ipMode");
    Objects.requireNonNull(userIdMode, "userIdMode");
    Objects.requireNonNull(appVersion, "appVersion");
    if (apdexTMs < 0) {
      throw new IllegalArgumentException("apdexTMs < 0: " + apdexTMs);
    }
    if (tsCorrectionMs < 0) {
      throw new IllegalArgumentException("tsCorrectionMs < 0: " + tsCorrectionMs);
    }
  }

  /** A context without service Apdex overrides ({@link ApdexOverrides#NONE}). */
  public SessionContext(
      int appId,
      int instanceId,
      String env,
      UUID sessionId,
      long agentEpoch,
      IpMode ipMode,
      UserIdMode userIdMode,
      int apdexTMs,
      long receivedAtMs,
      String appVersion,
      long tsCorrectionMs) {
    this(
        appId,
        instanceId,
        env,
        sessionId,
        agentEpoch,
        ipMode,
        userIdMode,
        apdexTMs,
        receivedAtMs,
        appVersion,
        tsCorrectionMs,
        ApdexOverrides.NONE);
  }

  /** The Apdex T of a transaction of {@code serviceId}: its override, else {@link #apdexTMs}. */
  public int apdexTMsFor(long serviceId) {
    return apdexOverrides.tFor(serviceId, apdexTMs);
  }

  /**
   * This session's context for one received batch: the receipt values replaced, everything else
   * kept.
   *
   * @param receivedAtMs server receive time of the batch
   * @param tsCorrectionMs the C-129 correction decided now ({@link
   *     ClockOffsetProvider#tsCorrectionMs})
   * @param apdexTMs the app's Apdex T now
   */
  public SessionContext atReceipt(long receivedAtMs, long tsCorrectionMs, int apdexTMs) {
    return atReceipt(receivedAtMs, tsCorrectionMs, apdexTMs, ApdexOverrides.NONE);
  }

  /**
   * This session's context for one received batch with the app's service Apdex overrides now.
   *
   * @param receivedAtMs server receive time of the batch
   * @param tsCorrectionMs the C-129 correction decided now
   * @param apdexTMs the app's Apdex T now
   * @param overrides the app's service Apdex T overrides now
   */
  public SessionContext atReceipt(
      long receivedAtMs, long tsCorrectionMs, int apdexTMs, ApdexOverrides overrides) {
    return new SessionContext(
        appId,
        instanceId,
        env,
        sessionId,
        agentEpoch,
        ipMode,
        userIdMode,
        apdexTMs,
        receivedAtMs,
        appVersion,
        tsCorrectionMs,
        overrides);
  }

  /** This context with another current {@code app_version} (InstanceUpdate, C-146). */
  public SessionContext withAppVersion(String newAppVersion) {
    return new SessionContext(
        appId,
        instanceId,
        env,
        sessionId,
        agentEpoch,
        ipMode,
        userIdMode,
        apdexTMs,
        receivedAtMs,
        newAppVersion,
        tsCorrectionMs,
        apdexOverrides);
  }
}
