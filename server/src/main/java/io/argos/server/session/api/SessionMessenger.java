/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import io.argos.proto.v1.Command;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.SessionClose;

/**
 * Server-to-agent messages of the ingest path on the session stream (11 §4.2, §7). Implemented by
 * lane {@code collector} over its in-memory session registry; called by lane {@code durable-ingest}
 * ({@code DictionaryResolver}) and by the collector itself (shutdown, REPLACED).
 *
 * <p>Sends are non-blocking for the caller: a message is handed to the session's stream observer
 * (sends on one stream are serialized, 12 §4.1) and the method returns. Messages never carry a
 * token (11 §11).
 *
 * <p>WO-26 adds {@link #sendCommand} and {@link #pushConfig}; their defaults send nothing (test
 * doubles that predate them).
 */
public interface SessionMessenger {

  /**
   * Asks an agent for dictionary texts the server does not know (11 §7 (3), at most {@code
   * ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE} refs per request): to the live session of
   * {@code instanceId} when there is one, otherwise to the session of {@code appId} with the most
   * recent heartbeat.
   *
   * @return {@code false} when the app has no live session (the request is not sent)
   */
  boolean requestDictionary(int appId, int instanceId, DictionaryRequest request);

  /**
   * Sends {@code close} to the live session of {@code instanceId} and completes its stream (12 §3.9
   * {@code SERVER_SHUTDOWN}, 11 §5 check 7 {@code REPLACED}, 12 §4.1 {@code TOKEN_REVOKED_CLOSE}).
   *
   * @return {@code false} when the instance has no live session
   */
  boolean close(int instanceId, SessionClose close);

  /**
   * Whether {@code instanceId} has a live session whose stream has not ended (WO-26: a command to
   * an instance without one is refused with {@code 409 instance_offline}).
   */
  default boolean hasLiveSession(int instanceId) {
    return false;
  }

  /**
   * Sends a server command to the live session of {@code instanceId} (11 §8.2, 12 §6.1 step 1). The
   * message is sent only when the session's transport is ready for it (no server-side buffering,
   * like the dictionary request); the caller keeps the command queued and tries again until its
   * TTL.
   *
   * @return {@code false} when the instance has no live session or its transport is not ready
   *     (nothing sent)
   */
  default boolean sendCommand(int instanceId, Command command) {
    return false;
  }

  /**
   * Pushes a configuration change to the live session of {@code instanceId} (11 §8.1, 12 §6.2
   * {@code apply()}), with the same readiness rule as {@link #sendCommand}.
   *
   * @return {@code false} when the instance has no live session or its transport is not ready
   *     (nothing sent)
   */
  default boolean pushConfig(int instanceId, ConfigPush push) {
    return false;
  }
}
