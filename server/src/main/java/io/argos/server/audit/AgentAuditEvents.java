/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * The audit events of agents (16 §11.1 group 토큰; ADR-0010 C-158 (1)), called on gRPC threads of the
 * collector. Implementations never block and never throw ({@link AgentAuditRecorder} over {@link
 * AsyncAuditDispatcher}); {@link #NONE} records nothing (collector harnesses without the audit
 * service).
 */
public interface AgentAuditEvents {

  /** Records nothing. */
  AgentAuditEvents NONE = new AgentAuditEvents() {};

  /**
   * {@code AGENT_AUTH_FAILED}: a missing, malformed, unknown, expired or revoked token.
   *
   * @param ip the client address
   * @param tokenPrefix the token's display prefix ({@code argos_} + 8 characters, {@code
   *     argos_****} for a missing or malformed token); never the token
   * @param reason {@code token_unknown}, {@code token_expired} or {@code token_revoked}
   */
  default void authFailed(String ip, String tokenPrefix, String reason) {}

  /**
   * {@code AGENT_AUTH_BLOCKED}: the address reached the failure threshold and is blocked.
   *
   * @param ip the client address
   * @param blockMinutes how long it stays blocked
   */
  default void authBlocked(String ip, long blockMinutes) {}

  /**
   * {@code AGENT_SESSION_REJECTED}: an authenticated agent's Hello was refused.
   *
   * @param ip the client address
   * @param tokenPrefix the token's display prefix
   * @param tokenAppId the token's application
   * @param appName the Hello's application name (agent input, cut)
   * @param env the Hello's environment (agent input, cut)
   * @param reason the refusal code (the {@code RejectReason} or limit name in lower case)
   */
  default void sessionRejected(
      String ip, String tokenPrefix, int tokenAppId, String appName, String env, String reason) {}
}
