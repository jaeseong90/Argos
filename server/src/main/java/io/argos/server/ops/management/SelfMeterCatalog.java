/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.micrometer.core.instrument.Meter;
import java.util.List;
import java.util.Objects;

/**
 * The server's own meters of 12 §9.4 (the canonical list; 17 §11.1 spells some differently and is
 * not implemented, ADR-0010 C-220 (7)) and the meters WO-28 adds, each with the Micrometer meter
 * that exposes it on {@code /actuator/prometheus} of the management port, or the implemented meter
 * it maps to with the decision that says so. {@code ServerMeterCatalogTest} checks this list
 * against 12 §9.4 and the sources; {@code SelfMetricsIT} (T-SV-047) scrapes it from a running
 * server.
 */
public final class SelfMeterCatalog {

  private SelfMeterCatalog() {}

  /** When a meter appears on a fresh server. */
  public enum Appears {
    /** Registered when its component starts (a gauge, or a counter built in a constructor). */
    AT_START,
    /** Registered when agents send data (the ingest path). */
    WITH_TRAFFIC,
    /**
     * Registered by the first event of its kind (a lazily built counter with labels), or present
     * only while its subject exists (the clock offset of live sessions).
     */
    ON_EVENT
  }

  /**
   * One documented meter.
   *
   * @param prometheus the name of 12 §9.4 (or of WO-28) as Prometheus exposes it, without labels
   * @param micrometer the Micrometer name that exposes it, or null when it maps to another meter
   * @param type the Micrometer type of {@code micrometer} (counters get {@code _total}, timers
   *     {@code _seconds})
   * @param appears when it appears on a fresh server
   * @param owner who registers it ({@code WO-xx}, or the WO-28 lane)
   * @param mappedTo the exposed meter(s) that carry it when {@code micrometer} is null
   * @param note why (the decision for a mapping, the source of a WO-28 name)
   */
  public record Entry(
      String prometheus,
      String micrometer,
      Meter.Type type,
      Appears appears,
      String owner,
      String mappedTo,
      String note) {

    public Entry {
      Objects.requireNonNull(prometheus, "prometheus");
      Objects.requireNonNull(appears, "appears");
      Objects.requireNonNull(owner, "owner");
    }

    /** Whether this documented name is exposed under another name. */
    public boolean mapped() {
      return micrometer == null;
    }
  }

  private static Entry meter(
      String prometheus, String micrometer, Meter.Type type, Appears appears, String owner) {
    return new Entry(prometheus, micrometer, type, appears, owner, null, null);
  }

  private static Entry mapped(String prometheus, String mappedTo, String note) {
    return new Entry(prometheus, null, Meter.Type.OTHER, Appears.ON_EVENT, "-", mappedTo, note);
  }

  /** The names listed in 12 §9.4, in its order. */
  public static final List<Entry> DOCUMENTED =
      List.of(
          meter(
              "argos_collector_received_total",
              "argos.collector.received",
              Meter.Type.COUNTER,
              Appears.WITH_TRAFFIC,
              "WO-21"),
          meter(
              "argos_collector_rejected_total",
              "argos.collector.rejected",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-21"),
          meter(
              "argos_collector_dropped_total",
              "argos.collector.dropped",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-21"),
          meter(
              "argos_collector_unknown_enum_total",
              "argos.collector.unknown.enum",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-21"),
          meter(
              "argos_sessions_active",
              "argos.sessions.active",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-21"),
          meter("argos_instances", "argos.instances", Meter.Type.GAUGE, Appears.AT_START, "WO-22"),
          meter("argos_wal_bytes", "argos.wal.bytes", Meter.Type.GAUGE, Appears.AT_START, "WO-21"),
          meter(
              "argos_wal_segments",
              "argos.wal.segments",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_wal_fsync_seconds",
              "argos.wal.fsync",
              Meter.Type.TIMER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_ingest_lag_seconds",
              "argos.ingest.lag",
              Meter.Type.TIMER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_ingest_errors_total",
              "argos.ingest.errors",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-21"),
          meter(
              "argos_ts_clamped_total",
              "argos.ts.clamped",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_ts_rejected_total",
              "argos.ts.rejected",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_late_arrivals_total",
              "argos.late.arrivals",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_dictionary_unresolved",
              "argos.dictionary.unresolved",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_dictionary_overflow_total",
              "argos.dictionary.overflow",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_writer_rows_total",
              "argos.writer.rows",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_writer_inserts_total",
              "argos.writer.inserts",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_writer_queue_rows",
              "argos.writer.queue.rows",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-21"),
          meter(
              "argos_realtime_subscribers",
              "argos.realtime.subscribers",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-22"),
          meter(
              "argos_realtime_dropped_total",
              "argos.realtime.dropped",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-22"),
          meter(
              "argos_instance_state_events_dropped_total",
              "argos.instance.state.events.dropped",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-22"),
          meter(
              "argos_instance_state_write_failures_total",
              "argos.instance.state.write.failures",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-22"),
          meter(
              "argos_collector_sessions_closed_total",
              "argos.collector.sessions.closed",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-22"),
          meter(
              "argos_alert_evaluations_total",
              "argos.alert.evaluations",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-25"),
          meter(
              "argos_alert_states",
              "argos.alert.states",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-25"),
          meter(
              "argos_notifications_total",
              "argos.notifications",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-25"),
          meter(
              "argos_notification_failures_total",
              "argos.notification.failures",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-25"),
          mapped(
              "argos_commands_total",
              "argos_commands_finished_total{type,status}, argos_commands_refused_total{type,reason}",
              "WO-26 implemented the result split as two counters (finished: completed, failed,"
                  + " expired; refused: rejection reason); same principle as ADR-0010 C-159 (3)"
                  + " (the implemented name is canonical). PM decision requested for the 12 §9.4"
                  + " correction (WO-28 report)."),
          mapped(
              "argos_config_changes_total",
              "argos_config_pushes_total{kind}",
              "WO-26 counts the configuration pushes per change kind (config, kill_switch); the"
                  + " result of each change is in config_changes and the CONFIG_CHANGE_APPLIED"
                  + " audit. PM decision requested for the 12 §9.4 correction (WO-28 report)."),
          mapped(
              "argos_auth_failures_total",
              "argos_collector_rejected_total{reason=\"token_*\"}",
              "ADR-0010 C-159 (3): the implemented name is canonical; 12 §9.4 is corrected."),
          meter(
              "argos_ratelimit_rejections_total",
              "argos.ratelimit.rejections",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-24"),
          meter(
              "argos_server_tls_cert_expiry_seconds",
              MeterNames.TLS_CERT_EXPIRY_SECONDS,
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 C"),
          meter(
              "argos_agent_token_expiry_seconds",
              "argos.agent.token.expiry.seconds",
              Meter.Type.GAUGE,
              Appears.ON_EVENT,
              "WO-26"),
          meter(
              "argos_ch_parts_active",
              "argos.ch.parts.active",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_ch_delayed_inserts_total",
              "argos.ch.delayed.inserts",
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_ch_disk_free_bytes",
              "argos.ch.disk.free.bytes",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_ch_query_p99_seconds",
              "argos.ch.query.p99.seconds",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_server_disk_usage_ratio",
              "argos.server.disk.usage.ratio",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_reconcile_mismatch_minutes",
              MeterNames.RECONCILE_MISMATCH_MINUTES,
              Meter.Type.GAUGE,
              Appears.ON_EVENT,
              "WO-27"),
          meter(
              "argos_privacy_jobs",
              "argos.privacy.jobs",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_clock_offset_ms",
              "argos.clock.offset.ms",
              Meter.Type.GAUGE,
              Appears.ON_EVENT,
              "WO-22"));

  /** The meters WO-28 adds beyond 12 §9.4 (plan 2.2 contract). */
  public static final List<Entry> ADDED =
      List.of(
          meter(
              "argos_ch_memory_tracking_bytes",
              "argos.ch.memory.tracking.bytes",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "C-238 (13): 12 §9.2 MemoryTracking, 12 §9.4 in its 8th correction"),
          meter(
              "argos_audit_dropped_total",
              "argos.audit.dropped",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-28 A"),
          meter(
              "argos_retention_emergency_drops_total",
              "argos.retention.emergency.drops",
              Meter.Type.COUNTER,
              Appears.ON_EVENT,
              "WO-28 B"),
          meter(
              "argos_writer_dead_letter_blocks",
              "argos.writer.dead.letter.blocks",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_writer_dead_letter_bytes",
              "argos.writer.dead.letter.bytes",
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 B"),
          meter(
              "argos_self_metrics_write_failures_total",
              MeterNames.SELF_METRICS_WRITE_FAILURES,
              Meter.Type.COUNTER,
              Appears.AT_START,
              "WO-28 C"),
          meter(
              "argos_server_tls_cert_expiry_days",
              MeterNames.TLS_CERT_EXPIRY_DAYS,
              Meter.Type.GAUGE,
              Appears.AT_START,
              "WO-28 C"));

  /**
   * The Prometheus exposition name of a Micrometer meter without labels: dots become underscores,
   * counters end with {@code _total} and timers with {@code _seconds} ({@code
   * PrometheusNamingConvention}; the base unit of a gauge is not appended because the server's
   * gauges carry the unit in the name).
   */
  public static String prometheusName(String micrometer, Meter.Type type) {
    String base = micrometer.replace('.', '_').replace('-', '_');
    if (type == Meter.Type.COUNTER && !base.endsWith("_total")) {
      return base + "_total";
    }
    if ((type == Meter.Type.TIMER || type == Meter.Type.LONG_TASK_TIMER)
        && !base.endsWith("_seconds")) {
      return base + "_seconds";
    }
    return base;
  }
}
