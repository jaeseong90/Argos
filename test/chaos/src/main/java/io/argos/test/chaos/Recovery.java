/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Recovery after a fault (18 §11.1 "복구 후 60초 내 인스턴스 UP·수집 재개"): the time from the fault's removal
 * until both the instance is up again on a session the server saw after the fault (state {@code UP}
 * or {@code WARN} with {@code last_heartbeat_at} at or after the removal) and the first xlog row of
 * a transaction that started after the removal is stored.
 *
 * @param faultEnd when the fault was removed
 * @param instanceUp when the instance was first seen up after it, or null
 * @param newRows when the first new row was first seen, or null
 */
public record Recovery(Instant faultEnd, Instant instanceUp, Instant newRows) {

  /** The target of 18 §11.1. */
  public static final Duration TARGET = Duration.ofSeconds(60);

  /** Whether both signals were seen. */
  public boolean resumed() {
    return instanceUp != null && newRows != null;
  }

  /** The recovery time, empty when collection did not resume. */
  public Optional<Duration> time() {
    if (!resumed()) {
      return Optional.empty();
    }
    Instant last = instanceUp.isAfter(newRows) ? instanceUp : newRows;
    Duration d = Duration.between(faultEnd, last);
    return Optional.of(d.isNegative() ? Duration.ZERO : d);
  }

  /** Whether the recovery took at most {@link #TARGET}. */
  public boolean withinTarget() {
    return time().map(t -> t.compareTo(TARGET) <= 0).orElse(false);
  }

  /** Whether an instance seen at {@code lastHeartbeat} in state {@code state} counts as back. */
  public static boolean instanceBack(String state, Instant lastHeartbeat, Instant faultEnd) {
    return ChaosStack.isUp(state) && lastHeartbeat != null && !lastHeartbeat.isBefore(faultEnd);
  }
}
