/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.api.AlertNotification.Severity;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.util.Objects;
import java.util.UUID;

/**
 * The mutable state of one rule and evaluation unit (12 §5.4 {@code AlertState}), owned by the
 * alert engine's thread. Times are epoch milliseconds, -1 when unset.
 */
public final class UnitState {

  final UUID ruleId;
  final String unitKey;
  Integer appId;
  String env;
  TargetType targetType;
  Long targetId;
  String targetName;

  AlertState state = AlertState.OK;
  Severity severity;
  long stateSinceMs;
  long pendingSinceMs = -1;
  long firingSinceMs = -1;
  long recoverSinceMs = -1;
  long lastNotifiedMs = -1;
  Severity deescalateTo;
  AlertState beforeNoData;
  Severity severityBeforeNoData;
  UUID eventId;
  long eventTsMs = -1;
  Double value;
  Double threshold;
  UUID ackedBy;
  String ackedByName;
  long ackedAtMs = -1;
  String ackNote;

  /**
   * A unit in state OK.
   *
   * @param ruleId the rule
   * @param unitKey the unit ({@code app}, {@code instance:<id>}, ...)
   */
  public UnitState(UUID ruleId, String unitKey) {
    this.ruleId = Objects.requireNonNull(ruleId, "ruleId");
    this.unitKey = Objects.requireNonNull(unitKey, "unitKey");
  }

  /** The rule. */
  public UUID ruleId() {
    return ruleId;
  }

  /** The unit key. */
  public String unitKey() {
    return unitKey;
  }

  /** The state. */
  public AlertState state() {
    return state;
  }

  /** The severity, null while OK. */
  public Severity severity() {
    return severity;
  }

  /** The event of the current firing, or null. */
  public UUID eventId() {
    return eventId;
  }

  /** The last value. */
  public Double value() {
    return value;
  }

  /** The threshold of the severity. */
  public Double threshold() {
    return threshold;
  }

  /** The FIRING transition time, -1 when not firing. */
  public long firingSinceMs() {
    return firingSinceMs;
  }

  /** The time the condition was first true, -1 when OK. */
  public long pendingSinceMs() {
    return pendingSinceMs;
  }

  /** The time of the last notification, -1 when none. */
  public long lastNotifiedMs() {
    return lastNotifiedMs;
  }

  /** The severity the next renotify applies, or null. */
  public Severity deescalateTo() {
    return deescalateTo;
  }

  /** The instance id of an {@code instance:<id>} unit, or null. */
  public Integer instanceId() {
    return unitKey.startsWith("instance:") ? Integer.valueOf(unitKey.substring(9)) : null;
  }
}
