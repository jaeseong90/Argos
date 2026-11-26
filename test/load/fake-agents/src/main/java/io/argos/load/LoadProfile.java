/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.storage.clickhouse.MemoryBudget;
import java.time.Duration;
import java.util.Locale;

/**
 * The run profiles of {@code serverLoadTest} (18 §8, WO-29). Only {@link #FULL} is the N-06 run;
 * the others scale the same load down for machines that cannot host it, and every timing or
 * resource verdict they produce is INVALID (see {@link Verdicts}).
 */
public enum LoadProfile {
  /**
   * N-06 on 13 §7.1 hardware: 50 instances over 10 apps, 5,000 TPS, 50,000 profile step rows/s,
   * 10,000 statistics rows/s, 1 hour after a 5 minute warm-up; ClickHouse 7.5 GiB, PostgreSQL 1.5
   * GiB, server heap 4 GiB.
   */
  FULL(
      50,
      10,
      5_000,
      50_000,
      10_000,
      Duration.ofMinutes(5),
      Duration.ofHours(1),
      Duration.ofMinutes(15),
      Duration.ofMinutes(1),
      Duration.ofMinutes(30),
      7_680L * 1024 * 1024,
      1_536L * 1024 * 1024,
      "4g"),
  /**
   * A shared developer machine: 10 instances over 5 apps, 1,000 TPS with the N-06 proportions
   * (10,000 step rows/s, 2,000 statistics rows/s), 5 minutes after a 1 minute warm-up (about 12 to
   * 15 minutes of wall time with the stack start and the drain); ClickHouse 4 GiB, PostgreSQL 1
   * GiB, server heap 2 GiB.
   */
  SMOKE(
      10,
      5,
      1_000,
      10_000,
      2_000,
      Duration.ofMinutes(1),
      Duration.ofMinutes(5),
      Duration.ofMinutes(3),
      Duration.ofSeconds(10),
      Duration.ofSeconds(60),
      4 * MemoryBudget.GIB,
      MemoryBudget.GIB,
      "2g"),
  /**
   * The runner's self-check (about 5 minutes): 4 instances over 2 apps, 200 TPS with the N-06
   * proportions, 90 seconds after a 20 second warm-up; ClickHouse 2 GiB, PostgreSQL 512 MiB, server
   * heap 1 GiB.
   */
  MINI(
      4,
      2,
      200,
      2_000,
      400,
      Duration.ofSeconds(20),
      Duration.ofSeconds(90),
      Duration.ofMinutes(2),
      Duration.ofSeconds(5),
      Duration.ofSeconds(20),
      2 * MemoryBudget.GIB,
      MemoryBudget.GIB / 2,
      "1g");

  private final int instances;
  private final int apps;
  private final double tps;
  private final double stepRowsPerSecond;
  private final double statRowsPerSecond;
  private final Duration warmUp;
  private final Duration duration;
  private final Duration drainTimeout;
  private final Duration lateMin;
  private final Duration lateMax;
  private final long clickHouseMemoryBytes;
  private final long postgresMemoryBytes;
  private final String serverHeap;

  LoadProfile(
      int instances,
      int apps,
      double tps,
      double stepRowsPerSecond,
      double statRowsPerSecond,
      Duration warmUp,
      Duration duration,
      Duration drainTimeout,
      Duration lateMin,
      Duration lateMax,
      long clickHouseMemoryBytes,
      long postgresMemoryBytes,
      String serverHeap) {
    this.instances = instances;
    this.apps = apps;
    this.tps = tps;
    this.stepRowsPerSecond = stepRowsPerSecond;
    this.statRowsPerSecond = statRowsPerSecond;
    this.warmUp = warmUp;
    this.duration = duration;
    this.drainTimeout = drainTimeout;
    this.lateMin = lateMin;
    this.lateMax = lateMax;
    this.clickHouseMemoryBytes = clickHouseMemoryBytes;
    this.postgresMemoryBytes = postgresMemoryBytes;
    this.serverHeap = serverHeap;
  }

  /** The profile named {@code name} ({@code full}, {@code smoke}, {@code mini}). */
  public static LoadProfile of(String name) {
    try {
      return valueOf(name.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown -Pserver.load.profile '" + name + "' (full, smoke or mini)", e);
    }
  }

  /** Lower-case name. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** Fake agent instances. */
  public int instances() {
    return instances;
  }

  /** Apps the instances are spread over. */
  public int apps() {
    return apps;
  }

  /** Transactions per second in total. */
  public double tps() {
    return tps;
  }

  /** Profile step rows per second in total (N-06: 50,000 at 5,000 TPS). */
  public double stepRowsPerSecond() {
    return stepRowsPerSecond;
  }

  /** Statistics rows ({@code SqlStat} + {@code ExtStat}) per second in total. */
  public double statRowsPerSecond() {
    return statRowsPerSecond;
  }

  /** Warm-up before the measured window. */
  public Duration warmUp() {
    return warmUp;
  }

  /** The measured window. */
  public Duration duration() {
    return duration;
  }

  /** How long the end check waits for the last rows. */
  public Duration drainTimeout() {
    return drainTimeout;
  }

  /** Shortest delay of a late arrival. */
  public Duration lateMin() {
    return lateMin;
  }

  /** Longest delay of a late arrival. */
  public Duration lateMax() {
    return lateMax;
  }

  /** ClickHouse container memory limit. */
  public long clickHouseMemoryBytes() {
    return clickHouseMemoryBytes;
  }

  /** PostgreSQL container memory limit. */
  public long postgresMemoryBytes() {
    return postgresMemoryBytes;
  }

  /** Server {@code -Xms}/{@code -Xmx}. */
  public String serverHeap() {
    return serverHeap;
  }
}
