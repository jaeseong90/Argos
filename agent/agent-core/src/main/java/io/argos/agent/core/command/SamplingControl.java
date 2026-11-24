/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import java.util.Objects;

/**
 * The profile sampler of the running agent from three inputs (WO-17, WO-19, 11 §4.2, §6, 10 §13.1):
 * the configured sampler of {@code sampling.profile.*} (local configuration or a remote change,
 * {@link #base}), the cap of the last server {@code Throttle.profile_normal_rate} ({@link
 * #throttle}) and the agent's own overhead cap ({@link #overheadLowered}: {@code OverheadMonitor}
 * halves the configured normal rate while the agent threads use more than 1% of the host CPU). The
 * tracer always gets {@code base.withMaxNormalRate(min(caps))}, so a remote change of {@code
 * sampling.profile.normal-rate} never lifts a throttle or the overhead cap, and no cap ever raises
 * the configured rate. Any agent thread ({@code argos-io}, {@code argos-sender}, {@code
 * argos-command}, {@code argos-watch}); the tracer publishes the immutable sampler through a
 * volatile field.
 *
 * <p>One control per recording runtime ({@link #shared}): the remote control of the gRPC transport
 * and the overhead monitor of {@code argos-watch}, which runs with every exporter, use the same
 * instance, so that neither publishes a sampler without the other's cap.
 */
public final class SamplingControl {
  /** The factor of the overhead cap on the configured normal rate (10 §13.1 "1/2"). */
  public static final double OVERHEAD_FACTOR = 0.5;

  private static volatile SamplingControl shared;

  private final TraceRuntime runtime;
  private Sampler base;
  private float cap = Float.NaN;
  private boolean overheadLowered;

  /** The control of {@code runtime}'s tracer with the configured sampler {@code base}. */
  public SamplingControl(TraceRuntime runtime, Sampler base) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.base = Objects.requireNonNull(base, "base");
  }

  /**
   * The one control of {@code runtime} (created with {@code initialBase} by the first caller, which
   * publishes nothing; a later caller gets the same instance and its {@link #base} unchanged).
   */
  public static SamplingControl shared(TraceRuntime runtime, Sampler initialBase) {
    Objects.requireNonNull(runtime, "runtime");
    synchronized (SamplingControl.class) {
      SamplingControl current = shared;
      if (current == null || current.runtime != runtime) {
        current = new SamplingControl(runtime, initialBase);
        shared = current;
      }
      return current;
    }
  }

  /** The configured sampler (without the caps). */
  public synchronized Sampler base() {
    return base;
  }

  /** The last throttle cap, or NaN before the first {@code Throttle}. */
  public synchronized float throttleCap() {
    return cap;
  }

  /** True while the overhead cap halves the configured normal rate. */
  public synchronized boolean overheadLowered() {
    return overheadLowered;
  }

  /** The normal rate the tracer uses now (the configured one under every cap). */
  public synchronized double effectiveNormalRate() {
    return effective().normalRate();
  }

  /**
   * A new configured sampler (remote {@code sampling.**} change, reload); the caps are re-applied.
   */
  public synchronized void base(Sampler value) {
    base = Objects.requireNonNull(value, "value");
    publish(effective());
  }

  /**
   * {@code Throttle.profile_normal_rate}: the normal rate is capped at {@code rate} from now on.
   */
  public synchronized void throttle(float rate) {
    cap = rate;
    publish(effective());
  }

  /**
   * The overhead cap (10 §13.1 {@code OverheadMonitor}, WO-19): {@code lowered} caps the normal
   * rate at {@value #OVERHEAD_FACTOR} of the configured one (and under the throttle cap); false
   * lifts it again.
   */
  public synchronized void overheadLowered(boolean lowered) {
    if (overheadLowered == lowered) {
      return;
    }
    overheadLowered = lowered;
    publish(effective());
  }

  private Sampler effective() {
    double max = cap;
    if (overheadLowered) {
      double own = base.normalRate() * OVERHEAD_FACTOR;
      max = Double.isNaN(max) ? own : Math.min(max, own);
    }
    return Double.isNaN(max) ? base : base.withMaxNormalRate(max);
  }

  private void publish(Sampler sampler) {
    TracerImpl tracer = runtime.tracerImpl();
    if (tracer != null) {
      tracer.setSampler(sampler);
    }
  }
}
