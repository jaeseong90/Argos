/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import com.google.protobuf.InvalidProtocolBufferException;
import io.argos.proto.LatencyBuckets;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.TransactionFlags;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.ingest.hash.Xxh3;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.RowBinaryBuffer;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.api.WalDataPayload;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decodes one WAL data record into ClickHouse rows (12 §3.5 steps 1-7, §3.6): the column mapping of
 * proto/README.md "Mapping to the ClickHouse V001 columns" plus the V002 columns of ADR-0010 C-144,
 * the writer rules of ADR-0010 C-143, C-138 and C-141, the timestamp guard ({@link TimestampGuard},
 * D-15 with C-129), the error group fingerprint ({@link ErrorGroupFingerprinter}, C-131), the Apdex
 * stamp of the receipt (the app's T, or the service's override of the receipt's snapshot, {@link
 * SessionContext#apdexTMsFor}) and the dictionary overflow mapping ({@link DictionaryResolver}).
 *
 * <p>Everything comes from the record: its header ({@code instance_id}, {@code epoch}, {@code
 * received_ms}, {@code ts_correction_ms}) and its payload ({@link WalDataPayload}: session values
 * and the batch), never from the current time or a live session, so the live path, recovery and the
 * re-insert of an intent produce the same rows (D-13, C-129 (3)). The one exception is the overflow
 * mapping of the dictionary caps, whose counters live in memory.
 *
 * <p>Bad data never throws: an undecodable record or item is skipped and counted ({@code
 * argos_ingest_errors_total{stage}}), the rest is kept (12 §3.9). Each item (transaction, profile
 * and step, metric sample, statistic, counter) is converted on its own: a {@link RuntimeException}
 * while converting it removes its partial row and skips that item only ({@code stage="decode"}).
 * Thread-safe (no state but the rate limit of one log line; the collaborators are thread-safe).
 */
@Component
public final class RecordDecoder {

  /** Prefix of the metric rows made from {@code StatBatch.counters} (12 §3.6). */
  public static final String AGENT_METRIC_PREFIX = "argos_agent_";

  private static final int ENUM8_COUNTER = 2;

  /** At most one WARN line per this interval for items skipped by a decode failure. */
  private static final long SKIP_WARN_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  private static final Logger LOG = LoggerFactory.getLogger(RecordDecoder.class);

  private final DictionaryResolver dictionary;
  private final ErrorGroupRecorder errorGroups;
  private final IngestMetrics metrics;
  private final AtomicLong lastSkipWarnNanos =
      new AtomicLong(System.nanoTime() - SKIP_WARN_INTERVAL_NANOS);

  public RecordDecoder(
      DictionaryResolver dictionary, ErrorGroupRecorder errorGroups, IngestMetrics metrics) {
    this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
    this.errorGroups = Objects.requireNonNull(errorGroups, "errorGroups");
    this.metrics = Objects.requireNonNull(metrics, "metrics");
  }

  /**
   * One WARN line a minute at most: the table and the exception class only (its message may carry
   * collected data); every skipped item is counted.
   */
  private void warnSkipped(Table table, RuntimeException e) {
    long now = System.nanoTime();
    long last = lastSkipWarnNanos.get();
    if (now - last >= SKIP_WARN_INTERVAL_NANOS && lastSkipWarnNanos.compareAndSet(last, now)) {
      LOG.warn(
          "An item for {} failed to decode and was skipped ({}); the rest of its batch is kept."
              + " Further skips this minute are only counted in argos_ingest_errors_total"
              + "{stage=\"decode\"}",
          table.tableName(),
          e.getClass().getName());
    }
  }

  /**
   * The rows of one record.
   *
   * @param session the context rebuilt from the record ({@code null} when the payload was
   *     unreadable)
   * @param rows rows per table (only tables with rows)
   * @param points XLog points of rows with {@code late = 0}
   * @param metrics samples of the realtime {@code counter} series ({@link MetricPoint.Series}) of
   *     rows with {@code late = 0}, only on the normal path ({@code sideEffects})
   */
  public record Decoded(
      SessionContext session,
      Map<Table, EncodedRows> rows,
      List<XlogPoint> points,
      List<MetricPoint> metrics) {

    static final Decoded EMPTY = new Decoded(null, Map.of(), List.of(), List.of());

    /** A decoded record without metric samples. */
    public Decoded(SessionContext session, Map<Table, EncodedRows> rows, List<XlogPoint> points) {
      this(session, rows, points, List.of());
    }
  }

  /**
   * Decodes {@code record}.
   *
   * @param tables the tables to make rows for
   * @param sideEffects {@code true} on the normal path: counters, error group upserts, dictionary
   *     lookups of unknown ids; {@code false} when recovery rebuilds the block of an intent (the
   *     record was decoded before)
   */
  public Decoded decode(WalRecord record, Set<Table> tables, boolean sideEffects) {
    WalDataPayload payload;
    try {
      payload = WalDataPayload.decode(record.payload());
    } catch (IllegalArgumentException e) {
      if (sideEffects) {
        metrics.error("wal_payload");
      }
      return Decoded.EMPTY;
    }
    SessionContext session =
        payload.context(
            record.instanceId(), record.epoch(), record.receivedMs(), record.tsCorrectionMs());
    Context c = new Context(session, sideEffects);
    try {
      switch (record.type()) {
        case TX -> {
          if (tables.contains(Table.XLOG)) {
            transactions(c, TransactionBatch.parseFrom(payload.batch()));
          }
        }
        case PROFILE -> {
          if (tables.contains(Table.PROFILE_STEP)) {
            profiles(c, ProfileBatch.parseFrom(payload.batch()));
          }
        }
        case METRIC -> {
          if (tables.contains(Table.METRIC)) {
            metrics(c, MetricBatch.parseFrom(payload.batch()));
          }
        }
        case STAT -> stats(c, StatBatch.parseFrom(payload.batch()), tables);
        default -> {
          return Decoded.EMPTY;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      if (sideEffects) {
        metrics.error("decode");
      }
      return new Decoded(session, Map.of(), List.of());
    }
    return c.result();
  }

  // --- xlog ---------------------------------------------------------------------------------

  private void transactions(Context c, TransactionBatch batch) {
    RowBinaryBuffer out = c.buffer(Table.XLOG, batch.getRecordsCount() * 256);
    long versionId = sessionVersionId(c);
    for (TransactionRecord r : batch.getRecordsList()) {
      c.item(Table.XLOG, () -> transaction(c, out, r, versionId));
    }
  }

  /** One xlog row (or none: unknown kind, rejected by the guard). */
  private void transaction(Context c, RowBinaryBuffer out, TransactionRecord r, long versionId) {
    SessionContext s = c.session;
    TransactionKind kind = r.getKind();
    if (!isKnown(kind)) {
      c.error("enum");
      return;
    }
    TimestampGuard.Result t =
        TimestampGuard.guard(
            r.getStartTsMs(), s.tsCorrectionMs(), s.receivedAtMs(), TimestampGuard.GRID_MS);
    c.count(t);
    if (t.rejected()) {
      return;
    }
    if (t.late() && c.sideEffects) {
      metrics.late(1);
    }
    long serviceId = c.resolve(DictionaryKind.SERVICE, r.getServiceId());
    long appVersionId =
        r.getAppVersionId() != 0
            ? c.resolve(DictionaryKind.APP_VERSION, r.getAppVersionId())
            : versionId;
    boolean error = r.getError();
    long errorGroupId = 0;
    if (error) {
      String pattern = ErrorGroupFingerprinter.pattern(r.getErrorMessage());
      long classId = r.getErrorClassId();
      long frameId = r.getErrorTopFrameId();
      if (classId != 0 || !pattern.isEmpty() || frameId != 0) {
        errorGroupId = ErrorGroupFingerprinter.groupId(classId, pattern, frameId);
        if (c.sideEffects) {
          errorGroups.observe(s.appId(), errorGroupId, classId, frameId, pattern, t.tsMs());
          c.resolve(DictionaryKind.FRAME, frameId);
        }
      }
    }
    int httpStatus =
        kind == TransactionKind.BATCH
            ? (error ? 1 : 0)
            : u16(Integer.toUnsignedLong(r.getHttpStatus()));
    int elapsedMs = (int) u32(microsToMillis(r.getElapsedUs()));

    out.i32(s.appId());
    out.string(s.env());
    out.i32(s.instanceId());
    out.i64(t.tsMs());
    out.i64(r.getTxId());
    out.u8(kind.getNumber());
    out.fixed(r.getTraceId().toByteArray(), ProtocolLimits.TRACE_ID_BYTES);
    out.i64(r.getSpanId());
    out.i64(r.getParentSpanId());
    out.u8(r.getIncomingTrace() ? 1 : 0);
    out.i32(r.getCallerAppId());
    out.i64(serviceId);
    out.i64(appVersionId);
    out.string(r.getHttpMethod());
    out.u16(httpStatus);
    out.i32(elapsedMs);
    out.u8(error ? 1 : 0);
    out.i64(c.resolve(DictionaryKind.CLASS, r.getErrorClassId()));
    out.i64(errorGroupId);
    // D-17: the T of the receipt, the service's override when the app had one (WO-27).
    out.u16(u16(s.apdexTMsFor(r.getServiceId())));
    out.ipv6(s.ipMode().storesRemoteIp() ? r.getRemoteIp().toByteArray() : null);
    out.u8(s.ipMode().enum8());
    out.string(s.userIdMode().storesUserId() ? r.getUserId() : "");
    out.u8(s.userIdMode().enum8());
    out.string(r.getRequestId());
    out.u16(u16(Integer.toUnsignedLong(r.getSqlCount())));
    out.i32(r.getSqlTimeMs());
    out.i32(r.getDbcWaitMs());
    out.u16(u16(Integer.toUnsignedLong(r.getExtCount())));
    out.i32(r.getExtTimeMs());
    out.i32(cpuTimeMs(r));
    out.i64(allocBytes(r));
    out.i64(c.resolve(DictionaryKind.THREAD, r.getThreadNameId()));
    out.u8(r.getProfileCaptured() ? 1 : 0);
    out.u8(r.getStreaming() ? 1 : 0);
    out.u8(r.getTimedOut() ? 1 : 0);
    out.u16(u16(Integer.toUnsignedLong(r.getDispatchCount())));
    out.u8(t.late() ? 1 : 0);
    out.u16((r.getFlags() | (r.getVirtualThread() ? TransactionFlags.VIRTUAL_THREAD : 0)) & 0xFFFF);
    out.varint(r.getSqlIdsCount());
    for (int i = 0; i < r.getSqlIdsCount(); i++) {
      out.i64(c.resolve(DictionaryKind.SQL_TEXT, r.getSqlIds(i)));
    }
    out.string(r.getUrlPath());
    out.varint(r.getHeadersCount());
    for (KeyValue header : r.getHeadersList()) {
      out.string(header.getKey());
      out.string(header.getValue());
    }
    c.rows(Table.XLOG);
    if (!t.late()) {
      c.points.add(
          new XlogPoint(
              r.getTxId(), t.tsMs(), elapsedMs, kind, serviceId, error, httpStatus, errorGroupId));
    }
  }

  /** ADR-0010 C-143 (6): the APP_VERSION key of the session's version for records that send 0. */
  private long sessionVersionId(Context c) {
    String version = c.session.appVersion();
    if (version.isEmpty()) {
      return 0;
    }
    long id = Xxh3.hash64(version);
    if (c.sideEffects) {
      dictionary.ensureServerEntry(c.session.appId(), DictionaryKind.APP_VERSION, id, version);
    }
    return dictionary.isOverflowed(c.session.appId(), DictionaryKind.APP_VERSION, id)
        ? DictionaryResolver.OVERFLOW_ID
        : id;
  }

  // --- profile_step ------------------------------------------------------------------------

  private void profiles(Context c, ProfileBatch batch) {
    int steps = 0;
    for (TransactionProfile p : batch.getProfilesList()) {
      steps += p.getStepsCount();
    }
    RowBinaryBuffer out = c.buffer(Table.PROFILE_STEP, steps * 96);
    for (TransactionProfile p : batch.getProfilesList()) {
      c.item(Table.PROFILE_STEP, () -> profile(c, out, p));
    }
  }

  /** The rows of one profile, one per step (a step that fails alone is skipped alone). */
  private void profile(Context c, RowBinaryBuffer out, TransactionProfile p) {
    SessionContext s = c.session;
    if (!isKnown(p.getKind())) {
      c.error("enum");
      return;
    }
    TimestampGuard.Result t =
        TimestampGuard.guard(
            p.getStartTsMs(), s.tsCorrectionMs(), s.receivedAtMs(), TimestampGuard.GRID_MS);
    c.count(t);
    if (t.rejected()) {
      return;
    }
    for (ProfileStep step : p.getStepsList()) {
      c.item(Table.PROFILE_STEP, () -> step(c, out, p, t, step));
    }
  }

  private void step(
      Context c,
      RowBinaryBuffer out,
      TransactionProfile p,
      TimestampGuard.Result t,
      ProfileStep step) {
    SessionContext s = c.session;
    StepType type = step.getType();
    if (type == StepType.STEP_TYPE_UNSPECIFIED || type == StepType.UNRECOGNIZED) {
      c.error("enum");
      return;
    }
    long nameId = name(c, type, step);
    long targetId =
        DictKinds.stepTarget(type)
            .map(kind -> c.resolve(kind, step.getTargetId()))
            .orElse(step.getTargetId());
    long elapsedUs = Integer.toUnsignedLong(step.getElapsedUs());
    long fetchUs = Integer.toUnsignedLong(step.getFetchUs());
    long execMs = type == StepType.SQL ? microsToMillis(Math.max(0, elapsedUs - fetchUs)) : 0;

    out.i32(s.appId());
    out.string(s.env());
    out.i32(s.instanceId());
    out.i64(p.getTxId());
    out.i64(t.tsMs());
    out.u8(p.getKind().getNumber());
    out.u16(u16(Integer.toUnsignedLong(step.getSeq())));
    out.u16(u16(Integer.toUnsignedLong(step.getParentSeq())));
    out.u8(type.getNumber());
    out.i32((int) u32(microsToMillis(Integer.toUnsignedLong(step.getStartOffsetUs()))));
    out.i32((int) u32(microsToMillis(elapsedUs)));
    out.i32((int) u32(execMs));
    out.i32((int) u32(microsToMillis(fetchUs)));
    out.i64(nameId);
    out.i64(targetId);
    out.i32(step.getRows());
    out.u16(status(c, type, step));
    out.u8(step.getError() ? 1 : 0);
    out.u16(step.getRepeatCount() == 0 ? 1 : u16(Integer.toUnsignedLong(step.getRepeatCount())));
    out.i32(step.getBatchSize());
    out.string(step.getDetail());
    out.string(""); // detail_enc: envelope encryption of full mode needs the master key (WO-28)
    out.varint(step.getFrameIdsCount());
    for (int i = 0; i < step.getFrameIdsCount(); i++) {
      out.i64(c.resolve(DictionaryKind.FRAME, step.getFrameIds(i)));
    }
    out.u8(p.getTruncated() ? 1 : 0);
    out.i32(p.getTotalSteps());
    c.rows(Table.PROFILE_STEP);
  }

  /** {@code name_id}: of the summarized step type for SUMMARY (C-141), else by the step type. */
  private static long name(Context c, StepType type, ProfileStep step) {
    StepType named = type;
    if (type == StepType.SUMMARY) {
      StepType summarized = StepType.forNumber(step.getStatus());
      named = summarized == null ? StepType.STEP_TYPE_UNSPECIFIED : summarized;
    }
    return DictKinds.stepName(named)
        .map(kind -> c.resolve(kind, step.getNameId()))
        .orElse(step.getNameId());
  }

  /**
   * {@code profile_step.status} (proto/README.md, ADR-0010 C-141, C-143 (5)): SUMMARY keeps its
   * summarized step type; otherwise the HTTP status, or when 0 the {@code FailureKind} number, an
   * unknown failure stored as {@code OTHER_ERROR}.
   */
  private int status(Context c, StepType type, ProfileStep step) {
    if (type == StepType.SUMMARY || step.getStatus() != 0) {
      return u16(Integer.toUnsignedLong(step.getStatus()));
    }
    FailureKind failure = step.getFailure();
    if (failure == FailureKind.UNRECOGNIZED) {
      if (c.sideEffects) {
        metrics.unknownEnum("failure", 1);
      }
      return FailureKind.OTHER_ERROR.getNumber();
    }
    return failure.getNumber();
  }

  // --- metric -------------------------------------------------------------------------------

  private void metrics(Context c, MetricBatch batch) {
    Map<Integer, MetricSeries> series = new HashMap<>();
    for (MetricSeries m : batch.getSeriesList()) {
      series.put(m.getIndex(), m);
    }
    RowBinaryBuffer out = c.buffer(Table.METRIC, batch.getSamplesCount() * 64);
    for (MetricSample sample : batch.getSamplesList()) {
      c.item(Table.METRIC, () -> sample(c, out, series.get(sample.getSeriesIndex()), sample));
    }
  }

  private void sample(Context c, RowBinaryBuffer out, MetricSeries m, MetricSample sample) {
    SessionContext s = c.session;
    if (m == null) {
      c.error("metric_series");
      return;
    }
    MetricKind kind = m.getKind();
    if (kind != MetricKind.GAUGE && kind != MetricKind.COUNTER) {
      c.error("enum");
      return;
    }
    TimestampGuard.Result t =
        TimestampGuard.guard(
            sample.getTsMs(), s.tsCorrectionMs(), s.receivedAtMs(), TimestampGuard.GRID_METRIC_MS);
    c.count(t);
    if (t.rejected()) {
      return;
    }
    metricRow(
        out, s, m.getName(), kind.getNumber(), t.tsMs(), sample.getValue(), sample.getDelta());
    c.rows(Table.METRIC);
    if (!t.late() && c.sideEffects) {
      MetricPoint.Series series = MetricPoint.Series.of(m.getName());
      if (series != null) {
        double v = series == MetricPoint.Series.GC_PAUSE ? sample.getDelta() : sample.getValue();
        c.jvmPoints.add(new MetricPoint(series, t.tsMs(), v));
      }
    }
  }

  private static void metricRow(
      RowBinaryBuffer out,
      SessionContext s,
      String name,
      int kind,
      long tsMs,
      double value,
      double delta) {
    out.i32(s.appId());
    out.string(s.env());
    out.i32(s.instanceId());
    out.string(name);
    out.u8(kind);
    out.i32((int) seconds(tsMs));
    out.f64(value);
    out.f64(delta);
  }

  // --- statistics ---------------------------------------------------------------------------

  private void stats(Context c, StatBatch batch, Set<Table> tables) {
    SessionContext s = c.session;
    long windowStart = batch.getWindowStartMs();
    if (tables.contains(Table.SQL_STAT_RAW) || tables.contains(Table.EXT_STAT_RAW)) {
      TimestampGuard.Result t =
          TimestampGuard.guard(
              windowStart, s.tsCorrectionMs(), s.receivedAtMs(), TimestampGuard.GRID_STAT_MS);
      if (tables.contains(Table.SQL_STAT_RAW)) {
        sqlStats(c, batch, t);
      }
      if (tables.contains(Table.EXT_STAT_RAW)) {
        extStats(c, batch, t);
      }
    }
    if (tables.contains(Table.METRIC) && batch.getCountersCount() > 0) {
      TimestampGuard.Result t =
          TimestampGuard.guard(
              windowStart, s.tsCorrectionMs(), s.receivedAtMs(), TimestampGuard.GRID_METRIC_MS);
      RowBinaryBuffer out = c.buffer(Table.METRIC, batch.getCountersCount() * 64);
      for (CounterDelta counter : batch.getCountersList()) {
        c.item(Table.METRIC, () -> counter(c, out, t, counter));
      }
    }
  }

  private void counter(Context c, RowBinaryBuffer out, TimestampGuard.Result t, CounterDelta d) {
    c.count(t);
    if (t.rejected()) {
      return;
    }
    String name =
        d.getName().startsWith(AGENT_METRIC_PREFIX)
            ? d.getName()
            : AGENT_METRIC_PREFIX + d.getName();
    double delta = unsignedToDouble(d.getDelta());
    metricRow(out, c.session, name, ENUM8_COUNTER, t.tsMs(), delta, delta);
    c.rows(Table.METRIC);
  }

  private void sqlStats(Context c, StatBatch batch, TimestampGuard.Result t) {
    RowBinaryBuffer out = c.buffer(Table.SQL_STAT_RAW, batch.getSqlCount() * 128);
    for (SqlStat stat : batch.getSqlList()) {
      c.item(Table.SQL_STAT_RAW, () -> sqlStat(c, out, t, stat));
    }
  }

  private void sqlStat(Context c, RowBinaryBuffer out, TimestampGuard.Result t, SqlStat stat) {
    SessionContext s = c.session;
    if (!isKnown(stat.getKind())) {
      c.error("enum");
      return;
    }
    c.count(t);
    if (t.rejected()) {
      return;
    }
    long sumMs = microsToMillis(stat.getSumUs());
    long fetchMs;
    long execMs;
    // ADR-0010 C-143 (2): stored exec + fetch = sum; a missing value is the remainder.
    if (stat.hasSumFetchUs()) {
      fetchMs = Math.min(microsToMillis(stat.getSumFetchUs()), sumMs);
      execMs = sumMs - fetchMs;
    } else if (stat.hasSumExecUs()) {
      execMs = Math.min(microsToMillis(stat.getSumExecUs()), sumMs);
      fetchMs = sumMs - execMs;
    } else {
      execMs = sumMs;
      fetchMs = 0;
    }
    out.i32(s.appId());
    out.string(s.env());
    out.i32(s.instanceId());
    out.i32((int) seconds(t.tsMs()));
    out.u8(stat.getKind().getNumber());
    out.i64(c.resolve(DictionaryKind.SQL_TEXT, stat.getSqlId()));
    out.i64(c.resolve(DictionaryKind.DB_TARGET, stat.getDbId()));
    out.i32(stat.getCount());
    out.i32(stat.getErrorCount());
    out.i64(sumMs);
    out.i64(execMs);
    out.i64(fetchMs);
    out.i32((int) u32(microsToMillis(Integer.toUnsignedLong(stat.getMaxUs()))));
    out.i64(stat.getFetchRows());
    buckets(out, stat.getBucketsList());
    c.rows(Table.SQL_STAT_RAW);
  }

  private void extStats(Context c, StatBatch batch, TimestampGuard.Result t) {
    RowBinaryBuffer out = c.buffer(Table.EXT_STAT_RAW, batch.getExtCount() * 128);
    for (ExtStat stat : batch.getExtList()) {
      c.item(Table.EXT_STAT_RAW, () -> extStat(c, out, t, stat));
    }
  }

  private void extStat(Context c, RowBinaryBuffer out, TimestampGuard.Result t, ExtStat stat) {
    SessionContext s = c.session;
    if (!isKnown(stat.getKind())) {
      c.error("enum");
      return;
    }
    c.count(t);
    if (t.rejected()) {
      return;
    }
    out.i32(s.appId());
    out.string(s.env());
    out.i32(s.instanceId());
    out.i32((int) seconds(t.tsMs()));
    out.u8(stat.getKind().getNumber());
    out.i64(c.resolve(DictionaryKind.HOST, stat.getHostId()));
    out.i32(stat.getCount());
    out.i32(stat.getErrorCount());
    out.i32(stat.getTimeoutCount());
    out.i32(stat.getConnectFailCount());
    out.i64(microsToMillis(stat.getSumUs()));
    out.i32((int) u32(microsToMillis(Integer.toUnsignedLong(stat.getMaxUs()))));
    buckets(out, stat.getBucketsList());
    c.rows(Table.EXT_STAT_RAW);
  }

  /** Exactly {@link LatencyBuckets#COUNT} counts: missing ones are 0, extra ones are dropped. */
  private static void buckets(RowBinaryBuffer out, List<Integer> buckets) {
    out.varint(LatencyBuckets.COUNT);
    for (int i = 0; i < LatencyBuckets.COUNT; i++) {
      out.i32(i < buckets.size() ? buckets.get(i) : 0);
    }
  }

  // --- helpers ------------------------------------------------------------------------------

  private static boolean isKnown(TransactionKind kind) {
    return kind == TransactionKind.HTTP
        || kind == TransactionKind.BATCH
        || kind == TransactionKind.MESSAGE;
  }

  /** Microseconds (unsigned) to milliseconds, rounded half up (11 §4.4). */
  static long microsToMillis(long micros) {
    if (micros < 0) {
      return Long.divideUnsigned(micros, 1000)
          + (Long.remainderUnsigned(micros, 1000) >= 500 ? 1 : 0);
    }
    return micros / 1000 + (micros % 1000 >= 500 ? 1 : 0);
  }

  static int u16(long value) {
    return (int) Math.min(Math.max(value, 0), 0xFFFF);
  }

  static long u32(long value) {
    return Math.min(Math.max(value, 0), 0xFFFF_FFFFL);
  }

  /** Epoch seconds of a {@code DateTime} column (UInt32). */
  static long seconds(long tsMs) {
    return u32(Math.floorDiv(tsMs, 1000L));
  }

  private static int cpuTimeMs(TransactionRecord r) {
    if (!r.hasCpuTimeUs() || r.getCpuTimeUs() < 0) {
      return -1; // D-23: not measured
    }
    return (int) Math.min(microsToMillis(r.getCpuTimeUs()), Integer.MAX_VALUE);
  }

  private static long allocBytes(TransactionRecord r) {
    if (!r.hasAllocBytes()) {
      return -1; // D-23: not measured
    }
    long value = r.getAllocBytes();
    return value < 0 ? Long.MAX_VALUE : value;
  }

  private static double unsignedToDouble(long value) {
    return value >= 0 ? value : (double) (value >>> 1) * 2.0 + (value & 1);
  }

  /** Per-record decode state. */
  private final class Context {
    final SessionContext session;
    final boolean sideEffects;
    final Map<Table, RowBinaryBuffer> buffers = new EnumMap<>(Table.class);
    final Map<Table, Integer> counts = new EnumMap<>(Table.class);
    final List<XlogPoint> points = new ArrayList<>();
    final List<MetricPoint> jvmPoints = new ArrayList<>();

    Context(SessionContext session, boolean sideEffects) {
      this.session = session;
      this.sideEffects = sideEffects;
    }

    RowBinaryBuffer buffer(Table table, int sizeHint) {
      return buffers.computeIfAbsent(table, t -> new RowBinaryBuffer(Math.min(sizeHint, 1 << 22)));
    }

    void rows(Table table) {
      counts.merge(table, 1, Integer::sum);
    }

    long resolve(DictionaryKind kind, long id) {
      if (id == 0) {
        return 0;
      }
      if (sideEffects) {
        return dictionary.resolve(session.appId(), session.instanceId(), kind, id);
      }
      return dictionary.isOverflowed(session.appId(), kind, id)
          ? DictionaryResolver.OVERFLOW_ID
          : id;
    }

    void count(TimestampGuard.Result t) {
      if (!sideEffects) {
        return;
      }
      if (t.corrected()) {
        metrics.clamped(IngestMetrics.ClampKind.SESSION_OFFSET, 1);
      }
      if (t.clamped()) {
        metrics.clamped(IngestMetrics.ClampKind.RECORD_CLAMP, 1);
      }
      if (t.rejected()) {
        metrics.rejected(1);
      }
    }

    void error(String stage) {
      if (sideEffects) {
        metrics.error(stage);
      }
    }

    /**
     * Converts one item; {@code body} writes at most rows of {@code table} (and xlog points). A
     * {@link RuntimeException} skips that item only (12 §3.9: never the whole batch): its partial
     * row bytes, its rows and its points are removed, and it is counted {@code
     * argos_ingest_errors_total{stage="decode"}}. The rest of the record is kept.
     */
    void item(Table table, Runnable body) {
      RowBinaryBuffer before = buffers.get(table);
      int mark = before == null ? 0 : before.length();
      Integer rowsBefore = counts.get(table);
      int pointsBefore = points.size();
      int jvmPointsBefore = jvmPoints.size();
      try {
        body.run();
      } catch (RuntimeException e) {
        RowBinaryBuffer out = buffers.get(table);
        if (out != null) {
          out.truncate(mark);
        }
        if (rowsBefore == null) {
          counts.remove(table);
        } else {
          counts.put(table, rowsBefore);
        }
        points.subList(pointsBefore, points.size()).clear();
        jvmPoints.subList(jvmPointsBefore, jvmPoints.size()).clear();
        error("decode");
        if (sideEffects) {
          warnSkipped(table, e);
        }
      }
    }

    Decoded result() {
      Map<Table, EncodedRows> rows = new EnumMap<>(Table.class);
      for (Map.Entry<Table, Integer> e : counts.entrySet()) {
        rows.put(e.getKey(), new EncodedRows(e.getValue(), buffers.get(e.getKey()).toByteString()));
      }
      return new Decoded(session, rows, points, jvmPoints);
    }
  }
}
