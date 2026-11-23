/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.EffectiveConfig;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.trace.TraceRuntime;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * What the command handlers read (filled by the wiring in {@code AgentStarter}): agent identity,
 * the recording runtime (active transactions), the self-diagnostics collector, the agent log
 * directory (temporary thread-dump files) and suppliers of the current configuration snapshot,
 * effective configuration, remote allow list, configuration hash, clock offset and extra values
 * (queue lengths, transport state). Every supplier is read on {@code argos-command} at the time of
 * the command, so a remote configuration change is visible to the next command. Any part may be
 * missing (unit tests); handlers then leave the matching items out.
 */
public final class CommandEnv {
  private final AgentInfo info;
  private final TraceRuntime runtime;
  private final SelfDiagnosticsCollector diagnostics;
  private final File logDir;
  private final Supplier<AgentConfig> config;
  private final Supplier<EffectiveConfig> effective;
  private final Supplier<AllowList> allow;
  private final Supplier<String> configHash;
  private final LongSupplier clockOffsetMillis;
  private final Supplier<Map<String, Object>> extras;

  private CommandEnv(Builder b) {
    this.info = b.info;
    this.runtime = b.runtime;
    this.diagnostics = b.diagnostics;
    this.logDir = b.logDir;
    this.config = b.config;
    this.effective = b.effective;
    this.allow = b.allow;
    this.configHash = b.configHash;
    this.clockOffsetMillis = b.clockOffsetMillis;
    this.extras = b.extras;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Agent identity, or null. */
  public AgentInfo info() {
    return info;
  }

  /** The recording runtime, or null. */
  public TraceRuntime runtime() {
    return runtime;
  }

  /** The self-diagnostics collector, or null. */
  public SelfDiagnosticsCollector diagnostics() {
    return diagnostics;
  }

  /** The agent log directory ({@code log.dir}), or null for the JVM temporary directory. */
  public File logDir() {
    return logDir;
  }

  /** The current configuration snapshot, or null. Never throws. */
  public AgentConfig config() {
    return get(config);
  }

  /** The current effective configuration with sources, or null. Never throws. */
  public EffectiveConfig effective() {
    return get(effective);
  }

  /** The current remote allow list, or null. Never throws. */
  public AllowList allow() {
    return get(allow);
  }

  /** The current effective configuration hash, or null. Never throws. */
  public String configHash() {
    return get(configHash);
  }

  /** True when a clock offset supplier was given. */
  public boolean hasClockOffset() {
    return clockOffsetMillis != null;
  }

  /** The current clock offset to the server in milliseconds (0 without a supplier). */
  public long clockOffsetMillis() {
    if (clockOffsetMillis == null) {
      return 0L;
    }
    try {
      return clockOffsetMillis.getAsLong();
    } catch (RuntimeException e) {
      return 0L;
    }
  }

  /** Extra values (names to numbers, strings or booleans); empty when none. Never throws. */
  public Map<String, Object> extras() {
    Map<String, Object> values = get(extras);
    return values == null ? Collections.<String, Object>emptyMap() : values;
  }

  private static <T> T get(Supplier<T> supplier) {
    if (supplier == null) {
      return null;
    }
    try {
      return supplier.get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Builder; every part is optional. */
  public static final class Builder {
    private AgentInfo info;
    private TraceRuntime runtime;
    private SelfDiagnosticsCollector diagnostics;
    private File logDir;
    private Supplier<AgentConfig> config;
    private Supplier<EffectiveConfig> effective;
    private Supplier<AllowList> allow;
    private Supplier<String> configHash;
    private LongSupplier clockOffsetMillis;
    private Supplier<Map<String, Object>> extras;

    Builder() {}

    public Builder info(AgentInfo value) {
      this.info = value;
      return this;
    }

    public Builder runtime(TraceRuntime value) {
      this.runtime = value;
      return this;
    }

    public Builder diagnostics(SelfDiagnosticsCollector value) {
      this.diagnostics = value;
      return this;
    }

    public Builder logDir(File value) {
      this.logDir = value;
      return this;
    }

    public Builder config(Supplier<AgentConfig> value) {
      this.config = value;
      return this;
    }

    public Builder effective(Supplier<EffectiveConfig> value) {
      this.effective = value;
      return this;
    }

    public Builder allow(Supplier<AllowList> value) {
      this.allow = value;
      return this;
    }

    public Builder configHash(Supplier<String> value) {
      this.configHash = value;
      return this;
    }

    public Builder clockOffsetMillis(LongSupplier value) {
      this.clockOffsetMillis = value;
      return this;
    }

    public Builder extras(Supplier<Map<String, Object>> value) {
      this.extras = value;
      return this;
    }

    public CommandEnv build() {
      return new CommandEnv(this);
    }
  }
}
