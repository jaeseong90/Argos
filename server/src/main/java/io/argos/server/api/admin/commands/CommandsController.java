/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.commands;

import io.argos.server.admin.command.CommandRouter;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The remote command API (14 §4.10, 16 §5.1-§5.3, SEC-21 to SEC-24, SEC-50; WO-26): request a
 * command of an instance, read its state, read its decrypted result, list the commands of an
 * instance. Command ids are UUIDv4 (16 §7.11); a command and its result are visible to the
 * requester and to administrators only, an administrator command's result to administrators only.
 * Every {@code /api/**} response is {@code Cache-Control: no-store} ({@code
 * SecurityHeadersFilter}).
 */
@RestController
public class CommandsController {

  /** Commands listed by default. */
  static final int DEFAULT_LIST = 50;

  private final CommandRouter commands;

  public CommandsController(CommandRouter commands) {
    this.commands = commands;
  }

  /** Requests a command. */
  @PostMapping("/api/v1/instances/{instanceId}/commands")
  @RequiresRole(Role.OPERATOR)
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ArgosAudit({AuditEvent.COMMAND_REQUESTED, AuditEvent.COMMAND_REJECTED})
  @Operation(
      operationId = "commands_create",
      summary = "Request a command of an instance",
      description =
          "Queues one command of the closed list (thread_dump, thread_stack, self_diagnosis:"
              + " operator; reload_config, env_dump: admin, 403 otherwise) and sends it on the"
              + " instance's agent session. thread_stack takes exactly one of args.thread_id,"
              + " args.tx_id (digits); the others take no args. 409 instance_offline without a"
              + " live session, 409 invalid_state while another command of the instance runs,"
              + " 429 rate_limited (Retry-After) beyond 10 per instance per minute, 409"
              + " secret_unavailable without a master key (results are stored encrypted). A"
              + " command not sent within ttl_s expires and is never delivered; poll GET"
              + " /commands/{commandId}.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:commands", "memory:sessions"})
  public Envelope<CommandDtos.CommandQueued> create(
      @PathVariable("instanceId") int instanceId,
      @RequestBody CommandDtos.CommandRequest body,
      HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("type", "required");
    }
    ArgosPrincipal principal = principal();
    return Envelope.of(
        CommandDtos.CommandQueued.of(
            commands.issue(
                principal, Actor.user(principal, request), instanceId, body.type(), body.args())));
  }

  /** A command's state. */
  @GetMapping("/api/v1/commands/{commandId}")
  @RequiresRole(Role.VIEWER)
  @Operation(
      operationId = "commands_get",
      summary = "A command's state",
      description =
          "The requester's own command, or any command for an administrator (403 not_owner"
              + " otherwise, 404 when it does not exist). status: queued, sent, done, failed,"
              + " expired.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:commands", "postgres:command_results"})
  public Envelope<CommandDtos.CommandView> get(@PathVariable("commandId") String commandId) {
    return Envelope.of(
        CommandDtos.CommandView.of(
            commands.find(principal(), ObjectAccessPolicy.requireUuidV4(commandId, "commandId"))));
  }

  /** A command's decrypted result. */
  @GetMapping("/api/v1/commands/{commandId}/result")
  @RequiresRole(Role.VIEWER)
  @ArgosAudit({AuditEvent.COMMAND_RESULT_VIEWED, AuditEvent.SENSITIVE_VIEW})
  @Operation(
      operationId = "commands_result",
      summary = "A command's result",
      description =
          "The decrypted result of a done command for its requester or an administrator (the"
              + " result of reload_config and env_dump for administrators only). format=json"
              + " (default) parses a thread dump or stack into threads[] and deadlocks[], other"
              + " results come as text; format=text returns every result as text. Viewing is"
              + " audited (COMMAND_RESULT_VIEWED; SENSITIVE_VIEW for env_dump and thread dumps)."
              + " 409 invalid_state when the command has no result.")
  @ArgosLimits(timeoutS = 10)
  @ArgosSource({"postgres:commands", "postgres:command_results"})
  public Envelope<CommandDtos.CommandResultView> result(
      @PathVariable("commandId") String commandId,
      @Parameter(description = "json (default) or text")
          @RequestParam(value = "format", required = false)
          String format,
      HttpServletRequest request) {
    ArgosPrincipal principal = principal();
    return Envelope.of(
        CommandDtos.CommandResultView.of(
            commands.result(
                principal,
                Actor.user(principal, request),
                ObjectAccessPolicy.requireUuidV4(commandId, "commandId"),
                format)));
  }

  /** The commands of an instance. */
  @GetMapping("/api/v1/instances/{instanceId}/commands")
  @RequiresRole(Role.OPERATOR)
  @Operation(
      operationId = "commands_list",
      summary = "Commands of an instance",
      description =
          "The newest commands of the instance (limit default 50, at most 100), every requester;"
              + " results are read through GET /commands/{commandId}/result.")
  @ArgosLimits(rowsMax = CommandRouter.MAX_LIST, timeoutS = 10)
  @ArgosSource({"postgres:commands", "postgres:command_results"})
  public Envelope<List<CommandDtos.CommandView>> list(
      @PathVariable("instanceId") int instanceId,
      @Parameter(description = "Rows, at most 100 (default 50)")
          @RequestParam(value = "limit", required = false)
          Integer limit) {
    int n = limit == null ? DEFAULT_LIST : limit;
    if (n < 1 || n > CommandRouter.MAX_LIST) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED).detail("limit", "out_of_range");
    }
    return Envelope.of(
        commands.list(instanceId, n).stream().map(CommandDtos.CommandView::of).toList());
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
