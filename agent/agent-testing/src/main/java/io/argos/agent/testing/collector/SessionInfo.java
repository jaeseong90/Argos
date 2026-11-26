/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.Message;
import java.time.Instant;

/**
 * One {@code AgentHello} a {@link FakeCollector} received and how it answered (11 §5).
 *
 * @param number session number (1, 2, ... in arrival order)
 * @param instanceId the instance id the collector assigned (0 when rejected or unanswered)
 * @param accepted whether {@code ServerHello.accepted} was true
 * @param appName {@code AgentHello.app_name}
 * @param env {@code AgentHello.env}
 * @param appVersion {@code AgentHello.app_version}
 * @param agentVersion {@code AgentHello.agent_version}
 * @param openedAt when the hello arrived
 * @param hello the hello message as received
 * @param sessionId {@code ServerHello.session_id} (null unless accepted)
 * @param rejectReason {@code ServerHello.reject_reason} name (null when accepted or unanswered)
 * @param token the {@code authorization} token (masked in {@code toString}; null when absent)
 * @param agentEpoch {@code AgentHello.agent_epoch}
 * @param plaintext the connection had no TLS
 * @param checkOnly {@code AgentHello.check_only} (ADR-0010 C-240 (1), {@code --check}): answered
 *     without a session or an instance (instance id 0, no session id) unless the collector ignores
 *     the field
 */
public record SessionInfo(
    int number,
    long instanceId,
    boolean accepted,
    String appName,
    String env,
    String appVersion,
    String agentVersion,
    Instant openedAt,
    Message hello,
    String sessionId,
    String rejectReason,
    ReceivedToken token,
    long agentEpoch,
    boolean plaintext,
    boolean checkOnly) {

  /** The WO-11 shape (no check-only flag: a normal Hello). */
  public SessionInfo(
      int number,
      long instanceId,
      boolean accepted,
      String appName,
      String env,
      String appVersion,
      String agentVersion,
      Instant openedAt,
      Message hello,
      String sessionId,
      String rejectReason,
      ReceivedToken token,
      long agentEpoch,
      boolean plaintext) {
    this(
        number,
        instanceId,
        accepted,
        appName,
        env,
        appVersion,
        agentVersion,
        openedAt,
        hello,
        sessionId,
        rejectReason,
        token,
        agentEpoch,
        plaintext,
        false);
  }

  /** The WO-05 shape (no session id, token or epoch). */
  public SessionInfo(
      int number,
      long instanceId,
      boolean accepted,
      String appName,
      String env,
      String appVersion,
      String agentVersion,
      Instant openedAt,
      Message hello) {
    this(
        number,
        instanceId,
        accepted,
        appName,
        env,
        appVersion,
        agentVersion,
        openedAt,
        hello,
        null,
        null,
        null,
        0,
        false,
        false);
  }
}
