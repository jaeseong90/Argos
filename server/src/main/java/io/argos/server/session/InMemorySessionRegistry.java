/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.SessionClose;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory {@link SessionRegistry} (12 §4.1): instance id → session, plus a per-token count.
 * Registration and removal are serialized (Hellos are rare); lookups of data calls read the
 * concurrent map without a lock.
 *
 * <p>Bounded: the number of sessions is limited by the Hello checks (instances per app, sessions
 * per token, {@code HelloService.MAX_SESSIONS}); the per-token map holds only tokens with a live
 * session. A replaced or closed session is never held.
 */
public final class InMemorySessionRegistry implements SessionRegistry {

  private final ConcurrentHashMap<Integer, AgentSession> byInstance = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> perToken = new HashMap<>();
  private final Object lock = new Object();

  @Override
  public Optional<AgentSession> find(int instanceId) {
    return Optional.ofNullable(byInstance.get(instanceId));
  }

  @Override
  public Optional<AgentSession> register(AgentSession session) {
    AgentSession previous;
    synchronized (lock) {
      previous = byInstance.put(session.instanceId(), session);
      if (previous != null) {
        decrement(previous.tokenId());
      }
      perToken.merge(session.tokenId(), 1, Integer::sum);
    }
    if (previous != null) {
      previous.stream()
          .close(
              SessionClose.newBuilder()
                  .setReason(CloseReason.REPLACED)
                  .setMessage("Another session of this instance started")
                  .build(),
              Status.OK,
              new Metadata());
    }
    return Optional.ofNullable(previous);
  }

  @Override
  public void unregister(AgentSession session) {
    synchronized (lock) {
      if (byInstance.remove(session.instanceId(), session)) {
        decrement(session.tokenId());
      }
    }
  }

  @Override
  public int closeByToken(UUID tokenId) {
    List<AgentSession> closed = new ArrayList<>();
    synchronized (lock) {
      for (AgentSession s : byInstance.values()) {
        if (s.tokenId().equals(tokenId) && byInstance.remove(s.instanceId(), s)) {
          decrement(tokenId);
          closed.add(s);
        }
      }
    }
    SessionClose close =
        SessionClose.newBuilder()
            .setReason(CloseReason.TOKEN_REVOKED_CLOSE)
            .setMessage("The agent token was revoked")
            .build();
    for (AgentSession s : closed) {
      s.stream()
          .close(
              close,
              Status.UNAUTHENTICATED.withDescription("The agent token was revoked"),
              new Metadata());
    }
    return closed.size();
  }

  @Override
  public int closeAll(SessionClose close, Status status, Metadata trailers) {
    List<AgentSession> closed;
    synchronized (lock) {
      closed = new ArrayList<>(byInstance.values());
      byInstance.clear();
      perToken.clear();
    }
    for (AgentSession s : closed) {
      s.stream().close(close, status, copy(trailers));
    }
    return closed.size();
  }

  @Override
  public Collection<AgentSession> byApp(int appId) {
    List<AgentSession> sessions = new ArrayList<>();
    for (AgentSession s : byInstance.values()) {
      if (s.appId() == appId) {
        sessions.add(s);
      }
    }
    return sessions;
  }

  @Override
  public Collection<AgentSession> all() {
    return List.copyOf(byInstance.values());
  }

  @Override
  public int countByToken(UUID tokenId) {
    synchronized (lock) {
      return perToken.getOrDefault(tokenId, 0);
    }
  }

  @Override
  public void onHeartbeat(int instanceId, long receivedAtMs) {
    AgentSession s = byInstance.get(instanceId);
    if (s != null) {
      s.heartbeat(receivedAtMs);
    }
  }

  @Override
  public int size() {
    return byInstance.size();
  }

  private void decrement(UUID tokenId) {
    perToken.computeIfPresent(tokenId, (k, n) -> n <= 1 ? null : n - 1);
  }

  private static Metadata copy(Metadata trailers) {
    Metadata copy = new Metadata();
    if (trailers != null) {
      copy.merge(trailers);
    }
    return copy;
  }
}
