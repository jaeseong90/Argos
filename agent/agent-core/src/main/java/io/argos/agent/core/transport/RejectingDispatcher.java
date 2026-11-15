/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AllowList;
import io.argos.agent.core.config.RemoteConfigGate;
import io.argos.agent.core.log.AgentLogger;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The {@link RemoteDispatcher} of WO-11: receives, evaluates and rejects; nothing is applied or
 * executed (remote configuration and commands are WO-17).
 *
 * <ul>
 *   <li>{@code ConfigPush}: every pushed key is evaluated against the local hard cap first ({@link
 *       RemoteConfigGate} with {@code remote-config.allow}, SEC-23, ADR-0010 C-118), and every key
 *       is answered {@code NOT_ALLOWED}: a key the gate rejects with the gate's reason ({@code
 *       immutable}, {@code not_allowed}, {@code unknown_key}, {@code invalid_value}, {@code
 *       reduce_masking}), a key it would allow with "not applied by this agent version". {@code
 *       applied_keys} stays empty and {@code effective_config_hash} is the unchanged hash.
 *   <li>{@code Command}: {@code REJECTED} for every type (11 §8.2 closed list; unknown values
 *       included), one chunk, no payload.
 * </ul>
 *
 * Messages carry key names and constant reasons only, never pushed values (they may be anything).
 * One WARN per minute at most notes that pushes or commands were rejected.
 */
final class RejectingDispatcher implements RemoteDispatcher {
  static final String NOT_APPLIED =
      "not applied: this agent version does not apply remote configuration";
  static final String COMMAND_REJECTED = "rejected: this agent version executes no commands";
  static final long WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);
  static final int MAX_REJECTED_KEYS = 1_000;

  private final RemoteConfigGate gate;
  private final AllowList allow;
  private final String configHash;
  private long lastWarnNanos;
  private boolean warned;

  RejectingDispatcher(AgentConfig config, String configHash) {
    this.gate = RemoteConfigGate.of(config);
    this.allow = AllowList.of(config.remoteConfigAllow());
    this.configHash = configHash == null ? "" : configHash;
  }

  @Override
  public ConfigApplyResult configPush(ConfigPush push) {
    ConfigApplyResult.Builder result =
        ConfigApplyResult.newBuilder()
            .setChangeId(push.getChangeId())
            .setEffectiveConfigHash(configHash);
    for (Map.Entry<String, String> entry : push.getValuesMap().entrySet()) {
      if (result.getRejectedCount() >= MAX_REJECTED_KEYS) {
        break;
      }
      RemoteConfigGate.Decision decision = gate.evaluate(entry.getKey(), entry.getValue(), allow);
      String message =
          decision.applies()
              ? NOT_APPLIED
              : "rejected by the agent's hard cap: " + decision.reason();
      result.addRejected(
          RejectedKey.newBuilder()
              .setKey(Utf8.cut(entry.getKey(), 256))
              .setCode(RejectCode.NOT_ALLOWED)
              .setMessage(message));
    }
    warn(true);
    return result.build();
  }

  @Override
  public List<CommandResult> command(Command command) {
    warn(false);
    return Collections.singletonList(
        CommandResult.newBuilder()
            .setCommandId(command.getCommandId())
            .setStatus(CommandStatus.REJECTED)
            .setChunkIndex(0)
            .setChunkCount(1)
            .setError(COMMAND_REJECTED)
            .build());
  }

  private void warn(boolean push) {
    long now = System.nanoTime();
    if (warned && now - lastWarnNanos < WARN_INTERVAL_NANOS) {
      return;
    }
    warned = true;
    lastWarnNanos = now;
    if (push) {
      AgentLogger.warn(
          "collector pushed a remote configuration change; this agent version applies none"
              + " (every key was answered NOT_ALLOWED)");
    } else {
      AgentLogger.warn(
          "collector sent a remote command; this agent version executes none (answered"
              + " REJECTED)");
    }
  }
}
