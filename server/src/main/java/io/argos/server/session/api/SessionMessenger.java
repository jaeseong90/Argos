/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

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
}
