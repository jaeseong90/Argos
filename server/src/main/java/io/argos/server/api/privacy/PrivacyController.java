/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.privacy;

import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.config.CidrRange;
import io.argos.server.privacy.deletion.DeletionJobService;
import io.argos.server.privacy.deletion.DeletionJobService.RequestException;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The privacy deletion API (SEC-33, F-S32; 14 §4.14, 16 §6.8, 13 §9), administrators only: a
 * request queues a job that deletes the matching transactions ({@code xlog}) and their steps
 * ({@code profile_step}) of a user id or a client address over at most {@code
 * argos.privacy.deletion.max-range} (30 days); rollups are aggregates and stay. Limited to {@value
 * io.argos.server.web.security.RateLimiter#PRIVACY_DELETES_PER_HOUR} requests per user and hour
 * (429 with {@code Retry-After}, 16 §7.13, ADR-0010 C-136 (2)).
 *
 * <p>The subject value travels in the request body only ({@code POST /admin/privacy/deletions},
 * ADR-0010 C-241 (3)): a query string would land in the access logs of the server and of every
 * proxy on the way. It never reaches a log, an error body, the audit log or PostgreSQL ({@code
 * privacy_jobs} and the audit record keep its SHA-256 only). A user id is normalised like the
 * search (C-163 (2)); an address must be an IP literal (never resolved).
 */
@RestController
@RequiresRole(Role.ADMIN)
public class PrivacyController {

  private final DeletionJobService deletions;

  public PrivacyController(DeletionJobService deletions) {
    this.deletions = deletions;
  }

  /** Queues a deletion of a user id or a client address. */
  @PostMapping("/api/v1/admin/privacy/deletions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @RateLimited(RateLimitBucket.PRIVACY_DELETE)
  @ArgosAudit(AuditEvent.PRIVACY_DELETE_REQUESTED)
  @ArgosLimits(rangeMax = "30d")
  @Operation(
      summary = "Delete the transactions of a user id or a client address",
      description =
          "Exactly one of user_id and ip, in the request body only (never in the URL, SEC-33)."
              + " Queues a job (GET /admin/privacy/jobs/{jobId}); rollups are not changed. 5"
              + " requests per user and hour.")
  public Envelope<PrivacyDtos.DeletionAccepted> delete(
      @RequestBody PrivacyDtos.DeletionRequest body, HttpServletRequest request) {
    String userId = body.userId();
    String ip = body.ip();
    String from = body.from();
    String to = body.to();
    Integer appId = body.appId();
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    boolean hasUser = userId != null && !userId.isEmpty();
    boolean hasIp = ip != null && !ip.isEmpty();
    if (hasUser == hasIp) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Give exactly one of user_id and ip")
          .detail("subject", "exactly_one");
    }
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    Actor actor = Actor.user(principal, request);
    UUID by = principal.userId();
    PrivacyJob job;
    try {
      if (hasUser) {
        if (userId.length() > 4 * QueryLimits.USER_ID_MAX_CHARS) {
          throw new ApiException(ErrorCode.FILTER_INVALID, "user_id is too long")
              .detail("user_id", "too_long");
        }
        String normalized = QueryLimits.userIdFilter(userId);
        if (normalized == null) {
          throw new ApiException(ErrorCode.FILTER_INVALID, "user_id is empty")
              .detail("user_id", "empty");
        }
        job = deletions.submitUserId(normalized, appId, f, t, by, actor);
      } else {
        InetAddress address = ip.length() > 64 ? null : CidrRange.literal(ip).orElse(null);
        if (address == null) {
          throw new ApiException(ErrorCode.FILTER_INVALID, "ip must be an IPv4 or IPv6 literal")
              .detail("ip", "invalid");
        }
        job = deletions.submitIp(address, appId, f, t, by, actor);
      }
    } catch (RequestException e) {
      throw switch (e.reason()) {
        case "before_from" ->
            new ApiException(ErrorCode.RANGE_INVALID, e.getMessage()).detail("to", "before_from");
        case "range_too_long" ->
            new ApiException(ErrorCode.RANGE_INVALID, e.getMessage()).detail("to", "too_long");
        case "app_not_found" -> new ApiException(ErrorCode.NOT_FOUND, e.getMessage());
        case "queue_full" -> new ApiException(ErrorCode.STORAGE_UNAVAILABLE, e.getMessage());
        default -> new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
      };
    }
    return Envelope.of(PrivacyDtos.DeletionAccepted.of(job));
  }

  /** The state of a deletion job (14 §4.14). */
  @GetMapping("/api/v1/admin/privacy/jobs/{jobId}")
  @Operation(summary = "The state of a privacy deletion job")
  public Envelope<PrivacyDtos.PrivacyJobView> job(@PathVariable("jobId") String jobId) {
    UUID id = ObjectAccessPolicy.requireUuidV4(jobId, "jobId");
    return Envelope.of(
        PrivacyDtos.PrivacyJobView.of(
            deletions
                .find(id)
                .orElseThrow(
                    () -> new ApiException(ErrorCode.NOT_FOUND, "The job does not exist"))));
  }
}
