/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.collector.auth.IpAuthFailureLimiter;
import io.argos.server.session.HelloService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Records the agent audit events through {@link AsyncAuditDispatcher} (never blocking the gRPC
 * thread; rate limited per client address for the authentication events and per token prefix for
 * the session events) and hands itself to the collector's {@link IpAuthFailureLimiter} and {@link
 * HelloService} when the context has them.
 *
 * <p>Fields: actor type {@code agent}, actor id and name the token's display prefix (never the
 * token), ip the client address; {@code AGENT_AUTH_FAILED}: target {@code token}/prefix, result
 * {@code failure} with the reason code; {@code AGENT_AUTH_BLOCKED}: target {@code address}/ip,
 * result {@code rejected} {@code auth_blocked}, {@code after.block_minutes}; {@code
 * AGENT_SESSION_REJECTED}: target {@code app}/the token's app id, result {@code rejected} with the
 * refusal code, {@code after.app_name} and {@code after.env} (the Hello's, at most 128 characters).
 */
@Component
public final class AgentAuditRecorder implements AgentAuditEvents {

  /** Characters of an agent-supplied name in a record. */
  static final int MAX_NAME_CHARS = 128;

  private final AsyncAuditDispatcher dispatcher;

  public AgentAuditRecorder(
      AsyncAuditDispatcher dispatcher,
      ObjectProvider<IpAuthFailureLimiter> limiter,
      ObjectProvider<HelloService> hello) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    limiter.ifAvailable(l -> l.auditEvents(this));
    hello.ifAvailable(h -> h.auditEvents(this));
  }

  @Override
  public void authFailed(String ip, String tokenPrefix, String reason) {
    dispatcher.submit(
        AuditEvent.AGENT_AUTH_FAILED,
        ip,
        agent(ip, tokenPrefix),
        new Target("token", tokenPrefix, null),
        AuditResult.failure(reason),
        null);
  }

  @Override
  public void authBlocked(String ip, long blockMinutes) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("block_minutes", blockMinutes);
    dispatcher.submit(
        AuditEvent.AGENT_AUTH_BLOCKED,
        ip,
        agent(ip, null),
        new Target("address", ip, null),
        AuditResult.rejected("auth_blocked"),
        after);
  }

  @Override
  public void sessionRejected(
      String ip, String tokenPrefix, int tokenAppId, String appName, String env, String reason) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put(
        "app_name",
        DefaultAuditService.printable(DefaultAuditService.cut(appName, MAX_NAME_CHARS)));
    after.put("env", DefaultAuditService.printable(DefaultAuditService.cut(env, MAX_NAME_CHARS)));
    dispatcher.submit(
        AuditEvent.AGENT_SESSION_REJECTED,
        tokenPrefix,
        agent(ip, tokenPrefix),
        new Target("app", Integer.toString(tokenAppId), null),
        AuditResult.rejected(reason),
        after);
  }

  private static Actor agent(String ip, String tokenPrefix) {
    return new Actor(Actor.Type.AGENT, tokenPrefix, tokenPrefix, ip, null);
  }
}
