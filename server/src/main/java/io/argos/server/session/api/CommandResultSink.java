/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import io.argos.proto.v1.CommandResult;

/**
 * Receives the {@code CommandResult} chunks agents send on their session stream (11 §8.2, 12 §6.1
 * step 2; WO-26 {@code CommandRouter}). The session handler calls it on the stream's thread with
 * the instance of the session the message arrived on, so a result is attributed to the sending
 * session only (an agent cannot answer for another instance).
 *
 * <p>Implementations return quickly and never throw: a failure is theirs to count and log (the
 * handler catches and counts anything thrown, and the stream continues). Result payloads are
 * collected data (thread dumps, masked environment dumps): never log them.
 */
public interface CommandResultSink {

  /**
   * One {@code CommandResult} message (a chunk of a result, or the whole result).
   *
   * @param instanceId the instance of the session the message arrived on
   * @param result the message as received (the gRPC limits already applied)
   */
  void onResult(int instanceId, CommandResult result);
}
