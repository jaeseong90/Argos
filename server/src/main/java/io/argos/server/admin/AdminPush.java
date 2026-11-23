/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin;

/**
 * Publishes administration results on the WebSocket {@code admin} topic (14 §5.2, §5.3; WO-26):
 * command state transitions and configuration change results. The topic is for administrators only
 * (WO-26 assumption: operators poll {@code GET /commands/{commandId}}). Implemented over {@code
 * RealtimeBus#publishAdmin}; never blocks, never throws.
 */
@FunctionalInterface
public interface AdminPush {

  /** Drops everything (tests, contexts without the realtime bus). */
  AdminPush NONE = (changeId, commandId, tMs, dataJson) -> {};

  /**
   * One push.
   *
   * @param changeId the configuration change, or null
   * @param commandId the command, or null
   * @param tMs server time of the transition
   * @param dataJson the push's {@code data} object (no collected data, no secrets)
   */
  void publish(String changeId, String commandId, long tMs, String dataJson);
}
