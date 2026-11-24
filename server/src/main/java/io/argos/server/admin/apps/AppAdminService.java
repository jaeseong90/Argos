/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.apps;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.SessionClose;
import io.argos.server.admin.token.TokenService;
import io.argos.server.alert.AlertRuleService;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.ingest.ApdexChangeService;
import io.argos.server.ingest.IngestStore.ApdexChange;
import io.argos.server.ingest.IngestStore.OverrideLimitException;
import io.argos.server.session.AgentSession;
import io.argos.server.session.InstanceStateTracker;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.app.AppAdminRepository;
import io.argos.server.storage.postgres.app.AppAdminRepository.DeletedApp;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.argos.server.storage.postgres.catalog.CatalogRepository.InstanceRecord;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Application and instance administration (WO-26; 14 §4.3, ADR-0010 C-153, C-207 (3), C-220 (1)):
 * create, change and delete applications, the service Apdex T overrides, archive and delete
 * instances. Callers (the controllers) resolve the application or instance first ({@code AppScope}:
 * 404 and the per-application permission) and pass the audit actor.
 *
 * <p>Deleting an application revokes all of its agent tokens ({@link TokenService#revokeAllForApp},
 * which ends their sessions with {@code UNAUTHENTICATED}), closes any other session of it (one
 * opened with the token of its home application, ADR-0010 C-155), soft-deletes its alert rules and
 * the row itself ({@link AppAdminRepository#softDelete}); its data stays until its retention ends.
 */
@Service
public class AppAdminService {

  /** The reason recorded on the tokens of a deleted application. */
  public static final String DELETE_REASON = "app_deleted";

  private static final Logger LOG = LoggerFactory.getLogger(AppAdminService.class);

  private final AppAdminRepository apps;
  private final CatalogRepository catalog;
  private final ApdexChangeService apdex;
  private final TokenService tokens;
  private final SessionRegistry registry;
  private final SessionMessenger messenger;
  private final ObjectProvider<AlertRuleService> rules;
  private final ObjectProvider<InstanceStateTracker> tracker;
  private final AuditService audit;
  private final Clock clock;

  public AppAdminService(
      AppAdminRepository apps,
      CatalogRepository catalog,
      ApdexChangeService apdex,
      TokenService tokens,
      SessionRegistry registry,
      SessionMessenger messenger,
      ObjectProvider<AlertRuleService> rules,
      ObjectProvider<InstanceStateTracker> tracker,
      AuditService audit,
      Clock clock) {
    this.apps = apps;
    this.catalog = catalog;
    this.apdex = apdex;
    this.tokens = tokens;
    this.registry = registry;
    this.messenger = messenger;
    this.rules = rules;
    this.tracker = tracker;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * A new application.
   *
   * @param name name (validated)
   * @param env environment (validated)
   * @param group group, or null
   * @param apdexTMs Apdex T
   * @param autoRegister registration policy {@code auto} (true), {@code approval} (false), or null
   *     for none (the environment default applies, ADR-0010 C-231 (7))
   */
  public record NewApp(
      String name,
      String env,
      @Nullable String group,
      int apdexTMs,
      @Nullable Boolean autoRegister) {}

  /**
   * The change of a {@code PATCH /apps/{appId}}; null fields stay as they are.
   *
   * @param setGroup whether the group changes
   * @param group the new group (null clears it); ignored unless {@code setGroup}
   * @param apdexTMs the new Apdex T, or null
   * @param autoRegister the new registration policy, or null
   * @param packages the new application packages (validated), or null
   */
  public record AppChange(
      boolean setGroup,
      @Nullable String group,
      @Nullable Integer apdexTMs,
      @Nullable Boolean autoRegister,
      @Nullable List<String> packages) {

    /** Whether the change changes nothing. */
    public boolean empty() {
      return !setGroup && apdexTMs == null && autoRegister == null && packages == null;
    }
  }

  /**
   * What a delete did.
   *
   * @param appId the application
   * @param tokensRevoked agent tokens revoked
   * @param sessionsClosed live sessions closed besides those of the revoked tokens
   * @param rulesDeleted alert rules deleted
   */
  public record Deletion(int appId, int tokensRevoked, int sessionsClosed, int rulesDeleted) {}

  /**
   * Creates an application.
   *
   * @throws ApiException 409 {@code already_exists} when (name, env) exists
   */
  public AppRecord create(NewApp app, Actor actor) {
    int id =
        apps.create(app.name(), app.env(), app.group(), app.apdexTMs(), app.autoRegister())
            .orElseThrow(
                () ->
                    new ApiException(
                            ErrorCode.ALREADY_EXISTS, "An application with this name exists")
                        .detail("name", "already_exists"));
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("env", app.env());
    if (app.group() != null) {
      after.put("group", app.group());
    }
    after.put("apdex_t_ms", app.apdexTMs());
    after.put("registration_policy", policy(app.autoRegister()));
    audit.record(
        AuditEvent.APP_CREATED,
        actor,
        new Target("app", Integer.toString(id), app.name()),
        AuditResult.success(),
        null,
        after);
    return required(id);
  }

  /**
   * Changes an application. The Apdex T goes through {@link ApdexChangeService#setAppT}: it applies
   * from now on and writes the {@code apdex} event marker (D-17).
   *
   * @throws ApiException 404 when the application does not exist (any more)
   */
  public AppRecord patch(AppRecord app, AppChange change, @Nullable UUID userId, Actor actor) {
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    if (!apps.update(
        app.id(), change.setGroup(), change.group(), change.autoRegister(), change.packages())) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    if (change.setGroup() && !Objects.equals(app.group(), change.group())) {
      before.put("group", app.group());
      after.put("group", change.group());
    }
    if (change.autoRegister() != null && !change.autoRegister().equals(app.autoRegister())) {
      before.put("registration_policy", policy(app.autoRegister()));
      after.put("registration_policy", policy(change.autoRegister()));
    }
    if (change.packages() != null && !change.packages().equals(app.appPackages())) {
      before.put("app_packages", app.appPackages());
      after.put("app_packages", change.packages());
    }
    if (change.apdexTMs() != null) {
      Optional<ApdexChange> c = setAppT(app.id(), change.apdexTMs(), userId);
      if (c.isEmpty()) {
        throw new ApiException(ErrorCode.NOT_FOUND);
      }
      if (c.get().changed()) {
        before.put("apdex_t_ms", c.get().oldTMs());
        after.put("apdex_t_ms", c.get().newTMs());
      }
    }
    if (!after.isEmpty()) {
      audit.record(
          AuditEvent.APP_UPDATED,
          actor,
          new Target("app", Integer.toString(app.id()), app.name()),
          AuditResult.success(),
          before,
          after);
    }
    return required(app.id());
  }

  /**
   * Deletes an application (see the class comment).
   *
   * @param confirm the name the administrator typed, which must equal the application's name
   * @throws ApiException 400 when {@code confirm} differs, 404 when the application is gone
   */
  public Deletion delete(AppRecord app, String confirm, @Nullable UUID userId, Actor actor) {
    if (confirm == null || !confirm.equals(app.name())) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "confirm must be the application's name")
          .detail("confirm", confirm == null || confirm.isEmpty() ? "required" : "mismatch");
    }
    int revoked = tokens.revokeAllForApp(actor, app.id(), DELETE_REASON);
    Optional<DeletedApp> deleted = apps.softDelete(app.id(), clock.instant());
    if (deleted.isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    int closed = closeSessions(app.id());
    int rulesDeleted = deleteRules(app.id(), userId);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("env", app.env());
    after.put("tokens_revoked", revoked);
    after.put("sessions_closed", closed);
    after.put("rules_deleted", rulesDeleted);
    audit.record(
        AuditEvent.APP_DELETED,
        actor,
        new Target("app", Integer.toString(app.id()), app.name()),
        AuditResult.success(),
        null,
        after);
    return new Deletion(app.id(), revoked, closed, rulesDeleted);
  }

  /**
   * Sets the Apdex T override of service {@code serviceId} (ADR-0010 C-220 (1)).
   *
   * @throws ApiException 404 for an unknown application, 409 {@code invalid_state} at the cap of
   *     {@code ApdexOverrides.MAX_PER_APP} overrides
   */
  public ApdexChange setServiceT(
      AppRecord app, long serviceId, int tMs, @Nullable UUID userId, Actor actor) {
    ApdexChange c;
    try {
      c =
          apdex
              .setServiceT(app.id(), serviceId, tMs, userId)
              .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    } catch (OverrideLimitException e) {
      throw new ApiException(ErrorCode.INVALID_STATE, "The application has 1,000 overrides")
          .detail("service_id", "override_limit");
    } catch (IllegalStateException e) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    auditOverride(app, serviceId, c, actor);
    return c;
  }

  /**
   * Removes the Apdex T override of service {@code serviceId}: its transactions get the
   * application's T again.
   */
  public ApdexChange clearServiceT(
      AppRecord app, long serviceId, @Nullable UUID userId, Actor actor) {
    ApdexChange c;
    try {
      c =
          apdex
              .clearServiceT(app.id(), serviceId, userId)
              .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    } catch (IllegalStateException e) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    auditOverride(app, serviceId, c, actor);
    return c;
  }

  /**
   * Archives an instance (hidden from the instance list by default; a reconnect lists it again).
   *
   * @return false when it was archived already (nothing changed, nothing audited)
   * @throws ApiException 409 {@code invalid_state} while the instance has a live session
   */
  public boolean archiveInstance(AppRecord app, InstanceRecord instance, Actor actor) {
    requireOffline(instance.id());
    if (!apps.archiveInstance(instance.id(), clock.instant())) {
      return false;
    }
    InstanceStateTracker t = tracker.getIfAvailable();
    if (t != null) {
      t.retire(instance.id());
    }
    audit.record(
        AuditEvent.INSTANCE_ARCHIVED,
        actor,
        new Target("instance", Integer.toString(instance.id()), instance.name()),
        AuditResult.success(),
        null,
        Map.of("app_id", app.id(), "env", app.env()));
    return true;
  }

  /**
   * Deletes an archived instance with its commands; its events and ClickHouse rows stay until their
   * retention ends.
   *
   * @throws ApiException 404, or 409 {@code invalid_state} when it is not archived or connected
   */
  public void deleteInstance(AppRecord app, InstanceRecord instance, Actor actor) {
    requireOffline(instance.id());
    switch (apps.deleteInstance(instance.id())) {
      case NOT_FOUND -> throw new ApiException(ErrorCode.NOT_FOUND);
      case NOT_ARCHIVED ->
          throw new ApiException(ErrorCode.INVALID_STATE, "Only an archived instance is deleted")
              .detail("instance", "not_archived");
      case DELETED -> {}
    }
    audit.record(
        AuditEvent.INSTANCE_DELETED,
        actor,
        new Target("instance", Integer.toString(instance.id()), instance.name()),
        AuditResult.success(),
        null,
        Map.of("app_id", app.id(), "env", app.env()));
  }

  private void requireOffline(int instanceId) {
    if (registry.find(instanceId).isPresent()) {
      throw new ApiException(ErrorCode.INVALID_STATE, "The instance is connected")
          .detail("instance", "connected");
    }
  }

  private Optional<ApdexChange> setAppT(int appId, int tMs, @Nullable UUID userId) {
    try {
      return apdex.setAppT(appId, tMs, userId);
    } catch (IllegalStateException e) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }

  private void auditOverride(AppRecord app, long serviceId, ApdexChange c, Actor actor) {
    if (!c.changed()) {
      return;
    }
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("service_id", Long.toUnsignedString(serviceId));
    before.put("apdex_t_ms", c.oldTMs());
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("service_id", Long.toUnsignedString(serviceId));
    after.put("apdex_t_ms", c.newTMs());
    audit.record(
        AuditEvent.APP_UPDATED,
        actor,
        new Target("app", Integer.toString(app.id()), app.name()),
        AuditResult.success(),
        before,
        after);
  }

  /** Closes the sessions of the application that the token revocation left open. */
  private int closeSessions(int appId) {
    SessionClose close =
        SessionClose.newBuilder()
            .setReason(CloseReason.TOKEN_REVOKED_CLOSE)
            .setMessage("The application was deleted")
            .build();
    int closed = 0;
    for (AgentSession s : new ArrayList<>(registry.byApp(appId))) {
      if (messenger.close(s.instanceId(), close)) {
        closed++;
      }
    }
    return closed;
  }

  /** Soft-deletes the alert rules of the application (their active alerts resolve). */
  private int deleteRules(int appId, @Nullable UUID userId) {
    AlertRuleService service = rules.getIfAvailable();
    if (service == null) {
      return 0;
    }
    int deleted = 0;
    for (AlertRuleService.RuleView rule : service.list(appId, null, null)) {
      try {
        service.delete(rule.id(), userId);
        deleted++;
      } catch (ApiException e) {
        if (e.errorCode() != ErrorCode.NOT_FOUND) {
          throw e;
        }
        LOG.debug("Alert rule {} of deleted app {} was deleted meanwhile", rule.id(), appId);
      }
    }
    return deleted;
  }

  private AppRecord required(int id) {
    return catalog.app(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  /** The audit value of a policy: {@code auto}, {@code approval}, or {@code inherit} for none. */
  private static String policy(@Nullable Boolean autoRegister) {
    return autoRegister == null ? "inherit" : autoRegister ? "auto" : "approval";
  }
}
