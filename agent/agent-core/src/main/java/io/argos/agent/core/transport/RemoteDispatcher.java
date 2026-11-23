/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.ConfigPush;
import java.util.Map;

/**
 * Where the session hands the server's {@code ConfigPush} and {@code Command} messages and the
 * {@code ServerHello.config} of every established session (11 §8, 10 §12, WO-17). Every method is
 * called on {@code argos-io} and returns at once: the work runs on {@code argos-command} ({@code
 * CommandDispatcher}), and its answers go back through the {@link ResultSink} of the session that
 * received the message.
 */
public interface RemoteDispatcher {
  /**
   * Hands answer messages ({@code ConfigApplyResult}, {@code CommandResult} chunks) to the session
   * that received the request. Any thread, never blocks: the message is written on that session's
   * event loop if the session is still the current one there, otherwise it is dropped and counted
   * ({@code argos_agent_session_messages_dropped_total}); the server expires the request with
   * {@code session_ended} (ADR-0010 C-231 (3)).
   */
  interface ResultSink {
    void send(AgentMessage message);
  }

  /** A configuration push; exactly one {@code ConfigApplyResult} goes to {@code sink}. */
  void configPush(ConfigPush push, ResultSink sink);

  /** A command; its {@code CommandResult} chunks (at least one) go to {@code sink}. */
  void command(Command command, ResultSink sink);

  /**
   * The {@code ServerHello.config} of a newly established session (ADR-0010 C-231 (4)): the values
   * the server holds for this agent. Nothing is reported back (1.x).
   */
  void helloConfig(Map<String, String> config);
}
