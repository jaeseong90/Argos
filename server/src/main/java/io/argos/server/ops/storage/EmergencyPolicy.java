/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

/**
 * The disk thresholds of F-S23 as a state machine with hysteresis (13 §8, 17 §9.2; PM question 1
 * assumption), fed with the used ratio of the fullest ClickHouse disk:
 *
 * <ul>
 *   <li>warning while the ratio is at or above {@code warn} (0.8);
 *   <li>the emergency retention starts at or above {@code emergency} (0.9) and keeps dropping until
 *       the ratio is below {@code target} (0.85), so it does not flap at 0.9;
 *   <li>inserts pause at or above {@code stop} (0.95) and resume only below {@code resume} ({@link
 *       #RESUME_MARGIN} under {@code stop}, 0.93): one drop frees a day of data at once, and
 *       resuming at 0.949 would pause again with the next blocks.
 * </ul>
 *
 * Not thread-safe; the storage health job is its only caller.
 */
public final class EmergencyPolicy {

  /** How far below the stop ratio the inserts resume. */
  public static final double RESUME_MARGIN = 0.02;

  private final double warn;
  private final double emergency;
  private final double target;
  private final double stop;
  private final double resume;
  private boolean warning;
  private boolean active;
  private boolean paused;
  private boolean evaluated;

  /**
   * @param warn warning ratio
   * @param emergency ratio where the shortening starts
   * @param target ratio below which it stops ({@code < emergency})
   * @param stop ratio where inserts pause
   */
  public EmergencyPolicy(double warn, double emergency, double target, double stop) {
    if (!(target < emergency && warn < emergency && emergency < stop)) {
      throw new IllegalArgumentException("warn, target < emergency < stop is required");
    }
    this.warn = warn;
    this.emergency = emergency;
    this.target = target;
    this.stop = stop;
    this.resume = stop - RESUME_MARGIN;
  }

  /**
   * The state after one observation.
   *
   * @param warning at or above the warning ratio
   * @param active the emergency retention drops a partition in this step
   * @param paused inserts are paused
   * @param changedActive {@code active} changed in this step
   * @param changedPaused {@code paused} changed in this step
   */
  public record State(
      boolean warning,
      boolean active,
      boolean paused,
      boolean changedActive,
      boolean changedPaused) {}

  /** Takes one observation; a NaN ratio (unknown) keeps the state. */
  public State observe(double ratio) {
    boolean wasActive = active;
    boolean wasPaused = paused;
    if (!Double.isNaN(ratio)) {
      evaluated = true;
      warning = ratio >= warn;
      if (!active && ratio >= emergency) {
        active = true;
      } else if (active && ratio < target) {
        active = false;
      }
      if (!paused && ratio >= stop) {
        paused = true;
      } else if (paused && ratio < resume) {
        paused = false;
      }
    }
    return new State(warning, active, paused, wasActive != active, wasPaused != paused);
  }

  /** Whether the emergency retention is on (false before the first known ratio). */
  public boolean active() {
    return active;
  }

  /** Whether a ratio was observed yet. */
  public boolean evaluated() {
    return evaluated;
  }

  /** The ratio below which inserts resume. */
  public double resumeRatio() {
    return resume;
  }
}
