/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.session.api.ClockOffsetProvider;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@link ClockOffsetProvider} over the live sessions: the {@link ClockSync} estimate of the
 * instance's session (the Hello estimate before the first heartbeat round). The C-129 correction
 * itself is the interface's default method.
 */
public final class SessionClockOffsets implements ClockOffsetProvider {

  private final SessionRegistry registry;

  public SessionClockOffsets(SessionRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  @Override
  public OptionalLong medianOffsetMs(int instanceId) {
    return registry
        .find(instanceId)
        .map(s -> OptionalLong.of(s.clock().estimateMs()))
        .orElse(OptionalLong.empty());
  }
}
