/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.engine;

import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.support.CronExpression;

/**
 * The gates before the state machine (12 §5.4): a rule is not evaluated outside its active time
 * slots, inside a recurring mute ({@code mute_schedules}) or a one-off mute ({@code mutes}), and an
 * instance is not evaluated in its warm-up grace; in all cases the state stays as it is.
 */
public final class Gates {

  private Gates() {}

  /** Whether {@code t} is inside one of the rule's active time slots (none: always). */
  public static boolean active(RuleSpec rule, ZonedDateTime t) {
    if (rule.activeTime().isEmpty()) {
      return true;
    }
    for (RuleSpec.ActiveTime a : rule.activeTime()) {
      if (a.contains(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code t} is inside a recurring mute: a cron start at most {@code duration} before
   * {@code t} (end exclusive).
   */
  public static boolean scheduleMuted(RuleSpec rule, ZonedDateTime t) {
    for (RuleSpec.MuteSchedule m : rule.muteSchedules()) {
      CronExpression cron = RuleCodec.cron(m.cron());
      if (cron == null) {
        continue;
      }
      ZonedDateTime next = cron.next(t.minus(m.duration()));
      if (next != null && !next.isAfter(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a one-off mute covers the rule unit at {@code t}; a null mute column matches anything.
   *
   * @param appId the rule's application, null for a server rule
   * @param env the application's environment, or null
   * @param instanceId the unit's instance, or null
   */
  public static boolean muted(
      List<MuteRow> mutes, UUID ruleId, Integer appId, String env, Integer instanceId, Instant t) {
    for (MuteRow m : mutes) {
      if (t.isBefore(m.startsAt()) || !t.isBefore(m.endsAt())) {
        continue;
      }
      if (m.ruleId() != null && !m.ruleId().equals(ruleId)) {
        continue;
      }
      if (m.appId() != null && !Objects.equals(m.appId(), appId)) {
        continue;
      }
      if (m.env() != null && !m.env().equals(env)) {
        continue;
      }
      if (m.instanceId() != null && !Objects.equals(m.instanceId(), instanceId)) {
        continue;
      }
      return true;
    }
    return false;
  }

  /**
   * Whether an instance is in its warm-up grace at {@code nowMs}.
   *
   * @param warmupStartMs the instance's warm-up start, -1 when unknown (no session: not warming)
   */
  public static boolean warming(long warmupStartMs, RuleSpec rule, long nowMs) {
    return warmupStartMs >= 0 && nowMs - warmupStartMs < rule.warmupGrace().toMillis();
  }
}
