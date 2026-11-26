/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.util.Locale;

/**
 * The length of a chaos run (18 §11.1). {@link #FULL} is the design: the server down for one hour,
 * ClickHouse paused for 30 minutes, five-minute baselines. {@link #SHORT} runs every phase for
 * minutes, to prove the runner and the verdict logic end to end on a shared machine; its timing and
 * p99 verdicts are INVALID ({@link Judge}).
 */
public enum ChaosProfile {
  /** Minutes per scenario: runner and verdict logic. */
  SHORT(
      Duration.ofSeconds(30),
      Duration.ofSeconds(60),
      Duration.ofSeconds(60),
      Duration.ofMinutes(3),
      Duration.ofMinutes(3),
      Duration.ofMinutes(3),
      Duration.ofMinutes(2),
      100),
  /** 18 §11.1 as designed. */
  FULL(
      Duration.ofMinutes(2),
      Duration.ofMinutes(5),
      Duration.ofMinutes(2),
      Duration.ofMinutes(10),
      Duration.ofHours(1),
      Duration.ofMinutes(30),
      Duration.ofMinutes(10),
      200);

  private final Duration warmUp;
  private final Duration baseline;
  private final Duration recovery;
  private final Duration drainTimeout;
  private final Duration serverDown;
  private final Duration clickHouseDown;
  private final Duration otherFault;
  private final int defaultRate;

  ChaosProfile(
      Duration warmUp,
      Duration baseline,
      Duration recovery,
      Duration drainTimeout,
      Duration serverDown,
      Duration clickHouseDown,
      Duration otherFault,
      int defaultRate) {
    this.warmUp = warmUp;
    this.baseline = baseline;
    this.recovery = recovery;
    this.drainTimeout = drainTimeout;
    this.serverDown = serverDown;
    this.clickHouseDown = clickHouseDown;
    this.otherFault = otherFault;
    this.defaultRate = defaultRate;
  }

  /** {@code short} or {@code full} (case-insensitive); blank is {@link #SHORT}. */
  public static ChaosProfile parse(String value) {
    if (value == null || value.isBlank()) {
      return SHORT;
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "short" -> SHORT;
      case "full" -> FULL;
      default ->
          throw new IllegalArgumentException(
              "chaos.profile must be short or full, got '" + value + "'");
    };
  }

  /** Load before the first scenario of a run (JIT, connection pools). */
  public Duration warmUp() {
    return warmUp;
  }

  /** The window before the fault whose sub-window p99 values give the noise band. */
  public Duration baseline() {
    return baseline;
  }

  /** The window after the fault (recovery is judged within 60 s of it). */
  public Duration recovery() {
    return recovery;
  }

  /** How long the runner waits for the stored rows after the load ends. */
  public Duration drainTimeout() {
    return drainTimeout;
  }

  /**
   * How long the fault of scenario {@code id} lasts: T-CH-001 the server down (short 3 min, full 1
   * h), T-CH-009 ClickHouse paused (short 3 min, full 30 min), every other scenario short 2 min,
   * full 10 min.
   */
  public Duration fault(String id) {
    return switch (id) {
      case "T-CH-001" -> serverDown;
      case "T-CH-009" -> clickHouseDown;
      default -> otherFault;
    };
  }

  /** Requests per second when {@code -Pchaos.rate} is not given (18 §11.1: 200; short 100). */
  public int defaultRate() {
    return defaultRate;
  }

  /** {@code short} or {@code full}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }
}
