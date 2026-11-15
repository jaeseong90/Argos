/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The ingest meters of 12 §9.4: {@code argos_ingest_errors_total{stage}}, {@code
 * argos_ts_clamped_total{kind="session_offset|record_clamp"}} (ADR-0010 C-129 (7)), {@code
 * argos_ts_rejected_total}, {@code argos_late_arrivals_total}, {@code
 * argos_collector_unknown_enum_total{field}} (values the writer maps, C-143 (1)) and {@code
 * argos_dictionary_overflow_total}. The dictionary gauge {@code argos_dictionary_unresolved} is
 * registered by {@link DictionaryResolver}, the writer and WAL meters by their classes.
 */
@Component
public class IngestMetrics {

  /** {@code argos_ts_clamped_total} kinds (C-129 (7)). */
  public enum ClampKind {
    /** A record of a corrected session (agent clock more than 5 s ahead). */
    SESSION_OFFSET("session_offset"),
    /** A record still beyond receive + 5 s after the correction, clamped to the receive time. */
    RECORD_CLAMP("record_clamp");

    private final String tag;

    ClampKind(String tag) {
      this.tag = tag;
    }
  }

  private final MeterRegistry registry;
  private final Map<String, Counter> errors = new ConcurrentHashMap<>();
  private final Map<String, Counter> unknownEnums = new ConcurrentHashMap<>();
  private final Counter clampedSession;
  private final Counter clampedRecord;
  private final Counter rejected;
  private final Counter late;
  private final Counter overflow;

  public IngestMetrics(MeterRegistry registry) {
    this.registry = registry;
    this.clampedSession = clamped(ClampKind.SESSION_OFFSET);
    this.clampedRecord = clamped(ClampKind.RECORD_CLAMP);
    this.rejected =
        Counter.builder("argos.ts.rejected")
            .description("Records older than receive time - 7 days, not stored (D-15)")
            .register(registry);
    this.late =
        Counter.builder("argos.late.arrivals")
            .description("Transactions received more than 60 s after their ts (late = 1, D-15)")
            .register(registry);
    this.overflow =
        Counter.builder("argos.dictionary.overflow")
            .description("Dictionary entries mapped to <overflow> by the 16 §4.4 caps")
            .register(registry);
  }

  private Counter clamped(ClampKind kind) {
    return Counter.builder("argos.ts.clamped")
        .tag("kind", kind.tag)
        .description("Records whose ts the server changed (D-15, ADR-0010 C-129)")
        .register(registry);
  }

  /** One decode or ingest failure of {@code stage}. */
  public void error(String stage) {
    errors
        .computeIfAbsent(
            stage,
            s ->
                Counter.builder("argos.ingest.errors")
                    .tag("stage", s)
                    .description("Records or items skipped by the ingest pipeline")
                    .register(registry))
        .increment();
  }

  /** {@code n} values of the enum field {@code field} that the writer did not know. */
  public void unknownEnum(String field, long n) {
    unknownEnums
        .computeIfAbsent(
            field,
            f ->
                Counter.builder("argos.collector.unknown.enum")
                    .tag("field", f)
                    .description("Unknown or unspecified enum values (11 §4.4, ADR-0010 C-143)")
                    .register(registry))
        .increment((double) n);
  }

  /** {@code n} records clamped or corrected. */
  public void clamped(ClampKind kind, long n) {
    (kind == ClampKind.SESSION_OFFSET ? clampedSession : clampedRecord).increment((double) n);
  }

  /** {@code n} records rejected by the 7-day rule. */
  public void rejected(long n) {
    rejected.increment((double) n);
  }

  /** {@code n} late transactions. */
  public void late(long n) {
    late.increment((double) n);
  }

  /** {@code n} dictionary entries mapped to {@code <overflow>}. */
  public void overflow(long n) {
    overflow.increment((double) n);
  }

  /** The registry (for gauges of the ingest components). */
  public MeterRegistry registry() {
    return registry;
  }
}
