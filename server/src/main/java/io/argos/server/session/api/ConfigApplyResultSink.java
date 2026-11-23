/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import io.argos.proto.v1.ConfigApplyResult;

/**
 * Receives the {@code ConfigApplyResult} messages agents send after a {@code ConfigPush} (11 §8.1,
 * 12 §6.2 {@code apply()}; WO-26 {@code ConfigChangeService}). The session handler calls it on the
 * stream's thread with the instance of the session the message arrived on.
 *
 * <p>Implementations return quickly and never throw (the handler catches and counts anything
 * thrown, and the stream continues).
 */
public interface ConfigApplyResultSink {

  /**
   * One {@code ConfigApplyResult}.
   *
   * @param instanceId the instance of the session the message arrived on
   * @param result the message as received
   */
  void onResult(int instanceId, ConfigApplyResult result);
}
