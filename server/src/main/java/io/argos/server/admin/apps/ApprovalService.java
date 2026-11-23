/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.apps;

import io.argos.server.admin.token.IssuedToken;
import io.argos.server.admin.token.TokenService;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.ingest.ApdexChangeService;
import io.argos.server.storage.postgres.app.AppAdminRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository.Approval;
import io.argos.server.storage.postgres.app.PendingRegistrationRepository.PendingRegistration;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The approval queue of automatic registration (14 §4.14, F-S30, 12 §4.4, ADR-0010 C-155 (3)):
 * list, approve, reject.
 *
 * <p>An approval creates the requested (name, env) as an environment variant of the token's home
 * application ({@link PendingRegistrationRepository#approve}: the home application's tokens are
 * accepted on their next retry), applies the optional group and Apdex T and issues one agent token
 * of the new application ({@link TokenService#issue}), returned once (14 §4.14 {@code {app,
 * token}}).
 */
@Service
public class ApprovalService {

  private final PendingRegistrationRepository queue;
  private final AppAdminRepository apps;
  private final CatalogRepository catalog;
  private final ApdexChangeService apdex;
  private final TokenService tokens;
  private final AuditService audit;

  public ApprovalService(
      PendingRegistrationRepository queue,
      AppAdminRepository apps,
      CatalogRepository catalog,
      ApdexChangeService apdex,
      TokenService tokens,
      AuditService audit) {
    this.queue = queue;
    this.apps = apps;
    this.catalog = catalog;
    this.apdex = apdex;
    this.tokens = tokens;
    this.audit = audit;
  }

  /**
   * An approved registration.
   *
   * @param app the application (new, or already the home application's variant)
   * @param token the token issued for it, shown once
   */
  public record Approved(AppRecord app, IssuedToken token) {}

  /** The queued requests, most recently attempted first (at most 1,000). */
  public List<PendingRegistration> list(@Nullable String env) {
    return queue.list(env);
  }

  /**
   * Approves request {@code id}.
   *
   * @param group the group to set, or null
   * @param apdexTMs the Apdex T to set, or null
   * @throws ApiException 404 for an unknown request, 409 {@code already_exists} when (name, env)
   *     belongs to another application, 409 {@code invalid_state} when the request has no home
   *     application (deleted, or a row older than V004), 409 {@code invalid_state} with the details
   *     {@code approval: applied, token: not_issued, app_id: token_limit} when the approval was
   *     applied but the application is at its token limit ({@link #approvedWithoutToken})
   */
  public Approved approve(
      int id,
      @Nullable String group,
      @Nullable Integer apdexTMs,
      @Nullable UUID userId,
      Actor actor) {
    Approval approval = queue.approve(id);
    switch (approval.outcome()) {
      case NOT_FOUND -> throw new ApiException(ErrorCode.NOT_FOUND);
      case NAME_TAKEN ->
          throw new ApiException(
                  ErrorCode.ALREADY_EXISTS,
                  "The name and environment belong to another application")
              .detail("name", "already_exists");
      case HOME_APP_MISSING ->
          throw new ApiException(
                  ErrorCode.INVALID_STATE, "The request has no home application any more")
              .detail("id", "home_app_missing");
      case APPROVED -> {}
    }
    int appId = approval.app().id();
    if (group != null) {
      apps.update(appId, true, group, null, null);
    }
    if (apdexTMs != null) {
      try {
        apdex.setAppT(appId, apdexTMs, userId);
      } catch (IllegalStateException e) {
        throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
      }
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("env", approval.app().env());
    after.put("request_id", id);
    if (approval.app().registeredFromAppId() > 0) {
      after.put("home_app_id", approval.app().registeredFromAppId());
    }
    if (group != null) {
      after.put("group", group);
    }
    if (apdexTMs != null) {
      after.put("apdex_t_ms", apdexTMs);
    }
    audit.record(
        AuditEvent.APP_APPROVED,
        actor,
        new Target("app", Integer.toString(appId), approval.app().name()),
        AuditResult.success(),
        null,
        after);
    IssuedToken token;
    try {
      token = tokens.issue(actor, appId, null);
    } catch (ApiException e) {
      if (e.errorCode() == ErrorCode.INVALID_STATE
          && e.details().contains(new ErrorBody.Detail("app_id", "token_limit"))) {
        throw approvedWithoutToken();
      }
      throw e;
    }
    AppRecord app = catalog.app(appId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return new Approved(app, token);
  }

  /**
   * The 409 of an approval whose token was not issued because the application already has its
   * maximum of active tokens (ADR-0010 C-231 (8)): the approval stays applied (the application
   * exists, the request is gone, the group and Apdex T are set, {@code APP_APPROVED} is audited),
   * which the body says with the details {@code approval: applied} and {@code token: not_issued}
   * next to the token service's {@code app_id: token_limit}.
   */
  static ApiException approvedWithoutToken() {
    return new ApiException(
            ErrorCode.INVALID_STATE,
            "The registration was approved, but no token was issued: the application has its"
                + " maximum of active agent tokens")
        .detail("approval", "applied")
        .detail("token", "not_issued")
        .detail("app_id", "token_limit");
  }

  /**
   * Rejects request {@code id}: the row is deleted (the agent's next retry queues it again).
   *
   * @throws ApiException 404 for an unknown request
   */
  public void reject(int id, Actor actor) {
    Optional<PendingRegistration> row = queue.find(id);
    if (row.isEmpty() || !queue.reject(id)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    PendingRegistration p = row.get();
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("env", p.env());
    if (p.homeAppId() != null) {
      after.put("home_app_id", p.homeAppId());
    }
    audit.record(
        AuditEvent.APP_REJECTED,
        actor,
        new Target("pending_registration", Integer.toString(id), p.name()),
        AuditResult.success(),
        null,
        after);
  }
}
