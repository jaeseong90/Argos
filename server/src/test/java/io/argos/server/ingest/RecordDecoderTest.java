/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
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
import io.argos.server.ingest.api.MetricPoint.Series;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.ingest.hash.Xxh3;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.api.Table;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The writer mapping of proto/README.md "Mapping to the ClickHouse V001 columns", the V002 columns
 * (ADR-0010 C-144) and the writer rules of ADR-0010 C-143, C-138, C-141, read back column by column
 * through the {@link Table} catalogue.
 */
@Tag("req:D-13")
@Tag("req:C-143")
@Tag("req:C-144")
class RecordDecoderTest {

  private static final long RECEIVED = 1_767_225_000_000L;

  private SimpleMeterRegistry registry;
  private IngestMetrics metrics;
  private RecordDecoder decoder;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new IngestMetrics(registry);
    decoder = IngestTestDoubles.decoder(new IngestTestDoubles.MemoryStore(), metrics);
  }

  private List<Map<String, Object>> rows(WalRecord record, Table table) {
    RecordDecoder.Decoded d = decoder.decode(record, EnumSet.allOf(Table.class), true);
    var rows = d.rows().get(table);
    if (rows == null) {
      return List.of();
    }
    List<Map<String, Object>> parsed = RowBinaryReader.rows(table, rows.rowBinary().toByteArray());
    assertThat(parsed).hasSize(rows.rowCount());
    return parsed;
  }

  private static TransactionRecord.Builder http() {
    return TransactionRecord.newBuilder()
        .setTxId(123456789L)
        .setTraceId(
            ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}))
        .setSpanId(11)
        .setParentSpanId(12)
        .setIncomingTrace(true)
        .setCallerAppId(5)
        .setKind(TransactionKind.HTTP)
        .setServiceId(1001)
        .setStartTsMs(RECEIVED - 1_000)
        .setElapsedUs(1_499)
        .setHttpMethod("GET")
        .setHttpStatus(200)
        .setUrlPath("/api/orders/42")
        .setRemoteIp(ByteString.copyFrom(new byte[] {10, 0, 0, 1}))
        .setUserId("u-1")
        .setSqlCount(70_000)
        .setSqlTimeMs(30)
        .setExtCount(2)
        .setExtTimeMs(40)
        .setDbcWaitMs(5)
        .setThreadNameId(2002)
        .setVirtualThread(true)
        .setProfileCaptured(true)
        .addSqlIds(3003)
        .addSqlIds(3004)
        .setDispatchCount(1)
        .setStreaming(true)
        .addHeaders(KeyValue.newBuilder().setKey("x-tenant").setValue("acme"))
        .setRequestId("req-9")
        .setFlags(2);
  }

  @Test
  @DisplayName("C-144 / proto README: every xlog column has its source and conversion")
  void xlogColumns() {
    SessionContext s =
        IngestTestDoubles.session(IpMode.MASKED, UserIdMode.PLAIN, RECEIVED, 0, "1.4.2");
    TransactionRecord r = http().setCpuTimeUs(2_500).build();
    Map<String, Object> row =
        rows(
                IngestTestDoubles.record(
                    WalRecordType.TX, s, TransactionBatch.newBuilder().addRecords(r).build(), 1),
                Table.XLOG)
            .get(0);
    assertThat(row.get("app_id")).isEqualTo(7L);
    assertThat(row.get("env")).isEqualTo("prod");
    assertThat(row.get("instance_id")).isEqualTo(3L);
    assertThat(row.get("ts")).isEqualTo(RECEIVED - 1_000);
    assertThat(row.get("tx_id")).isEqualTo(123456789L);
    assertThat(row.get("kind")).isEqualTo(1);
    assertThat((byte[]) row.get("trace_id")).hasSize(16).startsWith(1, 2, 3);
    assertThat(row.get("span_id")).isEqualTo(11L);
    assertThat(row.get("parent_span_id")).isEqualTo(12L);
    assertThat(row.get("incoming_trace")).isEqualTo(1);
    assertThat(row.get("caller_app_id")).isEqualTo(5L);
    assertThat(row.get("service_id")).isEqualTo(1001L);
    // C-143 (6): app_version_id 0 -> the APP_VERSION key of the session's version.
    assertThat(row.get("app_version_id")).isEqualTo(Xxh3.hash64("1.4.2"));
    assertThat(row.get("http_method")).isEqualTo("GET");
    assertThat(row.get("http_status")).isEqualTo(200);
    assertThat(row.get("elapsed_ms")).isEqualTo(1L); // 1,499 us rounds half up to 1 ms
    assertThat(row.get("error")).isEqualTo(0);
    assertThat(row.get("error_group_id")).isEqualTo(0L);
    assertThat(row.get("apdex_t_ms")).isEqualTo(400);
    assertThat(row.get("remote_ip")).isEqualTo("10.0.0.1"); // ::ffff:10.0.0.1
    assertThat(row.get("ip_mode")).isEqualTo(IpMode.MASKED.enum8());
    assertThat(row.get("user_id")).isEqualTo("u-1");
    assertThat(row.get("user_id_mode")).isEqualTo(UserIdMode.PLAIN.enum8());
    assertThat(row.get("request_id")).isEqualTo("req-9");
    assertThat(row.get("sql_count")).isEqualTo(65_535); // saturates
    assertThat(row.get("sql_time_ms")).isEqualTo(30L);
    assertThat(row.get("dbc_wait_ms")).isEqualTo(5L);
    assertThat(row.get("ext_count")).isEqualTo(2);
    assertThat(row.get("ext_time_ms")).isEqualTo(40L);
    assertThat(row.get("cpu_time_ms")).isEqualTo(3); // 2,500 us -> 3 ms
    assertThat(row.get("alloc_bytes")).isEqualTo(-1L); // absent (D-23)
    assertThat(row.get("thread_name_id")).isEqualTo(2002L);
    assertThat(row.get("profile_captured")).isEqualTo(1);
    assertThat(row.get("streaming")).isEqualTo(1);
    assertThat(row.get("timed_out")).isEqualTo(0);
    assertThat(row.get("dispatch_count")).isEqualTo(1);
    assertThat(row.get("late")).isEqualTo(0);
    assertThat(row.get("flags")).isEqualTo(3); // leaked + virtual thread (C-69)
    assertThat(row.get("sql_ids")).isEqualTo(List.of(3003L, 3004L));
    assertThat(row.get("url_path")).isEqualTo("/api/orders/42");
    assertThat(row.get("headers")).isEqualTo(Map.of("x-tenant", "acme"));
    assertThat(row).containsOnlyKeys(Table.XLOG.columnNames());
  }

  @Test
  @DisplayName("C-143 (1)(3) BATCH http_status 1/0; OFF modes store no remote_ip and no user_id")
  void batchStatusAndOffModes() {
    SessionContext s = IngestTestDoubles.session(IpMode.OFF, UserIdMode.OFF, RECEIVED, 0, "");
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(
                http()
                    .setKind(TransactionKind.BATCH)
                    .setHttpStatus(0)
                    .setError(true)
                    .setErrorClassId(9))
            .addRecords(http().setKind(TransactionKind.BATCH).setHttpStatus(0).setError(false))
            .build();
    List<Map<String, Object>> rows =
        rows(IngestTestDoubles.record(WalRecordType.TX, s, batch, 2), Table.XLOG);
    assertThat(rows).extracting(r -> r.get("http_status")).containsExactly(1, 0);
    assertThat(rows).extracting(r -> r.get("remote_ip")).containsOnly("0:0:0:0:0:0:0:0");
    assertThat(rows).extracting(r -> r.get("user_id")).containsOnly("");
    assertThat(rows).extracting(r -> r.get("ip_mode")).containsOnly(0);
    assertThat(rows).extracting(r -> r.get("user_id_mode")).containsOnly(0);
    assertThat(rows).extracting(r -> r.get("app_version_id")).containsOnly(0L);
    assertThat((long) rows.get(0).get("error_group_id")).isNotZero();
    assertThat(rows.get(1).get("error_group_id")).isEqualTo(0L);
  }

  @Test
  @DisplayName("C-143 (1) an item whose required enum is UNSPECIFIED is skipped and counted")
  void unknownEnumItem() {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(http().setKind(TransactionKind.TRANSACTION_KIND_UNSPECIFIED))
            .addRecords(http())
            .build();
    assertThat(rows(IngestTestDoubles.record(WalRecordType.TX, s, batch, 3), Table.XLOG))
        .hasSize(1);
    assertThat(registry.get("argos.ingest.errors").tag("stage", "enum").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("C-138 C-141 C-143 (4)(5) profile_step: kind, SQL exec/fetch, DBC, failure, SUMMARY")
  void profileSteps() {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    TransactionProfile profile =
        TransactionProfile.newBuilder()
            .setTxId(55)
            .setStartTsMs(RECEIVED - 2_000)
            .setKind(TransactionKind.MESSAGE)
            .setTruncated(true)
            .setTotalSteps(1_500)
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(1)
                    .setType(StepType.SQL)
                    .setStartOffsetUs(1_500)
                    .setElapsedUs(10_400)
                    .setFetchUs(2_600)
                    .setNameId(3003)
                    .setTargetId(4004)
                    .setRows(12)
                    .setDetail("[String(3)]")
                    .setBatchSize(4)
                    .addFrameIds(5005))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(2)
                    .setParentSeq(1)
                    .setType(StepType.DBC)
                    .setElapsedUs(900)
                    .setNameId(6006) // POOL
                    .setTargetId(4004) // DB_TARGET
                    .setRepeatCount(3))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(3)
                    .setType(StepType.HTTP_CALL)
                    .setFailureValue(42) // unknown FailureKind
                    .setError(true))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(4)
                    .setType(StepType.HTTP_CALL)
                    .setStatus(503)
                    .setFailure(FailureKind.HTTP_5XX))
            .addSteps(
                ProfileStep.newBuilder()
                    .setSeq(5)
                    .setType(StepType.SUMMARY)
                    .setStatus(StepType.SQL_VALUE)
                    .setRepeatCount(900)
                    .setElapsedUs(5_000_000)
                    .setFetchUs(40_000)
                    .setRows(2))
            .addSteps(ProfileStep.newBuilder().setSeq(6).setType(StepType.STEP_TYPE_UNSPECIFIED))
            .build();
    List<Map<String, Object>> rows =
        rows(
            IngestTestDoubles.record(
                WalRecordType.PROFILE,
                s,
                ProfileBatch.newBuilder().addProfiles(profile).build(),
                4),
            Table.PROFILE_STEP);
    assertThat(rows).hasSize(5);
    assertThat(rows).extracting(r -> r.get("kind")).containsOnly(3);
    assertThat(rows).extracting(r -> r.get("ts")).containsOnly(RECEIVED - 2_000);
    assertThat(rows).extracting(r -> r.get("truncated")).containsOnly(1);
    assertThat(rows).extracting(r -> r.get("total_steps")).containsOnly(1_500L);
    Map<String, Object> sql = rows.get(0);
    assertThat(sql.get("type")).isEqualTo(1);
    assertThat(sql.get("start_offset_ms")).isEqualTo(2L);
    assertThat(sql.get("elapsed_ms")).isEqualTo(10L);
    assertThat(sql.get("exec_ms")).isEqualTo(8L); // 7,800 us
    assertThat(sql.get("fetch_ms")).isEqualTo(3L); // 2,600 us
    assertThat(sql.get("rows")).isEqualTo(12L);
    assertThat(sql.get("batch_size")).isEqualTo(4L);
    assertThat(sql.get("detail")).isEqualTo("[String(3)]");
    assertThat(sql.get("detail_enc")).isEqualTo("");
    assertThat(sql.get("stack")).isEqualTo(List.of(5005L));
    assertThat(sql.get("repeat_count")).isEqualTo(1); // 0 -> 1
    Map<String, Object> dbc = rows.get(1);
    assertThat(dbc.get("name_id")).isEqualTo(6006L);
    assertThat(dbc.get("target_id")).isEqualTo(4004L);
    assertThat(dbc.get("parent_seq")).isEqualTo(1);
    assertThat(dbc.get("repeat_count")).isEqualTo(3);
    assertThat(rows.get(2).get("status")).isEqualTo(FailureKind.OTHER_ERROR_VALUE);
    assertThat(rows.get(3).get("status")).isEqualTo(503);
    Map<String, Object> summary = rows.get(4);
    assertThat(summary.get("type")).isEqualTo(11);
    assertThat(summary.get("status")).isEqualTo(StepType.SQL_VALUE);
    assertThat(summary.get("repeat_count")).isEqualTo(900);
    assertThat(summary.get("fetch_ms")).isEqualTo(40L);
    assertThat(summary.get("exec_ms")).isEqualTo(0L);
    assertThat(
            registry.get("argos.collector.unknown.enum").tag("field", "failure").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("C-15 metric rows: series name and kind, 5 s grid seconds, value and delta")
  void metrics() {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    MetricBatch batch =
        MetricBatch.newBuilder()
            .addSeries(
                MetricSeries.newBuilder()
                    .setIndex(0)
                    .setName("jvm_threads")
                    .setKind(MetricKind.GAUGE))
            .addSeries(
                MetricSeries.newBuilder()
                    .setIndex(1)
                    .setName("gc_total")
                    .setKind(MetricKind.COUNTER))
            .addSamples(
                MetricSample.newBuilder().setSeriesIndex(0).setTsMs(RECEIVED - 5_000).setValue(42))
            .addSamples(
                MetricSample.newBuilder()
                    .setSeriesIndex(1)
                    .setTsMs(RECEIVED - 5_000)
                    .setValue(100)
                    .setDelta(3))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(9).setTsMs(RECEIVED))
            .build();
    List<Map<String, Object>> rows =
        rows(IngestTestDoubles.record(WalRecordType.METRIC, s, batch, 5), Table.METRIC);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0))
        .containsEntry("name", "jvm_threads")
        .containsEntry("kind", 1)
        .containsEntry("ts", (RECEIVED - 5_000) / 1000)
        .containsEntry("value", 42.0);
    assertThat(rows.get(1)).containsEntry("kind", 2).containsEntry("delta", 3.0);
  }

  @Test
  @Tag("req:D-15")
  @DisplayName(
      "T-SV-020f metric samples for the counter push: heap (area heap), GC pause increase and"
          + " process CPU of rows with late = 0 only; other series, late rows and the recovery"
          + " path give none")
  void realtimeMetricPoints() {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    String[] names = {
      "jvm_memory_used_bytes{area=\"heap\"}",
      "jvm_memory_used_bytes{area=\"nonheap\"}",
      "jvm_gc_pause_seconds_total{gc=\"G1 Young Generation\"}",
      "process_cpu_usage_ratio",
      "system_cpu_usage_ratio"
    };
    MetricBatch.Builder b = MetricBatch.newBuilder();
    for (int i = 0; i < names.length; i++) {
      b.addSeries(
          MetricSeries.newBuilder()
              .setIndex(i)
              .setName(names[i])
              .setKind(i == 2 ? MetricKind.COUNTER : MetricKind.GAUGE));
    }
    long ts = RECEIVED - 5_000;
    b.addSamples(MetricSample.newBuilder().setSeriesIndex(0).setTsMs(ts).setValue(5e8))
        .addSamples(MetricSample.newBuilder().setSeriesIndex(1).setTsMs(ts).setValue(1e8))
        .addSamples(
            MetricSample.newBuilder().setSeriesIndex(2).setTsMs(ts).setValue(12.5).setDelta(0.04))
        .addSamples(MetricSample.newBuilder().setSeriesIndex(3).setTsMs(ts).setValue(0.37))
        .addSamples(MetricSample.newBuilder().setSeriesIndex(4).setTsMs(ts).setValue(0.9))
        // 90 s before receipt: stored with late = 1, never published.
        .addSamples(
            MetricSample.newBuilder().setSeriesIndex(0).setTsMs(RECEIVED - 90_000).setValue(4e8));
    WalRecord record = IngestTestDoubles.record(WalRecordType.METRIC, s, b.build(), 5);

    RecordDecoder.Decoded d = decoder.decode(record, EnumSet.allOf(Table.class), true);
    assertThat(d.rows().get(Table.METRIC).rowCount()).isEqualTo(6);
    assertThat(d.metrics())
        .containsExactly(
            new MetricPoint(Series.HEAP_USED, ts, 5e8),
            new MetricPoint(Series.GC_PAUSE, ts, 0.04),
            new MetricPoint(Series.CPU, ts, 0.37));
    assertThat(d.points()).isEmpty();

    RecordDecoder.Decoded replay = decoder.decode(record, EnumSet.allOf(Table.class), false);
    assertThat(replay.rows().get(Table.METRIC).rowBinary())
        .isEqualTo(d.rows().get(Table.METRIC).rowBinary());
    assertThat(replay.metrics()).isEmpty();
  }

  @Test
  @DisplayName("C-143 (2) sql_stat_raw exec + fetch = sum; ext_stat_raw; counters -> argos_agent_*")
  void stats() {
    long window = RECEIVED - 20_000;
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    StatBatch batch =
        StatBatch.newBuilder()
            .setWindowStartMs(window)
            .setWindowLenS(10)
            .addSql(sql(1).setSumUs(10_000).setSumExecUs(7_000).setSumFetchUs(3_000))
            .addSql(sql(2).setSumUs(10_000).setSumFetchUs(2_400))
            .addSql(sql(3).setSumUs(10_000).setSumExecUs(9_600))
            .addSql(sql(4).setSumUs(10_000))
            .addSql(sql(5).setSumUs(10_000).setSumFetchUs(50_000))
            .addExt(
                ExtStat.newBuilder()
                    .setHostId(77)
                    .setKind(TransactionKind.HTTP)
                    .setCount(3)
                    .setErrorCount(1)
                    .setTimeoutCount(1)
                    .setConnectFailCount(0)
                    .setSumUs(3_600)
                    .setMaxUs(2_000))
            .addCounters(
                CounterDelta.newBuilder().setName("transactions_ignored_total").setDelta(4))
            .build();
    WalRecord record = IngestTestDoubles.record(WalRecordType.STAT, s, batch, 6);
    List<Map<String, Object>> sqlRows = rows(record, Table.SQL_STAT_RAW);
    assertThat(sqlRows).extracting(r -> r.get("sum_ms")).containsOnly(10L);
    assertThat(sqlRows).extracting(r -> r.get("sum_exec_ms")).containsExactly(7L, 8L, 10L, 10L, 0L);
    assertThat(sqlRows).extracting(r -> r.get("sum_fetch_ms")).containsExactly(3L, 2L, 0L, 0L, 10L);
    assertThat(sqlRows).extracting(r -> r.get("ts")).containsOnly(window / 1000);
    assertThat((List<?>) sqlRows.get(0).get("buckets")).hasSize(15);
    List<Map<String, Object>> extRows = rows(record, Table.EXT_STAT_RAW);
    assertThat(extRows).hasSize(1);
    assertThat(extRows.get(0))
        .containsEntry("host_id", 77L)
        .containsEntry("sum_ms", 4L)
        .containsEntry("max_ms", 2L)
        .containsEntry("timeout_count", 1L);
    List<Map<String, Object>> counters = rows(record, Table.METRIC);
    assertThat(counters).hasSize(1);
    assertThat(counters.get(0))
        .containsEntry("name", "argos_agent_transactions_ignored_total")
        .containsEntry("kind", 2)
        .containsEntry("delta", 4.0)
        .containsEntry("ts", window / 1000);
  }

  private static SqlStat.Builder sql(long id) {
    return SqlStat.newBuilder()
        .setSqlId(id)
        .setDbId(9)
        .setKind(TransactionKind.HTTP)
        .setCount(2)
        .addAllBuckets(List.of(1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  @DisplayName(
      "C-129 one correction for xlog ts, profile ts, window_start and metric ts; tx_id kept")
  void correctionAppliesToEveryTable() {
    long correction = 60_000;
    SessionContext s =
        IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, correction, "");
    long agentTs = RECEIVED + 59_000;
    assertThat(
            rows(
                    IngestTestDoubles.record(
                        WalRecordType.TX,
                        s,
                        TransactionBatch.newBuilder()
                            .addRecords(http().setStartTsMs(agentTs))
                            .build(),
                        7),
                    Table.XLOG)
                .get(0))
        .containsEntry("ts", agentTs - correction)
        .containsEntry("tx_id", 123456789L);
    assertThat(
            rows(
                    IngestTestDoubles.record(
                        WalRecordType.PROFILE,
                        s,
                        ProfileBatch.newBuilder()
                            .addProfiles(
                                TransactionProfile.newBuilder()
                                    .setTxId(1)
                                    .setKind(TransactionKind.HTTP)
                                    .setStartTsMs(agentTs)
                                    .addSteps(
                                        ProfileStep.newBuilder()
                                            .setSeq(1)
                                            .setType(StepType.METHOD)))
                            .build(),
                        8),
                    Table.PROFILE_STEP)
                .get(0))
        .containsEntry("ts", agentTs - correction);
    long window = RECEIVED + 50_000;
    assertThat(
            rows(
                    IngestTestDoubles.record(
                        WalRecordType.STAT,
                        s,
                        StatBatch.newBuilder()
                            .setWindowStartMs(window)
                            .addSql(sql(1).setSumUs(1))
                            .build(),
                        9),
                    Table.SQL_STAT_RAW)
                .get(0))
        .containsEntry("ts", (window - correction) / 1000);
    assertThat(
            rows(
                    IngestTestDoubles.record(
                        WalRecordType.METRIC,
                        s,
                        MetricBatch.newBuilder()
                            .addSeries(
                                MetricSeries.newBuilder()
                                    .setIndex(0)
                                    .setName("m")
                                    .setKind(MetricKind.GAUGE))
                            .addSamples(
                                MetricSample.newBuilder()
                                    .setSeriesIndex(0)
                                    .setTsMs(RECEIVED + 55_000))
                            .build(),
                        10),
                    Table.METRIC)
                .get(0))
        .containsEntry("ts", (RECEIVED + 55_000 - correction) / 1000);
    assertThat(registry.get("argos.ts.clamped").tag("kind", "session_offset").counter().count())
        .isEqualTo(4.0);
    assertThat(registry.get("argos.ts.clamped").tag("kind", "record_clamp").counter().count())
        .isZero();
  }

  @Test
  @DisplayName(
      "T-SV-015a +10 s clamped and counted, -8 d rejected per record, -90 s late = 1 and not a point")
  void guardInXlog() {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(http().setTxId(1).setStartTsMs(RECEIVED + 10_000))
            .addRecords(http().setTxId(2).setStartTsMs(RECEIVED - 8L * 86_400_000))
            .addRecords(http().setTxId(3).setStartTsMs(RECEIVED - 90_000))
            .build();
    WalRecord record = IngestTestDoubles.record(WalRecordType.TX, s, batch, 11);
    RecordDecoder.Decoded d = decoder.decode(record, EnumSet.of(Table.XLOG), true);
    List<Map<String, Object>> rows =
        RowBinaryReader.rows(Table.XLOG, d.rows().get(Table.XLOG).rowBinary().toByteArray());
    assertThat(rows).extracting(r -> r.get("tx_id")).containsExactly(1L, 3L);
    assertThat(rows.get(0)).containsEntry("ts", RECEIVED).containsEntry("late", 0);
    assertThat(rows.get(1)).containsEntry("ts", RECEIVED - 90_000).containsEntry("late", 1);
    assertThat(d.points()).extracting(p -> p.txId()).containsExactly(1L);
    assertThat(registry.get("argos.ts.clamped").tag("kind", "record_clamp").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("argos.ts.rejected").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("argos.late.arrivals").counter().count()).isEqualTo(1.0);
  }

  private double decodeErrors() {
    return registry.get("argos.ingest.errors").tag("stage", "decode").counter().count();
  }

  private static List<Map<String, Object>> parsed(RecordDecoder.Decoded d, Table table) {
    var rows = d.rows().get(table);
    List<Map<String, Object>> parsed = RowBinaryReader.rows(table, rows.rowBinary().toByteArray());
    assertThat(parsed).as("rows of %s parse back to the row count", table).hasSize(rows.rowCount());
    return parsed;
  }

  @Test
  @DisplayName(
      "12 §3.9 an item whose conversion throws is skipped alone (its partial row cut), the rest of"
          + " the batch is kept and argos_ingest_errors_total{stage=decode} counts each skipped item")
  void failingItemSkippedAlone() {
    long poison = 0x0BADF00DL;
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    DictionaryResolver poisoned =
        new DictionaryResolver(
            store,
            IngestTestDoubles.provider(new IngestTestDoubles.RecordingMessenger()),
            metrics,
            10_000,
            1_000_000,
            Clock.systemUTC()) {
          @Override
          public long resolve(int appId, int instanceId, DictionaryKind kind, long id) {
            if (id == poison) {
              throw new IllegalStateException("conversion failure of the test");
            }
            return super.resolve(appId, instanceId, kind, id);
          }
        };
    decoder = new RecordDecoder(poisoned, new ErrorGroupRecorder(store, metrics), metrics);
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, "");

    // xlog: the thread name is resolved near the end of the row, after most of its bytes.
    RecordDecoder.Decoded tx =
        decoder.decode(
            IngestTestDoubles.record(
                WalRecordType.TX,
                s,
                TransactionBatch.newBuilder()
                    .addRecords(http().setTxId(1))
                    .addRecords(http().setTxId(2).setThreadNameId(poison))
                    .addRecords(http().setTxId(3))
                    .build(),
                13),
            EnumSet.allOf(Table.class),
            true);
    assertThat(parsed(tx, Table.XLOG)).extracting(r -> r.get("tx_id")).containsExactly(1L, 3L);
    assertThat(tx.points()).extracting(XlogPoint::txId).containsExactly(1L, 3L);
    assertThat(decodeErrors()).isEqualTo(1.0);

    // profile_step: the stack of the second step is resolved last; steps 1 and 3 stay.
    RecordDecoder.Decoded profile =
        decoder.decode(
            IngestTestDoubles.record(
                WalRecordType.PROFILE,
                s,
                ProfileBatch.newBuilder()
                    .addProfiles(
                        TransactionProfile.newBuilder()
                            .setTxId(77)
                            .setKind(TransactionKind.HTTP)
                            .setStartTsMs(RECEIVED - 1_000)
                            .setTotalSteps(3)
                            .addSteps(ProfileStep.newBuilder().setSeq(1).setType(StepType.METHOD))
                            .addSteps(
                                ProfileStep.newBuilder()
                                    .setSeq(2)
                                    .setType(StepType.METHOD)
                                    .addFrameIds(poison))
                            .addSteps(ProfileStep.newBuilder().setSeq(3).setType(StepType.METHOD)))
                    .build(),
                14),
            EnumSet.allOf(Table.class),
            true);
    assertThat(parsed(profile, Table.PROFILE_STEP))
        .extracting(r -> r.get("seq"))
        .containsExactly(1, 3);
    assertThat(decodeErrors()).isEqualTo(2.0);

    // Statistics: a failing SQL statistic in the middle, a failing outgoing one first.
    RecordDecoder.Decoded stats =
        decoder.decode(
            IngestTestDoubles.record(
                WalRecordType.STAT,
                s,
                StatBatch.newBuilder()
                    .setWindowStartMs(RECEIVED - 20_000)
                    .addSql(sql(1).setSumUs(1_000))
                    .addSql(sql(poison).setSumUs(1_000))
                    .addSql(sql(3).setSumUs(1_000))
                    .addExt(ExtStat.newBuilder().setHostId(poison).setKind(TransactionKind.HTTP))
                    .addExt(ExtStat.newBuilder().setHostId(77).setKind(TransactionKind.HTTP))
                    .build(),
                15),
            EnumSet.allOf(Table.class),
            true);
    assertThat(parsed(stats, Table.SQL_STAT_RAW))
        .extracting(r -> r.get("sql_id"))
        .containsExactly(1L, 3L);
    assertThat(parsed(stats, Table.EXT_STAT_RAW))
        .extracting(r -> r.get("host_id"))
        .containsExactly(77L);
    assertThat(decodeErrors()).isEqualTo(4.0);
  }

  @Test
  @DisplayName("D-13 decoding the same record twice gives the same bytes (live path = replay)")
  void deterministic() {
    SessionContext s =
        IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 10_000, "2.0");
    WalRecord record =
        IngestTestDoubles.record(
            WalRecordType.TX,
            s,
            TransactionBatch.newBuilder()
                .addRecords(http().setError(true).setErrorMessage("id 42 failed"))
                .build(),
            12);
    var first = decoder.decode(record, EnumSet.allOf(Table.class), true).rows().get(Table.XLOG);
    var replay = decoder.decode(record, EnumSet.of(Table.XLOG), false).rows().get(Table.XLOG);
    assertThat(replay.rowBinary()).isEqualTo(first.rowBinary());
  }
}
