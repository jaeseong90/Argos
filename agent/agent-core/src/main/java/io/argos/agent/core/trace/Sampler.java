/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.core.config.AgentConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The profile decision at the end of a transaction (10 §9.2 step 3, DSN-10-08): an error
 * transaction sends its profile; a transaction at least as slow as the slow threshold of its
 * service ({@code sampling.profile.slow-threshold-ms}: per-service value, else the default 1,000
 * ms) sends it; any other one sends it with probability {@code sampling.profile.normal-rate} (0.1,
 * {@link ThreadLocalRandom}). Immutable; a configuration change replaces the whole sampler.
 */
public final class Sampler {
  /** Not captured. */
  public static final int NONE = 0;

  /** Captured: the transaction is an error. */
  public static final int ERROR = 1;

  /** Captured: the transaction reached the slow threshold of its service. */
  public static final int SLOW = 2;

  /** Captured: chosen at random ({@code normal-rate}). */
  public static final int RANDOM = 3;

  /** Default slow threshold (10 §15). */
  public static final int DEFAULT_SLOW_THRESHOLD_MS = 1000;

  /** Default normal rate (10 §15). */
  public static final double DEFAULT_NORMAL_RATE = 0.1;

  /** Per-service thresholds kept at most (the configuration's own cap). */
  static final int MAX_SERVICES = 1_000;

  /** Uniform random numbers in [0, 1). */
  public interface RandomSource {
    double nextDouble();
  }

  private static final RandomSource THREAD_LOCAL_RANDOM = new ThreadLocalSource();

  private final long defaultSlowNanos;
  private final Map<String, Long> serviceSlowNanos;
  private final double normalRate;
  private final RandomSource random;

  /** A sampler with {@link ThreadLocalRandom} as random source. */
  public Sampler(int defaultSlowMs, Map<String, Integer> services, double normalRate) {
    this(defaultSlowMs, services, normalRate, THREAD_LOCAL_RANDOM);
  }

  /** A sampler with {@code random} as random source (tests). */
  public Sampler(
      int defaultSlowMs, Map<String, Integer> services, double normalRate, RandomSource random) {
    this.defaultSlowNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, defaultSlowMs));
    Map<String, Long> copy = new HashMap<String, Long>();
    if (services != null) {
      for (Map.Entry<String, Integer> entry : services.entrySet()) {
        if (copy.size() >= MAX_SERVICES) {
          break;
        }
        if (entry.getKey() != null && entry.getValue() != null) {
          copy.put(
              entry.getKey(),
              TimeUnit.MILLISECONDS.toNanos(Math.max(0, entry.getValue().intValue())));
        }
      }
    }
    this.serviceSlowNanos = Collections.unmodifiableMap(copy);
    this.normalRate = Double.isNaN(normalRate) ? 0.0 : Math.max(0.0, Math.min(1.0, normalRate));
    this.random = random == null ? THREAD_LOCAL_RANDOM : random;
  }

  /** The 10 §15 defaults: 1,000 ms for every service, normal rate 0.1. */
  public static Sampler defaults() {
    return new Sampler(
        DEFAULT_SLOW_THRESHOLD_MS, Collections.<String, Integer>emptyMap(), DEFAULT_NORMAL_RATE);
  }

  /**
   * {@code sampling.profile.slow-threshold-ms} (default and per service) and {@code normal-rate}.
   */
  public static Sampler of(AgentConfig config) {
    return new Sampler(
        config.slowThresholdMs(), config.slowThresholdServices(), config.normalRate());
  }

  /**
   * {@link #ERROR}, {@link #SLOW}, {@link #RANDOM} (profile captured) or {@link #NONE}, in this
   * order of precedence. {@code elapsedNanos} is the monotonic elapsed time (D-01).
   */
  public int decide(boolean error, long elapsedNanos, String service) {
    if (error) {
      return ERROR;
    }
    if (elapsedNanos >= slowThresholdNanos(service)) {
      return SLOW;
    }
    if (normalRate > 0.0 && random.nextDouble() < normalRate) {
      return RANDOM;
    }
    return NONE;
  }

  /** The slow threshold of {@code service} in nanoseconds. */
  public long slowThresholdNanos(String service) {
    if (service != null && !serviceSlowNanos.isEmpty()) {
      Long own = serviceSlowNanos.get(service);
      if (own != null) {
        return own.longValue();
      }
    }
    return defaultSlowNanos;
  }

  public double normalRate() {
    return normalRate;
  }

  private static final class ThreadLocalSource implements RandomSource {
    @Override
    public double nextDouble() {
      return ThreadLocalRandom.current().nextDouble();
    }
  }
}
