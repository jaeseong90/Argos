/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.v1.DictionaryEntries;
import java.util.List;

/**
 * What the {@link Sender} needs from the session ({@link SessionClient}; tests use a fake): whether
 * data RPCs may be sent (11 §3: only after {@code ServerHello.accepted}), the ids of the session,
 * the dictionary pre-send and the reactions of 11 §10 that concern the session.
 */
interface SessionLink {
  /** True while a session is established (accepted hello, stream open). */
  boolean established();

  /** {@code ServerHello.instance_id} of the current session (0 before the first). */
  long instanceId();

  /** {@code ServerHello.session_id} of the current session ("" before the first). */
  String sessionId();

  /**
   * Writes the dictionary entries on the session stream before the batch that references them (11
   * §7); false when there is no session to write to (the caller keeps them unsent).
   */
  boolean sendDictionary(List<DictionaryEntries> messages);

  /**
   * A data RPC sent on session {@code sessionId} got {@code FAILED_PRECONDITION(SESSION_REQUIRED)}
   * or {@code PERMISSION_DENIED}: that session is over and a new one is opened. {@link
   * #established()} is false when this returns, so nothing is resent on the old session; further
   * calls for the same session (the other in-flight batches) and calls for an older session do
   * nothing.
   */
  void reestablish(String sessionId);

  /**
   * A data RPC sent on session {@code sessionId} got {@code UNAUTHENTICATED}: the session waits the
   * fixed 5 minutes (C-102). Same rules for repeated or late calls as {@link #reestablish}.
   */
  void unauthenticated(String sessionId);
}
