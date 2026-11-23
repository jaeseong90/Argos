/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.server.admin.apps.AppAdminService;
import io.argos.server.admin.apps.AppInputs;
import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.transactions.Ids;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.ingest.IngestStore.ApdexChange;
import io.argos.server.storage.postgres.catalog.CatalogRepository.AppRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Application and instance administration (14 §4.3, ADR-0010 C-153; WO-26): create, change and
 * delete applications, service Apdex T overrides (ADR-0010 C-220 (1)), archive and delete
 * instances. Administrators only, except the archive (operators, 14 §7.1).
 */
@RestController
@RequiresRole(Role.ADMIN)
public class AppsAdminController {

  private final AppAdminService admin;
  private final AppsService apps;
  private final AppScope scope;

  public AppsAdminController(AppAdminService admin, AppsService apps, AppScope scope) {
    this.admin = admin;
    this.apps = apps;
    this.scope = scope;
  }

  /** Creates an application (14 §4.3). */
  @PostMapping("/api/v1/apps")
  @ResponseStatus(HttpStatus.CREATED)
  @ArgosAudit(AuditEvent.APP_CREATED)
  @Operation(
      operationId = "apps_create",
      summary = "Create an application",
      description =
          "name 1 to 128 and env 1 to 32 characters (no control characters), group at most 64,"
              + " apdex_t_ms 1 to 65535 (default 500), registration_policy auto (default) or"
              + " approval. 409 already_exists when (name, env) exists. Agents of the application"
              + " connect with a token issued by POST /admin/apps/{appId}/tokens.")
  public Envelope<AppViews.App> create(
      @RequestBody(required = false) AppAdminDtos.CreateAppRequest body,
      HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("name", "required");
    }
    Boolean policy = AppInputs.autoRegister(body.registrationPolicy());
    AppAdminService.NewApp app =
        new AppAdminService.NewApp(
            AppInputs.name(body.name()),
            AppInputs.env(body.env()),
            AppInputs.group(body.group()),
            body.apdexTMs() == null
                ? AppInputs.DEFAULT_APDEX_T_MS
                : AppInputs.apdexT("apdex_t_ms", body.apdexTMs()),
            policy == null || policy);
    AppRecord created = admin.create(app, actor(request));
    return Envelope.of(apps.views(List.of(created)).get(0));
  }

  /** Changes an application (14 §4.3). */
  @PatchMapping("/api/v1/apps/{appId}")
  @ArgosAudit(AuditEvent.APP_UPDATED)
  @Operation(
      operationId = "apps_patch",
      summary = "Change an application",
      description =
          "Absent fields stay as they are. group: an empty string clears it. apdex_t_ms applies to"
              + " transactions received from now on and writes an apdex event marker (D-17; past"
              + " data keeps its T). app_packages replaces the list (at most 50 Java package"
              + " names; JDK and Argos packages are refused). 400 when nothing changes.")
  public Envelope<AppViews.App> patch(
      @PathVariable("appId") int appId,
      @RequestBody(required = false) AppAdminDtos.PatchAppRequest body,
      HttpServletRequest request) {
    AppRecord app = scope.app(appId);
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The request changes nothing");
    }
    AppAdminService.AppChange change =
        new AppAdminService.AppChange(
            body.group() != null,
            body.group() == null ? null : AppInputs.group(body.group()),
            body.apdexTMs() == null ? null : AppInputs.apdexT("apdex_t_ms", body.apdexTMs()),
            AppInputs.autoRegister(body.registrationPolicy()),
            AppInputs.packages(body.appPackages()));
    if (change.empty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The request changes nothing");
    }
    AppRecord changed = admin.patch(app, change, principal().userId(), actor(request));
    return Envelope.of(apps.appDetail(changed));
  }

  /** Deletes an application (14 §4.3). */
  @DeleteMapping("/api/v1/apps/{appId}")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @ArgosAudit({AuditEvent.APP_DELETED, AuditEvent.TOKEN_REVOKED})
  @Operation(
      operationId = "apps_delete",
      summary = "Delete an application",
      description =
          "confirm must be the application's name (400 otherwise). Every agent token of the"
              + " application is revoked and its live sessions end with UNAUTHENTICATED within"
              + " 5 s, its alert rules are deleted, its instances archived; the application is no"
              + " longer listed and its name can be registered again. Collected data stays until"
              + " its retention ends.")
  public Envelope<AppAdminDtos.AppDeleted> delete(
      @PathVariable("appId") int appId,
      @Parameter(description = "The application's name, as a confirmation")
          @RequestParam(value = "confirm", required = false)
          String confirm,
      HttpServletRequest request) {
    AppRecord app = scope.app(appId);
    AppAdminService.Deletion d = admin.delete(app, confirm, principal().userId(), actor(request));
    return Envelope.of(
        new AppAdminDtos.AppDeleted(
            d.appId(), d.tokensRevoked(), d.sessionsClosed(), d.rulesDeleted()));
  }

  /** Sets a service's Apdex T override (ADR-0010 C-220 (1)). */
  @PutMapping("/api/v1/apps/{appId}/services/{serviceId}/apdex")
  @ArgosAudit(AuditEvent.APP_UPDATED)
  @Operation(
      operationId = "apps_service_apdex_put",
      summary = "Set a service's Apdex T",
      description =
          "Overrides the application's Apdex T for one service (D-17): transactions received from"
              + " now on are stamped with it and an apdex event marker is written; past data keeps"
              + " its T (the operator command argos-admin apdex reapply re-stamps it). apdex_t_ms"
              + " 1 to 65535. 409 invalid_state when the application has 1,000 overrides.")
  public Envelope<AppAdminDtos.ServiceApdex> putServiceApdex(
      @PathVariable("appId") int appId,
      @PathVariable("serviceId") String serviceId,
      @RequestBody(required = false) AppAdminDtos.ServiceApdexRequest body,
      HttpServletRequest request) {
    AppRecord app = scope.app(appId);
    long service = Ids.parseUnsigned("serviceId", serviceId);
    if (body == null || body.apdexTMs() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("apdex_t_ms", "required");
    }
    int t = AppInputs.apdexT("apdex_t_ms", body.apdexTMs());
    ApdexChange c = admin.setServiceT(app, service, t, principal().userId(), actor(request));
    return Envelope.of(view(app.id(), service, c, true));
  }

  /** Removes a service's Apdex T override (ADR-0010 C-220 (1)). */
  @DeleteMapping("/api/v1/apps/{appId}/services/{serviceId}/apdex")
  @ArgosAudit(AuditEvent.APP_UPDATED)
  @Operation(
      operationId = "apps_service_apdex_delete",
      summary = "Remove a service's Apdex T",
      description =
          "The service's transactions received from now on get the application's Apdex T again;"
              + " an apdex event marker is written when the T in effect changes.")
  public Envelope<AppAdminDtos.ServiceApdex> deleteServiceApdex(
      @PathVariable("appId") int appId,
      @PathVariable("serviceId") String serviceId,
      HttpServletRequest request) {
    AppRecord app = scope.app(appId);
    long service = Ids.parseUnsigned("serviceId", serviceId);
    ApdexChange c = admin.clearServiceT(app, service, principal().userId(), actor(request));
    return Envelope.of(view(app.id(), service, c, false));
  }

  /** Archives an instance (14 §4.3). */
  @PostMapping("/api/v1/instances/{instanceId}/archive")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @RequiresRole(Role.OPERATOR)
  @ArgosAudit(AuditEvent.INSTANCE_ARCHIVED)
  @Operation(
      operationId = "instances_archive",
      summary = "Archive an instance",
      description =
          "Hides the instance from the instance list (archived=true lists it); a reconnect lists"
              + " it again. 409 invalid_state while it is connected; archiving an archived"
              + " instance changes nothing.")
  public void archive(@PathVariable("instanceId") int instanceId, HttpServletRequest request) {
    AppScope.Scoped s = scope.instance(instanceId);
    admin.archiveInstance(s.app(), s.instance(), actor(request));
  }

  /** Deletes an archived instance (14 §4.3). */
  @DeleteMapping("/api/v1/instances/{instanceId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @ArgosAudit(AuditEvent.INSTANCE_DELETED)
  @Operation(
      operationId = "instances_delete",
      summary = "Delete an archived instance",
      description =
          "Only an archived instance that is not connected is deleted (409 invalid_state"
              + " otherwise), with its command history; its events and collected data stay until"
              + " their retention ends.")
  public void deleteInstance(
      @PathVariable("instanceId") int instanceId, HttpServletRequest request) {
    AppScope.Scoped s = scope.instance(instanceId);
    admin.deleteInstance(s.app(), s.instance(), actor(request));
  }

  private static AppAdminDtos.ServiceApdex view(
      int appId, long serviceId, ApdexChange c, boolean overridden) {
    return new AppAdminDtos.ServiceApdex(
        appId,
        Long.toUnsignedString(serviceId),
        c.newTMs(),
        overridden,
        c.changed(),
        c.eventId() == null ? null : c.eventId().toString(),
        c.ts());
  }

  private static ArgosPrincipal principal() {
    return ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
  }

  private static Actor actor(HttpServletRequest request) {
    return Actor.user(principal(), request);
  }
}
