/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * What one evaluation asks a {@link MetricProvider} for.
 *
 * @param metric the metric
 * @param appId the application, null for a server metric
 * @param kind the transaction kind of request metrics
 * @param targetType what the value is about: {@link TargetType#APP} for the whole application
 * @param targetId the instance, service or host id (decimal), null for the application or server
 * @param window the evaluation window
 * @param nowMs the evaluation time, epoch milliseconds
 * @param excludedInstances instances left out of an application value (in their warm-up grace)
 */
public record MetricRequest(
    AlertMetric metric,
    Integer appId,
    RuleSpec.Kind kind,
    TargetType targetType,
    String targetId,
    Duration window,
    long nowMs,
    Set<Integer> excludedInstances) {

  /** Validates and copies. */
  public MetricRequest {
    Objects.requireNonNull(metric, "metric");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(targetType, "targetType");
    Objects.requireNonNull(window, "window");
    excludedInstances = Set.copyOf(excludedInstances);
  }

  /** The target id as a number (instance ids are ints, service and host keys 64-bit). */
  public long targetIdAsLong() {
    return Long.parseUnsignedLong(targetId);
  }

  /** The window in whole seconds, at least 1. */
  public long windowSeconds() {
    return Math.max(1, window.toSeconds());
  }
}
