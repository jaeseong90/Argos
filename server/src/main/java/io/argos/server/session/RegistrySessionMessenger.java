/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.server.session.api.SessionMessenger;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SessionMessenger} over the live sessions (11 §7 (3), 12 §4.1): a dictionary request goes
 * to the instance's own session, or to the session of the app with the most recent heartbeat; a
 * close goes to the instance's session and ends its stream.
 */
public final class RegistrySessionMessenger implements SessionMessenger {

  private final SessionRegistry registry;

  public RegistrySessionMessenger(SessionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public boolean requestDictionary(int appId, int instanceId, DictionaryRequest request) {
    ServerMessage message = ServerMessage.newBuilder().setDictionaryRequest(request).build();
    Optional<AgentSession> own =
        registry.find(instanceId).filter(s -> s.appId() == appId && !s.stream().ended());
    if (own.isPresent() && own.get().stream().sendIfReady(message)) {
      return true;
    }
    return registry.byApp(appId).stream()
        .filter(s -> !s.stream().ended())
        .sorted(Comparator.comparingLong(AgentSession::lastHeartbeatMs).reversed())
        .anyMatch(s -> s.stream().sendIfReady(message));
  }

  @Override
  public boolean close(int instanceId, SessionClose close) {
    Optional<AgentSession> session = registry.find(instanceId);
    if (session.isEmpty()) {
      return false;
    }
    AgentSession s = session.get();
    registry.unregister(s);
    s.stream().close(close, endStatus(close), new Metadata());
    return true;
  }

  /** The stream status that follows {@code close} (11 §10, the contract vectors). */
  static Status endStatus(SessionClose close) {
    return switch (close.getReason()) {
      case TOKEN_REVOKED_CLOSE -> Status.UNAUTHENTICATED.withDescription(close.getMessage());
      case SERVER_SHUTDOWN -> Status.UNAVAILABLE.withDescription(close.getMessage());
      case PROTOCOL_ERROR -> Status.FAILED_PRECONDITION.withDescription(close.getMessage());
      case REPLACED, IDLE_TIMEOUT, CLOSE_REASON_UNSPECIFIED, UNRECOGNIZED -> Status.OK;
    };
  }
}
