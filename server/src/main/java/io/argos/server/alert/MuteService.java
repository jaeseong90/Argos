/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository;
import io.argos.server.storage.postgres.alert.rule.MuteRepository;
import io.argos.server.storage.postgres.alert.rule.MuteRepository.MuteRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One-off mutes of the alert engine ({@code /alerts/mutes} of 14 §4.13, WO-25; controllers are step
 * A2's): at most {@value #MAX_DAYS} days each, ending in the future. The engine reads the current
 * mutes at every evaluation, so a new mute applies within one evaluation interval.
 */
public class MuteService {

  /** Days a mute lasts at most (14 §4.13). */
  public static final int MAX_DAYS = 30;

  /** Characters of a reason at most ({@code mutes.reason}). */
  public static final int MAX_REASON_CHARS = 512;

  private final MuteRepository mutes;
  private final AlertRuleRepository rules;
  private final Clock clock;

  public MuteService(MuteRepository mutes, AlertRuleRepository rules, Clock clock) {
    this.mutes = mutes;
    this.rules = rules;
    this.clock = clock;
  }

  /**
   * Creates a mute; every target field is optional (null: any).
   *
   * @throws ApiException {@code invalid_request} for a range that is empty, ends in the past or is
   *     longer than 30 days, a reason that is too long, or an application or rule that does not
   *     exist
   */
  public MuteRow create(
      Integer appId,
      String env,
      Integer instanceId,
      UUID ruleId,
      Instant from,
      Instant to,
      String reason,
      UUID actor) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The mute range is invalid")
          .detail("to", "not_after_from");
    }
    if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A mute lasts at most 30 days")
          .detail("to", "longer_than_30_days");
    }
    if (!to.isAfter(clock.instant())) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The mute range is invalid")
          .detail("to", "in_the_past");
    }
    if (reason != null && reason.length() > MAX_REASON_CHARS) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The mute reason is too long")
          .detail("reason", "length");
    }
    if (appId != null && !rules.appExists(appId)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The application does not exist")
          .detail("target.app", "not_found");
    }
    if (ruleId != null && rules.find(ruleId).isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The rule does not exist")
          .detail("target.rule", "not_found");
    }
    MuteRow row =
        new MuteRow(
            UUID.randomUUID(), appId, env, instanceId, ruleId, from, to, reason, actor, null);
    mutes.insert(row);
    return mutes.find(row.id()).stream().findFirst().orElse(row);
  }

  /** The mutes that have not ended. */
  public List<MuteRow> current() {
    return mutes.current(clock.instant());
  }

  /** Deletes a mute ({@code 404} when it does not exist). */
  public void delete(UUID id) {
    if (!mutes.delete(id)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
  }
}
