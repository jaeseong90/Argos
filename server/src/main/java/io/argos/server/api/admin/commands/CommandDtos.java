/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.commands;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.admin.command.CommandRouter;
import io.argos.server.admin.command.ThreadDump;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.storage.postgres.command.CommandStore.CommandRow;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Request and response bodies of the command API (14 §4.10; WO-26). */
public final class CommandDtos {

  private CommandDtos() {}

  /**
   * {@code POST /instances/{instanceId}/commands}.
   *
   * @param type {@code thread_dump}, {@code thread_stack}, {@code self_diagnosis}, {@code
   *     reload_config} or {@code env_dump} ({@code env_dump} and {@code reload_config}: admin)
   * @param args {@code thread_stack} only: exactly one of {@code thread_id}, {@code tx_id} (digits)
   */
  @Schema(name = "CommandRequest")
  public record CommandRequest(
      @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
      @JsonProperty("args") @Nullable Map<String, String> args) {}

  /** {@code 202 {command_id, status: queued, ttl_s}}. */
  @Schema(name = "CommandQueued")
  public record CommandQueued(
      @JsonProperty("command_id") @Schema(requiredMode = RequiredMode.REQUIRED) UUID commandId,
      @JsonProperty("status") @Schema(requiredMode = RequiredMode.REQUIRED) String status,
      @JsonProperty("ttl_s") @Schema(requiredMode = RequiredMode.REQUIRED) int ttlS) {

    static CommandQueued of(CommandRouter.Issued issued) {
      return new CommandQueued(issued.commandId(), issued.status(), issued.ttlS());
    }
  }

  /**
   * A command ({@code GET /commands/{commandId}}, {@code GET /instances/{instanceId}/commands}).
   *
   * @param status {@code queued}, {@code sent}, {@code done}, {@code failed} or {@code expired}
   * @param error why it did not finish {@code done}: {@code rejected}, {@code cooldown}, {@code
   *     agent_failed}, {@code agent_expired}, {@code ttl} (never delivered), {@code timeout},
   *     {@code session_ended}, {@code invalid_result}; null otherwise
   * @param resultSize bytes of the stored result (uncompressed), null without one
   * @param truncated the agent cut the result at 1 MiB, null without a result
   * @param requestedByName the requester's user name for the command history (WO-37, 05 §2.7-4):
   *     {@code deleted-<id>} for a deleted user (ADR-0010 C-231 (5)), null when unknown
   */
  @Schema(name = "CommandView")
  public record CommandView(
      @JsonProperty("command_id") @Schema(requiredMode = RequiredMode.REQUIRED) UUID commandId,
      @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("args") @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, String> args,
      @JsonProperty("requested_by") @Schema(requiredMode = RequiredMode.REQUIRED) UUID requestedBy,
      @JsonProperty("requested_at") @Schema(requiredMode = RequiredMode.REQUIRED)
          Instant requestedAt,
      @JsonProperty("expires_at") @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
      @JsonProperty("status") @Schema(requiredMode = RequiredMode.REQUIRED) String status,
      @JsonProperty("sent_at") @Nullable Instant sentAt,
      @JsonProperty("finished_at") @Nullable Instant finishedAt,
      @JsonProperty("duration_ms") @Nullable Integer durationMs,
      @JsonProperty("result_size") @Nullable Integer resultSize,
      @JsonProperty("truncated") @Nullable Boolean truncated,
      @JsonProperty("error") @Nullable String error,
      @Schema(
              description =
                  "The requester's user name; deleted-<id> for a deleted user, null when unknown")
          @JsonProperty("requested_by_name")
          @ArgosUntrusted
          @Nullable String requestedByName) {

    static CommandView of(CommandRow r) {
      return new CommandView(
          r.id(),
          r.type(),
          r.instanceId(),
          r.args(),
          r.requestedBy(),
          r.requestedAt(),
          r.expiresAt(),
          r.status().toLowerCase(Locale.ROOT),
          r.sentAt(),
          r.finishedAt(),
          r.durationMs(),
          r.resultSize(),
          r.truncated(),
          r.errorCode(),
          r.requestedByName());
    }
  }

  /**
   * A command result ({@code GET /commands/{commandId}/result}): a thread dump or stack in {@code
   * json} format has {@code threads} and {@code deadlocks}; every other result, and {@code
   * format=text}, has {@code text}. Collected data: shown as text only, never as markup.
   *
   * @param format {@code json} or {@code text}
   * @param contentType what the agent sent: {@code text/plain} or {@code application/json}
   * @param truncated cut at 1 MiB by the agent, or by the parser's bounds
   * @param sizeBytes bytes of the result
   * @param threads the parsed threads, null when not parsed
   * @param deadlocks the deadlock cycles, null when not parsed
   * @param text the result as UTF-8 text, null when parsed
   */
  @Schema(name = "CommandResultView")
  public record CommandResultView(
      @JsonProperty("command_id") @Schema(requiredMode = RequiredMode.REQUIRED) UUID commandId,
      @JsonProperty("type") @Schema(requiredMode = RequiredMode.REQUIRED) String type,
      @JsonProperty("instance_id") @Schema(requiredMode = RequiredMode.REQUIRED) int instanceId,
      @JsonProperty("format") @Schema(requiredMode = RequiredMode.REQUIRED) String format,
      @JsonProperty("content_type") @Schema(requiredMode = RequiredMode.REQUIRED)
          String contentType,
      @JsonProperty("truncated") @Schema(requiredMode = RequiredMode.REQUIRED) boolean truncated,
      @JsonProperty("size_bytes") @Schema(requiredMode = RequiredMode.REQUIRED) int sizeBytes,
      @JsonProperty("threads") @Nullable List<ThreadView> threads,
      @JsonProperty("deadlocks") @Nullable List<DeadlockView> deadlocks,
      @JsonProperty("text") @ArgosUntrusted @Nullable String text) {

    static CommandResultView of(CommandRouter.ResultView v) {
      ThreadDump dump = v.threads();
      return new CommandResultView(
          v.command().id(),
          v.kind().wire(),
          v.command().instanceId(),
          v.format(),
          v.contentType(),
          v.truncated() || (dump != null && dump.cut()),
          v.sizeBytes(),
          dump == null ? null : dump.threads().stream().map(ThreadView::of).toList(),
          dump == null ? null : dump.deadlocks().stream().map(DeadlockView::of).toList(),
          v.text());
    }
  }

  /**
   * One thread of a dump (14 §4.10).
   *
   * @param id the JVM thread id, null when the dump has none
   * @param txId the Argos transaction running on it, null when none
   */
  @Schema(name = "CommandThread")
  public record ThreadView(
      @JsonProperty("id") @Nullable Long id,
      @JsonProperty("name") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          String name,
      @JsonProperty("state") @ArgosUntrusted @Nullable String state,
      @JsonProperty("virtual") @Schema(requiredMode = RequiredMode.REQUIRED) boolean virtual,
      @JsonProperty("locks_held") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> locksHeld,
      @JsonProperty("waiting_on") @ArgosUntrusted @Nullable String waitingOn,
      @JsonProperty("frames") @ArgosUntrusted @Schema(requiredMode = RequiredMode.REQUIRED)
          List<String> frames,
      @JsonProperty("tx_id") @Nullable String txId) {

    static ThreadView of(ThreadDump.ThreadInfo t) {
      return new ThreadView(
          t.id(),
          t.name(),
          t.state(),
          t.virtual(),
          t.locksHeld(),
          t.waitingOn(),
          t.frames(),
          t.txId());
    }
  }

  /** One deadlock cycle: the ids of its threads. */
  @Schema(name = "CommandDeadlock")
  public record DeadlockView(
      @JsonProperty("thread_ids") @Schema(requiredMode = RequiredMode.REQUIRED)
          List<Long> threadIds) {

    static DeadlockView of(ThreadDump.Deadlock d) {
      return new DeadlockView(d.threadIds());
    }
  }
}
