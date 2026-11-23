/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import java.util.Map;

/**
 * Executes one kind of server command (10 §12.1, 11 §8.2, C-103). Called on the {@code
 * argos-command} agent thread only, never on an application thread, so a handler may take its time
 * (bounded by the work it does) and may look up JMX beans; it never runs arbitrary code (the
 * command list is the closed {@code CommandType} enum, SEC-91).
 *
 * <p>Cooldowns, the TTL, chunking and the one-at-a-time rule belong to the dispatcher; a handler
 * only executes and caps its own payload ({@link CommandOutput} is already within its cap).
 */
public interface CommandHandler {
  /**
   * Argument key under which the dispatcher may pass the {@code command_id} of the command being
   * run (the server never sends this key: server arguments are {@code tx_id} and {@code
   * thread_id}). Handlers use it only to name temporary files; a missing or malformed value is
   * replaced by a local counter.
   */
  String COMMAND_ID_ARG = "argos.command_id";

  /**
   * Runs the command with the server's {@code args} (at most 8 entries of at most 256 bytes, 11
   * §4.2). A thrown exception becomes {@code FAILED} with its message only (no stack trace, no
   * collected data, no secret values).
   */
  CommandOutput run(Map<String, String> args) throws Exception;
}
