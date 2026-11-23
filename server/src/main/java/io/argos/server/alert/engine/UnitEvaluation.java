/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.engine.StateMachine.Change;
import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.metric.MetricValue;
import io.argos.server.alert.rule.RuleSpec;
import java.util.List;

/**
 * One evaluation of one unit after the time and mute gates (12 §5.4 order): an unavailable metric
 * is skipped; a request metric with fewer requests in the window than {@code min_samples} is not
 * evaluated (state kept, F-S25: low traffic does not fire, S-15); then a missing value goes to the
 * {@code no_data} policy and a value to the {@link StateMachine}.
 */
public final class UnitEvaluation {

  private UnitEvaluation() {}

  /** The changes of one evaluation (empty: nothing changed or not evaluated). */
  public static List<Change> evaluate(
      RuleSpec rule, AlertMetric metric, UnitState s, MetricValue v, long nowMs) {
    if (v.status() == MetricValue.Status.UNAVAILABLE) {
      return List.of();
    }
    if (metric.request() && v.samples() < rule.minSamples()) {
      return List.of();
    }
    return StateMachine.step(rule, s, v.present() ? v.value() : null, nowMs);
  }
}
