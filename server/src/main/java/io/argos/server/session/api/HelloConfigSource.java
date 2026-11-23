/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.util.List;
import java.util.Map;

/**
 * The configuration an accepted session receives in {@code ServerHello.config} (11 §4.2, 12 §6.2
 * {@code apply()}: an instance that was not connected when a change was applied gets the values in
 * its next Hello). WO-26's {@code ConfigChangeService} returns the values the server holds for the
 * application, limited to the keys the Hello's {@code remote_config_allow} names (SEC-23).
 *
 * <p>Called by {@code HelloService} on the stream's thread after the session is registered.
 * Implementations return quickly and never throw; {@code HelloService} answers with an empty map
 * when one does.
 */
@FunctionalInterface
public interface HelloConfigSource {

  /** No configuration (a context without the configuration change service). */
  HelloConfigSource NONE = (appId, instanceId, remoteConfigAllow) -> Map.of();

  /**
   * The {@code ServerHello.config} of a session.
   *
   * @param appId the session's application
   * @param instanceId the session's instance
   * @param remoteConfigAllow the Hello's {@code remote_config_allow}
   * @return dotted keys and the agent's string values
   */
  Map<String, String> configFor(int appId, int instanceId, List<String> remoteConfigAllow);
}
