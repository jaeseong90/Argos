/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.AlertNotification.Severity;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.rule.RuleSpec.Condition;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The alert state machine of 12 §5.4 (F-S17, F-S25, F-S26), one evaluation of one unit at a time:
 *
 * <pre>
 * OK ──(condition)──▶ PENDING ──(for)──▶ FIRING ──(ACK)──▶ ACKED
 *  ▲                    │(false)          │(recovery for recover.for)  │
 *  └────────────────────┘                 ▼                            ▼
 *                                      RESOLVED ◀───────────────────────┘
 * any ──(no value, no_data = warn|critical)──▶ NO_DATA ──(value)──▶ the state before, re-evaluated
 * </pre>
 *
 * <ul>
 *   <li>Two levels: critical if its threshold is met, else warn if its threshold is met. A rise
 *       while FIRING (or ACKED) is notified at once (ESCALATED); a fall applies at the next
 *       renotify.
 *   <li>Recovery (hysteresis): {@code recover.threshold} on the good side for {@code recover.for}
 *       (default {@code for / 2}); without a threshold, the condition false for that long.
 *   <li>Renotify every {@code renotify_interval} while FIRING; never while ACKED; RESOLVED is
 *       always notified.
 *   <li>No value with {@code no_data: ignore}: the state stays, except that PENDING returns to OK
 *       (the condition was not seen to hold continuously). An instance unit whose instance is gone
 *       resolves ({@link #resolveGone}, reason {@code target_gone}, C-226 (12)).
 *   <li>Every persisted change carries its {@link AlertReason} ({@code alert_events.reason}, C-227
 *       (3)).
 * </ul>
 *
 * <p>The caller has applied the gates (active time, mutes, warm-up, minimum samples) before; it
 * persists and notifies the returned changes in order.
 */
public final class StateMachine {

  private StateMachine() {}

  /**
   * One change of a unit.
   *
   * @param from the state before
   * @param to the state after
   * @param severity the severity after (null for OK)
   * @param notification the notification to send, or null
   * @param persist whether an {@code alert_events} row is written
   * @param eventId the id of that row, or (renotify) the current firing's event
   * @param durationS seconds the condition held (FIRING, ESCALATED) or the alert fired (RESOLVED)
   * @param reason why (the {@code alert_events.reason}), null only for a renotify (not persisted)
   */
  public record Change(
      AlertState from,
      AlertState to,
      Severity severity,
      AlertNotification.Kind notification,
      boolean persist,
      UUID eventId,
      int durationS,
      AlertReason reason) {}

  /** The severity {@code value} meets, or null. */
  public static Severity level(Condition c, double value) {
    if (c.critical() != null && c.operator().test(value, c.critical())) {
      return Severity.CRITICAL;
    }
    if (c.warn() != null && c.operator().test(value, c.warn())) {
      return Severity.WARN;
    }
    return null;
  }

  /** The threshold of {@code severity}. */
  public static Double threshold(Condition c, Severity severity) {
    if (severity == null) {
      return null;
    }
    return severity == Severity.CRITICAL
        ? (c.critical() != null ? c.critical() : c.warn())
        : (c.warn() != null ? c.warn() : c.critical());
  }

  /**
   * One evaluation.
   *
   * @param value the metric value, or null for no value
   * @return the changes, in order (empty: nothing changed)
   */
  public static List<Change> step(RuleSpec rule, UnitState s, Double value, long nowMs) {
    List<Change> out = new ArrayList<>();
    Condition c = rule.condition();
    if (value == null) {
      noValue(rule, s, nowMs, out);
      return out;
    }
    s.value = value;
    if (s.state == AlertState.NO_DATA) {
      AlertState back = s.beforeNoData == null ? AlertState.OK : s.beforeNoData;
      Severity sev = back.active() ? s.severityBeforeNoData : null;
      out.add(move(s, back, sev, null, true, nowMs, 0, AlertReason.DATA_RETURNED));
      s.beforeNoData = null;
      s.severityBeforeNoData = null;
      if (!back.active()) {
        s.pendingSinceMs = -1;
      }
    }
    Severity level = level(c, value);
    switch (s.state) {
      case OK, RESOLVED -> {
        if (level == null) {
          s.state = AlertState.OK;
          s.severity = null;
          return out;
        }
        s.pendingSinceMs = nowMs;
        s.threshold = threshold(c, level);
        out.add(move(s, AlertState.PENDING, level, null, true, nowMs, 0, AlertReason.CONDITION));
        if (rule.forDuration().isZero()) {
          fire(c, s, level, nowMs, out);
        }
      }
      case PENDING -> {
        if (level == null) {
          s.pendingSinceMs = -1;
          s.threshold = null;
          out.add(move(s, AlertState.OK, null, null, true, nowMs, 0, AlertReason.CLEARED));
          return out;
        }
        s.severity = level;
        s.threshold = threshold(c, level);
        if (nowMs - s.pendingSinceMs >= rule.forDuration().toMillis()) {
          fire(c, s, level, nowMs, out);
        }
      }
      case FIRING, ACKED -> firing(rule, s, value, level, nowMs, out);
      default -> {}
    }
    return out;
  }

  /**
   * Acknowledges a FIRING unit (F-S26).
   *
   * @return the change, or null when the unit is not FIRING
   */
  public static Change ack(UnitState s, UUID userId, String username, String note, long nowMs) {
    if (s.state != AlertState.FIRING) {
      return null;
    }
    s.ackedBy = userId;
    s.ackedByName = username;
    s.ackedAtMs = nowMs;
    s.ackNote = note;
    return move(
        s,
        AlertState.ACKED,
        s.severity,
        null,
        true,
        nowMs,
        seconds(nowMs - s.firingSinceMs),
        AlertReason.ACKED);
  }

  /**
   * Fires directly (event-driven rules: instance DOWN, channel failure), unless already active.
   *
   * @return the changes (empty when already FIRING or ACKED)
   */
  public static List<Change> fireNow(
      UnitState s,
      Severity severity,
      double value,
      Double threshold,
      AlertNotification.Kind kind,
      long nowMs) {
    List<Change> out = new ArrayList<>();
    if (s.state.active()) {
      return out;
    }
    s.value = value;
    s.pendingSinceMs = nowMs;
    s.threshold = threshold;
    s.firingSinceMs = nowMs;
    s.lastNotifiedMs = nowMs;
    s.recoverSinceMs = -1;
    s.deescalateTo = null;
    clearAck(s);
    UUID id = UUID.randomUUID();
    s.eventId = id;
    s.eventTsMs = nowMs;
    out.add(moveWithId(s, AlertState.FIRING, severity, kind, id, nowMs, 0, AlertReason.CONDITION));
    return out;
  }

  /**
   * Resolves directly (event-driven rules), when active.
   *
   * @return the changes (empty when not FIRING or ACKED)
   */
  public static List<Change> resolveNow(
      UnitState s, double value, AlertNotification.Kind kind, long nowMs) {
    return resolveNow(s, value, kind, AlertReason.RESOLVED, nowMs);
  }

  /**
   * Resolves directly for {@code reason} (event-driven rules: {@code resolved}; a disabled or
   * deleted rule: {@code rule_disabled}, {@code rule_deleted}), when active.
   *
   * @return the changes (empty when not FIRING or ACKED)
   */
  public static List<Change> resolveNow(
      UnitState s, double value, AlertNotification.Kind kind, AlertReason reason, long nowMs) {
    List<Change> out = new ArrayList<>();
    if (!s.state.active()) {
      return out;
    }
    s.value = value;
    resolve(s, kind, nowMs, out, reason);
    return out;
  }

  /**
   * Resolves an active unit whose target is gone (ADR-0010 C-226 (12)): an instance unit of a
   * {@code no_data: ignore} rule never sees a value again once its instance is DOWN, archived or
   * deleted, so it would stay FIRING (or ACKED) for ever. It resolves at once with no value, reason
   * {@code target_gone}.
   *
   * @return the changes (empty when not FIRING or ACKED)
   */
  public static List<Change> resolveGone(UnitState s, long nowMs) {
    List<Change> out = new ArrayList<>();
    if (!s.state.active()) {
      return out;
    }
    s.value = null;
    resolve(s, AlertNotification.Kind.RESOLVED, nowMs, out, AlertReason.TARGET_GONE);
    return out;
  }

  private static void noValue(RuleSpec rule, UnitState s, long nowMs, List<Change> out) {
    switch (rule.noData()) {
      case IGNORE -> {
        if (s.state == AlertState.PENDING) {
          s.pendingSinceMs = -1;
          s.threshold = null;
          out.add(move(s, AlertState.OK, null, null, true, nowMs, 0, AlertReason.CLEARED));
        }
      }
      case WARN, CRITICAL -> {
        if (s.state == AlertState.NO_DATA) {
          return;
        }
        AlertState before =
            s.state == AlertState.PENDING || s.state == AlertState.RESOLVED
                ? AlertState.OK
                : s.state;
        s.beforeNoData = before;
        s.severityBeforeNoData = before.active() ? s.severity : null;
        s.value = null;
        Severity sev = rule.noData() == RuleSpec.NoData.WARN ? Severity.WARN : Severity.CRITICAL;
        UUID id = UUID.randomUUID();
        out.add(
            moveWithId(
                s,
                AlertState.NO_DATA,
                sev,
                AlertNotification.Kind.FIRING,
                id,
                nowMs,
                0,
                AlertReason.NO_DATA));
        if (!before.active()) {
          s.eventId = id;
          s.eventTsMs = nowMs;
        }
      }
    }
  }

  private static void fire(Condition c, UnitState s, Severity level, long nowMs, List<Change> out) {
    int held = seconds(nowMs - s.pendingSinceMs);
    s.firingSinceMs = nowMs;
    s.lastNotifiedMs = nowMs;
    s.recoverSinceMs = -1;
    s.deescalateTo = null;
    s.threshold = threshold(c, level);
    clearAck(s);
    UUID id = UUID.randomUUID();
    s.eventId = id;
    s.eventTsMs = nowMs;
    out.add(
        moveWithId(
            s,
            AlertState.FIRING,
            level,
            AlertNotification.Kind.FIRING,
            id,
            nowMs,
            held,
            AlertReason.CONDITION));
  }

  private static void firing(
      RuleSpec rule, UnitState s, double value, Severity level, long nowMs, List<Change> out) {
    Condition c = rule.condition();
    if (level == Severity.CRITICAL && s.severity == Severity.WARN) {
      s.deescalateTo = null;
      s.threshold = threshold(c, Severity.CRITICAL);
      s.lastNotifiedMs = nowMs;
      out.add(
          new Change(
              s.state,
              s.state,
              Severity.CRITICAL,
              AlertNotification.Kind.ESCALATED,
              true,
              UUID.randomUUID(),
              seconds(nowMs - s.firingSinceMs),
              AlertReason.ESCALATED));
      s.severity = Severity.CRITICAL;
    } else if (level == Severity.WARN && s.severity == Severity.CRITICAL) {
      s.deescalateTo = Severity.WARN;
    } else if (level == Severity.CRITICAL) {
      s.deescalateTo = null;
    }
    boolean recovered;
    RuleSpec.Recover r = rule.recover();
    if (r != null && r.threshold() != null) {
      recovered = c.operator().recovered(value, r.threshold());
    } else {
      recovered = level == null;
    }
    long recoverForMs =
        r != null && r.forDuration() != null
            ? r.forDuration().toMillis()
            : rule.forDuration().toMillis() / 2;
    if (recovered) {
      if (s.recoverSinceMs < 0) {
        s.recoverSinceMs = nowMs;
      }
      if (nowMs - s.recoverSinceMs >= recoverForMs) {
        resolve(s, AlertNotification.Kind.RESOLVED, nowMs, out, AlertReason.RESOLVED);
        return;
      }
    } else {
      s.recoverSinceMs = -1;
    }
    if (s.state == AlertState.FIRING
        && !rule.renotifyInterval().isZero()
        && nowMs - s.lastNotifiedMs >= rule.renotifyInterval().toMillis()) {
      if (s.deescalateTo != null) {
        s.severity = s.deescalateTo;
        s.threshold = threshold(c, s.severity);
        s.deescalateTo = null;
      }
      s.lastNotifiedMs = nowMs;
      out.add(
          new Change(
              s.state,
              s.state,
              s.severity,
              AlertNotification.Kind.RENOTIFY,
              false,
              s.eventId,
              seconds(nowMs - s.firingSinceMs),
              null));
    }
  }

  private static void resolve(
      UnitState s, AlertNotification.Kind kind, long nowMs, List<Change> out, AlertReason reason) {
    int fired = seconds(nowMs - s.firingSinceMs);
    Severity sev = s.severity;
    out.add(move(s, AlertState.RESOLVED, sev, kind, true, nowMs, fired, reason));
    s.pendingSinceMs = -1;
    s.recoverSinceMs = -1;
    s.deescalateTo = null;
    s.lastNotifiedMs = nowMs;
  }

  private static void clearAck(UnitState s) {
    s.ackedBy = null;
    s.ackedByName = null;
    s.ackedAtMs = -1;
    s.ackNote = null;
  }

  private static Change move(
      UnitState s,
      AlertState to,
      Severity severity,
      AlertNotification.Kind notification,
      boolean persist,
      long nowMs,
      int durationS,
      AlertReason reason) {
    return moveWithId(
        s,
        to,
        severity,
        notification,
        persist ? UUID.randomUUID() : null,
        nowMs,
        durationS,
        reason);
  }

  private static Change moveWithId(
      UnitState s,
      AlertState to,
      Severity severity,
      AlertNotification.Kind notification,
      UUID id,
      long nowMs,
      int durationS,
      AlertReason reason) {
    AlertState from = s.state;
    s.state = to;
    s.severity = severity;
    s.stateSinceMs = nowMs;
    return new Change(from, to, severity, notification, id != null, id, durationS, reason);
  }

  static int seconds(long ms) {
    return (int) Math.max(0, Math.min(Integer.MAX_VALUE, ms / 1000));
  }
}
