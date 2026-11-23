/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The XLog points of one application received in one 100 ms window (12 §3.7 "묶음"), in arrival
 * order: what the {@code xlog} sink of {@code (xlog, app_id, env)} emits. Each subscription filters
 * it and builds its own push.
 *
 * @param appId application
 * @param env its environment
 * @param createdAtMs server time of the bundle (the push {@code t}, 14 §5.3)
 * @param points the points
 * @param slowThresholdMs the application's {@code slow_threshold_ms} for the status column
 * @param serviceNames the known names of the services of the points (cleaned for the API); a key
 *     without a known name is missing
 */
public record XlogBundle(
    int appId,
    String env,
    long createdAtMs,
    List<BusPoint> points,
    int slowThresholdMs,
    Map<Long, String> serviceNames) {

  /** Copies. */
  public XlogBundle {
    Objects.requireNonNull(env, "env");
    points = List.copyOf(points);
    serviceNames = Map.copyOf(serviceNames);
  }

  /** This bundle with the threshold and names resolved. */
  public XlogBundle resolved(int slowThreshold, Map<Long, String> names) {
    return new XlogBundle(appId, env, createdAtMs, points, slowThreshold, names);
  }
}
