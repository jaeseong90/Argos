/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import java.util.List;

/**
 * Where the session hands the server's {@code ConfigPush} and {@code Command} messages (11 §8, 10
 * §12). Called on {@code argos-io}: an implementation must answer quickly and must not block (work
 * that takes time belongs on {@code argos-command}, WO-17); the session writes the returned
 * messages on the stream. WO-11 installs {@link RejectingDispatcher}; WO-17 replaces it with the
 * command executor and the remote configuration apply.
 */
public interface RemoteDispatcher {
  /** The {@code ConfigApplyResult} of {@code push} (never null). */
  ConfigApplyResult configPush(ConfigPush push);

  /** The {@code CommandResult} chunks of {@code command} (at least one). */
  List<CommandResult> command(Command command);
}
