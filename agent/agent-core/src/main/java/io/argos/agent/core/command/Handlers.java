/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.proto.v1.CommandType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** The command handlers of {@code core.command} (10 §12.1, C-103). */
public final class Handlers {
  private Handlers() {}

  /**
   * {@code THREAD_DUMP}, {@code THREAD_STACK}, {@code SELF_DIAGNOSTICS} and {@code ENV_DUMP}.
   * {@code CONFIG_RELOAD} is added by the dispatcher wiring ({@code ReloadCommand}). The map is
   * unmodifiable; building it loads no JMX bean (handlers look them up on their first run).
   */
  public static Map<CommandType, CommandHandler> create(CommandEnv env) {
    Objects.requireNonNull(env, "env");
    EnumMap<CommandType, CommandHandler> handlers =
        new EnumMap<CommandType, CommandHandler>(CommandType.class);
    handlers.put(CommandType.THREAD_DUMP, new ThreadDumpCommand(env));
    handlers.put(CommandType.THREAD_STACK, new ThreadStackCommand(env));
    handlers.put(CommandType.SELF_DIAGNOSTICS, new SelfDiagCommand(env));
    handlers.put(CommandType.ENV_DUMP, new EnvDumpCommand(env));
    return Collections.unmodifiableMap(handlers);
  }
}
