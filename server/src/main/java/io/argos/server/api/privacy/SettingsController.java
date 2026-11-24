/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.privacy;

import io.argos.server.api.Envelope;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.api.query.QuerySources;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.ops.retention.RetentionApplier.TableState;
import io.argos.server.ops.retention.RetentionService;
import io.argos.server.ops.retention.RetentionService.ShorteningNotConfirmedException;
import io.argos.server.privacy.settings.PrivacySettingsService;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The retention and privacy defaults of the server (14 §4.14; WO-28), administrators only.
 *
 * <ul>
 *   <li>{@code GET/PUT /admin/settings/retention}: the effective ClickHouse retention, where each
 *       value comes from, and the tables with their TTL now; a {@code PUT} stores the given values
 *       (they win over {@code argos.retention.*}, PM question 3 assumption), applies them at once
 *       ({@code MODIFY TTL} without rewriting parts, partitions outside a shortened retention are
 *       dropped in the background) and records {@code RETENTION_CHANGED}. A shortening needs {@code
 *       confirm: true}.
 *   <li>{@code GET/PUT /admin/settings/privacy}: the default IP mode ({@code
 *       PRIVACY_SETTING_CHANGED}).
 * </ul>
 */
@RestController
@RequiresRole(Role.ADMIN)
public class SettingsController {

  private final RetentionService retention;
  private final PrivacySettingsService privacy;
  private final QuerySources sources;

  public SettingsController(
      RetentionService retention, PrivacySettingsService privacy, QuerySources sources) {
    this.retention = retention;
    this.privacy = privacy;
    this.sources = sources;
  }

  /** The effective retention and the tables. */
  @GetMapping("/api/v1/admin/settings/retention")
  @Operation(summary = "The effective data retention and the ClickHouse tables' TTL")
  public Envelope<PrivacyDtos.RetentionView> retention() {
    List<TableState> tables;
    try {
      tables = retention.tables();
    } catch (StatementException | RuntimeException e) {
      tables = null;
    }
    return Envelope.of(
        PrivacyDtos.RetentionView.of(retention.effective(), tables, retention.pendingDrops()));
  }

  /** Stores and applies new retention values. */
  @PutMapping("/api/v1/admin/settings/retention")
  @ArgosAudit(AuditEvent.RETENTION_CHANGED)
  @Operation(
      summary = "Change the data retention",
      description =
          "Absent fields keep their value. A shorter value needs confirm: true; data outside it"
              + " is dropped.")
  public Envelope<PrivacyDtos.RetentionUpdated> updateRetention(
      @RequestBody PrivacyDtos.RetentionUpdateRequest body, HttpServletRequest request) {
    if (body == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A body is required");
    }
    Map<String, Integer> changes = new LinkedHashMap<>();
    put(changes, "xlog_days", body.xlogDays());
    put(changes, "profile_days", body.profileDays());
    put(changes, "stat_raw_days", body.statRawDays());
    put(changes, "metric_raw_days", body.metricRawDays());
    put(changes, "rollup_1m_days", body.rollup1mDays());
    put(changes, "rollup_1h_days", body.rollup1hDays());
    if (changes.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "No retention value given")
          .detail("body", "empty");
    }
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    try {
      return Envelope.of(
          PrivacyDtos.RetentionUpdated.of(
              retention.update(
                  changes,
                  Boolean.TRUE.equals(body.confirm()),
                  principal.userId(),
                  Actor.user(principal, request))));
    } catch (ShorteningNotConfirmedException e) {
      ApiException error =
          new ApiException(
              ErrorCode.INVALID_REQUEST,
              "A shorter retention drops data: send confirm: true to change it");
      error.detail("confirm", "required");
      for (String f : e.fields()) {
        error.detail(f, "shorter");
      }
      throw error;
    } finally {
      // The query API's raw-retention boundaries follow at once (ADR-0010 C-238 (3)).
      sources.retentionChanged();
    }
  }

  private static void put(Map<String, Integer> changes, String field, Integer value) {
    if (value == null) {
      return;
    }
    if (value < 1 || value > 36_500) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "Retention values are 1 to 36500 days")
          .detail(field, "out_of_range");
    }
    changes.put(field, value);
  }

  /** The privacy defaults of the server. */
  @GetMapping("/api/v1/admin/settings/privacy")
  @Operation(summary = "The server's privacy defaults")
  public Envelope<PrivacyDtos.PrivacySettingsView> privacy() {
    PrivacySettingsService.IpModeSetting m = privacy.defaultIpMode();
    return Envelope.of(new PrivacyDtos.PrivacySettingsView(m.mode(), m.source()));
  }

  /** Changes the default IP mode. */
  @PutMapping("/api/v1/admin/settings/privacy")
  @ArgosAudit(AuditEvent.PRIVACY_SETTING_CHANGED)
  @Operation(
      summary = "Change the server's default IP mode",
      description = "Agents with a local capture.remote-ip keep theirs; nothing is pushed.")
  public Envelope<PrivacyDtos.PrivacySettingsView> updatePrivacy(
      @RequestBody PrivacyDtos.PrivacySettingsRequest body, HttpServletRequest request) {
    String mode = body == null ? null : body.defaultIpMode();
    if (mode == null || !PrivacySettingsService.IP_MODES.contains(mode)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "default_ip_mode is full, masked or off")
          .detail("default_ip_mode", "invalid");
    }
    ArgosPrincipal principal =
        ArgosPrincipal.current().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    PrivacySettingsService.IpModeSetting m =
        privacy.setDefaultIpMode(mode, principal.userId(), Actor.user(principal, request));
    return Envelope.of(new PrivacyDtos.PrivacySettingsView(m.mode(), m.source()));
  }
}
