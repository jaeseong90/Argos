/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import io.argos.server.collector.batch.UnknownEnumSink;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The collector's own meters (12 §9.4, Prometheus names in brackets):
 *
 * <ul>
 *   <li>{@code argos.collector.received{type}} [{@code argos_collector_received_total}]: received
 *       items per batch kind ({@code transactions} records, {@code profiles} profiles, {@code
 *       metrics} samples, {@code stats} rows, {@code active} snapshots), before any drop;
 *   <li>{@code argos.collector.rejected{reason}} [{@code argos_collector_rejected_total}]: refused
 *       calls and Hellos by reason (limit classes, token verdicts, session and Hello checks);
 *   <li>{@code argos.collector.dropped{reason}} [{@code argos_collector_dropped_total}]: items
 *       accepted and dropped by the server, {@code quota} or {@code wal_full} (D-06);
 *   <li>{@code argos.collector.redacted{reason}} [{@code argos_collector_redacted_total}]: steps
 *       whose detail the server cleared at receipt, {@code full_detail_unencrypted} for the bind
 *       values of a {@code SQL_PARAM_FULL} session (ADR-0010 C-148);
 *   <li>{@code argos.collector.unknown.enum{field}} [{@code argos_collector_unknown_enum_total}]
 *       (11 §4.4, ADR-0010 C-143 (1)), with a WARN line at most once a minute per field;
 *   <li>{@code argos.collector.auth.blocked} [{@code argos_collector_auth_blocked_total}]: calls
 *       refused because their address is blocked after authentication failures (16 §2.5);
 *   <li>{@code argos.collector.session.messages{type}} [{@code
 *       argos_collector_session_messages_total}]: session stream messages by type, including the
 *       ones this slice only counts ({@code bye}, {@code event}, {@code config_apply_result},
 *       {@code command_result}), and {@code heartbeat_ack_skipped} for heartbeats left unanswered
 *       because the agent did not read its stream (no buffering, SEC-11);
 *   <li>{@code argos.collector.sessions.closed{reason}} [{@code
 *       argos_collector_sessions_closed_total}]: sessions the server closed on its own (WO-22):
 *       {@code idle_timeout} (no agent message for 3 heartbeat intervals, ADR-0002 C-19) and {@code
 *       instance_merged} (a temporary {@code host:pid} instance continues as the existing instance
 *       of its new name, ADR-0010 C-158 (2));
 *   <li>{@code argos.collector.calls{method,code}} [{@code argos_collector_calls_seconds}]: call
 *       latency of the data RPCs;
 *   <li>{@code argos.sessions.active} [{@code argos_sessions_active}]: live sessions.
 * </ul>
 *
 * Every label value comes from a fixed set (no collected data, no token, no address).
 */
public final class CollectorMetrics implements UnknownEnumSink {

  private static final Logger LOG = LoggerFactory.getLogger(CollectorMetrics.class);

  /** Minimum time between two WARN lines about unknown values of one field (C-143 (1)). */
  static final long UNKNOWN_ENUM_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final MeterRegistry registry;
  private final Map<String, Long> lastUnknownEnumWarn = new ConcurrentHashMap<>();

  public CollectorMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** {@code items} received in a batch of {@code type}. */
  public void received(String type, int items) {
    if (items > 0) {
      counter("argos.collector.received", "type", type).increment(items);
    }
  }

  /** One refused call or Hello. */
  public void rejected(String reason) {
    counter("argos.collector.rejected", "reason", reason).increment();
  }

  /** {@code items} accepted and dropped for {@code reason} ({@code quota}, {@code wal_full}). */
  public void dropped(String reason, int items) {
    if (items > 0) {
      counter("argos.collector.dropped", "reason", reason).increment(items);
    }
  }

  /** {@code steps} whose detail was cleared at receipt for {@code reason} (ADR-0010 C-148). */
  public void redacted(String reason, int steps) {
    if (steps > 0) {
      counter("argos.collector.redacted", "reason", reason).increment(steps);
    }
  }

  /** One call refused for a blocked address. */
  public void authBlocked() {
    Counter.builder("argos.collector.auth.blocked")
        .description("Calls refused because their address is blocked (16 §2.5)")
        .register(registry)
        .increment();
  }

  /** One session stream message of {@code type}. */
  public void sessionMessage(String type) {
    counter("argos.collector.session.messages", "type", type).increment();
  }

  /** One session the server closed on its own for {@code reason}. */
  public void sessionClosed(String reason) {
    counter("argos.collector.sessions.closed", "reason", reason).increment();
  }

  /** The latency of one data call. */
  public void call(String method, Status.Code code, long nanos) {
    Timer.builder("argos.collector.calls")
        .tag("method", method)
        .tag("code", code.name())
        .description("Data RPC latency")
        .register(registry)
        .record(nanos, TimeUnit.NANOSECONDS);
  }

  /** Publishes the live session count. */
  public void bindActiveSessions(Supplier<Number> count) {
    Gauge.builder("argos.sessions.active", count)
        .description("Live agent sessions")
        .strongReference(true)
        .register(registry);
  }

  @Override
  public void unknownEnum(String field, int count) {
    if (count <= 0) {
      return;
    }
    counter("argos.collector.unknown.enum", "field", field).increment(count);
    long now = System.nanoTime();
    boolean[] warn = {false};
    lastUnknownEnumWarn.compute(
        field,
        (k, last) -> {
          if (last == null || now - last >= UNKNOWN_ENUM_WARN_INTERVAL_NANOS) {
            warn[0] = true;
            return now;
          }
          return last;
        });
    if (warn[0]) {
      LOG.warn(
          "Received {} unknown or unspecified value(s) of {}; the items were dropped (11 §4.4,"
              + " ADR-0010 C-143); further occurrences are counted in"
              + " argos_collector_unknown_enum_total for a minute without logging",
          count,
          field);
    }
  }

  private Counter counter(String name, String tag, String value) {
    return Counter.builder(name).tag(tag, value).register(registry);
  }
}
