/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.storage.EmergencyOrder.Candidate;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.postgres.ops.StorageOpsRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The emergency retention of F-S23 (13 §8, 12 §9.2, 17 §9.2; PM question 1 assumption): each {@link
 * #step} of the storage health job takes the used ratio of the fullest ClickHouse disk and
 *
 * <ul>
 *   <li>at or above {@code argos.ops.disk.warn-ratio} (0.8) logs a warning (the alert template
 *       "server disk" and the health panel read the gauge);
 *   <li>from {@code argos.ops.disk.emergency-ratio} (0.9) until the ratio is below {@code
 *       argos.ops.emergency.target-ratio} (0.85) drops one day partition per step, the oldest in
 *       the order of {@link EmergencyOrder} ({@code profile_step}, then {@code metric}, {@code
 *       sql_stat_raw}, {@code ext_stat_raw}, then {@code xlog}); never today's (UTC) partition and
 *       never a rollup. Every drop writes an {@code events} row of type {@code
 *       retention_emergency}, a {@code RETENTION_EMERGENCY_DROP} audit record and {@code
 *       argos_retention_emergency_drops_total{table}};
 *   <li>from {@code argos.ops.disk.stop-ratio} (0.95) pauses the ClickHouse inserts (WAL only,
 *       within its bound) and resumes them below 0.93 ({@link EmergencyPolicy}).
 * </ul>
 *
 * The drops use the migration account ({@code ALTER ... DROP PARTITION ID}, 13 §7.3), the partition
 * list the query account ({@code system.parts}). A failed drop is logged and tried again at the
 * next step.
 */
public final class EmergencyRetention {

  /** Partitions remembered as dropped by this service (so the TTL tracking ignores them). */
  static final int REMEMBERED = 1_000;

  /** Timeout of one ClickHouse statement. */
  static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(5);

  private static final Logger LOG = LoggerFactory.getLogger(EmergencyRetention.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Where the partitions come from and go (ClickHouse; a fake in unit tests). */
  public interface Partitions {

    /** The active day partitions of the tables of {@link EmergencyOrder#GROUPS}. */
    List<Candidate> candidates() throws Exception;

    /** Drops one partition. */
    void drop(Candidate candidate) throws Exception;
  }

  /** What the inserts do (the batch writer; a fake in unit tests). */
  public interface InsertGate {

    /** Pauses the inserts. */
    void pause();

    /** Resumes the inserts. */
    void resume();
  }

  /**
   * The result of one step.
   *
   * @param ratio the observed ratio (NaN: unknown)
   * @param state the threshold state after the step
   * @param dropped the partition dropped in this step, if any
   */
  public record Step(double ratio, EmergencyPolicy.State state, Optional<Candidate> dropped) {}

  private final EmergencyPolicy policy;
  private final Partitions partitions;
  private final StorageOpsRepository events;
  private final AuditService audit;
  private final InsertGate inserts;
  private final MeterRegistry registry;
  private final Clock clock;
  private final ArgosProperties.Ops ops;
  private final Set<String> dropped = new LinkedHashSet<>(); // guarded by this
  private boolean nothingLeftLogged;
  private boolean warning;
  private volatile boolean evaluated;
  private volatile boolean active;
  private volatile boolean paused;

  /**
   * @param ops {@code argos.ops.*}
   * @param partitions partition list and drop
   * @param events the {@code events} rows, or null (unit tests)
   * @param audit the audit service
   * @param inserts the insert gate
   * @param registry meters
   * @param clock UTC clock (today's partition)
   */
  public EmergencyRetention(
      ArgosProperties.Ops ops,
      Partitions partitions,
      StorageOpsRepository events,
      AuditService audit,
      InsertGate inserts,
      MeterRegistry registry,
      Clock clock) {
    this.ops = ops;
    this.policy =
        new EmergencyPolicy(
            ops.disk().warnRatio(),
            ops.disk().emergencyRatio(),
            ops.emergency().targetRatio(),
            ops.disk().stopRatio());
    this.partitions = partitions;
    this.events = events;
    this.audit = audit;
    this.inserts = inserts;
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * The ClickHouse partitions of {@code clickhouse} (query account lists, migration account drops).
   */
  public static Partitions clickHouse(ArgosProperties.ClickHouse clickhouse) {
    String database = clickhouse.database();
    String prefix = clickhouse.tablePrefix();
    return new Partitions() {
      @Override
      public List<Candidate> candidates() throws Exception {
        List<Candidate> out = new ArrayList<>();
        try (TableCatalog.ReaderSession reader =
            TableCatalog.openReader(clickhouse, Duration.ofSeconds(60))) {
          for (List<String> group : EmergencyOrder.GROUPS) {
            for (String table : group) {
              for (TableCatalog.PartitionInfo p :
                  TableCatalog.partitions(reader, database, prefix + table)) {
                out.add(new Candidate(table, p.id(), p.rows(), p.bytes()));
              }
            }
          }
        }
        return out;
      }

      @Override
      public void drop(Candidate candidate) throws Exception {
        try (RollupPartitions.Session sql = RollupPartitions.open(clickhouse, STATEMENT_TIMEOUT)) {
          TableCatalog.dropPartition(
              sql, database + "." + prefix + candidate.table(), candidate.partitionId());
        }
      }
    };
  }

  /** The batch writer as the insert gate. */
  public static InsertGate writer(ClickHouseBatchWriter writer) {
    return new InsertGate() {
      @Override
      public void pause() {
        writer.pauseInserts();
      }

      @Override
      public void resume() {
        writer.resumeInserts();
      }
    };
  }

  /**
   * One step with the used ratio of the fullest ClickHouse disk (NaN when unknown: nothing changes,
   * nothing is dropped).
   */
  public synchronized Step step(double ratio) {
    EmergencyPolicy.State state = policy.observe(ratio);
    evaluated = policy.evaluated();
    active = state.active();
    paused = state.paused();
    if (state.changedPaused()) {
      if (state.paused()) {
        LOG.error(
            "ClickHouse disk at {}: inserts paused at {} or more, data is kept in the WAL only"
                + " (bound argos.wal.max-bytes) until the disk is below {}",
            percent(ratio),
            percent(ops.disk().stopRatio()),
            percent(policy.resumeRatio()));
        inserts.pause();
      } else {
        LOG.warn("ClickHouse disk at {}: inserts resumed", percent(ratio));
        inserts.resume();
      }
    }
    if (state.changedActive()) {
      if (state.active()) {
        nothingLeftLogged = false;
        LOG.error(
            "ClickHouse disk at {} (emergency ratio {}): emergency retention started, the oldest"
                + " raw day partitions are dropped until the disk is below {}",
            percent(ratio),
            percent(ops.disk().emergencyRatio()),
            percent(ops.emergency().targetRatio()));
      } else {
        LOG.warn("ClickHouse disk at {}: emergency retention stopped", percent(ratio));
      }
    }
    if (!Double.isNaN(ratio) && state.warning() != warning) {
      warning = state.warning();
      if (warning) {
        LOG.warn(
            "ClickHouse disk at {} (warning ratio {}): free disk space or shorten the retention",
            percent(ratio),
            percent(ops.disk().warnRatio()));
      } else {
        LOG.info("ClickHouse disk at {}: below the warning ratio again", percent(ratio));
      }
    }
    if (!state.active()) {
      return new Step(ratio, state, Optional.empty());
    }
    return new Step(ratio, state, dropOne(ratio));
  }

  private Optional<Candidate> dropOne(double ratio) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    Optional<Candidate> next;
    try {
      next = EmergencyOrder.next(partitions.candidates(), today);
    } catch (Exception e) {
      LOG.warn(
          "Emergency retention could not list the partitions; retried at the next step: {}",
          oneLine(e));
      return Optional.empty();
    }
    if (next.isEmpty()) {
      if (!nothingLeftLogged) {
        nothingLeftLogged = true;
        LOG.error(
            "Emergency retention has nothing left to drop (only today's raw partitions and the"
                + " rollups remain) and the ClickHouse disk is at {}: add disk space",
            percent(ratio));
      }
      return Optional.empty();
    }
    Candidate c = next.get();
    long started = System.nanoTime();
    try {
      partitions.drop(c);
    } catch (Exception e) {
      LOG.warn(
          "Emergency retention could not drop partition {} of {}; retried at the next step: {}",
          c.partitionId(),
          c.table(),
          oneLine(e));
      return Optional.empty();
    }
    long dropMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
    remember(c);
    Counter.builder("argos.retention.emergency.drops")
        .tag("table", c.table())
        .description("Partitions dropped by the emergency retention (F-S23)")
        .register(registry)
        .increment();
    LOG.warn(
        "Emergency retention dropped partition {} of {} ({} rows, {} bytes, {} ms); ClickHouse disk"
            + " at {}",
        c.partitionId(),
        c.table(),
        c.rows(),
        c.bytes(),
        dropMs,
        percent(ratio));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("table", c.table());
    detail.put("partition", c.partitionId());
    detail.put("day", String.valueOf(c.day()));
    detail.put("rows", c.rows());
    detail.put("bytes", c.bytes());
    detail.put("disk_used_ratio", Math.round(ratio * 10_000) / 10_000.0);
    detail.put("drop_ms", dropMs);
    if (events != null) {
      try {
        events.insertEmergencyEvent(
            UUID.randomUUID(),
            "Emergency retention: dropped " + c.table() + " " + c.day(),
            JSON.writeValueAsString(detail),
            clock.instant());
      } catch (RuntimeException e) {
        LOG.warn("The retention_emergency event could not be written: {}", oneLine(e));
      }
    }
    try {
      audit.record(
          AuditEvent.RETENTION_EMERGENCY_DROP,
          Actor.system(),
          new Target("partition", c.table() + "/" + c.partitionId(), c.table()),
          AuditResult.success(),
          null,
          detail);
    } catch (RuntimeException e) {
      LOG.warn("The emergency drop could not be audited: {}", oneLine(e));
    }
    return next;
  }

  private void remember(Candidate c) {
    dropped.add(c.table() + "/" + c.partitionId());
    Iterator<String> it = dropped.iterator();
    while (dropped.size() > REMEMBERED && it.hasNext()) {
      it.next();
      it.remove();
    }
  }

  /** Whether this service dropped partition {@code partitionId} of {@code table}. */
  public synchronized boolean droppedByEmergency(String table, String partitionId) {
    return dropped.contains(table + "/" + partitionId);
  }

  /** Whether the emergency retention is on; null before the first known ratio. */
  public Boolean activeOrNull() {
    return evaluated ? active : null;
  }

  /** Whether the emergency retention is on now (false before the first known ratio). */
  public boolean active() {
    return active;
  }

  /** Whether the ratio is at or above the warning ratio (false before the first known ratio). */
  public synchronized boolean warning() {
    return warning;
  }

  /** Whether the inserts are paused by the stop ratio. */
  public boolean insertsPaused() {
    return paused;
  }

  private static String percent(double ratio) {
    return Double.isNaN(ratio) ? "unknown" : String.format(Locale.ROOT, "%.1f %%", ratio * 100);
  }

  private static String oneLine(Throwable e) {
    String m = e.getMessage();
    String text =
        e.getClass().getSimpleName()
            + (m == null ? "" : ": " + m.replaceAll("[\r\n\t]+", " ").strip());
    return text.length() <= 300 ? text : text.substring(0, 300);
  }
}
