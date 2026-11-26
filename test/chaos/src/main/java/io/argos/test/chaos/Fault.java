/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What a chaos scenario does to the stack between its baseline and its recovery window ({@link
 * ScenarioDriver}). The driver calls {@link #inject}, then {@link #during} until the fault's end
 * time, then {@link #remove}; the fault adds its own rows with {@link #checks} after the common
 * ones of 18 §11.1.
 */
public interface Fault {

  /** One line for the report: what is done and how. */
  String describe();

  /**
   * Runs before the scenario's quiet wait: a scenario that needs an agent configured differently
   * starts its own application here ({@link ChaosStack#startApp}) and returns it; the driver then
   * measures it instead of the main app ({@link ChaosStack#use}). Null (default): the main app.
   */
  default ChaosApp prepare(ScenarioContext c) throws Exception {
    return null;
  }

  /**
   * Runs after the verdicts, also when the scenario failed: stops what {@link #prepare} started.
   * The driver switches the probes back to the main app afterwards.
   */
  default void cleanup(ScenarioContext c) {}

  /** Starts the fault (the fault window starts before this call). */
  void inject(ScenarioContext c) throws Exception;

  /** Runs while the fault lasts; the default sleeps until {@code until}. */
  default void during(ScenarioContext c, Instant until) throws Exception {
    sleepUntil(until);
  }

  /** Ends the fault (the fault window ends when this call returns). */
  void remove(ScenarioContext c) throws Exception;

  /**
   * How long {@link #remove} may take (a server restart): the load generator runs that much longer
   * so that the recovery window keeps its length. Default zero.
   */
  default Duration removalAllowance(ChaosStack stack) {
    return Duration.ZERO;
  }

  /**
   * The collection outage the server must show for this fault ({@code collection-health} {@code
   * outages[].reason}: {@code server_down}, {@code session_lost}), or null when none is expected.
   */
  default String expectedOutage(ScenarioContext c) {
    return null;
  }

  /**
   * Why the outage check does not apply to this run (for example a forced server stop on Windows),
   * or null.
   */
  default String outageSkipReason(ScenarioContext c) {
    return null;
  }

  /** Whether an agent ERROR record is expected by this fault (the common check skips it). */
  default boolean errorExpected(AgentLog.Line line) {
    return false;
  }

  /** The scenario's own rows (18 §11.1 "추가 판정"). */
  default List<Check> checks(ScenarioContext c) {
    return List.of();
  }

  /** Sleeps until {@code until}. */
  static void sleepUntil(Instant until) throws InterruptedException {
    Duration left = Duration.between(Instant.now(), until);
    if (!left.isNegative() && !left.isZero()) {
      Thread.sleep(left.toMillis());
    }
  }
}
