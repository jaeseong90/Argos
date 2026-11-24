/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The audit rows of the rollup operations (ADR-0010 C-220 (3), 16 §11.1 group 운영): {@code
 * ROLLUP_REBUILT} ({@code argos-admin rollup rebuild}), {@code CONSISTENCY_CHECKED} (the nightly
 * {@link ReconcileJob} with actor {@code system}, and {@code argos-admin consistency check}) and
 * {@code APDEX_REAPPLIED} ({@code argos-admin apdex reapply}). The same shape for the job and the
 * command line, so that one query finds both. The values are counts, day names, table names and
 * ids: nothing collected and nothing secret.
 *
 * <p>Targets: {@code ROLLUP_REBUILT} and {@code CONSISTENCY_CHECKED} {@code day} with the UTC day
 * as id; {@code APDEX_REAPPLIED} {@code app} with the application id. A refused or failed run is
 * recorded too ({@link #refused}), with result {@code failure} and a short reason code.
 */
public final class RollupAudit {

  /** {@code target_type} of a day. */
  public static final String TARGET_DAY = "day";

  /** {@code target_type} of an application. */
  public static final String TARGET_APP = "app";

  private RollupAudit() {}

  /** {@code ROLLUP_REBUILT} for a finished rebuild of one day. */
  public static void rebuilt(AuditService audit, Actor actor, RebuildResult result) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("day", result.day().toString());
    after.put("tables", result.tables().stream().map(RebuildResult.TableResult::table).toList());
    after.put("changed_tables", result.changedTables());
    after.put("identical", result.identical());
    after.put("duration_ms", result.durationMs());
    audit.record(
        AuditEvent.ROLLUP_REBUILT,
        actor,
        day(result.day()),
        AuditResult.success(result.identical() ? null : "changed"),
        null,
        after);
  }

  /** {@code CONSISTENCY_CHECKED} for a finished check of one day. */
  public static void checked(AuditService audit, Actor actor, ReconcileReport report) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("day", report.day().toString());
    after.put("trigger", report.trigger());
    after.put("result", report.result());
    after.put("rebuild", report.rebuildEnabled());
    after.put("rebuilds", report.rebuilds().size());
    after.put("mismatching_tables", report.mismatchingTables());
    after.put("remaining_tables", report.remainingTables());
    after.put(
        "d12_mismatching_apps",
        report.d12().stream().mapToInt(c -> c.mismatchingApps().size()).sum());
    after.put("duration_ms", report.durationMs());
    boolean clean = report.remainingTables().isEmpty();
    audit.record(
        AuditEvent.CONSISTENCY_CHECKED,
        actor,
        day(report.day()),
        clean ? AuditResult.success(report.result()) : AuditResult.failure("mismatch_left"),
        null,
        after);
  }

  /** {@code APDEX_REAPPLIED} for a finished re-application. */
  public static void reapplied(AuditService audit, Actor actor, ApdexReapplier.Result result) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("app_id", result.appId());
    if (result.serviceId() != null) {
      after.put("service_id", Long.toUnsignedString(result.serviceId()));
    }
    after.put("from", result.from().toString());
    after.put("to", result.to().toString());
    after.put("app_t_ms", result.appTMs());
    after.put("overrides", result.overrides());
    after.put("days", result.days().size());
    after.put("rows_changed", result.rowsChanged());
    after.put("duration_ms", result.durationMs());
    audit.record(
        AuditEvent.APDEX_REAPPLIED, actor, app(result.appId()), AuditResult.success(), null, after);
  }

  /**
   * A run that was refused or failed before it produced a result.
   *
   * @param event {@code ROLLUP_REBUILT}, {@code CONSISTENCY_CHECKED} or {@code APDEX_REAPPLIED}
   * @param target {@link #day} or {@link #app}
   * @param reason {@link Outcome.Reason} as a lower-case code ({@code busy}, {@code failure}, ...)
   * @param detail extra fields (day, range), or null
   */
  public static void refused(
      AuditService audit,
      Actor actor,
      AuditEvent event,
      Target target,
      String reason,
      @Nullable Map<String, ?> detail) {
    audit.record(event, actor, target, AuditResult.failure(reason), null, detail);
  }

  /** The reason code of a refusal ({@code busy}, {@code already_done}, {@code usage}, ...). */
  public static String reason(Outcome.Reason reason) {
    return reason.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The target of a day. */
  public static Target day(LocalDate day) {
    return new Target(TARGET_DAY, day.toString(), null);
  }

  /** The target of an application. */
  public static Target app(int appId) {
    return new Target(TARGET_APP, Integer.toString(appId), null);
  }

  /** Detail of a refused run over a range. */
  public static Map<String, Object> range(LocalDate from, LocalDate to) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("from", from.toString());
    m.put("to", to.toString());
    return m;
  }

  /** Detail of a refused run of a day. */
  public static Map<String, Object> dayDetail(LocalDate day, List<String> tables) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("day", day.toString());
    if (!tables.isEmpty()) {
      m.put("tables", tables);
    }
    return m;
  }
}
