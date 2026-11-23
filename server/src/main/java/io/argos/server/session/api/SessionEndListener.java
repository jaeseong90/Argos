/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

/**
 * Told when a live agent session leaves the session registry, whatever the reason: the agent went
 * away (call cancelled), idle timeout, protocol error, a server close (token revoked, shutdown,
 * instance merge) or a newer session of the same instance replacing it (11 §5 check 7). WO-26 uses
 * it to expire the commands in flight on that instance (12 §6.1 step 3: "세션이 끊기면 QUEUED/SENT 명령은
 * EXPIRED").
 *
 * <p>Called after the session is removed, outside the registry's lock, on the thread that ended the
 * session (a gRPC thread, the session timer or a request thread). Implementations return quickly
 * and never throw (the registry catches anything thrown).
 */
public interface SessionEndListener {

  /**
   * A session of {@code instanceId} ended.
   *
   * @param instanceId the instance
   * @param appId the instance's application
   */
  void onSessionEnd(int instanceId, int appId);
}
