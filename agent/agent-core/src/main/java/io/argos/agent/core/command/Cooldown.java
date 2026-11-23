/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.config.AgentConfig;
import io.argos.proto.v1.CommandType;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The agent-side cooldown of each command type (SEC-24, 10 §12.1, 16 §5.2): a command of a type
 * that last started less than its cooldown ago is answered {@code COOLDOWN} and not run.
 *
 * <ul>
 *   <li>{@code THREAD_DUMP}: {@code commands.thread-dump.cooldown-seconds} (5 s, read from the
 *       configuration in force at each check);
 *   <li>{@code THREAD_STACK}: 1 s (10 §12.1);
 *   <li>{@code SELF_DIAGNOSTICS}: 1 s (not in the design documents; the plan's assumption);
 *   <li>{@code ENV_DUMP}: 60 s (16 §5.2);
 *   <li>{@code CONFIG_RELOAD}: 60 s (10 §12.1 "분당 1회").
 * </ul>
 *
 * Only a command that is started counts ({@link #tryStart}); a rejected, expired or cooled-down one
 * does not. Time comes from an injected nanosecond clock. Used on {@code argos-command} only.
 */
final class Cooldown {
  static final long THREAD_STACK_SECONDS = 1;
  static final long SELF_DIAGNOSTICS_SECONDS = 1;
  static final long ENV_DUMP_SECONDS = 60;
  static final long CONFIG_RELOAD_SECONDS = 60;

  private final Supplier<AgentConfig> config;
  private final Map<CommandType, Long> lastStartNanos =
      new EnumMap<CommandType, Long>(CommandType.class);

  Cooldown(Supplier<AgentConfig> config) {
    this.config = config;
  }

  /** The cooldown of {@code type} in seconds (0: none). */
  long seconds(CommandType type) {
    switch (type) {
      case THREAD_DUMP:
        AgentConfig current = config == null ? null : config.get();
        return (current == null ? AgentConfig.defaults() : current).threadDumpCooldownSeconds();
      case THREAD_STACK:
        return THREAD_STACK_SECONDS;
      case SELF_DIAGNOSTICS:
        return SELF_DIAGNOSTICS_SECONDS;
      case ENV_DUMP:
        return ENV_DUMP_SECONDS;
      case CONFIG_RELOAD:
        return CONFIG_RELOAD_SECONDS;
      default:
        return 0;
    }
  }

  /**
   * True, and {@code type} starts its cooldown at {@code nowNanos}, when its last start is at least
   * its cooldown ago; false (nothing changes) while it cools down.
   */
  boolean tryStart(CommandType type, long nowNanos) {
    Long last = lastStartNanos.get(type);
    long cooldownNanos = TimeUnit.SECONDS.toNanos(seconds(type));
    if (last != null && nowNanos - last.longValue() < cooldownNanos) {
      return false;
    }
    lastStartNanos.put(type, Long.valueOf(nowNanos));
    return true;
  }

  /** Whole seconds until {@code type} may start again at {@code nowNanos} (at least 1). */
  long remainingSeconds(CommandType type, long nowNanos) {
    Long last = lastStartNanos.get(type);
    if (last == null) {
      return 0;
    }
    long left = TimeUnit.SECONDS.toNanos(seconds(type)) - (nowNanos - last.longValue());
    return Math.max(1L, (left + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
  }
}
