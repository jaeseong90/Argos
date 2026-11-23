/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.SessionClose;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * The live agent sessions (12 §2 {@code SessionRegistry}, §4.1), in memory, keyed by instance (one
 * session per instance, 11 §5 check 7).
 *
 * <p>Differences from the 12 §2 signature: {@link #register} takes the built session (the Hello
 * checks run before it, {@code HelloService}); {@link #closeByToken} takes the {@code UUID} of
 * {@code agent_tokens.id} (V001); {@link #onHeartbeat} takes only the receive time (the clock
 * arithmetic lives in the session's {@link ClockSync}); {@link #unregister} and {@link #closeAll}
 * serve the end of a stream and the server shutdown (12 §3.9). WO-22 adds the instance state
 * machine behind the same registry.
 */
public interface SessionRegistry {

  /** The live session of {@code instanceId}. */
  Optional<AgentSession> find(int instanceId);

  /**
   * Registers {@code session} as the instance's session. An open session of the same instance gets
   * {@code SessionClose{REPLACED}} and its stream completes (11 §5 check 7; the contract vectors:
   * whatever its agent epoch).
   *
   * @return the replaced session, if any
   */
  Optional<AgentSession> register(AgentSession session);

  /**
   * Removes {@code session} if it is still the instance's session (its stream was cancelled: gRPC
   * keepalive or the agent went away, ADR-0010 C-128).
   */
  void unregister(AgentSession session);

  /**
   * Closes every session of {@code tokenId} with {@code SessionClose{TOKEN_REVOKED_CLOSE}} and
   * {@code UNAUTHENTICATED} (12 §4.1, SEC-04): the hook WO-26 calls after a revocation, once the
   * token cache entry is invalidated.
   *
   * @return the number of sessions closed
   */
  int closeByToken(UUID tokenId);

  /**
   * Closes every session with {@code close} and ends its stream with {@code status} and {@code
   * trailers} (12 §3.9 {@code SERVER_SHUTDOWN}).
   *
   * @return the number of sessions closed
   */
  int closeAll(SessionClose close, Status status, Metadata trailers);

  /** The live sessions of {@code appId}. */
  Collection<AgentSession> byApp(int appId);

  /** Every live session (a snapshot; WO-22's state monitor). */
  Collection<AgentSession> all();

  /** Live sessions opened with {@code tokenId} (11 §5 check 6: at most 100). */
  int countByToken(UUID tokenId);

  /** Records a heartbeat of {@code instanceId} received at {@code receivedAtMs}. */
  void onHeartbeat(int instanceId, long receivedAtMs);

  /** Live sessions. */
  int size();
}
