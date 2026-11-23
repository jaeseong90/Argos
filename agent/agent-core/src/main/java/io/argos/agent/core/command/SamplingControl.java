/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import java.util.Objects;

/**
 * The profile sampler of the running agent from two inputs (WO-17, 11 §4.2, §6): the configured
 * sampler of {@code sampling.profile.*} (local configuration or a remote change, {@link #base}) and
 * the cap of the last server {@code Throttle.profile_normal_rate} ({@link #throttle}). The tracer
 * always gets {@code base.withMaxNormalRate(cap)}, so a remote change of {@code
 * sampling.profile.normal-rate} never lifts a throttle the server asked for, and a throttle never
 * raises the configured rate. Any agent thread ({@code argos-io}, {@code argos-sender}, {@code
 * argos-command}); the tracer publishes the immutable sampler through a volatile field.
 */
public final class SamplingControl {
  private final TraceRuntime runtime;
  private Sampler base;
  private float cap = Float.NaN;

  /** The control of {@code runtime}'s tracer with the configured sampler {@code base}. */
  public SamplingControl(TraceRuntime runtime, Sampler base) {
    this.runtime = Objects.requireNonNull(runtime, "runtime");
    this.base = Objects.requireNonNull(base, "base");
  }

  /** The configured sampler (without the throttle cap). */
  public synchronized Sampler base() {
    return base;
  }

  /** The last throttle cap, or NaN before the first {@code Throttle}. */
  public synchronized float throttleCap() {
    return cap;
  }

  /**
   * A new configured sampler (remote {@code sampling.**} change, reload); the cap is re-applied.
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

  private Sampler effective() {
    return Float.isNaN(cap) ? base : base.withMaxNormalRate(cap);
  }

  private void publish(Sampler sampler) {
    TracerImpl tracer = runtime.tracerImpl();
    if (tracer != null) {
      tracer.setSampler(sampler);
    }
  }
}
