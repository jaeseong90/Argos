/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.registrations;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.admin.apps.AppInputs;
import io.argos.server.admin.apps.ApprovalService;
import io.argos.server.admin.token.IssuedToken;
import io.argos.server.api.Envelope;
import io.argos.server.api.apps.AppViews;
import io.argos.server.api.apps.AppsService;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository.PendingRegistration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The approval queue of automatic registration: {@code /api/v1/admin/pending-registrations*} (14
 * §4.14, F-S30; WO-26). Administrators only. An approval returns the new application's agent token
 * once ({@code Cache-Control: no-store} like every API response, 14 §7.4).
 */
@RestController
@RequestMapping("/api/v1/admin/pending-registrations")
@RequiresRole(Role.ADMIN)
public class PendingRegistrationsController {

  private final ApprovalService approvals;
  private final AppsService apps;

  public PendingRegistrationsController(ApprovalService approvals, AppsService apps) {
    this.approvals = approvals;
    this.apps = apps;
  }

  /**
   * A queued registration request.
   *
   * @param id request id
   * @param name the application name the agent sent
   * @param env its environment
   * @param host the agent's host
   * @param homeAppId the home application of the agent's token, or null
   * @param firstAttemptAt the first refused Hello
   * @param lastAttemptAt the latest refused Hello
   * @param attempts refused Hellos so far
   */
  @Schema(name = "PendingRegistration")
  public record PendingView(
      int id,
      @ArgosUntrusted String name,
      @ArgosUntrusted String env,
      @ArgosUntrusted String host,
      @JsonProperty("home_app_id") @Nullable Integer homeAppId,
      @JsonProperty("first_attempt_at") @Nullable Instant firstAttemptAt,
      @JsonProperty("last_attempt_at") @Nullable Instant lastAttemptAt,
      int attempts) {

    static PendingView of(PendingRegistration p) {
      return new PendingView(
          p.id(),
          p.name(),
          p.env(),
          p.host(),
          p.homeAppId(),
          p.firstAttemptAt(),
          p.lastAttemptAt(),
          p.attempts());
    }
  }

  /**
   * {@code POST .../{id}/approve}.
   *
   * @param group group of the new application, or null
   * @param apdexTMs its Apdex T, or null (500)
   */
  @Schema(name = "ApproveRegistrationRequest")
  public record ApproveRequest(
      @Nullable String group, @JsonProperty("apdex_t_ms") @Nullable Integer apdexTMs) {}

  /**
   * An agent token, shown once.
   *
   * @param tokenId token id (UUIDv4)
   * @param token the token: configure it as the agent's {@code server.token}; never shown again
   * @param prefix its first 14 characters, the only part shown again
   * @param expiresAt expiry, or null
   */
  @Schema(name = "ApprovedRegistrationToken")
  public record TokenOnce(
      @JsonProperty("token_id") String tokenId,
      @Sensitive String token,
      String prefix,
      @JsonProperty("expires_at") @Nullable Instant expiresAt) {

    static TokenOnce of(IssuedToken t) {
      return new TokenOnce(t.tokenId().toString(), t.token(), t.prefix(), t.expiresAt());
    }

    /** The prefix only, never the token. */
    @Override
    public String toString() {
      return "TokenOnce[tokenId=" + tokenId + ", prefix=" + prefix + "]";
    }
  }

  /**
   * The answer of an approval: the application and its token, shown once.
   *
   * @param app the application
   * @param token its new agent token
   */
  @Schema(name = "ApprovedRegistration")
  public record Approved(AppViews.App app, TokenOnce token) {}

  /** The queued requests (14 §4.14). */
  @GetMapping
  @Operation(
      operationId = "admin_pending_registrations_list",
      summary = "Queued registration requests",
      description =
          "Hellos of an application (name, env) that the server did not register automatically"
              + " (12 §4.4: its environment does not register automatically, or the token's home"
              + " application has 8 automatic variants), most recently attempted first, at most"
              + " 1,000. env filters by environment.")
  @ArgosLimits(rowsMax = PendingRegistrationRepository.MAX_ROWS)
  @ArgosSource({"postgres:pending_registrations"})
  public Envelope<List<PendingView>> list(
      @RequestParam(value = "env", required = false) String env) {
    String e = QueryLimits.text("env", env);
    List<PendingRegistration> rows = approvals.list(e == null || e.isBlank() ? null : e.strip());
    return Envelope.of(rows.stream().map(PendingView::of).toList());
  }

  /** Approves a request (14 §4.14). */
  @PostMapping("/{id}/approve")
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit({AuditEvent.APP_APPROVED, AuditEvent.TOKEN_ISSUED})
  @Operation(
      operationId = "admin_pending_registrations_approve",
      summary = "Approve a registration request",
      description =
          "Creates the application as an environment variant of the token's home application"
              + " (the home application's agents are accepted on their next retry), applies group"
              + " and apdex_t_ms and issues one agent token of the new application, returned in"
              + " this response only. 404 for an unknown request, 409 already_exists when (name,"
              + " env) belongs to another application, 409 invalid_state when the request has no"
              + " home application. 409 invalid_state with the details approval: applied,"
              + " token: not_issued and app_id: token_limit when the application has 5 active"
              + " tokens already: the approval stays applied (the request is gone, the"
              + " application exists) and no token is issued; issue one with POST"
              + " /admin/apps/{appId}/tokens after revoking another.")
  public Envelope<Approved> approve(
      @PathVariable("id") int id,
      @RequestBody(required = false) ApproveRequest body,
      HttpServletRequest request) {
    String group = body == null ? null : AppInputs.group(body.group());
    Integer t =
        body == null || body.apdexTMs() == null
            ? null
            : AppInputs.apdexT("apdex_t_ms", body.apdexTMs());
    ArgosPrincipal p = principal();
    ApprovalService.Approved a =
        approvals.approve(requireId(id), group, t, p.userId(), Actor.user(p, request));
    return Envelope.of(new Approved(apps.views(List.of(a.app())).get(0), TokenOnce.of(a.token())));
  }

  /** Rejects a request (14 §4.14). */
  @PostMapping("/{id}/reject")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.APP_REJECTED)
  @Operation(
      operationId = "admin_pending_registrations_reject",
      summary = "Reject a registration request",
      description =
          "Deletes the request; the agent's next retry queues it again (12 §4.4 keeps no deny"
              + " list). 404 for an unknown request.")
  public void reject(@PathVariable("id") int id, HttpServletRequest request) {
    approvals.reject(requireId(id), Actor.user(principal(), request));
  }

  private static int requireId(int id) {
    if (id <= 0) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    return id;
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }
}
