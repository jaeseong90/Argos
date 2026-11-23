/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import io.argos.proto.v1.CommandType;
import io.argos.server.auth.Role;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The closed list of remote commands (11 §8.2 {@code CommandType}, 16 §1.4, §5.1; 14 §4.10 API
 * names): nothing outside this enum can be requested or sent (SEC-91). The role is the minimum to
 * request the command (operator: thread dump, thread stack, self diagnosis; administrator: config
 * reload, environment dump, SEC-21).
 */
public enum CommandKind {
  /** Full thread dump. */
  THREAD_DUMP("thread_dump", CommandType.THREAD_DUMP, Role.OPERATOR, "thread_dump"),
  /** One thread's stack ({@code thread_id} or {@code tx_id}). */
  THREAD_STACK("thread_stack", CommandType.THREAD_STACK, Role.OPERATOR, "thread_dump"),
  /** The agent's self diagnosis report. */
  SELF_DIAGNOSIS("self_diagnosis", CommandType.SELF_DIAGNOSTICS, Role.OPERATOR, null),
  /** Reload of the agent's local configuration file. */
  RELOAD_CONFIG("reload_config", CommandType.CONFIG_RELOAD, Role.ADMIN, null),
  /** Environment variables and system properties, masked by the agent (SEC-20). */
  ENV_DUMP("env_dump", CommandType.ENV_DUMP, Role.ADMIN, "env_dump");

  private final String wire;
  private final CommandType proto;
  private final Role role;
  private final @Nullable String sensitiveView;

  CommandKind(String wire, CommandType proto, Role role, @Nullable String sensitiveView) {
    this.wire = wire;
    this.proto = proto;
    this.role = role;
    this.sensitiveView = sensitiveView;
  }

  /** The API and storage name ({@code commands.type}). */
  public String wire() {
    return wire;
  }

  /** The protocol value. */
  public CommandType proto() {
    return proto;
  }

  /** The minimum role that may request it. */
  public Role role() {
    return role;
  }

  /**
   * The {@code SENSITIVE_VIEW} type of a result view (16 §5.7: {@code env_dump}, {@code
   * thread_dump}), null when viewing its result is not a sensitive view.
   */
  public @Nullable String sensitiveView() {
    return sensitiveView;
  }

  /** Whether the result shows threads ({@code threads[]}, {@code deadlocks[]}). */
  public boolean threads() {
    return this == THREAD_DUMP || this == THREAD_STACK;
  }

  /** The kind of an API name. */
  public static Optional<CommandKind> of(@Nullable String wire) {
    if (wire == null) {
      return Optional.empty();
    }
    for (CommandKind k : values()) {
      if (k.wire.equals(wire)) {
        return Optional.of(k);
      }
    }
    return Optional.empty();
  }
}
