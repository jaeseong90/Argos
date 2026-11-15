/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.core.transport.AgentSession;
import java.util.List;

/**
 * The agent's {@link TomcatRegistry.Publisher}: the WO-11 session API ({@link AgentSession}, 11
 * §4.2 {@code InstanceUpdate}). The connector names go out as {@code connectors}; the instance name
 * becomes {@code <host>:<port>} of the first connector (10 §15 {@code app.instance} "자동({@code
 * host:port} → {@code host:pid})", 11 §4.2 {@code instance_name}) unless the operator configured
 * {@code app.instance}. The host is the one of the session's {@code AgentHello} (no name lookup).
 * {@link AgentSession} sends only changed values and never blocks.
 */
final class SessionPublisher implements TomcatRegistry.Publisher {
  private final boolean nameInstance;

  /** {@code nameInstance}: {@code app.instance} is not configured. */
  SessionPublisher(boolean nameInstance) {
    this.nameInstance = nameInstance;
  }

  /** The running transport's session, null before it started (or without gRPC transport). */
  @Override
  public Object destination() {
    return AgentSession.current();
  }

  @Override
  public void publish(Object destination, List<String> connectorNames, int port) {
    if (!(destination instanceof AgentSession)) {
      return;
    }
    AgentSession session = (AgentSession) destination;
    session.connectors(connectorNames);
    if (nameInstance && port > 0) {
      String host = session.host();
      if (host != null && !host.isEmpty()) {
        session.instanceName(host + ":" + port);
      }
    }
  }
}
